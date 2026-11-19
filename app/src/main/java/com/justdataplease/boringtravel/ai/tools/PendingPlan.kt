package com.justdataplease.boringtravel.ai.tools

import com.justdataplease.boringtravel.ai.DraftDay
import com.justdataplease.boringtravel.ai.DraftDayName
import com.justdataplease.boringtravel.ai.DraftItem
import com.justdataplease.boringtravel.ai.DraftUpdate
import com.justdataplease.boringtravel.ai.TripDraft
import java.time.LocalDate

/** What [PlannerTools.SET_TRIP] asked for; null = keep. */
data class TripPatch(
    val title: String? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val route: String? = null,
    val moveItemsWithDates: Boolean? = null,
    /** Days named or renamed, by date; an empty name takes the day's name away. */
    val dayNames: Map<LocalDate, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = this == TripPatch()
}

/** An item [PlannerTools.ADD_ITEMS] added, not saved yet: [id] is "new-1", "new-2"… so later calls can change it. */
data class PendingItem(val id: String, val date: LocalDate?, val dayIndex: Int?, val item: DraftItem)

/**
 * The planner's changes so far, waiting for the traveller's review: the trip, new items, changes to saved items (one
 * merged patch per id) and removals. Immutable: each tool call makes a new one. [toDraft] lays it out as the
 * [TripDraft] the review shows and [com.justdataplease.boringtravel.ai.DraftApplier] saves.
 */
data class PendingPlan(
    val trip: TripPatch = TripPatch(),
    val added: List<PendingItem> = emptyList(),
    val updates: Map<String, DraftUpdate> = emptyMap(),
    val removed: Set<String> = emptySet(),
    val questions: List<String> = emptyList(),
    val summary: String = "",
    val nextId: Int = 1,
) {
    val hasChanges: Boolean get() = !trip.isEmpty || added.isNotEmpty() || updates.isNotEmpty() || removed.isNotEmpty()

    fun toDraft(): TripDraft {
        val dated = added.filter { it.date != null }.groupBy { it.date!! }.toSortedMap()
            .map { (date, items) -> DraftDay(date = date.toString(), items = items.map { it.item }) }
        val indexed = added.filter { it.date == null && it.dayIndex != null }.groupBy { it.dayIndex!! }.toSortedMap()
            .map { (index, items) -> DraftDay(dayIndex = index, items = items.map { it.item }) }
        return TripDraft(
            title = trip.title,
            startDate = trip.startDate?.toString(),
            endDate = trip.endDate?.toString(),
            route = trip.route,
            moveItemsWithDates = trip.moveItemsWithDates,
            dayNames = trip.dayNames.map { (day, name) -> DraftDayName(day.toString(), name) },
            questions = questions,
            updates = updates.values.toList(),
            removeItemIds = removed.toList(),
            days = dated + indexed,
            ideas = added.filter { it.date == null && it.dayIndex == null }.map { it.item },
            summary = summary,
        )
    }

    /** [patch] over the pending change to the same saved item: its fields win, appended notes add up. */
    fun withUpdate(patch: DraftUpdate): PendingPlan {
        val before = updates[patch.itemId] ?: return copy(updates = updates + (patch.itemId to patch))
        // Another place without coordinates: the earlier pin was the earlier place's, so the app looks the new one up.
        val newPlace = patch.placeName != null && patch.placeName != before.placeName && patch.lat == null
        val note = patch.note ?: before.note
        val append = if (patch.note != null) patch.noteAppend else listOfNotNull(before.noteAppend, patch.noteAppend).filter { it.isNotBlank() }.distinct().joinToString("\n").ifBlank { null }
        val merged = DraftUpdate(
            itemId = patch.itemId,
            day = patch.day ?: before.day,
            time = patch.time ?: before.time,
            endDate = patch.endDate ?: before.endDate,
            endTime = patch.endTime ?: before.endTime,
            title = patch.title ?: before.title,
            // A note replaced earlier, then added to: one new note (a change can't both replace and add).
            note = if (note != null && append != null) listOf(note, append).filter { it.isNotBlank() }.joinToString("\n") else note,
            noteAppend = if (note != null) null else append,
            placeName = patch.placeName ?: before.placeName,
            lat = if (patch.lat != null) patch.lat else if (newPlace) null else before.lat,
            lng = if (patch.lat != null) patch.lng else if (newPlace) null else before.lng,
            kind = patch.kind ?: before.kind,
            travel = patch.travel ?: before.travel,
            url = patch.url ?: before.url,
        )
        return copy(updates = updates + (patch.itemId to merged))
    }
}
