package com.justdataplease.boringtravel.checkin

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.geo.Geo
import com.justdataplease.boringtravel.ui.trip.ItemNotes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class CheckInPlace(val itemId: String, val name: String, val lat: Double?, val lng: Double?)
data class CheckInFix(val time: Long, val lat: Double, val lng: Double, val accuracyM: Float?)
data class CheckInPrompt(
    val tripId: String,
    val tripTitle: String,
    val day: LocalDate,
    val places: List<CheckInPlace>,
    val nearbyItemId: String? = null,
    val nearbyAt: Long? = null,
)

/** Suggestions are questions, never evidence that a place was visited or an itinerary was completed. */
object DailyCheckIn {
    const val FRESH_MS = 5 * 60_000L
    const val MAX_ACCURACY_M = 100f
    const val NEARBY_M = 300.0

    fun localDay(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    fun places(trip: Trip, items: List<PlanItem>, day: LocalDate): List<CheckInPlace> {
        if (trip.deletedAt != null || !trip.isCurrent(day)) return emptyList()
        val plan = items.filter { it.tripId == trip.id && it.deletedAt == null }
        return plan.asSequence()
            .filter { it.day == day && it.kind != "note" }
            .sortedWith(compareBy<PlanItem> { it.position }.thenBy { it.timeMinutes ?: Int.MAX_VALUE }.thenBy { it.id })
            .mapNotNull { item ->
                // The note's place as one written from the item: "Alhambra (Granada)", so the item lists it.
                val name = ItemNotes.location(item, plan)
                if (name.isEmpty()) null else {
                    val located = validCoordinates(item.lat, item.lng)
                    CheckInPlace(item.id, name, item.lat.takeIf { located }, item.lng.takeIf { located })
                }
            }.distinctBy { Triple(it.name.lowercase(java.util.Locale.ROOT), it.lat, it.lng) }.toList()
    }

    /**
     * True when the owner already has a live entry in the trip written on [day] (in each entry's own zone): then
     * nothing is asked. In a shared trip, a friend's entry that day doesn't count: it's their note, not the owner's.
     */
    fun writtenOn(entries: List<Entry>, tripId: String, day: LocalDate): Boolean = entries.any { e ->
        e.tripId == tripId && e.deletedAt == null && e.authorId == null &&
            Instant.ofEpochMilli(e.occurredAt).atZone(runCatching { ZoneId.of(e.zoneId) }.getOrDefault(ZoneId.systemDefault())).toLocalDate() == day
    }

    fun validCoordinates(lat: Double?, lng: Double?): Boolean =
        lat != null && lng != null && lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

    /** Require both recent time and a conservative distance including the reported uncertainty. */
    fun nearby(places: List<CheckInPlace>, fix: CheckInFix?, now: Long): CheckInPlace? {
        if (fix == null || !validCoordinates(fix.lat, fix.lng)) return null
        val age = now - fix.time
        val accuracy = fix.accuracyM ?: return null
        if (age !in 0..FRESH_MS || !accuracy.isFinite() || accuracy < 0 || accuracy > MAX_ACCURACY_M) return null
        return places.mapNotNull { place ->
            if (!validCoordinates(place.lat, place.lng)) null
            else place to Geo.distanceM(fix.lat, fix.lng, place.lat!!, place.lng!!)
        }.filter { (_, distance) -> distance + accuracy <= NEARBY_M }.minByOrNull { it.second }?.first
    }

    /** Missing/declined location simply retains the manually selectable itinerary. */
    fun suggested(prompt: CheckInPrompt, fix: CheckInFix?, now: Long, locationAllowed: Boolean = true): CheckInPlace? =
        (if (locationAllowed) nearby(prompt.places, fix, now) else null) ?: prompt.places.firstOrNull()

    /**
     * The day's check-in entry id: the same for every confirmation that day, so a repeat opens the same note. In a
     * shared trip it includes this phone's [member] id there, so each member's check-in is their own entry.
     */
    fun entryId(tripId: String, day: LocalDate, member: String? = null): String = UUID.nameUUIDFromBytes(
        ("boringtravel:daily-check-in:$tripId:$day" + member?.let { ":$it" }.orEmpty()).toByteArray(Charsets.UTF_8),
    ).toString()

    /**
     * A repeat confirmation opens the original draft without replacing any later edits. [member]: this phone's id in
     * the trip when it is shared. An entry at that id written by someone else is never opened: a new note instead.
     */
    fun draft(
        existing: Entry?,
        prompt: CheckInPrompt,
        place: CheckInPlace,
        now: Long,
        zone: ZoneId,
        member: String? = null,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Entry {
        require(localDay(now, zone) == prompt.day) { "A new day has started. Open the journal again." }
        val id = entryId(prompt.tripId, prompt.day, member)
        if (existing != null && existing.authorId == null) {
            require(existing.id == id && existing.tripId == prompt.tripId && existing.deletedAt == null) {
                "This check-in was removed. Write a new note instead."
            }
            return existing
        }
        val doc = EntryDoc(blocks = listOf(Block.Place(place.name, place.lat, place.lng)))
        return Entry(
            id = if (existing == null) id else newId(), tripId = prompt.tripId, occurredAt = now, zoneId = zone.id,
            body = doc.toJson(), plainText = place.name, placeName = place.name,
            lat = place.lat, lng = place.lng, createdAt = now, updatedAt = now,
        )
    }
}
