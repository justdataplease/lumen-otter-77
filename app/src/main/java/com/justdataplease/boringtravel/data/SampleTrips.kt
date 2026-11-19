package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.programs.ProgramItem
import com.justdataplease.boringtravel.programs.ProgramLibrary
import com.justdataplease.boringtravel.programs.ProgramTrips
import java.time.LocalDate

/**
 * The example trips beside the demo, and the one-time move from the first examples to the current ones. Pure, so
 * the guard that keeps the traveller's own data safe is unit tested (SampleTripsTest).
 */
object SampleTrips {
    /** The first current example (replaced by the Argentina demo). */
    const val CYCLADES = "sample-cyclades"
    const val KYOTO = "sample-japan"
    const val LISBON = "sample-portugal"

    /** The examples' subtitle before the Demo tag; now each shows its route. */
    const val OLD_SUBTITLE = "Example trip"
    const val KYOTO_ROUTE = "Kyoto → Osaka"
    const val LISBON_ROUTE = "Lisbon → Porto"

    /** Next: in October next year; the bare trip (its plan: [SampleKyoto]). */
    fun kyoto(today: LocalDate, t: Long): Trip {
        val start = SampleKyoto.start(today)
        return Trip(KYOTO, SampleKyoto.TITLE, KYOTO_ROUTE, start, start.plusDays(SampleKyoto.DAYS - 1L), trackingLevel = "off", createdAt = t, updatedAt = t)
    }

    /** Past: in May (this year's when it is over, else last year's); the bare trip (its program: [SampleLisbon]). */
    fun lisbon(today: LocalDate, t: Long): Trip {
        val start = SampleLisbon.start(today)
        return Trip(LISBON, SampleLisbon.TITLE, LISBON_ROUTE, start, start.plusDays(SampleLisbon.DAYS - 1L), trackingLevel = "off", createdAt = t, updatedAt = t)
    }

    /**
     * Sample version 4: Kyoto and Lisbon were bare trips; where one is still untouched (same rule as [upgrade]) it gets
     * its full program on its own dates ([SampleKyoto], [SampleLisbon]), stamped [t] like a new seed. Anything the
     * traveller changed, deleted or planned stays as it is.
     */
    fun fillPrograms(rows: Rows, t: Long): List<SampleArgentina.Demo> = listOfNotNull(
        fill(rows, KYOTO) { SampleKyoto.demo(it, t) },
        fill(rows, LISBON) { SampleLisbon.demo(it, t) },
    )

    private fun fill(rows: Rows, tripId: String, build: (LocalDate) -> SampleArgentina.Demo): SampleArgentina.Demo? {
        val sample = untouched(rows, tripId) ?: return null
        if (sample.items.isNotEmpty() || sample.entries.isNotEmpty() || sample.stops.isNotEmpty()) return null
        return build(sample.trip.startDate ?: return null)
    }

    /**
     * Sample version 5: Kyoto & Osaka and Lisbon & Porto get their plans from the trip ideas (the default program at a
     * balanced pace, [SampleKyoto.plan] / [SampleLisbon.plan]) on the dates they have now (moved daily with the demo, or
     * by hand). Only these two example trips change, and only while they are there (a deleted one stays deleted):
     * their old plan is soft-deleted as a whole (example rows, ticked or not) and the new one added, stamped [t]. The
     * trip keeps its id, start, cover and flags; its end follows the program's length; a name and route nobody changed
     * become the idea's. Journal, pictures and stays stay; when the trip was untouched, its rows seeded with it are
     * restamped with it (as [DemoTrips.reanchor] does), so it still reads as untouched. [skip] = trips just filled.
     */
    fun newPrograms(rows: Rows, t: Long, skip: Set<String> = emptySet()): Changes {
        val plans = mapOf<String, (LocalDate) -> ProgramTrips.Built>(
            KYOTO to { SampleKyoto.plan(it, t) },
            LISBON to { SampleLisbon.plan(it, t) },
        )
        var out = Changes()
        for ((id, plan) in plans) {
            if (id in skip) continue
            val trip = rows.trips.firstOrNull { it.id == id && it.deletedAt == null } ?: continue
            val built = plan(trip.startDate ?: continue)
            val untouched = trip.updatedAt == trip.createdAt
            val stamp = trip.createdAt
            fun seeded(created: Long, updated: Long, deleted: Long?) = untouched && deleted == null && created == stamp && updated == created
            val entries = rows.entries.filter { it.tripId == id }
            val entryIds = entries.mapTo(HashSet()) { it.id }
            out = Changes(
                trips = out.trips + trip.copy(
                    title = if (untouched) built.trip.title else trip.title,
                    subtitle = if (untouched) built.trip.subtitle else trip.subtitle,
                    endDate = built.trip.endDate,
                    travelModes = built.trip.travelModes,
                    createdAt = if (untouched) t else trip.createdAt,
                    updatedAt = t,
                ),
                items = out.items + rows.items.filter { it.tripId == id && it.deletedAt == null }.map { it.copy(deletedAt = t, updatedAt = t) } + built.items,
                entries = out.entries + entries.filter { seeded(it.createdAt, it.updatedAt, it.deletedAt) }.map { it.copy(createdAt = t, updatedAt = t) },
                stops = out.stops + rows.stops.filter { it.tripId == id && seeded(it.createdAt, it.updatedAt, it.deletedAt) }.map { it.copy(createdAt = t, updatedAt = t) },
                // The pictures were restamped by the renaming in sample version 3: seeded = created with the trip.
                media = out.media + rows.media.filter { m ->
                    untouched && m.deletedAt == null && m.createdAt == stamp && (m.tripId == id || m.entryId in entryIds)
                }.map { it.copy(createdAt = t, updatedAt = t) },
            )
        }
        return out
    }

