package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.links.MapsLinks
import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.model.Vehicle
import java.time.LocalDate

/** What applying a reviewed draft will write: the trip, new items, and validated edits to stable existing ids. */
data class ApplyResult(
    val trip: Trip,
    /** True when [trip] has to be saved: it is new, or the draft changed it (name, dates, route, travel choices). */
    val saveTrip: Boolean,
    val newTrip: Boolean,
    val items: List<PlanItem>,
    /** Items left out because the plan already had them. */
    val duplicates: Int,
    /** "12 items on 5 days, 3 ideas". */
    val summary: String,
    val updatedItems: List<PlanItem> = emptyList(),
    val alreadyUpdated: Int = 0,
    /** Saved items removed (marked deleted). */
    val removedItems: List<PlanItem> = emptyList(),
    /** Saved items that only moved with the trip's new dates. */
    val movedItems: List<PlanItem> = emptyList(),
    /** The trip's dates changed. */
    val datesChanged: Boolean = false,
    /** Days named, renamed or un-named. */
    val namedDays: Int = 0,
) {
    /** Every plan row to write. */
    val rows: List<PlanItem> get() = items + updatedItems + movedItems + removedItems
}

/**
 * Turns a reviewed draft into rows. Pure: no database, no clock, no random ids (all passed in), so it is tested on
 * the JVM. New items go into their day by start time; duplicates and unticked items are left out. Saved items change
 * only through reviewed, baseline-checked patches, removals and the trip's own move. A trip without dates can receive
 * the draft's dates. New items use `source = "ai"`, keep the website the traveller gave or get a Google Maps link,
 * and how the AI said they are reached becomes the leg's travel choice. The days the draft names get their names
 * ([DayNames]); the names a trip has move with it when its plan moves.
 */
object DraftApplier {
    fun apply(
        layout: DraftLayout,
        trip: Trip?,
        existing: List<PlanItem>,
        unticked: Set<String>,
        draftId: String,
        now: Long,
        newId: () -> String,
    ): ApplyResult {
        require(layout.questions.isEmpty()) { "Answer the planner's questions before adding this draft." }
        require(layout.problems.isEmpty()) { "The proposed changes need a fresh review." }
        val removedIds = layout.removals.filter { it.key !in unticked }.map { it.item.id }.toSet()
        val updates = DraftEdits.resolve(layout.updates.filter { it.before.id !in removedIds }, existing, unticked, now)
        val change = layout.tripChange
        val datesOn = change != null && change.datesChanged && TripChange.DATES !in unticked
        val newTrip = trip == null
        var target = when {
            trip == null -> Trip(
                id = newId(), title = layout.title, subtitle = layout.route, startDate = layout.start, endDate = layout.end,
                createdAt = now, updatedAt = now,
            )
            else -> {
                var t = trip
                if (change?.title != null && TripChange.TITLE !in unticked) t = t.copy(title = change.title)
                if (change?.route != null && TripChange.ROUTE !in unticked) t = t.copy(subtitle = change.route)
                if (datesOn) t = t.copy(startDate = change!!.start, endDate = change.end)
                else if (layout.datesFromDraft && (trip.startDate == null || trip.endDate == null)) {
                    t = t.copy(startDate = trip.startDate ?: layout.start, endDate = trip.endDate ?: layout.end)
                }
                if (t != trip) t.copy(updatedAt = maxOf(now, trip.updatedAt + 1)) else t
            }
        }

        // Moves with the trip: every dated saved item that no reviewed change gives a day of its own.
        val shift = if (datesOn) change!!.shiftDays else 0L
        // The days' own names move with the plan too; then the reviewed names are set.
        val named = layout.dayNames.filter { it.key !in unticked }
        val names = DayNames.setAll(DayNames.shift(target.travelModes, shift), named.associate { it.day to it.name })
        if (names != target.travelModes) target = target.copy(travelModes = names, updatedAt = maxOf(now, (trip?.updatedAt ?: 0L) + 1))
        val ownDay = layout.updates.filter { it.key !in unticked && it.after.day != it.before.day && it.before.id !in removedIds }.map { it.before.id }.toSet()
        val replacements = updates.items.associateBy { it.id }.toMutableMap()
        val live = existing.filter { it.deletedAt == null && it.tripId == target.id }
        val moved = ArrayList<PlanItem>()
        if (shift != 0L) for (row in live) {
            val day = row.day ?: continue
            if (row.id in removedIds || row.id in ownDay) continue
            val replaced = replacements[row.id]
            if (replaced != null) replacements[row.id] = replaced.copy(day = day.plusDays(shift))
            else moved += row.copy(day = day.plusDays(shift), updatedAt = maxOf(now, row.updatedAt + 1))
        }
        val removed = live.filter { it.id in removedIds }.map { it.copy(deletedAt = now, updatedAt = maxOf(now, it.updatedAt + 1)) }

        // The plan as it will be, so new items and items moved to another day go in by start time.
        val movedById = moved.associateBy { it.id }
        val after = live.filter { it.id !in removedIds }.map { replacements[it.id] ?: movedById[it.id] ?: it }
        val byDay = after.groupBy { it.day }.mapValues { (_, rows) -> rows.sortedWith(ORDER).toMutableList() }.toMutableMap()
        for (id in ownDay) {
            val row = replacements[id] ?: continue
            val day = byDay.getOrPut(row.day) { ArrayList() }
            day.removeAll { it.id == id }
            val placed = row.copy(position = DayOrder.position(day, row.timeMinutes, null))
            day.add(placed)
            day.sortWith(ORDER)
            replacements[id] = placed
        }

        val occupied = after.groupBy { it.day }.mapValues { (_, rows) -> rows.mapTo(HashSet()) { DraftLayout.normaliseTitle(it.title) } }.toMutableMap()
        var concurrentDuplicates = 0
        val chosen = layout.all.filter { !it.duplicate && it.key !in unticked }.filter { p ->
            val fresh = occupied.getOrPut(p.day) { HashSet() }.add(DraftLayout.normaliseTitle(p.item.title))
            if (!fresh) concurrentDuplicates++
            fresh
        }
        val lastPlaced = HashMap<LocalDate?, PlanItem>()
        val travel = HashMap<String, Vehicle>()
        val items = chosen.map { p ->
            val day = byDay.getOrPut(p.day) { ArrayList() }
            val time = p.item.time?.let(::minutes)
            // The end as days after the item's own day, so it moves with it.
            val endDays = p.item.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { e -> p.day?.let { PlanSpan.daysAfter(it, e) } }
            val row = PlanSpan.normalized(PlanItem(
                id = newId(),
                tripId = target.id,
                day = p.day,
                position = DayOrder.position(day, time, lastPlaced[p.day]),
                timeMinutes = time,
                kind = p.item.kind,
                title = p.item.title,
                note = p.note,
                placeName = p.item.placeName.orEmpty(),
                lat = p.item.lat,
                lng = p.item.lng,
                source = ItemSource.Ai.id,
                externalId = draftId,
                createdAt = now,
                updatedAt = now,
                // A Google Maps link given is the Maps link; without a website, one to the place.
                url = p.item.url?.takeUnless(MapsLinks::isMaps),
                mapsUrl = p.item.url?.takeIf(MapsLinks::isMaps) ?: if (p.item.url == null) mapsLink(p.item) else null,
                endDays = endDays,
                endMinutes = p.item.endTime?.let(::minutes),
            ))
            day.add(row)
            day.sortWith(ORDER)
            lastPlaced[p.day] = row
            vehicle(p.item.travel)?.let { travel[row.id] = it }
            row
        }
        layout.updates.filter { it.key !in unticked && it.travel != null && it.before.id !in removedIds }.forEach { travel[it.before.id] = it.travel!! }
        if (travel.isNotEmpty()) target = withTravel(target, after.map { replacements[it.id] ?: it } + items, travel, now)

        return ApplyResult(
            trip = target,
            saveTrip = trip == null || target != trip,
            newTrip = newTrip,
            items = items,
            duplicates = layout.all.count { it.duplicate } + concurrentDuplicates,
            summary = summary(items),
            updatedItems = replacements.values.toList(),
            alreadyUpdated = updates.alreadyApplied,
            removedItems = removed,
            movedItems = moved,
            datesChanged = datesOn,
            namedDays = named.size,
        )
    }

