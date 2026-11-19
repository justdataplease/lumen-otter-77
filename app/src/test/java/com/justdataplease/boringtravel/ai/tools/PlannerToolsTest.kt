package com.justdataplease.boringtravel.ai.tools

import com.justdataplease.boringtravel.ai.DraftApplier
import com.justdataplease.boringtravel.ai.DraftLayout
import com.justdataplease.boringtravel.ai.TripChange
import com.justdataplease.boringtravel.ai.own.GeminiTools
import com.justdataplease.boringtravel.ai.own.OpenRouterTools
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.places.PlaceResult
import com.justdataplease.boringtravel.ui.trip.travel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The planner's tools carried out by the app: checks, pending changes, the loop, the review and the save. */
class PlannerToolsTest {
    private val today = LocalDate.of(2026, 11, 11)
    private val d12 = LocalDate.of(2026, 11, 12)
    private val trip = Trip("t", "Madrid", startDate = d12, endDate = d12.plusDays(7), createdAt = 1, updatedAt = 1)
    private val prado = PlanItem("p", "t", day = d12.plusDays(2), position = 1.0, timeMinutes = 18 * 60, kind = "place", title = "Museo del Prado", lat = 40.4138, lng = -3.6921, createdAt = 1, updatedAt = 1)
    private val hostal = PlanItem("h", "t", day = d12, position = 2.0, kind = "stay", title = "Hostal Alicante", lat = 40.4168, lng = -3.7067, createdAt = 1, updatedAt = 1)
    private val ctx = PlanContext(trip, listOf(prado, hostal), today)

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun call(name: String, args: String) = ToolCall(name, json(args))
    private val noPlaces = PlaceLookup { _, _ -> emptyList() }

    @Test fun addItemsGivesPendingIdsAndRefusesRepeats() = runBlocking {
        val runner = ToolRunner(ctx, noPlaces)
        val out = runner.run(call("add_items", """{"items":[
            {"date":"2026-11-13","time":"21:00","kind":"food","title":"Dinner at Botín","placeName":"Botín, Madrid","travel":"walk"},
            {"date":"2026-11-14","kind":"place","title":"Museo del Prado"},
            {"date":"13/11/2026","kind":"place","title":"Bad date"},
            {"kind":"activity","title":"Flamenco show"}
        ]}"""), PendingPlan())
        assertEquals(listOf("new-1", "new-2"), out.pending.added.map { it.id })
        assertEquals("walk", out.pending.added[0].item.travel)
        assertNull(out.pending.added[1].date) // the flamenco show is an idea
        val problems = out.result.getValue("problems").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(problems.any { "already planned" in it && "itemId p" in it })
        assertTrue(problems.any { "isn't yyyy-MM-dd" in it })
        // The same item again is pending already.
        val again = runner.run(call("add_items", """{"items":[{"date":"2026-11-13","kind":"food","title":"Dinner at Botin"}]}"""), out.pending)
        assertEquals(2, again.pending.added.size)
        assertTrue(again.result.toString().contains("already pending as new-1"))
    }

    @Test fun aTimeOnlyInTheNoteBecomesTheStartTime() = runBlocking {
        val out = ToolRunner(ctx, noPlaces).run(call("add_items", """{"items":[
            {"date":"2026-11-12","kind":"place","title":"Museo Reina Sofía","note":"19:00-21:00 · free entry"},
            {"date":"2026-11-12","kind":"stay","title":"Hotel Urban","note":"Until 15 Nov"},
            {"date":"2026-11-13","kind":"activity","title":"Concert","time":"18:30","note":"20:00 doors"}
        ]}"""), PendingPlan())
        assertEquals(listOf("19:00", null, "18:30"), out.pending.added.map { it.item.time })
    }

