package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.ui.components.BtTag
import com.justdataplease.boringtravel.model.PlanSpan
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.settings.PlanView
import com.justdataplease.boringtravel.links.PlacePhotos
import com.justdataplease.boringtravel.links.PlaceQuery
import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.places.DayPlace
import com.justdataplease.boringtravel.places.VisitMatcher
import com.justdataplease.boringtravel.ui.calendar.CalendarPrompt
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtFab
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtTextDialog
import com.justdataplease.boringtravel.ui.components.TravelModeChooser
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.place.readableOn
import androidx.compose.foundation.layout.offset
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import com.justdataplease.boringtravel.tags.ItemTags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.LocalDate
import java.time.ZoneId

/** What the item sheet edits: an existing item (as it was when opened), or a new one on [day] (null = Ideas). */
internal data class SheetTarget(val item: PlanItem?, val day: LocalDate?)

/** The List has not been scrolled for any day yet (null is a real answer: the top). */
private val NOT_SCROLLED = Any()

/**
 * The trip's plan, as the List or the Calendar ([PlanView]; Me › Plan view and the switch on the tab's own top row,
 * which also has Expand/Collapse for the List). Both have Chat (the AI planner) and Add at the bottom, and end with
 * Add from a link.
 *
 * The List ([planList]) has every day under its header, empty days folded, Ideas last. Collapsed: one line per item.
 * Expanded (design boards "Plan · day by day, travel per leg"): "Day 13 · El Chaltén", the day's items with their kind
 * in a circle and, between two located items, the leg: a dashed line in the route colour and a chip with the vehicle
 * and a rough time (tap to choose the vehicle). Today's header says TODAY; the List opens on the day asked for
 * ([selectedKey], e.g. from the Calendar tab or after adding a shared link), else on today while the trip is on. The
 * day's title opens the place page; a tap on an item opens its sheet (details, Getting there, Add to calendar); the
 * circle ticks it off. Within a day items with a time are in time order ([PlanSpan.dayOrder]); held, an item lifts and
 * drags within its day or onto another, and the drop says what changed with Undo ([PlanDrop]); held and let go, it
 * opens a menu (Copy name, Copy address, Share). The Calendar ([planCalendar]) keeps its own picked day in [selectedKey].
 */
