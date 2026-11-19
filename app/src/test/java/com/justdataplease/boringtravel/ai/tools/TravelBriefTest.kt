package com.justdataplease.boringtravel.ai.tools

import com.justdataplease.boringtravel.data.db.Trip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Point 11: the travel agent's questions, filled in by the model, answered on the sheet, read by the planner. */
class TravelBriefTest {
    private fun args(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun theAnswersReadAsOneMessage() {
        val brief = TravelBrief(
            where = "Lisbon", whenText = "May", days = 5, who = TravelBrief.Who.Family, people = 4,
            pace = TravelBrief.Pace.Balanced, interests = setOf(TravelBrief.Interest.Food, TravelBrief.Interest.Sights),
            budget = TravelBrief.Budget.Middle, gettingAround = TravelBrief.GettingAround.Transit,
            startFrom = "Athens", wishes = "Belém tower\nno early starts",
        )
        assertEquals(
            """
            Trip questions, answered:
            Where: Lisbon
            When: May
            How long: 5 days
            Who: family with children, 4 people
            Pace: balanced
            Interests: sights and history, food
            Budget: middle
            Getting around: on foot and public transport
            Starting from: Athens
            Must-sees and things to avoid: Belém tower no early starts
            Plan the trip and propose it.
            """.trimIndent(),
            brief.message(),
        )
        assertTrue(TravelBrief.answered(listOf("Lisbon in May", brief.message())))
        assertFalse(TravelBrief.answered(listOf("Plan 5 days in Lisbon in May")))
    }

    @Test fun whatIsLeftOpenIsThePlannersToDecide() {
        val m = TravelBrief(suggestWhere = true, days = 7, who = TravelBrief.Who.Couple).message()
        assertTrue(m.contains("Where: suggest a place that fits"))
        assertTrue(m.contains("How long: a week"))
        // A couple says how many by itself.
        assertTrue(m.contains("Who: couple\n"))
        assertTrue(m.contains("Decide for me: when, pace, interests, budget, getting around"))
        assertTrue(m.endsWith("Plan the trip and propose it."))
        val nothing = TravelBrief().message()
        assertTrue(nothing.contains("Decide for me: where, when, how long, who, pace, interests, budget, getting around"))
        assertFalse(nothing.contains("Starting from"))
        assertFalse("the answers follow the house style", (m + nothing).contains("!"))
    }

    @Test fun theModelFillsInWhatItKnowsAndNothingElse() {
        val b = TravelBrief.fromArgs(args("""{"where":"  Rome ","when":"a weekend in April","days":3,"who":"family","people":5,
            "pace":"EASY","interests":["food","museums","yoga"],"budget":"luxury","gettingAround":"car","startFrom":"Milan","wishes":"the Colosseum"}"""))
        assertEquals("Rome", b.where)
        assertEquals("a weekend in April", b.whenText)
        assertEquals(3, b.days)
        assertEquals(TravelBrief.Who.Family, b.who)
        assertEquals(5, b.people)
        assertEquals(TravelBrief.Pace.Easy, b.pace)
        // Only the app's own choices: an unknown interest or budget is left open.
        assertEquals(setOf(TravelBrief.Interest.Food, TravelBrief.Interest.Museums), b.interests)
        assertEquals(null, b.budget)
        assertEquals(TravelBrief.GettingAround.Car, b.gettingAround)
        assertEquals("Milan", b.startFrom)
        assertEquals("the Colosseum", b.wishes)
        // Nonsense numbers are left out; a couple is two; "suggest one" only without a place.
        val odd = TravelBrief.fromArgs(args("""{"days":400,"who":"couple","people":9,"suggestWhere":true,"where":"Crete"}"""))
        assertEquals(null, odd.days)
        assertEquals(2, odd.people)
        assertFalse(odd.suggestWhere)
        assertFalse(odd.asksPeople)
        assertTrue(TravelBrief.fromArgs(args("""{"suggestWhere":true}""")).suggestWhere)
        assertEquals(TravelBrief(), TravelBrief.fromArgs(args("{}")))
        assertEquals(6, TravelBrief.fromArgs(args("""{"who":"friends","people":12}""")).people)
    }

    @Test fun aSavedTripStartsTheQuestionsWithItsPlaceAndDates() {
        val start = LocalDate.of(2027, 5, 3)
        val trip = Trip("t", "Portugal", subtitle = "Lisbon → Porto", startDate = start, endDate = start.plusDays(4), createdAt = 1, updatedAt = 1)
        val b = TravelBrief.fromTrip(trip)
        assertEquals("Lisbon → Porto", b.where)
        assertEquals("2027-05-03 to 2027-05-07", b.whenText)
        assertEquals(5, b.days)
        assertEquals("Japan", TravelBrief.fromTrip(Trip("j", "Japan", createdAt = 1, updatedAt = 1)).where)
        assertEquals(TravelBrief(), TravelBrief.fromTrip(null))
    }

    @Test fun labelsAreShortAndPlain() {
        assertEquals(listOf("2 days", "3 days", "4 days", "5 days", "A week", "10 days", "2 weeks"), TravelBrief.DAY_CHOICES.map(TravelBrief::daysLabel))
        assertEquals("6 or more", TravelBrief.peopleLabel(6))
        val labels = TravelBrief.Who.entries.map { it.label } + TravelBrief.Pace.entries.map { it.label } + TravelBrief.Interest.entries.map { it.label } +
            TravelBrief.Budget.entries.map { it.label } + TravelBrief.GettingAround.entries.map { it.label }
        labels.forEach { l ->
            assertTrue(l, l.length <= 30 && l.first().isUpperCase() && !l.contains("!"))
            // Sentence case: no capital after the first word.
            assertTrue(l, l.split(' ').drop(1).none { it.first().isUpperCase() })
        }
    }
}
