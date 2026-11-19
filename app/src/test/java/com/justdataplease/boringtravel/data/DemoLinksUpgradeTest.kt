package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.ProgramTrips
import com.justdataplease.boringtravel.programs.daysAt
import com.justdataplease.boringtravel.programs.data.KyotoOsaka
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Sample version 7: the examples' plan items get their places' websites. Only items still as they were seeded change,
 * and only the url (ids, days, positions and ticks stay); an untouched trip is restamped as a whole so it still reads
 * as untouched; the traveller's own trips and anything they changed or made stay exactly as they are.
 */
class DemoLinksUpgradeTest {
    private val today = LocalDate.of(2026, 11, 7)
    private val seeded = 100L
    private val now = 9_000L

    /** The three examples as an install at sample version 6 has them: everything as seeded, no links yet. */
    private val argentina = SampleArgentina.demo(today, today.atTime(18, 0).atZone(SampleArgentina.ZONE).toInstant(), seeded).bare()
    private val kyoto = SampleKyoto.demo(SampleKyoto.start(today), seeded).bare()
    private val lisbon = SampleLisbon.demo(SampleLisbon.start(today), seeded).bare()
    private val demos = listOf(argentina, kyoto, lisbon)
    private fun SampleArgentina.Demo.bare() = copy(items = items.map { it.copy(url = null) })

    /** The traveller's own trip, made from the Kyoto idea before it had links, with a photo and a stay. */
    private val mine = ProgramTrips.build(KyotoOsaka.destination, KyotoOsaka.destination.default, Pace.Balanced, LocalDate.of(2027, 4, 1), 50, links = LinkPlaces::sortLinks, tripId = "5a1c7d2e-own")
        .let { it.copy(items = it.items.map { i -> i.copy(url = null) }) }
    private val myEntry = Entry("my-entry", mine.trip.id, occurredAt = 0, zoneId = "Asia/Tokyo", title = "Kyoto", createdAt = 50, updatedAt = 50)
    private val myPhoto = Media("my-photo", mine.trip.id, myEntry.id, "media/my-photo.jpg", createdAt = 50, updatedAt = 50)
    private val myStop = Stop("my-stop", mine.trip.id, arrivedAt = 0, lat = 35.0, lng = 135.7, createdAt = 50, updatedAt = 50)

    private fun rows(trips: List<Trip> = demos.map { it.trip }, items: List<PlanItem> = demos.flatMap { it.items }) = SampleTrips.Rows(
        trips = trips + mine.trip,
        items = items + mine.items,
        entries = demos.flatMap { it.entries } + myEntry,
        stops = demos.flatMap { it.stops } + myStop,
        media = demos.flatMap { it.media } + myPhoto,
    )

    private fun SampleTrips.Changes.touchesOwn() =
        trips.any { !it.isDemo } || items.any { !Trip.isDemoId(it.tripId) } || entries.any { !Trip.isDemoId(it.tripId) } ||
            stops.any { !Trip.isDemoId(it.tripId) } || media.any { !Trip.isDemoId(it.tripId) }

    /** The links a fresh seed has, by what identifies an item across installs. */
    private fun expected(): Map<String, String> {
        val fresh = listOf(
            SampleArgentina.demo(today, today.atTime(18, 0).atZone(SampleArgentina.ZONE).toInstant(), now),
            SampleKyoto.demo(SampleKyoto.start(today), now),
            SampleLisbon.demo(SampleLisbon.start(today), now),
        )
        return fresh.flatMap { it.items }.filter { it.url != null }.associate { key(it) to it.url!! }
    }
    private fun key(item: PlanItem) = item.externalId ?: "${item.tripId}|${item.day}|${item.kind}|${item.title}"

    /** [rows] with [c] written over them, as the upgrade writes them. */
    private fun SampleTrips.Rows.with(c: SampleTrips.Changes): SampleTrips.Rows {
        fun <T> merge(old: List<T>, new: List<T>, id: (T) -> String): List<T> {
            val byId = new.associateBy(id)
            return old.map { byId[id(it)] ?: it } + new.filter { n -> old.none { id(it) == id(n) } }
        }
        return copy(
            trips = merge(trips, c.trips) { it.id }, items = merge(items, c.items) { it.id },
            entries = merge(entries, c.entries) { it.id }, stops = merge(stops, c.stops) { it.id }, media = merge(media, c.media) { it.id },
        )
    }

