package com.justdataplease.boringtravel.clip

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One trip's scene edits for one route source (owner: "both video and the album I want to have an editor advanced in
 * order to make it as I like"): per-scene changes keyed like [ClipDesign.overrides], and the photo order per place.
 * Pure; [ClipEditStore] keeps them, [ClipDesign.withEdits] hands them to the plan and the renderer.
 */
data class ClipEdits(
    val scenes: Map<String, SceneOverride> = emptyMap(),
    /** Place name key → photo ids in the order wanted (see [ClipOrder]). */
    val order: Map<String, List<String>> = emptyMap(),
    /** Phone videos in the clip, in the order added (each after its place name, see [ClipVideo]). */
    val videos: List<ClipVideo> = emptyList(),
    /** Voice lines, at most one per scene ([VoiceLine.sceneKey]). */
    val voices: List<VoiceLine> = emptyList(),
    /** Who walks the legs on foot in this video (the editor's Traveller tool); null: Me › Your traveller. */
    val traveller: Traveller? = null,
    /** How the vehicles look in this video (the Traveller tool's Rides); null: Me › Your traveller & rides. */
    val rides: Rides? = null,
    /** Where the music starts, seconds into the track (the editor's "Starts at"); null: its strongest stretch. */
    val musicStartS: Double? = null,
    /**
     * Plan items left out of the video (owner, 2026-11-09: "add ability to deactivate a stop from the map next to let
     * go? and go directly to the next"): the route goes straight from the stop before to the one after, and the stop's
     * name, photos and close-up go with it. Planned routes only.
     */
    val skipped: Set<String> = emptySet(),
    /**
     * Each place's own photos (owner, 2026-11-12: "in clip I only see one photo I don't seem to select how to add
     * another or change order"), by its place name key, from the "Media here" panel: exactly these show there, in this
     * order, as far as the clip has room ([PhotoPicks]); a photo of another place (or of none) in a list moves to it.
     * An empty list: none there. A place without a list shows what the clip picks.
     */
    val picks: Map<String, List<String>> = emptyMap(),
) {
    val isEmpty: Boolean get() = scenes.isEmpty() && order.isEmpty() && videos.isEmpty() && voices.isEmpty() && traveller == null &&
        rides == null && musicStartS == null && skipped.isEmpty() && picks.isEmpty()

    /** [ids] left out of the video ([skip] true), or put back. */
    fun skipping(ids: Collection<String>, skip: Boolean): ClipEdits = copy(skipped = if (skip) skipped + ids else skipped - ids.toSet())

    fun voice(sceneKey: String): VoiceLine? = voices.firstOrNull { it.sceneKey == sceneKey }

    /** [v] for its scene, in place of the one there was. */
    fun withVoice(v: VoiceLine): ClipEdits = copy(voices = voices.filterNot { it.sceneKey == v.sceneKey } + v)

    fun withoutVoice(sceneKey: String): ClipEdits = copy(voices = voices.filterNot { it.sceneKey == sceneKey })

    fun video(id: String): ClipVideo? = videos.firstOrNull { it.id == id }

    /** [v] added, or put in place of the video with its id. */
    fun withVideo(v: ClipVideo): ClipEdits =
        copy(videos = if (videos.any { it.id == v.id }) videos.map { if (it.id == v.id) v else it } else videos + v)

    fun changeVideo(id: String, f: (ClipVideo) -> ClipVideo): ClipEdits = video(id)?.let { withVideo(f(it)) } ?: this

    fun withoutVideo(id: String): ClipEdits = copy(videos = videos.filterNot { it.id == id })

    fun scene(key: String): SceneOverride = scenes[key] ?: SceneOverride.NONE

    /** [key]'s change set to [o] (an empty one is dropped). */
    fun with(key: String, o: SceneOverride): ClipEdits = copy(scenes = if (o.isEmpty) scenes - key else scenes + (key to o))

    fun change(key: String, f: (SceneOverride) -> SceneOverride): ClipEdits = with(key, f(scene(key)))

    /** The place's photo order (empty = as taken). */
    fun withOrder(placeKey: String, ids: List<String>): ClipEdits =
        copy(order = if (ids.isEmpty()) order - placeKey else order + (placeKey to ids.distinct()))

    /** Place [placeKey]'s own photos, in order ([picks]; an empty list keeps none there); each leaves any other place's list. */
    fun withPicks(placeKey: String, ids: List<String>): ClipEdits {
        val mine = ids.distinct()
        val others = picks.filterKeys { it != placeKey }.mapValues { (_, list) -> list.filterNot { it in mine } }
        return copy(picks = others + (placeKey to mine))
    }

    /** One scene back to the defaults (a place name also loses its photo order and its own photos). */
    fun reset(key: String): ClipEdits = copy(scenes = scenes - key, order = order - key, picks = picks - key)

    /** Whether any scene looks or orders differently from the defaults (the traveller's own media aside). */
    val hasSceneEdits: Boolean get() = scenes.isNotEmpty() || order.isNotEmpty() || picks.isNotEmpty()

    /** Every scene back to the defaults; the traveller's own videos, voice lines, look, rides, music start and skipped stops stay. */
    fun resetScenes(): ClipEdits =
        ClipEdits(videos = videos, voices = voices, traveller = traveller, rides = rides, musicStartS = musicStartS, skipped = skipped)
}

