package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.export.BookDay
import com.justdataplease.boringtravel.export.ExportOptions
import com.justdataplease.boringtravel.export.GeoPoint
import com.justdataplease.boringtravel.export.RouteGeo
import com.justdataplease.boringtravel.export.RoutePlace
import com.justdataplease.boringtravel.export.TripBook
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Words printed in the book. English for now, like the print themes' [com.justdataplease.boringtravel.export.PrintLabels]. */
data class BookLabels(
    val day: String = "Day",
    val of: String = "of",
    val route: String = "The route",
    val numbers: String = "Trip in numbers",
    val notes: String = "Notes",
    val days: String = "Days",
    val km: String = "Kilometres",
    val countries: String = "Countries",
    val entries: String = "Entries",
    val photos: String = "Photos",
    val stops: String = "Stops",
    val longest: String = "Longest day",
    val mostPhotos: String = "Most photos",
    val brand: String = "BoringTravel",
)

/** One trip day as the book prints it: its number, place, countries, route and numbers. */
data class DayFacts(
    val date: LocalDate,
    val number: Int?,
    val total: Int?,
    val place: String?,
    val countries: List<String>,
    val km: Double,
    val stops: Int,
    val weather: String?,
    val route: RouteGeo?,
    val places: List<String>,
    val entries: Int,
    val photos: Int,
)

/**
 * What the book says about the trip, worked out once from a [TripBook]: dates, places, countries (from the stops,
 * carried over days without any), each day's route and the totals for "Trip in numbers".
 */
