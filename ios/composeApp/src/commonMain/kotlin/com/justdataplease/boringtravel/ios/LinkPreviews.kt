package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.justdataplease.boringtravel.places.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * Link previews and place photos, as the Android app looks them up and keeps them (links/LinkPreview.kt,
 * LinkPreviews.kt, LinkFetcher.kt): only for what is on screen, three at a time, once per link or place, kept in memory
 * and in a small file (400 at most), looked at again after 30 days, 7 days, a day or 15 minutes. Offline nothing breaks:
 * what was known stays. Written again here on [Net] instead of OkHttp; the file is the same JSON.
 */

/**
 * What a plan item's link looks like: a picture (the page's og:image or, for a Google Maps place, Wikipedia), the page's
 * title and site name, and Google's favicon for the site. A Wikipedia picture keeps its credit with it. A located item
 * without a link has one too, kept under its [PlaceQuery.key].
 */
@Serializable
data class LinkPreview(
    val url: String,
    val imageUrl: String? = null,
    val imageSource: String? = null,
    val title: String? = null,
    val siteName: String? = null,
    val faviconUrl: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val fetchedAt: Long = 0,
    val retryAt: Long = 0,
    val failed: Boolean = false,
    val photosOff: Boolean = false,
    val imageAuthor: String? = null,
    val imageLicense: String? = null,
    val imageLicenseUrl: String? = null,
    val imageFile: String? = null,
) {
    val fromWikipedia: Boolean get() = imageSource == SOURCE_WIKIPEDIA

    /** A Wikipedia picture without its licence: never shown, looked at again. */
    val uncredited: Boolean get() = fromWikipedia && imageUrl != null && imageLicense == null

    /** "Photo: Jane Doe, CC BY-SA 4.0 · Wikipedia" for a Wikipedia picture; null for anything else. */
    val credit: String? get() = imageLicense?.takeIf { fromWikipedia && imageUrl != null }?.let { WikiCredits.line(imageAuthor, it) }

    /** The Wikipedia picture's file page, opened from its credit. */
    val creditUrl: String? get() = imageFile?.takeIf { fromWikipedia }?.let(WikiCredits::fileUrl)

    fun withWikiPhoto(photo: WikiPhoto?): LinkPreview = copy(
        imageUrl = photo?.imageUrl, imageSource = photo?.let { SOURCE_WIKIPEDIA },
        imageAuthor = photo?.credit?.author, imageLicense = photo?.credit?.license,
        imageLicenseUrl = photo?.credit?.licenseUrl, imageFile = photo?.credit?.file,
    )

    fun withoutPicture(): LinkPreview =
        copy(imageUrl = null, imageSource = null, imageAuthor = null, imageLicense = null, imageLicenseUrl = null, imageFile = null)

    companion object {
        const val SOURCE_PAGE = "page"
        const val SOURCE_WIKIPEDIA = "wikipedia"
    }
}

/** How a look at a link went. */
enum class LinkOutcome { Picture, NoPicture, Refused, Offline }

/** When to look at a link again, and what to keep when a look fails. */
object LinkPolicy {
    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE

    fun retryAt(outcome: LinkOutcome, now: Long): Long = now + when (outcome) {
        LinkOutcome.Picture -> 30 * DAY
        LinkOutcome.NoPicture -> 7 * DAY
        LinkOutcome.Refused -> DAY
        LinkOutcome.Offline -> 15 * MINUTE
    }

    fun fresh(p: LinkPreview?, now: Long): Boolean = p != null && now < p.retryAt && !p.uncredited

    fun fresh(p: LinkPreview?, now: Long, photosOn: Boolean): Boolean = fresh(p, now) && !(photosOn && p!!.photosOff)

    /** What to show of [p]: without place photos, no Wikipedia picture; never a Wikipedia picture without its credit. */
    fun shown(p: LinkPreview, photosOn: Boolean): LinkPreview =
        if (!p.fromWikipedia || (photosOn && !p.uncredited)) p else p.withoutPicture()

