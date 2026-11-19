package com.justdataplease.boringtravel.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import com.justdataplease.boringtravel.ui.studio.StudioColors
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.justdataplease.boringtravel.ai.SharedItinerary
import com.justdataplease.boringtravel.links.ShareRoute
import com.justdataplease.boringtravel.ui.share.ShareToPlanSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.widget.Deeplink
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.ui.clip.ClipScreen
import com.justdataplease.boringtravel.ui.editor.WriteScreen
import com.justdataplease.boringtravel.ui.editor.rememberQuickCamera
import com.justdataplease.boringtravel.ui.export.ExportScreen
import com.justdataplease.boringtravel.ui.book.BookScreen
import com.justdataplease.boringtravel.ui.journal.EntryScreen
import com.justdataplease.boringtravel.ui.journal.LooseNotesScreen
import com.justdataplease.boringtravel.ui.map.MapScreen
import com.justdataplease.boringtravel.ui.map.TrackingSettingsScreen
import com.justdataplease.boringtravel.ui.me.BackupScreen
import com.justdataplease.boringtravel.ui.me.MeScreen
import com.justdataplease.boringtravel.ui.onboarding.WelcomeScreen
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.components.btBorder
import com.justdataplease.boringtravel.ui.components.btShadow
import com.justdataplease.boringtravel.ui.components.highlighter
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.theme.BtFonts
import com.justdataplease.boringtravel.ui.theme.NavBarSpec
import com.justdataplease.boringtravel.ui.theme.NavBars
import com.justdataplease.boringtravel.ui.theme.NavFace
import com.justdataplease.boringtravel.ui.theme.NavMarker
import com.justdataplease.boringtravel.ui.trip.TripScreen
import com.justdataplease.boringtravel.ui.tripcal.TripsCalendarScreen
import com.justdataplease.boringtravel.ui.trips.EditTripScreen
import com.justdataplease.boringtravel.ui.trips.TripsScreen

/** One item on the bottom bar. */
private data class BarItem(val label: String, val icon: ImageVector)

private val homeItems = HomeTab.entries.map { t ->
    BarItem(
        t.label,
        when (t) {
            HomeTab.Trips -> BtIcons.Trips
            HomeTab.Calendar -> BtIcons.Calendar
            HomeTab.Map -> BtIcons.Map
            HomeTab.Community -> BtIcons.Community
            HomeTab.Me -> BtIcons.Me
        },
    )
}

private val tripItems = TripTab.entries.map { t ->
    BarItem(
        t.label,
        when (t) {
            TripTab.Plan -> BtIcons.Checklist
            TripTab.Journal -> BtIcons.Journal
            TripTab.Map -> BtIcons.Map
            TripTab.Media -> BtIcons.Gallery
        },
    )
}

/**
 * The tab on show in the trip at [entry], kept in the entry's saved state (it survives Write, rotation and the
 * process being stopped): the route's tab, else Plan ([TripNav.landingTab]).
 */
private fun tripTabFlow(entry: NavBackStackEntry): StateFlow<String?> =
    entry.savedStateHandle.getStateFlow(TripState.TAB, TripNav.landingTab(entry.toRoute<TripDetail>().tab).key)

/** The bar's view of a back-stack entry that is not a place page. */
private fun whereOf(entry: NavBackStackEntry?): Where {
    val d = entry?.destination ?: return Where.Elsewhere
    return when {
        d.hasRoute<TripsHome>() -> Where.Home(HomeTab.Trips)
        d.hasRoute<CalendarHome>() -> Where.Home(HomeTab.Calendar)
        d.hasRoute<MapHome>() -> Where.Home(HomeTab.Map)
        d.hasRoute<MeHome>() -> Where.Home(HomeTab.Me)
        d.hasRoute<CommunityHome>() -> Where.Home(HomeTab.Community)
        d.hasRoute<CommunityPost>() || d.hasRoute<CommunityProfile>() || d.hasRoute<CommunityFollows>() -> Where.Under(HomeTab.Community)
        d.hasRoute<YourWorld>() -> Where.Under(HomeTab.Map)
        d.hasRoute<LooseNotes>() -> Where.Under(HomeTab.Trips)
        d.hasRoute<TripDetail>() ->
            Where.InTrip(entry.toRoute<TripDetail>().tripId, TripTab.of(tripTabFlow(entry).value) ?: TripTab.Plan)
        else -> Where.Elsewhere
    }
}

