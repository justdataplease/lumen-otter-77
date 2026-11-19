package com.justdataplease.boringtravel.ios

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.clip.MapStyleJson
import com.justdataplease.boringtravel.clip.MapTint
import com.justdataplease.boringtravel.clip.StreetTiles
import com.justdataplease.boringtravel.ui.map.MapData
import com.justdataplease.boringtravel.ui.map.MapGeoJson
import com.justdataplease.boringtravel.ui.theme.BtColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * What a map is drawn with, as the Android app's ui/map/BtMap.kt draws it: OpenFreeMap's style for the look (positron on
 * light looks, dark on Trail) in the look's colours, Terrain's hill shading or Earth's pictures from the shared
 * clip/MapStyles.kt, and on top the app's own layers (the planned route dashed, the travelled route solid with its gaps
 * dashed, flag pins for the plan, tick pins for stops with their names, ink pins for notes, the ring at "now").
 *
 * Android loads Street by its address and recolours the loaded layers; here every look is one style JSON, recoloured
 * before MapLibre reads it (MapStyleJson.tinted, the same rules as BtMap.recolour), so the iPhone's map code only loads
 * a style and fills the app's sources. The rules all live here, in common code, and are tested on the desktop.
 */

/** The map's colours, taken from the current look (ARGB). [plan] draws the planned route, dashed. */
data class MapColors(
    val land: Int, val sea: Int, val route: Int, val surface: Int, val ink: Int, val muted: Int, val park: Int, val plan: Int,
) {
    companion object {
        fun from(c: BtColors) = MapColors(
            land = c.land.toArgb(), sea = c.sea.toArgb(), route = c.route.toArgb(), surface = c.surface.toArgb(),
            ink = c.ink.toArgb(), muted = c.muted.toArgb(), park = mix(c.land, c.ink, 0.05f).toArgb(), plan = c.plan.toArgb(),
        )

        private fun mix(a: Color, b: Color, t: Float) = Color(
            a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
        )
    }
}

/** A style ready for MapLibre: its JSON, and whether it is the plain stand-in for a map that couldn't be had. */
data class MapStyleBuild(val json: String, val offline: Boolean)

/** The app's sources and layers on every map, named `bt-…` (the recolouring leaves those alone), as BtMap adds them. */
object MapLayers {
    const val S_ROUTES = "bt-routes"
    const val S_PLANNED = "bt-planned"
    const val S_PLANS = "bt-plans"
    const val S_STOPS = "bt-stops"
    const val S_ENTRIES = "bt-entries"
    const val S_ME = "bt-me"
    const val L_PLANNED = "bt-planned-line"
    const val L_ROUTE = "bt-route"
    const val L_ROUTE_JUMP = "bt-route-jump"
    const val L_PLANS = "bt-plans"
    const val L_STOPS = "bt-stops"
    const val L_STOP_NAMES = "bt-stop-names"
    const val L_ENTRIES = "bt-entries"
    const val L_ME_HALO = "bt-me-halo"
    const val L_ME = "bt-me"
    const val IMG_ENTRY = "bt-entry-pin"
    const val IMG_STOP = "bt-stop-pin"
    const val IMG_PLAN = "bt-plan-pin"

    /** OpenFreeMap's font for the stops' names. */
    const val FONT = "Noto Sans Regular"

    /** The sources in the order BtMap adds them. */
    val SOURCES = listOf(S_PLANNED, S_ROUTES, S_PLANS, S_STOPS, S_ENTRIES, S_ME)

    /** What each of the app's sources holds for [data], as GeoJSON (coordinates as [lng, lat]). */
    fun sources(data: MapData): Map<String, String> = linkedMapOf(
        S_PLANNED to MapGeoJson.line(data.planned),
        S_ROUTES to MapGeoJson.routes(data.routes),
        S_PLANS to MapGeoJson.marks(data.plans),
        S_STOPS to MapGeoJson.marks(data.stops),
        S_ENTRIES to MapGeoJson.marks(data.entries),
        S_ME to MapGeoJson.point(data.me),
    )

