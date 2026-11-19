package com.justdataplease.boringtravel.clip

import android.content.Context
import androidx.core.content.edit
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.LruCache
import com.justdataplease.boringtravel.globe.GlobeGeometry
import com.justdataplease.boringtravel.globe.GlobePin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** A base-map picture and the world rectangle it covers (measured from the picture itself). */
class MapLayer(val bitmap: Bitmap, val rect: WorldRect)

/**
 * The map under a clip: the first layer covers the whole clip, later ones are closer views (coarse to sharp). No
 * layers means no map could be fetched (offline): the renderer then draws a plain sea-coloured background with a
 * soft grid. [style] is the map look the layers were made with (its sources are credited on the clip). A Globe clip
 * also has [globe] (the app's globe, bundled, so it is there offline too); its layers are the street map draped over
 * the globe close in, covering only the frames that come that close.
 */
class BaseMap(val layers: List<MapLayer>, val style: MapStyle = MapStyle.Street, val globe: GlobeBase? = null) {
    val hasMap: Boolean get() = layers.isNotEmpty() || globe != null

    /** The credit lines for what is drawn: the map's sources (the globe's shapes alone without its street map), nothing without a map. */
    val credits: List<String>
        get() = when {
            globe != null && layers.isEmpty() -> listOf(GlobeShapes.CREDIT)
            hasMap -> style.credits
            else -> emptyList()
        }

    /** The same street map pictures with [globe] under them. */
    fun withGlobe(globe: GlobeBase?): BaseMap = if (globe == null) this else BaseMap(layers, MapStyle.Globe, globe)

    companion object {
        val Plain = BaseMap(emptyList())
    }
}

/**
 * The globe under a Globe clip: the Map tab's country shapes ([GlobeGeometry]), the trip's countries (counted codes,
 * in the order the route reaches them; filled in the look's highlighter) and their flag pins.
 */
class GlobeBase(val geometry: GlobeGeometry, val countries: List<String>, val pins: List<GlobePin>)

/** The last map look chosen for the clip and for the trip map, kept on this phone. */
object MapStylePrefs {
    const val CLIP = "clip"
    const val TRIP_MAP = "trip_map"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("map_style", Context.MODE_PRIVATE)

    /**
     * The trip map never gets Globe (a clip-only look); old prefs read as they were. A clip with nothing picked yet
     * starts on Globe (owner, 2026-11-08: "keep the best like globe by default").
     */
    fun load(context: Context, place: String): MapStyle {
        val saved = prefs(context).getString(place, null)
        if (saved == null && place == CLIP) return MapStyle.Globe
        return MapStyle.of(saved).let { if (place == TRIP_MAP && it !in MapStyle.tripMap) MapStyle.Street else it }
    }

    /** Forgets the clip's map, so the next clip starts on the default ([ClipPrefs.newDefaults]). */
    fun forgetClip(context: Context) {
        prefs(context).edit { remove(CLIP) }
    }

    fun save(context: Context, place: String, style: MapStyle) {
        prefs(context).edit().putString(place, style.id).apply()
    }
}

/**
 * Style JSON for Terrain and Earth: OpenFreeMap's own style, fetched once (kept in memory and in the cache folder,
 * so a map seen before still builds offline) with the hill shading or the pictures added by [MapStyleJson]. Street
 * loads OpenFreeMap's style by its address, as before.
 */
object MapStyleLoader {
    private val http by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }
    private val memory = ConcurrentHashMap<String, String>()

    /**
     * The style JSON for [style], or null for Street (and for Terrain when OpenFreeMap's style can't be had). Globe's
     * draped street map comes in [tint]'s colours (plain Street without them).
     */
    suspend fun json(context: Context, style: MapStyle, dark: Boolean, tint: MapTint? = null): String? {
        if (style == MapStyle.Street) return null
        val base = base(context.applicationContext, StreetTiles.style(dark))
        return runCatching { MapStyleJson.build(style, base, dark, tint) }.getOrNull()
    }

    /** A MapLibre style from [json], or the street style by its address when there is none. */
    fun builder(json: String?, dark: Boolean): Style.Builder =
        if (json != null) Style.Builder().fromJson(json) else Style.Builder().fromUri(StreetTiles.style(dark))

    private suspend fun base(context: Context, url: String): String? = withContext(Dispatchers.IO) {
        memory[url]?.let { return@withContext it }
        val file = File(File(context.cacheDir, "map-styles"), url.substringAfterLast('/') + ".json")
        val fetched = runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull()?.takeIf(::isStyle)
        if (fetched != null) {
            memory[url] = fetched
            runCatching { file.parentFile?.mkdirs(); file.writeText(fetched) }
            return@withContext fetched
        }
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.takeIf(::isStyle)?.also { memory[url] = it }
    }

    private fun isStyle(json: String): Boolean =
        runCatching { (Json.parseToJsonElement(json) as? JsonObject)?.containsKey("layers") == true }.getOrDefault(false)
}

/**
 * Base maps through MapLibre's snapshotter in the chosen [MapStyle]: no key, no per-view cost. Snapshots run on the
 * main thread (MapLibre needs its looper), one at a time.
 */
