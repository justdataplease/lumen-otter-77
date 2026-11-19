package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.ai.tools.LinkFound
import com.justdataplease.boringtravel.ai.tools.MapsLookup
import com.justdataplease.boringtravel.ai.tools.MessageLinks
import com.justdataplease.boringtravel.ai.tools.PageLookup
import com.justdataplease.boringtravel.ai.tools.PageTitle
import com.justdataplease.boringtravel.ai.tools.PendingPlan
import com.justdataplease.boringtravel.ai.tools.PlaceLookup
import com.justdataplease.boringtravel.ai.tools.PlanContext
import com.justdataplease.boringtravel.ai.tools.PlannerTools
import com.justdataplease.boringtravel.ai.tools.ToolCall
import com.justdataplease.boringtravel.ai.tools.ToolOutcome
import com.justdataplease.boringtravel.ai.tools.ToolRunner
import com.justdataplease.boringtravel.ai.tools.TripAnchors
import com.justdataplease.boringtravel.ios.MapsLinks
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.PlaceResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The AI planner's tools as the model calls them (ai/tools/, read in): set_trip, add_items, update_items,
 * remove_items and finish on made-up JSON arguments, with their checks (past dates, repeats, coordinates, a time at
 * the front of a note, flight numbers and booking codes by regex with lookbehind and inline (?i:)); where the trip is
 * from its route's towns (\b in a split); and the links in a message read before any model is asked.
 */
class PlannerToolsSmokeTest {
    private val newTrip = PlanContext(null, emptyList(), Samples.today)

    private fun call(name: String, args: String) = ToolCall(name, Json.parseToJsonElement(args).jsonObject)

