package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.calendar.CalendarImport
import com.justdataplease.boringtravel.calendar.CalendarLinks
import com.justdataplease.boringtravel.calendar.CalendarMarker
import com.justdataplease.boringtravel.calendar.CalendarSync
import com.justdataplease.boringtravel.calendar.EventTime
import com.justdataplease.boringtravel.calendar.IcsWriter
import com.justdataplease.boringtravel.calendar.MemoryPhoneCalendar
import com.justdataplease.boringtravel.calendar.TripEvents
import com.justdataplease.boringtravel.calendar.TripZone
import com.justdataplease.boringtravel.calendar.wallMs
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trip in the phone's calendar, through the recording fake ([MemoryPhoneCalendar]): what a trip's events say,
 * written, kept updated, moved, removed with the trip; never anyone else's event touched; the .ics file; importing
 * events into the plan; and the Calendar sheet with its question about access, as the Android app's rules have it.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarOnDesktopTest {
    init {
        runBlocking { BtFontFiles.load() }
    }

    private val start = localDate(2027, 4, 2)
    private val zone = "Europe/Athens"

    /** Naxos in April: a ferry at 07:30, a dinner 19:30–21:00, a hotel over three nights, an idea. */
    private fun calendarBook(): Pair<TripBook, Trip> {
        var clock = 1_795_000_000_000L
        var n = 0
        val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "cal-${n++}" })
        val trip = book.newTrip("Naxos", start, start.plusDays(4), subtitle = "Paros → Naxos")
        book.saveItem(book.draftItem(trip.id, start, 1.0).copy(title = "Ferry from Paros", kind = PlanKind.Transport.id, timeMinutes = 7 * 60 + 30, url = "https://www.blue-star.gr"))
        book.saveItem(book.draftItem(trip.id, start, 2.0).copy(title = "Hotel Grotta", kind = PlanKind.Stay.id, endDays = 3, placeName = "Chora, Naxos"))
        book.saveItem(book.draftItem(trip.id, start.plusDays(1), 1.0).copy(title = "Dinner in Chora", kind = PlanKind.Food.id, timeMinutes = 19 * 60 + 30, endMinutes = 21 * 60, note = "Book a table"))
        book.addItem(trip.id, "Portara at sunset", null)
        return book to trip
    }

    @Test
    fun theTripsEventsSayWhatAndroidsSay() {
        val (book, trip) = calendarBook()
        val events = TripEvents.build(trip, book.items(trip.id), forPhone = true)
        // The trip all day and free; each item on its day; the idea left out.
        // Within a day, untimed items first (Android's order: day, time or none, position).
        assertEquals(listOf("trip", "item/cal-2", "item/cal-1", "item/cal-3"), events.map { it.key })
        assertEquals(EventTime.Days(start, start.plusDays(5)), events[0].time)
        assertTrue(events[0].free)
        assertEquals(EventTime.At(start, 7 * 60 + 30, 60), events[2].time)
        // A stay up to its check-out morning: Thu to Sun covers three days.
        assertEquals(EventTime.Days(start, start.plusDays(3)), events[1].time)
        assertEquals(EventTime.At(start.plusDays(1), 19 * 60 + 30, 90), events[3].time)
        assertEquals("Book a table", events[3].description)
        assertEquals("https://www.blue-star.gr", events[2].description)
        // A timed event at its wall time in the trip's zone, as java.time's atZone puts it.
        val dinner = TripEvents.fields(events[3], zone)
        val expected = java.time.LocalDate.of(2027, 4, 3).atTime(19, 30).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
        assertEquals(expected, dinner.startMs)
        assertEquals(expected + 90 * 60_000L, dinner.endMs)
        assertEquals(expected, wallMs(start.plusDays(1), 19 * 60 + 30, zone))
        // All-day events at UTC midnights.
        assertEquals(start.toEpochDay() * 86_400_000L, TripEvents.fields(events[0], zone).startMs)
        // An item that came from the phone's calendar stays out of the phone's events, but not out of the .ics file.
        val imported = book.saveItem(book.draftItem(trip.id, start, 3.0).copy(title = "Boat tour", source = ItemSource.Calendar.id))!!
        assertFalse(TripEvents.build(trip, book.items(trip.id), forPhone = true).any { it.key == "item/${imported.id}" })
        assertTrue(TripEvents.build(trip, book.items(trip.id)).any { it.key == "item/${imported.id}" })
        // The zone's names.
        assertEquals("Athens · GMT+3", TripZone.label(zone, start))
        assertEquals("GMT+5:30", TripZone.offsetLabel("Asia/Kolkata", start))
    }

    @Test
    fun eventsAreWrittenUpdatedMovedAndRemovedAndNothingElseIsTouched() = runBlocking {
        val (book, trip) = calendarBook()
        val phone = MemoryPhoneCalendar()
        val dentist = phone.addOther("cal-icloud-home", "Dentist", wallMs(start, 10 * 60, zone), wallMs(start, 11 * 60, zone))
        val sync = CalendarSync(book, CalendarLinks(MemorySettingsFile()), phone, now = { 1_800_000_000_000L })
        val home = phone.calendars().first { it.id == "cal-icloud-home" }

        val added = sync.add(trip.id, home, zone)
        assertEquals(4, added.added)
        assertEquals("Home", added.calendarName)
        val ours = phone.events.values.filter { it.id != dentist }
        assertEquals(4, ours.size)
        // Each carries the marker: its link, and the source line at the end of its notes.
        ours.forEach { e ->
            assertNotNull(CalendarMarker.parse(e.url))
            assertTrue(e.notes.lines().last().startsWith("BoringTravel link: boringtravel://calendar/com.justdataplease.boringtravel/${trip.id}/"))
        }
        val link = assertNotNull(sync.link(trip.id))
        assertTrue(link.autoUpdate)
        assertEquals(4, link.events.size)
        assertFalse(link.isBehind(trip, book.items(trip.id), zone))

        // The plan changes: the calendar is behind, and an automatic update brings it in step (one event changed).
        val dinner = book.items(trip.id).single { it.title == "Dinner in Chora" }
        book.saveItem(dinner.copy(timeMinutes = 20 * 60, endMinutes = 22 * 60))
        assertTrue(link.isBehind(book.trip(trip.id)!!, book.items(trip.id), zone))
        sync.reconcileAutomatic()
        assertTrue(phone.calls.contains("apply cal-icloud-home +0 ~1 -0"))
        val moved = phone.events.values.single { it.fields.title == "Dinner in Chora" }
        assertEquals(wallMs(start.plusDays(1), 20 * 60, zone), moved.fields.startMs)
        assertNull(sync.pending(trip.id))
        // Ticking an item changes nothing in the calendar.
        book.setDone(dinner.id, true)
        assertFalse(sync.link(trip.id)!!.isBehind(book.trip(trip.id)!!, book.items(trip.id), zone))

        // An item deleted goes from the calendar; nothing new: "is up to date".
        book.removeItem(book.items(trip.id).single { it.title == "Ferry from Paros" }.id)
        val after = sync.update(trip.id)
        assertEquals(1, after.removed)
        assertEquals("Home is up to date.", com.justdataplease.boringtravel.ios.updated(sync.update(trip.id)))

        // Moved to another calendar: the events leave Home for Work.
        val work = phone.calendars().first { it.id == "cal-icloud-work" }
        val r = sync.add(trip.id, work, zone)
        assertEquals(3, r.removed)
        assertEquals(3, phone.events.values.count { it.calendarId == "cal-icloud-work" })
        assertEquals(listOf(dentist), phone.events.values.filter { it.calendarId == "cal-icloud-home" }.map { it.id })

        // Removed: every event of the trip, never the dentist.
        assertEquals(3, sync.remove(trip.id))
        assertEquals(listOf(dentist), phone.events.keys.toList())
        assertNull(sync.link(trip.id))
    }

    @Test
    fun aDeletedTripTakesItsEventsAndAnEventChangedElsewhereIsLeftForReview() = runBlocking {
        val (book, trip) = calendarBook()
        val phone = MemoryPhoneCalendar()
        val sync = CalendarSync(book, CalendarLinks(MemorySettingsFile()), phone)
        sync.add(trip.id, phone.calendars().first(), zone)
        assertEquals(4, phone.events.size)
        book.removeTrip(trip.id)
        sync.removeDeleted(trip.id)
        assertTrue(phone.events.isEmpty())
        assertNull(sync.link(trip.id))
        // Without access, removing quietly does nothing and says nothing.
        phone.access = "refused"
        sync.removeDeleted("another")
    }

    @Test
    fun theIcsFileIsRfc5545() {
        val (book, trip) = calendarBook()
        val events = TripEvents.build(trip, book.items(trip.id))
        val stamp = Instant.parse("2026-11-18T10:00:00Z").toEpochMilli()
        val text = IcsWriter.write(trip.title, events, zone, stamp, "1.0")
        val lines = text.split("\r\n")
        assertEquals("BEGIN:VCALENDAR", lines.first())
        assertTrue(text.endsWith("END:VCALENDAR\r\n"))
        assertTrue("X-WR-CALNAME:Naxos" in lines)
        // The zone's rules over the trip: summer time already began on 28 March 2027.
        assertTrue("TZID:Europe/Athens" in lines)
        val vtz = lines.subList(lines.indexOf("BEGIN:VTIMEZONE"), lines.indexOf("END:VTIMEZONE") + 1)
        assertTrue("BEGIN:DAYLIGHT" in vtz)
        assertTrue("TZOFFSETFROM:+0200" in vtz && "TZOFFSETTO:+0300" in vtz)
        assertTrue("DTSTART:20270328T030000" in vtz)
        assertTrue("DTSTART;TZID=Europe/Athens:20270403T193000" in lines)
        assertTrue("DTEND;TZID=Europe/Athens:20270403T210000" in lines)
        assertTrue("DTSTART;VALUE=DATE:20270402" in lines && "DTEND;VALUE=DATE:20270407" in lines)
        assertTrue("UID:trip-${trip.id}@boringtravel.justdataplease.com" in lines)
        assertTrue("DTSTAMP:20261118T100000Z" in lines)
        assertTrue("TRANSP:TRANSPARENT" in lines)
        // Escapes and folding at 75 octets, never inside a character.
        assertEquals("a\\, b\\; c\\\\ d\\n e", IcsWriter.text("a, b; c\\ d\n e"))
        val long = "DESCRIPTION:" + "Ελληνικά ".repeat(20)
        IcsWriter.fold(long).split("\r\n").forEach { assertTrue(it.encodeToByteArray().size <= 75) }
        assertEquals(long, IcsWriter.fold(long).replace("\r\n ", ""))
        // A fixed offset writes UTC times.
        assertFalse(IcsWriter.usesTzid("UTC"))
        assertTrue(IcsWriter.write("x", events, "UTC", stamp, "1.0").contains("DTSTART:20270402T073000Z"))
        assertEquals("Naxos.ics", IcsWriter.fileName("Naxos"))
    }

    @Test
    fun eventsInTheTripsDatesBecomePlanItemsOnce() = runBlocking {
        val (book, trip) = calendarBook()
        val phone = MemoryPhoneCalendar()
        phone.addOther("cal-google", "Flight A3 361 to Naxos", wallMs(start, 6 * 60, zone), wallMs(start, 7 * 60, zone))
        phone.addOther("cal-google", "Call with Maria", wallMs(start.plusDays(2), 18 * 60, zone), wallMs(start.plusDays(2), 18 * 60 + 30, zone), notes = "<p>Zoom</p>")
        phone.addOther("cal-google", "Dinner in Chora", wallMs(start.plusDays(1), 19 * 60 + 30, zone), wallMs(start.plusDays(1), 21 * 60, zone))
        phone.addOther("cal-google", "Last year", wallMs(start.minusDays(400), 9 * 60, zone), wallMs(start.minusDays(400), 10 * 60, zone))
        val sync = CalendarSync(book, CalendarLinks(MemorySettingsFile()), phone)
        sync.setZone(trip.id, zone)
        val found = sync.importCandidates(trip.id)
        assertEquals(listOf("Flight A3 361 to Naxos", "Dinner in Chora", "Call with Maria"), found.map { it.title })
        val flight = found.first()
        assertTrue(flight.guessed)
        assertEquals(PlanKind.Transport, flight.kind)
        assertEquals(6 * 60, flight.timeMinutes)
        // The dinner is in the plan already (same title, day and time).
        assertTrue(found.single { it.title == "Dinner in Chora" }.inPlan)
        assertEquals("Zoom", CalendarImport.note("<p>Zoom</p>"))
        assertEquals(2, sync.import(trip.id, found.filter { !it.inPlan }))
        val item = book.items(trip.id).single { it.title == "Flight A3 361 to Naxos" }
        assertEquals(ItemSource.Calendar.id, item.source)
        assertTrue(item.externalId!!.startsWith("cal:"))
        // Never twice.
        assertEquals(0, sync.import(trip.id, found))
        assertTrue(sync.importCandidates(trip.id).filter { it.title != "Dinner in Chora" }.all { it.inPlan })
    }

    @Test
    fun theCalendarSheetAsksForAccessThenAddsTheTrip() {
        val (book, trip) = calendarBook()
        val phone = MemoryPhoneCalendar(access = "notAsked")
        val settings = AppSettings.ready()
        settings.calendarLinks.setZone(trip.id, zone)
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent {
                BoringTravelApp(book, settings, start, startAt = listOf(Place.Trip(trip.id)), version = "1.0", calendar = phone)
            }
            waitForIdle()
            onNodeWithContentDescription("Trip options").performClick()
            onNodeWithText("Calendar").performClick()
            waitForIdle()
            onNodeWithText("The trip as an all-day event, and each planned item on its day.").assertExists()
            onNodeWithText("Add to calendar").performClick()
            waitForIdle()
            // Asked only here, after one line.
            onNodeWithText("To add the trip, BoringTravel needs your calendars. It only changes events it adds.").assertExists()
            onNodeWithText("Allow").performClick()
            waitForIdle()
            assertTrue("requestAccess" in phone.calls)
            onNodeWithText("Choose a calendar", ignoreCase = true).assertExists()
            onNodeWithText("iCloud").assertExists()
            onNodeWithText("Times in Athens · GMT+3").assertExists()
            // Google's main calendar is picked first, as on Android.
            onNodeWithText("Add 4 events").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Added to ana@gmail.com.").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(4, phone.events.size)
            onNodeWithText("Keep calendar updated").assertExists()
            onNodeWithText("Remove from calendar").performScrollTo().performClick()
            onNodeWithText("Remove").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Removed 4 events from ana@gmail.com.").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(phone.events.isEmpty())
        }
    }

    @Test
    fun refusedAccessOffersTheIcsFileAndSettings() {
        val (book, trip) = calendarBook()
        val phone = MemoryPhoneCalendar(access = "notAsked", answer = false)
        var shared: String? = null
        val fake = Phone(shareFile = { name, mime, bytes, subject -> shared = "$name $mime ${bytes.decodeToString().lines().first()} · $subject"; true })
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent {
                CompositionLocalProvider(LocalPhone provides fake) {
                    BoringTravelApp(book, AppSettings.ready(), start, startAt = listOf(Place.Trip(trip.id)), version = "1.0", calendar = phone)
                }
            }
            waitForIdle()
            onNodeWithContentDescription("Trip options").performClick()
            onNodeWithText("Calendar").performClick()
            onNodeWithText("Add to calendar").performClick()
            onNodeWithText("Allow").performClick()
            waitForIdle()
            onNodeWithText("No calendar access.").assertExists()
            onNodeWithText("Open settings").performScrollTo().performClick()
            assertTrue("openSettings" in phone.calls)
            onNodeWithText("Share .ics file").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { shared != null }
            // The trip's name as the subject, as Android's shareIcs gives it.
            assertEquals("Naxos.ics text/calendar BEGIN:VCALENDAR · Naxos", shared)
        }
    }

    @Test
    fun anItemOpensThePhonesNewEventScreen() {
        val (book, trip) = calendarBook()
        val phone = MemoryPhoneCalendar(access = "notAsked")
        val dinner = book.items(trip.id).single { it.title == "Dinner in Chora" }
        runDesktopComposeUiTest(width = 440, height = 2400) {
            setContent {
                BoringTravelApp(book, AppSettings.ready(), start, startAt = listOf(Place.Trip(trip.id)), version = "1.0", calendar = phone)
            }
            waitForIdle()
            onNodeWithText("Dinner in Chora").performClick()
            waitForIdle()
            onNodeWithText("Add to calendar").performScrollTo().performClick()
            onNodeWithText("Opens your calendar app with this event. To keep all plan steps, sync calendar.").assertExists()
            val opened = assertNotNull(phone.opened)
            assertEquals("Dinner in Chora", opened[0])
            assertEquals("Book a table\n\nNaxos", opened[2])
            assertEquals(dinner.id.isNotEmpty(), true)
            // No access was asked for that.
            assertFalse("requestAccess" in phone.calls)
        }
    }
}
