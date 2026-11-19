package com.justdataplease.boringtravel.export

import com.justdataplease.boringtravel.model.PrintTheme
import java.time.LocalDate
import java.util.Locale

/** How big photos print. Each theme reads it its own way (hero height, polaroid width, grid columns). */
enum class PhotoSize(val id: String, val label: String) {
    Small("s", "Small"),
    Medium("m", "Medium"),
    Large("l", "Large");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Large
    }
}

/** Paper for the PDF; [css] is the `@page` size keyword. */
enum class Paper(val id: String, val label: String, val css: String, val widthMm: Double, val heightMm: Double) {
    A4("a4", "A4", "A4", 210.0, 297.0),
    Letter("letter", "Letter", "letter", 215.9, 279.4);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: A4

        /** Letter where it is the usual paper (US, Canada, Mexico, the Philippines and a few more), A4 elsewhere. */
        fun default(locale: Locale = Locale.getDefault()): Paper =
            if (locale.country.uppercase() in setOf("US", "CA", "MX", "PH", "CL", "CO", "VE", "GT", "PR")) Letter else A4
    }
}

/** What the HTML is for: the print/PDF pages, or a plainer document for Google Docs to convert. */
enum class ExportTarget { Print, Docs }

/**
 * One export: theme, paper, photo size, the plan on or off, and the days to include ([from]..[to], both null for
 * the whole trip).
 */
data class ExportOptions(
    val theme: PrintTheme = PrintTheme.Notebook,
    val paper: Paper = Paper.A4,
    val photoSize: PhotoSize = PhotoSize.Large,
    val includePlan: Boolean = false,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    /** Notebook theme: quiet ruled lines (Notebook 2) instead of dots. */
    val ruled: Boolean = false,
) {
    val wholeTrip: Boolean get() = from == null && to == null

    fun includes(day: LocalDate): Boolean = (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to))
}

/** Labels printed on the pages. English for now; Wave 4 can fill them from string resources. */
data class PrintLabels(
    val day: String = "Day",
    val of: String = "of",
    val plan: String = "Plan",
    val ideas: String = "Ideas",
    val moved: String = "Moved",
    val stops: String = "Stops",
    val weather: String = "Weather",
    val days: String = "Days",
    val photos: String = "Photos",
    val entries: String = "Entries",
    val above: String = "Above",
    val nothingYet: String = "Nothing written for these days yet.",
    val brand: String = "BoringTravel",
)
