package com.justdataplease.boringtravel.ios

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.places.VisitMatcher
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDateOfEpochDay
import com.justdataplease.boringtravel.ui.components.BtBackButton
import com.justdataplease.boringtravel.ui.components.BtFab
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtSwitchRow
import com.justdataplease.boringtravel.ui.components.BtTextDialog
import com.justdataplease.boringtravel.ui.components.DemoTag
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btCardShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.launch
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * What the item sheet edits: an existing item (as it was when opened), or a new one on [day] (null = Ideas). [query]:
 * words already in the place's search (the previews).
 */
data class SheetTarget(val item: PlanItem?, val day: LocalDate?, val query: String? = null)

/** What saving a leg's travel says when the plan changed under it (the Android app's TripLegUpdates). */
const val LEG_CHANGED = "This route leg changed. Reopen it and choose again."

/** The List has not been scrolled for any day yet (null is a real answer: the top). */
private val NOT_SCROLLED = Any()

/**
 * One of the traveller's trips in trip mode, as the Android app's trip screen (ui/trip/TripScreen.kt): back, "Day 14 of
 * 31" (or the dates) with DEMO on an example trip, the trip's name, and the trip's menu (Edit trip, Add from a link,
 * Post to community, Community post when this phone posted the trip, Make a clip, Make a book, Share, Calendar, Delete
 * trip); then the section on show, which the bottom bar picks while a trip is open (Plan · Journal · Map · Media, the
 * Android app's TripNav). Plan is [PlanTab]; Journal is [TripJournalTab]; Map is [TripMapBoard]; Media is [TripMediaTab] (the trip's
 * saved clips and its photos by day, since round 20; [onAddMedia] opens a new note with the photo library).
 *
 * Everything read and every change goes through the engine's [TripBook]; [revision] changes whenever the trips do.
 * [planDay]: the plan's day to open on (a day picked on the Calendar tab); else today while the trip is on.
 * [settings]: Plan view and the List expanded or not. [sheetAtStart], [menuAtStart] and [travelAtStart] (an item's id)
 * open the item sheet, the menu or a leg's travel straight away (the previews).
 */
