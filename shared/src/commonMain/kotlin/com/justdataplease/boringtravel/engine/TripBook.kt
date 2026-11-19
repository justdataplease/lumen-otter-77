package com.justdataplease.boringtravel.engine

import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupFormatException
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.ClipRow
import com.justdataplease.boringtravel.backup.Snapshot
import com.justdataplease.boringtravel.backup.toData
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.clip.PlanRoute
import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.TripMarks
import com.justdataplease.boringtravel.data.db.BookDraft
import com.justdataplease.boringtravel.data.db.CountryMark
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.PlanStep
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.programs.Destination
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.Program
import com.justdataplease.boringtravel.programs.ProgramTrips
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.time.systemZoneId
import com.justdataplease.boringtravel.time.today
import com.justdataplease.boringtravel.ui.trips.HomeStats
import kotlinx.serialization.SerializationException

/** Where a phone without the Android database keeps its trips: one text, the backup's data.json. */
interface TripFile {
    /** The text last written; null when nothing was. */
    fun read(): String?
    fun write(text: String)
}

/** A [TripFile] in memory, for tests and previews. */
class MemoryTripFile(private var text: String? = null) : TripFile {
    override fun read(): String? = text
    override fun write(text: String) {
        this.text = text
    }
}

/** A day of a trip's plan as a screen lists it: "Day 3 · Temples and tea" and its rows in order. */
data class PlanDay(val date: LocalDate, val number: Int?, val name: String?, val steps: List<PlanStep>)

/**
 * The trips of a phone that has no database of its own yet (the iPhone app), end to end on the engine's rules: a trip
 * from a trip idea or from nothing, plan items, ticks, day names, journal notes with their photos and videos, deleting,
 * and keeping it all.
 *
 * Every row is the same row the Android app keeps in its database, and [file] holds them as the backup's data.json
 * ([BackupData], through [BackupJson]): what this writes, a restore on Android reads, and the other way round
 * ([restore]). Whatever the text holds that this class doesn't touch (photos' rows, stops, preferences, a newer
 * app's fields the format knows) is kept through every change.
 *
 * Stamps follow the Android app's rule (Stamps.next): a change is stamped now, and never earlier than the row's last.
 */
