package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.compat.Instant
import com.justdataplease.boringtravel.compat.Locale
import com.justdataplease.boringtravel.compat.ZoneOffset
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.site.CssFormats
import com.justdataplease.boringtravel.site.HeadingDates
import com.justdataplease.boringtravel.site.HtmlBlocks
import com.justdataplease.boringtravel.site.Organisation
import com.justdataplease.boringtravel.site.SiteAi
import com.justdataplease.boringtravel.site.SiteBlock
import com.justdataplease.boringtravel.site.SiteLinks
import com.justdataplease.boringtravel.site.SiteNotes
import com.justdataplease.boringtravel.site.SiteOrganizer
import com.justdataplease.boringtravel.site.SitePost
import com.justdataplease.boringtravel.site.SiteSections
import com.justdataplease.boringtravel.time.localDate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Copy from a link's text rules, read in from the Android app (site/): the dates in headings (English and Greek,
 * lookbehind, \p{L}, \p{M}), the organiser's cleaning and its JSON answer (\p{Cntrl}), the trips by dates, a long post
 * cut into sections, the formats written as styles, the blog addresses in a message and the notes' ids (MD5 v3 UUIDs).
 */
class SiteTextSmokeTest {
    private val today = Samples.today

    private fun post(id: String, title: String, at: String?, vararg blocks: SiteBlock): SitePost = SitePost(
        id, "https://slowroads.example.com/$id/", title, at?.let { Instant.parse(it) },
        blocks.toList().ifEmpty { listOf(SiteBlock.Text("Words about $title.")) },
    )

    // ---- HeadingDates

    @Test fun numericDatesTakeTheOrderTheDocumentShowsAndGreekDaysCountOn() {
        val dates = HeadingDates.resolve(listOf("12/05/2019 · Athens", "13/05/2019 · Hydra", "Ημέρα 3 · Poros"), today)
        assertEquals(listOf("2019-05-12", "2019-05-13", "2019-05-14"), dates.map { it?.toString() })
    }

    @Test fun ordinalsMonthNamesAndDayNumbers() {
        val dates = HeadingDates.resolve(listOf("Day 1 · 4th May", "Day 2", "2019-05-06 Paros"), today)
        assertEquals(listOf("2019-05-04", "2019-05-05", "2019-05-06"), dates.map { it?.toString() })
    }

    @Test fun greekMonthsWithTonosAndDialytika() {
        val dates = HeadingDates.resolve(listOf("Σάββατο 4 Μαΐου 2019", "Κυριακή 5 Μαΐου", "Χωρίς ημερομηνία"), today)
        assertEquals(listOf("2019-05-04", "2019-05-05", null), dates.map { it?.toString() })
    }

    @Test fun aTimeIsNotADateAndTheOrderIsReadFromTheNumbers() {
        assertNull(HeadingDates.scan("10.30 meeting at the port"))
        assertEquals(3 to 4, HeadingDates.scan("Δευτέρα 3/4")?.pair)
        assertEquals(7, HeadingDates.scan("Ημέρα 7")?.dayNumber)
        assertEquals(true, HeadingDates.numericOrder(listOf(1 to 4, 2 to 4, 3 to 4)))
        assertEquals(false, HeadingDates.numericOrder(listOf(4 to 13)))
        assertNull(HeadingDates.numericOrder(listOf(3 to 4)))
    }

    // ---- SiteOrganizer: cleaning

    @Test fun theSitesNameIsCutAtAWord() {
        assertEquals("Slow Roads", SiteOrganizer.siteTitle("  Slow   Roads  "))
        assertEquals(
            "Slow Roads: ferries, islands and very long lunches across",
            SiteOrganizer.siteTitle("Slow Roads: ferries, islands and very long lunches across the Aegean sea every summer"),
        )
    }

