package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import com.justdataplease.boringtravel.compat.SystemBars
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.EntryRow
import com.justdataplease.boringtravel.calendar.MemoryPhoneCalendar
import com.justdataplease.boringtravel.data.settings.TripsLook
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.ProgramLibrary
import com.justdataplease.boringtravel.tags.ItemTags
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.ui.theme.FontSet
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.ui.theme.TextFont
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import androidx.compose.ui.graphics.asSkiaBitmap
import java.io.File

/** An iPhone 17 Pro Max: 440 x 956 points at 3x, with its status bar and home indicator kept clear. */
private const val WIDTH = 1320
private const val HEIGHT = 2868
private const val DENSITY = 3f
private val FRAME = PaddingValues(top = 62.dp, bottom = 34.dp)

/** A fixed "today", so the pictures are the same on every run. */
val SAMPLE_TODAY: LocalDate = localDate(2026, 11, 18)

/**
 * A traveller a few days into a trip made from the Kyoto idea, with one coming up and one for someday: every row made
 * through the engine's trip store, as the app makes them.
 */
fun sampleBook(today: LocalDate = SAMPLE_TODAY): TripBook {
    var clock = 1_795_000_000_000L
    var n = 0
    val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "trip-${n++}" })
    val kyoto = ProgramLibrary.destinations.first()
    val trip = book.startFromIdea(kyoto, kyoto.default, Pace.Default, today.minusDays(2))
    book.items(trip.id).filter { item -> item.day?.let { it.toEpochDay() < today.toEpochDay() } == true }.forEach { book.setDone(it.id, true) }
    book.nameDay(trip.id, today, "Temples and tea")
    val walk = book.addItem(trip.id, "Evening walk by the Kamo river", today, PlanKind.Activity, timeMinutes = 19 * 60 + 30)!!
    book.saveItem(walk.copy(endMinutes = 21 * 60, note = "From Sanjo bridge", tags = ItemTags.format(listOf("sunset", "riverwalk"))))
    book.writeNote(
        trip.id, "Landed at Kansai before noon.\n\nThe lanterns in Gion were already lit when we walked back.", title = "First night",
        at = startOfDayMs(today.minusDays(2), kyoto.zoneId) + 21 * 3_600_000L, zoneId = kyoto.zoneId,
    )
    book.newTrip("Naxos", localDate(2027, 6, 17), localDate(2027, 6, 26), subtitle = "Cyclades by ferry")
    book.newTrip("Iceland ring road")
    return book
}

/**
 * A short trip made by hand on the screens: a ferry, a stay over three nights (its check-out its own row on a later
 * day), a timed dinner, an idea and two notes, so one page shows every kind of row and the journal.
 */
fun smallTripBook(today: LocalDate = SAMPLE_TODAY): TripBook {
    var clock = 1_795_000_000_000L
    var n = 0
    val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "small-${n++}" })
    val start = today.plusDays(1)
    val trip = book.newTrip("Naxos", start, start.plusDays(3), subtitle = "Paros → Naxos")
    book.saveItem(book.draftItem(trip.id, start, 1.0).copy(title = "Ferry from Paros", kind = PlanKind.Transport.id, timeMinutes = 9 * 60 + 15, endMinutes = 10 * 60 + 5))
    book.saveItem(
        book.draftItem(trip.id, start, 2.0).copy(
            title = "Hotel Grotta", kind = PlanKind.Stay.id, timeMinutes = 15 * 60, endDays = 3, endMinutes = 11 * 60, note = "Sea view room",
        ),
    )
    book.saveItem(book.draftItem(trip.id, start.plusDays(1), 1.0).copy(title = "Dinner in Chora", kind = PlanKind.Food.id, timeMinutes = 20 * 60, note = "Book a table"))
    book.addItem(trip.id, "Portara at sunset", null)
    book.writeNote(trip.id, "Packed light. The ferry leaves at nine.", title = "Before we go", at = startOfDayMs(today, "Europe/Athens") + 20 * 3_600_000L, zoneId = "Europe/Athens")
    book.writeNote(trip.id, "Bought the ferry tickets online.", at = startOfDayMs(today.minusDays(3), "Europe/Athens") + 10 * 3_600_000L, zoneId = "Europe/Athens")
    return book
}

/**
 * Two notes written when no trip was on, put into [book] the way a restore brings them (the iPhone writes notes in
 * trips only): what Notes outside trips lists.
 */
fun withLooseNotes(book: TripBook, today: LocalDate = SAMPLE_TODAY): TripBook {
    val data = BackupJson.decodeFromString(BackupData.serializer(), book.export())
    fun loose(id: String, daysAgo: Long, title: String, text: String): EntryRow {
        val at = startOfDayMs(today.minusDays(daysAgo), "Europe/Athens") + 18 * 3_600_000L
        val doc = EntryDoc.ofText(text)
        return EntryRow(id, tripId = null, occurredAt = at, zoneId = "Europe/Athens", title = title, body = doc.toJson(), plainText = text, placeName = "Athens", createdAt = at, updatedAt = at)
    }
    val notes = listOf(
        loose("loose-1", 40, "Ideas for spring", "Ferries to the small islands start in April. Look at Folegandros."),
        loose("loose-2", 9, "", "Bought a new rucksack, 30 litres. Fits under a plane seat."),
    )
    book.restore(BackupJson.encodeToString(BackupData.serializer(), data.copy(entries = data.entries + notes)))
    return book
}

