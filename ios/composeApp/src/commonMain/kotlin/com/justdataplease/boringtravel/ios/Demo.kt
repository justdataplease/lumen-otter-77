package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.backup.Snapshot
import com.justdataplease.boringtravel.clip.PlanRoute
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoAlbums
import com.justdataplease.boringtravel.engine.NoteText
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.ios.resources.Res
import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.ProgramTrips
import com.justdataplease.boringtravel.programs.data.KyotoOsaka
import com.justdataplease.boringtravel.programs.data.LisbonPorto
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.civil
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.time.today
import com.justdataplease.boringtravel.world.WorldInput

/*
 * Demo mode, as the Android app has it (data/DemoMode.kt, DemoTrips.kt, SampleData.kt, SampleTrips.kt, SampleBuilder.kt,
 * SampleArgentina.kt, SampleKyoto.kt, SampleLisbon.kt): the app shows either the example trips or the traveller's own,
 * never both. Written again here because those files read and write Room and build their rows with java.time; the rules,
 * ids, words and photos are Android's, the plans of Kyoto & Osaka and Lisbon & Porto the engine's own trip ideas.
 */

/** The examples' ids (the Android app's SampleTrips), and which of them are missing. */
object SampleTrips {
    const val KYOTO = "sample-japan"
    const val LISBON = "sample-portugal"

    /** The examples' subtitle before the Demo tag; now each shows its route. */
    const val OLD_SUBTITLE = "Example trip"
    const val KYOTO_ROUTE = "Kyoto → Osaka"
    const val LISBON_ROUTE = "Lisbon → Porto"

    /** The current examples, in the order they are seeded. */
    val CURRENT: List<String> = listOf(SampleArgentina.TRIP_ID, KYOTO, LISBON)

    /** Demo mode turned on: the current examples that are not here, or were deleted, and so are seeded again. */
    fun missing(trips: List<Trip>): List<String> = CURRENT.filter { id -> trips.none { it.id == id && it.deletedAt == null } }

    /** The set of examples the Android app seeds today (its SampleData.VERSION); this phone only ever seeds this one. */
    const val VERSION = 8
}

/**
 * Which rows show in which mode, and moving the examples in time (the Android app's DemoTrips): rows belong to a side
 * through their trip; rows outside any trip are the traveller's. Hidden rows are never changed.
 */
object DemoTrips {
    /** Whether a row of trip [tripId] (null = no trip) shows in this mode. */
    fun visible(tripId: String?, demoMode: Boolean): Boolean = Trip.isDemoId(tripId) == demoMode

    /**
     * What Your world, the globe and the Trips numbers count: only the side on show. In demo mode the countries the
     * traveller marked by hand stay out too.
     */
    fun visibleWorld(input: WorldInput, demoMode: Boolean): WorldInput = input.copy(
        trips = input.trips.filter { visible(it.id, demoMode) },
        stops = input.stops.filter { visible(it.tripId, demoMode) },
        entries = input.entries.filter { visible(it.tripId, demoMode) },
        media = input.media.filter { visible(it.tripId, demoMode) },
        planItems = input.planItems.filter { visible(it.tripId, demoMode) },
        marks = if (demoMode) emptyList() else input.marks,
        metersByTrip = input.metersByTrip.filterKeys { visible(it, demoMode) },
    )

    /** A backup (and what a restore brings in) without the example trips and everything in them. */
    fun withoutDemo(s: Snapshot): Snapshot = s.copy(
        trips = s.trips.filterNot { it.isDemo },
        planItems = s.planItems.filterNot { Trip.isDemoId(it.tripId) },
        entries = s.entries.filterNot { Trip.isDemoId(it.tripId) },
        media = withoutDemoMedia(s.entries, s.media),
        trackPoints = s.trackPoints.filterNot { Trip.isDemoId(it.tripId) },
        stops = s.stops.filterNot { Trip.isDemoId(it.tripId) },
        books = s.books.filterNot { Trip.isDemoId(it.tripId) },
    )

    /** Photos outside the example trips and their entries. */
    fun withoutDemoMedia(entries: List<Entry>, media: List<Media>): List<Media> {
        val demoEntries = entries.filter { Trip.isDemoId(it.tripId) }.mapTo(HashSet()) { it.id }
        return media.filterNot { Trip.isDemoId(it.tripId) || it.entryId in demoEntries }
    }

