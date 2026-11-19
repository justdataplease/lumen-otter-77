package com.justdataplease.boringtravel.together

import java.util.concurrent.ConcurrentHashMap

/**
 * Which changes to a shared trip need a new send queued. One queued send takes every change made until it starts; a
 * change after that queues the next, because the running send may have read the trip already. So every change is
 * followed by a send that starts after it, and a burst of changes queues one send, not one each.
 */
class SendQueue(private val queue: (tripId: String) -> Unit) {
    private val waiting: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun changed(tripId: String) {
        if (waiting.add(tripId)) queue(tripId)
    }

    /** The queued send of [tripId] starts: called before it reads the trip. */
    fun started(tripId: String) {
        waiting.remove(tripId)
    }
}