/** Writes a PNG of every screen into the folder given as the first argument: `gradlew :composeApp:screenshots`. */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots").apply { mkdirs() }
    runBlocking {
        BtFontFiles.load()
        CountryOutlines.load()
        CountryZones.load()
    }
    if (args.getOrNull(1) == "clip") {
        val (demo, demoKit) = demoBook()
        clips(out, demo, demoKit)
        return
    }
    // `-PshotsOnly=book`: only the book (round 21).
    if (args.getOrNull(1) == "book") {
        books(out)
        return
    }
    // `-PshotsOnly=parity`: only the parity round's pictures (Greek, sideways, the status bar, the screens it changed).
    if (args.getOrNull(1) == "parity") {
        parity(out)
        return
    }
    // `-PshotsOnly=parity2`: only the second parity round's pictures (the player, a note's video, the cover, the item's
    // Journal, the turned map, the calendar's zone, the Live Activity).
    if (args.getOrNull(1) == "parity2") {
        secondParity(out)
        return
    }
    // `-PshotsOnly=book-editor`: only the book's page editor and its tools (round 22).
    if (args.getOrNull(1) == "book-editor") {
        bookEditor(out)
        return
    }
    // `-PshotsOnly=accounts`: only the account, Drive's backups and the Drive buttons (round 23).
    if (args.getOrNull(1) == "accounts") {
        accounts(out)
        return
    }
    fun app(name: String, book: TripBook, vararg at: Place, look: Look = Look.Default, settings: AppSettings = AppSettings.ready(look), height: Int = HEIGHT, web: Web? = null) =
        shot(File(out, "$name.png"), height = height) {
            BoringTravelApp(book, settings, SAMPLE_TODAY, FRAME, startAt = at.toList(), version = PREVIEW_VERSION, web = web ?: Web.offline())
        }

    // The first start, before Trips.
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        app("welcome-${look.id}", TripBook(MemoryTripFile()), settings = AppSettings(MemorySettingsFile()).apply { if (look != Look.Default) this.look = look })
    }

    // A new traveller: no trips yet, the trip ideas.
    for (look in Look.entries) app("trips-${look.id}", TripBook(MemoryTripFile()), look = look)
    app("ideas", TripBook(MemoryTripFile()), Place.Ideas)
    app("idea", TripBook(MemoryTripFile()), Place.Idea(ProgramLibrary.destinations.first().id))
    screen(out, "idea-start") { TripIdea(TripBook(MemoryTripFile()), ProgramLibrary.destinations.first().id, {}, {}, pickingAtStart = true, initialStart = localDate(2027, 4, 2)) }
    app("new-trip", TripBook(MemoryTripFile()), Place.NewTrip)
    screen(out, "new-trip-form") { TripFormScreen(TripBook(MemoryTripFile()), null, {}, {}, manualAtStart = true) }

    // A traveller with trips of their own.
    val book = sampleBook()
    val current = book.sections(SAMPLE_TODAY).now!!
    val naxos = book.sections(SAMPLE_TODAY).next.single()
    val iceland = book.sections(SAMPLE_TODAY).someday.single()
    val walk = book.items(current.id).single { it.title.startsWith("Evening walk") }
    val note = book.entries(current.id).single()
    app("my-trips", book)
    app("trip", book, Place.Trip(current.id))
    val small = smallTripBook()
    app("trip-stay", small, Place.Trip(small.trips(SAMPLE_TODAY).single().id))
    app("trip-naxos", book, Place.Trip(naxos.id))
    app("trip-someday", book, Place.Trip(iceland.id))
    app("edit-trip", book, Place.Trip(naxos.id), Place.EditTrip(naxos.id))
    screen(out, "edit-trip-dates") { TripFormScreen(book, naxos.id, {}, {}, pickingAtStart = true) }
    screen(out, "trip-menu") { tripPage(book, current.id, menu = true) }
    screen(out, "item-new", frames = 24) { tripPage(book, current.id, sheet = SheetTarget(null, SAMPLE_TODAY)) }
    screen(out, "item-edit", frames = 24) { tripPage(book, current.id, sheet = SheetTarget(walk, walk.day)) }
    screen(out, "item-time", frames = 24) { itemSheet(book, current.id, walk, SheetAsk.Time) }
    screen(out, "item-delete", frames = 24) { itemSheet(book, current.id, walk, SheetAsk.Delete) }
    app("note", book, Place.Trip(current.id), Place.Note(current.id, note.id))
    screen(out, "note-delete") { NoteScreen(book, note.id, 0, {}, {}, {}, deletingAtStart = true) }
    app("write", book, Place.Trip(current.id), Place.Write(current.id, note.id))
    app("write-new", book, Place.Trip(current.id), Place.Write(current.id))
    // The sheet and the form in the dark looks too: their colours come from the theme.
    for (look in Look.entries.filter { it.dark }) {
        screen(out, "item-edit-${look.id}", look, frames = 24) { tripPage(book, current.id, sheet = SheetTarget(walk, walk.day)) }
        app("edit-trip-${look.id}", book, Place.Trip(naxos.id), Place.EditTrip(naxos.id), look = look)
    }

    // The bar in every look, in both its modes: Trips with the trips, and a trip's Journal.
    for (look in Look.entries) {
        app("my-trips-${look.id}", book, look = look)
        app("trip-journal-${look.id}", book, Place.Trip(current.id, TripTab.Journal), look = look)
    }
    for (look in listOf(Look.PostcardPop, Look.Market)) {
        app("trip-journal-empty-${look.id}", book, Place.Trip(naxos.id, TripTab.Journal), look = look)
    }
    for (look in listOf(Look.PostcardPop, Look.StickerBook)) {
        app("trip-map-${look.id}", book, Place.Trip(current.id, TripTab.Map), look = look, web = Recorded.web())
        app("trip-media-${look.id}", book, Place.Trip(current.id, TripTab.Media), look = look)
    }
    // Notes outside trips: the card on Trips, the list, one note.
    val loose = withLooseNotes(sampleBook())
    val looseNote = loose.snapshot.entries.first { it.tripId == null }
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        app("trips-loose-${look.id}", loose, look = look)
        app("loose-notes-${look.id}", loose, Place.LooseNotes, look = look)
    }
    app("loose-note", loose, Place.LooseNotes, Place.Note(null, looseNote.id))
    app("loose-notes-empty", TripBook(MemoryTripFile()), Place.LooseNotes)

    // The journal with photos: a note written on Android (formatting, lists, a layout, a checklist), read, edited and
    // its photos full screen; a note with a photo, a layout and a video, for the editor's photo tools.
    val (rich, kit) = richSampleBook()
    val on = rich.sections(SAMPLE_TODAY).now!!
    runBlocking { preload(rich, kit) }
    val video = rich.snapshot.media.single { it.isVideo }
    fun withMedia(name: String, vararg at: Place, look: Look = Look.Default, height: Int = HEIGHT) =
        shot(File(out, "$name.png"), frames = 12, height = height) {
            BoringTravelApp(rich, AppSettings.ready(look), SAMPLE_TODAY, FRAME, startAt = at.toList(), version = PREVIEW_VERSION, media = kit)
        }
    fun mediaScreen(name: String, look: Look, content: @Composable () -> Unit) = shot(File(out, "$name.png"), frames = 24) {
        CompositionLocalProvider(LocalMediaKit provides kit, LocalMediaRows provides { id: String -> rich.media(id) }) {
            BoringTravelTheme(look) { Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(FRAME)) { content() } }
        }
    }
    val journal = Place.Trip(on.id, TripTab.Journal)
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        withMedia("journal-photos-$id", journal, look = look)
        withMedia("note-rich-$id", journal, Place.Note(on.id, RICH_NOTE), look = look)
        withMedia("note-photos-$id", journal, Place.Note(on.id, PHOTO_NOTE), look = look)
        withMedia("write-rich-$id", journal, Place.Write(on.id, RICH_NOTE), look = look)
        mediaScreen("write-style-$id", look) { WriteScreen(rich, on.id, RICH_NOTE, SAMPLE_TODAY, {}, menuAtStart = WriteMenu.Style) }
        mediaScreen("write-lists-$id", look) { WriteScreen(rich, on.id, RICH_NOTE, SAMPLE_TODAY, {}, menuAtStart = WriteMenu.Lists) }
        mediaScreen("write-insert-$id", look) { WriteScreen(rich, on.id, PHOTO_NOTE, SAMPLE_TODAY, {}, menuAtStart = WriteMenu.Insert) }
        mediaScreen("write-photos-$id", look) { WriteScreen(rich, on.id, PHOTO_NOTE, SAMPLE_TODAY, {}) }
        mediaScreen("write-photo-selected-$id", look) { WriteScreen(rich, on.id, PHOTO_NOTE, SAMPLE_TODAY, {}, selectedAtStart = 0) }
        mediaScreen("write-layout-$id", look) { WriteScreen(rich, on.id, PHOTO_NOTE, SAMPLE_TODAY, {}, albumAtStart = 0) }
        mediaScreen("write-details-$id", look) { WriteScreen(rich, on.id, PHOTO_NOTE, SAMPLE_TODAY, {}, detailsAtStart = true) }
        // The note's video in Android's own player: the desktop's still player over the video's drawn first picture.
        shot(File(out, "video-$id.png"), frames = 24) {
            val playing = androidx.compose.runtime.remember {
                val drawn = DrawnVideoFiles(kit.files, lengthMs = 45_000, width = 1920, height = 1080)
                MediaKit(kit.files, frames = VideoFrames { p, max -> drawn.poster(p, max) }, playback = StillVideoPlayback())
            }
            CompositionLocalProvider(LocalMediaKit provides playing, LocalMediaRows provides { id: String -> rich.media(id) }) {
                BoringTravelTheme(look) { Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(FRAME)) { PlayVideo(video) {} } }
            }
        }
    }
    // The whole note at once, as long as it is.
    withMedia("note-rich-full", journal, Place.Note(on.id, RICH_NOTE), height = HEIGHT * 3)
    // The Calendar: the trips this month (one overlapping the one on now), the year, and no trips yet.
    val calendar = sampleBook().also { b ->
        b.newTrip("Tokyo", SAMPLE_TODAY.plusDays(4), SAMPLE_TODAY.plusDays(7))
        b.newTrip("Lisbon", SAMPLE_TODAY.plusDays(30), SAMPLE_TODAY.plusDays(36))
    }
    for (look in listOf(Look.PostcardPop, Look.Trail, Look.Sketchbook)) {
        app("calendar-${look.id}", calendar, Place.Home(HomeTab.Calendar), look = look)
    }
    for (look in listOf(Look.PostcardPop, Look.Market)) {
        screen(out, "calendar-year-${look.id}", look) {
            TripsCalendarScreen(calendar, SAMPLE_TODAY, 0, { _, _ -> }, {}, {}, {}, {}, yearAtStart = true)
        }
    }
    app("calendar-empty", TripBook(MemoryTripFile()), Place.Home(HomeTab.Calendar))
    // A plan item's tags, in the sheet (the walk has two).
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        screen(out, "item-tags-${look.id}", look, frames = 24) { tripPage(book, current.id, sheet = SheetTarget(walk, walk.day)) }
    }
    // The tab whose screen is still to come, and Me in every look (its theme picker shows the one picked).
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        app("community-${look.id}", book, Place.Home(HomeTab.Community), look = look)
    }
    for (look in Look.entries) app("me-${look.id}", book, Place.Home(HomeTab.Me), look = look)
    // Other fonts picked: a set for titles and dates, and a text face.
    val fonts = AppSettings.ready().apply {
        fontSet = FontSet.Notebook
        textFont = TextFont.Literata
    }
    app("me-fonts", book, Place.Home(HomeTab.Me), settings = fonts)
    app("trip-fonts", book, Place.Trip(current.id), settings = fonts)

    // Trips home: tickets (the default) and pictures, with a trip in each section, in two looks.
    val home = homeBook()
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        app("home-tickets-${look.id}", home, look = look)
        app("home-pictures-${look.id}", home, settings = AppSettings.ready(look).apply { tripsLook = TripsLook.Pictures })
    }
    app("home-tickets-past", home, look = Look.StickerBook, settings = AppSettings.ready(Look.StickerBook), height = HEIGHT * 2)

    // A trip's plan: collapsed (the default), expanded with the travel between places, the Calendar view, a leg's
    // travel, an item's Getting there, in two looks.
    val ba = argentinaPlanBook()
    val baTrip = ba.trips(SAMPLE_TODAY).single()
    val winery = ba.items(baTrip.id).single { it.title == "Winery in Valle de Uco" }
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        app("plan-collapsed-$id", book, Place.Trip(current.id), settings = AppSettings.ready(look))
        app("plan-expanded-$id", book, Place.Trip(current.id), settings = AppSettings.ready(look).apply { planExpanded = true })
        app("plan-calendar-$id", book, Place.Trip(current.id), settings = AppSettings.ready(look).apply { planView = PlanView.Calendar })
        app("plan-legs-$id", ba, Place.Trip(baTrip.id), settings = AppSettings.ready(look).apply { planExpanded = true })
        screen(out, "plan-travel-$id", look, frames = 24) { tripPage(ba, baTrip.id, travel = winery.id, settings = AppSettings.ready(look).apply { planExpanded = true }) }
        screen(out, "item-getting-there-$id", look, frames = 24) { tripPage(ba, baTrip.id, sheet = SheetTarget(winery, winery.day)) }
    }
    app("plan-calendar-stay", small, Place.Trip(small.trips(SAMPLE_TODAY).single().id), settings = AppSettings.ready().apply { planView = PlanView.Calendar })
    app("plan-expanded-stay", small, Place.Trip(small.trips(SAMPLE_TODAY).single().id), settings = AppSettings.ready().apply { planExpanded = true })

    // Demo mode: the examples on Trips, Argentina's plan and journal, Lisbon's drawn pictures, the Calendar, Me, and
    // every example deleted.
    val (demo, demoKit) = demoBook()
    fun withDemo(name: String, vararg at: Place, look: Look = Look.Default, expanded: Boolean = false, pictures: Boolean = false, height: Int = HEIGHT) =
        shot(File(out, "$name.png"), frames = 12, height = height) {
            val settings = AppSettings.ready(look).apply {
                demoMode = true
                planExpanded = expanded
                if (pictures) tripsLook = TripsLook.Pictures
            }
            BoringTravelApp(demo, settings, SAMPLE_TODAY, FRAME, startAt = at.toList(), version = PREVIEW_VERSION, media = demoKit)
        }
    for (look in listOf(Look.PostcardPop, Look.Market)) {
        val id = look.id
        withDemo("demo-trips-$id", look = look)
        withDemo("demo-plan-$id", Place.Trip(SampleArgentina.TRIP_ID), look = look, expanded = true)
        withDemo("demo-journal-$id", Place.Trip(SampleArgentina.TRIP_ID, TripTab.Journal), look = look)
        withDemo("demo-calendar-$id", Place.Home(HomeTab.Calendar), look = look)
    }
    withDemo("demo-trips-pictures", look = Look.Trail, pictures = true)
    val lisbonNote = demo.entries(SampleTrips.LISBON).first()
    val capri = demo.entries(SampleArgentina.TRIP_ID).last()
    for (look in listOf(Look.PostcardPop, Look.Sketchbook)) {
        withDemo("demo-lisbon-note-${look.id}", Place.Trip(SampleTrips.LISBON, TripTab.Journal), Place.Note(SampleTrips.LISBON, lisbonNote.id), look = look)
    }
    withDemo("demo-note-capri", Place.Trip(SampleArgentina.TRIP_ID, TripTab.Journal), Place.Note(SampleArgentina.TRIP_ID, capri.id))
    withDemo("demo-me", Place.Home(HomeTab.Me))
    withDemo("demo-edit-trip", Place.Trip(SampleTrips.LISBON), Place.EditTrip(SampleTrips.LISBON))
    val emptyDemo = TripBook(MemoryTripFile())
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val settings = AppSettings.ready(look).apply { demoMode = true }
        app("demo-empty-${look.id}", emptyDemo, settings = settings)
        app("demo-empty-calendar-${look.id}", emptyDemo, Place.Home(HomeTab.Calendar), settings = settings)
    }

    placesAndLinks(out, demo, demoKit)
    maps(out, demo, demoKit)
    dictationAndCheckIns(out)
    world(out, demo, demoKit)
    calendarAndBackups(out)
    share(out, demo, demoKit)
    planner(out)
    siteCopy(out)
    tracking(out)
    clips(out, demo, demoKit)
    books(out)
    parity(out)
    secondParity(out)
    accounts(out)
    println("Screenshots in ${out.absolutePath}")
}

