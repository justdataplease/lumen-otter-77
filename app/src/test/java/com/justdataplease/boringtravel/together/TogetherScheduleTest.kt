package com.justdataplease.boringtravel.together

import com.justdataplease.boringtravel.data.db.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TogetherScheduleTest {
    private val today = LocalDate.of(2027, 5, 1)

    private fun trip(end: LocalDate?, deleted: Long? = null) =
        Trip("t", "Patagonia", startDate = end?.minusDays(5), endDate = end, createdAt = 1, updatedAt = 1, deletedAt = deleted)

    @Test fun tripsLongOverStopSyncingInTheBackground() {
        assertTrue(Together.inBackground(trip(today.plusDays(3)), today))
        assertTrue(Together.inBackground(trip(today.minusDays(Together.BACKGROUND_DAYS)), today))
        assertFalse(Together.inBackground(trip(today.minusDays(Together.BACKGROUND_DAYS + 1)), today))
        // A trip without dates (an idea) keeps syncing.
        assertTrue(Together.inBackground(trip(null), today))
        // Gone or deleted here: synced once more, which finishes leaving it.
        assertTrue(Together.inBackground(null, today))
        assertTrue(Together.inBackground(trip(today.minusDays(90), deleted = 5), today))
    }

    @Test fun everyChangeIsFollowedByASendThatStartsAfterIt() {
        val queued = ArrayList<String>()
        val sends = SendQueue { queued += it }
        // A note and its three photos: one send, and another trip's change has its own.
        repeat(4) { sends.changed("t") }
        sends.changed("u")
        assertEquals(listOf("t", "u"), queued)
        // The send starts and reads the trip: a change from now on needs the next send, queued once.
        sends.started("t")
        sends.changed("t")
        sends.changed("t")
        assertEquals(listOf("t", "u", "t"), queued)
        // Offline for hours, the send still waiting: later changes go with it.
        sends.changed("u")
        assertEquals(3, queued.size)
    }
}
