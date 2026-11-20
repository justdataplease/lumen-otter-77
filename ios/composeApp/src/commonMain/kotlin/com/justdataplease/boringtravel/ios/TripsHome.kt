package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.settings.TripsLook
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.programs.Destination
import com.justdataplease.boringtravel.programs.Focus
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.ProgramLibrary
import com.justdataplease.boringtravel.programs.scene
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.civil
import com.justdataplease.boringtravel.ui.components.BrandMark
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.DemoTag
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.H1
import com.justdataplease.boringtravel.ui.components.PhotoFrame
import com.justdataplease.boringtravel.ui.components.SectionLabel
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.programs.ProgramPreview
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.trips.HomeStats
import com.justdataplease.boringtravel.util.Dates

/** What Trips counts and shows beside each trip, read from the trips' store (the Android app's repositories' counts). */
object TripsHomeData {
    /** Planned places per trip (notes left out), for "7 places planned". */
    fun plannedCounts(book: TripBook): Map<String, Int> =
        book.snapshot.planItems.filter { it.deletedAt == null && it.kind != "note" }.groupingBy { it.tripId }.eachCount()

    /** Journal entries per trip, for "23 entries" on past trips. */
    fun entryCounts(book: TripBook): Map<String, Int> =
        book.snapshot.entries.filter { it.deletedAt == null && it.tripId != null }.groupingBy { it.tripId!! }.eachCount()

    /** Each trip's first photo by time (trips without photos are left out): its picture when it has no cover. */
    fun firstPhotos(book: TripBook): Map<String, String> = book.snapshot.media
        .filter { it.deletedAt == null && it.tripId != null && it.mime.startsWith("image/") }
        .groupBy { it.tripId!! }
        .mapValues { (_, list) -> list.sortedWith(compareBy({ it.takenAt == null }, { it.takenAt }, { it.createdAt }, { it.id })).first().id }

    /** The trips on show: the examples in demo mode, the traveller's own otherwise ([DemoTrips.visible]). */
    fun visibleTrips(book: TripBook, demoMode: Boolean): List<Trip> =
        book.snapshot.trips.filter { it.deletedAt == null && DemoTrips.visible(it.id, demoMode) }

    /**
     * The trip on [day] among [trips] as Android's TripRepository.currentOn and ownCurrentOn pick it (its TripDao.allOn:
     * of the trips whose dates hold the day, the one that started last, then the one made last): what a Write, a photo
     * or a voice note without a trip goes into (null: outside trips), what the widgets and route tracking follow. Trips'
     * own "Now" card is HomeStats' (the one that started first), as on Android.
     */
    fun onDay(trips: List<Trip>, day: LocalDate): Trip? = trips
        .filter { it.deletedAt == null && it.isCurrent(day) }
        .sortedWith(compareByDescending<Trip> { it.startDate!!.toEpochDay() }.thenByDescending { it.createdAt })
        .firstOrNull()

    /** [onDay] among the trips on show ([visibleTrips]): Android's currentOn. */
    fun currentOn(book: TripBook, demoMode: Boolean, day: LocalDate): Trip? = onDay(visibleTrips(book, demoMode), day)
}

/**
 * Trips, as the Android app shows it (ui/trips/TripsScreen.kt): the date over "Trips" and New trip (Exit demo, in red,
 * in demo mode), the trip happening now with Write today and the camera, then Next, Someday and Past, a card for the
 * notes written outside any trip (only when there are some), and a quiet row to the trip ideas. With no trips of their
 * own yet: "No trips yet", New trip, and the trip ideas in a card.
 *
 * Two looks (Me › Trips look): Tickets, the default, shows each trip as a ticket with its countdown on a stub in the
 * trip's Calendar colour ([TripTicket]); Pictures keeps the framed covers and thumbnails and adds the countdown (a
 * sticker on the Now photo, the number at the end of each row). In demo mode only the example trips show ([DemoTrips]).
 * Since round 23 also the restore offer and the line when the daily backup isn't happening. Left out until its round:
 * Join a trip. [revision] changes whenever the trips do, so what is shown is read again.
 */