    /** The setting for an install that has none yet: on whenever there are examples (a store install has none). */
    fun initialMode(hasDemoTrips: Boolean): Boolean = hasDemoTrips

    /** A new trip of the traveller's own ends demo mode (and the app shows it). */
    fun leavesDemo(demoMode: Boolean, saved: Trip, existed: Boolean): Boolean = demoMode && !existed && !saved.isDemo

    /** One trip and what moves with it. */
    data class Rows(
        val trip: Trip,
        val items: List<PlanItem> = emptyList(),
        val entries: List<Entry> = emptyList(),
        val media: List<Media> = emptyList(),
        val stops: List<Stop> = emptyList(),
    )

    /** Days from the trip's start to [start] (0 when either is missing). */
    fun delta(trip: Trip, start: LocalDate?): Long {
        val from = trip.startDate ?: return 0
        return if (start == null) 0 else start.toEpochDay() - from.toEpochDay()
    }

    /** The zone most of [zones] name, else [fallback] (the Android app's TripZone.guess from the entries' zones). */
    fun zoneOf(zones: List<String>, fallback: String): String =
        zones.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: fallback

    /** [ms] moved by [days] whole days, keeping its time of day in the zone named [zone]. */
    fun move(ms: Long, days: Long, zone: String): Long {
        val day = localDateAt(ms, zone)
        return startOfDayMs(day.plusDays(days), zone) + (ms - startOfDayMs(day, zone))
    }

    /**
     * The whole trip [days] later (earlier when negative): its dates (same length), the plan's days and their names
     * ([DayNames]), entry times, photo times and stays. Times keep their time of day in their zone: an entry's own
     * zone, a photo its entry's, the rest the zone most entries were written in, else [fallback]. Ids, files and stamps
     * stay; the caller stamps the rows.
     */
    fun shift(rows: Rows, days: Long, fallback: String): Rows {
        if (days == 0L) return rows
        val tripZone = zoneOf(rows.entries.map { it.zoneId }, fallback)
        val entryZones = rows.entries.associate { it.id to (it.zoneId.ifBlank { tripZone }) }
        val t = rows.trip
        return Rows(
            trip = t.copy(startDate = t.startDate?.plusDays(days), endDate = t.endDate?.plusDays(days), travelModes = DayNames.shift(t.travelModes, days)),
            items = rows.items.map { it.copy(day = it.day?.plusDays(days)) },
            entries = rows.entries.map { it.copy(occurredAt = move(it.occurredAt, days, entryZones.getValue(it.id))) },
            media = rows.media.map { m -> m.copy(takenAt = m.takenAt?.let { move(it, days, m.entryId?.let(entryZones::get) ?: tripZone) }) },
            stops = rows.stops.map { s -> s.copy(arrivedAt = move(s.arrivedAt, days, tripZone), leftAt = s.leftAt?.let { move(it, days, tripZone) }) },
        )
    }

    /** The examples' subtitles before the Demo tag, and the route each shows now. */
    private val ROUTES: Map<String, Pair<String, String>> = mapOf(
        SampleArgentina.TRIP_ID to (SampleArgentina.OLD_SUBTITLE to SampleArgentina.SUBTITLE),
        SampleTrips.KYOTO to (SampleTrips.OLD_SUBTITLE to SampleTrips.KYOTO_ROUTE),
        SampleTrips.LISBON to (SampleTrips.OLD_SUBTITLE to SampleTrips.LISBON_ROUTE),
    )