    @Test fun updatesWorkOnSavedAndPendingItemsAndUnknownIdsComeBack() = runBlocking {
        val runner = ToolRunner(ctx, noPlaces)
        var plan = runner.run(call("add_items", """{"items":[{"date":"2026-11-13","kind":"food","title":"Dinner","placeName":"Botín, Madrid","lat":40.41,"lng":-3.70}]}"""), PendingPlan()).pending
        val out = runner.run(call("update_items", """{"updates":[
            {"itemId":"p","time":"17:00","noteAppend":"Free entry"},
            {"itemId":"p","kind":"activity"},
            {"itemId":"new-1","time":"21:30","placeName":"Casa Lucio, Madrid"},
            {"itemId":"zzz","time":"10:00"}
        ]}"""), plan)
        plan = out.pending
        val patch = plan.updates.getValue("p")
        assertEquals("17:00", patch.time)
        assertEquals("Free entry", patch.noteAppend)
        assertEquals("activity", patch.kind)
        val dinner = plan.added.single().item
        assertEquals("21:30", dinner.time)
        // A new place without coordinates drops the old pin, so the app looks the new one up.
        assertNull(dinner.lat)
        assertTrue(out.result.toString().contains("No item has id \\\"zzz\\\""))
    }

    @Test fun removeDropsPendingItemsAndMarksSavedOnes() = runBlocking {
        val runner = ToolRunner(ctx, noPlaces)
        var plan = runner.run(call("add_items", """{"items":[{"date":"2026-11-13","kind":"food","title":"Dinner"}]}"""), PendingPlan()).pending
        plan = runner.run(call("update_items", """{"updates":[{"itemId":"h","time":"14:00"}]}"""), plan).pending
        plan = runner.run(call("remove_items", """{"itemIds":["new-1","h","nope"]}"""), plan).pending
        assertTrue(plan.added.isEmpty())
        assertEquals(setOf("h"), plan.removed)
        assertFalse("h" in plan.updates)
    }

    @Test fun setTripChecksDatesAndFinishEndsTheTurn() = runBlocking {
        val runner = ToolRunner(PlanContext(null, emptyList(), today), noPlaces)
        val bad = runner.run(call("set_trip", """{"startDate":"2026-11-19","endDate":"2026-11-12"}"""), PendingPlan())
        assertTrue(bad.result.toString().contains("endDate is before startDate"))
        assertTrue(bad.pending.trip.isEmpty)
        val ok = runner.run(call("set_trip", """{"title":"Spain","startDate":"2026-11-12","endDate":"2026-11-19","route":"Madrid → Granada → Seville"}"""), PendingPlan())
        assertEquals(LocalDate.of(2026, 11, 19), ok.pending.trip.endDate)
        val done = runner.run(call("finish", """{"summary":"Trip set.","questions":["Which year?"]}"""), ok.pending)
        assertTrue(done.finished)
        assertEquals("Trip set.", done.pending.summary)
        assertEquals(listOf("Which year?"), done.pending.questions)
    }

    @Test fun aNewTripsPastDateIsQuestionedOnce() = runBlocking {
        val runner = ToolRunner(PlanContext(null, emptyList(), today), noPlaces)
        val first = runner.run(call("set_trip", """{"title":"Lisbon","startDate":"2026-03-03","endDate":"2026-03-06"}"""), PendingPlan())
        assertTrue(first.pending.trip.isEmpty)
        assertTrue(first.result.toString().contains("2027-03-03"))
        // Every item of the same turn on that date is questioned too: the model wrote them before it was told.
        val sameTurn = runner.run(call("add_items", """{"items":[{"date":"2026-03-03","kind":"place","title":"Belém"},{"date":"2026-03-03","kind":"food","title":"Lunch in Belém"}]}"""), PendingPlan())
        assertTrue(sameTurn.pending.added.isEmpty())
        // Sent again in its next turn, it is meant: a past trip to write up.
        runner.nextTurn()
        val again = runner.run(call("set_trip", """{"title":"Lisbon","startDate":"2026-03-03","endDate":"2026-03-06"}"""), PendingPlan())
        assertEquals(LocalDate.of(2026, 3, 3), again.pending.trip.startDate)
        // A saved trip may hold any date.
        val saved = ToolRunner(ctx, noPlaces).run(call("add_items", """{"items":[{"date":"2026-03-03","kind":"note","title":"Old note"}]}"""), PendingPlan())
        assertEquals(1, saved.pending.added.size)
    }

