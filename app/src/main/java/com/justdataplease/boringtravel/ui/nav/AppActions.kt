package com.justdataplease.boringtravel.ui.nav

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.toRoute
import com.justdataplease.boringtravel.billing.ProBilling

/**
 * Everything a screen can ask navigation to do, so screens never hold the NavController. [camera] opens the system
 * camera for a new photo entry (set up by AppNav, which owns the camera's result).
 */
class AppActions(
    private val nav: NavController,
    private val camera: (tripId: String?) -> Unit = {},
    private val joinLink: (com.justdataplease.boringtravel.together.ShareLink) -> Unit = {},
) {
    /** A shared trip's link typed or pasted in Join a trip: opens the same sheet as a link opened from a chat. */
    fun join(link: com.justdataplease.boringtravel.together.ShareLink) = joinLink(link)

    fun back() { nav.popBackStack() }

    /** One of the screens outside a trip (Trips, Calendar, Map, Community, Me), as the bar switches them. */
    fun tab(route: Any) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    fun finishOnboarding() = nav.navigate(TripsHome) { popUpTo(Welcome) { inclusive = true } }

    /**
     * Trip [tripId] in trip mode, on [tab] (plan | journal | map | media); null = Plan, or the tab on show when the same
     * trip is already open. The same trip already open is brought back rather than opened twice; another open trip is
     * closed first, so there is never more than one trip on the back stack (see [TripNav.open]).
     */
    fun openTrip(tripId: String, tab: String? = null) = goToTrip(tripId, tab, day = null, setDay = false)

    /** The trip's plan on [day] (null: its ideas), e.g. after a shared link was added there or from the Calendar. */
    fun openPlanDay(tripId: String, day: java.time.LocalDate?) =
        goToTrip(tripId, TripTab.Plan.key, day?.toEpochDay()?.toString() ?: "ideas", setDay = true)

    private fun goToTrip(tripId: String, tab: String?, day: String?, setDay: Boolean) {
        val open = openTripEntry()
        when (TripNav.open(tripId, open?.toRoute<TripDetail>()?.tripId)) {
            TripNav.Open.Reuse -> {
                val entry = requireNotNull(open)
                nav.popBackStack(entry.destination.id, inclusive = false)
                if (tab != null) entry.savedStateHandle[TripState.TAB] = TripTab.of(tab)?.key ?: TripTab.Plan.key
                if (setDay) entry.savedStateHandle[TripState.DAY] = day
            }
            TripNav.Open.Replace -> nav.navigate(TripDetail(tripId, tab, day)) { popUpTo<TripDetail> { inclusive = true } }
            TripNav.Open.Push -> nav.navigate(TripDetail(tripId, tab, day))
        }
    }

    /** The trip on the back stack, if one is open. */
    private fun openTripEntry(): NavBackStackEntry? = runCatching { nav.getBackStackEntry<TripDetail>() }.getOrNull()

    /**
     * A tab of the open trip, from the trip's bar. Tabs switch in place (the trip stays one back-stack entry, so back
     * leaves the trip); from a place page over the trip, the page closes first.
     */
    fun tripTab(tab: TripTab, where: Where) {
        val entry = openTripEntry() ?: return
        when (TripNav.tripTap(where, tab)) {
            TripNav.TripTap.Stay -> Unit
            TripNav.TripTap.SetTab -> entry.savedStateHandle[TripState.TAB] = tab.key
            TripNav.TripTap.BackToTrip -> {
                nav.popBackStack(entry.destination.id, inclusive = false)
                entry.savedStateHandle[TripState.TAB] = tab.key
            }
        }
    }

    /** A tap on the bar outside a trip: switch, or back to the tab from a page under it. */
    fun homeTab(tapped: HomeTab, where: Where) {
        val route: Any = when (tapped) {
            HomeTab.Trips -> TripsHome
            HomeTab.Calendar -> CalendarHome
            HomeTab.Map -> MapHome
            HomeTab.Community -> CommunityHome
            HomeTab.Me -> MeHome
        }
        when (TripNav.homeTap(where, tapped)) {
            TripNav.HomeTap.Stay -> Unit
            TripNav.HomeTap.BackToTab -> if (!nav.popBackStack(route, inclusive = false)) tab(route)
            TripNav.HomeTap.Switch -> tab(route)
        }
    }

    fun newTrip() = nav.navigate(EditTrip(null))

    /** New trip starting on [start] (Calendar: "New trip from here"). */
    fun newTripFrom(start: java.time.LocalDate) = nav.navigate(EditTrip(null, start.toEpochDay().toString()))
    fun editTrip(tripId: String) = nav.navigate(EditTrip(tripId))
    fun write(tripId: String? = null, entryId: String? = null, dictate: Boolean = false, attachPhotos: Boolean = false) = nav.navigate(Write(entryId, tripId, dictate, attachPhotos))

    /** A new note about plan item [itemId] of trip [tripId]: at the item's place, "Alhambra (Granada)". */
    fun writeAbout(tripId: String, itemId: String) = nav.navigate(Write(tripId = tripId, planItemId = itemId))

    /**
     * Write today in trip [tripId] from outside it (the Now card): the editor opens over the trip's Journal, so closing
     * it shows the new entry where it now lives; back again leaves the trip.
     */
    fun writeInTrip(tripId: String, dictate: Boolean = false) {
        openTrip(tripId, TripTab.Journal.key)
        write(tripId = tripId, dictate = dictate)
    }

    /** The camera now; the photo becomes a new entry in [tripId] (or today's trip), opened in Write. */
    fun takePhoto(tripId: String? = null) = camera(tripId)
    fun openEntry(entryId: String) = nav.navigate(EntryView(entryId))

    /** Entries written when no trip was on. */
    fun looseNotes() = nav.navigate(LooseNotes)
    fun clip(tripId: String) = nav.navigate(Clip(tripId))
    fun export(tripId: String) = nav.navigate(Export(tripId))
    fun book(tripId: String) = nav.navigate(BookEditor(tripId))
    fun trackingSettings() = nav.navigate(TrackingSettings)
    fun backupSettings() = nav.navigate(BackupSettings)
    fun aiSettings() = nav.navigate(AiSettings)
    /** The Pro offer; does nothing while Pro isn't for sale (the free launch, [ProBilling.PRO_FOR_SALE]). */
    fun paywall() { if (ProBilling.PRO_FOR_SALE) nav.navigate(Paywall) }
    fun planner(tripId: String? = null, chat: Boolean = false, start: String? = null) = nav.navigate(Planner(tripId, chat, start))

    /** Copy from a link, with [address] filled in when given; [tripId]: the trip it is opened from, offered to add to. */
    fun copySite(address: String? = null, tripId: String? = null) = nav.navigate(CopySite(address, tripId)) { launchSingleTop = true }

    /** Trip ideas: the list of destinations, or straight to [destinationId]'s questions and preview. */
    fun tripIdeas(destinationId: String? = null) = nav.navigate(TripIdeas(destinationId))

    /**
     * A trip idea became trip [tripId]: it opens on its Plan tab in place of Trip ideas (and of the New trip form Trip
     * ideas was opened from), so Back goes to where that started.
     */
    fun ideaUsed(tripId: String) {
        val below = nav.previousBackStackEntry
        val fromNewTrip = below?.destination?.hasRoute(EditTrip::class) == true && below.toRoute<EditTrip>().tripId == null
        nav.navigate(TripDetail(tripId, TripTab.Plan.key)) {
            if (fromNewTrip) popUpTo<EditTrip> { inclusive = true } else popUpTo<TripIdeas> { inclusive = true }
        }
    }

    /** The place page around a plan item or a saved stop of [tripId]. */
    fun place(tripId: String, itemId: String? = null, stopId: String? = null) = nav.navigate(Place(tripId, itemId, stopId))
    fun yourWorld() = nav.navigate(YourWorld)
    fun shareWorld() = nav.navigate(ShareWorld)
    fun placesBeen() = nav.navigate(PlacesBeen)

    /** The Map tab (the globe or Routes; what it shows next is set on graph.world.mapRequest first). */
    fun mapTab() = tab(MapHome)

    fun communityPost(postId: String) = nav.navigate(CommunityPost(postId)) { launchSingleTop = true }
    fun communityProfile(uid: String) = nav.navigate(CommunityProfile(uid)) { launchSingleTop = true }
    fun communityFollows(uid: String, followers: Boolean) = nav.navigate(CommunityFollows(uid, followers)) { launchSingleTop = true }
    fun editCommunityProfile() = nav.navigate(CommunityProfileEdit) { launchSingleTop = true }

    /** Post to the community from trip [tripId]: the whole trip, a saved clip ([clipId]) or photos ([mediaIds]) as an album. */
    fun postToCommunity(tripId: String, kind: String = "trip", clipId: String? = null, mediaIds: List<String>? = null) =
        nav.navigate(CommunityCompose(tripId, kind, clipId, mediaIds?.takeIf { it.isNotEmpty() }?.joinToString(",")))

    /** Just posted: the new post opens in place of the composer, so Back goes where posting started. */
    fun posted(postId: String) = nav.navigate(CommunityPost(postId)) { popUpTo<CommunityCompose> { inclusive = true } }

    /** A profile was made from the Community tab or a post: back to where that started. */
    fun profileSaved() { nav.popBackStack() }

    /** After the planner added items: back to the trip it came from (on its Plan), or the (new) trip in its place. */
    fun plannerDone(tripId: String) {
        val previous = nav.previousBackStackEntry
        if (previous?.destination?.hasRoute(TripDetail::class) == true && previous.toRoute<TripDetail>().tripId == tripId) {
            nav.popBackStack()
            previous.savedStateHandle[TripState.TAB] = TripTab.Plan.key
        } else {
            // A trip made with AI from the New trip form replaces that form too: Back leaves the new trip.
            val fromNewTrip = previous?.destination?.hasRoute(EditTrip::class) == true && previous.toRoute<EditTrip>().tripId == null
            nav.navigate(TripDetail(tripId, TripTab.Plan.key)) {
                if (fromNewTrip) popUpTo<EditTrip> { inclusive = true } else popUpTo<Planner> { inclusive = true }
            }
        }
    }
}

/** Keys of a trip's live state in its back-stack entry: the tab on show and the plan's day. */
internal object TripState {
    const val TAB = "trip.tab"
    const val DAY = "trip.day"
}
