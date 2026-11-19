package com.justdataplease.boringtravel.engine

import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.time.localDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Route tracking on a phone without the Android database (the iPhone): recorded points kept as the backup's rows, a
 * deleted trip's route gone, the latest point for a note's place, and a trip's own level.
 */
class TripBookTrackingTest {
    private var clock = 1_000L
    private val file = MemoryTripFile()
    private fun book() = TripBook(file, now = { clock }, ids = { "id-${clock++}" })

    private fun saved() = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read())).toSnapshot()

    private fun point(trip: String?, time: Long, lat: Double = 37.1, lng: Double = 25.4) =
        TrackPoint(tripId = trip, time = time, lat = lat, lng = lng, accuracyM = 12f, speedMps = 1.5f, altitudeM = 40.0, level = "balanced")

    @Test
    fun pointsAreKeptAsTheBackupsRowsAndComeBackFromIt() {
        val book = book()
        val trip = book.newTrip("Naxos", localDate(2026, 6, 17), localDate(2026, 6, 26))
        book.addTrackPoints(emptyList())
        assertEquals(emptyList(), saved().trackPoints)
        book.addTrackPoints(listOf(point(trip.id, 5_000), point(trip.id, 65_000, lat = 37.11)))
        book.addTrackPoints(listOf(point(trip.id, 125_000, lat = 37.12)))
        val rows = saved().trackPoints
        assertEquals(listOf(5_000L, 65_000L, 125_000L), rows.map { it.time })
        assertEquals(point(trip.id, 65_000, lat = 37.11), rows[1])
        // What another phone restores is the same rows.
        val other = TripBook(MemoryTripFile(book.export()))
        assertEquals(rows, other.snapshot.trackPoints)
    }

    @Test
    fun aDeletedTripsRouteGoesAndTheOthersStay() {
        val book = book()
        val naxos = book.newTrip("Naxos", localDate(2026, 6, 17), localDate(2026, 6, 26))
        val crete = book.newTrip("Crete", localDate(2026, 7, 1), localDate(2026, 7, 5))
        book.addTrackPoints(listOf(point(naxos.id, 1), point(crete.id, 2), point(null, 3), point(naxos.id, 4)))
        assertTrue(book.removeTrip(naxos.id))
        assertTrue(book.removeTrackPoints(naxos.id))
        assertEquals(listOf(crete.id, null), saved().trackPoints.map { it.tripId })
        assertFalse(book.removeTrackPoints(naxos.id))
    }

    @Test
    fun theLatestPointOfAnyTrip() {
        val book = book()
        assertNull(book.latestTrackPoint())
        book.addTrackPoints(listOf(point("a", 30), point("b", 90, lat = 40.0), point(null, 60)))
        assertEquals(90L, book.latestTrackPoint()?.time)
        assertEquals(40.0, book.latestTrackPoint()?.lat)
    }

    @Test
    fun aTripsOwnLevelIsStampedAndCanFollowThePhoneAgain() {
        val book = book()
        val trip = book.newTrip("Naxos", localDate(2026, 6, 17), localDate(2026, 6, 26))
        clock = 5_000L
        assertEquals("detailed", book.setTrackingLevel(trip.id, "detailed")?.trackingLevel)
        val kept = saved().trips.single()
        assertEquals("detailed", kept.trackingLevel)
        assertEquals(5_000L, kept.updatedAt)
        // The same level again changes nothing, not even the stamp.
        clock = 6_000L
        assertEquals(5_000L, book.setTrackingLevel(trip.id, "detailed")?.updatedAt)
        assertNull(book.setTrackingLevel(trip.id, null)?.trackingLevel)
        assertEquals(6_000L, saved().trips.single().updatedAt)
        assertNull(book.setTrackingLevel("nope", "low"))
        book.removeTrip(trip.id)
        assertNull(book.setTrackingLevel(trip.id, "low"))
    }
}
