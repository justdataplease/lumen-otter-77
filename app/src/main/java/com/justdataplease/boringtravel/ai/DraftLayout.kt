package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import java.text.Normalizer
import com.justdataplease.boringtravel.model.DayNames
import java.time.LocalDate
import java.util.Locale

/** One draft item placed for the preview: [key] is stable for the same draft (used to untick it). */
data class PlacedItem(
    val key: String,
    val item: DraftItem,
    /** Null = an idea. */
    val day: LocalDate?,
    /** The note as it will be saved (for an idea that lost its day: "Day 3 · …"). */
    val note: String,
    /** Same title as an item already in the plan (or earlier in this draft) on the same day: never added. */
    val duplicate: Boolean,
)

data class PlacedDay(val date: LocalDate, val dayNumber: Int?, val items: List<PlacedItem>)

/**
 * A day the draft names ([DayNames]): its [number] in the trip (null outside its dates), the name it has now
 * ([before], null: none) and the new one ([name], null: taken away). [key] unticks it.
 */
data class PlacedDayName(val key: String, val day: LocalDate, val number: Int?, val before: String?, val name: String?)

/** A saved item the draft removes; [key] unticks it. */
data class PlacedRemoval(val key: String, val item: PlanItem)

/**
 * A change to the saved trip itself, each part reviewed on its own: a new [title], new dates ([start]–[end]; the plan
 * moves [shiftDays] with them when the draft said so) and a new [route] line. Null parts don't change.
 */
data class TripChange(
    val before: Trip,
    val title: String? = null,
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val route: String? = null,
    val shiftDays: Long = 0,
) {
    val datesChanged: Boolean get() = start != null && end != null && (start != before.startDate || end != before.endDate)
    val parts: Int get() = listOf(title != null, datesChanged, route != null).count { it }

    companion object {
        const val TITLE = "t:title"
        const val DATES = "t:dates"
        const val ROUTE = "t:route"
    }
}

/**
 * A draft laid out against a trip: the trip's dates (kept, changed by the draft, made longer for a day outside them,
 * or taken from the draft when the trip has none), the days in date order, the ideas, which items repeat what is
 * already planned, the changes to saved items and the trip, and the removals. The preview shows it;
 * [DraftApplier] saves it, so both always agree.
 */
