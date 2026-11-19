package com.justdataplease.boringtravel.data.repo

import com.justdataplease.boringtravel.model.PlanSpan
import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.RouteLegSource
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.data.db.BtDatabase
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.DemoTrips
import com.justdataplease.boringtravel.data.TripRemoval
import com.justdataplease.boringtravel.data.DemoTrips.visibleIn
import com.justdataplease.boringtravel.doc.DocRender
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.links.MapsPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Now, on the clock rows are stamped with (see [Stamps]). */
fun now(): Long = Stamps.now()

/**
 * [demoMode]: Me › Demo mode. The lists here show one side only (see [DemoTrips.visible]): the example trips and their
 * rows in demo mode, else the traveller's own; nothing hidden is changed. [onNewOwnTrip] runs when a trip of the
 * traveller's own is made while in demo mode (demo mode ends).
 */
class TripRepository(
    private val db: BtDatabase,
    private val demoMode: Flow<Boolean> = flowOf(false),
    private val onNewOwnTrip: suspend () -> Unit = {},
) {
    fun observeTrips(): Flow<List<Trip>> = db.trips().observeAll().visibleIn(demoMode) { it.id }
    fun observeTrip(id: String): Flow<Trip?> = db.trips().observe(id)
    suspend fun get(id: String): Trip? = db.trips().get(id)

    /** Every trip, the traveller's own and the examples, in either mode (Add to plan picks among them). */
    fun observeEvery(): Flow<List<Trip>> = db.trips().observeAll()

    /** The traveller's own trips in either mode (route tracking keeps recording for them). */
    fun observeOwnTrips(): Flow<List<Trip>> = db.trips().observeAll().map { l -> l.filterNot { it.isDemo } }

    /**
     * The trip on [day] on the side on show (the one that started last): in demo mode the demo, so what is written
     * there is demo data. New entries, photos, the check-in and the widgets follow it.
     */
    suspend fun currentOn(day: LocalDate): Trip? {
        val demo = demoMode.first()
        return db.trips().allOn(day.toEpochDay()).firstOrNull { DemoTrips.visible(it.id, demo) }
    }

    /** The traveller's own trip on [day], in either mode: the one route tracking records for. */
    suspend fun ownCurrentOn(day: LocalDate): Trip? = db.trips().allOn(day.toEpochDay()).firstOrNull { !it.isDemo }

    suspend fun create(title: String, start: LocalDate?, end: LocalDate?, subtitle: String = ""): Trip {
        val t = now()
        val trip = Trip(newId(), title.trim(), subtitle.trim(), start, end, createdAt = t, updatedAt = t)
        db.trips().upsert(trip)
        if (DemoTrips.leavesDemo(demoMode.first(), trip, existed = false)) onNewOwnTrip()
        return trip
    }

    /**
     * A new trip with its plan in one go (a trip idea made the traveller's own, see programs/ProgramTrips). Like any new
     * trip of the traveller's own, it ends demo mode.
     */
    suspend fun createWithPlan(trip: Trip, items: List<PlanItem>): Trip {
        db.withTransaction {
            db.trips().upsert(trip)
            db.planItems().upsertAll(items)
        }
        if (DemoTrips.leavesDemo(demoMode.first(), trip, existed = false)) onNewOwnTrip()
        return trip
    }

    /**
     * Saves [trip] as a screen edited it (name, dates, cover): the stored travel choices and day names stay, since the
     * screen's copy of them may be old. [shiftDays]: the trip's start moved by so many days and its items with it, so
     * the day names move too ([DayNames.shift]).
     */
    suspend fun save(trip: Trip, shiftDays: Long = 0) {
        val existed = db.withTransaction {
            val current = db.trips().get(trip.id)
            val kept = TripLegUpdates.preserveChoices(trip, current)
            db.trips().upsert(kept.copy(travelModes = DayNames.shift(kept.travelModes, shiftDays), updatedAt = Stamps.next(current?.updatedAt ?: 0)))
            current != null
        }
        if (DemoTrips.leavesDemo(demoMode.first(), trip, existed)) onNewOwnTrip()
    }

    /**
     * Saves [trip] with its travel choices and day names as given: for a caller that worked them out from the row as
     * it is now, inside its own transaction (the planner's reviewed changes). [save] would put the stored ones back.
     */
    suspend fun saveWithChoices(trip: Trip) {
        val existed = db.withTransaction {
            val current = db.trips().get(trip.id)
            db.trips().upsert(trip.copy(updatedAt = Stamps.next(current?.updatedAt ?: 0)))
            current != null
        }
        if (DemoTrips.leavesDemo(demoMode.first(), trip, existed)) onNewOwnTrip()
    }

    /**
     * Names [day] of trip [tripId], or with a blank [name] takes its name away so it shows its place again
     * ([DayNames]). Read and written in one transaction; the trip as it is afterwards, null when it is gone.
     */
    suspend fun setDayName(tripId: String, day: LocalDate, name: String?): Trip? = db.withTransaction {
        val current = db.trips().get(tripId)?.takeIf { it.deletedAt == null } ?: return@withTransaction null
        val json = DayNames.set(current.travelModes, day, name)
        if (json == current.travelModes) current
        else current.copy(travelModes = json, updatedAt = Stamps.next(current.updatedAt)).also { db.trips().upsert(it) }
    }

    suspend fun setLegVehicle(tripId: String, key: RouteLegKey, vehicle: Vehicle?): Trip? = TripLegUpdates.set(
        key = key, vehicle = vehicle,
        transaction = { work -> db.withTransaction { work() } },
        read = { db.trips().get(tripId) }, activeKeys = { activeLegKeys(tripId, key.source, it) },
        write = { db.trips().upsert(it) }, clock = ::now,
    )

    /** Whether the leg [key] adds to the trip's km (null: the default, every leg but a flight). */
    suspend fun setLegKm(tripId: String, key: RouteLegKey, count: Boolean?): Trip? = TripLegUpdates.setKm(
        key = key, count = count,
        transaction = { work -> db.withTransaction { work() } },
        read = { db.trips().get(tripId) }, activeKeys = { activeLegKeys(tripId, key.source, it) },
        write = { db.trips().upsert(it) }, clock = ::now,
    )

    private suspend fun activeLegKeys(tripId: String, source: RouteLegSource, current: Trip): Set<RouteLegKey> = when (source) {
        RouteLegSource.Planned -> RouteLegs.planned(current, db.planItems().forTrip(tripId))
        RouteLegSource.Stops -> RouteLegs.savedStops(current, db.stops().forTrip(tripId))
    }.map { it.key }.toSet()

    /** Planned places per trip id (notes left out). */
    fun observePlannedCounts(): Flow<Map<String, Int>> = db.planItems().observePlannedCounts().map { l -> l.associate { it.tripId to it.n } }

    /** Journal entries per trip id. */
    fun observeEntryCounts(): Flow<Map<String, Int>> = db.entries().observeCountsByTrip().map { l -> l.associate { it.tripId to it.n } }

    /**
     * Marks only the trip's row deleted: what leaving a shared trip does (its rows stay, so joining again brings it
     * back; see together/). Deleting a trip of the traveller's own is [deleteWithContents].
     */
    suspend fun delete(id: String) = db.withTransaction {
        val trip = db.trips().get(id) ?: return@withTransaction
        val t = Stamps.next(trip.updatedAt)
        db.trips().upsert(trip.copy(deletedAt = t, updatedAt = t))
    }

    /**
     * Deletes trip [id] with everything in it, in one go ([TripRemoval.mark]): plan, journal, photos, stops and books
     * marked deleted (so backups carry the deletion), the recorded route removed. Returns the photos it deleted, whose
     * files the caller frees ([MediaRepository.freeFiles]). Never for a shared trip: that is left.
     */
    suspend fun deleteWithContents(id: String): List<Media> = db.withTransaction {
        val trip = db.trips().get(id) ?: return@withTransaction emptyList()
        val entries = db.entries().forTripAll(id)
        val media = db.media().forTripAll(id) + entries.map { it.id }.chunked(900).flatMap { db.media().forEntries(it) }
        val marked = TripRemoval.mark(trip, db.planItems().forTripAll(id), entries, media, db.stops().forTripWithDeleted(id)) { Stamps.next(it) }
        db.planItems().upsertAll(marked.items)
        db.entries().upsertAll(marked.entries)
        db.media().upsertAll(marked.media)
        db.stops().upsertAll(marked.stops)
        db.books().deleteForTrip(id, marked.trip.updatedAt)
        db.tracks().deleteForTrip(id)
        db.trips().upsert(marked.trip)
        marked.media
    }

    /**
     * An example trip's new start: the whole demo moves by the same number of days (dates, plan days, entry and photo
     * times, stays; see [DemoTrips.shift]), then [edit] (name, route, cover) is applied. The traveller's own trips
     * never move this way: their entries keep their times.
     */
    suspend fun moveDemo(id: String, start: LocalDate, edit: (Trip) -> Trip = { it }) = db.withTransaction {
        val trip = db.trips().get(id)?.takeIf { it.isDemo } ?: return@withTransaction
        val days = DemoTrips.delta(trip, start)
        val moved = DemoTrips.shift(
            DemoTrips.Rows(trip, db.planItems().forTrip(id), db.entries().forTrip(id), db.media().forTrip(id), db.stops().forTrip(id)),
            days, ZoneId.systemDefault(),
        )
        val t = Stamps.next(trip.updatedAt)
        if (days != 0L) {
            db.planItems().upsertAll(moved.items.map { it.copy(updatedAt = t) })
            db.entries().upsertAll(moved.entries.map { it.copy(updatedAt = t) })
            db.media().upsertAll(moved.media.map { it.copy(updatedAt = t) })
            db.stops().upsertAll(moved.stops.map { it.copy(updatedAt = t) })
        }
        // The travel choices as stored (read just above), the day names moved with the days.
        db.trips().upsert(edit(moved.trip).copy(travelModes = moved.trip.travelModes, updatedAt = t))
    }
}