class TripBook(
    private val file: TripFile,
    private val now: () -> Long = ::nowMs,
    private val ids: () -> String = ::newId,
    /** Sorts an item's website and Google Maps link (the Android app's LinkPlaces::sortLinks); as they are by default. */
    private val links: (PlanItem) -> PlanItem = { it },
) {
    private var data: BackupData = file.read()?.let(::decode) ?: BackupData()
    private var rows: Snapshot = data.toSnapshot()

    /** Every row, deleted ones too. */
    val snapshot: Snapshot get() = rows

    /** The trips that aren't deleted, as Trips lists them: now, next, someday, past. */
    fun sections(today: LocalDate = today()): HomeStats.Sections = HomeStats.sections(rows.trips, today)

    /** The same trips as one list in that order. */
    fun trips(today: LocalDate = today()): List<Trip> = sections(today).let { listOfNotNull(it.now) + it.next + it.someday + it.past }

    fun trip(id: String): Trip? = rows.trips.firstOrNull { it.id == id && it.deletedAt == null }

    /** The trip's live items in plan order: day by day, ideas last. */
    fun items(tripId: String): List<PlanItem> = PlanSpan.ordered(rows.planItems.filter { it.tripId == tripId && it.deletedAt == null })

    /** The plan's days in order, each with its number in the trip, its own name and its rows (an item's end row too). */
    fun days(tripId: String): List<PlanDay> {
        val trip = trip(tripId) ?: return emptyList()
        val names = DayNames.all(trip.travelModes)
        return PlanSpan.byDay(rows.planItems.filter { it.tripId == tripId }).map { (date, steps) ->
            PlanDay(date, trip.startDate?.let { (date.toEpochDay() - it.toEpochDay() + 1).toInt() }, names[date], steps)
        }
    }

    /** The trip's items without a day. */
    fun ideas(tripId: String): List<PlanItem> = items(tripId).filter { it.day == null }

    /** The trip's journal entries that aren't deleted, oldest first. */
    fun entries(tripId: String): List<Entry> =
        rows.entries.filter { it.tripId == tripId && it.deletedAt == null }.sortedWith(compareBy({ it.occurredAt }, { it.id }))

    /** A trip made from a trip idea ([ProgramTrips.build]), starting [start]; nothing in it is ticked. */
    fun startFromIdea(destination: Destination, program: Program, pace: Pace, start: LocalDate): Trip {
        val built = ProgramTrips.build(destination, program, pace, start, now(), links = links, ids = ids)
        save(rows.copy(trips = rows.trips + built.trip, planItems = rows.planItems + built.items))
        return built.trip
    }

    /** An empty trip; without dates it is a someday trip. */
    fun newTrip(title: String, start: LocalDate? = null, end: LocalDate? = null, subtitle: String = ""): Trip {
        val t = now()
        val trip = Trip(ids(), title.trim(), subtitle.trim(), start, end ?: start, createdAt = t, updatedAt = t)
        save(rows.copy(trips = rows.trips + trip))
        return trip
    }

    /** A plan item at the end of [day] (null = an idea), typed by hand. Null when there is no such trip. */
    fun addItem(
        tripId: String, title: String, day: LocalDate? = null, kind: PlanKind = PlanKind.Place, timeMinutes: Int? = null,
        note: String = "", placeName: String = "",
    ): PlanItem? {
        if (trip(tripId) == null) return null
        val t = now()
        val siblings = rows.planItems.filter { it.tripId == tripId && it.deletedAt == null && it.day == day }
        val item = links(PlanItem(
            id = ids(), tripId = tripId, day = day, position = (siblings.maxOfOrNull { it.position } ?: 0.0) + 1.0,
            timeMinutes = timeMinutes, kind = kind.id, title = title.trim(), note = note.trim(), placeName = placeName.trim(),
            source = ItemSource.Manual.id, createdAt = t, updatedAt = t,
        ))
        save(rows.copy(planItems = rows.planItems + item))
        return item
    }

    /** Ticks or unticks an item. Null when there is no such live item. */
    fun setDone(itemId: String, done: Boolean): PlanItem? {
        val item = rows.planItems.firstOrNull { it.id == itemId && it.deletedAt == null } ?: return null
        if (item.done == done) return item
        val next = item.copy(done = done, updatedAt = stamp(item.updatedAt))
        save(rows.copy(planItems = rows.planItems.map { if (it.id == itemId) next else it }))
        return next
    }

    /** Gives [day] its own name ("Temples and tea"); null or blank takes it away ([DayNames]). */
    fun nameDay(tripId: String, day: LocalDate, name: String?): Trip? {
        val trip = trip(tripId) ?: return null
        val modes = DayNames.set(trip.travelModes, day, name)
        if (modes == trip.travelModes) return trip
        val next = trip.copy(travelModes = modes, updatedAt = stamp(trip.updatedAt))
        save(rows.copy(trips = rows.trips.map { if (it.id == tripId) next else it }))
        return next
    }

    /**
     * A journal note of plain [text] (paragraphs separated by an empty line), written [at] in the zone named [zoneId].
     * Null when there is no such trip or nothing was written.
     */
    fun writeNote(tripId: String, text: String, title: String = "", at: Long = now(), zoneId: String = systemZoneId()): Entry? {
        if (trip(tripId) == null) return null
        val doc = EntryDoc.ofText(text)
        if (doc.isEmpty && title.isBlank()) return null
        val t = now()
        val entry = Entry(
            id = ids(), tripId = tripId, occurredAt = at, zoneId = zoneId, title = title.trim(), body = doc.toJson(),
            plainText = doc.blocks.joinToString("\n\n") { if (it is com.justdataplease.boringtravel.doc.Block.Paragraph) RichHtml.toPlainText(it.html) else "" }.trim(),
            createdAt = t, updatedAt = t,
        )
        save(rows.copy(entries = rows.entries + entry))
        return entry
    }

    /** Deletes the trip with everything in it ([TripMarks]). False when there is no such live trip. */
    fun removeTrip(tripId: String): Boolean {
        val trip = trip(tripId) ?: return false
        val marked = TripMarks.mark(trip, rows.planItems, rows.entries, rows.media, rows.stops, ::stamp)
        fun <T> List<T>.with(changed: List<T>, id: (T) -> String): List<T> {
            val byId = changed.associateBy(id)
            return map { byId[id(it)] ?: it }
        }
        save(rows.copy(
            trips = rows.trips.map { if (it.id == tripId) marked.trip else it },
            planItems = rows.planItems.with(marked.items) { it.id },
            entries = rows.entries.with(marked.entries) { it.id },
            media = rows.media.with(marked.media) { it.id },
            stops = rows.stops.with(marked.stops) { it.id },
        ))
        return true
    }

    /** A plan item that isn't deleted. */
    fun item(id: String): PlanItem? = rows.planItems.firstOrNull { it.id == id && it.deletedAt == null }

    /** A journal entry that isn't deleted. */
    fun entry(id: String): Entry? = rows.entries.firstOrNull { it.id == id && it.deletedAt == null }

    /**
     * Changes a trip as the Android app's Edit trip saves it: its name, route line and dates (no start = a someday
     * trip; an end before the start is the start). When the start moves, the dated plan items move by the same number
     * of days, ideas stay ideas, and the days' own names move with their days. Null when there is no such live trip
     * or the name is blank.
     */
    fun editTrip(tripId: String, title: String, subtitle: String, start: LocalDate?, end: LocalDate?): Trip? {
        val trip = trip(tripId) ?: return null
        if (title.isBlank()) return null
        val last = if (start == null) null else end?.takeIf { it.toEpochDay() >= start.toEpochDay() } ?: start
        val oldStart = trip.startDate
        val shift = if (oldStart != null && start != null) start.toEpochDay() - oldStart.toEpochDay() else 0L
        val edited = trip.copy(title = title.trim(), subtitle = subtitle.trim(), startDate = start, endDate = last, travelModes = DayNames.shift(trip.travelModes, shift))
        if (edited == trip) return trip
        val next = edited.copy(updatedAt = stamp(trip.updatedAt))
        val items = if (shift == 0L) rows.planItems else rows.planItems.map { item ->
            val day = item.day
            if (item.tripId != tripId || item.deletedAt != null || day == null) item
            else item.copy(day = day.plusDays(shift), updatedAt = stamp(item.updatedAt))
        }
        save(rows.copy(trips = rows.trips.map { if (it.id == tripId) next else it }, planItems = items))
        return next
    }

    /**
     * A new plan item for trip [tripId] on [day] (null = an idea) at [position], not saved yet: what the item sheet
     * edits before its first save ([saveItems]). Typed by hand, with a new id.
     */
    fun draftItem(tripId: String, day: LocalDate?, position: Double): PlanItem =
        PlanItem(id = ids(), tripId = tripId, day = day, position = position, title = "", source = ItemSource.Manual.id, createdAt = 0L, updatedAt = 0L)

    /**
     * Saves plan items as the Android app's item sheet does (PlanRepository.save): each tidied ([PlanSpan.normalized]),
     * stamped now and never earlier than the row it replaces; one not kept yet is added. An item without a title, of a
     * trip that isn't there, or deleted meanwhile is left out. The rows as saved, in the order given.
     */
    fun saveItems(items: List<PlanItem>): List<PlanItem> {
        val byId = rows.planItems.associateBy { it.id }
        val t = now()
        val saved = items.mapNotNull { item ->
            val current = byId[item.id]
            when {
                item.title.isBlank() || trip(item.tripId) == null -> null
                current != null && (current.deletedAt != null || current.tripId != item.tripId) -> null
                else -> links(PlanSpan.normalized(item)).copy(
                    createdAt = current?.createdAt ?: item.createdAt.takeIf { it > 0 } ?: t,
                    updatedAt = stamp(current?.updatedAt ?: item.updatedAt),
                    deletedAt = null,
                )
            }
        }.associateBy { it.id }
        if (saved.isEmpty()) return emptyList()
        save(rows.copy(planItems = rows.planItems.map { saved[it.id] ?: it } + saved.values.filter { it.id !in byId }))
        return saved.values.toList()
    }

    /** One item, as [saveItems] saves it; null when it was left out. */
    fun saveItem(item: PlanItem): PlanItem? = saveItems(listOf(item)).firstOrNull()

    /** Deletes a plan item (kept as deleted, so a restore elsewhere carries the deletion). False when there is no such live item. */
    fun removeItem(itemId: String): Boolean {
        val item = item(itemId) ?: return false
        val t = stamp(item.updatedAt)
        val gone = item.copy(deletedAt = t, updatedAt = t)
        save(rows.copy(planItems = rows.planItems.map { if (it.id == itemId) gone else it }))
        return true
    }

    /**
     * Note [entryId]'s text as plain paragraphs (separated by an empty line), for changing it on a phone that writes
     * plain text only. Null when there is no such note, or when it holds more than plain text (photos, lists,
     * checklists, formatting): changing it as plain text would lose that, so it isn't offered.
     */
    fun noteText(entryId: String): String? = entry(entryId)?.let { plainOf(EntryDoc.parse(it.body)) }

    /**
     * Changes note [entryId] to [text] (as [writeNote] takes it) and [title], keeping its font, its time and its place.
     * Null when there is no such note, when it isn't plain text ([noteText]), when another member of a shared trip
     * wrote it, or when nothing would be left.
     */
    fun editNote(entryId: String, text: String, title: String = ""): Entry? {
        val entry = entry(entryId) ?: return null
        val old = EntryDoc.parse(entry.body)
        if (entry.authorId != null || plainOf(old) == null) return null
        val doc = EntryDoc(font = old.font, blocks = EntryDoc.ofText(text).blocks)
        if (doc.isEmpty && title.isBlank()) return null
        val edited = entry.copy(title = title.trim(), body = doc.toJson(), plainText = plainOf(doc).orEmpty().trim())
        if (edited == entry) return entry
        val next = edited.copy(updatedAt = stamp(entry.updatedAt))
        save(rows.copy(entries = rows.entries.map { if (it.id == entryId) next else it }))
        return next
    }

    /**
     * Deletes note [entryId] and the photos and videos in it, as the Android app's Delete does (kept as deleted, so a
     * restore elsewhere carries the deletion). False when there is no such live note or another member wrote it.
     */
    fun removeNote(entryId: String): Boolean {
        val entry = entry(entryId) ?: return false
        if (entry.authorId != null) return false
        val t = stamp(entry.updatedAt)
        val photos = EntryDoc.parse(entry.body).mediaIds().toSet()
        save(rows.copy(
            entries = rows.entries.map { if (it.id == entryId) it.copy(deletedAt = t, updatedAt = t) else it },
            media = rows.media.map { m ->
                if (m.id !in photos || m.deletedAt != null) m else stamp(m.updatedAt).let { s -> m.copy(deletedAt = s, updatedAt = s) }
            },
        ))
        return true
    }

    /**
     * Saves note [entry] with [doc] as its body, as the Android app's editor saves it (JournalRepository.save): the body
     * as the document's JSON, the plain text for search and previews as the Android app makes it ([NoteText]), stamped
     * now and never earlier than the row it replaces. A note not kept yet is added under [entry]'s id; a note in a trip
     * needs the trip, and a note outside trips has a null trip. Everything else the row holds (when, where, the
     * weather, the title) is [entry]'s. Null when another member of a shared trip wrote it, or when a new note's trip
     * isn't there; the row as kept when nothing changed.
     */
    fun saveNote(entry: Entry, doc: EntryDoc): Entry? {
        val current = rows.entries.firstOrNull { it.id == entry.id }
        if (entry.authorId != null || current?.authorId != null) return null
        val tripId = entry.tripId
        if (current == null && tripId != null && trip(tripId) == null) return null
        val t = now()
        val written = entry.copy(
            body = doc.toJson(),
            plainText = NoteText.plainText(doc),
            createdAt = current?.createdAt ?: entry.createdAt.takeIf { it > 0 } ?: t,
        )
        if (current != null && written.copy(updatedAt = current.updatedAt) == current) return current
        val next = written.copy(updatedAt = stamp(maxOf(current?.updatedAt ?: 0L, entry.updatedAt)))
        save(rows.copy(entries = if (current == null) rows.entries + next else rows.entries.map { if (it.id == entry.id) next else it }))
        return next
    }

    /** A photo's or video's row that isn't deleted. */
    fun media(id: String): Media? = rows.media.firstOrNull { it.id == id && it.deletedAt == null }

    /**
     * Keeps [media], a photo or video just brought into a note, as the Android app's MediaRepository keeps an import:
     * its file is under the app's files at [Media.localPath] ("media/<id>.jpg"), and a backup carries it from there.
     * Created and updated now when it says nothing else. A row with the same id is replaced.
     */
    fun addMedia(media: Media): Media {
        val t = now()
        val row = media.copy(createdAt = media.createdAt.takeIf { it > 0 } ?: t, updatedAt = media.updatedAt.takeIf { it > 0 } ?: t)
        save(rows.copy(media = rows.media.filter { it.id != row.id } + row))
        return row
    }

    /**
     * Marks photo or video [id] deleted, as the Android app's MediaRepository.delete does when a note no longer shows
     * it: kept as deleted (a restore elsewhere carries the deletion), and a trip that had it as its cover goes back to
     * none. The file is left where it is. False when there is no such live row.
     */
    fun removeMedia(id: String): Boolean {
        val m = media(id) ?: return false
        val t = stamp(m.updatedAt)
        save(rows.copy(
            media = rows.media.map { if (it.id == id) it.copy(deletedAt = t, updatedAt = t) else it },
            trips = rows.trips.map { if (it.coverMediaId == id) it.copy(coverMediaId = null, updatedAt = stamp(it.updatedAt)) else it },
        ))
        return true
    }

    /**
     * Saves how leg [key] of trip [tripId] is travelled ([TravelModes.set]; null or Auto = back to the default), as the
     * Android app's TripRepository does (data/repo/TripLegUpdates.kt): only while the leg is still one of the plan's
     * ([PlanRoute.legKeys]), else an [IllegalStateException] "This route leg changed. Reopen it and choose again.". The
     * other legs' choices and anything else the travel choices hold stay. Null when there is no such live trip.
     */
    fun setLegVehicle(tripId: String, key: RouteLegKey, vehicle: Vehicle?): Trip? = updateLeg(tripId, key) { TravelModes.set(it, key, vehicle) }

    /** Saves whether leg [key] counts in the trip's km ([TravelModes.setKm]; null = the default), checked as [setLegVehicle]. */
    fun setLegKm(tripId: String, key: RouteLegKey, count: Boolean?): Trip? = updateLeg(tripId, key) { TravelModes.setKm(it, key, count) }

    private fun updateLeg(tripId: String, key: RouteLegKey, change: (String?) -> String?): Trip? {
        val trip = trip(tripId) ?: return null
        check(key in PlanRoute.legKeys(trip, rows.planItems).toSet()) { "This route leg changed. Reopen it and choose again." }
        val json = change(trip.travelModes)
        if (json == trip.travelModes) return trip
        val next = trip.copy(travelModes = json, updatedAt = stamp(trip.updatedAt))
        save(rows.copy(trips = rows.trips.map { if (it.id == tripId) next else it }))
        return next
    }

    /**
     * Writes rows as they are given, in one save: each replaces the row with its id, or is added. What a phone without
     * the database does where the Android app upserts rows it built itself (the demo's example trips, and moving them
     * around today); the stamps are the caller's, as on Android. Rows of the other kinds stay as they are.
     */
    fun putRows(
        trips: List<Trip> = emptyList(),
        items: List<PlanItem> = emptyList(),
        entries: List<Entry> = emptyList(),
        media: List<Media> = emptyList(),
        stops: List<Stop> = emptyList(),
    ) {
        if (trips.isEmpty() && items.isEmpty() && entries.isEmpty() && media.isEmpty() && stops.isEmpty()) return
        fun <T> List<T>.upsert(rows: List<T>, id: (T) -> String): List<T> {
            if (rows.isEmpty()) return this
            val byId = LinkedHashMap<String, T>()
            rows.forEach { byId[id(it)] = it }
            val kept = map { old -> byId.remove(id(old)) ?: old }
            return kept + byId.values
        }
        save(rows.copy(
            trips = rows.trips.upsert(trips) { it.id },
            planItems = rows.planItems.upsert(items) { it.id },
            entries = rows.entries.upsert(entries) { it.id },
            media = rows.media.upsert(media) { it.id },
            stops = rows.stops.upsert(stops) { it.id },
        ))
    }

    /**
     * Writes what a reviewed AI planner draft changes, in one save, as the Android app writes it in one transaction
     * (TripRepository.saveWithChoices, then PlanRepository.saveAll): [trip] when given (a new trip, or the trip with its
     * new name, dates, route, travel choices or day names), stamped now and never earlier than the row it replaces; each
     * of [items] (added, changed, moved and removed rows) tidied ([PlanSpan.normalized]) and stamped now, never earlier
     * than its own stamp. Each replaces the row with its id, or is added. Nothing given: nothing is written.
     */
    fun saveDraft(trip: Trip?, items: List<PlanItem>) {
        if (trip == null && items.isEmpty()) return
        val current = trip?.let { t -> rows.trips.firstOrNull { it.id == t.id } }
        val tripRows = listOfNotNull(trip?.copy(updatedAt = stamp(current?.updatedAt ?: 0L)))
        val itemRows = items.map { PlanSpan.normalized(it).copy(updatedAt = stamp(it.updatedAt)) }
        fun <T> List<T>.upsert(changed: List<T>, id: (T) -> String): List<T> {
            if (changed.isEmpty()) return this
            val byId = LinkedHashMap<String, T>()
            changed.forEach { byId[id(it)] = it }
            val kept = map { old -> byId.remove(id(old)) ?: old }
            return kept + byId.values
        }
        save(rows.copy(trips = rows.trips.upsert(tripRows) { it.id }, planItems = rows.planItems.upsert(itemRows) { it.id }))
    }

    /** The shared trips this phone takes part in, as the last restore brought them ([BackupData.sharedTrips]). */
    val sharedTrips: String? get() = data.sharedTrips

    /** The saved trip videos the last restore listed ([BackupData.savedClips]). */
    val savedClips: List<ClipRow>? get() = data.savedClips

    /** The trip videos' scene edits the last restore brought ([BackupData.clipEdits]). */
    val clipEdits: Map<String, String>? get() = data.clipEdits

    /**
     * Writes what a restore puts back, as the Android app's BackupManager writes a restore's plan into its database:
     * each row replaces the row with its id, or is added; the route points of the trips in [clearRoutes] (null: the
     * points recorded outside trips) go before [trackPoints] are added; and the backup's [sharedTrips], [savedClips]
     * and [clipEdits] take the place of what this file held. The stamps are the caller's. One save.
     */
    fun restoreRows(
        trips: List<Trip> = emptyList(),
        planItems: List<PlanItem> = emptyList(),
        entries: List<Entry> = emptyList(),
        media: List<Media> = emptyList(),
        stops: List<Stop> = emptyList(),
        countryMarks: List<CountryMark> = emptyList(),
        books: List<BookDraft> = emptyList(),
        trackPoints: List<TrackPoint> = emptyList(),
        clearRoutes: List<String?> = emptyList(),
        sharedTrips: String? = data.sharedTrips,
        savedClips: List<ClipRow>? = data.savedClips,
        clipEdits: Map<String, String>? = data.clipEdits,
    ) {
        fun <T> List<T>.upsert(rows: List<T>, id: (T) -> String): List<T> {
            if (rows.isEmpty()) return this
            val byId = LinkedHashMap<String, T>()
            rows.forEach { byId[id(it)] = it }
            val kept = map { old -> byId.remove(id(old)) ?: old }
            return kept + byId.values
        }
        val cleared = clearRoutes.toSet()
        data = data.copy(sharedTrips = sharedTrips, savedClips = savedClips, clipEdits = clipEdits)
        save(rows.copy(
            trips = rows.trips.upsert(trips) { it.id },
            planItems = rows.planItems.upsert(planItems) { it.id },
            entries = rows.entries.upsert(entries) { it.id },
            media = rows.media.upsert(media) { it.id },
            stops = rows.stops.upsert(stops) { it.id },
            countryMarks = rows.countryMarks.upsert(countryMarks) { it.id },
            books = rows.books.upsert(books) { it.id },
            trackPoints = rows.trackPoints.filter { it.tripId !in cleared } + trackPoints,
        ))
    }

    /**
     * Adds recorded route points, as the Android app's TrackWriter adds a batch of fixes to its database, in one save.
     * The database's own row numbers aren't kept here (a backup carries points without them), so [TrackPoint.id] is
     * left as given. Nothing given: nothing is written.
     */
    fun addTrackPoints(points: List<TrackPoint>) {
        if (points.isEmpty()) return
        save(rows.copy(trackPoints = rows.trackPoints + points))
    }

    /**
     * Removes trip [tripId]'s recorded route, as the Android app's TrackDao.deleteForTrip does when the trip is deleted:
     * points have no deletion mark, so they go. The points of other trips and outside trips stay. False when there were none.
     */
    fun removeTrackPoints(tripId: String): Boolean {
        if (rows.trackPoints.none { it.tripId == tripId }) return false
        save(rows.copy(trackPoints = rows.trackPoints.filter { it.tripId != tripId }))
        return true
    }

    /** The latest recorded point of any trip (the Android app's TrackDao.latest), for a note's place; null when there is none. */
    fun latestTrackPoint(): TrackPoint? = rows.trackPoints.maxByOrNull { it.time }

    /**
     * Trip [tripId]'s own route tracking level, a [com.justdataplease.boringtravel.model.TrackingLevel] id ("detailed"),
     * or null to follow the phone's level for every trip, as the Android app's TrackingController.setTripLevel saves it:
     * stamped now, never earlier than the trip's last change. The trip as kept; null when there is no such live trip.
     */
    fun setTrackingLevel(tripId: String, levelId: String?): Trip? {
        val trip = trip(tripId) ?: return null
        if (trip.trackingLevel == levelId) return trip
        val next = trip.copy(trackingLevel = levelId, updatedAt = stamp(trip.updatedAt))
        save(rows.copy(trips = rows.trips.map { if (it.id == tripId) next else it }))
        return next
    }

    /**
     * Trip [tripId]'s newest book draft that isn't deleted (Make a book), as the Android app's BookDao.latestForTrip
     * picks it: the latest change wins. Null when the trip has none.
     */
    fun latestBook(tripId: String): BookDraft? =
        rows.books.filter { it.tripId == tripId && it.deletedAt == null }.maxByOrNull { it.updatedAt }

    /**
     * Keeps book draft [draft] as the Android app's BookDao.upsert does: it replaces the row with its id, or is added.
     * Its stamps are the caller's (the book screen stamps each save). Null when its trip isn't here; the row as kept.
     */
    fun saveBook(draft: BookDraft): BookDraft? {
        if (trip(draft.tripId) == null) return null
        if (rows.books.any { it.id == draft.id && it == draft }) return draft
        save(rows.copy(books = if (rows.books.any { it.id == draft.id }) rows.books.map { if (it.id == draft.id) draft else it } else rows.books + draft))
        return draft
    }

    /**
     * Deletes trip [tripId]'s books, as the Android app's BookDao.deleteForTrip does when the trip is deleted: marked
     * deleted now (never earlier than the row's last change), so a backup carries the deletion; the journal and the
     * photos are untouched. False when it had none.
     */
    fun removeBooks(tripId: String): Boolean {
        if (rows.books.none { it.tripId == tripId && it.deletedAt == null }) return false
        save(rows.copy(books = rows.books.map { b ->
            if (b.tripId != tripId || b.deletedAt != null) b else stamp(b.updatedAt).let { t -> b.copy(deletedAt = t, updatedAt = t) }
        }))
        return true
    }

    /** The countries marked by hand that aren't deleted (Your world: been, want to go, a found one removed). */
    fun countryMarks(): List<CountryMark> = rows.countryMarks.filter { it.deletedAt == null }

    /**
     * Turns country mark [source] for [code] on (a new row, or the deleted one back) or off (kept as deleted, so a
     * restore elsewhere carries it), as the Android app's CountryRepository.set does. The id is "source:CODE" there
     * too, so the same mark made on two phones is one row. False when nothing changed.
     */
    fun setCountryMark(code: String, source: String, on: Boolean): Boolean = markCountries(code, listOf(source to on))

    /** "I've been here" (CountryRepository.markVisited): no longer hidden, added by hand, off the want-to-go list. */
    fun markCountryVisited(code: String): Boolean = markCountries(
        code, listOf(CountryMark.SOURCE_HIDDEN to false, CountryMark.SOURCE_MANUAL to true, CountryMark.SOURCE_WISH to false),
    )

    /**
     * Takes a country off the traveller's list (CountryRepository.remove): the hand-made mark goes, and one found from
     * their trips ([foundFromTrips]) is hidden.
     */
    fun removeCountry(code: String, foundFromTrips: Boolean): Boolean = markCountries(
        code, listOfNotNull(CountryMark.SOURCE_MANUAL to false, (CountryMark.SOURCE_HIDDEN to true).takeIf { foundFromTrips }),
    )

    /** "Want to go" on or off (CountryRepository.setWish). */
    fun setCountryWish(code: String, on: Boolean): Boolean = setCountryMark(code, CountryMark.SOURCE_WISH, on)

    /** The marks of [code] set as [changes] say, in one save. */
    private fun markCountries(code: String, changes: List<Pair<String, Boolean>>): Boolean {
        val c = code.trim().uppercase()
        if (c.isEmpty()) return false
        var marks = rows.countryMarks
        var changed = false
        for ((source, on) in changes) {
            val id = CountryMark.id(source, c)
            val existing = marks.firstOrNull { it.id == id }
            val next = when {
                existing == null -> if (on) now().let { t -> CountryMark(id, c, source, addedAt = t, updatedAt = t) } else null
                on && existing.deletedAt != null -> stamp(existing.updatedAt).let { t -> existing.copy(addedAt = t, updatedAt = t, deletedAt = null) }
                !on && existing.deletedAt == null -> stamp(existing.updatedAt).let { t -> existing.copy(updatedAt = t, deletedAt = t) }
                else -> null
            } ?: continue
            marks = if (existing == null) marks + next else marks.map { if (it.id == id) next else it }
            changed = true
        }
        if (changed) save(rows.copy(countryMarks = marks))
        return changed
    }

    /** [doc] as plain paragraphs, or null when writing that text again ([EntryDoc.ofText]) wouldn't give [doc] back. */
    private fun plainOf(doc: EntryDoc): String? {
        val texts = doc.blocks.map { block ->
            val p = block as? com.justdataplease.boringtravel.doc.Block.Paragraph ?: return null
            val text = RichHtml.toPlainText(p.html)
            if (p.list != null || RichHtml.escape(text).replace("\n", "<br>") != p.html) return null
            text
        }
        return texts.joinToString("\n\n")
    }

    /** Everything as the backup's data.json. */
    fun export(): String = BackupJson.encodeToString(BackupData.serializer(), data)

    /**
     * Replaces everything with the rows of [text] (a backup's data.json), the way a restore replaces the database.
     * A text that isn't one throws [BackupFormatException] and changes nothing.
     */
    fun restore(text: String) {
        val next = decode(text)
        val snapshot = next.toSnapshot()
        data = next
        rows = snapshot
        file.write(export())
    }

    private fun stamp(previous: Long): Long = maxOf(now(), previous + 1)

    private fun save(next: Snapshot) {
        // The parts of the file this class never changes travel on as they were.
        val written = next.toData(data.preferences).copy(sharedTrips = data.sharedTrips, savedClips = data.savedClips, clipEdits = data.clipEdits)
        file.write(BackupJson.encodeToString(BackupData.serializer(), written))
        data = written
        rows = next
    }

    private fun decode(text: String): BackupData = try {
        BackupJson.decodeFromString(BackupData.serializer(), text)
    } catch (e: SerializationException) {
        throw BackupFormatException("These trips could not be read.")
    } catch (e: IllegalArgumentException) {
        throw BackupFormatException("These trips could not be read.")
    }
}
