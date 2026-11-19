package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.backup.Snapshot
import com.justdataplease.boringtravel.calendar.TripZone
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.world.WorldInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Demo mode (Me › Demo mode): the app shows either the example trips ([Trip.isDemo]) or the traveller's own trips,
 * never both. Rows belong to a side through their trip; rows outside any trip are the traveller's. Hidden rows are
 * never changed. In demo mode the examples are kept around today (Argentina on day [SampleArgentina.TODAY]). Pure,
 * apart from the flow helper.
 */
object DemoTrips {
    /** Whether a row of trip [tripId] (null = no trip) shows in this mode. */
    fun visible(tripId: String?, demoMode: Boolean): Boolean = Trip.isDemoId(tripId) == demoMode

    /** Only the rows of the side [demoMode] shows; [tripId] says which trip a row belongs to. */
    fun <T> Flow<List<T>>.visibleIn(demoMode: Flow<Boolean>, tripId: (T) -> String?): Flow<List<T>> =
        combine(this, demoMode) { rows, demo -> rows.filter { visible(tripId(it), demo) } }

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

    /**
     * The setting for an install that has none yet: on whenever there are examples (the owner's call: demo mode stays
     * on by default, and each start says where to turn it off). Builds without examples start off.
     */
    fun initialMode(hasDemoTrips: Boolean): Boolean = hasDemoTrips

    /** A new trip of the traveller's own ends demo mode (and the app shows it). */
    fun leavesDemo(demoMode: Boolean, saved: Trip, existed: Boolean): Boolean = demoMode && !existed && !saved.isDemo

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

    /** One trip and what moves with it. */
    data class Rows(
        val trip: Trip,
        val items: List<PlanItem> = emptyList(),
        val entries: List<Entry> = emptyList(),
        val media: List<Media> = emptyList(),
        val stops: List<Stop> = emptyList(),
    )

    /** Days from the trip's start to [start] (0 when either is missing). */
    fun delta(trip: Trip, start: LocalDate?): Long =
        if (trip.startDate == null || start == null) 0 else start.toEpochDay() - trip.startDate!!.toEpochDay()

    /**
     * The whole trip [days] later (earlier when negative): its dates (same length), the plan's days and their names
     * ([DayNames]), entry times, photo times and stays. Times keep their time of day in their zone: an entry's own zone, a photo its entry's, the rest
     * the zone most entries were written in, else [fallback]. Ids, files and stamps stay; the caller stamps the rows.
     */
    fun shift(rows: Rows, days: Long, fallback: ZoneId): Rows {
        if (days == 0L) return rows
        val tripZone = TripZone.guess(null, rows.entries.map { it.zoneId }, emptyList(), fallback)
        val entryZones = rows.entries.associate { it.id to (TripZone.parse(it.zoneId) ?: tripZone) }
        fun move(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).plusDays(days).toInstant().toEpochMilli()
        val t = rows.trip
        return Rows(
            trip = t.copy(startDate = t.startDate?.plusDays(days), endDate = t.endDate?.plusDays(days), travelModes = DayNames.shift(t.travelModes, days)),
            items = rows.items.map { it.copy(day = it.day?.plusDays(days)) },
            entries = rows.entries.map { it.copy(occurredAt = move(it.occurredAt, entryZones.getValue(it.id))) },
            media = rows.media.map { m -> m.copy(takenAt = m.takenAt?.let { move(it, m.entryId?.let(entryZones::get) ?: tripZone) }) },
            stops = rows.stops.map { s -> s.copy(arrivedAt = move(s.arrivedAt, tripZone), leftAt = s.leftAt?.let { move(it, tripZone) }) },
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
     * [SampleArgentina.TODAY] again ([shift]), so Kyoto stays as far ahead and Lisbon as far back. Returns only the
     * trips that change (none when Argentina is already there, deleted or gone). Rows are stamped [t]; a row seeded
     * with its trip and never changed is restamped as seeded at [t], so it still reads as untouched. The examples'
     * old "Example trip" subtitles become their routes on the way.
     */
    fun reanchor(demo: List<Rows>, today: LocalDate, t: Long, fallback: ZoneId = SampleArgentina.ZONE): List<Rows> {
        val anchor = demo.firstOrNull { it.trip.id == SampleArgentina.TRIP_ID && it.trip.deletedAt == null } ?: return emptyList()
        val days = delta(anchor.trip, SampleArgentina.start(today))
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
                // The demo's pictures were restamped by the renaming in sample version 3: seeded = created with the trip.
                media = moved.media.map { if (it.createdAt == stamp) it.copy(createdAt = t, updatedAt = t) else it.copy(updatedAt = t) },
                stops = moved.stops.map { if (seeded(it.createdAt, it.updatedAt)) it.copy(createdAt = t, updatedAt = t) else it.copy(updatedAt = t) },
            )
        }
    }
}
