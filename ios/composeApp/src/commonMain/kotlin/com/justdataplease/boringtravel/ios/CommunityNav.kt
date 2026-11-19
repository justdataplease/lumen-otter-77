package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.snapshotFlow
import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.community.Community
import com.justdataplease.boringtravel.community.PostSource
import com.justdataplease.boringtravel.community.PostSync
import com.justdataplease.boringtravel.compat.File
import com.justdataplease.boringtravel.data.SavedClip
import com.justdataplease.boringtravel.data.SavedClipIndex
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.google.GoogleAccount
import com.justdataplease.boringtravel.stats.TripStats
import com.justdataplease.boringtravel.time.today
import com.justdataplease.boringtravel.ui.nav.TripTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The community's screens are the Android app's own (ui/community, read in at build time since round 24); what they reach
 * of Android's AppGraph and AppActions is here, under the same names: the community, the settings it reads (the
 * traveller, the account's name and email, Drive, whether the community's rules were agreed to), the trips (the
 * traveller's own for the trip picker, a copied trip saved, a trip as Post to community sees it), the trip posts kept in
 * step with their trips (round 25), and where each of its buttons goes on this app's stack.
 */

/**
 * Android's AppGraph as the community's screens read it (the read-in files import this as AppGraph). [files] are the
 * app's files (the saved clips a post takes); [scope] is the app's own (an unfinished post's Drive folder is deleted
 * there after its screen went, as Android's graph.scope).
 */
class CommunityGraph(
    val community: Community,
    val settings: CommunitySettings,
    val trips: CommunityTrips,
    val google: GoogleAccount?,
    val files: MediaFiles = MemoryMediaFiles(),
    val scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default),
) {
    /** Trip posts kept in step with their trips by hand (Android's graph.postSync): the trip's ⋮ › Community post. */
    val postSync: PostSync by lazy { PostSync(this) }
}

/** The settings the community's screens read and write, as Android's Settings has them (flows and setters). */
class CommunitySettings(private val app: AppSettings) {
    /** Me › Your traveller: a new profile's picture starts as it. */
    val traveller: Flow<Traveller> = snapshotFlow { app.traveller }

    /** Whether Me › Your traveller was ever picked (a new profile's picture is the traveller then). */
    val travellerChosen: Flow<Boolean> = snapshotFlow { app.travellerChosen }

    /** The account's name and email: a new profile's name and the username suggested from them. */
    val accountName: Flow<String?> = snapshotFlow { app.accountName }
    val accountEmail: Flow<String?> = snapshotFlow { app.accountEmail }

    /** Google Drive connected (Me › Backup & sync): a post's photos and clip go there. */
    val driveConnected: Flow<Boolean> = snapshotFlow { app.driveConnected }

    /** The community's rules, agreed to once before the first profile or post (this phone's answer, never in backups). */
    val communityRulesAccepted: Flow<Boolean> = snapshotFlow { app.communityRulesAccepted }

    fun setCommunityRulesAccepted(on: Boolean) {
        app.communityRulesAccepted = on
    }
}

/**
 * The trips as the community's screens ask for them: the traveller's own (the trip picker; demo trips left out), a
 * copied trip saved with its plan in one go, which ends demo mode as any new trip of the traveller's own does
 * (Android's TripRepository.createWithPlan), and a trip as Post to community sees it ([postSource]). [world] gives the
 * world on show (the trips' countries, in travel order), [clips] the trip videos kept on this phone, [demoMode] whether
 * the examples are on show.
 */
