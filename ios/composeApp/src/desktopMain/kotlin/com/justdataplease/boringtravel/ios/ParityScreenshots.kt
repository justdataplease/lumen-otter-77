package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.compat.SystemBars
import com.justdataplease.boringtravel.compat.setPhoneLanguage
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.Look
import java.io.File

/** Sideways an iPhone hides its status bar; the Dynamic Island's side and the home indicator stay clear. */
private val SIDEWAYS_FRAME = PaddingValues(start = 62.dp, end = 62.dp, bottom = 21.dp)

/**
 * The parity round's pictures (19 Nov 2026): a Greek phone's dates and numbers as Android writes them (`el-*`), the
 * screens sideways as Android's stretch (`landscape-*`, the island drawn at the left), the status bar on each theme's
 * Trips and in the studios (`status-*`), and the screens this round changed (Notes outside trips without Write, a note's
 * photos only looked at, Me with "Phone (Roboto)" and Android's version). `gradlew :composeApp:screenshots
 * -PshotsOnly=parity` draws only these.
 */
fun parity(out: File) {
    val book = sampleBook()
    val current = book.sections(SAMPLE_TODAY).now!!
    val note = book.entries(current.id).single()
    fun app(name: String, b: TripBook, vararg at: Place, settings: AppSettings = AppSettings.ready(), sideways: Boolean = false, web: Web? = null, media: MediaKit? = null) {
        val w = if (sideways) PHONE_H else PHONE_W
        val h = if (sideways) PHONE_W else PHONE_H
        File(out, "$name.png").writeBytes(
            render(w, h, PHONE_DENSITY, frames = 24) {
                // The trip's pages drawn by the desktop's stand-in for WebKit (Share's preview).
                CompositionLocalProvider(LocalPagePrinter provides DesktopPagePrinter()) {
                    BoringTravelApp(
                        b, settings, SAMPLE_TODAY, if (sideways) SIDEWAYS_FRAME else PHONE_FRAME, startAt = at.toList(), version = PREVIEW_VERSION,
                        web = web ?: Web.offline(), media = media ?: MediaKit.memory(),
                    )
                }
            },
        )
    }
    fun screen(name: String, look: Look = Look.Default, sideways: Boolean = false, content: @Composable () -> Unit) {
        val w = if (sideways) PHONE_H else PHONE_W
        val h = if (sideways) PHONE_W else PHONE_H
        File(out, "$name.png").writeBytes(
            render(w, h, PHONE_DENSITY, frames = 24) {
                BoringTravelTheme(look) {
                    DisposableEffect(look) {
                        SystemBars.set(!look.dark)
                        onDispose { }
                    }
                    Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(if (sideways) SIDEWAYS_FRAME else PHONE_FRAME)) { content() }
                }
            },
        )
    }

    // The status bar: dark icons on the four light themes, light on Trail (Android's SystemBars).
    for (look in Look.entries) app("status-trips-${look.id}", book, settings = AppSettings.ready(look))

    // A Greek phone: dates and numbers as Android writes them in Greek.
    setPhoneLanguage("el-GR")
    try {
        app("el-trips", book)
        app("el-trip-plan", book, Place.Trip(current.id))
        app("el-plan-calendar", book, Place.Trip(current.id), settings = AppSettings.ready().apply { planView = PlanView.Calendar })
        app("el-journal", book, Place.Trip(current.id, TripTab.Journal))
        app("el-note", book, Place.Trip(current.id, TripTab.Journal), Place.Note(current.id, note.id))
        app("el-calendar", book, Place.Home(HomeTab.Calendar))
        screen("el-calendar-year") { TripsCalendarScreen(book, SAMPLE_TODAY, 0, { _, _ -> }, {}, {}, {}, {}, yearAtStart = true) }
        val recorded = recordedBook()
        app("el-trip-map-recorded", recorded, Place.Trip(recorded.trips(SAMPLE_TODAY).single().id, TripTab.Map), web = Recorded.web())
        val ba = argentinaPlanBook()
        app("el-plan-legs", ba, Place.Trip(ba.trips(SAMPLE_TODAY).single().id), settings = AppSettings.ready().apply { planExpanded = true })
        app("el-world", worldTravellerBook(), Place.Home(HomeTab.Map))
        screen("el-date-picker") { StartDateDialog(10, {}, {}, initial = SAMPLE_TODAY.plusDays(30)) }
        screen("el-trip-dates") { TripDatesDialog(SAMPLE_TODAY.plusDays(30), SAMPLE_TODAY.plusDays(39), { _, _ -> }, {}) }
    } finally {
        setPhoneLanguage(null)
    }

    // The screens this round changed.
    val loose = withLooseNotes(sampleBook())
    app("notes-outside-trips", loose, Place.LooseNotes)
    app("notes-outside-trips-empty", TripBook(MemoryTripFile()), Place.LooseNotes)
    app("me-version", book, Place.Home(HomeTab.Me))

    // Sideways: Android's screens have no landscape forms, they stretch to the width; so do these.
    app("landscape-trips", book, sideways = true)
    app("landscape-trip-plan", book, Place.Trip(current.id), sideways = true)
    app("landscape-journal", book, Place.Trip(current.id, TripTab.Journal), sideways = true)
    app("landscape-note", book, Place.Trip(current.id, TripTab.Journal), Place.Note(current.id, note.id), sideways = true)
    app("landscape-write", book, Place.Trip(current.id, TripTab.Journal), Place.Write(current.id, note.id), sideways = true)
    app("landscape-calendar", book, Place.Home(HomeTab.Calendar), sideways = true)
    app("landscape-globe", worldTravellerBook(), Place.Home(HomeTab.Map), sideways = true)
    app("landscape-me", book, Place.Home(HomeTab.Me), sideways = true)
    app("landscape-new-trip", TripBook(MemoryTripFile()), Place.NewTrip, sideways = true)
    app("landscape-trail", book, settings = AppSettings.ready(Look.Trail), sideways = true)
    val (demo, demoKit) = demoBook()
    app("landscape-share", demo, Place.Trip(SampleArgentina.TRIP_ID), Place.Share(SampleArgentina.TRIP_ID), settings = AppSettings.ready().apply { demoMode = true }, sideways = true, media = demoKit)
    // The studios sideways: dark edge to edge, the island's side dark too.
    fun studio(name: String, content: @Composable () -> Unit) = File(out, "$name.png").writeBytes(
        render(PHONE_H, PHONE_W, PHONE_DENSITY, frames = 60) {
            CompositionLocalProvider(LocalMediaKit provides demoKit, LocalMediaRows provides { id: String -> demo.media(id) }, LocalPagePrinter provides DesktopPagePrinter()) {
                BoringTravelTheme(Look.PostcardPop) {
                    Box(Modifier.fillMaxSize().background(StudioColors.bg).padding(SIDEWAYS_FRAME)) { content() }
                }
            }
        },
    )
    studio("landscape-clip") { ClipScreen(demo, SampleArgentina.TRIP_ID, AppSettings.ready(), {}, {}, {}, frameAtStart = 0) }
    studio("landscape-book") { BookScreen(demo, SampleArgentina.TRIP_ID, AppSettings.ready().apply { demoMode = true }, onBack = {}) }
    println("Parity pictures in ${out.absolutePath}")
}
