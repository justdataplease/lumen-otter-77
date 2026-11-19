package com.justdataplease.boringtravel.tags

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A main category ("Food & Drink") and its subcategories. */
data class TagCategory(val name: String, val subs: List<TagSub>)

/** A subcategory ("Italian") and its tags, without the #. */
data class TagSub(val name: String, val tags: List<String>)

/** A tag with where it sits in the tree, for suggestions ("#pizza · Food & Drink › Italian"). */
data class TagHit(val tag: String, val category: String, val sub: String) {
    val path: String get() = "$category › $sub"
}

/**
 * The owner's tag tree (2026-11-12: "a tree of tags main category subcategory and the tag"; the list he gave, kept in
 * the app only for now: assets/tags/tags.json). The clip's scenes take tags from it ([suggest] as the traveller types,
 * or browsing category › subcategory), or a tag of their own.
 */
class TagTree(val categories: List<TagCategory>) {
    /** Every tag once, in the tree's order. */
    val all: List<TagHit> = buildList {
        val seen = HashSet<String>()
        for (c in categories) for (s in c.subs) for (t in s.tags) if (seen.add(t)) add(TagHit(t, c.name, s.name))
    }

    private val byTag = all.associateBy { it.tag }

    /** Each subcategory's and category's name as words, lowercase ("italian"; "greek", "mediterranean"), read once. */
    private val words: Map<String, List<String>> = categories.flatMap { c -> listOf(c.name) + c.subs.map { it.name } }.distinct()
        .associateWith { n -> n.lowercase().let { l -> listOf(l) + l.split(' ', '&', ',', '-', '/').filter { it.isNotEmpty() } } }

    /**
     * Tags for what the traveller typed: the tag itself first, then tags that start with it, then tags with it inside,
     * then the tags of a subcategory or category whose name starts with it ("ital" → #pizza, #pasta…). [taken] are left
     * out; at most [limit].
     */
    fun suggest(query: String, taken: Collection<String> = emptyList(), limit: Int = 12): List<TagHit> {
        val q = normalize(query) ?: return emptyList()
        val typed = query.trim().lowercase().removePrefix("#")
        val skip = taken.toHashSet()
        val out = LinkedHashMap<String, TagHit>()
        fun add(list: Sequence<TagHit>) {
            for (h in list) {
                if (out.size >= limit) return
                if (h.tag !in skip && h.tag !in out) out[h.tag] = h
            }
        }
        add(listOfNotNull(byTag[q]).asSequence())
        add(all.asSequence().filter { it.tag.startsWith(q) })
        add(all.asSequence().filter { q in it.tag })
        fun named(name: String) = words[name].orEmpty().any { it.startsWith(typed) }
        if (typed.length >= 2) {
            add(all.asSequence().filter { named(it.sub) })
            add(all.asSequence().filter { named(it.category) })
        }
        return out.values.toList()
    }

    companion object {
        const val ASSET = "tags/tags.json"
        /** The longest tag. */
        const val MAX_LEN = 30

        /** A tag as kept: lowercase letters and digits, no #, at most [MAX_LEN]; null when nothing is left. */
        fun normalize(raw: String): String? =
            raw.trim().removePrefix("#").lowercase().filter { it.isLetterOrDigit() }.take(MAX_LEN).ifEmpty { null }

        /** The tree in [text] (assets/tags/tags.json); anything unreadable is left out. */
        fun parse(text: String): TagTree {
            val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return TagTree(emptyList())
            fun str(o: JsonObject, k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val categories = (root["categories"] as? JsonArray).orEmpty().mapNotNull { c ->
                val co = c as? JsonObject ?: return@mapNotNull null
                val name = str(co, "name") ?: return@mapNotNull null
                val subs = (co["subs"] as? JsonArray).orEmpty().mapNotNull { s ->
                    val so = s as? JsonObject ?: return@mapNotNull null
                    val sub = str(so, "name") ?: return@mapNotNull null
                    val tags = (so["tags"] as? JsonArray).orEmpty().mapNotNull { t ->
                        runCatching { t.jsonPrimitive.contentOrNull }.getOrNull()?.let(::normalize)
                    }.distinct()
                    TagSub(sub, tags).takeIf { tags.isNotEmpty() }
                }
                TagCategory(name, subs).takeIf { subs.isNotEmpty() }
            }
            return TagTree(categories)
        }
    }
}
