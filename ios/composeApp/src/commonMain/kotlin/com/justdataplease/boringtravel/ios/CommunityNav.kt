package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.snapshotFlow
import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.community.Community
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.google.GoogleAccount
import com.justdataplease.boringtravel.ui.nav.TripTab
import kotlinx.coroutines.flow.Flow

/*
 * The community's screens are the Android app's own (ui/community, read in at build time since round 24); what they reach
 * of Android's AppGraph and AppActions is here, under the same names: the community, the settings it reads (the
 * traveller, the account's name and email, whether the community's rules were agreed to), the trips (the traveller's own
 * for the trip picker, a copied trip saved), and where each of its buttons goes on this app's stack.
 */

/** Android's AppGraph as the community's screens read it (the read-in files import this as AppGraph). */
class CommunityGraph(
    val community: Community,
    val settings: CommunitySettings,
    val trips: CommunityTrips,
    val google: GoogleAccount?,
)

/** The settings the community's screens read and write, as Android's Settings has them (flows and setters). */
class CommunitySettings(private val app: AppSettings) {
    /** Me › Your traveller: a new profile's picture starts as it. */
    val traveller: Flow<Traveller> = snapshotFlow { app.traveller }

    /** Whether Me › Your traveller was ever picked (a new profile's picture is the traveller then). */
    val travellerChosen: Flow<Boolean> = snapshotFlow { app.travellerChosen }

    /** The account's name and email: a new profile's name and the username suggested from them. */
    val accountName: Flow<String?> = snapshotFlow { app.accountName }
    val accountEmail: Flow<String?> = snapshotFlow { app.accountEmail }

    /** The community's rules, agreed to once before the first profile or post (this phone's answer, never in backups). */
    val communityRulesAccepted: Flow<Boolean> = snapshotFlow { app.communityRulesAccepted }

    fun setCommunityRulesAccepted(on: Boolean) {
        app.communityRulesAccepted = on
    }
}

/**
 * The trips as the community's screens ask for them: the traveller's own (the trip picker; demo trips left out) and a
 * copied trip saved with its plan in one go, which ends demo mode as any new trip of the traveller's own does
 * (Android's TripRepository.createWithPlan).
 */
class CommunityTrips(private val book: TripBook, private val onNewOwnTrip: () -> Unit, private val changed: () -> Unit) {
    fun ownTrips(): List<Trip> = book.snapshot.trips.filter { it.deletedAt == null && !it.isDemo }

    fun createWithPlan(trip: Trip, items: List<PlanItem>): Trip {
        book.putRows(trips = listOf(trip), items = items)
        if (!trip.isDemo) onNewOwnTrip()
        changed()
        return trip
    }
}

/**
 * Android's AppActions as the community's screens call them, on this app's stack: a post, a profile, someone's follow
 * lists and Community profile open over what shows (never twice on top: Android's launchSingleTop); a trip opens on the
 * tab asked for; posting is round 25's (the composer says it isn't on iPhone yet).
 */
class CommunityActions(
    private val goBack: () -> Unit,
    private val open: (Place) -> Unit,
    private val top: () -> Place?,
    private val openTripOn: (String, TripTab?) -> Unit,
    private val say: (String) -> Unit,
) {
    fun back() = goBack()

    private fun single(place: Place) {
        if (top() != place) open(place)
    }

    fun communityPost(postId: String) = single(Place.CommunityPost(postId))

    fun communityProfile(uid: String) = single(Place.CommunityProfile(uid))

    fun communityFollows(uid: String, followers: Boolean) = single(Place.CommunityFollows(uid, followers))

    fun editCommunityProfile() = single(Place.CommunityProfileEdit)

    /** Post to the community from trip [tripId]: the composer (round 25). */
    @Suppress("UNUSED_PARAMETER")
    fun postToCommunity(tripId: String, kind: String = "trip", clipId: String? = null, mediaIds: List<String>? = null) = say(NOT_ON_IPHONE)

    fun newTrip() = open(Place.NewTrip)

    /** Trip [tripId] on [tab] ("plan", "journal", "map", "media"; null: Plan). */
    fun openTrip(tripId: String, tab: String? = null) = openTripOn(tripId, TripTab.of(tab))

    /** A profile was made from the Community tab or a post: back to where that started. */
    fun profileSaved() = goBack()
}
