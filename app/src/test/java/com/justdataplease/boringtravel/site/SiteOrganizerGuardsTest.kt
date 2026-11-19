package com.justdataplease.boringtravel.site

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** Undated posts joining their journey, stray posts cut off, one rule for "close in time", titles made last. */
class SiteOrganizerGuardsTest {
    private val zone = ZoneOffset.UTC
    private val locale = Locale.ENGLISH

    private fun post(id: String, at: String?, title: String = id, text: String = "Words of $id.") =
        SitePost(id, "https://blog.example.com/$id", title, at?.let { Instant.parse(if ('T' in it) it else "${it}T10:00:00Z") }, listOf(SiteBlock.Text(text)))

    private suspend fun organise(posts: List<SitePost>, ai: SiteAi?, existing: List<ExistingTrip> = emptyList()) =
        SiteOrganizer.organise(posts, "W", zone, ai, locale, today = java.time.LocalDate.of(2026, 11, 12), existing = existing)

    private fun ids(o: Organised) = o.trips.map { t -> t.title to t.posts.map { it.id } }

    @Test fun undatedPostsAreSentWithTheDatedOnesAndJoinTheirJourney() = runTest {
        val posts = listOf(post("j1", "2016-04-01"), post("j2", "2016-04-03"), post("c1", "2021-08-02"), post("c2", "2021-08-04"), post("nara", null, "Deer of Nara"))
        val asked = ArrayList<String>()
        val ai = SiteAi { _, prompt ->
            asked += prompt
            """{"trips":[{"title":"Japan, spring 2016","posts":[1,2,5]},{"title":"Crete, 2021","posts":[3,4]}],"details":[{"post":5,"place":"Nara","date":"2016-04-02"}]}"""
        }
        val o = organise(posts, ai)
        assertEquals(1, asked.size)
        assertTrue(asked[0].contains("5 | no date | Deer of Nara |"))
        assertEquals(listOf("Japan, spring 2016" to listOf("j1", "nara", "j2"), "Crete, 2021" to listOf("c1", "c2")), ids(o))
    }

    @Test fun aPostTheModelDatedGoesToTheTripItsDateFallsIn() = runTest {
        // The model made "Nara" a trip of its own, but dated it 7 April: inside Japan's days.
        val posts = listOf(post("j1", "2016-04-01"), post("j2", "2016-04-10"), post("nara", null, "Deer of Nara"), post("old", null, "No date said"))
        val ai = SiteAi { _, _ ->
            """{"trips":[{"title":"Japan","posts":[1,2]},{"title":"Nara, April 2016","posts":[3]},{"title":"Loose","posts":[4]}],
               "details":[{"post":3,"place":"Nara","date":"2016-04-07"}]}"""
        }
        val o = organise(posts, ai)
        assertEquals(listOf("Japan" to listOf("j1", "nara", "j2"), "Loose" to listOf("old")), ids(o))
        assertEquals("Nara", o.trips[0].places[SiteNotes.entryId(posts[2])])
    }

    @Test fun aLaterRequestListsTheTripsMadeAndTakesTheirLetters() = runTest {
        // Enough long posts that the undated ones need a request of their own.
        val long = "w".repeat(400)
        val dated = (0 until 120).map { i -> post("d$i", java.time.LocalDate.of(2010, 1, 1).plusMonths(i / 10L).plusDays(i % 10L).toString(), "Title ${"t".repeat(90)}", long) }
        val undated = listOf(post("u1", null, "Back in January"), post("u2", null, "Somewhere"))
        val prompts = ArrayList<String>()
        val ai = SiteAi { _, prompt ->
            prompts += prompt
            if ("Trips already made" in prompt) {
                """{"trips":[{"trip":"a","posts":[1]},{"trip":"ZZ","title":"Fake","posts":[2]}]}"""
            } else {
                val n = prompt.lines().count { it.matches(Regex("^\\d+ \\|.*")) }
                """{"trips":[{"title":"Batch","posts":[${(1..n).joinToString(",")}]}]}"""
            }
        }
        val o = organise(dated + undated, ai)
        val late = prompts.last()
        assertTrue(prompts.size > 2)
        assertTrue(late.contains("A | Batch | 2010-01-01 – "))
        // u1 joined trip A; u2 named a trip that wasn't listed: its own trip by dates (the site's name).
        assertTrue(o.trips.first { t -> t.posts.any { it.id == "d0" } }.posts.any { it.id == "u1" })
        assertEquals(listOf("u2"), o.trips.single { it.title == "W" }.posts.map { it.id })
        assertTrue(o.trips.none { it.title == "Fake" })
    }

