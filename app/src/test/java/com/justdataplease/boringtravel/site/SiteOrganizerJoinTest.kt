package com.justdataplease.boringtravel.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** The organiser's details (places, dates) and new posts joining trips an earlier copy made. */
class SiteOrganizerJoinTest {
    private val today = LocalDate.of(2026, 11, 12)

    private fun post(n: Int, day: String?) =
        SitePost("$n", "https://blog.example.com/$n", "Post $n", day?.let { Instant.parse("${it}T10:00:00Z") }, listOf(SiteBlock.Text("x")))

    private fun at(day: String) = Instant.parse("${day}T10:00:00Z").toEpochMilli()

    @Test fun detailsAreCheckedStrictly() {
        val answer = SiteOrganizer.parse(
            """{"trips":[{"title":"Crete","posts":[1,2,3]}],"details":[
                {"post":1,"place":" \"Chania\" ","date":"2016-04-03"},
                {"post":1,"place":"Again"},
                {"post":2,"place":"https://evil.example.com","date":"2016-13-40"},
                {"post":3,"place":"null","date":"3 April"},
                {"post":4,"place":"Out of range"},
                {"post":"2","date":"2999-01-01"},
                {"post":3,"place":"${"Heraklion and the palace of Knossos and the whole north coast ".repeat(2)}"}
            ]}""",
            count = 3, today = today,
        )!!
        assertEquals(listOf(SiteOrganizer.AnswerTrip("Crete", listOf(1, 2, 3))), answer.trips)
        // Post 1 once (the first), post 2's address and impossible day dropped, post 3's "null" and wordy date dropped.
        assertEquals(listOf(SiteOrganizer.AnswerDetail(1, listOf(SiteOrganizer.AnswerPlace("Chania")), LocalDate.of(2016, 4, 3))), answer.details)
        assertEquals("Heraklion", SiteOrganizer.placeName("Heraklion"))
        org.junit.Assert.assertTrue(SiteOrganizer.placeName("Heraklion ".repeat(20))!!.length <= SiteOrganizer.PLACE_CHARS)
        assertNull(SiteOrganizer.placeName("www.example.com"))
        assertNull(SiteOrganizer.placeName("<b>Kyoto</b>"))
        assertNull(SiteOrganizer.placeName("123"))
        assertNull(SiteOrganizer.isoDay("1890-01-01", today))
        assertEquals(LocalDate.of(2031, 1, 1), SiteOrganizer.isoDay("2031-01-01", today))
    }

    private fun existing(id: String, title: String, vararg days: String) = ExistingTrip(id, title, null, null, days.map(::at))

    @Test fun aCopyStoppedHalfWayContinuesInTheSameTrip() {
        // The first copy stopped after Apr 1–5; Apr 8 and Apr 20 continue that journey, a 2019 post doesn't.
        val groups = SiteOrganizer.byDates(listOf(post(4, "2016-04-08"), post(5, "2016-04-20"), post(6, "2019-05-01")), "W", java.time.ZoneOffset.UTC)
        val joined = SiteOrganizer.joinExisting(groups, listOf(existing("japan", "Japan", "2016-04-01", "2016-04-05")))
        assertEquals(listOf("japan" to listOf("4", "5"), null to listOf("6")), joined.map { it.joins?.id to it.posts.map(SitePost::id) })
        assertEquals("Japan", joined[0].title)
    }

    @Test fun newPostsWrittenLaterOnTheSameJourney() {
        val groups = listOf(TripGroup("W · Apr 2016", listOf(post(9, "2016-04-12"))))
        val joined = SiteOrganizer.joinExisting(groups, listOf(existing("japan", "Japan", "2016-04-01", "2016-04-05")))
        assertEquals(listOf("japan"), joined.map { it.joins?.id })
    }

    @Test fun postsOnBothSidesOfTwoTripsGoToTheNearest() {
        val a = existing("a", "Lisbon", "2019-05-01", "2019-05-05")
        val b = existing("b", "Porto", "2019-05-25", "2019-05-30")
        val groups = listOf(TripGroup("new", listOf(post(1, "2019-05-08"), post(2, "2019-05-22"), post(3, "2019-04-20"), post(4, "2019-07-30"))))
        val joined = SiteOrganizer.joinExisting(groups, listOf(a, b))
        assertEquals(
            listOf("a" to listOf("3", "1"), "b" to listOf("2"), null to listOf("4")),
            joined.map { it.joins?.id to it.posts.map(SitePost::id) },
        )
    }

    @Test fun withoutTripsOrDatesNothingJoins() {
        val groups = listOf(TripGroup("x", listOf(post(1, null))))
        assertEquals(groups, SiteOrganizer.joinExisting(groups, listOf(existing("a", "A", "2019-05-01"))))
        assertEquals(groups, SiteOrganizer.joinExisting(groups, emptyList()))
    }
}