/**
 * A trip's ⋮ › Share on the demo's Argentina (its photos and notes): the preview of the pages, each format's sheet, the
 * days' sheet and a page big, in two looks; and the pages themselves as the desktop's stand-in draws them (on iPhone
 * WebKit draws them), in Notebook and Minimal.
 */
private fun share(out: File, demo: TripBook, demoKit: MediaKit) {
    val printer = DesktopPagePrinter()
    val trip = SampleArgentina.TRIP_ID
    fun shareShot(name: String, look: Look, start: ShareStart? = null, settings: AppSettings = AppSettings.ready(look)) =
        shot(File(out, "$name.png"), frames = 60) {
            CompositionLocalProvider(LocalMediaKit provides demoKit, LocalMediaRows provides { id: String -> demo.media(id) }, LocalPagePrinter provides printer) {
                BoringTravelTheme(look) {
                    Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(FRAME)) {
                        ShareScreen(demo, trip, settings, 0, {}, {}, startWith = start)
                    }
                }
            }
        }
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        shareShot("share-$id", look)
        shareShot("share-pdf-$id", look, ShareStart.Pdf)
        shareShot("share-text-$id", look, ShareStart.Text)
        shareShot("share-word-$id", look, ShareStart.Word)
        shareShot("share-days-$id", look, ShareStart.Days)
    }
    shareShot("share-page", Look.PostcardPop, ShareStart.Page)
    // Minimal, Letter, small photos and the plan, kept from the last time.
    shareShot("share-minimal", Look.Sketchbook, settings = AppSettings.ready(Look.Sketchbook).apply {
        printTheme = com.justdataplease.boringtravel.model.PrintTheme.Minimal
        setPrintChoices(PrintChoices(photoSize = "s", paper = "letter", plan = true))
    })
    // The pages as the stand-in draws them, a few days of the trip.
    runBlocking {
        val exports = TripExports(demo, demoKit.files, demoKit.frames, printer)
        val loaded = exports.load(trip)
        val start = loaded.trip.startDate!!
        for (theme in listOf(com.justdataplease.boringtravel.model.PrintTheme.Notebook, com.justdataplease.boringtravel.model.PrintTheme.Minimal)) {
            val options = com.justdataplease.boringtravel.export.ExportOptions(theme, includePlan = true, from = start.plusDays(9), to = start.plusDays(12))
            val pdf = exports.pdf(loaded, options) {}
            val pages = printer.open(demoKit.files, pdf)!!
            for (i in 0 until minOf(4, pages.count)) {
                val image = pages.render(i, 1240)!!
                val png = org.jetbrains.skia.Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(out, "share-pages-${theme.id}-${i + 1}.png").writeBytes(png)
            }
        }
    }
}

