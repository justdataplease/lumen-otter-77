package com.justdataplease.boringtravel.programs

import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A trip idea becomes a trip: dates, pace, order, times, travel modes, ideas and where each item came from. */
class ProgramTripsTest {
    private val start = LocalDate.of(2027, 4, 10)
    private val now = 5_000L

    private val a = 35.00 to 135.70
    private val b = 35.01 to 135.71
    private val c = 34.68 to 135.82
    private val d = 34.69 to 135.84

    /** Three days in two bases, with items for every pace, a note, a timed item and two ideas. */
    private val program = Program(
        id = "test-culture", destinationId = "test", focus = Focus.Culture, title = "Test", blurb = "",
        days = listOf(
            ProgramDay("Arrive", "Kyoto", listOf(
                ProgramItem("transport", "Fly in", at = a),
                ProgramItem("stay", "Inn", at = b, via = Vehicle.Car),
                ProgramItem("place", "Late temple", "Only when energetic", at = 35.02 to 135.72, via = Vehicle.Walk, minPace = Pace.Energetic),
            )),
            ProgramDay("Day trip to Nara", "Kyoto", listOf(
                ProgramItem("note", "Slow breakfast", minPace = Pace.Balanced),
                ProgramItem("transport", "Train to Nara", "≈ 45 min", at = c, via = Vehicle.Train, time = "08:15"),
                ProgramItem("place", "Park", at = d, via = Vehicle.Walk, minPace = Pace.Balanced, placeName = "Nara Park, Nara, Japan"),
            )),
            ProgramDay("", " Osaka ", listOf(
                ProgramItem("place", "Castle", at = 34.687 to 135.526, via = Vehicle.Train, minPace = Pace.Energetic),
                ProgramItem("food", "Market", at = 34.665 to 135.506, via = Vehicle.Walk, minPace = Pace.Energetic, time = "late"),
            )),
        ),
        ideas = listOf(ProgramItem("place", "Kobe", "Half a day", at = 34.69 to 135.19), ProgramItem("note", "Book seats")),
    )
    private val destination = Destination("test", "Kyoto & Osaka", "", listOf("JP"), "Asia/Tokyo", "", listOf(program))

    private fun ids(): () -> String { var n = 0; return { "id-${n++}" } }
    private fun build(pace: Pace, doneBefore: Int = 1, trackingLevel: String? = null) =
        ProgramTrips.build(destination, program, pace, start, now, links = LinkPlaces::sortLinks, ids = ids(), tripId = "trip", doneBefore = doneBefore, trackingLevel = trackingLevel)

    private fun dayOf(item: PlanItem): Int? = item.day?.let { (it.toEpochDay() - start.toEpochDay() + 1).toInt() }
    private fun titles(items: List<PlanItem>, day: Int?) = items.filter { dayOf(it) == day }.sortedBy { it.position }.map { it.title }

    @Test fun theTripRunsFromTheFirstDayForAsManyDaysAsTheProgram() {
        val (trip, items) = build(Pace.Balanced)
        assertEquals(Trip(
            "trip", "Kyoto & Osaka", "Kyoto → Osaka", start, start.plusDays(2), createdAt = now, updatedAt = now, travelModes = trip.travelModes,
        ), trip)
        assertEquals(setOf(1, 2, 3), items.mapNotNull(::dayOf).toSet())
        assertTrue(items.all { it.tripId == "trip" && it.createdAt == now && it.updatedAt == now && it.deletedAt == null })
        // The examples ask for no tracking; a trip of the traveller's own follows the app's setting.
        assertEquals("off", build(Pace.Balanced, trackingLevel = "off").trip.trackingLevel)
        assertNull(trip.trackingLevel)
    }

