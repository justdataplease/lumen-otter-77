package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoAlbums
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import java.io.File

/**
 * What deleting goes through (owner, 2026-11-11: "all elements should be deletable … and removed accordingly from
 * everywhere"). Pure, so the rules are unit tested; [Deletions] reads and writes.
 *
 * A trip of the traveller's own goes with everything in it: the trip row and its plan items, journal entries, photos
 * (the trip's and its entries') and stops are marked deleted, each stamped after its last change ([Stamps.next]), so a
 * backup restored later or another phone keeps them deleted; rows deleted before keep their stamps. A shared trip is
 * only left (its rows stay for joining again, see together/), so this never runs for one.
 */
object TripRemoval {
    /**
     * [trip] and what belongs to it, marked deleted: the engine's rule ([TripMarks.mark]), the same on iPhone. [stamp]
     * gives a row's new stamp from its previous one.
     */
    fun mark(trip: Trip, items: List<PlanItem>, entries: List<Entry>, media: List<Media>, stops: List<Stop>, stamp: (Long) -> Long): TripMarks.Marked =
        TripMarks.mark(trip, items, entries, media, stops, stamp)

    private val MEDIA_PATH = Regex("^media/[A-Za-z0-9][A-Za-z0-9._-]*$")

    /**
     * The app's copies of [deleted] photos that can go: "media/<file>" paths only (nothing outside the app's photo
     * folder, never the phone's gallery), and none a live photo still uses ([livePaths]: two rows can share a file).
     */
    fun filesToFree(deleted: List<Media>, livePaths: Set<String>): List<String> =
        deleted.asSequence()
            .filter { it.deletedAt != null }
            .map { it.localPath }
            .filter { MEDIA_PATH.matches(it) && ".." !in it && it !in livePaths }
            .distinct()
            .toList()

    /**
     * The trip video's own files under the app's files folder [filesDir]: its scene edits, voice lines and the phone
     * videos copied in for it (see clip/ClipEditStore, clip/ClipMedia; the same folder names as backups' extras).
     */
    fun clipFiles(filesDir: File, tripId: String): List<File> {
        val name = safe(tripId)
        return listOf(
            File(File(filesDir, "clip-edits"), "$name.json"),
            File(File(filesDir, "clip-voice"), name),
            File(File(filesDir, "clip-media"), name),
        )
    }

    /** Deletes [clipFiles] of [tripId]; returns how many files went. */
    fun deleteClipFiles(filesDir: File, tripId: String): Int = clipFiles(filesDir, tripId).sumOf { f ->
        when {
            f.isDirectory -> f.walkBottomUp().count { it.isFile && it.delete() }.also { f.deleteRecursively() }
            f.isFile -> if (f.delete()) 1 else 0
            else -> 0
        }
    }

    /** A trip id as the clip code names its files (see clip/ClipMedia.safe). */
    private fun safe(tripId: String): String = tripId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)

    /**
     * [doc] without photo [mediaId]: a single photo goes; a photo layout keeps its other photos in their order and
     * fits its arrangement to them ([PhotoAlbums.reflow]), turns into a single photo when one is left and goes when
     * none is. Unchanged when the photo isn't in it.
     */
    fun withoutPhoto(doc: EntryDoc, mediaId: String): EntryDoc {
        if (mediaId !in doc.mediaIds()) return doc
        val blocks = doc.blocks.flatMap { b ->
            when {
                b is Block.Photo && b.mediaId == mediaId -> emptyList()
                b is Block.Album && mediaId in b.mediaIds -> {
                    val left = b.copy(mediaIds = b.mediaIds.filter { it != mediaId }, frames = b.frames - mediaId)
                    when (left.mediaIds.size) {
                        0 -> emptyList()
                        1 -> listOf(Block.Photo(left.mediaIds.single(), frame = left.frameOf(left.mediaIds.single())))
                        else -> listOf(PhotoAlbums.reflow(left, keep = PhotoAlbums.layoutOf(b)))
                    }
                }
                else -> listOf(b)
            }
        }
        return doc.copy(blocks = blocks)
    }
}
