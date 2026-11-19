package com.justdataplease.boringtravel.engine

import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.data.db.CountryMark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Countries marked by hand (Your world), as the Android app's CountryRepository keeps them: one row per source and
 * country ("manual:GR"), turned off as a deleted row so a restore carries it, back on as the same row.
 */
class TripBookCountriesTest {
    private var clock = 1_000L
    private val file = MemoryTripFile()
    private fun book() = TripBook(file, now = { clock })

    private fun saved() = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read())).toSnapshot().countryMarks

    @Test
    fun beenHereWantToGoAndRemovedAreRowsOfTheirOwn() {
        val book = book()
        assertTrue(book.setCountryWish("jp", true))
        assertEquals(listOf(CountryMark("wish:JP", "JP", CountryMark.SOURCE_WISH, 1_000L, 1_000L)), saved())
        // Marking it twice changes nothing.
        assertFalse(book.setCountryWish("JP", true))

        // "I've been here": added by hand, off the want-to-go list (kept as deleted), never hidden.
        clock = 2_000L
        assertTrue(book.markCountryVisited("JP"))
        val marks = saved().associateBy { it.id }
        assertEquals(setOf("wish:JP", "manual:JP"), marks.keys)
        assertEquals(2_000L, marks.getValue("wish:JP").deletedAt)
        assertEquals(null, marks.getValue("manual:JP").deletedAt)
        assertEquals(listOf("manual:JP"), book.countryMarks().map { it.id })

        // Removed: the hand-made mark goes; a country found from the trips is hidden as well.
        clock = 3_000L
        assertTrue(book.removeCountry("JP", foundFromTrips = true))
        val after = saved().associateBy { it.id }
        assertEquals(3_000L, after.getValue("manual:JP").deletedAt)
        assertEquals(null, after.getValue("hidden:JP").deletedAt)
        assertEquals(listOf("hidden:JP"), book.countryMarks().map { it.id })

        // Back again: the same rows, live again, with new stamps; never earlier than their last.
        clock = 2_500L
        assertTrue(book.markCountryVisited("JP"))
        val back = saved().associateBy { it.id }
        assertEquals(null, back.getValue("manual:JP").deletedAt)
        assertEquals(3_001L, back.getValue("manual:JP").updatedAt)
        assertEquals(3_001L, back.getValue("hidden:JP").deletedAt)
        assertEquals(setOf("manual:JP"), book.countryMarks().map { it.id }.toSet())
    }

    @Test
    fun marksKeepTheRestOfTheTripsAndTravelOnThroughOtherChanges() {
        val book = book()
        val trip = book.newTrip("Naxos")
        book.setCountryWish("GR", true)
        book.addItem(trip.id, "Portara")
        // A later change to the trips keeps the marks in data.json.
        assertEquals(listOf("wish:GR"), saved().map { it.id })
        assertFalse(book.removeCountry("", foundFromTrips = false))
    }
}