    @Test fun thePaceDecidesWhichItemsCome() {
        assertEquals(listOf("Fly in", "Inn"), titles(build(Pace.Chill).items, 1))
        assertEquals(listOf("Train to Nara"), titles(build(Pace.Chill).items, 2))
        assertEquals(listOf("Slow breakfast", "Train to Nara", "Park"), titles(build(Pace.Balanced).items, 2))
        assertEquals(listOf("Fly in", "Inn", "Late temple"), titles(build(Pace.Energetic).items, 1))
        assertEquals(listOf("Castle", "Market"), titles(build(Pace.Energetic).items, 3))
        // A day with nothing at this pace keeps its first item, so no day is ever empty.
        assertEquals(listOf("Castle"), titles(build(Pace.Chill).items, 3))
        assertEquals(listOf("Castle"), titles(build(Pace.Balanced).items, 3))
        // The same as the program's own daysAt.
        for (pace in Pace.entries) {
            val items = build(pace).items
            assertEquals(program.daysAt(pace).map { day -> day.items.map { it.title } }, (1..3).map { titles(items, it) })
        }
    }

    @Test fun itemsKeepTheProgramOrderTimesAndPlaces() {
        val items = build(Pace.Energetic).items
        for (day in 1..3) assertEquals(items.filter { dayOf(it) == day }.indices.map { it + 1.0 }, items.filter { dayOf(it) == day }.map { it.position })
        val train = items.single { it.title == "Train to Nara" }
        assertEquals(8 * 60 + 15, train.timeMinutes)
        assertEquals("≈ 45 min", train.note)
        assertEquals(c.first to c.second, train.lat to train.lng)
        assertNull(items.single { it.title == "Market" }.timeMinutes)
        assertEquals("Nara Park, Nara, Japan", items.single { it.title == "Park" }.placeName)
        assertNull(items.single { it.title == "Slow breakfast" }.lat)
    }

    @Test fun ideasHaveNoDay() {
        val items = build(Pace.Chill).items
        assertEquals(listOf("Kobe", "Book seats"), titles(items, null))
        assertEquals(listOf(1.0, 2.0), items.filter { it.day == null }.map { it.position })
        assertTrue(items.filter { it.day == null }.none { it.done })
    }

    @Test fun everyItemSaysWhichProgramDayAndItemItCameFrom() {
        val energetic = build(Pace.Energetic).items.associate { it.title to it.externalId }
        assertEquals("test-culture:1:0", energetic["Fly in"])
        assertEquals("test-culture:1:2", energetic["Late temple"])
        assertEquals("test-culture:2:1", energetic["Train to Nara"])
        assertEquals("test-culture:3:1", energetic["Market"])
        assertEquals("test-culture:0:0", energetic["Kobe"])
        assertEquals("test-culture:0:1", energetic["Book seats"])
        // The index is the program's own, at every pace.
        val chill = build(Pace.Chill).items.associate { it.title to it.externalId }
        assertEquals(energetic["Train to Nara"], chill["Train to Nara"])
        assertTrue(build(Pace.Chill).items.all { it.source == ItemSource.Program.id })
        assertEquals(ItemSource.Program, ItemSource.of("program"))
        assertEquals(build(Pace.Energetic).items.size, energetic.values.toSet().size)
    }

    @Test fun theSameItemTwiceInADayIsTwoItems() {
        val coffee = ProgramItem("food", "Coffee", at = a)
        val twice = program.copy(days = listOf(ProgramDay("Coffee", "Kyoto", listOf(coffee, ProgramItem("place", "Walk", at = b, minPace = Pace.Energetic), coffee))), ideas = emptyList())
        assertEquals(listOf("test-culture:1:0", "test-culture:1:2"), ProgramTrips.build(destination, twice, Pace.Chill, start, now, links = LinkPlaces::sortLinks).items.map { it.externalId })
    }

    @Test fun legsTravelAsTheProgramSays() {
        val (trip, items) = build(Pace.Energetic)
        val chosen = TravelModes.choices(trip.travelModes)
        val modes = RouteLegs.planned(trip, items).associate { it.toTitle to chosen[it.key] }
        assertEquals(Vehicle.Car, modes["Inn"])
        assertEquals(Vehicle.Walk, modes["Late temple"])
        assertEquals(Vehicle.Train, modes["Train to Nara"])
        assertEquals(Vehicle.Walk, modes["Park"])
        assertEquals(Vehicle.Train, modes["Castle"])
        assertEquals(Vehicle.Walk, modes["Market"])
        // The first place and the ideas have no "via": nothing is chosen for them.
        assertNull(modes["Kobe"])
        // A slower pace leaves "Late temple" out: the train is still the way to Nara.
        val chill = build(Pace.Chill)
        val chillChosen = TravelModes.choices(chill.trip.travelModes)
        assertEquals(Vehicle.Train, RouteLegs.planned(chill.trip, chill.items).single { it.toTitle == "Train to Nara" }.let { chillChosen[it.key] })
    }

