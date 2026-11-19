package com.justdataplease.boringtravel.ui.world

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.geo.Continent
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btCardShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.world.MapRequest
import com.justdataplease.boringtravel.world.World
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.justdataplease.boringtravel.globe.GlobeGeometry
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.BtBackButton

/**
 * Your world (design boards "World · globe and flags"): back, "Your world" and share; the globe with flag pins; the
 * count, "1% of the world · 2 continents"; the flags collected with their names (the next trip's countries dashed,
 * "· next"); Add countries and Share. Continents, want to go and the travel totals follow below. Countries open on the
 * Map tab's globe.
 */
@Composable
fun YourWorldScreen(graph: AppGraph, actions: AppActions) {
    val world by graph.world.world.collectAsState()
    val geometry by produceState<GlobeGeometry?>(null) { value = graph.world.geometry() }
    // A little smaller than the Map tab's globe so the whole round earth fits, as on the board.
    val globe = remember { GlobeState(homeZoom = 0.92f) }
    val home = remember(world?.visitedCodes) { worldHome(world) }
    LaunchedEffect(home) { globe.setHome(home.first, home.second) }
    val onCountry: (String) -> Unit = { graph.world.mapRequest.value = MapRequest.Country(it); actions.mapTab() }
    BtScreen {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BtBackButton(actions::back)
                Text(Bt.text.display("Your world"), Modifier.weight(1f), style = Bt.text.h2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BtIconButton(BtIcons.Share, "Share", actions::shareWorld)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                GlobeView(
                    geometry = geometry,
                    state = globe,
                    visited = world?.visitedCodes.orEmpty(),
                    dashed = world?.let { it.planned + it.wished }.orEmpty(),
                    pins = remember(world) { worldPins(world) },
                    modifier = Modifier.size(300.dp),
                    description = "Globe with your countries",
                    onPin = onCountry,
                )
                val n = world?.countries ?: 0
                val continents = world?.continents?.count { it.value > 0 } ?: 0
                Text(
                    Bt.text.display(if (n == 1) "1 country" else "$n countries"),
                    Modifier.padding(top = 8.dp), style = Bt.text.h1, textAlign = TextAlign.Center,
                )
                Text(
                    "${world?.worldPercent ?: 0}% of the world · " + (if (continents == 1) "1 continent" else "$continents continents"),
                    Modifier.padding(top = 4.dp), style = Bt.text.sub, textAlign = TextAlign.Center,
                )
                FlagsRow(world, onCountry, Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BtGhostButton("Add countries", onClick = actions::placesBeen, icon = BtIcons.Add)
                    BtButton("Share", onClick = actions::shareWorld, icon = BtIcons.Share)
                }
                if (n == 0) {
                    Text(
                        "Countries fill in from your trips' stops, entries and photos. Add the places you have been before.",
                        Modifier.padding(horizontal = 28.dp, vertical = 12.dp), style = Bt.text.sub, textAlign = TextAlign.Center,
                    )
                }
                WorldDetails(world, onCountry, onContinent = { graph.world.mapRequest.value = MapRequest.ContinentView(it); actions.mapTab() })
            }
        }
    }
}

/**
 * Visited flags with their names, then the countries still to come dashed ("Chile · next"): the next trip's and the
 * ones planned in any trip but not visited yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlagsRow(world: World?, onCountry: (String) -> Unit, modifier: Modifier = Modifier) {
    val visited = world?.visited.orEmpty().map { it.code }
    val next = (world?.nextCodes.orEmpty() + world?.planned.orEmpty()).distinct().filter { it !in visited }
    val items = visited.map { it to false } + next.map { it to true }
    if (items.isEmpty()) return
    FlowRow(
        modifier, horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (code, next) ->
            Row(
                Modifier.clip(btCardShape()).clickable(role = Role.Button, onClickLabel = CountryInfo.name(code)) { onCountry(code) }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FlagSticker(code, 28.dp, dashed = next)
                Text(
                    if (next) "${CountryInfo.name(code)} · next" else CountryInfo.name(code),
                    style = Bt.text.sub, color = Bt.colors.muted, maxLines = 1,
                )
            }
        }
    }
}

/** Continents with small bars, want to go, and trips · days away · km: the rest of Your world. */
@Composable
private fun WorldDetails(world: World?, onCountry: (String) -> Unit, onContinent: (Continent) -> Unit) {
    val n = world?.countries ?: 0
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        val rows = world?.continentRows().orEmpty()
        if (rows.isNotEmpty()) {
            Section("Continents")
            val total = n.coerceAtLeast(1)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                rows.forEach { (k, count) -> ContinentBar(k, count, count.toFloat() / total, onClick = { onContinent(k) }) }
            }
        }
        WantToGo(world, onCountry)
        TravelTotals(world, Modifier.padding(horizontal = 20.dp).padding(top = 18.dp))
    }
}

