package com.justdataplease.boringtravel.clip

import kotlin.math.max
import kotlin.math.min

enum class SceneKind { Opening, Label, Photo, End }

/**
 * One scene of a clip for the scene editor's strip: the opening, a place name, a photo, the end card. [key] is its
 * override key in [ClipDesign.overrides]; it shows during frames [from, to) ([from] = [to] for a photo not in the
 * clip, at the frame where it would be); [frame] is the picture its thumbnail and the big frame show. A photo has its
 * [photoId] and the place it belongs to ([placeKey], for its order).
 */
data class ClipScene(
    val key: String,
    val kind: SceneKind,
    val title: String,
    val from: Int,
    val to: Int,
    val frame: Int,
    val shown: Boolean = true,
    val photoId: String? = null,
    val placeKey: String? = null,
)

/** The scene editor's view of a plan: its scenes in order, and which frames an edit changes. */
object ClipScenes {
    /**
     * The scenes of [plan] as [design] shows them: the opening, then each place name followed by its photos (in the
     * traveller's order; photos not in the clip too, so they can be put back), then the end card. Keys come from
     * the plan ([LabelRange.key], media ids), so they stay put across re-plans while the trip doesn't change.
     */
    fun list(plan: ClipPlan, design: ClipDesign): List<ClipScene> {
        val out = ArrayList<ClipScene>()
        out += ClipScene(
            ClipDesign.OPENING, SceneKind.Opening, "Opening", 0, plan.drawStart,
            frame = (plan.drawStart * 0.4).toInt().coerceIn(0, max(0, plan.frameCount - 1)),
            shown = design.openingVisible,
        )
        val pops = plan.photos.associateBy { it.id }
        val byPlace = plan.photoScenes.groupBy { it.placeKey }
        fun photos(key: String?): List<ClipScene> {
            val list = byPlace[key].orEmpty()
            val byId = list.associateBy { it.id }
            // A place's own photos first, in their order, then the ones it leaves out.
            val own = key?.let { design.picks[it] }?.filter { it in byId }
            val ordered = if (own != null) own + list.map { it.id }.filterNot { it in own }
                else ClipOrder.merge(list.map { it.id }, key?.let { design.order[it] })
            return ordered.mapNotNull { id -> byId[id] }.map { p ->
                val pop = pops[p.id]
                ClipScene(
                    ClipDesign.photoKey(p.id), SceneKind.Photo, "Photo",
                    from = pop?.fromFrame ?: p.frame, to = pop?.toFrame ?: p.frame,
                    frame = pop?.let { (it.fromFrame + it.toFrame) / 2 } ?: p.frame,
                    shown = pop != null, photoId = p.id, placeKey = key,
                )
            }
        }
        // Photos with no place name (rare: a stretch without one) go where they come in time.
        val loose = photos(null).toMutableList()
        val labelKeys = plan.labels.map { it.key }.toHashSet()
        loose += byPlace.keys.filter { it != null && it !in labelKeys }.flatMap { photos(it) }
        loose.sortBy { it.from }
        var li = 0
        for (range in plan.labels) {
            while (li < loose.size && loose[li].from < range.from) out += loose[li++]
            val shown = design.label(range.key, range.label)
            out += ClipScene(
                range.key, SceneKind.Label, (shown ?: range.label).text.ifEmpty { "Place" }, range.from, range.to,
                frame = labelFrame(range), shown = shown != null,
            )
            out += photos(range.key)
        }
        while (li < loose.size) out += loose[li++]
        val last = max(0, plan.frameCount - 1)
        out += ClipScene(ClipDesign.END, SceneKind.End, "End", plan.endCardStart, plan.frameCount, frame = last)
        return out
    }

    /** A frame where a place name is fully in (past its fade-in), not later than its middle. */
    private fun labelFrame(range: LabelRange): Int {
        val len = range.to - range.from
        return range.from + min(len / 2, 12).coerceAtLeast(0)
    }

    /**
     * The frames that look different between ([oldPlan], [old]) and ([newPlan], [new]), or null when everything may
     * (another timeline, or a global style change). Frames of each scene whose override changed, of photo cards that
     * moved or changed, and of photos in [changedPhotos] (their pictures changed).
     */
    fun dirtyFrames(
        oldPlan: ClipPlan, newPlan: ClipPlan, old: ClipDesign, new: ClipDesign, changedPhotos: Set<String> = emptySet(),
    ): List<IntRange>? {
        if (old.globals != new.globals) return null
        // Another traveller walks every leg on foot.
        if (old.travellerShown != new.travellerShown) return null
        // Other rides, or other colours, on every leg.
        if (old.ridesShown != new.ridesShown) return null
        if (!oldPlan.sameTimeline(newPlan)) return null
        val out = ArrayList<IntRange>()
        fun pops(plan: ClipPlan, id: String) = plan.photos.filter { it.id == id }.map { it.fromFrame until it.toFrame }
        // Photo cards that differ between the plans (left out, put back, order, side).
        val oldPops = oldPlan.photos.associateBy { it.fromFrame }
        val newPops = newPlan.photos.associateBy { it.fromFrame }
        for (f in oldPops.keys + newPops.keys) {
            val a = oldPops[f]
            val b = newPops[f]
            if (a != null && b != null && a.id == b.id && a.toFrame == b.toFrame && a.slot == b.slot && a.held == b.held) continue
            a?.let { out += it.fromFrame until it.toFrame }
            b?.let { out += it.fromFrame until it.toFrame }
        }
        val keys = old.overrides.keys + new.overrides.keys
        for (key in keys) {
            if (old.overrides[key] == new.overrides[key]) continue
            when {
                key == ClipDesign.OPENING -> out += 0 until newPlan.drawStart
                key == ClipDesign.END -> out += newPlan.endCardStart until newPlan.frameCount
                key.startsWith(ClipDesign.DAY_CARD) -> newPlan.inserts.filter { it.key == key }.forEach { out += it.fromFrame until it.toFrame }
                key.startsWith(ClipDesign.PHOTO) -> ClipDesign.photoId(key)?.let { id -> out += pops(oldPlan, id) + pops(newPlan, id) }
                else -> newPlan.labels.filter { it.key == key }.forEach { out += it.from until it.to }
            }
        }
        for (id in changedPhotos) out += pops(newPlan, id)
        return merge(out)
    }

    /** [ranges] sorted, with overlapping and touching ones joined. */
    fun merge(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.filter { !it.isEmpty() }.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) out[out.lastIndex] = last.first..max(last.last, r.last) else out += r
        }
        return out
    }
}
