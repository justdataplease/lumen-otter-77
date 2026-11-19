package com.justdataplease.boringtravel.geo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.min

/**
 * Offline country lookup from the bundled Natural Earth 1:110m outlines (assets/geo/countries.json, see the note
 * next to it). Point in polygon with a bounding-box prefilter; when no outline holds the point (coarse coasts, small
 * islands), the nearest outline within [nearbyM] counts. Returns ISO 3166-1 alpha-2 codes, except the two places
 * Natural Earth gives no ISO code (N. Cyprus "CYN", Somaliland "SOL"); callers may then ask Android's Geocoder.
 */
class Countries private constructor(private val countries: List<Country>) {

    /** One outline: rings of lng,lat pairs (first ring outside, the rest holes). */
    private class Polygon(val rings: List<DoubleArray>, val box: BBox)

    private class Country(val code: String, val polygons: List<Polygon>, val box: BBox)

    val size: Int get() = countries.size

    /**
     * One country's outlines for drawing (the globe): [polygons] are lists of rings of lng,lat pairs (first ring
     * outside, the rest holes); [boxes] is each polygon's lat/lng box, in the same order.
     */
    class Outline(val code: String, val polygons: List<List<DoubleArray>>, val boxes: List<BBox>)

    /** Every country's outlines, in file order. The arrays are shared: read them, never change them. */
    val outlines: List<Outline> by lazy { countries.map { c -> Outline(c.code, c.polygons.map { it.rings }, c.polygons.map { it.box }) } }

    /** The country at [lat], [lng], or null at sea (or on an island too small for 1:110m and far from any coast). */
    fun codeAt(lat: Double, lng: Double, nearbyM: Double = DEFAULT_NEARBY_M): String? {
        for (c in countries) {
            if (!c.box.contains(lat, lng)) continue
            if (c.polygons.any { it.box.contains(lat, lng) && inside(it, lat, lng) }) return c.code
        }
        if (nearbyM <= 0) return null
        val p = GeoPoint(lat, lng)
        var best: String? = null
        var bestM = nearbyM
        for (c in countries) {
            if (!c.box.expand(nearbyM).contains(lat, lng)) continue
            for (poly in c.polygons) {
                if (!poly.box.expand(nearbyM).contains(lat, lng)) continue
                val d = distanceToOutlineM(poly, p, bestM)
                if (d < bestM) { bestM = d; best = c.code }
            }
        }
        return best
    }

    /** Even–odd ray casting over every ring, so holes (lakes, enclaves) come out right. */
    private fun inside(poly: Polygon, lat: Double, lng: Double): Boolean {
        var inside = false
        for (r in poly.rings) {
            var j = r.size - 2
            var i = 0
            while (i < r.size) {
                val xi = r[i]; val yi = r[i + 1]
                val xj = r[j]; val yj = r[j + 1]
                if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
                i += 2
            }
        }
        return inside
    }

    private fun distanceToOutlineM(poly: Polygon, p: GeoPoint, limitM: Double): Double {
        var best = Double.MAX_VALUE
        val reach = BBox(p.lat, p.lng, p.lat, p.lng).expand(limitM)
        for (r in poly.rings) {
            var i = 2
            while (i < r.size) {
                val aLng = r[i - 2]; val aLat = r[i - 1]; val bLng = r[i]; val bLat = r[i + 1]
                // Skip edges whose box is nowhere near the point.
                if (max(aLat, bLat) >= reach.minLat && min(aLat, bLat) <= reach.maxLat &&
                    max(aLng, bLng) >= reach.minLng && min(aLng, bLng) <= reach.maxLng
                ) {
                    val d = Geo.distanceToSegmentM(p, GeoPoint(aLat, aLng), GeoPoint(bLat, bLng))
                    if (d < best) best = d
                }
                i += 2
            }
        }
        return best
    }

    companion object {
        /** Coasts at 1:110m are off by a few km; this also catches islands just off a mainland coast. */
        const val DEFAULT_NEARBY_M = 25_000.0

        /** Parses the bundled format: {"v":1,"c":[{"a":"GR","p":[[[lng,lat,...],[hole...]],...]}, ...]}. */
        fun parse(json: String): Countries {
            val root = Json.parseToJsonElement(json).jsonObject
            val list = root["c"]!!.jsonArray.map { el ->
                val o = el as JsonObject
                val polygons = o["p"]!!.jsonArray.mapNotNull { poly ->
                    val rings = (poly as JsonArray).map { ring ->
                        val a = ring.jsonArray
                        DoubleArray(a.size) { a[it].jsonPrimitive.double }
                    }.filter { it.size >= 8 }
                    if (rings.isEmpty()) null else Polygon(rings, ringBox(rings.first()))
                }
                if (polygons.isEmpty()) null
                else Country(o["a"]!!.jsonPrimitive.content, polygons, polygons.map { it.box }.reduce(BBox::union))
            }
            return Countries(list.filterNotNull())
        }

        private fun ringBox(r: DoubleArray): BBox {
            var minLng = Double.MAX_VALUE; var maxLng = -Double.MAX_VALUE
            var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
            var i = 0
            while (i < r.size) {
                minLng = min(minLng, r[i]); maxLng = max(maxLng, r[i])
                minLat = min(minLat, r[i + 1]); maxLat = max(maxLat, r[i + 1])
                i += 2
            }
            return BBox(minLat, minLng, maxLat, maxLng)
        }

        /** True for a two-letter ISO code (what flags and stats use). */
        fun isIsoAlpha2(code: String?): Boolean = code != null && code.length == 2 && code.all { it in 'A'..'Z' }
    }
}