/**
 * The maps on the desktop's drawn stand-in (no tiles), on recorded answers: the Map tab on today's trip, every trip's
 * routes, the globe still to come; a trip's map travelled and planned with its leg sheet, the map's looks, offline,
 * and a recorded route turned into a stop. Two looks each.
 */
private fun maps(out: File, demo: TripBook, demoKit: MediaKit) {
    val web = Recorded.web()
    val offline = Recorded.web(Recorded.net(connected = false))
    fun withWeb(name: String, book: TripBook, vararg at: Place, settings: AppSettings, media: MediaKit? = null, net: Web = web) =
        shot(File(out, "$name.png"), frames = 30) {
            BoringTravelApp(book, settings, SAMPLE_TODAY, FRAME, startAt = at.toList(), version = PREVIEW_VERSION, media = media ?: MediaKit.memory(), web = net)
        }
    fun mapScreen(name: String, look: Look, net: Web = web, content: @Composable () -> Unit) = shot(File(out, "$name.png"), frames = 30) {
        CompositionLocalProvider(LocalWeb provides net, LocalTrackProcessor provides null) {
            BoringTravelTheme(look) { Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(FRAME)) { content() } }
        }
    }
    val kyoto = sampleBook()
    val patagonia = patagoniaBook()
    val pat = patagonia.trips(SAMPLE_TODAY).single()
    val routes = mapsBook()
    val recorded = recordedBook()
    val naxos = recorded.trips(SAMPLE_TODAY).single()
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        // The Map tab: today's trip; every trip's routes, stops and notes; the globe (a later round).
        withWeb("map-$id", kyoto, Place.Home(HomeTab.Map), settings = AppSettings.ready(look))
        withWeb("map-all-$id", routes, Place.Home(HomeTab.Map), settings = AppSettings.ready(look))
        withWeb("map-globe-$id", kyoto, Place.Home(HomeTab.Map), settings = AppSettings.ready(look).apply { mapView = MapView.Globe })
        // A trip's map: Travelled (the stops joined, their names) and Planned (the plan's flags, the dashed route).
        withWeb("trip-map-travelled-$id", demo, Place.Trip(SampleArgentina.TRIP_ID, TripTab.Map), settings = AppSettings.ready(look).apply { demoMode = true }, media = demoKit)
        mapScreen("trip-map-planned-$id", look) { TripMapBoard(patagonia, pat.id, SAMPLE_TODAY, 0, AppSettings.ready(look), {}, MapActions(), plannedAtStart = true) }
        mapScreen("trip-map-demo-planned-$id", look) {
            TripMapBoard(demo, SampleArgentina.TRIP_ID, SAMPLE_TODAY, 0, AppSettings.ready(look), {}, MapActions(), plannedAtStart = true)
        }
        mapScreen("trip-map-styles-$id", look) { TripMapBoard(patagonia, pat.id, SAMPLE_TODAY, 0, AppSettings.ready(look), {}, MapActions(), stylesAtStart = true) }
        // Offline the first time: nothing of OpenFreeMap's style kept yet (the app keeps it in memory once fetched).
        MapBaseStyles.forget()
        mapScreen("trip-map-offline-$id", look, offline) { TripMapBoard(patagonia, pat.id, SAMPLE_TODAY, 0, AppSettings.ready(look), {}, MapActions(), plannedAtStart = true) }
        MapBaseStyles.forget()
        // A recorded route made into a stop (named by the phone's geocoder), the distance today.
        withWeb("trip-map-recorded-$id", recorded, Place.Trip(naxos.id, TripTab.Map), settings = AppSettings.ready(look))
    }
    // Every look's map colours.
    for (look in Look.entries) withWeb("trip-map-look-${look.id}", patagonia, Place.Trip(pat.id, TripTab.Map), settings = AppSettings.ready(look))
    // A stop's place page, opened from the map.
    val stop = recorded.snapshot.stops.single()
    withWeb("map-stop-page", recorded, Place.Trip(naxos.id, TripTab.Map), Place.PlacePage(naxos.id, stopId = stop.id), settings = AppSettings.ready(), net = offline)
}

/**
 * Dictation in the editor (listening with words coming in, the first-use word about the microphone, the way to Settings
 * after a refusal, an error), Me › Voice notes, and the daily check-in on a trip's Journal with its other places, on a
 * scripted recognizer. Two looks each.
 */
private fun dictationAndCheckIns(out: File) {
    fun withSpeech(name: String, look: Look, speech: SpeechEngine, content: @Composable () -> Unit) = shot(File(out, "$name.png"), frames = 24) {
        CompositionLocalProvider(LocalSpeech provides speech, LocalWeb provides Recorded.web()) {
            BoringTravelTheme(look) { Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(FRAME)) { content() } }
        }
    }
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        // A book of its own for each look: Write keeps what was dictated.
        val book = sampleBook()
        val trip = book.sections(SAMPLE_TODAY).now!!
        val note = book.entries(trip.id).single()
        withSpeech("write-dictating-$id", look, ScriptedSpeech(hearAtStart = "the lanterns were lit when we walked back")) {
            WriteScreen(book, trip.id, note.id, SAMPLE_TODAY, {}, dictate = true, dictationLanguage = "en-GB")
        }
        withSpeech("write-mic-ask-$id", look, ScriptedSpeech(access = MicAccess.NotAsked)) { WriteScreen(book, trip.id, null, SAMPLE_TODAY, {}, dictate = true) }
        withSpeech("write-mic-blocked-$id", look, ScriptedSpeech(access = MicAccess.Refused)) { WriteScreen(book, trip.id, null, SAMPLE_TODAY, {}, dictate = true) }
        withSpeech("write-dictation-error-$id", look, ScriptedSpeech(offline = false, failAtStart = 2)) {
            WriteScreen(book, trip.id, null, SAMPLE_TODAY, {}, dictate = true, dictationLanguage = "el-GR")
        }
        withSpeech("me-voice-$id", look, ScriptedSpeech()) { MeScreen(AppSettings.ready(look).apply { dictationLanguage = "el-GR" }, PREVIEW_VERSION, voiceAtStart = true) }
        // The check-in on the trip's Journal (today has places and nothing written), and its other places.
        withSpeech("check-in-$id", look, ScriptedSpeech()) {
            TripPage(book, trip.id, SAMPLE_TODAY, 0, {}, {}, {}, {}, {}, {}, tab = TripTab.Journal, settings = AppSettings.ready(look))
        }
        withSpeech("check-in-places-$id", look, ScriptedSpeech()) {
            TripPage(book, trip.id, SAMPLE_TODAY, 0, {}, {}, {}, {}, {}, {}, tab = TripTab.Journal, settings = AppSettings.ready(look), checkInChoicesAtStart = true)
        }
    }
}

/**
 * Your world: the globe on the Map tab with its sheet, Your world pulled up, a country's sheet (visited, and one to
 * come), a continent's, Places I've been and its question before removing, the share card; the flags on Trips, the
 * trip's header and the Calendar; the demo's globe. Two looks each, a notebook look for the tilted stickers.
 */
