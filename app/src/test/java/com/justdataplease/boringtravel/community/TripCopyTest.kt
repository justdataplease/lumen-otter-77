package com.justdataplease.boringtravel.community

import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TripCopyTest {
    private val shared = SharedTrip(
        days = 3,
        items = listOf(
            SharedItem(day = 1, title = "Buenos Aires", kind = "stay", place = "Palermo", lat = -34.58, lng = -58.42, time = 15 * 60),
            SharedItem(day = 1, title = "Dinner", kind = "food"),
            SharedItem(day = 3, title = "El Calafate", lat = -50.34, lng = -72.26, via = "plane", link = "https://example.com"),
            SharedItem(day = 0, title = "Tango show", kind = "activity"),
        ),
        notes = listOf(SharedNote(day = 1, title = "Hello", text = "x")),
    )
    private var n = 0
    private val ids = { "id${n++}" }

    @Test fun daysLandOnTheChosenStart() {
        val start = LocalDate.of(2027, 5, 10)
        val built = TripCopy.build(shared, "  Argentina ", "post1", start, now = 42, ids = ids)
        val t = built.trip
        assertEquals("Argentina", t.title)
        assertEquals(start, t.startDate)
        assertEquals(start.plusDays(2), t.endDate)
        assertEquals(42, t.createdAt)
        assertEquals(listOf(start, start, start.plusDays(2), null), built.items.map { it.day })
        assertEquals(listOf(1.0, 2.0, 1.0, 1.0), built.items.map { it.position })
        assertEquals(listOf("stay", "food", "place", "activity"), built.items.map { it.kind })
        assertEquals(900, built.items[0].timeMinutes)
        assertEquals("Palermo", built.items[0].placeName)
        assertEquals("https://example.com", built.items[2].url)
        // A post's Google Maps link is the copy's Maps link.
        val maps = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"
        val copied = TripCopy.build(SharedTrip(days = 1, items = listOf(SharedItem(day = 1, title = "Glacier", link = maps))), "A", "p", start, now = 1, ids = ids)
        assertEquals(null to maps, copied.items.single().let { it.url to it.mapsUrl })
    }

    @Test fun everythingIsNewAndMarkedAsFromTheCommunity() {
        val built = TripCopy.build(shared, "A", "post1", LocalDate.of(2027, 1, 1), now = 1, ids = ids)
        val all = listOf(built.trip.id) + built.items.map { it.id }
        assertEquals(all.size, all.toSet().size)
        assertTrue(built.items.all { it.tripId == built.trip.id })
        assertTrue(built.items.all { it.source == ItemSource.Community.id })
        assertEquals(ItemSource.Community, ItemSource.of("community"))
        assertEquals(listOf("community:post1:0", "community:post1:1", "community:post1:2", "community:post1:3"), built.items.map { it.externalId })
        assertTrue(built.items.none { it.done })
        assertFalse(built.trip.isDemo)
    }

    @Test fun travelChoicesComeAlong() {
        val built = TripCopy.build(shared, "A", "p", LocalDate.of(2027, 1, 1), now = 1, ids = ids)
        val legs = RouteLegs.planned(built.trip, built.items)
        assertEquals(Vehicle.Plane, legs.single { it.key.toId == built.items[2].id }.vehicle)
        assertTrue(TravelModes.choices(built.trip.travelModes).isNotEmpty())
    }

    @Test fun withoutAStartEverythingIsAnIdea() {
        val built = TripCopy.build(shared, "A", "p", null, now = 1, ids = ids)
        assertNull(built.trip.startDate)
        assertNull(built.trip.endDate)
        assertTrue(built.items.all { it.day == null })
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), built.items.map { it.position })
    }

    @Test fun aPlanOfIdeasNeedsNoStart() {
        val ideas = SharedTrip(days = 0, items = listOf(SharedItem(title = "Somewhere")))
        assertFalse(TripCopy.needsStart(ideas))
        assertTrue(TripCopy.needsStart(shared))
        val built = TripCopy.build(ideas, "A", "p", LocalDate.of(2027, 1, 1), now = 1, ids = ids)
        assertNull(built.trip.startDate)
        assertNull(built.items.single().day)
    }

    @Test fun badValuesAreDropped() {
        val odd = SharedTrip(days = 1, items = listOf(
            SharedItem(day = 1, title = " ", kind = "rocket", lat = 10.0, lng = null, time = 5000, link = "javascript:alert(1)", via = "teleport"),
        ))
        val item = TripCopy.build(odd, "", "p", LocalDate.of(2027, 1, 1), now = 1, ids = ids).items.single()
        assertEquals("Place", item.title)
        assertEquals("place", item.kind)
        assertNull(item.lat)
        assertNull(item.timeMinutes)
        assertNull(item.url)
    }
}
