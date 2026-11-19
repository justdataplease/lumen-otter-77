package com.justdataplease.boringtravel.world

import com.justdataplease.boringtravel.data.db.CountryMark
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.geo.BBox
import com.justdataplease.boringtravel.geo.Continent
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.geo.GeoPoint
import com.justdataplease.boringtravel.stats.TripStats
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.civil
import com.justdataplease.boringtravel.time.localDateAt
import kotlin.math.roundToLong

/*
 * The traveller's world as the Android app works it out (world/WorldModel.kt there), written again because that file
 * compares days with java.time and calls Java's Math and putIfAbsent: the same names, rules and results, with days
 * compared by their epoch day and a time zone given by its id. The Android app's own WorldModelTest runs against it.
 */

/** Everything the world views read, from the phone's own rows. */
data class WorldInput(
    val trips: List<Trip> = emptyList(),
    val stops: List<Stop> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val media: List<Media> = emptyList(),
    val planItems: List<PlanItem> = emptyList(),
    val marks: List<CountryMark> = emptyList(),
    /** Route length per trip id, in metres (TripStats.meters over each trip's points). */
    val metersByTrip: Map<String, Double> = emptyMap(),
)

/** One country as the world views show it: visited, planned or wished. */
data class CountryStat(
    /** ISO alpha-2 (or "XK"), see [CountryInfo.key]. */
    val code: String,
    val continent: Continent?,
    val visited: Boolean,
    /** Trips with a stop, entry, photo or ticked plan item here, newest first. */
    val tripIds: List<String> = emptyList(),
    /** Trips still to come (or someday) with a planned place here, soonest first. */
    val plannedTripIds: List<String> = emptyList(),
    val days: Int = 0,
    val places: Int = 0,
    val entries: Int = 0,
    val photos: Int = 0,
    val firstVisit: LocalDate? = null,
    /** Added by hand ("I've been here"). */
    val manual: Boolean = false,
    /** Found from the traveller's own rows (so removing it hides it instead). */
    val found: Boolean = false,
    val wished: Boolean = false,
    /** The best photo from here: a chosen trip cover, else a journal photo, else any photo taken here. */
    val heroMediaId: String? = null,
    /** Mean of the traveller's own points here (where the pin goes when the table has no label point). */
    val ownCenter: GeoPoint? = null,
    /** The box around the traveller's own points here ("Show on the map"). */
    val ownBox: BBox? = null,
)

data class World(
    /** Visited countries, earliest first visit first (hand-added ones without a date last). */
    val visited: List<CountryStat> = emptyList(),
    /** Every country with something to show: visited, planned or wished. */
    val byCode: Map<String, CountryStat> = emptyMap(),
    /** Planned in a trip to come and not visited yet (dashed on the globe). */
    val planned: Set<String> = emptySet(),
    /** "Want to go", not visited yet (dashed too). */
    val wished: Set<String> = emptySet(),
    /** The next trip with dates and the countries it plans that aren't visited yet. */
    val nextTripId: String? = null,
    val nextCodes: List<String> = emptyList(),
    /** Each trip's countries in travel order (flags on trip cards). */
    val tripCountries: Map<String, List<String>> = emptyMap(),
    /**
     * Per trip, the countries of the dates something places it (in the order they came): stops, entries, photos and
     * ticked items, else the plan items on that day. Only dates with something; the Calendar tab fills the rest.
     */
    val tripDayCountries: Map<String, Map<LocalDate, List<String>>> = emptyMap(),
    val continents: Map<Continent, Int> = emptyMap(),
    val worldPercent: Int = 0,
    /** Trips started so far (past and today's). */
    val trips: Int = 0,
    val daysAway: Int = 0,
    val km: Double = 0.0,
    val sinceYear: Int? = null,
    val hasTrips: Boolean = false,
    val tripsById: Map<String, Trip> = emptyMap(),
) {
    val countries: Int get() = visited.size
    val visitedCodes: Set<String> by lazy { visited.mapTo(HashSet()) { it.code } }

    /** A country's continent counts, "Europe 6": visited ones, plus 0 rows for continents only planned so far. */
    fun continentRows(): List<Pair<Continent, Int>> {
        val rows = continents.toMutableMap()
        (planned + wished).mapNotNull(CountryInfo::continentOf).forEach { if (it !in rows) rows[it] = 0 }
        return rows.entries.sortedWith(compareByDescending<Map.Entry<Continent, Int>> { it.value }.thenBy { it.key.ordinal }).map { it.key to it.value }
    }

    companion object {
        val EMPTY = World()
    }
}

