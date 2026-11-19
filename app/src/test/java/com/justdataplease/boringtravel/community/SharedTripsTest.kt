package com.justdataplease.boringtravel.community

import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SharedTripsTest {
    private val start = LocalDate.of(2027, 3, 1)
    private val trip = Trip("t1", "Patagonia", startDate = start, endDate = start.plusDays(4), createdAt = 1, updatedAt = 1)

    private fun item(id: String, day: LocalDate?, pos: Double, title: String = id, lat: Double? = null, lng: Double? = null, note: String = "") =
        PlanItem(
            id = id, tripId = "t1", day = day, position = pos, title = title, lat = lat, lng = lng, note = note,
            placeName = "Place $id", timeMinutes = 9 * 60, url = "https://example.com/$id", done = true,
            createdAt = 1, updatedAt = 1,
        )

    private fun entry(id: String, day: LocalDate, text: String = "We walked.") = Entry(
        id = id, tripId = "t1", occurredAt = day.atTime(18, 0).atZone(ZoneId.of("America/Argentina/Buenos_Aires")).toInstant().toEpochMilli(),
        zoneId = "America/Argentina/Buenos_Aires", title = "Day $id", plainText = text, placeName = "Our hotel, Street 5",
        lat = -50.3, lng = -72.2, createdAt = 1, updatedAt = 1,
    )

    @Test fun planDaysCountFromTheTripsFirstDay() {
        val items = listOf(
            item("b", start.plusDays(1), 2.0), item("a", start.plusDays(1), 1.0), item("c", start, 1.0), item("idea", null, 1.0),
            item("gone", start, 3.0).copy(deletedAt = 5),
        )
        val t = SharedTrips.build(trip, items)
        assertEquals(5, t.days)
        assertEquals(listOf("c", "a", "b", "idea"), t.items.map { it.title })
        assertEquals(listOf(1, 2, 2, 0), t.items.map { it.day })
        assertEquals(listOf("idea"), t.ideas.map { it.title })
        assertEquals(listOf(1, 2), t.plannedDays)
        val c = t.items.first()
        assertEquals("Place c", c.place)
        assertEquals(540, c.time)
        assertEquals("https://example.com/c", c.link)
        // One link per place: the website, else the Google Maps link.
        val maps = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"
        val links = SharedTrips.build(trip, listOf(item("w", start, 1.0).copy(mapsUrl = maps), item("m", start, 2.0).copy(url = null, mapsUrl = maps))).items.map { it.link }
        assertEquals(listOf("https://example.com/w", maps), links)
    }

    @Test fun aTripWithoutDatesCountsFromItsFirstPlannedDay() {
        val someday = trip.copy(startDate = null, endDate = null)
        val t = SharedTrips.build(someday, listOf(item("x", start.plusDays(10), 1.0), item("y", start.plusDays(12), 1.0)))
        assertEquals(3, t.days)
        assertEquals(listOf(1, 3), t.items.map { it.day })
        assertEquals(0, SharedTrips.build(someday, listOf(item("i", null, 1.0))).days)
    }

    @Test fun nothingPrivateTravels() {
        val items = listOf(item("a", start, 1.0, lat = -50.0, lng = -73.0, note = "Booking code ABC123"))
        val t = SharedTrips.build(trip, items, listOf(SharedTrips.NoteInput(entry("e1", start.plusDays(1)), listOf("photo1"))))
        val json = SharedTrips.encode(t)
        // Plan notes (booking codes), done ticks, where a note was written and recorded routes stay home.
        assertFalse(json, json.contains("ABC123"))
        assertFalse(json, json.contains("\"done\""))
        assertFalse(json, json.contains("Our hotel"))
        assertFalse(json, json.contains("-72.2"))
        assertFalse(json, json.contains("track"))
        assertFalse(json, json.contains("stop"))
        // What it does carry.
        assertTrue(json.contains("-73.0"))
        assertTrue(json.contains("We walked."))
        assertTrue(json.contains("photo1"))
    }

    @Test fun notesKeepTheirDayDateAndPhotos() {
        val notes = listOf(
            SharedTrips.NoteInput(entry("late", start.plusDays(3)), listOf("p3")),
            SharedTrips.NoteInput(entry("early", start), listOf("p1", "p2", "p1")),
            SharedTrips.NoteInput(entry("after", start.plusDays(20))),
        )
        val t = SharedTrips.build(trip, emptyList(), notes)
        assertEquals(listOf("Day early", "Day late", "Day after"), t.notes.map { it.title })
        assertEquals(listOf(1, 4, 0), t.notes.map { it.day })
        assertEquals("2027-03-01", t.notes.first().date)
        assertEquals(listOf("p1", "p2"), t.notes.first().photos)
    }

    @Test fun aCraftedTripIsMadeSafeWhenRead() {
        val crafted = """{"v":1,"days":99999,"items":[
            {"day":-3,"title":"Past the pole","lat":123.0,"lng":10.0,"time":5000,"link":"javascript:alert(1)"},
            {"day":2,"title":"Half a place","lat":10.0,"time":-1,"link":" https://ok.example/x "},
            {"day":500,"title":"Fine","lat":-50.0,"lng":-73.0,"time":600,"link":"ftp://x"}],
          "notes":[{"day":-1,"photos":["x","abcdef","abcdef","../etc/passwd","ghijk-_9"]}]}"""
        val t = SharedTrips.decode(crafted)!!
        assertEquals(400, t.days)
        assertEquals(listOf(0, 2, 400), t.items.map { it.day })
        assertEquals(listOf(null, null, -50.0), t.items.map { it.lat })
        assertEquals(listOf(null, null, -73.0), t.items.map { it.lng })
        assertEquals(listOf(null, null, 600), t.items.map { it.time })
        assertEquals(listOf(null, "https://ok.example/x", null), t.items.map { it.link })
        assertEquals(0, t.notes.single().day)
        assertEquals(listOf("abcdef", "ghijk-_9"), t.notes.single().photos)
        // Copying it makes a plan the app can draw.
        val built = TripCopy.build(t, "Crafted", "post1", start, now = 0)
        assertTrue(built.items.all { i -> (i.lat?.let { it in -90.0..90.0 } ?: true) && (i.lat == null) == (i.lng == null) })
        // What the app writes itself reads back unchanged.
        val own = SharedTrips.build(trip, listOf(item("a", start, 1.0, lat = -50.0, lng = -73.0)))
        assertEquals(own, SharedTrips.decode(SharedTrips.encode(own)))
    }

    @Test fun longNotesAreCut() {
        val t = SharedTrips.build(trip, emptyList(), listOf(SharedTrips.NoteInput(entry("e", start, "x".repeat(10_000)))))
        assertEquals(CommunityLimits.NOTE_TEXT, t.notes.single().text.length)
        assertTrue(t.notes.single().text.endsWith("…"))
    }

    @Test fun travelChoicesTravelWithThePlan() {
        val a = item("a", start, 1.0, lat = -34.6, lng = -58.4)
        val b = item("b", start.plusDays(1), 1.0, lat = -50.3, lng = -72.3)
        val leg = RouteLegs.planned(trip, listOf(a, b)).single()
        val chosen = trip.copy(travelModes = TravelModes.set(null, leg.key, Vehicle.Plane))
        val t = SharedTrips.build(chosen, listOf(a, b))
        assertNull(t.items[0].via)
        assertEquals("plane", t.items[1].via)
    }

    @Test fun roundTripAndUnknownFields() {
        val t = SharedTrips.build(trip, listOf(item("a", start, 1.0)), listOf(SharedTrips.NoteInput(entry("e", start), listOf("photo1"))))
        assertEquals(t, SharedTrips.decode(SharedTrips.encode(t)))
        val newer = SharedTrips.encode(t).replaceFirst("{", "{\"later\":{\"x\":1},")
        assertEquals(t, SharedTrips.decode(newer))
        assertNull(SharedTrips.decode("{\"v\":99,\"days\":1}"))
        assertNull(SharedTrips.decode("not json"))
        assertNull(SharedTrips.decode(null))
    }

    @Test fun fitKeepsTheTripUnderTheCap() {
        val items = (1..40).map { item("i$it", start.plusDays((it % 5).toLong()), it.toDouble()) }
        val notes = (1..30).map { SharedTrips.NoteInput(entry("e$it", start.plusDays((it % 5).toLong()), "word ".repeat(1_100))) }
        val t = SharedTrips.build(trip, items, notes)
        assertTrue(SharedTrips.bytes(t) > 100_000)
        val fitted = assertNotNull(SharedTrips.fit(t, 100_000))
        assertTrue(SharedTrips.bytes(fitted) <= 100_000)
        assertEquals(t.items, fitted.items) // the plan stays whole; notes give way
        assertTrue(fitted.notes.size in 1 until t.notes.size || fitted.notes.all { it.text.length <= 1_500 })
        // Small trips go as they are.
        val small = SharedTrips.build(trip, items.take(3))
        assertEquals(small, SharedTrips.fit(small))
        // A plan too big for the cap even without notes and ideas can't be posted.
        assertNull(SharedTrips.fit(t, 500))
    }

    private fun <T> assertNotNull(v: T?): T {
        assertTrue("expected a value", v != null)
        return v!!
    }
}