/**
 * The app's navigation. [pendingAction] is a widget button or launcher shortcut; [actionFromOutside] says it came
 * from outside the app (the home screen), so a camera closed without a photo goes back there ([onLeave]).
 * [pendingImport] is an itinerary shared for the planner, [pendingPlace] a link shared for Add to plan; both wait
 * until the welcome screen is done.
 */
@Composable
fun AppNav(
    graph: AppGraph,
    onboardingDone: Boolean,
    pendingAction: String? = null,
    actionFromOutside: Boolean = false,
    onActionHandled: () -> Unit = {},
    onLeave: () -> Unit = {},
    pendingImport: SharedItinerary? = null,
    onImportHandled: () -> Unit = {},
    pendingPlace: ShareRoute? = null,
    onPlaceHandled: () -> Unit = {},
    pendingJoin: com.justdataplease.boringtravel.together.ShareLink? = null,
    onJoinHandled: () -> Unit = {},
) {
    val nav = rememberNavController()
    val camera = rememberQuickCamera(
        graph,
        onEntry = { id -> nav.navigate(Write(entryId = id, fromCamera = true)) },
        onCancel = { leave -> if (leave) onLeave() },
    )
    var typedJoin by remember { mutableStateOf<com.justdataplease.boringtravel.together.ShareLink?>(null) }
    val actions = remember(nav, camera) { AppActions(nav, { tripId -> camera(tripId, false) }, joinLink = { typedJoin = it }) }
    // 1.11.0 kept the tab last used on each trip in this file; trips open on Plan now, so nothing stale stays behind.
    LaunchedEffect(graph) { withContext(Dispatchers.IO) { graph.app.deleteSharedPreferences(OLD_TAB_MEMORY) } }
    // Demo mode: a reminder each time the app starts in it (with the way out), and a word when a new trip of the
    // traveller's own ends it (the new trip opens as usual).
    val snackbar = remember { SnackbarHostState() }
    var demoNoticeShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(graph) { graph.demo.left.collect { snackbar.showSnackbar("Demo mode off") } }
    // Someone joined a shared trip by its link: they are added without a tap here, so say who.
    LaunchedEffect(graph) {
        graph.together.joined.collect { j ->
            val title = graph.trips.observeTrip(j.tripId).first()?.title.orEmpty()
            snackbar.showSnackbar(com.justdataplease.boringtravel.ui.together.TogetherText.joined(j.names, title))
        }
    }
    LaunchedEffect(graph, onboardingDone) {
        if (demoNoticeShown || !onboardingDone || !graph.settings.demoMode.first()) return@LaunchedEffect
        demoNoticeShown = true
        val result = snackbar.showSnackbar(DemoNotice.TEXT, actionLabel = DemoNotice.ACTION, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) graph.demo.set(false)
    }
    // Widget buttons and launcher shortcuts: the editor (for today's trip), the camera, dictation, a new trip or
    // today's trip.
    LaunchedEffect(pendingAction) {
        val action = pendingAction ?: return@LaunchedEffect
        // The setting now, not when the app opened: onboarding may have finished since.
        if (onboardingDone || graph.settings.onboardingDone.first()) {
            val trip = graph.trips.currentOn(LocalDate.now())
            when (action) {
                Deeplink.WRITE -> actions.write(tripId = trip?.id)
                Deeplink.CAMERA -> camera(trip?.id, actionFromOutside)
                Deeplink.PHOTO -> actions.write(tripId = trip?.id, attachPhotos = true)
                Deeplink.VOICE -> actions.write(tripId = trip?.id, dictate = true)
                Deeplink.NEW_TRIP -> actions.newTrip()
                Deeplink.TODAY -> if (trip != null) actions.openTrip(trip.id, "journal")
                else -> Deeplink.postOf(action)?.let(actions::communityPost)
            }
        }
        onActionHandled()
    }
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    LaunchedEffect(pendingImport, destination) {
        val share = pendingImport ?: return@LaunchedEffect
        // On a fresh install, keep the share until the traveller finishes the welcome screen.
        if (destination == null || destination.hasRoute<Welcome>()) return@LaunchedEffect
        nav.navigate(Planner())
        nav.currentBackStackEntry?.savedStateHandle?.apply {
            set("sharedItineraryText", share.text)
            set("sharedItineraryUri", share.uri)
            set("sharedItineraryPictures", ArrayList(share.pictures))
        }
        onImportHandled()
    }
    // A shared link opens Add to plan over the screen; a few words without a link get a short note. On a fresh
    // install both wait until the traveller finishes the welcome screen.
    val shareReady = destination != null && !destination.hasRoute<Welcome>()
    val noteScope = rememberCoroutineScope()
    LaunchedEffect(pendingPlace, shareReady) {
        if (pendingPlace != ShareRoute.NoLink || !shareReady) return@LaunchedEffect
        onPlaceHandled()
        noteScope.launch { snackbar.showSnackbar("Nothing to add: share a link") }
    }
    // The bar: Trips · Calendar · Map · Me outside a trip (also under Your world and Notes outside trips); the trip's
    // own Plan · Journal · Map · Media inside one, and on a place page opened from the trip.
    val below = nav.previousBackStackEntry
    // The trip on screen, or under the place page on screen; its tab is observed so the bar follows it.
    val tripEntry = when {
        destination?.hasRoute<TripDetail>() == true -> entry
        destination?.hasRoute<Place>() == true && below?.destination?.hasRoute<TripDetail>() == true -> below
        else -> null
    }
    val tripTabKey by remember(tripEntry) { tripEntry?.let { tripTabFlow(it) } ?: MutableStateFlow(null) }.collectAsState()
    val where: Where = when {
        entry != null && destination?.hasRoute<TripDetail>() == true ->
            Where.InTrip(entry!!.toRoute<TripDetail>().tripId, TripTab.of(tripTabKey) ?: TripTab.Plan)
        entry != null && destination?.hasRoute<Place>() == true -> {
            val under = tripEntry?.let { Where.InTrip(it.toRoute<TripDetail>().tripId, TripTab.of(tripTabKey) ?: TripTab.Plan) }
                ?: whereOf(below)
            Where.Place(entry!!.toRoute<Place>().tripId, under)
        }
        else -> whereOf(entry)
    }
    val bar = TripNav.bar(where)

    // The page is drawn once behind everything, so a bar without a background (Sketchbook) shows the same ruling,
    // aligned with the screens' own paper (same origin, same whole-pixel step).
    Box(Modifier.fillMaxSize().paper(Bt.tokens)) {
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                // The page colour on ink, the reverse of the screen in every theme (light and dark ones). The texts set
                // their colour: the theme's type styles carry the ink.
                val c = Bt.colors
                Snackbar(
                    modifier = Modifier.padding(12.dp),
                    action = data.visuals.actionLabel?.let { label ->
                        { TextButton(onClick = data::performAction) { Text(label, style = Bt.text.button, color = c.bg) } }
                    },
                    containerColor = c.ink,
                    contentColor = c.bg,
                ) { Text(data.visuals.message, style = Bt.text.body, color = c.bg) }
            }
        },
        bottomBar = {
            if (bar != BarMode.Hidden) {
                BtNavBar(bar) { i ->
                    when (bar) {
                        is BarMode.Trip -> actions.tripTab(TripTab.entries[i], where)
                        is BarMode.Outside -> actions.homeTab(HomeTab.entries[i], where)
                        BarMode.Hidden -> Unit
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = if (onboardingDone) TripsHome else Welcome,
            modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()),
            enterTransition = { fadeIn(tween(160)) },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(160)) },
            popExitTransition = { fadeOut(tween(120)) },
        ) {
            composable<Welcome> { WelcomeScreen(graph, actions) }
            composable<TripsHome> { TripsScreen(graph, actions) }
            composable<CalendarHome> { TripsCalendarScreen(actions) }
            composable<MapHome> { MapScreen(graph, actions, tripId = null) }
            composable<MeHome> { MeScreen(graph, actions) }
            composable<TripDetail> { backStack ->
                val route = backStack.toRoute<TripDetail>()
                val handle: SavedStateHandle = backStack.savedStateHandle
                val tab by remember(backStack) { tripTabFlow(backStack) }.collectAsState()
                val day by remember(backStack) { handle.getStateFlow(TripState.DAY, route.day) }.collectAsState()
                val shown = TripTab.of(tab) ?: TripTab.Plan
                TripScreen(graph, actions, route.tripId, shown, day, onPlanDay = { handle[TripState.DAY] = it })
            }
            composable<LooseNotes> { LooseNotesScreen(graph, actions) }
            composable<EditTrip> {
                val route = it.toRoute<EditTrip>()
                EditTripScreen(graph, actions, route.tripId, route.start?.toLongOrNull()?.let(LocalDate::ofEpochDay))
            }
            composable<Write> { WriteScreen(graph, actions, it.toRoute<Write>()) }
            composable<EntryView> { EntryScreen(graph, actions, it.toRoute<EntryView>().entryId) }
            composable<Clip> { ClipScreen(graph, actions, it.toRoute<Clip>().tripId) }
            composable<Export> { ExportScreen(graph, actions, it.toRoute<Export>().tripId) }
            composable<BookEditor> { BookScreen(graph, actions, it.toRoute<BookEditor>().tripId) }
            composable<TrackingSettings> { TrackingSettingsScreen(graph, actions) }
            composable<BackupSettings> { BackupScreen(graph, actions) }
            composable<AiSettings> { com.justdataplease.boringtravel.ui.me.AiScreen(graph, actions) }
            composable<Paywall> { com.justdataplease.boringtravel.ui.pro.ProScreen(graph, actions) }
            composable<Planner> { backStack ->
                val sharedText by backStack.savedStateHandle.getStateFlow<String?>("sharedItineraryText", null).collectAsState()
                val sharedUri by backStack.savedStateHandle.getStateFlow<String?>("sharedItineraryUri", null).collectAsState()
                val sharedPictures by backStack.savedStateHandle.getStateFlow<ArrayList<String>?>("sharedItineraryPictures", null).collectAsState()
                val planner = backStack.toRoute<Planner>()
                com.justdataplease.boringtravel.ui.ai.PlannerScreen(
                    graph, actions, planner.tripId, sharedText, sharedUri, sharedPictures.orEmpty(), onSharedHandled = {
                        backStack.savedStateHandle["sharedItineraryText"] = null
                        backStack.savedStateHandle["sharedItineraryUri"] = null
                        backStack.savedStateHandle["sharedItineraryPictures"] = null
                    }, startInChat = planner.chat, start = planner.start,
                )
            }
            composable<CopySite> {
                val route = it.toRoute<CopySite>()
                com.justdataplease.boringtravel.ui.site.CopySiteScreen(graph, actions, route.address, route.tripId)
            }
            composable<Place> { com.justdataplease.boringtravel.ui.place.PlaceScreen(graph, actions, it.toRoute<Place>()) }
            composable<TripIdeas> { com.justdataplease.boringtravel.ui.programs.ProgramsScreen(graph, actions, it.toRoute<TripIdeas>().destinationId) }
            composable<YourWorld> { com.justdataplease.boringtravel.ui.world.YourWorldScreen(graph, actions) }
            composable<ShareWorld> { com.justdataplease.boringtravel.ui.world.ShareWorldScreen(graph, actions) }
            composable<PlacesBeen> { com.justdataplease.boringtravel.ui.world.PlacesBeenScreen(graph, actions) }
            composable<CommunityHome> { com.justdataplease.boringtravel.ui.community.CommunityScreen(graph, actions) }
            composable<CommunityPost> { com.justdataplease.boringtravel.ui.community.PostScreen(graph, actions, it.toRoute<CommunityPost>().postId) }
            composable<CommunityProfile> { com.justdataplease.boringtravel.ui.community.ProfileScreen(graph, actions, it.toRoute<CommunityProfile>().uid) }
            composable<CommunityFollows> { it.toRoute<CommunityFollows>().let { r -> com.justdataplease.boringtravel.ui.community.FollowListScreen(graph, actions, r.uid, r.followers) } }
            composable<CommunityProfileEdit> { com.justdataplease.boringtravel.ui.community.ProfileEditScreen(graph, actions) }
            composable<CommunityCompose> { com.justdataplease.boringtravel.ui.community.ComposeScreen(graph, actions, it.toRoute<CommunityCompose>()) }
        }
    }
    // The dark editors (studio: the book, the trip video) carry on under the gesture bar: no strip of page colour below their tools.
    if (entry?.destination?.let { it.hasRoute<BookEditor>() || it.hasRoute<Clip>() } == true) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars).background(StudioColors.bg),
        )
    }
    // A shared trip's link: join it, or add the friend who sent theirs back (waits for the welcome screen too).
    val join = pendingJoin ?: typedJoin
    if (shareReady && join != null) {
        com.justdataplease.boringtravel.ui.together.JoinSheet(
            graph, join,
            onOpenTrip = { tripId ->
                onJoinHandled()
                typedJoin = null
                actions.openTrip(tripId)
            },
            onClose = {
                onJoinHandled()
                typedJoin = null
            },
        )
    }
    if (shareReady && pendingPlace is ShareRoute.Place) {
        ShareToPlanSheet(
            graph, pendingPlace.link,
            onAdded = { tripId, day ->
                onPlaceHandled()
                actions.openPlanDay(tripId, day)
            },
            onNewTrip = {
                onPlaceHandled()
                actions.newTrip()
            },
            onDismiss = onPlaceHandled,
            onCopy = {
                onPlaceHandled()
                actions.copySite(pendingPlace.link.url)
            },
            onList = { tripId ->
                onPlaceHandled()
                actions.copySite(pendingPlace.link.url, tripId)
            },
        )
    }
    }
}

