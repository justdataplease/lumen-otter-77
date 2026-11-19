package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LegVehiclePlanTest {
    private val trip = Trip("t", "Mixed journey", createdAt = 1, updatedAt = 1)
    private val zone = ZoneId.of("UTC")
    private val points = (0..3).map { i -> PlanItem("p$i", trip.id, LocalDate.of(2031, 1, 1), i.toDouble(), title = "Place $i",
        lat = 37.0 + i * .2, lng = 25.0 + i * .1, createdAt = 1, updatedAt = 1) }
    private val modes = listOf(Vehicle.Walk, Vehicle.Plane, Vehicle.Car)
    private fun mixedTrip(source: RouteLegSource = RouteLegSource.Planned): Trip {
        val raw = modes.foldIndexed(null as String?) { i, json, vehicle ->
            TravelModes.set(json, RouteLegKey(source, "p$i", "p${i + 1}"), vehicle)
        }
        return trip.copy(travelModes = raw)
    }
    private fun input(t: Trip = mixedTrip()) = ClipSources.available(t, emptyList(), emptyList(), points, emptyList(), zone).getValue(ClipSource.Planned)

    @Test fun mixedWalkPlaneCarAppearOnTheirOwnLegInPreviewAndExportFrames() {
        for (view in ClipView.entries) for (seconds in listOf(15, 30)) {
            val plan = requireNotNull(ClipPlan.build(input(), ClipOptions(view = view, seconds = seconds)))
            assertEquals(modes, plan.legVehicle)
            assertEquals(4, plan.xs.size) // A chosen plane still connects actual planned places without a fabricated arc.
            for (segment in modes.indices) {
                val frames = (plan.drawStart until plan.drawEnd).filter { plan.frame(it).headSeg == segment }
                assertTrue("segment $segment has frames", frames.isNotEmpty())
                assertEquals(modes[segment], plan.frame(frames[frames.size / 2]).vehicle)
                assertTrue(frames.all { plan.frame(it).vehicle == modes[segment] })
            }
        }
    }

    @Test fun missingChoicesTravelByCarEvenWhenAWholeTripOverrideWasPreviouslySelected() {
        // Unchosen planned legs travel as the plan shows them: by car (owner, 2026-11-07), never the clip-wide override.
        for (override in Vehicle.entries) {
            val plan = requireNotNull(ClipPlan.build(input(trip), ClipOptions(vehicle = override)))
            assertEquals(listOf(Vehicle.Car, Vehicle.Car, Vehicle.Car), plan.legVehicle)
        }
    }

    @Test fun choiceChangesRetainMapGeometryAndLookupDoesNotDependOnChoiceListOrder() {
        val source = input()
        val initial = requireNotNull(ClipPlan.build(source, ClipOptions(view = ClipView.Perspective)))
        val reversed = requireNotNull(ClipPlan.build(source.copy(routeLegs = source.routeLegs.reversed()), initial.options))
        assertEquals(modes, reversed.legVehicle)
        val updated = mixedTrip().copy(travelModes = TravelModes.set(mixedTrip().travelModes, source.routeLegs[1].key, Vehicle.Ferry))
        val revised = requireNotNull(ClipPlan.build(source.copy(routeLegs = RouteLegs.withChoices(updated, source.routeLegs)), initial.options))
        assertEquals(listOf(Vehicle.Walk, Vehicle.Ferry, Vehicle.Car), revised.legVehicle)
        // The route and its overview keep their shape; only the timing (and so the camera and its closer map
        // pictures) follows the new vehicle's speed.
        assertTrue(initial.xs.contentEquals(revised.xs) && initial.ys.contentEquals(revised.ys))
        assertEquals(initial.overview, revised.overview)
    }

    @Test fun equalTimeSavedStopsRetainTheCorrectPairsAndDoNotUsePlannedChoices() {
        val stops = points.map { Stop(it.id, trip.id, 100, lat = requireNotNull(it.lat), lng = requireNotNull(it.lng), createdAt = 1, updatedAt = 1) }
        fun plan(t: Trip) = requireNotNull(ClipPlan.build(ClipSources.available(t, emptyList(), stops, emptyList(), emptyList(), zone).getValue(ClipSource.Stops), ClipOptions()))
        assertEquals(modes, plan(mixedTrip(RouteLegSource.Stops)).legVehicle)
        assertEquals(listOf(Vehicle.Auto, Vehicle.Auto, Vehicle.Auto), plan(mixedTrip()).legVehicle)
    }

    @Test fun aShortMiddleWalkBetweenLongJourneysRemainsVisibleInBothSourcesAndCameras() {
        val longShortLong = points.mapIndexed { i, item ->
            item.copy(lat = 0.0, lng = listOf(0.0, 10.0, 10.001, 20.001)[i])
        }
        val chosen = listOf(Vehicle.Car, Vehicle.Walk, Vehicle.Plane)
        for (source in listOf(ClipSource.Planned, ClipSource.Stops)) {
            val namespace = if (source == ClipSource.Planned) RouteLegSource.Planned else RouteLegSource.Stops
            val raw = chosen.foldIndexed(null as String?) { i, json, vehicle ->
                TravelModes.set(json, RouteLegKey(namespace, "p$i", "p${i + 1}"), vehicle)
            }
            val stops = longShortLong.mapIndexed { i, p -> Stop(p.id, trip.id, i * 86_400_000L,
                lat = requireNotNull(p.lat), lng = requireNotNull(p.lng), createdAt = 1, updatedAt = 1) }
            val inputs = ClipSources.available(trip.copy(travelModes = raw), emptyList(), stops, longShortLong, emptyList(), zone)
            for (view in ClipView.entries) {
                val plan = requireNotNull(ClipPlan.build(inputs.getValue(source), ClipOptions(view = view, seconds = 15)))
                val frames = (plan.drawStart until plan.drawEnd).map(plan::frame)
                assertEquals(450, plan.frameCount)
                for (i in chosen.indices) {
                    val legFrames = frames.filter { it.headSeg == i }
                    assertTrue("$source/$view leg $i", legFrames.size >= 15)
                    assertTrue(legFrames.all { it.vehicle == chosen[i] })
                }
                assertTrue((plan.drawStart until plan.drawEnd step 3).any { plan.frame(it).vehicle == Vehicle.Walk })
                assertTrue(plan.days.all { day ->
                    (day.fromFrame until day.toFrame).all { plan.frame(it).headSeg in day.fromIdx until day.toIdx }
                })
            }
        }
    }

    @Test fun busyItinerariesKeepTheFixedBudgetAndAtLeastOneFrameWhenThereIsRoom() {
        val weights = List(40) { if (it == 20) 1.0 else 1_000_000.0 }
        val shares = ClipPlan.shareLegFrames(weights, 315, 30)
        assertEquals(315, shares.sum())
        assertTrue(shares.all { it >= 7 })
        assertTrue(ClipPlan.shareLegFrames(weights, 40, 30).all { it == 1 })
        val crowded = ClipPlan.shareLegFrames(weights, 20, 30)
        assertEquals(20, crowded.sum())
        assertTrue(crowded.all { it >= 0 })
        assertTrue(crowded.any { it == 0 })
    }

    @Test fun oneFrameAllocationsSampleTheirOwnLegRatherThanSkippingToTheNextVehicle() {
        // 15 s: a 1.5 s opening and a 2 s close leave 345 drawing frames, one per leg.
        val many = (0..345).map { i -> points.first().copy(id = "place-$i", position = i.toDouble(), lat = 0.0, lng = i * .001) }
        val input = ClipSources.available(trip, emptyList(), emptyList(), many, emptyList(), zone).getValue(ClipSource.Planned)
        val plan = requireNotNull(ClipPlan.build(input, ClipOptions(seconds = 15)))
        assertEquals(450, plan.frameCount)
        assertEquals(345, plan.drawEnd - plan.drawStart)
        assertEquals((0 until 345).toList(), (plan.drawStart until plan.drawEnd).map { plan.frame(it).headSeg })
    }
}
