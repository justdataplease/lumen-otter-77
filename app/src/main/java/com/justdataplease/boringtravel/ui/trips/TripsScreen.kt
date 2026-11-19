package com.justdataplease.boringtravel.ui.trips

import com.justdataplease.boringtravel.ui.me.RestoreOffer
import com.justdataplease.boringtravel.backup.BackupText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.settings.TripsLook
import com.justdataplease.boringtravel.programs.Destination
import com.justdataplease.boringtravel.programs.ProgramLibrary
import com.justdataplease.boringtravel.programs.scene
import com.justdataplease.boringtravel.tripcal.TripColors
import com.justdataplease.boringtravel.tripcal.TripSpan
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.DrawnScene
import com.justdataplease.boringtravel.ui.programs.ProgramPreview
import com.justdataplease.boringtravel.ui.components.BrandMark
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.DemoTag
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.SectionLabel
import com.justdataplease.boringtravel.ui.components.H1
import com.justdataplease.boringtravel.ui.components.PhotoFrame
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.tripcal.TripPalette
import com.justdataplease.boringtravel.ui.world.TripFlags
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Trips home (design boards "Trips · home, the trip happening now"): the date and "Trips" with a round "+" for a new
 * trip, the trip happening now on a framed cover with Write today, mic and camera, the lifetime row (countries, days
 * away, trips), then Next, Someday and Past with framed thumbnails, a card for notes written outside any trip (only
 * when there are some), and a quiet Trip ideas row. With no trips of their own (demo mode off) the traveller sees a New
 * trip button and the trip ideas instead. Everything about one trip is inside it (its own bar: Plan · Journal · Map ·
 * Media).
 *
 * Two looks (Me › Trips look): Tickets, the default, shows each trip as a ticket with its countdown on a stub in the
 * trip's Calendar colour ([TripTicket]); Pictures keeps the framed covers and thumbnails and adds the countdown (a
 * sticker on the Now photo, the number at the end of each row).
 */
