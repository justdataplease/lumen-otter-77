package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.data.DemoTrips
import com.justdataplease.boringtravel.data.db.BookDraft
import com.justdataplease.boringtravel.data.db.CountryMark
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.MediaTypes
import com.justdataplease.boringtravel.data.repo.Stamps
import java.security.MessageDigest

/**
 * What a restore writes. Each list goes to its table's upsert; the trips in [clearRoutes] (null: the points recorded
 * outside trips) lose their route points first, then [trackPoints] are inserted.
 */
data class MergePlan(
    val trips: List<Trip> = emptyList(),
    val planItems: List<PlanItem> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val media: List<Media> = emptyList(),
    val trackPoints: List<TrackPoint> = emptyList(),
    val stops: List<Stop> = emptyList(),
    val countryMarks: List<CountryMark> = emptyList(),
    val books: List<BookDraft> = emptyList(),
    val clearRoutes: List<String?> = emptyList(),
) {
    val isEmpty: Boolean
        get() = trips.isEmpty() && planItems.isEmpty() && entries.isEmpty() && media.isEmpty() && trackPoints.isEmpty() && stops.isEmpty() &&
            countryMarks.isEmpty() && books.isEmpty() && clearRoutes.isEmpty()
}

/**
 * How a restore puts a backup on the phone (owner, 2026-11-12: "if restore, restore entirely, like a database"): the
 * phone goes back to the backup.
 *
 * - A row the backup has is written as the backup has it, whatever happened to it on the phone since (changed, deleted).
 * - A row the backup doesn't have is removed: marked deleted, the way the app deletes, so the removal reaches later
 *   backups and can be undone ([BackupManager.undoRestore] keeps the phone as it was).
 * - The route (its points have no id) becomes the backup's, trip by trip.
 *
 * Two things are never rolled back. The example trips: they aren't in backups. And the trips shared now: they belong
 * to everyone in them, so there the backup only adds. What is new, newer or was deleted here comes back; the rest stays.
 *
 * A row the restore changes gets a new stamp unless the backup's own is the later one, so the restore is the latest
 * change wherever the row travels next (a later backup, a shared trip). Restoring the same backup twice changes nothing.
 */
object BackupMerge {
    private val SAFE_PATH = Regex("^media/[A-Za-z0-9][A-Za-z0-9._-]*$")

    /**
     * How one table's rows are read and stamped: [at] is the row with a new updatedAt and deletedAt; [foreign] is
     * another member's row in a shared trip (a note or a photo), which only its author changes.
     */
    class Rows<T>(
        val id: (T) -> String,
        val updatedAt: (T) -> Long,
        val deletedAt: (T) -> Long?,
        val tripId: (T) -> String?,
        val at: (T, Long, Long?) -> T,
        val foreign: (T) -> Boolean = { false },
    )

    val TRIPS = Rows<Trip>({ it.id }, { it.updatedAt }, { it.deletedAt }, { it.id }, { r, u, d -> r.copy(updatedAt = u, deletedAt = d) })
    val PLAN_ITEMS = Rows<PlanItem>({ it.id }, { it.updatedAt }, { it.deletedAt }, { it.tripId }, { r, u, d -> r.copy(updatedAt = u, deletedAt = d) })
    val ENTRIES = Rows<Entry>({ it.id }, { it.updatedAt }, { it.deletedAt }, { it.tripId }, { r, u, d -> r.copy(updatedAt = u, deletedAt = d) }) { it.authorId != null }
    val MEDIA = Rows<Media>({ it.id }, { it.updatedAt }, { it.deletedAt }, { it.tripId }, { r, u, d -> r.copy(updatedAt = u, deletedAt = d) }) { it.authorId != null }
    val STOPS = Rows<Stop>({ it.id }, { it.updatedAt }, { it.deletedAt }, { it.tripId }, { r, u, d -> r.copy(updatedAt = u, deletedAt = d) })
    val COUNTRY_MARKS = Rows<CountryMark>({ it.id }, { it.updatedAt }, { it.deletedAt }, { null }, { r, u, d -> r.copy(updatedAt = u, deletedAt = d) })
    val BOOKS = Rows<BookDraft>({ it.id }, { it.updatedAt }, { it.deletedAt }, { it.tripId }, { r, u, d -> r.copy(updatedAt = u, deletedAt = d) })