private fun world(out: File, demo: TripBook, demoKit: MediaKit) {
    val book = worldTravellerBook()
    val cyclades = book.trips(SAMPLE_TODAY).first { it.title.startsWith("Cyclades") }
    fun app(name: String, vararg at: Place, settings: AppSettings, b: TripBook = book, media: MediaKit? = null) = shot(File(out, "$name.png"), frames = 30) {
        BoringTravelApp(b, settings, SAMPLE_TODAY, FRAME, startAt = at.toList(), version = PREVIEW_VERSION, media = media ?: MediaKit.memory())
    }
    fun mapScreen(name: String, look: Look, content: @Composable () -> Unit) = shot(File(out, "$name.png"), frames = 30) {
        BoringTravelTheme(look) { Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(FRAME)) { content() } }
    }
    for (look in listOf(Look.PostcardPop, Look.Trail, Look.Sketchbook)) {
        val id = look.id
        val settings = { AppSettings.ready(look) }
        app("world-globe-$id", Place.Home(HomeTab.Map), settings = settings())
        mapScreen("world-sheet-$id", look) { MapHome(book, settings(), SAMPLE_TODAY, 0, {}, MapActions(), expandedAtStart = true) }
        mapScreen("world-country-$id", look) { MapHome(book, settings(), SAMPLE_TODAY, 0, {}, MapActions(), countryAtStart = "GR") }
        app("world-places-been-$id", Place.PlacesBeen, settings = settings())
        app("world-share-$id", Place.ShareWorld, settings = settings())
        app("world-trips-$id", settings = settings())
        app("world-trip-header-$id", Place.Trip(cyclades.id), settings = settings())
    }
    mapScreen("world-country-to-come", Look.PostcardPop) { MapHome(book, AppSettings.ready(), SAMPLE_TODAY, 0, {}, MapActions(), countryAtStart = "JP") }
    mapScreen("world-country-not-yet", Look.Trail) { MapHome(book, AppSettings.ready(Look.Trail), SAMPLE_TODAY, 0, {}, MapActions(), countryAtStart = "PE") }
    mapScreen("world-continent", Look.PostcardPop) {
        WorldMode(book, AppSettings.ready(), SAMPLE_TODAY, 0, WorldNav(), {}, continentAtStart = com.justdataplease.boringtravel.geo.Continent.Europe)
    }
    mapScreen("world-places-been-remove", Look.PostcardPop) { PlacesBeenScreen(book, SAMPLE_TODAY, 0, false, {}, {}, removingAtStart = "GR") }
    app("world-trips-pictures", settings = AppSettings.ready(Look.Market).apply { tripsLook = TripsLook.Pictures })
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        mapScreen("world-calendar-${look.id}", look) { TripsCalendarScreen(book, cyclades.startDate!!, 0, { _, _ -> }, {}, {}, {}, {}) }
    }
    // A new traveller: an empty globe with its hint, and the demo's world.
    app("world-empty", Place.Home(HomeTab.Map), settings = AppSettings.ready(), b = TripBook(MemoryTripFile()))
    for (look in listOf(Look.PostcardPop, Look.Market)) {
        app("world-demo-${look.id}", Place.Home(HomeTab.Map), settings = AppSettings.ready(look).apply { demoMode = true }, b = demo, media = demoKit)
    }
}

/**
 * The phone's calendar and backup files: the trip's Calendar sheet (not linked, the question about access, the
 * calendars to pick, linked and kept updated, the events to import), the Plan tab's line when the calendar is behind,
 * the item sheet's Add to calendar; Me › Backup & sync (the file's buttons, the question before a restore, after a
 * restore with Undo). On a recorded phone calendar ([MemoryPhoneCalendar]). Two looks each.
 */
private fun calendarAndBackups(out: File) {
    val zone = "Europe/Athens"
    fun sheetShot(name: String, look: Look, book: TripBook, tripId: String, phone: MemoryPhoneCalendar, settings: AppSettings, height: Int = HEIGHT, content: @Composable () -> Unit) =
        shot(File(out, "$name.png"), frames = 24, height = height) {
            val sync = androidx.compose.runtime.remember { com.justdataplease.boringtravel.calendar.CalendarSync(book, settings.calendarLinks, phone) }
            CompositionLocalProvider(LocalCalendar provides sync, LocalAppVersion provides PREVIEW_VERSION) {
                BoringTravelTheme(look) { Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(FRAME)) { content() } }
            }
        }
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        val book = smallTripBook()
        val trip = book.trips(SAMPLE_TODAY).single()
        val settings = AppSettings.ready(look).apply { calendarLinks.setZone(trip.id, zone) }
        val granted = MemoryPhoneCalendar()
        // Not linked yet: what goes in, Share .ics file, Import from calendar, the zone.
        sheetShot("calendar-sheet-$id", look, book, trip.id, granted, settings) {
            TripPage(book, trip.id, SAMPLE_TODAY, 0, {}, {}, {}, {}, {}, {}, settings = settings, calendarAtStart = true)
        }
        // The question before the phone's own, then the calendars by account.
        sheetShot("calendar-ask-$id", look, book, trip.id, MemoryPhoneCalendar(access = "notAsked"), settings) {
            TripPage(book, trip.id, SAMPLE_TODAY, 0, {}, {}, {}, {}, {}, {}, settings = settings)
            CalendarSheet(book, trip.id, 0, {}, startWith = CalendarStart.Add)
        }
        sheetShot("calendar-pick-$id", look, book, trip.id, granted, settings) {
            TripPage(book, trip.id, SAMPLE_TODAY, 0, {}, {}, {}, {}, {}, {}, settings = settings)
            CalendarSheet(book, trip.id, 0, {}, startWith = CalendarStart.Add)
        }
        // Events in the trip's dates, the obvious ones ticked.
        val withEvents = MemoryPhoneCalendar().apply {
            val day = trip.startDate!!
            addOther("cal-google", "Flight A3 361 to Naxos", com.justdataplease.boringtravel.calendar.wallMs(day, 6 * 60, zone), com.justdataplease.boringtravel.calendar.wallMs(day, 7 * 60, zone))
            addOther("cal-icloud-home", "Call with Maria", com.justdataplease.boringtravel.calendar.wallMs(day.plusDays(1), 18 * 60, zone), com.justdataplease.boringtravel.calendar.wallMs(day.plusDays(1), 18 * 60 + 30, zone))
            addOther("cal-icloud-work", "Ferry from Paros", com.justdataplease.boringtravel.calendar.wallMs(day, 9 * 60 + 15, zone), com.justdataplease.boringtravel.calendar.wallMs(day, 10 * 60, zone))
        }
        sheetShot("calendar-import-$id", look, book, trip.id, withEvents, settings) {
            TripPage(book, trip.id, SAMPLE_TODAY, 0, {}, {}, {}, {}, {}, {}, settings = settings)
            CalendarSheet(book, trip.id, 0, {}, startWith = CalendarStart.Import)
        }
        // Linked to Home and kept updated; then the plan changes with updates off: the Plan tab's line.
        val linkedBook = smallTripBook()
        val linkedTrip = linkedBook.trips(SAMPLE_TODAY).single()
        val linkedSettings = AppSettings.ready(look).apply { calendarLinks.setZone(linkedTrip.id, zone) }
        val phone = MemoryPhoneCalendar()
        val sync = com.justdataplease.boringtravel.calendar.CalendarSync(linkedBook, linkedSettings.calendarLinks, phone, now = { 1_795_100_000_000L })
        runBlocking { sync.add(linkedTrip.id, phone.calendars().first { it.id == "cal-icloud-home" }, zone) }
        sheetShot("calendar-linked-$id", look, linkedBook, linkedTrip.id, phone, linkedSettings) {
            TripPage(linkedBook, linkedTrip.id, SAMPLE_TODAY, 0, {}, {}, {}, {}, {}, {}, settings = linkedSettings, calendarAtStart = true)
        }
        runBlocking { sync.setAutomatic(linkedTrip.id, false) }
        val dinner = linkedBook.items(linkedTrip.id).single { it.title == "Dinner in Chora" }
        linkedBook.saveItem(dinner.copy(timeMinutes = 20 * 60 + 30))
        sheetShot("calendar-behind-$id", look, linkedBook, linkedTrip.id, phone, linkedSettings) {
            TripPage(linkedBook, linkedTrip.id, SAMPLE_TODAY, 0, {}, {}, {}, {}, {}, {}, settings = linkedSettings)
        }
        // A plan item: Add to calendar (the phone's own new-event screen) and "sync calendar"; tall, so the sheet's
        // calendar section near its end is in the picture.
        val ferry = book.items(trip.id).single { it.title == "Ferry from Paros" }
        sheetShot("calendar-item-$id", look, book, trip.id, granted, settings, height = HEIGHT * 2) {
            TripPage(book, trip.id, SAMPLE_TODAY, 0, {}, {}, {}, {}, {}, {}, settings = settings, sheetAtStart = SheetTarget(ferry, ferry.day))
        }

        // Me › Backup & sync: the file's buttons; the question before a restore; after a restore, with Undo.
        val files = MemoryMediaFiles()
        val backups = BackupFiles(sampleBook(), AppSettings.ready(look), files, PREVIEW_VERSION, now = { 1_795_200_000_000L })
        fun backupShot(name: String, on: BackupFiles = backups, content: @Composable () -> Unit) = shot(File(out, "$name.png"), frames = 12) {
            CompositionLocalProvider(LocalBackupFiles provides on) {
                BoringTravelTheme(look) { Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(FRAME)) { content() } }
            }
        }
        backupShot("backup-$id") { BackupScreen(AppSettings.ready(look), {}, {}) }
        val (path, _) = runBlocking { backups.saveFile() }
        val manifest = runBlocking { backups.peekFile(path) }
        backupShot("backup-restore-ask-$id") { BackupScreen(AppSettings.ready(look), {}, {}, askAtStart = manifest) }
        // Restored on a phone that held something else: what changed, and the copy kept for Undo.
        val otherPhone = BackupFiles(smallTripBook(), AppSettings.ready(look), files, PREVIEW_VERSION, now = { 1_795_300_000_000L })
        files.write(BackupFiles.PICKED, files.read(path)!!)
        val restored = runBlocking { otherPhone.restoreFile(BackupFiles.PICKED) }
        backupShot("backup-restored-$id", otherPhone) { BackupScreen(AppSettings.ready(look), {}, {}, noteAtStart = com.justdataplease.boringtravel.backup.BackupText.restored(restored)) }
        backupShot("backup-undo-ask-$id", otherPhone) { BackupScreen(AppSettings.ready(look), {}, {}, undoAskAtStart = true) }
    }
    // Me with its Backup & sync row.
    shot(File(out, "me-backup-row.png"), frames = 6) {
        BoringTravelApp(sampleBook(), AppSettings.ready(), SAMPLE_TODAY, FRAME, startAt = listOf(Place.Home(HomeTab.Me)), version = PREVIEW_VERSION)
    }
}

