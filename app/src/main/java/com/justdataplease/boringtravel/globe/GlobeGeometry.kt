package com.justdataplease.boringtravel.globe

import com.justdataplease.boringtravel.geo.Countries
import com.justdataplease.boringtravel.geo.CountryInfo
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The globe's shapes as unit vectors, computed once: every country ring, and the graticule (every 30°). Each ring
 * also has a bounding cap (centre direction and the sine of its angular radius) so a frame can skip rings wholly
 * behind the globe and draw wholly visible ones without clipping.
 */
class GlobeGeometry private constructor(
    /** x, y, z per vertex, rings one after another. */
    val xyz: FloatArray,
    val ringStart: IntArray,
    val ringSize: IntArray,
    /** Index into [codes] per ring. */
    val ringCountry: IntArray,
    /** Cap per ring: centre x, y, z and sin(radius) (above 1 = always clip). */
    val ringCap: FloatArray,
    /** True when the edge from this vertex to the next is a cut along the 180° meridian or the South Pole. */
    val seam: BooleanArray,
    /** Natural Earth code per country ("GR", "CYN", …). */
    val codes: Array<String>,
    /** The counted code per country ([CountryInfo.key]: "CYN" → "CY"). */
    val keys: Array<String?>,
    val gridXyz: FloatArray,
    val gridStart: IntArray,
    val gridSize: IntArray,
) {
    val ringCount: Int get() = ringStart.size
    val countryCount: Int get() = codes.size
    val maxRing: Int = ringSize.maxOrNull() ?: 0

    companion object {
        /** Builds from rings of lng,lat pairs per country (the bundled Natural Earth outlines). */
        fun from(countries: Countries): GlobeGeometry = from(countries.outlines.map { o -> o.code to o.polygons.flatten() })

        fun from(rings: List<Pair<String, List<DoubleArray>>>): GlobeGeometry {
            val codes = rings.map { it.first }.toTypedArray()
            val xyz = FloatList()
            val seam = ArrayList<Boolean>()
            val starts = ArrayList<Int>()
            val sizes = ArrayList<Int>()
            val owners = ArrayList<Int>()
            val caps = FloatList()
            rings.forEachIndexed { ci, (_, list) ->
                for (ring in list) {
                    var n = ring.size / 2
                    // Rings repeat their first point at the end; the clipper closes them itself.
                    if (n > 1 && ring[0] == ring[2 * n - 2] && ring[1] == ring[2 * n - 1]) n--
                    if (n < 3) continue
                    starts += xyz.size / 3
                    sizes += n
                    owners += ci
                    var sx = 0.0
                    var sy = 0.0
                    var sz = 0.0
                    val base = xyz.size
                    for (i in 0 until n) {
                        val lng = ring[2 * i]
                        val lat = ring[2 * i + 1]
                        val x = GlobeMath.unitX(lat, lng)
                        val y = GlobeMath.unitY(lat, lng)
                        val z = GlobeMath.unitZ(lat)
                        xyz.add(x); xyz.add(y); xyz.add(z)
                        sx += x; sy += y; sz += z
                        val j = (i + 1) % n
                        val lng2 = ring[2 * j]
                        val lat2 = ring[2 * j + 1]
                        seam += (abs(lng) >= 179.99 && abs(lng2) >= 179.99) || (lat <= -89.99 && lat2 <= -89.99)
                    }
                    val len = sqrt(sx * sx + sy * sy + sz * sz)
                    if (len < 1e-6) {
                        caps.add(0f); caps.add(0f); caps.add(1f); caps.add(2f)
                    } else {
                        val cx = (sx / len).toFloat()
                        val cy = (sy / len).toFloat()
                        val cz = (sz / len).toFloat()
                        var minDot = 1f
                        for (i in 0 until n) {
                            val d = cx * xyz[base + 3 * i] + cy * xyz[base + 3 * i + 1] + cz * xyz[base + 3 * i + 2]
                            if (d < minDot) minDot = d
                        }
                        val radius = acos(minDot.toDouble().coerceIn(-1.0, 1.0))
                        // A cap of 90° or more can't be judged from its centre: always clip it.
                        val sinR = if (radius >= Math.PI / 2 - 1e-3) 2f else (sin(radius) + 1e-4).toFloat()
                        caps.add(cx); caps.add(cy); caps.add(cz); caps.add(sinR)
                    }
                }
            }

            val grid = FloatList()
            val gStart = ArrayList<Int>()
            val gSize = ArrayList<Int>()
            fun line(points: List<Pair<Double, Double>>) {
                gStart += grid.size / 3
                gSize += points.size
                for ((lat, lng) in points) {
                    grid.add(GlobeMath.unitX(lat, lng)); grid.add(GlobeMath.unitY(lat, lng)); grid.add(GlobeMath.unitZ(lat))
                }
            }
            for (lon in -180 until 180 step 30) line((-90..90 step 3).map { it.toDouble() to lon.toDouble() })
            for (lat in -60..60 step 30) line((-180..180 step 3).map { lat.toDouble() to it.toDouble() })

            return GlobeGeometry(
                xyz = xyz.toArray(),
                ringStart = starts.toIntArray(),
                ringSize = sizes.toIntArray(),
                ringCountry = owners.toIntArray(),
                ringCap = caps.toArray(),
                seam = seam.toBooleanArray(),
                codes = codes,
                keys = Array(codes.size) { CountryInfo.key(codes[it]) },
                gridXyz = grid.toArray(),
                gridStart = gStart.toIntArray(),
                gridSize = gSize.toIntArray(),
            )
        }
    }
}

/** A growable float array without boxing (only used while building). */
internal class FloatList(capacity: Int = 1024) {
    private var a = FloatArray(capacity)
    var size = 0; private set

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    fun toArray(): FloatArray = a.copyOf(size)
}
