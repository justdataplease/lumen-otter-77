package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.ai.tools.PlannerTools
import com.justdataplease.boringtravel.ai.tools.TravelBrief
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The planner's tools: names, arguments, and the one-answer schema made from them. */
class DraftSchemaTest {
    @Test fun toolsHaveUniqueNamesAndThreeWaysToEndATurn() {
        val names = PlannerTools.all.map { it.name }
        assertEquals(names.toSet().size, names.size)
        // finish replies; the trip questions and Copy from a link hand over to the traveller.
        assertEquals(listOf(PlannerTools.ASK_TRAVELLER, PlannerTools.COPY_NOTES, PlannerTools.FINISH), PlannerTools.all.filter { it.finishes }.map { it.name })
        assertEquals(listOf(PlannerTools.FIND_PLACES, PlannerTools.READ_LINK), PlannerTools.all.filter { it.lookup }.map { it.name })
        assertEquals(listOf(PlannerTools.FIND_PLACES), PlannerTools.all.filter { !it.oneAnswer }.map { it.name })
        PlannerTools.all.forEach { assertTrue(it.name, it.description.length in 40..600) }
    }

    @Test fun theNewToolsTakeWhatTheyNeed() {
        assertEquals(setOf("url"), PlannerTools.readLink.parameters.properties.keys - PlannerTools.readLink.parameters.optional.toSet())
        assertEquals(setOf("url"), PlannerTools.copyNotes.parameters.properties.keys - PlannerTools.copyNotes.parameters.optional.toSet())
        // Every trip question is optional: the model fills in only what the chat said.
        val ask = PlannerTools.askTraveller.parameters
        assertEquals(ask.properties.keys, ask.optional.toSet())
        assertEquals(TravelBrief.Pace.entries.map { it.id }, (ask.properties.getValue("pace") as SchemaNode.Choice).values)
        val interests = ask.properties.getValue("interests") as SchemaNode.Arr
        assertEquals(TravelBrief.Interest.entries.map { it.id }, (interests.items as SchemaNode.Choice).values)
    }

    @Test fun kindsAndTravelAreTheAppsOwn() {
        val kind = PlannerTools.newItem.properties.getValue("kind") as SchemaNode.Choice
        assertEquals(PlanKind.entries.map { it.id }, kind.values)
        val travel = PlannerTools.newItem.properties.getValue("travel") as SchemaNode.Choice
        assertTrue(travel.values.all { id -> Vehicle.entries.any { it.id == id && it != Vehicle.Auto } })
        assertEquals(6, travel.values.size)
    }

    @Test fun requiredArgumentsAreTheEssentials() {
        assertEquals(setOf("kind", "title"), PlannerTools.newItem.properties.keys - PlannerTools.newItem.optional.toSet())
        assertEquals(setOf("itemId"), PlannerTools.itemUpdate.properties.keys - PlannerTools.itemUpdate.optional.toSet())
        assertEquals(emptySet<String>(), PlannerTools.setTrip.parameters.properties.keys - PlannerTools.setTrip.parameters.optional.toSet())
        assertEquals(setOf("summary"), PlannerTools.finish.parameters.properties.keys - PlannerTools.finish.parameters.optional.toSet())
    }

    @Test fun oneAnswerHoldsEveryToolButTheMapSearchAndRequiresFinish() {
        val one = DraftSchema.generation
        assertEquals(PlannerTools.all.filter { it.oneAnswer }.map { it.name }, one.properties.keys.toList())
        assertTrue(PlannerTools.FIND_PLACES !in one.properties)
        assertEquals(one.properties.keys - PlannerTools.FINISH, one.optional.toSet())
        PlannerTools.all.filter { it.oneAnswer }.forEach { tool ->
            assertEquals(tool.parameters.properties, (one.properties.getValue(tool.name) as SchemaNode.Obj).properties)
        }
    }

    @Test fun parserReadsTheNewFields() {
        val text = """
            {"title": "Spain", "startDate": "2026-11-12", "endDate": "2026-11-19", "route": "Madrid → Granada", "moveItemsWithDates": "true",
             "removeItemIds": ["a", "a", " "], "summary": "Done.",
             "days": [{"date": "2026-11-12", "items": [{"time": "15:50", "kind": "flight", "title": "Fly to Madrid", "travel": "Flight", "url": "https://www.aena.es/"}]}],
             "updates": [{"itemId": "x", "kind": "hotel", "travel": "bus", "url": "javascript:alert(1)"}]}
        """.trimIndent()
        val d = TripDraftParser.parse(text)
        assertEquals("Madrid → Granada", d.route)
        assertEquals(true, d.moveItemsWithDates)
        assertEquals(listOf("a"), d.removeItemIds)
        val fly = d.days.single().items.single()
        assertEquals("transport", fly.kind)
        assertEquals("plane", fly.travel)
        assertEquals("https://www.aena.es/", fly.url)
        val u = d.updates.single()
        assertEquals("stay", u.kind)
        assertEquals("car", u.travel)
        assertEquals(null, u.url)
    }
}
