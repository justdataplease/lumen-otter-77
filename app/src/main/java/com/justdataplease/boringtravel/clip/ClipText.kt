package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.util.Dates
import java.util.Locale
import kotlin.math.roundToLong

/** A place name on the video: [place] in large type with [day] ("Day 4") small above it; either may be missing. */
data class ClipLabel(val day: String?, val place: String?) {
    /** "Day 4 · Naxos", "Naxos", "Day 4". */
    val text: String get() = when {
        day != null && place != null -> ClipText.dayLabel(day, place)
        else -> place ?: day.orEmpty()
    }
}

/** The few texts a clip shows, kept plain: "Day 4 · Naxos", "10 days · 46 km", "Greece, Italy +2". */
object ClipText {
    fun km(km: Double, locale: Locale): String =
        if (km < 10) String.format(locale, "%.1f km", km) else String.format(locale, "%,d km", km.roundToLong())

    fun stats(days: Int, km: Double, locale: Locale): String =
        if (km < 0.05) Dates.days(days) else "${Dates.days(days)} · ${km(km, locale)}"

    /**
     * The stats line over the journey ([ClipDesign.stats]), in [TripStat] order: "Day 3 of 10 · 128 km". A one-day
     * trip says just "Day 1"; the distance keeps one decimal under 10 km, so it counts up smoothly.
     */
    fun statsLine(s: TripStats, show: Set<TripStat>, locale: Locale): String = TripStat.entries.filter { it in show }.joinToString(" · ") {
        when (it) {
            TripStat.Day -> if (s.days > 1) "Day ${s.day} of ${s.days}" else "Day ${s.day}"
            TripStat.Distance -> km(s.km, locale)
        }
    }

    /** Country names from ISO codes, in the order visited: up to three, then "+N". Null when there are none. */
    fun countries(codes: List<String>, locale: Locale): String? {
        val names = codes.map { it.trim().uppercase(Locale.ROOT) }.distinct().mapNotNull { code ->
            runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(locale) }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != code }
        }.distinct()
        if (names.isEmpty()) return null
        return if (names.size <= 3) names.joinToString(", ") else names.take(3).joinToString(", ") + " +${names.size - 3}"
    }

    /** "0:09" for a frame count, rounded like the speed chips ([ClipTiming.clock]) so both show the same length. */
    fun clock(frames: Int, fps: Int): String = ClipTiming.clock(frames.toDouble() / fps.coerceAtLeast(1))

    /** The place part of a stop name: "Halki, Naxos" → "Naxos", "Lisbon" → "Lisbon". */
    fun place(name: String): String? {
        val parts = name.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val p = (if (parts.size >= 2) parts[1] else parts.firstOrNull()) ?: return null
        return if (p.length > 24) p.take(23).trimEnd() + "…" else p
    }

    /** "Day 4 · Naxos"; [day] is "Day 4", or a date when the day falls outside the trip's dates. */
    fun dayLabel(day: String, place: String?): String = if (place != null) "$day · $place" else day

    fun dayName(number: Int): String = "Day $number"

    /**
     * The end card's credit lines for the map sources ([BaseMap.credits]), small under the totals: OpenStreetMap's
     * guidelines accept end credits for video, so the map frames carry no text.
     */
    fun credits(sources: List<String>): List<String> = sources.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** "Cyclades, slowly" → "Cyclades-slowly-clip.mp4" (letters and digits of any script kept). */
    fun fileName(title: String): String {
        val slug = title.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(60).trim('-')
        return "${slug.ifEmpty { "trip" }}-clip.mp4"
    }
}
