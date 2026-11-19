package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.clip.Geo
import com.justdataplease.boringtravel.clip.RouteLeg
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.time.startOfDayMs

/*
 * Where a plan day is, for its header ("Day 4 · Naxos"), as the Android app works it out (places/DayPlace.kt,
 * places/PlaceNames.kt, ui/trip/PlanDayLabel.kt and PlaceGrouping.itemPlaceLabel in ui/place/PlaceModel.kt), written
 * again here because those files use java.time and Java's Locale. Same rules, same results; they go once the Android
 * files move into the engine.
 */

/**
 * Every country's name in English and in the phone's language, lowercase: what [PlaceNames.area] drops from the end
 * of a place name. The phone's own list (Java's Locale on the desktop, Foundation's NSLocale on iPhone).
 */
expect fun countryNames(): Set<String>

private val countries: Set<String> by lazy { countryNames() }

/** Short place texts: "Halki, Naxos, Greece" for lists, "Naxos" for a day header. */
object PlaceNames {
    // Administrative words that make names long without telling the traveller anything ("Naxos Regional Unit").
    private val suffixes = listOf("Regional Unit", "Municipal Unit", "Municipality", "County", "Prefecture")
    private val prefixes = listOf("Regional Unit of ", "Municipality of ", "County ")

    /** "Naxos Regional Unit" → "Naxos", "County Kerry" → "Kerry"; anything else unchanged. */
    fun clean(area: String): String {
        var s = area.trim()
        for (p in prefixes) if (s.startsWith(p, ignoreCase = true) && s.length > p.length) s = s.substring(p.length).trim()
        for (x in suffixes) if (s.endsWith(" $x", ignoreCase = true)) s = s.dropLast(x.length + 1).trim()
        return s
    }

    /**
     * "name, area, country" with empty parts and repeats left out. [areas] are tried in order (city, county, state)
     * and the first one that says something new is used.
     */
    fun compact(name: String, areas: List<String?>, country: String?): String {
        val n = name.trim()
        val area = areas.asSequence()
            .mapNotNull { it?.let(::clean)?.takeIf(String::isNotBlank) }
            .firstOrNull { !it.equals(n, ignoreCase = true) && !it.equals(country?.trim(), ignoreCase = true) }
        return listOfNotNull(n.takeIf(String::isNotBlank), area, country?.trim()?.takeIf { it.isNotBlank() && !it.equals(n, ignoreCase = true) })
            .joinToString(", ")
    }

    /**
     * The area a place name belongs to, for "Day 4 · Naxos": the last part after dropping the country
     * ("Halki, Naxos, Greece" → "Naxos", "Agios Georgios, Naxos" → "Naxos", "Naxos, Greece" → "Naxos").
     * Three or more parts are the app's own "name, area, country" form, so the last part is always the country.
     */
    fun area(placeName: String): String? {
        val parts = placeName.split(',').map(String::trim).filter(String::isNotBlank)
        if (parts.isEmpty()) return null
        val dropCountry = parts.size >= 3 || (parts.size == 2 && parts.last().lowercase() in countries)
        return (if (dropCountry) parts.dropLast(1) else parts).last()
    }
}

object PlaceGrouping {
    /** Items this close to the anchor are at the same place: a town and its trailheads, a city and its airport. */
    const val RADIUS_M = 40_000.0

    fun located(item: PlanItem): Boolean {
        val lat = item.lat
        val lng = item.lng
        return lat != null && lng != null && lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
    }

    /**
     * The place a plan item names: the area of its place ("Halki, Naxos, Greece" → "Naxos"), else for travel the
     * destination in its title ("Ferry to Naxos" → "Naxos"), else its title.
     */
    fun itemPlaceLabel(item: PlanItem): String {
        item.placeName.takeIf { it.isNotBlank() }?.let { name -> PlaceNames.area(name)?.let { return it } }
        val title = item.title.trim()
        if (item.kind == PlanKind.Transport.id) {
            val m = Regex("\\bto\\s+(.+)$", RegexOption.IGNORE_CASE).find(title)
            if (m != null) return m.groupValues[1].trim()
        }
        return title
    }

