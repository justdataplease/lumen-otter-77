package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.calendar.CalendarSync
import com.justdataplease.boringtravel.calendar.MemoryPhoneCalendar
import com.justdataplease.boringtravel.clip.ClipShape
import com.justdataplease.boringtravel.compat.SystemBars
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.clip.FullScreenVideo
import com.justdataplease.boringtravel.ui.clip.VideoSpot
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.BtRoboto
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.io.File

/*
 * The second parity round's pictures (19 Nov 2026): Android's own video player for a note's video (playing, paused,
 * can't be played) and for a saved clip, Watch's full-screen video; "Preparing the video… 40%" in Write with its X and
 * the question before leaving; New trip's cover and Photos in notes, Edit trip's cover from the trip's photos; a plan
 * item's Journal section (empty, with notes); a turned map; the calendar's zone from the planned country; and the route
 * recording's Live Activity drawn with Compose from the same words as the SwiftUI view (RecordingLiveActivity.swift),
 * whose own drawing is seen only on a Mac. `gradlew :composeApp:screenshots -PshotsOnly=parity2` draws only these.
 */

/** A phone's library picker that hands over one file, written where a picker puts it. */
private class OnePick(private val files: MediaFiles, private val path: String, private val bytes: ByteArray, private val mime: String) : MediaPicker {
    override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) {
        files.write(path, bytes)
        done(listOf(PickedMedia(path, mime)))
    }

    override val hasCamera: Boolean = true
    override fun takePhoto(done: (PickedMedia?) -> Unit) = done(null)
    override fun recordVideo(done: (PickedMedia?) -> Unit) = done(null)
}

/** A phone's video work that says 40% and goes no further: "Preparing the video… 40%" stays for the picture. */
private object HalfwayVideoPrep : VideoPrep {
    override fun probe(path: String): VideoProbe = VideoProbe(20_000, 1920, 1080)

    override suspend fun transcode(source: String, out: String, probe: VideoProbe, bitrate: Int, shortSide: Int?, audioBitrate: Int, onProgress: (Float) -> Unit) {
        onProgress(0.4f)
        awaitCancellation()
    }
}

/** The drawn map turned to [bearing] wherever it is asked to go (two fingers turned it), a little wider so all still shows. */
private class TurnedMapKit(private val bearing: Double) : MapKit {
    @Composable
    override fun MapView(scene: MapScene, move: MapMove?, events: MapEvents, modifier: Modifier) =
        DrawnMapKit.MapView(scene, move?.let { it.copy(camera = it.camera.copy(bearing = bearing, zoom = it.camera.zoom - 0.4)) }, events, modifier)
}

