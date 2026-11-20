package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.Stamps
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.google.GoogleAccount
import com.justdataplease.boringtravel.together.Together
import com.justdataplease.boringtravel.together.TogetherEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/*
 * Travel together's sheets are the Android app's own (ui/together, read in at build time since round 26); what they reach
 * of Android's AppGraph is here, under the same names: the shared trips ([Together]), the settings they read (Drive,
 * demo mode, the account), the trips (one trip as it changes, one now), leaving and deleting (Android's Deletions), demo
 * mode, the app's scope and its Google account (ui/together/TogetherGoogle.kt).
 */

/** Android's AppGraph as Travel together's sheets read it (the read-in files import this as AppGraph). */
class TogetherGraph(
    val together: Together,
    val settings: TogetherSettings,
    val trips: TogetherTrips,
    val deletions: TripDeletions,
    val demo: TogetherDemo,
    /** The app frame's own coroutines (the main thread): an action goes on when its sheet closes, as on Android's graph.scope. */
    val scope: CoroutineScope,
    val google: GoogleAccount?,
)

/** The settings the sheets read, as Android's Settings has them (flows). */
class TogetherSettings(private val app: AppSettings) {
    val driveConnected: Flow<Boolean> = snapshotFlow { app.driveConnected }
    val demoMode: Flow<Boolean> = snapshotFlow { app.demoMode }
    val accountEmail: Flow<String?> = snapshotFlow { app.accountEmail }
}

/** The trips as the sheets ask for them: one as it changes ([revision]: the screens' count of changes), one now. */
class TogetherTrips(private val book: TripBook, private val revision: () -> Int) {
    fun observeTrip(tripId: String): Flow<Trip?> = snapshotFlow {
        revision()
        book.snapshot.trips.firstOrNull { it.id == tripId && it.deletedAt == null }
    }

    suspend fun get(tripId: String): Trip? = book.snapshot.trips.firstOrNull { it.id == tripId && it.deletedAt == null }
}

/** Demo mode as the Join sheet turns it off (Android's DemoMode.set): the app frame's own. */
class TogetherDemo(private val setDemo: suspend (Boolean) -> Unit) {
    suspend fun set(on: Boolean) = setDemo(on)
}

/**
 * Deleting and leaving a trip with what hangs off it (the Android app's data/Deletions.kt, as far as trips go): a trip
 * of the traveller's own goes with its plan, journal, photos, book, clips and route ([removeTripAndFiles]); a shared trip
 * others are in is left instead (the others keep it; here only the trip's row is marked deleted, so joining again brings
 * it back with everything in it). Either way its events leave the phone's calendar ([calendarGone], quietly).
 * [tripGone] tells the app what went (the route, the screens), [changed] that the trips changed.
 */
class TripDeletions(
    private val book: TripBook,
    private val files: MediaFiles,
    private val together: Together?,
    private val calendarGone: suspend (String) -> Unit,
    private val tripGone: (String) -> Unit,
    private val changed: () -> Unit,
) {
    /** Trip ⋮ › Delete trip and Edit trip › Delete trip: a shared trip with others in it is left instead. */
    suspend fun deleteTrip(tripId: String) {
        val shared = together?.engine?.load()?.of(tripId)
        if (shared != null && shared.members.isNotEmpty()) return leaveTrip(tripId)
        // Invited to, but nobody joined: sharing stops, and the trip is deleted like any other.
        if (shared != null) together?.leave(tripId)
        removeTripAndFiles(book, files, tripId)
        tripGone(tripId)
        changed()
    }

    /**
     * Leaves shared trip [tripId] (Travel together › Leave, or deleting it): the others keep it; here only the trip's row
     * is marked deleted, so joining again brings it back with everything in it. Its calendar events go, quietly.
     */
    suspend fun leaveTrip(tripId: String) {
        together?.leave(tripId)
        book.snapshot.trips.firstOrNull { it.id == tripId && it.deletedAt == null }?.let { trip ->
            val t = Stamps.next(trip.updatedAt)
            book.putRows(trips = listOf(trip.copy(deletedAt = t, updatedAt = t)))
        }
        changed()
        calendarGone(tripId)
    }

    /** Whether deleting [tripId] leaves it instead (shared, with others in it): the dialog says Leave then. */
    fun leaves(tripId: String): Boolean = together?.state?.value?.of(tripId)?.members?.isNotEmpty() == true
}

/** The shared trips for every screen; null where a screen is drawn on its own. */
val LocalTogether = staticCompositionLocalOf<Together?> { null }

/** The Travel together sheets' AppGraph; null where a screen is drawn on its own. */
val LocalTogetherGraph = staticCompositionLocalOf<TogetherGraph?> { null }

/** What Together's engine answers a join link with, for the screens that only need to know. */
typealias JoinOutcome = TogetherEngine.Opened
