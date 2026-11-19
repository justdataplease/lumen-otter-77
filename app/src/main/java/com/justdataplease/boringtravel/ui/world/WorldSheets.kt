package com.justdataplease.boringtravel.ui.world

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.geo.Continent
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.MediaImage
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import com.justdataplease.boringtravel.world.CountryStat
import com.justdataplease.boringtravel.world.World
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The sheet's page: paper in the notebooks, the surface elsewhere; a grab bar on top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorldSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Bt.colors
    val notebook = Bt.tokens.look.isNotebook
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (notebook) c.bg else c.surface,
        contentColor = c.ink,
        shape = RoundedCornerShape(topStart = if (notebook) 12.dp else 22.dp, topEnd = if (notebook) 12.dp else 22.dp),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .navigationBarsPadding(),
            content = content,
        )
    }
}

@Composable
private fun GrabBar(modifier: Modifier = Modifier) {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp).then(modifier), contentAlignment = Alignment.Center) {
        Box(Modifier.size(38.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Bt.colors.line))
    }
}

/**
 * One country: your best photo there, flag and name, continent (opens the continent), trips · days · places, the trips
 * (there and planned), and what to do: show it on the map, "I've been here", plan a trip, want to go.
 */
@Composable
fun CountrySheet(
    graph: AppGraph,
    actions: AppActions,
    world: World?,
    code: String,
    onDismiss: () -> Unit,
    onContinent: (Continent) -> Unit,
    onShowRoutes: () -> Unit,
) {
    val c = Bt.colors
    val stat = world?.byCode?.get(code)
    val visited = stat?.visited == true
    val wished = world?.wished?.contains(code) == true
    val continent = CountryInfo.continentOf(code)
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    WorldSheet(onDismiss) {
        stat?.heroMediaId?.let { MediaImage(it, Modifier.fillMaxWidth().height(150.dp), contentDescription = "A photo from ${CountryInfo.name(code)}") }
        GrabBar()
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FlagSticker(code, 52.dp, dashed = !visited)
                Column(Modifier.weight(1f)) {
                    Text(Bt.text.display(CountryInfo.name(code)), style = Bt.text.h2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row {
                        if (continent != null) {
                            Text(
                                continent.label,
                                Modifier.clickable(role = Role.Button, onClickLabel = "Show ${continent.label}") { onContinent(continent) },
                                style = Bt.text.sub.copy(textDecoration = TextDecoration.Underline),
                            )
                        }
                        val tail = subLine(stat, world, wished)
                        if (tail.isNotEmpty()) Text((if (continent != null) " · " else "") + tail, style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!visited) {
                    BtIconButton(
                        if (wished) BtIcons.Bookmarked else BtIcons.Bookmark,
                        if (wished) "Remove from want to go" else "Want to go",
                        { scope.launch { graph.countries.setWish(code, !wished) } },
                    )
                }
            }

            if (visited) {
                HorizontalDivider(Modifier.padding(top = 12.dp), color = c.line)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Fact("Trips", stat.tripIds.size, Modifier.weight(1f))
                    Fact("Days", stat.days, Modifier.weight(1f))
                    Fact("Places", stat.places, Modifier.weight(1f))
                }
                BtDivider()
            }

            val trips = (stat?.tripIds.orEmpty() + stat?.plannedTripIds.orEmpty()).distinct().mapNotNull { world?.tripsById?.get(it) }
            trips.take(6).forEach { t -> TripLine(t, today) { onDismiss(); actions.openTrip(t.id) } }

            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    visited && stat.found -> BtButton("Show on the map", onClick = onShowRoutes, modifier = Modifier.fillMaxWidth())
                    !visited -> BtButton("I've been here", onClick = { scope.launch { graph.countries.markVisited(code) } }, modifier = Modifier.fillMaxWidth())
                }
                BtGhostButton(
                    "Plan a trip here",
                    onClick = {
                        scope.launch {
                            val id = WorldActions.planTripIn(graph, code)
                            onDismiss()
                            actions.openTrip(id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (visited) {
                    Text(
                        "Remove from my countries",
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .clickable(role = Role.Button) { scope.launch { graph.countries.remove(code, stat.found); onDismiss() } }
                            .padding(8.dp),
                        style = Bt.text.sub,
                    )
                }
            }
        }
    }
}

/** "first trip in 2019", "added by you", "planned", "on your want-to-go list". */
private fun subLine(stat: CountryStat?, world: World?, wished: Boolean): String {
    if (stat?.visited == true) {
        val firstTrip = stat.tripIds.mapNotNull { world?.tripsById?.get(it)?.startDate }.minOrNull()
        val year = (listOfNotNull(firstTrip, stat.firstVisit).minOrNull())?.year
        return when {
            year != null -> "first trip in $year"
            stat.manual -> "added by you"
            else -> ""
        }
    }
    return when {
        stat?.plannedTripIds?.isNotEmpty() == true -> "planned"
        wished -> "on your want-to-go list"
        else -> ""
    }
}

@Composable
private fun Fact(label: String, value: Int, modifier: Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = Bt.text.eyebrow.copy(fontSize = 10.sp))
        Text(value.toString(), style = Bt.text.mono.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = Bt.colors.ink)
    }
}