    @Test fun freshExamplesAlreadyHaveTheirLinks() {
        val links = expected()
        // Argentina: 30 of its 51 rows (the rest are hotels, meals, buses and notes). Kyoto and Lisbon: every program
        // item with a website at the demo's pace, ideas included.
        assertEquals(30, links.keys.count { it.startsWith(SampleArgentina.TRIP_ID) })
        for (program in listOf(SampleKyoto.program, SampleLisbon.program)) {
            val withLinks = program.daysAt(Pace.Balanced).sumOf { d -> d.items.count { it.url != null } } + program.ideas.count { it.url != null }
            assertTrue(program.id, withLinks >= 40)
            assertEquals(program.id, withLinks, links.keys.count { it.startsWith("${program.id}:") })
        }
        // A fresh seed (and so Me › Demo mode bringing an example back) needs nothing more.
        val fresh = listOf(
            SampleArgentina.demo(today, today.atTime(18, 0).atZone(SampleArgentina.ZONE).toInstant(), seeded),
            SampleKyoto.demo(SampleKyoto.start(today), seeded),
            SampleLisbon.demo(SampleLisbon.start(today), seeded),
        )
        val c = SampleTrips.addLinks(SampleTrips.Rows(fresh.map { it.trip }, fresh.flatMap { it.items }, fresh.flatMap { it.entries }, fresh.flatMap { it.stops }, fresh.flatMap { it.media }), now)
        assertEquals(SampleTrips.Changes(), c)
    }

    @Test fun untouchedExamplesGetEveryLinkAndKeepTheirItems() {
        val before = rows()
        val c = SampleTrips.addLinks(before, now)
        assertTrue(!c.touchesOwn())
        val links = expected()
        val linked = c.items.filter { it.url != null }
        assertEquals(links.size, linked.size)
        for (item in linked) assertEquals(item.title, links[key(item)], item.url)
        // Same rows: ids, days, positions, ticks, places; only the link and the stamps change.
        val old = before.items.associateBy { it.id }
        for (item in c.items) {
            val was = old.getValue(item.id)
            assertEquals(was.copy(url = item.url, createdAt = now, updatedAt = now), item)
        }
        // Argentina's days so far stay ticked.
        assertEquals(argentina.items.count { it.done }, c.items.count { it.tripId == SampleArgentina.TRIP_ID && it.done })
        // Untouched trips are restamped with everything seeded with them, so they still read as untouched.
        assertEquals(SampleTrips.CURRENT.toSet(), c.trips.map { it.id }.toSet())
        assertTrue(c.trips.all { it.createdAt == now && it.updatedAt == now })
        assertEquals(demos.sumOf { it.items.size }, c.items.size)
        assertEquals(demos.sumOf { it.entries.size } to demos.sumOf { it.stops.size }, c.entries.size to c.stops.size)
        assertEquals(demos.sumOf { it.media.size }, c.media.size)
        assertTrue((c.entries.map { it.createdAt to it.updatedAt } + c.stops.map { it.createdAt to it.updatedAt } + c.media.map { it.createdAt to it.updatedAt }).all { it == now to now })
        // The daily move sees an untouched demo: every row is restamped with it again.
        val after = before.with(c)
        val moved = DemoTrips.reanchor(
            after.trips.filter { it.isDemo }.map { t ->
                DemoTrips.Rows(t, after.items.filter { it.tripId == t.id }, after.entries.filter { it.tripId == t.id }, after.media.filter { it.tripId == t.id }, after.stops.filter { it.tripId == t.id })
            },
            today.plusDays(1), now + 1,
        )
        assertEquals(3, moved.size)
        assertTrue(moved.all { m -> (listOf(m.trip.createdAt) + m.items.map { it.createdAt } + m.entries.map { it.createdAt } + m.stops.map { it.createdAt }).all { it == now + 1 } })
        // Once is enough.
        assertEquals(SampleTrips.Changes(), SampleTrips.addLinks(after, now + 2))
    }