/**
 * Undo and redo over immutable states: [edit] pushes the present, [undo] and [redo] step through. Edits with the same
 * [merge] key in a row (typing a name) count as one step. At most [limit] steps are kept.
 */
data class EditHistory<T>(
    val present: T,
    val past: List<T> = emptyList(),
    val future: List<T> = emptyList(),
    private val mergeKey: String? = null,
    private val limit: Int = 100,
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun edit(next: T, merge: String? = null): EditHistory<T> {
        if (next == present) return this
        if (merge != null && merge == mergeKey && past.isNotEmpty()) return copy(present = next, future = emptyList())
        return copy(present = next, past = (past + present).takeLast(limit), future = emptyList(), mergeKey = merge)
    }

    fun undo(): EditHistory<T> =
        if (past.isEmpty()) this else copy(present = past.last(), past = past.dropLast(1), future = listOf(present) + future, mergeKey = null)

    fun redo(): EditHistory<T> =
        if (future.isEmpty()) this else copy(present = future.first(), past = past + present, future = future.drop(1), mergeKey = null)
}

/** Photo order within a place: the traveller's order laid over the photos where they were taken. */
object ClipOrder {
    /**
     * The ids to show in [natural]'s slots (the photos in the order they come along the route) with [wanted] laid
     * over them: the slots of photos named in [wanted] take those photos in [wanted]'s order; others (new photos)
     * keep their own slots.
     */
    fun merge(natural: List<String>, wanted: List<String>?): List<String> {
        if (wanted.isNullOrEmpty()) return natural
        val present = natural.toHashSet()
        val queue = ArrayDeque(wanted.distinct().filter { it in present })
        val named = queue.toHashSet()
        return natural.map { if (it in named) queue.removeFirst() else it }
    }

    /**
     * [ids] (slot order) with each place's order applied: [groups] gives each slot's place key (null: no place);
     * a place's slots are filled per [order].
     */
    fun arrange(ids: List<String>, groups: List<String?>, order: Map<String, List<String>>): List<String> {
        if (order.isEmpty()) return ids
        val out = ids.toMutableList()
        for ((key, wanted) in order) {
            val slots = groups.indices.filter { groups[it] == key }
            if (slots.size < 2) continue
            val merged = merge(slots.map { ids[it] }, wanted)
            slots.forEachIndexed { i, slot -> out[slot] = merged[i] }
        }
        return out
    }

    /** [ids] with the one at [from] moved to [to] (both clamped). */
    fun move(ids: List<String>, from: Int, to: Int): List<String> {
        if (ids.isEmpty()) return ids
        val f = from.coerceIn(0, ids.lastIndex)
        val t = to.coerceIn(0, ids.lastIndex)
        if (f == t) return ids
        val out = ids.toMutableList()
        out.add(t, out.removeAt(f))
        return out
    }
}

/**
 * Where the renderer drew a scene's movable part in the last frame (a place name block, a photo card), in frame
 * pixels, and where its middle may go when dragged ([xRange] null: it only moves up and down), as shares.
 */
class SceneHit(val box: FrameBox, val xRange: ClosedFloatingPointRange<Float>?, val yRange: ClosedFloatingPointRange<Float>)

/** Dragging a place name or a photo card on the editor's frame: positions are stored as shares of the frame. */
object SceneDrag {
    /**
     * The share after dragging [deltaPx] across a view [viewPx] long, starting at share [start], kept in [range]
     * (where the renderer can draw it, so a drag never sits in a dead zone).
     */
    fun move(start: Float, deltaPx: Float, viewPx: Float, range: ClosedFloatingPointRange<Float> = 0f..1f): Float {
        if (viewPx <= 0f) return start.coerceIn(range)
        return (start + deltaPx / viewPx).coerceIn(range)
    }

