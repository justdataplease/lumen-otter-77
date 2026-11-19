package com.justdataplease.boringtravel.engine

import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.RichHtml

/**
 * A journal note's plain text, as the Android app's DocRender makes it (doc/DocRender.kt: plainText and preview): what
 * an entry keeps in `plainText` for search, previews and the AI, and the first words a list shows. The same rules, so a
 * note saved on either phone has the same plain text. DocRender stays in the Android app because its print HTML calls
 * Java; these two functions are plain Kotlin there too and can move here as they are.
 */
object NoteText {
    /** List items as "• Coffee" and "2. Tea", checklists as "[x] Tickets", photos by their captions, blocks apart by an empty line. */
    fun plainText(doc: EntryDoc): String {
        var number = 0 // position in the current numbered list
        return doc.blocks.mapNotNull { b ->
            number = if (b is Block.Paragraph && b.list == ListStyle.Number) number + 1 else 0
            when (b) {
                is Block.Paragraph -> RichHtml.toPlainText(b.html).let { t ->
                    when {
                        t.isBlank() || b.list == null -> t
                        b.list == ListStyle.Bullet -> "• $t"
                        else -> "$number. $t"
                    }
                }
                is Block.Heading -> b.text
                is Block.Quote -> RichHtml.toPlainText(b.html)
                is Block.Photo -> b.caption.ifBlank { null }
                is Block.Album -> null
                is Block.Checklist -> b.items.joinToString("\n") { (if (it.done) "[x] " else "[ ] ") + it.text }
                is Block.Place -> b.name
                Block.Divider, Block.PageBreak, is Block.Unknown -> null
            }
        }.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** The first words for list previews, at most [max] letters, cut at a word. */
    fun preview(doc: EntryDoc, max: Int = 160): String {
        val t = plainText(doc).replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max).substringBeforeLast(' ') + "…"
    }
}
