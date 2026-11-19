package com.justdataplease.boringtravel.ui.place

import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.geo.ReverseGeocoder
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.places.PlaceNames
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.DemoTag
import com.justdataplease.boringtravel.ui.components.EmptyState
import com.justdataplease.boringtravel.ui.components.btCardShape
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.components.copyOnLongPress
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.nav.Place
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.trip.PlanDays
import com.justdataplease.boringtravel.ui.trip.PlanItemSheet
import com.justdataplease.boringtravel.ui.trip.GettingThere
import com.justdataplease.boringtravel.ui.trip.KindBadge
import com.justdataplease.boringtravel.ui.components.BtFab
import com.justdataplease.boringtravel.ui.components.BtBackButton
import com.justdataplease.boringtravel.ui.components.SectionLabel
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * A place in a trip (design boards "Place · arriving somewhere new"). The hero follows the theme; the rest is only
 * what the app knows: the country, the days and places at this place in the plan, how long, how you get there,
 * where you stay, notes, and the plan's items here. Add a place (or Add to plan, for a stop that isn't planned).
 */
@Composable
fun PlaceScreen(graph: AppGraph, actions: AppActions, route: Place) {
    val trip by remember(route.tripId) { graph.trips.observeTrip(route.tripId) }.collectAsStateWithLifecycle(initialValue = null)
    val items by remember(route.tripId) { graph.plan.observeItems(route.tripId) }.collectAsStateWithLifecycle(initialValue = null)
    val stops by remember(route.tripId) { graph.tracks.observeStops(route.tripId) }.collectAsStateWithLifecycle(initialValue = null)
    val media by remember(route.tripId) { graph.media.observeForTrip(route.tripId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val t = trip
    val all = items
    val stopList = stops
    BtScreen {
        if (t == null || all == null || stopList == null) return@BtScreen
        val anchorItem = route.itemId?.let { id -> all.firstOrNull { it.id == id } }
        val stop = route.stopId?.let { id -> stopList.firstOrNull { it.id == id && it.deletedAt == null } }
        val lat = anchorItem?.lat ?: stop?.lat
        val lng = anchorItem?.lng ?: stop?.lng
        if (lat == null || lng == null) {
            Column(Modifier.fillMaxSize()) {
                TopButtons(actions, onShare = null, demo = t.isDemo)
                EmptyState("No place here", "This item has no location in the plan yet.")
            }
            return@BtScreen
        }
        PlaceContent(graph, actions, t, all, anchorItem, stop, lat, lng, PlaceGrouping.photosNear(media, lat, lng))
    }
}

@Composable
private fun PlaceContent(
    graph: AppGraph, actions: AppActions, trip: Trip, all: List<PlanItem>, anchorItem: PlanItem?, stop: Stop?,
    lat: Double, lng: Double, photos: List<com.justdataplease.boringtravel.data.db.Media>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val summary = remember(trip, all, lat, lng) { PlaceGrouping.summarize(trip, all, lat, lng) }

    // The name: a stop's name, the area of the item's place name, the plan's arrival near it ("Transfer to El Chaltén"),
    // else what the phone's geocoder calls the spot (best effort, needs a network on most phones), else the item's title.
    val arrival = remember(all, anchorItem, stop, lat, lng) {
        val day = anchorItem?.day ?: stop?.let { java.time.Instant.ofEpochMilli(it.arrivedAt).atZone(ZoneId.systemDefault()).toLocalDate() }
        PlaceGrouping.arrivalName(all.filter { it.tripId == trip.id }, day, lat, lng)
    }
    val known = stop?.name?.takeIf { it.isNotBlank() }?.let { PlaceNames.area(it) ?: it }
        ?: anchorItem?.placeName?.takeIf { it.isNotBlank() }?.let { PlaceNames.area(it) }
        ?: arrival
    var geocoded by remember(lat, lng) { mutableStateOf<ReverseGeocoder.Place?>(null) }
    var country by remember(lat, lng) { mutableStateOf(stop?.countryCode) }
    LaunchedEffect(lat, lng) {
        if (country == null) country = graph.world.countryAt(lat, lng, 60_000.0)
        if (known == null || country == null) {
            val g = ReverseGeocoder.lookup(context, lat, lng)
            geocoded = g
            if (country == null) country = g?.countryCode
        }
    }
    val name = known ?: geocoded?.name?.let(PlaceNames::clean) ?: anchorItem?.let(PlaceGrouping::itemPlaceLabel) ?: "This place"
    val code = CountryInfo.key(country)

    val days = summary.days
    val daysLabel = PlaceGrouping.daysLabel(summary.dayNumbers)
    val placesText = summary.sights.size.let { n -> if (n == 1) "1 place" else "$n places" }
    val style = PlaceHeroStyle.of(Bt.tokens.look.id)
    val hero = HeroData(
        name = name,
        countryCode = code,
        countryName = code?.let { CountryInfo.name(it) },
        daysLabel = daysLabel,
        planLine = daysLabel?.let { "$it · $placesText" },
        photos = photos,
        sights = summary.sights,
        located = summary.items.filter(PlaceGrouping::located),
        onward = summary.departure?.let { leg ->
            val day = leg.toItem?.day?.let { d -> PlanDays.number(trip.startDate, trip.endDate, d) }
            listOfNotNull("${leg.vehicle.label} to ${leg.toLabel}", day?.let { "day $it" }).joinToString(", ")
        },
        stamp = summary.sights.firstOrNull { it.day != null }?.let { s ->
            PlaceGrouping.sticker(s.title, 16).uppercase() to "${Dates.weekday(s.day!!)} ${Dates.dayMonth(s.day!!)}".uppercase()
        },
        lat = lat,
        lng = lng,
    )

    var editing by remember { mutableStateOf<PlanItem?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val tripDays = remember(trip.startDate, trip.endDate, all) { PlanDays.days(trip.startDate, trip.endDate, all) }

    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 104.dp)) {
            PlaceHero(style, hero)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spacer(Modifier.height(4.dp))
                PlaceBlurbHook(name, summary)
                if (days.isNotEmpty()) {
                    HowLong(days.size)
                    PlanBlock(days, summary.sights.size)
                }
                Sections(graph, summary, trip)
                if (summary.items.isNotEmpty()) {
                    InPlan(summary.items.filter { it.kind != PlanKind.Note.id }, trip, onToggle = { item ->
                        scope.launch { graph.plan.setDone(item, !item.done) }
                    }, onOpen = { editing = it })
                }
            }
        }
        // Once the page scrolls, the status bar gets the page's paper behind it so text never runs under the clock.
        if (scrolled) Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).paper(Bt.tokens))
        TopButtons(actions, demo = trip.isDemo, onRemove = if (stop != null && anchorItem == null) ({ removing = true }) else null, onShare = {
            val text = listOfNotNull(
                listOfNotNull(name, code?.let { CountryInfo.name(it) }).joinToString(", "),
                listOfNotNull(daysLabel, trip.title.takeIf { daysLabel != null }).joinToString(" of ").ifBlank { null },
                summary.sights.take(6).joinToString(" · ") { it.title }.ifBlank { null },
            ).joinToString("\n")
            runCatching {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }
        })
        val inPlan = summary.inPlan
        BtFab(
            if (inPlan) "Add a place" else "Add to plan",
            onClick = {
                if (inPlan || stop == null) adding = true
                else scope.launch {
                    val zone = ZoneId.systemDefault()
                    val day = java.time.Instant.ofEpochMilli(stop.arrivedAt).atZone(zone).toLocalDate()
                    val planned = graph.plan.add(trip.id, stop.name.ifBlank { name }, day = day.takeIf { it in tripDays }, kind = PlanKind.Place.id)
                    graph.plan.save(planned.copy(placeName = stop.name, lat = stop.lat, lng = stop.lng))
                    Toast.makeText(context, "Added to the plan", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        )
    }

    editing?.let { item ->
        PlanItemSheet(
            graph = graph, tripId = trip.id, original = item, newDay = item.day, items = all, days = tripDays, onClose = { editing = null },
            onOpenEntry = { actions.openEntry(it) },
            onWrite = { actions.writeAbout(trip.id, it) },
        )
    }
    if (adding) {
        PlanItemSheet(graph = graph, tripId = trip.id, original = null, newDay = days.lastOrNull(), items = all, days = tripDays, onClose = { adding = false })
    }
    if (removing && stop != null) {
        // A stop found from the route (or the demo's stays): removed by hand, it never comes back from the route.
        BtConfirmDialog(
            title = "Remove this stop?", body = "It goes from the map, the trip's clips and Your world. The recorded route stays.",
            confirm = "Remove", danger = true,
            onConfirm = {
                removing = false
                graph.scope.launch { graph.tracks.deleteStop(stop) }
                actions.back()
            },
            onDismiss = { removing = false },
        )
    }
}

/**
 * Where a short AI description of the place (Pro) will go: the feel, best season, a local tip, always labelled as AI.
 * Nothing is shown until that exists; the page never invents facts or weather.
 */
@Composable
internal fun PlaceBlurbHook(name: String, summary: PlaceSummary) = Unit

/** Back on the left (with the Demo tag on an example trip), Share (and Remove, for a stop) on the right, over the hero. */
@Composable
private fun TopButtons(actions: AppActions, onShare: (() -> Unit)?, demo: Boolean = false, onRemove: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BtBackButton(actions::back)
        if (demo) DemoTag(Modifier.padding(start = 10.dp))
        Spacer(Modifier.weight(1f))
        if (onRemove != null) BtIconButton(BtIcons.Delete, "Remove this stop", onRemove, danger = true)
        if (onShare != null) BtIconButton(BtIcons.Share, "Share", onShare)
    }
}

