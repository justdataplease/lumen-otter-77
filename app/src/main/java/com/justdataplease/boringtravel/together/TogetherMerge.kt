package com.justdataplease.boringtravel.together

import com.justdataplease.boringtravel.backup.Snapshot
import com.justdataplease.boringtravel.backup.BackupSafety
import com.justdataplease.boringtravel.backup.BackupMerge
import com.justdataplease.boringtravel.backup.EntryRow
import com.justdataplease.boringtravel.backup.PlanItemRow
import com.justdataplease.boringtravel.backup.TripRow
import com.justdataplease.boringtravel.backup.toEntry
import com.justdataplease.boringtravel.backup.toItem
import com.justdataplease.boringtravel.backup.toRow
import com.justdataplease.boringtravel.backup.toTrip
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.MediaTypes
import java.security.MessageDigest

/** One trip's rows on this phone, deleted ones included. */
data class TripRows(
    val trip: Trip?,
    val planItems: List<PlanItem> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val media: List<Media> = emptyList(),
)

/** What merging another member's file changes on this phone. */
data class TogetherPlan(
    val trip: Trip? = null,
    val planItems: List<PlanItem> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val media: List<Media> = emptyList(),
) {
    val isEmpty: Boolean get() = trip == null && planItems.isEmpty() && entries.isEmpty() && media.isEmpty()
}

/**
 * How a shared trip's rows travel between members. Plain Kotlin, unit-tested.
 *
 * - Rows match by id and the later `updatedAt` wins; a tie keeps the phone's row. Deletions are rows too.
 * - Only rows of this trip: an incoming row whose id belongs to another trip on this phone is ignored ([elsewhere]).
 * - The trip is never deleted or hidden by someone else: deleting a shared trip means leaving it, on one phone.
 * - Entries and photos keep their author: a change that would give one to someone else is ignored. On this phone the
 *   owner's own rows have no author ([me] in the files).
 * - A photo's file on this phone (path, checksum, backup copy) is this phone's; only what describes the photo
 *   travels, and whether it is a video (its type: a video's file keeps its type and gets a video's path here).
 * - A deleted row travels as a bare tombstone (id, times, author): the file is link-shared, so nothing deleted stays
 *   readable in it. On a phone that has the row, a tombstone only marks it deleted.
 */
object TogetherMerge {
    /** This phone's rows of [tripId] as the file the other members read. [videoBytes]: a video's size, when known. */
    fun file(rows: TripRows, me: String, members: List<Member>, writtenAt: Long, videoBytes: (Media) -> Long = { 0 }): ShareFile {
        val trip = requireNotNull(rows.trip) { "No trip to share" }
        // A place that can't be on Earth or a number that isn't one (a photo's damaged GPS tag) can't be written, and
        // the others would refuse the whole file: such a row goes without it, as in a backup.
        val clean = BackupSafety.clean(Snapshot(planItems = rows.planItems, entries = rows.entries, media = rows.media))
        return ShareFile(
            writer = me,
            writtenAt = writtenAt,
            members = members,
            // This phone's tracking level and its own deletion (leaving) stay here.
            trip = trip.toRow().copy(trackingLevel = null, deletedAt = null),
            planItems = clean.planItems.filter { it.tripId == trip.id }.map {
                if (it.deletedAt != null) PlanItemRow(id = it.id, tripId = it.tripId, updatedAt = it.updatedAt, deletedAt = it.deletedAt) else it.toRow()
            },
            entries = clean.entries.filter { it.tripId == trip.id }.map {
                val author = it.authorId ?: me
                if (it.deletedAt != null) EntryRow(id = it.id, tripId = it.tripId, updatedAt = it.updatedAt, deletedAt = it.deletedAt, authorId = author)
                else it.toRow().copy(authorId = author)
            },
            photos = clean.media.filter { it.tripId == trip.id }.map {
                val author = it.authorId ?: me
                if (it.deletedAt != null) SharedPhoto(id = it.id, authorId = author, updatedAt = it.updatedAt, deletedAt = it.deletedAt)
                else SharedPhoto(
                    id = it.id, entryId = it.entryId, fileId = it.sharedFileId, width = it.width, height = it.height, takenAt = it.takenAt,
                    lat = it.lat, lng = it.lng, authorId = author, createdAt = it.createdAt, updatedAt = it.updatedAt,
                    // A photo's copy is always a small JPEG; a video goes as it is.
                    mime = if (it.isVideo) it.mime else MediaTypes.JPEG,
                    bytes = if (it.isVideo) videoBytes(it).coerceAtLeast(0) else 0,
                )
            },
        )
    }

    /**
     * The last file of a member who left [tripId]: only their own line, marked left, and no rows, so the others see
     * them go and nothing of the trip stays readable by link.
     */
    fun farewell(tripId: String, me: Member, writtenAt: Long): ShareFile =
        ShareFile(writer = me.id, writtenAt = writtenAt, members = listOf(me.copy(left = true, seenAt = 0)), trip = TripRow(id = tripId))

    /** True when [file] lists this phone ([me]) with its current file ([fileId]): its writer reads this phone's changes. */
    fun lists(file: ShareFile, me: String, fileId: String?): Boolean =
        fileId != null && file.members.any { it.id == me && it.fileId == fileId && !it.left }

