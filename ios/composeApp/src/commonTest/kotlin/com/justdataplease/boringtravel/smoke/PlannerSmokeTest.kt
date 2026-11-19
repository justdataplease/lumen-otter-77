package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.DraftEdits
import com.justdataplease.boringtravel.ai.DraftLayout
import com.justdataplease.boringtravel.ai.DraftLimitException
import com.justdataplease.boringtravel.ai.DraftParseException
import com.justdataplease.boringtravel.ai.DraftUpdate
import com.justdataplease.boringtravel.ai.PlannerError
import com.justdataplease.boringtravel.ai.PlannerNotSetUpException
import com.justdataplease.boringtravel.ai.PlannerPrompt
import com.justdataplease.boringtravel.ai.TripDraftParser
import com.justdataplease.boringtravel.ai.own.OwnAiModels
import com.justdataplease.boringtravel.ai.own.OwnAiProvider
import com.justdataplease.boringtravel.ai.tools.LinkPage
import com.justdataplease.boringtravel.ai.tools.LinkPost
import com.justdataplease.boringtravel.ai.tools.LinkText
import com.justdataplease.boringtravel.ai.tools.LoopTurn
import com.justdataplease.boringtravel.ai.tools.PendingPlan
import com.justdataplease.boringtravel.ai.tools.PlannerTools
import com.justdataplease.boringtravel.ai.tools.StayNames
import com.justdataplease.boringtravel.ai.tools.TravelBrief
import com.justdataplease.boringtravel.compat.Instant
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.site.SiteBlock
import com.justdataplease.boringtravel.site.SitePost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The AI planner's pure parts, read in from the Android app (ai/): the system prompt and the trip as context, the
 * model's draft read forgivingly (times like "7pm", "\s" collapsing), its layout (accents folded), patches checked,
 * errors sorted by their words (\b), stays named by area (\p{Mn}), the trip questions, own-key model names and the
 * page text the planner reads.
 */
class PlannerSmokeTest {
    private val trip = Samples.trip()
    private val items = Samples.plan()

    // ---- PlannerPrompt

    @Test fun theSystemPromptSaysTodayAndFollowsItsOwnRules() {
        for (tools in listOf(true, false)) {
            val s = PlannerPrompt.system(Samples.today, tools)
            assertTrue(s.contains("Today is 2026-11-19."), "tools=$tools")
            assertTrue(s.contains("place, food, stay, transport, activity, note"))
            assertTrue(s.contains("A message starting \"${TravelBrief.HEADER}\" holds their answers"))
            assertFalse(s.contains("!"))
            assertEquals(tools, s.contains(PlannerTools.FIND_PLACES))
        }
    }

    @Test fun theTripIsSentWithItsItems() {
        val p = PlannerPrompt.context(trip, items)
        assertTrue(p.startsWith("Trip: Naxos, slowly\n"), p)
        assertTrue(p.contains("Dates: 2027-09-10 to 2027-09-14 (5 days)"))
        assertTrue(p.contains("Route: Paros → Naxos"))
        assertTrue(p.contains("- i1 · 2027-09-10 07:25 · transport · Ferry to Naxos · at Piraeus port · note: Blue Star"), p)
        assertTrue(p.contains("- i2 · 2027-09-11 19:30 · food · Dinner by the harbour (37.1, 25.37)"), p)
        assertTrue(p.contains("- i3 · idea · food · Kitron tasting in Halki"))
        assertFalse(p.contains("Removed earlier"))
        assertTrue(PlannerPrompt.context(null, emptyList()).startsWith("Trip: a new trip"))
        val turns = PlannerPrompt.turns(trip, items, PendingPlan(), listOf(ChatTurn(true, "Four days on Naxos"), ChatTurn(false, "Added.")), "Make day 2 slower")
        assertEquals(LoopTurn.Traveller("Four days on Naxos"), turns[0])
        assertEquals(LoopTurn.Planner("Added."), turns[1])
        assertTrue((turns[2] as LoopTurn.Traveller).text.endsWith("The traveller says:\nMake day 2 slower"))
    }

    // ---- TripDraftParser and DraftLayout