    /** The preview to keep after a look: [new], unless it failed and [old] knew more. */
    fun merge(old: LinkPreview?, new: LinkPreview): LinkPreview {
        if (!new.failed || old == null) return new
        val picture = if (old.imageUrl != null) old else new
        return old.copy(
            imageUrl = picture.imageUrl,
            imageSource = picture.imageSource,
            imageAuthor = picture.imageAuthor,
            imageLicense = picture.imageLicense,
            imageLicenseUrl = picture.imageLicenseUrl,
            imageFile = picture.imageFile,
            title = old.title ?: new.title,
            siteName = old.siteName ?: new.siteName,
            faviconUrl = old.faviconUrl ?: new.faviconUrl,
            lat = old.lat ?: new.lat,
            lng = old.lng ?: new.lng,
            retryAt = new.retryAt,
            failed = true,
            photosOff = new.photosOff,
        )
    }
}

/** The previews file: a JSON list, newest looks kept when it grows past [MAX]. */
object LinkPreviewCodec {
    const val MAX = 400
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val list = ListSerializer(LinkPreview.serializer())

    fun encode(previews: Collection<LinkPreview>): String = json.encodeToString(list, trim(previews))

    fun decode(text: String): Map<String, LinkPreview> =
        runCatching { json.decodeFromString(list, text) }.getOrDefault(emptyList()).associateBy { it.url }

    fun trim(previews: Collection<LinkPreview>): List<LinkPreview> =
        if (previews.size <= MAX) previews.toList() else previews.sortedByDescending { it.fetchedAt }.take(MAX)
}

/** A site answered, but not with the page (403, 404, 500 …): an IOException, as the Android app's. */
class LinkHttpException(val code: Int) : com.justdataplease.boringtravel.io.IOException("The site answered $code")

/**
 * The network side of link previews, kept thin: each call is one request (or a short chain of redirects) and hands
 * strings to the parsers. Every request names the app ([appUserAgent]); a page that refuses it with 403 is asked once
 * more in the crawler form link-preview bots use ([crawlerUserAgent]), which still names the app.
 */
class LinkFetcher(private val net: Net, private val appUserAgent: String, private val crawlerUserAgent: String = appUserAgent, private val language: String = "en") {
    /** The page's preview tags, read from at most [MAX_PAGE_BYTES] of it. A link straight to a picture is the picture. */
    suspend fun page(url: String): PageMeta {
        val target = LinkUrls.forFetch(url) ?: throw NetException("Not a web link")
        return try {
            page(target, appUserAgent)
        } catch (e: LinkHttpException) {
            if (e.code != 403 || crawlerUserAgent == appUserAgent) throw e
            page(target, crawlerUserAgent)
        }
    }

    private suspend fun page(target: String, userAgent: String): PageMeta {
        val r = net.ask(
            NetRequest(
                target,
                mapOf("User-Agent" to userAgent, "Accept" to "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8", "Accept-Language" to acceptLanguage()),
                maxBytes = MAX_PAGE_BYTES, timeoutMs = 8_000, readTimeoutMs = 8_000,
            ),
        )
        if (!r.ok) throw LinkHttpException(r.code)
        val type = r.header("Content-Type")
        val media = type?.substringBefore(';')?.trim()?.lowercase()
        if (media != null && media.startsWith("image/")) return PageMeta(imageUrl = r.url)
        if (media != null && "html" !in media && "xml" !in media) return PageMeta()
        val head = latin1(r.body, 4096)
        val charset = MetaTags.charset(type, head)?.lowercase()
        return MetaTags.parse(decodeText(r.body, charset), r.url)
    }

    /** True when [url] answers 200 with a picture; Google's favicon service answers 404 for sites it doesn't know. */
    suspend fun isImage(url: String): Boolean {
        val r = net.ask(NetRequest(url, mapOf("User-Agent" to appUserAgent), maxBytes = 64 * 1024))
        return r.ok && r.header("Content-Type").orEmpty().startsWith("image/")
    }

    /**
     * The long Maps link behind a short one, by following its redirects one at a time and stopping at the first long
     * Maps link, so Google's consent page and the Maps page itself are never loaded. Asked with the app's own User-Agent.
     */
    suspend fun expandMaps(url: String): String? {
        var current = url
        repeat(MAX_HOPS) {
            current = MapsLinks.unwrapConsent(current)
            if (MapsLinks.isMaps(current) && !MapsLinks.isShort(current)) return current
            val target = LinkUrls.forFetch(current) ?: return null
            val r = net.ask(NetRequest(target, mapOf("User-Agent" to appUserAgent), followRedirects = false, maxBytes = MAX_REDIRECT_PAGE_BYTES))
            if (r.ok) return MapsLinks.findInHtml(r.text())
            if (!r.redirect) throw LinkHttpException(r.code)
            val location = r.header("Location") ?: return null
            current = MapsLinks.fromIntent(location) ?: WebUrl.of(target)?.resolve(location)?.toString() ?: return null
        }
        return null
    }