    /**
     * Keeps the demo around [today]: every example trip moves by the days that make today Argentina's day
     * [SampleArgentina.TODAY] again ([shift]), so Kyoto stays as far ahead and Lisbon as far back. Returns only the trips
     * that change (none when Argentina is already there, deleted or gone). Rows are stamped [t]; a row seeded with its
     * trip and never changed is restamped as seeded at [t], so it still reads as untouched.
     */
    fun reanchor(demo: List<Rows>, today: LocalDate, t: Long, fallback: String = SampleArgentina.ZONE): List<Rows> {
        val anchor = demo.firstOrNull { it.trip.id == SampleArgentina.TRIP_ID && it.trip.deletedAt == null } ?: return emptyList()
        val days = delta(anchor.trip, Samples.argentinaStart(today))
        return demo.filter { it.trip.isDemo }.mapNotNull { rows ->
            val route = ROUTES[rows.trip.id]?.takeIf { (old, _) -> rows.trip.subtitle == old }?.second
            if (days == 0L && route == null) return@mapNotNull null
            val moved = shift(rows, days, fallback)
            val stamp = rows.trip.createdAt
            fun seeded(created: Long, updated: Long) = created == stamp && updated == created
            val trip = moved.trip.copy(subtitle = route ?: moved.trip.subtitle)
            Rows(
                trip = if (seeded(trip.createdAt, trip.updatedAt)) trip.copy(createdAt = t, updatedAt = t) else trip.copy(updatedAt = t),
                items = moved.items.map { if (seeded(it.createdAt, it.updatedAt)) it.copy(createdAt = t, updatedAt = t) else it.copy(updatedAt = t) },
                entries = moved.entries.map { if (seeded(it.createdAt, it.updatedAt)) it.copy(createdAt = t, updatedAt = t) else it.copy(updatedAt = t) },
                media = moved.media.map { if (it.createdAt == stamp) it.copy(createdAt = t, updatedAt = t) else it.copy(updatedAt = t) },
                stops = moved.stops.map { if (seeded(it.createdAt, it.updatedAt)) it.copy(createdAt = t, updatedAt = t) else it.copy(updatedAt = t) },
            )
        }
    }
}

/**
 * One example trip as rows, with where each picture comes from: a real photo of the app's own ([photos], by media id)
 * or a drawn one ([pictures]).
 */
class SampleDemo(
    val trip: Trip,
    val items: List<PlanItem>,
    val entries: List<Entry> = emptyList(),
    val media: List<Media> = emptyList(),
    val stops: List<Stop> = emptyList(),
    val photos: Map<String, DemoPhotos.Photo> = emptyMap(),
    val pictures: Map<String, DemoArt.Picture> = emptyMap(),
)

/** The three examples built from their tables (the Android app's SampleArgentina, SampleKyoto, SampleLisbon). */
object Samples {
    /** Argentina's first day: today is its day [SampleArgentina.TODAY]. */
    fun argentinaStart(today: LocalDate): LocalDate = today.minusDays((SampleArgentina.TODAY - 1).toLong())

    /** Kyoto & Osaka: 3 October next year. */
    fun kyotoStart(today: LocalDate): LocalDate = localDate(today.civil.year + 1, 10, 3)

    /** Lisbon & Porto: 9 May, this year's once the trip is over, else last year's. */
    fun lisbonStart(today: LocalDate): LocalDate {
        val days = LisbonPorto.destination.default.days.size
        val year = today.civil.year
        val thisYear = localDate(year, 5, 9)
        return if (thisYear.plusDays(days - 1L).toEpochDay() < today.toEpochDay()) thisYear else localDate(year - 1, 5, 9)
    }

    /** Nothing in the demo is later than this: 20 minutes before [now], to the minute. */
    fun latest(now: Long): Long = (now - 20 * 60_000L).let { it - it.mod(60_000L) }

    /** "HH:mm" on day [day] of a trip from [start], in the zone named [zone]. */
    fun at(start: LocalDate, zone: String, day: Int, time: String): Long =
        startOfDayMs(start.plusDays((day - 1).toLong()), zone) + minutes(time) * 60_000L

    /** "HH:mm" on the day of [written] in [zone]; an empty time is 10 minutes before [written]. */
    fun at(written: Long, zone: String, time: String): Long =
        if (time.isEmpty()) written - 600_000L else startOfDayMs(localDateAt(written, zone), zone) + minutes(time) * 60_000L

    private fun minutes(time: String): Int = ProgramTrips.minutes(time) ?: 0

    /** The pictures of one trip: media rows written like imported photos, and where each file comes from. */
    private class Album(private val tripId: String, private val t: Long, private val ids: () -> String) {
        val media = ArrayList<Media>()
        val photos = HashMap<String, DemoPhotos.Photo>()
        val pictures = HashMap<String, DemoArt.Picture>()

        fun addPhoto(entryId: String?, photo: DemoPhotos.Photo, taken: Long, place: Pair<Double, Double>): Media {
            val id = ids()
            photos[id] = photo
            return Media(
                id = id, tripId = tripId, entryId = entryId, localPath = DemoPhotos.localPath(photo, id), mime = "image/jpeg",
                width = photo.width, height = photo.height, takenAt = taken, lat = place.first, lng = place.second,
                createdAt = t, updatedAt = t,
            ).also { media += it }
        }

