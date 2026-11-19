package com.justdataplease.boringtravel.ui.share

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.links.LinkPreview
import com.justdataplease.boringtravel.links.SharedLink
import com.justdataplease.boringtravel.model.PlanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ShareTargetsTest {
    private val today = LocalDate.of(2027, 4, 10)

    private fun trip(id: String, start: LocalDate? = null, end: LocalDate? = null, created: Long = 1, deleted: Long? = null) =
        Trip(id, "Trip $id", startDate = start, endDate = end, createdAt = created, updatedAt = created, deletedAt = deleted)

    private val now = trip("now", today.minusDays(3), today.plusDays(3))
    private val alsoNow = trip("alsoNow", today.minusDays(1), today.plusDays(1))
    private val soon = trip("soon", today.plusDays(20), today.plusDays(30))
    private val later = trip("later", today.plusDays(90), today.plusDays(95))
    private val someday = trip("someday", created = 5)
    private val older = trip("older", created = 9).copy(startDate = null)
    private val past = trip("past", today.minusDays(60), today.minusDays(50))
    private val lastYear = trip("lastYear", today.minusDays(400), today.minusDays(390), created = 99)

    @Test fun ownTripsOnlyUnlessThereAreNone() {
        val demo = trip("demo-kyoto", today, today.plusDays(5))
        val sample = trip("sample-argentina", today.minusDays(13), today.plusDays(17))
        assertEquals(ShareTrips(listOf(past), demo = false), ShareTargets.trips(listOf(demo, past, sample)))
        // Only the examples: they are offered, and said to be the examples.
        assertEquals(ShareTrips(listOf(demo, sample), demo = true), ShareTargets.trips(listOf(demo, sample)))
        // Deleted trips are never offered.
        assertEquals(ShareTrips(listOf(demo), demo = true), ShareTargets.trips(listOf(demo, trip("gone", deleted = 3))))
        assertEquals(ShareTrips(emptyList(), demo = false), ShareTargets.trips(emptyList()))
    }

    @Test fun theTripHappeningNowElseTheNextElseOneWithoutDatesElseTheLatest() {
        assertEquals(alsoNow, ShareTargets.defaultTrip(listOf(past, soon, now, alsoNow), today))
        assertEquals(soon, ShareTargets.defaultTrip(listOf(past, later, someday, soon), today))
        assertEquals(older, ShareTargets.defaultTrip(listOf(past, someday, older, lastYear), today))
        assertEquals(past, ShareTargets.defaultTrip(listOf(lastYear, past), today))
        assertNull(ShareTargets.defaultTrip(emptyList(), today))
        // The last day of a trip is still that trip.
        assertEquals(now, ShareTargets.defaultTrip(listOf(now, soon), today.plusDays(3)))
    }

    @Test fun todayInsideTheTripElseItsFirstDayElseItsIdeas() {
        assertEquals(today, ShareTargets.defaultDay(now, today))
        assertEquals(soon.startDate, ShareTargets.defaultDay(soon, today))
        assertEquals(past.startDate, ShareTargets.defaultDay(past, today))
        assertNull(ShareTargets.defaultDay(someday, today))
    }

    @Test fun theKindOfferedFirstFollowsTheName() {
        assertEquals(PlanKind.Stay, ShareTargets.defaultKind("Hotel Granvia Kyoto"))
        assertEquals(PlanKind.Food, ShareTargets.defaultKind("Taverna Lefteris"))
        assertEquals(PlanKind.Place, ShareTargets.defaultKind("Yasaka Shrine"))
        assertEquals(PlanKind.Place, ShareTargets.defaultKind(null))
        assertEquals(listOf(PlanKind.Place, PlanKind.Food, PlanKind.Stay), ShareTargets.kinds)
    }

    private fun planned(id: String, day: LocalDate?, position: Double) =
        PlanItem(id, now.id, day, position = position, title = id, createdAt = 1, updatedAt = 1)

    @Test fun aSharedMapsPlaceGoesAtTheEndOfTheDayWithItsNameAndPin() {
        val link = SharedLink("https://www.google.com/maps/place/Yasaka+Shrine/@35.0036,135.7785,17z", "Yasaka Shrine")
        val dayItems = listOf(planned("a", today, 1.0), planned("b", today, 4.0), planned("other day", today.plusDays(1), 9.0))
        val item = ShareTargets.item("new", now, today, PlanKind.Place, link, null, dayItems, now = 42)
        assertEquals(now.id, item.tripId)
        assertEquals(today, item.day)
        assertEquals(5.0, item.position, 0.0)
        assertEquals("Yasaka Shrine", item.title)
        assertEquals("Yasaka Shrine", item.placeName)
        assertEquals(35.0036, item.lat!!, 0.0)
        // The Maps link is the place's Maps link, not its website.
        assertEquals(link.url, item.mapsUrl)
        assertNull(item.url)
        assertEquals(PlanKind.Place.id, item.kind)
        assertEquals(42L, item.createdAt)
    }

    @Test fun aShortLinkTakesThePinItsLookFoundElseIsFilledLater() {
        val link = SharedLink("https://maps.app.goo.gl/abc", "Yasaka Shrine")
        val looked = LinkPreview(link.url, title = "八坂神社", lat = 35.0036, lng = 135.7785)
        val item = ShareTargets.item("new", now, null, PlanKind.Food, link, looked, emptyList(), now = 1)
        // The name the Maps app shared it with, the pin from the look; Ideas when no day is picked.
        assertEquals("Yasaka Shrine", item.title)
        assertEquals(35.0036, item.lat!!, 0.0)
        assertNull(item.day)
        assertEquals(PlanKind.Food.id, item.kind)
        // Not looked at yet and shared without a name: a placeholder until the place answers.
        val bare = ShareTargets.item("new", now, today, PlanKind.Place, SharedLink(link.url), null, emptyList(), now = 1)
        assertEquals(LinkPlaces.PLACEHOLDER_MAPS, bare.title)
        assertNull(bare.lat)
    }

    @Test fun aSiteIsTitledByItsPageElseItsDomain() {
        val link = SharedLink("https://www.tavernalefteris.gr/", "Taverna Lefteris - Naxos")
        val page = LinkPreview(link.url, title = "Taverna Lefteris")
        assertEquals("Taverna Lefteris", ShareTargets.item("n", now, today, PlanKind.Food, link, page, emptyList(), 1).title)
        assertEquals("Taverna Lefteris - Naxos", ShareTargets.item("n", now, today, PlanKind.Food, link, null, emptyList(), 1).title)
        val plain = ShareTargets.item("n", now, today, PlanKind.Place, SharedLink(link.url), null, emptyList(), 1)
        assertEquals("tavernalefteris.gr", plain.title)
        assertFalse(plain.lat != null)
        assertTrue(plain.url == link.url)
        assertNull(plain.mapsUrl)
    }
}