    /**
     * The list of saved places behind a Google Maps link: a short link followed first, then the list's places asked
     * for as its own page asks. Null when the link isn't a list's; a list that gives nothing throws [MapsListException].
     */
    suspend fun mapsList(url: String): MapsList? {
        val long = (if (MapsLinks.isShort(url)) expandMaps(url) else MapsLinks.unwrapConsent(url)) ?: return null
        val ref = MapsLists.ref(long) ?: return null
        for (ask in MapsLists.requestUrls(ref)) {
            val r = net.ask(NetRequest(ask, mapOf("User-Agent" to appUserAgent), maxBytes = MapsLists.MAX_BYTES, timeoutMs = LIST_MS, readTimeoutMs = LIST_MS))
            if (!r.ok) continue
            MapsLists.parse(r.text())?.let { return it }
        }
        throw MapsListException()
    }

    /** One answer of Wikipedia's query API; Wikimedia asks for a named client. */
    suspend fun wikipedia(url: String): String {
        val r = net.ask(NetRequest(url, mapOf("User-Agent" to appUserAgent), maxBytes = MAX_PAGE_BYTES))
        if (!r.ok) throw LinkHttpException(r.code)
        return r.text()
    }

    private fun acceptLanguage(): String = if (language.startsWith("en")) "$language,en;q=0.9" else "$language,en;q=0.8"

    companion object {
        /** The app's page, in every User-Agent. */
        const val APP_PAGE = "https://justdataplease.com/apps/boringtravel/"

        /** "BoringTravel/1.0 (iPhone; +https://justdataplease.com/apps/boringtravel/)" (Android: "(Android; +…)"). */
        fun userAgent(version: String): String = "BoringTravel/$version (iPhone; +$APP_PAGE)"

        /** After a 403 only: the form link-preview bots use. It names the app and claims no browser or device. */
        fun crawlerUserAgent(version: String): String = "Mozilla/5.0 (compatible; BoringTravel/$version; +$APP_PAGE)"

        const val MAX_PAGE_BYTES = 512L * 1024
        private const val MAX_REDIRECT_PAGE_BYTES = 256L * 1024
        private const val MAX_HOPS = 6
        private const val LIST_MS = 30_000L

        private fun latin1(bytes: ByteArray, max: Int): String {
            val n = minOf(bytes.size, max)
            val chars = CharArray(n) { (bytes[it].toInt() and 0xff).toChar() }
            return chars.concatToString()
        }

        /** [bytes] as text in [charset]: UTF-8 unless a page says Latin-1 or Windows-1252 (the only others read). */
        fun decodeText(bytes: ByteArray, charset: String?): String = when (charset) {
            "iso-8859-1", "latin1", "windows-1252", "cp1252", "us-ascii" -> latin1(bytes, bytes.size)
            else -> bytes.decodeToString()
        }
    }
}

/**
 * Previews for plan items' links, and photos for located items without one, looked up lazily: only for a row or sheet
 * on screen, a few at a time, once per link or place (kept in memory and in a small file in the caches, looked at again
 * per [LinkPolicy]). Only a link's own site, Google's favicon service, the place search (a Maps link without a pin)
 * and Wikipedia are asked; Wikipedia only while Me › Place photos is on.
 */
class LinkPreviews(private val web: Web) {
    // Replaced whole under [lock], read without it (the screens peek from the main thread).
    private var cache: Map<String, LinkPreview> = emptyMap()
    private var icons: Map<String, Boolean> = emptyMap()
    private val inFlight = HashMap<String, Deferred<LinkPreview>>()
    private val lock = Mutex()
    private var loaded = false
    private var saveJob: Job? = null
    private val gate = Semaphore(MAX_AT_ONCE)
    private val scope = web.scope
    private val clock = web.clock

    /** Changes when a look kept something new: screens showing [peek] read it again. */
    var revision by mutableIntStateOf(0)
        private set