    @Test fun bookingDetailsFromTheMessageMustLandInAnItem() = runBlocking {
        val message = "ATH -> MAD Flight Nov 12, 2026 (15:50 - 16:50)\nFlight Info: Iberia IB 3151 (Booking Code: JMKXT)\nHostal Alicante, C. del Arenal, 16, 28013 Madrid"
        val runner = ToolRunner(PlanContext(null, emptyList(), today), noPlaces)
        assertEquals(listOf("IB 3151", "JMKXT"), runner.missingDetails(message, PendingPlan()))
        val held = PendingPlan(added = listOf(PendingItem("new-1", d12, null, com.justdataplease.boringtravel.ai.DraftItem(kind = "transport", title = "Fly to Madrid", note = "15:50-16:50 · IB3151 · booking JMKXT"))))
        assertEquals(emptyList<String>(), runner.missingDetails(message, held))
        // In the loop: one question back, then the model adds them.
        val chat = Scripted(listOf(
            ModelTurn("", listOf(call("add_items", """{"items":[{"date":"2026-11-12","kind":"transport","title":"Fly to Madrid"}]}"""), call("finish", """{"summary":"Done."}"""))),
            ModelTurn("", listOf(call("update_items", """{"updates":[{"itemId":"new-1","noteAppend":"IB 3151 · booking JMKXT"}]}"""), call("finish", """{"summary":"Done."}"""))),
        ))
        val result = ToolLoop(chat).run("rules", listOf(LoopTurn.Traveller(message)), runner, PendingPlan(), message = message)
        assertTrue(result.finished)
        assertTrue(result.pending.added.single().item.note.contains("JMKXT"))
        assertTrue((chat.seen[1].last() as LoopTurn.Results).results.last().second.toString().contains("IB 3151, JMKXT"))
    }

    @Test fun findPlacesPrefersResultsNearTheGivenTown() = runBlocking {
        val madrid = PlaceResult("Madrid", "Madrid, Spain", 40.4168, -3.7038)
        val lookup = PlaceLookup { q, near ->
            when {
                q.startsWith("Madrid") -> listOf(madrid)
                near != null -> listOf(PlaceResult("Hotel Central", "Lima, Peru", -12.05, -77.04), PlaceResult("Hotel Central", "Madrid, Spain", 40.41, -3.70))
                else -> emptyList()
            }
        }
        val out = ToolRunner(ctx, lookup).run(call("find_places", """{"queries":[{"query":"Hotel Central","near":"Madrid, Spain"}]}"""), PendingPlan())
        val first = out.result.getValue("results").jsonArray[0].jsonObject.getValue("found").jsonArray[0].jsonObject
        assertEquals("Madrid, Spain", first.getValue("address").jsonPrimitive.content)
    }

    private class Scripted(private val turns: List<ModelTurn>) : ToolChat {
        val seen = ArrayList<List<LoopTurn>>()
        override suspend fun next(system: String, turns: List<LoopTurn>, tools: List<PlannerTool>): ModelTurn {
            seen += turns.toList()
            return this.turns[seen.size - 1]
        }
    }

    @Test fun theLoopRunsCallsSendsResultsBackAndStopsAtFinish() = runBlocking {
        val chat = Scripted(listOf(
            ModelTurn("", listOf(call("add_items", """{"items":[{"date":"2026-11-13","kind":"food","title":"Dinner"}]}"""), call("finish", """{"summary":"Added dinner."}"""), call("set_trip", """{"route":"Madrid"}"""))),
        ))
        val result = ToolLoop(chat).run("rules", listOf(LoopTurn.Traveller("add dinner")), ToolRunner(ctx, noPlaces), PendingPlan())
        assertTrue(result.finished)
        assertEquals("Added dinner.", result.reply)
        // Finish runs last: the route set in the same turn is kept.
        assertEquals("Madrid", result.pending.trip.route)
        assertEquals(listOf("add_items", "set_trip", "finish"), result.calls.map { it.name })

        val twoRounds = Scripted(listOf(
            ModelTurn("", listOf(call("update_items", """{"updates":[{"itemId":"bad","time":"10:00"}]}"""))),
            ModelTurn("", listOf(call("finish", """{"summary":"Done."}"""))),
        ))
        ToolLoop(twoRounds).run("rules", listOf(LoopTurn.Traveller("x")), ToolRunner(ctx, noPlaces), PendingPlan())
        val results = twoRounds.seen[1].last() as LoopTurn.Results
        assertTrue(results.results.single().second.toString().contains("No item has id"))
    }

