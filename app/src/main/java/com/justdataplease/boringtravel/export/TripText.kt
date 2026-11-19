package com.justdataplease.boringtravel.export

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.doc.DocRender
import com.justdataplease.boringtravel.model.PlanSpan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The trip as plain text for Android's share sheet (owner, 2026-11-12: "Share Text"): the name and dates, then each
 * chosen day under its own heading with its plan (when the plan is included: time, name, place) and its notes (title,
 * time, place and words). Photos are left out; a photo's caption stays as a line of its own. Pure.
 */
object TripText {
    fun build(book: TripBook, options: ExportOptions, locale: Locale = Locale.getDefault(), labels: PrintLabels = PrintLabels()): String {
        val trip = book.trip
        val days = book.days(options)
        val longDate = DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale)
        val hhmm = DateTimeFormatter.ofPattern("HH:mm", locale)
        val out = StringBuilder()
        fun line(s: String = "") = out.append(s.trimEnd()).append('\n')
        fun planLines(items: List<PlanItem>) = items.forEach { p ->
            val what = listOfNotNull(p.title.trim(), PlanSpan.until(p), p.placeName.trim().takeIf { it.isNotEmpty() && it != p.title.trim() })
                .filter { it.isNotEmpty() }.joinToString(" · ")
            line(listOf(spanTime(p), what).filter { it.isNotEmpty() }.joinToString("  "))
        }

        line(trip.title.trim())
        if (trip.subtitle.isNotBlank()) line(trip.subtitle.trim())
        dateRange(book, options, days, locale)?.let(::line)
        if (days.isEmpty()) {
            line()
            line(labels.nothingYet)
        }
        days.forEach { d ->
            line()
            val number = d.number?.let { n -> if (d.total != null && d.total > 0) "${labels.day} $n ${labels.of} ${d.total}" else "${labels.day} $n" }
            line(listOfNotNull(number, longDate.format(d.date), d.place).joinToString(" · "))
            if (options.includePlan && d.plan.isNotEmpty()) {
                line()
                line(labels.plan)
                planLines(d.plan)
            }
            d.entries.forEach { be ->
                val e = be.entry
                line()
                val zone = runCatching { ZoneId.of(e.zoneId) }.getOrDefault(book.zone)
                if (e.title.isNotBlank()) line(e.title.trim())
                line(listOf(hhmm.format(Instant.ofEpochMilli(e.occurredAt).atZone(zone)), e.placeName.trim()).filter { it.isNotEmpty() }.joinToString(" · "))
                val words = DocRender.plainText(be.doc).trim()
                if (words.isNotEmpty()) line(words.replace(Regex("\n{3,}"), "\n\n"))
            }
        }
        val ideas = if (options.includePlan && options.wholeTrip) book.ideas() else emptyList()
        if (ideas.isNotEmpty()) {
            line()
            line(labels.ideas)
            planLines(ideas)
        }
        return out.toString().trimEnd() + "\n"
    }

    /** "16–25 Jun 2027", narrowed to the chosen days. */
    private fun dateRange(book: TripBook, options: ExportOptions, days: List<BookDay>, locale: Locale): String? {
        val s = book.trip.startDate ?: days.firstOrNull()?.date ?: return null
        val e = book.trip.endDate ?: days.lastOrNull()?.date ?: s
        val from = options.from?.takeIf { it.isAfter(s) } ?: s
        val to = options.to?.takeIf { it.isBefore(e) } ?: e
        return TripHtml.range(from, to, locale)
    }
}