    private val draftText = """
        Here is the plan.
        ```json
        {"title":"Naxos, slowly","startDate":"2027-09-10","endDate":"2027-09-14T00:00:00","route":"Paros → Naxos",
         "days":[{"date":"2027-09-10","items":[
            {"time":"9:30","kind":"ferry","title":"Ferry to Naxos","placeName":"Naxos port, Greece","lat":"37.1056","lng":25.3747,"travel":"boat"},
            {"time":"7pm","kind":"restaurant","title":"Dinner   by the harbour","url":"https://lefteris.example.com/menu"}]},
          {"dayIndex":2,"items":[]}],
         "ideas":[{"title":"","placeName":"Portara, Naxos"},{"title":""}],
         "questions":["Which ferry?","Which ferry?"],
         "summary":"Two   days on Naxos."}
        ```
    """.trimIndent()

    @Test fun aDraftIsReadForgivingly() {
        val d = TripDraftParser.parse(draftText)
        assertEquals("Naxos, slowly", d.title)
        assertEquals("2027-09-10", d.startDate)
        assertEquals("2027-09-14", d.endDate)
        assertEquals(1, d.days.size)
        val (ferry, dinner) = d.days.single().items
        assertEquals("09:30", ferry.time)
        assertEquals("transport", ferry.kind)
        assertEquals(37.1056, ferry.lat)
        assertEquals("ferry", ferry.travel)
        assertEquals("19:00", dinner.time)
        assertEquals("food", dinner.kind)
        assertEquals("Dinner by the harbour", dinner.title)
        assertEquals("https://lefteris.example.com/menu", dinner.url)
        assertEquals(listOf("Portara, Naxos"), d.ideas.map { it.title })
        assertEquals(listOf("Which ferry?"), d.questions)
        assertEquals("Two days on Naxos.", d.summary)
        assertEquals(3, d.itemCount)
        assertFailsWith<DraftParseException> { TripDraftParser.parse("Sorry, no plan today.") }
        val tooLong = "{\"days\":[" + (1..61).joinToString(",") { "{\"dayIndex\":$it,\"items\":[{\"title\":\"x\"}]}" } + "]}"
        assertFailsWith<DraftLimitException> { TripDraftParser.parse(tooLong) }
    }

    @Test fun timesKindsWaysAndLinks() {
        assertEquals("07:30", TripDraftParser.normaliseTime("7.30"))
        assertEquals("19:40", TripDraftParser.normaliseTime("19h40"))
        assertEquals("19:30", TripDraftParser.normaliseTime("7.30 p.m."))
        assertEquals("00:05", TripDraftParser.normaliseTime("12:05am"))
        assertEquals("23:59", TripDraftParser.normaliseTime("24:00"))
        assertNull(TripDraftParser.normaliseTime("25:00"))
        assertNull(TripDraftParser.normaliseTime("noon"))
        assertEquals("stay", TripDraftParser.kindOf(" Hotel "))
        assertEquals("note", TripDraftParser.kindOf("something else"))
        assertEquals("walk", TripDraftParser.travelOf("on foot"))
        assertNull(TripDraftParser.travelOf("teleport"))
        assertEquals("https://example.org/x", TripDraftParser.webUrl(" https://example.org/x "))
        assertNull(TripDraftParser.webUrl("ftp://example.org/x"))
        assertNull(TripDraftParser.webUrl("example.org"))
        assertNull(TripDraftParser.webUrl("https://example.org/a b"))
        assertEquals("2027-06-17", TripDraftParser.isoDate("2027-06-17T10:00"))
        assertNull(TripDraftParser.isoDate("2027-02-30"))
    }

    @Test fun aDraftLaysOutOnItsDays() {
        val layout = DraftLayout.of(TripDraftParser.parse(draftText), null, emptyList())
        assertEquals("2027-09-10", layout.start?.toString())
        assertEquals("2027-09-14", layout.end?.toString())
        assertEquals(listOf("Ferry to Naxos", "Dinner by the harbour", "Portara, Naxos"), layout.all.map { it.item.title })
        assertTrue(layout.all.none { it.duplicate })
        assertEquals("portara at sunset", DraftLayout.normaliseTitle("Portára at  SUNSET!"))
        assertEquals("ταβερνα το λιμανι", DraftLayout.normaliseTitle("Ταβέρνα «Το Λιμάνι»"))
        assertEquals("ναξοσ", DraftLayout.normaliseTitle("ΝΆΞΟΣ"))
    }

