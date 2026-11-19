package com.justdataplease.boringtravel.programs

import com.justdataplease.boringtravel.clip.PlanRoute
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.time.LocalDate

/**
 * Turns a trip idea into a trip: the destination, one of its programs, a pace and the first day give the trip and its
 * plan, laid out the way the example trips are (SampleBuilder.plan): one plan day per program day from [build]'s
 * start, items in program order, the legs' travel modes from each item's `via` (set through RouteLegs/TravelModes
 * like the planner sets them), `time` as the item's time, its website as the item's link, and the program's ideas
 * without a day. Every item says where it came from: source "program", externalId "<programId>:<day>:<index>"
 * ([externalId], read back by [itemAt]). Pure (ProgramTripsTest).
 */
object ProgramTrips {
    /** The trip row and its plan (ideas have no day). */
    data class Built(val trip: Trip, val items: List<PlanItem>)

    /** The day number in [externalId] for the program's ideas (days count from 1). */
    const val IDEAS_DAY = 0

    /**
     * The trip from [program] at [pace], starting [start], stamped [now]. Items on days before [doneBefore] are ticked
     * (notes never are), as the example trips do for days already travelled; a trip of the traveller's own starts with
     * nothing ticked. [trackingLevel] null = the app's setting (the examples use "off"). [links] sorts an item's
     * website and Google Maps link (the Android app passes LinkPlaces::sortLinks).
     */
    fun build(
        destination: Destination,
        program: Program,
        pace: Pace,
        start: LocalDate,
        now: Long,
        links: (PlanItem) -> PlanItem,
        ids: () -> String = ::newId,
        tripId: String = ids(),
        doneBefore: Int = 1,
        trackingLevel: String? = null,
    ): Built {
        val days = program.daysAt(pace)
        val trip = Trip(
            id = tripId, title = destination.title, subtitle = route(program), startDate = start,
            endDate = start.plusDays((days.size - 1).coerceAtLeast(0).toLong()),
            trackingLevel = trackingLevel, createdAt = now, updatedAt = now,
        )
        val items = ArrayList<PlanItem>()
        val via = HashMap<String, Vehicle>()
        fun add(item: ProgramItem, day: Int, position: Int, index: Int) {
            val kind = PlanKind.of(item.kind)
            // A Google Maps link is the item's Maps link, any other its website.
            val row = links(PlanItem(
                id = ids(), tripId = tripId, day = if (day == IDEAS_DAY) null else start.plusDays((day - 1).toLong()),
                position = position.toDouble(), timeMinutes = minutes(item.time), kind = kind.id,
                title = item.title.trim(), note = item.note.trim(), placeName = item.placeName.trim(),
                lat = item.at?.first, lng = item.at?.second,
                done = day != IDEAS_DAY && day < doneBefore && kind != PlanKind.Note,
                source = ItemSource.Program.id, externalId = externalId(program.id, day, index),
                createdAt = now, updatedAt = now, url = item.url?.trim()?.takeIf { it.isNotEmpty() },
            ))
            items += row
            item.via?.takeIf { it != Vehicle.Auto && item.at != null }?.let { via[row.id] = it }
        }
        days.forEachIndexed { d, day ->
            // The index in the program's own day (every pace), so the same item has the same id at any pace. daysAt
            // keeps the order, so the kept items are found walking the full day once (the same item twice stays two).
            val all = program.days[d].items
            var cursor = 0
            day.items.forEachIndexed { position, item ->
                while (cursor < all.size && all[cursor] !== item) cursor++
                add(item, d + 1, position + 1, if (cursor < all.size) cursor++ else position)
            }
        }
        program.ideas.forEachIndexed { i, item -> add(item, IDEAS_DAY, i + 1, i) }
        // Trains, walks and flights between the places, as the planner would set them.
        var modes: String? = null
        for (key in PlanRoute.legKeys(trip, items)) {
            val v = via[key.toId] ?: continue
            modes = TravelModes.set(modes, key, v)
        }
        return Built(trip.copy(travelModes = modes), items)
    }

    /** "kyoto-osaka-culture:3:0": item 0 of day 3 of that program; day [IDEAS_DAY] = its ideas. */
    fun externalId(programId: String, day: Int, index: Int): String = "$programId:$day:$index"

    /** The program item behind [externalId] among [programs] (its day's item, or an idea), or null when none is. */
    fun itemAt(programs: List<Program>, externalId: String?): ProgramItem? {
        val parts = externalId?.split(':')?.takeIf { it.size == 3 } ?: return null
        val program = programs.firstOrNull { it.id == parts[0] } ?: return null
        val day = parts[1].toIntOrNull() ?: return null
        val index = parts[2].toIntOrNull() ?: return null
        val items = if (day == IDEAS_DAY) program.ideas else program.days.getOrNull(day - 1)?.items ?: return null
        return items.getOrNull(index)
    }

    /** Where the nights are, in order: "Kyoto → Osaka" (a day trip keeps its base, so it never shows here). */
    fun route(program: Program): String {
        val bases = ArrayList<String>()
        for (day in program.days) {
            val base = day.base.trim()
            if (base.isNotEmpty() && !base.equals(bases.lastOrNull(), ignoreCase = true)) bases += base
        }
        return bases.joinToString(" → ")
    }

    /** "07:30" → 450 minutes after midnight; anything else (blank, "late", 25:00) → null. */
    fun minutes(time: String?): Int? {
        val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").matchEntire(time ?: return null) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }
}