fun secondParity(out: File) {
    runBlocking { CountryZones.load() }
    fun shoot(name: String, tall: Float = 1f, taps: List<String> = emptyList(), frames: Int = 30, content: @Composable () -> Unit) {
        val h = (PHONE_H * tall).toInt()
        File(out, "$name.png").writeBytes(
            if (taps.isEmpty()) render(PHONE_W, h, PHONE_DENSITY, frames, content) else renderTapping(PHONE_W, h, PHONE_DENSITY, frames, taps, content),
        )
    }

    // 1. The player: a note's video in Android's own full-screen player, playing, paused and one it can't play; a saved
    // clip from the Media tab (once, with the controls); Watch after an export (the studio's full-screen video).
    val (rich, richKit) = richSampleBook()
    val on = rich.sections(SAMPLE_TODAY).now!!
    val video = rich.snapshot.media.single { it.isVideo }
    richKit.files.write("exports/Kyoto-clip.mp4", ByteArray(4096) { (it % 199).toByte() })
    val drawn = DrawnVideoFiles(richKit.files, lengthMs = 45_000, width = 1920, height = 1080)
    val clipDrawn = DrawnVideoFiles(richKit.files, lengthMs = 42_000)
    // A kit per picture, made before it is drawn (a kit keeps its pictures; one made on each frame would start again).
    fun videoKit(playback: VideoPlayback = StillVideoPlayback(), story: Boolean = false) =
        MediaKit(richKit.files, frames = VideoFrames { p, max -> (if (story) clipDrawn else drawn).poster(p, max) }, playback = playback)
    val rows = { id: String -> rich.media(id) }
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        val playing = videoKit()
        shoot("player-note-playing-$id") { Themed(look, playing, rows) { PlayVideo(video) {} } }
        val paused = videoKit()
        shoot("player-note-paused-$id", taps = listOf("Pause")) { Themed(look, paused, rows) { PlayVideo(video) {} } }
    }
    val cant = videoKit(StillVideoPlayback({ null }))
    shoot("player-cant-play") { Themed(Look.Default, cant, rows) { PlayVideo(video) {} } }
    val clipKit = videoKit(StillVideoPlayback({ 42_000 }), story = true)
    shoot("player-media-clip") { Themed(Look.Default, clipKit, rows) { ClipViewer("exports/Kyoto-clip.mp4", {}, loop = false, controls = true) } }
    for ((name, taps) in listOf("clip-watch" to emptyList(), "clip-watch-playing" to listOf("Play video"))) {
        val watching = videoKit(StillVideoPlayback({ 42_000 }), story = true)
        shoot(name, taps = taps) { Themed(Look.Default, watching, rows) { FullScreenVideo("exports/Kyoto-clip.mp4", ClipShape.Story, remember { VideoSpot() }) {} } }
    }

    // 2. A note's video being made 720p: "Preparing the video… 40%" with its X, then Back's question. A book of its own
    // each, as Write keeps its draft.
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        for ((name, taps) in listOf("write-preparing-video-$id" to listOf("Choose photos and videos"), "write-stop-video-ask-$id" to listOf("Choose photos and videos", "Back"))) {
            val (book, kit) = richSampleBook()
            runBlocking { preload(book, kit) }
            val trip = book.sections(SAMPLE_TODAY).now!!
            val picker = OnePick(kit.files, "picked/harbour.mov", ByteArray(2048) { (it % 97).toByte() }, "video/quicktime")
            val writing = MediaKit(kit.files, picker, frames = VideoFrames { p, max -> DrawnVideoFiles(kit.files).poster(p, max) }, videos = HalfwayVideoPrep)
            shoot(name, taps = taps) { Themed(look, writing, { m: String -> book.media(m) }) { WriteScreen(book, trip.id, PHOTO_NOTE, SAMPLE_TODAY, {}) } }
        }
    }

    // 3. New trip's cover and Photos in notes (Android's defaults: Full, Rounded), then a cover picked; Edit trip offers
    // the trip's own photos, and one chosen.
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        val empty = TripBook(MemoryTripFile())
        val files = MemoryMediaFiles()
        val kit = MediaKit(files, OnePick(files, "picked/cover.jpg", samplePhoto(Scene.Olive, 1600, 1000), MediaTypes.JPEG))
        shoot("new-trip-cover-$id", tall = 1.15f) {
            Themed(look, kit, { m: String -> empty.media(m) }) { TripFormScreen(empty, null, {}, {}, manualAtStart = true, settings = AppSettings.ready(look)) }
        }
        val picked = TripBook(MemoryTripFile())
        shoot("new-trip-cover-picked-$id", tall = 1.15f, taps = listOf("Add a cover photo")) {
            Themed(look, kit, { m: String -> picked.media(m) }) { TripFormScreen(picked, null, {}, {}, manualAtStart = true, settings = AppSettings.ready(look)) }
        }
        shoot("edit-trip-cover-$id", tall = 1.15f) {
            Themed(look, richKit, rows) { TripFormScreen(rich, on.id, {}, {}, settings = AppSettings.ready(look)) }
        }
    }
    shoot("edit-trip-cover-chosen", tall = 1.15f, taps = listOf("Use as cover")) {
        Themed(Look.Default, richKit, rows) { TripFormScreen(rich, on.id, {}, {}, settings = AppSettings.ready()) }
    }

    // 4. A plan item's sheet ends with its Journal: nothing yet, then two notes written about it.
    for ((name, notes) in listOf("item-journal-empty" to false, "item-journal-notes" to true)) {
        val book = sampleBook()
        val trip = book.sections(SAMPLE_TODAY).now!!
        val items = book.items(trip.id)
        val item = items.firstOrNull { it.day == SAMPLE_TODAY && it.lat != null && PlanKind.of(it.kind) != PlanKind.Transport }
            ?: items.first { it.title.startsWith("Evening walk") }
        if (notes) {
            val place = ItemNotes.location(item, items)
            val zone = "Asia/Tokyo"
            fun about(id: String, title: String, text: String, hour: Int) = book.saveNote(
                Entry(id, trip.id, occurredAt = startOfDayMs(SAMPLE_TODAY, zone) + hour * 3_600_000L, zoneId = zone, title = title, placeName = place, createdAt = 0L, updatedAt = 0L),
                EntryDoc.ofText(text),
            )
            about("about-1", "Early, before the crowds", "The gates were still wet from the night. Only a few people on the path.", 8)
            about("about-2", "", "Came back at dusk for the lanterns.", 18)
        }
        for (look in listOf(Look.PostcardPop, Look.Trail)) {
            shoot("$name-${look.id}", tall = 1.6f) {
                Themed(look, MediaKit.memory(), { m: String -> book.media(m) }) {
                    TripPage(book, trip.id, SAMPLE_TODAY, 0, {}, {}, {}, {}, {}, {}, sheetAtStart = SheetTarget(item, item.day), settings = AppSettings.ready(look))
                }
            }
        }
    }

    // 5. A map turned by two fingers (MapLibre turns it on iPhone; no compass, no tilt, as Android's).
    val patagonia = patagoniaBook()
    val pat = patagonia.trips(SAMPLE_TODAY).single()
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        shoot("map-turned-${look.id}") {
            CompositionLocalProvider(LocalWeb provides Recorded.web(), LocalTrackProcessor provides null, LocalMapKit provides TurnedMapKit(35.0)) {
                Themed(look, MediaKit.memory(), { null }) {
                    TripMapBoard(patagonia, pat.id, SAMPLE_TODAY, 0, AppSettings.ready(look), {}, MapActions(), plannedAtStart = true)
                }
            }
        }
    }

    // 6. The calendar's zone from the planned places' country (Denmark: Copenhagen, as Android's ICU names it).
    val cph = TripBook(MemoryTripFile())
    val cphTrip = cph.newTrip("Copenhagen", SAMPLE_TODAY.plusDays(10), SAMPLE_TODAY.plusDays(13), subtitle = "Nyhavn and Tivoli")
    for ((n, spot) in listOf(Triple("Nyhavn canal tour", 55.6798, 12.5911), Triple("Tivoli Gardens", 55.6737, 12.5681)).withIndex()) {
        val i = cph.addItem(cphTrip.id, spot.first, SAMPLE_TODAY.plusDays(10L + n), PlanKind.Activity, timeMinutes = 10 * 60 + n * 8 * 60)!!
        cph.saveItem(i.copy(lat = spot.second, lng = spot.third))
    }
    val cphSettings = AppSettings.ready()
    shoot("calendar-zone-country", frames = 24) {
        val sync = remember { CalendarSync(cph, cphSettings.calendarLinks, MemoryPhoneCalendar(), countries = { CountryOutlines.loaded }, countryZones = CountryZones::of) }
        CompositionLocalProvider(LocalCalendar provides sync) {
            Themed(Look.Default, MediaKit.memory(), { null }) {
                TripPage(cph, cphTrip.id, SAMPLE_TODAY, 0, {}, {}, {}, {}, {}, {}, settings = cphSettings, calendarAtStart = true)
            }
        }
    }

    // 7. The route recording's Live Activity, from the words the app hands the phone (TrackingNotes.recording).
    val card = TrackingNotes.recording("Kyoto in autumn", TrackingLevel.Balanced)
    lockScreen(out, "live-activity-lock-ios17", card, stop = true)
    lockScreen(out, "live-activity-lock-ios16", card, stop = false)
    island(out, "live-activity-island-compact", card, expanded = false)
    island(out, "live-activity-island-expanded", card, expanded = true)
    println("Second parity pictures in ${out.absolutePath}")
}

