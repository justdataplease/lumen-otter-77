package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.widget.QuickBarLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The home-screen widgets' snapshot (ios/Widgets.kt), the app's links from widgets and quick actions: the words are the
 * Android app's (its WidgetText, read in), day by day. Runs on the desktop and on the iPhone Simulator.
 */
class WidgetsTest {
    private val today = localDate(2026, 6, 19)
    private var clock = 1_780_000_000_000L

    private fun book() = TripBook(MemoryTripFile(), now = { clock++ })

    @Test
    fun linksIntoTheApp() {
        assertEquals(Deeplink.WRITE, Deeplink.of("boringtravel://write"))
        assertEquals(Deeplink.VOICE, Deeplink.of(" boringtravel://Voice/ "))
        assertEquals(Deeplink.NEW_TRIP, Deeplink.of("boringtravel://new_trip?from=shortcut"))
        assertEquals(Deeplink.TODAY, Deeplink.of(Deeplink.link(Deeplink.TODAY)))
        assertNull(Deeplink.of("boringtravel://join#abc"))
        assertNull(Deeplink.of("https://justdataplease.com/apps/boringtravel/"))
        assertNull(Deeplink.of("write"))
        val links = AppLinks()
        assertTrue(links.open("boringtravel://camera"))
        assertEquals(Deeplink.CAMERA, links.pending)
        assertEquals(Deeplink.CAMERA, links.take())
        assertNull(links.pending)
        assertTrue(!links.open("mailto:someone@example.com"))
        assertNull(links.take())
    }

    @Test
    fun aTripDayWithItsNameAndTheNotesWrittenToday() {
        val book = book()
        val trip = book.newTrip("Cyclades", today.minusDays(3), today.plusDays(5))
        book.nameDay(trip.id, today, "Naxos")
        val noon = startOfDayMs(today, null) + 12 * 3_600_000L
        book.writeNote(trip.id, "Ferry at nine.", at = noon)
        book.writeNote(trip.id, "Dinner by the port.", at = noon + 3_600_000L)
        val s = WidgetSnapshot.build(book, Look.StickerBook, demoMode = false, today = today)
        assertEquals(WidgetSnapshot.DAYS, s.days.size)
        val d = s.days[0]
        assertEquals(startOfDayMs(today, null), d.from)
        assertEquals("Cyclades", d.title)
        assertEquals("Day 4 of 9 · 2 written today", d.line)
        assertEquals("Day 4 · Naxos", d.primary)
        assertEquals("Cyclades", d.secondary)
        // Tomorrow, without a name: the day's count, nothing written yet.
        assertEquals("Day 5 of 9", s.days[1].primary)
        assertTrue(s.days[1].line.startsWith("Day 5 of 9 · "))
        assertEquals(startOfDayMs(today.plusDays(1), null), s.days[1].from)
        // After the trip: no trip, and the date.
        assertEquals("No trip today", s.days[6].primary)
        assertTrue(s.days[6].line.startsWith("No trip today · "))
        assertEquals("Today", s.days[6].title)
    }

    @Test
    fun noTripNamesTheNextOne() {
        val book = book()
        book.newTrip("Crete", today.plusDays(12), today.plusDays(20))
        val s = WidgetSnapshot.build(book, Look.PostcardPop, demoMode = false, today = today)
        assertEquals("No trip today", s.days[0].primary)
        assertEquals("Crete in 12 days", s.days[0].secondary)
        assertEquals("Crete in 11 days", s.days[1].secondary)
    }

    @Test
    fun demoModeShowsTheExampleAndHidesTheTravellersOwn() {
        val book = book()
        book.newTrip("Crete", today.minusDays(1), today.plusDays(2))
        book.putRows(trips = listOf(com.justdataplease.boringtravel.data.db.Trip("demo-argentina", "Argentina & Patagonia", startDate = today.minusDays(13), endDate = today.plusDays(17), createdAt = 1, updatedAt = 1)))
        assertEquals("Crete", WidgetSnapshot.build(book, Look.Trail, demoMode = false, today = today).days[0].title)
        val demo = WidgetSnapshot.build(book, Look.Trail, demoMode = true, today = today)
        assertEquals("Argentina & Patagonia", demo.days[0].title)
        assertEquals("Day 14 of 31", demo.days[0].primary)
    }

    @Test
    fun eachThemesColoursPaperAndTheQuickBarsWidths() {
        val book = book()
        for (look in Look.entries) {
            val s = WidgetSnapshot.build(book, look, demoMode = false, today = today)
            assertEquals(look.id, s.look)
            for (key in listOf("surface", "ink", "muted", "primary", "onPrimary", "chip", "paper", "note", "onNote", "button", "onButton")) {
                val c = assertNotNull(s.colors[key], "${look.id}: $key")
                assertTrue(Regex("^#[0-9A-F]{8}$").matches(c), "${look.id}: $key = $c")
            }
            assertTrue(s.paper in setOf("dots", "lines", "grid", "plain"))
            // The JSON the widget reads comes back the same.
            assertEquals(s, WidgetSnapshot.parse(WidgetSnapshot.json(s)))
        }
        assertEquals("plain", WidgetSnapshot.build(book, Look.Trail, false, today).paper)
        assertTrue(WidgetSnapshot.build(book, Look.Trail, false, today).pill)
        // As the bar narrows: Voice goes first, then the stamp, then the text.
        val q = WidgetSnapshot.quickWidths()
        assertTrue(q.context < q.mark && q.mark < q.voice, "$q")
        assertTrue(QuickBarLayout.of(q.voice.toFloat(), 60f).voice)
        assertTrue(!QuickBarLayout.of(q.voice - 1f, 60f).voice)
        assertTrue(QuickBarLayout.of(q.context.toFloat(), 60f).context)
        assertTrue(!QuickBarLayout.of(q.context - 1f, 60f).context)
    }

    @Test
    fun theWriterSendsAChangeOnceAndTheStampWithANewTheme() {
        val widget = MemoryHomeWidget()
        val writer = WidgetWriter(widget)
        val book = book()
        writer.write(book, Look.PostcardPop, false, today)
        assertEquals(1, widget.saves)
        val stamp = assertNotNull(widget.mark)
        // A PNG.
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), stamp.take(4).map { it.toInt() and 0xFF })
        writer.write(book, Look.PostcardPop, false, today)
        assertEquals(1, widget.saves)
        book.newTrip("Crete", today, today.plusDays(2))
        writer.write(book, Look.PostcardPop, false, today)
        assertEquals(2, widget.saves)
        widget.mark = null
        writer.write(book, Look.Market, false, today)
        assertEquals(3, widget.saves)
        assertNotNull(widget.mark)
        assertEquals("market", WidgetSnapshot.parse(widget.snapshot!!)?.look)
    }
}
