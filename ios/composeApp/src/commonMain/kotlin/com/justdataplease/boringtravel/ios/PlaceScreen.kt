package com.justdataplease.boringtravel.ios

import androidx.compose.animation.AnimatedVisibility
import com.justdataplease.boringtravel.geo.CountryInfo
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.ui.components.BtBackButton
import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import com.justdataplease.boringtravel.ui.components.BtFab
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.DemoTag
import com.justdataplease.boringtravel.ui.components.EmptyState
import com.justdataplease.boringtravel.ui.components.SectionLabel
import com.justdataplease.boringtravel.ui.components.btCardShape
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates

/**
 * A place in a trip, as the Android app's place page (ui/place/PlaceScreen.kt): the hero in the theme's style, then only
 * what the app knows: the country, the days and places at this place in the plan, how long, how you get there, where
 * you stay, notes, and the plan's items here. Add a place (or Add to plan, for a stop that isn't planned); a stop can
 * be removed. Opened from a day's title around plan item [itemId], or around stop [stopId] (the map, once it is here).
 */
@Composable
fun PlaceScreen(
    book: TripBook,
    tripId: String,
    itemId: String?,
    stopId: String?,
    revision: Int,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onSay: (String) -> Unit = {},
    sheetAtStart: Boolean = false,
    /** An item's journal note opened from its sheet, and its Write (Android's openEntry and writeAbout); null: none. */
    onOpenNote: ((String) -> Unit)? = null,
    onWriteAbout: ((String) -> Unit)? = null,
) {
    val trip = remember(book, tripId, revision) { book.trip(tripId) }
    val all = remember(book, tripId, revision) { book.items(tripId) }
    val stops = remember(book, tripId, revision) { book.snapshot.stops.filter { it.tripId == tripId && it.deletedAt == null } }
    val media = remember(book, tripId, revision) { book.snapshot.media.filter { it.tripId == tripId && it.deletedAt == null } }
    BtScreen {
        if (trip == null) return@BtScreen
        val anchorItem = itemId?.let { id -> all.firstOrNull { it.id == id } }
        val stop = stopId?.let { id -> stops.firstOrNull { it.id == id } }
        val lat = anchorItem?.lat ?: stop?.lat
        val lng = anchorItem?.lng ?: stop?.lng
        if (lat == null || lng == null) {
            Column(Modifier.fillMaxSize()) {
                TopButtons(onBack, onShare = null, demo = trip.isDemo)
                EmptyState("No place here", "This item has no location in the plan yet.")
            }
            return@BtScreen
        }
        PlaceContent(book, trip, all, anchorItem, stop, lat, lng, PlaceGrouping.photosNear(media, lat, lng), onBack, onChanged, onSay, sheetAtStart, onOpenNote, onWriteAbout)
    }
}

