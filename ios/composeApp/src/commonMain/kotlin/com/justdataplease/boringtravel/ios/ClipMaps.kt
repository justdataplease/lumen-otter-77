package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.compat.diag
import com.justdataplease.boringtravel.clip.BaseMap
import com.justdataplease.boringtravel.clip.LayerSpec
import com.justdataplease.boringtravel.clip.MapLayer
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.clip.MapStyleJson
import com.justdataplease.boringtravel.clip.MapTint
import com.justdataplease.boringtravel.clip.Mercator
import com.justdataplease.boringtravel.clip.StreetTiles
import com.justdataplease.boringtravel.clip.WorldRect
import com.justdataplease.boringtravel.compat.graphics.Bitmap
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The trip video's map pictures (the Android app's clip/BaseMap.kt BaseMaps): one picture of the map per LayerSpec the
 * plan asks for, taken in the map's look and measured, so the renderer knows exactly which world rectangle it shows.
 * Android takes them with MapLibre's snapshotter; here MapSnapshots does (iPhone: MapLibre's MLNMapSnapshotter through
 * Swift, iosMain/…/IosClipMaps.kt; the desktop, the tests and the screenshots: the countries' outlines drawn, no tiles).
 */

/** A map picture taken for [spec], and where two world points ([MapSnapshots.take]'s a and b) landed in it. */
class MapSnapshot(val image: Image, val ax: Double, val ay: Double, val bx: Double, val by: Double)

/** Takes the video's map pictures. */
interface MapSnapshots {
    /** Whether the phone has a connection (offline, Android leaves the map out and the video draws its grid). */
    fun online(): Boolean

    /**
     * [spec]'s world rectangle (south, west, north, east in degrees) at [LayerSpec.widthPx] × [LayerSpec.heightPx]
     * pixels and [LayerSpec.pixelRatio] in the style [json] (null: the street style at [styleUrl]), with where the points
     * [aLat]/[aLng] and [bLat]/[bLng] land in it (pixels, or points of the map's own: [ClipMaps.measure] tells them
     * apart); null when it couldn't be taken. Called on the main thread, one at a time.
     */
    suspend fun take(
        json: String?, styleUrl: String, spec: LayerSpec, south: Double, west: Double, north: Double, east: Double,
        aLat: Double, aLng: Double, bLat: Double, bLng: Double,
    ): MapSnapshot?
}

/** The pictures of a clip's map, as the Android app's BaseMaps fetches them. */
object ClipMaps {
    private const val TIMEOUT_MS = 25_000L

    /** One picture per spec, look and colours: reopening a clip or changing its length reuses what is drawn. */
    private data class Key(val spec: LayerSpec, val dark: Boolean, val style: MapStyle, val tint: MapTint?)

    /** At most 24 MB of pictures kept (Android's LruCache); one dropped here is freed once no clip shows it. */
    private val cache = LinkedHashMap<Key, MapLayer>()
    private const val CACHE_BYTES = 24 * 1024 * 1024

    private fun cached(key: Key): MapLayer? = cache.remove(key)?.also { cache[key] = it }

    private fun keep(key: Key, layer: MapLayer) {
        cache[key] = layer
        var bytes = cache.values.sumOf { it.bitmap.allocationByteCount.toLong() }
        while (bytes > CACHE_BYTES && cache.size > 1) {
            val eldest = cache.keys.first()
            bytes -= cache.remove(eldest)?.bitmap?.allocationByteCount ?: 0
        }
    }

    /** Forgets every picture (tests start from nothing). */
    fun forget() = cache.clear()

