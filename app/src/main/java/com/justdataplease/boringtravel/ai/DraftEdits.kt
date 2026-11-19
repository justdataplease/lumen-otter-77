package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.links.MapsLinks
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.Vehicle
import java.time.LocalDate

/**
 * Baseline captured when the AI request started, and the exact values shown for review. [travel]: how the traveller
 * gets to the item, saved as its leg's choice.
 */
data class PlacedUpdate(val key: String, val before: PlanItem, val after: PlanItem, val travel: Vehicle? = null) {
    val changed: Boolean get() = before != after || travel != null
}

data class UpdatePreview(val updates: List<PlacedUpdate>, val problems: List<String>)
data class ResolvedUpdates(val items: List<PlanItem>, val alreadyApplied: Int)

class StaleDraftException(message: String) : Exception(message)

/** Patches are strict, preserve all omitted fields, and never delete or recreate an existing item. */
object DraftEdits {
    /** [start]–[end]: the trip's dates once the draft is saved (the draft may move or extend them). */
    fun preview(
        patches: List<DraftUpdate>, trip: Trip?, baseline: List<PlanItem>,
        start: LocalDate? = trip?.startDate, end: LocalDate? = trip?.endDate,
    ): UpdatePreview {
        val existing = baseline.filter { it.deletedAt == null && it.tripId == trip?.id }.associateBy { it.id }
        val seen = HashSet<String>()
        val problems = ArrayList<String>()
        val updates = ArrayList<PlacedUpdate>()
        patches.forEach { patch ->
            val before = existing[patch.itemId]
            when {
                before == null -> problems += "A proposed change refers to an item that isn't in this trip. Ask the planner for a fresh draft."
                !seen.add(patch.itemId) -> problems += "The planner proposed more than one change to ${before.title}. Ask it to combine those changes."
                else -> try {
                    updates += PlacedUpdate("u:${before.id}", before, applyPatch(before, patch, start, end), patch.travel?.let { id -> Vehicle.entries.firstOrNull { it.id == id && it != Vehicle.Auto } })
                } catch (e: IllegalArgumentException) {
                    problems += "${before.title}: ${e.message}"
                }
            }
        }
        return UpdatePreview(updates, problems.distinct())
    }