    @Test fun aFinishThatChangedNothingIsAskedOnceToMakeTheChange() = runBlocking {
        val chat = Scripted(listOf(
            ModelTurn("", listOf(call("find_places", """{"queries":[{"query":"Botín","near":"Madrid"}]}"""), call("finish", """{"summary":"Added dinner."}"""))),
            ModelTurn("", listOf(call("add_items", """{"items":[{"date":"2026-11-13","time":"21:00","kind":"food","title":"Dinner at Botín"}]}"""), call("finish", """{"summary":"Added dinner."}"""))),
        ))
        val result = ToolLoop(chat).run("rules", listOf(LoopTurn.Traveller("add dinner")), ToolRunner(ctx, noPlaces), PendingPlan())
        assertTrue(result.finished)
        assertEquals("Dinner at Botín", result.pending.added.single().item.title)
        val asked = chat.seen[1].last() as LoopTurn.Results
        assertTrue(asked.results.last().second.toString().contains("Nothing has changed yet"))
        // A plain question, or a second finish, ends the turn as it is.
        val question = Scripted(listOf(ModelTurn("", listOf(call("finish", """{"summary":"Which year?","questions":["Which year?"]}""")))))
        assertTrue(ToolLoop(question).run("rules", listOf(LoopTurn.Traveller("Paris in May")), ToolRunner(ctx, noPlaces), PendingPlan()).finished)
        val twice = Scripted(listOf(ModelTurn("", listOf(call("finish", """{"summary":"Day 2 is free."}"""))), ModelTurn("", listOf(call("finish", """{"summary":"Day 2 is free."}""")))))
        assertEquals("Day 2 is free.", ToolLoop(twice).run("rules", listOf(LoopTurn.Traveller("what's on day 2?")), ToolRunner(ctx, noPlaces), PendingPlan()).reply)
    }

    @Test fun reviewShowsTripChangesRemovalsAndATripThatGrows() {
        val pending = PendingPlan(
            trip = TripPatch(title = "Spain", startDate = d12.plusDays(2), endDate = d12.plusDays(9), moveItemsWithDates = true),
            removed = setOf("h"),
            added = listOf(PendingItem("new-1", d12.plusDays(11), null, com.justdataplease.boringtravel.ai.DraftItem(kind = "food", title = "Late dinner"))),
        )
        val layout = DraftLayout.of(pending.toDraft(), trip, ctx.items)
        val change = layout.tripChange!!
        assertEquals("Spain", change.title)
        assertEquals(d12.plusDays(2), change.start)
        // A day after the new end makes the trip longer.
        assertEquals(d12.plusDays(11), change.end)
        assertEquals(2L, change.shiftDays)
        assertEquals(listOf("h"), layout.removals.map { it.item.id })
        assertEquals(1 + 2 + 1, layout.reviewableCount) // the dinner, two trip parts (name, dates), one removal
    }

    @Test fun savingMovesThePlanRemovesAndPlacesNewItemsByTime() {
        val dinner = com.justdataplease.boringtravel.ai.DraftItem(time = "21:00", kind = "food", title = "Dinner", placeName = "Botín, Madrid", lat = 40.4141, lng = -3.7081)
        val lunch = com.justdataplease.boringtravel.ai.DraftItem(time = "13:00", kind = "food", title = "Lunch", lat = 40.41, lng = -3.70)
        val pending = PendingPlan(
            trip = TripPatch(startDate = d12.plusDays(1), endDate = d12.plusDays(8), moveItemsWithDates = true),
            added = listOf(PendingItem("new-1", d12.plusDays(3), null, dinner), PendingItem("new-2", d12.plusDays(3), null, lunch)),
            removed = setOf("h"),
        )
        val layout = DraftLayout.of(pending.toDraft(), trip, ctx.items)
        var n = 0
        val r = DraftApplier.apply(layout, trip, ctx.items, emptySet(), "draft", 100) { "id${++n}" }
        assertEquals(d12.plusDays(1), r.trip.startDate)
        assertTrue(r.datesChanged)
        assertEquals(d12.plusDays(3), r.movedItems.single { it.id == "p" }.day) // the Prado moved a day
        assertEquals(100L, r.removedItems.single().deletedAt)
        // On the Prado's new day: lunch (13:00), the Prado (18:00), dinner (21:00).
        val day = (r.items + r.movedItems).filter { it.day == d12.plusDays(3) }.sortedBy { it.position }.map { it.title }
        assertEquals(listOf("Lunch", "Museo del Prado", "Dinner"), day)
        // No website given: a Google Maps link, kept as the Maps link.
        val dinnerItem = r.items.single { it.title == "Dinner" }
        assertTrue(dinnerItem.mapsUrl!!.startsWith("https://www.google.com/maps/search/?api=1&query=Bot"))
        assertEquals(null, dinnerItem.url)
    }