class PlanRepository(private val db: BtDatabase) {
    fun observeItems(tripId: String): Flow<List<PlanItem>> = db.planItems().observeForTrip(tripId)
    suspend fun items(tripId: String): List<PlanItem> = db.planItems().forTrip(tripId)
    suspend fun get(id: String): PlanItem? = db.planItems().get(id)

    suspend fun add(tripId: String, title: String, day: LocalDate?, kind: String = "place", timeMinutes: Int? = null, note: String = ""): PlanItem {
        val siblings = db.planItems().forTrip(tripId).filter { it.day == day }
        val t = now()
        val item = PlanItem(
            id = newId(), tripId = tripId, day = day, position = (siblings.maxOfOrNull { it.position } ?: 0.0) + 1.0,
            timeMinutes = timeMinutes, kind = kind, title = title.trim(), note = note, createdAt = t, updatedAt = t,
        )
        db.planItems().upsert(item)
        return item
    }

    /**
     * Saves a change: stamped now, and never earlier than the row it replaces (merges keep the later change); its end
     * tidied ([PlanSpan.normalized]) whoever wrote it.
     */
    suspend fun save(item: PlanItem) = db.planItems().upsert(PlanSpan.normalized(item).copy(updatedAt = Stamps.next(item.updatedAt)))
    suspend fun saveAll(items: List<PlanItem>) = db.planItems().upsertAll(items.map { PlanSpan.normalized(it).copy(updatedAt = Stamps.next(it.updatedAt)) })
    suspend fun setDone(item: PlanItem, done: Boolean) = save(item.copy(done = done))
    suspend fun delete(item: PlanItem) = save(item.copy(deletedAt = now()))

