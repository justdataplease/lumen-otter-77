package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.widget.PaperGrid
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.skia.Image
import java.io.File

/*
 * Round 10's pictures: Me › Route tracking (off, each step, recording, stopped, Location Services off), a trip map's
 * Route tracking sheet (on a trip day, and before the trip), the trip map's dot while it records, a new note placed at
 * the last route point, and the home-screen widgets drawn from the snapshot the app writes. The widgets are SwiftUI on
 * iPhone (iosApp/BoringTravelWidget): these are Compose's drawing of the same snapshot, the same layout, so there is a
 * picture to look at on Windows; the SwiftUI drawing itself is only seen on a Mac.
 */

private const val T_WIDTH = 1320
private const val T_HEIGHT = 2868
private const val T_DENSITY = 3f
private val T_FRAME = PaddingValues(top = 62.dp, bottom = 34.dp)

/** A phone in memory as the pictures need it: its location answers, its notes, the clock at noon on [SAMPLE_TODAY]. */
private class PicturePhone(
    grant: LocationGrant = LocationGrant.WhileUsing,
    notes: NoteGrant = NoteGrant.Allowed,
    precise: Boolean = true,
    services: Boolean = true,
    alwaysAsked: Boolean = false,
    /** The Always path (off by default, as Android's background recording): on only for its own two pictures. */
    private val alwaysPath: Boolean = IosTrackingPlan.ASK_ALWAYS,
) {
    val sensor = MemoryRouteSensor(grantNow = grant, preciseNow = precise, servicesNow = services)
    val notes = MemoryPhoneNotes(notes)
    val store = MemorySettingsFile().also { if (alwaysAsked) TrackingStore(it).alwaysAsked = true }
    val noon = startOfDayMs(SAMPLE_TODAY, null) + 12 * 3_600_000L

    fun tracking(book: TripBook, settings: AppSettings, scope: CoroutineScope) =
        RouteTracking(book, settings, sensor, notes, MemoryNightlyRuns(), TrackingStore(store), scope, clock = { noon }, askAlways = alwaysPath)
}

