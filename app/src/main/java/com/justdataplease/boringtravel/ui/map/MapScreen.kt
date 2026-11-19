package com.justdataplease.boringtravel.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.settings.MapView
import com.justdataplease.boringtravel.geo.BBox
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.tracking.RouteShape
import com.justdataplease.boringtravel.tracking.SpikeFilter
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtHeader
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.BtRadioRow
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtSegmented
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.EmptyState
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.trips.HomeStats
import com.justdataplease.boringtravel.ui.world.WorldFocus
import com.justdataplease.boringtravel.ui.world.WorldMode
import com.justdataplease.boringtravel.world.MapRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The map: the Map tab ([tripId] null) or one trip (inside TripScreen's Map tab). The Map tab is Globe · Map: our own
 * globe (ui/world), or the trip happening now (travelled and planned, the leg sheet; ui/map/TripMapBoard) and every
 * trip on OpenFreeMap tiles with its cleaned route, stops and entries. Opening a map also processes the trip's points
 * (stops, countries).
 */
@Composable
fun MapScreen(graph: AppGraph, actions: AppActions, tripId: String?) {
    if (tripId == null) MapHome(graph, actions) else TripMapBoard(graph, actions, tripId, edgeToEdge = false)
}

/**
 * The Map tab: Globe or Map, as the person last picked (the globe first, as before). Map is the trip happening now
 * (travelled and planned, the leg sheet), or every trip's route when no trip is on; during a trip the header button
 * switches between that trip and all trips.
 */
@Composable
private fun MapHome(graph: AppGraph, actions: AppActions) {
    val trips by graph.trips.observeTrips().collectAsStateWithLifecycle(initialValue = null)
    val today = remember { LocalDate.now() }
    // The same "now" as the Trips home: the current trip that started first.
    val current = remember(trips, today) { trips?.let { HomeStats.sections(it, today).now } }
    val savedView by graph.settings.mapView.collectAsStateWithLifecycle(initialValue = null)
    var picked by rememberSaveable { mutableStateOf<Int?>(null) }
    val mode = picked ?: savedView?.let { if (it == MapView.Map) 1 else 0 } ?: return
    var allTrips by rememberSaveable { mutableStateOf(false) }
    var focus by remember { mutableStateOf<BBox?>(null) }
    var routesSummary by remember { mutableStateOf<String?>(null) }
    val world by graph.world.world.collectAsStateWithLifecycle()
    val request by graph.world.mapRequest.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun select(m: Int) {
        picked = m
        if (m == 0) focus = null
        scope.launch { graph.settings.setMapView(if (m == 1) MapView.Map else MapView.Globe) }
    }
    fun showRoutes(code: String) {
        scope.launch {
            focus = WorldFocus.box(graph, world, code)
            allTrips = true
            select(1)
        }
    }
    // "Show on the map" and country requests come from the globe and Your world.
    LaunchedEffect(request) {
        when (val r = request) {
            is MapRequest.Routes -> { graph.world.mapRequest.value = null; showRoutes(r.code) }
            is MapRequest.Country, is MapRequest.ContinentView -> select(0) // the globe takes it from here
            null -> Unit
        }
    }
    val showTrip = mode == 1 && current != null && !allTrips
    BtScreen {
        Column(Modifier.fillMaxSize()) {
            val eyebrow = when {
                mode == 0 -> world?.sinceYear?.let { "Since $it" }
                showTrip -> current.title
                else -> routesSummary
            }
            BtHeader("Map", eyebrow = eyebrow) {
                if (mode == 1 && current != null) {
                    if (allTrips) BtIconButton(BtIcons.Route, "Today's trip", { allTrips = false; focus = null })
                    else BtIconButton(BtIcons.World, "All trips", { allTrips = true })
                }
            }
            BtSegmented(
                listOf("Globe", "Map"), mode,
                onSelect = ::select,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    mode == 0 -> WorldMode(graph, actions, onShowRoutes = ::showRoutes)
                    showTrip -> TripMapBoard(graph, actions, current.id, edgeToEdge = false)
                    else -> AllTripsMap(graph, actions, focus, onSummary = { routesSummary = it })
                }
            }
        }
    }
}

