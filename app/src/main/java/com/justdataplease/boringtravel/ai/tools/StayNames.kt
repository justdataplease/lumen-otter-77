package com.justdataplease.boringtravel.ai.tools

import java.text.Normalizer

/**
 * Stays are planned by area, never as a named hotel or hostel the traveller didn't give (owner, 2026-11-12: "do not
 * propose specific hotel or hostels just plan them"). A stay named like a lodging ("Hotel Avenida Palace", "Lisbon
 * Destination Hostel") is the planner's own invention unless the traveller's words hold the rest of its name: their
 * booking ("Hotel: Memmo Alfama") or "a hotel in Alfama". "Stay in Alfama" names no lodging and always passes. Pure.
 */
object StayNames {
    /** True when [title] (or the name before the first comma of [placeName]) is a lodging the traveller never named. */
    fun invented(title: String, placeName: String?, travellerWords: String): Boolean {
        val own = words(travellerWords).toSet()
        return listOfNotNull(title, placeName?.substringBefore(',')).any { name ->
            val w = words(name)
            w.any { it in LODGING } && w.any { it !in LODGING && it !in SMALL && it !in own }
        }
    }

    /** Lower-case words without accents ("Hôtel" → "hotel"), "b&b" kept whole. */
    internal fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
            .split(SPLIT).filter { it.isNotEmpty() }

    private val MARKS = Regex("\\p{Mn}+")
    private val SPLIT = Regex("[^\\p{L}\\p{N}&]+")

    /** Words that make a name a lodging, and a few chains that are never generic. */
    private val LODGING = setOf(
        "hotel", "hotels", "hostel", "hostels", "hostal", "inn", "resort", "suites", "b&b", "guesthouse", "pension", "pensione",
        "posada", "pousada", "ryokan", "riad", "motel", "aparthotel", "airbnb", "marriott", "hilton", "hyatt", "ibis",
        "novotel", "sheraton", "radisson", "mercure", "accor", "kempinski", "intercontinental",
    )

    /** Words a stay may have around an area's name ("Stay in", "near the"). */
    private val SMALL = setOf("stay", "staying", "in", "at", "near", "by", "the", "a", "an", "of", "on", "and", "old", "town", "centre", "center", "area", "district", "quarter", "night", "nights")
}