    /**
     * Sample version 7: the examples' plan items get the websites their places have now ([ProgramItem.url] for Kyoto &
     * Osaka and Lisbon & Porto, the plan's table for Argentina, [SampleArgentina.link]). Only the three example trips,
     * only while they are there, and only an item still as it was seeded: not deleted, never edited (created =
     * updated), no link of its own, and still the item it came from (a program item: its externalId, title and kind;
     * Argentina: its day, kind and title). Such an item keeps its id, day, position and tick and gets the link,
     * stamped [t]. As in [newPrograms], an untouched trip and every row seeded with it are restamped at [t] together,
     * so it still reads as untouched; in a trip the traveller changed, only the linked items' updatedAt moves.
     */
    fun addLinks(rows: Rows, t: Long): Changes {
        val programs = ProgramLibrary.destinations.flatMap { it.programs }
        var out = Changes()
        for (id in CURRENT) {
            val trip = rows.trips.firstOrNull { it.id == id && it.deletedAt == null } ?: continue
            val items = rows.items.filter { it.tripId == id }
            val links = HashMap<String, String>()
            for (item in items) {
                if (item.deletedAt != null || item.url != null || item.updatedAt != item.createdAt) continue
                val url = if (id == SampleArgentina.TRIP_ID) {
                    SampleArgentina.link(item, trip.startDate)
                } else {
                    ProgramTrips.itemAt(programs, item.externalId)
                        ?.takeIf { item.source == ItemSource.Program.id && it.title.trim() == item.title && PlanKind.of(it.kind).id == item.kind }
                        ?.url
                }
                if (url != null) links[item.id] = url
            }
            if (links.isEmpty()) continue
            val untouched = trip.updatedAt == trip.createdAt
            val stamp = trip.createdAt
            fun seeded(created: Long, updated: Long, deleted: Long?) = untouched && deleted == null && created == stamp && updated == created
            val entries = rows.entries.filter { it.tripId == id }
            val entryIds = entries.mapTo(HashSet()) { it.id }
            out = Changes(
                trips = out.trips + listOfNotNull(if (untouched) trip.copy(createdAt = t, updatedAt = t) else null),
                items = out.items + items.mapNotNull { item ->
                    val url = links[item.id]
                    val restamp = seeded(item.createdAt, item.updatedAt, item.deletedAt)
                    when {
                        url != null -> item.copy(url = url, createdAt = if (restamp) t else item.createdAt, updatedAt = t)
                        restamp -> item.copy(createdAt = t, updatedAt = t)
                        else -> null
                    }
                },
                entries = out.entries + entries.filter { seeded(it.createdAt, it.updatedAt, it.deletedAt) }.map { it.copy(createdAt = t, updatedAt = t) },
                stops = out.stops + rows.stops.filter { it.tripId == id && seeded(it.createdAt, it.updatedAt, it.deletedAt) }.map { it.copy(createdAt = t, updatedAt = t) },
                // The pictures were restamped by the renaming in sample version 3: seeded = created with the trip.
                media = out.media + rows.media.filter { m ->
                    untouched && m.deletedAt == null && m.createdAt == stamp && (m.tripId == id || m.entryId in entryIds)
                }.map { it.copy(createdAt = t, updatedAt = t) },
            )
        }
        return out
    }

    /** The current examples, in the order they are seeded. */
    val CURRENT: List<String> = listOf(SampleArgentina.TRIP_ID, KYOTO, LISBON)

    /** Me › Demo mode on: the current examples that are not here, or were deleted, and so are seeded again. */
    fun missing(trips: List<Trip>): List<String> = CURRENT.filter { id -> trips.none { it.id == id && it.deletedAt == null } }