    /** A share rounded to 0.1 % (the stored value), so tiny jitter doesn't make a new edit. */
    fun rounded(share: Float): Float = kotlin.math.round(share * 1000f) / 1000f
}

/**
 * The edits as JSON, one document per trip with a part per route source:
 * `{"v":1,"sources":{"planned":{"scenes":{"label:item:x":{"text":"El Chaltén"}},"order":{…},"videos":[{…}],"skipped":["y"]}}}`.
 * Unknown keys and bad values are ignored, so an older or newer app reads what it knows (a file without "videos" has
 * none).
 */
object ClipEditsJson {
    const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val MAX_TEXT = 80
    /** More photos than one place could ever show. */
    private const val MAX_PICKS = 200
    /** No bundled track is longer than this. */
    private const val MAX_MUSIC_START_S = 600.0

    fun encode(all: Map<String, ClipEdits>): String = JsonObject(
        linkedMapOf(
            "v" to JsonPrimitive(VERSION),
            "sources" to JsonObject(all.filterValues { !it.isEmpty }.mapValues { (_, e) -> encodeEdits(e) }),
        ),
    ).toString()

    fun decode(text: String?): Map<String, ClipEdits> {
        if (text.isNullOrBlank()) return emptyMap()
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyMap()
        val sources = root["sources"] as? JsonObject ?: return emptyMap()
        return sources.mapNotNull { (source, value) -> (value as? JsonObject)?.let { source to decodeEdits(it) } }
            .filter { !it.second.isEmpty }.toMap()
    }

