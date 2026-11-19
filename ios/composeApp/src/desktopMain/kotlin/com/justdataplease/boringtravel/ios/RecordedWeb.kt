package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.places.LatLng

/*
 * The network as recorded answers, for the screenshots and the tests: nothing here reaches the network. The answers
 * are the Android app's own test fixtures (app/src/test: PhotonParserTest's "halki naxos", MetaTagsTest's Kyoto page,
 * WikiPhotosTest's Yasaka Shrine geosearch, WikiCreditsTest's Commons metadata, MapsLinksTest's Yasaka link and
 * MapsListsTest's "Seville" list), and pictures drawn here.
 */

/**
 * A network that answers from [answers] (the first whose test takes the address), 404 for anything else; a POST is
 * answered the same way (the AI planner's requests: the answer may read the request's body).
 */
class RecordedNet(private val answers: List<Pair<(String) -> Boolean, (NetRequest) -> NetAnswer>>, var connected: Boolean = true) : Net {
    /** Every request asked, in order. */
    val asked = mutableListOf<NetRequest>()

    override fun online(): Boolean = connected

    override suspend fun get(request: NetRequest): NetAnswer {
        asked += request
        if (!connected) throw NetException("No connection")
        val answer = answers.firstOrNull { it.first(request.url) }?.second ?: return NetAnswer(404, request.url)
        return answer(request)
    }

    override suspend fun post(request: NetRequest): NetAnswer = get(request)
}

object Recorded {
    /** The Android app's PhotonParserTest answer for "halki naxos" (trimmed real answer, with broken features). */
    val PHOTON_HALKI = """
    {"type":"FeatureCollection","features":[
      {"type":"Feature","properties":{"osm_type":"N","osm_id":806501481,"osm_key":"place","osm_value":"village","type":"city",
        "name":"Halki","county":"Naxos Regional Unit","state":"Aegean","country":"Greece","postcode":"843 02","countrycode":"GR"},
        "geometry":{"type":"Point","coordinates":[25.4839886,37.0639027]}},
      {"type":"Feature","properties":{"osm_type":"R","osm_id":99,"osm_key":"place","osm_value":"village","type":"city",
        "name":"Halki","county":"Naxos Regional Unit","state":"Aegean","country":"Greece","countrycode":"GR"},
        "geometry":{"type":"Point","coordinates":[25.4850,37.0645]}},
      {"type":"Feature","properties":{"osm_type":"N","osm_id":5132512835,"osm_key":"amenity","osm_value":"cafe","type":"house",
        "name":"Portara Coffee & Bar","street":"Naxos - Engares","city":"Naxos","county":"Naxos Regional Unit","state":"Aegean",
        "country":"Greece","countrycode":"gr"},
        "geometry":{"type":"Point","coordinates":[25.3745944,37.1074593]}},
      {"type":"Feature","properties":{"osm_type":"N","osm_id":7,"osm_key":"tourism","osm_value":"hotel","type":"house",
        "name":"Studios Agios Georgios","city":"Naxos","country":"Greece","countrycode":"GR"},
        "geometry":{"type":"Point","coordinates":[25.373,37.099]}},
      {"type":"Feature","properties":{"osm_type":"N","osm_id":8,"osm_key":"amenity","osm_value":"ferry_terminal","type":"house",
        "name":"Naxos Port","city":"Naxos","country":"Greece","countrycode":"GR"},
        "geometry":{"type":"Point","coordinates":[25.3747,37.1056]}},
      {"type":"Feature","properties":{"osm_type":"W","osm_id":9,"osm_key":"building","osm_value":"yes","type":"house",
        "street":"Papavasileiou","housenumber":"12","city":"Naxos","country":"Greece","countrycode":"GR"},
        "geometry":{"type":"Point","coordinates":[25.3723,37.1102]}},
      {"type":"Feature","properties":{"name":"No point"}},
      {"type":"Feature","properties":{"osm_key":"place"},"geometry":{"type":"Point","coordinates":[25.0,37.0]}},
      {"type":"Feature","properties":{"name":"Bad point"},"geometry":{"type":"Point","coordinates":["x",37.0]}},
      {"type":"Feature","properties":{"name":"Greece","osm_key":"place","osm_value":"country","country":"Greece","countrycode":"GR"},
        "geometry":{"type":"Point","coordinates":[22.0,39.0]}}
    ]}
    """.trimIndent()

    /** Photon's reverse answer for the desktop's spot (Syntagma Square): one feature, as /reverse gives it. */
    val PHOTON_SYNTAGMA = """
    {"type":"FeatureCollection","features":[
      {"type":"Feature","properties":{"osm_type":"W","osm_id":24,"osm_key":"place","osm_value":"square","type":"street",
        "name":"Syntagma Square","city":"Athens","state":"Attica","country":"Greece","countrycode":"GR"},
        "geometry":{"type":"Point","coordinates":[23.7348,37.9755]}}
    ]}
    """.trimIndent()

