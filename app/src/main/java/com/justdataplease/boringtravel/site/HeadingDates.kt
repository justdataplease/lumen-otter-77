package com.justdataplease.boringtravel.site

import java.text.Normalizer
import java.time.LocalDate

/**
 * Dates written in the headings (or first lines) of a long document's sections: "2016-04-03", "3/4/2016",
 * "13.04.2016", "3 April 2016", "April 3rd", "Saturday 3 April", "3 Απριλίου 2016", "Σάββατο 3 Απριλίου", and "Day 3" /
 * "Ημέρα 3". Read together, so one section helps the others: a numeric date whose order is ambiguous (3/4) takes the
 * order the document shows elsewhere (a 13/4, or a day that counts up while the month stays); a date without a year
 * takes the year of the section before it (the next year when the month goes back) or after it; "Day N" counts on from
 * the first section with a date (taken as day 1 unless it says its own day). Nothing found stays null. Pure and tested.
 */
object HeadingDates {
    /** What one text says, before the document is read as a whole. */
    internal data class Raw(
        val year: Int? = null,
        val month: Int? = null,
        val day: Int? = null,
        /** Two numbers whose order (day/month or month/day) isn't known yet. */
        val pair: Pair<Int, Int>? = null,
        /** "Day 3". */
        val dayNumber: Int? = null,
        /** What a clear numeric date says about the document's order: day first (13/4) or month first (4/13). */
        val dayFirst: Boolean? = null,
    )

    /** The date of each text, in order; [today] bounds the sane range. */
    fun resolve(texts: List<String>, today: LocalDate = LocalDate.now()): List<LocalDate?> {
        val raws = texts.map(::scan)
        val evidence = raws.mapNotNull { it?.dayFirst }
        val dayFirst = when {
            true in evidence -> true
            false in evidence -> false
            else -> numericOrder(raws.mapNotNull { it?.pair })
        }
        // Calendar parts: month, day and maybe a year.
        val parts: List<Triple<Int?, Int, Int>?> = raws.map { r ->
            when {
                r == null -> null
                r.month != null && r.day != null -> Triple(r.year, r.month, r.day)
                r.pair != null && dayFirst != null -> {
                    val (a, b) = r.pair
                    if (dayFirst) Triple(r.year, b, a) else Triple(r.year, a, b)
                }
                else -> null
            }
        }
        val dates = arrayOfNulls<LocalDate>(texts.size)
        // Years: from the section itself, else carried forward (a month going back is the next year), else back.
        var lastYear: Int? = null
        var lastMonth: Int? = null
        val years = arrayOfNulls<Int>(texts.size)
        for ((i, p) in parts.withIndex()) {
            p ?: continue
            val y = p.first ?: lastYear?.let { if (lastMonth != null && p.second < lastMonth!!) it + 1 else it }
            years[i] = y
            if (y != null) { lastYear = y; lastMonth = p.second }
        }
        var nextYear: Int? = null
        var nextMonth: Int? = null
        for (i in parts.indices.reversed()) {
            val p = parts[i] ?: continue
            if (years[i] == null && nextYear != null) years[i] = if (p.second > nextMonth!!) nextYear - 1 else nextYear
            if (years[i] != null) { nextYear = years[i]; nextMonth = p.second }
        }
        for ((i, p) in parts.withIndex()) {
            p ?: continue
            val y = years[i] ?: continue
            dates[i] = runCatching { LocalDate.of(y, p.second, p.third) }.getOrNull()?.takeIf { sane(it, today) }
        }
        // "Day N": from the first section with a date.
        val anchor = dates.indexOfFirst { it != null }
        if (anchor >= 0) {
            val anchorDay = raws[anchor]?.dayNumber ?: 1
            for ((i, r) in raws.withIndex()) {
                if (dates[i] != null || r?.dayNumber == null) continue
                dates[i] = dates[anchor]!!.plusDays((r.dayNumber - anchorDay).toLong()).takeIf { sane(it, today) }
            }
        }
        return dates.toList()
    }

    private fun sane(d: LocalDate, today: LocalDate) = d.year >= 1950 && !d.isAfter(today.plusYears(5))

    /**
     * Day first (true), month first (false), or unknown (null) for the numeric dates of one document: a first number
     * over 12 means day first, a second over 12 month first; else the number that changes while the other stays is the
     * day (1/4, 2/4, 3/4).
     */
    internal fun numericOrder(pairs: List<Pair<Int, Int>>): Boolean? {
        if (pairs.any { it.first > 12 }) return true
        if (pairs.any { it.second > 12 }) return false
        if (pairs.size < 2) return null
        val firsts = pairs.map { it.first }.toSet()
        val seconds = pairs.map { it.second }.toSet()
        return when {
            firsts.size > 1 && seconds.size == 1 -> true
            seconds.size > 1 && firsts.size == 1 -> false
            else -> null
        }
    }