    /**
     * The place's name from the plan itself: the destination of the latest travel item up to [day] that lies near the
     * spot ("Transfer to El Chaltén" for a trail outside El Chaltén). Null when no such arrival is in the plan.
     */
    fun arrivalName(items: List<PlanItem>, day: LocalDate?, lat: Double, lng: Double, radiusM: Double = RADIUS_M): String? = items
        .filter { it.deletedAt == null && it.kind == PlanKind.Transport.id && located(it) && (day == null || it.day?.let { d -> d.toEpochDay() <= day.toEpochDay() } == true) }
        .filter { Geo.distanceM(lat, lng, it.lat!!, it.lng!!) <= radiusM }
        .sortedWith(compareBy<PlanItem>({ it.day == null }, { it.day?.toEpochDay() }, { it.position }, { it.id }))
        .mapNotNull { item -> itemPlaceLabel(item).takeIf { it != item.title.trim() || item.placeName.isNotBlank() } }
        .lastOrNull()

    /**
     * The place a located item is in: the plan's arrival near it ("Plane from Mendoza", not from the last show there),
     * else its own place label.
     */
    fun placeOf(items: List<PlanItem>, item: PlanItem?): String? {
        if (item == null) return null
        if (located(item)) arrivalName(items, item.day, item.lat!!, item.lng!!)?.let { return it }
        return itemPlaceLabel(item)
    }

    // ---- The place page (the Android app's ui/place/PlaceModel.kt) ----

    private val SIGHT_KINDS = setOf(PlanKind.Place.id, PlanKind.Food.id, PlanKind.Activity.id)

    /** Live items of the trip in plan order (by day, each day in time order, ideas last). */
    fun ordered(tripId: String, items: List<PlanItem>): List<PlanItem> =
        PlanSpan.ordered(items.filter { it.tripId == tripId && it.deletedAt == null })

    /** Located items within [radiusM] of the anchor. */
    fun near(items: List<PlanItem>, lat: Double, lng: Double, radiusM: Double = RADIUS_M): List<PlanItem> =
        items.filter { located(it) && Geo.distanceM(lat, lng, it.lat!!, it.lng!!) <= radiusM }

    /**
     * The items at the place, in plan order: located items near the anchor or near one of those (one step out), plus
     * each unlocated item on a day whose located item just before it (or, when none, just after it) is at the place.
     * Ideas (no day) only count when they are located.
     */
    fun itemsAt(tripId: String, items: List<PlanItem>, lat: Double, lng: Double, radiusM: Double = RADIUS_M): List<PlanItem> {
        val ordered = ordered(tripId, items)
        val first = near(ordered, lat, lng, radiusM)
        val nearIds = (first + first.flatMap { f -> near(ordered, f.lat!!, f.lng!!, radiusM) }).mapTo(HashSet()) { it.id }
        val out = ArrayList<PlanItem>()
        for ((day, dayItems) in ordered.groupBy { it.day }) {
            for ((i, item) in dayItems.withIndex()) {
                if (item.id in nearIds) { out += item; continue }
                if (day == null || located(item)) continue
                val context = dayItems.subList(0, i).lastOrNull(::located) ?: dayItems.subList(i + 1, dayItems.size).firstOrNull(::located)
                if (context != null && context.id in nearIds) out += item
            }
        }
        return ordered.filter { o -> out.any { it.id == o.id } }
    }

    /** The distinct days of [items], in order. */
    fun days(items: List<PlanItem>): List<LocalDate> = items.mapNotNull { it.day }.distinctBy { it.toEpochDay() }.sortedBy { it.toEpochDay() }

    /** "Day 13" numbers inside the trip's dates. */
    fun dayNumbers(trip: Trip, days: List<LocalDate>): List<Int> {
        val start = trip.startDate ?: return emptyList()
        val end = trip.endDate ?: return emptyList()
        return days.filter { it.toEpochDay() in start.toEpochDay()..end.toEpochDay() }.map { (it.toEpochDay() - start.toEpochDay() + 1).toInt() }
    }

    /** "Day 13", "Days 13–16", "Days 4, 6–8". */
    fun daysLabel(numbers: List<Int>): String? {
        val n = numbers.distinct().sorted()
        if (n.isEmpty()) return null
        if (n.size == 1) return "Day ${n[0]}"
        val runs = ArrayList<String>()
        var start = n[0]
        var prev = n[0]
        for (x in n.drop(1) + Int.MIN_VALUE) {
            if (x == prev + 1) { prev = x; continue }
            runs += if (start == prev) "$start" else "$start–$prev"
            start = x
            prev = x
        }
        return "Days ${runs.joinToString(", ")}"
    }