fun tracking(out: File) {
    fun shot(name: String, content: @Composable () -> Unit) = File(out, "$name.png").writeBytes(render(T_WIDTH, T_HEIGHT, T_DENSITY, frames = 24, content))

    fun app(name: String, book: TripBook, phone: PicturePhone, settings: AppSettings, vararg at: Place, web: Web = Web.offline(), stop: Boolean = false) = shot(name) {
        val scope = rememberCoroutineScope()
        val tracking = remember {
            phone.tracking(book, settings, scope).also {
                it.start()
                if (stop) it.stopForToday()
            }
        }
        BoringTravelApp(book, settings, SAMPLE_TODAY, T_FRAME, startAt = at.toList(), version = PREVIEW_VERSION, web = web, tracking = tracking)
    }

    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        // Me › Route tracking before anything is switched on: Off, nothing to allow.
        app("tracking-off-$id", sampleBook(), PicturePhone(LocationGrant.NotAsked, NoteGrant.NotAsked), AppSettings.ready(look), Place.Tracking)
        // Balanced picked on a trip day: the first step, location While Using.
        app("tracking-precise-$id", sampleBook(), PicturePhone(LocationGrant.NotAsked, NoteGrant.NotAsked), AppSettings.ready(look).apply { trackingLevel = TrackingLevel.Balanced }, Place.Tracking)
        // Location given: recording, and the last step (the notes; Android's steps end there).
        app("tracking-notes-$id", sampleBook(), PicturePhone(LocationGrant.WhileUsing, NoteGrant.NotAsked), AppSettings.ready(look).apply { trackingLevel = TrackingLevel.Balanced }, Place.Tracking)
        // Everything given: recording, with Stop for today, no step left.
        app("tracking-recording-$id", sampleBook(), PicturePhone(LocationGrant.WhileUsing, NoteGrant.Allowed), AppSettings.ready(look).apply { trackingLevel = TrackingLevel.Detailed }, Place.Tracking)
    }
    // The Always path, off by default as Android's background recording (IosTrackingPlan.ASK_ALWAYS), drawn with it
    // switched on: its step ("Allow all the time"), and the same step once asked and refused (it leads to Settings).
    app(
        "tracking-always-off-by-default-ask", sampleBook(), PicturePhone(LocationGrant.WhileUsing, NoteGrant.Allowed, alwaysPath = true),
        AppSettings.ready().apply { trackingLevel = TrackingLevel.Balanced }, Place.Tracking,
    )
    app(
        "tracking-always-off-by-default-settings", sampleBook(), PicturePhone(LocationGrant.WhileUsing, alwaysAsked = true, alwaysPath = true),
        AppSettings.ready().apply { trackingLevel = TrackingLevel.Low }, Place.Tracking,
    )
    val settings = AppSettings.ready().apply { trackingLevel = TrackingLevel.Balanced }
    // Stopped for today; Precise Location off; Location Services off.
    app("tracking-stopped", sampleBook(), PicturePhone(LocationGrant.WhileUsing), settings, Place.Tracking, stop = true)
    app("tracking-imprecise", sampleBook(), PicturePhone(LocationGrant.WhileUsing, precise = false), settings, Place.Tracking)
    // Location Services off: iOS then reports every app as refused.
    app("tracking-location-off", sampleBook(), PicturePhone(LocationGrant.Refused, services = false), settings, Place.Tracking)
    // No trip today: Me's level for every trip, and what it says.
    app("tracking-no-trip", TripBook(MemoryTripFile()), PicturePhone(LocationGrant.WhileUsing), settings, Place.Tracking)
    // Me with its Route tracking row and the level.
    app("me-route-tracking", sampleBook(), PicturePhone(), AppSettings.ready().apply { trackingLevel = TrackingLevel.Detailed }, Place.Home(HomeTab.Me))

    // A trip's map: the dot while today's route records, the Route tracking button, and its sheet.
    val web = Recorded.web()
    val recorded = recordedBook()
    val naxos = recorded.trips(SAMPLE_TODAY).single()
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val lookSettings = AppSettings.ready(look).apply { trackingLevel = TrackingLevel.Balanced }
        app("trip-map-recording-${look.id}", recorded, PicturePhone(LocationGrant.WhileUsing), lookSettings, Place.Trip(naxos.id, TripTab.Map), web = web)
        shot("trip-map-tracking-sheet-${look.id}") {
            val scope = rememberCoroutineScope()
            val tracking = remember { PicturePhone(LocationGrant.WhileUsing).tracking(recorded, lookSettings, scope).also { it.start() } }
            CompositionLocalProvider(LocalWeb provides web, LocalTracking provides tracking, LocalTrackProcessor provides null) {
                BoringTravelTheme(look) {
                    Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(T_FRAME)) {
                        TripMapBoard(recorded, naxos.id, SAMPLE_TODAY, 0, lookSettings, {}, MapActions(), trackingAtStart = true)
                    }
                }
            }
        }
    }
    // Before the trip: "Starts on …", the levels only.
    val later = sampleBook()
    val coming = later.trips(SAMPLE_TODAY).first { it.title == "Naxos" }
    shot("trip-map-tracking-later") {
        val scope = rememberCoroutineScope()
        val tracking = remember { PicturePhone(LocationGrant.NotAsked, NoteGrant.NotAsked).tracking(later, AppSettings.ready(), scope) }
        CompositionLocalProvider(LocalWeb provides web, LocalTracking provides tracking, LocalTrackProcessor provides null) {
            BoringTravelTheme(Look.Default) {
                Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(T_FRAME)) {
                    TripMapBoard(later, coming.id, SAMPLE_TODAY, 0, AppSettings.ready(), {}, MapActions(), plannedAtStart = true, trackingAtStart = true)
                }
            }
        }
    }

    // A new note in today's trip, placed at the last route point (an hour old here), named by the phone's geocoder.
    val placed = sampleBook()
    val kyoto = placed.trips(SAMPLE_TODAY).first()
    withPoints(placed, kyoto.id, listOf(Triple(com.justdataplease.boringtravel.time.nowMs() - 3_600_000L, 34.9671, 135.7727)))
    val named = Web(OfflineNet, FixedLocation(com.justdataplease.boringtravel.places.LatLng(34.9671, 135.7727), named = GeoAddress(locality = "Fushimi")))
    app("write-route-place", placed, PicturePhone(), AppSettings.ready(), Place.Write(kyoto.id), web = named)

    // The widgets, from the snapshot the app writes, in every theme; then with no trip on (the next one named) and before
    // the app has written anything (the gallery's preview).
    for (look in Look.entries) {
        val snapshot = WidgetSnapshot.build(sampleBook(), look, demoMode = false, today = SAMPLE_TODAY)
        widgets(out, "widgets-${look.id}", snapshot, WidgetSnapshot.markPng(look))
    }
    val noTrip = TripBook(MemoryTripFile()).also { it.newTrip("Naxos", localDate(2026, 11, 30), localDate(2026, 12, 6)) }
    widgets(out, "widgets-no-trip", WidgetSnapshot.build(noTrip, Look.StickerBook, demoMode = false, today = SAMPLE_TODAY), WidgetSnapshot.markPng(Look.StickerBook))
    widgets(out, "widgets-demo", WidgetSnapshot.build(demoBook().first, Look.Sketchbook, demoMode = true, today = SAMPLE_TODAY), WidgetSnapshot.markPng(Look.Sketchbook))
}