private data class AllView(val data: MapData, val summary: String?)

@Composable
private fun AllTripsMap(graph: AppGraph, actions: AppActions, focus: BBox?, onSummary: (String?) -> Unit) {
    val trips by graph.trips.observeTrips().collectAsStateWithLifecycle(initialValue = null)
    val stops by graph.tracks.observeAllStops().collectAsStateWithLifecycle(initialValue = emptyList())
    val entries by graph.journal.observeEntries().collectAsStateWithLifecycle(initialValue = emptyList())
    val latest by graph.tracks.observeLatest().collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(Unit) { graph.trips.ownCurrentOn(LocalDate.now())?.let { graph.tracking.processor.process(it.id) } }
    val view by produceState<AllView?>(null, trips, stops, entries, latest?.id) {
        val list = trips ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val ids = list.map { it.id }.toSet()
            var meters = 0.0
            val routes = list.mapNotNull { t ->
                val cleaned = SpikeFilter.filter(graph.tracks.forTrip(t.id))
                meters += RouteShape.distanceM(cleaned)
                MapData.route(t.id, cleaned, MapData.ALL_TOLERANCE_M)
            }
            val data = MapData(
                routes = routes,
                stops = MapData.stops(stops.filter { it.tripId == null || it.tripId in ids }),
                entries = MapData.entries(entries),
            )
            val summary = if (routes.isEmpty()) null
            else "${routes.size} ${if (routes.size == 1) "trip" else "trips"} · ${RouteShape.formatDistance(meters)}"
            AllView(data, summary)
        }
    }
    var fitTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(view?.summary) { onSummary(view?.summary) }
    Box(Modifier.fillMaxSize()) {
        BtMap(
            data = view?.data ?: MapData(),
            modifier = Modifier.fillMaxSize(),
            fitKey = fitTick.takeIf { it > 0 },
            focus = focus,
            onTap = { tap ->
                when (tap) {
                    is MapTap.Trip -> actions.openTrip(tap.tripId, "map")
                    is MapTap.StopMark -> tap.tripId?.let { actions.place(it, stopId = tap.stopId) }
                    is MapTap.Entry -> actions.openEntry(tap.entryId)
                    is MapTap.Plan -> Unit
                }
            },
        )
        if (view?.data?.isEmpty == false) {
            BtIconButton(BtIcons.Zoom, "Show everything", { fitTick++ }, Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
        if (view?.data?.isEmpty == true) {
            BtCard(Modifier.align(Alignment.Center).padding(24.dp)) {
                EmptyState("No routes yet", "Your trips' routes, stops and places show up here.")
            }
        }
    }
}

/**
 * Me › Route tracking: what records now (with Start recording or Stop for today), the default level for trips, and
 * what Android still needs.
 */
@Composable
fun TrackingSettingsScreen(graph: AppGraph, actions: AppActions) {
    val level by graph.settings.trackingLevel.collectAsStateWithLifecycle(initialValue = null)
    val status by graph.tracking.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { graph.tracking.refresh(force = false) }
    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Route tracking", onBack = actions::back)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BtCard(Modifier.fillMaxWidth()) {
                    Eyebrow("Now")
                    RecordingNow(graph, status, Modifier.padding(top = 4.dp), appLevel = level)
                }
                val l = level
                if (l != null) {
                    BtCard(Modifier.fillMaxWidth()) {
                        Eyebrow("For every trip")
                        TrackingLevel.entries.forEach { option ->
                            BtRadioRow(option.label, option.blurb, option == l, onClick = { scope.launch { graph.settings.setTrackingLevel(option) } })
                        }
                        Text(
                            "Records where you go during a trip's dates, so your map and clips draw themselves. ${howItRecords()} Change a trip's level on its map. Your route is saved on this phone; cloud backups are managed separately.",
                            Modifier.padding(top = 8.dp),
                            style = Bt.text.fine,
                        )
                    }
                    val needs = status.target.level.takeIf { status.target.tripId != null } ?: l
                    if (needs != TrackingLevel.Off) {
                        BtCard(Modifier.fillMaxWidth()) {
                            TrackingPermissionFlow(graph, needs)
                        }
                    }
                }
            }
        }
    }
}
