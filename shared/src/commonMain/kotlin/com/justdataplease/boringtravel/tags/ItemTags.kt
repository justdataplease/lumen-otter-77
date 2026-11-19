package com.justdataplease.boringtravel.tags

import com.justdataplease.boringtravel.data.db.PlanItem

/**
 * A plan item's tags (owner, 2026-11-12: tags "on plan items only", and "clip inherits tags from items plan"): picked
 * on the item from the owner's tree ([TagTree]) or typed, kept in [PlanItem.tags] as words separated by single spaces
 * ("pizza streetfood"), each as [TagTree.normalize] keeps it. The clip shows them on the item's place. Pure, unit
 * tested.
 */
object ItemTags {
    /** Most tags on one item (as many as a clip scene shows). */
    const val MAX = 8

    /** The tags in [stored], each once, in their order; whatever isn't a tag is dropped. */
    fun parse(stored: String?): List<String> =
        stored.orEmpty().split(' ', ',', '\n', '\t').mapNotNull(TagTree::normalize).distinct().take(MAX)

    /** [tags] as stored; null when none are left. */
    fun format(tags: List<String>): String? = tags.mapNotNull(TagTree::normalize).distinct().take(MAX).joinToString(" ").ifEmpty { null }

    /** [stored] as this app would have written it (rows from a backup or another member's phone). */
    fun tidy(stored: String?): String? = format(parse(stored))

    fun of(item: PlanItem): List<String> = parse(item.tags)

    /** [tags] with [tag] added at the end, or unchanged when it is there already, isn't a tag, or there are [MAX]. */
    fun add(tags: List<String>, tag: String): List<String> {
        val t = TagTree.normalize(tag) ?: return tags
        return if (t in tags || tags.size >= MAX) tags else tags + t
    }

    /** "#pizza #streetfood", for a line under the item's name. */
    fun line(tags: List<String>): String = tags.joinToString(" ") { "#$it" }
}