    @Test fun aStrayPostNeverStretchesATrip() = runTest {
        val posts = listOf(post("camera", "2018-01-10", "New camera"), post("p1", "2019-05-03"), post("p2", "2019-05-10"), post("p3", "2019-05-14"))
        val ai = SiteAi { _, _ -> """{"trips":[{"title":"Portugal, spring 2019","posts":[1,2,3,4]}],"details":[{"post":1,"place":"Athens"}]}""" }
        val o = organise(posts, ai)
        assertEquals(listOf("W · Jan 2018" to listOf("camera"), "Portugal, spring 2019" to listOf("p1", "p2", "p3")), ids(o))
        assertEquals(mapOf(SiteNotes.entryId(posts[0]) to "Athens"), o.trips[0].places)
        assertTrue(o.trips[1].places.isEmpty())
        // Posts up to 30 days apart stay together; the largest part keeps the title.
        val kept = SiteOrganizer.splitLong(listOf(TripGroup("Long walk", listOf(post("a", "2020-01-01"), post("b", "2020-01-31"), post("c", "2020-03-15"), post("d", "2020-03-20"), post("e", "2020-03-25")))))
        assertEquals(listOf("" to listOf("a", "b"), "Long walk" to listOf("c", "d", "e")), kept.map { it.title to it.posts.map(SitePost::id) })
    }

    @Test fun oneRuleForCloseInTime() = runTest {
        // 9 Aug 10:00 to 24 Aug 09:00 is 14 days and 23 hours: apart, for the date groups and for joining alike.
        val a = Instant.parse("2021-08-09T10:00:00Z")
        val b = Instant.parse("2021-08-24T09:00:00Z")
        assertFalse(SiteOrganizer.within(a, b))
        assertTrue(SiteOrganizer.within(a, Instant.parse("2021-08-23T10:00:00Z")))
        val posts = listOf(post("x", "2021-08-09T10:00:00Z"), post("y", "2021-08-24T09:00:00Z"))
        assertEquals(2, SiteOrganizer.dateRuns(posts.sortedBy { it.at }).size)
        val existing = ExistingTrip("old", "WordPress News · Jan 2021 – Apr 2022", null, null, listOf(Instant.parse("2021-07-30T10:00:00Z").toEpochMilli()))
        val o = organise(posts, ai = null, existing = existing.let(::listOf))
        assertEquals(listOf("WordPress News · Jan 2021 – Apr 2022" to listOf("x"), "W · Aug 2021" to listOf("y")), ids(o))
    }

    @Test fun titlesAreMadeAfterTheJoining() = runTest {
        // The model's trip runs into an earlier copy's trip; what stays behind is titled from its own dates.
        val posts = listOf(post("a", "2021-05-30"), post("b", "2021-06-10"), post("c", "2021-06-28"))
        val ai = SiteAi { _, _ -> """{"trips":[{"title":"","posts":[1,2,3]}]}""" }
        val existing = listOf(ExistingTrip("old", "Spring", null, null, listOf(Instant.parse("2021-05-25T10:00:00Z").toEpochMilli())))
        val o = organise(posts, ai, existing)
        // a is close to the earlier trip, b to a; c is 18 days after b: a trip of its own, titled from its own date.
        assertEquals(listOf("Spring" to listOf("a", "b"), "W · Jun 2021" to listOf("c")), ids(o))
        val apart = organise(listOf(post("a", "2021-05-30"), post("z", "2021-06-28")), SiteAi { _, _ -> """{"trips":[{"title":"","posts":[1,2]}]}""" }, existing)
        assertEquals(listOf("Spring" to listOf("a"), "W · Jun 2021" to listOf("z")), ids(apart))
    }

    @Test fun aLongSiteNameIsCutAtAWord() {
        assertEquals("Detailed Business Travel Itinerary Template for Long", SiteOrganizer.siteTitle("Detailed Business Travel Itinerary Template for Long Journeys Abroad"))
        assertEquals("Short name", SiteOrganizer.siteTitle("  Short   name "))
        assertEquals("A".repeat(60), SiteOrganizer.siteTitle("A".repeat(70)))
    }
}