    /**
     * Fetches [specs] in order and reports the map after each one through [onLayer], so a preview can show the
     * overview while closer pictures are still coming, and how many are done through [onProgress] (0–1). A failed
     * overview means [BaseMap.Plain]; a failed closer picture is left out. [tint]: Globe's street map colours.
     *
     * A Globe route runs on past 180° in world columns outside 0–1 ([com.justdataplease.boringtravel.clip.ClipGlobe.near]);
     * such a picture is taken a whole world over and put back where the route is.
     */
    suspend fun fetch(
        snapshots: MapSnapshots, web: Web, specs: List<LayerSpec>, dark: Boolean, style: MapStyle = MapStyle.Street, tint: MapTint? = null,
        onLayer: (BaseMap) -> Unit = {}, onProgress: (Float) -> Unit = {},
    ): BaseMap {
        if (specs.isEmpty()) return BaseMap.Plain
        val connected = snapshots.online()
        var json: String? = null
        var styleLoaded = false
        val layers = ArrayList<MapLayer>()
        for ((i, spec) in specs.withIndex()) {
            val key = Key(spec, dark, style, tint)
            val layer = cached(key) ?: if (connected) {
                if (!styleLoaded) {
                    json = styleJson(web, style, dark, tint)
                    styleLoaded = true
                }
                val turns = floor(spec.rect.cx)
                val local = if (turns == 0.0) spec else spec.copy(rect = spec.rect.shiftedX(-turns))
                snapshot(snapshots, local, json, StreetTiles.style(dark))?.let { MapLayer(it.bitmap, it.rect.shiftedX(turns)) }?.also { keep(key, it) }
            } else null
            onProgress((i + 1).toFloat() / specs.size)
            if (layer == null) {
                if (i == 0) return BaseMap.Plain
                continue
            }
            layers += layer
            onLayer(BaseMap(layers.toList(), style))
        }
        return BaseMap(layers.toList(), style)
    }

    /**
     * The style for [style] as Android's MapStyleLoader makes it: Street is OpenFreeMap's own style by its address
     * (null here), the others OpenFreeMap's style with the hill shading or the pictures added, Globe's draped street
     * map in [tint]'s colours.
     */
    private suspend fun styleJson(web: Web, style: MapStyle, dark: Boolean, tint: MapTint?): String? {
        if (style == MapStyle.Street) return null
        val base = MapBaseStyles.base(web, dark)
        return runCatching { MapStyleJson.build(style, base, dark, tint) }.getOrNull()
    }

    private suspend fun snapshot(snapshots: MapSnapshots, spec: LayerSpec, json: String?, url: String): MapLayer? {
        var answered = false
        val layer = withTimeoutOrNull(TIMEOUT_MS) {
        val r = spec.rect
        // Two known points a quarter and three quarters across, to measure the picture by.
        val ax = r.x0 + r.w * 0.25
        val ay = r.y0 + r.h * 0.25
        val bx = r.x0 + r.w * 0.75
        val by = r.y0 + r.h * 0.75
        val shot = runCatching {
            snapshots.take(
                json, url, spec, Mercator.lat(r.y1), Mercator.lng(r.x0), Mercator.lat(r.y0), Mercator.lng(r.x1),
                Mercator.lat(ay), Mercator.lng(ax), Mercator.lat(by), Mercator.lng(bx),
            )
        }.onFailure { diag("map picture failed: $it") }.getOrNull()
        answered = true
        if (shot == null) {
            diag("map picture: none (${if (json == null) url else "built style"}, ${spec.pointSize()})")
            return@withTimeoutOrNull null
        }
        measure(shot, spec).also { if (it == null) diag("map picture: couldn't be measured") }
        }
        // Why the trip video's map may stay "Loading the map" on iPhone (the Mac run of 19 Nov 2026): in the app's output.
        if (!answered) diag("map picture: no answer in ${TIMEOUT_MS / 1000} s (${if (json == null) url else "built style"})")
        return layer
    }

    /**
     * Works out which world rectangle the picture really shows from where the two known points landed (Web Mercator is
     * linear in x and y, so two points fix it exactly); the points may be in the map's points rather than the
     * picture's pixels, which the scale tells apart (the Android app's BaseMaps.measure).
     */
    fun measure(shot: MapSnapshot, spec: LayerSpec): MapLayer? {
        val image = shot.image
        val r = spec.rect
        val ax = r.x0 + r.w * 0.25
        val ay = r.y0 + r.h * 0.25
        val bx = r.x0 + r.w * 0.75
        val by = r.y0 + r.h * 0.75
        var pax = shot.ax; var pay = shot.ay; var pbx = shot.bx; var pby = shot.by
        var sx = (pbx - pax) / (bx - ax)
        var sy = (pby - pay) / (by - ay)
        if (!(sx > 0 && sy > 0 && sx.isFinite() && sy.isFinite())) {
            image.close()
            return null
        }
        val fit = min(image.width / r.w, image.height / r.h)
        if (abs(sx * spec.pixelRatio / fit - 1) < 0.05) {
            val k = spec.pixelRatio.toDouble()
            pax *= k; pay *= k; pbx *= k; pby *= k; sx *= k; sy *= k
        }
        val x0 = ax - pax / sx
        val y0 = ay - pay / sy
        return MapLayer(Bitmap.of(image), WorldRect(x0, y0, x0 + image.width / sx, y0 + image.height / sy))
    }
}

