package com.justdataplease.boringtravel.export

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.doc.EntryDoc
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import com.justdataplease.boringtravel.model.PlanSpan

/**
 * Everything one trip export reads, loaded once from the database: the trip, its entries, plan, photos, route
 * points and stops. Pure data, so the HTML can be built and tested on the JVM.
 */
data class TripBook(
    val trip: Trip,
    val entries: List<Entry> = emptyList(),
    val plan: List<PlanItem> = emptyList(),
    val media: List<Media> = emptyList(),
    val track: List<TrackPoint> = emptyList(),
    val stops: List<Stop> = emptyList(),
) {
    private val mediaById: Map<String, Media> by lazy { media.filter { it.deletedAt == null }.associateBy { it.id } }

    fun media(id: String): Media? = mediaById[id]

    /** The zone the trip was written in: the entries' most common zone, else the phone's. */
    val zone: ZoneId by lazy {
        entries.groupingBy { it.zoneId }.eachCount().maxByOrNull { it.value }?.key
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
    }

    fun dateOf(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    private fun entryDate(e: Entry): LocalDate =
        Instant.ofEpochMilli(e.occurredAt).atZone(runCatching { ZoneId.of(e.zoneId) }.getOrDefault(zone)).toLocalDate()

    /** The days to print for [options], in order. Days with nothing on them are left out. */
    fun days(options: ExportOptions): List<BookDay> {
        val liveEntries = entries.filter { it.deletedAt == null }.sortedBy { it.occurredAt }
        val parsed = liveEntries.map { BookEntry(it, EntryDoc.parse(it.body), entryDate(it)) }
        val inEntries = parsed.flatMap { it.doc.mediaIds() }.toSet()
        val loose = media.filter { it.deletedAt == null && it.id !in inEntries && it.tripId == trip.id }
            .sortedBy { it.takenAt ?: it.createdAt }
        val looseByDate = loose.groupBy { dateOf(it.takenAt ?: it.createdAt) }
        val entriesByDate = parsed.groupBy { it.date }
        // In the plan's own order (PlanSpan.dayOrder: times decide), so the pages agree with the Plan tab.
        val planByDate = PlanSpan.ordered(plan.filter { it.deletedAt == null && it.day != null }).groupBy { it.day!! }
        val trackByDate = track.sortedBy { it.time }.groupBy { dateOf(it.time) }
        val stopsByDate = stops.filter { it.deletedAt == null }.sortedBy { it.arrivedAt }.groupBy { dateOf(it.arrivedAt) }

        val candidates = sortedSetOf<LocalDate>()
        val start = trip.startDate
        val end = trip.endDate
        if (start != null && end != null && !end.isBefore(start)) {
            var d: LocalDate = start
            while (!d.isAfter(end)) { candidates += d; d = d.plusDays(1) }
        }
        candidates += entriesByDate.keys
        candidates += looseByDate.keys
        if (options.includePlan) candidates += planByDate.keys

        val total = trip.days
        val kept = candidates.filter { options.includes(it) }.filter { d ->
            entriesByDate[d].orEmpty().isNotEmpty() || looseByDate[d].orEmpty().isNotEmpty() ||
                (options.includePlan && planByDate[d].orEmpty().isNotEmpty())
        }
        return kept.mapIndexed { index, d ->
            val dayEntries = entriesByDate[d].orEmpty()
            val entryPhotos = dayEntries.flatMap { it.doc.mediaIds() }.distinct().mapNotNull { media(it) }
            val dayLoose = looseByDate[d].orEmpty()
            val dayStops = stopsByDate[d].orEmpty()
            val dayTrack = trackByDate[d].orEmpty()
            val number = if (start != null) {
                (d.toEpochDay() - start.toEpochDay() + 1).toInt().takeIf { total == null || it in 1..total }
            } else index + 1
            val withWeather = dayEntries.firstOrNull { it.entry.tempC != null || !it.entry.weather.isNullOrBlank() }?.entry
            BookDay(
                date = d,
                number = number,
                total = if (start != null) total else kept.size,
                entries = dayEntries,
                photos = (entryPhotos + dayLoose).distinctBy { it.id },
                loosePhotos = dayLoose,
                plan = planByDate[d].orEmpty(),
                track = dayTrack,
                stops = dayStops,
                place = dayStops.lastOrNull { it.name.isNotBlank() }?.name
                    ?: dayEntries.firstOrNull { it.entry.placeName.isNotBlank() }?.entry?.placeName,
                tempC = withWeather?.tempC,
                weather = withWeather?.weather?.takeIf { it.isNotBlank() },
                km = Geo.lengthKm(dayTrack.map { GeoPoint(it.lat, it.lng) }),
            )
        }
    }

    /** Plan items without a day (ideas), in their order. */
    fun ideas(): List<PlanItem> = PlanSpan.dayOrder(plan.filter { it.deletedAt == null && it.day == null })

    /**
     * The route for the cover: the recorded track inside [options]' days; if there is none, the stops joined in
     * order; if none either, the planned places (then [RouteGeo.planned] is true and the line is drawn dashed).
     */
    fun route(options: ExportOptions): RouteGeo {
        val points = track.sortedBy { it.time }.filter { options.includes(dateOf(it.time)) }.map { GeoPoint(it.lat, it.lng) }
        val stopPlaces = stops.filter { it.deletedAt == null && options.includes(dateOf(it.arrivedAt)) }
            .sortedBy { it.arrivedAt }.map { RoutePlace(GeoPoint(it.lat, it.lng), it.name) }
        if (points.size >= 2) return RouteGeo(points, stopPlaces, planned = false)
        if (stopPlaces.isNotEmpty()) return RouteGeo(stopPlaces.map { it.point }, stopPlaces, planned = false)
        val planned = PlanSpan.ordered(plan.filter { it.deletedAt == null && it.lat != null && it.lng != null && (it.day == null || options.includes(it.day!!)) })
            .map { RoutePlace(GeoPoint(it.lat!!, it.lng!!), it.placeName.ifBlank { it.title }) }
        return RouteGeo(planned.map { it.point }, planned, planned = true)
    }
}

data class BookEntry(val entry: Entry, val doc: EntryDoc, val date: LocalDate)

/** One printed day. [number] is "Day N" within the trip (null outside its dates); [photos] includes [loosePhotos]. */
data class BookDay(
    val date: LocalDate,
    val number: Int?,
    val total: Int?,
    val entries: List<BookEntry>,
    val photos: List<Media>,
    val loosePhotos: List<Media>,
    val plan: List<PlanItem>,
    val track: List<TrackPoint>,
    val stops: List<Stop>,
    val place: String?,
    val tempC: Double?,
    val weather: String?,
    val km: Double,
)

data class GeoPoint(val lat: Double, val lng: Double)

data class RoutePlace(val point: GeoPoint, val name: String)

/** A route line in degrees plus its named places; [planned] = not travelled yet (drawn dashed). */
data class RouteGeo(val line: List<GeoPoint>, val places: List<RoutePlace>, val planned: Boolean) {
    val isEmpty: Boolean get() = line.isEmpty() && places.isEmpty()
}

object Geo {
    private const val EARTH_KM = 6371.0088

    fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun lengthKm(points: List<GeoPoint>): Double = points.zipWithNext { a, b -> distanceKm(a, b) }.sum()
}