@Composable
internal fun PlanTab(graph: AppGraph, actions: AppActions, tripId: String, trip: Trip?, selectedKey: String?, onSelect: (String?) -> Unit) {
    val items by remember(tripId) { graph.plan.observeItems(tripId) }.collectAsState(initial = null)
    val stops by remember(tripId) { graph.tracks.observeStops(tripId) }.collectAsState(initial = emptyList())
    val viewSetting by remember { graph.settings.planView }.collectAsState(initial = null)
    val expandedSetting by remember { graph.settings.planExpanded }.collectAsState(initial = null)
    val today = remember { LocalDate.now() }
    val zone = remember { ZoneId.systemDefault() }

    // Plan ↔ route: tick items the route passed on their day. Only ever false → true.
    LaunchedEffect(tripId) {
        withContext(Dispatchers.Default) {
            val list = graph.plan.items(tripId)
            val visited = VisitMatcher.visited(list, graph.tracks.forTrip(tripId), zone)
            if (visited.isNotEmpty()) graph.plan.saveAll(list.filter { it.id in visited && !it.done }.map { it.copy(done = true) })
        }
    }

    val all = items
    val view = viewSetting
    val expanded = expandedSetting
    if (trip == null || all == null || view == null || expanded == null) return
    val days = remember(trip.startDate, trip.endDate, all) { PlanDays.days(trip.startDate, trip.endDate, all) }
    // The Calendar's picked day (and the Add row's day there).
    val selectedDay: LocalDate? = when (val k = selectedKey) {
        null -> PlanDays.initial(days, today)
        PLAN_IDEAS -> null
        else -> k.toLongOrNull()?.let(LocalDate::ofEpochDay)?.takeIf { it in days } ?: PlanDays.initial(days, today)
    }
    var sheet by remember(tripId) { mutableStateOf<SheetTarget?>(null) }
    var travelKey by remember(tripId) { mutableStateOf<RouteLegKey?>(null) }
    var savingTravel by remember(tripId) { mutableStateOf(false) }
    var travelError by remember(tripId) { mutableStateOf<String?>(null) }
    val incomingTravel = remember(trip, all) { PlanTravelRows.incoming(trip, all) }
    val byId = remember(all) { all.associateBy { it.id } }
    val travelTarget = PlanTravelRows.resolve(incomingTravel, travelKey)
    LaunchedEffect(travelKey, travelTarget, savingTravel) {
        if (travelKey != null && travelTarget == null && !savingTravel) {
            travelKey = null
            travelError = "These places changed. Choose travel for the updated route."
        }
    }
    val select: (LocalDate?) -> Unit = { onSelect(it?.toEpochDay()?.toString() ?: PLAN_IDEAS) }
    // Travel items show how they travel: the leg's choice, else what the name says ("Fly to…").
    val badgeVehicle: (PlanItem) -> Vehicle? = { item ->
        incomingTravel[item.id]?.mode ?: TravelDefault.guess(item.title, travelItem = true).takeIf { item.kind == PlanKind.Transport.id }
    }
    val firstDay = rememberFirstDayOfWeek()
    val legs = remember(incomingTravel) { incomingTravel.mapValues { it.value.mode } }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val notices = remember { SnackbarHostState() }
    val noticeScope = rememberCoroutineScope()
    // The days' own names ("Day 3 · Temples and tea"), and the day whose name is being typed.
    val dayNames = remember(trip.travelModes) { DayNames.all(trip.travelModes) }
    var renaming by remember(tripId) { mutableStateOf<LocalDate?>(null) }
    // Today, the day asked for and a day with a name always show their header, even with nothing planned.
    val focus = PlanListLogic.focus(selectedKey, days, today)
    val keep = remember(today, focus, dayNames) { setOfNotNull(today, (focus as? PlanListLogic.Focus.Day)?.day) + dayNames.keys }
    val sections = remember(trip.startDate, trip.endDate, all, legs, keep) { PlanListLogic.sections(trip.startDate, trip.endDate, all, legs, keep) }
    val rows = remember(sections, days.isEmpty()) { PlanListLogic.rows(sections, ideasHeader = days.isNotEmpty()) }

    // Each view keeps its own place. The List scrolls to [focus] when it opens and when another day is asked for;
    // a switch between collapsed and expanded keeps the row at the top (same keys).
    val lead = if (days.isEmpty()) 1 else 0 // the Ideas header with "Add dates"
    val focusIndex = PlanListLogic.indexOf(rows, focus)?.plus(lead) ?: 0
    // Opening on the List starts at the day (no jump from the top).
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (view == PlanView.List) focusIndex else 0)
    val calendarState = rememberLazyListState()

    // A drag reorders a copy of the rows as the finger moves; the drop saves, and the copy stays until the saved plan
    // comes back from the database (no flash of the old order).
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
        graph.scope.launch {
            try {
                graph.plan.saveAll(saves)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                dragRows = null // Not saved: the List shows the plan as it is.
            }
        }
        val notice = PlanDrop.notice(item, saves.first()) ?: return
        noticeScope.launch {
            notices.currentSnackbarData?.dismiss()
            val result = notices.showSnackbar(notice, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) graph.scope.launch { graph.plan.saveAll(undo) }
        }
    }

    val ctx = PlanViewContext(
        trip = trip, items = all, byId = byId, stops = stops, zone = zone, today = today, travel = incomingTravel, legs = legs,
        badgeVehicle = badgeVehicle,
        onToggle = { item -> graph.scope.launch { graph.plan.setDone(item, !item.done) } },
        onOpen = { item -> sheet = SheetTarget(item, item.day) },
        onAdd = { day -> sheet = SheetTarget(null, day) },
        onPlace = { actions.place(tripId, itemId = it) },
        onTravel = { connection ->
            travelError = null
            travelKey = connection.leg.key
        },
        onShare = { item -> shareItem(context, item.title, shareText(item, incomingTravel[item.id], all)) },
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
    )
    renaming?.let { day ->
        val number = PlanDays.number(trip.startDate, trip.endDate, day)
        val place = remember(day, stops, all) { DayPlace.label(day, stops, all, zone) ?: PlanDayLabel.label(day, all) }
        BtTextDialog(
            title = "Rename ${number?.let { "day $it" } ?: Dates.dayMonth(day)}",
            value = dayNames[day].orEmpty(),
            hint = place.orEmpty(),
            note = if (place != null) "Leave it empty to show the place: $place." else "Leave it empty for no name.",
            max = DayNames.MAX,
            onSave = { text ->
                renaming = null
                graph.scope.launch { graph.trips.setDayName(tripId, day, text) }
            },
            onDismiss = { renaming = null },
        )
    }
    val pickView: (PlanView) -> Unit = { v -> graph.scope.launch { graph.settings.setPlanView(v) } }
    val pickExpanded: (Boolean) -> Unit = { on -> graph.scope.launch { graph.settings.setPlanExpanded(on) } }

    var scrolledFor by remember(tripId) { mutableStateOf<Any?>(if (view == PlanView.List) focus else NOT_SCROLLED) }
    LaunchedEffect(view, focus) {
        if (view == PlanView.List && scrolledFor != focus) {
            scrolledFor = focus
            listState.scrollToItem(focusIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        PlanToolbar(view, expanded, planSummary(days.size, all.size), onExpanded = pickExpanded, onView = pickView)
        CalendarPrompt(graph, trip, all)
        if (travelError != null && travelKey == null) {
            Text(travelError.orEmpty(), Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = Bt.text.fine, color = Bt.colors.danger)
        }
        // Adding is a real button (owner, 2026-11-10: "should have a nice button, + is too small"): in an empty plan
        // it sits in the list; once there are items it floats over the list's corner, always in reach. Chat (the AI
        // planner) sits beside it, so adding by hand and with AI are one tap each (owner, 19 Nov 2026: option A).
        val add = { sheet = SheetTarget(null, if (view == PlanView.List) PlanDays.initial(days, today) else selectedDay) }
        val chat = { actions.planner(tripId, chat = true) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = if (view == PlanView.List) listState else calendarState,
                contentPadding = PaddingValues(bottom = if (all.isEmpty()) 32.dp else 96.dp),
            ) {
                // A trip without dates: the Ideas header with "Add dates" instead of the list's small one.
                if (days.isEmpty()) item(key = "head") { IdeasHeader(trip, actions) }
                when (view) {
                    PlanView.List -> planList(ctx, dragRows ?: rows, expanded, reorder)
                    PlanView.Calendar -> planCalendar(ctx, days, selectedDay, firstDay, select)
                }
                if (all.isEmpty()) item(key = "add") { AddButtons(onChat = chat, onAdd = add) }
                // Owner, 17 Nov 2026: "add places to an existing trip using all methods": a Google Maps list, a blog, a Google Doc.
                item(key = "link") { AddFromLinkCard { actions.copySite(tripId = tripId) } }
            }
            if (all.isNotEmpty()) {
                Row(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BtFab("Chat", onClick = chat, icon = BtIcons.Sparkle, accent = true)
                    BtFab("Add", onClick = add)
                }
            }
            // "Moved to Tue 14 · 11:00" with Undo, above the Add button.
            PlanNotice(notices, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
        }
    }

    sheet?.let { target ->
        PlanItemSheet(
            graph = graph,
            tripId = tripId,
            original = target.item,
            newDay = target.day,
            items = all,
            days = days,
            onClose = { sheet = null },
            onOpenEntry = { actions.openEntry(it) },
            onWrite = { actions.writeAbout(tripId, it) },
        )
    }
    travelTarget?.let { connection ->
        TravelModeChooser(
            fromLabel = connection.fromLabel,
            toLabel = connection.toLabel,
            selected = connection.mode,
            enabled = !savingTravel,
            error = travelError,
            onDismiss = { if (!savingTravel) { travelKey = null; travelError = null } },
            countedKm = connection.leg.countsKm,
            kmLine = legKmLine(connection.leg.distanceM, connection.leg.countsKm),
            onCountKm = { on ->
                if (!savingTravel) {
                    savingTravel = true
                    travelError = null
                    val key = connection.leg.key
                    val choice = legKmChoice(connection.mode, on)
                    graph.scope.launch(Dispatchers.Main) {
                        try {
                            if (graph.trips.setLegKm(tripId, key, choice) == null) {
                                travelKey = null
                                travelError = "This trip is no longer available."
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IllegalStateException) {
                            travelError = if (e.message == "This route leg changed. Reopen it and choose again.") e.message else "Couldn't save travel. Try again."
                        } catch (_: Exception) {
                            travelError = "Couldn't save travel. Try again."
                        } finally {
                            savingTravel = false
                        }
                    }
                }
            },
            onPick = { vehicle ->
                if (!savingTravel) {
                    savingTravel = true
                    travelError = null
                    // Keep the user's selected pair, not whichever leg may later precede this destination.
                    val key = connection.leg.key
                    graph.scope.launch(Dispatchers.Main) {
                        try {
                            val saved = graph.trips.setLegVehicle(tripId, key, vehicle)
                            if (saved == null) {
                                travelKey = null
                                travelError = "This trip is no longer available."
                            } else travelKey = null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IllegalStateException) {
                            if (e.message == "This route leg changed. Reopen it and choose again.") {
                                travelError = e.message
                                travelKey = null
                            } else travelError = "Couldn't save travel. Try again."
                        } catch (_: Exception) {
                            travelError = "Couldn't save travel. Try again."
                        } finally {
                            savingTravel = false
                        }
                    }
                }
            },
        )
    }
}

/** A drop's notice with its Undo, in the app's snackbar colours (ink, the page colour on it). */
@Composable
private fun PlanNotice(host: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(host, modifier) { data ->
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
}

/** The line icon for a vehicle. */
internal fun vehicleIcon(v: Vehicle?): ImageVector = when (v) {
    Vehicle.Walk -> BtIcons.Walk
    Vehicle.Bike -> BtIcons.Bike
    Vehicle.Car -> BtIcons.Car
    Vehicle.Train -> BtIcons.Train
    Vehicle.Ferry -> BtIcons.Ferry
    Vehicle.Plane -> BtIcons.Plane
    Vehicle.Auto, null -> BtIcons.Route
}

/** The line icon for a plan item's kind; travel items show how they travel when a vehicle is set. */
internal fun kindIcon(kind: String, vehicle: Vehicle? = null): ImageVector = when (PlanKind.of(kind)) {
    PlanKind.Place -> BtIcons.Place
    PlanKind.Food -> BtIcons.Food
    PlanKind.Stay -> BtIcons.Stay
    PlanKind.Transport -> if (vehicle != null && vehicle != Vehicle.Auto) vehicleIcon(vehicle) else BtIcons.Transport
    PlanKind.Activity -> BtIcons.Activity
    PlanKind.Note -> BtIcons.Note
}

private val KIND_CIRCLE = 36.dp
private val ROW_START = 20.dp

/**
 * An item in the expanded List: the leg that arrives at it ([LegRow], when it has one), then the item ([PlanRow]).
 * A tap opens the item's sheet (Getting there, Add to calendar); held, it drags ([reorder]) or opens its menu
 * ([PlanRowTouch]); the place page opens from the day's title.
 */
@Composable
internal fun ExpandedItemRow(ctx: PlanViewContext, line: PlanLine, reorder: ReorderableCollectionItemScope? = null) {
    val item = line.item
    Column {
        ctx.travel[line.id]?.let { connection ->
            LegRow(LegRowModel.of(connection, ctx.byId[connection.leg.key.fromItemId], item), onClick = { ctx.onTravel(connection) })
        }
        PlanRowTouch(ctx, line, reorder) { touch, holding ->
            if (line.end) EndRow(item, vehicle = ctx.badgeVehicle(item), modifier = touch)
            // A hold's release is not a tick.
            else PlanRow(item, vehicle = ctx.badgeVehicle(item), onToggle = { if (!holding()) ctx.onToggle(item) }, modifier = touch)
        }
    }
}

/**
 * The end of an item on a later day ("Hostal Alicante", CHECK-OUT, 11:00; owner, 2026-11-12: "to be seen as 2 items"):
 * the kind in a dashed circle, the end's name and tag; a tap opens the same item.
 */
@Composable
private fun EndRow(item: PlanItem, vehicle: Vehicle?, modifier: Modifier = Modifier) {
    val name = PlanSpan.endName(item)
    Row(
        modifier.fillMaxWidth()
            .padding(start = ROW_START - 6.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { EndBadge(item.kind, vehicle, KIND_CIRCLE) }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, Modifier.weight(1f, fill = false), style = Bt.text.item, maxLines = 2, overflow = TextOverflow.Ellipsis)
                BtTag(PlanSpan.endTag(item).uppercase())
            }
            item.endMinutes?.let { Text(Dates.time(it), Modifier.padding(top = 1.dp), style = Bt.text.sub, maxLines = 1) }
        }
    }
}

/** The kind in a dashed circle: an item's end, which opens the item but isn't ticked off on its own. */
@Composable
internal fun EndBadge(kind: String, vehicle: Vehicle?, size: androidx.compose.ui.unit.Dp) {
    val c = Bt.colors
    Box(
        Modifier.size(size).drawBehind {
            val w = 1.5.dp.toPx()
            drawCircle(
                c.muted, radius = this.size.minDimension / 2 - w / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                ),
            )
        },
        contentAlignment = Alignment.Center,
    ) { Icon(kindIcon(kind, vehicle), null, Modifier.size(size * 0.5f), tint = c.muted) }
}