    @Test fun placeNamesAndTripTitlesAreMadePlain() {
        assertEquals("Portara", SiteOrganizer.placeName("  \"**Portara**\" "))
        assertEquals("Fushimi Inari", SiteOrganizer.placeName("Fushimi\tInari"))
        assertNull(SiteOrganizer.placeName("https://slowroads.example.com/naxos"))
        assertNull(SiteOrganizer.placeName("www.example.org"))
        assertNull(SiteOrganizer.placeName("12345"))
        assertNull(SiteOrganizer.placeName("null"))
        assertNull(SiteOrganizer.placeName("<b>Portara</b>"))
        assertEquals("Cyclades, summer 2019", SiteOrganizer.tripTitle("# Cyclades, summer 2019\n"))
        assertEquals("Νάξος και Πάρος", SiteOrganizer.tripTitle("“Νάξος   και Πάρος”"))
    }

    @Test fun isoDaysAreStrictAndBounded() {
        assertEquals("2019-05-04", SiteOrganizer.isoDay(" 2019-05-04 ", today)?.toString())
        assertNull(SiteOrganizer.isoDay("2019-5-4", today))
        assertNull(SiteOrganizer.isoDay("2019-02-30", today))
        assertNull(SiteOrganizer.isoDay("1949-12-31", today))
        assertNull(SiteOrganizer.isoDay("2040-01-01", today))
    }

    @Test fun aPostsLineForTheModel() {
        val p = SitePost(
            "p3", "https://slowroads.example.com/2019/05/naxos/", "Naxos | at last", Instant.parse("2019-05-04T08:30:00Z"),
            listOf(SiteBlock.Text("We took the <b>ferry</b> to Naxos."), SiteBlock.Picture("https://cdn.example.com/a.jpg", "Harbour")),
            tags = listOf("greece", "islands"),
        )
        assertEquals("3 | 2019-05-04 | Naxos at last | We took the ferry to Naxos. | #greece #islands", SiteOrganizer.line(3, p, ZoneOffset.UTC))
        assertEquals("3 | 2019-05-04 | Naxos at last | We took th… | #greece #islands", SiteOrganizer.line(3, p, ZoneOffset.UTC, textChars = 10))
        assertEquals("1 | no date | Only pictures | Harbour | ", SiteOrganizer.line(1, p.copy(title = "Only pictures", at = null, blocks = p.blocks.drop(1), tags = emptyList()), ZoneOffset.UTC))
    }

    @Test fun theModelsAnswerIsChecked() {
        val answer = """
            Here you go:
            ```json
            {"trips":[{"title":"  \"Cyclades, summer 2019\"  ","posts":[1,2,"3",9]},{"title":"Again","posts":[2]},{"title":"Joined","posts":[4],"trip":"Z"}],
             "details":[{"post":1,"places":[{"place":"**Portara**","area":"Naxos, Greece","part":2,"last":1},{"place":"portara","area":null},{"place":"https://x.example.com"}],"date":null},
                        {"post":3,"places":[],"date":"2019-06-02"},
                        {"post":2,"place":"Halki","area":"Naxos, Greece"}]}
            ```
        """.trimIndent()
        val parsed = assertNotNull(SiteOrganizer.parse(answer, 4, today))
        assertEquals(listOf(SiteOrganizer.AnswerTrip("Cyclades, summer 2019", listOf(1, 2, 3))), parsed.trips)
        val byPost = parsed.details.associateBy { it.post }
        assertEquals(listOf(SiteOrganizer.AnswerPlace("Portara", "Naxos, Greece", 2, null)), byPost.getValue(1).places)
        assertEquals("2019-06-02", byPost.getValue(3).date?.toString())
        assertEquals("Halki", byPost.getValue(2).place)
        assertNull(SiteOrganizer.parse("no JSON at all", 4, today))
        assertEquals(listOf(SiteOrganizer.AnswerTrip("", listOf(4), "A")), SiteOrganizer.parse("""{"trips":[{"posts":[4],"trip":"a"}]}""", 4, today, setOf("A"))?.trips)
    }

    // ---- SiteOrganizer: trips by dates and by a model

