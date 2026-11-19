package com.justdataplease.boringtravel.ui.programs

import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.programs.Destination
import com.justdataplease.boringtravel.programs.Focus
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.Program
import com.justdataplease.boringtravel.programs.ProgramDay
import com.justdataplease.boringtravel.programs.ProgramItem
import com.justdataplease.boringtravel.programs.ProgramLibrary
import com.justdataplease.boringtravel.programs.ProgramTrips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** What Trip ideas shows: the same days and items the trip gets, in short plain lines. */
class ProgramPreviewTest {
    private val program = Program(
        id = "p", destinationId = "d", focus = Focus.Food, title = "Eat", blurb = "",
        days = listOf(
            ProgramDay("Arrive", "Kyoto", listOf(
                ProgramItem("transport", "Train to Kyoto", "≈ 1 h 20", at = 35.0 to 135.7, via = Vehicle.Train, time = "07:30"),
                ProgramItem("food", "Market", "Stalls close early", at = 35.01 to 135.76, via = Vehicle.Walk),
                ProgramItem("place", "Late bar", minPace = Pace.Energetic),
            )),
            ProgramDay(" ", "Osaka", listOf(ProgramItem("note", "Rest", time = "late"))),
        ),
        ideas = listOf(ProgramItem("place", "Kobe", "Half a day")),
        sources = listOf("https://www.japan-guide.com/e/e3900.html", "https://kyoto.travel/en/", "http://www.japan-guide.com/x", " ", "Local tourist office"),
    )

    @Test fun daysAreTheTripsDaysAtThatPace() {
        val chill = ProgramPreview.days(program, Pace.Chill)
        assertEquals(listOf("Day 1 · Arrive", "Day 2"), chill.map { it.label })
        assertEquals(listOf(listOf("Train to Kyoto", "Market"), listOf("Rest")), chill.map { d -> d.rows.map { it.title } })
        assertEquals(3, ProgramPreview.days(program, Pace.Energetic).first().rows.size)
        // The preview is what the trip gets.
        val trip = ProgramTrips.build(Destination("d", "D", "", listOf("JP"), "Asia/Tokyo", "", listOf(program)), program, Pace.Chill, LocalDate.of(2027, 1, 1), 1L, links = LinkPlaces::sortLinks)
        assertEquals(chill.flatMap { d -> d.rows.map { it.title } }, trip.items.filter { it.day != null }.sortedWith(compareBy({ it.day }, { it.position })).map { it.title })
    }

    @Test fun rowsSayTimeAndNoteAndHowTravelGoes() {
        val (train, market) = ProgramPreview.days(program, Pace.Chill).first().rows
        assertEquals("07:30 · ≈ 1 h 20", train.line)
        assertEquals(Vehicle.Train, train.vehicle)
        assertEquals("transport", train.kind)
        assertEquals("Stalls close early", market.line)
        // Only travel shows a vehicle; the market is reached on foot but is food.
        assertNull(market.vehicle)
        assertEquals("", ProgramPreview.days(program, Pace.Chill)[1].rows.single().line)
        assertEquals(listOf("Kobe"), ProgramPreview.ideas(program).map { it.title })
    }

    @Test fun shortFacts() {
        assertEquals("2 days · Kyoto → Osaka", ProgramPreview.facts(program))
        assertEquals("Day 4 · Day trip to Nara", ProgramPreview.dayLabel(4, " Day trip to Nara "))
        assertEquals(ProgramLibrary.destinations.joinToString(", ") { it.title }, ProgramPreview.names(ProgramLibrary.destinations))
    }

    @Test fun sourcesAreTheSitesOnce() {
        assertEquals("Sources: japan-guide.com · kyoto.travel · Local tourist office", ProgramPreview.sources(program.sources))
        assertNull(ProgramPreview.sources(emptyList()))
        assertNull(ProgramPreview.sources(listOf(" ")))
        assertEquals("visitportugal.com", ProgramPreview.site("https://WWW.VisitPortugal.com:443/en?x=1"))
    }
}
