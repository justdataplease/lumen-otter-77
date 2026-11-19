package com.justdataplease.boringtravel.ai.tools

import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.DraftEdits
import com.justdataplease.boringtravel.ai.DraftItem
import com.justdataplease.boringtravel.ai.DraftUpdate
import com.justdataplease.boringtravel.ai.PlanRequest
import com.justdataplease.boringtravel.ai.PlanResult
import com.justdataplease.boringtravel.ai.PlannerEngine
import com.justdataplease.boringtravel.ai.PlannerProcess
import com.justdataplease.boringtravel.ai.PlannerRuns
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.links.MapsList
import com.justdataplease.boringtravel.links.MapsListPlace
import com.justdataplease.boringtravel.places.PlaceResult
import kotlinx.coroutines.delay
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
 * Owner, 17 Nov 2026: "import a trip from a Google Maps user list", and the planner's tools looked at again. A list in
 * a message, the add_maps_list tool, and the planner bugs found in that review.
 */
class MapsListToolsTest {
    private val today = LocalDate.of(2026, 11, 17)
    private val d = LocalDate.of(2026, 12, 10)
    private val trip = Trip("t", "Andalusia", startDate = d, endDate = d.plusDays(3), createdAt = 1, updatedAt = 1)
    private val sol = PlanItem("s", "t", day = d, position = 1.0, kind = "stay", title = "Stay in Santa Cruz", lat = 37.386, lng = -5.991, createdAt = 1, updatedAt = 1)
    private val ctx = PlanContext(trip, listOf(sol), today)
    private val link = "https://maps.app.goo.gl/4XbhDugkXP74C4Vu7?g_st=ac"
    private val seville = MapsList(
        "abc", "Seville", "",
        listOf(
            MapsListPlace("Plaza de España", 37.3772, -5.9869, "Plaza de España, Sevilla", mapsUrl = "https://maps.google.com/?cid=1"),
            MapsListPlace("Bodega Santa Cruz", 37.3863, -5.9915, "C. Rodrigo Caro, 1, Sevilla", "Montaditos at the bar", "https://maps.google.com/?cid=2"),
            MapsListPlace("Real Alcázar", 37.3831, -5.9902, "Patio de Banderas, Sevilla", mapsUrl = "https://maps.google.com/?cid=3"),
            MapsListPlace("Mezquita-Catedral", 37.8789, -4.7794, "C. Cardenal Herrero, 1, Córdoba", mapsUrl = "https://maps.google.com/?cid=4"),
        ),
        total = 4,
    )
    private val lists = MapsListLookup { url -> if (url.startsWith("https://maps.app.goo.gl/")) seville else null }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun call(name: String, args: String) = ToolCall(name, json(args))

    /** An engine that must not be asked, or that keeps the request it was asked with. */
    private class Engine(private val allowed: Boolean) : PlannerEngine {
        var request: PlanRequest? = null
        override val isAvailable = true
        override val modelName = "test"
        override suspend fun plan(request: PlanRequest, onStep: (String) -> Unit): PlanResult {
            check(allowed) { "the model was asked" }
            this.request = request
            return PlanResult(request.pending, "", emptyList())
        }
    }

    @Test fun aListAloneBecomesANewTripsIdeasWithoutAModel() = runBlocking {
        val out = PlannerProcess(null, lists = lists).run(Engine(allowed = false), null, emptyList(), PendingPlan(), emptyList<ChatTurn>(), link, today)
        assertTrue(out.byApp)
        assertTrue(out.ready)
        assertEquals("Seville", out.pending.trip.title)
        assertEquals(listOf("Plaza de España", "Bodega Santa Cruz", "Real Alcázar", "Mezquita-Catedral"), out.pending.added.map { it.item.title })
        assertTrue(out.pending.added.all { it.date == null && it.item.lat != null })
        val bodega = out.pending.added[1].item
        assertEquals("food", bodega.kind)
        assertEquals("Montaditos at the bar", bodega.note)
        assertEquals("https://maps.google.com/?cid=2", bodega.url)
        assertTrue(out.reply.startsWith("Added 4 places to your ideas"))
        // In a trip that has some of them: those are said, not added again.
        val saved = sol.copy(id = "p", title = "Plaza de España", kind = "place")
        val again = PlannerProcess(null, lists = lists).run(Engine(allowed = false), trip, listOf(sol, saved), PendingPlan(), emptyList<ChatTurn>(), link, today)
        assertEquals(3, again.pending.added.size)
        assertNull(again.pending.trip.title)
        assertTrue(again.reply.endsWith("Plaza de España is already in your plan."))
    }

