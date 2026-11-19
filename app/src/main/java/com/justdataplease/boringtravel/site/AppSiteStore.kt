package com.justdataplease.boringtravel.site

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.BuildConfig
import com.justdataplease.boringtravel.ai.PlannerAllowance
import com.justdataplease.boringtravel.ai.PlannerUsage
import com.justdataplease.boringtravel.ai.own.OwnKeyPlanner
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.repo.PhotoFiles
import com.justdataplease.boringtravel.data.repo.PhotoTime
import com.justdataplease.boringtravel.data.repo.now
import com.justdataplease.boringtravel.google.await
import com.justdataplease.boringtravel.links.LinkFetcher
import com.justdataplease.boringtravel.links.LinkHttpException
import com.justdataplease.boringtravel.links.LinkUrls
import com.justdataplease.boringtravel.places.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Copy from a link for this app: the reader over OkHttp, the phone's database and files, and the AI to organise with. */
fun siteImporter(graph: AppGraph): SiteImporter {
    val http = OkHttpClient.Builder().build()
    val web = OkSiteWeb(http, LinkFetcher.userAgent(BuildConfig.VERSION_NAME), LinkFetcher.crawlerUserAgent(BuildConfig.VERSION_NAME))
    val store = AppSiteStore(graph, http, LinkFetcher.userAgent(BuildConfig.VERSION_NAME))
    val reader = SiteReader(web, drive = DriveDocsOf(graph), inlineDir = { store.inlineDir })
    return SiteImporter(
        graph.scope,
        reader = { address, isCopied, onProgress, stop -> reader.read(address, isCopied, onProgress, stop) },
        store = store,
        ai = { siteAi(graph) },
        // A list of saved places shared from Google Maps, read as the link previews read Maps links.
        lists = { url -> graph.links.mapsList(url) },
    )
}

/**
 * The model that organises the posts: the traveller's own key when Me › AI uses one (their own bill, no count), else
 * the built-in planner, each request counted as one of the day's answers ([PlannerAllowance.DAILY_ANSWERS]); null when
 * neither can answer. The numbered list of dates, titles and first words is sent, and in a copy of a few posts the
 * start of each paragraph and heading ([SiteOrganizer]).
 */
internal fun siteAi(graph: AppGraph): SiteAi? {
    val own: OwnKeyPlanner? = graph.ownAi.engine()
    if (own != null) return SiteAi { system, prompt -> own.answer(system, prompt, SiteOrganizer.schema) }
    val planner = graph.planner
    if (!planner.isAvailable) return null
    val usage = PlannerUsage(graph.app)
    return SiteAi { system, prompt ->
        val isPro = graph.billing.state.value.isPro
        if (usage.check(isPro, startsPlan = false) !is PlannerAllowance.Check.Allowed) throw IOException("No AI answers left today")
        planner.answer(system, prompt, SiteOrganizer.schema).also { usage.record(isPro, startsPlan = false) }
    }
}

/**
 * A Google Doc through the owner's Drive, the planner's access path ([com.justdataplease.boringtravel.google.DriveApi]):
 * an HTML export when Drive is connected and may open the document. With the `drive.file` scope that is only a
 * document this app made (its own trip exports); a pasted link to any other private document is refused (404), so the
 * public export is tried next.
 */
private class DriveDocsOf(private val graph: AppGraph) : DriveDocs {
    override suspend fun html(id: String, read: (InputStream) -> String): DriveDoc? {
        if (!graph.drive.isConnected()) return null
        return DriveDoc(null, graph.drive.export(id, "text/html", read))
    }
}

/**
 * [SiteStore] on the phone: notes through the journal, photos through the media repository, trips through the trip
 * repository (a new trip of the traveller's own ends demo mode, as any new trip does), pictures into
 * `files/media/<id>.<ext>` like photos picked from the gallery. A picture the phone can show but the app doesn't keep
 * as it is (AVIF, BMP …) is kept as a JPEG; SVG and anything that can't be decoded is left out. Each picture left out
 * is logged with its host and why (tag "BoringTravelSite"), never its full address.
 */