    private val isoRe = Regex("""(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)""")
    private val numericRe = Regex("""(?<![\d/.])(\d{1,2})[/.](\d{1,2})(?:[/.](\d{4}|\d{2}))?(?![\d/])""")
    private val dayRe = Regex("""(?<!\p{L})(?:day|ημερα|μερα)\s*(\d{1,3})(?!\d)""")
    private val tokenRe = Regex("""\p{L}+|\d+""")

    /** What [text] says by itself; null when it holds no date. */
    internal fun scan(text: String): Raw? {
        val t = fold(text)
        isoRe.find(t)?.let { m ->
            return Raw(year = m.groupValues[1].toInt(), month = m.groupValues[2].toInt(), day = m.groupValues[3].toInt())
        }
        val dayNumber = dayRe.find(t)?.groupValues?.get(1)?.toInt()
        named(t)?.let { return it.copy(dayNumber = dayNumber) }
        numericRe.find(t)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { y -> if (y.length == 2) 2000 + y.toInt() else y.toInt() }
            // "10.30" is a time, "3.4" without a year too likely a number: dots need a year.
            val dot = t[m.range.first + m.groupValues[1].length] == '.'
            if (!(dot && year == null) && a in 1..31 && b in 1..31 && (a <= 12 || b <= 12)) {
                return when {
                    a > 12 -> Raw(year = year, month = b, day = a, dayNumber = dayNumber, dayFirst = true)
                    b > 12 -> Raw(year = year, month = a, day = b, dayNumber = dayNumber, dayFirst = false)
                    else -> Raw(year = year, pair = a to b, dayNumber = dayNumber)
                }
            }
        }
        return dayNumber?.let { Raw(dayNumber = it) }
    }

    /** A month by name with a day beside it ("3 april", "april 3rd", "3 απριλιου"), and a four-digit year if near. */
    private fun named(t: String): Raw? {
        val tokens = tokenRe.findAll(t).map { it.value }.toList()
        for ((i, tok) in tokens.withIndex()) {
            val month = MONTHS[tok] ?: continue
            val before = tokens.getOrNull(i - 1)?.let(::dayOf) ?: tokens.getOrNull(i - 2)?.takeIf { tokens[i - 1] == "of" }?.let(::dayOf)
            val after = tokens.getOrNull(i + 1)?.let(::dayOf)
            val day = before ?: after ?: continue
            val year = (tokens.subList(i + 1, minOf(tokens.size, i + 4))).firstOrNull { it.length == 4 && it.all(Char::isDigit) }?.toInt()
            return Raw(year = year, month = month, day = day)
        }
        return null
    }

    /** "3", "03", "3rd" (the suffix comes as its own token after the digits). */
    private fun dayOf(token: String): Int? = token.toIntOrNull()?.takeIf { it in 1..31 }

    /** Lower case without accents, ordinal suffixes split off ("3rd" → "3 rd"). */
    private fun fold(text: String): String {
        val n = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return n.replace(Regex("(\\d)(st|nd|rd|th|ης|η)(?!\\p{L})"), "$1 ")
    }

    private val MONTHS: Map<String, Int> = buildMap {
        val english = listOf(
            listOf("january", "jan"), listOf("february", "feb"), listOf("march", "mar"), listOf("april", "apr"), listOf("may"),
            listOf("june", "jun"), listOf("july", "jul"), listOf("august", "aug"), listOf("september", "sep", "sept"),
            listOf("october", "oct"), listOf("november", "nov"), listOf("december", "dec"),
        )
        // Greek without accents: the name, its genitive (3 Απριλίου) and the short forms.
        val greek = listOf(
            listOf("ιανουαριος", "ιανουαριου", "ιαν"), listOf("φεβρουαριος", "φεβρουαριου", "φεβ"), listOf("μαρτιος", "μαρτιου", "μαρ"),
            listOf("απριλιος", "απριλιου", "απρ"), listOf("μαιος", "μαιου", "μαι"), listOf("ιουνιος", "ιουνιου", "ιουν"),
            listOf("ιουλιος", "ιουλιου", "ιουλ"), listOf("αυγουστος", "αυγουστου", "αυγ"), listOf("σεπτεμβριος", "σεπτεμβριου", "σεπ"),
            listOf("οκτωβριος", "οκτωβριου", "οκτ"), listOf("νοεμβριος", "νοεμβριου", "νοε"), listOf("δεκεμβριος", "δεκεμβριου", "δεκ"),
        )
        english.forEachIndexed { i, names -> names.forEach { put(it, i + 1) } }
        greek.forEachIndexed { i, names -> names.forEach { put(it, i + 1) } }
    }
}
