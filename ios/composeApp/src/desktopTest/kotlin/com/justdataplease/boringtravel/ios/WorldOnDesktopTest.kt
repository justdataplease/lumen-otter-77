package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.data.db.CountryMark
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.globe.GlobeMath
import com.justdataplease.boringtravel.globe.GlobePalette
import com.justdataplease.boringtravel.globe.GlobePin
import com.justdataplease.boringtravel.globe.GlobeRenderer
import com.justdataplease.boringtravel.globe.Ortho
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.ui.theme.BtLooks
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.world.ShareCard
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Your world on the desktop: the countries worked out from a book's trips (a located note, a ticked item, a planned
 * place), the globe's projection and what a tap reaches against the Android app's maths, the flags on Trips, the
 * trip's header and the Calendar, the country sheet's marks, Places I've been with its question before removing, and
 * the share card. The Android app's own tests of the globe and the world run beside these (build.gradle.kts).
 */
@OptIn(ExperimentalTestApi::class)
class WorldOnDesktopTest {
    private val today = SAMPLE_TODAY

    init {
        runBlocking {
            BtFontFiles.load()
            CountryOutlines.load()
        }
    }

    /**
     * A past trip to Athens and Rome with a note written in Athens (day 2) and the Colosseum ticked in Rome (day 3), and a
     * trip to Kyoto next spring with a planned place: Greece and Italy visited, Japan planned.
     */
    private fun worldBook(): TripBook {
        var clock = 1_795_000_000_000L
        var n = 0
        val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "w-${n++}" })
        val start = localDate(2026, 6, 17)
        val cyclades = book.newTrip("Cyclades and Rome", start, start.plusDays(5), subtitle = "Naxos → Rome")
        val note = book.writeNote(cyclades.id, "The bus to Halki.", "Halki", at = startOfDayMs(start.plusDays(1), "Europe/Athens") + 13 * 3_600_000L, zoneId = "Europe/Athens")!!
        book.putRows(entries = listOf(note.copy(lat = 37.9715, lng = 23.7257, placeName = "Plaka, Athens")))
        book.saveItem(book.draftItem(cyclades.id, start.plusDays(2), 1.0).copy(title = "Colosseum", lat = 41.8902, lng = 12.4922, placeName = "Rome", done = true))
        val kyoto = book.newTrip("Kyoto", localDate(2027, 4, 2), localDate(2027, 4, 9))
        book.saveItem(book.draftItem(kyoto.id, localDate(2027, 4, 3), 1.0).copy(title = "Fushimi Inari", lat = 34.9671, lng = 135.7727, kind = PlanKind.Place.id))
        return book
    }

    @Test
    fun theWorldComesFromTheTripsRows() {
        val book = worldBook()
        val w = WorldSource().world(book, demoMode = false, today = today)
        assertEquals(setOf("GR", "IT"), w.visitedCodes)
        assertEquals(setOf("JP"), w.planned)
        assertEquals(listOf("JP"), w.nextCodes)
        assertEquals("2 countries · 1% of the world", countLine(w))
        assertEquals(2026, w.sinceYear)
        // The flags on each trip, in travel order; a trip still to come shows where it plans to go.
        val cyclades = book.trips(today).single { it.title.startsWith("Cyclades") }
        val kyoto = book.trips(today).single { it.title == "Kyoto" }
        assertEquals(listOf("GR", "IT"), w.tripCountries[cyclades.id])
        assertEquals(listOf("JP"), w.tripCountries[kyoto.id])
        // Six days away, all in the past trip; days fill from the last known country.
        assertEquals(6, w.daysAway)
        assertEquals(2, w.byCode.getValue("GR").days)
        assertEquals(4, w.byCode.getValue("IT").days)
        assertEquals(1, w.byCode.getValue("GR").entries)
        // The Calendar's day flags: the day of the note is Greece, the day of the ticked item Italy.
        val days = w.tripDayCountries.getValue(cyclades.id)
        assertEquals(listOf("GR"), days[cyclades.startDate!!.plusDays(1)])
        assertEquals(listOf("IT"), days[cyclades.startDate!!.plusDays(2)])
        val span = TripSpan.split(listOf(cyclades), w.tripCountries, w.tripDayCountries).spans.single()
        assertEquals(listOf(listOf("GR"), listOf("GR"), listOf("IT"), listOf("IT"), listOf("IT"), listOf("IT")), TripDays.countries(span))
        // In demo mode only the examples count: none here, and the hand-made marks stay out.
        book.markCountryVisited("FR")
        assertEquals(0, WorldSource().world(book, demoMode = true, today = today).countries)
        assertEquals(setOf("GR", "IT", "FR"), WorldSource().world(book, demoMode = false, today = today).visitedCodes)
    }

    @Test
    fun theGlobeProjectsAndAnswersTapsAsAndroidsDoes() {
        // The Android app's orthographic view: the point under the centre, a point on the rim, one behind the globe.
        val o = Ortho().set(0.0, 0.0, 200f, 200f, 100f)
        val east = floatArrayOf(GlobeMath.unitX(0.0, 90.0), GlobeMath.unitY(0.0, 90.0), GlobeMath.unitZ(0.0))
        assertEquals(300f, o.screenX(o.x(east[0], east[1], east[2])), 1e-3f)
        assertEquals(200f, o.screenY(o.y(east[0], east[1], east[2])), 1e-3f)
        assertTrue(o.depth(GlobeMath.unitX(0.0, 180.0), GlobeMath.unitY(0.0, 180.0), GlobeMath.unitZ(0.0)) < 0f)
        val out = DoubleArray(2)
        assertTrue(o.unproject(200f, 150f, out))
        assertEquals(30.0, out[0], 1e-6) // half a radius up is 30° north (asin 0.5)
        assertEquals(0.0, out[1], 1e-6)

        // The renderer drawn on a picture: a pin on Greece answers a tap there when Greece faces the viewer, never when
        // it is behind the globe.
        val geometry = assertNotNull(runBlocking { CountryOutlines.geometry() })
        val renderer = GlobeRenderer(geometry, density = 1f)
        val text = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        renderer.setPalette(GlobePalette.from(BtLooks.of(Look.Default)), text, TextStyle(fontSize = GlobeRenderer.LABEL_SP.sp))
        renderer.setPins(listOf(GlobePin("GR", 39.49, 21.73, "Greece")))
        renderer.setCountries(setOf("GR"), setOf("JP"))
        fun drawAt(lon: Double, lat: Double) {
            val bmp = ImageBitmap(400, 400)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(400f, 400f)) {
                renderer.draw(this, lon, lat, 200f, 200f, 150f, 400f, 400f)
            }
        }
        drawAt(21.73, 39.49)
        assertEquals("GR", renderer.pinAt(200f, 200f, 4f))
        assertTrue(renderer.unproject(200f, 200f, out))
        assertTrue(abs(out[0] - 39.49) < 1e-3 && abs(out[1] - 21.73) < 1e-3)
        drawAt(21.73 + 180.0, -39.49)
        assertNull(renderer.pinAt(200f, 200f, 40f))
        // Off the disc: no place at all.
        assertTrue(!renderer.unproject(5f, 5f, out))
    }

    @Test
    fun flagsShowOnTripsTheTripsHeaderAndTheCalendar() {
        val book = worldBook()
        val settings = AppSettings.ready()
        val cyclades = book.trips(today).single { it.title.startsWith("Cyclades") }
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent { BoringTravelApp(book, settings, today, version = "1.0") }
            waitForIdle()
            // Trips: the past trip's two flags, the next trip's planned one.
            assertTrue(onAllNodesWithContentDescription("Greece").fetchSemanticsNodes().isNotEmpty())
            assertTrue(onAllNodesWithContentDescription("Italy").fetchSemanticsNodes().isNotEmpty())
            assertTrue(onAllNodesWithContentDescription("Japan").fetchSemanticsNodes().isNotEmpty())
        }
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent { BoringTravelApp(book, settings, today, startAt = listOf(Place.Trip(cyclades.id)), version = "1.0") }
            waitForIdle()
            onNodeWithContentDescription("Greece").assertExists()
            onNodeWithContentDescription("Italy").assertExists()
        }
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent { TripsCalendarScreen(book, cyclades.startDate!!, 0, { _, _ -> }, {}, {}, {}, {}) }
            waitForIdle()
            assertTrue(onAllNodesWithContentDescription("Greece").fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun theMapTabOpensOnTheGlobeAndACountrysSheetMarksIt() {
        val book = worldBook()
        val settings = AppSettings.ready()
        // The globe by default, as on Android.
        assertEquals(MapView.Globe, settings.mapView)
        var changes by mutableIntStateOf(0)
        runDesktopComposeUiTest(width = 440, height = 1400) {
            setContent { MapHome(book, settings, today, changes, {}, MapActions(), WorldNav(changed = { changes++ }), countryAtStart = "FR") }
            waitForIdle()
            onNodeWithText("Since 2026", ignoreCase = true).assertExists()
            assertTrue(onAllNodesWithText("Places I've been").fetchSemanticsNodes().isNotEmpty())
            // France: not visited, so "Want to go" and "I've been here".
            onNodeWithContentDescription("Want to go").performClick()
            waitForIdle()
            assertEquals(listOf("wish:FR"), book.countryMarks().map { it.id })
            onNodeWithText("I've been here").performClick()
            waitForIdle()
            assertEquals(setOf("manual:FR"), book.countryMarks().map { it.id }.toSet())
            assertTrue(changes >= 2)
        }
        assertEquals(setOf("GR", "IT", "FR"), WorldSource().world(book, false, today).visitedCodes)
    }

    @Test
    fun planATripHereMakesASomedayTripWithItsPlace() {
        val book = TripBook(MemoryTripFile())
        val id = WorldActions.planTripIn(book, "PT")
        val trip = assertNotNull(book.trip(id))
        assertEquals("Portugal", trip.title)
        assertNull(trip.startDate)
        val idea = book.ideas(id).single()
        assertEquals("Portugal", idea.placeName)
        assertEquals(CountryInfo.labelPoint("PT"), idea.lat!! to idea.lng!!)
        // Planned there now: dashed on the globe.
        assertEquals(setOf("PT"), WorldSource().world(book, false, today).planned)
    }

    @Test
    fun placesIveBeenAddsACountryAndRemovesItAfterAsking() {
        val book = worldBook()
        var changes by mutableIntStateOf(0)
        runDesktopComposeUiTest(width = 440, height = 1400) {
            setContent { PlacesBeenScreen(book, today, changes, false, onBack = {}, onChanged = { changes++ }) }
            waitForIdle()
            onNodeWithText("Been · 2", ignoreCase = true).assertExists()
            // The search finds a country without its accents.
            onNode(hasText("Search countries")).assertExists()
            onNode(hasSetTextAction()).performTextInput("japan")
            waitForIdle()
            onNodeWithText("Japan").performClick()
            waitForIdle()
            assertTrue(book.countryMarks().any { it.id == "manual:JP" && it.source == CountryMark.SOURCE_MANUAL })
            onNodeWithText("Been · 3", ignoreCase = true).assertExists()
            // Hold to remove: asked first; Cancel keeps it.
            onNodeWithText("Japan").performTouchInput { longClick() }
            onNodeWithText("Remove Japan?").assertExists()
            onNodeWithText("You can add it again any time.").assertExists()
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            assertTrue(book.countryMarks().any { it.id == "manual:JP" })
            onNodeWithText("Japan").performTouchInput { longClick() }
            onNodeWithText("Remove").performClick()
            waitForIdle()
            assertTrue(book.countryMarks().none { it.id == "manual:JP" })
            assertEquals(setOf("GR", "IT"), WorldSource().world(book, false, today).visitedCodes)
            // Greece comes from the trips: removing it hides it, which needs its own words.
            onNode(hasSetTextAction()).performTextReplacement("zzzz")
            waitForIdle()
            onNodeWithText("No country by that name.").assertExists()
            onNode(hasSetTextAction()).performTextReplacement("greece")
            waitForIdle()
            onNodeWithText("Greece").performTouchInput { longClick() }
            onNodeWithText("It comes from your trips. It stays off your list until you add it again.").assertExists()
            onNodeWithText("Remove").performClick()
            waitForIdle()
            assertEquals(setOf("IT"), WorldSource().world(book, false, today).visitedCodes)
            assertTrue(book.countryMarks().any { it.id == "hidden:GR" })
        }
    }

    @Test
    fun theShareCardIsDrawnInTheTheme() {
        val book = worldBook()
        val w = WorldSource().world(book, false, today)
        assertEquals("2 countries", ShareCard.headline(w.countries))
        assertEquals("1% of the world · 1 continent", ShareCard.line(w))
        val geometry = runBlocking { CountryOutlines.geometry() }
        val text = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val style = TextStyle(fontSize = 15.sp)
        val card = ShareCard.render(BtLooks.of(Look.Default), w, geometry, text, ShareCard.Faces(style, style, style))
        assertEquals(ShareCard.WIDTH, card.width)
        assertEquals(ShareCard.HEIGHT, card.height)
        val png = ShareCard.png(card)
        // A PNG: its signature, and something drawn.
        assertEquals(0x89.toByte(), png[0])
        assertTrue(png.size > 10_000)
    }

}
