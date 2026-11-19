package com.justdataplease.boringtravel.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.justdataplease.boringtravel.time.LocalDate

/*
 * Room tables. Every syncable row has a String id (UUID), updatedAt for last-write-wins merges, and a soft
 * deletedAt, so Drive backups and the daily Firebase sync can merge without losing deletions.
 */

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey val id: String,
    val title: String,
    val subtitle: String = "",
    /** Null dates = a someday trip (ideas only). */
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val coverMediaId: String? = null,
    /** Per-trip override of the tracking level id (null = the app setting). */
    val trackingLevel: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /** Per-leg travel choices; pair keys and unknown future JSON fields are preserved across backups. */
    val travelModes: String? = null,
) {
    val days: Int? get() = if (startDate != null && endDate != null) (endDate.toEpochDay() - startDate.toEpochDay() + 1).toInt() else null

    fun isCurrent(today: LocalDate) = startDate != null && endDate != null && today.toEpochDay() in startDate.toEpochDay()..endDate.toEpochDay()
    fun isPast(today: LocalDate) = endDate != null && today.toEpochDay() > endDate.toEpochDay()
    fun dayNumber(today: LocalDate): Int? = startDate?.let { (today.toEpochDay() - it.toEpochDay() + 1).toInt() }

    /** An example trip (see [isDemoId]): tagged "Demo", never in the traveller's own numbers. */
    val isDemo: Boolean get() = isDemoId(id)

    companion object {
        /**
         * The example trips' ids: "sample-…" and "demo-…" (the traveller's own trips get UUIDs). Rows of these trips
         * (plan, entries, photos, stops) are the demo's too; edited or not, they stay examples.
         */
        fun isDemoId(id: String?): Boolean = id != null && (id.startsWith("sample-") || id.startsWith("demo-"))
    }
}

@Entity(
    tableName = "plan_items",
    indices = [Index("tripId"), Index("tripId", "day")],
)
data class PlanItem(
    @PrimaryKey val id: String,
    val tripId: String,
    /** Null = an idea not placed on a day yet. */
    val day: LocalDate? = null,
    /** Order within the day; doubles so an item can move between two others without renumbering. */
    val position: Double = 0.0,
    /** Minutes after midnight, local to the trip. */
    val timeMinutes: Int? = null,
    val kind: String = "place",
    val title: String,
    val note: String = "",
    val placeName: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val done: Boolean = false,
    val source: String = "manual",
    /** Id at the source (AI draft id, Tripadvisor location id later). */
    val externalId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /**
     * The place's own website, http(s) only; null = none. Since schema 5 (a Google Maps link kept here then moved to
     * [mapsUrl] in schema 9).
     */
    val url: String? = null,
    /**
     * When it ends (owner, 2026-11-12: "in each trip item add from date time to date time"): [endDays] days after
     * [day] (null or 0 = the same day) at [endMinutes] (minutes after midnight; null = no end time). A hotel from Thu
     * 20:30 to Sun 11:00 is endDays 3, endMinutes 660; a concert 19:00–22:00 endMinutes 1320. Kept relative to [day],
     * so moving the item or the trip moves its end too. See [com.justdataplease.boringtravel.model.PlanSpan]. Since
     * schema 7.
     */
    val endDays: Int? = null,
    val endMinutes: Int? = null,
    /** The end's own row name ("Check-out"); null = the item's title. */
    val endTitle: String? = null,
    /** The end as its own row in the plan and a stop in the clip; null = when it falls on another day. */
    val endRow: Boolean? = null,
    /**
     * The item's tags (owner, 2026-11-12: tags "on plan items only"; the clip shows them): each as
     * [com.justdataplease.boringtravel.tags.TagTree.normalize] keeps it, separated by single spaces
     * ("pizza streetfood"); null = none. Read and written through [com.justdataplease.boringtravel.tags.ItemTags].
     * Since schema 8.
     */
    val tags: String? = null,
    /**
     * The place's Google Maps link (owner, 2026-11-13: "add to a place a website and a google url manually"), http(s)
     * only; null = none. A Maps link never sits in [url]: [com.justdataplease.boringtravel.links.LinkPlaces.sortLinks]
     * moves it here. Since schema 9.
     */
    val mapsUrl: String? = null,
)

@Entity(
    tableName = "entries",
    indices = [Index("tripId"), Index("occurredAt")],
)
data class Entry(
    @PrimaryKey val id: String,
    val tripId: String? = null,
    /** When it happened (epoch ms) and the zone it was written in, for "Sat 19 Jun · Naxos" headers. */
    val occurredAt: Long,
    val zoneId: String,
    val title: String = "",
    /** The entry document as JSON (see doc/EntryDoc.kt). */
    val body: String = "",
    /** Plain text of the document, for search, previews and AI. */
    val plainText: String = "",
    val placeName: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val weather: String? = null,
    val tempC: Double? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /**
     * Who wrote it, in a shared trip: the member's id there (see together/). Null = written on this phone, by its
     * owner; only the author changes an entry. Since schema 6.
     */
    val authorId: String? = null,
)

/** Full-text index over entries (title and plain text), kept in step by Room's triggers. */
@Fts4(contentEntity = Entry::class)
@Entity(tableName = "entries_fts")
data class EntryFts(
    val title: String,
    val plainText: String,
)

@Entity(
    tableName = "media",
    indices = [Index("tripId"), Index("entryId")],
)
data class Media(
    @PrimaryKey val id: String,
    val tripId: String? = null,
    val entryId: String? = null,
    /** Path inside the app's files dir (files/media/...). */
    val localPath: String,
    val mime: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    /** Capture time from EXIF (epoch ms); used to match a photo to the route and a day. */
    val takenAt: Long? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    /** Id of the copy in the owner's Google Drive, once uploaded. */
    val driveFileId: String? = null,
    val sha256: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /** Who added it, in a shared trip (as [Entry.authorId]); null = this phone's owner. Since schema 6. */
    val authorId: String? = null,
    /**
     * The small copy the other members download, in its author's shared Drive folder (see together/). Not the backup
     * copy ([driveFileId], which only its own account can open). Since schema 6.
     */
    val sharedFileId: String? = null,
) {
    /**
     * A journal video (see data/repo/JournalVideos): a [com.justdataplease.boringtravel.doc.Block.Photo] like any
     * photo, told apart only by its type. Places that need a still picture use its first frame.
     */
    val isVideo: Boolean get() = mime.startsWith("video/")
}

/** Raw route points; never synced one by one (a day becomes one encoded line). */
@Entity(
    tableName = "track_points",
    indices = [Index("time"), Index("tripId")],
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String? = null,
    val time: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val altitudeM: Double? = null,
    /** Tracking level id that recorded it. */
    val level: String = "low",
)

/** A place the traveller stayed a while (from the points or added by hand). */
@Entity(
    tableName = "stops",
    indices = [Index("tripId"), Index("arrivedAt")],
)
data class Stop(
    @PrimaryKey val id: String,
    val tripId: String? = null,
    val arrivedAt: Long,
    val leftAt: Long? = null,
    val lat: Double,
    val lng: Double,
    val name: String = "",
    /** ISO 3166-1 alpha-2, for countries and flags in stats. */
    val countryCode: String? = null,
    val source: String = "auto",
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)