    private fun problems(o: ToolOutcome): List<String> = o.result["problems"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    @Test fun setTripNamesTheTripAndADay() = runTest {
        val runner = ToolRunner(newTrip, null)
        val o = runner.run(
            call(PlannerTools.SET_TRIP, """{"title":"Naxos, slowly","startDate":"2027-09-10","endDate":"2027-09-14","route":"Paros → Naxos","dayNames":[{"date":"2027-09-11","name":"Temples   and tea"}]}"""),
            PendingPlan(),
        )
        assertEquals(emptyList<String>(), problems(o))
        assertEquals("Naxos, slowly", o.pending.trip.title)
        assertEquals("2027-09-10", o.pending.trip.startDate?.toString())
        assertEquals("Paros → Naxos", o.pending.trip.route)
        assertEquals(listOf("2027-09-11" to "Temples and tea"), o.pending.trip.dayNames.map { (d, n) -> d.toString() to n })
        assertEquals("2027-09-10 to 2027-09-14", o.result["tripDates"]?.jsonPrimitive?.content)
        val wrong = runner.run(call(PlannerTools.SET_TRIP, """{"startDate":"2027-09-14","endDate":"2027-09-10"}"""), PendingPlan())
        assertEquals(listOf("endDate is before startDate."), problems(wrong))
        val notADate = runner.run(call(PlannerTools.SET_TRIP, """{"startDate":"14/09/2027"}"""), PendingPlan())
        assertEquals(listOf("startDate \"14/09/2027\" isn't a date. Use yyyy-MM-dd."), problems(notADate))
    }

    @Test fun addItemsChecksEveryItem() = runTest {
        val runner = ToolRunner(newTrip, null)
        val o = runner.run(
            call(
                PlannerTools.ADD_ITEMS,
                """{"items":[
                    {"date":"2027-09-10","kind":"ferry","title":"Ferry to Naxos","note":"07:25 · Blue Star","placeName":"Naxos port, Greece"},
                    {"title":"Portara","placeName":"Portara, Naxos","lat":37.1106,"lng":25.3713},
                    {"date":"2027-09-10","title":"ferry to NAXOS!"},
                    {"date":"2026-03-04","title":"Old date"},
                    {"title":"Bad pin","lat":95,"lng":10}
                ]}""",
            ),
            PendingPlan(),
        )
        assertEquals(listOf("new-1", "new-2"), o.pending.added.map { it.id })
        val ferry = o.pending.added[0]
        assertEquals("2027-09-10", ferry.date?.toString())
        assertEquals("07:25", ferry.item.time)
        assertEquals("transport", ferry.item.kind)
        assertNull(o.pending.added[1].date)
        assertEquals(37.1106, o.pending.added[1].item.lat)
        val added = o.result["added"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("new-1", "new-2"), added.map { it["id"]!!.jsonPrimitive.content })
        assertTrue(added[0].containsKey("map"))
        val p = problems(o)
        assertEquals(3, p.size, p.toString())
        assertTrue(p[0].startsWith("ferry to NAXOS! is already pending as new-1") && ToolRunner.isRepeat(p[0]), p[0])
        assertTrue(p[1].startsWith("Old date: 2026-03-04 is in the past (today is 2026-11-19). A date without a year is its next one: 2027-03-04."), p[1])
        assertTrue(p[2].startsWith("Bad pin: lat/lng ") && p[2].endsWith("isn't a place."), p[2])
    }

    @Test fun updateRemoveAndFinish() = runTest {
        val runner = ToolRunner(newTrip, null)
        val first = runner.run(
            call(PlannerTools.ADD_ITEMS, """{"items":[{"date":"2027-09-10","title":"Ferry to Naxos","note":"07:25 · Blue Star"},{"title":"Portara"}]}"""),
            PendingPlan(),
        ).pending
        val updated = runner.run(
            call(PlannerTools.UPDATE_ITEMS, """{"updates":[{"itemId":"new-1","time":"8:05","noteAppend":"Seat 41"},{"itemId":"nope","title":"x"}]}"""),
            first,
        )
        assertEquals("08:05", updated.pending.added[0].item.time)
        assertEquals("07:25 · Blue Star · Seat 41", updated.pending.added[0].item.note)
        assertEquals(listOf("No item has id \"nope\". Use the itemIds from the plan or the pending ids."), problems(updated))
        val removed = runner.run(call(PlannerTools.REMOVE_ITEMS, """{"itemIds":["new-2","zzz"]}"""), updated.pending)
        assertEquals(listOf("new-1"), removed.pending.added.map { it.id })
        assertEquals(listOf("No item has id \"zzz\"."), problems(removed))
        val done = runner.run(call(PlannerTools.FINISH, """{"summary":"Ferry   and Portara.","questions":["Which ferry?","Which ferry?"]}"""), removed.pending)
        assertTrue(done.finished)
        assertEquals("Ferry and Portara.", done.pending.summary)
        assertEquals(listOf("Which ferry?"), done.pending.questions)
        val draft = done.pending.toDraft()
        assertEquals(listOf("2027-09-10"), draft.days.map { it.date })
        assertEquals(1, draft.itemCount)
        val unknown = runner.run(ToolCall("fly_me_there", JsonObject(emptyMap())), PendingPlan())
        assertTrue(problems(unknown).single().startsWith("There is no tool called fly_me_there."))
    }

    @Test fun aStayNamedAsAHotelTheTravellerNeverGaveIsRefused() = runTest {
        val runner = ToolRunner(newTrip, null, travellerWords = "Plan three easy days on Naxos")
        val o = runner.run(
            call(PlannerTools.ADD_ITEMS, """{"items":[{"date":"2027-09-10","kind":"stay","title":"Hotel Aegean Breeze","placeName":"Chora, Naxos"},{"date":"2027-09-10","kind":"stay","title":"Stay in Chora","placeName":"Chora, Naxos"}]}"""),
            PendingPlan(),
        )
        assertEquals(listOf("Stay in Chora"), o.pending.added.map { it.item.title })
        assertTrue(problems(o).single().contains("plan where to stay, not which hotel or hostel"))
    }

    @Test fun flightNumbersAndBookingCodesNoItemHoldsYet() {
        val runner = ToolRunner(newTrip, null)
        val message = "Flight IB 3151 on the 10th, then A3 612 back. Booking Code: JMKXT, confirmation #88231. Seat 12C."
        assertEquals(listOf("IB 3151", "A3 612", "JMKXT", "88231"), runner.missingDetails(message, PendingPlan()))
        assertEquals(emptyList<String>(), runner.missingDetails("Just a quiet week on Naxos.", PendingPlan()))
    }

    @Test fun whereTheTripIsFromPinsOrItsRoutesTowns() = runTest {
        val towns = mapOf("Paros" to LatLng(37.08, 25.15), "Naxos" to LatLng(37.10, 25.37), "Ios" to LatLng(36.72, 25.28))
        val asked = ArrayList<String>()
        val lookup = PlaceLookup { query, _ ->
            asked += query
            listOfNotNull(towns[query]?.let { PlaceResult(query, "$query, Greece", it.lat, it.lng) })
        }
        val routed = Samples.trip().copy(subtitle = "Paros to Naxos and Ios · Toronto")
        val anchors = TripAnchors(PlanContext(routed, emptyList(), Samples.today), lookup).of(PendingPlan())
        assertEquals(listOf("Paros", "Naxos", "Ios", "Toronto"), asked)
        assertEquals(listOf(towns.getValue("Paros"), towns.getValue("Naxos"), towns.getValue("Ios")), anchors)
        val pinned = TripAnchors(PlanContext(Samples.trip(), Samples.plan(), Samples.today), lookup).of(PendingPlan())
        assertEquals(listOf(LatLng(37.1, 25.37)), pinned)
    }

    @Test fun linksInAMessageAreReadBeforeAnyModel() = runTest {
        val maps = "https://www.google.com/maps/place/Kafeneio+Lefkes/@37.0489,25.1960,17z"
        val site = "https://slowroads.example.com/naxos-guide"
        val message = "Dinner at $maps then see $site and https://docs.google.com/document/d/${Samples.DOC_ID}/edit"
        val links = MessageLinks.links(message)
        assertEquals(listOf(maps, site), links)
        val found = MessageLinks.read(
            links,
            maps = MapsLookup { url, _ -> MapsLinks.parse(url) },
            pages = PageLookup { PageTitle("Naxos in three days | Slow Roads", "Slow Roads") },
            places = null,
            trip = emptyList(),
        )
        assertEquals(LinkFound(maps, "Kafeneio Lefkes", 37.0489, 25.196, maps = true), found[0])
        assertEquals("Slow Roads", found[1].name)
        assertEquals("Naxos in three days | Slow Roads", found[1].page)
        assertFalse(found[1].located)
        val context = MessageLinks.context(found)
        assertTrue(context.contains("- $maps: Kafeneio Lefkes (37.0489, 25.196)"), context)
        assertTrue(context.contains("a web page titled \"Naxos in three days | Slow Roads\""), context)
        val places = MessageLinks.places(found)
        assertEquals(listOf("Kafeneio Lefkes"), places.map { it.title })
        assertEquals("place", places.single().item["kind"]?.jsonPrimitive?.content)
        assertEquals(PlannerTools.ADD_ITEMS, MessageLinks.addCall(places).name)
        assertEquals(listOf("Slow Roads", "Naxos in three days"), MessageLinks.names(PageTitle("Naxos in three days | Slow Roads", "Slow Roads")))
        assertTrue(MessageLinks.onlyPlaces("Kafeneio Lefkes\n$maps", found.take(1)))
        assertFalse(MessageLinks.onlyPlaces("Dinner at Kafeneio Lefkes $maps", found.take(1)))
        assertTrue(MessageLinks.onlyMapsLinks("$maps https://maps.app.goo.gl/AbCdEf123"))
        assertEquals("Added Portara to your ideas, with its pin. Tell me a day and time to plan it.", MessageLinks.reply(listOf("Portara"), emptyList()))
        assertEquals("Nothing added.", MessageLinks.reply(emptyList(), emptyList()))
    }
}
