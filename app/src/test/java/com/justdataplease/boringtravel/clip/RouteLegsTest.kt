package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.Vehicle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RouteLegsTest {
    private val day = LocalDate.of(2030, 5, 1)
    private val trip = Trip("t", "Future journey", createdAt = 1, updatedAt = 1)
    private fun key(a: String, b: String, source: RouteLegSource = RouteLegSource.Planned) = RouteLegKey(source, a, b)
    private fun place(id: String, order: Int) = PlanItem(id, trip.id, day, order.toDouble(), title = id,
        lat = 37.0 + order * .1, lng = 25.0, createdAt = 1, updatedAt = 1)

    @Test fun legacyEmptyMalformedAndUnknownModesAreUnspecified() {
        for (raw in listOf(null, "", "{}", "{broken", "[]", """{"v":9,"legs":[]}""",
            """{"v":1,"legs":[{"source":"planned","from":"a","to":"b","vehicle":"hovercraft"}]}""")) {
            assertTrue("$raw", TravelModes.choices(raw).isEmpty())
        }
        assertTrue(RouteLegs.planned(trip, listOf(place("a", 0), place("b", 1))).all { it.vehicle == null })
    }

    @Test fun unrelatedAndTargetUnknownFieldsSurviveSelectionAndClearing() {
        val raw = """{"v":1,"future":{"keep":[1,2]},"legs":[{"source":"planned","from":"a","to":"b","vehicle":"walk","extra":"keep"},{"source":"future","from":"x","to":"y","vehicle":"rocket"}]}"""
        val changed = requireNotNull(TravelModes.set(raw, key("a", "b"), Vehicle.Plane))
        val before = Json.parseToJsonElement(raw).jsonObject
        val after = Json.parseToJsonElement(changed).jsonObject
        assertEquals(before["future"], after["future"])
        assertEquals(before.getValue("legs").jsonArray[1], after.getValue("legs").jsonArray[0])
        assertEquals("\"keep\"", after.getValue("legs").jsonArray.last().jsonObject["extra"].toString())
        assertEquals(Vehicle.Plane, TravelModes.choices(changed)[key("a", "b")])
        val cleared = requireNotNull(TravelModes.set(changed, key("a", "b"), Vehicle.Auto))
        assertTrue(TravelModes.choices(cleared).isEmpty())
        assertEquals(after["future"], Json.parseToJsonElement(cleared).jsonObject["future"])
        assertTrue(cleared.contains("extra"))
    }

    @Test fun unsupportedOrBrokenDataCannotBeOverwrittenByAnUnrelatedEdit() {
        for (raw in listOf("""{"v":2,"legs":[]}""", """{"v":1,"legs":{}}""", "{broken")) {
            assertTrue(runCatching { TravelModes.set(raw, key("a", "b"), Vehicle.Car) }.isFailure)
        }
    }

    @Test fun keyFieldsDoNotCollideAndSourceAndDirectionRemainDistinct() {
        val choices = linkedMapOf(key("a/b", "c") to Vehicle.Walk, key("a", "b/c") to Vehicle.Plane,
            key("c", "a/b") to Vehicle.Car, key("a/b", "c", RouteLegSource.Stops) to Vehicle.Train)
        val json = choices.entries.fold(null as String?) { raw, (key, vehicle) -> TravelModes.set(raw, key, vehicle) }
        assertEquals(choices, TravelModes.choices(json))
    }

    @Test fun lastDuplicateUnknownChoiceDoesNotResurrectAnOlderMode() {
        val raw = """{"legs":[{"source":"planned","from":"a","to":"b","vehicle":"walk"},{"source":"planned","from":"a","to":"b","vehicle":"future"}]}"""
        assertTrue(TravelModes.choices(raw).isEmpty())
        assertEquals(mapOf(key("a", "b") to Vehicle.Bike), TravelModes.choices(TravelModes.set(raw, key("a", "b"), Vehicle.Bike)))
    }

    @Test fun crossDayPairsUseOnlyOrderedLocatedDistinctActivePlaces() {
        val a = place("a", 0)
        val duplicate = a.copy(id = "duplicate", position = .5)
        val missing = place("missing", 1).copy(lat = null)
        val b = place("b", 2).copy(day = day.plusDays(1), position = 0.0)
        val deleted = place("deleted", 3).copy(deletedAt = 2)
        val result = RouteLegs.planned(trip, listOf(deleted, b, missing, duplicate, a))
        assertEquals(listOf(key("a", "b")), result.map { it.key })
        assertEquals(day, result.single().fromDay)
        assertEquals(day.plusDays(1), result.single().toDay)
    }

    @Test fun reorderingDeletingAndInsertingNeverTransfersChoicesToAnotherPair() {
        val original = listOf(place("a", 0), place("b", 1), place("c", 2), place("d", 3))
        val saved = trip.copy(travelModes = TravelModes.set(null, key("a", "b"), Vehicle.Plane))
        assertEquals(Vehicle.Plane, RouteLegs.planned(saved, original).first().vehicle)
        val reordered = original.map { if (it.id == "b") it.copy(position = 2.5) else it }
        assertTrue(RouteLegs.planned(saved, reordered).all { it.vehicle == null })
        assertTrue(RouteLegs.planned(saved, original.filterNot { it.id == "b" }).all { it.vehicle == null })
        val inserted = original + place("new", 8).copy(position = .5)
        assertTrue(RouteLegs.planned(saved, inserted).all { it.vehicle == null })
        assertEquals(Vehicle.Plane, RouteLegs.planned(saved, original.map { it.copy(title = "Renamed") }).first().vehicle)
    }

    @Test fun savedStopsUseStableTimestampTiesCollapseAdjacentDuplicatesAndKeepReturns() {
        fun stop(id: String, time: Long, lat: Double) = Stop(id, trip.id, time, lat = lat, lng = 25.0, name = id, createdAt = 1, updatedAt = 1)
        val a = stop("a", 1, 37.0)
        val b = stop("b", 1, 37.1)
        val stops = listOf(stop("return", 5, 37.0), b, stop("duplicate", 2, 37.1), a,
            stop("missing", 3, Double.NaN), stop("deleted", 4, 38.0).copy(deletedAt = 4))
        val result = RouteLegs.savedStops(trip, stops, ZoneId.of("UTC"))
        assertEquals(listOf(key("a", "b", RouteLegSource.Stops), key("b", "return", RouteLegSource.Stops)), result.map { it.key })
    }
}