    @Test fun daysAlreadyTravelledAreTickedExceptNotes() {
        val items = build(Pace.Balanced, doneBefore = 3).items
        assertTrue(items.filter { dayOf(it) in 1..2 && it.kind != "note" }.all { it.done })
        assertFalse(items.single { it.title == "Slow breakfast" }.done)
        assertTrue(items.filter { dayOf(it) == 3 || it.day == null }.none { it.done })
        // A trip of the traveller's own starts with nothing ticked.
        assertTrue(build(Pace.Energetic).items.none { it.done })
    }

    @Test fun theRouteListsTheBasesInOrder() {
        assertEquals("Kyoto → Osaka", ProgramTrips.route(program))
        val back = program.copy(days = program.days + ProgramDay("Back", "kyoto", emptyList()) + ProgramDay("Home", "  ", emptyList()))
        assertEquals("Kyoto → Osaka → kyoto", ProgramTrips.route(back))
        assertEquals("", ProgramTrips.route(program.copy(days = emptyList())))
    }

    @Test fun timesAreReadOnlyWhenTheyAreTimes() {
        assertEquals(0, ProgramTrips.minutes("00:00"))
        assertEquals(7 * 60 + 5, ProgramTrips.minutes("7:05"))
        assertEquals(23 * 60 + 59, ProgramTrips.minutes(" 23:59 "))
        for (bad in listOf(null, "", "late", "24:00", "12:60", "12.30", "1230", "12:3")) assertNull(bad, ProgramTrips.minutes(bad))
    }

    @Test fun anItemsWebsiteBecomesItsLink() {
        val linked = program.copy(
            days = listOf(ProgramDay("Temples", "Kyoto", listOf(
                ProgramItem("place", "Fushimi Inari", at = a, url = "https://inari.jp/en/"),
                ProgramItem("food", "Lunch nearby"),
                ProgramItem("place", "Blank link", at = b, url = "  "),
            ))),
            ideas = listOf(ProgramItem("place", "Koyasan", at = c, url = " https://www.koyasan.or.jp/en/ ")),
        )
        val items = ProgramTrips.build(destination, linked, Pace.Chill, start, now, links = LinkPlaces::sortLinks).items.associate { it.title to it.url }
        assertEquals("https://inari.jp/en/", items["Fushimi Inari"])
        assertEquals("https://www.koyasan.or.jp/en/", items["Koyasan"])
        assertNull(items["Lunch nearby"])
        assertNull(items["Blank link"])
        assertTrue(build(Pace.Energetic).items.all { it.url == null })
    }

    @Test fun anExternalIdLeadsBackToItsProgramItem() {
        val programs = listOf(program)
        for (pace in Pace.entries) {
            for (item in build(pace).items) {
                assertEquals(item.title, ProgramTrips.itemAt(programs, item.externalId)?.title)
            }
        }
        assertEquals("Kobe", ProgramTrips.itemAt(programs, "test-culture:0:0")?.title)
        for (bad in listOf(null, "", "test-culture", "test-culture:9:0", "test-culture:1:9", "other:1:0", "test-culture:x:0", "a:b:c:d")) {
            assertNull(bad, ProgramTrips.itemAt(programs, bad))
        }
    }

    @Test fun unknownKindsBecomePlaces() {
        val odd = program.copy(days = listOf(ProgramDay("Odd", "Kyoto", listOf(ProgramItem("museum", "Something", at = a)))), ideas = emptyList())
        assertEquals("place", ProgramTrips.build(destination, odd, Pace.Chill, start, now, links = LinkPlaces::sortLinks).items.single().kind)
    }
}