        fun addPicture(entryId: String?, pic: SampleRows.Pic, taken: Long, place: Pair<Double, Double>): Media {
            val id = ids()
            val picture = DemoArt.Picture(pic.kind, pic.shape, pic.mirror)
            pictures[id] = picture
            val size = picture.size
            return Media(
                id = id, tripId = tripId, entryId = entryId, localPath = DemoArt.localPath(picture, id), mime = "image/jpeg",
                width = size.width, height = size.height, takenAt = taken, lat = place.first, lng = place.second,
                createdAt = t, updatedAt = t,
            ).also { media += it }
        }
    }

    /**
     * The trip and its plan from [start], [days] long (the Android app's SampleBuilder.plan): items on days before
     * [doneBefore] are ticked, except notes and optional ones; the legs get the rows' travel modes.
     */
    private fun plan(tripId: String, title: String, subtitle: String, start: LocalDate, days: Int, rows: List<SampleRows.Row>, t: Long, doneBefore: Int, ids: () -> String): Pair<Trip, List<PlanItem>> {
        val trip = Trip(tripId, title, subtitle, start, start.plusDays((days - 1).toLong()), trackingLevel = "off", createdAt = t, updatedAt = t)
        val positions = HashMap<Int?, Double>()
        val items = rows.map { r ->
            val pos = (positions[r.day] ?: 0.0) + 1.0
            positions[r.day] = pos
            val (url, maps) = PlanItemText.sortLinks(r.url, null)
            PlanItem(
                id = ids(), tripId = tripId, day = r.day?.let { start.plusDays((it - 1).toLong()) }, position = pos,
                kind = r.kind, title = r.title, note = r.note, lat = r.at?.first, lng = r.at?.second,
                done = r.day != null && r.day < doneBefore && r.kind != "note" && !r.optional,
                createdAt = t, updatedAt = t, url = url, mapsUrl = maps,
            )
        }
        // Flights, trains, boats and walks between the places, set the way the planner would.
        val viaById = rows.zip(items).mapNotNull { (r, item) -> r.via?.let { item.id to it } }.toMap()
        var modes: String? = null
        for (key in PlanRoute.legKeys(trip, items)) {
            val via = viaById[key.toId] ?: continue
            modes = TravelModes.set(modes, key, via)
        }
        return trip.copy(travelModes = modes) to items
    }

    /**
     * The journal entries of [days], each written at [written] with its pictures, placed as Write places photos unless a
     * picture says otherwise (the Android app's SampleBuilder.entries). Weather is never filled in.
     */
    private fun entries(
        tripId: String, zone: String, days: List<SampleRows.Day>, album: Album, t: Long, ids: () -> String,
        written: (SampleRows.Day) -> Long, taken: (SampleRows.Day, SampleRows.Pic, Long) -> Long,
    ): List<Entry> {
        val defaults = PhotoDefaults.Page
        return days.map { d ->
            val id = ids()
            val at = written(d)
            fun snap(s: SampleRows.Snap) = album.addPhoto(id, s.photo, minOf(at(at, zone, s.time), at), s.at ?: d.at)
            val blocks = d.parts.map { part ->
                when (part) {
                    is SampleRows.Text -> Block.Paragraph(part.html)
                    is SampleRows.Check -> Block.Checklist(part.items)
                    is SampleRows.Pic -> {
                        val m = album.addPicture(id, part, minOf(taken(d, part, at), at), part.at ?: d.at)
                        Block.Photo(m.id, rotation = defaults.tiltFor(0), width = defaults.width, frame = defaults.frame)
                    }
                    is SampleRows.Snap -> Block.Photo(
                        snap(part).id, rotation = part.tilt ?: defaults.tiltFor(0), width = part.width ?: defaults.width,
                        frame = part.frame ?: defaults.frame, caption = part.caption,
                    )
                    is SampleRows.Layout -> Block.Album(PhotoAlbums.usual(part.snaps.size).id, part.snaps.map { snap(it).id }).withFrame(part.frame)
                }
            }
            val doc = EntryDoc(blocks = blocks)
            Entry(
                id = id, tripId = tripId, occurredAt = at, zoneId = zone, title = d.title,
                body = doc.toJson(), plainText = NoteText.plainText(doc), placeName = d.place, lat = d.at.first, lng = d.at.second,
                weather = null, tempC = null, createdAt = t, updatedAt = t,
            )
        }
    }

