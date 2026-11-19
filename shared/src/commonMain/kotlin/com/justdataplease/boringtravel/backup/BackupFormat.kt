package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.data.db.BookDraft
import com.justdataplease.boringtravel.data.db.CountryMark
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.justdataplease.boringtravel.io.IOException
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.parseLocalDateOrNull

/*
 * The backup zip. Format 1 (Drive backups): manifest.json (what is inside) and data.json (every row of every table,
 * soft-deleted rows too, so a restore also carries deletions). Photos are not in it: each is uploaded once to
 * BoringTravel/Photos and found again by Media.driveFileId. Format 2 (backup files, see BackupArchive) is the same two
 * files plus the photos in media/ and the trips' other files in extras/.
 *
 * The rows below mirror the Room tables but are their own types, so the database can change without breaking old
 * backups: new fields get defaults, unknown fields are ignored. docs/BACKUP-FORMAT.md lists every field and the rules;
 * BackupSchemaCoverageTest fails when a column has no field here.
 */

/** The format this app writes into a Drive backup's manifest and marker (BackupCodec.FORMAT on Android). */
const val BACKUP_FORMAT = 1

/** How data.json is written and read: unknown fields ignored, defaults written, nulls left out. */
val BackupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** A backup that can't be read: damaged, not a BoringTravel backup, or from a newer app. */
class BackupFormatException(message: String) : IOException(message)

/** Every row of every table, as Room entities. */
data class Snapshot(
    val trips: List<Trip> = emptyList(),
    val planItems: List<PlanItem> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val media: List<Media> = emptyList(),
    val trackPoints: List<TrackPoint> = emptyList(),
    val stops: List<Stop> = emptyList(),
    val countryMarks: List<CountryMark> = emptyList(),
    val books: List<BookDraft> = emptyList(),
) {
    /** Rows per table, deleted ones included. */
    fun tableCounts(): Map<String, Int> = linkedMapOf(
        "trips" to trips.size,
        "plan_items" to planItems.size,
        "entries" to entries.size,
        "media" to media.size,
        "track_points" to trackPoints.size,
        "stops" to stops.size,
        "country_marks" to countryMarks.size,
        "books" to books.size,
    )

    /**
     * What a person would count: trips, entries and photos that aren't deleted, and not in a deleted trip (a shared
     * trip left keeps its rows for joining again; trips deleted by older versions left theirs unmarked).
     */
    fun items(): BackupCounts {
        val gone = trips.filter { it.deletedAt != null }.mapTo(HashSet()) { it.id }
        return BackupCounts(
            trips = trips.count { it.deletedAt == null },
            entries = entries.count { it.deletedAt == null && it.tripId !in gone },
            photos = media.count { it.deletedAt == null && it.tripId !in gone },
        )
    }
}

@Serializable
data class BackupCounts(val trips: Int = 0, val entries: Int = 0, val photos: Int = 0)

@Serializable
data class BackupManifest(
    val format: Int = BACKUP_FORMAT,
    val app: String = "BoringTravel",
    val appVersion: String = "",
    /** Epoch ms. */
    val createdAt: Long = 0,
    /** Rows per table in data.json, deleted ones included. */
    val counts: Map<String, Int> = emptyMap(),
    /** Trips, entries and photos that aren't deleted, for "2 trips · 31 entries · 148 photos". */
    val items: BackupCounts = BackupCounts(),
    /** SHA-256 of the exact UTF-8 data.json bytes. Optional for older format-1 backups. */
    val dataSha256: String? = null,
)

/** The hidden marker in the app's Drive folder: the newest backup, so a new phone finds it with one small read. */
@Serializable
data class BackupMarker(
    val format: Int = BACKUP_FORMAT,
    val id: String,
    val name: String,
    val createdAt: Long,
    val items: BackupCounts = BackupCounts(),
    val appVersion: String = "",
)

