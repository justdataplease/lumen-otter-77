package com.justdataplease.boringtravel.ui.journal

import androidx.compose.runtime.Immutable
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.repo.VideoLimits
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.DocRender
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.util.Dates
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * An entry with what the lists need, parsed once (off the main thread) instead of on every draw. [videoIds]: the ids
 * of the videos (MediaRepository.observeVideoIds), counted apart from the photos.
 */
@Immutable
class EntryInfo(val entry: Entry, videoIds: Set<String> = emptySet()) {
    val doc: EntryDoc = EntryDoc.parse(entry.body)
    val date: LocalDate = Dates.localDate(entry.occurredAt, entry.zoneId)
    val preview: String = DocRender.preview(doc)

    /** Its photos and videos, in reading order. */
    val photoIds: List<String> = doc.mediaIds().filter { it.isNotBlank() }

    /** The entry's first photo when it is in a photo layout: the list shows that layout in small. */
    val firstAlbum: Block.Album? = doc.blocks.firstOrNull { (it is Block.Photo && it.mediaId.isNotBlank()) || it is Block.Album } as? Block.Album
    val meta: String = photoIds.count { it in videoIds }.let { videos ->
        JournalLogic.meta(entry.placeName, entry.tempC, photoIds.size - videos, videos)
    }
}

/** Pure helpers for the journal screens (unit tested). */
object JournalLogic {
    /** Mono line under an entry: "Naxos · 27° · 3 photos", "Naxos · 2 photos, 1 video". */
    fun meta(place: String, tempC: Double?, photos: Int, videos: Int = 0): String = listOfNotNull(
        place.trim().ifBlank { null },
        tempC?.let { "${it.roundToInt()}°" },
        counts(photos, videos),
    ).joinToString(" · ")

    /** "3 photos", "1 video", "2 photos, 1 video"; null for none. */
    fun counts(photos: Int, videos: Int): String? = listOfNotNull(
        when {
            photos <= 0 -> null
            photos == 1 -> "1 photo"
            else -> "$photos photos"
        },
        when {
            videos <= 0 -> null
            videos == 1 -> "1 video"
            else -> "$videos videos"
        },
    ).joinToString(", ").ifEmpty { null }

    /**
     * The editor's line after a video went in, when it goes up to Drive (a backup or a shared trip: [uploads]): that it
     * takes a while, with its length when it is over a minute. Null when it stays on the phone.
     */
    fun videoAdded(durationMs: Long?, uploads: Boolean): String? = when {
        !uploads -> null
        durationMs != null && durationMs > 60_000 + VideoLimits.SLACK_MS ->
            "This video is ${VideoLimits.minutes(durationMs)} min long. It will take a while to upload."
        else -> "Videos take a while to upload."
    }

    /** A photo in a media grid, with the entry it opens ([entryId] null: a photo of the trip that is in no entry). */
    data class PhotoCell(val mediaId: String, val entryId: String?, val time: Long, val zoneId: String, val media: Media) {
        val date: LocalDate get() = Dates.localDate(time, zoneId)
    }

    /**
     * Every photo placed in [entries] that still has its file row, newest first (capture time, else the entry's
     * time). Photos removed from an entry or never saved into one are left out. With [looseZone], the trip's photos
     * that were never meant for an entry come too (brought in from the phone while making the book: no entry id on
     * their row), by their capture time in that zone.
     */
    fun photoCells(entries: List<EntryInfo>, media: List<Media>, looseZone: String? = null): List<PhotoCell> {
        val byId = media.associateBy { it.id }
        val placed = entries.flatMap { info ->
            info.photoIds.mapNotNull { id ->
                byId[id]?.let { m -> PhotoCell(id, info.entry.id, m.takenAt ?: info.entry.occurredAt, info.entry.zoneId, m) }
            }
        }
        val inEntries = placed.mapTo(HashSet()) { it.mediaId }
        val loose = if (looseZone == null) emptyList() else media
            .filter { it.entryId == null && it.deletedAt == null && it.mime.startsWith("image/") && it.id !in inEntries }
            .map { m -> PhotoCell(m.id, null, m.takenAt ?: m.createdAt, looseZone, m) }
        return (placed + loose).distinctBy { it.mediaId }.sortedByDescending { it.time }
    }

    /** Photos grouped by day, days in order (a trip reads front to back). */
    fun photoDays(cells: List<PhotoCell>): List<Pair<LocalDate, List<PhotoCell>>> =
        cells.sortedBy { it.time }.groupBy { it.date }.toList().sortedBy { it.first }

    /** "Day 4" when [day] falls inside the trip's dates. */
    fun tripDay(start: LocalDate?, end: LocalDate?, day: LocalDate): Int? {
        if (start == null || day.isBefore(start) || (end != null && day.isAfter(end))) return null
        return (day.toEpochDay() - start.toEpochDay() + 1).toInt()
    }

    /** A piece of an entry as the view lays it out: one block, or a run of list items drawn as one list. */
    sealed interface Piece {
        data class One(val index: Int, val block: Block) : Piece
        data class Items(val style: ListStyle, val items: List<Pair<Int, Block.Paragraph>>) : Piece
    }

    fun pieces(blocks: List<Block>): List<Piece> {
        val out = ArrayList<Piece>()
        var run: MutableList<Pair<Int, Block.Paragraph>>? = null
        var style: ListStyle? = null
        fun flush() {
            val r = run
            val s = style
            if (r != null && s != null && r.isNotEmpty()) out += Piece.Items(s, r.toList())
            run = null
            style = null
        }
        blocks.forEachIndexed { i, b ->
            val list = (b as? Block.Paragraph)?.list
            if (b is Block.Paragraph && list != null) {
                if (list != style) flush()
                if (run == null) {
                    run = ArrayList()
                    style = list
                }
                run?.add(i to b)
            } else {
                flush()
                out += Piece.One(i, b)
            }
        }
        flush()
        return out
    }
}