private val LocalDate.ep: Long get() = toEpochDay()

/**
 * Works out the traveller's world. Visited countries come from (all through the offline country lookup [codeAt]):
 * stops (their stored code first), journal entries with a place, photos with a location, plan items ticked done, and
 * countries added by hand; a country removed by hand stays out. Only live rows count (no deletedAt), in trips that
 * aren't deleted or outside any trip. Planned = places in trips still to come (or someday) that aren't visited yet.
 *
 * Days per country: each day of a trip goes to the country of that day's stops, entries, photos or ticked items; a
 * day with none carries the last known country (or the next one). Rows outside trips add the days they happened on.
 */
object WorldModel {
    private val PLACE_KINDS = setOf("place", "food", "stay", "activity")

    private class Acc(val code: String) {
        val trips = LinkedHashSet<String>()
        val planned = LinkedHashSet<String>()
        val tripless = HashSet<LocalDate>()
        val placeNames = HashSet<String>()
        val placeCells = HashSet<Long>()
        var places = 0
        var entries = 0
        var photos = 0
        var first: LocalDate? = null
        var sumLat = 0.0
        var sumLng = 0.0
        var points = 0
        var box: BBox? = null
        var found = false

        fun seen(date: LocalDate?) {
            val f = first
            if (date != null && (f == null || date.ep < f.ep)) first = date
        }

        fun point(lat: Double, lng: Double) {
            sumLat += lat
            sumLng += lng
            points++
            val b = BBox(lat, lng, lat, lng)
            box = box?.union(b) ?: b
        }

        /** Counts a place unless its name or its ~1 km cell was counted already. */
        fun place(name: String?, lat: Double, lng: Double) {
            val key = name?.split(',')?.firstOrNull()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val cell = cell(lat, lng)
            val newName = key == null || key !in placeNames
            val newCell = cell !in placeCells
            if (newName && newCell) places++
            key?.let { placeNames += it }
            placeCells += cell
        }
    }

    /** A ~1 km grid cell id (0.01°), for telling places apart and for caching lookups. */
    fun cell(lat: Double, lng: Double): Long {
        val a = (lat * 100).roundToLong() + 9_000
        val b = (lng * 100).roundToLong() + 18_000
        return a * 40_000 + b
    }