    /**
     * [style] with the app's sources (empty until filled) and layers on top of everything. The stops' names need the
     * style's glyphs: without them (the plain stand-in) or without [stopNames] they are left out.
     */
    fun addTo(style: String, c: MapColors, stopNames: Boolean): String {
        val root = Json.parseToJsonElement(style) as JsonObject
        val sources = LinkedHashMap((root["sources"] as? JsonObject).orEmpty())
        for (id in SOURCES) sources[id] = buildJsonObject {
            put("type", "geojson")
            put("data", Json.parseToJsonElement(EMPTY))
        }
        val names = stopNames && root["glyphs"] != null
        val layers = (root["layers"] as? JsonArray).orEmpty() + layers(c, names)
        return JsonObject(root + mapOf("sources" to JsonObject(sources), "layers" to JsonArray(layers))).toString()
    }

    /** The app's layers, bottom to top: the planned route under the travelled one, pins, names, notes, "now". */
    fun layers(c: MapColors, stopNames: Boolean): List<JsonElement> = buildList {
        add(line(L_PLANNED, S_PLANNED, c.plan, 2.6, opacity = 0.85, dash = listOf(2.2, 2.2), join = true))
        val jump = buildJsonArray { add("=="); add(buildJsonArray { add("get"); add(MapGeoJson.P_JUMP) }); add(true) }
        add(line(L_ROUTE_JUMP, S_ROUTES, c.route, 2.5, opacity = 0.8, dash = listOf(2.0, 2.0), filter = jump))
        add(line(L_ROUTE, S_ROUTES, c.route, 4.0, join = true, filter = buildJsonArray { add("!"); add(jump) }))
        add(pin(L_PLANS, S_PLANS, IMG_PLAN, padding = 5.0))
        add(pin(L_STOPS, S_STOPS, IMG_STOP, padding = 6.0))
        if (stopNames) add(buildJsonObject {
            put("id", L_STOP_NAMES)
            put("type", "symbol")
            put("source", S_STOPS)
            putJsonObject("layout") {
                put("text-field", buildJsonArray { add("get"); add(MapGeoJson.P_NAME) })
                putJsonArray("text-font") { add(FONT) }
                put("text-size", 12)
                put("text-anchor", "left")
                putJsonArray("text-offset") { add(0.9); add(0) }
                put("text-optional", true)
            }
            putJsonObject("paint") {
                put("text-color", MapTint.hex(c.ink))
                put("text-halo-color", MapTint.hex(c.surface))
                put("text-halo-width", 1.5)
            }
        })
        add(pin(L_ENTRIES, S_ENTRIES, IMG_ENTRY, padding = null))
        add(buildJsonObject {
            put("id", L_ME_HALO)
            put("type", "circle")
            put("source", S_ME)
            putJsonObject("paint") {
                put("circle-radius", 15)
                put("circle-color", MapTint.hex(c.route))
                put("circle-opacity", 0.12)
                put("circle-stroke-color", MapTint.hex(c.route))
                put("circle-stroke-width", 2.5)
                put("circle-stroke-opacity", 0.6)
            }
        })
        add(buildJsonObject {
            put("id", L_ME)
            put("type", "circle")
            put("source", S_ME)
            putJsonObject("paint") {
                put("circle-radius", 6)
                put("circle-color", MapTint.hex(c.route))
                put("circle-stroke-color", MapTint.hex(c.surface))
                put("circle-stroke-width", 2.5)
            }
        })
    }

    private fun line(
        id: String, source: String, color: Int, width: Double, opacity: Double? = null, dash: List<Double>? = null,
        join: Boolean = false, filter: JsonArray? = null,
    ) = buildJsonObject {
        put("id", id)
        put("type", "line")
        put("source", source)
        if (filter != null) put("filter", filter)
        putJsonObject("layout") {
            put("line-cap", "round")
            if (join) put("line-join", "round")
        }
        putJsonObject("paint") {
            put("line-color", MapTint.hex(color))
            put("line-width", width)
            if (opacity != null) put("line-opacity", opacity)
            if (dash != null) putJsonArray("line-dasharray") { dash.forEach { add(it) } }
        }
    }