internal class AppSiteStore(private val graph: AppGraph, http: OkHttpClient, private val userAgent: String) : SiteStore {
    private val client = http.newBuilder()
        .connectTimeout(SiteLimits.CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SiteLimits.READ_SECONDS, TimeUnit.SECONDS)
        .callTimeout(SiteLimits.PICTURE_SECONDS, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    /** Pictures that came inside a page (a Google Doc), until they are copied. */
    val inlineDir: File get() = File(graph.app.cacheDir, "site-pictures")

    override suspend fun copied(entryId: String): CopiedNote? =
        graph.db.entries().get(entryId)?.takeIf { it.deletedAt == null }?.let { CopiedNote(it.tripId, it.occurredAt) }

    override suspend fun trip(id: String, notes: List<Long>): ExistingTrip? =
        graph.trips.get(id)?.takeIf { it.deletedAt == null }?.let { ExistingTrip(it.id, it.title, it.startDate, it.endDate, notes) }

    override suspend fun createTrip(title: String, start: LocalDate?, end: LocalDate?): String = graph.trips.create(title, start, end).id

    override suspend fun extendTrip(id: String, start: LocalDate?, end: LocalDate?) {
        val trip = graph.trips.get(id)?.takeIf { it.deletedAt == null } ?: return
        graph.trips.save(trip.copy(startDate = start ?: trip.startDate, endDate = end ?: trip.endDate))
    }

    override suspend fun saveNote(note: SiteNotes.Note, media: List<Media>) {
        graph.db.withTransaction {
            media.forEach { graph.media.save(it) }
            graph.journal.save(note.entry, note.doc)
        }
    }

    override suspend fun discard(pictures: List<SavedPicture>) = withContext(Dispatchers.IO) {
        pictures.filter { it.fresh }.forEach { runCatching { File(graph.app.filesDir, it.media.localPath).delete() } }
    }

    override suspend fun clearInline() = withContext(Dispatchers.IO) {
        runCatching { inlineDir.deleteRecursively() }
        Unit
    }

    private val onMap = SitePlaces(SiteLimits.MAP_GAP_MS) { query, around -> graph.places.search(query, around) }

    override suspend fun findPlace(name: String, area: String?, near: LatLng?): SiteNotes.Place? = onMap.find(name, area, near)

    override suspend fun addPlanItem(item: PlanItem): Boolean = graph.db.withTransaction {
        if (graph.db.planItems().get(item.id) != null) return@withTransaction false
        val live = graph.db.planItems().forTrip(item.tripId)
        if (live.any { SiteNotes.samePlace(it, item) }) return@withTransaction false
        graph.plan.save(item.copy(position = (live.filter { it.day == item.day }.maxOfOrNull { it.position } ?: 0.0) + 1.0))
        true
    }

    override suspend fun savePicture(url: String, mediaId: String, local: File?): SavedPicture {
        val host = url.toHttpUrlOrNull()?.host ?: "?"
        try {
            reuse(mediaId)?.let { return it }
            val active = currentCoroutineContext()
            if (local != null) {
                return withContext(Dispatchers.IO) { keep(mediaId) { partial -> local.inputStream().use { PhotoFiles.copy(it, partial) { active.ensureActive() } } } }
            }
            val target = LinkUrls.forFetch(url)?.takeIf { it.startsWith("https://") } ?: throw IOException("not a secure address")
            val request = Request.Builder().url(target).header("User-Agent", userAgent).header("Accept", "image/*").build()
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw LinkHttpException(response.code)
                val body = response.body ?: throw IOException("empty answer")
                if (body.contentLength() > SiteLimits.MAX_PICTURE_BYTES) throw IOException("larger than 15 MB")
                val type = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                if (type.isNotEmpty() && !type.startsWith("image/") && type != "application/octet-stream") throw IOException("not a picture ($type)")
                if (type == "image/svg+xml") throw IOException("SVG drawing")
                return withContext(Dispatchers.IO) {
                    keep(mediaId) { partial -> Capped(body.byteStream(), SiteLimits.MAX_PICTURE_BYTES).use { PhotoFiles.copy(it, partial) { active.ensureActive() } } }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = when (e) {
                is LinkHttpException -> "HTTP ${e.code}"
                is IOException -> e.message ?: e.javaClass.simpleName
                else -> e.javaClass.simpleName
            }
            Log.i(TAG, "Picture left out: $host: $why")
            throw e
        }
    }

    /**
     * Writes a picture through [write] to a partial file, checks it is one, and keeps it as `media/<id>.<ext>`: as it
     * is when the app shows that type, else as a JPEG when the phone can decode it (AVIF, BMP, ICO …).
     */
    private fun keep(mediaId: String, write: (File) -> String): SavedPicture {
        val partial = File(graph.app.filesDir, "media/$mediaId.download")
        partial.delete()
        try {
            var checksum = write(partial)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(partial.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("can't be decoded (${bounds.outMimeType ?: "unknown type"})")
            var mime = bounds.outMimeType ?: "image/jpeg"
            var ext = EXTENSIONS[mime]
            if (ext == null) {
                reencode(partial, bounds)
                mime = "image/jpeg"
                ext = "jpg"
                checksum = sha256(partial)
                BitmapFactory.decodeFile(partial.path, bounds.apply { inJustDecodeBounds = true })
            }
            val rel = "media/$mediaId.$ext"
            val file = File(graph.app.filesDir, rel)
            if (!partial.renameTo(file)) throw IOException("couldn't keep the picture")
            return SavedPicture(media(mediaId, rel, mime, bounds, file, checksum), fresh = true)
        } finally {
            partial.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** [file] decoded (scaled down to at most [MAX_REENCODE_SIDE]) and written back as a JPEG. */
    private fun reencode(file: File, bounds: BitmapFactory.Options) {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_REENCODE_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("can't be decoded (${bounds.outMimeType})")
        try {
            val out = File(file.path + ".jpg")
            out.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) throw IOException("couldn't write a JPEG") }
            if (!file.delete() || !out.renameTo(file)) throw IOException("couldn't keep the picture")
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * An earlier copy of the same picture: kept when its photo is still in the app, else its file goes (a note
     * deleted since, or a copy stopped before its note was saved).
     */
    private suspend fun reuse(mediaId: String): SavedPicture? {
        val row = graph.db.media().get(mediaId)
        return withContext(Dispatchers.IO) {
            for (ext in EXTENSIONS.values.distinct()) {
                val rel = "media/$mediaId.$ext"
                val f = File(graph.app.filesDir, rel)
                if (!f.exists()) continue
                if (row != null && row.deletedAt == null && row.localPath == rel && f.length() > 0) return@withContext SavedPicture(row, fresh = false)
                f.delete()
            }
            null
        }
    }

    /** The photo row, with the size (turned as the picture says) and the capture time and place when it still has them. */
    private fun media(id: String, rel: String, mime: String, bounds: BitmapFactory.Options, file: File, checksum: String): Media {
        var w = bounds.outWidth
        var h = bounds.outHeight
        var takenAt: Long? = null
        var lat: Double? = null
        var lng: Double? = null
        runCatching {
            val exif = ExifInterface(file)
            takenAt = PhotoTime.parse(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
            exif.latLong?.takeIf { it[0].isFinite() && it[1].isFinite() && it[0] in -90.0..90.0 && it[1] in -180.0..180.0 }?.let { lat = it[0]; lng = it[1] }
            if (exif.rotationDegrees == 90 || exif.rotationDegrees == 270) { val t = w; w = h; h = t }
        }
        val t = now()
        return Media(
            id = id, localPath = rel, mime = mime, width = w, height = h, takenAt = takenAt,
            lat = lat, lng = lng, sha256 = checksum, createdAt = t, updatedAt = t,
        )
    }

    /** Stops reading past [max] bytes: a picture that turns out larger than it said fails instead of filling the phone. */
    private class Capped(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) check(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) check(it.toLong()) }

        private fun check(n: Long) {
            count += n
            if (count > max) throw IOException("larger than 15 MB")
        }
    }

    private companion object {
        const val TAG = "BoringTravelSite"

        /** The picture types the app keeps as they are, by the type the picture itself declares. */
        val EXTENSIONS = mapOf(
            "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif",
            "image/heif" to "heic", "image/heic" to "heic",
        )

        /** A picture kept as a JPEG is at most this many pixels on its longer side, at this quality. */
        const val MAX_REENCODE_SIDE = 4096
        const val JPEG_QUALITY = 90
    }
}