/**
 * A traveller with a past trip to Athens and Rome (a note written in Athens, the Colosseum ticked), a week in
 * Lisbon last spring, Japan coming up, Mexico for someday, and two countries marked by hand: France been to, Peru on the
 * want-to-go list.
 */
fun worldTravellerBook(): TripBook {
    var clock = 1_795_000_000_000L
    var n = 0
    val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "world-${n++}" })
    val start = localDate(2026, 6, 17)
    val cyclades = book.newTrip("Cyclades and Rome", start, start.plusDays(5), subtitle = "Naxos → Rome")
    val note = book.writeNote(cyclades.id, "The bus to Halki.", "Halki", at = startOfDayMs(start.plusDays(1), "Europe/Athens") + 13 * 3_600_000L, zoneId = "Europe/Athens")!!
    book.putRows(entries = listOf(note.copy(lat = 37.9715, lng = 23.7257, placeName = "Plaka, Athens")))
    book.saveItem(book.draftItem(cyclades.id, start.plusDays(2), 1.0).copy(title = "Colosseum", lat = 41.8902, lng = 12.4922, placeName = "Rome", done = true))
    val lisbon = book.newTrip("Lisbon in spring", localDate(2026, 5, 9), localDate(2026, 5, 17), subtitle = "Lisbon → Porto")
    book.saveItem(book.draftItem(lisbon.id, localDate(2026, 5, 10), 1.0).copy(title = "Alfama", lat = 38.7116, lng = -9.1300, done = true))
    book.saveItem(book.draftItem(lisbon.id, localDate(2026, 5, 15), 1.0).copy(title = "Porto", lat = 41.1496, lng = -8.6109, done = true))
    val kyoto = book.newTrip("Kyoto", localDate(2027, 4, 2), localDate(2027, 4, 9), subtitle = "Kyoto → Osaka")
    book.saveItem(book.draftItem(kyoto.id, localDate(2027, 4, 3), 1.0).copy(title = "Fushimi Inari", lat = 34.9671, lng = 135.7727))
    val mexico = book.newTrip("Mexico City")
    book.saveItem(book.draftItem(mexico.id, null, 1.0).copy(title = "Coyoacán", lat = 19.3467, lng = -99.1617))
    book.markCountryVisited("FR")
    book.setCountryWish("PE", true)
    return book
}

/** Two past trips with recorded routes (a ferry across a gap, a walk), a stop of the traveller's own and a located note. */
fun mapsBook(): TripBook {
    var clock = 1_795_000_000_000L
    var n = 0
    val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "maps-${n++}" })
    val naxos = book.newTrip("Naxos", localDate(2026, 6, 17), localDate(2026, 6, 26), subtitle = "Cyclades by ferry")
    val lisbon = book.newTrip("Lisbon in spring", localDate(2026, 5, 9), localDate(2026, 5, 17))
    book.writeNote(naxos.id, "The bus to Halki.", "Halki", at = startOfDayMs(localDate(2026, 6, 20), "Europe/Athens") + 13 * 3_600_000L, zoneId = "Europe/Athens")
    val note = book.entries(naxos.id).single()
    book.putRows(entries = listOf(note.copy(lat = 37.0587, lng = 25.4807, placeName = "Halki")))
    val t0 = startOfDayMs(localDate(2026, 6, 18), "Europe/Athens") + 7 * 3_600_000L
    fun leg(from: Pair<Double, Double>, to: Pair<Double, Double>, start: Long, minutes: Int) = (0..minutes / 3).map { i ->
        val f = i.toDouble() / (minutes / 3)
        Triple(start + i * 3 * 60_000L, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f)
    }
    // Paros to Naxos by ferry, then the bus up to Halki; Lisbon's walk through Alfama.
    withPoints(book, naxos.id, leg(37.0853 to 25.1488, 37.1056 to 25.3747, t0, 66) + leg(37.1056 to 25.3747, 37.0587 to 25.4807, t0 + 4 * 3_600_000L, 42))
    val l0 = startOfDayMs(localDate(2026, 5, 10), "Europe/Lisbon") + 10 * 3_600_000L
    withPoints(book, lisbon.id, leg(38.7139 to -9.1334, 38.7110 to -9.1300, l0, 30) + leg(38.7110 to -9.1300, 38.7071 to -9.1366, l0 + 31 * 60_000L, 30))
    book.putRows(stops = listOf(
        com.justdataplease.boringtravel.data.db.Stop("maps-stop", naxos.id, t0 + 2 * 3_600_000L, t0 + 4 * 3_600_000L, 37.1056, 25.3747, name = "Naxos port", countryCode = "GR", source = "manual", createdAt = clock, updatedAt = clock),
    ))
    return book
}

/**
 * A trip on now with an hour at Halki's square recorded this morning and the bus on: what the trip map turns into a
 * stop when it opens (named by a geocoder that answers "Chalki").
 */
fun recordedBook(): TripBook {
    var clock = 1_795_000_000_000L
    val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "rec-${clock++}" })
    val trip = book.newTrip("Naxos", SAMPLE_TODAY.minusDays(2), SAMPLE_TODAY.plusDays(3), subtitle = "Paros → Naxos")
    book.saveItem(book.draftItem(trip.id, SAMPLE_TODAY, 1.0).copy(title = "Lunch in Halki", lat = 37.0587, lng = 25.4807))
    book.saveItem(book.draftItem(trip.id, SAMPLE_TODAY, 2.0).copy(title = "Portara at sunset", lat = 37.1111, lng = 25.3721, timeMinutes = 19 * 60))
    val t0 = startOfDayMs(SAMPLE_TODAY, null) + 9 * 3_600_000L
    val ride = (0..20).map { i -> Triple(t0 + i * 180_000L, 37.1056 + (37.0587 - 37.1056) * i / 20.0, 25.3747 + (25.4807 - 25.3747) * i / 20.0) }
    val stay = (1..30).map { i -> Triple(t0 + 3_600_000L + i * 120_000L, 37.0587 + (if (i % 2 == 0) 1 else -1) * 0.0002, 25.4807) }
    withPoints(book, trip.id, ride + stay)
    runBlocking {
        TrackProcessor(book, FixedLocation(null, GeoAddress(locality = "Chalki", countryCode = "GR")), now = { t0 + 3 * 3_600_000L }, ids = { "rec-stop" })
            .process(trip.id, com.justdataplease.boringtravel.model.TrackingLevel.Balanced)
    }
    return book
}