/**
 * The last backup this phone wrote (kept in its settings, never in a backup): [sum] is the SHA-256 of its data.json,
 * so the next backup can tell that nothing changed since ([BackupManager]); [marker] says which backup it is.
 */
@Serializable
data class BackupStamp(val sum: String, val marker: BackupMarker)

@Serializable
data class BackupData(
    val trips: List<TripRow> = emptyList(),
    val planItems: List<PlanItemRow> = emptyList(),
    val entries: List<EntryRow> = emptyList(),
    val media: List<MediaRow> = emptyList(),
    val trackPoints: List<TrackPointRow> = emptyList(),
    val stops: List<StopRow> = emptyList(),
    /** Since app schema 2; older backups have none. */
    val countryMarks: List<CountryMarkRow> = emptyList(),
    /** Book layouts (Make a book), since app schema 4; older backups have none and older apps skip them. */
    val books: List<BookRow> = emptyList(),
    /** Portable preferences only; old backups omit this field. No credentials or device calendar IDs. */
    val preferences: BackupPreferences? = null,
    /**
     * Shared trips this phone takes part in (together/TogetherState as JSON), so a restored phone keeps syncing them
     * as the same member; added 2026-11-11, older backups omit it.
     */
    val sharedTrips: String? = null,
    /**
     * The saved trip videos on the phone when the backup was made (their files are in BoringTravel › Videos, or in a
     * backup file's extras/clips/): a restore brings back these. Added 2026-11-11; null in older backups.
     */
    val savedClips: List<ClipRow>? = null,
    /**
     * Each trip's video scene edits (the text of files/clip-edits/<trip>.json), by trip id, so a Drive backup holds
     * the editor's work too. Added 2026-11-11; null in older backups. A restore writes one only where the phone has none.
     */
    val clipEdits: Map<String, String>? = null,
)

