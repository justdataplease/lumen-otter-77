package com.justdataplease.boringtravel.doc

/**
 * The small HTML subset journal text is kept in: b/strong, i/em, u, s/del, a href, br, and span with a style limited
 * to font-family, font-size, color and background-color. Everything else is dropped (tags) or escaped (text), so the
 * same fragment is safe in the editor, in PDF templates and in Google Docs uploads.
 */
object RichHtml {
    private val allowedTags = setOf("b", "strong", "i", "em", "u", "s", "del", "a", "br", "span")
    private val allowedStyle = setOf("font-family", "font-size", "color", "background-color", "font-weight", "font-style", "text-decoration")
    private val tagRegex = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>")
    private val attrRegex = Regex("""([a-zA-Z-]+)\s*=\s*("([^"]*)"|'([^']*)')""")
    private val safeStyleValue = Regex("""^[#a-zA-Z0-9 ,.%()'"-]+$""")

    fun escape(text: String): String = buildString(text.length + 16) {
        for (ch in text) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }

    /** Keeps allowed tags with allowed attributes; drops other tags (keeping their text) and script/style content. */
    fun sanitize(html: String): String {
        val noScripts = html.replace(Regex("(?is)<(script|style|iframe|object)[^>]*>.*?</\\1>"), "")
        val out = StringBuilder(noScripts.length)
        var last = 0
        for (m in tagRegex.findAll(noScripts)) {
            out.append(escapeLoose(noScripts.substring(last, m.range.first)))
            last = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            if (tag !in allowedTags) continue
            if (closing) {
                if (tag != "br") out.append("</").append(tag).append('>')
                continue
            }
            out.append('<').append(tag)
            for (a in attrRegex.findAll(m.groupValues[3])) {
                val name = a.groupValues[1].lowercase()
                val value = a.groupValues[3].ifEmpty { a.groupValues[4] }
                when {
                    tag == "a" && name == "href" && isSafeUrl(value) -> out.append(" href=\"").append(escape(value)).append('"')
                    tag == "span" && name == "style" -> cleanStyle(value)?.let { out.append(" style=\"").append(escape(it)).append('"') }
                }
            }
            out.append('>')
        }
        out.append(escapeLoose(noScripts.substring(last)))
        return out.toString()
    }

    /** Text only, with entities decoded and <br> as newlines. */
    fun toPlainText(html: String): String = decodeEntities(
        html.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(tagRegex, ""),
    )

    fun decodeEntities(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&nbsp;", " ").replace("&amp;", "&")

    private fun isSafeUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("https://") || u.startsWith("http://") || u.startsWith("mailto:") || u.startsWith("geo:")
    }

    private fun cleanStyle(style: String): String? = style.split(';')
        .mapNotNull { decl ->
            val i = decl.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val prop = decl.substring(0, i).trim().lowercase()
            val value = decl.substring(i + 1).trim()
            if (prop in allowedStyle && value.isNotEmpty() && safeStyleValue.matches(value) && "url" !in value.lowercase() && "expression" !in value.lowercase()) "$prop: $value" else null
        }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("; ")

    /** Escapes stray < > in text but keeps existing entities as they are. */
    private fun escapeLoose(text: String): String = text.replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("&(?!(amp|lt|gt|quot|#39|nbsp|#\\d+|#x[0-9a-fA-F]+);)"), "&amp;")
}