    @Test fun itemsTheTravellerChangedOrMadeAreLeftAlone() {
        val recoleta = argentina.items.single { it.title == "Recoleta Cemetery" }
        val perito = argentina.items.single { it.title == "Perito Moreno Glacier" }
        val campanario = argentina.items.single { it.title == "Cerro Campanario chairlift" }
        val fushimi = kyoto.items.single { it.title == "Fushimi Inari at first light" }
        val kiyomizu = kyoto.items.single { it.title == "Kiyomizu-dera" }
        val nijo = kyoto.items.single { it.title == "Nijo Castle" }
        val changed = mapOf(
            recoleta.id to recoleta.copy(note = "With Ana", updatedAt = 500), // edited
            perito.id to perito.copy(deletedAt = 500, updatedAt = 500), // deleted
            campanario.id to campanario.copy(url = "https://example.com/my-link"), // a link of its own
            fushimi.id to fushimi.copy(title = "Fushimi Inari"), // not the program's item any more
            kiyomizu.id to kiyomizu.copy(source = ItemSource.Manual.id), // not from the program
            nijo.id to nijo.copy(day = nijo.day!!.plusDays(1), updatedAt = 600), // moved to another day
        )
        // One of the traveller's own in a demo trip, on a demo item's day and with its name.
        val made = PlanItem("made-in-demo", SampleArgentina.TRIP_ID, day = recoleta.day, position = 9.0, kind = recoleta.kind, title = "Café Tortoni", createdAt = seeded, updatedAt = seeded)
        val items = demos.flatMap { it.items }.map { changed[it.id] ?: it } + made
        val c = SampleTrips.addLinks(rows(items = items), now)
        assertTrue(!c.touchesOwn())
        val out = c.items.associateBy { it.id }
        // Edited, deleted and moved rows are not written at all; the others keep what they had.
        for (id in listOf(recoleta.id, perito.id, nijo.id)) assertNull(out[id])
        assertEquals("https://example.com/my-link", out[campanario.id]?.url ?: changed.getValue(campanario.id).url)
        assertNull(out[fushimi.id]?.url)
        assertNull(out[kiyomizu.id]?.url)
        assertNull(out["made-in-demo"]?.url)
        // They all had a link to get; everything else still gets its own.
        val left = listOf(recoleta, perito, campanario, fushimi, kiyomizu, nijo)
        assertTrue(left.all { key(it) in expected() })
        val others = c.items.filter { it.url != null && it.id != campanario.id }
        assertEquals(expected().size - left.size, others.size)
    }

    @Test fun aChangedOrDeletedExampleTripOnlyGetsItsLinks() {
        val renamed = kyoto.trip.copy(title = "Japan in spring", updatedAt = 700)
        val gone = lisbon.trip.copy(deletedAt = 800, updatedAt = 800)
        val c = SampleTrips.addLinks(rows(trips = listOf(argentina.trip, renamed, gone)), now)
        // Deleted: nothing of it is written.
        assertTrue(c.items.none { it.tripId == SampleTrips.LISBON } && c.entries.none { it.tripId == SampleTrips.LISBON } && c.media.none { it.tripId == SampleTrips.LISBON })
        // Changed: the trip row stays as it is, its linked items move their updatedAt only, nothing else is restamped.
        assertTrue(c.trips.none { it.id == SampleTrips.KYOTO })
        val kyotoItems = c.items.filter { it.tripId == SampleTrips.KYOTO }
        assertTrue(kyotoItems.isNotEmpty() && kyotoItems.all { it.url != null && it.createdAt == seeded && it.updatedAt == now })
        assertEquals(expected().keys.count { it.startsWith(KyotoOsaka.destination.default.id) }, kyotoItems.size)
        // Argentina, untouched, as before.
        assertEquals(listOf(SampleArgentina.TRIP_ID), c.trips.map { it.id })
    }

    @Test fun nothingButTheTravellersOwnTripsMeansNothingToDo() {
        val own = SampleTrips.Rows(trips = listOf(mine.trip), items = mine.items, entries = listOf(myEntry), stops = listOf(myStop), media = listOf(myPhoto))
        assertEquals(SampleTrips.Changes(), SampleTrips.addLinks(own, now))
    }
}
