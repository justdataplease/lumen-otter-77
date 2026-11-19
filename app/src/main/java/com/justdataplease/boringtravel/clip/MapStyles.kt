package com.justdataplease.boringtravel.clip

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The map looks of the clip and the trip map: Street (OpenFreeMap), Terrain (the street map with hill shading) and
 * Earth (satellite pictures); the clip also has Globe (the app's own globe from space, with the street map in the
 * look's colours draped over it close in, see [ClipGlobe]). Every source's address, sharpest zoom and credit sits in
 * one object below ([StreetTiles], [TerrainTiles], [ImageryTiles], [GlobeShapes]), so a provider can be swapped in one
 * place.
 */
enum class MapStyle(val id: String, val label: String) {
    Street("street", "Street"),
    Terrain("terrain", "Terrain"),
    Earth("earth", "Earth"),
    Globe("globe", "Globe");

    /**
     * The closest map zoom worth showing; past it the pictures only get blurrier. The clip's camera never goes closer
     * ([ZoomLimits]): Street and Terrain to a few buildings (vector roads and names stay crisp; Terrain's soft hill
     * shading just gets softer), Earth to its satellite pictures' last zoom, Globe as far as the street map draped
     * over it.
     */
    val maxZoom: Double
        get() = when (this) {
            Street, Terrain, Globe -> StreetTiles.MAX_ZOOM.toDouble()
            Earth -> ImageryTiles.MAX_ZOOM.toDouble()
        }

    /** The credit lines a clip with this map carries (the renderer wraps them to the frame). */
    val credits: List<String>
        get() = when (this) {
            Street -> listOf(StreetTiles.CREDIT)
            Terrain -> listOf(StreetTiles.CREDIT, TerrainTiles.CREDIT)
            Earth -> listOf(ImageryTiles.CREDIT, StreetTiles.CREDIT)
            Globe -> listOf(GlobeShapes.CREDIT, StreetTiles.CREDIT)
        }

    companion object {
        /** The looks the trip map offers (a flat MapLibre map: no Globe). */
        val tripMap: List<MapStyle> = listOf(Street, Terrain, Earth)

        fun of(id: String?): MapStyle = entries.firstOrNull { it.id == id } ?: Street
    }
}

/**
 * The globe's own shapes: Natural Earth's 1:110m country outlines bundled with the app (assets/geo, public domain),
 * as on the Map tab's globe. Credited though not required.
 */
object GlobeShapes {
    const val CREDIT = "Country shapes: Natural Earth"
}

/**
 * The look's colours for a street map drawn in them (Globe's close views, like the trip map's own recolouring): land,
 * sea, parks and woods, names. ARGB ints.
 */
data class MapTint(val land: Int, val sea: Int, val park: Int, val ink: Int) {
    companion object {
        /** Parks a touch darker than the land (5 % of the ink), as on the trip map. */
        fun of(land: Int, sea: Int, ink: Int): MapTint = MapTint(land or OPAQUE, sea or OPAQUE, mix(land, ink, 0.05), ink or OPAQUE)

        private const val OPAQUE = 0xFF shl 24

        private fun mix(a: Int, b: Int, t: Double): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt().coerceIn(0, 255)
            return OPAQUE or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** "#RRGGBB" for a style's paint. */
        fun hex(color: Int): String = String.format(java.util.Locale.ROOT, "#%06X", color and 0xFFFFFF)
    }
}

/** OpenFreeMap's vector styles: no key, no per-view cost; the same tiles as the rest of the app's maps. */
object StreetTiles {
    const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
    const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
    const val CREDIT = "© OpenFreeMap © OpenMapTiles © OpenStreetMap"
    /**
     * The closest zoom the clip shows: the vector tiles end at zoom 14 but draw crisply well past it; at 18 the
     * frame's short side is about 150–200 m, a few buildings.
     */
    const val MAX_ZOOM = 18

    fun style(dark: Boolean) = if (dark) STYLE_DARK else STYLE_LIGHT
}

/**
 * Elevation for hill shading: Mapzen's Terrain Tiles on AWS Open Data (Terrarium PNGs, zoom 0–15, no key). Their
 * attribution page (github.com/tilezen/joerd, docs/attribution.md) lists every source; the clip carries the short
 * line [CREDIT], the map's attribution button the longer [ATTRIBUTION_HTML] with a link to the full list.
 */
object TerrainTiles {
    const val TILES = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
    const val ENCODING = "terrarium"
    const val TILE_SIZE = 256
    const val MAX_ZOOM = 15
    const val CREDIT = "Terrain: Mapzen, AWS Open Data · USGS, NOAA, Copernicus EU-DEM and others"
    const val ATTRIBUTION_HTML = "<a href=\"https://github.com/tilezen/joerd/blob/master/docs/attribution.md\">" +
        "Terrain: Mapzen, AWS Open Data · USGS, NOAA, Copernicus EU-DEM and others</a>"
}

/**
 * Satellite pictures: EOX's Sentinel-2 cloudless 2016 in Web Mercator (layer s2cloudless_3857, JPEG, zoom 0–14).
 * The 2016 vintage is CC BY 4.0 (EOX's FAQ: free for commercial apps and video exports, with the credit on the
 * picture); the 2018 and later layers are non-commercial. Past zoom 14 the server only enlarges, so the camera stops
 * there ([MapStyle.maxZoom]).
 */
object ImageryTiles {
    const val TILES = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless_3857/default/g/{z}/{y}/{x}.jpg"
    const val TILE_SIZE = 256
    const val MAX_ZOOM = 14
    const val CREDIT = "EOxCloudless 2016 https://cloudless.eox.at by EOX IT Services GmbH " +
        "(Contains modified Copernicus Sentinel data 2016 & 2017)"
    const val ATTRIBUTION_HTML = "<a href=\"https://cloudless.eox.at\">EOxCloudless 2016</a> by " +
        "<a href=\"https://eox.at\">EOX IT Services GmbH</a> (Contains modified Copernicus Sentinel data 2016 &amp; 2017)"
}

/**
 * Builds the Terrain and Earth styles as MapLibre style JSON on top of OpenFreeMap's own style (fetched by the
 * caller), so labels, fonts and sprites stay OpenFreeMap's. Plain Kotlin and tested; the base style's unknown fields
 * are kept as they are.
 *
 * Added layers are named `bt-…` (the trip map leaves those colours alone) except the water drawn back over the hill
 * shading, which keeps "water" in its name so the trip map recolours it like the rest of the water.
 */
object MapStyleJson {
    const val DEM_SOURCE = "bt-dem"
    const val HILLSHADE_LAYER = "bt-hillshade"
    const val IMAGERY_SOURCE = "bt-imagery"
    const val IMAGERY_LAYER = "bt-earth-imagery"
    const val EARTH_BACKGROUND = "bt-earth-background"
    const val EARTH_PREFIX = "bt-earth-"
    const val WATER_OVER_PREFIX = "water-over-hillshade-"

    /** Ground layers the hill shading goes above; it goes under the first layer that is not one of them. */
    private val GROUND_TYPES = setOf("background", "fill", "raster")
    private val ABOVE_GROUND = setOf("building", "transportation", "transportation_name", "aeroway", "boundary")

    /**
     * The style for [style]: null for Street (use [StreetTiles.style] directly). [base] is OpenFreeMap's JSON. Globe's
     * draped street map is the street style in [tint]'s colours ([tinted]); without a tint or the base style, plain
     * Street.
     */
    fun build(style: MapStyle, base: String?, dark: Boolean, tint: MapTint? = null): String? = when (style) {
        MapStyle.Street -> null
        MapStyle.Terrain -> base?.let { terrain(it, dark) }
        MapStyle.Earth -> earth(base)
        MapStyle.Globe -> if (base != null && tint != null) tinted(base, tint) else null
    }

    /**
     * The street style in the look's colours, as the trip map recolours it (BtMap): the background is the land, water
     * fills and rivers the sea, land cover, land use and parks a touch darker than the land, every name in the ink on
     * a land-coloured halo. Our own `bt-` layers and everything else stay as they are.
     */
    fun tinted(base: String, tint: MapTint): String {
        val root = parse(base)
        val land = JsonPrimitive(MapTint.hex(tint.land))
        val sea = JsonPrimitive(MapTint.hex(tint.sea))
        val park = JsonPrimitive(MapTint.hex(tint.park))
        val ink = JsonPrimitive(MapTint.hex(tint.ink))
        val layers = layers(root).map { layer ->
            val id = id(layer).lowercase()
            val type = type(layer)
            val paint: Map<String, JsonElement>? = when {
                id.startsWith("bt-") -> null
                type == "background" -> mapOf("background-color" to land)
                type == "fill" && (id.contains("water") || id.contains("ocean")) -> mapOf("fill-color" to sea)
                type == "line" && id.startsWith("waterway") -> mapOf("line-color" to sea)
                type == "fill" && (id.startsWith("landcover") || id.startsWith("landuse") || id.startsWith("park")) -> mapOf("fill-color" to park)
                type == "symbol" -> mapOf(
                    "text-color" to ink, "text-halo-color" to land,
                    "text-halo-width" to JsonPrimitive(1.25), "text-halo-blur" to JsonPrimitive(0.5),
                )
                else -> null
            }
            if (paint == null) layer else layer.with("paint", JsonObject(((layer as? JsonObject)?.get("paint") as? JsonObject).orEmpty() + paint))
        }
        return JsonObject(root + ("layers" to JsonArray(layers))).toString()
    }

    /**
     * The street style with hill shading over the land fills (parks, woods, ice) and under roads, buildings and
     * labels. Water is drawn again over the shading so the sea floor doesn't show through.
     */
    fun terrain(base: String, dark: Boolean): String {
        val root = parse(base)
        val sources = (root["sources"] as? JsonObject).orEmpty().toMutableMap()
        sources[DEM_SOURCE] = buildJsonObject {
            put("type", "raster-dem")
            putJsonArray("tiles") { add(JsonPrimitive(TerrainTiles.TILES)) }
            put("encoding", TerrainTiles.ENCODING)
            put("tileSize", TerrainTiles.TILE_SIZE)
            put("maxzoom", TerrainTiles.MAX_ZOOM)
            put("attribution", TerrainTiles.ATTRIBUTION_HTML)
        }
        val layers = layers(root).toMutableList()
        val at = layers.indexOfFirst { !isGround(it) }.let { if (it < 0) layers.size else it }
        val water = layers.subList(0, at).filter { type(it) == "fill" && sourceLayer(it) == "water" }
            .mapIndexed { i, layer -> layer.with("id", JsonPrimitive("$WATER_OVER_PREFIX$i")) }
        layers.addAll(at, listOf(hillshade(dark)) + water)
        return JsonObject(root + mapOf("sources" to JsonObject(sources), "layers" to JsonArray(layers))).toString()
    }

    /**
     * Satellite pictures with OpenFreeMap's place and water names (white on a dark halo) and borders on top, when
     * [base] is there; without it, the pictures alone.
     */
    fun earth(base: String?): String {
        val root = base?.let { runCatching { parse(it) }.getOrNull() }
        val sources = LinkedHashMap<String, JsonElement>()
        sources[IMAGERY_SOURCE] = buildJsonObject {
            put("type", "raster")
            putJsonArray("tiles") { add(JsonPrimitive(ImageryTiles.TILES)) }
            put("tileSize", ImageryTiles.TILE_SIZE)
            put("maxzoom", ImageryTiles.MAX_ZOOM)
            put("attribution", ImageryTiles.ATTRIBUTION_HTML)
        }
        val layers = ArrayList<JsonElement>()
        layers += buildJsonObject {
            put("id", EARTH_BACKGROUND)
            put("type", "background")
            put("paint", buildJsonObject { put("background-color", "#0E1C26") })
        }
        layers += buildJsonObject {
            put("id", IMAGERY_LAYER)
            put("type", "raster")
            put("source", IMAGERY_SOURCE)
            put("paint", buildJsonObject { put("raster-fade-duration", 0) })
        }
        val out = LinkedHashMap<String, JsonElement>()
        out["version"] = JsonPrimitive(8)
        out["name"] = JsonPrimitive("BoringTravel Earth")
        if (root != null) {
            val overlay = layers(root).mapNotNull { layer ->
                when {
                    type(layer) == "line" && sourceLayer(layer) == "boundary" -> layer.with("id", JsonPrimitive(EARTH_PREFIX + id(layer)))
                        .with("paint", JsonObject(((layer as? JsonObject)?.get("paint") as? JsonObject).orEmpty() + mapOf(
                            "line-color" to JsonPrimitive("rgba(255,255,255,0.55)"),
                        )))
                    type(layer) == "symbol" && sourceLayer(layer) in setOf("place", "water_name") ->
                        layer.with("id", JsonPrimitive(EARTH_PREFIX + id(layer))).with("paint", buildJsonObject {
                            put("text-color", "#FFFFFF")
                            put("text-halo-color", "rgba(0,0,0,0.72)")
                            put("text-halo-width", 1.4)
                            put("text-halo-blur", 0.6)
                        })
                    else -> null
                }
            }
            val used = overlay.mapNotNull { (it as? JsonObject)?.get("source")?.let { s -> (s as? JsonPrimitive)?.contentOrNull } }.toSet()
            val baseSources = (root["sources"] as? JsonObject).orEmpty()
            for (name in used) baseSources[name]?.let { sources[name] = it }
            if (overlay.isNotEmpty() && used.all { it in sources }) {
                root["glyphs"]?.let { out["glyphs"] = it }
                root["sprite"]?.let { out["sprite"] = it }
                layers += overlay
            }
        }
        out["sources"] = JsonObject(sources)
        out["layers"] = JsonArray(layers)
        return JsonObject(out).toString()
    }

    private fun hillshade(dark: Boolean): JsonObject = buildJsonObject {
        put("id", HILLSHADE_LAYER)
        put("type", "hillshade")
        put("source", DEM_SOURCE)
        put("paint", buildJsonObject {
            put("hillshade-illumination-anchor", "map")
            put("hillshade-illumination-direction", 315)
            if (dark) {
                put("hillshade-exaggeration", 0.55)
                put("hillshade-shadow-color", "rgba(0,0,0,0.7)")
                put("hillshade-highlight-color", "rgba(255,255,255,0.12)")
                put("hillshade-accent-color", "rgba(0,0,0,0.35)")
            } else {
                put("hillshade-exaggeration", 0.45)
                put("hillshade-shadow-color", "rgba(72,62,52,0.55)")
                put("hillshade-highlight-color", "rgba(255,255,255,0.4)")
                put("hillshade-accent-color", "rgba(72,62,52,0.25)")
            }
        })
    }

    private fun isGround(layer: JsonElement) = type(layer) in GROUND_TYPES && sourceLayer(layer) !in ABOVE_GROUND

    private fun parse(json: String): JsonObject =
        Json.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("A map style is a JSON object")

    private fun layers(root: JsonObject): List<JsonElement> = (root["layers"] as? JsonArray).orEmpty()
    private fun text(layer: JsonElement, key: String) = ((layer as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
    private fun type(layer: JsonElement) = text(layer, "type")
    private fun id(layer: JsonElement) = text(layer, "id").orEmpty()
    private fun sourceLayer(layer: JsonElement) = text(layer, "source-layer")
    private fun JsonElement.with(key: String, value: JsonElement): JsonElement =
        JsonObject((this as? JsonObject).orEmpty() + (key to value))
}
