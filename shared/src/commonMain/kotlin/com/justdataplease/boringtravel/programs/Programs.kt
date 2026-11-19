package com.justdataplease.boringtravel.programs

import com.justdataplease.boringtravel.model.Vehicle

/**
 * Ready-made trip programs ("Trip ideas"): a few destinations, each with one program per [Focus]. The traveller answers
 * two short questions (pace, focus), gets the matching program, and can turn it into a trip of their own. The demo
 * trips Kyoto & Osaka and Lisbon & Porto are built from the default program ([Destination.default]).
 *
 * Everything here is our own writing, checked against public sources (listed in [Program.sources]); nothing is booked,
 * there are no prices, and opening hours are only mentioned where they shape the day ("closed Mondays").
 */

/** How full the days are. An item shows from its [ProgramItem.minPace] up. */
enum class Pace(val id: String, val label: String, val blurb: String) {
    Chill("chill", "Chill", "Late starts, two or three things a day, time to sit."),
    Balanced("balanced", "Balanced", "Full days with a long lunch."),
    Energetic("energetic", "Energetic", "Early starts, as much as the day holds.");

    companion object {
        val Default = Balanced
        fun of(id: String?): Pace = entries.firstOrNull { it.id == id } ?: Default
    }
}

/** What the days are built around. */
enum class Focus(val id: String, val label: String, val blurb: String) {
    Food("food", "Eat & drink", "Markets, local dishes, a cooking class, wine or sake."),
    Culture("culture", "Sights & history", "Temples, castles, old quarters and museums."),
    Nature("nature", "Nature & views", "Walks, gardens, coast and viewpoints.");

    companion object {
        val Default = Culture
        fun of(id: String?): Focus = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * One plan item of a program day. [kind] is a PlanKind id ("place", "food", "stay", "transport", "activity", "note").
 * [at] = (lat, lng) of the real place, approximate (5 decimals at most; null for notes and loose items).
 * [via] = how you reach this place from the previous located item on the route (null on the very first item).
 * [time] = "HH:mm" local time when the timing matters (sunrise walk, a show), else null.
 * [minPace] = the slowest pace that still includes it (Chill = always in; Energetic = only for energetic days).
 * [placeName] = "Fushimi Inari Taisha, Kyoto, Japan" style name for the place line, or "".
 * [url] = the place's own website (English where there is one), else its tourism board's page; for a train or boat,
 * the operator's page for that service. Https, checked to load; null for generic items ("Lunch nearby", notes).
 */
data class ProgramItem(
    val kind: String,
    val title: String,
    val note: String = "",
    val at: Pair<Double, Double>? = null,
    val via: Vehicle? = null,
    val time: String? = null,
    val minPace: Pace = Pace.Chill,
    val placeName: String = "",
    val url: String? = null,
)

/** One day: a short name ("Higashiyama temples", "Day trip to Nara"), where you sleep ([base]), and its items in order. */
data class ProgramDay(val title: String, val base: String, val items: List<ProgramItem>)

/** One program of a destination, built around one [focus]. [ideas] are extras not on a day (they land in Ideas). */
data class Program(
    val id: String,
    val destinationId: String,
    val focus: Focus,
    val title: String,
    val blurb: String,
    val days: List<ProgramDay>,
    val ideas: List<ProgramItem> = emptyList(),
    /** Public pages the facts were checked against (shown as "Sources" at the bottom of the preview). */
    val sources: List<String> = emptyList(),
)

/** A destination with its programs (one per focus) and the trip it makes: title, ISO country codes, time zone. */
data class Destination(
    val id: String,
    val title: String,
    val blurb: String,
    val countryCodes: List<String>,
    val zoneId: String,
    /** "Best in spring (late March–May) and autumn (October–November)". */
    val bestTime: String,
    val programs: List<Program>,
) {
    fun program(focus: Focus): Program = programs.firstOrNull { it.focus == focus } ?: programs.first()

    /** The program the demo trip uses. */
    val default: Program get() = program(Focus.Default)
}

/** A program's days at a [pace]: items above that pace are left out (a day never ends up empty: its first item stays). */
fun Program.daysAt(pace: Pace): List<ProgramDay> = days.map { day ->
    val kept = day.items.filter { it.minPace.ordinal <= pace.ordinal }
    day.copy(items = kept.ifEmpty { day.items.take(1) })
}
