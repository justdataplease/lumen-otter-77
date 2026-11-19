package com.justdataplease.boringtravel.together

import kotlinx.serialization.Serializable

/** One shared trip as this phone keeps track of it (not shared: the member files hold what is shared). */
@Serializable
data class SharedTrip(
    val tripId: String,
    /** This phone's member id in the trip (stamps the owner's entries and photos in the files). */
    val me: String,
    val myName: String = "",
    /** This phone's folder and file in the owner's Drive, once made (Drive connected). */
    val folderId: String? = null,
    val fileId: String? = null,
    /** The other members (never [me]). */
    val members: List<Member> = emptyList(),
    /** Per member, a hash of the last file merged, so an unchanged file is not merged again. */
    val lastRead: Map<String, String> = emptyMap(),
    /** Per member, Drive's checksum of the file last read, so an unchanged file is not even downloaded again. */
    val seen: Map<String, String> = emptyMap(),
    /** A hash of what this phone last wrote (without its time), so an unchanged trip is not written again. */
    val lastWritten: String? = null,
    /** A fingerprint of the rows and members behind [lastWritten], so an unchanged trip is not even encoded again. */
    val lastRows: String? = null,
    /**
     * Another member's file lists this phone's file ([fileId]), so the others read this phone's changes. Until then the
     * trip's mailbox tells them where it is ([TripMail]), or the owner sends their link.
     */
    val listed: Boolean = false,
    /** Members whose file could not be read last time (link removed, file deleted). */
    val unreachable: List<String> = emptyList(),
    val joinedAt: Long = 0,
    /** When this phone last finished a sync of the trip (epoch ms). */
    val syncedAt: Long = 0,
    /** What this phone last posted to the trip's mailbox ([TripMail.posted]: the Firebase user and [fileId]); null: nothing. */
    val mailed: String? = null,
    /** When [mailed] was posted (epoch ms). */
    val mailedAt: Long = 0,
) {
    /**
     * Everyone as written into this phone's file: the owner first. Without times, so the file changes only when the
     * trip or the members do (each reader stamps a member's time from their file's own time).
     */
    fun everyone(): List<Member> = listOf(Member(me, myName, fileId)) + members.map { it.copy(seenAt = 0) }

    /** The others can't read this phone's changes yet: no file, or nobody who is in lists it. */
    val needsLinkBack: Boolean get() = fileId == null || (!listed && members.any { !it.left })
}

@Serializable
data class TogetherState(
    val trips: List<SharedTrip> = emptyList(),
    /** Trips left on this phone: trip id → the member id it had there, so joining again carries on as that member. */
    val former: Map<String, String> = emptyMap(),
    /**
     * Trips left while Drive couldn't be told (offline, or not connected): their copy must not stay readable by link,
     * so the next sync finishes the leaving ([TogetherEngine.finishLeaving]).
     */
    val unfinished: List<SharedTrip> = emptyList(),
) {
    fun of(tripId: String): SharedTrip? = trips.firstOrNull { it.tripId == tripId }
    fun with(trip: SharedTrip): TogetherState =
        if (trips.any { it.tripId == trip.tripId }) copy(trips = trips.map { if (it.tripId == trip.tripId) trip else it }) else copy(trips = trips + trip)

    /** Stops tracking [tripId] here, remembering who this phone was in it. */
    fun leaving(tripId: String): TogetherState {
        val s = of(tripId) ?: return this
        return copy(trips = trips.filter { it.tripId != tripId }, former = former + (tripId to s.me))
    }

    val isEmpty: Boolean get() = trips.isEmpty() && former.isEmpty() && unfinished.isEmpty()
}

/** Where a shared trip's sync stands, for the trip screen. */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Working : SyncStatus
    data class Done(val at: Long, val changed: Boolean) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}