    /** Which "How long" chip the plan's length falls in: 0 up to 2 days, 1 for 3–4, 2 for 5 or more; null with none. */
    fun lengthBucket(days: Int): Int? = when {
        days <= 0 -> null
        days <= 2 -> 0
        days <= 4 -> 1
        else -> 2
    }

    val LENGTH_LABELS = listOf("2 days", "3–4 days", "5+ days")

    /** The first leg into the place from outside it, and the first leg out of it after that. */
    fun legs(trip: Trip, items: List<PlanItem>, atIds: Set<String>): Pair<LegInfo?, LegInfo?> {
        val legs = RouteLegs.planned(trip, items)
        val byId = items.associateBy { it.id }
        fun info(leg: RouteLeg): LegInfo {
            val from = byId[leg.key.fromItemId]
            val to = byId[leg.key.toItemId]
            val d = if (from != null && to != null && located(from) && located(to)) Geo.distanceM(from.lat!!, from.lng!!, to.lat!!, to.lng!!) else null
            return LegInfo(leg, placeOf(items, from) ?: leg.fromTitle, placeOf(items, to) ?: leg.toTitle, to, d)
        }
        val inIndex = legs.indexOfFirst { it.key.toItemId in atIds && it.key.fromItemId !in atIds }
        val outIndex = legs.withIndex().indexOfFirst { (i, it) -> i > inIndex && it.key.fromItemId in atIds && it.key.toItemId !in atIds }
        return legs.getOrNull(inIndex)?.let(::info) to legs.getOrNull(outIndex)?.let(::info)
    }

    fun summarize(trip: Trip, items: List<PlanItem>, lat: Double, lng: Double, radiusM: Double = RADIUS_M): PlaceSummary {
        val at = itemsAt(trip.id, items, lat, lng, radiusM)
        val days = days(at)
        val (arrival, departure) = legs(trip, items.filter { it.tripId == trip.id && it.deletedAt == null }, at.mapTo(HashSet()) { it.id })
        return PlaceSummary(
            anchorLat = lat, anchorLng = lng, items = at, days = days, dayNumbers = dayNumbers(trip, days),
            sights = at.filter { it.kind in SIGHT_KINDS },
            stays = at.filter { it.kind == PlanKind.Stay.id },
            notes = at.filter { it.kind == PlanKind.Note.id },
            arrival = arrival, departure = departure,
        )
    }

    /** "Car from El Calafate · ≈ 3 h", "Plane from Bariloche". */
    fun arrivalLine(leg: LegInfo): String {
        val how = leg.vehicle.label
        val estimate = leg.distanceM?.let { LegEstimate.minutes(leg.vehicle, it) }?.let(LegEstimate::short)
        return listOfNotNull("$how from ${leg.fromLabel}", estimate).joinToString(" · ")
    }

    /** Photos taken within [radiusM] of the anchor, newest first (the place's pictures: videos left out). */
    fun photosNear(media: List<Media>, lat: Double, lng: Double, radiusM: Double = RADIUS_M): List<Media> = media
        .filter { m ->
            val mLat = m.lat
            val mLng = m.lng
            m.deletedAt == null && !m.isVideo && mLat != null && mLng != null && Geo.distanceM(lat, lng, mLat, mLng) <= radiusM
        }
        .sortedByDescending { it.takenAt ?: it.createdAt }

    /** A short sticker label from an item title: before a colon or " and ", at most [max] characters. */
    fun sticker(title: String, max: Int = 18): String {
        val t = title.substringAfter(": ", title).substringBefore(" and ").trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }
}

/**
 * Everything the place page says, built only from the trip's own plan and photos: [items] at the place in trip order,
 * [days] they sit on, the sights, stays and notes among them, and the legs in and out.
 */
data class PlaceSummary(
    val anchorLat: Double,
    val anchorLng: Double,
    val items: List<PlanItem>,
    val days: List<LocalDate>,
    val dayNumbers: List<Int>,
    val sights: List<PlanItem>,
    val stays: List<PlanItem>,
    val notes: List<PlanItem>,
    val arrival: LegInfo?,
    val departure: LegInfo?,
) {
    val inPlan: Boolean get() = items.isNotEmpty()
}

