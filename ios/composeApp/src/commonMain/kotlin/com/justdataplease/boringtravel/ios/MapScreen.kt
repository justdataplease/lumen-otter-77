package com.justdataplease.boringtravel.ios

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.geo.BBox
import com.justdataplease.boringtravel.geo.GeoPoint
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.tracking.RouteShape
import com.justdataplease.boringtravel.tracking.SpikeFilter
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtHeader
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtSegmented
import com.justdataplease.boringtravel.ui.components.EmptyState
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.map.LegCard
import com.justdataplease.boringtravel.ui.map.LegSheetModel
import com.justdataplease.boringtravel.ui.map.MapData
import com.justdataplease.boringtravel.ui.map.MapRoute
import com.justdataplease.boringtravel.ui.map.MapTap
import com.justdataplease.boringtravel.ui.map.TripMapModel
import com.justdataplease.boringtravel.ui.map.TripMapView
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.trips.HomeStats

/*
 * The maps, as the Android app's ui/map/MapScreen.kt and TripMapBoard.kt show them: the Map tab (Globe · Map; on Map the
 * trip happening now, or every trip's routes, stops and notes) and a trip's own map (Travelled · Planned, the map's look,
 * the distance chip and the leg sheet). Opening a map turns the trip's recorded points into stops first ([TrackProcessor]).
 */

/** The app's one [TrackProcessor] (stops from the recorded points), for the maps; null in previews without one. */
val LocalTrackProcessor = staticCompositionLocalOf<TrackProcessor?> { null }

/** What the map screens open: a trip on its Map tab, a plan item's or a stop's place page, a note. */
class MapActions(
    val openTripMap: (tripId: String) -> Unit = {},
    val openPlace: (tripId: String, itemId: String?, stopId: String?) -> Unit = { _, _, _ -> },
    val openNote: (tripId: String?, entryId: String) -> Unit = { _, _ -> },
    /** The trip video (the Android app's "Play the route clip"). */
    val clip: (tripId: String) -> Unit = {},
)

/** "Now" for a screen drawn on [today]: the clock when it is today, else midday of that day (previews and tests). */
fun nowOn(today: LocalDate): Long = if (today == localDateAt(nowMs(), null)) nowMs() else startOfDayMs(today, null) + 12 * 3_600_000L

/**
 * The Map tab (the Android app's ui/map/MapScreen.kt MapHome): Globe or Map, as last picked (the globe first). Map is
 * the trip on now (its trip map, travelled and planned), or every trip's routes when none is on; during a trip the
 * header's button switches between that trip and all trips. The globe's "Show on the map" opens Map on all trips,
 * framed on that country. [countryAtStart] and [expandedAtStart] open the globe with a country's sheet or Your world
 * pulled up (screenshots, tests).
 */
@Composable
fun MapHome(
    book: TripBook, settings: AppSettings, today: LocalDate, revision: Int, onChanged: () -> Unit, actions: MapActions,
    world: WorldNav = WorldNav(), countryAtStart: String? = null, expandedAtStart: Boolean = false,
) {
    val trips = remember(book, revision, settings.demoMode) { TripsHomeData.visibleTrips(book, settings.demoMode) }
    val current = remember(trips, today) { HomeStats.sections(trips, today).now }
    val mode = if (settings.mapView == MapView.Map) 1 else 0
    var allTrips by rememberSaveable { mutableStateOf(false) }
    var focus by remember { mutableStateOf<BBox?>(null) }
    var routesSummary by remember { mutableStateOf<String?>(null) }
    val source = LocalWorld.current
    val countries = rememberWorld(book, revision, settings.demoMode, today)
    val scope = rememberCoroutineScope()
    fun select(m: Int) {
        if (m == 0) focus = null
        settings.mapView = if (m == 1) MapView.Map else MapView.Globe
    }
    fun showRoutes(code: String) {
        scope.launch {
            focus = WorldFocus.box(countries, code)
            allTrips = true
            select(1)
        }
    }
    // "Show on the map" and country requests come from the globe and Your world.
    val request = source.mapRequest
    LaunchedEffect(request) {
        when (request) {
            is MapRequest.Routes -> { source.mapRequest = null; showRoutes(request.code) }
            is MapRequest.Country, is MapRequest.ContinentView -> select(0) // the globe takes it from here
            null -> Unit
        }
    }
    val showTrip = mode == 1 && current != null && !allTrips
    BtScreen {
        Column(Modifier.fillMaxSize()) {
            val eyebrow = when {
                mode == 0 -> countries.sinceYear?.let { "Since $it" }
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
                    mode == 0 -> WorldMode(
                        book, settings, today, revision, world, onShowRoutes = ::showRoutes,
                        countryAtStart = countryAtStart, expandedAtStart = expandedAtStart,
                    )
                    showTrip -> TripMapBoard(book, current.id, today, revision, settings, onChanged, actions)
                    else -> AllTripsMap(book, trips, settings.demoMode, today, revision, onChanged, actions, focus, onSummary = { routesSummary = it })
                }
            }
        }
    }
}

