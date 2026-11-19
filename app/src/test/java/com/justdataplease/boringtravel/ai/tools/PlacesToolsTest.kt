package com.justdataplease.boringtravel.ai.tools

import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.PlanRequest
import com.justdataplease.boringtravel.ai.PlanResult
import com.justdataplease.boringtravel.ai.PlannerEngine
import com.justdataplease.boringtravel.ai.PlannerProcess
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.links.MapsPlace
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.LooseQueries
import com.justdataplease.boringtravel.places.NearTrip
import com.justdataplease.boringtravel.places.PlaceResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * Owner, 15 Nov 2026: the planner "does a lot of iterations and does not work with a Google Maps URL, where it should be
 * instant"; "by giving a name it did many iterations, stuck at finding a place"; "in Madrid I asked for a restaurant, it
 * found 4 and took the one in Barcelona". Places nearest the trip, searches that don't repeat, Maps links read by the app.
 */
class PlacesToolsTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val d = LocalDate.of(2026, 12, 10)
    private val trip = Trip("t", "Madrid", startDate = d, endDate = d.plusDays(3), createdAt = 1, updatedAt = 1)
    private val sol = PlanItem("s", "t", day = d, position = 1.0, kind = "stay", title = "Stay in Sol", lat = 40.4169, lng = -3.7035, createdAt = 1, updatedAt = 1)
    private val ctx = PlanContext(trip, listOf(sol), today)

    private val barcelona = PlaceResult("Casa Lucio", "Casa Lucio, Barcelona, Spain", 41.3874, 2.1686)
    private val madrid = PlaceResult("Casa Lucio", "Casa Lucio, Calle Cava Baja 35, Madrid, Spain", 40.4122, -3.7088)
    private val valencia = PlaceResult("Casa Lucio", "Casa Lucio, Valencia, Spain", 39.4699, -0.3763)
    private val toledo = PlaceResult("Casa Lucio", "Casa Lucio, Toledo, Spain", 39.8628, -4.0273)

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun call(name: String, args: String) = ToolCall(name, json(args))
    private fun found(result: JsonObject, n: Int = 0) = result.getValue("results").jsonArray[n].jsonObject

    @Test fun theNearestTownComesFirstTheSearchOrderInsideIt() {
        val anchors = listOf(LatLng(40.4169, -3.7035))
        // Photon's order: Barcelona, Valencia, Madrid, Toledo.
        val ranked = NearTrip.ranked(listOf(barcelona, valencia, madrid, toledo), anchors)
        assertEquals(listOf(madrid, toledo, barcelona, valencia), ranked)
        // Two in the same town keep the search's order (a street of the name doesn't beat the restaurant).
        val street = PlaceResult("Calle de Casa Lucio", "Madrid", 40.4170, -3.7036)
        assertEquals(listOf(madrid, street), NearTrip.ranked(listOf(madrid, street), anchors))
        // No trip pins: as found.
        assertEquals(listOf(barcelona, madrid), NearTrip.ranked(listOf(barcelona, madrid), emptyList()))
        // A Maps link's name searched near the trip takes the nearest within reach.
        assertEquals(madrid, LinkPlaces.choose(listOf(barcelona, madrid), LatLng(40.4169, -3.7035)))
    }

    @Test fun anAddressNobodyFindsIsSearchedLooserButNeverAsABareTown() = runBlocking {
        assertEquals(
            listOf("Mollafenari, Elizi, Bab-ı Ali Cd. 27, Fatih, İstanbul, Turkey", "Bab-ı Ali Cd. 27, Fatih, İstanbul, Turkey"),
            LooseQueries.of("Mollafenari, Elizi, Bab-ı Ali Cd. No:27, 34120 Fatih/İstanbul, Turkey"),
        )
        assertEquals(listOf("Calle de la Cava Baja 35, Madrid, Spain"), LooseQueries.of("Calle de la Cava Baja 35, 28005 Madrid, Spain"))
        // A name with its town: nothing looser that isn't a bare town.
        assertTrue(LooseQueries.of("Pandeli, Eminönü, Istanbul, Turkey").isEmpty())
        // A looser form's answer far from the trip doesn't count.
        val asked = ArrayList<String>()
        val street = PlaceResult("Bab-ı Ali Caddesi", "Fatih, İstanbul", 41.0085, 28.9735)
        val istanbul = listOf(LatLng(41.0086, 28.9802))
        val got = LooseQueries.search("Mollafenari, Elizi, Bab-ı Ali Cd. No:27, 34120 Fatih/İstanbul, Turkey", istanbul) { q ->
            asked += q
            if (q.startsWith("Bab-ı Ali")) listOf(street) else emptyList()
        }
        assertEquals(listOf(street), got)
        assertEquals(3, asked.size)
        assertTrue(LooseQueries.search("Calle de la Cava Baja 35, 28005 Madrid, Spain", istanbul) { listOf(madrid) }.isNotEmpty())
        assertTrue(LooseQueries.search("Calle de la Cava Baja 35, 28005 Madrid, Spain", istanbul) { q -> if (q.contains("28005")) emptyList() else listOf(madrid) }.isEmpty())
        // A name written with its kind and quarter: the name alone, near the trip, a venue of that name.
        val pandeli = PlaceResult("Pandeli", "Eminönü, Istanbul", 41.0171, 28.9712)
        assertEquals(listOf("Lhardy Restaurante", "Lhardy"), LooseQueries.names("Lhardy Restaurante"))
        assertEquals(listOf(pandeli), LooseQueries.search("Pandeli Restaurant, Spice Bazaar, Istanbul, Turkey", istanbul) { q -> if (q == "Pandeli") listOf(pandeli) else emptyList() })
        val sokak = PlaceResult("Pandeli Sokak", "Istanbul", 41.0, 28.97, area = true)
        assertTrue(LooseQueries.search("Pandeli Restaurant, Spice Bazaar, Istanbul, Turkey", istanbul) { q -> if (q.contains("Bazaar")) emptyList() else listOf(sokak) }.isEmpty())
    }

    @Test fun findPlacesPutsTheTripsTownFirstWithItsDistance() = runBlocking {
        val lookup = PlaceLookup { _, _ -> listOf(barcelona, valencia, madrid, toledo) }
        val out = ToolRunner(ctx, lookup).run(call("find_places", """{"queries":[{"query":"Casa Lucio"}]}"""), PendingPlan())
        val first = found(out.result).getValue("found").jsonArray[0].jsonObject
        assertTrue(first.getValue("address").jsonPrimitive.content.contains("Madrid"))
        assertTrue(first.getValue("kmFromTrip").jsonPrimitive.content.toDouble() < 2.0)
        assertEquals(3, found(out.result).getValue("found").jsonArray.size)
    }

    @Test fun aTripWithoutPinsIsFoundByItsRoute() = runBlocking {
        val bare = PlanContext(trip.copy(subtitle = "Madrid → Toledo"), emptyList(), today)
        val towns = mapOf("Madrid" to PlaceResult("Madrid", "Madrid, Spain", 40.4168, -3.7038), "Toledo" to PlaceResult("Toledo", "Toledo, Spain", 39.8628, -4.0273))
        val lookup = PlaceLookup { q, _ -> towns[q]?.let(::listOf) ?: listOf(barcelona, madrid) }
        assertEquals(2, TripAnchors(bare, lookup).of(PendingPlan()).size)
        val out = ToolRunner(bare, lookup).run(call("find_places", """{"queries":[{"query":"Casa Lucio"}]}"""), PendingPlan())
        assertTrue(found(out.result).getValue("found").jsonArray[0].jsonObject.getValue("address").jsonPrimitive.content.contains("Madrid"))
    }

    @Test fun nothingFoundSaysAddItByNameAndDontSearchAgain() = runBlocking {
        val out = ToolRunner(ctx, PlaceLookup { _, _ -> emptyList() }).run(call("find_places", """{"queries":[{"query":"Nowhere Bar"}]}"""), PendingPlan())
        assertEquals(ToolRunner.NOT_FOUND, found(out.result).getValue("next").jsonPrimitive.content)
        // Only far-away places: said so.
        val far = ToolRunner(ctx, PlaceLookup { _, _ -> listOf(barcelona) }).run(call("find_places", """{"queries":[{"query":"Casa Lucio"}]}"""), PendingPlan())
        assertEquals(ToolRunner.FAR_AWAY, found(far.result).getValue("next").jsonPrimitive.content)
    }

    @Test fun aPlaceIsSearchedOnceAndTwoSearchCallsAreTheMost() = runBlocking {
        var searches = 0
        val runner = ToolRunner(ctx, PlaceLookup { _, _ -> searches++; listOf(madrid) })
        runner.run(call("find_places", """{"queries":[{"query":"Casa Lucio"},{"query":"casa lucio"}]}"""), PendingPlan())
        assertEquals(1, searches)
        val again = runner.run(call("find_places", """{"queries":[{"query":"Casa Lucio"},{"query":"Botín"}]}"""), PendingPlan())
        assertEquals(2, searches)
        assertEquals(ToolRunner.SEARCHED_ALREADY, found(again.result, 0).getValue("again").jsonPrimitive.content)
        assertFalse(found(again.result, 1).containsKey("again"))
        val third = runner.run(call("find_places", """{"queries":[{"query":"Lhardy"}]}"""), PendingPlan())
        assertEquals(ToolRunner.ENOUGH_SEARCHING, third.result.getValue("problems").jsonArray.single().jsonPrimitive.content)
        assertEquals(2, searches)
    }

    @Test fun pinsAfterTheAnswerTakeTheTripsTown() = runBlocking {
        val pending = PendingPlan(added = listOf(PendingItem("new-1", d.plusDays(1), null, com.justdataplease.boringtravel.ai.DraftItem(kind = "food", title = "Dinner at Casa Lucio", placeName = "Casa Lucio"))))
        val located = PendingPlaces.locate(pending, ctx, PlaceLookup { _, _ -> listOf(barcelona, madrid) })
        assertEquals(madrid.lat, located.added.single().item.lat!!, 1e-9)
    }

    // ---- Google Maps links ----

    private val link = "https://maps.app.goo.gl/AbC123"
    private val tortoni = MapsPlace("Café Tortoni, Avenida de Mayo 825, Buenos Aires", -34.6087, -58.3788)

    @Test fun aMessageOfLinksAloneNeedsNoModel() {
        assertTrue(MessageLinks.onlyMapsLinks(link))
        assertTrue(MessageLinks.onlyMapsLinks("$link\n$link"))
        assertFalse(MessageLinks.onlyMapsLinks("Dinner here on the 4th $link"))
        assertFalse(MessageLinks.onlyMapsLinks("https://example.com/post"))
        val f = LinkFound(link, tortoni.name, tortoni.lat, tortoni.lng)
        assertEquals("Café Tortoni", f.title)
        assertTrue(MessageLinks.onlyPlaces(link, listOf(f)))
        // The Maps app shares the name beside the link.
        assertTrue(MessageLinks.onlyPlaces("Café Tortoni $link", listOf(f)))
        assertFalse(MessageLinks.onlyPlaces("Dinner here on the 4th $link", listOf(f)))
        // A link that couldn't be read goes to the model.
        assertFalse(MessageLinks.onlyPlaces(link, listOf(LinkFound(link, null, null, null))))
        // Google Docs are imports, not places: not read here.
        assertEquals(listOf(link), MessageLinks.links("$link https://docs.google.com/document/d/abc123def456ghi789/edit"))
    }

    /** An engine that must not be asked, or that keeps the request it was asked with. */
    private class Engine(private val allowed: Boolean) : PlannerEngine {
        var request: PlanRequest? = null
        override val isAvailable = true
        override val modelName = "test"
        override suspend fun plan(request: PlanRequest, onStep: (String) -> Unit): PlanResult {
            check(allowed) { "the model was asked" }
            this.request = request
            return PlanResult(request.pending, "Done.", emptyList())
        }
    }

    @Test fun linksAloneBecomeIdeasWithTheirPinsWithoutAModel() = runBlocking {
        val maps = MapsLookup { url, _ -> if (url == link) tortoni else null }
        val out = PlannerProcess(null, null, maps).run(Engine(allowed = false), trip, listOf(sol), PendingPlan(), emptyList<ChatTurn>(), link, today)
        assertTrue(out.byApp)
        assertTrue(out.ready)
        val item = out.pending.added.single()
        assertNull(item.date)
        assertEquals("Café Tortoni", item.item.title)
        assertEquals("food", item.item.kind)
        assertEquals(-34.6087, item.item.lat!!, 1e-9)
        assertEquals(link, item.item.url)
        assertTrue(out.reply.startsWith("Added Café Tortoni"))
        // The same link again: already pending.
        val again = PlannerProcess(null, null, maps).run(Engine(allowed = false), trip, listOf(sol), out.pending, emptyList<ChatTurn>(), link, today)
        assertEquals(1, again.pending.added.size)
        assertEquals("Café Tortoni is already in your plan.", again.reply)
        // A place the trip has on a day already (as the review counts it): not added, and said so.
        val saved = sol.copy(id = "c", title = "Café Tortoni", kind = "food")
        val onADay = PlannerProcess(null, null, maps).run(Engine(allowed = false), trip, listOf(sol, saved), PendingPlan(), emptyList<ChatTurn>(), link, today)
        assertTrue(onADay.pending.added.isEmpty())
        assertEquals("Café Tortoni is already in your plan.", onADay.reply)
    }

    @Test fun withOtherWordsTheModelGetsTheLinksPlace() = runBlocking {
        val maps = MapsLookup { _, _ -> tortoni }
        val engine = Engine(allowed = true)
        val out = PlannerProcess(null, null, maps).run(engine, trip, listOf(sol), PendingPlan(), emptyList<ChatTurn>(), "Coffee here on the 11th at 10:00 $link", today)
        assertFalse(out.byApp)
        val said = (engine.request!!.turns().last() as LoopTurn.Traveller).text
        assertTrue(said.contains("$link: Café Tortoni, Avenida de Mayo 825, Buenos Aires (-34.6087, -58.3788)"))
        // read_link on it answers with the place, not a refusal.
        val answer = engine.request!!.runner().run(call("read_link", """{"url":"$link"}"""), PendingPlan()).result
        assertEquals(-34.6087, answer.getValue("lat").jsonPrimitive.content.toDouble(), 1e-9)
    }

    // ---- websites ----

    private val site = "https://www.casalucio.es/"
    private val madridFood = madrid.copy(kind = com.justdataplease.boringtravel.model.PlanKind.Food)
    private val portoCity = PlaceResult("Porto", "Porto, Portugal", 41.1496, -8.6109, area = true)

    @Test fun aWebsiteIsNamedByItselfAndMatchedOnlyByTheSameName() {
        assertEquals(listOf("Casa Lucio", "Restaurante en Madrid"), MessageLinks.names(PageTitle("Casa Lucio | Restaurante en Madrid", "Casa Lucio")))
        assertEquals(listOf("Casa Lucio"), MessageLinks.names(PageTitle("Home - Casa Lucio", null)))
        assertTrue(MessageLinks.sameName("Casa Lucio", "CASA LUCIO"))
        assertTrue(MessageLinks.sameName("Café Tortoni", "Cafe Tortoni"))
        assertTrue(MessageLinks.sameName("Restaurante Botín", "Botín"))
        assertFalse(MessageLinks.sameName("Porto", "Four days in Porto"))
        assertFalse(MessageLinks.sameName("Bar", "Bar Pepe"))
    }

    @Test fun aPlacesOwnWebsiteAloneIsAddedAtOnceNearTheTrip() = runBlocking {
        val pages = PageLookup { PageTitle("Casa Lucio | Restaurante en Madrid", "Casa Lucio") }
        val out = PlannerProcess(PlaceLookup { _, _ -> listOf(barcelona, madridFood) }, null, null, pages)
            .run(Engine(allowed = false), trip, listOf(sol), PendingPlan(), emptyList<ChatTurn>(), site, today)
        assertTrue(out.byApp)
        val item = out.pending.added.single().item
        assertEquals("Casa Lucio", item.title)
        assertEquals("food", item.kind)
        assertEquals(madrid.lat, item.lat!!, 1e-9)
        assertEquals(site, item.url)
    }

    @Test fun aBlogPostOrAnUnknownPageGoesToTheModelWithItsTitle() = runBlocking {
        val pages = PageLookup { PageTitle("Four days in Porto | Nomad Notes", "Nomad Notes") }
        val engine = Engine(allowed = true)
        val post = "https://nomadnotes.example/four-days-in-porto"
        val out = PlannerProcess(PlaceLookup { _, _ -> listOf(portoCity) }, null, null, pages)
            .run(engine, trip, listOf(sol), PendingPlan(), emptyList<ChatTurn>(), post, today)
        assertFalse(out.byApp)
        val said = (engine.request!!.turns().last() as LoopTurn.Traveller).text
        assertTrue(said, said.contains("$post: a web page titled \"Four days in Porto | Nomad Notes\""))
        // A venue of the name, but in another town: not this trip's, so the model decides.
        val far = PlannerProcess(PlaceLookup { _, _ -> listOf(barcelona) }, null, null, PageLookup { PageTitle("Casa Lucio", null) })
            .run(Engine(allowed = true), trip, listOf(sol), PendingPlan(), emptyList<ChatTurn>(), site, today)
        assertFalse(far.byApp)
    }

    @Test fun aFinishBesideAFailedChangeIsSentBackOnce() = runBlocking {
        val chat = object : ToolChat {
            var n = 0
            override suspend fun next(system: String, turns: List<LoopTurn>, tools: List<PlannerTool>): ModelTurn = when (n++) {
                0 -> ModelTurn("", listOf(call("add_items", """{"items":[{"date":"11/12/2026","kind":"food","title":"Dinner"}]}"""), call("finish", """{"summary":"Added dinner."}""")))
                else -> ModelTurn("", listOf(call("add_items", """{"items":[{"date":"2026-12-11","kind":"food","title":"Dinner"}]}"""), call("finish", """{"summary":"Added dinner."}""")))
            }
        }
        val result = ToolLoop(chat).run("rules", listOf(LoopTurn.Traveller("dinner on the 11th")), ToolRunner(ctx, null), PendingPlan())
        assertTrue(result.finished)
        assertEquals(2, chat.n)
        assertEquals(LocalDate.of(2026, 12, 11), result.pending.added.single().date)
    }
}