    /**
     * Gives item [id] the place behind its Google Maps link [url] (a short link answers only after saving), if the
     * item still has that link and no place of its own by then.
     */
    suspend fun fillFromLink(id: String, url: String, place: MapsPlace) = db.withTransaction {
        val current = db.planItems().get(id)?.takeIf { it.deletedAt == null && it.mapsUrl == url } ?: return@withTransaction
        val filled = LinkPlaces.fillLater(current, url, place)
        if (filled != current) db.planItems().upsert(filled.copy(updatedAt = Stamps.next(current.updatedAt)))
    }
}

class JournalRepository(private val db: BtDatabase, private val demoMode: Flow<Boolean> = flowOf(false)) {
    fun observeEntries(): Flow<List<Entry>> = db.entries().observeAll().visibleIn(demoMode) { it.tripId }
    fun observeForTrip(tripId: String): Flow<List<Entry>> = db.entries().observeForTrip(tripId)
    fun observeEntry(id: String): Flow<Entry?> = db.entries().observe(id)
    suspend fun get(id: String): Entry? = db.entries().get(id)
    suspend fun forTrip(tripId: String): List<Entry> = db.entries().forTrip(tripId)

    /** Entries on show written between [from] and [to] (epoch ms): "2 written today". */
    suspend fun visibleCountBetween(from: Long, to: Long): Int {
        val demo = demoMode.first()
        return db.entries().tripIdsBetween(from, to).count { DemoTrips.visible(it.ifEmpty { null }, demo) }
    }