/** Every trip's route (cleaned, drawn coarser), its stops and the located notes; "N trips · X km" goes to the header. */
data class AllTripsView(val data: MapData, val summary: String?)

object AllTripsMapModel {
    fun build(book: TripBook, trips: List<Trip>, demoMode: Boolean): AllTripsView {
        val s = book.snapshot
        val ids = trips.map { it.id }.toSet()
        var meters = 0.0
        val routes = trips.mapNotNull { t ->
            val cleaned = SpikeFilter.filter(s.trackPoints.filter { it.tripId == t.id })
            meters += RouteShape.distanceM(cleaned)
            MapData.route(t.id, cleaned, MapData.ALL_TOLERANCE_M)
        }
        // A note or a stop outside trips is the traveller's own: not shown among the examples.
        fun shown(tripId: String?) = if (tripId == null) !demoMode else tripId in ids
        val data = MapData(
            routes = routes,
            stops = MapData.stops(s.stops.filter { shown(it.tripId) }),
            entries = MapData.entries(s.entries.filter { shown(it.tripId) }),
        )
        val summary = if (routes.isEmpty()) null else "${routes.size} ${if (routes.size == 1) "trip" else "trips"} · ${RouteShape.formatDistance(meters)}"
        return AllTripsView(data, summary)
    }
}

@Composable
private fun AllTripsMap(
    book: TripBook, trips: List<Trip>, demoMode: Boolean, today: LocalDate, revision: Int, onChanged: () -> Unit,
    actions: MapActions, focus: BBox?, onSummary: (String?) -> Unit,
) {
    val processor = LocalTrackProcessor.current
    val tracking = LocalTracking.current
    // The traveller's own trip on now gets its stops first (the examples record nothing), with what was just recorded.
    LaunchedEffect(Unit) {
        tracking?.flush()
        val own = HomeStats.sections(trips.filter { !it.isDemo }, today).now
        if (own != null && processor?.process(own.id) == true) onChanged()
    }
    val view = remember(book, revision, trips, demoMode) { AllTripsMapModel.build(book, trips, demoMode) }
    var fitTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(view.summary) { onSummary(view.summary) }
    Box(Modifier.fillMaxSize()) {
        BtMap(
            data = view.data,
            modifier = Modifier.fillMaxSize(),
            fitKey = fitTick.takeIf { it > 0 },
            focus = focus,
            onTap = { tap ->
                when (tap) {
                    is MapTap.Trip -> actions.openTripMap(tap.tripId)
                    is MapTap.StopMark -> tap.tripId?.let { actions.openPlace(it, null, tap.stopId) }
                    is MapTap.Entry -> actions.openNote(book.entry(tap.entryId)?.tripId, tap.entryId)
                    is MapTap.Plan -> Unit
                }
            },
        )
        if (!view.data.isEmpty) {
            BtIconButton(BtIcons.Zoom, "Show everything", { fitTick++ }, Modifier.align(Alignment.TopEnd).padding(12.dp))
        } else {
            BtCard(Modifier.align(Alignment.Center).padding(24.dp)) {
                EmptyState("No routes yet", "Your trips' routes, stops and places show up here.")
            }
        }
    }
}