    private val ORDER = PlanSpan.ORDER

    /** The leg into each item of [travel] takes that vehicle when it differs from what the plan would show anyway. */
    private fun withTravel(trip: Trip, items: List<PlanItem>, travel: Map<String, Vehicle>, now: Long): Trip {
        var raw = trip.travelModes
        val legs = runCatching { RouteLegs.planned(trip, items) }.getOrDefault(emptyList())
        for (leg in legs) {
            val wanted = travel[leg.key.toId] ?: continue
            val shown = leg.vehicle ?: TravelDefault.between(leg.toTitle, leg.samePlace, leg.distanceM, leg.toTravel)
            if (shown == wanted) continue
            raw = runCatching { TravelModes.set(raw, leg.key, wanted) }.getOrDefault(raw)
        }
        return if (raw == trip.travelModes) trip else trip.copy(travelModes = raw, updatedAt = maxOf(now, trip.updatedAt + 1))
    }

    private fun vehicle(id: String?): Vehicle? = id?.let { v -> Vehicle.entries.firstOrNull { it.id == v && it != Vehicle.Auto } }

    /** A Google Maps link for a located or named item that came without a website; none for notes. */
    private fun mapsLink(item: DraftItem): String? {
        if (item.kind == PlanKind.Note.id) return null
        return MapsLinks.searchUrl(item.placeName, item.lat, item.lng)
    }

    /** "12 items on 5 days, 3 ideas", "1 item on 1 day", "2 ideas", "Nothing new". */
    fun summary(items: List<PlanItem>): String {
        val onDays = items.filter { it.day != null }
        val days = onDays.mapNotNull { it.day }.distinct().size
        val ideas = items.size - onDays.size
        val parts = buildList {
            if (onDays.isNotEmpty()) add("${plural(onDays.size, "item")} on ${plural(days, "day")}")
            if (ideas > 0) add(plural(ideas, "idea"))
        }
        return if (parts.isEmpty()) "Nothing new" else parts.joinToString(", ")
    }

    private fun plural(n: Int, word: String) = "$n ${if (n == 1) word else word + "s"}"

    private fun minutes(hhmm: String): Int? {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }
}

/**
 * Where a new item goes in a day ([day] in order): a timed item before the first item that starts later (the clock
 * wins over the order the AI listed them in); an untimed one right after the item placed before it from the same
 * draft ([after], keeps the draft's order), else at the end.
 */
internal object DayOrder {
    fun position(day: List<PlanItem>, time: Int?, after: PlanItem?): Double {
        if (day.isEmpty()) return 1.0
        val insertAt = if (time == null) {
            after?.let { a -> day.indexOfFirst { it.id == a.id } + 1 }?.takeIf { it > 0 } ?: day.size
        } else {
            day.indices.firstOrNull { i -> day[i].timeMinutes?.let { it > time } == true } ?: day.size
        }
        val before = day.getOrNull(insertAt - 1)?.position
        val next = day.getOrNull(insertAt)?.position
        return when {
            before == null && next == null -> 1.0
            before == null -> next!! - 1.0
            next == null -> before + 1.0
            else -> (before + next) / 2.0
        }
    }
}