    @Test fun tripsByTheGapsBetweenDates() {
        val posts = listOf(
            post("a", "Athens", "2019-05-03T10:00:00Z"), post("b", "Naxos", "2019-05-05T10:00:00Z"),
            post("c", "Lisbon", "2019-06-30T10:00:00Z"), post("d", "Undated", null),
        )
        val trips = SiteOrganizer.byDates(posts, "Slow Roads", ZoneOffset.UTC, Locale.UK)
        assertEquals(listOf("Slow Roads · May 2019", "Slow Roads · Jun 2019", "Slow Roads"), trips.map { it.title })
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d")), trips.map { g -> g.posts.map { it.id } })
        val twoMonths = listOf(post("e", "One", "2019-05-30T10:00:00Z"), post("f", "Two", "2019-06-02T10:00:00Z"))
        assertEquals("Slow Roads · May – Jun 2019", SiteOrganizer.dateTitle("Slow Roads", twoMonths, ZoneOffset.UTC, Locale.UK))
        val twoYears = listOf(post("g", "One", "2019-12-30T10:00:00Z"), post("h", "Two", "2020-01-02T10:00:00Z"))
        assertEquals("Slow Roads · Dec 2019 – Jan 2020", SiteOrganizer.dateTitle("Slow Roads", twoYears, ZoneOffset.UTC, Locale.UK))
    }

    @Test fun aModelSortsThePostsAndNamesAPlace() = runTest {
        val posts = listOf(
            post("p1", "Athens at night", "2019-05-03T20:00:00Z"),
            post("p2", "Naxos at last", "2019-05-05T09:00:00Z"),
            post("p3", "Paros by bike", "2019-05-07T09:00:00Z"),
        )
        var asked = ""
        val ai = SiteAi { _, prompt ->
            asked = prompt
            """{"trips":[{"title":"Naxos and Paros, May 2019","posts":[1,2,3]}],"details":[{"post":2,"places":[{"place":"Portara","area":"Naxos, Greece"}]}]}"""
        }
        val organised = SiteOrganizer.organise(posts, "Slow Roads", ZoneOffset.UTC, ai, Locale.UK, today)
        assertEquals(Organisation.Ai, organised.by)
        assertTrue(organised.asked)
        val trip = organised.trips.single()
        assertEquals("Naxos and Paros, May 2019", trip.title)
        assertEquals(listOf("p1", "p2", "p3"), trip.posts.map { it.id })
        assertEquals("Portara", trip.places[SiteNotes.entryId(posts[1])])
        assertEquals("Naxos, Greece", organised.areas[SiteNotes.entryId(posts[1])])
        assertTrue(asked.startsWith("Blog: Slow Roads\nPosts:\n1 | 2019-05-03 | Athens at night | "), asked)
    }

    @Test fun aModelThatFailsLeavesItToTheDates() = runTest {
        val posts = listOf(post("p1", "Athens", "2019-05-03T20:00:00Z"), post("p2", "Naxos", "2019-05-05T09:00:00Z"))
        val organised = SiteOrganizer.organise(posts, "Slow Roads", ZoneOffset.UTC, SiteAi { _, _ -> throw IllegalStateException("no answer") }, Locale.UK, today)
        assertEquals(Organisation.Dates, organised.by)
        assertEquals(listOf("Slow Roads · May 2019"), organised.trips.map { it.title })
        val none = SiteOrganizer.organise(posts, "Slow Roads", ZoneOffset.UTC, null, Locale.UK, today)
        assertEquals(Organisation.Dates, none.by)
        assertFalse(none.asked)
    }

    // ---- SiteSections

    @Test fun aLongPostIsCutAtItsDatedHeadings() {
        val long = post(
            "diary", "Island diary", null,
            SiteBlock.Text("Before we left."),
            SiteBlock.Heading("3 May 2019 · Athens", 2), SiteBlock.Text("Acropolis at dawn."),
            SiteBlock.Heading("Day 2 · Hydra", 2), SiteBlock.Text("No cars."),
            SiteBlock.Heading("Day 3 · Poros", 2), SiteBlock.Text("Lemon groves."),
        )
        val parts = SiteSections.split(long, ZoneOffset.UTC, today)
        assertEquals(listOf("Island diary", "3 May 2019 · Athens", "Day 2 · Hydra", "Day 3 · Poros"), parts.map { it.title })
        assertEquals(listOf("intro", "3 may 2019 · athens", "day 2 · hydra", "day 3 · poros"), parts.map { it.part })
        assertEquals(
            listOf("2019-05-03T11:59:00Z", "2019-05-03T12:00:00Z", "2019-05-04T12:00:00Z", "2019-05-05T12:00:00Z"),
            parts.map { it.at.toString() },
        )
        assertEquals(listOf(SiteBlock.Text("No cars.")), parts[2].blocks)
        // Without repeated headings the post stays one note.
        assertEquals(1, SiteSections.split(post("one", "One", null, SiteBlock.Text("Just words.")), ZoneOffset.UTC, today).size)
    }

