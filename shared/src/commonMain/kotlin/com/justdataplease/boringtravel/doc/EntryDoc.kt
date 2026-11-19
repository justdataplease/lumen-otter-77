package com.justdataplease.boringtravel.doc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A journal entry's body: a font and a list of blocks, stored as small versioned JSON in `entries.body`:
 *
 *     {"v":1,"font":"literata","blocks":[{"t":"p","html":"Forty minutes <b>late</b>"},
 *       {"t":"img","media":"<id>","rot":-4,"w":0.7,"frame":"polaroid","cap":"Halki square"}]}
 *
 * Text blocks keep a small HTML subset (see [RichHtml]). Unknown block types are kept as [Block.Unknown] and
 * written back unchanged, so an older app version never drops what a newer one wrote. A [Block.Album] is written as
 * its photos, one ordinary `img` block each marked with the arrangement and its spot, so versions without photo
 * layouts show (and keep) them as single photos:
 *
 *     {"t":"img","media":"<id1>","frame":"rounded","album":"4-grid","spot":0}, … ,{…,"album":"4-grid","spot":3}
 *
 * A photo framed in its layout adds `"lframe":"polaroid"` (and has it as its `frame`, for the versions that show
 * single photos); without the key it is plain, as every photo of a layout was before layouts had frames.
 */
data class EntryDoc(
    val font: String? = null,
    val blocks: List<Block> = emptyList(),
) {
    val isEmpty: Boolean get() = blocks.all { it is Block.Paragraph && RichHtml.toPlainText(it.html).isBlank() }

    /** Every photo and video in the entry, in reading order (single ones and those of each layout). */
    fun mediaIds(): List<String> = blocks.flatMap { b ->
        when (b) {
            is Block.Photo -> listOf(b.mediaId)
            is Block.Album -> b.mediaIds
            else -> emptyList()
        }
    }

    fun toJson(): String = EntryDocJson.encode(this)

    companion object {
        const val VERSION = 1
        fun parse(json: String?): EntryDoc = EntryDocJson.decode(json)
        fun ofText(text: String): EntryDoc = EntryDoc(blocks = text.split("\n\n").filter { it.isNotBlank() }.map { Block.Paragraph(RichHtml.escape(it.trim()).replace("\n", "<br>")) })
    }
}

enum class PhotoFrame(val id: String) {
    None("none"), Polaroid("polaroid"), Tape("tape"), Rounded("rounded");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: None
    }
}

enum class Align(val id: String) {
    Start("start"), Center("center"), End("end");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Center
    }
}

data class CheckItem(val text: String, val done: Boolean = false)

/** A paragraph that is one item of a list. Consecutive paragraphs with the same style form one list. */
enum class ListStyle(val id: String) {
    Bullet("ul"), Number("ol");

    companion object {
        fun of(id: String?): ListStyle? = entries.firstOrNull { it.id == id }
    }
}

sealed interface Block {
    /**
     * Rich paragraph, as a sanitized HTML fragment (no wrapping <p>). [list] marks it as a bulleted or numbered list
     * item (stored as `"list":"ul"|"ol"`; versions that don't know the key show it as a plain paragraph).
     */
    data class Paragraph(val html: String, val list: ListStyle? = null) : Block

    data class Heading(val text: String, val level: Int = 2) : Block

    data class Quote(val html: String) : Block

    /**
     * A photo placed in the text. [rotation] in degrees (−15…15), [width] as a fraction of the text column
     * (0.3…1.0), a [frame] and an optional [caption].
     */
    data class Photo(
        val mediaId: String,
        val rotation: Float = 0f,
        val width: Float = 1f,
        val frame: PhotoFrame = PhotoFrame.None,
        val caption: String = "",
        val align: Align = Align.Center,
        /** Optional continuous position in the column's spare width: 0 left, 1 right. */
        val position: Float? = null,
    ) : Block

