package com.justdataplease.boringtravel.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TripBookTest {
    private val book = ExportFixtures.book()

    @Test fun daysCarryNumbersEntriesAndPhotosInOrder() {
        val days = book.days(ExportOptions())
        assertEquals(listOf(3, 4), days.map { it.number })
        assertEquals(10, days.first().total)
        val d4 = days[1]
        assertEquals(listOf("Ferry day", "The bus to Halki"), d4.entries.map { it.entry.title })
        // Entry photos in entry order, then the loose ones by time.
        assertEquals(listOf("p-ferry", "p-halki", "p-loose1", "p-loose2"), d4.photos.map { it.id })
        assertEquals(listOf("p-loose1", "p-loose2"), d4.loosePhotos.map { it.id })
        assertEquals("Halki, Naxos", d4.place)
        assertEquals(27.0, d4.tempC!!, 0.0)
        assertEquals(2, d4.stops.size)
        assertEquals(listOf("Ferry Paros → Naxos", "Lunch in Halki"), d4.plan.map { it.title })
    }

    @Test fun movedDistanceIsPlausible() {
        val km = book.days(ExportOptions())[1].km
        // Paros → Naxos by ferry (~20 km with the bend), then the bus up to Halki.
        assertTrue("$km", km in 25.0..45.0)
    }

    @Test fun distanceBetweenTwoKnownPoints() {
        val km = Geo.distanceKm(GeoPoint(37.9838, 23.7275), GeoPoint(37.0853, 25.1488)) // Athens → Parikia
        assertTrue("$km", km in 155.0..170.0)
    }

    @Test fun somedayTripCountsItsOwnDays() {
        val someday = book.copy(trip = book.trip.copy(startDate = null, endDate = null))
        val days = someday.days(ExportOptions())
        assertEquals(listOf(1, 2), days.map { it.number })
        assertEquals(2, days.first().total)
    }

    @Test fun deletedRowsAreLeftOut() {
        val b = book.copy(entries = book.entries.map { if (it.id == "e3") it.copy(deletedAt = 5) else it }, media = book.media.filterNot { it.id == "p-beach" })
        assertEquals(1, b.days(ExportOptions()).size)
    }

    @Test fun paperDefaultsByCountry() {
        assertEquals(Paper.Letter, Paper.default(Locale.US))
        assertEquals(Paper.A4, Paper.default(Locale.UK))
        assertEquals(Paper.A4, Paper.default(Locale.forLanguageTag("el-GR")))
    }
}
