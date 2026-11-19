package com.justdataplease.boringtravel.places

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.ui.trip.ItemNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TripPlacesTest {
    private val d1 = LocalDate.of(2027, 3, 1)
    private val d2 = LocalDate.of(2027, 3, 2)
    private val d3 = LocalDate.of(2027, 3, 3)
    private val d9 = LocalDate.of(2027, 3, 9)

    private fun item(
        id: String, title: String, day: LocalDate?, place: String = "", kind: String = "place",
        lat: Double? = null, lng: Double? = null, pos: Double = 0.0, deleted: Boolean = false,
    ) = PlanItem(
        id = id, tripId = "t", day = day, position = pos, kind = kind, title = title, placeName = place, lat = lat, lng = lng,
        createdAt = 0, updatedAt = 0, deletedAt = if (deleted) 1L else null,
    )

    private fun stop(id: String, name: String, at: Long) = Stop(id = id, tripId = "t", arrivedAt = at, lat = -41.1, lng = -71.3, name = name, createdAt = 0, updatedAt = 0)

    private fun entry(id: String, place: String, at: Long = 0) =
        Entry(id = id, tripId = "t", occurredAt = at, zoneId = "UTC", placeName = place, createdAt = 0, updatedAt = 0)

    @Test fun theDaysItemsByNameFirstThenTownsEntriesStopsAndTheNearestDaysOfThePlan() {
        val items = listOf(
            // Day 1 in Buenos Aires, day 2 in El Chaltén (its arrival names the town), day 9 far away.
            item("ba", "Recoleta Cemetery", d1, place = "Buenos Aires", lat = -34.58, lng = -58.39),
            item("tr", "Transfer to El Chaltén", d2, kind = "transport", lat = -49.33, lng = -72.89, pos = 1.0),
            item("lc", "Laguna Capri", d2, lat = -49.30, lng = -72.93, pos = 2.0),
            item("pn", "Grey glacier", d9, place = "Torres del Paine", lat = -51.0, lng = -73.2),
        )
        val places = TripPlaces.of(
            items,
            listOf(stop("s1", "Bariloche", 10), stop("s2", "Mendoza", 20)),
            listOf(entry("e1", "Mirador de los Cóndores", at = 5), entry("e2", "Palermo Soho", at = 1)),
            day = d2,
        )
        assertEquals(
            listOf(
                "El Chaltén", "Laguna Capri (El Chaltén)", // the day's own items: the ride by where it goes, the spot by name
                "Mirador de los Cóndores", "Palermo Soho", // entries, newest first
                "Mendoza", "Bariloche", // stops, newest first
                "Recoleta Cemetery (Buenos Aires)", "Buenos Aires", // day 1 is one day away: the item, then its town
                "Grey glacier (Torres del Paine)", "Torres del Paine", // day 9 is seven away
            ),
            places.map { it.name },
        )
        assertEquals(listOf("El Chaltén", "Laguna Capri (El Chaltén)"), places.filter { it.onDay }.map { it.name })
        // The spot keeps its pin; the ride's destination has none (the station isn't the town).
        assertEquals(-49.30, places[1].lat!!, 1e-9)
        assertNull(places[0].lat)
    }

    @Test fun planItemsAreNamedAsANoteAboutThemIsPlacedSoThePickedNoteShowsUnderThem() {
        val items = listOf(
            item("ar", "Train to Granada", d2, kind = "transport", lat = 37.18, lng = -3.60, pos = 0.0),
            item("al", "Alhambra", d2, lat = 37.176, lng = -3.588, pos = 1.0),
            item("st", "Hostal Lima", d2, kind = "stay", place = "Calle Lima 3, Granada, Spain", pos = 2.0),
            item("fo", "Bar Los Diamantes", d2, kind = "food", lat = 37.175, lng = -3.597, pos = 3.0),
            item("fl", "Flamenco show", d2, kind = "activity", pos = 4.0),
            item("gr", "Granada", d2, place = "Granada", pos = 5.0),
            item("nt", "Book the Alhambra tickets", d2, kind = "note", pos = 6.0),
        )
        val places = TripPlaces.of(items, emptyList(), emptyList(), day = d2)
        val onDay = places.filter { it.onDay }
        assertEquals(
            listOf(
                "Granada", // the ride, by where it goes; also the item named Granada (the name alone: the place is the name)
                "Alhambra (Granada)", "Hostal Lima (Granada)", "Bar Los Diamantes (Granada)", "Flamenco show",
            ),
            onDay.map { it.name },
        )
        // Each one is what a note written from the item says, so picking it puts the note under the item.
        for (id in listOf("al", "st", "fo", "fl", "gr")) {
            val planItem = items.first { it.id == id }
            val name = ItemNotes.location(planItem, items)
            assertTrue(name, places.any { it.name == name })
            assertTrue(name, ItemNotes.named(planItem, name))
        }
        // A pin when the item has one; a name only without.
        assertEquals(37.176, places.first { it.name == "Alhambra (Granada)" }.lat!!, 1e-9)
        assertNull(places.first { it.name == "Flamenco show" }.lat)
        assertNull(places.first { it.name == "Hostal Lima (Granada)" }.lat)
        // A plain note of the plan is no place.
        assertTrue(places.none { it.name.startsWith("Book the") })
    }

    @Test fun aNameShowsOnceAndTakesAPointWhenALaterSourceHasOne() {
        val items = listOf(item("a", "Museum", d1, place = "Bariloche"))
        val places = TripPlaces.of(items, listOf(stop("s", "bariloche", 0)), emptyList(), day = d1)
        assertEquals(listOf("Museum (Bariloche)", "Bariloche"), places.map { it.name })
        assertNull(places[0].lat)
        assertEquals(-41.1, places[1].lat!!, 1e-9)
        // A note written from the item earlier names it the same way: still once, still the day's.
        val again = TripPlaces.of(items, emptyList(), listOf(entry("e", "museum (bariloche)")), day = d1)
        assertEquals(listOf("Museum (Bariloche)", "Bariloche"), again.map { it.name })
        assertTrue(again[0].onDay)
    }

    @Test fun notesDeletedRowsBlanksAndTheEntryItselfAreLeftOutAndItemsWithoutAPinGiveTheirName() {
        val items = listOf(
            item("t", "Tango show", d1, kind = "activity"), // no point, no place name: its name alone
            item("n", "Buy a SIM card", d1, kind = "note", lat = 1.0, lng = 1.0),
            item("r", "Fly to Mendoza", d1, kind = "transport"),
            item("x", "Gone", d1, place = "Nowhere", deleted = true),
            item("b", "  ", d1),
        )
        val places = TripPlaces.of(items, emptyList(), listOf(entry("me", "Here"), entry("other", "There")), day = d1, exceptEntry = "me")
        assertEquals(listOf("Tango show", "Mendoza", "There"), places.map { it.name })
        assertEquals(listOf(true, true, false), places.map { it.onDay })
        assertNull(places[0].lat)
        assertNull(places[1].lat)
    }

    @Test fun withoutADayThePlanFollowsItsOwnOrderAndIdeasComeLast() {
        val items = listOf(
            item("i", "Teatro Colón", null, place = "Buenos Aires"),
            item("b", "Bodega visit", d3, place = "Mendoza"),
            item("a", "Obelisco", d1, place = "Buenos Aires"),
        )
        val places = TripPlaces.of(items, emptyList(), emptyList(), day = null)
        assertEquals(
            listOf("Obelisco (Buenos Aires)", "Buenos Aires", "Bodega visit (Mendoza)", "Mendoza", "Teatro Colón (Buenos Aires)"),
            places.map { it.name },
        )
        assertTrue(places.none { it.onDay })
    }

    @Test fun whatIsTypedBringsItsMatchesFirstAndHidesNothing() {
        val places = listOf(KnownPlace("Buenos Aires"), KnownPlace("El Chaltén"), KnownPlace("Chalten viewpoint"))
        assertEquals(places, TripPlaces.suggest(places, "  "))
        assertEquals(listOf("Chalten viewpoint", "El Chaltén", "Buenos Aires"), TripPlaces.suggest(places, "chal").map { it.name })
        assertEquals(places, TripPlaces.suggest(places, "Palermo"))
    }

    @Test fun typingLooksThroughEveryPlaceNotOnlyTheOnesShown() {
        // Forty items on forty days: the last is far down the list, past any cut.
        val items = (1..40).map { n -> item("i$n", "Spot $n", d1.plusDays(n.toLong()), place = "Town $n") }
        val places = TripPlaces.of(items, emptyList(), emptyList(), day = d1)
        assertEquals(80, places.size)
        assertEquals("Spot 40 (Town 40)", TripPlaces.shown(places, "spot 40", 12).first().name)
        assertEquals(12, TripPlaces.shown(places, "", 12).size)
    }

    @Test fun theCutNeverHidesTheDaysItems() {
        val places = listOf(
            KnownPlace("Alhambra (Granada)", onDay = true),
            KnownPlace("Albaicín (Granada)", onDay = true),
            KnownPlace("Sacromonte (Granada)", onDay = true),
            KnownPlace("Granada"),
            KnownPlace("Seville"),
        )
        assertEquals(places.take(3), TripPlaces.shown(places, "", 2))
        // Typing brings a match to the front; the day's items still show after it.
        assertEquals(
            listOf("Seville", "Alhambra (Granada)", "Albaicín (Granada)", "Sacromonte (Granada)"),
            TripPlaces.shown(places, "sev", 2).map { it.name },
        )
        assertEquals(places, TripPlaces.shown(places, "", 12))
    }

    @Test fun aPickTakesThePlacesPointAndNeverKeepsAnotherPlacesPoint() {
        val alhambra = KnownPlace("Alhambra (Granada)", 37.176, -3.588, onDay = true)
        val flamenco = KnownPlace("Flamenco show", onDay = true)
        val granada = KnownPlace("Granada")
        val places = listOf(alhambra, flamenco, granada)
        // The picked place's own point.
        assertEquals(LatLng(37.176, -3.588), TripPlaces.pointAfterPick(places, alhambra, null, null))
        assertEquals(LatLng(37.176, -3.588), TripPlaces.pointAfterPick(places, alhambra, 40.0, -3.0))
        // Without one: the Alhambra's point (picked before, or the item the note was written from) goes ...
        assertNull(TripPlaces.pointAfterPick(places, flamenco, 37.176, -3.588))
        assertNull(TripPlaces.pointAfterPick(places, granada, 37.176, -3.588))
        // ... the phone's or the route's point stays, and no point stays none.
        assertEquals(LatLng(37.17, -3.6), TripPlaces.pointAfterPick(places, flamenco, 37.17, -3.6))
        assertNull(TripPlaces.pointAfterPick(places, granada, null, null))
    }
}
