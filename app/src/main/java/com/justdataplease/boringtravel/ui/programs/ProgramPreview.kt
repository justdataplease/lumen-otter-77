package com.justdataplease.boringtravel.ui.programs

import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.programs.Destination
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.Program
import com.justdataplease.boringtravel.programs.ProgramItem
import com.justdataplease.boringtravel.programs.ProgramTrips
import com.justdataplease.boringtravel.programs.daysAt
import com.justdataplease.boringtravel.util.Dates

/** The texts Trip ideas shows for a program at a pace: the same days and items the trip will get. Pure (tested). */
object ProgramPreview {
    /** One item: its kind (and vehicle, for travel), title and "07:30 · note". */
    data class Row(val kind: String, val vehicle: Vehicle?, val title: String, val line: String)

    /** "Day 3 · Day trip to Nara" and its items. */
    data class Day(val number: Int, val label: String, val rows: List<Row>)

    fun days(program: Program, pace: Pace): List<Day> =
        program.daysAt(pace).mapIndexed { i, d -> Day(i + 1, dayLabel(i + 1, d.title), d.items.map(::row)) }

    fun ideas(program: Program): List<Row> = program.ideas.map(::row)

    fun row(item: ProgramItem): Row = Row(
        kind = PlanKind.of(item.kind).id,
        // Travel shows how it travels: its own way, else what its name says ("Fly to Lisbon" → plane), as in the Plan.
        vehicle = if (PlanKind.of(item.kind) == PlanKind.Transport) item.via ?: TravelDefault.guess(item.title) else null,
        title = item.title.trim(),
        line = listOfNotNull(ProgramTrips.minutes(item.time)?.let(Dates::time), item.note.trim().ifBlank { null }).joinToString(" · "),
    )

    fun dayLabel(number: Int, title: String): String = if (title.isBlank()) "Day $number" else "Day $number · ${title.trim()}"

    /** "12 days · Kyoto → Osaka". */
    fun facts(program: Program): String =
        listOf(Dates.days(program.days.size), ProgramTrips.route(program)).filter { it.isNotBlank() }.joinToString(" · ")

    /** "Kyoto & Osaka, Lisbon & Porto". */
    fun names(destinations: List<Destination>): String = destinations.joinToString(", ") { it.title }

    /** "Sources: japan-guide.com · kyoto.travel" (each site once), or null without any. */
    fun sources(urls: List<String>): String? =
        urls.mapNotNull(::site).distinct().takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "Sources: ")

    /** "https://www.japan-guide.com/e/e3900.html" → "japan-guide.com"; text that is not a link stays as it is. */
    fun site(url: String): String? {
        val t = url.trim()
        if (t.isEmpty()) return null
        val host = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#:]+)""").find(t)?.groupValues?.get(1) ?: return t
        return host.lowercase().removePrefix("www.")
    }
}
