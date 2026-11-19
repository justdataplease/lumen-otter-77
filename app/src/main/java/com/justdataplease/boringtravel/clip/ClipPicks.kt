package com.justdataplease.boringtravel.clip

/**
 * A place's own photos (owner, 2026-11-12: "in clip I only see one photo I don't seem to select how to add another or
 * change order"): the "Media here" panel ticks a place's photos in or out, orders them and adds any photo of the trip
 * ([ClipEdits.picks]). The plan shows a place's picks together where the journey reaches it, in their order, one pause
 * each (with the Album layout, pages of up to [PhotoLayout.ALBUM_MAX] in one pause), the still map before the first and
 * after the last; as many as the clip has room for ([fitting], served place by place along the route). The panel asks
 * [fits] before a tick, with the same sums, so what is ticked shows. Pure.
 */
object PhotoPicks {
    /** A little slack for the sums in seconds. */
    private const val EPS = 1e-6

    /**
     * Seconds a place's picks stop the journey: each pause ([holds]: the picked photos' pauses in order; 0 shows over
     * the moving map and costs nothing), an album page's being its first photo's, and the still map ([gapS]) before
     * and after them once.
     */
    fun seconds(holds: List<Double>, gapS: Double, layout: PhotoLayout): Double {
        val paused = holds.filter { it > 0.0 }
        if (paused.isEmpty()) return 0.0
        val pauses = if (layout == PhotoLayout.Album) paused.chunked(PhotoLayout.ALBUM_MAX).sumOf { it.first() } else paused.sum()
        return pauses + 2 * gapS.coerceAtLeast(0.0)
    }

    /** How many of [holds] (in order) fit in [roomS] seconds at one place. */
    fun fitting(holds: List<Double>, gapS: Double, layout: PhotoLayout, roomS: Double): Int {
        var n = 0
        while (n < holds.size && seconds(holds.subList(0, n + 1), gapS, layout) <= roomS + EPS) n++
        return n
    }

    /** Whether a photo pausing [addS] still fits after [holds] at a place that may take [roomS] seconds. */
    fun fits(holds: List<Double>, addS: Double, gapS: Double, layout: PhotoLayout, roomS: Double): Boolean =
        seconds(holds + addS, gapS, layout) <= roomS + EPS

    /** How many more photos pausing [addS] fit after [holds] in [roomS] (as many as asked for when they don't pause). */
    fun more(holds: List<Double>, addS: Double, gapS: Double, layout: PhotoLayout, roomS: Double, most: Int = 99): Int {
        if (addS <= 0.0) return most
        var n = 0
        while (n < most && fits(holds + List(n) { addS }, addS, gapS, layout, roomS)) n++
        return n
    }

    /** The ids ticked at place [key]: its own list ([ClipEdits.picks]), else the ones that show there now ([shown], in order). */
    fun ticked(edits: ClipEdits, key: String, shown: List<String>): List<String> = edits.picks[key] ?: shown

    /**
     * [id] ticked at place [key] (put in last) or unticked. From then on the place shows exactly its ticks: the first
     * tick at a place keeps what showed there ([shown]). Put in, the photo leaves any other place's list; taken out, a
     * photo added from elsewhere goes back where the clip had it.
     */
    fun toggle(edits: ClipEdits, key: String, shown: List<String>, id: String): ClipEdits {
        val now = ticked(edits, key, shown)
        return edits.withPicks(key, if (id in now) now - id else now + id)
    }

    /** The ticked photo at [from] moved to [to] at place [key] (the order they show in). */
    fun move(edits: ClipEdits, key: String, shown: List<String>, from: Int, to: Int): ClipEdits =
        edits.withPicks(key, ClipOrder.move(ticked(edits, key, shown), from, to))
}

/** One photo in a place's "Media here" panel: [ticked] to show there, [shown] when it does, [added] from elsewhere. */
data class PlacePhoto(val id: String, val ticked: Boolean, val shown: Boolean, val added: Boolean)

/**
 * A place's photos as the "Media here" panel lists them ([photos]: the ticked ones first, in the order they show, then
 * the others in route order), and the room the place has for its picks ([roomS]: the clip's room less what the other
 * places' own photos take; on the free version at a Pace, also what keeps the clip within its minute), so a tick that
 * can't show is refused before it is made ([fits]).
 */
class PlacePhotos(
    val key: String,
    val photos: List<PlacePhoto>,
    /** The ticked photos' pauses, in order. */
    private val holds: List<Double>,
    private val roomS: Double,
    private val gapS: Double,
    private val layout: PhotoLayout,
    private val pauseOf: (String) -> Double,
) {
    val ticked: List<String> get() = photos.filter { it.ticked }.map { it.id }
    val shownCount: Int get() = photos.count { it.shown }

    /** Whether ticking [id] shows it (a ticked photo can always be taken out). */
    fun fits(id: String): Boolean = id in ticked || PhotoPicks.fits(holds, pauseOf(id), gapS, layout, roomS)

    /** How many more photos with the clip's pause fit here. */
    fun more(clipPauseS: Double): Int = PhotoPicks.more(holds, clipPauseS, gapS, layout, roomS)

    /** Ticked photos the clip has no room for (they don't show). */
    val noRoom: List<String> get() = photos.filter { it.ticked && !it.shown }.map { it.id }

    companion object {
        /**
         * The place [key]'s photos in [plan] as [design] shows them ([scenes]: [ClipScenes.list] of them). Without
         * [pro], a clip at a Pace (no set length, where pauses add to it) keeps its picks within the free minute.
         */
        fun of(plan: ClipPlan, design: ClipDesign, scenes: List<ClipScene>, key: String, pro: Boolean = true): PlacePhotos {
            val here = scenes.filter { it.kind == SceneKind.Photo && it.placeKey == key && it.photoId != null }
            val shownIds = here.filter { it.shown }.mapNotNull { it.photoId }
            val known = here.mapNotNull { it.photoId }.toHashSet()
            val ticked = PhotoPicks.ticked(design.edits, key, shownIds).filter { it in known }
            val added = plan.photoScenes.filter { it.added }.mapTo(HashSet()) { it.id }
            val shown = shownIds.toHashSet()
            val others = here.mapNotNull { it.photoId }.filterNot { it in ticked }
            val photos = (ticked + others).map { PlacePhoto(it, it in ticked, it in shown, it in added) }
            val otherPlaces = plan.pickedSeconds.filterKeys { it != key }.values.sum()
            val gapS = design.photoGapS.coerceIn(0.0, ClipDesign.MAX_GAP_S)
            var room = plan.photoRoom - otherPlaces
            if (!pro && plan.options.length == null) {
                val now = PhotoPicks.seconds(shownIds.map(design::photoPause), gapS, design.photoLayout)
                room = minOf(room, now + ClipTiming.FREE_MAX_SECONDS - plan.seconds)
            }
            return PlacePhotos(key, photos, ticked.map(design::photoPause), room.coerceAtLeast(0.0), gapS, design.photoLayout, design::photoPause)
        }
    }
}