    /** Saves the entry with [doc] as its body; keeps the plain-text copy for search in step. */
    suspend fun save(entry: Entry, doc: EntryDoc) {
        // The editor saves from the main thread as you type; a long note's JSON and plain text are made off it.
        val (body, plain) = withContext(Dispatchers.Default) { doc.toJson() to DocRender.plainText(doc) }
        db.entries().upsert(entry.copy(body = body, plainText = plain, updatedAt = Stamps.next(entry.updatedAt)))
    }

    suspend fun save(entry: Entry) = db.entries().upsert(entry.copy(updatedAt = Stamps.next(entry.updatedAt)))
    suspend fun delete(id: String) {
        val e = db.entries().get(id) ?: return
        val t = Stamps.next(e.updatedAt)
        db.entries().upsert(e.copy(deletedAt = t, updatedAt = t))
    }
}

class MediaRepository(private val context: Context, private val db: BtDatabase, private val demoMode: Flow<Boolean> = flowOf(false)) {
    fun observeForTrip(tripId: String): Flow<List<Media>> = db.media().observeForTrip(tripId)
    fun observeAll(): Flow<List<Media>> = db.media().observeAll().visibleIn(demoMode) { it.tripId }
    suspend fun get(id: String): Media? = db.media().get(id)
    fun file(media: Media): File = File(context.filesDir, media.localPath)

    /** Trip id → its first photo; a trip's picture when it has no cover (trips without photos are missing). */
    fun observeFirstPhotos(): Flow<Map<String, String>> = db.media().observeFirstPhotos().map { l -> l.associate { it.tripId to it.mediaId } }