/**
 * The bottom bar, drawn per theme from [NavBars]: Postcard Pop's coral pill on a plain white bar, Trail's luggage tag,
 * Sticker Book's highlighter, Sketchbook's pencil loop (no bar, the ruling runs through) and Market's dot sticker.
 */
@Composable
private fun BtNavBar(mode: BarMode, onSelect: (Int) -> Unit) {
    val t = Bt.tokens
    val spec = NavBars.of(t.look)
    val shape = RoundedCornerShape(spec.corner)
    val items: @Composable () -> Unit = {
        // Entering or leaving a trip swaps the items with a quick crossfade (the bar itself stays); a tab change
        // within a mode only moves the marker.
        AnimatedContent(
            targetState = mode,
            contentKey = { it is BarMode.Trip },
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
            label = "Bar items",
        ) { m ->
            val list = if (m is BarMode.Trip) tripItems else homeItems
            val selected = TripNav.selectedIndex(m)
            Row(Modifier.fillMaxWidth().height(spec.height)) {
                list.forEachIndexed { i, item ->
                    NavItem(item, i == selected, spec, Modifier.weight(1f).fillMaxHeight()) { onSelect(i) }
                }
            }
        }
    }
    if (spec.floating) {
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = spec.inset, end = spec.inset, bottom = spec.inset)
                .height(spec.height),
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .btShadow(spec.shadow, shape)
                    .clip(shape)
                    .then(if (spec.fill != null) Modifier.background(spec.fill) else Modifier)
                    .btBorder(spec.border, shape)
                    .drawBehind { barDecor(spec) },
            )
            items()
        }
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (spec.fill != null) Modifier.background(spec.fill) else Modifier)
                .drawBehind { spec.topLine?.let { drawRect(it, size = Size(size.width, 1.dp.toPx())) } }
                .navigationBarsPadding(),
        ) { items() }
    }
}

