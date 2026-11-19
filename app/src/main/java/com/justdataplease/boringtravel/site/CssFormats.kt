package com.justdataplease.boringtravel.site

import org.jsoup.nodes.Element

/**
 * Bold, italic, underline and strike-through written as styles rather than tags: Google Docs' export puts them in
 * `<style>` rules for classes (`.c3{font-weight:700}`, `<span class="c3">`), other pages in a `style` attribute. Only
 * these four survive; fonts, sizes and colours are left out like everywhere else. Pure and unit tested.
 */
class CssFormats private constructor(private val byClass: Map<String, Set<String>>) {
    /** The journal tags ("b", "i", "u", "s") that [e]'s classes and own `style` attribute ask for. */
    fun of(e: Element): Set<String> {
        val classes = e.classNames()
        val fromClasses = if (byClass.isEmpty() || classes.isEmpty()) emptySet() else classes.flatMapTo(HashSet()) { byClass[it].orEmpty() }
        val style = e.attr("style")
        return if (style.isEmpty()) fromClasses else fromClasses + declarations(style)
    }

    companion object {
        val NONE = CssFormats(emptyMap())

        private val ruleRe = Regex("""([^{}]+)\{([^{}]*)\}""")
        private val classSelectorRe = Regex("""^[a-zA-Z0-9]*\.([A-Za-z0-9_-]+)$""")

        /** The class rules of a page's stylesheets ([css], the text of its `<style>` elements). */
        fun of(css: String): CssFormats {
            if (css.isBlank()) return NONE
            val map = HashMap<String, MutableSet<String>>()
            for (m in ruleRe.findAll(css)) {
                val tags = declarations(m.groupValues[2])
                if (tags.isEmpty()) continue
                for (selector in m.groupValues[1].split(',')) {
                    val name = classSelectorRe.matchEntire(selector.trim())?.groupValues?.get(1) ?: continue
                    map.getOrPut(name) { HashSet() } += tags
                }
            }
            return CssFormats(map)
        }

        /** The formats one block of declarations asks for ("font-weight:700;font-style:italic" → b, i). */
        fun declarations(block: String): Set<String> {
            val out = HashSet<String>(2)
            for (d in block.split(';')) {
                val i = d.indexOf(':')
                if (i <= 0) continue
                val prop = d.substring(0, i).trim().lowercase()
                val value = d.substring(i + 1).trim().lowercase().removeSuffix("!important").trim()
                when (prop) {
                    "font-weight" -> if (value == "bold" || value == "bolder" || (value.toIntOrNull() ?: 0) >= 600) out += "b"
                    "font-style" -> if (value == "italic" || value.startsWith("oblique")) out += "i"
                    "text-decoration", "text-decoration-line" -> {
                        if ("underline" in value) out += "u"
                        if ("line-through" in value) out += "s"
                    }
                }
            }
            return out
        }
    }
}