    /** The Android app's MetaTagsTest page. */
    const val KYOTO_PAGE_URL = "https://www.kyoto.travel/en/see-and-do/yasaka.html"
    const val KYOTO_IMAGE = "https://www.kyoto.travel/img/yasaka.jpg"
    val KYOTO_PAGE = """
        <!doctype html><html><head>
        <meta charset="utf-8">
        <title>Ignored when og:title is there</title>
        <meta property="og:site_name" content="Kyoto City Official Travel Guide">
        <meta property="og:title" content="Yasaka Shrine">
        <meta property="og:image" content="$KYOTO_IMAGE">
        </head><body></body></html>
    """.trimIndent()

    /** The Android app's MapsLinksTest link for Yasaka Shrine, and a short link that leads to it. */
    const val YASAKA_LONG =
        "https://www.google.com/maps/place/Yasaka+Shrine/@35.0036559,135.7785534,17z/data=!3m1!4b1!4m6!3m5!1s0x600108e9fe6b81e1:0x6c8c6b8b4bd3c8f!8m2!3d35.0036559!4d135.7785534!16zL20vMDJ5ZmNt?entry=ttu"
    const val YASAKA_SHORT = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"
    val YASAKA = LatLng(35.0036559, 135.7785534)

    /** The Android app's WikiPhotosTest answer for the Yasaka Shrine pin (real, 2026-11-07). */
    const val YASAKA_THUMB = "https://thumb.wikimedia.org/wikipedia/commons/thumb/9/96/Yasaka_jinja.jpg/960px-Yasaka_jinja.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail"
    val WIKI_YASAKA = """
        {"batchcomplete":"","query":{"pages":{
          "1584824":{"pageid":1584824,"ns":0,"title":"Yasaka Shrine","index":-1,
            "thumbnail":{"source":"$YASAKA_THUMB","width":640,"height":480},
            "coordinates":[{"lat":35.00361111,"lon":135.77861111,"primary":"","globe":"earth"}]},
          "7351895":{"pageid":7351895,"ns":0,"title":"Shijō Street","index":0,
            "thumbnail":{"source":"https://thumb.wikimedia.org/wikipedia/commons/thumb/4/4c/Shijo_dori_01.JPG/960px-Shijo_dori_01.JPG?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail","width":640,"height":480},
            "coordinates":[{"lat":35.003825,"lon":135.777222,"primary":"","globe":"earth"}]}
        }}}
    """.trimIndent()

    /** The Android app's WikiCreditsTest answer: Jane Doe, CC BY-SA 4.0. */
    val WIKI_CREDIT = """{"batchcomplete":"","query":{"normalized":[{"from":"File:Yasaka_jinja.jpg","to":"File:Yasaka jinja.jpg"}],""" +
        """"pages":{"-1":{"ns":6,"title":"File:Yasaka jinja.jpg","missing":"","known":"","imagerepository":"shared",""" +
        """"imageinfo":[{"extmetadata":{"Artist":{"value":"<a href=\"//commons.wikimedia.org/wiki/User:Jane_Doe\" title=\"User:Jane Doe\">Jane Doe</a>","source":"commons-desc-page"},""" +
        """"LicenseShortName":{"value":"CC BY-SA 4.0","source":"commons-desc-page"},"LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/4.0","source":"commons-desc-page"},""" +
        """"AttributionRequired":{"value":"true","source":"commons-desc-page"}}}]}}}}"""

    /** A shared list's short link, the long link it passes on to (MapsListsTest's), and the list (MapsListsTest's "Seville"). */
    const val LIST_SHORT = "https://maps.app.goo.gl/SevilleListShared"
    const val LIST_LONG = "https://www.google.com/maps/@/data=!4m3!11m2!2smznoUpXRzRCwLM9endLiBGRe8dnOzg!3e3!18m1!1e1?utm_source=mstt_0&g_ep=CAESBzI2"
    val LIST_SEVILLE = """)]}'
[[["mznoUpXRzRCwLM9endLiBGRe8dn",1,null,1,1],4,[2,1,"https://www.google.com/maps/placelists/list/mznoUpXRzRCwLM9endLiBGRe8dn"],["A. Traveller","https://example.com/a.png","1"],"Seville","Tapas and sights",null,null,
[
 [null,[null,null,"",null,"C. San Basilio, 16, 14004 Córdoba",[null,null,37.8767624,-4.7836871],["967465349572558039","-6613953165144310848"],"/g/1q5bn_k6x"],"Casa de Comidas La Posada","Try the salmorejo",null,null,null,[],[[1],["967465349572558039","-6613953165144310848"]],[1,0],[1,0],null,["A. Traveller","x","1"]],
 [null,[null,null,"The White House, 1600 Pennsylvania Ave NW, Washington, DC 20500",null,"",[null,null,38.8976763,-77.0365298],["1","2"]],"","",null,null,null,[],[[1],["1","2"]],[1,0],[1,0],null,["A. Traveller","x","1"]],
 [null,[null,null,"",null,"",null,["3","4"]],"No pin","",null,null,null,[],[[1],["3","4"]],[1,0],[1,0],null,null],
 [null,[null,null,"",null,"",[null,null,0.0,0.0],["5","6"]],"Null island","",null,null,null,[],[[1],["5","6"]],[1,0],[1,0],null,null],
 [null,[null,null,"",null,"Plaza de España, Sevilla",[null,null,37.3772,-5.9869],["7","8"]],"Plaza de España","",null,null,null,[],[[1],["7","8"]],[1,0],[1,0],null,null]
],
[null,null,null,[10,"10"]],[1701688509,484720000],[1765370565,792988000],7,null,null,null,null,"x",null,null,"https://www.google.com/local/content/rap/report?postId=x"]]"""