@Composable
fun TripsHome(
    book: TripBook,
    today: LocalDate,
    revision: Int,
    onNewTrip: () -> Unit,
    onOpenIdeas: () -> Unit,
    onOpenIdea: (String) -> Unit,
    onOpenTrip: (String) -> Unit,
    onLooseNotes: () -> Unit = {},
    settings: AppSettings? = null,
    onEditTrip: (String) -> Unit = {},
    onWriteToday: (String) -> Unit = {},
    onTakePhoto: (String) -> Unit = {},
    onDictate: (String) -> Unit = {},
    onExitDemo: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onRestored: () -> Unit = {},
    /** The last row's "Join a friend's trip": a pasted link (round 26, Android's JoinTripRow, read in). */
    onJoin: (com.justdataplease.boringtravel.together.ShareLink) -> Unit = {},
) {
    val prefs = settings ?: remember { AppSettings.ready() }
    // A phone that has never backed up looks in Drive first and asks about restoring (RestoreOffer, since round 23).
    RestoreOffer(prefs, onRestored)
    // The daily backup isn't happening: said here, where it is seen (the Android owner: backups must not fail quietly).
    val google = com.justdataplease.boringtravel.google.LocalGoogle.current
    val backupTrouble = com.justdataplease.boringtravel.backup.BackupText.trouble(
        prefs.autoBackup, prefs.driveConnected, google?.needsReconnect == true, prefs.backupFreshAt, com.justdataplease.boringtravel.time.nowMs(),
    )
    val demoMode = prefs.demoMode
    val all = remember(book, revision, demoMode) { TripsHomeData.visibleTrips(book, demoMode) }
    val s = remember(all, today) { HomeStats.sections(all, today) }
    val planned = remember(book, revision) { TripsHomeData.plannedCounts(book) }
    val entries = remember(book, revision) { TripsHomeData.entryCounts(book) }
    val photos = remember(book, revision) { TripsHomeData.firstPhotos(book) }
    // Notes outside any trip are the traveller's own: none show in demo mode.
    val loose = remember(book, revision, demoMode) { if (demoMode) 0 else book.snapshot.entries.count { it.tripId == null && it.deletedAt == null } }
    val tickets = prefs.tripsLook == TripsLook.Tickets
    // Each trip's countries in travel order: its flags (Your world, worked out from the trips on show).
    val flags = rememberWorld(book, revision, demoMode, today).tripCountries
    // A trip has the same colour here as on the Calendar tab (same trips, same assignment).
    val tokens = Bt.tokens
    val colorIndex = remember(all) { TripColors.assign(TripSpan.split(all).spans) }
    fun colorOf(id: String): Color = TripPalette.color(tokens, colorIndex[id] ?: TripColors.preferred(id))
    BtScreen(statusScrim = true) {
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
                    // The empty state below has its own button, and one is enough. In demo mode it is Exit demo, in red.
                    if (all.isNotEmpty()) {
                        if (demoMode) BtButton("Exit demo", onClick = onExitDemo, icon = BtIcons.Close, small = true, danger = true)
                        else BtButton("New trip", onClick = onNewTrip, icon = BtIcons.Add, small = true)
                    }
                }
            }
            if (demoMode) item(key = "demo") { DemoBanner() }
            backupTrouble?.let { text -> item(key = "backup-trouble") { BackupTrouble(text, onOpen = onOpenBackup) } }
            // Nothing of their own yet: New trip and the trip ideas, front and centre. In demo mode with every example
            // deleted: the way out.
            if (all.isEmpty()) item(key = "empty") { if (demoMode) EmptyDemo(onExit = onExitDemo) else TripIdeasInvite(onNewTrip, onOpenIdea) }
            s.now?.let { now ->
                item(key = "now-${now.id}") {
                    if (tickets) NowTicket(now, today, colorOf(now.id), flags[now.id].orEmpty(), onOpen = { onOpenTrip(now.id) }, onWrite = { onWriteToday(now.id) }, onCamera = { onTakePhoto(now.id) }, onDictate = { onDictate(now.id) })
                    else NowCard(now, photos[now.id], today, flags[now.id].orEmpty(), onOpen = { onOpenTrip(now.id) }, onWrite = { onWriteToday(now.id) }, onCamera = { onTakePhoto(now.id) }, onDictate = { onDictate(now.id) })
                }
            }
            var index = 0
            fun section(label: String, list: List<Trip>, counts: Map<String, Int>, past: Boolean) {
                if (list.isEmpty()) return
                item(key = "eyebrow-$label") { SectionLabel(label, Modifier.padding(start = 20.dp, top = 18.dp, bottom = 2.dp)) }
                val start = index
                itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
                    if (tickets) {
                        TicketRow(t, today, colorOf(t.id), counts[t.id], past, flags[t.id].orEmpty(), onOpen = { onOpenTrip(t.id) }, onAddDates = { onEditTrip(t.id) })
                    } else {
                        TripRow(t, photos[t.id], tripLine(t, counts[t.id], past), TripCountdown.of(t, today), start + i, flags[t.id].orEmpty(), onOpen = { onOpenTrip(t.id) })
                    }
                }
                index += list.size
            }
            section("Next", s.next, planned, past = false)
            section("Someday", s.someday, planned, past = false)
            section("Past", s.past, entries, past = true)
            if (loose > 0) item(key = "loose") { LooseNotesCard(loose, onClick = onLooseNotes) }
            if (!demoMode && all.isNotEmpty()) item(key = "ideas") { TripIdeasRow(onClick = onOpenIdeas) }
            // A friend's trip link pasted here joins it (Travel together, round 26: the Android app's own row and dialog).
            item(key = "join") { com.justdataplease.boringtravel.ui.together.JoinTripRow(onJoin = onJoin) }
        }
    }
}