    @Test fun patchesAreCheckedBeforeReview() {
        val preview = DraftEdits.preview(
            listOf(DraftUpdate(itemId = "i1", time = "08:05"), DraftUpdate(itemId = "zzz", time = "09:00"), DraftUpdate(itemId = "i1", title = "Twice")),
            trip, items,
        )
        assertEquals(8 * 60 + 5, preview.updates.single().after.timeMinutes)
        assertEquals(2, preview.problems.size)
        assertTrue(preview.problems.any { it.startsWith("A proposed change refers to an item that isn't in this trip") })
        assertTrue(preview.problems.any { it.contains("more than one change to Ferry to Naxos") })
        val bad = DraftEdits.preview(listOf(DraftUpdate(itemId = "i1", time = "25:00"), DraftUpdate(itemId = "i2", day = "2030-01-01")), trip, items)
        assertTrue(bad.updates.isEmpty())
        assertEquals(
            listOf("Ferry to Naxos: The proposed time isn't valid. Ask for HH:mm.", "Dinner by the harbour: The new date is outside this trip. Change the trip dates first."),
            bad.problems,
        )
    }

    // ---- PlannerError

    @Test fun errorsAreSortedByTheirWords() {
        assertTrue(PlannerError.isQuota(Exception("HTTP 429 Too Many Requests")))
        assertFalse(PlannerError.isQuota(Exception("order 4290 shipped")))
        assertTrue(PlannerError.isTransient(RuntimeException("wrapped", Exception("503 Service Unavailable"))))
        assertFalse(PlannerError.isTransient(Exception("5030 bytes")))
        assertEquals(PlannerError.Quota, PlannerError.of(Exception("HTTP 503"), debugBuild = false))
        assertEquals(PlannerError.Quota, PlannerError.of(Exception("RESOURCE_EXHAUSTED"), debugBuild = false))
        assertEquals(PlannerError.NotSetUp, PlannerError.of(PlannerNotSetUpException(), debugBuild = false))
        assertEquals(PlannerError.Unreadable, PlannerError.of(DraftParseException("x"), debugBuild = false))
        assertEquals(PlannerError.AppCheckDebug, PlannerError.of(IllegalStateException("App Check token is invalid"), debugBuild = true))
        assertEquals(PlannerError.AppCheck, PlannerError.of(IllegalStateException("App Check token is invalid"), debugBuild = false))
        assertEquals(PlannerError.Timeout, PlannerError.of(Exception("Deadline exceeded"), debugBuild = false))
        assertEquals(PlannerError.Unknown, PlannerError.of(Exception("something odd"), debugBuild = false))
    }

    // ---- StayNames and TravelBrief

    @Test fun staysArePlannedByArea() {
        assertEquals(listOf("hotel", "lefkes", "b&b"), StayNames.words("Hôtel Lefkés, B&B"))
        assertTrue(StayNames.invented("Hotel Lefkes", null, "we want a quiet place"))
        assertFalse(StayNames.invented("Hotel Lefkes", null, "Booked: Hotel Lefkes in Paros"))
        assertFalse(StayNames.invented("Stay in Alfama", "Alfama, Lisbon", ""))
        assertTrue(StayNames.invented("Stay in Naxos", "Ξενοδοχείο Αφροδίτη Resort, Naxos", ""))
    }

    @Test fun theTripQuestionsAndTheirAnswers() {
        val args = Json.parseToJsonElement(
            """{"where":"  Naxos \n and Paros ","days":4,"who":"couple","pace":"EASY","interests":["food","beaches","unknown"],"budget":"nope"}""",
        ).jsonObject
        val brief = TravelBrief.fromArgs(args)
        assertEquals("Naxos and Paros", brief.where)
        assertEquals(4, brief.days)
        assertEquals(TravelBrief.Who.Couple, brief.who)
        assertEquals(2, brief.people)
        assertEquals(TravelBrief.Pace.Easy, brief.pace)
        assertEquals(setOf(TravelBrief.Interest.Food, TravelBrief.Interest.Beaches), brief.interests)
        assertNull(brief.budget)
        val message = brief.message()
        assertTrue(message.startsWith(TravelBrief.HEADER + "\nWhere: Naxos and Paros\nHow long: 4 days\nWho: couple\nPace: easy\n"), message)
        assertTrue(message.contains("Interests: food, beaches\n"))
        assertTrue(message.contains("Decide for me: when, budget, getting around\n"))
        assertTrue(message.endsWith("Plan the trip and propose it."))
        assertTrue(TravelBrief.answered(listOf("hello", "  " + message)))
        assertEquals("A week", TravelBrief.daysLabel(7))
        assertEquals("2027-09-10 to 2027-09-14", TravelBrief.fromTrip(trip).whenText)
    }

