package com.justdataplease.boringtravel.world

import com.justdataplease.boringtravel.data.db.CountryMark
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.geo.Continent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class WorldModelTest {
    private val today = LocalDate.of(2026, 11, 6)
    private val zone = ZoneOffset.UTC
    private val now = today.atTime(12, 0).toInstant(zone).toEpochMilli()

    /** A pretend country lookup: a few boxes around real places. */
    private val codeAt: (Double, Double) -> String? = { lat, lng ->
        when {
            lat in 36.0..42.5 && lng in -9.6..-6.0 -> "PT"
            lat in 28.0..35.9 && lng in -13.0..-1.0 -> "MA"
            lat in 34.0..42.0 && lng in 19.0..28.0 -> "GR"
            lat in 36.0..47.0 && lng in 6.0..18.9 -> "IT"
            lat in 30.0..46.0 && lng in 129.0..146.0 -> "JP"
            lat in -47.0..-34.0 && lng in 166.0..179.0 -> "NZ"
            else -> null
        }
    }

    private val naxos = 37.10 to 25.37
    private val halki = 37.06 to 25.48
    private val rome = 41.90 to 12.50
    private val lisbon = 38.72 to -9.14
    private val kyoto = 35.01 to 135.77
    private val marrakesh = 31.63 to -8.0

    private fun ms(d: LocalDate, h: Int = 12) = d.atTime(h, 0).toInstant(zone).toEpochMilli()
    private fun trip(id: String, start: LocalDate?, end: LocalDate?, cover: String? = null, deleted: Boolean = false) =
        Trip(id, id, "", start, end, cover, createdAt = 1, updatedAt = 1, deletedAt = if (deleted) 2 else null)
    private fun stop(id: String, trip: String?, p: Pair<Double, Double>, at: LocalDate, until: LocalDate? = null, code: String? = null, name: String = "", deleted: Boolean = false) =
        Stop(id, trip, ms(at), until?.let { ms(it) }, p.first, p.second, name, code, createdAt = 1, updatedAt = 1, deletedAt = if (deleted) 2 else null)
    private fun entry(id: String, trip: String?, p: Pair<Double, Double>?, at: LocalDate, place: String = "", deleted: Boolean = false) =
        Entry(id, trip, ms(at), "UTC", placeName = place, lat = p?.first, lng = p?.second, createdAt = 1, updatedAt = 1, deletedAt = if (deleted) 2 else null)
    private fun photo(id: String, trip: String?, p: Pair<Double, Double>?, at: LocalDate?, entryId: String? = null) =
        Media(id, trip, entryId, "media/$id.jpg", takenAt = at?.let { ms(it) }, lat = p?.first, lng = p?.second, createdAt = 1, updatedAt = 1)
    private fun item(id: String, trip: String, p: Pair<Double, Double>?, day: LocalDate? = null, done: Boolean = false, kind: String = "place", place: String = "") =
        PlanItem(id, trip, day, 1.0, null, kind, id, "", place, p?.first, p?.second, done, createdAt = 1, updatedAt = 1)
    private fun mark(code: String, source: String, deleted: Boolean = false) =
        CountryMark(CountryMark.id(source, code), code, source, 1, 1, if (deleted) 2 else null)

    private fun build(input: WorldInput) = WorldModel.build(input, today, zone, now, codeAt)

    @Test fun everySourceCountsThroughTheLookup() {
        val w = build(
            WorldInput(
                trips = listOf(
                    trip("lisbon", LocalDate.of(2026, 5, 9), LocalDate.of(2026, 5, 17)),
                    trip("cyclades", LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 12)),
                    trip("japan", LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 4)),
                ),
                stops = listOf(
                    stop("s1", "lisbon", lisbon, LocalDate.of(2026, 5, 9), code = " pt"), // stored code wins, any case
                    stop("s2", "cyclades", naxos, LocalDate.of(2026, 11, 3)), // no code yet: looked up
                ),
                entries = listOf(entry("e1", "cyclades", halki, LocalDate.of(2026, 11, 5))),
                media = listOf(photo("m1", null, rome, LocalDate.of(2025, 6, 1))), // a photo with a place, outside trips
                planItems = listOf(item("kyoto", "japan", kyoto, LocalDate.of(2024, 3, 2), done = true)),
                marks = listOf(mark("FR", CountryMark.SOURCE_MANUAL)),
            ),
        )
        assertEquals(setOf("PT", "GR", "IT", "JP", "FR"), w.visitedCodes)
        assertEquals(5, w.countries)
        assertEquals(3, w.worldPercent) // 5 of 195
        assertEquals(mapOf(Continent.Europe to 4, Continent.Asia to 1), w.continents)
        // Earliest first; the hand-added one (no date) last.
        assertEquals(listOf("JP", "IT", "PT", "GR", "FR"), w.visited.map { it.code })
        assertTrue(w.byCode["FR"]!!.manual)
        assertFalse(w.byCode["FR"]!!.found)
        assertEquals(2024, w.sinceYear)
        assertTrue(w.hasTrips)
    }

    @Test fun deletedRowsAndRemovedCountriesStayOut() {
        val w = build(
            WorldInput(
                trips = listOf(trip("a", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3)), trip("gone", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2), deleted = true)),
                stops = listOf(
                    stop("s1", "a", naxos, LocalDate.of(2026, 5, 1), deleted = true),
                    stop("s2", "gone", lisbon, LocalDate.of(2025, 1, 1)), // in a deleted trip
                ),
                entries = listOf(entry("e1", "a", kyoto, LocalDate.of(2026, 5, 2), deleted = true)),
                media = listOf(photo("m1", "a", rome, LocalDate.of(2026, 5, 2))),
                planItems = listOf(item("p1", "a", marrakesh)), // planned, not done, trip over: neither
                marks = listOf(mark("IT", CountryMark.SOURCE_HIDDEN), mark("NZ", CountryMark.SOURCE_MANUAL, deleted = true)),
            ),
        )
        assertTrue(w.visited.isEmpty())
        assertTrue(w.planned.isEmpty())
        assertEquals(0, w.worldPercent)
        assertNull(w.tripCountries["a"])
    }

    @Test fun addedByHandBeatsRemoved() {
        val w = build(
            WorldInput(
                media = listOf(photo("m1", null, rome, null)),
                marks = listOf(mark("IT", CountryMark.SOURCE_HIDDEN), mark("IT", CountryMark.SOURCE_MANUAL)),
            ),
        )
        assertEquals(setOf("IT"), w.visitedCodes)
    }

    @Test fun plansWishesAndTheNextTrip() {
        val w = build(
            WorldInput(
                trips = listOf(
                    trip("been", LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 5)),
                    trip("next", LocalDate.of(2027, 10, 3), LocalDate.of(2027, 10, 14)),
                    trip("later", LocalDate.of(2028, 1, 3), LocalDate.of(2028, 1, 9)),
                    trip("someday", null, null),
                ),
                stops = listOf(stop("s1", "been", kyoto, LocalDate.of(2025, 4, 1), code = "JP")),
                planItems = listOf(
                    item("p1", "next", kyoto, LocalDate.of(2027, 10, 4)), // already been: not "planned"
                    item("p2", "next", marrakesh, LocalDate.of(2027, 10, 9)),
                    item("p3", "later", rome),
                    item("p4", "someday", lisbon),
                ),
                marks = listOf(mark("NZ", CountryMark.SOURCE_WISH), mark("JP", CountryMark.SOURCE_WISH)),
            ),
        )
        assertEquals(setOf("MA", "IT", "PT"), w.planned)
        assertEquals(setOf("NZ"), w.wished) // Japan is visited already
        assertEquals("next", w.nextTripId)
        assertEquals(listOf("MA"), w.nextCodes)
        assertEquals(listOf("JP", "MA"), w.tripCountries["next"])
        assertEquals(listOf("later"), w.byCode["IT"]!!.plannedTripIds)
        assertEquals(listOf("next"), w.byCode["MA"]!!.plannedTripIds)
        assertEquals(listOf("someday"), w.byCode["PT"]!!.plannedTripIds)
        // Continent rows: visited ones first, then 0 rows for continents only planned or wished.
        val rows = w.continentRows()
        assertEquals(Continent.Asia to 1, rows.first())
        assertTrue((Continent.Africa to 0) in rows)
        assertTrue((Continent.Oceania to 0) in rows)
    }

    @Test fun eachTripDayGoesToWhereYouWere() {
        val w = build(
            WorldInput(
                trips = listOf(trip("t", LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 12))),
                stops = listOf(stop("s1", "t", naxos, LocalDate.of(2026, 11, 3), until = LocalDate.of(2026, 11, 4))),
                entries = listOf(entry("e1", "t", rome, LocalDate.of(2026, 11, 6))),
            ),
        )
        // 3, 4 Nov in Greece, 5 Nov carried over (no rows that day), 6 Nov (today) in Italy; the rest is still to come.
        assertEquals(3, w.byCode["GR"]!!.days)
        assertEquals(1, w.byCode["IT"]!!.days)
        assertEquals(4, w.daysAway)
        assertEquals(listOf("GR", "IT"), w.tripCountries["t"])
    }

    @Test fun aTripWithOneCountryAndNoDatedRowsGetsAllItsDays() {
        val w = build(
            WorldInput(
                trips = listOf(trip("t", LocalDate.of(2026, 5, 9), LocalDate.of(2026, 5, 17))),
                planItems = listOf(item("p", "t", lisbon, done = true)), // ticked, no day
            ),
        )
        assertEquals(9, w.byCode["PT"]!!.days)
        assertEquals(LocalDate.of(2026, 5, 9), w.byCode["PT"]!!.firstVisit)
    }

    @Test fun placesAreCountedOnce() {
        val w = build(
            WorldInput(
                trips = listOf(trip("t", LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 12))),
                stops = listOf(
                    stop("s1", "t", halki, LocalDate.of(2026, 11, 4), name = "Halki, Naxos"),
                    stop("s2", "t", naxos, LocalDate.of(2026, 11, 5)), // unnamed, elsewhere
                ),
                entries = listOf(entry("e1", "t", halki, LocalDate.of(2026, 11, 4), place = "Halki")), // same name
                planItems = listOf(
                    item("Lunch in Halki", "t", halki, LocalDate.of(2026, 11, 4), done = true, kind = "food"), // same spot
                    item("Ferry", "t", naxos, LocalDate.of(2026, 11, 3), done = true, kind = "transport"), // not a place
                ),
                media = listOf(photo("m1", "t", halki, LocalDate.of(2026, 11, 4))),
            ),
        )
        val gr = w.byCode["GR"]!!
        assertEquals(2, gr.places)
        assertEquals(1, gr.entries)
        assertEquals(1, gr.photos)
        assertEquals(listOf("t"), gr.tripIds)
    }

    @Test fun theHeroPhotoIsAChosenCoverThenAJournalPhoto() {
        val trips = listOf(trip("t", LocalDate.of(2026, 5, 9), LocalDate.of(2026, 5, 17), cover = "cover"))
        val photos = listOf(
            photo("cover", "t", lisbon, LocalDate.of(2026, 5, 10)),
            photo("loose", "t", lisbon, LocalDate.of(2026, 5, 15)),
            photo("journal", "t", lisbon, LocalDate.of(2026, 5, 11), entryId = "e"),
        )
        assertEquals("cover", build(WorldInput(trips = trips, media = photos)).byCode["PT"]!!.heroMediaId)
        val noCover = listOf(trip("t", LocalDate.of(2026, 5, 9), LocalDate.of(2026, 5, 17)))
        assertEquals("journal", build(WorldInput(trips = noCover, media = photos)).byCode["PT"]!!.heroMediaId)
        // A cover taken somewhere else isn't this country's photo.
        val awayCover = listOf(trip("t", LocalDate.of(2026, 5, 9), LocalDate.of(2026, 5, 17), cover = "rome"))
        val withRome = photos + photo("rome", "t", rome, LocalDate.of(2026, 5, 12))
        assertEquals("journal", build(WorldInput(trips = awayCover, media = withRome)).byCode["PT"]!!.heroMediaId)
    }

    @Test fun totalsCountStartedTripsOnly() {
        val w = build(
            WorldInput(
                trips = listOf(
                    trip("past", LocalDate.of(2026, 5, 9), LocalDate.of(2026, 5, 17)),
                    trip("now", LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 12)),
                    trip("next", LocalDate.of(2027, 10, 3), LocalDate.of(2027, 10, 14)),
                ),
                metersByTrip = mapOf("past" to 10_000.0, "now" to 5_500.0, "next" to 99_000.0),
            ),
        )
        assertEquals(2, w.trips)
        assertEquals(15.5, w.km, 1e-9)
        assertEquals(9 + 4, w.daysAway)
        assertEquals(0, w.countries)
    }

    @Test fun countriesPerTripDateForTheCalendar() {
        val start = LocalDate.of(2026, 11, 3)
        val w = build(
            WorldInput(
                trips = listOf(
                    trip("now", start, start.plusDays(6)),
                    trip("later", LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 5)),
                    trip("past", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5)),
                ),
                stops = listOf(
                    stop("s1", "now", lisbon, start, until = start, code = "PT"),
                    // In Lisbon in the morning, then Marrakesh: the day's countries in that order.
                    Stop("s2", "now", ms(start.plusDays(2), 8), ms(start.plusDays(2), 9), lisbon.first, lisbon.second, "", "PT", createdAt = 1, updatedAt = 1),
                ),
                entries = listOf(entry("e1", "now", marrakesh, start.plusDays(1)), entry("e2", "now", marrakesh, start.plusDays(2))),
                planItems = listOf(
                    item("p1", "now", rome, start), // planned for a day something already places: what happened wins
                    item("p2", "now", naxos, start.plusDays(5)),
                    item("p3", "later", kyoto, LocalDate.of(2027, 3, 2)),
                    item("p4", "later", rome, null), // an idea without a day
                    item("p5", "past", lisbon, LocalDate.of(2026, 5, 2)), // never ticked, but planned for that day
                ),
            ),
        )
        assertEquals(
            mapOf(
                start to listOf("PT"),
                start.plusDays(1) to listOf("MA"),
                start.plusDays(2) to listOf("PT", "MA"),
                start.plusDays(5) to listOf("GR"),
            ),
            w.tripDayCountries["now"],
        )
        assertEquals(mapOf(LocalDate.of(2027, 3, 2) to listOf("JP")), w.tripDayCountries["later"])
        assertEquals(mapOf(LocalDate.of(2026, 5, 2) to listOf("PT")), w.tripDayCountries["past"])
        // The flags and visited countries are as before: a plan alone is not a visit.
        assertFalse("IT" in w.visitedCodes)
    }

    @Test fun nothingYet() {
        val w = build(WorldInput())
        assertEquals(0, w.countries)
        assertEquals(0, w.worldPercent)
        assertFalse(w.hasTrips)
        assertNull(w.sinceYear)
        assertTrue(w.continentRows().isEmpty())
    }

    @Test fun cellsTellNearbySpotsApart() {
        assertEquals(WorldModel.cell(37.061, 25.481), WorldModel.cell(37.0612, 25.4808))
        assertTrue(WorldModel.cell(37.06, 25.48) != WorldModel.cell(37.08, 25.48))
        assertTrue(WorldModel.cell(-33.9, 151.2) != WorldModel.cell(33.9, -151.2))
    }
}