/** A leg in or out of a place: the vehicle, both ends and the leg's destination item. */
data class LegInfo(val leg: RouteLeg, val fromLabel: String, val toLabel: String, val toItem: PlanItem?, val distanceM: Double?) {
    val vehicle: Vehicle get() = leg.travel
}

/**
 * The plan item a day's title opens the place page on: the day's first located item that is not travel and lies in the
 * title's place, else the day's arrival into that place, else the day's first located item that is not travel, else
 * the day's last located item, else the last located item before the day. Null: nothing to open.
 */
object PlanDayAnchor {
    fun item(day: LocalDate, items: List<PlanItem>, place: String?): PlanItem? {
        val live = PlanSpan.ordered(items.filter { it.deletedAt == null })
            .filter { it.kind != PlanKind.Note.id && PlaceGrouping.located(it) }
        val today = live.filter { it.day == day }
        val stays = today.filter { it.kind != PlanKind.Transport.id }
        if (place != null) {
            fun inPlace(item: PlanItem) = PlaceGrouping.placeOf(items, item).equals(place, ignoreCase = true)
            stays.firstOrNull(::inPlace)?.let { return it }
            today.lastOrNull { it.kind == PlanKind.Transport.id && inPlace(it) }?.let { return it }
        }
        stays.firstOrNull()?.let { return it }
        today.lastOrNull()?.let { return it }
        return live.lastOrNull { d -> d.day?.let { it.toEpochDay() < day.toEpochDay() } == true }
    }
}

/** Where a trip day was spent, for headers like "Day 4 · Naxos". */
object DayPlace {
    /**
     * The area named most often among the day's stops (where the traveller really was), or, for days without stops,
     * among the day's planned places. A tie goes to the later one, where the day ended. [now] ends the latest stay:
     * days still to come are named by their plan. [zoneId]: the zone the day is counted in (null: the phone's).
     */
    fun label(day: LocalDate, stops: List<Stop>, items: List<PlanItem>, zoneId: String? = null, now: Long = nowMs()): String? {
        val start = startOfDayMs(day, zoneId)
        val end = startOfDayMs(day.plusDays(1), zoneId)
        // A stay without a departure lasts until the next stop arrives, or until now for the latest one.
        val live = stops.filter { it.deletedAt == null && it.name.isNotBlank() }.sortedBy { it.arrivedAt }
        val fromStops = live.mapIndexedNotNull { i, s ->
            val until = s.leftAt ?: live.getOrNull(i + 1)?.arrivedAt ?: maxOf(now, s.arrivedAt)
            if (s.arrivedAt < end && until >= start) PlaceNames.area(s.name) else null
        }
        val names = fromStops.ifEmpty {
            items.filter { it.day == day && it.deletedAt == null && it.placeName.isNotBlank() }
                .sortedBy { it.position }
                .mapNotNull { PlaceNames.area(it.placeName) }
        }
        if (names.isEmpty()) return null
        val counts = names.groupingBy { it.lowercase() }.eachCount()
        val best = counts.values.max()
        return names.last { counts[it.lowercase()] == best }
    }
}

/**
 * Where a plan day is when neither the stops nor the items' place names say: the destination of the latest travel item
 * on or before that day ("Transfer to El Chaltén" → "El Chaltén"), so the days after an arrival keep its name. Travel
 * items without a destination ("Fly home") are skipped. Null when the plan has no such item yet.
 */
object PlanDayLabel {
    fun label(day: LocalDate, items: List<PlanItem>): String? = PlanSpan.ordered(items.filter { it.deletedAt == null })
        .filter { it.kind == PlanKind.Transport.id && it.day?.let { d -> d.toEpochDay() <= day.toEpochDay() } == true }
        .mapNotNull { item -> PlaceGrouping.itemPlaceLabel(item).takeIf { it != item.title.trim() || item.placeName.isNotBlank() } }
        .lastOrNull()
}

/** A plan day's place, as the Android app's day headers find it: the stops and places of the day, else the latest arrival. */
fun dayPlace(day: LocalDate, stops: List<Stop>, items: List<PlanItem>): String? =
    DayPlace.label(day, stops, items) ?: PlanDayLabel.label(day, items)
