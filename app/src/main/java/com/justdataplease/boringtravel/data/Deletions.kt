package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.doc.EntryDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deleting things the traveller made, with what hangs off them (rules in [TripRemoval]). Each runs in the app's scope,
 * so leaving the screen half-way never leaves it half-done, and returns when done.
 */
class Deletions(private val graph: AppGraph) {
    /**
     * Trip ⋮ › Delete trip and Edit trip › Delete trip. A trip of the traveller's own goes with its plan, journal,
     * photos (the app's copies), stops, recorded route, book, clips and the clip editor's files; a shared trip is left
     * instead (Travel together: the others keep it, and its rows stay here for joining again). Either way the trip's
     * events leave the phone's calendar. Community posts stay: each is a copy of its own, deleted from the post.
     */
    suspend fun deleteTrip(tripId: String) {
        val shared = graph.together.engine.load().of(tripId)
        if (shared != null && shared.members.isNotEmpty()) return leaveTrip(tripId)
        graph.scope.launch {
            // Invited to, but nobody joined: sharing stops, and the trip is deleted like any other.
            if (shared != null) graph.together.leave(tripId)
            val photos = graph.trips.deleteWithContents(tripId)
            graph.media.freeFiles(photos)
            SavedClips.of(graph.app).deleteTrip(tripId)
            withContext(Dispatchers.IO) { runCatching { TripRemoval.deleteClipFiles(graph.app.filesDir, tripId) } }
            graph.calendar.removeDeleted(tripId)
        }.join()
    }

    /**
     * Leaves shared trip [tripId] (Travel together › Leave, or deleting it): the others keep it; here only the trip's
     * row is marked deleted, so joining again brings it back with everything in it. Its calendar events go.
     */
    suspend fun leaveTrip(tripId: String) {
        graph.scope.launch {
            graph.together.leave(tripId)
            graph.trips.delete(tripId)
            graph.calendar.removeDeleted(tripId)
        }.join()
    }

    /** A journal entry and its photos (both kept as marked rows for sync and backups); the photos' files go. */
    suspend fun deleteEntry(entry: Entry) {
        graph.scope.launch {
            graph.journal.delete(entry.id)
            val photos = EntryDoc.parse(entry.body).mediaIds().distinct().mapNotNull { graph.media.get(it) }
            photos.forEach { graph.media.delete(it) }
            graph.media.freeFiles(photos.mapNotNull { graph.media.get(it.id) })
        }.join()
    }

    /**
     * One photo (Media tab): it leaves every entry of the traveller's own that shows it and the trip's cover, so the
     * book and new clips (they draw only live photos) leave it out too; then the app's copy goes. A shared trip's
     * other members' entries are theirs to change.
     */
    suspend fun deletePhoto(media: Media) {
        graph.scope.launch {
            val inTrip = media.tripId?.let { graph.journal.forTrip(it) }.orEmpty()
            val own = media.entryId?.takeIf { id -> inTrip.none { it.id == id } }?.let { graph.journal.get(it) }
            for (e in inTrip + listOfNotNull(own)) {
                if (e.authorId != null || e.deletedAt != null) continue
                val doc = EntryDoc.parse(e.body)
                val without = TripRemoval.withoutPhoto(doc, media.id)
                if (without != doc) graph.journal.save(e, without)
            }
            val current = graph.media.get(media.id)?.takeIf { it.deletedAt == null } ?: return@launch
            graph.media.delete(current)
            graph.media.freeFiles(listOfNotNull(graph.media.get(media.id)))
        }.join()
    }
}