@Composable
fun TripPage(
    book: TripBook,
    tripId: String,
    today: LocalDate,
    revision: Int,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onEdit: () -> Unit,
    onOpenNote: (String) -> Unit,
    onWrite: () -> Unit,
    onDeleted: () -> Unit,
    tab: TripTab = TripTab.Plan,
    planDay: LocalDate? = null,
    sheetAtStart: SheetTarget? = null,
    menuAtStart: Boolean = false,
    settings: AppSettings? = null,
    travelAtStart: String? = null,
    onPlace: (itemId: String) -> Unit = {},
    onAddFromLink: () -> Unit = {},
    maps: MapActions = MapActions(),
    onWriteNote: (entryId: String) -> Unit = {},
    checkInChoicesAtStart: Boolean = false,
    calendarAtStart: Boolean = false,
    onShare: () -> Unit = {},
    onSay: (String) -> Unit = {},
    /** The Plan's Chat: the AI planner on this trip (Android 1.22.0: beside Add; the ✦ left the top bar). */
    onChat: () -> Unit = {},
    onClip: () -> Unit = {},
    onAddMedia: () -> Unit = onWrite,
    onBook: () -> Unit = {},
    /** The item sheet's Write: a new note about plan item [itemId] (Android's AppActions.writeAbout). */
    onWriteAbout: (itemId: String) -> Unit = { onWrite() },
    /** The ⋮'s Post to community: the composer for this trip (round 25). */
    onPostToCommunity: () -> Unit = {},
    /**
     * The ⋮'s Community post, only when this phone posted the trip (its post link): the post's sheet (Android's
     * PostSyncSheet, read in), drawn with its onClose.
     */
    postSheet: (@Composable (onClose: () -> Unit) -> Unit)? = null,
    /** The ⋮'s Travel together open at the start (the previews). */
    togetherAtStart: Boolean = false,
) {
    val trip = remember(book, tripId, revision) { book.trip(tripId) }
    var calendarOpen by remember { mutableStateOf(calendarAtStart) }
    val prefs = settings ?: remember { AppSettings.ready() }
    val files = LocalMediaKit.current.files
    var deleting by remember { mutableStateOf(false) }
    var postOpen by rememberSaveable { mutableStateOf(false) }
    // Travel together (round 26): who the trip is shared with, the sheet, and the trip kept in step while it shows.
    var togetherOpen by rememberSaveable { mutableStateOf(togetherAtStart) }
    val graph = LocalTogetherGraph.current
    val together = LocalTogether.current
    val sharing = together?.state?.collectAsState()?.value
    val shared = sharing?.of(tripId)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(tripId, shared != null, lifecycle) {
        // A shared trip keeps in step with the others while it is open and in front (not with the app in the background):
        // Android's repeatOnLifecycle(STARTED), on the lifecycle's own state (repeatOnLifecycle itself runs on
        // Dispatchers.Main, which the desktop's tests don't have).
        if (shared != null && together != null) {
            lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }.distinctUntilChanged()
                .collectLatest { started -> if (started) together.keepInSync(tripId) }
        }
    }
    // Deleted (or left) elsewhere while open: the screen closes itself (the Android app's TripScreen).
    LaunchedEffect(trip == null) { if (trip == null) onDeleted() }
    // The trip's countries, from the side it is on (the examples, or the traveller's own).
    val flags = rememberWorld(book, revision, trip?.isDemo == true, today).tripCountries[tripId].orEmpty()
    if (trip == null) return
    BtScreen {
        Column(Modifier.fillMaxSize()) {
            TripHeader(
                trip, flags, today, onBack, onEdit, onDelete = { deleting = true }, menuAtStart, onAddFromLink, onCalendar = { calendarOpen = true },
                onShare = onShare, onBook = onBook, onClip = onClip, onPostToCommunity = onPostToCommunity,
                onPost = if (postSheet != null) ({ postOpen = true }) else null,
                onTogether = if (graph != null) ({ togetherOpen = true }) else null,
                sharedWith = shared?.members?.count { !it.left }, shared = shared != null,
            )
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) },
                label = "Trip section",
            ) { section ->
                Box(Modifier.fillMaxSize()) {
                    when (section) {
                        TripTab.Plan -> PlanTab(
                            book, trip, today, revision, prefs, onChanged, onEdit, planDay, sheetAtStart, travelAtStart, onPlace, onChat,
                            onOpenNote = onOpenNote, onWriteAbout = onWriteAbout,
                        )
                        TripTab.Journal -> TripJournalTab(book, tripId, revision, onOpenNote, onWrite) {
                            DailyJournalCheckIn(book, prefs, tripId, today, onChanged, onWrite = { onWriteNote(it.id) }, choicesAtStart = checkInChoicesAtStart)
                        }
                        TripTab.Map -> TripMapBoard(book, tripId, today, revision, prefs, onChanged, maps)
                        TripTab.Media -> TripMediaTab(book, tripId, revision, onOpenNote, onAddMedia, onChanged)
                    }
                }
            }
        }
    }
    // The trip and the phone's calendars (⋮ › Calendar).
    if (calendarOpen) CalendarSheet(book, tripId, revision, onClose = { calendarOpen = false }, onChanged = onChanged)
    // The trip's community post: update it now (only by hand), open it, stop updating it.
    if (postOpen && postSheet != null) postSheet { postOpen = false }
    // Deleted (or left): the screen closes itself when the trip goes (above). A shared trip others are in is left.
    val scope = rememberCoroutineScope()
    if (deleting) DeleteTripDialog(
        onConfirm = {
            val deletions = graph?.deletions
            if (deletions == null) {
                deleting = false
                removeTripAndFiles(book, files, tripId)
                onDeleted()
            } else scope.launch {
                deletions.deleteTrip(tripId)
                deleting = false
                onChanged()
            }
        },
        onDismiss = { deleting = false },
        leaves = graph?.deletions?.leaves(tripId) == true,
    )
    // Travel together: invite, who is in, sync now, leave (the Android app's own sheet, read in).
    if (togetherOpen && graph != null) {
        com.justdataplease.boringtravel.ui.together.TogetherSheet(graph, tripId, onClose = { togetherOpen = false }, onLeft = { togetherOpen = false })
    }
}

