package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.geo.Continent
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.civil
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btCardShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import com.justdataplease.boringtravel.world.CountryStat
import com.justdataplease.boringtravel.world.World

/*
 * Your world's sheets, as the Android app's ui/world/WorldSheets.kt and YourWorld.kt: one country (its photo, flag and
 * name, the trips there, what to do), one continent, and Your world itself (the numbers, continents, flags collected,
 * want to go, the totals, Share and Places I've been). The marks a traveller makes are the engine's country marks
 * ([TripBook.markCountryVisited], [TripBook.setCountryWish], [TripBook.removeCountry]), as Android's CountryRepository.
 */

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
        Column(Modifier.fillMaxWidth().background(c.surface), content = content)
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
    book: TripBook,
    today: LocalDate,
    world: World?,
    code: String,
    nav: WorldNav,
    onDismiss: () -> Unit,
    onContinent: (Continent) -> Unit,
    onShowRoutes: () -> Unit,
) {
    val c = Bt.colors
    val stat = world?.byCode?.get(code)
    val visited = stat?.visited == true
    val wished = world?.wished?.contains(code) == true
    val continent = CountryInfo.continentOf(code)
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
                        { if (book.setCountryWish(code, !wished)) nav.changed() },
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
            trips.take(6).forEach { t -> TripLine(t, today) { onDismiss(); nav.openTrip(t.id) } }

            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    visited && stat.found -> BtButton("Show on the map", onClick = onShowRoutes, modifier = Modifier.fillMaxWidth())
                    !visited -> BtButton("I've been here", onClick = { if (book.markCountryVisited(code)) nav.changed() }, modifier = Modifier.fillMaxWidth())
                }
                BtGhostButton(
                    "Plan a trip here",
                    onClick = {
                        val id = WorldActions.planTripIn(book, code)
                        onDismiss()
                        nav.changed()
                        nav.openTrip(id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (visited) {
                    Text(
                        "Remove from my countries",
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .clickable(role = Role.Button) {
                                if (book.removeCountry(code, stat.found)) nav.changed()
                                onDismiss()
                            }
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
        val firstTrip = stat.tripIds.mapNotNull { world?.tripsById?.get(it)?.startDate }.minByOrNull { it.toEpochDay() }
        val year = listOfNotNull(firstTrip, stat.firstVisit).minByOrNull { it.toEpochDay() }?.civil?.year
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
        start.toEpochDay() > today.toEpochDay() -> "Next · ${Dates.monthYear(start)}"
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
            Text(if (mine.isEmpty()) "None yet" else "${mine.size} ${plural(mine.size, "country", "countries")}", style = Bt.text.sub)
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

/**
 * Countries and % of the world, continents with small bars, flags collected (the next trip's country dashed), want
 * to go, trips · days away · km, and Share. With no countries yet: a plain empty state, still shareable.
 */
@Composable
fun YourWorldContent(
    world: World?,
    nav: WorldNav,
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
            BtButton("Share", onClick = nav.shareWorld, icon = BtIcons.Share, modifier = Modifier.weight(1f))
            BtGhostButton("Places I've been", onClick = nav.placesBeen, icon = BtIcons.AddPlace, modifier = Modifier.weight(1.3f))
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