/** Pictures rows: "Oct 2027 · 12 days · 7 places", "May 2026 · 9 days · 23 entries", "No dates yet · 2 places". */
private fun tripLine(trip: Trip, count: Int?, past: Boolean): String {
    val base = if (trip.startDate == null) "No dates yet" else Dates.tripSummary(trip)
    return listOfNotNull(base, TripCountdown.countLine(count, past, short = true)).joinToString(" · ")
}

/** The route part of a subtitle: "Example trip · Buenos Aires → Santiago" (older examples) shows "Buenos Aires → Santiago". */
fun routeLine(subtitle: String): String {
    val parts = subtitle.split(" · ").map(String::trim).filter(String::isNotEmpty)
    return parts.firstOrNull { "→" in it } ?: subtitle.trim()
}

/** "25 Oct – 24 Nov"; across New Year with the years. */
private fun nowDates(start: LocalDate, end: LocalDate): String =
    if (start.civil.year == end.civil.year) "${Dates.dayMonth(start)} – ${Dates.dayMonth(end)}" else Dates.range(start, end)

/**
 * Tickets: the trip happening now ("Now · day 14 of 31", its days as a bar in its colour, "25 Oct – 24 Nov"), with Write
 * today, the mic (Write with dictation on) and the camera under it.
 */
