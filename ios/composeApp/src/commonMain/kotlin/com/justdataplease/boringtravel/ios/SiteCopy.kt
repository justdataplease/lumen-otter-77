package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.compat.File
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.io.IOException
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.PlaceResult
import com.justdataplease.boringtravel.site.CopiedNote
import com.justdataplease.boringtravel.site.ExistingTrip
import com.justdataplease.boringtravel.site.NetSiteWeb
import com.justdataplease.boringtravel.site.SavedPicture
import com.justdataplease.boringtravel.site.SiteImportState
import com.justdataplease.boringtravel.site.SiteImporter
import com.justdataplease.boringtravel.site.SiteLimits
import com.justdataplease.boringtravel.site.SiteNotes
import com.justdataplease.boringtravel.site.SitePlaces
import com.justdataplease.boringtravel.site.SiteReader
import com.justdataplease.boringtravel.site.SiteStore
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.coroutines.CoroutineContext
import androidx.compose.ui.graphics.asSkiaBitmap

/*
 * Copy from a link on this phone (the Android app's site/AppSiteStore.kt: siteImporter, siteAi, AppSiteStore). The
 * reading, the sorting, the proposal and the copy are the Android app's own files (site/, read in at build time); this
 * file gives them the phone: the pages over this app's network (NetSiteWeb), the trips and notes in the trip store,
 * the pictures in the app's files, the map search, the AI that sorts (the planner's: PlannerKit.siteAi), and time to
 * finish when the app goes to the background ([BackgroundWork]).
 */

/**
 * The phone's leave to go on after the app leaves the screen (nothing on the desktop). On iPhone: UIApplication's
 * background task (about half a minute, then the app is paused and the work goes on when it is back: a cut-short read
 * says so, a copy keeps every whole note, an export makes the part again) and, since iOS 26, a continued processing
 * task (BGContinuedProcessingTask: started by the traveller's own tap, the system showing [name] and the work's
 * progress, going on away from the screen as Android's work does, until it is done or the system or the traveller
 * stops it).
 */
interface BackgroundWork {
    /** Asks for time to finish [name] (the words the system shows with it); the ticket says how far it is and when it is done. */
    fun beginBackgroundWork(name: String): BackgroundTicket
}

/** Time asked for by [BackgroundWork]: ended once, when the work is over. */
fun interface BackgroundTicket {
    fun endBackgroundWork()

    /** How far the work is, 0–1, for the system's own progress (iOS 26's continued task); nothing elsewhere. */
    fun progressBackgroundWork(done: Float) {}

    /** Whether the work may go on away from the screen for as long as it takes (iOS 26's continued task, once running). */
    val keepsGoingAway: Boolean get() = false
}

/** No time to ask for (the desktop, tests). */
object NoBackgroundWork : BackgroundWork {
    override fun beginBackgroundWork(name: String): BackgroundTicket = BackgroundTicket {}
}

/** The phone's background time, for the app's long work. */
val LocalBackgroundWork = staticCompositionLocalOf<BackgroundWork> { NoBackgroundWork }

/** Copy from a link for every screen: one for the app, as Android's AppGraph.siteImport (null in previews without one). */
val LocalSiteImport = staticCompositionLocalOf<SiteImporter?> { null }

/**
 * Asks [work] for time while [importer] reads or copies, and gives it back as soon as it is done (Android's process
 * goes on by itself; an iPhone app is paused a few seconds after it leaves the screen). Runs until cancelled.
 */
suspend fun keepCopyAlive(importer: SiteImporter, work: BackgroundWork) {
    var ticket: BackgroundTicket? = null
    try {
        importer.state.collect { s ->
            val busy = s is SiteImportState.Reading || s is SiteImportState.Copying
            if (busy && ticket == null) ticket = work.beginBackgroundWork(BACKGROUND_NAME)
            if (!busy) {
                ticket?.endBackgroundWork()
                ticket = null
            }
            copyProgress(s)?.let { ticket?.progressBackgroundWork(it) }
        }
    } finally {
        ticket?.endBackgroundWork()
    }
}

/**
 * How far a copy is, for the system's own progress (iOS 26): reading moves a little with every post found (its end
 * isn't known), the copy itself by its notes done of all; null when it isn't working.
 */
fun copyProgress(s: SiteImportState): Float? = when (s) {
    is SiteImportState.Reading -> (s.found / (s.found + 20f)) * 0.1f
    is SiteImportState.Copying -> 0.1f + 0.9f * (s.done.toFloat() / s.total.coerceAtLeast(1)).coerceIn(0f, 1f)
    else -> null
}

