package com.justdataplease.boringtravel.art

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** The ten kinds of drawn place (design board "Drawn pictures"). */
enum class SceneKind(val id: String, val label: String) {
    Mountain("mountain", "Mountain"), Lake("lake", "Lake"), City("city", "City"), Village("village", "Village"),
    Coast("coast", "Coast"), Forest("forest", "Forest"), Glacier("glacier", "Glacier"), Countryside("countryside", "Countryside"),
    Desert("desert", "Desert"), Night("night", "City at night");

    companion object {
        fun of(id: String?): SceneKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One theme's colours for the drawn pictures, exactly as on the board. Colours are "#RRGGBB". [sky] runs top to
 * bottom, [buildings] are five building colours, [dark] the night sky top to bottom. [sketch] is pen-line art:
 * every shape outlined in [ink] on paper.
 */
data class ScenePalette(
    val key: String,
    val sky: List<String>, val sun: String, val far: String, val near: String, val snow: String, val glow: String,
    val water: String, val water2: String, val grass: String, val grass2: String, val ground: String,
    val buildings: List<String>, val win: String, val ink: String, val dark: List<String>, val lit: String, val sand: String,
) {
    val sketch: Boolean get() = key == ScenePalettes.SKETCH
}

object ScenePalettes {
    const val POP = "pop"
    const val TRAIL = "trail"
    const val STICKER = "sticker"
    const val SKETCH = "sketch"
    const val MARKET = "market"

    val pop = ScenePalette(
        POP, listOf("#7CCBF0", "#9FDAF5", "#C6EBFA"), "#FFC93C", "#5E8FE0", "#2F4FA8", "#FFFFFF", "#FF8A6B",
        "#13A89E", "#5FD3C8", "#7BC86C", "#4FA65A", "#E9D9B8",
        listOf("#FF5A3C", "#13A89E", "#FFC93C", "#2F6FDB", "#FF8FB1"), "#FFFFFF", "#1D1B3A", listOf("#1D1B3A", "#2F2A5C", "#4A3F7A"), "#FFC93C", "#F2D99B",
    )
    val trail = ScenePalette(
        TRAIL, listOf("#3B2B5C", "#E9785A", "#FFC27A"), "#FFE0A3", "#6B4A6E", "#2B2E3F", "#FBE3D4", "#E36B4F",
        "#2E7FA8", "#7CC0DE", "#2F6B4F", "#1F5240", "#C98F4F",
        listOf("#E36B4F", "#F6C445", "#2F6B4F", "#F6F1E4", "#6B4A6E"), "#FFE7B8", "#3D2448", listOf("#24132C", "#3D2448", "#2C3A5C"), "#F6C445", "#E9C48F",
    )
    val sticker = ScenePalette(
        STICKER, listOf("#BFDDF2", "#D4E8F6", "#E8F3FB"), "#FFE066", "#A9BEDA", "#6F8DB8", "#FFFFFF", "#FF8FB1",
        "#5BA4D6", "#A9D3EE", "#8FD6B0", "#5FB78E", "#F3E3C4",
        listOf("#FF8FB1", "#7FD8B2", "#FFE066", "#A9BEDA", "#FFB38A"), "#FFFFFF", "#1E2A5A", listOf("#1E2A5A", "#2E3D78", "#46559A"), "#FFE066", "#F7E3B5",
    )
    val sketch = ScenePalette(
        SKETCH, listOf("#FCFCF9", "#FCFCF9", "#FCFCF9"), "#FFE45C", "#FCFCF9", "#EEF0F7", "#FFFFFF", "#F7C2B6",
        "#E6EEF8", "#FCFCF9", "#FCFCF9", "#EEF0F7", "#FCFCF9",
        listOf("#FCFCF9", "#FFE45C", "#FCFCF9", "#F7C2B6", "#FCFCF9"), "#FCFCF9", "#1F2D6B", listOf("#E6EAF5", "#E6EAF5", "#E6EAF5"), "#FFE45C", "#FCFCF9",
    )
    val market = ScenePalette(
        MARKET, listOf("#6B2D5C", "#8A3E6E", "#B4566F"), "#F2C14E", "#4A1E40", "#3A1532", "#F8F2E6", "#E4572E",
        "#2F7F7A", "#7BC4BE", "#7BAF5A", "#5C8F42", "#C98F4F",
        listOf("#E4572E", "#F2C14E", "#2F7F7A", "#F8F2E6", "#B4566F"), "#F8F2E6", "#3A2A1C", listOf("#2A1430", "#3A1532", "#4A1E40"), "#F2C14E", "#E8C98F",
    )

    val all = listOf(pop, trail, sticker, sketch, market)

    /** Postcard Pop: the default theme, and the colours the demo trip's photos are written in. */
    val default: ScenePalette get() = pop

    fun of(key: String?): ScenePalette = all.firstOrNull { it.key == key } ?: default
}

/** Pure sizes and fitting (unit tested). The pictures are drawn on a 240×160 canvas and cropped to fill. */
object SceneMath {
    const val VIEW_W = 240f
    const val VIEW_H = 160f

    data class Fit(val scale: Float, val dx: Float, val dy: Float)
    data class Size(val width: Int, val height: Int)

    /** Shapes of a written picture: the board's own 3:2, a square, or a 4:5 portrait (crops of the same scene). */
    enum class Shape(val w: Int, val h: Int) { Wide(3, 2), Square(1, 1), Tall(4, 5) }

    /** Scale and offset that fill a [w]×[h] box with the [viewW]×[viewH] canvas, centred and cropped. */
    fun slice(w: Float, h: Float, viewW: Float = VIEW_W, viewH: Float = VIEW_H): Fit {
        val s = max(w / viewW, h / viewH)
        return Fit(s, (w - viewW * s) / 2f, (h - viewH * s) / 2f)
    }

    /** Pixel size of a picture written as a photo: [longEdge] on the long side, even numbers. */
    fun pixelSize(shape: Shape, longEdge: Int = 1200): Size {
        fun even(v: Float) = (v / 2f).roundToInt().coerceAtLeast(1) * 2
        return if (shape.w >= shape.h) Size(even(longEdge.toFloat()), even(longEdge * shape.h.toFloat() / shape.w))
        else Size(even(longEdge * shape.w.toFloat() / shape.h), even(longEdge.toFloat()))
    }
}

/**
 * Which kind of place to draw when there is no photo: from the words (place name, trip title, entry text), then
 * the country, then the spot on the map, then a stable hash, so the same trip or place always gets the same picture.
 */
object ScenePick {
    /** A trip's picture: its title and subtitle, then its country, then its id. */
    fun forTrip(id: String, title: String, subtitle: String = "", country: String? = null): SceneKind =
        kindOf("$title $subtitle") ?: kindOfCountry(country) ?: byHash(id)

    /** A place or an entry: its name, then other words about it, the country, the spot, any named place = a city. */
    fun forPlace(name: String, text: String = "", country: String? = null, lat: Double? = null, lng: Double? = null, key: String = name): SceneKind =
        kindOf(name) ?: kindOf(text) ?: kindOfCountry(country) ?: kindOfSpot(lat, lng) ?: (if (name.isNotBlank()) SceneKind.City else byHash(key))

    /** First kind whose words appear in [text] (case and accents ignored); the order of [keywords] decides ties. */
    fun kindOf(text: String): SceneKind? {
        val tokens = normalize(text).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val padded = " ${tokens.joinToString(" ")} "
        return keywords.firstOrNull { (_, words) ->
            words.any { w ->
                when {
                    w.endsWith("*") -> w.dropLast(1).let { stem -> tokens.any { it.startsWith(stem) } }
                    ' ' in w -> padded.contains(" $w ")
                    else -> w in tokens
                }
            }
        }?.first
    }

    fun kindOfCountry(code: String?): SceneKind? = code?.uppercase(Locale.ROOT)?.let { c -> countries.entries.firstOrNull { c in it.value }?.key }

    /** Far north or south is ice; otherwise the same spot (to about 10 km) always draws the same kind. */
    fun kindOfSpot(lat: Double?, lng: Double?): SceneKind? {
        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return null
        if (abs(lat) >= 62.0) return SceneKind.Glacier
        return byHash("%.1f,%.1f".format(Locale.ROOT, lat, lng))
    }

    fun byHash(key: String): SceneKind = SceneKind.entries[bucket(key, SceneKind.entries.size)]

    /** Whether a drawn picture for [key] is shown mirrored: the same kind looks different from trip to trip. */
    fun mirrorFor(key: String): Boolean = bucket("mirror:$key", 2) == 1

    /** Stable across runs, devices and Java versions: FNV-1a over UTF-8, then mixed (murmur3's finaliser). */
    fun bucket(key: String, n: Int): Int {
        var h = 0x811C9DC5.toInt()
        for (b in key.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xFF)
            h *= 0x01000193
        }
        // FNV's low bits follow the bytes' parity; mixing spreads every bit before the modulo.
        h = h xor (h ushr 16)
        h *= 0x85EBCA6B.toInt()
        h = h xor (h ushr 13)
        h *= 0xC2B2AE35.toInt()
        h = h xor (h ushr 16)
        return Math.floorMod(h, n)
    }

    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    // "word" = that word, "stem*" = any word starting so, "two words" = that phrase; Greek too (accents dropped).
    private val keywords: List<Pair<SceneKind, List<String>>> = listOf(
        SceneKind.Glacier to listOf(
            "glacier*", "ice", "icefield*", "perito", "iceland", "greenland", "antarctic*", "svalbard", "arctic", "lapland",
            "alaska", "glaciar*", "παγετων*", "ισλανδ*",
        ),
        SceneKind.Lake to listOf("lake*", "laguna*", "lago*", "loch", "lac", "fjord*", "λιμν*"),
        SceneKind.Mountain to listOf(
            "mount*", "mt", "cerro*", "monte", "pico", "fitz roy", "peak*", "summit", "alps", "alpine", "dolomit*", "trek*",
            "hike", "hikes", "hiking", "trail*", "patagonia", "andes", "himalaya*", "everest", "nepal", "ski", "skiing",
            "chalten", "torres del paine", "condores", "βουν*", "ορος", "ορειν*", "πεζοπορ*",
        ),
        SceneKind.Coast to listOf(
            "beach*", "coast*", "sea", "seaside", "port", "puerto", "harbour", "harbor", "bay", "island*", "playa*", "costa",
            "plage", "riviera", "cyclades", "naxos", "paros", "santorini", "mykonos", "crete", "corfu", "aegean", "ionian",
            "amalfi", "algarve", "ferry", "sailing", "cruise", "snorkel*", "surf*", "παραλ*", "θαλασσ*", "νησ*", "λιμαν*", "κυκλαδ*",
        ),
        SceneKind.Village to listOf("village*", "pueblo*", "hamlet", "aldea", "dorf", "χωριο", "χωρια"),
        SceneKind.Forest to listOf("forest*", "bosque*", "woods", "jungle", "rainforest", "arrayanes", "wald", "δασος", "δαση"),
        SceneKind.Countryside to listOf(
            "vineyard*", "winery", "wineries", "wine*", "uco", "bodega*", "malbec", "vendimia", "tuscany", "toscana", "provence",
            "countryside", "farm*", "hills", "valley", "valle", "douro", "κρασ*", "αμπελ*", "λοφ*",
        ),
        SceneKind.Desert to listOf("desert*", "atacama", "sahara", "dune*", "wadi", "outback", "ερημ*"),
        SceneKind.Night to listOf("night*", "tango", "bar", "bars", "pub", "club", "concert", "obelisco", "νυχτ*", "βραδ*"),
        SceneKind.City to listOf(
            "city", "town", "old town", "buenos aires", "santiago", "mendoza", "bariloche", "lisbon", "lisboa", "porto", "rome",
            "roma", "paris", "london", "madrid", "barcelona", "berlin", "vienna", "prague", "budapest", "amsterdam", "istanbul",
            "athens", "new york", "tokyo", "kyoto", "osaka", "seoul", "πολη", "αθηνα",
        ),
    )

    private val countries: Map<SceneKind, Set<String>> = mapOf(
        SceneKind.Glacier to setOf("IS", "GL", "AQ", "SJ", "FO"),
        SceneKind.Mountain to setOf("CH", "AT", "NP", "BT", "AR", "CL", "PE", "BO", "NO", "NZ", "LI", "AD", "KG", "TJ"),
        SceneKind.Coast to setOf(
            "GR", "HR", "MT", "CY", "MV", "SC", "MU", "FJ", "BS", "BB", "JM", "CU", "DO", "PH", "ID", "LK", "CV", "PF", "ME", "AL",
            "BZ", "CR", "PA", "TO", "WS", "VU", "AG", "LC", "GD", "KN", "VC", "TT", "MH", "PW", "KI", "TV", "NR",
        ),
        SceneKind.Desert to setOf("MA", "EG", "JO", "AE", "SA", "OM", "QA", "TN", "DZ", "NA", "BW", "LY", "MR", "ML", "NE", "TD", "SD", "KW", "BH"),
        SceneKind.Countryside to setOf("IE", "SI", "SK", "RO", "BG", "GE", "AM", "RW", "UG", "LU", "MD"),
        SceneKind.Forest to setOf("FI", "GA", "CG", "CD", "SR", "GY"),
    )
}