    fun encodeEdits(e: ClipEdits): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>(
            "scenes" to JsonObject(e.scenes.filterValues { !it.isEmpty }.mapValues { (_, o) -> encodeScene(o) }),
            "order" to JsonObject(e.order.filterValues { it.isNotEmpty() }.mapValues { (_, ids) -> JsonArray(ids.map(::JsonPrimitive)) }),
        ).apply {
            if (e.videos.isNotEmpty()) put("videos", JsonArray(e.videos.map(::encodeVideo)))
            if (e.voices.isNotEmpty()) put("voices", JsonArray(e.voices.map(::encodeVoice)))
            e.traveller?.let { put("traveller", JsonPrimitive(it.encode())) }
            e.rides?.let { put("rides", JsonPrimitive(it.encode())) }
            e.musicStartS?.let { put("musicStart", JsonPrimitive(it)) }
            if (e.skipped.isNotEmpty()) put("skipped", JsonArray(e.skipped.sorted().map(::JsonPrimitive)))
            // An empty list counts too (none at that place).
            if (e.picks.isNotEmpty()) put("picks", JsonObject(e.picks.mapValues { (_, ids) -> JsonArray(ids.map(::JsonPrimitive)) }))
        },
    )

    private fun encodeVoice(v: VoiceLine): JsonObject = JsonObject(
        linkedMapOf("scene" to JsonPrimitive(v.sceneKey), "file" to JsonPrimitive(v.file), "duration" to JsonPrimitive(v.durationMs)),
    )

    /** A stored voice line, or null when something is missing or odd (a file name with a path is never taken). */
    private fun decodeVoice(o: JsonObject): VoiceLine? {
        val scene = o["scene"]?.let(::str)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: return null
        val file = o["file"]?.let(::str)?.trim()?.takeIf { plainName(it) } ?: return null
        val duration = o["duration"]?.let { runCatching { it.jsonPrimitive.takeIf { p -> !p.isString }?.doubleOrNull }.getOrNull() }
            ?.takeIf { it.isFinite() && it > 0 }?.toLong() ?: return null
        return VoiceLine(scene, file, duration.coerceAtMost(VoiceLine.MAX_MS))
    }

    /** A file name on its own: no folders, nothing odd. */
    private fun plainName(f: String): Boolean =
        f.isNotEmpty() && f.length <= 200 && f.none { it == '/' || it == '\\' } && f != "." && f != ".."

    private fun encodeVideo(v: ClipVideo): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>(
            "id" to JsonPrimitive(v.id), "file" to JsonPrimitive(v.file), "scene" to JsonPrimitive(v.sceneKey),
            "duration" to JsonPrimitive(v.durationMs), "start" to JsonPrimitive(v.startMs), "length" to JsonPrimitive(v.lengthMs),
            "sound" to JsonPrimitive(v.sound),
        ).apply { v.source?.let { put("from", JsonPrimitive(it)) } },
    )

    /**
     * A stored video, or null when something it needs is missing or odd (a file name with a path is never taken). One
     * saved before journal videos could be added has no "from" (a phone video).
     */
    private fun decodeVideo(o: JsonObject): ClipVideo? {
        fun text(k: String) = o[k]?.let(::str)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
        fun long(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.takeIf { p -> !p.isString }?.doubleOrNull }.getOrNull() }
            ?.takeIf { it.isFinite() && it >= 0 }?.toLong()
        val id = text("id") ?: return null
        val file = text("file")?.takeIf { f -> f.none { it == '/' || it == '\\' } && f != "." && f != ".." } ?: return null
        val scene = text("scene") ?: return null
        val duration = long("duration")?.takeIf { it > 0 } ?: return null
        val sound = o["sound"]?.let { runCatching { it.jsonPrimitive.takeIf { p -> !p.isString }?.booleanOrNull }.getOrNull() } ?: false
        return ClipVideo(id, file, scene, duration, sound = sound, source = text("from"))
            .trimmed(long("start") ?: 0L, long("length") ?: ClipVideo.DEFAULT_MS)
    }

    fun decodeEdits(o: JsonObject): ClipEdits {
        val scenes = (o["scenes"] as? JsonObject)?.mapNotNull { (key, v) ->
            (v as? JsonObject)?.let { decodeScene(it) }?.takeIf { !it.isEmpty && key.isNotBlank() }?.let { key to it }
        }?.toMap().orEmpty()
        val order = (o["order"] as? JsonObject)?.mapNotNull { (key, v) ->
            val ids = (v as? JsonArray)?.mapNotNull { str(it) }?.filter { it.isNotBlank() }?.distinct().orEmpty()
            if (key.isBlank() || ids.isEmpty()) null else key to ids
        }?.toMap().orEmpty()
        val videos = (o["videos"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::decodeVideo) }?.distinctBy { it.id }
            ?.take(ClipVideo.MAX_VIDEOS).orEmpty()
        val voices = (o["voices"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::decodeVoice) }?.distinctBy { it.sceneKey }.orEmpty()
        val traveller = o["traveller"]?.let(::str)?.let(Traveller::decodeOrNull)
        val rides = o["rides"]?.let(::str)?.let(Rides::decodeOrNull)
        val musicStart = o["musicStart"]?.let { runCatching { it.jsonPrimitive.takeIf { p -> !p.isString }?.doubleOrNull }.getOrNull() }
            ?.takeIf { it.isFinite() && it >= 0.0 && it < MAX_MUSIC_START_S }
        // A file from before skipped stops has none.
        val skipped = (o["skipped"] as? JsonArray)?.mapNotNull { str(it)?.trim()?.takeIf { id -> id.isNotEmpty() && id.length <= 200 } }
            ?.toSet().orEmpty()
        // A file from before the places' own photos has none; an empty list is kept (none at that place).
        val picks = (o["picks"] as? JsonObject)?.mapNotNull { (key, v) ->
            val ids = (v as? JsonArray)?.mapNotNull { str(it)?.trim()?.takeIf { id -> id.isNotEmpty() && id.length <= 200 } }?.distinct()
            if (key.isBlank() || ids == null) null else key to ids.take(MAX_PICKS)
        }?.toMap().orEmpty()
        return ClipEdits(scenes, order, videos, voices, traveller, rides, musicStart, skipped, picks)
    }

    private fun encodeScene(o: SceneOverride): JsonObject {
        val m = linkedMapOf<String, JsonElement>()
        o.name?.let { m["name"] = JsonPrimitive(it.id) }
        o.y?.let { m["y"] = JsonPrimitive(it) }
        o.hidden?.let { m["hidden"] = JsonPrimitive(it) }
        o.text?.let { m["text"] = JsonPrimitive(it) }
        o.kicker?.let { m["kicker"] = JsonPrimitive(it) }
        o.note?.let { m["note"] = JsonPrimitive(it) }
        o.spot?.let { m["spot"] = JsonPrimitive(it) }
        o.font?.let { m["font"] = JsonPrimitive(it.id) }
        o.back?.let { m["back"] = JsonPrimitive(it.id) }
        o.color?.let { m["color"] = JsonPrimitive(it.id) }
        o.photoRow?.let { m["row"] = JsonPrimitive(it.id) }
        o.photoSide?.let { m["side"] = JsonPrimitive(it.id) }
        o.x?.let { m["x"] = JsonPrimitive(it) }
        o.show?.let { m["show"] = JsonPrimitive(it) }
        o.hold?.let { m["hold"] = JsonPrimitive(it) }
        o.size?.let { m["size"] = JsonPrimitive(it.id) }
        o.scale?.let { m["scale"] = JsonPrimitive(it) }
        o.textScale?.let { m["textScale"] = JsonPrimitive(it) }
        o.tilt?.let { m["tilt"] = JsonPrimitive(it) }
        o.caption?.let { m["caption"] = JsonPrimitive(it) }
        o.dates?.let { m["dates"] = JsonPrimitive(it) }
        o.zoom?.let { m["zoom"] = JsonPrimitive(it) }
        o.closeUp?.let { m["closeUp"] = JsonPrimitive(it.id) }
        o.stay?.let { m["stay"] = JsonPrimitive(it) }
        o.tags?.takeIf { it.isNotEmpty() }?.let { m["tags"] = JsonArray(it.map(::JsonPrimitive)) }
        o.tagsHidden?.let { m["tagsHidden"] = JsonPrimitive(it) }
        o.tagColor?.let { m["tagColor"] = JsonPrimitive(it.id) }
        o.tagScale?.let { m["tagScale"] = JsonPrimitive(it) }
        o.tagX?.let { m["tagX"] = JsonPrimitive(it) }
        o.tagY?.let { m["tagY"] = JsonPrimitive(it) }
        return JsonObject(m)
    }

    private fun decodeScene(o: JsonObject): SceneOverride {
        fun text(k: String) = o[k]?.let(::str)?.take(MAX_TEXT)
        fun num(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.takeIf { p -> !p.isString }?.doubleOrNull }.getOrNull() }?.takeIf { it.isFinite() }
        fun bool(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.takeIf { p -> !p.isString }?.booleanOrNull }.getOrNull() }
        fun <T> pick(k: String, values: Collection<T>, id: (T) -> String): T? = text(k)?.let { v -> values.firstOrNull { id(it) == v } }
        fun share(k: String) = num(k)?.takeIf { it in 0.0..1.0 }?.toFloat()
        return SceneOverride(
            name = pick("name", NamePlace.entries) { it.id },
            y = share("y"),
            hidden = bool("hidden"),
            text = text("text"),
            kicker = text("kicker"),
            note = text("note"),
            spot = text("spot"),
            font = pick("font", ClipFont.entries) { it.id },
            back = pick("back", TextBack.entries) { it.id },
            color = pick("color", TextColor.entries) { it.id },
            photoRow = pick("row", PhotoRow.entries) { it.id },
            photoSide = pick("side", PhotoSide.entries) { it.id },
            x = share("x"),
            show = bool("show"),
            hold = num("hold")?.takeIf { it in 0.0..ClipDesign.MAX_HOLD_S },
            size = pick("size", PhotoSize.entries) { it.id },
            scale = num("scale")?.takeIf { it in Spots.PHOTO_SCALE.start.toDouble()..Spots.PHOTO_SCALE.endInclusive.toDouble() }?.toFloat(),
            textScale = num("textScale")?.takeIf { it in Spots.TEXT_SCALE.start.toDouble()..Spots.TEXT_SCALE.endInclusive.toDouble() }?.toFloat(),
            tilt = num("tilt")?.takeIf { it in -MAX_TILT..MAX_TILT }?.toFloat(),
            caption = text("caption"),
            dates = bool("dates"),
            zoom = num("zoom")?.takeIf { it == kotlin.math.round(it) && kotlin.math.abs(it) <= ClipDesign.MAX_ZOOM_STEPS && it != 0.0 }?.toInt(),
            closeUp = pick("closeUp", CloseUp.entries) { it.id },
            stay = num("stay")?.takeIf { it in 0.0..StopStays.MAX_S },
            tags = (o["tags"] as? JsonArray)?.mapNotNull { str(it) }?.let(ClipTags::clean)?.takeIf { it.isNotEmpty() },
            tagsHidden = bool("tagsHidden"),
            tagColor = pick("tagColor", TagColor.entries) { it.id },
            tagScale = num("tagScale")?.toFloat()?.takeIf { it in ClipTags.SCALE },
            tagX = share("tagX"),
            tagY = share("tagY"),
        )
    }

    private fun str(e: JsonElement): String? = runCatching { e.jsonPrimitive.takeIf { it.isString }?.contentOrNull }.getOrNull()

    /** The most tilt a card may have (the editor offers ±8°). */
    const val MAX_TILT = 20.0
}