/**
 * The desktop's, the tests' and the screenshots' map pictures: no tiles, the countries' outlines (the Android app's
 * Natural Earth file, as the desktop's map draws them) on the sea, in the map look's colours, exactly over the asked
 * rectangle. [online] false stands for a phone without a connection.
 */
class DrawnSnapshots(private val online: Boolean = true) : MapSnapshots {
    override fun online(): Boolean = online

    override suspend fun take(
        json: String?, styleUrl: String, spec: LayerSpec, south: Double, west: Double, north: Double, east: Double,
        aLat: Double, aLng: Double, bLat: Double, bLng: Double,
    ): MapSnapshot? {
        val rings = DrawnLand.load()
        val w = spec.widthPx
        val h = spec.heightPx
        val r = spec.rect
        val (land, sea) = colours(json, styleUrl)
        val surface = Surface.makeRasterN32Premul(w, h)
        try {
            val c = surface.canvas
            c.clear(sea)
            fun px(lng: Double) = ((Mercator.x(lng) - r.x0) / r.w * w).toFloat()
            fun py(lat: Double) = ((Mercator.y(lat) - r.y0) / r.h * h).toFloat()
            val fill = Paint().apply { isAntiAlias = true; color = land }
            val edge = Paint().apply { isAntiAlias = true; color = 0x33000000; mode = PaintMode.STROKE; strokeWidth = 1.2f * spec.pixelRatio }
            for (ring in rings) {
                if (ring.size < 6) continue
                // A ring well off the picture is left out.
                var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
                var i = 0
                while (i + 1 < ring.size) {
                    val x = Mercator.x(ring[i]); val y = Mercator.y(ring[i + 1])
                    if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                    i += 2
                }
                if (maxX < r.x0 || minX > r.x1 || maxY < r.y0 || minY > r.y1) continue
                val b = PathBuilder(PathFillMode.EVEN_ODD)
                i = 0
                while (i + 1 < ring.size) {
                    if (i == 0) b.moveTo(px(ring[i]), py(ring[i + 1])) else b.lineTo(px(ring[i]), py(ring[i + 1]))
                    i += 2
                }
                b.closePath()
                val path = b.detach()
                c.drawPath(path, fill)
                c.drawPath(path, edge)
                path.close()
                b.close()
            }
            fill.close()
            edge.close()
            val image = surface.makeImageSnapshot()
            return MapSnapshot(image, px(aLng).toDouble(), py(aLat).toDouble(), px(bLng).toDouble(), py(bLat).toDouble())
        } finally {
            surface.close()
        }
    }

    /** Land and sea as the look's map has them, roughly: OpenFreeMap's light or dark, the hills', the satellite's. */
    private fun colours(json: String?, styleUrl: String): Pair<Int, Int> {
        val dark = styleUrl.endsWith(StreetTiles.STYLE_DARK.substringAfterLast('/'))
        return when {
            json != null && "sentinel" in json.lowercase() -> 0xFF3E5232.toInt() to 0xFF14283A.toInt()
            json != null && "hillshade" in json.lowercase() -> (if (dark) 0xFF34303A.toInt() else 0xFFEDE6D6.toInt()) to (if (dark) 0xFF1B2230.toInt() else 0xFFB9D3DA.toInt())
            dark -> 0xFF2E2D33.toInt() to 0xFF181C24.toInt()
            else -> 0xFFF2F1EC.toInt() to 0xFFC5D7DD.toInt()
        }
    }
}

/** The look's map size the picture is taken at in points (the picture is [LayerSpec.pixelRatio] times that). */
fun LayerSpec.pointSize(): Pair<Int, Int> =
    (widthPx / pixelRatio).roundToInt().coerceAtLeast(64) to (heightPx / pixelRatio).roundToInt().coerceAtLeast(64)
