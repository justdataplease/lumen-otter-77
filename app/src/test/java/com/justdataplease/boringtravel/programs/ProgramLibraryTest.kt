package com.justdataplease.boringtravel.programs

import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.art.SceneKind
import com.justdataplease.boringtravel.data.SampleLisbon
import com.justdataplease.boringtravel.geo.Geo
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.programs.data.ArgentinaPatagonia
import com.justdataplease.boringtravel.programs.data.KyotoOsaka
import com.justdataplease.boringtravel.programs.data.LisbonPorto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The bundled trip ideas, whatever their content: what the screens and the trip builder rely on. Written against any
 * destination so the researched programs pass the same checks as the placeholders.
 */
class ProgramLibraryTest {
    private val all = ProgramLibrary.destinations
    private val start = LocalDate.of(2027, 4, 10)

    @Test fun kyotoOsakaThenLisbonPortoThenArgentinaWithDifferentPictures() {
        assertEquals(listOf(KyotoOsaka.destination.id, LisbonPorto.destination.id, ArgentinaPatagonia.destination.id), all.map { it.id })
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertNotEquals(ProgramLibrary.scene(KyotoOsaka.destination), ProgramLibrary.scene(LisbonPorto.destination))
        assertEquals(all.size, all.map { ProgramLibrary.scene(it) }.toSet().size)
        assertEquals(SceneKind.City, ProgramLibrary.scene(KyotoOsaka.destination))
        assertEquals(SceneKind.Mountain, ProgramLibrary.scene(ArgentinaPatagonia.destination))
        assertEquals(ArgentinaPatagonia.destination, ProgramLibrary.destination("argentina-patagonia"))
        assertEquals(KyotoOsaka.destination, ProgramLibrary.destination(KyotoOsaka.destination.id))
        assertEquals(null, ProgramLibrary.destination("nowhere"))
        assertEquals(null, ProgramLibrary.destination(null))
    }

    @Test fun everyDestinationHasOneProgramPerAnswer() {
        val programIds = all.flatMap { d -> d.programs.map { it.id } }
        assertEquals(programIds.size, programIds.toSet().size)
        for (d in all) {
            assertEquals(d.id, Focus.entries.toSet(), d.programs.map { it.focus }.toSet())
            assertEquals(d.id, Focus.entries.size, d.programs.size)
            assertTrue(d.id, d.programs.all { it.destinationId == d.id && it.id.isNotBlank() && ':' !in it.id })
            for (f in Focus.entries) assertEquals(f, d.program(f).focus)
            assertEquals(Focus.Culture, d.default.focus)
            assertTrue(d.id, d.title.isNotBlank() && d.blurb.isNotBlank() && d.bestTime.isNotBlank())
            assertNotNull(ZoneId.of(d.zoneId))
            assertTrue(d.id, d.countryCodes.isNotEmpty() && d.countryCodes.all { it.matches(Regex("[A-Z]{2}")) })
        }
    }

    @Test fun everyItemIsSomethingThePlanCanShow() {
        for (d in all) for (p in d.programs) {
            assertTrue(p.id, p.days.isNotEmpty() && p.title.isNotBlank())
            for ((i, day) in p.days.withIndex()) assertTrue("${p.id} day ${i + 1}", day.items.isNotEmpty())
            for (item in p.days.flatMap { it.items } + p.ideas) {
                val at = "${p.id}: ${item.title}"
                assertTrue(at, item.title.isNotBlank())
                assertEquals(at, item.kind, PlanKind.of(item.kind).id)
                if (item.time != null) assertNotNull(at, ProgramTrips.minutes(item.time))
                item.at?.let { (lat, lng) -> assertTrue(at, lat in -85.0..85.0 && lng in -180.0..180.0) }
            }
        }
    }

    @Test fun everyProgramMakesATripAtEveryPace() {
        for (d in all) for (p in d.programs) for (pace in Pace.entries) {
            val (trip, items) = ProgramTrips.build(d, p, pace, start, 1L, links = LinkPlaces::sortLinks)
            assertEquals(p.id, p.days.size, trip.days)
            assertEquals(d.title, trip.title)
            assertTrue(p.id, trip.subtitle.isNotBlank())
            val days = (0 until p.days.size).map { start.plusDays(it.toLong()) }.toSet()
            assertEquals("${p.id} $pace", days, items.mapNotNull { it.day }.toSet())
            assertEquals(p.ideas.size, items.count { it.day == null })
            assertEquals(items.size, items.map { it.externalId }.toSet().size)
        }
    }

    /** The demo's journal (SampleLisbon) is written on Lisbon & Porto's default program: each entry's place is on its day. */
    @Test fun lisbonsJournalMatchesItsDays() {
        val demo = SampleLisbon.demo(SampleLisbon.start(start), 1L)
        val zone = ZoneId.of(LisbonPorto.destination.zoneId)
        for (e in demo.entries) {
            val day = Instant.ofEpochMilli(e.occurredAt).atZone(zone).toLocalDate()
            val there = demo.items.filter { it.day == day && it.lat != null }
            val nearest = there.minOfOrNull { Geo.distanceM(e.lat!!, e.lng!!, it.lat!!, it.lng!!) }
            assertNotNull("${e.title}: no place planned that day", nearest)
            assertTrue("${e.title}: ${nearest!! / 1000} km from that day's places", nearest <= 30_000.0)
        }
    }
}