/** The stitch inside a slab or kraft strip. */
private fun DrawScope.barDecor(spec: NavBarSpec) {
    spec.stitch?.let { s ->
        val inset = spec.stitchInset.toPx()
        val w = s.width.toPx()
        val r = (spec.corner.toPx() - inset).coerceAtLeast(0f)
        drawRoundRect(
            s.color, topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
            cornerRadius = CornerRadius(r),
            style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
        )
    }
}

/** Postcard Pop's pill: every tab's icon sits in a slot this size, so the active pill stays in line with the rest. */
private val PILL_W = 46.dp
private val PILL_H = 28.dp
private val PILL_CORNER = 10.dp

@Composable
private fun NavItem(tab: BarItem, on: Boolean, spec: NavBarSpec, modifier: Modifier, onClick: () -> Unit) {
    val ty = Bt.tokens.type
    val face = { f: NavFace ->
        when (f) {
            NavFace.Albert -> BtFonts.albert
            NavFace.Body -> ty.body
            NavFace.Hand -> ty.hand
        }
    }
    val hand = on && spec.activeFace == NavFace.Hand
    val size = if (on) spec.activeLabelSize else spec.labelSize
    val labelStyle = TextStyle(
        fontFamily = face(if (on) spec.activeFace else spec.labelFace),
        fontWeight = if (on) spec.activeLabelWeight else spec.labelWeight,
        fontSize = size,
        lineHeight = if (hand) size else size * 1.25f,
        letterSpacing = spec.letterSpacing,
        color = if (on) spec.activeLabel else spec.label,
    )
    val label = if (spec.upper) tab.label.uppercase() else tab.label
    // The active label's width: Trail's tag grows to the left for a long one ("COMMUNITY") so no letter falls off it.
    var labelWidth by remember { mutableStateOf(0f) }
    Column(
        modifier
            .semantics { this.selected = on }
            .clickable(role = Role.Tab, onClickLabel = tab.label, onClick = onClick)
            .then(if (on) Modifier.drawBehind { markerBehind(spec, labelWidth) } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        val tint = if (on) spec.activeIcon else spec.icon
        if (spec.marker == NavMarker.Pill) {
            Box(
                Modifier.size(PILL_W, PILL_H)
                    .then(if (on) Modifier.clip(RoundedCornerShape(PILL_CORNER)).background(spec.markerColor) else Modifier),
                contentAlignment = Alignment.Center,
            ) { Icon(tab.icon, null, Modifier.size(20.dp), tint = tint) }
        } else {
            Icon(tab.icon, null, Modifier.size(20.dp), tint = tint)
        }
        val labelModifier = if (on && spec.marker == NavMarker.Highlighter) {
            Modifier.rotate(spec.markerTilt).highlighter(spec.markerColor, from = 0.40f, to = 0.94f).padding(horizontal = HIGHLIGHTER_PAD)
        } else {
            Modifier
        }
        // Five tabs on a 360 dp phone leave 56–67 dp per label, less with a larger font: a label that doesn't fit
        // steps down from its size (to [labelMinSize] at most) instead of losing its last letters ("Community").
        Text(
            label, labelModifier, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = labelMinSize(size), maxFontSize = size, stepSize = 0.25.sp),
            onTextLayout = { labelWidth = it.size.width.toFloat() },
        )
    }
}