    @Test fun theAisTravelChoiceBecomesTheLegsChoiceWhenItDiffers() {
        val athens = com.justdataplease.boringtravel.ai.DraftItem(kind = "transport", title = "Leave Athens", lat = 37.9364, lng = 23.9445)
        val madrid = com.justdataplease.boringtravel.ai.DraftItem(time = "15:50", kind = "transport", title = "Athens to Madrid", lat = 40.4839, lng = -3.5680, travel = "plane")
        val granada = com.justdataplease.boringtravel.ai.DraftItem(kind = "stay", title = "Suspiro 201", lat = 37.1732, lng = -3.5977, travel = "train")
        val pending = PendingPlan(
            trip = TripPatch(title = "Spain", startDate = d12, endDate = d12.plusDays(7)),
            added = listOf(PendingItem("new-1", d12, null, athens), PendingItem("new-2", d12, null, madrid), PendingItem("new-3", d12.plusDays(3), null, granada)),
        )
        val layout = DraftLayout.of(pending.toDraft(), null, emptyList())
        var n = 0
        val r = DraftApplier.apply(layout, null, emptyList(), emptySet(), "draft", 100) { "id${++n}" }
        val legs = RouteLegs.planned(r.trip, r.items).associateBy { it.key.toId }
        val flight = r.items.single { it.title == "Athens to Madrid" }
        val stay = r.items.single { it.title == "Suspiro 201" }
        assertEquals(Vehicle.Plane, legs.getValue(flight.id).travel)
        assertEquals(Vehicle.Train, legs.getValue(stay.id).travel)
        // Plane is what the leg shows anyway (2,370 km): only the train is stored.
        assertEquals(1, Regex("\"vehicle\"").findAll(r.trip.travelModes.orEmpty()).count())
    }

    @Test fun travelDefaultsFlyOverLongHopsAndReadFlightNames() {
        assertEquals(Vehicle.Plane, TravelDefault.between("Madrid", samePlace = false, distanceM = 2_370_000.0))
        assertEquals(Vehicle.Car, TravelDefault.between("Granada", samePlace = false, distanceM = 360_000.0))
        assertEquals(Vehicle.Plane, TravelDefault.between("ATH -> MAD Flight", samePlace = false, travelItem = true))
        assertEquals(Vehicle.Plane, TravelDefault.between("Πτήση για Μαδρίτη", samePlace = false))
        assertEquals(Vehicle.Train, TravelDefault.between("AVE Madrid → Sevilla", samePlace = false, travelItem = true))
        // Not a travel item: "train" in its name says nothing.
        assertEquals(Vehicle.Car, TravelDefault.between("Lunch by the train station", samePlace = false, travelItem = false))
        assertEquals(Vehicle.Walk, TravelDefault.between("Mercado de San Miguel", samePlace = true, distanceM = 800.0))
    }