/** A screen as the app frames it, in [look], over the media of [kit] (rows by id from [rows]). */
@Composable
private fun Themed(look: Look, kit: MediaKit, rows: (String) -> com.justdataplease.boringtravel.data.db.Media?, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalMediaKit provides kit, LocalMediaRows provides rows, LocalAppVersion provides PREVIEW_VERSION) {
        BoringTravelTheme(look) {
            DisposableEffect(look) {
                SystemBars.set(!look.dark)
                onDispose { }
            }
            Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(PHONE_FRAME)) { content() }
        }
    }

private val WALLPAPER = Brush.verticalGradient(listOf(Color(0xFF3E5C7E), Color(0xFF7A8FA6), Color(0xFF2B3546)))

private const val DRAWN_NOTE = "Drawn with Compose from the words the app hands the phone; on iPhone the Live Activity is SwiftUI " +
    "(iosApp/BoringTravelWidget/RecordingLiveActivity.swift), whose own drawing is seen only on a Mac."

/** The Lock Screen with the recording card at its foot: Stop on iOS 17 and later; none before (a tap opens Route tracking). */
private fun lockScreen(out: File, name: String, card: RecordingCardText, stop: Boolean) =
    File(out, "$name.png").writeBytes(render(PHONE_W, PHONE_H, PHONE_DENSITY, frames = 4) {
        DisposableEffect(Unit) {
            SystemBars.set(false)
            onDispose { }
        }
        Box(Modifier.fillMaxSize().background(WALLPAPER)) {
            Column(Modifier.fillMaxWidth().padding(top = 76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Wednesday 18 November", color = Color.White.copy(alpha = 0.9f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text("9:41", color = Color.White.copy(alpha = 0.92f), fontSize = 104.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(40.dp))
                Text(
                    if (stop) "iOS 17 and later: Stop on the card." else "iOS 16.1 to 16.x: no button; a tap opens Me › Route tracking, where Stop for today is.",
                    Modifier.padding(horizontal = 32.dp), color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(DRAWN_NOTE, Modifier.padding(horizontal = 32.dp), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 128.dp)) {
                RecordingCardPicture(card, stop)
            }
            // The flashlight and camera buttons, and the home indicator.
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 46.dp, end = 46.dp, bottom = 46.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                for (icon in listOf(BtIcons.Sparkle, BtIcons.Camera)) {
                    Box(Modifier.size(50.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(22.dp), tint = Color.White)
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp).size(width = 144.dp, height = 5.dp).clip(CircleShape).background(Color.White))
        }
    })

/** The Dynamic Island with the recording on: compact (the plane and the location mark either side), or held open. */
private fun island(out: File, name: String, card: RecordingCardText, expanded: Boolean) =
    File(out, "$name.png").writeBytes(renderPhone(PHONE_W, PHONE_H, PHONE_DENSITY, frames = 4, statusBar = !expanded) {
        DisposableEffect(Unit) {
            SystemBars.set(false)
            onDispose { }
        }
        Box(Modifier.fillMaxSize().background(WALLPAPER)) {
            if (expanded) {
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 11.dp, start = 11.dp, end = 11.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(44.dp)).background(Color.Black).padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 18.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(37.dp), contentAlignment = Alignment.CenterStart) {
                        Icon(BtIcons.Plane, null, Modifier.size(22.dp), tint = Color.White)
                    }
                    Text(
                        card.title, Modifier.fillMaxWidth(), color = Color.White, fontFamily = BtRoboto.family, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(card.trip, Modifier.weight(1f), color = Color.White.copy(alpha = 0.6f), fontFamily = BtRoboto.family, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        StopPicture(card.stop, Color.White)
                    }
                }
            } else {
                Row(
                    Modifier.align(Alignment.TopCenter).offset(y = 11.dp).size(width = 216.dp, height = 37.dp)
                        .background(Color.Black, RoundedCornerShape(19.dp)).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(BtIcons.Plane, null, Modifier.size(18.dp), tint = Color.White)
                    Spacer(Modifier.weight(1f))
                    Icon(BtIcons.MyLocation, null, Modifier.size(17.dp), tint = Color(0xFF0A84FF))
                }
            }
            Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (expanded) "Held open: the title, the trip and Stop (iOS 17 and later)." else "Compact, while another app is open: the plane and the location mark.",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(DRAWN_NOTE, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
    })

/**
 * The recording card as RecordingCardView lays it out (RecordingLiveActivity.swift): the plane and "BoringTravel", the
 * title, the trip, and Stop when [stop]; Roboto, on the Lock Screen's light material.
 */
@Composable
fun RecordingCardPicture(card: RecordingCardText, stop: Boolean, modifier: Modifier = Modifier) {
    val ink = Color.Black
    val secondary = Color(0x993C3C43)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.82f)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(BtIcons.Plane, null, Modifier.size(14.dp), tint = ink)
                Text("BoringTravel", color = secondary, fontFamily = BtRoboto.family, fontSize = 12.sp)
            }
            Text(card.title, color = ink, fontFamily = BtRoboto.family, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (card.trip.isNotEmpty()) Text(card.trip, color = secondary, fontFamily = BtRoboto.family, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        if (stop) StopPicture(card.stop, ink)
    }
}

/** Stop as the SwiftUI button draws it: the primary colour's 14% capsule, its word in Roboto Medium. */
@Composable
private fun StopPicture(word: String, ink: Color) {
    Box(Modifier.clip(CircleShape).background(ink.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(word, color = ink, fontFamily = BtRoboto.family, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
