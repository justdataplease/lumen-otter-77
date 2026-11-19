package com.justdataplease.boringtravel.ui.world

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.geo.BBox
import com.justdataplease.boringtravel.geo.Continent
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.globe.GlobeGeometry
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.world.MapRequest
import com.justdataplease.boringtravel.world.World
import kotlinx.coroutines.launch

/** Zoom for a picked country and for a continent. */
private const val COUNTRY_ZOOM = 1.9f
private const val CONTINENT_ZOOM = 1.2f

/**
 * The Map tab's World view: the globe, and a sheet pulled up from below. Its peek is the count line and the stats
 * strip; pulled up, it is Your world. Tapping a flag (or any country) opens that country's sheet; its continent opens
 * the continent's. Everything flies the globe there first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldMode(graph: AppGraph, actions: AppActions, onShowRoutes: (String) -> Unit) {
    val world by graph.world.world.collectAsState()
    val geometry by produceState<GlobeGeometry?>(null) { value = graph.world.geometry() }
    val state = remember { GlobeState() }
    val scope = rememberCoroutineScope()
    val c = Bt.colors
    val notebook = Bt.tokens.look.isNotebook
    val density = LocalDensity.current

    var country by remember { mutableStateOf<String?>(null) }
    var continent by remember { mutableStateOf<Continent?>(null) }

    val home = remember(world?.visitedCodes) { worldHome(world) }
    LaunchedEffect(home) { state.setHome(home.first, home.second) }

    fun openCountry(code: String) {
        continent = null
        country = code
        val (lat, lng) = CountryInfo.labelPoint(code) ?: world?.byCode?.get(code)?.ownCenter?.let { it.lat to it.lng } ?: return
        state.flyTo(lat, lng, maxOf(state.zoom, COUNTRY_ZOOM))
    }

    fun openContinent(k: Continent) {
        country = null
        continent = k
        state.flyTo(k.centerLat, k.centerLng, CONTINENT_ZOOM)
    }

    val request by graph.world.mapRequest.collectAsState()
    LaunchedEffect(request) {
        when (val r = request) {
            is MapRequest.Country -> { graph.world.mapRequest.value = null; openCountry(r.code) }
            is MapRequest.ContinentView -> { graph.world.mapRequest.value = null; openContinent(r.continent) }
            else -> Unit
        }
    }

    val scaffold = rememberBottomSheetScaffoldState()
    var peekPx by remember { mutableIntStateOf(0) }
    val peekDp = with(density) { peekPx.toDp() }.coerceAtLeast(96.dp)
    val sheetShape = RoundedCornerShape(topStart = if (notebook) 10.dp else 22.dp, topEnd = if (notebook) 10.dp else 22.dp)
    val expanded = scaffold.bottomSheetState.targetValue == SheetValue.Expanded
    val sheetOpen = country != null || continent != null
    // With a country or continent sheet open, the globe moves up into the space above it.
    val insetPx by animateFloatAsState(
        targetValue = with(density) { (if (sheetOpen) 360.dp else peekDp).toPx() },
        label = "globe-inset",
    )

    // Clipped: the part of the sheet below the screen's bottom edge must not show around the floating bottom bars.
    BottomSheetScaffold(
        modifier = Modifier.clipToBounds(),
        scaffoldState = scaffold,
        sheetPeekHeight = peekDp,
        sheetShape = sheetShape,
        sheetContainerColor = if (notebook) c.bg else c.surface,
        sheetContentColor = c.ink,
        sheetShadowElevation = 10.dp,
        sheetTonalElevation = 0.dp,
        sheetDragHandle = null,
        containerColor = Color.Transparent,
        sheetContent = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface),
            ) {
                Column(Modifier.fillMaxWidth().onSizeChanged { peekPx = it.height }) {
                    if (notebook) BtDivider()
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 8.dp)
                            .size(38.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line),
                    )
                    Text(
                        countLine(world),
                        Modifier.padding(horizontal = 20.dp),
                        style = Bt.text.title,
                    )
                    WorldStatsStrip(
                        world,
                        Modifier.padding(horizontal = 20.dp).padding(top = 6.dp),
                        onClick = { scope.launch { scaffold.bottomSheetState.expand() } },
                    )
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    YourWorldContent(
                        graph, actions, world,
                        onCountry = ::openCountry,
                        onContinent = { k -> scope.launch { scaffold.bottomSheetState.partialExpand() }; openContinent(k) },
                        showHeader = false,
                    )
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            GlobeView(
                geometry = geometry,
                state = state,
                visited = world?.visitedCodes.orEmpty(),
                dashed = world?.let { it.planned + it.wished }.orEmpty(),
                pins = remember(world) { worldPins(world) },
                modifier = Modifier.fillMaxSize(),
                spin = !sheetOpen && !expanded,
                bottomInsetPx = insetPx,
                description = globeDescription(world),
                onPin = ::openCountry,
                onGlobeTap = { lat, lng ->
                    scope.launch {
                        val code = CountryInfo.key(graph.world.countryAt(lat, lng) ?: graph.world.countryAt(lat, lng, 60_000.0))
                        if (code != null) openCountry(code)
                    }
                },
            )
            GlobeChip(
                "Places I've been", onClick = actions::placesBeen,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 6.dp),
                icon = BtIcons.AddPlace,
            )
            val w = world
            val nudge: Pair<String, (() -> Unit)?>? = when {
                w == null -> null
                !w.hasTrips && w.countries == 0 -> "No trips yet · Plan one" to actions::newTrip
                w.countries == 0 -> "Countries fill in as you travel" to actions::placesBeen
                !state.interacted -> "Drag to spin · tap a flag" to null
                else -> null
            }
            AnimatedVisibility(
                visible = nudge != null && !sheetOpen,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = peekDp + 14.dp),
            ) {
                // Keep the last text while it fades out.
                val last = remember { arrayOfNulls<Pair<String, (() -> Unit)?>>(1) }
                if (nudge != null) last[0] = nudge
                last[0]?.let { (text, onClick) -> GlobeChip(text, onClick) }
            }
        }
    }

    country?.let { code ->
        CountrySheet(
            graph, actions, world, code,
            onDismiss = { country = null },
            onContinent = ::openContinent,
            onShowRoutes = { country = null; onShowRoutes(code) },
        )
    }
    continent?.let { k ->
        ContinentSheet(
            world, k,
            onDismiss = { continent = null },
            onCountry = ::openCountry,
        )
    }
}

/** What TalkBack says for the globe. */
private fun globeDescription(world: World?): String {
    val n = world?.countries ?: 0
    if (n == 0) return "Globe. No countries yet."
    val names = world!!.visited.joinToString(", ") { CountryInfo.name(it.code) }
    return "Globe. ${countLine(world)}: $names."
}