/**
 * Places and links, on recorded answers (the Android app's own test fixtures, [Recorded]): never the network. The
 * previews and pictures are looked up before drawing, so the first frames have them.
 */
private fun placesAndLinks(out: File, demo: TripBook, demoKit: MediaKit) {
    val web = Recorded.web()
    val offline = Recorded.web(Recorded.net(connected = false))
    val links = linksBook()
    val trip = links.trips(SAMPLE_TODAY).single()
    val items = links.items(trip.id)
    val site = items.single { it.url != null }
    val maps = items.single { it.mapsUrl != null }
    runBlocking { warm(web, items) }
    fun withWeb(name: String, book: TripBook, vararg at: Place, look: Look = Look.Default, settings: AppSettings = AppSettings.ready(look), media: MediaKit? = null, net: Web = web) =
        shot(File(out, "$name.png"), frames = 30) {
            BoringTravelApp(book, settings, SAMPLE_TODAY, FRAME, startAt = at.toList(), version = PREVIEW_VERSION, media = media ?: MediaKit.memory(), web = net)
        }
    fun webScreen(name: String, look: Look, net: Web = web, content: @Composable () -> Unit) = shot(File(out, "$name.png"), frames = 30) {
        CompositionLocalProvider(LocalWeb provides net) {
            BoringTravelTheme(look) { Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(FRAME)) { content() } }
        }
    }
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        // The plan: link pictures, favicons and place photos in the circles; a day's title with its chevron.
        withWeb("plan-links-$id", links, Place.Trip(trip.id), settings = AppSettings.ready(look).apply { planExpanded = true })
        withWeb("plan-links-collapsed-$id", links, Place.Trip(trip.id), look = look)
        // The item sheet: the place's search, a website's preview, a Google Maps link with the place's photo.
        webScreen("item-place-search-$id", look) { tripPage(links, trip.id, sheet = SheetTarget(null, SAMPLE_TODAY, query = "halki")) }
        webScreen("item-website-$id", look) { tripPage(links, trip.id, sheet = SheetTarget(site, site.day)) }
        webScreen("item-maps-link-$id", look) { tripPage(links, trip.id, sheet = SheetTarget(maps, maps.day)) }
        webScreen("item-new-paste-$id", look) { tripPage(links, trip.id, sheet = SheetTarget(null, SAMPLE_TODAY)) }
        // A note's place: on the map, and from the trip.
        webScreen("write-place-$id", look) { WriteScreen(links, trip.id, null, SAMPLE_TODAY, {}, detailsAtStart = true, placeTypedAtStart = "halki") }
        // Copy from a link: the address (its other states are siteCopy's, SiteScreenshots.kt).
        withWeb("copy-link-$id", links, Place.Trip(trip.id), Place.CopySite(trip.id), look = look)
        // A plan's place page without photos of its own: the drawn scene.
        withWeb("place-drawn-$id", links, Place.Trip(trip.id), Place.PlacePage(trip.id, itemId = site.id), look = look)
    }
    // Offline: the search says so; a list can't be read.
    webScreen("item-place-offline", Look.PostcardPop, offline) { tripPage(links, trip.id, sheet = SheetTarget(null, SAMPLE_TODAY, query = "halki")) }
    // The place page in every theme (each has its own hero), on the demo's Argentina with its photos; a stop's page.
    val argentina = demo.items(SampleArgentina.TRIP_ID)
    val chalten = argentina.firstOrNull { "Chalt" in it.placeName && it.lat != null } ?: argentina.first { it.lat != null }
    val stop = demo.snapshot.stops.last { it.tripId == SampleArgentina.TRIP_ID && it.deletedAt == null }
    for (look in Look.entries) {
        withWeb(
            "place-${look.id}", demo, Place.Trip(SampleArgentina.TRIP_ID), Place.PlacePage(SampleArgentina.TRIP_ID, itemId = chalten.id),
            settings = AppSettings.ready(look).apply { demoMode = true }, media = demoKit, net = offline,
        )
    }
    withWeb("place-stop", demo, Place.Trip(SampleArgentina.TRIP_ID), Place.PlacePage(SampleArgentina.TRIP_ID, stopId = stop.id), settings = AppSettings.ready().apply { demoMode = true }, media = demoKit, net = offline)
    withWeb("place-full", demo, Place.Trip(SampleArgentina.TRIP_ID), Place.PlacePage(SampleArgentina.TRIP_ID, itemId = chalten.id), settings = AppSettings.ready(Look.Sketchbook).apply { demoMode = true }, media = demoKit, net = offline)
}

/** Every preview, place photo and picture the link screens show, looked up on the recorded answers before drawing. */
private suspend fun warm(web: Web, items: List<com.justdataplease.boringtravel.data.db.PlanItem>) {
    val near = items.firstOrNull { it.lat != null }?.let { com.justdataplease.boringtravel.places.LatLng(it.lat!!, it.lng!!) }
    val urls = items.flatMap { listOfNotNull(it.url, it.mapsUrl) }
    val previews = urls.mapNotNull { web.links.preview(it, near) } +
        items.mapNotNull { PlacePhotos.query(it.copy(url = null)) }.mapNotNull { web.links.placePhoto(it) }
    for (p in previews) for (u in listOfNotNull(p.imageUrl, p.faviconUrl)) for (size in listOf(160, 640, 1280, 2048)) web.images.load(u, size)
}

/** A short trip whose items have a website, a Google Maps link, a pin of their own and nothing. */
fun linksBook(today: LocalDate = SAMPLE_TODAY): TripBook {
    var clock = 1_795_000_000_000L
    var n = 0
    val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "links-${n++}" }, links = LinkPlaces::sortLinks)
    val trip = book.newTrip("Kyoto", today, today.plusDays(3), subtitle = "Kyoto → Nara")
    fun add(day: LocalDate?, pos: Double, title: String, kind: PlanKind, time: Int? = null, place: String = "", lat: Double? = null, lng: Double? = null, url: String? = null, mapsUrl: String? = null) =
        book.saveItem(book.draftItem(trip.id, day, pos).copy(title = title, kind = kind.id, timeMinutes = time, placeName = place, lat = lat, lng = lng, url = url, mapsUrl = mapsUrl))
    add(today, 1.0, "Yasaka Shrine", PlanKind.Place, 9 * 60, "Gion, Kyoto, Japan", Recorded.YASAKA.lat, Recorded.YASAKA.lng, url = Recorded.KYOTO_PAGE_URL)
    add(today, 2.0, "Shrine at night", PlanKind.Activity, 20 * 60, "Yasaka Shrine, Kyoto, Japan", 35.0037, 135.7787, mapsUrl = Recorded.YASAKA_LONG)
    add(today.plusDays(1), 1.0, "Fushimi Inari", PlanKind.Place, 8 * 60, "Fushimi Inari Taisha, Kyoto, Japan", 34.9671, 135.7727)
    add(today.plusDays(1), 2.0, "Lunch near the station", PlanKind.Food, 13 * 60)
    add(null, 1.0, "Nishiki Market", PlanKind.Food)
    return book
}

/** [sampleBook] with a past trip and its notes: a trip in every section of Trips. */
fun homeBook(today: LocalDate = SAMPLE_TODAY): TripBook {
    val book = sampleBook(today)
    val past = book.newTrip("Lisbon in spring", localDate(2026, 5, 9), localDate(2026, 5, 17), subtitle = "Lisbon → Porto")
    book.writeNote(past.id, "Tram 28 before nine.", title = "Alfama", at = startOfDayMs(localDate(2026, 5, 10), "Europe/Lisbon") + 20 * 3_600_000L, zoneId = "Europe/Lisbon")
    book.writeNote(past.id, "Port in a cool cellar.", title = "Gaia", at = startOfDayMs(localDate(2026, 5, 15), "Europe/Lisbon") + 19 * 3_600_000L, zoneId = "Europe/Lisbon")
    book.newTrip("Sicily by train", localDate(2027, 4, 2), localDate(2027, 4, 9), subtitle = "Palermo → Catania")
    return book
}

