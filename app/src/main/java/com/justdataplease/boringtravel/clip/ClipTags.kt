package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.tags.TagTree

/**
 * The tags a place shows as stickers under its name (owner, 2026-11-12: "in text i can also add tag color size and the
 * spot"; then "exclude from clip; clip inherits tags from plan items"): the tags of the plan items that make the place
 * ([byPlace]), each "#tag" on a rounded sticker in one colour ([TagColor]), at a size and a spot (the clip's, or the
 * place's own: [ClipDesign.tagsShown], [ClipDesign.tagColor], [ClipDesign.tagScale], [SceneOverride.tagX],
 * [SceneOverride.tagY]; by default under the name). Tags are set on plan items only.
 */
object ClipTags {
    /** Most tags on one scene. */
    const val MAX = 8

    /** The size slider: × the stickers' own size. */
    val SCALE = 0.6f..2.0f

    /** [tags] as kept: each [TagTree.normalize]d, once, at most [MAX]. */
    fun clean(tags: List<String>): List<String> = tags.mapNotNull(TagTree::normalize).distinct().take(MAX)

    /**
     * Each place name's tags by its key: the tags of its plan items ([places]: each key with the plan items it stands
     * for, [LabelRange.stops]), in plan order ([waypoints]), each once, at most [MAX]. A place with no plan item behind
     * it (a recording's or saved stops' day) or no tags has none.
     */
    fun byPlace(places: List<Pair<String, List<String>>>, waypoints: List<PlannedWaypoint>): Map<String, List<String>> {
        if (waypoints.isEmpty()) return emptyMap()
        val byItem = waypoints.associate { it.itemId to it.tags }
        val out = LinkedHashMap<String, List<String>>()
        for ((key, stops) in places) {
            val tags = clean(stops.flatMap { byItem[it].orEmpty() })
            if (tags.isNotEmpty()) out[key] = tags
        }
        return out
    }
}

/** The stickers' colour: the sticker and its words ([ink]), ARGB. */
enum class TagColor(val id: String, val label: String, val fill: Long, val ink: Long) {
    Sun("sun", "Sun", 0xFFFFC93C, 0xFF1D1B3A),
    Coral("coral", "Coral", 0xFFFF5A3C, 0xFFFFFFFF),
    Sea("sea", "Sea", 0xFF13A89E, 0xFFFFFFFF),
    Ink("ink", "Ink", 0xFF1D1B3A, 0xFFFFFFFF),
    White("white", "White", 0xFFFFFFFF, 0xFF1D1B3A),
    Pink("pink", "Pink", 0xFFFF8FB1, 0xFF1D1B3A);

    companion object {
        val DEFAULT = Sun
    }
}