object BaseMaps {
    const val STYLE_LIGHT = StreetTiles.STYLE_LIGHT
    const val STYLE_DARK = StreetTiles.STYLE_DARK
    private const val TIMEOUT_MS = 25_000L
    // Reopening a clip or changing its duration/vehicle reuses the already rendered geography.
    // Eviction only drops the cache's reference; active previews can still own these bitmaps.
    private data class Key(val spec: LayerSpec, val dark: Boolean, val style: MapStyle, val tint: MapTint?)
    private val cache = object : LruCache<Key, MapLayer>(24 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: MapLayer): Int = value.bitmap.allocationByteCount
    }

    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Fetches [specs] in order and reports the map after each one through [onLayer], so a preview can show the
     * overview while closer pictures are still coming, and how many are done through [onProgress] (0–1). A failed
     * overview means [BaseMap.Plain]; a failed closer picture is left out. [tint]: Globe's street map colours.
     *
     * A Globe route runs on past 180° in world columns outside 0–1 ([ClipGlobe.near]); such a picture is taken a whole
     * world over and put back where the route is.
     */
    suspend fun fetch(
        context: Context, specs: List<LayerSpec>, dark: Boolean, style: MapStyle = MapStyle.Street, tint: MapTint? = null,
        onLayer: (BaseMap) -> Unit = {}, onProgress: (Float) -> Unit = {},
    ): BaseMap {
        if (specs.isEmpty()) return BaseMap.Plain
        val connected = online(context)
        var styleJson: String? = null
        var styleLoaded = false
        val layers = ArrayList<MapLayer>()
        for ((i, spec) in specs.withIndex()) {
            val key = Key(spec, dark, style, tint)
            val layer = cache.get(key) ?: if (connected) {
                if (!styleLoaded) { styleJson = MapStyleLoader.json(context, style, dark, tint); styleLoaded = true }
                val source = styleJson
                val turns = floor(spec.rect.cx)
                val local = if (turns == 0.0) spec else spec.copy(rect = spec.rect.shiftedX(-turns))
                snapshot(context.applicationContext, local) { MapStyleLoader.builder(source, dark) }
                    ?.let { withContext(Dispatchers.Default) { MapLayer(compact(it.bitmap), it.rect.shiftedX(turns)) } }
                    ?.also { cache.put(key, it) }
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

    private suspend fun snapshot(context: Context, spec: LayerSpec, style: () -> Style.Builder): MapLayer? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val result = runCatching {
                    MapLibre.getInstance(context)
                    val r = spec.rect
                    val bounds = LatLngBounds.Builder()
                        .include(LatLng(Mercator.lat(r.y0), Mercator.lng(r.x0)))
                        .include(LatLng(Mercator.lat(r.y1), Mercator.lng(r.x1)))
                        .build()
                    val options = MapSnapshotter.Options(
                        (spec.widthPx / spec.pixelRatio).roundToInt().coerceAtLeast(64),
                        (spec.heightPx / spec.pixelRatio).roundToInt().coerceAtLeast(64),
                    )
                        .withPixelRatio(spec.pixelRatio)
                        .withStyleBuilder(style())
                        .withRegion(bounds)
                        .withLogo(false)
                    val snapshotter = QuietSnapshotter(context, options)
                    cont.invokeOnCancellation { snapshotter.cancel() }
                    snapshotter.start(
                        object : MapSnapshotter.SnapshotReadyCallback {
                            override fun onSnapshotReady(snapshot: MapSnapshot) {
                                if (cont.isActive) cont.resume(runCatching { measure(snapshot, spec) }.getOrNull())
                            }
                        },
                        object : MapSnapshotter.ErrorHandler {
                            override fun onError(error: String) {
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                }
                if (result.isFailure && cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * Works out which world rectangle the picture really shows by projecting two known points with the snapshot
     * (Web Mercator is linear in x and y, so two points fix it exactly).
     */
    private fun measure(snapshot: MapSnapshot, spec: LayerSpec): MapLayer? {
        val bitmap = snapshot.bitmap
        val r = spec.rect
        val ax = r.x0 + r.w * 0.25
        val ay = r.y0 + r.h * 0.25
        val bx = r.x0 + r.w * 0.75
        val by = r.y0 + r.h * 0.75
        val pa = snapshot.pixelForLatLng(LatLng(Mercator.lat(ay), Mercator.lng(ax)))
        val pb = snapshot.pixelForLatLng(LatLng(Mercator.lat(by), Mercator.lng(bx)))
        var pax = pa.x.toDouble(); var pay = pa.y.toDouble(); var pbx = pb.x.toDouble(); var pby = pb.y.toDouble()
        var sx = (pbx - pax) / (bx - ax)
        var sy = (pby - pay) / (by - ay)
        if (!(sx > 0 && sy > 0 && sx.isFinite() && sy.isFinite())) return null
        // Guard against a projection given in map points rather than bitmap pixels.
        val fit = min(bitmap.width / r.w, bitmap.height / r.h)
        if (abs(sx * spec.pixelRatio / fit - 1) < 0.05) {
            val k = spec.pixelRatio.toDouble()
            pax *= k; pay *= k; pbx *= k; pby *= k; sx *= k; sy *= k
        }
        val x0 = ax - pax / sx
        val y0 = ay - pay / sy
        return MapLayer(bitmap, WorldRect(x0, y0, x0 + bitmap.width / sx, y0 + bitmap.height / sy))
    }

    /**
     * Maps are opaque, so they are kept as RGB_565 (dithered): half the memory of a snapshot, twice the map for the
     * clip's budget ([ClipPlan.LAYER_BUDGET_PX]).
     */
    private fun compact(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.RGB_565) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(bitmap, 0f, 0f, Paint(Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** The snapshotter without its logo and attribution overlay; the clip draws its own credit line. */
    private class QuietSnapshotter(context: Context, options: MapSnapshotter.Options) : MapSnapshotter(context, options) {
        override fun addOverlay(mapSnapshot: MapSnapshot) = Unit
    }
}