/** A trip with located places a long way apart and close together: flights, drives and walks between them. */
fun argentinaPlanBook(today: LocalDate = SAMPLE_TODAY): TripBook {
    var clock = 1_795_000_000_000L
    var n = 0
    val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "ba-${n++}" })
    val trip = book.newTrip("Buenos Aires and Mendoza", today, today.plusDays(4), subtitle = "Buenos Aires → Mendoza")
    fun add(day: Int, title: String, kind: String, lat: Double, lng: Double, time: Int? = null, place: String = "") =
        book.saveItem(book.draftItem(trip.id, today.plusDays(day.toLong()), (n + 1).toDouble()).copy(title = title, kind = kind, lat = lat, lng = lng, timeMinutes = time, placeName = place))
    add(0, "Fly to Buenos Aires", "transport", -34.8222, -58.5358, 7 * 60)
    add(0, "Boutique hotel in Palermo Soho", "stay", -34.5886, -58.4302, 11 * 60, "Palermo Soho, Buenos Aires, Argentina")
    add(1, "Recoleta Cemetery", "place", -34.5875, -58.3934, 10 * 60, "Recoleta, Buenos Aires, Argentina")
    add(1, "Rosedal, Palermo", "place", -34.5718, -58.4176, 15 * 60, "Palermo, Buenos Aires, Argentina")
    add(2, "Fly to Mendoza", "transport", -32.8317, -68.7929, 9 * 60)
    add(2, "Winery in Valle de Uco", "activity", -33.62, -69.20, 13 * 60)
    return book
}

/**
 * Patagonia as the Android app's LegSheetModelTest plans it (a flight to Bariloche on day 8, to El Calafate and the
 * transfer to El Chaltén on day 13 by car, a lookout there, back to El Calafate on day 17), on day 14 of 31 at
 * [today]: the leg sheet shows "Day 13 · Leg 2 of 3".
 */
fun patagoniaBook(today: LocalDate = SAMPLE_TODAY): TripBook {
    var clock = 1_795_000_000_000L
    val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "pat-${clock++}" })
    val start = today.minusDays(13)
    val trip = book.newTrip("Patagonia", start, start.plusDays(30), subtitle = "Bariloche → El Chaltén")
    fun add(id: String, day: Int, kind: PlanKind, title: String, lat: Double, lng: Double, pos: Double = 1.0) =
        book.saveItem(book.draftItem(trip.id, start.plusDays(day - 1L), pos).copy(id = id, kind = kind.id, title = title, lat = lat, lng = lng))!!
    add("bariloche", 8, PlanKind.Transport, "Fly to Bariloche", -41.1512, -71.1578)
    add("calafate", 13, PlanKind.Transport, "Fly to El Calafate", -50.2803, -72.0531)
    add("chalten", 13, PlanKind.Transport, "Transfer to El Chaltén", -49.3315, -72.8863)
    add("mirador", 13, PlanKind.Place, "Mirador de los Cóndores", -49.3373, -72.8778, pos = 2.0)
    add("back", 17, PlanKind.Transport, "Back to El Calafate", -50.3379, -72.2648)
    book.setLegVehicle(trip.id, com.justdataplease.boringtravel.clip.RouteLegKey(com.justdataplease.boringtravel.clip.RouteLegSource.Planned, "calafate", "chalten"), com.justdataplease.boringtravel.model.Vehicle.Car)
    return book
}

/**
 * [book] with recorded points for trip [tripId], put in the way a restore brings them (this phone records no route
 * yet): each (epoch ms, lat, lng), 12 m accurate.
 */
fun withPoints(book: TripBook, tripId: String, points: List<Triple<Long, Double, Double>>): TripBook {
    val data = BackupJson.decodeFromString(BackupData.serializer(), book.export())
    val rows = points.map { (t, lat, lng) -> com.justdataplease.boringtravel.backup.TrackPointRow(tripId, t, lat, lng, 12f, null, null, "balanced") }
    book.restore(BackupJson.encodeToString(BackupData.serializer(), data.copy(trackPoints = data.trackPoints + rows)))
    return book
}

/** The demo as Me › Demo mode makes it (the example trips, their photos and drawn pictures in [MediaKit]'s files). */
fun demoBook(today: LocalDate = SAMPLE_TODAY): Pair<TripBook, MediaKit> {
    var clock = 1_795_000_000_000L
    var n = 0
    val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "own-${n++}" })
    book.newTrip("Naxos", localDate(2027, 6, 17), localDate(2027, 6, 26), subtitle = "Cyclades by ferry")
    val kit = MediaKit(MemoryMediaFiles())
    runBlocking {
        DemoMode(book, AppSettings.ready(), kit.files, now = { clock++ }, ids = { "demo-row-${n++}" }).set(true, today)
        preload(book, kit)
    }
    return book to kit
}

@Composable
private fun tripPage(book: TripBook, tripId: String, sheet: SheetTarget? = null, menu: Boolean = false, travel: String? = null, settings: AppSettings? = null) =
    TripPage(book, tripId, SAMPLE_TODAY, 0, {}, {}, {}, {}, {}, {}, sheetAtStart = sheet, menuAtStart = menu, settings = settings, travelAtStart = travel)

@Composable
private fun itemSheet(book: TripBook, tripId: String, item: com.justdataplease.boringtravel.data.db.PlanItem, ask: SheetAsk) {
    val trip = book.trip(tripId)!!
    val items = book.items(tripId)
    tripPage(book, tripId)
    PlanItemSheet(book, tripId, item, item.day, items, PlanDays.days(trip.startDate, trip.endDate, items), {}, {}, askAtStart = ask)
}

/** One screen as the app frames it, in [look], with whatever it has open at the start. */
private fun screen(out: File, name: String, look: Look = Look.Default, frames: Int = FRAMES, content: @Composable () -> Unit) =
    shot(File(out, "$name.png"), frames) {
        BoringTravelTheme(look) {
            // The theme's status bar, as the app sets it (App.kt); a studio inside sets its own over it.
            DisposableEffect(look) {
                SystemBars.set(!look.dark)
                onDispose { }
            }
            Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(FRAME)) { content() }
        }
    }

@OptIn(ExperimentalComposeUiApi::class)
private fun shot(file: File, frames: Int = FRAMES, height: Int = HEIGHT, content: @Composable () -> Unit) {
    file.writeBytes(render(WIDTH, height, DENSITY, frames, content))
}

/** Every photo's picture made at every size a screen asks for, so the pictures are drawn by the first frames. */
suspend fun preload(book: TripBook, kit: MediaKit) {
    for (m in book.snapshot.media) for (size in listOf(160, 320, 640, 1280, 2048)) kit.pictures.load(m, size)
}

/** [content] drawn to a PNG off screen, after [frames] frames so everything that settles after the first one is in. */
@OptIn(ExperimentalComposeUiApi::class)
fun render(width: Int, height: Int, density: Float, frames: Int = FRAMES, content: @Composable () -> Unit): ByteArray =
    renderPhone(width, height, density, frames, statusBar = true, content = content)

/** [render], with the phone's status bar drawn over the picture ([PhoneStatusBar]) or not (a home screen's). */
fun renderPhone(width: Int, height: Int, density: Float, frames: Int = FRAMES, statusBar: Boolean, content: @Composable () -> Unit): ByteArray {
    // Each picture starts with a light page's status bar; the app and the studios set theirs as they draw.
    SystemBars.set(true)
    val scene = ImageComposeScene(width, height, Density(density)) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (statusBar) PhoneStatusBar(landscape = width > height)
        }
    }
    try {
        var image = scene.render(0)
        repeat(frames) { frame ->
            Thread.sleep(FRAME_MILLIS)
            // A frame is a picture in native memory, which Java's collector doesn't count: closed here, not left to it.
            image.close()
            image = scene.render((frame + 1) * FRAME_MILLIS * 1_000_000)
        }
        return image.use { last -> last.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
    } finally {
        scene.close()
        // And whatever else a screen drew (photos, paragraphs) is native too and goes with its Java object: collect
        // now. 317 pictures of 7 frames filled GitHub's 7.7 GB Linux runner, which then stopped the job (18 Nov 2026).
        System.gc()
    }
}

private const val FRAMES = 6
private const val FRAME_MILLIS = 50L