/**
 * One trip's map as the trip view model builds it (the Android app's TripMapBoard): the travelled route (or, without
 * one, the saved stops joined in time order), stops, notes and "now", the plan's places, and the planned route through
 * them in plan order.
 */
object TripMapBoardModel {
    fun build(book: TripBook, trip: Trip, now: Long): TripMapView {
        val s = book.snapshot
        val points = s.trackPoints.filter { it.tripId == trip.id }
        val stops = s.stops.filter { it.tripId == trip.id }
        val items = book.items(trip.id)
        val entries = book.entries(trip.id)
        val travelled = TripMapModel.build(trip, points, stops, items, entries, now, null)
        val planned = RouteLegs.waypoints(trip, items).map { GeoPoint(it.lat, it.lng) }
        val ordered = RouteLegs.orderedStops(trip, stops)
        val routes = travelled.data.routes.ifEmpty {
            if (ordered.size < 2) emptyList()
            else listOf(MapRoute(trip.id, listOf(RouteShape.Piece(ordered.map { GeoPoint(it.lat, it.lng) }, jump = false))))
        }
        val today = localDateAt(now, null)
        val me = travelled.data.me ?: ordered.lastOrNull()
            ?.takeIf { trip.isCurrent(today) && localDateAt(it.arrivedAt, null) == today }
            ?.let { GeoPoint(it.lat, it.lng) }
        return travelled.copy(data = travelled.data.copy(routes = routes, planned = planned, me = me))
    }

    /** Travelled: the route, stops, notes and "now", with the plan dashed underneath. Planned: the plan alone. */
    fun shown(view: TripMapView, planned: Boolean): MapData =
        if (!planned) view.data.copy(plans = emptyList()) else MapData(plans = view.data.plans, planned = view.data.planned)
}

/**
 * One trip's map: Travelled · Planned on a pill, the map's look (Street · Terrain · Earth, kept for every trip map),
 * "Show the whole trip", the distance chip, and underneath the leg the trip has reached ("Day 13 · Leg 9 of 21",
 * "El Calafate → El Chaltén", about how long) with the legs before and after it. A tap on a stop or a planned place
 * opens its place page, on a note pin the note. Route tracking (a dated trip not yet over) opens the trip's level and
 * what records now; the distance chip has a dot while the route records. Left out until its round: the trip video.
 */