    /**
     * A cheap fingerprint of what [file] would hold for [rows] and [members]: each row's id and stamp (every change to a
     * row moves its stamp forward) plus the photos' shared copies, which can arrive without a new stamp.
     */
    fun fingerprint(rows: TripRows, members: List<Member>): String {
        val d = MessageDigest.getInstance("SHA-256")
        fun add(vararg parts: Any?) {
            parts.forEach { d.update(it.toString().toByteArray(Charsets.UTF_8)); d.update(0.toByte()) }
            d.update(1.toByte())
        }
        rows.trip?.let { add(it.id, it.updatedAt) }
        members.forEach { add(it.id, it.name, it.fileId, it.left) }
        rows.planItems.sortedBy { it.id }.forEach { add(it.id, it.updatedAt) }
        rows.entries.sortedBy { it.id }.forEach { add(it.id, it.updatedAt, it.authorId) }
        rows.media.sortedBy { it.id }.forEach { add(it.id, it.updatedAt, it.authorId, it.sharedFileId) }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /** The changes [incoming] brings to this phone's [local] rows. [elsewhere]: ids that belong to other trips here. */
    fun plan(local: TripRows, incoming: ShareFile, me: String, elsewhere: Set<String> = emptySet()): TogetherPlan {
        val tripId = incoming.trip.id
        require(local.trip == null || local.trip.id == tripId) { "Different trip" }
        fun author(id: String): String? = id.takeUnless { it == me }

        val trip = trip(local.trip, incoming.trip)

        val mineItems = local.planItems.associateBy { it.id }
        val items = BackupMerge.newer(
            local.planItems,
            incoming.planItems.filter { it.tripId == tripId && it.id !in elsewhere }.map { it.toItem() },
            { it.id }, { it.updatedAt },
        ).map { row -> mineItems[row.id]?.takeIf { row.deletedAt != null }?.copy(updatedAt = row.updatedAt, deletedAt = row.deletedAt) ?: row }

        val mineEntries = local.entries.associateBy { it.id }
        val entries = incoming.entries.filter { it.tripId == tripId && it.id !in elsewhere }.mapNotNull { row ->
            val e = row.toEntry().copy(authorId = author(requireNotNull(row.authorId)))
            val current = mineEntries[e.id]
            when {
                current == null -> e
                current.authorId != e.authorId -> null
                e.updatedAt > current.updatedAt && e.deletedAt != null -> current.copy(updatedAt = e.updatedAt, deletedAt = e.deletedAt)
                e.updatedAt > current.updatedAt -> e
                else -> null
            }
        }

        val mineMedia = local.media.associateBy { it.id }
        val media = incoming.photos.filter { it.id !in elsewhere }.mapNotNull { p ->
            val by = author(p.authorId)
            val mime = MediaTypes.shared(p.mime)
            val known = mineMedia[p.id]
            // Another member's video an older app here took for a photo: a video now, fetched again into a video's path.
            val current = known?.takeIf { by != null && it.authorId == by && MediaTypes.isVideo(mime) && !it.isVideo }
                ?.copy(mime = mime, localPath = sharedPath(p.id, mime)) ?: known
            when {
                current == null -> Media(
                    id = p.id, tripId = tripId, entryId = p.entryId, localPath = sharedPath(p.id, mime), mime = mime,
                    width = p.width, height = p.height, takenAt = p.takenAt, lat = p.lat, lng = p.lng,
                    createdAt = p.createdAt, updatedAt = p.updatedAt, deletedAt = p.deletedAt, authorId = by, sharedFileId = p.fileId,
                )
                current.authorId != by -> null
                p.updatedAt > current.updatedAt && p.deletedAt != null -> current.copy(updatedAt = p.updatedAt, deletedAt = p.deletedAt)
                p.updatedAt > current.updatedAt -> current.copy(
                    entryId = p.entryId, width = p.width, height = p.height, takenAt = p.takenAt, lat = p.lat, lng = p.lng,
                    updatedAt = p.updatedAt, deletedAt = p.deletedAt, sharedFileId = p.fileId ?: current.sharedFileId,
                )
                // The author uploaded the small copy after the last change: learn where it is without a new stamp.
                p.updatedAt == current.updatedAt && current.sharedFileId == null && p.fileId != null && by != null -> current.copy(sharedFileId = p.fileId)
                current !== known -> current
                else -> null
            }
        }
        return TogetherPlan(trip, items, entries, media)
    }

    /**
     * The members after reading [file]: its writer's own line is the truth about them (name, file, left); anyone else
     * it lists that this phone doesn't know yet is added. Nobody is removed. [me] is never changed from outside.
     */
    fun members(known: List<Member>, file: ShareFile, me: String): List<Member> {
        val out = LinkedHashMap<String, Member>()
        known.forEach { out[it.id] = it }
        file.members.firstOrNull { it.id == file.writer && it.id != me }?.let { w ->
            val was = out[w.id]
            out[w.id] = w.copy(
                name = ShareCodec.cleanName(w.name).ifBlank { was?.name.orEmpty() },
                fileId = w.fileId ?: was?.fileId,
                seenAt = maxOf(file.writtenAt, was?.seenAt ?: 0),
            )
        }
        file.members.filter { it.id != file.writer && it.id != me && it.id !in out }.take(ShareCodec.MAX_MEMBERS).forEach {
            out[it.id] = it.copy(name = ShareCodec.cleanName(it.name))
        }
        // The owner is one of the [ShareCodec.MAX_MEMBERS] a file may list.
        return out.values.take(ShareCodec.MAX_MEMBERS - 1)
    }

    /** Where another member's photo or video ([mime]) lives on this phone (the app's media folder, like every photo). */
    fun sharedPath(id: String, mime: String = MediaTypes.JPEG): String = "media/shared-$id.${MediaTypes.extension(mime)}"

    private fun trip(local: Trip?, row: TripRow): Trip? {
        // Only what describes the trip travels: this phone's tracking level and its own deletion (leaving) stay.
        val theirs = row.toTrip().copy(trackingLevel = local?.trackingLevel, deletedAt = local?.deletedAt)
        if (local == null) return theirs
        if (row.updatedAt <= local.updatedAt) return null
        return theirs.copy(createdAt = local.createdAt).takeIf { it != local }
    }
}