@Composable
private fun NowTicket(trip: Trip, today: LocalDate, color: Color, flags: List<String>, onOpen: () -> Unit, onWrite: () -> Unit, onCamera: () -> Unit, onDictate: () -> Unit) {
    val start = trip.startDate
    val end = trip.endDate
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        TripTicket(
            TripCountdown.of(trip, today), color, "Now · day ${trip.dayNumber(today)} of ${trip.days}", trip.title, trip.isDemo,
            onClick = onOpen,
            flags = flags,
        ) {
            TripProgress(TripCountdown.progress(start, end, today), color, Modifier.padding(top = 10.dp))
            if (start != null && end != null) {
                Text(nowDates(start, end), Modifier.padding(top = 5.dp), style = Bt.text.fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        NowActions(onWrite, onDictate, onCamera, Modifier.padding(top = 14.dp))
    }
}

/** Write today (the trip's Journal, then Write over it), the mic (the same, dictating) and the camera (a new note with the photo). */
@Composable
private fun NowActions(onWrite: () -> Unit, onDictate: () -> Unit, onCamera: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BtButton("Write today", onClick = onWrite, icon = BtIcons.Journal, small = true)
        BtIconButton(BtIcons.Mic, "Dictate", onDictate)
        BtIconButton(BtIcons.Camera, "Take a photo", onCamera)
    }
}

/**
 * Tickets: a trip in Next, Someday or Past. Eyebrow "3 Oct 2027 · 12 days", "May 2026 · 9 days" or "No dates yet"; under
 * the title "68 places planned" or "8 entries" (else its route). A trip without dates gets "Add dates" on the stub.
 */
@Composable
private fun TicketRow(trip: Trip, today: LocalDate, color: Color, count: Int?, past: Boolean, flags: List<String>, onOpen: () -> Unit, onAddDates: () -> Unit) {
    val start = trip.startDate
    val eyebrow = when {
        start == null -> "No dates yet"
        past -> Dates.tripSummary(trip)
        else -> listOfNotNull(Dates.dayMonthYear(start), trip.days?.let(Dates::days)).joinToString(" · ")
    }
    val line = TripCountdown.countLine(count, past) ?: routeLine(trip.subtitle).takeIf { it.isNotBlank() }
    TripTicket(
        TripCountdown.of(trip, today), color, eyebrow, trip.title, trip.isDemo,
        onClick = onOpen,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        onAddDates = if (start == null) onAddDates else null,
        flags = flags,
    ) {
        if (line != null) Text(line, Modifier.padding(top = 2.dp), style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Pictures: the trip happening now on its framed cover, the countdown sticker on its corner, Write today and the camera. */
@Composable
private fun NowCard(trip: Trip, photo: String?, today: LocalDate, flags: List<String>, onOpen: () -> Unit, onWrite: () -> Unit, onCamera: () -> Unit, onDictate: () -> Unit) {
    BtCard(
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        padding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
        onClick = onOpen,
    ) {
        // The cover leans −1.5°, times the theme's tilt factor; the countdown sticker sits on its top corner.
        Box(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            PhotoFrame(Modifier.fillMaxWidth(), tiltDeg = -1.5f) {
                TripCover(trip, photo, Modifier.fillMaxWidth().height(150.dp), flags.firstOrNull())
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
        NowActions(onWrite, onDictate, onCamera, Modifier.padding(top = 12.dp))
    }
}

/** Pictures: a framed thumbnail, the title with its flags, the line, and the countdown at the end ("330 / days to go"). */
@Composable
private fun TripRow(trip: Trip, photo: String?, line: String, count: TripCountdown.Count, index: Int, flags: List<String>, onOpen: () -> Unit) {
    val pad = Bt.tokens.photo.framePad
    val thumb: Dp = 50.dp
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhotoFrame(Modifier.width(thumb + pad * 2), tiltDeg = -3f) {
            TripCover(trip, photo, Modifier.fillMaxWidth().height(thumb), flags.firstOrNull())
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

/**
 * A trip's picture (the Android app's ui/trips/TripCover.kt): its cover photo, else its first photo ([firstPhotoId]),
 * else a drawn picture picked for the trip, half of them mirrored.
 */
@Composable
fun TripCover(trip: Trip, firstPhotoId: String?, modifier: Modifier = Modifier, country: String? = null) {
    val photo = trip.coverMediaId ?: firstPhotoId
    val rows = LocalMediaRows.current
    if (photo != null && rows(photo) != null) {
        MediaImage(photo, modifier, contentDescription = "Cover photo", playBadge = false)
    } else {
        val kind = remember(trip.id, trip.title, trip.subtitle, country) { sceneFor(trip, country) }
        DrawnScene(kind, modifier, key = trip.id)
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

/** No trips of their own yet: "No trips yet" with one New trip button, then the trip ideas in a card. */
@Composable
private fun TripIdeasInvite(onNewTrip: () -> Unit, onOpenIdea: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp)) {
        Text("No trips yet", style = Bt.text.title)
        Text("Add your own, or start from a ready-made trip.", Modifier.padding(top = 2.dp), style = Bt.text.sub)
        BtButton("New trip", onClick = onNewTrip, modifier = Modifier.padding(top = 12.dp), icon = BtIcons.Add)
        BtCard(Modifier.padding(top = 18.dp), padding = PaddingValues(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 6.dp)) {
            Eyebrow("Trip ideas")
            Text("Pick a place, answer two questions, get a day-by-day plan.", Modifier.padding(top = 4.dp, end = 4.dp, bottom = 6.dp), style = Bt.text.sub)
            ProgramLibrary.destinations.forEachIndexed { index, destination ->
                if (index > 0) BtDivider()
                IdeaRow(destination, onClick = { onOpenIdea(destination.id) })
            }
        }
    }
}

/** A destination in the Trips card: its drawn picture as a framed thumbnail, the name, "12 days · Kyoto → Osaka". */
@Composable
private fun IdeaRow(destination: Destination, onClick: () -> Unit) {
    val pad = Bt.tokens.photo.framePad
    val thumb: Dp = 50.dp
    val line = remember(destination) { ProgramPreview.facts(destination.default) }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhotoFrame(Modifier.width(thumb + pad * 2), tiltDeg = -3f) {
            DrawnScene(ProgramLibrary.scene(destination), Modifier.fillMaxWidth().height(thumb), key = destination.id)
        }
        Column(Modifier.weight(1f)) {
            Text(destination.title, style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Box(Modifier.size(36.dp).clip(CircleShape).background(Bt.colors.chip), contentAlignment = Alignment.Center) {
            Icon(BtIcons.World, null, Modifier.size(19.dp), tint = Bt.colors.ink)
        }
        Column(Modifier.weight(1f)) {
            Text("Trip ideas", style = Bt.text.item, maxLines = 1)
            Text("Ready day-by-day plans: $names", style = Bt.text.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(BtIcons.Forward, null, Modifier.size(20.dp), tint = Bt.colors.muted)
    }
}

/**
 * Trip ideas, step 1 (the Android app's ui/programs/ProgramsScreen.kt): the destinations, each with its drawn picture in
 * the theme's frame, its days, name, a line about it and the best time to go.
 */
@Composable
fun TripIdeasScreen(onBack: () -> Unit, onPick: (String) -> Unit) {
    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Trip ideas", onBack = onBack)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "intro") {
                    Text(
                        "Pick a place, answer two questions, get a day-by-day plan.",
                        Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp), style = Bt.text.sub,
                    )
                }
                items(ProgramLibrary.destinations, key = { it.id }) { d -> DestinationCard(d) { onPick(d.id) } }
            }
        }
    }
}

/** A destination as the trip cards show a trip: its drawn picture in the theme's frame, then the facts. */
@Composable
private fun DestinationCard(d: Destination, onClick: () -> Unit) {
    val days = remember(d) { d.default.days.size }
    BtCard(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        padding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
        onClick = onClick,
    ) {
        PhotoFrame(Modifier.fillMaxWidth().padding(bottom = 10.dp), tiltDeg = -1.5f) {
            DrawnScene(ProgramLibrary.scene(d), Modifier.fillMaxWidth().height(130.dp), key = d.id)
        }
        Eyebrow(Dates.days(days))
        Text(d.title, Modifier.padding(top = 4.dp), style = Bt.text.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (d.blurb.isNotBlank()) Text(d.blurb, Modifier.padding(top = 2.dp), style = Bt.text.sub, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (d.bestTime.isNotBlank()) Text(d.bestTime, Modifier.padding(top = 6.dp), style = Bt.text.fine, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Trip ideas, steps 2 and 3, as on Android: two questions as chips (how you like to travel, what matters most) over
 * the matching program, which changes as the chips do: its days with their items, the ideas, and the sources. "Use
 * this program" asks for the first day ([StartDateDialog]) and makes it a trip of the traveller's own (the engine's
 * ProgramTrips). [pickingAtStart] opens the date straight away (the previews).
 */
@Composable
fun TripIdea(
    book: TripBook,
    destinationId: String,
    onBack: () -> Unit,
    onUsed: (String) -> Unit,
    pickingAtStart: Boolean = false,
    initialStart: LocalDate? = null,
) {
    val destination = remember(destinationId) { ProgramLibrary.destination(destinationId) } ?: return
    var paceId by rememberSaveable { mutableStateOf(Pace.Default.id) }
    var focusId by rememberSaveable { mutableStateOf(Focus.Default.id) }
    var picking by rememberSaveable { mutableStateOf(pickingAtStart) }
    var failed by remember { mutableStateOf(false) }
    val pace = Pace.of(paceId)
    val focus = Focus.of(focusId)
    val program = destination.program(focus)
    val days = remember(program, pace) { ProgramPreview.days(program, pace) }
    val ideas = remember(program) { ProgramPreview.ideas(program) }
    val sources = remember(program) { ProgramPreview.sources(program.sources) }

    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar(destination.title, onBack = onBack)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                    item(key = "pace") {
                        Question("How do you like to travel?", Pace.entries, pace, { it.label }, pace.blurb) { paceId = it.id }
                    }
                    item(key = "focus") {
                        Question("What matters most?", Focus.entries, focus, { it.label }, focus.blurb) { focusId = it.id }
                    }
                    item(key = "program") {
                        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
                            BtDivider()
                            Eyebrow(ProgramPreview.facts(program), Modifier.padding(top = 16.dp))
                            Text(program.title, Modifier.padding(top = 4.dp), style = Bt.text.title)
                            if (program.blurb.isNotBlank()) Text(program.blurb, Modifier.padding(top = 2.dp), style = Bt.text.sub)
                            if (destination.bestTime.isNotBlank()) Text(destination.bestTime, Modifier.padding(top = 6.dp), style = Bt.text.fine)
                        }
                    }
                    for (day in days) {
                        item(key = "day-${day.number}") {
                            Text(
                                day.label, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
                                style = Bt.text.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        itemsIndexed(day.rows, key = { i, _ -> "day-${day.number}-$i" }) { _, row -> ProgramItemRow(row) }
                    }
                    if (ideas.isNotEmpty()) {
                        item(key = "ideas") {
                            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp)) {
                                SectionLabel("Ideas")
                                Text("Not on a day. They go to Ideas in the plan.", Modifier.padding(top = 2.dp), style = Bt.text.fine)
                            }
                        }
                        itemsIndexed(ideas, key = { i, _ -> "idea-$i" }) { _, row -> ProgramItemRow(row) }
                    }
                    if (sources != null) {
                        item(key = "sources") {
                            Text(sources, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp), style = Bt.text.fine)
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    if (failed) {
                        Text("Couldn't make the trip. Try again.", Modifier.fillMaxWidth().padding(bottom = 6.dp), style = Bt.text.fine, color = Bt.colors.danger)
                    }
                    BtButton("Use this program", onClick = { picking = true }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (picking) StartDateDialog(
        days = program.days.size,
        initial = initialStart,
        onDismiss = { picking = false },
        onPick = { start ->
            picking = false
            failed = false
            runCatching { book.startFromIdea(destination, program, pace, start) }
                .onSuccess { onUsed(it.id) }
                .onFailure { failed = true }
        },
    )
}

/** A question and its answers as chips; the picked answer's one line under them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Question(title: String, options: List<T>, selected: T, label: (T) -> String, blurb: String, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
        Text(title, style = Bt.text.item)
        FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> BtChip(label(o), o == selected, { onPick(o) }) }
        }
        Text(blurb, style = Bt.text.sub)
    }
}

/** An item as the plan shows it: the kind in a circle, the title, and "07:30 · note". */
@Composable
private fun ProgramItemRow(row: ProgramPreview.Row) {
    val c = Bt.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 5.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.chip), contentAlignment = Alignment.Center) {
            Icon(kindIcon(row.kind, row.vehicle), null, Modifier.size(17.dp), tint = c.ink)
        }
        Column(Modifier.weight(1f)) {
            Text(row.title, style = Bt.text.item, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row.line.isNotBlank()) Text(row.line, Modifier.padding(top = 1.dp), style = Bt.text.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The daily backup isn't happening ([BackupText.trouble]): what is wrong, and the way to Backup & sync (Android's BackupTrouble). */
@Composable
private fun BackupTrouble(text: String, onOpen: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Open Backup & sync", onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.material3.Icon(BtIcons.Cloud, null, Modifier.size(18.dp), tint = Bt.colors.danger)
        Text(text, Modifier.weight(1f), style = Bt.text.sub, color = Bt.colors.danger, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text("Open", style = Bt.text.label, color = Bt.colors.ink)
    }
}
