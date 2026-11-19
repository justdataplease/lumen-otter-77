package com.justdataplease.boringtravel.links

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.justdataplease.boringtravel.BuildConfig
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.LooseQueries
import com.justdataplease.boringtravel.places.PlaceSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Previews for plan items' links, and photos for located items without one, looked up lazily: only for a row or
 * sheet on screen, never on the main thread, a few at a time, once per link or place (kept in memory and in a small
 * file, looked at again per [LinkPolicy]). Offline nothing breaks: what was known stays, or nothing shows. Pictures
 * load through [imageLoader] (memory and disk cache).
 *
 * Only a link's own site, Google's favicon service, the place search (a Google Maps link without a pin) and Wikipedia
 * are contacted. Wikipedia only while [placePhotos] (Me › Place photos) is on: for a Google Maps link's photo and for
 * located items without a link ([placePhoto]).
 */
class LinkPreviews(
    context: Context,
    private val scope: CoroutineScope,
    private val places: () -> PlaceSearch,
    private val placePhotos: Flow<Boolean> = flowOf(true),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val app = context.applicationContext
    /** Touched only off the main thread (the first look at the folder may create it). */
    private val file by lazy { File(app.noBackupFilesDir, "link_previews.json") }
    private val cache = ConcurrentHashMap<String, LinkPreview>()
    private val icons = ConcurrentHashMap<String, Boolean>()
    private val inFlight = HashMap<String, Deferred<LinkPreview>>()
    private val loadLock = Mutex()
    private val saveLock = Mutex()
    @Volatile private var loaded = false
    private var saveJob: Job? = null
    private val gate = Semaphore(MAX_AT_ONCE)

    /**
     * The app's own User-Agent for every request: its name, version and page (Wikimedia asks for a name and a way to
     * reach the maker, and refuses generic clients). No request claims to be a browser.
     */
    private val appUserAgent = LinkFetcher.userAgent(BuildConfig.VERSION_NAME)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val fetcher by lazy { LinkFetcher(client, appUserAgent, LinkFetcher.crawlerUserAgent(BuildConfig.VERSION_NAME)) }

    private val wiki = WikiLookup(ask = { fetcher.wikipedia(it) }, enabled = { photosOn() })

    /**
     * Me › Place photos for drawing: off until the setting is read, so nothing shows a Wikipedia picture before it is
     * known. Lookups read the setting itself ([photosOn]).
     */
    val placePhotosOn: StateFlow<Boolean> = placePhotos.stateIn(scope, SharingStarted.Eagerly, false)

    private suspend fun photosOn(): Boolean = placePhotos.first()

    /** Pictures and favicons, with the app's own User-Agent. */
    private val imageClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", appUserAgent).build()) }
            .build()
    }

    /**
     * The pictures' loader: its own small memory and disk caches, cross-fading in. Cheap to build on the main thread;
     * Coil creates the HTTP client and the disk cache on its own threads at the first picture.
     */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(app)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageClient })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(app, 0.05).build() }
            .diskCache { DiskCache.Builder().directory(File(app.cacheDir, "link_images").toOkioPath()).maxSizeBytes(40L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
    }

    /** What is known about [url] right now, from memory only (safe on the main thread); may be stale or null. */
    fun peek(url: String): LinkPreview? = cache[url]?.let { LinkPolicy.shown(it, placePhotosOn.value) }

    /**
     * The preview for [url]: the kept one while it is fresh, else a new look (shared with anyone already waiting for
     * the same link). [near] leans the place search towards the trip when a Maps link has a name but no pin.
     */
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

    /**
     * A new look at [url] now, whether the kept one is fresh or not (the item sheet's Renew details). A look that
     * fails keeps what was known ([LinkPreview.failed] says so).
     */
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

    /**
     * The photo kept for [query], from memory only (safe on the main thread); null while place photos are off, and
     * no picture without its credit.
     */
    fun peekPlace(query: PlaceQuery): LinkPreview? = if (placePhotosOn.value) cache[query.key]?.let { LinkPolicy.shown(it, true) } else null

    /**
     * Wikipedia's photo of a located item's place (see [PlacePhotos]): the kept one while it is fresh, else a new
     * look. Null, and nothing asked, while place photos are off.
     */
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

    /**
     * The list of saved places a Google Maps link shares ([MapsLists]); null when the link is something else (a place,
     * a search, not a Maps link). Throws when the list can't be read ([MapsListException], no connection).
     */
    suspend fun mapsList(url: String): MapsList? = if (MapsLinks.isMaps(url)) fetcher.mapsList(url) else null

    /** One look at [key] (a link, or a place's key), a few at a time, shared with anyone already waiting for it. */
    private fun lookUp(key: String, resolve: suspend (now: Long) -> LinkPreview): Deferred<LinkPreview> = synchronized(inFlight) {
        inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) {
            try {
                val result = gate.withPermit { resolve(clock()) }
                val kept = LinkPolicy.merge(cache[key], result)
                cache[key] = kept
                scheduleSave()
                kept
            } finally {
                synchronized(inFlight) { inFlight.remove(key) }
            }
        }.also {
            inFlight[key] = it
            it.start()
        }
    }

    private suspend fun resolve(url: String, near: LatLng?, photos: Boolean, now: Long): LinkPreview =
        if (MapsLinks.isMaps(url)) resolveMaps(url, near, photos, now) else resolvePage(url, now)

    /** A located item's place: Wikipedia's article named like it nearby, else the nearest with a photo. */
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

    /** A website: its og:image, title and site name, and Google's favicon for its host. */
    private suspend fun resolvePage(url: String, now: Long): LinkPreview = coroutineScope {
        val icon = async { LinkUrls.host(url)?.let { favicon(it) } }
        var meta: PageMeta? = null
        val outcome = outcomeOf {
            meta = fetcher.page(url)
            if (meta.imageUrl != null) LinkOutcome.Picture else LinkOutcome.NoPicture
        }
        val m = meta
        LinkPreview(
            url = url, imageUrl = m?.imageUrl, imageSource = m?.imageUrl?.let { LinkPreview.SOURCE_PAGE },
            title = m?.title, siteName = m?.siteName, faviconUrl = icon.await(),
            fetchedAt = now, retryAt = LinkPolicy.retryAt(outcome, now), failed = outcome.failed,
        )
    }

    /**
     * A Google Maps link: never its page (it only offers the Maps logo). The name and pin come from the link (a short
     * one followed first), the pin from the place search when the link names a place without one, the picture from
     * Wikipedia's article there ([photos]: only with Me › Place photos on).
     */
    private suspend fun resolveMaps(url: String, near: LatLng?, photos: Boolean, now: Long): LinkPreview = coroutineScope {
        val icon = async { favicon(MapsLinks.FAVICON_HOST) }
        var place: MapsPlace? = null
        var photo: WikiPhoto? = null
        val outcome = outcomeOf {
            val long = if (MapsLinks.isShort(url)) fetcher.expandMaps(url) else url
            val parsed = long?.let(MapsLinks::parse)
            val name = parsed?.name
            // A name that is a full address ("…, Bab-ı Ali Cd. No:27, 34120 Fatih/İstanbul") is searched looser too.
            val found = if (parsed != null && !parsed.hasPoint && name != null) {
                LinkPlaces.choose(LooseQueries.search(name, listOfNotNull(near)) { places().search(it, near) }, near)
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
    } catch (_: IOException) {
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
        icons[host] = known
        return if (known) url else null
    }

    /** Reads the kept previews once, off the main thread. */
    private suspend fun load() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            val saved = withContext(Dispatchers.IO) { runCatching { if (file.exists()) LinkPreviewCodec.decode(file.readText()) else null }.getOrNull() }
            saved?.forEach { (k, v) -> cache.putIfAbsent(k, v) }
            loaded = true
        }
    }

    /** Writes the previews a moment after the last change (one write for a screen full of links). */
    private fun scheduleSave() {
        synchronized(this) {
            saveJob?.cancel()
            saveJob = scope.launch(Dispatchers.IO) {
                delay(SAVE_DELAY_MS)
                saveLock.withLock {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    runCatching {
                        tmp.writeText(LinkPreviewCodec.encode(cache.values))
                        if (!tmp.renameTo(file)) {
                            file.delete()
                            tmp.renameTo(file)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_AT_ONCE = 3
        const val SAVE_DELAY_MS = 1_500L
    }
}
