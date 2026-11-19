package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.ProgramLibrary
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The iPhone app on the engine, as far as Windows can run it: the screens' own path (use a trip idea, add to the plan,
 * tick, write a note, close and open the app again, delete) and the screens drawn off screen with those trips.
 */
class EngineOnDesktopTest {
    private val today = SAMPLE_TODAY

    @Test
    fun aTripMadeOnTheScreensIsThereAfterTheAppIsOpenedAgain() {
        val file = MemoryTripFile()
        val first = TripBook(file)
        // Trip idea › Use this program, starting a month on (TripIdea).
        val kyoto = ProgramLibrary.destinations.first()
        val trip = first.startFromIdea(kyoto, kyoto.default, Pace.Default, today.plusDays(28))
        // The trip's page: Add on its first day, a tick, Write (TripPage).
        val day = first.days(trip.id).first()
        val added = assertNotNull(first.addItem(trip.id, "Tea ceremony", day.date))
        first.setDone(added.id, true)
        assertNotNull(first.writeNote(trip.id, "Booked the ryokan."))

        // A cold start reads the same trips from what was kept.
        val second = TripBook(file)
        assertEquals(first.snapshot, second.snapshot)
        assertEquals(listOf(trip.id), second.sections(today).next.map { it.id })
        assertEquals("Tea ceremony", second.days(trip.id).first().steps.last().item.title)
        assertTrue(second.days(trip.id).first().steps.last().item.done)
        assertEquals("Booked the ryokan.", second.entries(trip.id).single().plainText)

        // Delete trip.
        assertTrue(second.removeTrip(trip.id))
        assertTrue(TripBook(file).trips(today).isEmpty())
    }

    @Test
    fun theScreensDrawTheTravellersTrips() = runBlocking {
        BtFontFiles.load()
        val empty = TripBook(MemoryTripFile())
        val book = sampleBook()
        val current = assertNotNull(book.sections(today).now)
        fun png(book: TripBook, vararg at: Place) = render(660, 1434, 1.5f) { BoringTravelApp(book, AppSettings.ready(), today, startAt = at.toList()) }

        val newTraveller = png(empty)
        val trips = png(book)
        val page = png(book, Place.Trip(current.id))
        // Three different pictures, none of them an empty page.
        assertFalse(newTraveller.contentEquals(trips))
        assertFalse(trips.contentEquals(page))
        assertTrue(listOf(newTraveller, trips, page).all { it.size > 20_000 })

        // A tick changes what the trip's page shows (today's first row: the page opens on today).
        val item = book.days(current.id).first { it.date == today }.steps.first { !it.end }.item
        book.setDone(item.id, !item.done)
        assertFalse(page.contentEquals(png(book, Place.Trip(current.id))))
        if (System.getProperty("shots") != null) File(System.getProperty("shots"), "test-trip.png").writeBytes(page)
    }
}