    /** A place stayed, added by hand at the plan's point. No track between them. */
    private fun stop(tripId: String, arrive: Long, leave: Long?, p: Pair<Double, Double>, name: String, country: String, t: Long, ids: () -> String) = Stop(
        id = ids(), tripId = tripId, arrivedAt = arrive, leftAt = leave, lat = p.first, lng = p.second, name = name,
        countryCode = country, source = "manual", createdAt = t, updatedAt = t,
    )

    /** Today's entry: 20 minutes ago, or 12:40 on the trail when that was earlier. Never in the future. */
    fun todayEntryTime(day: LocalDate, now: Long): Long = minOf(latest(now), startOfDayMs(day, SampleArgentina.ZONE) + (12 * 60 + 40) * 60_000L)

    /**
     * "Argentina & Patagonia" for a phone whose date is [today]: day [SampleArgentina.TODAY] of 31 in El Chaltén, the
     * days before ticked, a journal with the real photos, the Fitz Roy cover and the places stayed. [now] places today's
     * entry; [t] stamps every row.
     */
    fun argentina(today: LocalDate, now: Long, t: Long, ids: () -> String = ::newId): SampleDemo {
        val start = argentinaStart(today)
        val zone = SampleArgentina.ZONE
        val (trip, items) = plan(SampleArgentina.TRIP_ID, SampleArgentina.TITLE, SampleArgentina.SUBTITLE, start, SampleArgentina.DAYS, SampleArgentina.rows, t, SampleArgentina.TODAY, ids)
        val album = Album(SampleArgentina.TRIP_ID, t, ids)
        // A set time on a day of the trip, but never after [latest] (a phone far west of Argentina).
        fun at(day: Int, time: String) = minOf(at(start, zone, day, time), latest(now))
        val journal = entries(
            SampleArgentina.TRIP_ID, zone, SampleArgentina.days, album, t, ids,
            written = { d -> d.time?.let { at(d.day, it) } ?: todayEntryTime(start.plusDays((d.day - 1).toLong()), now) },
            taken = { _, _, written -> written },
        )
        // The cover: Fitz Roy from the village, on arrival.
        val cover = album.addPhoto(null, DemoPhotos.FITZ_ROY, at(13, "17:10"), SampleArgentina.CHALTEN)
        fun stay(arrive: Long, leave: Long?, p: Pair<Double, Double>, name: String) = stop(SampleArgentina.TRIP_ID, arrive, leave, p, name, "AR", t, ids)
        val stops = listOf(
            stay(at(1, "19:30"), at(4, "08:00"), SampleArgentina.PALERMO, "Palermo Soho, Buenos Aires"),
            stay(at(4, "14:30"), at(8, "08:00"), -33.62 to -69.20, "Valle de Uco, Mendoza"),
            stay(at(8, "15:00"), at(13, "05:30"), -41.053 to -71.535, "Lake Nahuel Huapi, Bariloche"),
            stay(at(13, "15:30"), null, SampleArgentina.CHALTEN, "El Chaltén"),
        )
        return SampleDemo(trip.copy(coverMediaId = cover.id), items, journal, album.media.toList(), stops, album.photos.toMap())
    }

    /** "Kyoto & Osaka" from [start]: the trip idea's default program at a balanced pace, nothing ticked, no journal. */
    fun kyoto(start: LocalDate, t: Long, ids: () -> String = ::newId): SampleDemo {
        val built = ProgramTrips.build(
            KyotoOsaka.destination, KyotoOsaka.destination.default, Pace.Balanced, start, t,
            links = ::sortLinks, ids = ids, tripId = SampleTrips.KYOTO, trackingLevel = "off",
        )
        return SampleDemo(built.trip, built.items)
    }