class BookFacts(val book: TripBook, val locale: Locale = Locale.getDefault(), val labels: BookLabels = BookLabels()) {
    val trip = book.trip
    val bookDays: List<BookDay> = book.days(ExportOptions(includePlan = false))
    val days: List<DayFacts>
    private val byDate: Map<LocalDate, DayFacts>

    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    private val weekdayLong = DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)
    private val shortDate = DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale)

    init {
        var lastCountry: String? = null
        days = bookDays.map { d ->
            val codes = d.stops.mapNotNull { it.countryCode?.uppercase()?.takeIf { c -> c.length == 2 } }.distinct()
            val countries = codes.ifEmpty { listOfNotNull(lastCountry) }
            codes.lastOrNull()?.let { lastCountry = it }
            DayFacts(
                date = d.date,
                number = d.number,
                total = d.total,
                place = d.place,
                countries = countries,
                km = d.km,
                stops = d.stops.size,
                weather = weather(d),
                route = dayRoute(d),
                places = d.stops.map { short(it.name) }.filter { it.isNotBlank() }.distinct(),
                entries = d.entries.size,
                photos = d.photos.count { !it.isVideo },
            )
        }
        byDate = days.associateBy { it.date }
    }

    fun day(date: LocalDate?): DayFacts? = date?.let { byDate[it] }

    val start: LocalDate? get() = trip.startDate ?: days.firstOrNull()?.date
    val end: LocalDate? get() = trip.endDate ?: days.lastOrNull()?.date ?: start

    /** "16–25 Jun 2027". */
    val dateRange: String get() {
        val s = start ?: return ""
        val e = end ?: s
        return when {
            s.year != e.year -> "${dayMonthYear.format(s)} – ${dayMonthYear.format(e)}"
            s.month != e.month -> "${dayMonth.format(s)} – ${dayMonthYear.format(e)}"
            s == e -> dayMonthYear.format(s)
            else -> "${s.dayOfMonth}–${dayMonthYear.format(e)}"
        }
    }

    val year: String get() = start?.year?.toString().orEmpty()

    /** The trip's countries in the order they were first reached. */
    val countries: List<String> = days.flatMap { it.countries }.distinct()

    val totalDays: Int get() = trip.days ?: days.size
    val km: Double get() = days.sumOf { it.km }
    val entries: Int get() = book.entries.count { it.deletedAt == null }
    /** The trip's photos (a video shows as its frame in the book but isn't counted). */
    val photos: Int get() = book.media.count { it.deletedAt == null && it.tripId == trip.id && !it.isVideo }
        .coerceAtLeast(bookDays.sumOf { d -> d.photos.count { !it.isVideo } })
    val stops: Int get() = days.sumOf { it.stops }

    /** The whole route, as on the cover (track, else stops, else planned places). */
    val route: RouteGeo get() = book.route(ExportOptions())

    /** The route with each place named by its island or region ("Paros", "Naxos"), for the map and the cover. */
    val regionRoute: RouteGeo get() = route.let { r ->
        val names = r.places.map { region(it.name) }
        r.copy(places = r.places.mapIndexed { i, p -> p.copy(name = simplest(names[i], names)) })
    }

    /** The route with only its first and last place marked (the cover). */
    val coverRoute: RouteGeo get() = regionRoute.let { r -> r.copy(places = listOfNotNull(r.places.firstOrNull(), r.places.lastOrNull().takeIf { r.places.size > 1 })) }

    /** Islands or regions in visit order: "Piraeus → Paros → Naxos → Amorgos". */
    val regions: List<String> get() = route.places.map { region(it.name) }.let { all -> all.map { simplest(it, all) } }.filter { it.isNotBlank() }.distinct()

    /** "Naxos port" → "Naxos" when "Naxos" is also among the [names]. */
    private fun simplest(name: String, names: List<String>): String =
        names.filter { it != name && it.isNotBlank() && name.startsWith("$it ") }.minByOrNull { it.length } ?: name

    /** The day's entries as "09:00" and title, for the day opener. */
    fun dayEntries(date: LocalDate): List<Pair<String, String>> = bookDays.firstOrNull { it.date == date }?.entries.orEmpty()
        .filter { it.entry.title.isNotBlank() }
        .map { e ->
            val z = runCatching { java.time.ZoneId.of(e.entry.zoneId) }.getOrDefault(book.zone)
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(java.time.Instant.ofEpochMilli(e.entry.occurredAt).atZone(z)) to e.entry.title.trim()
        }

    /** Places along the route, short names, first visit order. */
    val places: List<String> get() = days.flatMap { it.places }.distinct()

    /** Where each day ended, numbered, for the map page. */
    val dayEnds: List<Pair<Int, GeoPoint>> get() = bookDays.mapNotNull { d ->
        val n = d.number ?: return@mapNotNull null
        val p = d.track.lastOrNull()?.let { GeoPoint(it.lat, it.lng) } ?: d.stops.lastOrNull()?.let { GeoPoint(it.lat, it.lng) }
        p?.let { n to it }
    }

    fun dayTitle(d: DayFacts): String = weekdayLong.format(d.date)
    fun dayMonth(date: LocalDate): String = dayMonth.format(date)

    /** "Day 4 of 10", "Day 4", or the date when the day is outside the trip's dates. */
    fun dayLabel(d: DayFacts): String = when {
        d.number == null -> shortDate.format(d.date)
        d.total != null && d.total > 0 -> "${labels.day} ${d.number} ${labels.of} ${d.total}"
        else -> "${labels.day} ${d.number}"
    }

    /** "18.4 km · 3 stops · 27° sunny". */
    fun dayLine(d: DayFacts): String = listOfNotNull(
        km(d.km).takeIf { d.km >= 0.05 },
        if (d.stops > 0) "${d.stops} ${if (d.stops == 1) "stop" else "stops"}" else null,
        d.weather,
    ).joinToString(" · ")

    fun km(v: Double): String = if (v < 100) String.format(locale, "%.1f km", v) else String.format(locale, "%,.0f km", v)
    fun kmNumber(v: Double): String = if (v < 100) String.format(locale, "%.1f", v) else String.format(locale, "%,.0f", v)

    /** The day with the most kilometres and the day with the most photos, for the numbers page. */
    val longestDay: DayFacts? get() = days.filter { it.km >= 0.5 }.maxByOrNull { it.km }
    val photoDay: DayFacts? get() = days.filter { it.photos > 0 }.maxByOrNull { it.photos }

    fun media(id: String?): Media? = id?.let { book.media(it) }

    /** A photo's shape (width / height), 4:3 when unknown. */
    fun aspect(id: String?): Double {
        val m = media(id) ?: return 4.0 / 3.0
        return if (m.width > 0 && m.height > 0) m.width.toDouble() / m.height else 4.0 / 3.0
    }

    private fun weather(d: BookDay): String? {
        val t = d.tempC?.let { "${it.roundToInt()}°" }
        val w = d.weather?.replace('_', ' ')
        return listOfNotNull(t, w).joinToString(" ").ifBlank { null }
    }

    private fun dayRoute(d: BookDay): RouteGeo? {
        val places = d.stops.map { RoutePlace(GeoPoint(it.lat, it.lng), short(it.name)) }
        return when {
            d.track.size >= 2 -> RouteGeo(d.track.map { GeoPoint(it.lat, it.lng) }, places, planned = false)
            places.size >= 2 -> RouteGeo(places.map { it.point }, places, planned = false)
            else -> null
        }
    }

    companion object {
        /** "Halki, Naxos" → "Halki": the first part of a place name, for labels. */
        fun short(name: String?): String = name?.split(',', '→')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }.orEmpty()

        /** "Halki, Naxos" → "Naxos": the last part, the island or region (the day stamp). */
        fun region(name: String?): String = name?.split(',', '→')?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }.orEmpty()
    }
}
