package com.justdataplease.boringtravel.clip

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStylesTest {
    /** OpenFreeMap's layer lists as served on 2026-11-07 (id:type:source-layer); paints left out. */
    private val positronLayers = listOf(
        "background:background:", "park:fill:park", "water:fill:water", "landcover_ice_shelf:fill:landcover",
        "landcover_glacier:fill:landcover", "landuse_residential:fill:landuse", "landcover_wood:fill:landcover",
        "waterway:line:waterway", "building:fill:building", "tunnel_motorway_casing:line:transportation",
        "aeroway-taxiway:line:aeroway", "aeroway-area:fill:aeroway", "road_area_pier:fill:transportation",
        "highway_path:line:transportation", "highway_major_inner:line:transportation", "railway:line:transportation",
        "boundary_3:line:boundary", "boundary_2:line:boundary", "boundary_disputed:line:boundary",
        "waterway_line_label:symbol:waterway", "water_name_point_label:symbol:water_name",
        "highway-name-major:symbol:transportation_name", "airport:symbol:aerodrome_label", "label_other:symbol:place",
        "label_town:symbol:place", "label_city:symbol:place", "label_country_1:symbol:place",
    )
    private val darkLayers = listOf(
        "background:background:", "water:fill:water", "landcover_ice_shelf:fill:landcover", "landcover_glacier:fill:landcover",
        "landuse_residential:fill:landuse", "landcover_wood:fill:landcover", "landuse_park:fill:landuse",
        "waterway:line:waterway", "water_name:symbol:water_name", "building:fill:building",
        "aeroway-taxiway:line:aeroway", "highway_minor:line:transportation", "road_oneway:symbol:transportation",
        "highway_name_other:symbol:transportation_name", "boundary_state:line:boundary",
        "boundary_country_z5-:line:boundary", "place_other:symbol:place", "place_city:symbol:place",
        "place_country_major:symbol:place",
    )

    private fun base(layers: List<String>) = buildJsonObject {
        put("version", 8)
        put("glyphs", "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf")
        put("sprite", "https://tiles.openfreemap.org/sprites/ofm_f384/ofm")
        put("metadata", buildJsonObject { put("kept", true) })
        put("sources", buildJsonObject {
            put("openmaptiles", buildJsonObject { put("type", "vector"); put("url", "https://tiles.openfreemap.org/planet") })
        })
        put("layers", JsonArray(layers.map { row ->
            val (id, type, sourceLayer) = row.split(':')
            buildJsonObject {
                put("id", id)
                put("type", type)
                if (type != "background") put("source", "openmaptiles")
                if (sourceLayer.isNotEmpty()) put("source-layer", sourceLayer)
                put("paint", buildJsonObject { put("fill-color", "#123456") })
                put("layout", buildJsonObject { put("kept", id) })
            }
        }))
    }.toString()

    private fun parse(json: String) = Json.parseToJsonElement(json) as JsonObject
    private fun layers(style: JsonObject) = (style["layers"] as JsonArray).map { it as JsonObject }
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun ids(style: JsonObject) = layers(style).map { it.text("id") }

    @Test fun streetIsOpenFreeMapByItsAddress() {
        assertNull(MapStyleJson.build(MapStyle.Street, base(positronLayers), dark = false))
        assertEquals("https://tiles.openfreemap.org/styles/positron", StreetTiles.style(false))
        assertEquals("https://tiles.openfreemap.org/styles/dark", StreetTiles.style(true))
    }

    @Test fun terrainShadesTheLandUnderRoadsAndLabelsAndRedrawsTheWater() {
        for ((layersIn, dark) in listOf(positronLayers to false, darkLayers to true)) {
            val style = parse(requireNotNull(MapStyleJson.build(MapStyle.Terrain, base(layersIn), dark)))
            val ids = ids(style)
            val shade = ids.indexOf(MapStyleJson.HILLSHADE_LAYER)
            // Over every land fill, under the first line (rivers), buildings, roads and every label.
            assertEquals(ids.indexOf("waterway") - 2, shade)
            assertTrue(ids.indexOf("landcover_wood") < shade)
            assertTrue(layers(style).withIndex().filter { it.value.text("type") == "symbol" }.all { it.index > shade })
            // The water again on top, named so the trip map recolours it with the rest of the water.
            val over = layers(style)[shade + 1]
            assertEquals("${MapStyleJson.WATER_OVER_PREFIX}0", over.text("id"))
            assertEquals("water", over.text("source-layer"))
            assertTrue(over.text("id")!!.contains("water"))
            // Everything else kept, in order, with the unknown fields.
            assertEquals(layersIn.map { it.substringBefore(':') }, ids.filterNot { it == MapStyleJson.HILLSHADE_LAYER || it!!.startsWith(MapStyleJson.WATER_OVER_PREFIX) })
            assertEquals("landcover_wood", (layers(style).first { it.text("id") == "landcover_wood" }["layout"] as JsonObject).text("kept"))
            assertTrue((style["metadata"] as JsonObject).containsKey("kept"))
            assertEquals(style.text("glyphs"), parse(base(layersIn)).text("glyphs"))
            // The elevation source: Terrarium tiles from AWS Open Data, credited.
            val dem = (style["sources"] as JsonObject)[MapStyleJson.DEM_SOURCE] as JsonObject
            assertEquals("raster-dem", dem.text("type"))
            assertEquals("terrarium", dem.text("encoding"))
            assertEquals(TerrainTiles.TILES, ((dem["tiles"] as JsonArray)[0] as JsonPrimitive).content)
            assertEquals("15", dem.text("maxzoom"))
            assertTrue(dem.text("attribution")!!.contains("Mapzen"))
            assertTrue((style["sources"] as JsonObject).containsKey("openmaptiles"))
            val paint = layers(style)[shade]["paint"] as JsonObject
            assertTrue(paint.containsKey("hillshade-shadow-color"))
        }
        assertTrue(TerrainTiles.TILES.startsWith("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"))
    }

    @Test fun terrainNeedsOpenFreeMapsStyle() {
        assertNull(MapStyleJson.build(MapStyle.Terrain, null, dark = false))
    }

    @Test fun earthIsThePicturesWithWhiteNamesAndBorders() {
        for ((layersIn, dark) in listOf(positronLayers to false, darkLayers to true)) {
            val style = parse(requireNotNull(MapStyleJson.build(MapStyle.Earth, base(layersIn), dark)))
            val ls = layers(style)
            assertEquals(MapStyleJson.EARTH_BACKGROUND, ls[0].text("id"))
            assertEquals(MapStyleJson.IMAGERY_LAYER, ls[1].text("id"))
            assertEquals("raster", ls[1].text("type"))
            // Only names (places, water) and borders come along, all renamed so the trip map leaves their colours.
            val overlay = ls.drop(2)
            assertTrue(overlay.isNotEmpty())
            assertTrue(overlay.all { it.text("id")!!.startsWith(MapStyleJson.EARTH_PREFIX) })
            assertTrue(overlay.all { (it.text("type") == "symbol" && it.text("source-layer") in setOf("place", "water_name")) ||
                (it.text("type") == "line" && it.text("source-layer") == "boundary") })
            assertTrue(overlay.any { it.text("source-layer") == "place" })
            assertFalse(overlay.any { it.text("source-layer") == "transportation" })
            for (label in overlay.filter { it.text("type") == "symbol" }) {
                assertEquals("#FFFFFF", (label["paint"] as JsonObject).text("text-color"))
            }
            assertEquals(parse(base(layersIn)).text("glyphs"), style.text("glyphs"))
            val sources = style["sources"] as JsonObject
            val imagery = sources[MapStyleJson.IMAGERY_SOURCE] as JsonObject
            assertEquals(ImageryTiles.TILES, ((imagery["tiles"] as JsonArray)[0] as JsonPrimitive).content)
            assertEquals("14", imagery.text("maxzoom"))
            assertTrue(imagery.text("attribution")!!.contains("EOX IT Services GmbH"))
            assertTrue(sources.containsKey("openmaptiles"))
        }
    }

    @Test fun earthWithoutOpenFreeMapIsThePicturesAlone() {
        for (base in listOf(null, "not a style")) {
            val style = parse(requireNotNull(MapStyleJson.build(MapStyle.Earth, base, dark = false)))
            assertEquals(listOf(MapStyleJson.EARTH_BACKGROUND, MapStyleJson.IMAGERY_LAYER), ids(style))
            assertNull(style["glyphs"])
            assertEquals(setOf(MapStyleJson.IMAGERY_SOURCE), (style["sources"] as JsonObject).keys)
        }
    }

    @Test fun theImageryIsSentinel2Cloudless2016InWebMercatorAndCredited() {
        // EOX's WMTS: layer s2cloudless_3857 (the 2016 vintage), tile matrix set "g", rows before columns.
        assertTrue(ImageryTiles.TILES.contains("/s2cloudless_3857/default/g/{z}/{y}/{x}.jpg"))
        assertTrue(ImageryTiles.TILES.startsWith("https://"))
        assertEquals(14, ImageryTiles.MAX_ZOOM)
        assertEquals(14.0, MapStyle.Earth.maxZoom, 0.0)
        // The vector maps go to building level.
        assertEquals(18.0, MapStyle.Street.maxZoom, 0.0)
        assertEquals(18.0, MapStyle.Terrain.maxZoom, 0.0)
        assertTrue(ImageryTiles.CREDIT.startsWith("EOxCloudless 2016"))
        assertTrue(ImageryTiles.CREDIT.contains("EOX IT Services GmbH"))
        assertTrue(ImageryTiles.CREDIT.contains("Contains modified Copernicus Sentinel data 2016"))
    }

    @Test fun eachMapCreditsItsSources() {
        assertEquals(listOf(StreetTiles.CREDIT), MapStyle.Street.credits)
        assertEquals(listOf(StreetTiles.CREDIT, TerrainTiles.CREDIT), MapStyle.Terrain.credits)
        // Earth's names come from OpenStreetMap through OpenFreeMap.
        assertEquals(listOf(ImageryTiles.CREDIT, StreetTiles.CREDIT), MapStyle.Earth.credits)
        assertTrue(StreetTiles.CREDIT.contains("OpenStreetMap"))
    }

    @Test fun savedIdsReadBackAndUnknownOnesAreStreet() {
        for (m in MapStyle.entries) assertEquals(m, MapStyle.of(m.id))
        assertEquals(MapStyle.Street, MapStyle.of(null))
        assertEquals(MapStyle.Street, MapStyle.of("satellite-2030"))
        assertEquals(listOf("Street", "Terrain", "Earth", "Globe"), MapStyle.entries.map { it.label })
        // The trip map keeps its three; the clip has the globe too.
        assertEquals(listOf("Street", "Terrain", "Earth"), MapStyle.tripMap.map { it.label })
    }

    @Test fun globesStreetMapIsTheStreetStyleInTheLooksColours() {
        val tint = MapTint.of(land = 0xFFF4EBDD.toInt(), sea = 0xFF9CC9D6.toInt(), ink = 0xFF1E2A3A.toInt())
        for ((layersIn, dark) in listOf(positronLayers to false, darkLayers to true)) {
            val style = parse(requireNotNull(MapStyleJson.build(MapStyle.Globe, base(layersIn), dark, tint)))
            val byId = layers(style).associateBy { it.text("id") }
            fun paint(id: String) = byId.getValue(id)["paint"] as JsonObject
            assertEquals("#F4EBDD", paint("background").text("background-color"))
            assertEquals("#9CC9D6", paint("water").text("fill-color"))
            assertEquals("#9CC9D6", paint("waterway").text("line-color"))
            // Woods a touch darker than the land (5 % of the ink).
            assertEquals(MapTint.hex(tint.park), paint("landcover_wood").text("fill-color"))
            assertTrue(tint.park != tint.land)
            for (label in layers(style).filter { it.text("type") == "symbol" }) {
                assertEquals("#1E2A3A", (label["paint"] as JsonObject).text("text-color"))
                assertEquals("#F4EBDD", (label["paint"] as JsonObject).text("text-halo-color"))
            }
            // Roads, buildings and borders as they were; the rest of the style kept.
            assertEquals("#123456", paint("building").text("fill-color"))
            assertEquals(ids(parse(base(layersIn))), ids(style))
            assertEquals(parse(base(layersIn)).text("glyphs"), style.text("glyphs"))
            assertTrue((style["metadata"] as JsonObject).containsKey("kept"))
        }
        // Without its colours or OpenFreeMap's style: plain Street by its address.
        assertNull(MapStyleJson.build(MapStyle.Globe, base(positronLayers), dark = false))
        assertNull(MapStyleJson.build(MapStyle.Globe, null, dark = false, tint = tint))
        assertEquals(18.0, MapStyle.Globe.maxZoom, 0.0)
    }
}