@Composable
fun TripsScreen(graph: AppGraph, actions: AppActions) {
    val trips by remember(graph) { graph.trips.observeTrips() }.collectAsStateWithLifecycle(initialValue = null)
    val planned by remember(graph) { graph.trips.observePlannedCounts() }.collectAsStateWithLifecycle(initialValue = emptyMap())
    val entries by remember(graph) { graph.trips.observeEntryCounts() }.collectAsStateWithLifecycle(initialValue = emptyMap())
    val photos by remember(graph) { graph.media.observeFirstPhotos() }.collectAsStateWithLifecycle(initialValue = emptyMap())
    // Entries outside any trip are the traveller's own: none show in demo mode.
    val looseNotes by remember(graph) {
        combine(graph.db.entries().observeLooseCount(), graph.settings.demoMode) { n, demo -> if (demo) 0 else n }
    }.collectAsStateWithLifecycle(initialValue = 0)
    // Countries and days away come from the same place as the globe, so the two never disagree.
    val world by graph.world.world.collectAsStateWithLifecycle()
    val flags = world?.tripCountries.orEmpty()
    val today = remember { LocalDate.now() }

    val all = trips
    val s = remember(all, today) { HomeStats.sections(all.orEmpty(), today) }
    val demoMode by graph.settings.demoMode.collectAsStateWithLifecycle(initialValue = false)
    val tickets = graph.settings.tripsLook.collectAsStateWithLifecycle(initialValue = TripsLook.Tickets).value == TripsLook.Tickets
    // A trip has the same colour here as on the Calendar tab (same trips, same assignment).
    val tokens = Bt.tokens
    val colorIndex = remember(all) { TripColors.assign(TripSpan.split(all.orEmpty()).spans) }
    fun colorOf(id: String): Color = TripPalette.color(tokens, colorIndex[id] ?: TripColors.preferred(id))
    val scope = rememberCoroutineScope()
    val exitDemo: () -> Unit = { scope.launch { graph.demo.set(false) } }
    // A phone that has never backed up looks in Drive first and asks about restoring (ui/me/RestoreOffer).
    RestoreOffer(graph)
    // The daily backup isn't happening: said here, where it is seen (owner, 2026-11-11: backups must not fail quietly).
    val autoBackup by graph.settings.autoBackup.collectAsStateWithLifecycle(initialValue = false)
    val driveConnected by graph.settings.driveConnected.collectAsStateWithLifecycle(initialValue = false)
    val needsReconnect by graph.google.needsReconnect.collectAsStateWithLifecycle()
    val lastBackupAt by graph.settings.backupFreshAt.collectAsStateWithLifecycle(initialValue = null)
    val backupTrouble = BackupText.trouble(autoBackup, driveConnected, needsReconnect, lastBackupAt, System.currentTimeMillis())

    BtScreen(statusScrim = true) {
        // The screen already ends at the bottom bar (AppNav's Scaffold padding).
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("${Dates.weekday(today)} ${Dates.dayMonth(today)}")
                        H1("Trips")
                    }
                    // Me has its own tab: the header's button starts a new trip (a named button: owner, 2026-11-10,
                    // "should have a nice button, + is too small"). Not while the list is loading or empty: the empty
                    // state below has its own button, and one is enough. In demo mode it is Exit demo, in red (owner,
                    // 2026-11-11: "in order to create a new trip should not be in demo").
                    if (!all.isNullOrEmpty()) {
                        if (demoMode) BtButton("Exit demo", onClick = exitDemo, icon = BtIcons.Close, small = true, danger = true)
                        else BtButton("New trip", onClick = actions::newTrip, icon = BtIcons.Add, small = true)
                    }
                }
            }
            if (demoMode) item(key = "demo") { DemoBanner() }
            backupTrouble?.let { text -> item(key = "backup-trouble") { BackupTrouble(text, onOpen = actions::backupSettings) } }
            // Nothing of their own yet: New trip and the trip ideas, front and centre. In demo mode with every example
            // deleted: the way out.
            if (all != null && all.isEmpty()) item(key = "empty") { if (demoMode) EmptyDemo(onExit = exitDemo) else TripIdeasInvite(actions) }
            s.now?.let { now ->
                item(key = "now-${now.id}") {
                    if (tickets) NowTicket(now, today, colorOf(now.id), actions, flags[now.id].orEmpty())
                    else NowCard(now, photos[now.id], today, actions, flags[now.id].orEmpty())
                }
            }
            // No lifetime numbers row here (owner, 2026-11-08: "you can remove that"); the Map tab's Your world has them.
            var index = 0
            fun section(label: String, list: List<Trip>, counts: Map<String, Int>, past: Boolean) {
                if (list.isEmpty()) return
                item(key = "eyebrow-$label") { SectionLabel(label, Modifier.padding(start = 20.dp, top = 18.dp, bottom = 2.dp)) }
                val start = index
                itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
                    if (tickets) {
                        TicketRow(t, today, colorOf(t.id), counts[t.id], past, actions, flags[t.id].orEmpty())
                    } else {
                        TripRow(t, photos[t.id], tripLine(t, counts[t.id], past), TripCountdown.of(t, today), start + i, actions, flags[t.id].orEmpty())
                    }
                }
                index += list.size
            }
            section("Next", s.next, planned, past = false)
            section("Someday", s.someday, planned, past = false)
            section("Past", s.past, entries, past = true)
            if (looseNotes > 0) item(key = "loose") { LooseNotesCard(looseNotes, onClick = actions::looseNotes) }
            if (!demoMode && !all.isNullOrEmpty()) item(key = "ideas") { TripIdeasRow(onClick = { actions.tripIdeas() }) }
            if (all != null) item(key = "join") { com.justdataplease.boringtravel.ui.together.JoinTripRow(onJoin = actions::join) }
        }
    }
}

/** Notes written when no trip was on (the widget's Write with no trip on, older quick notes): one card that opens them. */
@Composable
private fun LooseNotesCard(count: Int, onClick: () -> Unit) {
    BtCard(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
        padding = PaddingValues(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        onClick = onClick,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Bt.colors.chip), contentAlignment = Alignment.Center) {
                Icon(BtIcons.Journal, null, Modifier.size(19.dp), tint = Bt.colors.ink)
            }
            Column(Modifier.weight(1f)) {
                Text("Notes outside trips", style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (count == 1) "1 note" else "$count notes", style = Bt.text.sub, maxLines = 1)
            }
            Icon(BtIcons.Forward, null, Modifier.size(20.dp), tint = Bt.colors.muted)
        }
    }
}

/** Pictures rows: "Oct 2027 · 12 days · 7 places", "May 2026 · 9 days · 23 entries", "No dates yet · 2 places". */
private fun tripLine(trip: Trip, count: Int?, past: Boolean): String {
    val base = if (trip.startDate == null) "No dates yet" else Dates.tripSummary(trip)
    return listOfNotNull(base, TripCountdown.countLine(count, past, short = true)).joinToString(" · ")
}

/**
 * Tickets: the trip happening now ("Now · day 14 of 31", its days as a bar in its colour, "25 Oct – 24 Nov"), with
 * Write today, mic and camera under it.
 */