/**
 * Between two items: a dashed line in the route colour under the kind circles, and a chip with the vehicle and a
 * rough time ("Car · ≈ 3 h"). The whole row opens the vehicle choice.
 */
@Composable
private fun LegRow(model: LegRowModel, onClick: () -> Unit) {
    val c = Bt.colors
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Choose travel", onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(start = ROW_START, end = 20.dp)
            .drawBehind {
                val x = KIND_CIRCLE.toPx() / 2
                drawLine(
                    c.route, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(KIND_CIRCLE + 12.dp))
        Row(
            Modifier.clip(btControlShape()).background(c.chip).heightIn(min = 30.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(vehicleIcon(model.vehicle), null, Modifier.size(16.dp), tint = c.ink)
            Text(model.text, style = Bt.text.label.copy(fontSize = Bt.text.small * 1.1f), color = c.ink, maxLines = 1)
        }
        model.aside?.let { aside ->
            Text(aside, Modifier.padding(start = 8.dp).weight(1f), style = Bt.text.fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun IdeasHeader(trip: Trip, actions: AppActions) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 6.dp)) {
        Text("Ideas", style = Bt.text.title)
        if (trip.startDate == null) {
            Text("This trip has no dates yet. Add them to plan day by day.", Modifier.padding(top = 2.dp, bottom = 8.dp), style = Bt.text.sub)
            BtGhostButton("Add dates", onClick = { actions.editTrip(trip.id) })
        }
    }
}

/**
 * The kind in a circle (the link's picture or favicon when the item has a link, else its place's photo), then the
 * title and "time · note". The circle ticks the item off (a check replaces the kind); [modifier] carries the rest of
 * the row's touch ([PlanRowTouch]: a tap opens the item).
 */
@Composable
private fun PlanRow(item: PlanItem, vehicle: Vehicle?, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = Bt.colors
    Row(
        modifier.fillMaxWidth()
            .padding(start = ROW_START - 6.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .clickable(role = Role.Checkbox, onClickLabel = if (item.done) "Mark ${item.title} not done" else "Mark ${item.title} done", onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            // The place photo also stands in for a link whose site has no picture of its own.
            val place = remember(item) { PlacePhotos.query(item.copy(url = null)) }
            KindBadge(item.kind, vehicle, item.done, KIND_CIRCLE, link = item.url, place = place)
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            val tag = PlanSpan.startTag(item)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.title, Modifier.weight(1f, fill = false), style = Bt.text.item, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (tag != null) BtTag(tag.uppercase())
            }
            // "19:00–22:00 · 3 h" or "20:30 → Sun 15 11:00 · 3 nights", then the note.
            val sub = listOfNotNull(PlanSpan.times(item), PlanSpan.length(item), item.note.trim().ifBlank { null }).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, Modifier.padding(top = 1.dp), style = Bt.text.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.placeName.isNotBlank() && !item.placeName.startsWith(item.title, ignoreCase = true)) {
                Text(item.placeName, style = Bt.text.fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ItemTagsLine(ItemTags.of(item))
        }
    }
}

/**
 * The kind of a plan item in a circle (the boards' `.s-kind`); a done item keeps its kind and gets a small check
 * in the route colour on the circle's edge. With a [link], the site's picture (or its favicon) covers the kind once
 * it has loaded, at the same size; without one, the photo of its [place], which also covers a link whose site has no picture (a site icon like a
 * blogging platform's logo said little).
 */
@Composable
internal fun KindBadge(
    kind: String,
    vehicle: Vehicle?,
    done: Boolean,
    size: androidx.compose.ui.unit.Dp,
    link: String? = null,
    place: PlaceQuery? = null,
) {
    val c = Bt.colors
    Box(Modifier.size(size)) {
        Box(Modifier.matchParentSize().clip(CircleShape).background(c.chip), contentAlignment = Alignment.Center) {
            Icon(kindIcon(kind, vehicle), null, Modifier.size(size * 0.53f), tint = c.ink)
            if (link != null) LinkBadgeLayers(link, size, fallback = place)
            else if (place != null) PlacePhotoLayer(place)
        }
        if (done) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(size * 0.44f).clip(CircleShape)
                    .background(c.route),
                contentAlignment = Alignment.Center,
            ) { Icon(BtIcons.Check, "Done", Modifier.size(size * 0.3f), tint = readableOn(c.route)) }
        }
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

/** Copy from a link, into this trip: a shared Google Maps list's places, or a blog's or document's places and notes. */
@Composable
private fun AddFromLinkCard(onClick: () -> Unit) {
    BtCard(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp), padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), onClick = onClick) {
        Column {
            Text("Add from a link", style = Bt.text.item)
            Text("A Google Maps list, a blog post or a Google Doc: its places go into this plan.", Modifier.padding(top = 2.dp), style = Bt.text.sub)
        }
    }
    Box(Modifier.height(8.dp))
}
