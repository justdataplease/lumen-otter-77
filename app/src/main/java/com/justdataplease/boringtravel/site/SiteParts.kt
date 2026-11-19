package com.justdataplease.boringtravel.site

import org.jsoup.Jsoup

/**
 * A place the organiser found in a post: its [name] as the post writes it and, for the map search only, the [area]
 * (town and country) it is in.
 */
data class SiteSpot(val name: String, val area: String? = null)

/**
 * A post about several places as a note per place (owner, 16 Nov 2026: "a post may be 10 places, it should create 10
 * places, 10 journal posts if it can separate them"). The organiser is shown the post's blocks that have words as
 * numbered parts ([listed]) and says, for each place, the part where the post starts telling about it and the last
 * part that is about it. [cut] then cuts the blocks there: the model only points at parts, the words and pictures stay
 * as they are, each in exactly one note, in the post's order.
 *
 * - Places that begin at two parts or more: a note for each stretch, placed at the place that begins there; places
 *   beginning at the same part share that note. What comes before the first of them, and what follows a place's last
 *   part until the next place begins (how to get around, where they stayed, the closing lines), are notes of their own
 *   when they have words or pictures.
 * - A place named without a stretch of its own goes with the first note whose words name it (the first note when none
 *   does): it places that note when the note has no place yet, else it is one more place the note tells about.
 * - Fewer: the post stays one note, placed at its first place.
 * - Every place gets its item in the trip's plan either way ([Part.pin] and [Part.also]).
 *
 * A stretch that starts with a heading is titled by it (the heading isn't said twice); another keeps the post's
 * title. A place told under a heading begins at the heading, wherever the model pointed, so a heading never closes
 * the note before it. The stretches keep the post's order a minute apart, and its tags close the last one. The last stretch keeps
 * the post's own id ([SitePost.part] unchanged), so the post counts as copied once all its notes are; the others are
 * named after their place. Pure and unit tested.
 */
object SiteParts {
    /** The most parts of one post the organiser is shown; what follows stays with the last stretch. */
    const val MAX_LISTED = 120

    private const val MAX_KEY = 80

    /**
     * A place with the part (from 1, as [listed]) where the post starts telling about it and the [last] part that is
     * about it; no [part]: named without a stretch of its own; no [last]: its stretch runs to the next place's.
     */
    data class Found(val spot: SiteSpot, val part: Int? = null, val last: Int? = null)

    /** One note: a post or a stretch of it, the place it is at ([pin]) and the other places it tells about ([also]). */
    data class Part(val post: SitePost, val pin: SiteSpot? = null, val also: List<SiteSpot> = emptyList())

    /** A block's words as plain text; null for a picture, a divider and a block without words. */
    fun words(block: SiteBlock): String? = when (block) {
        is SiteBlock.Text -> Jsoup.parse(block.html).text()
        is SiteBlock.Heading -> block.text
        is SiteBlock.Quote -> Jsoup.parse(block.html).text()
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    /** The indexes of [post]'s blocks with words, the first [MAX_LISTED]: its parts as the organiser numbers them from 1. */
    fun listed(post: SitePost): List<Int> = post.blocks.indices.filter { words(post.blocks[it]) != null }.take(MAX_LISTED)

    fun cut(post: SitePost, found: List<Found>): List<Part> {
        if (found.isEmpty()) return listOf(Part(post))
        val listed = listed(post)
        // The block each place's stretch starts at; null: named without a stretch of its own.
        val starts = found.map { f -> f.part?.let { start(post, listed, it) } }
        val cuts = starts.filterNotNull().distinct().sorted()
        val spots = found.map { it.spot }
        if (cuts.size < 2) return listOf(Part(post, spots.first(), spots.drop(1)))

        val stretches = ArrayList<Stretch>()
        val before = trim(post.blocks.subList(0, cuts[0]))
        if (before.isNotEmpty()) stretches += Stretch(before)
        cuts.forEachIndexed { i, at ->
            val here = found.indices.filter { starts[it] == at }
            val next = cuts.getOrElse(i + 1) { post.blocks.size }
            // The stretch ends where the part after its last one begins, when that is before the next place.
            val last = here.mapNotNull { j -> found[j].last?.takeIf { it >= found[j].part!! } }.maxOrNull()
            val end = last?.let { listed.getOrNull(it) }?.takeIf { it in (at + 1) until next } ?: next
            stretches += Stretch(trim(post.blocks.subList(at, end)), spots[here.first()], here.drop(1).mapTo(ArrayList()) { spots[it] })
            val after = trim(post.blocks.subList(end, next))
            if (after.isNotEmpty()) stretches += Stretch(after)
        }
        // A place without a stretch of its own: with the first note whose words name it, else with the first note.
        for (spot in spots.filterIndexed { i, _ -> starts[i] == null }) {
            val home = stretches.firstOrNull { s -> s.blocks.any { words(it)?.contains(spot.name, ignoreCase = true) == true } } ?: stretches.first()
            if (home.pin == null) home.pin = spot else home.also += spot
        }
        val seen = HashMap<String, Int>()
        return stretches.mapIndexed { i, s ->
            val last = i == stretches.lastIndex
            val heading = (s.blocks.firstOrNull() as? SiteBlock.Heading)?.takeIf { it.text.isNotBlank() }
            val key = s.pin?.name?.let { "place:" + it.lowercase().replace(Regex("\\s+"), " ").trim().take(MAX_KEY) } ?: if (i == 0) "start" else "rest"
            val n = seen.merge(key, 1, Int::plus)!!
            Part(
                post.copy(
                    id = post.id + "#place" + i,
                    title = heading?.text?.trim() ?: post.title,
                    blocks = if (heading != null) s.blocks.drop(1) else s.blocks,
                    at = post.at?.plusMillis(i * SiteSections.STEP_MS),
                    tags = if (last) post.tags else emptyList(),
                    part = if (last) post.part else listOfNotNull(post.part, if (n > 1) "$key#$n" else key).joinToString("/"),
                ),
                s.pin, s.also.toList(),
            )
        }
    }

    /**
     * The block where the stretch that the model starts at [part] begins: that part's, or the heading (or headings)
     * right above it. Null when the post has no such part.
     */
    private fun start(post: SitePost, listed: List<Int>, part: Int): Int? {
        var k = part - 1
        if (k !in listed.indices) return null
        while (k > 0 && post.blocks[listed[k - 1]] is SiteBlock.Heading) k--
        return listed[k]
    }

    /** A run of a post's blocks that becomes one note. */
    private class Stretch(val blocks: List<SiteBlock>, var pin: SiteSpot? = null, val also: MutableList<SiteSpot> = ArrayList())

    /** [blocks] without the dividers at their ends; empty when nothing with words or a picture is left. */
    private fun trim(blocks: List<SiteBlock>): List<SiteBlock> {
        val kept = blocks.dropWhile { it is SiteBlock.Divider }.dropLastWhile { it is SiteBlock.Divider }
        return if (kept.any { it is SiteBlock.Picture || words(it) != null }) kept else emptyList()
    }
}