/** A saved trip video as the Media tab lists it (data/SavedClip); the video itself is a file, not a row. */
@Serializable
data class ClipRow(
    val id: String,
    val tripId: String,
    val title: String = "",
    val createdAt: Long = 0,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class BackupPreferences(
    val look: String? = null,
    val fontSet: String? = null,
    val printTheme: String? = null,
    val dictationLanguage: String? = null,
    val autoBackup: Boolean? = null,
    val wifiOnly: Boolean? = null,
    /** Me › Trips look (added 2026-11-07; older backups omit it). */
    val tripsLook: String? = null,
    /** Me › Fonts › Text (added 2026-11-07; older backups omit it). */
    val textFont: String? = null,
    /** Me › Place photos (added 2026-11-07; older backups omit it, and the phone keeps its own). */
    val placePhotos: Boolean? = null,
    /** Me › Plan view (added 2026-11-07; older backups omit it). "timeline" in older ones: the List, expanded. */
    val planView: String? = null,
    /** The Plan tab's List expanded or collapsed (added 2026-11-07; older backups omit it). */
    val planExpanded: Boolean? = null,
    /** Me › Your traveller, as [com.justdataplease.boringtravel.clip.Traveller.encode] (added 2026-11-08; older backups omit it). */
    val traveller: String? = null,
    /** The rides of Me › Your traveller & rides, as [com.justdataplease.boringtravel.clip.Rides.encode] (added 2026-11-08; older backups omit it). */
    val rides: String? = null,
    /**
     * The trip video's remembered choices, each one of the phone's small settings files as [PrefsBackup] text: speed,
     * length, shape and style; the map look; the music. Added 2026-11-11; older backups omit them.
     */
    val clipStyle: String? = null,
    val mapStyle: String? = null,
    val clipMusic: String? = null,
    /**
     * More of the app's remembered choices (owner, 2026-11-14: "in backup add some more settings … default settings we
     * have at the whole app, even in clip book print"); older backups omit them. The Map tab's view (globe or map).
     */
    val mapView: String? = null,
    /** The Share screen's photo size, paper, notebook paper (ruled) and "Include the plan" (data/settings/PrintChoices). */
    val printPhotos: String? = null,
    val printPaper: String? = null,
    val printRuled: Boolean? = null,
    val printPlan: Boolean? = null,
    /** Each trip's defaults for new photos in its notes, as [com.justdataplease.boringtravel.data.settings.TripPhotoStyles.encode]. */
    val tripPhotos: String? = null,
)

@Serializable
data class TripRow(
    val id: String,
    val title: String = "",
    val subtitle: String = "",
    /** ISO dates, "2027-06-17". */
    val startDate: String? = null,
    val endDate: String? = null,
    val coverMediaId: String? = null,
    val trackingLevel: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    val travelModes: String? = null,
)

@Serializable
data class PlanItemRow(
    val id: String,
    val tripId: String,
    val day: String? = null,
    val position: Double = 0.0,
    val timeMinutes: Int? = null,
    val kind: String = "place",
    val title: String = "",
    val note: String = "",
    val placeName: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val done: Boolean = false,
    val source: String = "manual",
    val externalId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    /** The item's link, since app schema 5; older backups have none and older apps skip it. */
    val url: String? = null,
    /** When it ends (days after [day], minutes), its end row's name and whether that row shows; since app schema 7. */
    val endDays: Int? = null,
    val endMinutes: Int? = null,
    val endTitle: String? = null,
    val endRow: Boolean? = null,
    /** The item's tags, space separated; since app schema 8. */
    val tags: String? = null,
    /**
     * The place's Google Maps link, since app schema 9; older backups kept it in [url] ([BackupSafety.clean] moves
     * it). Keep it last (positional).
     */
    val mapsUrl: String? = null,
)

@Serializable
data class EntryRow(
    val id: String,
    val tripId: String? = null,
    val occurredAt: Long = 0,
    val zoneId: String = "UTC",
    val title: String = "",
    val body: String = "",
    val plainText: String = "",
    val placeName: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val weather: String? = null,
    val tempC: Double? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    /** The entry's author in a shared trip, since app schema 6; older backups have none. Keep it last (positional). */
    val authorId: String? = null,
)

@Serializable
data class MediaRow(
    val id: String,
    val tripId: String? = null,
    val entryId: String? = null,
    val localPath: String = "",
    val mime: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val takenAt: Long? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val driveFileId: String? = null,
    val sha256: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    /** Shared trips, since app schema 6; older backups have neither. Keep them last (positional). */
    val authorId: String? = null,
    val sharedFileId: String? = null,
)

/** Route points have no stable id (the table numbers them); a restore matches them by time and place. */
@Serializable
data class TrackPointRow(
    val tripId: String? = null,
    val time: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val altitudeM: Double? = null,
    val level: String = "low",
)

@Serializable
data class StopRow(
    val id: String,
    val tripId: String? = null,
    val arrivedAt: Long = 0,
    val leftAt: Long? = null,
    val lat: Double,
    val lng: Double,
    val name: String = "",
    val countryCode: String? = null,
    val source: String = "auto",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
)

@Serializable
data class CountryMarkRow(
    val id: String,
    val code: String,
    val source: String = CountryMark.SOURCE_MANUAL,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
)

/** A book draft: its pages stay the book's own JSON, carried as text. */
@Serializable
data class BookRow(
    val id: String,
    val tripId: String,
    val format: String = "a5",
    val theme: String = "notebook",
    val fontSet: String = "theme",
    val bleed: Boolean = false,
    val pages: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
)

/*
 * One mapping per table between Room and the portable rows, used by backups and by shared trips' files
 * (together/TogetherMerge), so a new column can't be carried in one and silently reset in the other.
 */

fun Trip.toRow() = TripRow(id, title, subtitle, startDate?.toString(), endDate?.toString(), coverMediaId, trackingLevel, createdAt, updatedAt, deletedAt, travelModes)

fun TripRow.toTrip() = Trip(id, title, subtitle, isoDay(startDate), isoDay(endDate), coverMediaId, trackingLevel, createdAt, updatedAt, deletedAt, travelModes)

fun PlanItem.toRow() = PlanItemRow(
    id, tripId, day?.toString(), position, timeMinutes, kind, title, note, placeName, lat, lng,
    done, source, externalId, createdAt, updatedAt, deletedAt, url, endDays, endMinutes, endTitle, endRow, tags, mapsUrl,
)

fun PlanItemRow.toItem() = PlanItem(
    id, tripId, isoDay(day), position, timeMinutes, kind, title, note, placeName, lat, lng,
    done, source, externalId, createdAt, updatedAt, deletedAt, url, endDays, endMinutes, endTitle, endRow, tags, mapsUrl,
)

fun Entry.toRow() = EntryRow(
    id, tripId, occurredAt, zoneId, title, body, plainText, placeName, lat, lng, weather, tempC,
    createdAt, updatedAt, deletedAt, authorId,
)

fun EntryRow.toEntry() = Entry(
    id, tripId, occurredAt, zoneId, title, body, plainText, placeName, lat, lng, weather, tempC,
    createdAt, updatedAt, deletedAt, authorId,
)

fun Snapshot.toData(preferences: BackupPreferences? = null) = BackupData(
    trips = trips.map { it.toRow() },
    planItems = planItems.map { it.toRow() },
    entries = entries.map { it.toRow() },
    media = media.map {
        MediaRow(
            it.id, it.tripId, it.entryId, it.localPath, it.mime, it.width, it.height, it.takenAt, it.lat, it.lng, it.driveFileId, it.sha256,
            it.createdAt, it.updatedAt, it.deletedAt, it.authorId, it.sharedFileId,
        )
    },
    trackPoints = trackPoints.map { TrackPointRow(it.tripId, it.time, it.lat, it.lng, it.accuracyM, it.speedMps, it.altitudeM, it.level) },
    stops = stops.map { StopRow(it.id, it.tripId, it.arrivedAt, it.leftAt, it.lat, it.lng, it.name, it.countryCode, it.source, it.createdAt, it.updatedAt, it.deletedAt) },
    countryMarks = countryMarks.map { CountryMarkRow(it.id, it.code, it.source, it.addedAt, it.updatedAt, it.deletedAt) },
    books = books.map { BookRow(it.id, it.tripId, it.format, it.theme, it.fontSet, it.bleed, it.pages, it.createdAt, it.updatedAt, it.deletedAt) },
    preferences = preferences,
)

/** Back to Room entities. Route points get id 0, so the table numbers them on insert. */
fun BackupData.toSnapshot() = Snapshot(
    trips = trips.map { it.toTrip() },
    planItems = planItems.map { it.toItem() },
    entries = entries.map { it.toEntry() },
    media = media.map {
        Media(
            it.id, it.tripId, it.entryId, it.localPath, it.mime, it.width, it.height, it.takenAt, it.lat, it.lng, it.driveFileId, it.sha256,
            it.createdAt, it.updatedAt, it.deletedAt, it.authorId, it.sharedFileId,
        )
    },
    trackPoints = trackPoints.map { TrackPoint(0, it.tripId, it.time, it.lat, it.lng, it.accuracyM, it.speedMps, it.altitudeM, it.level) },
    stops = stops.map { Stop(it.id, it.tripId, it.arrivedAt, it.leftAt, it.lat, it.lng, it.name, it.countryCode, it.source, it.createdAt, it.updatedAt, it.deletedAt) },
    countryMarks = countryMarks.map { CountryMark(it.id, it.code.uppercase(), it.source, it.addedAt, it.updatedAt, it.deletedAt) },
    books = books.map { BookDraft(it.id, it.tripId, it.format, it.theme, it.fontSet, it.bleed, it.pages, it.createdAt, it.updatedAt, it.deletedAt) },
)

private fun isoDay(iso: String?): LocalDate? = iso?.let {
    parseLocalDateOrNull(it) ?: throw BackupFormatException("This backup contains an invalid date and can't be restored.")
}