    @Test fun pendingPlacesFindsPinsNearThePlaceBefore() = runBlocking {
        val asked = ArrayList<Pair<String, Double?>>()
        val lookup = PlaceLookup { q, near -> asked += q to near?.lat; listOf(PlaceResult(q, q, 40.0 + asked.size, -3.0)) }
        val pending = PendingPlan(added = listOf(
            PendingItem("new-1", d12, null, com.justdataplease.boringtravel.ai.DraftItem(kind = "place", title = "A", placeName = "Place A")),
            PendingItem("new-2", d12, null, com.justdataplease.boringtravel.ai.DraftItem(kind = "note", title = "Note", placeName = "Not looked up")),
            PendingItem("new-3", d12.plusDays(1), null, com.justdataplease.boringtravel.ai.DraftItem(kind = "place", title = "B", placeName = "Place A")),
        ), updates = mapOf("p" to com.justdataplease.boringtravel.ai.DraftUpdate(itemId = "p", placeName = "Prado annex")))
        val located = PendingPlaces.locate(pending, ctx, lookup)
        assertNotNull(located.added[0].item.lat)
        assertNull(located.added[1].item.lat)
        assertEquals(located.added[0].item.lat, located.added[2].item.lat) // same name, one look-up
        assertEquals(40.4138, asked.single { it.first == "Prado annex" }.second)
        assertEquals(2, asked.size)
    }