    @Test fun withWordsTheModelGetsTheListAndAddsItInOneCall() = runBlocking {
        val engine = Engine(allowed = true)
        val out = PlannerProcess(null, lists = lists).run(engine, trip, listOf(sol), PendingPlan(), emptyList<ChatTurn>(), "add these to the 11th $link", today)
        assertFalse(out.byApp)
        val said = (engine.request!!.turns().last() as LoopTurn.Traveller).text
        assertTrue(said.contains("a Google Maps list \"Seville\" with 4 places"))
        assertTrue(said.contains("call ${PlannerTools.ADD_MAPS_LIST}"))
        assertTrue(said.contains("2. Bodega Santa Cruz (37.3863, -5.9915) · note: Montaditos at the bar"))
        // The model's one call: every place, on the day asked, with each pending id for later calls.
        val runner = engine.request!!.runner()
        val added = runner.run(call(PlannerTools.ADD_MAPS_LIST, """{"url":"$link","date":"2026-12-11"}"""), PendingPlan())
        assertEquals(4, added.pending.added.size)
        assertTrue(added.pending.added.all { it.date == d.plusDays(1) })
        assertEquals(4, added.result.getValue("added").jsonPrimitive.content.toInt())
        val ids = added.result.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf("new-1", "new-2", "new-3", "new-4"), ids)
        // Giving them days afterwards works on those ids.
        val moved = runner.run(call(PlannerTools.UPDATE_ITEMS, """{"updates":[{"itemId":"new-4","date":"2026-12-12"}]}"""), added.pending)
        assertEquals(d.plusDays(2), moved.pending.added.single { it.id == "new-4" }.date)
        // A list the model names by another spelling of its link: the one list there is.
        val respelled = runner.run(call(PlannerTools.ADD_MAPS_LIST, """{"url":"maps.app.goo.gl/4XbhDugkXP74C4Vu7"}"""), PendingPlan())
        assertEquals(4, respelled.pending.added.size)
        // read_link on the list answers with its places.
        val read = runner.run(call(PlannerTools.READ_LINK, """{"url":"$link"}"""), PendingPlan()).result
        assertEquals("Seville", read.getValue("mapsList").jsonPrimitive.content)
        assertEquals(4, read.getValue("places").jsonArray.size)
    }

    @Test fun noListNoUnaskedListAndAListThatCouldNotBeRead() = runBlocking {
        val none = ToolRunner(ctx, null).run(call(PlannerTools.ADD_MAPS_LIST, """{"url":"$link"}"""), PendingPlan())
        assertTrue(none.result.getValue("problems").jsonArray.single().jsonPrimitive.content.contains("no Google Maps list"))
        val unread = ToolRunner(ctx, null, found = listOf(LinkFound(link, null, null, null, listUnread = true)))
            .run(call(PlannerTools.ADD_MAPS_LIST, """{"url":"$link"}"""), PendingPlan())
        assertTrue(unread.result.getValue("problems").jsonArray.single().jsonPrimitive.content.contains("couldn't read"))
        // One answer with every tool filled in empty (strict schemas): the list isn't added unasked.
        val found = listOf(LinkFound(link, "Seville", null, null, list = seville))
        val runner = ToolRunner(ctx, null, found = found)
        val root = json("""{"add_maps_list":{"url":"","date":""},"ask_traveller":{"where":null,"when":""},"finish":{"summary":"Nothing to change."}}""")
        val result = PlannerRuns.carry(root, runner, PendingPlan())
        assertTrue(result.pending.added.isEmpty())
        assertNull(result.action)
        assertEquals("Nothing to change.", result.reply)
    }

    // ---- the review's findings ----

    @Test fun aReplacedNoteThenAnAdditionIsOneNote() {
        val first = PendingPlan().withUpdate(DraftUpdate(itemId = "s", note = "Tickets 11:00"))
        val second = first.withUpdate(DraftUpdate(itemId = "s", noteAppend = "Booking JMKXT"))
        val u = second.updates.getValue("s")
        assertEquals("Tickets 11:00\nBooking JMKXT", u.note)
        assertNull(u.noteAppend)
        // The review takes it (a change that both replaces and adds was refused, so Save stayed off).
        assertTrue(DraftEdits.preview(listOf(u), trip, listOf(sol)).problems.isEmpty())
        // Two additions add up.
        val adds = PendingPlan().withUpdate(DraftUpdate(itemId = "s", noteAppend = "A")).withUpdate(DraftUpdate(itemId = "s", noteAppend = "B"))
        assertEquals("A\nB", adds.updates.getValue("s").noteAppend)
    }

    @Test fun anotherPlaceKeepsNoPinOfTheOldOne() {
        val first = PendingPlan().withUpdate(DraftUpdate(itemId = "s", title = "Hotel Lisboa Plaza", placeName = "Hotel Lisboa Plaza, Lisbon", lat = 38.72, lng = -9.14))
        val second = first.withUpdate(DraftUpdate(itemId = "s", title = "Memmo Alfama", placeName = "Memmo Alfama, Lisbon"))
        assertNull(second.updates.getValue("s").lat)
        // A saved item that becomes another place the map didn't find: no pin rather than the old place's.
        val after = DraftEdits.preview(listOf(DraftUpdate(itemId = "s", title = "Hotel Triana", placeName = "Hotel Triana, Sevilla")), trip, listOf(sol)).updates.single().after
        assertNull(after.lat)
        // The same place written out more fully keeps its pin.
        val fuller = DraftEdits.preview(listOf(DraftUpdate(itemId = "s", placeName = "Barrio Santa Cruz, Sevilla, Spain")), trip, listOf(sol)).updates.single().after
        assertEquals(37.386, fuller.lat!!, 1e-9)
    }

    @Test fun aTripMovedByItsStartDateOnly() = runBlocking {
        val out = ToolRunner(ctx, null).run(call(PlannerTools.SET_TRIP, """{"startDate":"2026-12-20","moveItemsWithDates":true,"dayNames":[{"date":"2026-12-22","name":"Córdoba"}]}"""), PendingPlan())
        assertFalse(out.result.containsKey("problems"))
        assertEquals("2026-12-20 to 2026-12-23", out.result.getValue("tripDates").jsonPrimitive.content)
        assertEquals("Córdoba", out.pending.trip.dayNames.getValue(LocalDate.of(2026, 12, 22)))
        // Without moving the plan, a start after the end is still a mistake.
        val wrong = ToolRunner(ctx, null).run(call(PlannerTools.SET_TRIP, """{"startDate":"2026-12-20"}"""), PendingPlan())
        assertTrue(wrong.result.containsKey("problems"))
    }

    @Test fun theSameTitleOnTwoDaysOfATripWithoutDates() = runBlocking {
        val bare = PlanContext(null, emptyList(), today)
        val out = ToolRunner(bare, null).run(
            call(PlannerTools.ADD_ITEMS, """{"items":[{"kind":"food","title":"Dinner by the harbour","dayIndex":1},{"kind":"food","title":"Dinner by the harbour","dayIndex":3}]}"""),
            PendingPlan(),
        )
        assertEquals(listOf(1, 3), out.pending.added.map { it.dayIndex })
    }

    @Test fun anEmptyLinkTakesAPendingItemsLinkAway() = runBlocking {
        val runner = ToolRunner(ctx, null)
        val added = runner.run(call(PlannerTools.ADD_ITEMS, """{"items":[{"kind":"food","title":"Eslava","url":"https://espacioeslava.com"}]}"""), PendingPlan())
        val cleared = runner.run(call(PlannerTools.UPDATE_ITEMS, """{"updates":[{"itemId":"new-1","url":""}]}"""), added.pending)
        assertNull(cleared.pending.added.single().item.url)
    }

    @Test fun aHotelFromTheTravellersOwnLinkIsTheirs() = runBlocking {
        val found = listOf(LinkFound(link, "Memmo Alfama Hotel", 38.711, -9.13))
        val runner = ToolRunner(ctx, null, travellerWords = "We stay here 11-13 Dec $link", found = found)
        val out = runner.run(call(PlannerTools.ADD_ITEMS, """{"items":[{"kind":"stay","title":"Memmo Alfama Hotel","date":"2026-12-11"}]}"""), PendingPlan())
        assertFalse(out.result.containsKey("problems"))
        assertEquals(1, out.pending.added.size)
        // One the traveller never gave is still refused.
        val invented = runner.run(call(PlannerTools.ADD_ITEMS, """{"items":[{"kind":"stay","title":"Hotel Alfonso XIII","date":"2026-12-11"}]}"""), PendingPlan())
        assertTrue(invented.result.containsKey("problems"))
    }

    @Test fun aSlowMapKeepsThePinsFoundInTime() = runBlocking {
        val pending = PendingPlan(
            added = listOf(
                PendingItem("new-1", d, null, DraftItem(kind = "place", title = "Real Alcázar", placeName = "Real Alcázar, Sevilla")),
                PendingItem("new-2", d, null, DraftItem(kind = "place", title = "Slow Place", placeName = "Slow Place, Sevilla")),
            ),
        )
        val lookup = PlaceLookup { q, _ ->
            if (q.startsWith("Slow")) delay(5_000)
            listOf(PlaceResult("Real Alcázar", "Real Alcázar, Sevilla", 37.3831, -5.9902))
        }
        val located = PendingPlaces.locate(pending, ctx, lookup, budgetMs = 800)
        assertEquals(37.3831, located.added[0].item.lat!!, 1e-9)
        assertNull(located.added[1].item.lat)
    }

    @Test fun aNewMessageStartsWithoutTheLastReplyOrQuestions() = runBlocking {
        val engine = Engine(allowed = true)
        val before = PendingPlan(summary = "Old reply.", questions = listOf("Which year?"))
        val out = PlannerProcess(null).run(engine, trip, listOf(sol), before, emptyList<ChatTurn>(), "2027", today)
        assertEquals("", engine.request!!.pending.summary)
        assertTrue(out.layout.questions.isEmpty())
        assertEquals("", out.reply)
    }
}