/** What the phone calls a running copy (iPhone: its background task, and on iOS 26 the system's progress title). */
const val COPY_WORK_NAME = "Copy from a link"

private const val BACKGROUND_NAME = COPY_WORK_NAME

/**
 * How the copy is set up besides the phone (tests and previews change it): [pause] between two pages of a site
 * (SiteLimits.PAGE_PAUSE_MS on the phone), the gap between two map questions ([mapGapMs]), where the reading runs
 * ([reading]: off the main thread), and [inlineDir]: where pictures that come inside a page wait (a Google Doc's); null:
 * under the caches, when the app has a folder there.
 */
data class SiteCopySetup(
    val pause: suspend (Long) -> Unit = { delay(it) },
    val mapGapMs: Long = SiteLimits.MAP_GAP_MS,
    val reading: CoroutineContext = Dispatchers.Default,
    val inlineDir: File? = null,
)

/**
 * Copy from a link for this app (Android's siteImporter): reads on [web] (the link previews' User-Agent), keeps into
 * [book] and the app's [files], sorts with the planner's AI ([kit]), runs in [scope] (the app's: leaving the screen
 * doesn't stop it). [onOwnTrip]: a trip of the traveller's own was made (demo mode ends); [changed]: the trip store
 * changed (the screens read it again).
 */
fun siteImporter(
    scope: CoroutineScope,
    book: TripBook,
    files: MediaFiles,
    web: Web,
    kit: PlannerKit?,
    onOwnTrip: (Trip) -> Unit = {},
    changed: () -> Unit = {},
    setup: SiteCopySetup = SiteCopySetup(),
    /** The owner's Drive (since round 23): a Google Doc it may open is read through it first. */
    drive: com.justdataplease.boringtravel.google.DriveApi? = null,
): SiteImporter {
    // Pictures inside a page wait under the caches (the phone may empty them; they are copied out first).
    val inline = setup.inlineDir ?: web.cache.absolute("site-pictures").takeUnless { it.startsWith("memory:") }?.let(::File)
    val download = inline?.let { File(it.path + "-download") }
    val userAgent = LinkFetcher.userAgent(web.version)
    val siteWeb = NetSiteWeb(web.net, userAgent, LinkFetcher.crawlerUserAgent(web.version), tempDir = { download })
    val store = BookSiteStore(
        book, files, web.net, userAgent, { query, near -> web.places.search(query, near) }, inline,
        onOwnTrip = onOwnTrip, changed = changed, mapGapMs = setup.mapGapMs,
    )
    // A Google Doc through Drive first when it is connected (round 23), else its public export, else its published page.
    val reader = SiteReader(siteWeb, pause = setup.pause, drive = drive?.let(::DriveDocsOf), inlineDir = { store.inlineDir })
    return SiteImporter(
        scope,
        // The reading (pages, feeds, HTML into notes) off the main thread; the trip store is written on it.
        reader = { address, isCopied, onProgress, stop -> withContext(setup.reading) { reader.read(address, isCopied, onProgress, stop) } },
        store = store,
        ai = { kit?.siteAi() },
        // A list of saved places shared from Google Maps, read as the link previews read Maps links.
        lists = { url -> web.links.mapsList(url) },
    )
}

/**
 * [SiteStore] on this phone (Android's AppSiteStore): notes and their photos into the trip store in one write, trips
 * through it (a new trip of the traveller's own ends demo mode, [onOwnTrip]), pictures into the app's files as
 * `media/<id>.<ext>` like photos picked from the library. A picture the app keeps as it is (JPEG, PNG, WebP, GIF) stays
 * so; one the phone can draw but the app doesn't keep (BMP, ICO …) becomes a JPEG; SVG and anything that can't be drawn
 * is left out (the copy counts it). [inlineDir] holds the pictures that came inside a page until they are copied.
 */