/** A trip row: title, then "NOW · DAY 4", "MAR 2024", "NEXT · OCT 2027" or "SOMEDAY". */
@Composable
private fun TripLine(trip: Trip, today: LocalDate, onClick: () -> Unit) {
    val start = trip.startDate
    val status = when {
        trip.isCurrent(today) -> "Now · day ${trip.dayNumber(today)}"
        start == null -> "Someday"
        start.isAfter(today) -> "Next · ${Dates.monthYear(start)}"
        else -> Dates.monthYear(start)
    }
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(trip.title, Modifier.weight(1f), style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status.uppercase(), Modifier.padding(start = 8.dp), style = Bt.text.mono.copy(fontSize = 11.sp))
        }
        BtDivider()
    }
}

/** A continent: your countries there with a photo from your trips, then the ones planned or wished. */
@Composable
fun ContinentSheet(world: World?, continent: Continent, onDismiss: () -> Unit, onCountry: (String) -> Unit) {
    val mine = world?.visited.orEmpty().filter { it.continent == continent }.sortedWith(compareByDescending<CountryStat> { it.days }.thenBy { CountryInfo.name(it.code) })
    val later = (world?.planned.orEmpty() + world?.wished.orEmpty()).filter { CountryInfo.continentOf(it) == continent }
    WorldSheet(onDismiss) {
        GrabBar()
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(Bt.text.display(continent.label), style = Bt.text.h2)
            Text(
                if (mine.isEmpty()) "None yet" else "${mine.size} ${plural(mine.size, "country", "countries")}",
                style = Bt.text.sub,
            )
            Spacer(Modifier.height(8.dp))
            mine.forEach { s -> CountryRow(s.code, statLine(s), s.heroMediaId, dashed = false) { onCountry(s.code) } }
            later.forEach { code ->
                CountryRow(code, if (code in world?.planned.orEmpty()) "Planned" else "Want to go", null, dashed = true) { onCountry(code) }
            }
            if (mine.isEmpty() && later.isEmpty()) {
                Text("Tap a country on the globe to add it.", Modifier.padding(vertical = 12.dp), style = Bt.text.sub)
            }
        }
    }
}

/** "3 trips · 41 days · 12 entries", or "Added by you". */
private fun statLine(s: CountryStat): String {
    val parts = listOfNotNull(
        s.tripIds.size.takeIf { it > 0 }?.let { "$it ${plural(it, "trip", "trips")}" },
        s.days.takeIf { it > 0 }?.let { "$it ${plural(it, "day", "days")}" },
        s.entries.takeIf { it > 0 }?.let { "$it ${plural(it, "entry", "entries")}" },
    )
    return if (parts.isEmpty()) (if (s.manual) "Added by you" else "") else parts.joinToString(" · ")
}

@Composable
private fun CountryRow(code: String, line: String, photo: String?, dashed: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlagSticker(code, 36.dp, dashed = dashed)
            Column(Modifier.weight(1f)) {
                Text(CountryInfo.name(code), style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line.isNotEmpty()) Text(line, style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (photo != null) {
                MediaImage(photo, Modifier.width(56.dp).height(42.dp).clip(RoundedCornerShape(Bt.tokens.photo.corner)))
            }
        }
        BtDivider()
    }
}
