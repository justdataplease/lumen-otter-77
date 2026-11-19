package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.art.DemoArt
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.programs.Focus
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.Program
import com.justdataplease.boringtravel.programs.ProgramTrips
import com.justdataplease.boringtravel.programs.daysAt
import com.justdataplease.boringtravel.programs.data.KyotoOsaka
import com.justdataplease.boringtravel.programs.data.LisbonPorto
import com.justdataplease.boringtravel.tracking.StopSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The other two examples, built from the trip ideas: Kyoto & Osaka (next) and Lisbon & Porto (past, with a journal).
 * Written against whatever the programs hold, so the researched content passes the same checks.
 */
class SampleProgramsTest {
    private val today = LocalDate.of(2026, 11, 7)
    private val t = 1_000L
    private val kyoto = SampleKyoto.demo(SampleKyoto.start(today), t)
    private val lisbon = SampleLisbon.demo(SampleLisbon.start(today), t)

    private fun inside(d: LocalDate, demo: SampleArgentina.Demo) = !d.isBefore(demo.trip.startDate) && !d.isAfter(demo.trip.endDate)
    private fun lisbonDay(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(SampleLisbon.ZONE).toLocalDate()

    private fun everyDayHasSomething(demo: SampleArgentina.Demo, days: Int) {
        val start = demo.trip.startDate!!
        assertEquals(days, demo.trip.days)
        assertEquals((0 until days).map { start.plusDays(it.toLong()) }.toSet(), demo.items.mapNotNull { it.day }.toSet())
        assertTrue(demo.items.all { it.day.let { d -> d == null || inside(d, demo) } })
    }

    /** The demo is the default program (Sights & history) at a balanced pace, item for item, ideas included. */
    private fun isTheDefaultProgram(demo: SampleArgentina.Demo, program: Program) {
        assertEquals(Focus.Culture, program.focus)
        val start = demo.trip.startDate!!
        val days = program.daysAt(Pace.Balanced)
        for ((i, day) in days.withIndex()) {
            val planned = demo.items.filter { it.day == start.plusDays(i.toLong()) }.sortedBy { it.position }
            assertEquals("day ${i + 1}", day.items.map { it.title.trim() }, planned.map { it.title })
            assertEquals("day ${i + 1}", day.items.map { ProgramTrips.minutes(it.time) }, planned.map { it.timeMinutes })
        }
        assertEquals(program.ideas.map { it.title.trim() }, demo.items.filter { it.day == null }.sortedBy { it.position }.map { it.title })
        assertTrue(demo.items.all { it.source == ItemSource.Program.id && it.externalId!!.startsWith("${program.id}:") })
        assertEquals(ProgramTrips.route(program), demo.trip.subtitle)
    }

    /** Every leg into a place the program says how to reach goes that way. */
    private fun legsFollowTheProgram(demo: SampleArgentina.Demo, program: Program) {
        val via = (program.daysAt(Pace.Balanced).flatMap { it.items } + program.ideas).filter { it.at != null && it.via != null && it.via != Vehicle.Auto }
        val chosen = TravelModes.choices(demo.trip.travelModes)
        val byId = demo.items.associateBy { it.id }
        val legs = RouteLegs.planned(demo.trip, demo.items)
        assertTrue(legs.isNotEmpty())
        for (leg in legs) {
            val to = byId.getValue(leg.key.toId)
            val wanted = via.firstOrNull { it.title.trim() == to.title && it.at!!.first == to.lat && it.at!!.second == to.lng }?.via
            assertEquals(to.title, wanted, chosen[leg.key])
        }
    }

    // ---- Kyoto & Osaka ----

    @Test fun kyotoIsAPlannedTripAboutElevenMonthsAhead() {
        val trip = kyoto.trip
        val program = KyotoOsaka.destination.default
        assertEquals(SampleTrips.KYOTO, trip.id)
        assertTrue(trip.isDemo)
        assertEquals(KyotoOsaka.destination.title, trip.title)
        assertEquals(LocalDate.of(2027, 10, 3), trip.startDate)
        assertEquals(program.days.size, SampleKyoto.DAYS)
        assertEquals("off", trip.trackingLevel)
        everyDayHasSomething(kyoto, program.days.size)
        // Still to come: nothing done, no journal, no pictures, no stays.
        assertTrue(kyoto.items.none { it.done })
        assertTrue(kyoto.entries.isEmpty() && kyoto.media.isEmpty() && kyoto.stops.isEmpty())
        isTheDefaultProgram(kyoto, program)
        legsFollowTheProgram(kyoto, program)
        // Places in Japan.
        assertTrue(kyoto.items.filter { it.lat != null }.all { it.lat!! in 24.0..46.0 && it.lng!! in 122.0..146.0 })
    }

    // ---- Lisbon & Porto ----

    @Test fun lisbonIsAPastTripAllDone() {
        val trip = lisbon.trip
        val program = LisbonPorto.destination.default
        assertEquals(SampleTrips.LISBON, trip.id)
        assertTrue(trip.isDemo)
        assertEquals(LisbonPorto.destination.title, trip.title)
        assertEquals(LocalDate.of(2026, 5, 9), trip.startDate)
        assertTrue(trip.isPast(today))
        everyDayHasSomething(lisbon, program.days.size)
        // Every planned place done; notes and ideas never are.
        assertTrue(lisbon.items.filter { it.day != null && it.kind != "note" }.all { it.done })
        assertTrue(lisbon.items.filter { it.day == null || it.kind == "note" }.none { it.done })
        isTheDefaultProgram(lisbon, program)
        legsFollowTheProgram(lisbon, program)
        assertTrue(lisbon.items.filter { it.lat != null }.all { it.lat!! in 36.5..42.5 && it.lng!! in -10.0..-6.0 })
    }

    @Test fun lisbonStartsInMayAndIsOverByToday() {
        // Before the May trip is over this year, it is last year's.
        assertEquals(LocalDate.of(2026, 5, 9), SampleLisbon.start(LocalDate.of(2027, 5, 9)))
        assertEquals(LocalDate.of(2027, 5, 9), SampleLisbon.start(LocalDate.of(2027, 5, 9).plusDays(SampleLisbon.DAYS.toLong())))
    }

    @Test fun lisbonsJournalLiesWithinItsDays() {
        assertTrue(lisbon.entries.size in 6..8)
        assertTrue(lisbon.entries.all { inside(lisbonDay(it.occurredAt), lisbon) && it.tripId == SampleTrips.LISBON && it.zoneId == SampleLisbon.ZONE.id })
        assertTrue(lisbon.entries.all { it.weather == null && it.tempC == null && it.plainText.isNotBlank() && it.lat != null })
        // One entry a day at most, in trip order, Lisbon first then Porto.
        assertEquals(lisbon.entries.size, lisbon.entries.map { lisbonDay(it.occurredAt) }.toSet().size)
        assertEquals(listOf("Alfama, first evening", "Tram 28 and the castle", "Belém", "Sintra", "Bairro Alto", "North to Porto", "Lello and the cellars", "Douro valley"), lisbon.entries.map { it.title })
        // Untouched rows: one stamp.
        assertTrue((lisbon.entries.map { it.createdAt to it.updatedAt } + lisbon.items.map { it.createdAt to it.updatedAt }).all { it == t to t })
    }

    @Test fun lisbonsPicturesAreDrawnLikeArgentinasAndTakenBeforeEachEntry() {
        val byId = lisbon.media.associateBy { it.id }
        for (e in lisbon.entries) {
            val photos = EntryDoc.parse(e.body).blocks.filterIsInstance<Block.Photo>()
            assertEquals(e.title, 1, photos.size)
            val m = byId.getValue(photos.single().mediaId)
            assertEquals(e.id, m.entryId)
            assertTrue(m.localPath.startsWith("media/demo-"))
            assertEquals(DemoArt.Live(lisbon.pictures.getValue(m.id).kind, lisbon.pictures.getValue(m.id).mirror), DemoArt.live(m.localPath))
            assertTrue(m.takenAt!! <= e.occurredAt)
            assertEquals(lisbonDay(e.occurredAt), lisbonDay(m.takenAt!!))
        }
        // City, coast, village (Sintra), countryside (the Douro) and night; never the same picture twice.
        val kinds = lisbon.pictures.values.map { it.kind.id }.toSet()
        assertEquals(setOf("city", "coast", "village", "countryside", "night"), kinds)
        assertEquals(lisbon.pictures.size, lisbon.pictures.values.toSet().size)
    }

    @Test fun lisbonsStaysAreLisbonThenPorto() {
        assertEquals(listOf("Alfama, Lisbon", "Ribeira, Porto"), lisbon.stops.sortedBy { it.arrivedAt }.map { it.name })
        assertTrue(lisbon.stops.all { it.countryCode == "PT" && it.source == StopSource.MANUAL && it.leftAt != null })
        assertTrue(lisbon.stops.all { inside(lisbonDay(it.arrivedAt), lisbon) && inside(lisbonDay(it.leftAt!!), lisbon) })
        lisbon.stops.sortedBy { it.arrivedAt }.zipWithNext().forEach { (a, b) -> assertTrue(a.leftAt!! <= b.arrivedAt) }
    }

    @Test fun nothingIsBooked() {
        for (item in kyoto.items + lisbon.items) {
            assertTrue(item.title, !Regex("""€|\$|¥""").containsMatchIn(item.title + " " + item.note))
        }
    }

    // ---- Older installs ----

    private val seeded = 100L
    private fun bare() = SampleTrips.Rows(trips = listOf(SampleTrips.kyoto(today, seeded), SampleTrips.lisbon(today, seeded)))

    @Test fun untouchedBareExamplesGetTheirProgramsOnTheirOwnDates() {
        // Moved (re-anchored) a few days from the canonical dates: the program follows the trip.
        val rows = bare().let { r -> r.copy(trips = r.trips.map { it.copy(startDate = it.startDate!!.plusDays(3), endDate = it.endDate!!.plusDays(3)) }) }
        val filled = SampleTrips.fillPrograms(rows, 9_000L).associateBy { it.trip.id }
        assertEquals(setOf(SampleTrips.KYOTO, SampleTrips.LISBON), filled.keys)
        for ((id, demo) in filled) {
            val before = rows.trips.single { it.id == id }
            assertEquals(before.startDate to before.endDate, demo.trip.startDate to demo.trip.endDate)
            assertTrue(demo.items.all { it.tripId == id && it.day.let { d -> d == null || inside(d, demo) } })
            assertTrue((listOf(demo.trip.createdAt, demo.trip.updatedAt) + demo.items.map { it.updatedAt }).all { it == 9_000L })
        }
        assertEquals(8, filled.getValue(SampleTrips.LISBON).entries.size)
    }

    @Test fun anExampleTheTravellerTouchedIsNotFilled() {
        val base = bare()
        val renamed = base.copy(trips = base.trips.map { if (it.id == SampleTrips.KYOTO) it.copy(title = "Japan", updatedAt = 500) else it })
        assertEquals(listOf(SampleTrips.LISBON), SampleTrips.fillPrograms(renamed, 9_000L).map { it.trip.id })
        val deleted = base.copy(trips = base.trips.map { if (it.id == SampleTrips.LISBON) it.copy(deletedAt = 500, updatedAt = 500) else it })
        assertEquals(listOf(SampleTrips.KYOTO), SampleTrips.fillPrograms(deleted, 9_000L).map { it.trip.id })
        val planned = base.copy(items = listOf(PlanItem("mine", SampleTrips.KYOTO, title = "Ramen", createdAt = 500, updatedAt = 500)))
        assertEquals(listOf(SampleTrips.LISBON), SampleTrips.fillPrograms(planned, 9_000L).map { it.trip.id })
        // Already filled (a fresh seed): nothing to do.
        val full = SampleTrips.Rows(trips = listOf(kyoto.trip, lisbon.trip), items = kyoto.items + lisbon.items, entries = lisbon.entries, stops = lisbon.stops, media = lisbon.media)
        assertTrue(SampleTrips.fillPrograms(full, 9_000L).isEmpty())
    }
}
