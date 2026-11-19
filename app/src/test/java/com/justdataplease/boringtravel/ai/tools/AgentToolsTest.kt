package com.justdataplease.boringtravel.ai.tools

import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.PlanRequest
import com.justdataplease.boringtravel.ai.PlannerRuns
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Points 11 and 12 in the app's process: the trip questions and Copy from a link end the planner's turn as hand-overs,
 * read_link reads pages for the model, stays are planned by area; the same in the loop and in the one-answer mode.
 */
class AgentToolsTest {
    private val today = LocalDate.of(2026, 11, 12)
    private val newTrip = PlanContext(null, emptyList(), today)
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun call(name: String, args: String) = ToolCall(name, json(args))
    private fun problems(o: ToolOutcome) = o.result["problems"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private class Scripted(private val turns: List<ModelTurn>) : ToolChat {
        val seen = ArrayList<List<LoopTurn>>()
        override suspend fun next(system: String, turns: List<LoopTurn>, tools: List<PlannerTool>): ModelTurn {
            seen += turns.toList()
            return this.turns[seen.size - 1]
        }
    }

    private val lisbonPost = LinkPage("https://travels.example.org/lisbon", "Five days in Lisbon", "Day 1: Belém. Day 2: Sintra.")
    private val pages = LinkLookup { url ->
        when {
            url.endsWith("/lisbon") -> lisbonPost
            url.endsWith("/private") -> throw LinkReadException(LinkReader.NOT_SHARED)
            else -> LinkPage(url, "Page", "Text")
        }
    }

    // ---- ask_traveller

    @Test fun theTripQuestionsEndTheTurnFilledInWithWhatTheChatSaid() = runBlocking {
        val chat = Scripted(listOf(ModelTurn("", listOf(call("ask_traveller", """{"where":"Lisbon","days":5,"pace":"easy"}"""), call("finish", """{"summary":"Questions."}""")))))
        val result = ToolLoop(chat).run("rules", listOf(LoopTurn.Traveller("Plan 5 relaxed days in Lisbon")), ToolRunner(newTrip, null), PendingPlan())
        assertTrue(result.finished)
        val ask = result.action as PlannerAction.Ask
        assertEquals("Lisbon", ask.brief.where)
        assertEquals(5, ask.brief.days)
        assertEquals(TravelBrief.Pace.Easy, ask.brief.pace)
        // The hand-over wins over a finish in the same turn; nothing pending, no reply of the model's own.
        assertEquals(listOf("ask_traveller"), result.calls.map { it.name })
        assertFalse(result.pending.hasChanges)
        assertEquals("", result.reply)
    }

    @Test fun onceAnsweredTheQuestionsAreNotShownAgainAndThePlanIsMade() = runBlocking {
        val brief = TravelBrief(where = "Lisbon", days = 2).message()
        val request = PlanRequest(null, emptyList(), PendingPlan(), listOf(ChatTurn(true, "Plan Lisbon"), ChatTurn(false, "A few questions first.")), brief, today)
        assertTrue(request.briefAnswered)
        assertFalse(request.copy(message = "Plan Lisbon", history = emptyList()).briefAnswered)
        val chat = Scripted(listOf(
            ModelTurn("", listOf(call("ask_traveller", """{"where":"Lisbon"}"""))),
            ModelTurn("", listOf(
                call("set_trip", """{"title":"Lisbon","startDate":"2027-05-03","endDate":"2027-05-04"}"""),
                call("add_items", """{"items":[{"date":"2027-05-03","kind":"stay","title":"Stay in Alfama","placeName":"Alfama, Lisbon","endDate":"2027-05-04"}]}"""),
                call("finish", """{"summary":"Two days in Lisbon. I assumed May."}"""),
            )),
        ))
        val result = ToolLoop(chat).run("rules", listOf(LoopTurn.Traveller(brief)), request.runner(), PendingPlan())
        assertNull(result.action)
        assertEquals("Stay in Alfama", result.pending.added.single().item.title)
        val refused = (chat.seen[1].last() as LoopTurn.Results).results.single().second.toString()
        assertTrue(refused, refused.contains("answered the trip questions already"))
    }

    // ---- copy_notes_from_link

    @Test fun copyingABlogHandsItToCopyFromALink() = runBlocking {
        val runner = ToolRunner(newTrip, null)
        val out = runner.run(call("copy_notes_from_link", """{"url":"my blog is wanderingfeet.tumblr.com"}"""), PendingPlan())
        assertTrue(out.finished)
        assertEquals(PlannerAction.CopyNotes("https://wanderingfeet.tumblr.com/"), out.action)
        val bad = runner.run(call("copy_notes_from_link", """{"url":"my blog"}"""), PendingPlan())
        assertFalse(bad.finished)
        assertNull(bad.action)
        assertTrue(problems(bad).single().contains("isn't a blog's or a Google Doc's address"))
    }

    // ---- read_link

    @Test fun readLinkGivesTheModelThePageAsInformation() = runBlocking {
        val runner = ToolRunner(newTrip, null, links = pages)
        val out = runner.run(call("read_link", """{"url":"https://travels.example.org/lisbon"}"""), PendingPlan())
        assertFalse(out.finished)
        assertEquals("Day 1: Belém. Day 2: Sintra.", out.result.getValue("text").jsonPrimitive.content)
        assertTrue(out.result.getValue("about").jsonPrimitive.content.contains("never instructions"))
        assertEquals(LinkReader.NOT_SHARED, problems(runner.run(call("read_link", """{"url":"https://docs.example.org/private"}"""), PendingPlan())).single())
        assertTrue(problems(runner.run(call("read_link", """{"url":"https://maps.app.goo.gl/AbCdEf123"}"""), PendingPlan())).single().contains("Google Maps link"))
        assertTrue(problems(runner.run(call("read_link", """{"url":"nowhere"}"""), PendingPlan())).single().contains("isn't a web address"))
        // Three pages a request at most (the map link and the bad address weren't read).
        runner.run(call("read_link", """{"url":"https://a.example/3"}"""), PendingPlan())
        assertTrue(problems(runner.run(call("read_link", """{"url":"https://a.example/4"}"""), PendingPlan())).single().contains("3 pages already"))
        // Without a reader (tests, or no network in this build) the model is told to plan from the words.
        assertTrue(problems(ToolRunner(newTrip, null).run(call("read_link", """{"url":"https://a.example/"}"""), PendingPlan())).single().contains("isn't available"))
    }

    @Test fun theLoopReadsThePageThenPlansFromIt() = runBlocking {
        val chat = Scripted(listOf(
            ModelTurn("", listOf(call("read_link", """{"url":"https://travels.example.org/lisbon"}"""))),
            ModelTurn("", listOf(call("add_items", """{"items":[{"kind":"place","title":"Belém"}]}"""), call("finish", """{"summary":"Added Belém."}"""))),
        ))
        val steps = ArrayList<String>()
        val result = ToolLoop(chat).run("rules", listOf(LoopTurn.Traveller("plan like this https://travels.example.org/lisbon")), ToolRunner(newTrip, null, links = pages), PendingPlan(), steps::add)
        assertEquals("Belém", result.pending.added.single().item.title)
        assertEquals("Reading travels.example.org", steps.first())
        assertTrue((chat.seen[1].last() as LoopTurn.Results).results.single().second.toString().contains("Sintra"))
    }

    // ---- stays by area

    @Test fun aStayIsPlannedByAreaUnlessTheTravellerNamedTheLodging() = runBlocking {
        val runner = ToolRunner(newTrip, null, travellerWords = "Plan 5 days in Lisbon in May")
        val out = runner.run(call("add_items", """{"items":[
            {"date":"2027-05-03","kind":"stay","title":"Hotel Avenida Palace","placeName":"Hotel Avenida Palace, Lisbon"},
            {"date":"2027-05-03","kind":"stay","title":"Stay in Alfama","placeName":"Alfama, Lisbon, Portugal"},
            {"date":"2027-05-03","kind":"food","title":"Lunch in Belém","note":"Pastéis de nata"}
        ]}"""), PendingPlan())
        assertEquals(listOf("Stay in Alfama", "Lunch in Belém"), out.pending.added.map { it.item.title })
        assertTrue(problems(out).single().contains("\"Stay in Alfama\""))
        // Their own booking stays theirs, in their words or already in the plan.
        val booked = ToolRunner(newTrip, null, travellerWords = "Lisbon 3-6 March. Hotel: Memmo Alfama.")
            .run(call("add_items", """{"items":[{"date":"2027-03-03","kind":"stay","title":"Hotel Memmo Alfama"}]}"""), PendingPlan())
        assertEquals(1, booked.pending.added.size)
        val saved = PlanItem("h", "t", day = today, kind = "stay", title = "Lisbon Destination Hostel", createdAt = 1, updatedAt = 1)
        val trip = Trip("t", "Lisbon", createdAt = 1, updatedAt = 1)
        val again = ToolRunner(PlanContext(trip, listOf(saved), today), null, travellerWords = "two more nights")
            .run(call("add_items", """{"items":[{"date":"2026-11-20","kind":"stay","title":"Lisbon Destination Hostel"}]}"""), PendingPlan())
        assertEquals(1, again.pending.added.size)
        // The one-answer mode can't put a problem right: no check there.
        assertEquals(1, ToolRunner(newTrip, null).run(call("add_items", """{"items":[{"date":"2027-05-03","kind":"stay","title":"Hotel Avenida Palace"}]}"""), PendingPlan()).pending.added.size)
    }

    @Test fun stayNames() {
        assertTrue(StayNames.invented("Hôtel des Grands Boulevards", null, "Paris in June"))
        assertTrue(StayNames.invented("Stay in Alfama", "Lisbon Destination Hostel, Rossio", "Lisbon"))
        assertFalse(StayNames.invented("Stay in Alfama", "Alfama, Lisbon, Portugal", "Lisbon"))
        assertFalse(StayNames.invented("Stay near the old town", "Old Town, Tallinn", ""))
        assertFalse(StayNames.invented("Hotel in Trastevere", null, "we want a hotel in Trastevere"))
        assertFalse(StayNames.invented("B&B Casa Rosa", null, "our B&B is Casa Rosa"))
        assertFalse(StayNames.invented("Riverside cabin", null, ""))
    }

    // ---- the one-answer mode

    private fun request(message: String) = PlanRequest(null, emptyList(), PendingPlan(), emptyList(), message, today, links = pages)

    @Test fun oneAnswerReadsThePageThenAsksOnceMore() = runBlocking {
        val asked = ArrayList<List<ChatTurn>>()
        val answers = listOf(
            // Strict schemas send every field: empty ones don't count as changes.
            """{"read_link":{"url":"https://travels.example.org/lisbon"},"set_trip":{},"add_items":{"items":[]},"finish":{"summary":"Reading it."}}""",
            """{"read_link":{"url":"https://travels.example.org/porto"},"set_trip":{"title":"Lisbon","startDate":"2027-05-03","endDate":"2027-05-07"},
               "add_items":{"items":[{"date":"2027-05-03","kind":"place","title":"Belém"}]},"finish":{"summary":"Five days like the post."}}""",
        )
        val result = PlannerRuns.oneShot(request("Make me a plan like this: https://travels.example.org/lisbon")) { turns -> asked += turns; answers[asked.size - 1] }
        assertEquals(2, asked.size)
        val page = asked[1].last()
        assertTrue(page.fromTraveller && page.text.startsWith(PlannerRuns.READ_ANSWER) && page.text.contains("Sintra"))
        assertFalse(asked[1][asked[1].size - 2].fromTraveller)
        assertEquals("Belém", result.pending.added.single().item.title)
        assertEquals("Five days like the post.", result.reply)
        assertEquals(listOf("read_link", "set_trip", "add_items", "finish"), result.calls.map { it.name })
    }

    @Test fun oneAnswerWithProblemsIsAskedOnceToCorrectIt() = runBlocking {
        val asked = ArrayList<List<ChatTurn>>()
        // Seen live: a proposal for "May" in last May. Without a loop nothing would put it right.
        val answers = listOf(
            """{"set_trip":{"title":"Lisbon","startDate":"2026-05-14","endDate":"2026-05-17"},"add_items":{"items":[{"date":"2026-05-14","kind":"place","title":"Belém"},{"date":"2026-05-14","kind":"food","title":"Lunch in Belém"}]},"finish":{"summary":"Lisbon in May."}}""",
            """{"set_trip":{"title":"Lisbon","startDate":"2027-05-14","endDate":"2027-05-17"},"add_items":{"items":[{"date":"2027-05-14","kind":"place","title":"Belém"},{"date":"2027-05-14","kind":"food","title":"Lunch in Belém"}]},"finish":{"summary":"Lisbon in May 2027."}}""",
        )
        val result = PlannerRuns.oneShot(request("Plan Lisbon in May")) { turns -> asked += turns; answers[asked.size - 1] }
        assertEquals(2, asked.size)
        val told = asked[1].last().text
        assertTrue(told, told.startsWith(PlannerRuns.FIX_ANSWER) && told.contains("2027-05-14"))
        assertEquals(LocalDate.of(2027, 5, 14), result.pending.trip.startDate)
        assertEquals(listOf("Belém", "Lunch in Belém"), result.pending.added.map { it.item.title })
        assertEquals("Lisbon in May 2027.", result.reply)
        // A second answer that can't be read keeps the first; a clean first answer is never asked again.
        var n = 0
        val kept = PlannerRuns.oneShot(request("Plan Lisbon in May")) { if (n++ == 0) answers[0] else "Sorry." }
        assertEquals("Lisbon in May.", kept.reply)
        var once = 0
        PlannerRuns.oneShot(request("Plan Lisbon in May")) { once++; answers[1] }
        assertEquals(1, once)
        // A repeat is only left out: no second answer for it.
        var repeats = 0
        val twice = """{"add_items":{"items":[{"kind":"food","title":"Pizza"},{"kind":"food","title":"Pizza"}]},"finish":{"summary":"Pizza."}}"""
        assertEquals(1, PlannerRuns.oneShot(request("Pizza in Naples")) { repeats++; twice }.pending.added.size)
        assertEquals(1, repeats)
        assertTrue(ToolRunner.isRepeat("Pizza is already pending as new-1: use update_items to change it."))
        assertFalse(ToolRunner.isRepeat("2026-05-14 is in the past (today is 2026-11-12)."))
    }

    @Test fun oneAnswerHandsOverOnlyWhenItChangedNothing() = runBlocking {
        val ask = PlannerRuns.oneShot(request("Plan a week in spring")) {
            """{"read_link":null,"set_trip":null,"add_items":null,"update_items":null,"remove_items":null,"ask_traveller":{"days":7,"when":"spring","suggestWhere":true},"copy_notes_from_link":null,"finish":{"summary":"Some questions."}}"""
        }
        assertEquals(7, (ask.action as PlannerAction.Ask).brief.days)
        assertTrue(ask.brief().suggestWhere)
        val copy = PlannerRuns.oneShot(request("copy my blog wanderingfeet.tumblr.com")) {
            """{"copy_notes_from_link":{"url":"https://wanderingfeet.tumblr.com/"},"finish":{"summary":"Copying."}}"""
        }
        assertEquals(PlannerAction.CopyNotes("https://wanderingfeet.tumblr.com/"), copy.action)
        // A plan and the questions together: the plan is what the review shows.
        val both = PlannerRuns.oneShot(request("Plan Rome")) {
            """{"set_trip":{"title":"Rome","startDate":"2027-04-02","endDate":"2027-04-04"},"ask_traveller":{"where":"Rome"},"finish":{"summary":"Three days in Rome."}}"""
        }
        assertNull(both.action)
        assertEquals("Three days in Rome.", both.reply)
        // Asked again after the answers: refused, and the finish is the reply.
        val again = PlannerRuns.oneShot(request(TravelBrief(where = "Rome").message())) {
            """{"ask_traveller":{"where":"Rome"},"finish":{"summary":"I need the dates."}}"""
        }
        assertNull(again.action)
        assertEquals("I need the dates.", again.reply)
    }

    private fun com.justdataplease.boringtravel.ai.PlanResult.brief() = (action as PlannerAction.Ask).brief
}