    /**
     * What to write so the phone ([local], every row it has) becomes the backup ([incoming], without the examples).
     * [shared]: the trips shared now. [stamp]: the time of a change to a row last stamped with its argument.
     */
    fun replace(local: Snapshot, incoming: Snapshot, shared: Set<String> = emptySet(), stamp: (Long) -> Long = { Stamps.next(it) }): MergePlan {
        // What a restore may remove: the traveller's own rows, never the examples'.
        val own = DemoTrips.withoutDemo(local)
        val (clear, points) = routes(own.trackPoints, incoming.trackPoints, shared)
        return MergePlan(
            trips = table(TRIPS, local.trips, incoming.trips, own.trips, shared, stamp),
            planItems = table(PLAN_ITEMS, local.planItems, incoming.planItems, own.planItems, shared, stamp),
            entries = table(ENTRIES, local.entries, incoming.entries, own.entries, shared, stamp),
            media = media(local.media, incoming.media, own.media, shared, stamp),
            trackPoints = points,
            stops = table(STOPS, local.stops, incoming.stops, own.stops, shared, stamp),
            countryMarks = table(COUNTRY_MARKS, local.countryMarks, incoming.countryMarks, own.countryMarks, shared, stamp),
            books = table(BOOKS, local.books, incoming.books, own.books, shared, stamp),
            clearRoutes = clear,
        )
    }

    /**
     * One table put back as the backup has it: the [incoming] rows that differ from the phone's ([local]), and the
     * [removable] rows the backup doesn't have, marked deleted. In a [shared] trip a row only comes in when it is new,
     * newer, or was deleted here (never another member's), and nothing is removed.
     */
    fun <T> table(t: Rows<T>, local: List<T>, incoming: List<T>, removable: List<T>, shared: Set<String>, stamp: (Long) -> Long): List<T> {
        fun together(row: T) = t.tripId(row)?.let { it in shared } == true
        val mine = local.associateBy(t.id)
        val theirs = incoming.associateBy(t.id)
        val out = ArrayList<T>()
        for (row in theirs.values) {
            val current = mine[t.id(row)]
            when {
                // New here, or later in the backup than here (it was made on another phone): as it is.
                current == null || t.updatedAt(row) > t.updatedAt(current) -> out += row
                // The same row already (an earlier restore of this backup only gave it a later stamp).
                t.at(row, t.updatedAt(current), t.deletedAt(row)) == current -> Unit
                // The backup's row, as a change made now. Shared trips: only what was deleted here comes back.
                !together(current) || (t.deletedAt(row) == null && t.deletedAt(current) != null && !t.foreign(current)) ->
                    out += t.at(row, stamp(t.updatedAt(current)), t.deletedAt(row))
            }
        }
        for (current in removable) {
            if (t.deletedAt(current) != null || t.id(current) in theirs || together(current)) continue
            val at = stamp(t.updatedAt(current))
            out += t.at(current, at, at)
        }
        return out
    }

    /** Incoming rows that are new to the phone or changed later than the phone's copy (shared trips merge this way). */
    fun <T> newer(local: List<T>, incoming: List<T>, id: (T) -> String, updatedAt: (T) -> Long): List<T> {
        val mine = local.associateBy(id)
        return incoming.associateBy(id).values.filter { row ->
            val current = mine[id(row)]
            current == null || updatedAt(row) > updatedAt(current)
        }
    }

    /** Photos go back like any table ([table]), after the backup's rows are made safe for this phone ([photos]). */
    fun media(
        local: List<Media>, incoming: List<Media>, removable: List<Media> = emptyList(), shared: Set<String> = emptySet(),
        stamp: (Long) -> Long = { Stamps.next(it) },
    ): List<Media> = table(MEDIA, local, photos(local, incoming), removable, shared, stamp)

