package com.justdataplease.boringtravel.model

import com.justdataplease.boringtravel.backup.toRow
import com.justdataplease.boringtravel.backup.toTrip
import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.RouteLegSource
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.Trip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A day's own name ("Day 3 · Temples and tea"), kept by date in the trip's portable JSON beside the travel choices. */
class DayNamesTest {
    private val d3 = LocalDate.of(2026, 5, 4)
    private val d4 = d3.plusDays(1)
    private val leg = RouteLegKey(RouteLegSource.Planned, "a", "b")

    @Test fun aDayIsNamedRenamedAndUnNamed() {
        assertEquals(emptyMap<LocalDate, String>(), DayNames.all(null))
        val named = DayNames.set(null, d3, "  Temples   and tea ")
        assertEquals("""{"days":{"2026-05-04":"Temples and tea"}}""", named)
        assertEquals("Temples and tea", DayNames.of(named, d3))
        assertNull(DayNames.of(named, d4))
        val both = DayNames.setAll(named, mapOf(d4 to "Beach day", d3 to "Old town"))
        assertEquals(mapOf(d3 to "Old town", d4 to "Beach day"), DayNames.all(both))
        // The same name again changes nothing: the same text comes back, so the trip isn't saved for it.
        assertSame(both, DayNames.set(both, d3, "Old town"))
        assertSame(both, DayNames.set(both, d3.plusDays(9), " "))
        // A blank name takes the name away; the last one leaves nothing behind.
        val one = DayNames.set(both, d3, "")
        assertEquals(mapOf(d4 to "Beach day"), DayNames.all(one))
        assertNull(DayNames.set(one, d4, null))
    }

    @Test fun aNameIsOneShortLine() {
        assertEquals("Temples and tea", DayNames.clean("Temples\nand\ttea"))
        assertNull(DayNames.clean("  \n "))
        assertNull(DayNames.clean(null))
        val long = DayNames.clean("The temples of the eastern hills and a very long tea ceremony afterwards")!!
        assertTrue(long, long.length <= DayNames.MAX && long == "The temples of the eastern hills and a")
        assertEquals(DayNames.MAX, DayNames.clean("x".repeat(90))!!.length)
        assertEquals("Ναοί και τσάι 🍵", DayNames.clean(" Ναοί και τσάι 🍵 "))
    }

    @Test fun theNamesLiveBesideTheTravelChoicesAndNeitherLosesTheOther() {
        // Travel choices first, then a name: the legs and a field of some later version stay as they are.
        val legs = TravelModes.set("""{"v":1,"legs":[],"later":{"x":1}}""", leg, Vehicle.Train)
        val named = DayNames.set(legs, d3, "Temples and tea")!!
        assertEquals(mapOf(leg to Vehicle.Train), TravelModes.choices(named))
        assertEquals("""{"x":1}""", Json.parseToJsonElement(named).jsonObject.getValue("later").toString())
        // A travel choice made afterwards (also by a version that knows nothing of day names) keeps the names.
        val again = TravelModes.set(named, RouteLegKey(RouteLegSource.Planned, "b", "c"), Vehicle.Ferry)
        assertEquals(mapOf(d3 to "Temples and tea"), DayNames.all(again))
        assertEquals(2, TravelModes.choices(again).size)
        // Un-naming the day leaves the travel choices.
        assertEquals(2, TravelModes.choices(DayNames.set(again, d3, null)).size)
        assertTrue(DayNames.all(DayNames.set(again, d3, null)).isEmpty())
    }

    @Test fun theNamesMoveWithTheTrip() {
        val named = DayNames.setAll(null, mapOf(d3 to "Temples and tea", d4 to "Beach day"))
        assertEquals(mapOf(d3.plusDays(2) to "Temples and tea", d4.plusDays(2) to "Beach day"), DayNames.all(DayNames.shift(named, 2)))
        assertEquals(mapOf(d3.minusDays(30) to "Temples and tea", d4.minusDays(30) to "Beach day"), DayNames.all(DayNames.shift(named, -30)))
        assertSame(named, DayNames.shift(named, 0))
        assertNull(DayNames.shift(null, 3))
        val legsOnly = TravelModes.set(null, leg, Vehicle.Train)
        assertSame(legsOnly, DayNames.shift(legsOnly, 3))
    }

    @Test fun aTextThatCannotBeReadIsLeftAlone() {
        for (broken in listOf("not json", "[1,2]", "\"days\"")) {
            assertTrue(DayNames.all(broken).isEmpty())
            assertSame(broken, DayNames.set(broken, d3, "Temples and tea"))
            assertSame(broken, DayNames.shift(broken, 2))
        }
        // Names that aren't names, days that aren't days: skipped, the good one kept.
        val odd = """{"days":{"2026-05-04":"Temples and tea","someday":"x","2026-05-05":7,"2026-05-06":"  "}}"""
        assertEquals(mapOf(d3 to "Temples and tea"), DayNames.all(odd))
        assertTrue(DayNames.all("""{"days":["Temples"]}""").isEmpty())
    }

    @Test fun backupsAndSharedTripsCarryTheNamesAsTheyAre() {
        // Both write the trip's row with this text in it (backup/BackupFormat, together/TogetherMerge).
        val trip = Trip("t", "Japan", startDate = d3.minusDays(2), endDate = d3.plusDays(5), createdAt = 1, updatedAt = 2, travelModes = DayNames.set(null, d3, "Temples and tea"))
        assertEquals(trip, trip.toRow().toTrip())
        assertEquals("Temples and tea", DayNames.of(trip.toRow().travelModes, d3))
    }
}
