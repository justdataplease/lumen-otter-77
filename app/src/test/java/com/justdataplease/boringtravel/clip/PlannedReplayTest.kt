package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class PlannedReplayTest {
    private val day = LocalDate.of(2035, 7, 3)
    private val zone = ZoneId.of("Europe/Athens")
    private val trip = Trip("future", "Next summer", startDate = day, endDate = day.plusDays(3), createdAt = 1, updatedAt = 1)

    private fun place(id: String, index: Int, date: LocalDate? = day) = PlanItem(
        id = id, tripId = trip.id, day = date, position = index.toDouble(), title = "Place $id",
        lat = 37.0 + index * 0.1, lng = 25.0 + index * 0.1, createdAt = 1, updatedAt = 1,
    )

    private fun sources(items: List<PlanItem>, points: List<TrackPoint> = emptyList(), stops: List<Stop> = emptyList()) =
        ClipSources.available(trip, points, stops, items, emptyList(), zone)

    @Test fun scheduledDaysInTimeOrderComeBeforeUndatedIdeas() {
        // Times decide within a day (owner, 2026-11-12), whatever order the positions say.
        val evening = place("evening", 1).copy(timeMinutes = 18 * 60)
        val morning = place("morning", 2).copy(timeMinutes = 8 * 60)
        val next = place("next", 0, day.plusDays(1))
        val idea = place("idea", 3, null)
        val result = PlannedReplay.build(trip, listOf(idea, next, morning, evening))
        assertEquals(listOf("morning", "evening", "next", "idea"), result.waypoints.map { it.itemId })
        assertEquals(1, result.unscheduledPlaces)
        assertEquals(0, result.missingLocations)
    }

    @Test fun theOrderComesFromTheWholeDayNotOnlyThePinnedItems() {
        // Lunch has a time but no pin; the museum has a pin but no time. The day reads hotel, museum, lunch, as the
        // List shows it, so the legs between pinned places agree with the rows.
        val lunch = place("lunch", 1).copy(timeMinutes = 13 * 60, lat = null, lng = null)
        val museum = place("museum", 2)
        val hotel = place("hotel", 3).copy(timeMinutes = 9 * 60)
        assertEquals(listOf("hotel", "museum"), PlannedReplay.build(trip, listOf(lunch, museum, hotel)).waypoints.map { it.itemId })
    }

    @Test fun equalPositionsUseTimeThenStableId() {
        val a = place("a", 0).copy(lat = 37.1, timeMinutes = 600)
        val b = place("b", 0).copy(lat = 37.2, timeMinutes = 600)
        val c = place("c", 0).copy(lat = 37.3, timeMinutes = 700)
        assertEquals(listOf("a", "b", "c"), PlannedReplay.build(trip, listOf(c, b, a)).waypoints.map { it.itemId })
    }

    @Test fun adjacentDuplicatePlacesCollapseButLaterReturnIsPreserved() {
        val a = place("a", 0)
        val duplicate = a.copy(id = "duplicate", position = 1.0, lat = requireNotNull(a.lat) + 0.000001)
        val b = place("b", 2)
        val back = a.copy(id = "back", position = 3.0)
        val result = PlannedReplay.build(trip, listOf(back, duplicate, b, a))
        assertEquals(listOf("a", "b", "back"), result.waypoints.map { it.itemId })
        assertEquals(0, result.missingLocations)
    }

    @Test fun missingAndUnsupportedCoordinatesAreExcludedWithoutGuessingLocations() {
        val items = listOf(
            place("valid", 0), place("missing", 1).copy(lat = null), place("partial", 2).copy(lng = null),
            place("nan", 3).copy(lat = Double.NaN), place("infinite", 4).copy(lng = Double.POSITIVE_INFINITY),
            place("pole", 5).copy(lat = 89.0), place("longitude", 6).copy(lng = 181.0),
            place("deleted", 7).copy(deletedAt = 2), place("other-trip", 8).copy(tripId = "other"),
        )
        val result = PlannedReplay.build(trip, items)
        assertEquals(listOf("valid"), result.waypoints.map { it.itemId })
        assertEquals(6, result.missingLocations)
        assertTrue(sources(items).isEmpty())
    }

    @Test fun repeatedSinglePlaceCannotMakeAReplay() {
        val a = place("a", 0)
        assertTrue(sources(listOf(a, a.copy(id = "b", position = 1.0))).isEmpty())
    }

    @Test fun futureTripNeedsNoGpsAndRetainsEveryWaypointInBothCameraModes() {
        // Collinear place B must survive thinning, and choosing Plane must not invent an arc.
        val places = listOf(place("a", 0), place("b", 1), place("c", 4))
        val input = sources(places).getValue(ClipSource.Planned)
        assertTrue(input.points.isEmpty())
        assertEquals(places.map { it.title }, input.stops.map { it.name })
        for (view in ClipView.entries) {
            val plan = requireNotNull(ClipPlan.build(input, ClipOptions(view = view, vehicle = Vehicle.Plane), Locale.ENGLISH))
            assertEquals(ClipSource.Planned, plan.source)
            assertEquals(places.size, plan.xs.size)
            places.forEachIndexed { i, place ->
                assertEquals(Mercator.x(requireNotNull(place.lng)), plan.xs[i], 1e-12)
                assertEquals(Mercator.y(requireNotNull(place.lat)), plan.ys[i], 1e-12)
            }
            // No "Planned route" text: each place's own name, with its day, while the head is nearest to it.
            assertTrue(plan.days.none { it.label.contains("Planned") })
            val names = (0 until plan.frameCount).mapNotNull { plan.frame(it).place }.distinct()
            assertEquals(places.map { ClipLabel("Day 1", it.title) }, names)
            assertFalse(plan.endCard.dates.orEmpty().contains("2000"))
            assertTrue(plan.endCard.stats.contains("3 places"))
            assertTrue(plan.endCard.stats.endsWith("approx."))
            assertTrue(plan.projection(plan.drawStart + 60).tiltDegrees > 0 || view == ClipView.Flat)
            val footprint = plan.layerSpecs().first().rect
            assertTrue(plan.xs.indices.all { footprint.contains(plan.xs[it], plan.ys[it]) })
        }
    }

    @Test fun plannedAutoNeverInfersAPlaneFromInternalAnimationSeconds() {
        // 300 km in one animation second must not read as a flight: an unchosen leg is the plan's default, by car.
        val input = sources(listOf(place("a", 0), place("b", 20))).getValue(ClipSource.Planned)
        val plan = requireNotNull(ClipPlan.build(input, ClipOptions(vehicle = Vehicle.Auto)))
        assertEquals(listOf(Vehicle.Car), plan.vehiclesUsed)
    }

    @Test fun sourcesPreferUsableRecordingThenPlanThenSavedStops() {
        val points = listOf(
            TrackPoint(tripId = trip.id, time = 1_000, lat = 37.0, lng = 25.0),
            TrackPoint(tripId = trip.id, time = 60_000, lat = 37.1, lng = 25.1),
        )
        val stops = points.mapIndexed { i, point -> Stop(
            id = "stop-$i", tripId = trip.id, arrivedAt = point.time, lat = point.lat, lng = point.lng,
            createdAt = 1, updatedAt = 1,
        ) }
        val items = listOf(place("a", 0), place("b", 1))
        assertEquals(listOf(ClipSource.Planned, ClipSource.Recorded, ClipSource.Stops), sources(items, points, stops).keys.toList()) // the trip plan first: the clip's default route
        assertEquals(ClipSource.Planned, sources(items, points.map { it.copy(accuracyM = 1_000f) }, stops).keys.first())
        val fallback = sources(emptyList(), emptyList(), stops)
        assertEquals(listOf(ClipSource.Stops), fallback.keys.toList())
        assertNotNull(ClipPlan.build(fallback.getValue(ClipSource.Stops), ClipOptions(view = ClipView.Perspective)))
    }

    @Test fun anItemEndingOnAnotherDayIsAStopThereToo() {
        // Owner, 2026-11-12: the end "will be available in clip": the hotel again on the check-out morning.
        val hotel = place("hotel", 1).copy(kind = "stay", timeMinutes = 20 * 60, endDays = 2, endMinutes = 11 * 60)
        val museum = place("museum", 2, day.plusDays(1))
        val beach = place("beach", 3, day.plusDays(2)).copy(timeMinutes = 15 * 60)
        val replay = PlannedReplay.build(trip, listOf(beach, museum, hotel))
        assertEquals(listOf("hotel", "museum", "hotel~end", "beach"), replay.waypoints.map { it.itemId })
        val end = replay.waypoints[2]
        assertEquals(day.plusDays(2), end.day)
        assertEquals(hotel.lat!!, end.lat, 1e-9)
        assertEquals("the start carries how long it lasts", (2 * 24 + 11 - 20).toDouble(), replay.waypoints[0].hours!!, 1e-9)
        assertEquals(null, end.hours)
        // Switched off in the item: no stop at the end.
        assertEquals(listOf("hotel", "museum", "beach"), PlannedReplay.build(trip, listOf(beach, museum, hotel.copy(endRow = false))).waypoints.map { it.itemId })
    }
}
