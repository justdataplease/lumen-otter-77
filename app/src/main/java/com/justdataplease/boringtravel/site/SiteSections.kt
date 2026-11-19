package com.justdataplease.boringtravel.site

import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.ZoneId

/**
 * One long page or document cut into notes at its headings (a Google Doc travel diary, a single long page): at the
 * highest heading level that repeats, each section becomes a note titled by its heading, in order; what comes before
 * the first heading is a note with the document's own title. A section's date comes from its heading or first line
 * ([HeadingDates]); sections on the same day keep their order a minute apart, and a section without a date sits just
 * after the one before it (or just before the next), so the journal shows them in the document's order. A document
 * without repeated headings stays one note. The words and pictures are not touched. Pure and unit tested.
 */
object SiteSections {
    /** How far apart sections of the same day are, and an undated section from its neighbour. */
    const val STEP_MS = 60_000L

    /** The hour a section dated by day only is placed at, in the phone's time zone. */
    const val NOON = 12

    /** How much of a section's first line is searched for a date. */
    private const val FIRST_LINE = 120

    private const val MAX_PART_KEY = 80

    fun split(post: SitePost, zone: ZoneId, today: LocalDate = LocalDate.now(zone)): List<SitePost> {
        val headings = post.blocks.filterIsInstance<SiteBlock.Heading>()
        val level = headings.groupingBy { it.level }.eachCount().filter { it.value >= 2 }.keys.minOrNull() ?: return listOf(post)
        // Sections: (heading or null for the intro, blocks after it).
        val sections = ArrayList<Pair<SiteBlock.Heading?, MutableList<SiteBlock>>>()
        sections += null to ArrayList()
        for (b in post.blocks) {
            if (b is SiteBlock.Heading && b.level == level) sections += b to ArrayList() else sections.last().second += b
        }
        val kept = sections.filter { (h, blocks) -> h != null || blocks.isNotEmpty() }
        val dates = HeadingDates.resolve(kept.map { (h, blocks) -> listOfNotNull(h?.text, firstLine(blocks)).joinToString(" \n ") }, today)
        val times = times(dates, zone)
        val seen = HashMap<String, Int>()
        return kept.mapIndexed { i, (h, blocks) ->
            val key = h?.let { partKey(it.text) } ?: "intro"
            val n = seen.merge(key, 1, Int::plus)!!
            post.copy(
                id = post.id + "#" + i,
                title = h?.text ?: post.title,
                at = times[i]?.let { java.time.Instant.ofEpochMilli(it) } ?: post.at,
                blocks = blocks,
                part = if (n > 1) "$key#$n" else key,
            )
        }
    }

    /**
     * Times for sections in order: a dated one at noon on its day plus a minute per section just before it on the same
     * day (its date is the document's, even out of order); an undated one a minute after the section before it, or
     * (before any date) a minute per place before the first dated one.
     */
    internal fun times(dates: List<LocalDate?>, zone: ZoneId): List<Long?> {
        val out = arrayOfNulls<Long>(dates.size)
        var previous: Long? = null
        var previousDay: LocalDate? = null
        var sameDay = 0
        for ((i, d) in dates.withIndex()) {
            if (d != null) {
                val same = d == previousDay
                sameDay = if (same) sameDay + 1 else 0
                val noon = d.atTime(NOON, 0).atZone(zone).toInstant().toEpochMilli() + sameDay * STEP_MS
                // Later on the same day than whatever came between (an undated section placed after the last one).
                out[i] = if (same && previous != null) maxOf(noon, previous + STEP_MS) else noon
                previousDay = d
            } else if (previous != null) {
                out[i] = previous + STEP_MS
            }
            previous = out[i] ?: previous
        }
        val first = out.indexOfFirst { it != null }
        if (first > 0) for (i in 0 until first) out[i] = out[first]!! - (first - i) * STEP_MS
        return out.toList()
    }

    private fun firstLine(blocks: List<SiteBlock>): String? = blocks.firstNotNullOfOrNull { b ->
        when (b) {
            is SiteBlock.Text -> Jsoup.parse(b.html).text()
            is SiteBlock.Heading -> b.text
            else -> null
        }?.takeIf { it.isNotBlank() }?.take(FIRST_LINE)
    }

    /** A section's lasting name: its heading in lower case with single spaces. */
    private fun partKey(heading: String): String = heading.lowercase().replace(Regex("\\s+"), " ").trim().take(MAX_PART_KEY)
}
