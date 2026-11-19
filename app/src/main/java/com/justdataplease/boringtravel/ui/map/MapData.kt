package com.justdataplease.boringtravel.ui.map

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.geo.BBox
import com.justdataplease.boringtravel.geo.GeoPoint
import com.justdataplease.boringtravel.tracking.RouteShape
import com.justdataplease.boringtravel.tracking.SpikeFilter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId

/** One trip's route as drawn pieces. */
data class MapRoute(val tripId: String, val pieces: List<RouteShape.Piece>)

/** A dot or pin: a stop, a plan item or an entry. */
data class MapMark(val id: String, val tripId: String?, val lat: Double, val lng: Double, val title: String)

/** Everything a map shows, as plain data built off the main thread. */
data class MapData(
    val routes: List<MapRoute> = emptyList(),
    val stops: List<MapMark> = emptyList(),
    val plans: List<MapMark> = emptyList(),
    val entries: List<MapMark> = emptyList(),
    /** The latest point of a trip that is recording now. */
    val me: GeoPoint? = null,
    /** The planned route through the plan's located items, in plan order (drawn dashed in the plan colour). */
    val planned: List<GeoPoint> = emptyList(),
) {
    val isEmpty: Boolean get() = routes.isEmpty() && stops.isEmpty() && plans.isEmpty() && entries.isEmpty() && me == null && planned.isEmpty()

    val bounds: BBox?
        get() = BBox.of(
            routes.flatMap { r -> r.pieces.flatMap { it.points } } +
                (stops + plans + entries).map { GeoPoint(it.lat, it.lng) } + listOfNotNull(me) + planned,
        )

    companion object {
        /** Display tolerance (m): fine for one trip, coarser for the map of every trip. */
        const val TRIP_TOLERANCE_M = 4.0
        const val ALL_TOLERANCE_M = 25.0

        /** [cleaned] = points already through [SpikeFilter]. */
        fun route(tripId: String, cleaned: List<TrackPoint>, toleranceM: Double): MapRoute? {
            val pieces = RouteShape.pieces(cleaned, toleranceM)
            return if (pieces.isEmpty()) null else MapRoute(tripId, pieces)
        }

        fun stops(stops: List<Stop>) = stops.filter { it.deletedAt == null }.map { MapMark(it.id, it.tripId, it.lat, it.lng, it.name) }

        fun plans(items: List<PlanItem>) = items.mapNotNull { i ->
            val lat = i.lat
            val lng = i.lng
            if (i.deletedAt != null || lat == null || lng == null) null else MapMark(i.id, i.tripId, lat, lng, i.title)
        }

        fun entries(entries: List<Entry>) = entries.mapNotNull { e ->
            val lat = e.lat
            val lng = e.lng
            if (e.deletedAt != null || lat == null || lng == null) null else MapMark(e.id, e.tripId, lat, lng, e.title.ifBlank { e.placeName })
        }
    }
}

/** The trip map: what it draws and its two chips ("18.4 km today", "3 stops"); [current] = the trip is on today. */
data class TripMapView(val data: MapData, val distance: String, val stops: String, val current: Boolean, val hasTrack: Boolean)

object TripMapModel {
    /** Today's distance and stops while the trip is on, the whole trip's otherwise. */
    fun build(
        trip: Trip,
        raw: List<TrackPoint>,
        stops: List<Stop>,
        items: List<PlanItem>,
        entries: List<Entry>,
        now: Long,
        zone: ZoneId,
    ): TripMapView {
        val cleaned = SpikeFilter.filter(raw)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val current = trip.isCurrent(today)
        val live = stops.filter { it.deletedAt == null }
        val distance: String
        val stopCount: Int
        if (current) {
            val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            distance = "${RouteShape.formatDistance(RouteShape.distanceM(cleaned, from, to))} today"
            stopCount = RouteShape.stopsBetween(live, from, to, now).size
        } else {
            distance = RouteShape.formatDistance(RouteShape.distanceM(cleaned))
            stopCount = live.size
        }
        val data = MapData(
            routes = listOfNotNull(MapData.route(trip.id, cleaned, MapData.TRIP_TOLERANCE_M)),
            stops = MapData.stops(live),
            plans = MapData.plans(items),
            entries = MapData.entries(entries),
            me = if (current) cleaned.lastOrNull()?.let { GeoPoint(it.lat, it.lng) } else null,
        )
        return TripMapView(
            data, distance, if (stopCount == 1) "1 stop" else "$stopCount stops", current,
            hasTrack = cleaned.isNotEmpty() || live.isNotEmpty(),
        )
    }
}

/** What a tap on the map hit. */
sealed interface MapTap {
    data class Trip(val tripId: String) : MapTap
    data class StopMark(val stopId: String, val tripId: String?) : MapTap
    data class Plan(val itemId: String) : MapTap
    data class Entry(val entryId: String) : MapTap
}

/** GeoJSON for the map's sources. Coordinates are [lng, lat]. */
object MapGeoJson {
    const val P_ID = "id"
    const val P_TRIP = "trip"
    const val P_NAME = "name"
    const val P_JUMP = "jump"

    private fun position(p: GeoPoint) = buildJsonArray { add(JsonPrimitive(p.lng)); add(JsonPrimitive(p.lat)) }

    private fun collection(features: List<JsonObject>): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }.toString()

    private fun feature(geometry: JsonObject, props: Map<String, Any?>) = buildJsonObject {
        put("type", "Feature")
        put("geometry", geometry)
        putJsonObject("properties") {
            props.forEach { (k, v) ->
                when (v) {
                    null -> Unit
                    is Boolean -> put(k, v)
                    is Number -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }
    }

    fun routes(routes: List<MapRoute>): String = collection(
        routes.flatMap { r ->
            r.pieces.filter { it.points.size >= 2 }.map { piece ->
                feature(
                    buildJsonObject {
                        put("type", "LineString")
                        putJsonArray("coordinates") { piece.points.forEach { add(position(it)) } }
                    },
                    mapOf(P_TRIP to r.tripId, P_JUMP to piece.jump),
                )
            }
        },
    )

    fun marks(marks: List<MapMark>): String = collection(
        marks.map { m ->
            feature(
                buildJsonObject {
                    put("type", "Point")
                    put("coordinates", position(GeoPoint(m.lat, m.lng)))
                },
                mapOf(P_ID to m.id, P_TRIP to m.tripId, P_NAME to m.title),
            )
        },
    )

    /** One dashed line through [points] (the planned route), or an empty collection. */
    fun line(points: List<GeoPoint>): String = collection(
        if (points.size < 2) emptyList() else listOf(
            feature(
                buildJsonObject {
                    put("type", "LineString")
                    putJsonArray("coordinates") { points.forEach { add(position(it)) } }
                },
                emptyMap(),
            ),
        ),
    )

    fun point(p: GeoPoint?): String = collection(
        listOfNotNull(p).map { feature(buildJsonObject { put("type", "Point"); put("coordinates", position(it)) }, emptyMap()) },
    )
}