class CommunityTrips(
    private val book: TripBook,
    private val onNewOwnTrip: () -> Unit,
    private val changed: () -> Unit,
    private val world: WorldSource = WorldSource(),
    private val clips: () -> List<SavedClip> = { emptyList() },
    private val demoMode: () -> Boolean = { false },
) {
    fun ownTrips(): List<Trip> = book.snapshot.trips.filter { it.deletedAt == null && !it.isDemo }

    fun createWithPlan(trip: Trip, items: List<PlanItem>): Trip {
        book.putRows(trips = listOf(trip), items = items)
        if (!trip.isDemo) onNewOwnTrip()
        changed()
        return trip
    }

    /**
     * Trip [tripId] as Post to community sees it (Android's postSource): its plan, notes, photos, saved clips (newest
     * first), countries in travel order (waiting up to 4 s for the countries' outlines, as Android waits for its world)
     * and the recorded route's length; null when it is gone.
     */
    suspend fun postSource(tripId: String): PostSource? {
        val rows = book.snapshot
        val trip = rows.trips.firstOrNull { it.id == tripId && it.deletedAt == null } ?: return null
        val items = book.items(tripId)
        val entries = rows.entries.filter { it.tripId == tripId && it.deletedAt == null }
        val photos = rows.media.filter { it.tripId == tripId && it.deletedAt == null }
        val saved = SavedClipIndex.forTrip(clips(), tripId)
        val meters = withContext(Dispatchers.Default) {
            runCatching { TripStats.meters(rows.trackPoints.filter { it.tripId == tripId }) }.getOrDefault(0.0)
        }
        val countries = withTimeoutOrNull(4_000) {
            CountryOutlines.load()
            withContext(Dispatchers.Default) { world.world(book, demoMode(), today()).tripCountries[tripId].orEmpty() }
        }.orEmpty()
        return PostSource(trip, items, entries, photos, saved, countries, meters)
    }
}

/**
 * The trip videos kept on this phone as Post to community reads them (Android's SavedClips for the composer): a clip's
 * video and its picture as files under the app's [files] (compat's File on them: what is there, its bytes).
 */
class PostClips(private val files: MediaFiles) {
    fun video(clip: SavedClip): File = File(SavedClipIndex.video(clip.id), AppFileStore(files))

    /** The clip's saved picture (from 40 % in), or null when it has none. */
    fun thumbnail(clip: SavedClip): File? =
        SavedClipIndex.thumb(clip.id).takeIf { (files.size(it) ?: 0L) > 0L }?.let { File(it, AppFileStore(files)) }
}

/**
 * Post to community (Android's CommunityCompose route): from trip [tripId], its whole trip ("trip"), a saved clip
 * ([clipId], "clip") or photos ([mediaIds], comma-separated, "album").
 */
data class CommunityCompose(val tripId: String, val kind: String = "trip", val clipId: String? = null, val mediaIds: String? = null)

/**
 * Android's AppActions as the community's screens call them, on this app's stack: a post, a profile, someone's follow
 * lists and Community profile open over what shows (never twice on top: Android's launchSingleTop); a trip opens on the
 * tab asked for; Post to community opens the composer, and once posted the new post takes the composer's place.
 */
class CommunityActions(
    private val goBack: () -> Unit,
    private val open: (Place) -> Unit,
    private val top: () -> Place?,
    private val openTripOn: (String, TripTab?) -> Unit,
    @Suppress("unused") private val say: (String) -> Unit,
) {
    fun back() = goBack()

    private fun single(place: Place) {
        if (top() != place) open(place)
    }

    fun communityPost(postId: String) = single(Place.CommunityPost(postId))

    fun communityProfile(uid: String) = single(Place.CommunityProfile(uid))

    fun communityFollows(uid: String, followers: Boolean) = single(Place.CommunityFollows(uid, followers))

    fun editCommunityProfile() = single(Place.CommunityProfileEdit)

    /** Post to the community from trip [tripId]: the whole trip, a saved clip ([clipId]) or photos ([mediaIds]) as an album. */
    fun postToCommunity(tripId: String, kind: String = "trip", clipId: String? = null, mediaIds: List<String>? = null) =
        open(Place.Compose(CommunityCompose(tripId, kind, clipId, mediaIds?.takeIf { it.isNotEmpty() }?.joinToString(","))))

    /** Just posted: the new post opens in place of the composer, so Back goes where posting started (Android's posted). */
    fun posted(postId: String) {
        if (top() is Place.Compose) goBack()
        open(Place.CommunityPost(postId))
    }

    /** The composer's Make a clip: the trip video's studio for trip [tripId]. */
    fun clip(tripId: String) = open(Place.Clip(tripId))

    fun newTrip() = open(Place.NewTrip)

    /** Trip [tripId] on [tab] ("plan", "journal", "map", "media"; null: Plan). */
    fun openTrip(tripId: String, tab: String? = null) = openTripOn(tripId, TripTab.of(tab))

    /** A profile was made from the Community tab or a post: back to where that started. */
    fun profileSaved() = goBack()
}
