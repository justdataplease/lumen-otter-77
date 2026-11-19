package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.doc.RichHtml
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate

/**
 * The book's pages as small versioned JSON (`books.pages`):
 *
 *     {"v":2,"paper":"dots","pad4":true,"hidden":["<entryId>"],"pages":[
 *       {"id":"…","t":"cover","title":"Cyclades, slowly","sub":"Paros → Naxos","photos":[{"m":"<mediaId>","z":1.2,"x":0.5,"y":0.4}]},
 *       {"id":"…","t":"photo-text","entry":"<id>","day":"2027-06-19","photos":[…],"paras":[{"k":"title","h":"The bus"},{"k":"p","h":"…"}]},
 *       {"id":"…","t":"photo","free":true,"bg":{"k":"color","c":"#F4EFE6"},"els":[
 *         {"id":"t0","k":"photo","x":12.5,"y":20,"w":90,"h":64,"r":-2,"d":true,"photo":{"m":"<mediaId>"},"st":"print","pad":[2.3,2.3,2.3,2.3]},
 *         {"id":"…","k":"text","x":12.5,"y":90,"w":80,"h":10,"txt":"Naxos","f":"display","pt":24,"c":"#1E2A5A","al":"center"}]}]}
 *
 * Element positions are mm from the trim's top-left, sizes in mm, turns in degrees, type in points: the same units
 * the page's HTML (and so the PDF) uses. `"hide":true` marks an element or background left off the page (missing =
 * shown, so books saved before it read as they were). An album page (`"t":"album"`) names its arrangement in
 * `"album"` (an [com.justdataplease.boringtravel.album.AlbumLayouts] id; missing on every other page). Version 1 (no
 * elements) reads as it is ([migrate]). Keys this version doesn't know are kept in each object's `extra` and written
 * back unchanged; unknown templates, paragraph and element kinds are kept as their ids. A missing or damaged document
 * reads as an empty book.
 */
object BookJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val DOC_KEYS = setOf("v", "paper", "pad4", "hidden", "pages")
    private val PAGE_KEYS = setOf("id", "t", "photos", "title", "sub", "paras", "entry", "day", "start", "free", "els", "bg", "album")
    private val EL_KEYS = setOf(
        "id", "k", "x", "y", "w", "h", "r", "d", "photo", "st", "pad", "tapes", "band", "txt", "bind", "f", "pt", "lh", "al", "b", "i",
        "up", "hl", "role", "c", "fill", "sw", "words", "codes", "fh", "src", "lab", "marks", "scale", "cols", "pitch", "hide",
    )
    private val BG_KEYS = setOf("k", "c", "photo", "dim", "hide")
    private val SLOT_KEYS = setOf("m", "z", "x", "y", "tilt", "cap")
    private val PARA_KEYS = setOf("k", "h", "entry", "cont")

    fun encode(doc: BookDoc): String = JsonObject(
        linkedMapOf<String, JsonElement>().apply {
            put("v", JsonPrimitive(BookDoc.VERSION))
            put("paper", JsonPrimitive(doc.paper.id))
            put("pad4", JsonPrimitive(doc.padTo4))
            if (doc.hidden.isNotEmpty()) put("hidden", JsonArray(doc.hidden.sorted().map(::JsonPrimitive)))
            put("pages", JsonArray(doc.pages.map(::encodePage)))
            putExtra(doc.extra, DOC_KEYS)
        },
    ).toString()

    fun decode(text: String?): BookDoc {
        if (text.isNullOrBlank()) return BookDoc()
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.let(::migrate) ?: return BookDoc()
        return BookDoc(
            pages = (root["pages"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodePage) },
            paper = BookPaper.of(root.str("paper")),
            padTo4 = root.bool("pad4") ?: true,
            hidden = (root["hidden"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.toSet(),
            extra = root.without(DOC_KEYS),
        )
    }

    /**
     * Brings an older document up to [BookDoc.VERSION]. Version 1 (or no version) had no elements and no backgrounds,
     * so its pages read unchanged; everything else in it, known or not, is kept. A newer version is read as far as
     * this one understands it.
     */
    fun migrate(root: JsonObject): JsonObject {
        val v = (root["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toInt() ?: 1
        if (v >= BookDoc.VERSION) return root
        // 1 → 2: nothing to rewrite; the pages are template pages with no elements.
        return JsonObject(root + ("v" to JsonPrimitive(BookDoc.VERSION)))
    }

    private fun encodePage(p: BookPage): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>().apply {
            put("id", JsonPrimitive(p.id))
            put("t", JsonPrimitive(p.templateId))
            p.title?.let { put("title", JsonPrimitive(it)) }
            p.subtitle?.let { put("sub", JsonPrimitive(it)) }
            p.entryId?.let { put("entry", JsonPrimitive(it)) }
            p.day?.let { put("day", JsonPrimitive(it.toString())) }
            if (!p.start) put("start", JsonPrimitive(false))
            if (p.slots.isNotEmpty()) put("photos", JsonArray(p.slots.map(::encodeSlot)))
            if (p.paras.isNotEmpty()) put("paras", JsonArray(p.paras.map(::encodePara)))
            if (p.free) put("free", JsonPrimitive(true))
            if (p.els.isNotEmpty()) put("els", JsonArray(p.els.map(::encodeEl)))
            p.bg?.let { put("bg", encodeBg(it)) }
            p.album?.let { put("album", JsonPrimitive(it)) }
            putExtra(p.extra, PAGE_KEYS)
        },
    )

    private fun decodePage(o: JsonObject): BookPage? {
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        return BookPage(
            id = id,
            templateId = o.str("t") ?: Template.Notes.id,
            slots = (o["photos"] as? JsonArray).orEmpty().map { (it as? JsonObject)?.let(::decodeSlot) ?: Slot() },
            title = o.str("title"),
            subtitle = o.str("sub"),
            paras = (o["paras"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodePara) },
            entryId = o.str("entry"),
            day = o.str("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            start = o.bool("start") ?: true,
            free = o.bool("free") == true,
            els = (o["els"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodeEl) }.distinctBy { it.id },
            bg = (o["bg"] as? JsonObject)?.let(::decodeBg),
            album = FreeValues.id(o.str("album")),
            extra = o.without(PAGE_KEYS),
        )
    }

    private fun encodeEl(e: FreeEl): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>().apply {
            put("id", JsonPrimitive(e.id))
            put("k", JsonPrimitive(e.kind))
            put("x", JsonPrimitive(mm(e.box.x)))
            put("y", JsonPrimitive(mm(e.box.y)))
            put("w", JsonPrimitive(mm(e.box.w)))
            put("h", JsonPrimitive(mm(e.box.h)))
            if (e.rot != 0.0) put("r", JsonPrimitive(mm(e.rot)))
            if (e.derived) put("d", JsonPrimitive(true))
            e.photo?.let { put("photo", encodeSlot(it)) }
            e.style?.let { put("st", JsonPrimitive(it)) }
            e.pad?.let { put("pad", JsonArray(listOf(it.l, it.t, it.r, it.b).map { v -> JsonPrimitive(mm(v)) })) }
            if (e.tapes.isNotEmpty()) put("tapes", JsonArray(e.tapes.map { t -> JsonArray(listOf(t.cx, t.cy, t.w, t.h, t.angle).map { v -> JsonPrimitive(mm(v)) }) }))
            if (e.band) put("band", JsonPrimitive(true))
            if (e.text.isNotEmpty()) put("txt", JsonPrimitive(e.text))
            e.bind?.let { put("bind", JsonPrimitive(it)) }
            e.font?.let { put("f", JsonPrimitive(it)) }
            if (e.pt != 0.0) put("pt", JsonPrimitive(mm(e.pt)))
            if (e.kind == FreeKind.TEXT) put("lh", JsonPrimitive(round(e.lh)))
            if (e.align != "left") put("al", JsonPrimitive(e.align))
            if (e.bold) put("b", JsonPrimitive(true))
            if (e.italic) put("i", JsonPrimitive(true))
            if (e.upper) put("up", JsonPrimitive(true))
            if (e.mark) put("hl", JsonPrimitive(true))
            e.role?.let { put("role", JsonPrimitive(it)) }
            e.color?.let { put("c", JsonPrimitive(it)) }
            e.fill?.let { put("fill", JsonPrimitive(it)) }
            if (e.stroke != 0.0) put("sw", JsonPrimitive(mm(e.stroke)))
            if (e.words.isNotEmpty()) put("words", JsonArray(e.words.map(::JsonPrimitive)))
            if (e.codes.isNotEmpty()) put("codes", JsonArray(e.codes.map(::JsonPrimitive)))
            if (e.flagH != 0.0) put("fh", JsonPrimitive(mm(e.flagH)))
            e.src?.let { put("src", JsonPrimitive(it)) }
            if (e.labels) put("lab", JsonPrimitive(true))
            if (e.markers) put("marks", JsonPrimitive(true))
            if (e.scaleBar) put("scale", JsonPrimitive(true))
            if (e.cols != 1) put("cols", JsonPrimitive(e.cols))
            if (e.pitch != 0.0) put("pitch", JsonPrimitive(mm(e.pitch)))
            if (e.hidden) put("hide", JsonPrimitive(true))
            putExtra(e.extra, EL_KEYS)
        },
    )

    private fun decodeEl(o: JsonObject): FreeEl? {
        val id = FreeValues.id(o.str("id")) ?: return null
        val kind = o.str("k")?.takeIf { it.isNotBlank() } ?: return null
        val x = o.num("x") ?: return null
        val y = o.num("y") ?: return null
        val w = o.num("w")?.coerceIn(0.0, MAX_MM) ?: return null
        val h = o.num("h")?.coerceIn(0.0, MAX_MM) ?: return null
        fun nums(a: JsonArray?): List<Double>? = a?.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull?.takeIf { v -> v.isFinite() } ?: 0.0 }
        val pad = nums(o["pad"] as? JsonArray)?.takeIf { it.size == 4 }?.map { it.coerceIn(0.0, 100.0) }?.let { Pad(it[0], it[1], it[2], it[3]) }
        val tapes = (o["tapes"] as? JsonArray).orEmpty().mapNotNull { t -> nums(t as? JsonArray)?.takeIf { it.size == 5 }?.let { Tape(it[0], it[1], it[2], it[3], it[4]) } }
        return FreeEl(
            id = id,
            kind = kind,
            box = Box(x.coerceIn(-MAX_MM, MAX_MM), y.coerceIn(-MAX_MM, MAX_MM), w, h),
            rot = normalAngle(o.num("r") ?: 0.0),
            derived = o.bool("d") == true,
            photo = (o["photo"] as? JsonObject)?.let(::decodeSlot),
            style = FreeValues.role(o.str("st")),
            pad = pad,
            tapes = tapes,
            band = o.bool("band") == true,
            text = o.str("txt").orEmpty().take(MAX_TEXT),
            bind = o.str("bind")?.takeIf { it == FreeEl.TITLE || it == FreeEl.SUBTITLE || it.startsWith(FreeEl.CAP) && FreeValues.id(it.removePrefix(FreeEl.CAP)) != null },
            font = FreeValues.font(o.str("f")),
            pt = (o.num("pt") ?: 0.0).coerceIn(0.0, MAX_PT),
            lh = (o.num("lh") ?: 1.25).coerceIn(0.6, 4.0),
            align = FreeValues.align(o.str("al")),
            bold = o.bool("b") == true,
            italic = o.bool("i") == true,
            upper = o.bool("up") == true,
            mark = o.bool("hl") == true,
            role = FreeValues.role(o.str("role")),
            color = FreeValues.color(o.str("c")),
            fill = FreeValues.color(o.str("fill")),
            stroke = (o.num("sw") ?: 0.0).coerceIn(0.0, 20.0),
            words = (o["words"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.take(40) }.take(4),
            codes = (o["codes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.take(3) }.take(12),
            flagH = (o.num("fh") ?: 0.0).coerceIn(0.0, 100.0),
            src = o.str("src")?.takeIf { it in ROUTE_SOURCES },
            labels = o.bool("lab") == true,
            markers = o.bool("marks") == true,
            scaleBar = o.bool("scale") == true,
            cols = (o.num("cols") ?: 1.0).toInt().coerceIn(1, 3),
            pitch = (o.num("pitch") ?: 0.0).coerceIn(0.0, 50.0),
            hidden = o.bool("hide") == true,
            extra = o.without(EL_KEYS),
        )
    }

    private fun encodeBg(b: PageBg): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>().apply {
            put("k", JsonPrimitive(b.kind))
            b.color?.let { put("c", JsonPrimitive(it)) }
            b.photo?.let { put("photo", encodeSlot(it)) }
            if (b.dim != 0.0) put("dim", JsonPrimitive(round(b.dim)))
            if (b.hidden) put("hide", JsonPrimitive(true))
            putExtra(b.extra, BG_KEYS)
        },
    )

    private fun decodeBg(o: JsonObject): PageBg? {
        val kind = o.str("k") ?: return null
        return PageBg(
            kind = kind,
            color = FreeValues.color(o.str("c")),
            photo = (o["photo"] as? JsonObject)?.let(::decodeSlot),
            dim = (o.num("dim") ?: 0.0).coerceIn(0.0, PageBg.MAX_DIM),
            hidden = o.bool("hide") == true,
            extra = o.without(BG_KEYS),
        )
    }

    private fun encodeSlot(s: Slot): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>().apply {
            s.mediaId?.let { put("m", JsonPrimitive(it)) }
            if (s.crop.zoom != 1.0) put("z", JsonPrimitive(round(s.crop.zoom)))
            if (s.crop.cx != 0.5) put("x", JsonPrimitive(round(s.crop.cx)))
            if (s.crop.cy != 0.5) put("y", JsonPrimitive(round(s.crop.cy)))
            s.tilt?.let { put("tilt", JsonPrimitive(round(it))) }
            if (s.caption.isNotEmpty()) put("cap", JsonPrimitive(s.caption))
            putExtra(s.extra, SLOT_KEYS)
        },
    )

    private fun decodeSlot(o: JsonObject): Slot = Slot(
        mediaId = o.str("m"),
        crop = CropMath.clamp(Crop(o.num("z") ?: 1.0, o.num("x") ?: 0.5, o.num("y") ?: 0.5)),
        tilt = o.num("tilt")?.coerceIn(-MAX_TILT, MAX_TILT),
        caption = o.str("cap").orEmpty(),
        extra = o.without(SLOT_KEYS),
    )

    private fun encodePara(p: Para): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>().apply {
            put("k", JsonPrimitive(p.kind))
            put("h", JsonPrimitive(p.html))
            p.entry?.let { put("entry", JsonPrimitive(it)) }
            if (p.cont) put("cont", JsonPrimitive(true))
            putExtra(p.extra, PARA_KEYS)
        },
    )

    private fun decodePara(o: JsonObject): Para? {
        val kind = o.str("k") ?: ParaKind.P
        val html = o.str("h") ?: return null
        // Paragraphs hold the journal's sanitized subset (titles and lines are escaped text); clean it again on the way in.
        return Para(kind, RichHtml.sanitize(html), o.str("entry"), o.bool("cont") == true, o.without(PARA_KEYS))
    }

    /** Largest photo tilt either way, in degrees. */
    const val MAX_TILT = 12.0

    private const val MAX_MM = 2000.0
    private const val MAX_PT = 400.0
    private const val MAX_TEXT = 4000
    private val ROUTE_SOURCES = setOf("cover", "trip", "day")

    private fun round(v: Double): Double = Math.round(v * 10000) / 10000.0

    /** Positions and sizes to a thousandth of a mm. */
    private fun mm(v: Double): Double = Math.round(v * 1000) / 1000.0

    /** Degrees in (-180, 180]. */
    fun normalAngle(a: Double): Double {
        if (!a.isFinite()) return 0.0
        var r = a % 360.0
        if (r > 180.0) r -= 360.0
        if (r <= -180.0) r += 360.0
        return r
    }

    private fun MutableMap<String, JsonElement>.putExtra(extra: JsonObject, known: Set<String>) {
        extra.forEach { (k, v) -> if (k !in known && k !in this) put(k, v) }
    }

    private fun JsonObject.without(keys: Set<String>): JsonObject = JsonObject(filterKeys { it !in keys })
    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.content
    private fun JsonObject.num(key: String): Double? = prim(key)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.takeIf { !it.isString }?.booleanOrNull
}
