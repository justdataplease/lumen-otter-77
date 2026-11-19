package com.justdataplease.boringtravel.checkin

import androidx.room.withTransaction
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.db.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

class JournalCheckIns(private val graph: AppGraph) {
    private val store = CheckInStore(graph.app)

    suspend fun claim(tripId: String?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): CheckInPrompt? =
        withContext(Dispatchers.IO) {
            val day = DailyCheckIn.localDay(now, zone)
            val trip = if (tripId == null) graph.trips.currentOn(day) else graph.trips.get(tripId)
            trip ?: return@withContext null
            val places = DailyCheckIn.places(trip, graph.plan.items(trip.id), day)
            if (places.isEmpty()) return@withContext null
            // Today is already written for this trip: there is nothing to invite.
            if (DailyCheckIn.writtenOn(graph.journal.forTrip(trip.id), trip.id, day)) return@withContext null
            // One indexed LIMIT 1 query, not a complete route load. A point belonging to another trip is ignored.
            val recorded = graph.db.tracks().latest()?.takeIf { it.tripId == trip.id }
            val fix = recorded?.let { CheckInFix(it.time, it.lat, it.lng, it.accuracyM) }
            val nearest = DailyCheckIn.nearby(places, fix, now)
            if (!store.claim(trip.id, day)) return@withContext null
            CheckInPrompt(trip.id, trip.title, day, places, nearest?.itemId, fix?.time.takeIf { nearest != null })
        }

    /** The plan is reread in the same transaction as draft insertion; deleted/moved places cannot be confirmed. */
    suspend fun confirm(prompt: CheckInPrompt, itemId: String): Entry = withContext(Dispatchers.IO) {
        // In a shared trip each member's check-in is their own entry (the same id on every phone would clash). Read
        // before the transaction: a sync holds the shared trips' lock while it waits for the database.
        val member = graph.together.engine.load().of(prompt.tripId)?.me
        graph.db.withTransaction {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val trip = requireNotNull(graph.trips.get(prompt.tripId)) { "This trip is no longer available." }
            val place = DailyCheckIn.places(trip, graph.plan.items(trip.id), DailyCheckIn.localDay(now, zone))
                .firstOrNull { it.itemId == itemId }
            requireNotNull(place) { "Today's plan changed. Write a note or reopen the journal." }
            val existing = graph.journal.get(DailyCheckIn.entryId(prompt.tripId, prompt.day, member))
            val entry = DailyCheckIn.draft(existing, prompt, place, now, zone, member)
            if (entry !== existing) graph.db.entries().upsert(entry)
            entry
        }
    }
}