    /**
     * Copies a picked photo into the app (the picker's URI permission doesn't last) and reads its capture time,
     * place and size from EXIF. The photo picker may strip location; the time still places it on the route.
     */
    suspend fun import(uri: Uri, tripId: String?, entryId: String?): Media = withContext(Dispatchers.IO) {
        val id = newId()
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; "image/heic", "image/heif" -> "heic"; else -> "jpg" }
        val rel = "media/$id.$ext"
        val out = File(context.filesDir, rel)
        val importContext = currentCoroutineContext()
        val checksum = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Can't open the photo" }
            PhotoFiles.copy(input, out) { importContext.ensureActive() }
        }
        var takenAt: Long? = null
        var lat: Double? = null
        var lng: Double? = null
        var w = 0
        var h = 0
        runCatching {
            val exif = ExifInterface(out)
            takenAt = PhotoTime.parse(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
                ?: PhotoTime.parse(exif.getAttribute(ExifInterface.TAG_DATETIME), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME))
            // A damaged GPS tag can read as no number at all: such a photo simply has no place.
            exif.latLong?.takeIf { it[0].isFinite() && it[1].isFinite() && it[0] in -90.0..90.0 && it[1] in -180.0..180.0 }?.let { lat = it[0]; lng = it[1] }
            w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            if (exif.rotationDegrees == 90 || exif.rotationDegrees == 270) { val t = w; w = h; h = t }
        }
        val t = now()
        val media = Media(
            id = id, tripId = tripId, entryId = entryId, localPath = rel, mime = mime, width = w, height = h,
            takenAt = takenAt, lat = lat, lng = lng, sha256 = checksum,
            createdAt = t, updatedAt = t,
        )
        db.media().upsert(media)
        media
    }

    /** A video added to the journal, and how long it is (null: unknown). */
    class VideoImport(val media: Media, val durationMs: Long?)

    /**
     * Adds a picked or recorded video ([JournalVideos]): the whole of it, compressed to at most 720p, then saved like a
     * photo (files/media/<id>.mp4, type video/mp4). [onProgress] gets 0..1 on the main thread. Throws
     * [VideoImportException] with a message to show when it can't be added; nothing is left behind then.
     */
    suspend fun importVideo(uri: Uri, tripId: String?, entryId: String?, onProgress: (Float) -> Unit = {}): VideoImport {
        val id = newId()
        val v = JournalVideos(context).prepare(uri, id, onProgress)
        val t = now()
        val media = Media(
            id = id, tripId = tripId, entryId = entryId, localPath = v.localPath, mime = v.mime, width = v.width, height = v.height,
            takenAt = v.takenAt, lat = v.lat, lng = v.lng, sha256 = v.sha256, createdAt = t, updatedAt = t,
        )
        try {
            db.media().upsert(media)
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { File(context.filesDir, v.localPath).delete() }
            throw e
        }
        return VideoImport(media, v.durationMs)
    }

    /** The ids of the live videos (lists count them apart: "2 photos, 1 video"). */
    fun observeVideoIds(): Flow<Set<String>> = db.media().observeVideoIds().map { it.toSet() }

    suspend fun save(media: Media) = db.media().upsert(media.copy(updatedAt = Stamps.next(media.updatedAt)))

    /**
     * Marks the photo deleted. A trip that had it as its cover goes back to its first photo (or its drawn picture).
     * The file stays until [freeFiles].
     */
    suspend fun delete(media: Media) = db.withTransaction {
        save(media.copy(deletedAt = now()))
        db.trips().withCover(media.id).forEach { t -> db.trips().upsert(t.copy(coverMediaId = null, updatedAt = Stamps.next(t.updatedAt))) }
    }

    /**
     * Deletes the app's copies of [deleted] photos that no live photo still uses ([TripRemoval.filesToFree]); the
     * phone's gallery and Drive are never touched. A restore brings a file back with its row, if ever.
     */
    suspend fun freeFiles(deleted: List<Media>) = withContext(Dispatchers.IO) {
        val paths = deleted.filter { it.deletedAt != null }.map { it.localPath }.distinct()
        if (paths.isEmpty()) return@withContext
        val live = paths.chunked(900).flatMapTo(HashSet()) { db.media().livePaths(it) }
        val root = context.filesDir.canonicalFile
        for (rel in TripRemoval.filesToFree(deleted, live)) {
            val f = File(root, rel)
            if (f.canonicalFile.parentFile?.parentFile == root) runCatching { f.delete() }
        }
    }

}

class TrackRepository(private val db: BtDatabase, private val demoMode: Flow<Boolean> = flowOf(false)) {
    suspend fun add(points: List<TrackPoint>) = db.tracks().insertAll(points)
    fun observeForTrip(tripId: String): Flow<List<TrackPoint>> = db.tracks().observeForTrip(tripId)
    suspend fun forTrip(tripId: String): List<TrackPoint> = db.tracks().forTrip(tripId)
    fun observeLatest(): Flow<TrackPoint?> = db.tracks().observeLatest()

    fun observeStops(tripId: String): Flow<List<Stop>> = db.stops().observeForTrip(tripId)
    fun observeAllStops(): Flow<List<Stop>> = db.stops().observeAll().visibleIn(demoMode) { it.tripId }
    suspend fun stops(tripId: String): List<Stop> = db.stops().forTrip(tripId)

    /** A trip's stops including deleted ones (for the stop detector). */
    suspend fun stopsWithDeleted(tripId: String): List<Stop> = db.stops().forTripWithDeleted(tripId)
    suspend fun saveStops(stops: List<Stop>) = db.stops().upsertAll(stops.map { it.copy(updatedAt = now()) })

    /** Removes a stop by hand: marked deleted, so the stop detector never suggests it again (see [stopsWithDeleted]). */
    suspend fun deleteStop(stop: Stop) {
        val t = Stamps.next(stop.updatedAt)
        db.stops().upsertAll(listOf(stop.copy(deletedAt = t, updatedAt = t)))
    }
}