    /** A pin layer: the picture's tip on the point; [padding] null = notes, drawn over everything and never hidden. */
    private fun pin(id: String, source: String, image: String, padding: Double?) = buildJsonObject {
        put("id", id)
        put("type", "symbol")
        put("source", source)
        putJsonObject("layout") {
            put("icon-image", image)
            put("icon-anchor", "bottom")
            if (padding == null) {
                put("icon-allow-overlap", true)
                put("icon-ignore-placement", true)
            } else {
                put("icon-allow-overlap", false)
                put("icon-padding", padding)
            }
        }
    }

    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
}

/**
 * The style for a map in [style] and the look's colours (Android: MapStyleLoader with BtMap.recolour): OpenFreeMap's
 * own style ([base], null when it couldn't be had) with Terrain's shading or Earth's pictures, recoloured, with the
 * app's layers on top. Without OpenFreeMap's style, Street and Terrain are the plain stand-in ([offline]); Earth is its
 * pictures alone, as on Android.
 */
object MapStyles {
    /** How far past the satellite pictures' last zoom the traveller may still zoom in on Earth (BtMap's). */
    const val EARTH_EXTRA_ZOOM = 2.0

    /** MapLibre's own closest zoom, for every other look. */
    const val DEFAULT_MAX_ZOOM = 25.5

    fun maxZoom(style: MapStyle): Double =
        if (style == MapStyle.Earth) com.justdataplease.boringtravel.clip.ImageryTiles.MAX_ZOOM + EARTH_EXTRA_ZOOM else DEFAULT_MAX_ZOOM

    fun build(style: MapStyle, base: String?, dark: Boolean, c: MapColors, stopNames: Boolean): MapStyleBuild {
        val built = runCatching {
            when (style) {
                MapStyle.Terrain -> base?.let { MapStyleJson.terrain(it, dark) }
                MapStyle.Earth -> MapStyleJson.earth(base)
                MapStyle.Street, MapStyle.Globe -> base
            }
        }.getOrNull() ?: return offline(c)
        val tint = MapTint(c.land, c.sea, c.park, c.ink)
        return runCatching { MapStyleBuild(MapLayers.addTo(MapStyleJson.tinted(built, tint), c, stopNames), offline = false) }.getOrElse { offline(c) }
    }

    /** Just the look's land colour: no tiles and no glyphs (so no stop names), with the app's layers on it. */
    fun offline(c: MapColors): MapStyleBuild {
        val plain = """{"version":8,"name":"offline","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"${MapTint.hex(c.land)}"}}]}"""
        return MapStyleBuild(MapLayers.addTo(plain, c, stopNames = false), offline = true)
    }
}

/**
 * OpenFreeMap's style for the light or dark looks, fetched once (Android's MapStyleLoader: 15 s, kept in memory and in
 * the caches folder, so a map seen before still builds offline).
 */
object MapBaseStyles {
    private val memory = HashMap<String, String>()

    /** The cache file for [url]: "map-styles/positron.json". */
    fun cachePath(url: String) = "map-styles/" + url.substringAfterLast('/') + ".json"

    suspend fun base(web: Web, dark: Boolean): String? {
        val url = StreetTiles.style(dark)
        memory[url]?.let { return it }
        val fetched = runCatching {
            web.net.ask(NetRequest(url, mapOf("User-Agent" to web.searchAgent), maxBytes = MAX_BYTES, timeoutMs = TIMEOUT_MS, readTimeoutMs = TIMEOUT_MS))
        }.getOrNull()?.takeIf { it.ok }?.text()?.takeIf(::isStyle)
        if (fetched != null) {
            memory[url] = fetched
            runCatching { web.cache.write(cachePath(url), fetched.encodeToByteArray()) }
            return fetched
        }
        return runCatching { web.cache.read(cachePath(url))?.decodeToString() }.getOrNull()?.takeIf(::isStyle)?.also { memory[url] = it }
    }

    /** Forgets what was fetched (tests start from nothing). */
    fun forget() = memory.clear()

    fun isStyle(json: String): Boolean =
        runCatching { (Json.parseToJsonElement(json) as? JsonObject)?.containsKey("layers") == true }.getOrDefault(false)

    private const val TIMEOUT_MS = 15_000L
    private const val MAX_BYTES = 4L * 1024 * 1024
}