data class DraftLayout(
    val title: String,
    val start: LocalDate?,
    val end: LocalDate?,
    /** The draft set the dates of a trip that had none (or of a new trip). */
    val datesFromDraft: Boolean,
    val summary: String,
    val days: List<PlacedDay>,
    val ideas: List<PlacedItem>,
    val questions: List<String> = emptyList(),
    val updates: List<PlacedUpdate> = emptyList(),
    val problems: List<String> = emptyList(),
    /** A new trip's route line. */
    val route: String = "",
    val tripChange: TripChange? = null,
    val removals: List<PlacedRemoval> = emptyList(),
    /** The days the draft names or renames, in date order: only the ones whose name changes. */
    val dayNames: List<PlacedDayName> = emptyList(),
) {
    val all: List<PlacedItem> get() = days.flatMap { it.items } + ideas
    val addable: List<PlacedItem> get() = all.filterNot { it.duplicate }
    val reviewableCount: Int get() = addable.size + updates.count { it.changed } + removals.size + (tripChange?.parts ?: 0) + dayNames.size

    companion object {
        /** Longest trip the planner sets up from a draft. */
        const val MAX_TRIP_DAYS = 90
        const val NEW_TRIP_TITLE = "New trip"

        fun of(draft: TripDraft, trip: Trip?, existing: List<PlanItem>, baseline: List<PlanItem> = existing): DraftLayout {
            val draftStart = draft.startDate?.let(::date)
            val draftEnd = draft.endDate?.let(::date)
            val removedIds = draft.removeItemIds.toSet()
            val patches = draft.updates.filter { it.itemId !in removedIds }
            // Every date the draft puts something on: new items' days and changed items' new days.
            val dayDates = draft.days.mapNotNull { d -> d.date?.let(::date) } +
                patches.mapNotNull { u -> u.day?.takeIf { it.isNotBlank() }?.let(::date) }
            val maxIndex = draft.days.mapIndexed { i, d -> d.dayIndex ?: (i + 1) }.maxOrNull() ?: 0

            val tripStart = trip?.startDate
            val tripEnd = trip?.endDate
            val start: LocalDate?
            val end: LocalDate?
            val fromDraft: Boolean
            var shift = 0L
            if (tripStart != null && tripEnd != null) {
                // A saved trip with dates: the draft may move it, resize it, or need a day outside it.
                val length = tripEnd.toEpochDay() - tripStart.toEpochDay()
                val moves = draft.moveItemsWithDates == true
                var s = draftStart ?: if (draftEnd != null && moves) draftEnd.minusDays(length) else tripStart
                var e = draftEnd ?: if (draftStart != null && moves) draftStart.plusDays(length) else tripEnd
                if (e.isBefore(s)) { s = tripStart; e = tripEnd }
                if (moves) shift = s.toEpochDay() - tripStart.toEpochDay()
                dayDates.minOrNull()?.let { if (it.isBefore(s)) s = it }
                dayDates.maxOrNull()?.let { if (it.isAfter(e)) e = it }
                if (e.toEpochDay() - s.toEpochDay() >= MAX_TRIP_DAYS) e = s.plusDays(MAX_TRIP_DAYS - 1L)
                start = s
                end = e
                fromDraft = false
            } else {
                // A start the trip already has stays; otherwise the earliest of the draft's start and its dated days.
                val s = tripStart ?: listOfNotNull(draftStart, dayDates.minOrNull()).minOrNull()
                if (s == null) {
                    start = null
                    end = null
                    fromDraft = false
                } else {
                    val byIndex = s.plusDays(maxOf(maxIndex, 1) - 1L)
                    val e = listOfNotNull(draftEnd, byIndex, dayDates.maxOrNull()).max()
                    start = s
                    end = if (e.toEpochDay() - s.toEpochDay() >= MAX_TRIP_DAYS) s.plusDays(MAX_TRIP_DAYS - 1L) else e
                    fromDraft = true
                }
            }

            val live = existing.filter { it.deletedAt == null }
            val takenOnDay = HashMap<LocalDate?, MutableSet<String>>()
            live.filter { it.id !in removedIds }.forEach { takenOnDay.getOrPut(it.day?.plusDays(shift)) { HashSet() } += normaliseTitle(it.title) }
            val takenAnywhere = live.filter { it.id !in removedIds }.mapTo(HashSet()) { normaliseTitle(it.title) }

            val byDay = sortedMapOf<LocalDate, MutableList<PlacedItem>>()
            val ideas = ArrayList<PlacedItem>()
            draft.days.forEachIndexed { di, d ->
                val index = d.dayIndex ?: (di + 1)
                val resolved = d.date?.let(::date) ?: start?.plusDays(index - 1L)
                val inTrip = resolved != null && start != null && end != null && !resolved.isBefore(start) && !resolved.isAfter(end)
                d.items.forEachIndexed { ii, item ->
                    val key = "d$di-$ii"
                    if (inTrip) {
                        val t = normaliseTitle(item.title)
                        val dup = !takenOnDay.getOrPut(resolved) { HashSet() }.add(t)
                        takenAnywhere += t
                        byDay.getOrPut(resolved) { ArrayList() } += PlacedItem(key, item, resolved, item.note, dup)
                    } else {
                        // No dates to place it on, or a date outside the trip: keep it as an idea, with its day in the note.
                        val label = resolved?.toString() ?: "Day $index"
                        ideas += idea(key, item, label, takenAnywhere)
                    }
                }
            }
            draft.ideas.forEachIndexed { i, item -> ideas += idea("i$i", item, null, takenAnywhere) }

            val days = byDay.map { (date, items) ->
                PlacedDay(date, start?.let { (date.toEpochDay() - it.toEpochDay() + 1).toInt() }, items)
            }
            val title = trip?.title ?: draft.title?.takeIf { it.isNotBlank() } ?: NEW_TRIP_TITLE
            val questions = draft.questions.toMutableList()
            if (draft.days.isNotEmpty() && (start == null || end == null || end.isBefore(start))) {
                questions += "What are the trip's start and end dates, including the year?"
            }
            val edits = DraftEdits.preview(patches, trip, baseline, start, end)
            val baselineLive = baseline.filter { it.deletedAt == null && it.tripId == trip?.id }.associateBy { it.id }
            val removals = draft.removeItemIds.distinct().mapNotNull { baselineLive[it] }.map { PlacedRemoval("r:${it.id}", it) }

            val change = trip?.let { t ->
                val rename = draft.title?.trim()?.takeIf { it.isNotBlank() && it != t.title }
                val reroute = draft.route?.trim()?.takeIf { it.isNotBlank() && it != t.subtitle }
                val datesMove = tripStart != null && tripEnd != null && (start != tripStart || end != tripEnd)
                TripChange(t, rename, if (datesMove) start else null, if (datesMove) end else null, reroute, if (datesMove) shift else 0)
                    .takeIf { it.parts > 0 }
            }
            // The days' names as saved move with the plan; the draft's are by the dates as they will be.
            val savedNames = DayNames.all(trip?.travelModes).mapKeys { it.key.plusDays(shift) }
            val named = draft.dayNames.mapNotNull { n -> date(n.date)?.let { it to DayNames.clean(n.name) } }.toMap()
                .filter { (day, name) -> savedNames[day] != name }.toSortedMap()
                .map { (day, name) ->
                    val number = if (start != null && end != null && !day.isBefore(start) && !day.isAfter(end)) (day.toEpochDay() - start.toEpochDay() + 1).toInt() else null
                    PlacedDayName("n:$day", day, number, savedNames[day], name)
                }
            return DraftLayout(
                title, start, end, fromDraft, draft.summary, days, ideas, questions.distinct(), edits.updates, edits.problems,
                route = if (trip == null) draft.route.orEmpty() else trip.subtitle, tripChange = change, removals = removals,
                dayNames = named,
            )
        }

        private fun idea(key: String, item: DraftItem, dayLabel: String?, taken: MutableSet<String>): PlacedItem {
            val note = if (dayLabel == null) item.note else listOf(dayLabel, item.note).filter { it.isNotBlank() }.joinToString(" · ")
            val dup = !taken.add(normaliseTitle(item.title))
            return PlacedItem(key, item, null, note, dup)
        }

        private fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()

        /** Lower case, no accents or punctuation, one space: "Portara at sunset!" = "portara at  Sunset". */
        fun normaliseTitle(s: String): String =
            Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace('ς', 'σ')
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
    }
}