@Composable
private fun NowTicket(trip: Trip, today: LocalDate, color: Color, actions: AppActions, flags: List<String>) {
    val start = trip.startDate
    val end = trip.endDate
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        TripTicket(
            TripCountdown.of(trip, today), color, "Now · day ${trip.dayNumber(today)} of ${trip.days}", trip.title, flags, trip.isDemo,
            onClick = { actions.openTrip(trip.id) },
        ) {
            TripProgress(TripCountdown.progress(start, end, today), color, Modifier.padding(top = 10.dp))
            if (start != null && end != null) {
                Text(nowDates(start, end), Modifier.padding(top = 5.dp), style = Bt.text.fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BtButton("Write today", onClick = { actions.writeInTrip(trip.id) }, icon = BtIcons.Journal, small = true)
            BtIconButton(BtIcons.Mic, "Dictate", { actions.writeInTrip(trip.id, dictate = true) })
            BtIconButton(BtIcons.Camera, "Take a photo", { actions.takePhoto(trip.id) })
        }
    }
}

/** "25 Oct – 24 Nov"; across New Year with the years. */
private fun nowDates(start: LocalDate, end: LocalDate): String =
    if (start.year == end.year) "${Dates.dayMonth(start)} – ${Dates.dayMonth(end)}" else Dates.range(start, end)

/**
 * Tickets: a trip in Next, Someday or Past. Eyebrow "3 Oct 2027 · 12 days", "May 2026 · 9 days" or "No dates yet";
 * under the title "68 places planned" or "8 entries" (else its route). A trip without dates gets "Add dates" on the stub.
 */
@Composable
private fun TicketRow(trip: Trip, today: LocalDate, color: Color, count: Int?, past: Boolean, actions: AppActions, flags: List<String>) {
    val start = trip.startDate
    val eyebrow = when {
        start == null -> "No dates yet"
        past -> Dates.tripSummary(trip)
        else -> listOfNotNull(Dates.dayMonthYear(start), trip.days?.let(Dates::days)).joinToString(" · ")
    }
    val line = TripCountdown.countLine(count, past) ?: routeLine(trip.subtitle).takeIf { it.isNotBlank() }
    TripTicket(
        TripCountdown.of(trip, today), color, eyebrow, trip.title, flags, trip.isDemo,
        onClick = { actions.openTrip(trip.id) },
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        onAddDates = if (start == null) ({ actions.editTrip(trip.id) }) else null,
    ) {
        if (line != null) Text(line, Modifier.padding(top = 2.dp), style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NowCard(trip: Trip, photo: String?, today: LocalDate, actions: AppActions, flags: List<String>) {
    BtCard(
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        padding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
        onClick = { actions.openTrip(trip.id) },
    ) {
        // The cover leans −1.5° and the thumbnails −3°, times the theme's tilt factor (as on the boards); the countdown
        // sticker sits on its top corner.
        Box(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            PhotoFrame(Modifier.fillMaxWidth(), tiltDeg = -1.5f) {
                TripCover(trip, photo, flags.firstOrNull(), Modifier.fillMaxWidth().height(150.dp))
            }
            CountdownSticker(TripCountdown.of(trip, today), Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-10).dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Now · day ${trip.dayNumber(today)} of ${trip.days}", Modifier.weight(1f, fill = false))
            if (trip.isDemo) DemoTag(Modifier.padding(start = 8.dp))
            TripFlags(flags, Modifier.padding(start = 8.dp))
        }
        Text(trip.title, Modifier.padding(top = 4.dp), style = Bt.text.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (trip.subtitle.isNotBlank()) {
            Text(routeLine(trip.subtitle), Modifier.padding(top = 2.dp), style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BtButton("Write today", onClick = { actions.writeInTrip(trip.id) }, icon = BtIcons.Journal, small = true)
            BtIconButton(BtIcons.Mic, "Dictate", { actions.writeInTrip(trip.id, dictate = true) })
            BtIconButton(BtIcons.Camera, "Take a photo", { actions.takePhoto(trip.id) })
        }
    }
}

/** The route part of a subtitle: "Example trip · Buenos Aires → Santiago" (older examples) shows "Buenos Aires → Santiago". */
internal fun routeLine(subtitle: String): String {
    val parts = subtitle.split(" · ").map(String::trim).filter(String::isNotEmpty)
    return parts.firstOrNull { "→" in it } ?: subtitle.trim()
}

/** Pictures: a framed thumbnail, the title with its flags, the line, and the countdown at the end ("330 / days to go"). */
@Composable
private fun TripRow(trip: Trip, photo: String?, line: String, count: TripCountdown.Count, index: Int, actions: AppActions, flags: List<String>) {
    val pad = Bt.tokens.photo.framePad
    val thumb: Dp = 50.dp
    Row(
        Modifier.fillMaxWidth().clickable { actions.openTrip(trip.id) }.padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhotoFrame(Modifier.width(thumb + pad * 2), tiltDeg = -3f) {
            TripCover(trip, photo, flags.firstOrNull(), Modifier.fillMaxWidth().height(thumb))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trip.title, Modifier.weight(1f, fill = false), style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TripFlags(flags, Modifier.padding(start = 6.dp), size = 14.dp, max = 3)
                if (trip.isDemo) DemoTag(Modifier.padding(start = 8.dp))
            }
            Text(line, style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (count.phase != TripCountdown.Phase.Someday) {
            CountdownText(
                count, Bt.colors.ink, numberSize = 30.sp,
                unitStyle = Bt.text.fine.copy(fontSize = 11.sp), wordStyle = Bt.text.item,
                alignment = Alignment.End,
            )
        }
    }
}

/** The daily backup isn't happening ([BackupText.trouble]): what is wrong, and the way to Backup & sync. */
@Composable
private fun BackupTrouble(text: String, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open Backup & sync", onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(BtIcons.Cloud, null, Modifier.size(18.dp), tint = Bt.colors.danger)
        Text(text, Modifier.weight(1f), style = Bt.text.sub, color = Bt.colors.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Open", style = Bt.text.label, color = Bt.colors.ink)
    }
}

/** Demo mode: the tag and what is on show (the way out is the header's Exit demo). */
@Composable
private fun DemoBanner() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DemoTag()
        Text("Example trips. Yours are hidden.", Modifier.weight(1f), style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Demo mode with every example deleted: Exit demo (turns Me › Demo mode off), where New trip would be. */
@Composable
private fun EmptyDemo(onExit: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BrandMark(size = 56.dp)
        Spacer(Modifier.height(8.dp))
        Text("No example trips", style = Bt.text.title, textAlign = TextAlign.Center)
        Text("Exit demo to see your own trips and plan a new one.", style = Bt.text.sub, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        BtButton("Exit demo", onClick = onExit, icon = BtIcons.Close, danger = true)
    }
}

/**
 * No trips of their own yet: "No trips yet" with one New trip button, then the trip ideas in a card (each destination
 * with its drawn picture, its days and route).
 */
@Composable
private fun TripIdeasInvite(actions: AppActions) {
    val destinations = remember { ProgramLibrary.destinations }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp)) {
        Text("No trips yet", style = Bt.text.title)
        Text("Add your own, or start from a ready-made trip.", Modifier.padding(top = 2.dp), style = Bt.text.sub)
        BtButton("New trip", onClick = actions::newTrip, modifier = Modifier.padding(top = 12.dp), icon = BtIcons.Add)
        BtCard(Modifier.padding(top = 18.dp), padding = PaddingValues(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 6.dp)) {
            Eyebrow("Trip ideas")
            Text("Pick a place, answer two questions, get a day-by-day plan.", Modifier.padding(top = 4.dp, end = 4.dp, bottom = 6.dp), style = Bt.text.sub)
            destinations.forEachIndexed { i, d ->
                if (i > 0) BtDivider()
                IdeaRow(d, onClick = { actions.tripIdeas(d.id) })
            }
        }
    }
}

/** A destination in the Trips card: its drawn picture as a framed thumbnail, the name, "12 days · Kyoto → Osaka". */
@Composable
private fun IdeaRow(d: Destination, onClick: () -> Unit) {
    val pad = Bt.tokens.photo.framePad
    val thumb: Dp = 50.dp
    val line = remember(d) { ProgramPreview.facts(d.default) }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhotoFrame(Modifier.width(thumb + pad * 2), tiltDeg = -3f) {
            DrawnScene(ProgramLibrary.scene(d), Modifier.fillMaxWidth().height(thumb), key = d.id)
        }
        Column(Modifier.weight(1f)) {
            Text(d.title, style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(BtIcons.Forward, null, Modifier.size(20.dp), tint = Bt.colors.muted)
    }
}

/** With trips of their own: one quiet row at the end of the list that opens Trip ideas. */
@Composable
private fun TripIdeasRow(onClick: () -> Unit) {
    val names = remember { ProgramPreview.names(ProgramLibrary.destinations) }
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Bt.colors.chip), contentAlignment = Alignment.Center) {
            Icon(BtIcons.World, null, Modifier.size(19.dp), tint = Bt.colors.ink)
        }
        Column(Modifier.weight(1f)) {
            Text("Trip ideas", style = Bt.text.item, maxLines = 1)
            Text("Ready day-by-day plans: $names", style = Bt.text.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(BtIcons.Forward, null, Modifier.size(20.dp), tint = Bt.colors.muted)
    }
}
