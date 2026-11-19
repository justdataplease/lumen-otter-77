package com.justdataplease.boringtravel.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.clip.MapStylePrefs
import com.justdataplease.boringtravel.clip.PlannedReplay
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.tracking.RouteShape
import java.time.Instant
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.geo.GeoPoint
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.tracking.TrackingPlan
import com.justdataplease.boringtravel.tracking.TrackingStatus
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.BtRadioRow
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * One trip's map (design boards "Map · travelled and planned"): the travelled route solid in the route colour, the
 * planned one dashed, stops and a ring at "now". On top, [lead] (back, or the globe on the Map tab) and Travelled ·
 * Planned; underneath, a sheet with the leg the trip has reached ("Day 13 · Leg 9 of 21", "El Calafate → El Chaltén",
 * about how long), its vehicle and "Play the route clip". A tap on a stop or a planned place opens that place. The
 * map button picks Street · Terrain · Earth (the clip's map styles), remembered for the next trip map.
 * [edgeToEdge] = drawn under the status bar (the Map tab), not under a trip's header.
 */
@Composable
internal fun TripMapBoard(
    graph: AppGraph,
    actions: AppActions,
    tripId: String,
    edgeToEdge: Boolean,
    lead: (@Composable () -> Unit)? = null,
) {
    val trip by graph.trips.observeTrip(tripId).collectAsStateWithLifecycle(initialValue = null)
    val points by graph.tracks.observeForTrip(tripId).collectAsStateWithLifecycle(initialValue = emptyList())
    val stops by graph.tracks.observeStops(tripId).collectAsStateWithLifecycle(initialValue = emptyList())
    val items by graph.plan.observeItems(tripId).collectAsStateWithLifecycle(initialValue = emptyList())
    val entries by graph.journal.observeForTrip(tripId).collectAsStateWithLifecycle(initialValue = emptyList())
    val appLevel by graph.settings.trackingLevel.collectAsStateWithLifecycle(initialValue = null)
    val status by graph.tracking.status.collectAsStateWithLifecycle()
    LaunchedEffect(tripId) { graph.tracking.processor.process(tripId) }
    var mode by rememberSaveable(tripId) { mutableIntStateOf(0) }
    val view by produceState<TripMapView?>(null, trip, points, stops, items, entries) {
        val t = trip ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val travelled = TripMapModel.build(t, points, stops, items, entries, now, zone)
            val planned = PlannedReplay.build(t, items).waypoints.map { GeoPoint(it.lat, it.lng) }
            // Without a recorded track, the travelled line joins the saved stops in order (as the clip's "Saved stops"
            // does), and "now" is today's latest stop.
            val ordered = RouteLegs.orderedStops(t, stops)
            val routes = travelled.data.routes.ifEmpty {
                if (ordered.size < 2) emptyList()
                else listOf(MapRoute(t.id, listOf(RouteShape.Piece(ordered.map { GeoPoint(it.lat, it.lng) }, jump = false))))
            }
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val me = travelled.data.me ?: ordered.lastOrNull()
                ?.takeIf { t.isCurrent(today) && Instant.ofEpochMilli(it.arrivedAt).atZone(zone).toLocalDate() == today }
                ?.let { GeoPoint(it.lat, it.lng) }
            travelled.copy(data = travelled.data.copy(routes = routes, planned = planned, me = me))
        }
    }
    val cards = remember(trip, items) { trip?.let { LegSheetModel.cards(it, items) }.orEmpty() }
    val today = remember { LocalDate.now() }
    var legIndex by rememberSaveable(tripId) { mutableIntStateOf(-1) }
    LaunchedEffect(cards.size) { if (legIndex !in cards.indices) legIndex = LegSheetModel.currentIndex(cards, today) }
    var fitTick by remember { mutableIntStateOf(0) }
    var sheetPx by remember { mutableIntStateOf(0) }
    var trackingOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var mapStyle by remember { mutableStateOf(MapStylePrefs.load(context, MapStylePrefs.TRIP_MAP)) }
    var stylesOpen by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    val sheetDp = with(density) { sheetPx.toDp() }
    val v = view
    // Travelled: the route, stops, entries and "now", with the plan dashed underneath. Planned: the plan alone.
    val shown = when {
        v == null -> MapData()
        mode == 0 -> v.data.copy(plans = emptyList())
        else -> MapData(plans = v.data.plans, planned = v.data.planned)
    }
    LaunchedEffect(mode) { fitTick++ }

    Box(Modifier.fillMaxSize()) {
        BtMap(
            data = shown,
            modifier = Modifier.fillMaxSize(),
            showStopNames = true,
            contentPadding = PaddingValues(bottom = sheetDp, top = if (edgeToEdge) 96.dp else 56.dp),
            fitKey = fitTick.takeIf { it > 0 },
            mapStyle = mapStyle,
            onTap = { tap ->
                when (tap) {
                    is MapTap.Entry -> actions.openEntry(tap.entryId)
                    is MapTap.StopMark -> actions.place(tripId, stopId = tap.stopId)
                    is MapTap.Plan -> actions.place(tripId, itemId = tap.itemId)
                    is MapTap.Trip -> Unit
                }
            },
        )
        Column(
            Modifier.fillMaxWidth().then(if (edgeToEdge) Modifier.statusBarsPadding() else Modifier).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                lead?.invoke()
                MapSegmented(listOf("Travelled", "Planned"), mode) { mode = it }
                Spacer(Modifier.weight(1f))
                val t = trip
                if (t != null && appLevel != null && t.startDate != null && t.endDate != null && !t.isPast(today)) {
                    BtIconButton(BtIcons.MyLocation, "Route tracking", { trackingOpen = true })
                }
                BtIconButton(BtIcons.Map, "Map style", { stylesOpen = !stylesOpen }, filled = stylesOpen)
                if (!shown.isEmpty) BtIconButton(BtIcons.Zoom, "Show the whole trip", { fitTick++ })
            }
            if (stylesOpen) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                MapSegmented(MapStyle.tripMap.map { it.label }, MapStyle.tripMap.indexOf(mapStyle)) { i ->
                    mapStyle = MapStyle.tripMap[i]
                    MapStylePrefs.save(context, MapStylePrefs.TRIP_MAP, mapStyle)
                }
            }
            if (mode == 0 && v != null && v.hasTrack) {
                val recording = status.target.tripId == tripId && status.recording && !status.stopped
                // Without recorded points there is no distance to tell, only the stops.
                MapNote(if (points.isEmpty()) "${v.stops}${if (v.current) " today" else ""}" else "${v.distance} · ${v.stops}", live = recording)
            }
        }
        LegSheet(
            actions, trip, cards, legIndex.coerceIn(-1, cards.lastIndex),
            onStep = { legIndex = (legIndex + it).coerceIn(0, cards.lastIndex) },
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { sheetPx = it.height },
        )
    }
    val t = trip
    if (trackingOpen && t != null) TrackingLevelSheet(graph, t, appLevel ?: TrackingLevel.Balanced, status, onClose = { trackingOpen = false })
}