    val fetcher = LinkFetcher(web.net, LinkFetcher.userAgent(web.version), LinkFetcher.crawlerUserAgent(web.version), web.language)

    private val wiki = WikiLookup(ask = { fetcher.wikipedia(it) }, enabled = { web.placePhotos() })

    private fun photosOn(): Boolean = web.placePhotos()

    /** What is known about [url] right now, from memory only; may be stale or null. */
    fun peek(url: String): LinkPreview? = cache[url]?.let { LinkPolicy.shown(it, photosOn()) }

    /** The preview for [url]: the kept one while it is fresh, else a new look (shared with anyone already waiting). */
    suspend fun preview(url: String, near: LatLng? = null): LinkPreview? {
        load()
        val on = photosOn()
        val kept = cache[url]
        if (LinkPolicy.fresh(kept, clock(), on)) return kept?.let { LinkPolicy.shown(it, on) }
        val found = try {
            lookUp(url) { now -> resolve(url, near, on, now) }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            kept
        }
        return found?.let { LinkPolicy.shown(it, on) }
    }

    /** A new look at [url] now (the item sheet's Renew details). A look that fails keeps what was known. */
    suspend fun renew(url: String, near: LatLng? = null): LinkPreview? {
        load()
        val on = photosOn()
        val kept = cache[url]
        val found = try {
            lookUp(url) { now -> resolve(url, near, on, now) }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            kept?.copy(failed = true)
        }
        return found?.let { LinkPolicy.shown(it, on) }
    }

    /** The photo kept for [query], from memory only; null while place photos are off. */
    fun peekPlace(query: PlaceQuery): LinkPreview? = if (photosOn()) cache[query.key]?.let { LinkPolicy.shown(it, true) } else null

    /** Wikipedia's photo of a located item's place: the kept one while fresh, else a new look. Null while photos are off. */
    suspend fun placePhoto(query: PlaceQuery): LinkPreview? {
        if (!photosOn()) return null
        load()
        val key = query.key
        val kept = cache[key]
        if (LinkPolicy.fresh(kept, clock())) return kept?.let { LinkPolicy.shown(it, true) }
        val found = try {
            lookUp(key) { now -> resolvePlace(query, now) }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            kept
        }
        return found?.let { LinkPolicy.shown(it, true) }
    }

    /** The place a Google Maps link points at: from the link when it says, else after following it (short links). */
    suspend fun mapsPlace(url: String, near: LatLng? = null): MapsPlace? {
        if (!MapsLinks.isMaps(url)) return null
        MapsLinks.parse(url)?.takeIf { it.hasPoint }?.let { return it }
        val p = preview(url, near) ?: return null
        return if (p.lat != null && p.lng != null) MapsPlace(p.title, p.lat, p.lng) else null
    }

    /** The list of saved places a Google Maps link shares; null when the link is something else. */
    suspend fun mapsList(url: String): MapsList? = if (MapsLinks.isMaps(url)) fetcher.mapsList(url) else null

