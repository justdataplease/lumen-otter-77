package com.justdataplease.boringtravel.ui.journal

/**
 * Reads the journal's small HTML subset (b/strong, i/em, u, s/del, a href, br, span style with color,
 * background-color, font-size, font-weight, font-style, text-decoration) into styled runs. The entry view turns the
 * runs into an AnnotatedString; keeping this step free of Compose makes it easy to test.
 */
object HtmlRuns {
    /** Font size of a run: CSS px (shown as sp) or em (relative to the paragraph). */
    sealed interface Size {
        data class Px(val value: Float) : Size
        data class Em(val value: Float) : Size
    }

    data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val href: String? = null,
        /** ARGB. */
        val color: Long? = null,
        val background: Long? = null,
        val size: Size? = null,
    )

    data class Run(val text: String, val style: Style = Style())

    private val tagRegex = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>")
    private val attrRegex = Regex("""([a-zA-Z-]+)\s*=\s*("([^"]*)"|'([^']*)')""")
    private val spaces = Regex("[ \\t\\r\\n\\f]+")

    fun parse(html: String): List<Run> {
        val out = ArrayList<Run>()
        val stack = ArrayList<Pair<String, Style>>() // open tag → style inside it
        fun style() = stack.lastOrNull()?.second ?: Style()
        fun text(raw: String) {
            if (raw.isEmpty()) return
            var t = decode(raw.replace(spaces, " "))
            // Collapse a space that follows another space or a line break across tags, as HTML does.
            val before = out.lastOrNull()?.text
            if (t.startsWith(' ') && (before == null || before.endsWith(' ') || before.endsWith('\n'))) t = t.trimStart(' ')
            if (t.isEmpty()) return
            val s = style()
            val prev = out.lastOrNull()
            if (prev != null && prev.style == s) out[out.size - 1] = prev.copy(text = prev.text + t) else out += Run(t, s)
        }

        var last = 0
        for (m in tagRegex.findAll(html)) {
            text(html.substring(last, m.range.first))
            last = m.range.last + 1
            val tag = m.groupValues[2].lowercase()
            if (m.groupValues[1] == "/") {
                val i = stack.indexOfLast { it.first == tag }
                if (i >= 0) while (stack.size > i) stack.removeAt(stack.size - 1)
                continue
            }
            if (tag == "br") {
                appendBreak(out, style())
                continue
            }
            val base = style()
            val attrs = attrs(m.groupValues[3])
            val next = when (tag) {
                "b", "strong" -> base.copy(bold = true)
                "i", "em" -> base.copy(italic = true)
                "u" -> base.copy(underline = true)
                "s", "del", "strike" -> base.copy(strike = true)
                "a" -> base.copy(href = attrs["href"]?.let(::decode)?.takeIf(::isSafeUrl) ?: base.href)
                "span" -> attrs["style"]?.let { applyCss(base, it) } ?: base
                else -> base
            }
            stack += tag to next
        }
        text(html.substring(last))
        return trimEdges(out)
    }

    /** Plain text of the runs, for tests and accessibility. */
    fun plain(runs: List<Run>): String = runs.joinToString("") { it.text }

    private fun appendBreak(out: ArrayList<Run>, s: Style) {
        val prev = out.lastOrNull()
        // A space right before a line break is noise from the editor; drop it.
        if (prev != null && prev.style == s) out[out.size - 1] = prev.copy(text = prev.text.trimEnd(' ') + "\n") else out += Run("\n", s)
    }

    /** Leading and trailing spaces of the whole paragraph are not shown (HTML does the same). */
    private fun trimEdges(runs: ArrayList<Run>): List<Run> {
        if (runs.isEmpty()) return runs
        runs[0] = runs[0].copy(text = runs[0].text.trimStart(' '))
        val l = runs.size - 1
        runs[l] = runs[l].copy(text = runs[l].text.trimEnd(' '))
        return runs.filter { it.text.isNotEmpty() }
    }

    private fun attrs(s: String): Map<String, String> =
        attrRegex.findAll(s).associate { it.groupValues[1].lowercase() to it.groupValues[3].ifEmpty { it.groupValues[4] } }

    private fun applyCss(base: Style, css: String): Style {
        var s = base
        for (decl in decode(css).split(';')) {
            val i = decl.indexOf(':')
            if (i <= 0) continue
            val prop = decl.substring(0, i).trim().lowercase()
            val value = decl.substring(i + 1).trim().lowercase()
            s = when (prop) {
                "color" -> parseColor(value)?.let { s.copy(color = it) } ?: s
                "background-color", "background" -> parseColor(value)?.let { s.copy(background = it) } ?: s
                "font-size" -> parseSize(value)?.let { s.copy(size = it) } ?: s
                "font-weight" -> s.copy(bold = value == "bold" || value == "bolder" || (value.toIntOrNull() ?: 400) >= 600)
                "font-style" -> s.copy(italic = value == "italic" || value == "oblique")
                "text-decoration", "text-decoration-line" -> s.copy(
                    underline = s.underline || "underline" in value,
                    strike = s.strike || "line-through" in value,
                )
                else -> s
            }
        }
        return s
    }

    private val named = mapOf(
        "black" to 0xFF000000, "white" to 0xFFFFFFFF, "red" to 0xFFFF0000, "green" to 0xFF008000,
        "blue" to 0xFF0000FF, "gray" to 0xFF808080, "grey" to 0xFF808080, "orange" to 0xFFFFA500,
        "purple" to 0xFF800080, "brown" to 0xFFA52A2A, "navy" to 0xFF000080, "teal" to 0xFF008080,
    )

    /** `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb(…)`, `rgba(…)` or a basic colour name → ARGB; null when unreadable. */
    fun parseColor(value: String): Long? {
        val v = value.trim().lowercase()
        named[v]?.let { return it }
        if (v.startsWith("#")) {
            val h = v.substring(1)
            if (!h.all { it.isDigit() || it in 'a'..'f' }) return null
            return when (h.length) {
                3 -> 0xFF000000 or h.map { "$it$it".toLong(16) }.let { (r, g, b) -> (r shl 16) or (g shl 8) or b }
                6 -> 0xFF000000 or h.toLong(16)
                8 -> (h.substring(6, 8).toLong(16) shl 24) or h.substring(0, 6).toLong(16)
                else -> null
            }
        }
        val open = v.indexOf('(')
        if ((v.startsWith("rgb(") || v.startsWith("rgba(")) && v.endsWith(")")) {
            val parts = v.substring(open + 1, v.length - 1).split(',', ' ', '/').filter { it.isNotBlank() }
            if (parts.size < 3) return null
            fun channel(p: String): Long? = if (p.endsWith("%")) p.dropLast(1).toFloatOrNull()?.let { (it * 2.55f).toLong() } else p.toFloatOrNull()?.toLong()
            val r = channel(parts[0]) ?: return null
            val g = channel(parts[1]) ?: return null
            val b = channel(parts[2]) ?: return null
            val a = parts.getOrNull(3)?.let { p -> if (p.endsWith("%")) p.dropLast(1).toFloatOrNull()?.div(100f) else p.toFloatOrNull() } ?: 1f
            val alpha = (a.coerceIn(0f, 1f) * 255f + 0.5f).toLong()
            return (alpha shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        return null
    }

    /** `20px`, `20.0px`, `1.25em`, `120%`, or small/large keywords. */
    fun parseSize(value: String): Size? {
        val v = value.trim().lowercase()
        return when {
            v.endsWith("px") -> v.dropLast(2).trim().toFloatOrNull()?.takeIf { it in 6f..96f }?.let { Size.Px(it) }
            v.endsWith("pt") -> v.dropLast(2).trim().toFloatOrNull()?.takeIf { it in 5f..72f }?.let { Size.Px(it * 4f / 3f) }
            v.endsWith("rem") -> v.dropLast(3).trim().toFloatOrNull()?.takeIf { it in 0.4f..6f }?.let { Size.Em(it) }
            v.endsWith("em") -> v.dropLast(2).trim().toFloatOrNull()?.takeIf { it in 0.4f..6f }?.let { Size.Em(it) }
            v.endsWith("%") -> v.dropLast(1).trim().toFloatOrNull()?.takeIf { it in 40f..600f }?.let { Size.Em(it / 100f) }
            v == "small" || v == "smaller" -> Size.Em(0.85f)
            v == "x-small" -> Size.Em(0.75f)
            v == "large" || v == "larger" -> Size.Em(1.2f)
            v == "x-large" -> Size.Em(1.5f)
            v == "xx-large" -> Size.Em(2f)
            else -> null
        }
    }

    private fun isSafeUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("https://") || u.startsWith("http://") || u.startsWith("mailto:") || u.startsWith("geo:")
    }

    private val entity = Regex("&(#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
    private val namedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    /** Decodes named and numeric entities in one pass (so "&amp;lt;" stays "&lt;"). */
    fun decode(s: String): String = if ('&' !in s) s else entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint) ?: m.value
            else -> namedEntities[e.lowercase()] ?: m.value
        }
    }

    private fun codePoint(cp: Int): String? = if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null
}