/** "How long": the plan's length at this place, marked on 2 days · 3–4 days · 5+ days. */
@Composable
private fun HowLong(days: Int) {
    val c = Bt.colors
    val bucket = PlaceGrouping.lengthBucket(days)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("How long · in your plan")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaceGrouping.LENGTH_LABELS.forEachIndexed { i, label ->
                val on = i == bucket
                Text(
                    label,
                    Modifier.clip(btControlShape()).background(if (on) c.chipOn else c.chip).padding(horizontal = 14.dp, vertical = 8.dp),
                    style = Bt.text.label.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (on) c.onChipOn else c.ink,
                )
            }
        }
    }
}

/** The plan in one block: "4 · days in your plan · 13–16 Mar 2027 · 3 places". */
@Composable
private fun PlanBlock(days: List<LocalDate>, places: Int) {
    val c = Bt.colors
    Row(
        Modifier.fillMaxWidth().clip(btCardShape()).background(c.ink).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(Bt.text.display(days.size.toString()), style = Bt.text.h1, color = c.route)
        Column {
            Text(if (days.size == 1) "day in your plan" else "days in your plan", style = Bt.text.item, color = c.bg)
            Text(
                "${Dates.range(days.first(), days.last())} · ${if (places == 1) "1 place" else "$places places"}",
                style = Bt.text.sub, color = c.bg.copy(alpha = 0.8f),
            )
        }
    }
}