    private fun applyPatch(before: PlanItem, patch: DraftUpdate, start: LocalDate?, end: LocalDate?): PlanItem {
        val day = if (patch.day == null) before.day else patch.day.trim().let {
            if (it.isEmpty()) null else {
                runCatching { LocalDate.parse(it) }.getOrNull() ?: throw IllegalArgumentException("The proposed date isn't valid. Ask for yyyy-MM-dd.")
            }
        }
        if (patch.day != null && day != null && start != null && end != null) {
            require(!day.isBefore(start) && !day.isAfter(end)) { "The new date is outside this trip. Change the trip dates first." }
        }
        fun clockOf(raw: String, what: String): Int {
            require(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d").matches(raw)) { "The proposed $what isn't valid. Ask for HH:mm." }
            return raw.take(2).toInt() * 60 + raw.takeLast(2).toInt()
        }
        val time = if (patch.time == null) before.timeMinutes else patch.time.trim().let { if (it.isEmpty()) null else clockOf(it, "time") }
        // The end: "" removes it; a new last day counts from the item's (new) day, so a moved item keeps its length.
        var endDays = before.endDays
        var endMinutes = before.endMinutes
        patch.endDate?.trim()?.let { raw ->
            if (raw.isEmpty()) { endDays = null; endMinutes = null }
            else {
                val last = runCatching { LocalDate.parse(raw) }.getOrNull() ?: throw IllegalArgumentException("The proposed end date isn't valid. Ask for yyyy-MM-dd.")
                val from = requireNotNull(day) { "An idea without a day has no end. Give it a day first." }
                endDays = requireNotNull(PlanSpan.daysAfter(from, last)) { "The end date must be on or after the day, within two months." }
            }
        }
        patch.endTime?.trim()?.let { raw -> endMinutes = if (raw.isEmpty()) null else clockOf(raw, "end time") }
        val title = patch.title?.trim() ?: before.title
        require(patch.title == null || title.isNotBlank() && title.length <= 160) { "The changed title must be between 1 and 160 characters." }
        require(patch.note == null || patch.noteAppend == null) { "The draft both replaces and appends the note. Ask for one clear change." }
        require(patch.note?.length?.let { it <= 8000 } != false && patch.noteAppend?.length?.let { it <= 4000 } != false) { "The note is too long. Split the update into smaller changes." }
        val append = patch.noteAppend?.trim().orEmpty()
        val note = patch.note ?: if (append.isBlank() || before.note.contains(append)) before.note else listOf(before.note.trimEnd(), append).filter { it.isNotBlank() }.joinToString("\n")
        require((patch.lat == null) == (patch.lng == null)) { "A changed map location needs both coordinates." }
        if (patch.lat != null && patch.lng != null) {
            require(patch.lat.isFinite() && patch.lng.isFinite() && patch.lat in -90.0..90.0 && patch.lng in -180.0..180.0 && !(patch.lat == 0.0 && patch.lng == 0.0)) { "The proposed map location isn't valid." }
        }
        val place = patch.placeName?.trim() ?: before.placeName
        require(patch.placeName == null || place.length <= 200) { "The place name is too long." }
        val kind = patch.kind?.let { PlanKind.of(TripDraftParser.kindOf(it)).id } ?: before.kind
        val moved = patch.placeName != null && place != before.placeName
        // Another place under another name, which the map didn't find: no pin rather than the old place's.
        val otherPlace = moved && title != before.title && patch.lat == null
        val lat = patch.lat ?: if (otherPlace) null else before.lat
        val lng = patch.lng ?: if (otherPlace) null else before.lng
        // A proposed Google Maps link is the Maps link, any other the website.
        val proposed = patch.url?.takeIf { it.isNotEmpty() }
        val proposedMaps = proposed?.takeIf(MapsLinks::isMaps)
        val url = when {
            patch.url == "" -> null
            proposed != null && proposedMaps == null -> proposed
            // A different place under another name keeps no website of the old one.
            moved && title != before.title -> null
            else -> before.url
        }
        val mapsUrl = when {
            proposedMaps != null -> proposedMaps
            // A different place keeps no Maps link of the old one: it follows. A website removed or gone leaves a
            // Google Maps link to the place in its place.
            moved && before.mapsUrl != null -> MapsLinks.searchUrl(place.ifBlank { null }, lat, lng)
            before.url != null && url == null && before.mapsUrl == null -> MapsLinks.searchUrl(place.ifBlank { null }, lat, lng)
            patch.url == "" && before.mapsUrl == null -> MapsLinks.searchUrl(place.ifBlank { null }, lat, lng)
            else -> before.mapsUrl
        }
        val after = before.copy(day = day, timeMinutes = time, title = title, note = note, placeName = place,
            lat = lat, lng = lng, kind = kind, url = url, mapsUrl = mapsUrl, endDays = endDays, endMinutes = endMinutes)
        // Only an end that changed is tidied, so an untouched item stays exactly as saved.
        return if (after.endDays == before.endDays && after.endMinutes == before.endMinutes && after.day == before.day && after.timeMinutes == before.timeMinutes) after
        else PlanSpan.normalized(after)
    }

    /** Must run with the caller's reads and writes in one database transaction. */
    fun resolve(updates: List<PlacedUpdate>, current: List<PlanItem>, unticked: Set<String>, now: Long): ResolvedUpdates {
        val rows = current.associateBy { it.id }
        val writes = ArrayList<PlanItem>()
        var already = 0
        updates.filter { it.key !in unticked && it.before != it.after }.forEach { update ->
            val row = rows[update.before.id]
            if (row == null || row.deletedAt != null || row.tripId != update.before.tripId) {
                throw StaleDraftException("${update.before.title} was removed or moved. Make a new draft before saving.")
            }
            if (row.copy(updatedAt = update.after.updatedAt) == update.after) {
                already++
            } else {
                // Compare the complete baseline as well as its timestamp: two edits may share a millisecond.
                if (row != update.before) throw StaleDraftException("${update.before.title} changed after this draft was made. Make a new draft to keep the latest details.")
                writes += update.after.copy(updatedAt = now)
            }
        }
        return ResolvedUpdates(writes, already)
    }
}