/** iPhone 17 Pro Max's widget sizes, in points: small 170 square, medium 364 by 170. */
private const val SMALL = 170
private const val MEDIUM = 364

/**
 * The four widgets on a plain home screen, as the SwiftUI extension lays them out from [s] (BoringTravelWidget.swift):
 * Today small and medium, the Quick bar, the Quick bar small; and the Quick bar at a 6.1-inch phone's 329 points, where
 * Voice steps aside.
 */
private fun widgets(out: File, name: String, s: WidgetSnapshot, mark: ByteArray) {
    val stamp = Image.makeFromEncoded(mark).toComposeImageBitmap()
    // The home screen's own status bar is white on its wallpaper, not the app's: none drawn here.
    File(out, "$name.png").writeBytes(renderPhone(T_WIDTH, T_HEIGHT, T_DENSITY, frames = 4, statusBar = false) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF5B7FA6), Color(0xFF2E3F57))))) {
            Column(Modifier.padding(top = 120.dp, start = 24.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    WidgetFrame(SMALL) { TodayPicture(s, stamp, wide = false) }
                    WidgetFrame(SMALL) { QuickSmallPicture(s) }
                }
                WidgetFrame(MEDIUM) { TodayPicture(s, stamp, wide = true) }
                WidgetFrame(MEDIUM) { QuickBarPicture(s, stamp) }
                WidgetFrame(329) { QuickBarPicture(s, stamp) }
                Text("Drawn with Compose from the widgets' snapshot; on iPhone the widgets are SwiftUI.", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            }
        }
    })
}

@Composable
private fun WidgetFrame(width: Int, content: @Composable () -> Unit) {
    Box(Modifier.size(width.dp, SMALL.dp).clip(RoundedCornerShape(22.dp))) { content() }
}

private fun WidgetSnapshot.c(name: String): Color = Color((colors[name] ?: "#FF000000").removePrefix("#").toLong(16).toInt())