    /** [zone]: the zone's id the days are counted in (null: the phone's), as the Android app's ZoneId. */
    fun build(input: WorldInput, today: LocalDate, zone: String?, nowMs: Long, codeAt: (Double, Double) -> String?): World {
        val trips = input.trips.filter { it.deletedAt == null }
        val tripById = trips.associateBy { it.id }
        fun liveTrip(id: String?) = id == null || id in tripById

        val live = input.marks.filter { it.deletedAt == null }
        val manual = live.filter { it.source == CountryMark.SOURCE_MANUAL }.mapNotNullTo(LinkedHashSet()) { CountryInfo.key(it.code) }
        val hidden = live.filter { it.source == CountryMark.SOURCE_HIDDEN }.mapNotNullTo(HashSet()) { CountryInfo.key(it.code) } - manual
        val wishMarks = live.filter { it.source == CountryMark.SOURCE_WISH }.mapNotNullTo(LinkedHashSet()) { CountryInfo.key(it.code) }

        val acc = LinkedHashMap<String, Acc>()
        fun accOf(code: String) = acc.getOrPut(code) { Acc(code) }
        // Per trip: the countries seen on each date, and every country seen at all (for undated evidence).
        val tripDays = HashMap<String, HashMap<LocalDate, LinkedHashSet<String>>>()
        val tripAny = HashMap<String, LinkedHashSet<String>>()
        val tripOrder = HashMap<String, MutableList<Pair<Long, String>>>()
        // Per trip and date, for the Calendar tab: the countries seen (with their time) and the ones planned that day.
        val seenOn = HashMap<String, HashMap<LocalDate, MutableList<Pair<Long, String>>>>()
        val plannedOn = HashMap<String, HashMap<LocalDate, LinkedHashSet<String>>>()

        fun evidence(code: String, tripId: String?, dates: List<LocalDate>, order: Long) {
            val a = accOf(code)
            a.found = true
            dates.forEach(a::seen)
            if (tripId != null) {
                a.trips += tripId
                tripAny.getOrPut(tripId) { LinkedHashSet() } += code
                val byDay = tripDays.getOrPut(tripId) { HashMap() }
                dates.forEach { byDay.getOrPut(it) { LinkedHashSet() } += code }
                val seen = seenOn.getOrPut(tripId) { HashMap() }
                dates.forEach { seen.getOrPut(it) { ArrayList() } += order to code }
                tripOrder.getOrPut(tripId) { ArrayList() } += order to code
                tripById[tripId]?.startDate?.let(a::seen)
            } else {
                a.tripless += dates
            }
        }

        // An entry's own zone when it has one, else [zone] (the engine reads a zone it doesn't know as the phone's).
        fun dateOf(ms: Long, zoneId: String? = null): LocalDate = localDateAt(ms, zoneId?.takeIf { it.isNotBlank() } ?: zone)

        for (s in input.stops) {
            if (s.deletedAt != null || !liveTrip(s.tripId)) continue
            val code = CountryInfo.key(s.countryCode) ?: CountryInfo.key(codeAt(s.lat, s.lng)) ?: continue
            if (code in hidden) continue
            val from = dateOf(s.arrivedAt)
            val to = dateOf(minOf(s.leftAt ?: nowMs, nowMs).coerceAtLeast(s.arrivedAt))
            val dates = ArrayList<LocalDate>()
            var d = from
            while (d.ep <= to.ep && dates.size < 400) { dates += d; d = d.plusDays(1) }
            evidence(code, s.tripId, dates, s.arrivedAt)
            accOf(code).apply { point(s.lat, s.lng); place(s.name.ifBlank { null }, s.lat, s.lng) }
        }
        for (e in input.entries) {
            val lat = e.lat ?: continue
            val lng = e.lng ?: continue
            if (e.deletedAt != null || !liveTrip(e.tripId)) continue
            val code = CountryInfo.key(codeAt(lat, lng)) ?: continue
            if (code in hidden) continue
            evidence(code, e.tripId, listOf(dateOf(e.occurredAt, e.zoneId)), e.occurredAt)
            accOf(code).apply { point(lat, lng); entries++; place(e.placeName.ifBlank { null }, lat, lng) }
        }
        for (m in input.media) {
            val lat = m.lat ?: continue
            val lng = m.lng ?: continue
            if (m.deletedAt != null || !liveTrip(m.tripId)) continue
            val code = CountryInfo.key(codeAt(lat, lng)) ?: continue
            if (code in hidden) continue
            val taken = m.takenAt
            evidence(code, m.tripId, listOfNotNull(taken?.let { dateOf(it) }), taken ?: m.createdAt)
            // A video places the traveller there too; only photos are counted and shown as the country's picture.
            accOf(code).apply { point(lat, lng); if (!m.isVideo) photos++ }
        }
        // Plan items: ticked ones are visits; the rest of a trip still to come are plans.
        val plannedCodes = LinkedHashSet<String>()
        val plannedByTrip = HashMap<String, LinkedHashSet<String>>()
        for (i in input.planItems.sortedWith(compareBy<PlanItem>({ it.day == null }, { it.day?.ep }, { it.position }))) {
            val lat = i.lat ?: continue
            val lng = i.lng ?: continue
            val trip = tripById[i.tripId] ?: continue
            if (i.deletedAt != null) continue
            val code = CountryInfo.key(codeAt(lat, lng)) ?: continue
            val day = i.day
            if (!i.done && day != null && code !in hidden) plannedOn.getOrPut(trip.id) { HashMap() }.getOrPut(day) { LinkedHashSet() } += code
            if (i.done) {
                if (code in hidden) continue
                evidence(code, i.tripId, listOfNotNull(day), day?.ep?.times(86_400_000L) ?: Long.MAX_VALUE)
                accOf(code).apply {
                    point(lat, lng)
                    if (i.kind in PLACE_KINDS) place(i.placeName.ifBlank { null }, lat, lng)
                }
            } else if (!trip.isPast(today)) {
                plannedCodes += code
                plannedByTrip.getOrPut(trip.id) { LinkedHashSet() } += code
                accOf(code).planned += trip.id
            }
        }
        manual.forEach { accOf(it) }

        // Days per country, trip by trip.
        val days = HashMap<String, Int>()
        for (trip in trips) {
            val start = trip.startDate ?: continue
            val end = trip.endDate ?: continue
            if (start.ep > today.ep) continue
            val last = if (end.ep > today.ep) today else end
            val byDay = tripDays[trip.id].orEmpty()
            val any = tripAny[trip.id].orEmpty()
            if (any.isEmpty()) continue
            val span = (last.ep - start.ep + 1).toInt().coerceIn(0, 3_660)
            val dated = byDay.keys.filter { it.ep >= start.ep && it.ep <= last.ep }.sortedBy { it.ep }
            for (k in 0 until span) {
                val d = start.plusDays(k.toLong())
                val here: Set<String> = byDay[d]
                    ?: dated.lastOrNull { it.ep < d.ep }?.let { byDay[it] }
                    ?: dated.firstOrNull { it.ep > d.ep }?.let { byDay[it] }
                    ?: any.takeIf { it.size == 1 }
                    ?: continue
                here.forEach { days[it] = (days[it] ?: 0) + 1 }
            }
        }

        val visitedCodes = acc.values.filter { it.found || it.code in manual }.map { it.code }.toSet()
        val planned = plannedCodes.filterTo(LinkedHashSet()) { it !in visitedCodes }
        val wished = wishMarks.filterTo(LinkedHashSet()) { it !in visitedCodes }
        wished.forEach { accOf(it) }

        // Hero photos: a trip cover the traveller chose (when it was taken here, or has no place), else a journal photo
        // from here, else any photo from here; newest trip first.
        val mediaById = input.media.filter { it.deletedAt == null }.associateBy { it.id }
        val photoCode = HashMap<String, String?>()
        fun codeOfPhoto(m: Media): String? = photoCode.getOrPut(m.id) {
            val lat = m.lat
            val lng = m.lng
            if (lat == null || lng == null) null else CountryInfo.key(codeAt(lat, lng))
        }

        val stats = LinkedHashMap<String, CountryStat>()
        for (a in acc.values) {
            val isVisited = a.code in visitedCodes
            val tripIds = a.trips.mapNotNull { tripById[it] }
                .sortedWith(compareByDescending<Trip> { it.startDate?.ep ?: Long.MIN_VALUE }.thenByDescending { it.createdAt }).map { it.id }
            val plannedTrips = a.planned.mapNotNull { tripById[it] }
                .sortedWith(compareBy<Trip>({ it.startDate == null }, { it.startDate?.ep })).map { it.id }
            val hero = tripIds.asSequence().mapNotNull { tripById[it]?.coverMediaId?.let(mediaById::get) }
                .firstOrNull { codeOfPhoto(it).let { c -> c == null || c == a.code } }?.id
                ?: input.media.asSequence().filter { it.deletedAt == null && !it.isVideo && liveTrip(it.tripId) && codeOfPhoto(it) == a.code }
                    .sortedWith(compareBy<Media>({ it.entryId == null }, { -(it.takenAt ?: it.createdAt) })).firstOrNull()?.id
            val tripDaysHere = days[a.code] ?: 0
            val triplessDays = a.tripless.count { d ->
                trips.none { t ->
                    val s = t.startDate
                    val e = t.endDate
                    s != null && e != null && d.ep >= s.ep && d.ep <= e.ep && a.code in tripAny[t.id].orEmpty()
                }
            }
            stats[a.code] = CountryStat(
                code = a.code,
                continent = CountryInfo.continentOf(a.code),
                visited = isVisited,
                tripIds = tripIds,
                plannedTripIds = plannedTrips,
                days = tripDaysHere + triplessDays,
                places = a.places,
                entries = a.entries,
                photos = a.photos,
                firstVisit = a.first,
                manual = a.code in manual,
                found = a.found,
                wished = a.code in wishMarks,
                heroMediaId = hero,
                ownCenter = if (a.points > 0) GeoPoint(a.sumLat / a.points, a.sumLng / a.points) else null,
                ownBox = a.box,
            )
        }

        val visited = stats.values.filter { it.visited }
            .sortedWith(compareBy<CountryStat>({ it.firstVisit == null }, { it.firstVisit?.ep }, { it.code }))
        val continents = HashMap<Continent, Int>()
        visited.forEach { s -> s.continent?.let { continents[it] = (continents[it] ?: 0) + 1 } }

        // Flags per trip: where it went (in order), then for trips still to come, where it plans to go.
        val tripCountries = HashMap<String, List<String>>()
        for (t in trips) {
            val went = tripOrder[t.id].orEmpty().sortedBy { it.first }.map { it.second }.distinct().filter { it in visitedCodes }
            val plans = if (t.isPast(today)) emptyList() else plannedByTrip[t.id].orEmpty().filter { it !in went }
            val all = went + plans
            if (all.isNotEmpty()) tripCountries[t.id] = all
        }

        // Countries per trip date: what happened that day wins over what was planned for it.
        val tripDayCountries = HashMap<String, Map<LocalDate, List<String>>>()
        for (t in trips) {
            val seen = seenOn[t.id].orEmpty()
            val plan = plannedOn[t.id].orEmpty()
            if (seen.isEmpty() && plan.isEmpty()) continue
            val byDate = HashMap<LocalDate, List<String>>()
            plan.forEach { (d, codes) -> byDate[d] = codes.toList() }
            seen.forEach { (d, list) -> byDate[d] = list.sortedBy { it.first }.map { it.second }.distinct() }
            tripDayCountries[t.id] = byDate
        }

        val next = trips.filter { t -> t.startDate?.let { it.ep > today.ep } == true }.sortedBy { it.startDate?.ep }
            .firstOrNull { plannedByTrip[it.id].orEmpty().any { c -> c !in visitedCodes } }
        val nextCodes = next?.let { plannedByTrip[it.id].orEmpty().filter { c -> c !in visitedCodes } }.orEmpty()

        val started = trips.filter { t -> t.startDate?.let { it.ep <= today.ep } == true }
        val since = (started.mapNotNull { it.startDate } + visited.mapNotNull { it.firstVisit }).minByOrNull { it.ep }?.civil?.year

        return World(
            visited = visited,
            byCode = stats,
            planned = planned,
            wished = wished,
            nextTripId = next?.id,
            nextCodes = nextCodes,
            tripCountries = tripCountries,
            tripDayCountries = tripDayCountries,
            continents = continents,
            worldPercent = CountryInfo.worldPercent(visited.map { it.code }),
            trips = started.size,
            daysAway = TripStats.daysAway(trips, today),
            km = started.sumOf { input.metersByTrip[it.id] ?: 0.0 } / 1000.0,
            sinceYear = since,
            hasTrips = trips.isNotEmpty(),
            tripsById = tripById,
        )
    }
}
