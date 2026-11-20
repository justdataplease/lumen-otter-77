package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.art.SceneKind
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.export.ExportOptions
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.components.BrandMark
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File

/*
 * Round 28's pictures, the leftover lines: Android's toasts as the app's pill (over the plan and its bar, over Trips, on a
 * place page), the quick camera's note, the Media tab's Add media after the photo library came back (the library itself
 * is iOS's own sheet: it can't be drawn here), a page break in Write and the pages it prints, the book's PDF errors, the
 * ten drawn scenes in every theme, the brand mark in every theme, Lisbon's drawn photos in other themes (and its print
 * in Postcard Pop) and the widget gallery's preview. `gradlew :composeApp:screenshots -PshotsOnly=leftovers` draws only
 * these.
 */
fun leftovers(out: File) {
    fun drive(name: String, content: @Composable () -> Unit, steps: CommunityDriver.() -> Unit) {
        CommunityDriver(PHONE_W, PHONE_H, content).use { d ->
            d.frames(30)
            d.steps()
            d.frames(8)
            File(out, "$name.png").writeBytes(d.png())
        }
    }

    // ---------------------------------------------------------------- 109: Android's toasts
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val book = smallTripBook()
        val trip = book.trips(SAMPLE_TODAY).single()
        drive("toast-copied-${look.id}", {
            CompositionLocalProvider(LocalPhone provides Phone(copy = {})) {
                BoringTravelApp(book, AppSettings.ready(look), SAMPLE_TODAY, PHONE_FRAME, startAt = listOf(Place.Trip(trip.id)), version = PREVIEW_VERSION)
            }
        }) {
            holdAt(clickCentre("Open Hotel Grotta"))
            frames(6)
            tapText("Copy name")
        }
    }
    run {
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos", SAMPLE_TODAY.minusDays(1), SAMPLE_TODAY.plusDays(3))
        val at = startOfDayMs(SAMPLE_TODAY, null) + 10 * 3_600_000L
        book.putRows(stops = listOf(Stop("stop-1", trip.id, at, at + 7_200_000L, 37.1036, 25.3766, "Chora, Naxos, Greece", "GR", createdAt = at, updatedAt = at)))
        drive("toast-added-to-plan", {
            BoringTravelApp(book, AppSettings.ready(), SAMPLE_TODAY, PHONE_FRAME, startAt = listOf(Place.Trip(trip.id), Place.PlacePage(trip.id, stopId = "stop-1")), version = PREVIEW_VERSION)
        }) { tapText("Add to plan") }
    }
    run {
        val book = sampleBook()
        val files = MemoryMediaFiles()
        val camera = object : MediaPicker {
            override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) = done(emptyList())
            override val hasCamera = true
            // A shot whose file never came (the camera's copy gone): the photo can't be added.
            override fun takePhoto(done: (PickedMedia?) -> Unit) = done(PickedMedia("picked/shot.jpg", MediaTypes.JPEG))
            override fun recordVideo(done: (PickedMedia?) -> Unit) = done(null)
        }
        drive("toast-photo-not-added", {
            BoringTravelApp(book, AppSettings.ready(), SAMPLE_TODAY, PHONE_FRAME, version = PREVIEW_VERSION, media = MediaKit(files, camera))
        }) { tapDescription("Take a photo") }
    }

    // ---------------------------------------------------------------- the quick camera's note, made at once
    run {
        val book = sampleBook()
        val files = MemoryMediaFiles()
        val camera = object : MediaPicker {
            override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) = done(emptyList())
            override val hasCamera = true
            override fun takePhoto(done: (PickedMedia?) -> Unit) {
                files.write("picked/shot.jpg", samplePhoto(Scene.Sunset, 1200, 900))
                done(PickedMedia("picked/shot.jpg", MediaTypes.JPEG))
            }
            override fun recordVideo(done: (PickedMedia?) -> Unit) = done(null)
        }
        drive("write-camera-note", {
            BoringTravelApp(book, AppSettings.ready(), SAMPLE_TODAY, PHONE_FRAME, version = PREVIEW_VERSION, media = MediaKit(files, camera))
        }) {
            tapDescription("Take a photo")
            frames(30)
        }
    }

    // ---------------------------------------------------------------- 461: the Media tab's Add media
    run {
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos", SAMPLE_TODAY.minusDays(1), SAMPLE_TODAY.plusDays(3))
        val files = MemoryMediaFiles()
        val library = object : MediaPicker {
            override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) {
                files.write("picked/a.jpg", samplePhoto(Scene.Sea, 1200, 900))
                files.write("picked/b.jpg", samplePhoto(Scene.Olive, 1200, 900))
                done(listOf(PickedMedia("picked/a.jpg", MediaTypes.JPEG), PickedMedia("picked/b.jpg", MediaTypes.JPEG)))
            }
            override val hasCamera = true
            override fun takePhoto(done: (PickedMedia?) -> Unit) = done(null)
            override fun recordVideo(done: (PickedMedia?) -> Unit) = done(null)
        }
        drive("media-add-media", {
            BoringTravelApp(book, AppSettings.ready(), SAMPLE_TODAY, PHONE_FRAME, startAt = listOf(Place.Trip(trip.id, TripTab.Media)), version = PREVIEW_VERSION, media = MediaKit(files, library))
        }) {}
        drive("media-add-media-picked", {
            BoringTravelApp(book, AppSettings.ready(), SAMPLE_TODAY, PHONE_FRAME, startAt = listOf(Place.Trip(trip.id, TripTab.Media)), version = PREVIEW_VERSION, media = MediaKit(files, library))
        }) {
            tapText("Add media")
            frames(40)
        }
    }

    // ---------------------------------------------------------------- 477: a page break, in Write and in print
    run {
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos", SAMPLE_TODAY, SAMPLE_TODAY.plusDays(1))
        val at = startOfDayMs(SAMPLE_TODAY, "Europe/Athens") + 19 * 3_600_000L
        val doc = EntryDoc(
            blocks = listOf(
                Block.Paragraph("The ferry was late, so we sat on the harbour wall with a bag of peaches."),
                Block.PageBreak,
                Block.Heading("Chora at night", 2),
                Block.Paragraph("Lanterns over the lanes, and a cat on every step."),
            ),
        )
        val note = book.saveNote(com.justdataplease.boringtravel.data.db.Entry("pb", trip.id, at, "Europe/Athens", title = "Two pages", createdAt = at, updatedAt = at), doc)!!
        drive("write-page-break", {
            BoringTravelApp(book, AppSettings.ready(), SAMPLE_TODAY, PHONE_FRAME, startAt = listOf(Place.Trip(trip.id, TripTab.Journal), Place.Write(trip.id, note.id)), version = PREVIEW_VERSION)
        }) {}
        drive("note-page-break", {
            BoringTravelApp(book, AppSettings.ready(), SAMPLE_TODAY, PHONE_FRAME, startAt = listOf(Place.Trip(trip.id, TripTab.Journal), Place.Note(trip.id, note.id)), version = PREVIEW_VERSION)
        }) {}
        // The pages it prints (the desktop's stand-in of WebKit's print, from the same HTML): the part after the break on
        // a page of its own.
        runBlocking {
            val files = MemoryMediaFiles()
            val exports = TripExports(book, files, VideoFrames { _, _ -> null }, DesktopPagePrinter())
            val pdf = exports.pdf(exports.load(trip.id), ExportOptions()) {}
            PagesPdf.read(files.read(pdf)!!).forEachIndexed { i, jpeg ->
                Image.makeFromEncoded(jpeg).use { image ->
                    File(out, "print-page-break-p${i + 1}.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 911: the book's PDF errors
    run {
        val (book, kit) = demoBook()
        val settings = AppSettings.ready(Look.PostcardPop).apply { demoMode = true }
        val ways = listOf(
            "slow" to object : PagePrinter by DesktopPagePrinter() {
                override suspend fun writeBook(files: MediaFiles, htmlPath: String, html: String, pdfPath: String, sheet: BookSheetSize, ground: Long): Int =
                    withTimeout(1) { delay(1_000); 0 }
            },
            "empty" to object : PagePrinter by DesktopPagePrinter() {
                override suspend fun writeBook(files: MediaFiles, htmlPath: String, html: String, pdfPath: String, sheet: BookSheetSize, ground: Long): Int = 0
            },
            "failed" to object : PagePrinter by DesktopPagePrinter() {
                override suspend fun writeBook(files: MediaFiles, htmlPath: String, html: String, pdfPath: String, sheet: BookSheetSize, ground: Long): Int =
                    throw IllegalStateException("WebKit gave up")
            },
        )
        for ((how, printer) in ways) {
            File(out, "book-error-$how.png").writeBytes(
                render(PHONE_W, PHONE_H, PHONE_DENSITY, 80) {
                    CompositionLocalProvider(LocalMediaKit provides kit, LocalMediaRows provides { id: String -> book.media(id) }, LocalPagePrinter provides printer) {
                        BoringTravelTheme(Look.PostcardPop) {
                            Box(Modifier.fillMaxSize().background(StudioColors.bg).padding(PHONE_FRAME)) {
                                // Send to Drive makes the PDF first: here it goes wrong before Drive is asked.
                                BookScreen(book, SampleArgentina.TRIP_ID, settings, onBack = {}, startWith = BookStart.Drive)
                            }
                        }
                    }
                },
            )
        }
    }

    // ---------------------------------------------------------------- 1385, 1386: the drawn scenes and the brand mark
    for (look in Look.entries) {
        File(out, "scenes-${look.id}.png").writeBytes(
            render(PHONE_W, PHONE_H, PHONE_DENSITY, 4) {
                BoringTravelTheme(look) {
                    Column(Modifier.fillMaxSize().paper(Bt.tokens).padding(PHONE_FRAME).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Drawn scenes · ${look.label}", style = Bt.text.title)
                        SceneKind.entries.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { kind ->
                                    Column(Modifier.width(196.dp)) {
                                        DrawnScene(kind, Modifier.width(196.dp).height(118.dp), key = "gallery-${kind.id}")
                                        Text(kind.label, Modifier.padding(top = 2.dp), style = Bt.text.sub)
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
    File(out, "brand-mark-looks.png").writeBytes(
        render(PHONE_W, PHONE_H, PHONE_DENSITY, 4) {
            Column(Modifier.fillMaxSize().padding(PHONE_FRAME).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (look in Look.entries) {
                    BoringTravelTheme(look) {
                        Row(Modifier.paper(Bt.tokens).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            BrandMark()
                            BrandMark(size = 56.dp)
                            BrandMark(size = 28.dp)
                            Text(look.label, style = Bt.text.item)
                        }
                    }
                }
            }
        },
    )

    // ---------------------------------------------------------------- 1404: Lisbon's drawn photos in other themes
    run {
        val (demo, kit) = demoBook()
        val lisbonNote = demo.entries(SampleTrips.LISBON).first()
        for ((look, at) in listOf(
            Look.Trail to listOf(Place.Trip(SampleTrips.LISBON, TripTab.Journal)),
            Look.Market to listOf(Place.Trip(SampleTrips.LISBON, TripTab.Media)),
            Look.StickerBook to listOf(Place.Trip(SampleTrips.LISBON, TripTab.Journal), Place.Note(SampleTrips.LISBON, lisbonNote.id)),
        )) {
            val name = when (at.last()) {
                is Place.Note -> "demo-lisbon-note-${look.id}"
                else -> "demo-lisbon-${(at.last() as Place.Trip).tab?.key}-${look.id}"
            }
            File(out, "$name.png").writeBytes(
                render(PHONE_W, PHONE_H, PHONE_DENSITY, 12) {
                    BoringTravelApp(demo, AppSettings.ready(look).apply { demoMode = true }, SAMPLE_TODAY, PHONE_FRAME, startAt = at, version = PREVIEW_VERSION, media = kit)
                },
            )
        }
        // Its print, made from the files: Postcard Pop's colours while the app shows Trail's.
        runBlocking {
            val exports = TripExports(demo, kit.files, VideoFrames { _, _ -> null }, DesktopPagePrinter())
            val pdf = exports.pdf(exports.load(SampleTrips.LISBON), ExportOptions()) {}
            val pages = PagesPdf.read(kit.files.read(pdf)!!)
            val page = pages.indexOfFirst { it.size > 60_000 }.coerceAtLeast(1).coerceAtMost(pages.lastIndex)
            Image.makeFromEncoded(pages[page]).use { image ->
                File(out, "demo-lisbon-print.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }

    // ---------------------------------------------------------------- 1325: the widget gallery's preview
    run {
        val base = WidgetSnapshot.build(TripBook(MemoryTripFile()), Look.PostcardPop, demoMode = false, today = SAMPLE_TODAY)
        // BoringTravelWidget.swift's Snapshot.preview: Android's widget_preview_day and widget_preview_trip.
        val preview = base.copy(
            days = listOf(base.days.first().copy(title = "Argentina & Patagonia", line = "Day 14 of 31 · 2 written today", primary = "Day 14 · El Chaltén", secondary = "Argentina & Patagonia")),
        )
        widgets(out, "widgets-gallery-preview", preview, WidgetSnapshot.markPng(Look.PostcardPop))
    }
}