    /**
     * "Lisbon & Porto" from [start]: the trip idea's default program at a balanced pace, all done, with its short journal
     * (drawn pictures) and the two places stayed (Lisbon until day 6, then Porto).
     */
    fun lisbon(start: LocalDate, t: Long, ids: () -> String = ::newId): SampleDemo {
        val program = LisbonPorto.destination.default
        val days = program.days.size
        val built = ProgramTrips.build(
            LisbonPorto.destination, program, Pace.Balanced, start, t,
            links = ::sortLinks, ids = ids, tripId = SampleTrips.LISBON, doneBefore = days + 1, trackingLevel = "off",
        )
        val zone = SampleLisbonJournal.ZONE
        val album = Album(SampleTrips.LISBON, t, ids)
        fun at(day: Int, time: String) = at(start, zone, day, time)
        val journal = entries(
            SampleTrips.LISBON, zone, SampleLisbonJournal.days, album, t, ids,
            written = { d -> at(d.day, requireNotNull(d.time)) },
            taken = { d, pic, _ -> at(d.day, pic.time) },
        )
        val stops = listOf(
            stop(SampleTrips.LISBON, at(1, "15:00"), at(6, "09:30"), SampleLisbonJournal.ALFAMA, "Alfama, Lisbon", "PT", t, ids),
            stop(SampleTrips.LISBON, at(6, "14:00"), at(days, "11:00"), SampleLisbonJournal.RIBEIRA, "Ribeira, Porto", "PT", t, ids),
        )
        return SampleDemo(built.trip, built.items, journal, album.media.toList(), stops, pictures = album.pictures.toMap())
    }

    private fun sortLinks(item: PlanItem): PlanItem {
        val (url, maps) = PlanItemText.sortLinks(item.url, item.mapsUrl)
        return if (url == item.url && maps == item.mapsUrl) item else item.copy(url = url, mapsUrl = maps)
    }
}

/**
 * Demo mode on this phone (the Android app's DemoMode): a store install starts with no examples and demo mode off;
 * turning it on adds the examples that are missing (or were deleted) and brings the demo to today; while it is on the
 * demo is kept around today, once a day at most. The examples are rows of the trips' store ([TripBook.putRows]) and
 * their photos files of the app's own ([MediaFiles]), written as an import writes them. The traveller's own trips are
 * never changed. [photo] reads one of the app's own files (the demo's photos); [now] and [ids] are the clock and ids.
 */