class BookSiteStore(
    private val book: TripBook,
    private val files: MediaFiles,
    private val net: Net,
    private val userAgent: String,
    search: suspend (String, LatLng?) -> List<PlaceResult>,
    val inlineDir: File?,
    private val onOwnTrip: (Trip) -> Unit = {},
    private val changed: () -> Unit = {},
    mapGapMs: Long = SiteLimits.MAP_GAP_MS,
    private val now: () -> Long = ::nowMs,
) : SiteStore {
    private val onMap = SitePlaces(mapGapMs, search)

    override suspend fun copied(entryId: String): CopiedNote? =
        book.snapshot.entries.firstOrNull { it.id == entryId }?.takeIf { it.deletedAt == null }?.let { CopiedNote(it.tripId, it.occurredAt) }

    override suspend fun trip(id: String, notes: List<Long>): ExistingTrip? =
        book.trip(id)?.let { ExistingTrip(it.id, it.title, it.startDate, it.endDate, notes) }

    override suspend fun createTrip(title: String, start: LocalDate?, end: LocalDate?): String {
        val trip = book.newTrip(title, start, end)
        onOwnTrip(trip)
        changed()
        return trip.id
    }

    override suspend fun extendTrip(id: String, start: LocalDate?, end: LocalDate?) {
        val trip = book.trip(id) ?: return
        book.putRows(trips = listOf(trip.copy(startDate = start ?: trip.startDate, endDate = end ?: trip.endDate, updatedAt = stamp(trip.updatedAt))))
        changed()
    }

    /** The note and its photos in one write: a note is kept whole or not at all (Android: one transaction). */
    override suspend fun saveNote(note: SiteNotes.Note, media: List<Media>) {
        val t = now()
        book.putRows(
            entries = listOf(note.entry.copy(updatedAt = stamp(note.entry.updatedAt))),
            media = media.map { it.copy(createdAt = it.createdAt.takeIf { c -> c > 0 } ?: t, updatedAt = stamp(it.updatedAt)) },
        )
        changed()
    }

    override suspend fun discard(pictures: List<SavedPicture>) = withContext(MediaWork) {
        pictures.filter { it.fresh }.forEach { files.delete(it.media.localPath) }
    }

    override suspend fun clearInline() {
        withContext(MediaWork) { inlineDir?.deleteRecursively() }
    }

    override suspend fun findPlace(name: String, area: String?, near: LatLng?): SiteNotes.Place? = onMap.find(name, area, near)

    /** [item] last on its day, unless the trip has had it (deleted since: it stays deleted) or has a place of that name there. */
    override suspend fun addPlanItem(item: PlanItem): Boolean {
        if (book.snapshot.planItems.any { it.id == item.id }) return false
        val live = book.items(item.tripId)
        if (live.any { SiteNotes.samePlace(it, item) }) return false
        val position = (live.filter { it.day == item.day }.maxOfOrNull { it.position } ?: 0.0) + 1.0
        val saved = book.saveItem(item.copy(position = position)) != null
        if (saved) changed()
        return saved
    }

    override suspend fun savePicture(url: String, mediaId: String, local: File?): SavedPicture {
        reuse(mediaId)?.let { return it }
        val bytes = if (local != null) withContext(MediaWork) { local.readBytes() } else download(url)
        return withContext(MediaWork) { keep(mediaId, bytes) }
    }

    /** At most 15 MB of a picture over https, named as the app, asking for pictures only (Android's request). */
    private suspend fun download(url: String): ByteArray {
        val target = LinkUrls.forFetch(url)?.takeIf { it.startsWith("https://") } ?: throw IOException("not a secure address")
        val r = net.ask(
            NetRequest(
                target, mapOf("User-Agent" to userAgent, "Accept" to "image/*"), maxBytes = SiteLimits.MAX_PICTURE_BYTES + 1,
                timeoutMs = SiteLimits.PICTURE_SECONDS * 1000, readTimeoutMs = SiteLimits.READ_SECONDS * 1000,
                connectTimeoutMs = SiteLimits.CONNECT_SECONDS * 1000,
            ),
        )
        if (!r.ok) throw LinkHttpException(r.code)
        val length = r.header("Content-Length")?.trim()?.toLongOrNull()
        if ((length != null && length > SiteLimits.MAX_PICTURE_BYTES) || r.body.size > SiteLimits.MAX_PICTURE_BYTES) throw IOException("larger than 15 MB")
        val type = r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (type.isNotEmpty() && !type.startsWith("image/") && type != "application/octet-stream") throw IOException("not a picture ($type)")
        if (type == "image/svg+xml") throw IOException("SVG drawing")
        if (r.body.isEmpty()) throw IOException("empty answer")
        return r.body
    }

    /**
     * Keeps [bytes] as `media/<id>.<ext>`, as the Android app's AppSiteStore.keep: as they are when the app shows that
     * type (JPEG, PNG, WebP, GIF, HEIC: its own type as the phone's reader names it), else as a JPEG when the phone can
     * read it (AVIF, BMP, ICO…; at most 4096 px on its longer side, quality 90, its tags left behind), with its size as
     * shown and, from its own tags, when it was taken (DateTimeOriginal) and where. The phone's reader is ImageIO on
     * iPhone, so a HEIC or AVIF picture is read there ([Images]).
     */
    private fun keep(mediaId: String, bytes: ByteArray): SavedPicture {
        val read = Images.facts(bytes) ?: throw IOException("can't be decoded (unknown type)")
        // Android's decoder names every HEIF picture (a HEIC too) "image/heif": the row says so on both phones.
        val type = read.mime?.lowercase()?.let { if (it == "image/heic") "image/heif" else it }
        val keptExt = type?.let(KEPT::get)
        val (mime, ext, out) = if (type != null && keptExt != null) Triple(type, keptExt, bytes) else Triple(MediaTypes.JPEG, "jpg", reencode(bytes, type))
        val facts = if (out === bytes) read else Images.facts(out) ?: throw IOException("can't be decoded (${read.mime})")
        val rel = "media/$mediaId.$ext"
        if (!files.write(rel, out)) throw IOException("couldn't keep the picture")
        // Android reads the capture time from DateTimeOriginal only here (and the place), from whichever type was kept.
        val tags = facts.tags
        val t = now()
        return SavedPicture(
            Media(
                id = mediaId, localPath = rel, mime = mime, width = facts.width, height = facts.height,
                takenAt = Exif.parseTime(tags.dateTimeOriginal, tags.offsetTimeOriginal), lat = tags.lat, lng = tags.lng,
                sha256 = Sha256.hex(out), createdAt = t, updatedAt = t,
            ),
            fresh = true,
        )
    }

    /**
     * [bytes] drawn upright and written as a JPEG, at most [MAX_REENCODE_SIDE] px on the longer side: Skia's when it reads
     * them (BMP, ICO), else the phone's (AVIF and the rest ImageIO reads).
     */
    private fun reencode(bytes: ByteArray, type: String?): ByteArray {
        if (Images.skiaFormat(bytes) == null) {
            return runCatching { Images.phone.jpeg(bytes, MAX_REENCODE_SIDE, JPEG_QUALITY) }.getOrNull() ?: throw IOException("can't be decoded ($type)")
        }
        val picture = Pictures.decode(bytes, MAX_REENCODE_SIDE) ?: throw IOException("can't be decoded ($type)")
        val image = Image.makeFromBitmap(picture.asSkiaBitmap())
        try {
            return image.encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)?.bytes ?: throw IOException("couldn't write a JPEG")
        } finally {
            image.close()
        }
    }

    /**
     * An earlier copy of the same picture: kept when its photo is still in the app, else its file goes (a note deleted
     * since, or a copy stopped before its note was saved).
     */
    private suspend fun reuse(mediaId: String): SavedPicture? {
        val row = book.media(mediaId)
        return withContext(MediaWork) {
            for (ext in KEPT.values.distinct()) {
                val rel = "media/$mediaId.$ext"
                val size = files.size(rel) ?: continue
                if (row != null && row.localPath == rel && size > 0) return@withContext SavedPicture(row, fresh = false)
                files.delete(rel)
            }
            null
        }
    }

    private fun stamp(previous: Long): Long = maxOf(now(), previous + 1)

    private companion object {
        /** The picture types the app keeps as they are (Android's EXTENSIONS, by the type the picture itself declares). */
        val KEPT: Map<String, String> = mapOf(
            "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif",
            "image/heif" to "heic", "image/heic" to "heic",
        )

        const val MAX_REENCODE_SIDE = 4096
        const val JPEG_QUALITY = 90
    }
}

/**
 * A Google Doc through the owner's Drive (the Android app's site/AppSiteStore.kt DriveDocsOf): an HTML export when
 * Drive is connected and may open the document. With the `drive.file` scope that is only a document this app made (its
 * own trip exports); a pasted link to any other private document is refused (404), so the public export is tried next.
 * Drive exports at most 10 MB, read whole before the reader takes it.
 */
internal class DriveDocsOf(private val drive: com.justdataplease.boringtravel.google.DriveApi) : com.justdataplease.boringtravel.site.DriveDocs {
    override suspend fun html(id: String, read: (com.justdataplease.boringtravel.compat.InputStream) -> String): com.justdataplease.boringtravel.site.DriveDoc? {
        if (!drive.isConnected()) return null
        val bytes = drive.export(id, "text/html")
        return com.justdataplease.boringtravel.site.DriveDoc(null, read(com.justdataplease.boringtravel.compat.ByteArrayInputStream(bytes)))
    }
}
