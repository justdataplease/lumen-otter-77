package com.justdataplease.boringtravel.ui.place

import com.justdataplease.boringtravel.clip.Geo
import com.justdataplease.boringtravel.clip.RouteLeg
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.places.PlaceNames
import com.justdataplease.boringtravel.ui.trip.LegEstimate
import com.justdataplease.boringtravel.ui.trip.travel
import java.time.LocalDate

/**
 * Everything the place page says, built only from the trip's own plan and photos: no descriptions, ratings or
 * weather. [items] are the plan items at the place in trip order (located ones near the anchor, plus unlocated ones
 * on those days that follow a nearby item); [days] the trip days they sit on.
 */
data class PlaceSummary(
    val anchorLat: Double,
    val anchorLng: Double,
    val items: List<PlanItem>,
    val days: List<LocalDate>,
    /** "Day 13" numbers of [days], or empty when the trip has no dates. */
    val dayNumbers: List<Int>,
    /** Places, food and activities at the place (not stays, transport or notes). */
    val sights: List<PlanItem>,
    val stays: List<PlanItem>,
    val notes: List<PlanItem>,
    /** How you arrive: the first leg into the place from somewhere else, with its destination item. */
    val arrival: LegInfo?,
    /** How you leave: the first leg out of the place after it. */
    val departure: LegInfo?,
) {
    val inPlan: Boolean get() = items.isNotEmpty()
}

/** A leg in or out of a place: the vehicle chosen (or null), both ends and the leg's destination item. */
data class LegInfo(val leg: RouteLeg, val fromLabel: String, val toLabel: String, val toItem: PlanItem?, val distanceM: Double?) {
    /** The chosen vehicle, else the default ([com.justdataplease.boringtravel.model.TravelDefault]). */
    val vehicle: Vehicle get() = leg.travel
}

/** Which of the trip's items and days belong to one place (pure, unit tested). */
object PlaceGrouping {
    /** Items this close to the anchor are at the same place: a town and its trailheads, a city and its airport. */
    const val RADIUS_M = 40_000.0

    private val SIGHT_KINDS = setOf(PlanKind.Place.id, PlanKind.Food.id, PlanKind.Activity.id)

    fun located(item: PlanItem): Boolean {
        val lat = item.lat
        val lng = item.lng
        return lat != null && lng != null && lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
    }

    /** Live items of [trip] in plan order ([PlanSpan.ordered]: by day, each day in time order, ideas last). */
    fun ordered(tripId: String, items: List<PlanItem>): List<PlanItem> =
        PlanSpan.ordered(items.filter { it.tripId == tripId && it.deletedAt == null })

    /** Located items within [radiusM] of the anchor. */
    fun near(items: List<PlanItem>, lat: Double, lng: Double, radiusM: Double = RADIUS_M): List<PlanItem> =
        items.filter { located(it) && Geo.distanceM(lat, lng, it.lat!!, it.lng!!) <= radiusM }

    /**
     * The items at the place, in plan order: located items near the anchor or near one of those (one step out, so an
     * airport anchor still reaches the lake on the town's far side, without chaining down a whole valley), plus each
     * unlocated item on a day whose located item just before it (or, when there is none before, just after it) is
     * at the place. Ideas (no day) only count when they are located.
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
    fun days(items: List<PlanItem>): List<LocalDate> = items.mapNotNull { it.day }.distinct().sorted()

    /** "Day 13" numbers inside the trip's dates. */
    fun dayNumbers(trip: Trip, days: List<LocalDate>): List<Int> {
        val start = trip.startDate ?: return emptyList()
        val end = trip.endDate ?: return emptyList()
        return days.filter { !it.isBefore(start) && !it.isAfter(end) }.map { (it.toEpochDay() - start.toEpochDay() + 1).toInt() }
    }

    /** "Day 13", "Days 13–16", "Days 4, 6–8". Runs of consecutive numbers are joined with an en dash. */
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

    /** Which "How long" chip the plan's length falls in: 0 = up to 2 days, 1 = 3–4 days, 2 = 5 days or more; null with no days. */
    fun lengthBucket(days: Int): Int? = when {
        days <= 0 -> null
        days <= 2 -> 0
        days <= 4 -> 1
        else -> 2
    }

    val LENGTH_LABELS = listOf("2 days", "3–4 days", "5+ days")

    /**
     * The label a plan item gives the place it sits in: the area of its place name ("Halki, Naxos, Greece" → "Naxos"),
     * else its title with travel words taken off ("Transfer to El Chaltén" → "El Chaltén", "Fly to Mendoza" → "Mendoza").
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
     * anchor ("Transfer to El Chaltén" for a trail outside El Chaltén). Null when no such arrival is in the plan.
     */
    fun arrivalName(items: List<PlanItem>, day: LocalDate?, lat: Double, lng: Double, radiusM: Double = RADIUS_M): String? = items
        .filter { it.deletedAt == null && it.kind == PlanKind.Transport.id && located(it) && (day == null || it.day?.isAfter(day) == false) }
        .filter { Geo.distanceM(lat, lng, it.lat!!, it.lng!!) <= radiusM }
        .sortedWith(compareBy<PlanItem>({ it.day == null }, { it.day }, { it.position }, { it.id }))
        .mapNotNull { item -> itemPlaceLabel(item).takeIf { it != item.title.trim() || item.placeName.isNotBlank() } }
        .lastOrNull()

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

    /**
     * The place a located item is in: the plan's arrival near it ("Plane from Mendoza", not from the last show there),
     * else its own place label.
     */
    fun placeOf(items: List<PlanItem>, item: PlanItem?): String? {
        if (item == null) return null
        if (located(item)) arrivalName(items, item.day, item.lat!!, item.lng!!)?.let { return it }
        return itemPlaceLabel(item)
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

    /** "Car from El Calafate · ≈ 3 h", "Plane from Bariloche", "Choose travel from Bariloche". */
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