/** Getting there, Where to stay and Notes, each a row that opens to what the plan says. */
@Composable
private fun Sections(graph: AppGraph, summary: PlaceSummary, trip: Trip) {
    Column {
        summary.arrival?.let { leg ->
            Section(BtIcons.Route, "Getting there", initiallyOpen = true) {
                val arrival = PlaceGrouping.arrivalLine(leg)
                Text(arrival, Modifier.copyOnLongPress(arrival, "Getting there"), style = Bt.text.body)
                val day = leg.toItem?.day?.let { PlanDays.number(trip.startDate, trip.endDate, it) }
                val note = listOfNotNull(leg.toItem?.note?.trim()?.ifBlank { null }, day?.let { "Day $it in your plan" }).joinToString(" · ")
                if (note.isNotBlank()) Text(note, style = Bt.text.sub)
                // How you get here: Car unless chosen otherwise; a tap saves it for this leg.
                GettingThere(graph, trip.id, leg.leg, leg.vehicle, line = null, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Section(BtIcons.Stay, "Where to stay", initiallyOpen = summary.stays.isNotEmpty()) {
            if (summary.stays.isEmpty()) Text("No stay in the plan yet.", style = Bt.text.sub)
            summary.stays.forEach { s ->
                // A long press copies the stay's name (with its address) or its note.
                Text(s.title, Modifier.copyOnLongPress(nameAndAddress(s), "Name and address"), style = Bt.text.item)
                if (s.note.isNotBlank()) Text(s.note, Modifier.copyOnLongPress(s.note, "Note"), style = Bt.text.sub)
            }
        }
        if (summary.notes.isNotEmpty()) {
            Section(BtIcons.Note, "Notes", initiallyOpen = true) {
                summary.notes.forEach { n ->
                    Text(n.title, Modifier.copyOnLongPress(n.title, "Note"), style = Bt.text.item)
                    if (n.note.isNotBlank()) Text(n.note, Modifier.copyOnLongPress(n.note, "Note"), style = Bt.text.sub)
                }
            }
        }
    }
}

@Composable
private fun Section(icon: ImageVector, title: String, initiallyOpen: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val c = Bt.colors
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    Column(
        Modifier.fillMaxWidth().drawBehind {
            drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        },
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (open) "Close $title" else "Open $title") { open = !open }
                .heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(c.chip), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = c.route)
            }
            Text(title, Modifier.weight(1f), style = Bt.text.item.copy(fontSize = 16.sp))
            Icon(if (open) BtIcons.Collapse else BtIcons.Expand, null, Modifier.size(20.dp), tint = c.muted)
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(start = 46.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        }
    }
}

/** An item's name, and its address on a second line when it has one: what a long press copies. */
private fun nameAndAddress(item: PlanItem): String =
    listOfNotNull(item.title.trim(), item.placeName.trim().takeIf { it.isNotEmpty() && it != item.title.trim() }).joinToString("\n")

/** The plan's items at this place, by day: tick one off, tap to edit, a long press copies its name and address. */
@Composable
private fun InPlan(items: List<PlanItem>, trip: Trip, onToggle: (PlanItem) -> Unit, onOpen: (PlanItem) -> Unit) {
    val c = Bt.colors
    Column {
        SectionLabel("In your plan", Modifier.padding(top = 6.dp, bottom = 4.dp))
        items.forEach { item ->
            Row(
                // The copy sits inside the click, so a long press copies and the release doesn't open the item.
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Edit ${item.title}") { onOpen(item) }
                    .copyOnLongPress(nameAndAddress(item), "Name and address").padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .clickable(role = Role.Checkbox, onClickLabel = if (item.done) "Mark not done" else "Mark done") { onToggle(item) },
                    contentAlignment = Alignment.Center,
                ) {
                    KindBadge(item.kind, null, item.done, 32.dp)
                }
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(item.title, style = Bt.text.item, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val day = item.day?.let { PlanDays.number(trip.startDate, trip.endDate, it) }
                    val sub = listOfNotNull(day?.let { "Day $it" }, item.timeMinutes?.let(Dates::time), item.note.trim().ifBlank { null }).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = Bt.text.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(1.dp))
    }
}
