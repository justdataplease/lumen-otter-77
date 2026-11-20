package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.systemZoneId
import kotlinx.coroutines.withContext

/*
 * "Take a photo" from Trips' Now card, a widget or a Home Screen quick action, as the Android app's rememberQuickCamera
 * and PhotoEntries do it (ui/editor/TakePhoto.kt; round 28): the camera, then the photo becomes a new note at once (the
 * photo its only block, at the photo's time and its own place), which opens in Write; when the photo can't be added,
 * Android's toast "The photo couldn't be added." and nothing opens.
 */

/** The time a camera shot gets (the Android app's data/repo/PhotoTime.kt forShot). */
object ShotTime {
    /** How far outside the camera session a shot's own time may be (a slow save, a clock a little off). */
    const val SESSION_SLACK_MS = 2 * 60_000L

    /**
     * The time for a photo just taken with the camera: its own time when that falls while the camera was open, else
     * when the camera came back (a camera with a wrong clock, or no time at all).
     */
    fun forShot(taken: Long?, launchedAt: Long, returnedAt: Long): Long =
        taken?.takeIf { it in (launchedAt - SESSION_SLACK_MS)..(returnedAt + SESSION_SLACK_MS) } ?: returnedAt
}

/** A new note made from a camera shot (the Android app's PhotoEntries). */
object PhotoEntries {
    /** What Android says when a shot couldn't become a note (its toast). */
    const val FAILED = "The photo couldn't be added."

    /**
     * Brings [shot] in and saves a new note in [tripId] (null: outside trips) with the photo as its only block, sitting
     * as [defaults] say (the trip's Photos in notes), at the photo's time ([ShotTime.forShot]) and its own point when it
     * has one. Returns the note's id; throws when the photo couldn't be added (its copy is gone then).
     */
    suspend fun create(
        book: TripBook, files: MediaFiles, shot: PickedMedia, tripId: String?, defaults: PhotoDefaults,
        launchedAt: Long, returnedAt: Long, zoneId: String = systemZoneId(), ids: () -> String = ::newId,
    ): String {
        val id = ids()
        val media = withContext(MediaWork) { MediaImporter(files).prepare(shot, tripId, id) }
        val entry = Entry(
            id = id, tripId = tripId, occurredAt = ShotTime.forShot(media.takenAt, launchedAt, returnedAt),
            zoneId = zoneId, lat = media.lat, lng = media.lng, createdAt = 0L, updatedAt = 0L,
        )
        val photo = Block.Photo(media.id, rotation = defaults.tiltFor(0), width = defaults.width, frame = defaults.frame)
        book.addMedia(media)
        if (book.saveNote(entry, EntryDoc(blocks = listOf(photo))) == null) {
            // The trip went meanwhile: the photo's row and file go too, as an import that never reached a note.
            book.removeMedia(media.id)
            files.delete(media.localPath)
            error("The note couldn't be saved")
        }
        return id
    }
}