    /**
     * Photos and videos in a layout across the text column: [mediaIds] fill the spots of arrangement [layoutId] (one
     * of [com.justdataplease.boringtravel.album.AlbumLayouts]) in order; spots after the last one stay empty. An id
     * this version doesn't know is kept and drawn as the usual arrangement for the photos ([PhotoAlbums.grids]).
     * [frames] is how each of them is framed in its spot, by media id (owner, 2026-11-14: "make layout photos also have
     * options like polaroid", "polaroid or style for each"): the frames of a single photo; one without an entry is
     * plain ([PhotoFrame.None], never stored here), as every photo of a layout was before.
     */
    data class Album(val layoutId: String, val mediaIds: List<String>, val frames: Map<String, PhotoFrame> = emptyMap()) : Block {
        /** The frame of photo [id] in this layout. */
        fun frameOf(id: String): PhotoFrame = frames[id] ?: PhotoFrame.None

        /** The frame they all have; null when they differ. */
        val frame: PhotoFrame? get() = mediaIds.map(::frameOf).distinct().singleOrNull()

        /** Every photo and video of the layout in [frame]. */
        fun withFrame(frame: PhotoFrame): Album =
            copy(frames = if (frame == PhotoFrame.None) emptyMap() else mediaIds.distinct().associateWith { frame })

        /** Only [id] in [frame]; the others stay as they are. */
        fun withFrame(id: String, frame: PhotoFrame): Album =
            copy(frames = if (frame == PhotoFrame.None) frames - id else frames + (id to frame))
    }

    data class Checklist(val items: List<CheckItem>) : Block

    data class Place(val name: String, val lat: Double? = null, val lng: Double? = null) : Block

    data object Divider : Block

    /** A deliberate new printed page; older versions retain it as an unknown block. */
    data object PageBreak : Block

    /** A block type this version doesn't know; kept verbatim. */
    data class Unknown(val raw: JsonObject) : Block
}