/** A small floating pill over the globe. */
@Composable
fun GlobeChip(text: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Bt.colors
    val shape = btControlShape()
    Row(
        modifier
            .height(48.dp)
            .shadow(4.dp, shape)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = c.ink)
        Text(text, style = Bt.text.label, color = if (onClick != null) c.ink else c.muted, maxLines = 1)
    }
}

/** Where "Show on the map" frames the Routes map: the country (its main outline) around the traveller's own places. */
object WorldFocus {
    suspend fun box(graph: AppGraph, world: World?, code: String): BBox? {
        val own = world?.byCode?.get(code)?.ownBox
        val label = CountryInfo.labelPoint(code)
        val boxes = graph.world.countries()?.outlines?.filter { CountryInfo.key(it.code) == code }?.flatMap { it.boxes }.orEmpty()
        val main = label?.let { (lat, lng) -> boxes.firstOrNull { it.contains(lat, lng) } }
            ?: boxes.maxByOrNull { (it.maxLat - it.minLat) * (it.maxLng - it.minLng) }
        return when {
            own != null && main != null -> own.union(main)
            main != null -> main
            own != null -> own.expand(30_000.0)
            label != null -> BBox(label.first, label.second, label.first, label.second).expand(150_000.0)
            else -> null
        }
    }
}
