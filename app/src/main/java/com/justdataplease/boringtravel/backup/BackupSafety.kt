package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.tags.ItemTags

/**
 * The rules that keep a backup from failing or doing harm (owner, 2026-11-11: "at no circumstances should the backup
 * fail"; "make the backup safe"). Pure, unit tested; [BackupManager] applies them.
 *
 * - One bad row never spoils a backup: [clean] mends a place that can't be on Earth before the rows are written, and
 *   again when a backup is read, so a restore takes everything else instead of refusing the whole backup.
 * - One bad photo never stops a backup: it is left for the next one and counted ([photosLeft]).
 * - A phone with nothing on it never writes a backup ([worthBackingUp]): an empty backup would be the newest one a
 *   new phone is offered.
 */
object BackupSafety {
    private fun onEarth(lat: Double?, lng: Double?): Boolean =
        (lat == null || (lat.isFinite() && lat in -90.0..90.0)) && (lng == null || (lng.isFinite() && lng in -180.0..180.0))

    /**
     * [s] with every place valid: a plan item, note or photo with an impossible point loses the point (it keeps its
     * name and everything else), an impossible end (a day past two months, a time past midnight) is left out, tags are tidied ([ItemTags.tidy]); a route point or stop, which is nothing but its point, is left out; a plan position
     * that isn't a number becomes 0, and a temperature, accuracy, speed or height that isn't a number is left blank.
     * Links are http(s) only, and a Google Maps link kept as the website (backups before schema 9) is the Maps link
     * ([LinkPlaces.sortLinks]).
     */
    fun clean(s: Snapshot): Snapshot = s.copy(
        planItems = s.planItems.map { i ->
            val placed = if (onEarth(i.lat, i.lng)) i else i.copy(lat = null, lng = null)
            val ended = if (PlanSpan.validEnd(placed.endDays, placed.endMinutes)) placed else placed.copy(endDays = null, endMinutes = null)
            val tagged = ended.copy(tags = ItemTags.tidy(ended.tags))
            val linked = LinkPlaces.sortLinks(tagged.copy(url = LinkPlaces.web(tagged.url), mapsUrl = LinkPlaces.web(tagged.mapsUrl)))
            if (linked.position.isFinite()) linked else linked.copy(position = 0.0)
        },
        entries = s.entries.map { e ->
            val placed = if (onEarth(e.lat, e.lng)) e else e.copy(lat = null, lng = null)
            if (placed.tempC?.isFinite() == false) placed.copy(tempC = null) else placed
        },
        media = s.media.map { if (onEarth(it.lat, it.lng)) it else it.copy(lat = null, lng = null) },
        // A reading that isn't a number (a sensor can say NaN) can't be written as JSON: it is left blank.
        trackPoints = s.trackPoints.filter { onEarth(it.lat, it.lng) }.map { p ->
            if (p.accuracyM?.isFinite() != false && p.speedMps?.isFinite() != false && p.altitudeM?.isFinite() != false) p
            else p.copy(
                accuracyM = p.accuracyM?.takeIf { it.isFinite() }, speedMps = p.speedMps?.takeIf { it.isFinite() },
                altitudeM = p.altitudeM?.takeIf { it.isFinite() },
            )
        },
        stops = s.stops.filter { onEarth(it.lat, it.lng) },
    )

    /** Whether the phone holds anything of the traveller's own that isn't deleted: only then is a backup written. */
    fun worthBackingUp(s: Snapshot): Boolean =
        s.trips.any { it.deletedAt == null } || s.planItems.any { it.deletedAt == null } || s.entries.any { it.deletedAt == null } ||
            s.media.any { it.deletedAt == null } || s.stops.any { it.deletedAt == null } || s.countryMarks.any { it.deletedAt == null } ||
            s.books.any { it.deletedAt == null } || s.trackPoints.isNotEmpty()

    /** The traveller's own live photos: the ones a Drive backup saves (a shared trip's other members keep theirs). */
    fun ownPhotos(media: List<Media>): List<Media> = media.filter { it.deletedAt == null && it.authorId == null }

    /**
     * Own photos in [media] that have no checked copy in Drive after the uploads ([verified]: the Drive ids found or
     * made) and aren't [waiting] for their shared trip: they stay for the next backup.
     */
    fun photosLeft(media: List<Media>, verified: Set<String>, waiting: Set<String>): List<Media> =
        ownPhotos(media).filter { it.id !in waiting && (it.driveFileId == null || it.driveFileId !in verified) }

    /**
     * Whether the automatic backup must wait: this phone has never backed up, the traveller hasn't been asked about
     * restoring yet, and Drive already holds a backup with something in it ([driveHasData]). The app asks first
     * (owner: "in a new phone search for a backup first"), so a new phone never backs up over the question.
     */
    fun waitForRestoreAnswer(lastBackupAt: Long?, restoreAsked: Boolean, driveHasData: Boolean): Boolean =
        lastBackupAt == null && !restoreAsked && driveHasData
}