/**
 * A trip's Plan (the Android app's PlanTab.kt): "12 days · 34 items" with Expand/Collapse and List · Calendar, then the
 * plan as the List (collapsed: one line per item; expanded: every day in full with the travel between two places) or
 * the Calendar (a month grid; the picked day's items under it). A tap on an item opens its sheet, its circle ticks it,
 * press and hold lifts it to drag within its day or onto another day, a folded run of empty days or Ideas, and the
 * drop says what changed with Undo ([PlanDrop]); held and let go it opens Copy name, Copy address and Share. A day's
 * title opens the day's place page ([onPlace], the item [PlanDayAnchor] picks); held, it renames the day. A leg between
 * two places opens its travel ([TravelModeChooser]). The circles show a link's picture or favicon, else the place's
 * Wikipedia photo. Chat (the AI planner, [onChat]) and Add: a pair in an empty plan, floating over the list's corner
 * once there are items (Android 1.22.0). No "Add from a link" card under the plan (Android, 19 Nov 2026): the trip's
 * menu keeps it. Items the recorded route passed on their day are ticked when the plan opens.
 */
@Composable
private fun PlanTab(
    book: TripBook,
    trip: Trip,
    today: LocalDate,
    revision: Int,
    settings: AppSettings,
    onChanged: () -> Unit,
    onEdit: () -> Unit,
    planDay: LocalDate?,
    sheetAtStart: SheetTarget?,
    travelAtStart: String?,
    onPlace: (itemId: String) -> Unit,
    onChat: () -> Unit,
    onOpenNote: (String) -> Unit = {},
    onWriteAbout: (String) -> Unit = {},
) {
    val tripId = trip.id
    val phone = LocalPhone.current
    val haptics = LocalHapticFeedback.current
    // Plan ↔ route: items the recorded route passed on their day are ticked when the plan opens; never unticked.
    LaunchedEffect(tripId) {
        val visited = VisitMatcher.visited(book.items(tripId), book.snapshot.trackPoints.filter { it.tripId == tripId }, null)
        if (visited.isNotEmpty()) {
            visited.forEach { book.setDone(it, true) }
            onChanged()
        }
    }
    val all = remember(book, tripId, revision) { book.items(tripId) }
    // Where the traveller was, as a backup from Android brings it (this phone doesn't track yet): it names the days.
    val stops = remember(book, tripId, revision) { book.snapshot.stops.filter { it.tripId == tripId && it.deletedAt == null } }
    val view = settings.planView
    val expanded = settings.planExpanded
    val days = remember(trip.startDate, trip.endDate, all) { PlanDays.days(trip.startDate, trip.endDate, all) }

    // The day asked for (the Calendar tab) and the Calendar view's picked day share one key: an epoch day or the Ideas.
    var selectedKey by rememberSaveable(tripId) { mutableStateOf(planDay?.toEpochDay()?.toString()) }
    LaunchedEffect(planDay) { if (planDay != null) selectedKey = planDay.toEpochDay().toString() }
    val selectedDay: LocalDate? = when (val k = selectedKey) {
        null -> PlanDays.initial(days, today)
        PLAN_IDEAS -> null
        else -> k.toLongOrNull()?.let(::localDateOfEpochDay)?.takeIf { it in days } ?: PlanDays.initial(days, today)
    }

    var sheet by remember(tripId) { mutableStateOf(sheetAtStart) }
    var travelKey by remember(tripId) { mutableStateOf<RouteLegKey?>(null) }
    var travelError by remember(tripId) { mutableStateOf<String?>(null) }
    val incomingTravel = remember(trip, all) { PlanTravelRows.incoming(trip, all) }
    LaunchedEffect(travelAtStart) { travelAtStart?.let { id -> incomingTravel[id]?.let { travelKey = it.leg.key } } }
    val byId = remember(all) { all.associateBy { it.id } }
    val travelTarget = PlanTravelRows.resolve(incomingTravel, travelKey)
    LaunchedEffect(travelKey, travelTarget) {
        // Read again here: the key may have been set by another effect in this same frame.
        val key = travelKey
        if (key != null && PlanTravelRows.resolve(incomingTravel, key) == null) {
            travelKey = null
            travelError = "These places changed. Choose travel for the updated route."
        }
    }
    // Travel items show how they travel: the leg's choice, else what the name says ("Fly to…").
    val badgeVehicle: (PlanItem) -> Vehicle? = { item ->
        incomingTravel[item.id]?.mode ?: TravelDefault.guess(item.title, travelItem = true).takeIf { item.kind == PlanKind.Transport.id }
    }
    val legs = remember(incomingTravel) { incomingTravel.mapValues<String, PlanTravelConnection, Vehicle?> { it.value.mode } }
    val notices = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val dayNames = remember(trip.travelModes) { DayNames.all(trip.travelModes) }
    var renaming by remember(tripId) { mutableStateOf<LocalDate?>(null) }
    // Today, the day asked for and a day with a name always show their header, even with nothing planned.
    val focus = PlanListLogic.focus(selectedKey, days, today)
    val keep = remember(today, focus, dayNames) { setOfNotNull(today, (focus as? PlanListLogic.Focus.Day)?.day) + dayNames.keys }
    val sections = remember(trip.startDate, trip.endDate, all, legs, keep) { PlanListLogic.sections(trip.startDate, trip.endDate, all, legs, keep) }
    val rows = remember(sections, days.isEmpty()) { PlanListLogic.rows(sections, ideasHeader = days.isNotEmpty()) }

    // Each view keeps its own place. The List scrolls to [focus] when it opens and when another day is asked for; a
    // switch between collapsed and expanded keeps the row at the top (same keys).
    val lead = if (days.isEmpty()) 1 else 0 // the Ideas header with "Add dates"
    val focusIndex = PlanListLogic.indexOf(rows, focus)?.plus(lead) ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (view == PlanView.List) focusIndex else 0)
    val calendarState = rememberLazyListState()

    // A drag reorders a copy of the rows as the finger moves; the drop saves, and the copy stays until the saved plan
    // comes back (no flash of the old order).
    var dragRows by remember(tripId) { mutableStateOf<List<PlanListLogic.Row>?>(null) }
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val list = dragRows ?: return@rememberReorderableLazyListState
        val i = list.indexOfFirst { it.key == from.key }
        val j = list.indexOfFirst { it.key == to.key }
        if (i < 0 || j < 0) return@rememberReorderableLazyListState
        dragRows = list.toMutableList().apply { add(j, removeAt(i)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    LaunchedEffect(all) { if (!reorder.isAnyItemDragging) dragRows = null }
    fun drop(line: PlanLine) {
        val list = dragRows
        val item = byId[line.item.id]
        val target = list?.let { PlanDrop.target(it, PlanListLogic.Row.Line(line).key) }
        val moved = list != null && list.map { it.key } != rows.map { it.key }
        val saves = if (item == null || target == null || !moved) emptyList()
        else PlanDrop.drop(item, target.day, target.before.map { byId[it.id] ?: it }, target.after.map { byId[it.id] ?: it })
        if (item == null || saves.isEmpty()) {
            dragRows = null
            return
        }
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        val undo = saves.mapNotNull { byId[it.id] }
        if (book.saveItems(saves).isEmpty()) dragRows = null // Not saved: the List shows the plan as it is.
        onChanged()
        val notice = PlanDrop.notice(item, saves.first()) ?: return
        scope.launch {
            notices.currentSnackbarData?.dismiss()
            val result = notices.showSnackbar(notice, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                book.saveItems(undo)
                onChanged()
            }
        }
    }

    val ctx = PlanViewContext(
        trip = trip, items = all, byId = byId, stops = stops, today = today, travel = incomingTravel, legs = legs,
        badgeVehicle = badgeVehicle,
        onToggle = { item ->
            book.setDone(item.id, !item.done)
            onChanged()
        },
        onOpen = { item -> sheet = SheetTarget(item, item.day) },
        onAdd = { day -> sheet = SheetTarget(null, day) },
        onTravel = { connection ->
            travelError = null
            travelKey = connection.leg.key
        },
        onShare = { item -> phone.share(item.title, shareText(item, incomingTravel[item.id], all)) },
        onLift = {
            dragRows = dragRows ?: rows
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        },
        onDrop = ::drop,
        dayNames = dayNames,
        onRenameDay = { day ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            renaming = day
        },
        onPlace = onPlace,
    )
    renaming?.let { day ->
        val number = PlanDays.number(trip.startDate, trip.endDate, day)
        val place = remember(day, stops, all) { dayPlace(day, stops, all) }
        BtTextDialog(
            title = "Rename ${number?.let { "day $it" } ?: Dates.dayMonth(day)}",
            value = dayNames[day].orEmpty(),
            hint = place.orEmpty(),
            note = if (place != null) "Leave it empty to show the place: $place." else "Leave it empty for no name.",
            max = DayNames.MAX,
            onSave = { text ->
                renaming = null
                book.nameDay(tripId, day, text)
                onChanged()
            },
            onDismiss = { renaming = null },
        )
    }

    var scrolledFor by remember(tripId) { mutableStateOf<Any?>(if (view == PlanView.List) focus else NOT_SCROLLED) }
    LaunchedEffect(view, focus) {
        if (view == PlanView.List && scrolledFor != focus) {
            scrolledFor = focus
            listState.scrollToItem(focusIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        PlanToolbar(
            view, expanded, planSummary(days.size, all.size),
            onExpanded = { settings.planExpanded = it },
            onView = { settings.planView = it },
        )
        CalendarPrompt(book, trip, all, revision)
        val error = travelError
        if (error != null && travelKey == null) {
            Text(error, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = Bt.text.fine, color = Bt.colors.danger)
        }
        // Adding is a real button: in an empty plan it sits in the list; once there are items it floats over the list's
        // corner. Chat (the AI planner) sits beside it (Android 1.22.0: the owner's option A).
        val add = { sheet = SheetTarget(null, if (view == PlanView.List) PlanDays.initial(days, today) else selectedDay) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = if (view == PlanView.List) listState else calendarState,
                contentPadding = PaddingValues(bottom = if (all.isEmpty()) 32.dp else 96.dp),
            ) {
                // A trip without dates: the Ideas header with "Add dates" instead of the list's small one.
                if (days.isEmpty()) item(key = "head") { IdeasHeader(trip, onAddDates = onEdit) }
                when (view) {
                    PlanView.List -> planList(ctx, dragRows ?: rows, expanded, reorder)
                    PlanView.Calendar -> planCalendar(ctx, days, selectedDay, phone.firstDayOfWeek) { selectedKey = it?.toEpochDay()?.toString() ?: PLAN_IDEAS }
                }
                if (all.isEmpty()) item(key = "add") { AddButtons(onChat = onChat, onAdd = add) }
            }
            if (all.isNotEmpty()) {
                Row(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BtFab("Chat", onClick = onChat, icon = BtIcons.Sparkle, accent = true)
                    BtFab("Add", onClick = add)
                }
            }
            // "Moved to Tue 14 · 11:00" with Undo, above the Add button.
            BtSnackbarHost(notices, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
        }
    }

    sheet?.let { target ->
        PlanItemSheet(
            book, tripId, target.item, target.day, all, days, onChanged = onChanged, onClose = { sheet = null }, placeQueryAtStart = target.query,
            onOpenEntry = onOpenNote, onWrite = onWriteAbout,
        )
    }
    travelTarget?.let { connection ->
        fun save(change: () -> Trip?) {
            travelError = null
            try {
                if (change() == null) travelError = "This trip is no longer available."
                travelKey = null
                onChanged()
            } catch (e: IllegalStateException) {
                if (e.message == LEG_CHANGED) {
                    travelError = e.message
                    travelKey = null
                } else travelError = "Couldn't save travel. Try again."
            } catch (_: Exception) {
                travelError = "Couldn't save travel. Try again."
            }
        }
        TravelModeChooser(
            fromLabel = connection.fromLabel,
            toLabel = connection.toLabel,
            selected = connection.mode,
            error = travelError,
            onDismiss = {
                travelKey = null
                travelError = null
            },
            countedKm = connection.leg.countsKm,
            kmLine = legKmLine(connection.leg.distanceM, connection.leg.countsKm),
            onCountKm = { on ->
                val key = connection.leg.key
                // The switch keeps the sheet open, as on Android.
                travelError = null
                try {
                    if (book.setLegKm(tripId, key, legKmChoice(connection.mode, on)) == null) {
                        travelKey = null
                        travelError = "This trip is no longer available."
                    }
                    onChanged()
                } catch (e: IllegalStateException) {
                    travelError = if (e.message == LEG_CHANGED) e.message else "Couldn't save travel. Try again."
                } catch (_: Exception) {
                    travelError = "Couldn't save travel. Try again."
                }
            },
            onPick = { vehicle -> save { book.setLegVehicle(tripId, connection.leg.key, vehicle) } },
            rides = settings.rides,
        )
    }
}

/**
 * One explicit choice for a single leg (the Android app's TravelModeChooser): "Travel for this leg", "From → To", the six
 * ways in tiles with the paper vehicles in the theme's colours and the traveller's [rides] (the trip video's own 3D
 * models, drawn once by the Android app's VehicleSprites, read in since round 18), "Count in km", "Leave unselected"
 * (back to the default), and "Keep saved choice" after an error. A tap on a tile saves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TravelModeChooser(
    fromLabel: String,
    toLabel: String,
    selected: Vehicle?,
    onPick: (Vehicle?) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    error: String? = null,
    countedKm: Boolean? = null,
    kmLine: String = "",
    onCountKm: (Boolean) -> Unit = {},
    rides: com.justdataplease.boringtravel.clip.Rides = com.justdataplease.boringtravel.clip.Rides.DEFAULT,
) {
    val c = Bt.colors
    // The vehicles in the current theme's colours, as Me's rides (the replay's own models; see VehicleSprites).
    val palette = com.justdataplease.boringtravel.clip.PaperVehicle3D.Palette.of(Bt.tokens)
    var artwork by remember { mutableStateOf<Map<Vehicle, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap()) }
    LaunchedEffect(palette, rides) {
        artwork = try {
            com.justdataplease.boringtravel.clip.VehicleSprites.load(palette, rides).mapValues { it.value.asImageBitmap() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Travel for this leg", style = Bt.text.h2)
            Text("$fromLabel → $toLabel", style = Bt.text.body)
            if (error != null) Text(error, style = Bt.text.sub, color = c.danger)
            Vehicle.entries.filter { it != Vehicle.Auto }.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { vehicle ->
                        val chosen = selected == vehicle
                        val shape = btCardShape()
                        Column(
                            Modifier.weight(1f).heightIn(min = 94.dp).clip(shape)
                                .background(if (chosen) c.chipOn else c.bg)
                                .border(1.dp, if (chosen) c.primary else c.line, shape)
                                .semantics { this.selected = chosen }
                                .clickable(enabled = enabled, role = Role.RadioButton, onClickLabel = vehicle.label) { onPick(vehicle) }
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            TravelModeIcon(vehicle, Modifier.size(48.dp), artwork[vehicle])
                            Text(vehicle.label, style = Bt.text.label, color = if (chosen) c.onChipOn else c.ink)
                        }
                    }
                }
            }
            if (countedKm != null) BtSwitchRow("Count in km", kmLine, countedKm, onCountKm, enabled = enabled)
            if (selected != null && selected != Vehicle.Auto) {
                BtGhostButton("Leave unselected", { onPick(null) }, Modifier.fillMaxWidth(), enabled = enabled)
            }
            if (error != null) BtGhostButton("Keep saved choice", onDismiss, Modifier.fillMaxWidth())
        }
    }
}

/** The paper vehicle (in the theme's colours) for the travel tiles; the route's mark while the pictures are drawn. */
@Composable
fun TravelModeIcon(vehicle: Vehicle, modifier: Modifier = Modifier.size(40.dp), sprite: androidx.compose.ui.graphics.ImageBitmap? = null) {
    if (sprite != null) {
        androidx.compose.foundation.Image(sprite, null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Fit)
    } else {
        // Never build the models on the main thread while the pictures are being drawn.
        Icon(BtIcons.Route, null, modifier, tint = Bt.colors.muted)
    }
}

/** Chat and "Add a place or a note": the main buttons of a plan with nothing in it yet, the pair that floats later. */
@Composable
private fun AddButtons(onChat: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BtFab("Chat", onClick = onChat, icon = BtIcons.Sparkle, accent = true)
        BtFab("Add a place or a note", onClick = onAdd, modifier = Modifier.weight(1f))
    }
}

/**
 * Back, "Day 14 of 31" (or the dates), DEMO and up to 3 flags over the trip's name in the display face, and the trip's
 * menu. No ✦ here since Android 1.22.0: the AI chat is the Plan's Chat, beside Add.
 */
@Composable
private fun TripHeader(
    trip: Trip, flags: List<String>, today: LocalDate, onBack: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, menuAtStart: Boolean,
    onAddFromLink: () -> Unit, onCalendar: () -> Unit, onShare: () -> Unit, onBook: () -> Unit,
    onClip: () -> Unit, onPostToCommunity: () -> Unit, onPost: (() -> Unit)?,
    onTogether: (() -> Unit)? = null, sharedWith: Int? = null, shared: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(menuAtStart) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BtBackButton(onBack)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(dateLine(trip, today), Modifier.weight(1f, fill = false))
                if (trip.isDemo) DemoTag(Modifier.padding(start = 6.dp))
                TripFlags(flags, Modifier.padding(start = 6.dp), size = 14.dp, max = 3)
            }
            Text(Bt.text.display(trip.title), style = Bt.text.h2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // A shared trip shows who it is shared with; the button opens Travel together (round 26).
        if (sharedWith != null && onTogether != null) SharedBadge(sharedWith, onTogether)
        // The AI chat is the Plan's Chat button, beside Add (Android 1.22.0: the ✦ left the top bar on every tab).
        Box {
            BtIconButton(BtIcons.More, "Trip options", { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Bt.colors.surface) {
                DropdownMenuItem(text = { Text("Edit trip") }, leadingIcon = { Icon(BtIcons.Edit, null) }, onClick = {
                    menuOpen = false
                    onEdit()
                })
                // A Google Maps list into this trip (Copy from a link).
                DropdownMenuItem(text = { Text("Add from a link") }, leadingIcon = { Icon(BtIcons.Link, null) }, onClick = {
                    menuOpen = false
                    onAddFromLink()
                })
                // Owner, 2026-11-11 (Android): "in 3 dots add post to community second".
                DropdownMenuItem(text = { Text("Post to community") }, leadingIcon = { Icon(BtIcons.Community, null) }, onClick = {
                    menuOpen = false
                    onPostToCommunity()
                })
                // The trip's post: update it now (only by hand), open it, stop updating it.
                if (onPost != null) {
                    DropdownMenuItem(text = { Text("Community post") }, leadingIcon = { Icon(BtIcons.Share, null) }, onClick = {
                        menuOpen = false
                        onPost()
                    })
                }
                // Make a clip opens the trip video, Make a book the book; Share prints and exports the trip.
                DropdownMenuItem(text = { Text("Make a clip") }, leadingIcon = { Icon(BtIcons.Movie, null) }, onClick = {
                    menuOpen = false
                    onClip()
                })
                DropdownMenuItem(text = { Text("Make a book") }, leadingIcon = { Icon(BtIcons.Journal, null) }, onClick = {
                    menuOpen = false
                    onBook()
                })
                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(BtIcons.Share, null) }, onClick = {
                    menuOpen = false
                    onShare()
                })
                // The trip and the phone's calendars: add, keep updated, remove, an .ics file, import.
                DropdownMenuItem(text = { Text("Calendar") }, leadingIcon = { Icon(BtIcons.Calendar, null) }, onClick = {
                    menuOpen = false
                    onCalendar()
                })
                // Travel together (round 26): invite, who is in, sync, leave.
                if (onTogether != null) {
                    DropdownMenuItem(text = { Text("Travel together") }, leadingIcon = { Icon(BtIcons.Me, null) }, onClick = {
                        menuOpen = false
                        onTogether()
                    })
                }
                // Owner (Android, 2026-11-11): "also make able to delete trip". Last, in the danger colour; a shared trip is left.
                val danger = Bt.colors.danger
                DropdownMenuItem(
                    text = { Text(if (shared) "Leave trip" else "Delete trip", color = danger) },
                    leadingIcon = { Icon(BtIcons.Delete, null, tint = danger) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** "Shared" with the number of others, in the trip header (the Android app's SharedBadge). */
@Composable
private fun SharedBadge(others: Int, onClick: () -> Unit) {
    val c = Bt.colors
    Row(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(c.accent.copy(alpha = 0.22f))
            .clickable(role = Role.Button, onClickLabel = "Travel together", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(BtIcons.Me, null, Modifier.size(16.dp), tint = c.ink)
        Text(if (others > 0) "+$others" else "Shared", style = Bt.text.label, color = c.ink)
    }
}

/** "Day 14 of 31" while the trip is on, else its dates ("1–31 Mar 2027 · 31 days"). */
fun dateLine(trip: Trip, today: LocalDate): String {
    val s = trip.startDate
    val e = trip.endDate
    val days = trip.days
    return when {
        s == null || e == null || days == null -> "Dates to be decided"
        trip.isCurrent(today) -> "Day ${trip.dayNumber(today)} of $days"
        else -> "${Dates.range(s, e)} · $days ${if (days == 1) "day" else "days"}"
    }
}

/** A trip without dates: "Ideas", what having no dates means, and Add dates (Edit trip). */
@Composable
private fun IdeasHeader(trip: Trip, onAddDates: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 6.dp)) {
        Text("Ideas", style = Bt.text.title)
        if (trip.startDate == null) {
            Text("This trip has no dates yet. Add them to plan day by day.", Modifier.padding(top = 2.dp, bottom = 8.dp), style = Bt.text.sub)
            BtGhostButton("Add dates", onClick = onAddDates)
        }
    }
}