@Composable
private fun PlaceContent(
    book: TripBook, trip: Trip, all: List<PlanItem>, anchorItem: PlanItem?, stop: Stop?,
    lat: Double, lng: Double, photos: List<Media>, onBack: () -> Unit, onChanged: () -> Unit, onSay: (String) -> Unit, sheetAtStart: Boolean,
    onOpenNote: ((String) -> Unit)?, onWriteAbout: ((String) -> Unit)?,
) {
    val web = LocalWeb.current
    val phone = LocalPhone.current
    val summary = remember(trip, all, lat, lng) { PlaceGrouping.summarize(trip, all, lat, lng) }

    // The name: a stop's name, the area of the item's place name, the plan's arrival near it, else what the phone's
    // geocoder calls the spot, else the item's own place label.
    val arrival = remember(all, anchorItem, stop, lat, lng) {
        val day = anchorItem?.day ?: stop?.let { localDateAt(it.arrivedAt, null) }
        PlaceGrouping.arrivalName(all.filter { it.tripId == trip.id }, day, lat, lng)
    }
    val known = stop?.name?.takeIf { it.isNotBlank() }?.let { PlaceNames.area(it) ?: it }
        ?: anchorItem?.placeName?.takeIf { it.isNotBlank() }?.let { PlaceNames.area(it) }
        ?: arrival
    var geocoded by remember(lat, lng) { mutableStateOf<GeoAddress?>(null) }
    var country by remember(lat, lng) { mutableStateOf(stop?.countryCode) }
    LaunchedEffect(lat, lng) {
        if (country == null) country = CountryOutlines.load()?.codeAt(lat, lng, COUNTRY_NEAR_M)
        if (known == null || country == null) {
            val g = runCatching { web.location.address(lat, lng, 6_000) }.getOrNull()
            geocoded = g
            if (country == null) country = g?.countryCode?.uppercase()
        }
    }
    val name = known ?: geocoded?.let(GeoNames::pageName)?.let(PlaceNames::clean) ?: anchorItem?.let(PlaceGrouping::itemPlaceLabel) ?: "This place"
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
    var adding by rememberSaveable { mutableStateOf(sheetAtStart) }
    var removing by remember { mutableStateOf(false) }
    val tripDays = remember(trip.startDate, trip.endDate, all) { PlanDays.days(trip.startDate, trip.endDate, all) }

    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 104.dp)) {
            PlaceHero(style, hero)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spacer(Modifier.height(4.dp))
                if (days.isNotEmpty()) {
                    HowLong(days.size)
                    PlanBlock(days, summary.sights.size)
                }
                Sections(book, summary, trip, onChanged)
                if (summary.items.isNotEmpty()) {
                    InPlan(summary.items.filter { it.kind != PlanKind.Note.id }, trip, onToggle = { item ->
                        book.setDone(item.id, !item.done)
                        onChanged()
                    }, onOpen = { editing = it })
                }
            }
        }
        // Once the page scrolls, the status bar gets the page's paper behind it so text never runs under the clock.
        if (scrolled) Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).paper(Bt.tokens))
        TopButtons(onBack, demo = trip.isDemo, onRemove = if (stop != null && anchorItem == null) ({ removing = true }) else null, onShare = {
            val text = listOfNotNull(
                listOfNotNull(name, code?.let { CountryInfo.name(it) }).joinToString(", "),
                listOfNotNull(daysLabel, trip.title.takeIf { daysLabel != null }).joinToString(" of ").ifBlank { null },
                summary.sights.take(6).joinToString(" · ") { it.title }.ifBlank { null },
            ).joinToString("\n")
            // No subject: Android's place page sends the text alone (ACTION_SEND without EXTRA_SUBJECT).
            phone.share("", text)
        })
        val inPlan = summary.inPlan
        BtFab(
            if (inPlan) "Add a place" else "Add to plan",
            onClick = {
                if (inPlan || stop == null) adding = true
                else {
                    val day = localDateAt(stop.arrivedAt, null)
                    val planned = book.addItem(trip.id, stop.name.ifBlank { name }, day = day.takeIf { d -> tripDays.any { it.toEpochDay() == d.toEpochDay() } }, kind = PlanKind.Place)
                    if (planned != null) book.saveItem(planned.copy(placeName = stop.name, lat = stop.lat, lng = stop.lng))
                    onChanged()
                    onSay("Added to the plan")
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        )
    }

    editing?.let { item ->
        PlanItemSheet(book, trip.id, item, item.day, all, tripDays, onChanged = onChanged, onClose = { editing = null }, onOpenEntry = onOpenNote, onWrite = onWriteAbout)
    }
    if (adding) {
        PlanItemSheet(book, trip.id, null, days.lastOrNull(), all, tripDays, onChanged = onChanged, onClose = { adding = false })
    }
    if (removing && stop != null) {
        // A stop removed by hand: it never comes back from the route.
        BtConfirmDialog(
            title = "Remove this stop?", body = "It goes from the map, the trip's clips and Your world. The recorded route stays.",
            confirm = "Remove", danger = true,
            onConfirm = {
                removing = false
                val t = maxOf(nowMs(), stop.updatedAt + 1)
                book.putRows(stops = listOf(stop.copy(deletedAt = t, updatedAt = t)))
                onChanged()
                onBack()
            },
            onDismiss = { removing = false },
        )
    }
}

/** Where the offline outlines still count a point as in a country (coarse coasts, islands near a coast). */
private const val COUNTRY_NEAR_M = 60_000.0

/** Back on the left (with the Demo tag on an example trip), Share (and Remove, for a stop) on the right, over the hero. */
@Composable
private fun TopButtons(onBack: () -> Unit, onShare: (() -> Unit)?, demo: Boolean = false, onRemove: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BtBackButton(onBack)
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
private fun Sections(book: TripBook, summary: PlaceSummary, trip: Trip, onChanged: () -> Unit) {
    Column {
        summary.arrival?.let { leg ->
            Section(BtIcons.Route, "Getting there", initiallyOpen = true) {
                val arrival = PlaceGrouping.arrivalLine(leg)
                Text(arrival, Modifier.copyOnLongPress(arrival, "Getting there"), style = Bt.text.body)
                val day = leg.toItem?.day?.let { PlanDays.number(trip.startDate, trip.endDate, it) }
                val note = listOfNotNull(leg.toItem?.note?.trim()?.ifBlank { null }, day?.let { "Day $it in your plan" }).joinToString(" · ")
                if (note.isNotBlank()) Text(note, style = Bt.text.sub)
                // How you get here: Car unless chosen otherwise; a tap saves it for this leg.
                GettingThere(book, trip.id, leg.leg, leg.vehicle, line = null, onChanged = onChanged, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Section(BtIcons.Stay, "Where to stay", initiallyOpen = summary.stays.isNotEmpty()) {
            if (summary.stays.isEmpty()) Text("No stay in the plan yet.", style = Bt.text.sub)
            summary.stays.forEach { s ->
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
            drawLine(
                c.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
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
    Column {
        SectionLabel("In your plan", Modifier.padding(top = 6.dp, bottom = 4.dp))
        items.forEach { item ->
            Row(
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