    /**
     * What is left of example trips [ids] that come back (their plan, journal, pictures and stays stay in the database
     * when a trip is deleted): soft-deleted at [t], so nothing shows twice. Nothing outside those trips is touched.
     */
    fun leftovers(rows: Rows, ids: Set<String>, t: Long): Changes {
        val entries = rows.entries.filter { it.tripId in ids && it.deletedAt == null }
        val entryIds = rows.entries.filter { it.tripId in ids }.mapTo(HashSet()) { it.id }
        return Changes(
            items = rows.items.filter { it.tripId in ids && it.deletedAt == null }.map { it.copy(deletedAt = t, updatedAt = t) },
            entries = entries.map { it.copy(deletedAt = t, updatedAt = t) },
            stops = rows.stops.filter { it.tripId in ids && it.deletedAt == null }.map { it.copy(deletedAt = t, updatedAt = t) },
            media = rows.media.filter { (it.tripId in ids || it.entryId in entryIds) && it.deletedAt == null }.map { it.copy(deletedAt = t, updatedAt = t) },
        )
    }

    /** Everything stored, read once for the move. [trackedTrips] = trips with any route points. */
    data class Rows(
        val trips: List<Trip>,
        val items: List<PlanItem> = emptyList(),
        val entries: List<Entry> = emptyList(),
        val stops: List<Stop> = emptyList(),
        val media: List<Media> = emptyList(),
        val trackedTrips: Set<String> = emptySet(),
    )

    /** Rows to write (old examples soft-deleted, Kyoto added back, new plans, restamped rows). */
    data class Changes(
        val trips: List<Trip> = emptyList(),
        val items: List<PlanItem> = emptyList(),
        val entries: List<Entry> = emptyList(),
        val stops: List<Stop> = emptyList(),
        val media: List<Media> = emptyList(),
    )

    private class Sample(val trip: Trip, val items: List<PlanItem>, val entries: List<Entry>, val stops: List<Stop>)

    /**
     * From the first examples to the current ones: the Cyclades example and the fixed-date Argentina plan go, but
     * only while untouched; Kyoto comes back if the Argentina plan had removed it (or was never there). Anything
     * the traveller made or changed stays exactly as it is.
     */
    fun upgrade(rows: Rows, today: LocalDate, t: Long): Changes {
        val retire = listOfNotNull(
            untouched(rows, CYCLADES),
            untouched(rows, SampleArgentina.LEGACY_TRIP_ID)?.takeIf { it.trip.startDate == SampleArgentina.LEGACY_START },
        )
        val japan = rows.trips.firstOrNull { it.id == KYOTO }
        val legacy = rows.trips.firstOrNull { it.id == SampleArgentina.LEGACY_TRIP_ID }
        // The first Argentina example removed an untouched Kyoto in the same step it was added, at the same moment.
        val autoRemoved = japan?.deletedAt != null && legacy != null && japan.deletedAt == legacy.createdAt &&
            japan.updatedAt == japan.deletedAt && rows.items.none { it.tripId == KYOTO && it.deletedAt == null }
        val addKyoto = japan == null || autoRemoved
        return Changes(
            trips = retire.map { it.trip.copy(deletedAt = t, updatedAt = t) } + listOfNotNull(if (addKyoto) kyoto(today, t) else null),
            items = retire.flatMap { it.items }.map { it.copy(deletedAt = t, updatedAt = t) },
            entries = retire.flatMap { it.entries }.map { it.copy(deletedAt = t, updatedAt = t) },
            stops = retire.flatMap { it.stops }.map { it.copy(deletedAt = t, updatedAt = t) },
        )
    }

    /**
     * An example trip nobody has touched: not deleted, never edited, and every row in it seeded together with it
     * (same moment, never changed). Photos, route points or any later row mean the traveller has made it theirs.
     */
    private fun untouched(rows: Rows, tripId: String): Sample? {
        val trip = rows.trips.firstOrNull { it.id == tripId } ?: return null
        if (trip.deletedAt != null || trip.updatedAt != trip.createdAt) return null
        val stamp = trip.createdAt
        fun seeded(created: Long, updated: Long, deleted: Long?) = created == stamp && updated == created && deleted == null
        val items = rows.items.filter { it.tripId == tripId }
        val entries = rows.entries.filter { it.tripId == tripId }
        val stops = rows.stops.filter { it.tripId == tripId }
        if (!items.all { seeded(it.createdAt, it.updatedAt, it.deletedAt) }) return null
        if (!entries.all { seeded(it.createdAt, it.updatedAt, it.deletedAt) }) return null
        if (!stops.all { seeded(it.createdAt, it.updatedAt, it.deletedAt) }) return null
        val entryIds = entries.mapTo(HashSet()) { it.id }
        if (rows.media.any { it.tripId == tripId || it.entryId in entryIds }) return null
        if (tripId in rows.trackedTrips) return null
        return Sample(trip, items, entries, stops)
    }
}