class DemoMode(
    private val book: TripBook,
    private val settings: AppSettings,
    private val files: MediaFiles,
    private val now: () -> Long = ::nowMs,
    private val ids: () -> String = ::newId,
    private val photo: suspend (String) -> ByteArray? = { path -> runCatching { Res.readBytes(path) }.getOrNull() },
) {
    /** At the first start: on whenever there are examples (none on a store install), else off. Decided once. */
    fun chooseOnce() {
        if (settings.demoModeChosen) return
        settings.demoMode = DemoTrips.initialMode(hasDemoTrips = book.snapshot.trips.any { it.deletedAt == null && it.isDemo })
    }

    /**
     * Me › Demo mode and Trips › Exit demo. Turning it on adds the examples that are missing and brings the demo back to
     * [today]. Returns when done.
     */
    suspend fun set(on: Boolean, today: LocalDate = today()) {
        settings.demoMode = on
        if (on) {
            ensure(today)
            anchor(force = true, today = today)
        }
    }

    /** A trip of the traveller's own is about to be made: show their trips. True when demo mode was on (say so). */
    fun leave(): Boolean {
        if (!settings.demoMode) return false
        settings.demoMode = false
        return true
    }

    /**
     * Seeds the current examples that are not here or were deleted; what was left of a deleted one is soft-deleted
     * first, so nothing doubles. The traveller's own trips are never changed.
     */
    suspend fun ensure(today: LocalDate) {
        val missing = SampleTrips.missing(book.snapshot.trips)
        if (missing.isNotEmpty()) {
            val t = now()
            val demos = missing.map { id ->
                when (id) {
                    SampleArgentina.TRIP_ID -> Samples.argentina(today, now(), t, ids)
                    SampleTrips.KYOTO -> Samples.kyoto(Samples.kyotoStart(today), t, ids)
                    else -> Samples.lisbon(Samples.lisbonStart(today), t, ids)
                }
            }
            val media = demos.flatMap { writeFiles(it) }
            val s = book.snapshot
            val back = missing.toSet()
            val entryIds = s.entries.filter { it.tripId in back }.mapTo(HashSet()) { it.id }
            // What a deleted example left behind goes first (its rows stay deleted when a trip is deleted).
            val gone = Pair(
                s.planItems.filter { it.tripId in back && it.deletedAt == null }.map { it.copy(deletedAt = t, updatedAt = t) },
                s.entries.filter { it.tripId in back && it.deletedAt == null }.map { it.copy(deletedAt = t, updatedAt = t) },
            )
            val goneMedia = s.media.filter { (it.tripId in back || it.entryId in entryIds) && it.deletedAt == null }.map { it.copy(deletedAt = t, updatedAt = t) }
            val goneStops = s.stops.filter { it.tripId in back && it.deletedAt == null }.map { it.copy(deletedAt = t, updatedAt = t) }
            book.putRows(
                trips = demos.map { it.trip },
                items = gone.first + demos.flatMap { it.items },
                entries = gone.second + demos.flatMap { it.entries },
                media = goneMedia + media,
                stops = goneStops + demos.flatMap { it.stops },
            )
        }
        settings.sampleVersion = SampleTrips.VERSION
    }

    /** Writes [demo]'s pictures under media/ (real photos copied from the app's files, drawn ones drawn); the rows with checksums. */
    private suspend fun writeFiles(demo: SampleDemo): List<Media> = demo.media.mapNotNull { m ->
        val bytes = demo.photos[m.id]?.let { photo(it.resource) } ?: demo.pictures[m.id]?.let { DemoArt.jpeg(it) }
        if (bytes != null && files.write(m.localPath, bytes)) m.copy(sha256 = Sha256.hex(bytes)) else m
    }

    /** Moves the whole demo so [today] is Argentina's day 14 again; at most once a day unless [force]. */
    fun anchor(force: Boolean, today: LocalDate = today()) {
        if (!settings.demoMode) return
        if (!force && settings.demoAnchoredDay == today.toEpochDay()) return
        val s = book.snapshot
        val rows = s.trips.filter { it.isDemo }.map { t ->
            DemoTrips.Rows(
                t,
                s.planItems.filter { it.tripId == t.id && it.deletedAt == null },
                s.entries.filter { it.tripId == t.id && it.deletedAt == null },
                s.media.filter { it.tripId == t.id && it.deletedAt == null },
                s.stops.filter { it.tripId == t.id && it.deletedAt == null },
            )
        }
        val moved = DemoTrips.reanchor(rows, today, now())
        if (moved.isNotEmpty()) {
            book.putRows(
                trips = moved.map { it.trip }, items = moved.flatMap { it.items }, entries = moved.flatMap { it.entries },
                media = moved.flatMap { it.media }, stops = moved.flatMap { it.stops },
            )
        }
        settings.demoAnchoredDay = today.toEpochDay()
    }

    companion object {
        /**
         * An example trip's new start (Edit trip): the whole example moves by the same number of days (dates, plan days,
         * entry and photo times, stays; [DemoTrips.shift]) and takes [title] and [subtitle], as the Android app's
         * TripRepository.moveDemo. False when it isn't a live example trip with dates.
         */
        fun moveDemo(book: TripBook, tripId: String, start: LocalDate, title: String, subtitle: String, now: Long = nowMs()): Boolean {
            val s = book.snapshot
            val trip = s.trips.firstOrNull { it.id == tripId && it.deletedAt == null && it.isDemo } ?: return false
            if (trip.startDate == null) return false
            val days = DemoTrips.delta(trip, start)
            val rows = DemoTrips.Rows(
                trip,
                s.planItems.filter { it.tripId == tripId && it.deletedAt == null },
                s.entries.filter { it.tripId == tripId && it.deletedAt == null },
                s.media.filter { it.tripId == tripId && it.deletedAt == null },
                s.stops.filter { it.tripId == tripId && it.deletedAt == null },
            )
            val moved = DemoTrips.shift(rows, days, SampleArgentina.ZONE)
            val t = maxOf(now, trip.updatedAt + 1)
            fun stamped(updated: Long) = maxOf(t, updated + 1)
            val editedTrip = moved.trip.copy(title = title.trim(), subtitle = subtitle.trim())
            if (days == 0L && editedTrip == trip) return true
            book.putRows(
                trips = listOf(editedTrip.copy(updatedAt = stamped(trip.updatedAt))),
                items = if (days == 0L) emptyList() else moved.items.map { it.copy(updatedAt = stamped(it.updatedAt)) },
                entries = if (days == 0L) emptyList() else moved.entries.map { it.copy(updatedAt = stamped(it.updatedAt)) },
                media = if (days == 0L) emptyList() else moved.media.map { it.copy(updatedAt = stamped(it.updatedAt)) },
                stops = if (days == 0L) emptyList() else moved.stops.map { it.copy(updatedAt = stamped(it.updatedAt)) },
            )
            return true
        }
    }
}
