package com.justdataplease.boringtravel.ui.place

import java.util.Locale

/**
 * The place page's hero, one per theme (design boards "Place · arriving somewhere new"): a postcard with a postmark
 * (Postcard Pop), a full-bleed scene with taped photo stickers (Trail), a polaroid with washi tape (Sticker Book), a
 * hand-drawn map with a sticky note (Sketchbook), a torn poster with a rubber stamp (Market).
 */
enum class PlaceHeroStyle { Postcard, Trail, Sticker, Sketch, Market;

    companion object {
        /**
         * The hero for a theme id or name. Read by name so it follows the theme system without a compile-time link:
         * "pop"/"Postcard Pop", "trail", "sticker"/"Sticker Book", "sketch"/"Sketchbook", "market". The older looks map
         * to their closest theme; anything unknown gets the default, Postcard.
         */
        fun of(vararg names: String?): PlaceHeroStyle {
            val text = names.filterNotNull().joinToString(" ").lowercase(Locale.ROOT)
            return when {
                "pop" in text || "postcard" in text -> Postcard
                "trail" in text || "night" in text -> Trail
                "sticker" in text || "notebook" in text -> Sticker
                "sketch" in text || "lined" in text || "graph" in text -> Sketch
                "market" in text || "kraft" in text -> Market
                else -> Postcard
            }
        }
    }
}