@Composable
private fun TodayPicture(s: WidgetSnapshot, stamp: ImageBitmap, wide: Boolean) {
    val d = s.days.first()
    Column(Modifier.fillMaxSize().background(s.c("surface")).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.foundation.Image(BitmapPainter(stamp), null, Modifier.size(28.dp))
            Column {
                Text(d.title, color = s.c("ink"), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d.line, color = s.c("muted"), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TodayButton("Write", s, primary = true, Modifier.weight(1f))
            if (wide) TodayButton("Photo", s, primary = false, Modifier.weight(1f))
            TodayButton("Voice", s, primary = false, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TodayButton(label: String, s: WidgetSnapshot, primary: Boolean, modifier: Modifier) {
    Box(modifier.height(40.dp).clip(CircleShape).background(if (primary) s.c("primary") else s.c("chip")), contentAlignment = Alignment.Center) {
        Text(label, color = if (primary) s.c("onPrimary") else s.c("ink"), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun QuickBarPicture(s: WidgetSnapshot, stamp: ImageBitmap) {
    val d = s.days.first()
    BoxWithConstraints(Modifier.fillMaxSize().drawBehind { paperOf(s) }) {
        val w = maxWidth.value.toInt()
        val showText = w >= s.quick.context
        val showMark = w >= s.quick.mark
        val showVoice = w >= s.quick.voice
        Row(
            Modifier.fillMaxSize().padding(start = if (showMark) 10.dp else 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, if (showText) Alignment.Start else Alignment.CenterHorizontally),
        ) {
            if (showMark) androidx.compose.foundation.Image(BitmapPainter(stamp), null, Modifier.padding(end = 2.dp).size(32.dp))
            if (showText) Column(Modifier.weight(1f)) {
                Text(d.primary, color = s.c("barInk"), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d.secondary, color = s.c("barMuted"), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            NotePicture(s, s.quick.note.toFloat())
            RoundPicture(s, BtIcons.Camera)
            if (showVoice) RoundPicture(s, BtIcons.Mic)
        }
    }
}

@Composable
private fun QuickSmallPicture(s: WidgetSnapshot) {
    BoxWithConstraints(Modifier.fillMaxSize().drawBehind { paperOf(s) }, contentAlignment = Alignment.Center) {
        val note = (maxWidth.value - 2 - 20 - 8 - 40).coerceIn(40f, s.quick.noteMax.toFloat())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NotePicture(s, note)
            RoundPicture(s, BtIcons.Camera)
        }
    }
}

@Composable
private fun NotePicture(s: WidgetSnapshot, width: Float) {
    Box(Modifier.size(width.dp, 40.dp), contentAlignment = Alignment.Center) {
        if (s.pill) Box(Modifier.fillMaxSize().clip(CircleShape).background(s.c("note")))
        else Box(Modifier.fillMaxSize().padding(vertical = 2.5.dp, horizontal = 1.5.dp).rotate(s.noteTilt).clip(RoundedCornerShape(8.dp)).background(s.c("note")))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(BtIcons.Edit, null, Modifier.size(16.dp), tint = s.c("onNote"))
            Text("Note", color = s.c("onNote"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun RoundPicture(s: WidgetSnapshot, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val edge = s.colors["buttonEdge"]
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(s.c("button"))
            .then(if (edge != null) Modifier.border(s.edgeWidth.dp, s.c("buttonEdge"), CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(20.dp), tint = s.c("onButton")) }
}

/** The theme's paper with its dots, rules or squares, centred (PaperGrid), as the widget's PaperView draws it. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.paperOf(s: WidgetSnapshot) {
    drawRect(s.c("paper"))
    val ink = s.colors["texture"]?.let { s.c("texture") } ?: return
    val unit = density
    when (s.paper) {
        "dots" -> {
            val step = s.dotStep * unit
            val r = (if (s.dotStep < PaperGrid.DOT_STEP) PaperGrid.DOT_RADIUS * 0.9f else PaperGrid.DOT_RADIUS) * unit
            for (y in PaperGrid.dots(size.height, step)) for (x in PaperGrid.dots(size.width, step)) drawCircle(ink, r, Offset(x, y))
        }
        "lines" -> for (y in PaperGrid.rules(size.height, PaperGrid.LINE_STEP * unit)) drawRect(ink, Offset(0f, y), Size(size.width, PaperGrid.RULE * unit))
        "grid" -> {
            for (y in PaperGrid.rules(size.height, PaperGrid.GRID_STEP * unit)) drawRect(ink, Offset(0f, y), Size(size.width, PaperGrid.RULE * unit))
            for (x in PaperGrid.rules(size.width, PaperGrid.GRID_STEP * unit)) drawRect(ink, Offset(x, 0f), Size(PaperGrid.RULE * unit, size.height))
        }
    }
}