/** Sticker Book's marker swipe reaches this far past the active label on each side. */
private val HIGHLIGHTER_PAD = 4.dp

/** The smallest a bar label may get: 60 % of its size, never under 8 sp. */
internal fun labelMinSize(size: TextUnit): TextUnit {
    val scaled = size * 0.6f
    return if (scaled > 8.sp) scaled else 8.sp
}

/** The marker drawn behind the active tab: Trail's tag, Sketchbook's loop, Market's dot. */
private fun DrawScope.markerBehind(spec: NavBarSpec, labelWidth: Float = 0f) {
    val cx = size.width / 2
    when (spec.marker) {
        NavMarker.LuggageTag -> {
            // `.ltag`: 66 x 48 at top 7, from 29 left of centre; the point 16 dp deep on the left; -5 degrees.
            // Wider for a label that would reach past the point (12 dp clear of it), never narrower.
            val left = minOf(cx - 29.dp.toPx(), cx - labelWidth / 2 - 12.dp.toPx())
            val top = 7.dp.toPx()
            val w = cx + 37.dp.toPx() - left
            val h = 48.dp.toPx()
            val point = 16.dp.toPx()
            val tag = Path().apply {
                moveTo(left + point, top)
                lineTo(left + w, top)
                lineTo(left + w, top + h)
                lineTo(left + point, top + h)
                lineTo(left, top + h / 2)
                close()
            }
            rotate(spec.markerTilt, Offset(left + w / 2, top + h / 2)) { drawPath(tag, spec.markerColor) }
            // `.lhole`: the punched hole, 6 dp, 21 left of centre, 27 down.
            drawCircle(spec.activeIcon, 3.dp.toPx(), Offset(left + 11.dp.toPx(), 30.dp.toPx()))
        }
        NavMarker.PencilLoop -> {
            // `.loop`: a 62 x 40 hand-drawn loop at top 4, centred (the board's SVG path, smooth curves expanded).
            val u = 1.dp.toPx()
            val ox = cx - 31 * u
            val oy = 4 * u
            fun p(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
            val loop = Path().apply {
                val start = p(10f, 24f)
                moveTo(start.x, start.y)
                cubic(p(7f, 12f), p(22f, 5f), p(34f, 5f))
                cubic(p(46f, 5f), p(57f, 11f), p(55f, 21f))
                cubic(p(53f, 31f), p(40f, 36f), p(27f, 35f))
                cubic(p(14f, 34f), p(6f, 28f), p(12f, 15f))
            }
            drawPath(loop, spec.markerColor, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        NavMarker.DotSticker -> {
            // `.pdot`: a 40 dp sticker at top 3, 5 dp right of centre, with a small soft shadow.
            val r = 20.dp.toPx()
            val c = Offset(cx + 5.dp.toPx(), 3.dp.toPx() + r)
            drawCircle(Color(0x403A2A1C), r, c + Offset(0f, 2.dp.toPx()))
            drawCircle(spec.markerColor, r, c)
        }
        NavMarker.Pill, NavMarker.Highlighter -> Unit
    }
}

private fun Path.cubic(a: Offset, b: Offset, c: Offset) = cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)

/** The preferences file of 1.11.0's tab memory, deleted on start. */
private const val OLD_TAB_MEMORY = "trip_tabs"

/** The reminder shown each time the app starts in demo mode. */
internal object DemoNotice {
    const val TEXT = "You're looking at the demo. Turn it off in Me › Demo mode."
    const val ACTION = "Turn off"
}
