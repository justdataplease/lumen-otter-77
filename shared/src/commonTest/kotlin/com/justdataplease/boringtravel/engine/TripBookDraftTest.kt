package com.justdataplease.boringtravel.engine

import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.time.localDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A reviewed AI planner draft written into the trip store as the Android app writes it (saveWithChoices, then
 * saveAll): the trip and the rows replaced or added in one save, stamped never earlier than before, still a backup's
 * data.json.
 */
class TripBookDraftTest {
    private var clock = 1_000L
    private var next = 0
    private val file = MemoryTripFile()
    private val book = TripBook(file, now = { clock }, ids = { "id-${next++}" })
    private val start = localDate(2027, 5, 3)

    private fun restored() = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read())).toSnapshot()

    @Test
    fun aNewTripAndItsItemsAreWrittenInOneSave() {
        val trip = Trip("t1", "Lisbon", "Alfama → Belém", start, start.plusDays(3), createdAt = 5, updatedAt = 5)
        val stay = PlanItem("i1", "t1", start, 1.0, kind = "stay", title = "Stay in Alfama", endDays = 3, source = "ai", createdAt = 5, updatedAt = 5)
        val idea = PlanItem("i2", "t1", null, 1.0, title = "Pastéis de Belém", source = "ai", createdAt = 5, updatedAt = 5)
        clock = 2_000L
        book.saveDraft(trip, listOf(stay, idea))
        val saved = assertNotNull(book.trip("t1"))
        assertEquals("Alfama → Belém", saved.subtitle)
        assertEquals(2_000L, saved.updatedAt)
        assertEquals(listOf("Stay in Alfama", "Pastéis de Belém"), book.items("t1").map { it.title })
        assertTrue(book.items("t1").all { it.updatedAt == 2_000L })
        assertEquals(book.snapshot, restored())
    }

    @Test
    fun changedAndRemovedRowsReplaceTheirsAndStampsNeverGoBack() {
        val trip = book.newTrip("Rome", start, start.plusDays(2))
        val item = assertNotNull(book.addItem(trip.id, "Colosseum", start))
        val later = item.copy(timeMinutes = 9 * 60 + 30, updatedAt = 9_000L)
        val gone = assertNotNull(book.addItem(trip.id, "Forum", start)).let { it.copy(deletedAt = 3_000L, updatedAt = 3_000L) }
        clock = 3_000L
        book.saveDraft(trip.copy(title = "Rome in May", updatedAt = trip.updatedAt), listOf(later, gone))
        assertEquals("Rome in May", book.trip(trip.id)?.title)
        assertEquals(570, book.item(item.id)?.timeMinutes)
        // A row stamped later than now keeps a later stamp.
        assertEquals(9_001L, book.item(item.id)?.updatedAt)
        assertNull(book.item(gone.id))
        assertEquals(2, book.snapshot.planItems.size)
        assertEquals(book.snapshot, restored())
        // Nothing given: nothing written.
        val before = file.read()
        book.saveDraft(null, emptyList())
        assertEquals(before, file.read())
    }
}
