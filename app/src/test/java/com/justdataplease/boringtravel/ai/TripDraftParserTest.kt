package com.justdataplease.boringtravel.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDraftParserTest {
    private val sample = """
        {
          "title": "Naxos, slowly",
          "startDate": "2027-06-17",
          "endDate": "2027-06-20",
          "summary": "Four days on Naxos.",
          "days": [
            {"date": "2027-06-17", "items": [
              {"time": "07:30", "kind": "transport", "title": "Ferry Paros → Naxos", "note": "About 1 h", "placeName": "Naxos port", "lat": 37.1056, "lng": 25.3747}
            ]},
            {"dayIndex": 2, "items": [{"kind": "food", "title": "Lunch in Halki"}]}
          ],
          "ideas": [{"kind": "place", "title": "Portara at sunset"}]
        }
    """.trimIndent()

    @Test fun readsAFullDraft() {
        val d = TripDraftParser.parse(sample)
        assertEquals("Naxos, slowly", d.title)
        assertEquals("2027-06-17", d.startDate)
        assertEquals("2027-06-20", d.endDate)
        assertEquals("Four days on Naxos.", d.summary)
        assertEquals(2, d.days.size)
        val ferry = d.days[0].items[0]
        assertEquals(DraftItem("07:30", "transport", "Ferry Paros → Naxos", "About 1 h", "Naxos port", 37.1056, 25.3747), ferry)
        assertEquals(2, d.days[1].dayIndex)
        assertNull(d.days[1].date)
        assertEquals(listOf("Portara at sunset"), d.ideas.map { it.title })
        assertEquals(3, d.itemCount)
    }

    @Test fun emptyObjectIsAnEmptyDraft() = assertEquals(TripDraft(), TripDraftParser.parse("{}"))

    @Test fun stripsCodeFencesAndWords() {
        val d = TripDraftParser.parse("Here you go:\n```json\n{\"summary\": \"Ok.\", \"ideas\": [{\"title\": \"Beach\"}]}\n```")
        assertEquals("Ok.", d.summary)
        assertEquals("Beach", d.ideas.single().title)
    }

    @Test(expected = DraftParseException::class)
    fun noJsonThrows() { TripDraftParser.parse("Sorry, I can't help with that.") }

    @Test fun parseOrNullSwallowsBadAnswers() = assertNull(TripDraftParser.parseOrNull("[1, 2, 3]"))

    @Test fun toleratesWrongTypes() {
        val d = TripDraftParser.parse(
            """
            {"title": 12, "summary": null, "days": {"dayIndex": "3", "items": [
                {"title": "Kitron tasting", "lat": "37.06", "lng": "25.48", "time": 930},
                {"placeName": "Halki"},
                {"note": "no title, no place"},
                "not an item"
            ]}, "ideas": "nope"}
            """.trimIndent(),
        )
        assertEquals("12", d.title)
        assertEquals("", d.summary)
        val day = d.days.single()
        assertEquals(3, day.dayIndex)
        assertEquals(listOf("Kitron tasting", "Halki"), day.items.map { it.title })
        assertEquals(37.06, day.items[0].lat!!, 1e-9)
        assertEquals(25.48, day.items[0].lng!!, 1e-9)
        assertNull("930 is not a time", day.items[0].time)
        assertEquals("note", day.items[0].kind)
        assertTrue(d.ideas.isEmpty())
    }

    @Test fun dropsEmptyDaysAndBadIndexes() {
        val d = TripDraftParser.parse("""{"days": [{"date": "2027-06-17", "items": []}, {"dayIndex": -2, "items": [{"title": "Swim"}]}]}""")
        assertEquals(1, d.days.size)
        assertNull(d.days[0].dayIndex)
    }

    @Test fun mapsKinds() {
        assertEquals("food", TripDraftParser.kindOf("Restaurant"))
        assertEquals("stay", TripDraftParser.kindOf(" HOTEL "))
        assertEquals("transport", TripDraftParser.kindOf("ferry"))
        assertEquals("activity", TripDraftParser.kindOf("activity"))
        assertEquals("place", TripDraftParser.kindOf("museum"))
        assertEquals("note", TripDraftParser.kindOf("spaceship"))
        assertEquals("note", TripDraftParser.kindOf(null))
    }

    @Test fun normalisesTimes() {
        assertEquals("07:30", TripDraftParser.normaliseTime("7:30"))
        assertEquals("07:30", TripDraftParser.normaliseTime("07.30"))
        assertEquals("19:40", TripDraftParser.normaliseTime("19h40"))
        assertEquals("19:30", TripDraftParser.normaliseTime("7.30 pm"))
        assertEquals("19:00", TripDraftParser.normaliseTime("7pm"))
        assertEquals("00:05", TripDraftParser.normaliseTime("12:05am"))
        assertEquals("12:00", TripDraftParser.normaliseTime("12 p.m."))
        assertEquals("08:15", TripDraftParser.normaliseTime("08:15:00"))
        assertEquals("23:59", TripDraftParser.normaliseTime("24:00"))
        assertEquals("10:59", TripDraftParser.normaliseTime("10:75"))
        assertNull(TripDraftParser.normaliseTime("25:10"))
        assertNull(TripDraftParser.normaliseTime("13 pm"))
        assertNull(TripDraftParser.normaliseTime("morning"))
        assertNull(TripDraftParser.normaliseTime(""))
    }

    @Test fun normalisesDates() {
        assertEquals("2027-06-17", TripDraftParser.isoDate("2027-06-17T10:00:00Z"))
        assertEquals("2027-06-17", TripDraftParser.isoDate(" 2027-06-17 "))
        assertNull(TripDraftParser.isoDate("June 17"))
        assertNull(TripDraftParser.isoDate("2027-02-30"))
    }

    @Test fun dropsImpossibleCoordinates() {
        val d = TripDraftParser.parse(
            """{"ideas": [
                {"title": "A", "lat": 200, "lng": 10},
                {"title": "B", "lat": 37.1},
                {"title": "C", "lat": 0, "lng": 0},
                {"title": "D", "lat": -33.86, "lng": 151.21}
            ]}""",
        )
        assertEquals(listOf(null, null, null, -33.86), d.ideas.map { it.lat })
        assertEquals(listOf(null, null, null, 151.21), d.ideas.map { it.lng })
    }

    @Test fun clipsLongDisplayText() {
        val longTitle = "x".repeat(500)
        val d = TripDraftParser.parse("""{"summary": "  lots   of   space  ", "ideas": [{"title": "$longTitle"}]}""")
        assertEquals("lots of space", d.summary)
        assertEquals(1, d.ideas.size)
        assertTrue(d.ideas[0].title.length <= 120)
        assertTrue(d.ideas[0].title.endsWith("…"))
    }
}
