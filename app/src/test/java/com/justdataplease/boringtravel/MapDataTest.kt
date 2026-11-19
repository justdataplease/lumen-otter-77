package com.justdataplease.boringtravel

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.geo.GeoPoint
import com.justdataplease.boringtravel.tracking.RouteShape
import com.justdataplease.boringtravel.ui.map.MapData
import com.justdataplease.boringtravel.ui.map.MapGeoJson
import com.justdataplease.boringtravel.ui.map.TripMapModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class MapDataTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val today = LocalDate.of(2026, 6, 18)
    private fun at(day: LocalDate, time: String) = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
    private fun pt(time: Long, lat: Double, lng: Double) = TrackPoint(tripId = "c", time = time, lat = lat, lng = lng, accuracyM = 12f)

    @Before fun english() = Locale.setDefault(Locale.US)

    /** Today's ferry and bus, like the sample trip: Paros â†’ Naxos port â†’ Halki. */
    private fun todaysRoute(): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        fun leg(from: Pair<Double, Double>, to: Pair<Double, Double>, start: Long, minutes: Int) {
            val n = minutes / 3
            for (i in 0..n) {
                val f = i.toDouble() / n
                out += pt(start + i * 3 * 60_000L, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f)
            }
        }
        leg(37.0853 to 25.1488, 37.1056 to 25.3747, at(today, "07:30"), 66)
        leg(37.1056 to 25.3747, 37.0587 to 25.4807, at(today, "11:40"), 42)
        return out
    }

    @Test fun piecesSplitAtJumps() {
        val t = at(today, "08:00")
        val pts = listOf(pt(t, 37.0, 25.0), pt(t + 60_000, 37.001, 25.0), pt(t + 120_000, 38.0, 23.0), pt(t + 180_000, 38.001, 23.0))
        val pieces = RouteShape.pieces(pts)
        assertEquals(listOf(false, true, false), pieces.map { it.jump })
        assertEquals(listOf(GeoPoint(37.001, 25.0), GeoPoint(38.0, 23.0)), pieces[1].points)
        // A long gap without points, a few km apart, is a jump too; a short one isn't.
        val gap = listOf(pt(t, 37.0, 25.0), pt(t + 4 * 3_600_000L, 37.03, 25.0))
        assertTrue(RouteShape.pieces(gap).single().jump)
        assertFalse(RouteShape.pieces(listOf(pt(t, 37.0, 25.0), pt(t + 60_000, 37.03, 25.0))).single().jump)
    }

    @Test fun distanceTodayAndFormatting() {
        val pts = todaysRoute()
        val total = RouteShape.distanceM(pts)
        assertTrue(total in 25_000.0..35_000.0)
        val morning = RouteShape.distanceM(pts, at(today, "07:00"), at(today, "09:00"))
        assertTrue(morning in 18_000.0..23_000.0)
        assertEquals("18.4 km", RouteShape.formatDistance(18_420.0))
        assertEquals("650 m", RouteShape.formatDistance(655.0))
        assertEquals("0 km", RouteShape.formatDistance(0.0))
        assertEquals("1,234 km", RouteShape.formatDistance(1_234_400.0))
    }

    @Test fun tripMapChipsWhileTheTripIsOn() {
        val trip = Trip("c", "Cyclades, slowly", startDate = today.minusDays(3), endDate = today.plusDays(6), createdAt = 0, updatedAt = 0)
        fun stop(id: String, from: Long, to: Long?, lat: Double, lng: Double) = Stop(id, "c", from, to, lat, lng, createdAt = 0, updatedAt = 0)
        val stops = listOf(
            stop("parikia", at(today.minusDays(3), "20:30"), at(today, "07:20"), 37.0853, 25.1488),
            stop("agios", at(today, "09:45"), at(today, "11:35"), 37.0990, 25.3730),
            stop("halki", at(today, "12:20"), null, 37.0587, 25.4807),
            stop("old", at(today.minusDays(2), "10:00"), at(today.minusDays(2), "12:00"), 37.0, 25.1),
        )
        val items = listOf(PlanItem("p", "c", title = "Portara", lat = 37.1111, lng = 25.3721, createdAt = 0, updatedAt = 0), PlanItem("q", "c", title = "Idea", createdAt = 0, updatedAt = 0))
        val entries = listOf(Entry("e", "c", at(today, "13:10"), zone.id, title = "The bus to Halki", lat = 37.0587, lng = 25.4807, createdAt = 0, updatedAt = 0))
        val v = TripMapModel.build(trip, todaysRoute(), stops, items, entries, at(today, "14:00"), zone)
        assertTrue(v.current)
        assertTrue(v.distance.endsWith(" km today"))
        assertEquals("3 stops", v.stops)
        assertEquals(1, v.data.routes.size)
        assertEquals(4, v.data.stops.size)
        assertEquals(listOf("p"), v.data.plans.map { it.id })
        assertEquals(listOf("e"), v.data.entries.map { it.id })
        assertEquals(37.0587, v.data.me!!.lat, 1e-9)
        // After the trip: the whole trip's numbers and no current position.
        val after = TripMapModel.build(trip, todaysRoute(), stops, items, entries, at(today.plusDays(10), "10:00"), zone)
        assertFalse(after.current)
        assertEquals("4 stops", after.stops)
        assertNull(after.data.me)
    }

    @Test fun emptyTripHasNoTrack() {
        val trip = Trip("j", "Kyoto & Osaka", startDate = today.plusDays(100), endDate = today.plusDays(110), createdAt = 0, updatedAt = 0)
        val v = TripMapModel.build(trip, emptyList(), emptyList(), emptyList(), emptyList(), at(today, "10:00"), zone)
        assertFalse(v.hasTrack)
        assertTrue(v.data.isEmpty)
        assertNull(v.data.bounds)
    }

    @Test fun geoJsonHasLngLatAndProperties() {
        val route = MapData.route("c", todaysRoute(), MapData.TRIP_TOLERANCE_M)!!
        val json = Json.parseToJsonElement(MapGeoJson.routes(listOf(route))).jsonObject
        val f = json["features"]!!.jsonArray[0].jsonObject
        assertEquals("LineString", f["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val first = f["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray
        assertEquals(25.1488, first[0].jsonPrimitive.content.toDouble(), 1e-6) // lng first
        assertEquals(37.0853, first[1].jsonPrimitive.content.toDouble(), 1e-6)
        assertEquals("c", f["properties"]!!.jsonObject["trip"]!!.jsonPrimitive.content)
        assertFalse(f["properties"]!!.jsonObject["jump"]!!.jsonPrimitive.boolean)

        val marks = Json.parseToJsonElement(MapGeoJson.marks(MapData.stops(listOf(Stop("s", null, 0, null, 1.0, 2.0, name = "Halki", createdAt = 0, updatedAt = 0))))).jsonObject
        val props = marks["features"]!!.jsonArray[0].jsonObject["properties"]!!.jsonObject
        assertEquals("Halki", props["name"]!!.jsonPrimitive.content)
        assertNull(props["trip"]) // no trip: no property
        assertEquals(0, Json.parseToJsonElement(MapGeoJson.point(null)).jsonObject["features"]!!.jsonArray.size)
    }
}
