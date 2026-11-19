package com.justdataplease.boringtravel.together

import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.tags.ItemTags
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A shared trip file that can't be read: damaged, someone else's kind of file, or from a newer app. */
class ShareFormatException(message: String) : IOException(message)

/**
 * Writes and reads `trip.btshare` (gzip JSON of a [ShareFile]). Plain Kotlin, unit-tested. Another member's file comes
 * from a folder anyone with the link can see, so every read is bounded and checked before anything is merged.
 */
object ShareCodec {
    const val FORMAT = 1
    const val FILE_NAME = "trip.btshare"
    const val MIME = "application/octet-stream"

    /** A long trip with a full journal is well under 2 MB of JSON. */
    const val MAX_JSON_BYTES = 24L * 1024 * 1024
    const val MAX_GZIP_BYTES = 8L * 1024 * 1024
    const val MAX_MEMBERS = 12
    const val MAX_NAME = 40
    private const val MAX_ROWS = 50_000
    private val ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val DRIVE_ID = Regex("[A-Za-z0-9_-]{5,200}")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(file: ShareFile): ByteArray {
        val text = json.encodeToString(ShareFile.serializer(), file).toByteArray(Charsets.UTF_8)
        if (text.size > MAX_JSON_BYTES) throw ShareFormatException("This trip is too big to share.")
        return ByteArrayOutputStream(text.size / 4 + 64).use { buf ->
            GZIPOutputStream(buf).use { it.write(text) }
            buf.toByteArray()
        }
    }

    /** Reads and checks another member's file; throws [ShareFormatException] when it can't be used. */
    fun decode(bytes: ByteArray): ShareFile {
        if (bytes.size > MAX_GZIP_BYTES) throw ShareFormatException("This shared trip is too big to open.")
        val text = try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_JSON_BYTES) throw ShareFormatException("This shared trip is too big to open.")
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } catch (e: ShareFormatException) {
            throw e
        } catch (_: IOException) {
            throw ShareFormatException("This shared trip is damaged.")
        }
        val file = try {
            json.decodeFromString(ShareFile.serializer(), text)
        } catch (_: SerializationException) {
            throw ShareFormatException("This shared trip is damaged.")
        } catch (_: IllegalArgumentException) {
            throw ShareFormatException("This shared trip is damaged.")
        }
        if (file.app != "BoringTravel" || file.format < 1) throw ShareFormatException("This isn't a BoringTravel trip.")
        if (file.format > FORMAT) throw ShareFormatException("A member has a newer BoringTravel. Update the app to see their changes.")
        validate(file)
        return mend(file)
    }

    /**
     * A row with a place that can't be on Earth, an impossible end, untidy tags, or a link that isn't a web address, keeps everything else: one bad
     * row never costs a member's whole file. A Google Maps link sent as the website (an app before schema 9) is the
     * Maps link ([LinkPlaces.sortLinks]).
     */
    private fun mend(f: ShareFile): ShareFile = f.copy(
        planItems = f.planItems.map { r ->
            val placed = if (place(r.lat, r.lng)) r else r.copy(lat = null, lng = null)
            val ended = if (PlanSpan.validEnd(placed.endDays, placed.endMinutes)) placed else placed.copy(endDays = null, endMinutes = null)
            val tagged = ended.copy(tags = ItemTags.tidy(ended.tags))
            val (url, mapsUrl) = LinkPlaces.sortLinks(LinkPlaces.web(tagged.url), LinkPlaces.web(tagged.mapsUrl))
            tagged.copy(url = url, mapsUrl = mapsUrl)
        },
        entries = f.entries.map { if (place(it.lat, it.lng)) it else it.copy(lat = null, lng = null) },
        photos = f.photos.map { if (place(it.lat, it.lng)) it else it.copy(lat = null, lng = null) },
    )

    private fun validate(f: ShareFile) {
        fun bad(): Nothing = throw ShareFormatException("This shared trip is damaged.")
        if (!ID.matches(f.writer) || !ID.matches(f.trip.id)) bad()
        if (f.members.size > MAX_MEMBERS || f.members.none { it.id == f.writer }) bad()
        if (f.members.any { !ID.matches(it.id) || (it.fileId != null && !DRIVE_ID.matches(it.fileId)) }) bad()
        if (f.members.map { it.id }.toSet().size != f.members.size) bad()
        if (f.planItems.size + f.entries.size + f.photos.size > MAX_ROWS) bad()
        val tripId = f.trip.id
        if (f.planItems.any { !ID.matches(it.id) || it.tripId != tripId || !it.position.isFinite() }) bad()
        if (f.entries.any { !ID.matches(it.id) || it.tripId != tripId || it.authorId?.let(ID::matches) != true }) bad()
        if (f.photos.any { !ID.matches(it.id) || !ID.matches(it.authorId) || (it.fileId != null && !DRIVE_ID.matches(it.fileId)) }) bad()
        listOf(f.planItems.map { it.id }, f.entries.map { it.id }, f.photos.map { it.id }).forEach { if (it.toSet().size != it.size) bad() }
        listOfNotNull(f.trip.startDate, f.trip.endDate).forEach { if (runCatching { LocalDate.parse(it) }.isFailure) bad() }
        f.planItems.forEach { item -> item.day?.let { if (runCatching { LocalDate.parse(it) }.isFailure) bad() } }
    }

    private fun place(lat: Double?, lng: Double?): Boolean =
        (lat == null || (lat.isFinite() && lat in -90.0..90.0)) && (lng == null || (lng.isFinite() && lng in -180.0..180.0))

    /** A member's name as shown: trimmed, one line, at most [MAX_NAME] characters. */
    fun cleanName(name: String): String =
        name.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(MAX_NAME)
}