    private suspend fun lookUp(key: String, resolve: suspend (now: Long) -> LinkPreview): Deferred<LinkPreview> = lock.withLock {
        inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) {
            try {
                val result = gate.withPermit { resolve(clock()) }
                lock.withLock {
                    val kept = LinkPolicy.merge(cache[key], result)
                    cache = cache + (key to kept)
                    kept
                }.also {
                    revision++
                    scheduleSave()
                }
            } finally {
                lock.withLock { inFlight.remove(key) }
            }
        }.also {
            inFlight[key] = it
            it.start()
        }
    }

    private suspend fun resolve(url: String, near: LatLng?, photos: Boolean, now: Long): LinkPreview =
        if (MapsLinks.isMaps(url)) resolveMaps(url, near, photos, now) else resolvePage(url, now)

    private suspend fun resolvePlace(query: PlaceQuery, now: Long): LinkPreview {
        var photo: WikiPhoto? = null
        val outcome = outcomeOf {
            photo = wiki.photo(query.names, query.lat, query.lng)
            if (photo != null) LinkOutcome.Picture else LinkOutcome.NoPicture
        }
        val p = photo
        return LinkPreview(
            url = query.key, title = p?.title, siteName = PlacePhotos.SITE_NAME, lat = query.lat, lng = query.lng,
            fetchedAt = now, retryAt = LinkPolicy.retryAt(outcome, now), failed = outcome.failed,
        ).withWikiPhoto(p)
    }

    private suspend fun resolvePage(url: String, now: Long): LinkPreview = coroutineScope {
        val icon = async { LinkUrls.host(url)?.let { favicon(it) } }
        var meta: PageMeta? = null
        val outcome = outcomeOf {
            val m = fetcher.page(url)
            meta = m
            if (m.imageUrl != null) LinkOutcome.Picture else LinkOutcome.NoPicture
        }
        val m = meta
        LinkPreview(
            url = url, imageUrl = m?.imageUrl, imageSource = m?.imageUrl?.let { LinkPreview.SOURCE_PAGE },
            title = m?.title, siteName = m?.siteName, faviconUrl = icon.await(),
            fetchedAt = now, retryAt = LinkPolicy.retryAt(outcome, now), failed = outcome.failed,
        )
    }

    /**
     * A Google Maps link: never its page. The name and pin come from the link (a short one followed first), the pin from
     * the place search when the link names a place without one, the picture from Wikipedia's article there.
     */
    private suspend fun resolveMaps(url: String, near: LatLng?, photos: Boolean, now: Long): LinkPreview = coroutineScope {
        val icon = async { favicon(MapsLinks.FAVICON_HOST) }
        var place: MapsPlace? = null
        var photo: WikiPhoto? = null
        val outcome = outcomeOf {
            val long = if (MapsLinks.isShort(url)) fetcher.expandMaps(url) else url
            val parsed = long?.let(MapsLinks::parse)
            val name = parsed?.name
            val found = if (parsed != null && !parsed.hasPoint && name != null) {
                LinkPlaces.choose(LooseQueries.search(name, listOfNotNull(near)) { web.places.search(it, near) }, near)
            } else null
            val p = if (found != null) parsed?.copy(lat = found.lat, lng = found.lng) else parsed
            place = p
            val lat = p?.lat
            val lng = p?.lng
            val picked = if (photos && lat != null && lng != null) wiki.photo(listOfNotNull(name), lat, lng) else null
            photo = picked
            if (picked != null) LinkOutcome.Picture else LinkOutcome.NoPicture
        }
        LinkPreview(
            url = url, title = place?.name, siteName = MapsLinks.SITE_NAME, faviconUrl = icon.await(),
            lat = place?.lat, lng = place?.lng,
            fetchedAt = now, retryAt = LinkPolicy.retryAt(outcome, now), failed = outcome.failed, photosOff = !photos,
        ).withWikiPhoto(photo)
    }

    /** Runs [look]; a refusal or a lost connection becomes its outcome instead of an exception. */
    private suspend fun outcomeOf(look: suspend () -> LinkOutcome): LinkOutcome = try {
        look()
    } catch (e: CancellationException) {
        throw e
    } catch (_: LinkHttpException) {
        LinkOutcome.Refused
    } catch (_: NetException) {
        LinkOutcome.Offline
    } catch (_: MapsListException) {
        LinkOutcome.Offline
    } catch (_: Exception) {
        LinkOutcome.NoPicture
    }

    private val LinkOutcome.failed: Boolean get() = this == LinkOutcome.Refused || this == LinkOutcome.Offline

    /** Google's favicon for [host] when Google knows one (checked once per host per run), else null. */
    private suspend fun favicon(host: String): String? {
        val url = LinkUrls.favicon(host)
        icons[host]?.let { return if (it) url else null }
        val known = try {
            fetcher.isImage(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        lock.withLock { icons = icons + (host to known) }
        return if (known) url else null
    }

    /** Reads the kept previews once. */
    private suspend fun load() {
        if (loaded) return
        val saved = runCatching { web.cache.read(FILE)?.decodeToString()?.let(LinkPreviewCodec::decode) }.getOrNull()
        lock.withLock {
            if (loaded) return
            if (saved != null) cache = saved + cache
            loaded = true
        }
    }

    /** Writes the previews a moment after the last change (one write for a screen full of links). */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            val text = lock.withLock { LinkPreviewCodec.encode(cache.values) }
            runCatching { web.cache.write(FILE, text.encodeToByteArray()) }
        }
    }

    private companion object {
        const val MAX_AT_ONCE = 3
        const val SAVE_DELAY_MS = 1_500L
        const val FILE = "link_previews.json"
    }
}
