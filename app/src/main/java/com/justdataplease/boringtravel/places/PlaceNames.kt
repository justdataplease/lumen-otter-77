package com.justdataplease.boringtravel.places

import java.util.Locale

/** Short place texts: "Halki, Naxos, Greece" for lists, "Naxos" for a day header. */
object PlaceNames {
    // Administrative words that make names long without telling the traveller anything ("Naxos Regional Unit").
    private val suffixes = listOf("Regional Unit", "Municipal Unit", "Municipality", "County", "Prefecture")
    private val prefixes = listOf("Regional Unit of ", "Municipality of ", "County ")

    /** "Naxos Regional Unit" → "Naxos", "County Kerry" → "Kerry"; anything else unchanged. */
    fun clean(area: String): String {
        var s = area.trim()
        for (p in prefixes) if (s.startsWith(p, ignoreCase = true) && s.length > p.length) s = s.substring(p.length).trim()
        for (x in suffixes) if (s.endsWith(" $x", ignoreCase = true)) s = s.dropLast(x.length + 1).trim()
        return s
    }

    /**
     * "name, area, country" with empty parts and repeats left out. [areas] are tried in order (city, county, state)
     * and the first one that says something new is used.
     */
    fun compact(name: String, areas: List<String?>, country: String?): String {
        val n = name.trim()
        val area = areas.asSequence()
            .mapNotNull { it?.let(::clean)?.takeIf(String::isNotBlank) }
            .firstOrNull { !it.equals(n, ignoreCase = true) && !it.equals(country?.trim(), ignoreCase = true) }
        return listOfNotNull(n.takeIf(String::isNotBlank), area, country?.trim()?.takeIf { it.isNotBlank() && !it.equals(n, ignoreCase = true) })
            .joinToString(", ")
    }

    private val countryNames: Set<String> by lazy {
        val out = HashSet<String>()
        for (code in Locale.getISOCountries()) {
            val l = Locale.Builder().setRegion(code).build()
            out += l.getDisplayCountry(Locale.ENGLISH).lowercase()
            out += l.getDisplayCountry(Locale.getDefault()).lowercase()
            out += l.getDisplayCountry(l).lowercase()
        }
        out -= ""
        out
    }

    /**
     * The area a place name belongs to, for "Day 4 · Naxos": the last part after dropping the country
     * ("Halki, Naxos, Greece" → "Naxos", "Agios Georgios, Naxos" → "Naxos", "Naxos, Greece" → "Naxos").
     * Three or more parts are our own "name, area, country" form, so the last part is always the country.
     */
    fun area(placeName: String): String? {
        val parts = placeName.split(',').map(String::trim).filter(String::isNotBlank)
        if (parts.isEmpty()) return null
        val dropCountry = parts.size >= 3 || (parts.size == 2 && parts.last().lowercase() in countryNames)
        return (if (dropCountry) parts.dropLast(1) else parts).last()
    }
}