@Composable
private fun WantToGo(world: World?, onCountry: (String) -> Unit) {
    val wished = world?.wished.orEmpty().toList()
    if (wished.isEmpty()) return
    Section("Want to go")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        wished.forEach { code ->
            Column(
                Modifier.width(64.dp).clickable(role = Role.Button) { onCountry(code) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FlagSticker(code, 40.dp, dashed = true)
                Text(CountryInfo.name(code), Modifier.padding(top = 4.dp), style = Bt.text.fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Countries and % of the world, continents with small bars, flags collected (the next trip's country dashed), want
 * to go, trips · days away · km, and Share. With no countries yet: a plain empty state, still shareable.
 */
@Composable
fun YourWorldContent(
    graph: AppGraph,
    actions: AppActions,
    world: World?,
    onCountry: (String) -> Unit,
    onContinent: (Continent) -> Unit,
    showHeader: Boolean,
) {
    val n = world?.countries ?: 0
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        if (showHeader) {
            Eyebrow(world?.sinceYear?.let { "Since $it" } ?: "Your countries", Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(n.toString(), plural(n, "country", "countries"), highlight = true, modifier = Modifier.weight(1f))
            Tile("${world?.worldPercent ?: 0}%", "of the world", highlight = false, modifier = Modifier.weight(1f))
        }

        if (n == 0) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No countries yet", style = Bt.text.title, textAlign = TextAlign.Center)
                Text(
                    "They fill in from your trips' stops, entries and photos. Add the places you've been before.",
                    Modifier.padding(top = 4.dp), style = Bt.text.sub, textAlign = TextAlign.Center,
                )
            }
        }

        val rows = world?.continentRows().orEmpty()
        if (rows.isNotEmpty()) {
            Section("Continents")
            val total = n.coerceAtLeast(1)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                rows.forEach { (k, count) -> ContinentBar(k, count, count.toFloat() / total, onClick = { onContinent(k) }) }
            }
        }

        if (n > 0 || world?.nextCodes?.isNotEmpty() == true) {
            Section("Flags collected")
            FlagGrid(world, onCountry)
        }

        val wished = world?.wished.orEmpty().toList()
        if (wished.isNotEmpty()) {
            Section("Want to go")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                wished.forEach { code ->
                    Column(
                        Modifier.width(64.dp).clickable(role = Role.Button) { onCountry(code) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FlagSticker(code, 40.dp, dashed = true)
                        Text(CountryInfo.name(code), Modifier.padding(top = 4.dp), style = Bt.text.fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        TravelTotals(world, Modifier.padding(horizontal = 20.dp).padding(top = 18.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BtButton("Share", onClick = actions::shareWorld, icon = BtIcons.Share, modifier = Modifier.weight(1f))
            BtGhostButton("Places I've been", onClick = actions::placesBeen, icon = BtIcons.AddPlace, modifier = Modifier.weight(1.3f))
        }
    }
}

@Composable
private fun Section(title: String) {
    Eyebrow(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp))
}

/** A big number tile; the countries tile gets a highlighter stroke in the notebooks, the accent elsewhere. */
@Composable
private fun Tile(value: String, label: String, highlight: Boolean, modifier: Modifier = Modifier) {
    val c = Bt.colors
    val notebook = Bt.tokens.look.isNotebook
    val shape = btCardShape()
    Box(modifier) {
        if (notebook) Box(Modifier.matchParentSize().offset(3.dp, 4.dp).clip(shape).background(c.ink.copy(alpha = 0.07f)))
        Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(12.dp)) {
            Text(
                Bt.text.display(value),
                Modifier.then(
                    if (highlight) Modifier.drawBehind {
                        drawRect(c.accent, Offset(0f, size.height * 0.55f), Size(size.width, size.height * 0.4f))
                    } else Modifier,
                ),
                style = Bt.text.h1.copy(fontSize = Bt.text.h1.fontSize * 0.95f),
            )
            Text(label, style = Bt.text.sub)
        }
    }
}

@Composable
private fun ContinentBar(continent: Continent, count: Int, fraction: Float, onClick: () -> Unit) {
    val c = Bt.colors
    val notebook = Bt.tokens.look.isNotebook
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Show ${continent.label}", onClick = onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(continent.label, Modifier.width(104.dp), style = Bt.text.body.copy(fontSize = 13.sp, lineHeight = 16.sp), maxLines = 1)
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(3.dp)).background(c.chip)) {
            if (count > 0) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0.04f, 1f)).clip(RoundedCornerShape(3.dp)).background(c.accent)
                        .then(if (notebook) Modifier.border(1.dp, c.ink, RoundedCornerShape(3.dp)) else Modifier),
                )
            }
        }
        Text(count.toString(), Modifier.width(22.dp), style = Bt.text.mono.copy(fontSize = 12.sp), color = c.ink, textAlign = TextAlign.End)
    }
}

/** Visited flags in the order they were first visited, then the next trip's countries, dashed. */
@Composable
private fun FlagGrid(world: World?, onCountry: (String) -> Unit) {
    val items = world?.visited.orEmpty().map { it.code to false } + world?.nextCodes.orEmpty().map { it to true }
    val cols = 4
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (code, next) ->
                    Column(
                        Modifier.weight(1f).clickable(role = Role.Button) { onCountry(code) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FlagSticker(code, 40.dp, dashed = next)
                        Text(
                            if (next) "${CountryInfo.name(code)} · next" else CountryInfo.name(code),
                            Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                            style = Bt.text.fine.copy(fontSize = 11.sp),
                            color = if (next) Bt.colors.muted else Bt.colors.ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        )
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