    // ---- Formats written as styles

    @Test fun boldItalicUnderlineAndStrikeFromStyles() {
        assertEquals(setOf("b", "i"), CssFormats.declarations("font-weight:bolder;font-style:oblique 10deg;color:red"))
        assertEquals(setOf("u", "s"), CssFormats.declarations("text-decoration: underline line-through !important"))
        assertEquals(emptySet<String>(), CssFormats.declarations("font-weight:400"))
        val html = "<style>.c1{font-weight:700}.c2,.c3{font-style:italic;text-decoration:underline line-through}p{color:red}</style>" +
            "<p><span class=\"c1\">Bold</span> and <span class=\"c3\">slanted</span> and <span style=\"font-weight:600\">heavy</span></p>"
        assertEquals(
            listOf(SiteBlock.Text("<b>Bold</b> and <i><u><s>slanted</s></u></i> and <b>heavy</b>")),
            HtmlBlocks.parse(html, Samples.BLOG),
        )
    }

    // ---- SiteLinks

    @Test fun blogAddressesInAMessage() {
        assertEquals("https://slowroads.tumblr.com/", SiteLinks.offer("Copy my blog slowroads.tumblr.com please"))
        assertEquals(
            "https://slowroads.example.com/naxos",
            SiteLinks.offer("See https://www.google.com/maps/place/Portara/@37.1106,25.3713,17z and https://slowroads.example.com/naxos."),
        )
        assertNull(SiteLinks.offer("https://drive.google.com/file/d/abcdefghijklmnopqrst/view"))
        assertNull(SiteLinks.offer("no address here, user@slowroads.tumblr.com"))
        assertTrue(SiteLinks.looksLikeSite("https://slowroads.example.com/"))
        assertTrue(SiteLinks.looksLikeSite("https://slowroads.wordpress.com/2019/05/naxos/"))
        assertTrue(SiteLinks.looksLikeSite("https://docs.google.com/document/d/${Samples.DOC_ID}/edit"))
        assertFalse(SiteLinks.looksLikeSite("https://slowroads.example.com/2019/05/naxos/"))
        assertEquals("slowroads.example.com", SiteLinks.host("https://www.slowroads.example.com/blog"))
    }

    // ---- SiteNotes

    @Test fun noteIdsAreTheSameForEverySpellingOfAnAddress() {
        assertEquals("https://slowroads.example.com/2019/05/naxos", SiteNotes.canonical("http://www.SlowRoads.example.com/2019/05/naxos/#comments"))
        assertEquals("https://slowroads.example.com/?p=77", SiteNotes.canonical("https://slowroads.example.com/?p=77"))
        val a = SitePost("a", "http://www.slowroads.example.com/2019/05/naxos/", "", null, emptyList())
        val b = a.copy(url = "https://slowroads.example.com/2019/05/naxos")
        val id = SiteNotes.entryId(a)
        assertEquals(id, SiteNotes.entryId(b))
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id), id)
        assertTrue(id != SiteNotes.entryId(b.copy(part = "day 2")))
        assertEquals(SiteNotes.placeItemId("t1", "Portara"), SiteNotes.placeItemId("t1", "  PORTARA "))
        assertEquals(Block.Paragraph("#greece #islands"), SiteNotes.tagLine(listOf("#greece", "islands", "greece")))
        assertNull(SiteNotes.tagLine(emptyList()))
    }
}