internal object EntryDocJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(doc: EntryDoc): String = buildJsonObject {
        put("v", EntryDoc.VERSION)
        doc.font?.let { put("font", it) }
        put("blocks", buildJsonArray {
            doc.blocks.forEach { b -> if (b is Block.Album) albumPhotos(b).forEach(::add) else encodeBlock(b)?.let(::add) }
        })
    }.toString()

    fun decode(text: String?): EntryDoc {
        if (text.isNullOrBlank()) return EntryDoc()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return EntryDoc.ofText(text) // not JSON: treat as plain text written elsewhere
        val objects = (root["blocks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return EntryDoc(font = root.str("font"), blocks = decodeBlocks(objects))
    }

    /** Marks an `img` block as one photo of a layout ([Block.Album]); older versions ignore both keys. */
    private const val ALBUM = "album"
    private const val SPOT = "spot"

    /** A photo's frame in its layout, on its `img` block; absent for a plain one. */
    private const val LAYOUT_FRAME = "lframe"

    /**
     * A layout's photos as ordinary photo blocks, so versions without layouts show them as full-width photos: each in
     * its frame, rounded for a plain one (what every layout wrote before its photos had frames).
     */
    private fun albumPhotos(b: Block.Album): List<JsonObject> = b.mediaIds.mapIndexed { i, id ->
        val frame = b.frameOf(id)
        buildJsonObject {
            put("t", "img"); put("media", id)
            put("frame", PhotoAlbums.singleFrame(frame).id)
            put(ALBUM, b.layoutId); put(SPOT, i)
            if (frame != PhotoFrame.None) put(LAYOUT_FRAME, frame.id)
        }
    }

    /** The frames a run of layout photos says, by media id (plain ones left out). */
    private fun albumFrames(run: List<JsonObject>): Map<String, PhotoFrame> = buildMap {
        for (o in run) PhotoFrame.of(o.str(LAYOUT_FRAME)).takeIf { it != PhotoFrame.None }?.let { put(o.str("media")!!, it) }
    }

    /**
     * Blocks in order; `img` blocks marked as spots 0, 1, 2, … of the same layout, one after the other, become one
     * [Block.Album] (a new run starts wherever the spots start again). A run of one photo stays a single photo.
     */
    private fun decodeBlocks(objects: List<JsonObject>): List<Block> {
        val out = ArrayList<Block>(objects.size)
        var run: MutableList<JsonObject>? = null
        var runLayout: String? = null
        var lastSpot = -1
        fun close() {
            val r = run ?: return
            out += if (r.size == 1) decodeBlock(r[0])
            else Block.Album(runLayout!!, r.map { it.str("media")!! }, albumFrames(r))
            run = null
            runLayout = null
        }
        for (o in objects) {
            val layout = o.str(ALBUM)?.takeIf { it.isNotBlank() && o.str("t") == "img" && !o.str("media").isNullOrBlank() }
            val spot = o.prim(SPOT)?.intOrNull?.takeIf { it >= 0 }
            if (layout == null || spot == null) {
                close()
                out += decodeBlock(o)
                continue
            }
            if (run == null || layout != runLayout || spot != lastSpot + 1) {
                close()
                run = ArrayList()
                runLayout = layout
            }
            run!!.add(o)
            lastSpot = spot
        }
        close()
        return out
    }

    /** One block's JSON; a layout is written photo by photo ([albumPhotos]) instead. */
    private fun encodeBlock(b: Block): JsonObject? = when (b) {
        is Block.Paragraph -> buildJsonObject { put("t", "p"); put("html", b.html); b.list?.let { put("list", it.id) } }
        is Block.Heading -> buildJsonObject { put("t", "h"); put("text", b.text); if (b.level != 2) put("level", b.level) }
        is Block.Quote -> buildJsonObject { put("t", "quote"); put("html", b.html) }
        is Block.Photo -> buildJsonObject {
            put("t", "img"); put("media", b.mediaId)
            if (b.rotation != 0f) put("rot", b.rotation)
            if (b.width != 1f) put("w", b.width)
            if (b.frame != PhotoFrame.None) put("frame", b.frame.id)
            if (b.caption.isNotEmpty()) put("cap", b.caption)
            if (b.align != Align.Center) put("align", b.align.id)
            b.position?.takeIf { it.isFinite() }?.let { put("x", it.coerceIn(0f, 1f)) }
        }
        is Block.Checklist -> buildJsonObject {
            put("t", "check")
            put("items", buildJsonArray { b.items.forEach { add(buildJsonObject { put("text", it.text); if (it.done) put("done", true) }) } })
        }
        is Block.Place -> buildJsonObject { put("t", "place"); put("name", b.name); b.lat?.let { put("lat", it) }; b.lng?.let { put("lng", it) } }
        Block.Divider -> buildJsonObject { put("t", "hr") }
        Block.PageBreak -> buildJsonObject { put("t", "page") }
        is Block.Album -> null
        is Block.Unknown -> b.raw
    }

    private fun decodeBlock(o: JsonObject): Block = when (o.str("t")) {
        "p" -> Block.Paragraph(RichHtml.sanitize(o.str("html").orEmpty()), ListStyle.of(o.str("list")))
        "h" -> Block.Heading(o.str("text").orEmpty(), o.prim("level")?.intOrNull ?: 2)
        "quote" -> Block.Quote(RichHtml.sanitize(o.str("html").orEmpty()))
        "img" -> Block.Photo(
            mediaId = o.str("media").orEmpty(),
            rotation = (o.prim("rot")?.floatOrNull?.takeIf { it.isFinite() } ?: 0f).coerceIn(-15f, 15f),
            width = (o.prim("w")?.floatOrNull?.takeIf { it.isFinite() } ?: 1f).coerceIn(0.3f, 1f),
            frame = PhotoFrame.of(o.str("frame")),
            caption = o.str("cap").orEmpty(),
            align = Align.of(o.str("align")),
            position = o.prim("x")?.floatOrNull?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        )
        "check" -> Block.Checklist(
            (o["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .map { CheckItem(it.str("text").orEmpty(), it.prim("done")?.booleanOrNull == true) },
        )
        "place" -> Block.Place(o.str("name").orEmpty(), o.prim("lat")?.doubleOrNull, o.prim("lng")?.doubleOrNull)
        "hr" -> Block.Divider
        "page" -> Block.PageBreak
        else -> Block.Unknown(o)
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.content
}
