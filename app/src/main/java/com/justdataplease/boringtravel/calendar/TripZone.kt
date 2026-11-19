package com.justdataplease.boringtravel.calendar

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** The zone a trip's times are read in, and the others worth offering. */
data class ZoneChoice(val current: ZoneId, val options: List<ZoneId>)

/**
 * Plan times are wall-clock times at the destination ("ferry 07:30"), but a trip stores no zone. The calendar needs
 * one, so it is guessed and can be changed: the zone chosen before, else the zone most journal entries were written
 * in, else the one zone of the countries the planned places are in, else the phone's zone. Pure.
 */
object TripZone {

    /**
     * [placeZones]: for each planned place, the zones of its country (one for most countries, several for the US,
     * Russia, Brazil…; empty when unknown).
     */
    fun guess(chosen: String?, entryZones: List<String>, placeZones: List<Set<String>>, phone: ZoneId): ZoneId {
        parse(chosen)?.let { return it }
        entryZones.mapNotNull(::parse).groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { return it.key }
        val single = placeZones.filter { it.size == 1 }.map { it.first() }
        single.mapNotNull(::parse).groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { return it.key }
        return phone
    }

    /** The guess first, then the phone's zone, the entries' and the places' countries' zones, east to west. */
    fun options(current: ZoneId, phone: ZoneId, entryZones: List<String>, placeZones: List<Set<String>>, on: LocalDate): List<ZoneId> {
        val rest = (listOf(phone) + entryZones.mapNotNull(::parse) + placeZones.flatten().mapNotNull(::parse))
            .distinct()
            .filter { it != current }
            .sortedWith(compareByDescending<ZoneId> { offsetSeconds(it, on) }.thenBy { city(it) })
        return (listOf(current) + rest).take(60)
    }

    fun parse(id: String?): ZoneId? = id?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    /** "New York" from America/New_York, "UTC" for UTC. */
    fun city(zone: ZoneId): String {
        val id = zone.id
        if (id == "Z" || id.equals("UTC", true) || id.equals("Etc/UTC", true) || id.equals("GMT", true)) return "UTC"
        return id.substringAfterLast('/').replace('_', ' ')
    }

    /** "GMT+2", "GMT-5", "GMT+5:30" on [on] (summer time included). */
    fun offsetLabel(zone: ZoneId, on: LocalDate): String {
        val total = offsetSeconds(zone, on)
        if (total == 0) return "GMT"
        val abs = Math.abs(total)
        val h = abs / 3600
        val m = abs / 60 % 60
        return "GMT" + (if (total < 0) "-" else "+") + h + (if (m != 0) String.format(Locale.ROOT, ":%02d", m) else "")
    }

    /** "Athens · GMT+3". */
    fun label(zone: ZoneId, on: LocalDate): String {
        val city = city(zone)
        return if (city == "UTC") "UTC" else "$city · ${offsetLabel(zone, on)}"
    }

    private fun offsetSeconds(zone: ZoneId, on: LocalDate): Int = on.atTime(12, 0).atZone(zone).offset.totalSeconds
}