    /**
     * OpenFreeMap's positron and dark styles as the Android app's MapStylesTest records their layers (served on
     * 2026-11-07; id, type, source layer), with their glyphs, sprite and vector source; paints left out.
     */
    fun openFreeMapStyle(dark: Boolean): String {
        val layers = if (dark) listOf(
            "background:background:", "water:fill:water", "landcover_ice_shelf:fill:landcover", "landcover_glacier:fill:landcover",
            "landuse_residential:fill:landuse", "landcover_wood:fill:landcover", "landuse_park:fill:landuse",
            "waterway:line:waterway", "water_name:symbol:water_name", "building:fill:building",
            "aeroway-taxiway:line:aeroway", "highway_minor:line:transportation", "road_oneway:symbol:transportation",
            "highway_name_other:symbol:transportation_name", "boundary_state:line:boundary",
            "boundary_country_z5-:line:boundary", "place_other:symbol:place", "place_city:symbol:place",
            "place_country_major:symbol:place",
        ) else listOf(
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
        val rows = layers.joinToString(",") { row ->
            val (id, type, sourceLayer) = row.split(':')
            buildString {
                append("""{"id":"$id","type":"$type"""")
                if (type != "background") append(""","source":"openmaptiles"""")
                if (sourceLayer.isNotEmpty()) append(""","source-layer":"$sourceLayer"""")
                append(""","paint":{}}""")
            }
        }
        return """{"version":8,"name":"${if (dark) "Dark" else "Positron"}","glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",""" +
            """"sprite":"https://tiles.openfreemap.org/sprites/ofm_f384/ofm","sources":{"openmaptiles":{"type":"vector","url":"https://tiles.openfreemap.org/planet",""" +
            """"attribution":"<a href=\"https://openfreemap.org\">OpenFreeMap</a> <a href=\"https://www.openmaptiles.org/\">© OpenMapTiles</a> Data from <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a>"}},""" +
            """"layers":[$rows]}"""
    }

    /** Every answer above, and pictures for the page's og:image, the favicons and the Wikipedia thumbnail. */
    fun net(connected: Boolean = true): RecordedNet {
        val photo = samplePhoto(Scene.Town, 960, 540)
        val sea = samplePhoto(Scene.Sea, 960, 720)
        val icon = samplePhoto(Scene.Sunset, 128, 128)
        fun ok(body: String, type: String = "application/json; charset=utf-8") = { r: NetRequest -> NetAnswer(200, r.url, mapOf("Content-Type" to type), body.encodeToByteArray()) }
        fun image(bytes: ByteArray) = { r: NetRequest -> NetAnswer(200, r.url, mapOf("Content-Type" to "image/jpeg"), bytes) }
        fun redirect(to: String) = { r: NetRequest -> NetAnswer(302, r.url, mapOf("Location" to to)) }
        return RecordedNet(
            listOf(
                { u: String -> u.startsWith("https://photon.komoot.io/api/") } to ok(PHOTON_HALKI),
                { u: String -> u.startsWith("https://photon.komoot.io/reverse") } to ok(PHOTON_SYNTAGMA),
                { u: String -> u == KYOTO_PAGE_URL } to ok(KYOTO_PAGE, "text/html; charset=utf-8"),
                { u: String -> u == KYOTO_IMAGE } to image(photo),
                { u: String -> u.startsWith("https://www.google.com/s2/favicons") } to image(icon),
                { u: String -> u == YASAKA_SHORT } to redirect(YASAKA_LONG),
                { u: String -> u == LIST_SHORT } to redirect(LIST_LONG),
                { u: String -> u.startsWith("https://www.google.com/maps/preview/entitylist/getlist") } to ok(LIST_SEVILLE, "application/json; charset=utf-8"),
                { u: String -> u.startsWith(WikiPhotos.API) && "imageinfo" in u } to ok(WIKI_CREDIT),
                { u: String -> u.startsWith(WikiPhotos.API) } to ok(WIKI_YASAKA),
                { u: String -> u.startsWith("https://thumb.wikimedia.org/") } to image(sea),
                { u: String -> u == "https://tiles.openfreemap.org/styles/positron" } to ok(openFreeMapStyle(dark = false)),
                { u: String -> u == "https://tiles.openfreemap.org/styles/dark" } to ok(openFreeMapStyle(dark = true)),
            ),
            connected,
        )
    }

    /** The app's network side on recorded answers, at the desktop's fixed spot; previews kept in memory. */
    fun web(net: Net = net(), placePhotos: () -> Boolean = { true }, location: PhoneLocation = FixedLocation(LatLng(37.9755, 23.7348))): Web =
        Web(net, location, MemoryMediaFiles(), version = "1.0", language = "en", placePhotos = placePhotos)
}