    // ---- Own keys' model names

    @Test fun modelNamesFollowTheNewestOfAFamily() {
        assertEquals("gpt-6.1-sol", OwnAiModels.resolve(OwnAiProvider.ChatGpt, "gpt-sol-latest", listOf("gpt-5-sol", "gpt-6.1-sol", "gpt-6.1-sol-pro", "gpt-6-astra")))
        assertEquals("claude-haiku-4-2", OwnAiModels.resolve(OwnAiProvider.Claude, "claude-haiku-latest", listOf("claude-haiku-3-9", "claude-haiku-4", "claude-haiku-4-2", "claude-opus-5")))
        assertEquals("my-own-model", OwnAiModels.resolve(OwnAiProvider.Gemini, "my-own-model", emptyList()))
        assertEquals(
            listOf("gpt-sol-latest", "gpt-astra-latest", "gpt-zeta-latest"),
            OwnAiModels.offered(OwnAiProvider.ChatGpt, listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6-image", "gpt-6.1-sol-pro", "gpt-5-zeta")),
        )
        assertEquals("gemini-flash-lite-latest", OwnAiModels.upgrade(OwnAiProvider.Gemini, "gemini-3.5-flash-lite"))
        assertEquals("~openai/gpt-luna-latest", OwnAiModels.upgrade(OwnAiProvider.OpenRouter, "openai/gpt-6-luna"))
        assertTrue(OwnAiModels.isLatest("~x-ai/grok-latest"))
        assertEquals(OwnAiProvider.Claude, OwnAiProvider.of("claude"))
        assertEquals(OwnAiModels.GEMINI_DEFAULT, OwnAiModels.fallback(OwnAiProvider.Gemini))
    }

    // ---- Tools and the page text the planner reads

    @Test fun theToolsAndAPageAsText() {
        assertEquals(PlannerTools.ADD_ITEMS, PlannerTools.named("add_items")?.name)
        assertTrue(PlannerTools.all.any { it.name == PlannerTools.FINISH && it.finishes })
        val blocks = listOf(
            SiteBlock.Heading("Naxos", 2), SiteBlock.Text("Ferry at <b>7</b>.<br>Then coffee."),
            SiteBlock.Text("Boots", ListStyle.Bullet), SiteBlock.Text("Hat", ListStyle.Bullet),
            SiteBlock.Text("First", ListStyle.Number), SiteBlock.Quote("Go early"), SiteBlock.Divider,
            SiteBlock.Picture("https://cdn.example.com/p.jpg", "Portara at dusk"),
        )
        assertEquals(
            "## Naxos\n\nFerry at 7.\nThen coffee.\n\n- Boots\n- Hat\n\n1. First\n\n> Go early\n\n[Picture: Portara at dusk]",
            LinkText.of(blocks),
        )
        assertEquals("aaaaaaaaaa" to true, LinkText.cap("a".repeat(10) + "\n" + "b".repeat(10), 15))
        assertEquals("short" to false, LinkText.cap("short", 15))
        assertTrue(LinkText.isIndex("https://slowroads.example.com/blog"))
        assertFalse(LinkText.isIndex("https://slowroads.example.com/2019/05/naxos"))
        assertTrue(LinkText.isMap("https://www.google.com/maps/place/Portara/@37.1106,25.3713,17z"))
        assertEquals("https://slowroads.example.com/naxos", LinkText.address("read http://slowroads.example.com/naxos, please"))
        assertEquals("https://slowroads.tumblr.com/", LinkText.address("copy slowroads.tumblr.com"))
        assertNull(LinkText.address("https://www.google.com/maps/place/Portara/@37.1106,25.3713,17z"))
        val posts = LinkText.posts(listOf(SitePost("a", "https://slowroads.example.com/a/", "", Instant.parse("2019-05-04T08:30:00Z"), emptyList())))
        assertEquals(listOf(LinkPost("https://slowroads.example.com/a/", "https://slowroads.example.com/a/", "2019-05-04")), posts)
        val result = LinkText.result(LinkPage("https://slowroads.example.com/", "Slow Roads", "Some text", cut = true, posts = posts))
        assertEquals("Slow Roads", result["title"]?.jsonPrimitive?.content)
        assertEquals(1, result["posts"]?.jsonArray?.size)
        assertTrue(result.containsKey("cut") && result.containsKey("next"))
    }
}