    /**
     * The backup's photo rows for this phone: a stored path that would leave the app's media folder, or that is another
     * photo's here, is replaced by a safe one; and what the phone has learned about the same photo since the backup
     * (where it sits in Drive, its copy in a shared trip, its checksum) stays: it is a fact, not an edit.
     */
    fun photos(local: List<Media>, incoming: List<Media>): List<Media> {
        val mine = local.associateBy { it.id }
        val paths = local.associateBy { it.localPath }.toMutableMap()
        return incoming.associateBy { it.id }.values.map { raw ->
            var row = safePath(raw)
            var collision = 0
            while (paths[row.localPath]?.let { it.id != row.id && !sameContent(it, row, requireHash = true) } == true) {
                val suffix = digest("${row.id}:${collision++}")
                row = row.copy(localPath = "media/restored-$suffix.${replacementPath(row).substringAfterLast('.')}")
            }
            paths[row.localPath] = row
            val current = mine[row.id]
            if (current == null || !sameContent(row, current)) row
            else row.copy(
                driveFileId = row.driveFileId ?: current.driveFileId,
                sharedFileId = row.sharedFileId ?: current.sharedFileId,
                sha256 = row.sha256 ?: current.sha256,
            )
        }
    }

    private fun sameContent(a: Media, b: Media, requireHash: Boolean = false): Boolean =
        if (a.sha256 != null && b.sha256 != null) a.sha256.equals(b.sha256, ignoreCase = true)
        else !requireHash && a.localPath == b.localPath

    private data class PointKey(val tripId: String?, val time: Long, val lat: Double, val lng: Double)

    private fun key(p: TrackPoint) = PointKey(p.tripId, p.time, p.lat, p.lng)

    /**
     * The route put back as the backup has it, trip by trip (null: the points recorded outside trips). A trip whose
     * points differ from the backup's is cleared and takes the backup's; one with the same points is left alone. A
     * [shared] trip only gains the backup's points it lacks. Returns the trips to clear and the points to insert.
     * [local]: the traveller's own points (the examples' are never touched).
     */
    fun routes(local: List<TrackPoint>, incoming: List<TrackPoint>, shared: Set<String> = emptySet()): Pair<List<String?>, List<TrackPoint>> {
        val mine = local.groupBy { it.tripId }
        val theirs = incoming.groupBy { it.tripId }
        val clear = ArrayList<String?>()
        val add = ArrayList<TrackPoint>()
        for (trip in mine.keys + theirs.keys) {
            val here = mine[trip].orEmpty()
            val there = theirs[trip].orEmpty()
            if (trip != null && trip in shared) {
                add += trackPoints(here, there)
                continue
            }
            val wanted = there.distinctBy(::key)
            if (here.mapTo(HashSet(), ::key) == wanted.mapTo(HashSet(), ::key)) continue
            if (here.isNotEmpty()) clear += trip
            add += wanted.map { it.copy(id = 0) }
        }
        return clear to add
    }

    /** The [incoming] points the phone lacks. The same place and time in two trips keeps both. Database ids are reset. */
    fun trackPoints(local: List<TrackPoint>, incoming: List<TrackPoint>): List<TrackPoint> {
        val seen = local.mapTo(HashSet(local.size + incoming.size), ::key)
        return incoming.filter { seen.add(key(it)) }.map { it.copy(id = 0) }
    }

    /** Media paths stay "media/<file>" inside the app's files; anything else becomes "media/<id>.<ext>". */
    fun safePath(m: Media): Media {
        if (SAFE_PATH.matches(m.localPath) && ".." !in m.localPath) return m
        return m.copy(localPath = replacementPath(m))
    }

    /** A video keeps a video's extension ("media/<id>.mp4"), so it still plays after a restore ([MediaTypes.extension]). */
    private fun replacementPath(m: Media): String {
        val ext = MediaTypes.extension(m.mime)
        // Do not strip characters: different hostile/legacy IDs could collapse onto the same filename.
        val name = m.id.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,120}")) }
            ?: digest(m.id)
        return "media/$name.$ext"
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