    @Test fun geminiTurnsKeepTheirContentAndResultsGoBackAsFunctionResponses() {
        val body = """{"candidates":[{"content":{"role":"model","parts":[
            {"text":"thinking","thought":true},
            {"functionCall":{"name":"add_items","args":{"items":[]},"id":"c1"},"thoughtSignature":"sig"},
            {"functionCall":{"name":"finish","args":{"summary":"ok"}}}]},"finishReason":"STOP"}]}"""
        val turn = GeminiTools.parse(body)
        assertEquals(listOf("add_items", "finish"), turn.calls.map { it.name })
        assertEquals("c1", turn.calls[0].id)
        assertEquals("", turn.text)
        val request = GeminiTools.body("rules", listOf(LoopTurn.Traveller("hi"), LoopTurn.Planner("", turn.calls, turn.raw), LoopTurn.Results(listOf(turn.calls[0] to json("""{"added":[]}""")))), PlannerTools.all)
        val contents = request.getValue("contents").jsonArray
        assertTrue(contents[1].toString().contains("\"thoughtSignature\":\"sig\""))
        assertEquals("c1", contents[2].jsonObject.getValue("parts").jsonArray[0].jsonObject.getValue("functionResponse").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("ANY", request.getValue("toolConfig").jsonObject.getValue("functionCallingConfig").jsonObject.getValue("mode").jsonPrimitive.content)
        assertEquals(PlannerTools.all.size, request.getValue("tools").jsonArray[0].jsonObject.getValue("functionDeclarations").jsonArray.size)
    }

    @Test fun openRouterToolCallsAndToolMessages() {
        val body = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
            "tool_calls":[{"id":"call_1","type":"function","function":{"name":"set_trip","arguments":"{\"title\":\"Spain\"}"}}],
            "reasoning_details":[{"type":"reasoning.text","text":"x"}]}}]}"""
        val turn = OpenRouterTools.parse(body)
        assertEquals("Spain", turn.calls.single().args.getValue("title").jsonPrimitive.content)
        val request = OpenRouterTools.body("~deepseek/deepseek-flash-latest", "rules", listOf(LoopTurn.Traveller("hi"), LoopTurn.Planner("", turn.calls, turn.raw), LoopTurn.Results(listOf(turn.calls[0] to JsonObject(mapOf("ok" to JsonPrimitive(true)))))), PlannerTools.all)
        val messages = request.getValue("messages").jsonArray
        assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertTrue(messages[2].jsonObject.containsKey("reasoning_details"))
        assertEquals("call_1", messages[3].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("required", request.getValue("tool_choice").jsonPrimitive.content)
        assertTrue(request.getValue("tools") is JsonArray)
    }

    @Test fun tripChangePartsCanBeUntickedOneByOne() {
        val pending = PendingPlan(trip = TripPatch(title = "Spain", route = "Madrid → Granada", startDate = d12, endDate = d12.plusDays(9)))
        val layout = DraftLayout.of(pending.toDraft(), trip, ctx.items)
        val r = DraftApplier.apply(layout, trip, ctx.items, setOf(TripChange.TITLE), "draft", 100) { "x" }
        assertEquals("Madrid", r.trip.title)
        assertEquals("Madrid → Granada", r.trip.subtitle)
        assertEquals(d12.plusDays(9), r.trip.endDate)
        assertTrue(r.movedItems.isEmpty()) // longer, not moved
    }

    @Test fun aDayIsNamedThroughSetTripReviewedAndSaved() = runBlocking {
        // Owner, 16 Nov 2026: "how can the planner rename a day … not the plan items but the day".
        val runner = ToolRunner(ctx, noPlaces)
        val out = runner.run(call("set_trip", """{"dayNames":[{"date":"2026-11-14","name":" Prado  and tapas "},{"date":"2026-11-12","name":"Arrival"}]}"""), PendingPlan())
        assertEquals(mapOf(d12.plusDays(2) to "Prado and tapas", d12 to "Arrival"), out.pending.trip.dayNames)
        assertTrue(out.pending.hasChanges)
        assertTrue(out.pending.added.isEmpty() && out.pending.updates.isEmpty())
        assertTrue(out.result.toString(), out.result.toString().contains("2026-11-14: Prado and tapas"))
        // A day that isn't one of the trip's, and a date that isn't one, come back; nothing of that call is kept.
        val bad = runner.run(call("set_trip", """{"dayNames":[{"date":"2026-12-25","name":"Christmas"},{"date":"Tuesday","name":"Beach"}]}"""), out.pending)
        val problems = bad.result.getValue("problems").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(problems.toString(), problems.any { "2026-12-25 is not a day of the trip" in it } && problems.any { "\"Tuesday\" isn't a date" in it })
        assertEquals(out.pending, bad.pending)
        // The review: one line a day, in date order, each with its own tick.
        val layout = DraftLayout.of(out.pending.toDraft(), trip, ctx.items)
        assertEquals(listOf(Triple(1, null, "Arrival"), Triple(3, null, "Prado and tapas")), layout.dayNames.map { Triple(it.number, it.before, it.name) })
        assertEquals(2, layout.reviewableCount)
        assertNull(layout.tripChange)
        // Saved without the one unticked: the day has its name, and nothing else of the trip or its plan changes.
        val r = DraftApplier.apply(layout, trip, ctx.items, setOf(layout.dayNames[0].key), "draft", 100) { "x" }
        assertEquals(mapOf(d12.plusDays(2) to "Prado and tapas"), DayNames.all(r.trip.travelModes))
        assertTrue(r.saveTrip)
        assertEquals(1, r.namedDays)
        assertTrue(r.rows.isEmpty())
        assertEquals(trip.copy(travelModes = r.trip.travelModes, updatedAt = 100), r.trip)
        // A new trip can be made with its days named.
        val fresh = ToolRunner(PlanContext(null, emptyList(), today), noPlaces).run(
            call("set_trip", """{"title":"Spain","startDate":"2026-11-12","endDate":"2026-11-14","dayNames":[{"date":"2026-11-13","name":"Toledo"}]}"""), PendingPlan(),
        ).pending
        val made = DraftApplier.apply(DraftLayout.of(fresh.toDraft(), null, emptyList()), null, emptyList(), emptySet(), "draft", 100) { "new" }
        assertEquals(mapOf(d12.plusDays(1) to "Toledo"), DayNames.all(made.trip.travelModes))
    }

    @Test fun dayNamesMoveWithTheTripAndAnEmptyNameTakesOneAway() = runBlocking {
        val named = trip.copy(travelModes = DayNames.setAll(null, mapOf(d12 to "Arrival", d12.plusDays(2) to "Prado and tapas")))
        val runner = ToolRunner(PlanContext(named, ctx.items, today), noPlaces)
        // The whole trip a day later, its first day un-named and the Prado's day renamed (by their new dates).
        val plan = runner.run(call("set_trip", """{"startDate":"2026-11-13","endDate":"2026-11-20","moveItemsWithDates":true,
            "dayNames":[{"date":"2026-11-13","name":""},{"date":"2026-11-15","name":"Museums"}]}"""), PendingPlan()).pending
        val layout = DraftLayout.of(plan.toDraft(), named, ctx.items)
        assertEquals(listOf(Triple(1, "Arrival", null), Triple(3, "Prado and tapas", "Museums")), layout.dayNames.map { Triple(it.number, it.before, it.name) })
        val r = DraftApplier.apply(layout, named, ctx.items, emptySet(), "draft", 100) { "x" }
        assertEquals(mapOf(d12.plusDays(3) to "Museums"), DayNames.all(r.trip.travelModes))
        assertEquals(d12.plusDays(3), r.movedItems.single { it.id == "p" }.day)
        // Only the dates ticked: the names the trip had move with its plan.
        val moved = DraftApplier.apply(layout, named, ctx.items, layout.dayNames.map { it.key }.toSet(), "draft", 100) { "x" }
        assertEquals(mapOf(d12.plusDays(1) to "Arrival", d12.plusDays(3) to "Prado and tapas"), DayNames.all(moved.trip.travelModes))
        assertEquals(0, moved.namedDays)
        // A name the day has already is nothing to review.
        val same = runner.run(call("set_trip", """{"dayNames":[{"date":"2026-11-12","name":"Arrival"}]}"""), PendingPlan()).pending
        assertTrue(DraftLayout.of(same.toDraft(), named, ctx.items).dayNames.isEmpty())
        // A trip without dates has no days to name.
        val undated = ToolRunner(PlanContext(trip.copy(startDate = null, endDate = null), emptyList(), today), noPlaces)
        assertTrue(undated.run(call("set_trip", """{"dayNames":[{"date":"2026-11-12","name":"Arrival"}]}"""), PendingPlan()).result.toString().contains("no dates yet"))
    }

    @Test fun theAiSetsBothTimesAndTheLastDay() = runBlocking {
        // Owner, 2026-11-12: "Yandel is 3 hours long, starts at 19:00"; a hotel for 3 nights.
        val runner = ToolRunner(ctx, noPlaces)
        val out = runner.run(call("add_items", """{"items":[
            {"date":"2026-11-13","time":"19:00","endTime":"22:00","kind":"activity","title":"Yandel Sinfónico","placeName":"Madrid","lat":40.42,"lng":-3.70},
            {"date":"2026-11-15","time":"15:00","endDate":"2026-11-18","endTime":"11:00","kind":"stay","title":"Suspiro 201 Suite","lat":37.17,"lng":-3.59},
            {"date":"2026-11-15","endDate":"2026-11-14","kind":"activity","title":"Backwards"},
            {"date":"2026-11-16","endTime":"25:00","kind":"activity","title":"Bad time"}
        ]}"""), PendingPlan())
        assertEquals(listOf("22:00", "11:00"), out.pending.added.map { it.item.endTime })
        assertEquals(listOf(null, "2026-11-18"), out.pending.added.map { it.item.endDate })
        val problems = out.result.getValue("problems").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(problems.any { "before date" in it })
        assertTrue(problems.any { "endTime" in it })
        // Saved as the plan keeps them: days after the item's day, minutes.
        val layout = DraftLayout.of(out.pending.toDraft(), trip, ctx.items)
        var n = 0
        val r = DraftApplier.apply(layout, trip, ctx.items, emptySet(), "draft", 100) { "id${++n}" }
        val yandel = r.items.single { it.title == "Yandel Sinfónico" }
        assertEquals(null, yandel.endDays); assertEquals(22 * 60, yandel.endMinutes)
        val suspiro = r.items.single { it.title == "Suspiro 201 Suite" }
        assertEquals(3, suspiro.endDays); assertEquals(11 * 60, suspiro.endMinutes)
        // A later change: a longer stay, then no end at all.
        val longer = runner.run(call("update_items", """{"updates":[{"itemId":"h","endDate":"2026-11-15","endTime":"12:00"}]}"""), PendingPlan()).pending
        assertEquals("2026-11-15", longer.updates.getValue("h").endDate)
        val cleared = runner.run(call("update_items", """{"updates":[{"itemId":"h","endDate":""}]}"""), longer).pending
        assertEquals("", cleared.updates.getValue("h").endDate)
        assertEquals("12:00", cleared.updates.getValue("h").endTime)
    }
}