/** Travelled · Planned on a small floating pill. */
@Composable
private fun MapSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Bt.colors
    val shape = btControlShape()
    Row(Modifier.shadow(2.dp, shape).clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.clip(shape).background(if (on) c.chipOn else c.surface)
                    .semantics { this.selected = on }
                    .clickable(role = Role.Tab, onClickLabel = label) { onSelect(i) }
                    .heightIn(min = 40.dp).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Bt.text.label.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = if (on) c.onChipOn else c.muted)
            }
        }
    }
}

/** "18.4 km today · 3 stops" on a small chip, with a dot while the route is recording. */
@Composable
private fun MapNote(text: String, live: Boolean) {
    val c = Bt.colors
    val shape = btControlShape()
    Row(
        Modifier.heightIn(min = 32.dp).shadow(2.dp, shape).clip(shape).background(c.surface).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (live) Box(Modifier.size(8.dp).clip(CircleShape).background(c.route))
        Text(text, style = Bt.text.label, color = c.ink, maxLines = 1)
    }
}

/**
 * The sheet under the map: the leg reached by today and the route clip. How each leg travels is chosen in the plan and
 * in the video editor, not here (owner, 2026-11-08).
 */
@Composable
private fun LegSheet(
    actions: AppActions, trip: Trip?, cards: List<LegCard>, index: Int,
    onStep: (Int) -> Unit, modifier: Modifier,
) {
    val c = Bt.colors
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Column(
        modifier.fillMaxWidth().shadow(10.dp, shape).clip(shape).background(c.surface)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 14.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line))
        val card = cards.getOrNull(index)
        if (trip == null) return@Column
        if (card == null) {
            Text("No planned route yet", Modifier.padding(top = 12.dp), style = Bt.text.title)
            Text("Places in the plan with a location draw the route here.", Modifier.padding(top = 2.dp), style = Bt.text.sub)
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(card.eyebrow, Modifier.weight(1f))
            BtIconButton(BtIcons.Back, "Previous leg", { onStep(-1) }, size = 40.dp)
            BtIconButton(BtIcons.Forward, "Next leg", { onStep(1) }, size = 40.dp)
        }
        Text(
            card.title,
            Modifier.clip(btControlShape()).clickable(role = Role.Button, onClickLabel = "Open ${card.to}") {
                actions.place(trip.id, itemId = card.leg.key.toItemId)
            },
            style = Bt.text.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(card.note, Modifier.padding(top = 2.dp), style = Bt.text.sub, color = c.muted)
        BtButton("Play the route clip", onClick = { actions.clip(trip.id) }, icon = BtIcons.Route, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

/**
 * The trip's route tracking level (its own setting; the app default is in Me), as a sheet from the map; on a trip day
 * also what records now, with Start recording or Stop for today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackingLevelSheet(graph: AppGraph, trip: Trip, appLevel: TrackingLevel, status: TrackingStatus, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val level = TrackingPlan.levelFor(trip, appLevel)
    val today = LocalDate.now()
    val current = trip.isCurrent(today)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bt.colors.surface,
        contentColor = Bt.colors.ink,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("Route tracking", style = Bt.text.title)
            val start = trip.startDate
            if (!current && start != null) Text("Starts on ${Dates.dayLong(start)}.", Modifier.padding(top = 2.dp), style = Bt.text.sub)
            TrackingLevel.entries.forEach { l ->
                BtRadioRow(l.label, l.blurb, l == level, onClick = { scope.launch { graph.tracking.setTripLevel(trip.id, l) } })
            }
            if (current && level != TrackingLevel.Off && status.target.tripId == trip.id) {
                RecordingNow(graph, status, Modifier.padding(top = 8.dp))
                TrackingPermissionFlow(graph, level, Modifier.padding(top = 8.dp))
            }
            Text("For this trip. ${howItRecords()} Your route is saved on this phone. Manage cloud backups in Settings.", Modifier.padding(top = 8.dp), style = Bt.text.fine)
        }
    }
}
