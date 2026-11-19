package com.justdataplease.boringtravel.book

import kotlin.math.roundToInt

/**
 * The editor's "On this page" list: every element of a page, top first (the last drawn is the first row), with a
 * short name and kind for each row. Pure, so the order and the words are unit-tested.
 */
object Layers {
    /** [drawOrder] (bottom first) as the list's rows, top first. */
    fun <T> topFirst(drawOrder: List<T>): List<T> = drawOrder.asReversed()

    /** The drawing-order place (0 = bottom) of row [row] of [count] rows. */
    fun zOf(row: Int, count: Int): Int = count - 1 - row

    /** The row (0 = top) of drawing-order place [z] of [count]. */
    fun rowOf(z: Int, count: Int): Int = count - 1 - z

    /** [rows] with row [from] moved to row [to]. */
    fun <T> move(rows: List<T>, from: Int, to: Int): List<T> {
        if (from !in rows.indices || to !in rows.indices || from == to) return rows
        val list = rows.toMutableList()
        list.add(to, list.removeAt(from))
        return list
    }

    /** "−3°", "+12°" for a turned element, null when it is upright. */
    fun angle(rot: Double): String? {
        val d = rot.roundToInt()
        return when {
            d == 0 -> null
            d < 0 -> "−${-d}°"
            else -> "+$d°"
        }
    }

    /** What sort of face [f] is, in a word. */
    fun faceKind(f: Face): String = when (f) {
        Face.Caveat, Face.Kalam, Face.Architects, Face.Gochi -> "Hand"
        Face.Literata, Face.LiterataItalic, Face.SourceSerif, Face.Lora -> "Serif"
        Face.Albert, Face.WorkSans, Face.Barlow, Face.BarlowCondensed -> "Sans"
        Face.PlexMono, Face.CourierPrime, Face.JetBrains -> "Mono"
    }

    /** One word for what [e] is: Photo, Title, Caption, Text, Page text, Sticker, Map, Shape, Line, Paper. */
    fun kindWord(e: FreeEl): String = when (e.kind) {
        FreeKind.PHOTO -> "Photo"
        FreeKind.TEXT -> when {
            e.bind == FreeEl.TITLE -> "Title"
            e.bind == FreeEl.SUBTITLE -> "Subtitle"
            e.captionOf != null -> "Caption"
            else -> "Text"
        }
        FreeKind.FLOW -> "Page text"
        FreeKind.STAMP, FreeKind.FLAGS, FreeKind.TAPE, FreeKind.CORNERS -> "Sticker"
        FreeKind.ROUTE -> "Map"
        FreeKind.SHAPE, FreeKind.SHADE -> "Shape"
        FreeKind.LINE, FreeKind.RULE -> "Line"
        FreeKind.LINES -> "Paper"
        else -> "Item"
    }

    /** The row's name: the words, the photo's caption or place, or what the thing is. */
    fun name(e: FreeEl, facts: BookFacts): String = when (e.kind) {
        FreeKind.PHOTO -> e.photo?.let { photoName(it, facts) } ?: "No photo yet"
        FreeKind.TEXT -> e.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Empty"
        FreeKind.FLOW -> "Page text"
        FreeKind.STAMP -> when (e.style) {
            "day" -> "Day stamp"
            "postage" -> "Postage stamp"
            else -> "Paper plane"
        }
        FreeKind.FLAGS -> if (e.codes.isEmpty()) "Flag" else (if (e.codes.size == 1) "Flag " else "Flags ") + e.codes.joinToString(" ")
        FreeKind.TAPE -> "Washi tape"
        FreeKind.CORNERS -> "Photo corners"
        FreeKind.ROUTE -> when (e.src) {
            FreeLayout.COVER -> "Whole route"
            FreeLayout.TRIP -> "Trip route"
            else -> "Day's route"
        }
        FreeKind.SHAPE -> if (e.style == "oval") "Circle" else "Box"
        FreeKind.SHADE -> "Shade"
        FreeKind.LINE, FreeKind.RULE -> "Line"
        FreeKind.LINES -> "Writing lines"
        else -> "Item"
    }

    /**
     * The row's second line: the kind and how it looks ("Photo · frame, −3°", "Caption · Hand"). [face] gives the face
     * a font token stands for in this book.
     */
    fun detail(e: FreeEl, face: (String?) -> Face): String {
        val parts = ArrayList<String>()
        when (e.kind) {
            FreeKind.PHOTO -> {
                if (e.photo?.mediaId == null) parts += "empty"
                when (e.style) {
                    FreeLayout.PRINT -> parts += "frame"
                    FreeLayout.BLEED -> parts += "full page"
                }
            }
            FreeKind.TEXT -> parts += faceKind(face(e.font))
            FreeKind.FLOW -> if (e.cols > 1) parts += "${e.cols} columns"
        }
        angle(e.rot)?.let { parts += it }
        if (e.hidden) parts += "hidden"
        val word = if (e.kind == FreeKind.FLOW) "Text" else kindWord(e)
        return if (parts.isEmpty()) word else "$word · " + parts.joinToString(", ")
    }

    /** A photo's name: its caption, else the place or title of the entry it belongs to. */
    fun photoName(slot: Slot, facts: BookFacts): String? {
        slot.caption.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val id = slot.mediaId ?: return null
        val entryId = facts.media(id)?.entryId ?: return "Photo"
        val entry = facts.book.entries.firstOrNull { it.id == entryId && it.deletedAt == null } ?: return "Photo"
        return entry.placeName.trim().takeIf { it.isNotEmpty() } ?: entry.title.trim().takeIf { it.isNotEmpty() } ?: "Photo"
    }

    /** The background row's name and second line. */
    fun background(bg: PageBg?, paper: BookPaper?, facts: BookFacts): Pair<String, String> {
        val hidden = if (bg?.hidden == true) ", hidden" else ""
        return when (bg?.kind) {
            PageBg.COLOR -> "Colour" to "Background$hidden"
            PageBg.PHOTO -> (bg.photo?.let { photoName(it, facts) } ?: "Photo") to "Background photo" + (if (bg.dim > 0) ", dimmed" else "") + hidden
            null -> (paper?.let { "${it.label} paper" } ?: "Paper") to "Background · the theme's"
            else -> "Background" to "From a newer version$hidden"
        }
    }
}