@Composable
fun TripMapBoard(
    book: TripBook,
    tripId: String,
    today: LocalDate,
    revision: Int,
    settings: AppSettings,
    onChanged: () -> Unit,
    actions: MapActions,
    lead: (@Composable () -> Unit)? = null,
    plannedAtStart: Boolean = false,
    stylesAtStart: Boolean = false,
    trackingAtStart: Boolean = false,
) {
    val trip = remember(book, tripId, revision) { book.trip(tripId) } ?: return
    val processor = LocalTrackProcessor.current
    val tracking = LocalTracking.current
    LaunchedEffect(tripId) {
        // What was just recorded goes into the store first, then becomes stops.
        tracking?.flush()
        if (processor?.process(tripId) == true) onChanged()
    }
    var trackingOpen by rememberSaveable { mutableStateOf(trackingAtStart) }
    var mode by rememberSaveable(tripId) { mutableIntStateOf(if (plannedAtStart) 1 else 0) }
    val now = remember(today) { nowOn(today) }
    val view = remember(book, trip, revision, now) { TripMapBoardModel.build(book, trip, now) }
    val hasPoints = remember(book, tripId, revision) { book.snapshot.trackPoints.any { it.tripId == tripId } }
    val cards = remember(trip, revision) { LegSheetModel.cards(trip, book.items(tripId)) }
    var legIndex by rememberSaveable(tripId) { mutableIntStateOf(-1) }
    LaunchedEffect(cards.size) { if (legIndex !in cards.indices) legIndex = LegSheetModel.currentIndex(cards, today) }
    var fitTick by remember { mutableIntStateOf(0) }
    var sheetPx by remember { mutableIntStateOf(0) }
    var stylesOpen by rememberSaveable { mutableStateOf(stylesAtStart) }
    val sheetDp = with(LocalDensity.current) { sheetPx.toDp() }
    val shown = TripMapBoardModel.shown(view, planned = mode == 1)
    LaunchedEffect(mode) { fitTick++ }

    Box(Modifier.fillMaxSize()) {
        BtMap(
            data = shown,
            modifier = Modifier.fillMaxSize(),
            showStopNames = true,
            contentPadding = PaddingValues(bottom = sheetDp, top = 56.dp),
            fitKey = fitTick.takeIf { it > 0 },
            mapStyle = settings.tripMapStyle,
            onTap = { tap ->
                when (tap) {
                    is MapTap.Entry -> actions.openNote(tripId, tap.entryId)
                    is MapTap.StopMark -> actions.openPlace(tripId, null, tap.stopId)
                    is MapTap.Plan -> actions.openPlace(tripId, tap.itemId, null)
                    is MapTap.Trip -> Unit
                }
            },
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                lead?.invoke()
                MapSegmented(listOf("Travelled", "Planned"), mode) { mode = it }
                Spacer(Modifier.weight(1f))
                if (tracking != null && trip.startDate != null && trip.endDate != null && !trip.isPast(today)) {
                    BtIconButton(BtIcons.MyLocation, "Route tracking", { trackingOpen = true })
                }
                BtIconButton(BtIcons.Map, "Map style", { stylesOpen = !stylesOpen }, filled = stylesOpen)
                if (!shown.isEmpty) BtIconButton(BtIcons.Zoom, "Show the whole trip", { fitTick++ })
            }
            if (stylesOpen) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                MapSegmented(MapStyle.tripMap.map { it.label }, MapStyle.tripMap.indexOf(settings.tripMapStyle)) { i ->
                    settings.tripMapStyle = MapStyle.tripMap[i]
                }
            }
            if (mode == 0 && view.hasTrack) {
                // Without recorded points there is no distance to tell, only the stops; a dot while this trip records.
                val status = tracking?.status
                val recording = status != null && status.target.tripId == tripId && status.recording && !status.stopped
                MapNote(if (!hasPoints) "${view.stops}${if (view.current) " today" else ""}" else "${view.distance} · ${view.stops}", live = recording)
            }
        }
        LegSheet(
            trip, cards, legIndex.coerceIn(-1, cards.lastIndex),
            onStep = { legIndex = (legIndex + it).coerceIn(0, cards.lastIndex) },
            onOpen = { card -> actions.openPlace(tripId, card.leg.key.toItemId, null) },
            onClip = { actions.clip(tripId) },
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { sheetPx = it.height },
        )
    }
    if (trackingOpen && tracking != null) TrackingLevelSheet(tracking, trip, settings.trackingLevel, today, onClose = { trackingOpen = false })
}

/** Travelled · Planned (and Street · Terrain · Earth) on a small floating pill. */
@Composable
fun MapSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
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
 * The sheet under the map: the leg reached by today, the legs before and after it, and the route clip. How each leg
 * travels is chosen in the plan, not here (as on Android).
 */
@Composable
private fun LegSheet(
    trip: Trip, cards: List<LegCard>, index: Int, onStep: (Int) -> Unit, onOpen: (LegCard) -> Unit, onClip: () -> Unit,
    modifier: Modifier,
) {
    val c = Bt.colors
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Column(
        modifier.fillMaxWidth().shadow(10.dp, shape).clip(shape).background(c.surface)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 14.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line))
        val card = cards.getOrNull(index)
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
            Modifier.clip(btControlShape()).clickable(role = Role.Button, onClickLabel = "Open ${card.to}") { onOpen(card) },
            style = Bt.text.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(card.note, Modifier.padding(top = 2.dp), style = Bt.text.sub, color = c.muted)
        BtButton("Play the route clip", onClick = onClip, icon = BtIcons.Route, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}
