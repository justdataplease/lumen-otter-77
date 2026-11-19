package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip

/**
 * Deleting a trip of the traveller's own (owner, 2026-11-11: "all elements should be deletable … and removed
 * accordingly from everywhere"): the trip row and its plan items, journal entries, photos (the trip's and its
 * entries') and stops are marked deleted, each stamped after its last change, so a backup restored later or another
 * phone keeps them deleted; rows deleted before keep their stamps. Pure; the Android app's TripRemoval and the
 * iPhone's trip store both use it.
 */
object TripMarks {
    /** The rows to write for deleting a trip: only the ones that change. */
    data class Marked(
        val trip: Trip,
        val items: List<PlanItem> = emptyList(),
        val entries: List<Entry> = emptyList(),
        val media: List<Media> = emptyList(),
        val stops: List<Stop> = emptyList(),
    )

    /**
     * [trip] and what belongs to it, marked deleted. [media] may hold more than the trip's (the photos of its entries are
     * looked up by entry too); a photo counts when it is in the trip or in one of its entries. [stamp] gives a row's
     * new stamp from its previous one.
     */
    fun mark(trip: Trip, items: List<PlanItem>, entries: List<Entry>, media: List<Media>, stops: List<Stop>, stamp: (Long) -> Long): Marked {
        val entryIds = entries.filter { it.tripId == trip.id }.mapTo(HashSet()) { it.id }
        val t = stamp(trip.updatedAt)
        return Marked(
            trip = trip.copy(deletedAt = trip.deletedAt ?: t, updatedAt = t),
            items = items.filter { it.tripId == trip.id && it.deletedAt == null }
                .map { val s = stamp(it.updatedAt); it.copy(deletedAt = s, updatedAt = s) },
            entries = entries.filter { it.tripId == trip.id && it.deletedAt == null }
                .map { val s = stamp(it.updatedAt); it.copy(deletedAt = s, updatedAt = s) },
            media = media.filter { (it.tripId == trip.id || it.entryId in entryIds) && it.deletedAt == null }.distinctBy { it.id }
                .map { val s = stamp(it.updatedAt); it.copy(deletedAt = s, updatedAt = s) },
            stops = stops.filter { it.tripId == trip.id && it.deletedAt == null }
                .map { val s = stamp(it.updatedAt); it.copy(deletedAt = s, updatedAt = s) },
        )
    }
}
