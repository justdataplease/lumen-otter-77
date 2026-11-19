package com.justdataplease.boringtravel.engine

import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.data.db.BookDraft
import com.justdataplease.boringtravel.time.localDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A trip's book drafts (Make a book) in the trip store, as the Android app's BookDao keeps them: the newest live draft
 * opens, a save replaces its row, a deleted trip's books are marked deleted, and all of it is still a backup's data.json.
 */
class TripBookBooksTest {
    private var clock = 1_000L
    private var next = 0
    private val file = MemoryTripFile()
    private val book = TripBook(file, now = { clock }, ids = { "id-${next++}" })
    private val start = localDate(2027, 6, 16)

    private fun restored() = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read())).toSnapshot()

    private fun draft(id: String, tripId: String, updatedAt: Long, pages: String = """{"v":2,"pages":[]}""") =
        BookDraft(id, tripId, "a5", "notebook", "theme", false, pages, createdAt = 10, updatedAt = updatedAt)

    @Test
    fun theNewestLiveDraftOpensAndASaveReplacesItsRow() {
        val trip = book.newTrip("Cyclades, slowly", start, start.plusDays(9))
        assertNull(book.latestBook(trip.id))
        assertNotNull(book.saveBook(draft("b1", trip.id, 100)))
        assertNotNull(book.saveBook(draft("b2", trip.id, 200)))
        assertEquals("b2", book.latestBook(trip.id)?.id)
        // A later save of the first one makes it the newest; its row is replaced, not added.
        assertNotNull(book.saveBook(draft("b1", trip.id, 300, """{"v":2,"paper":"lines","pages":[]}""")))
        assertEquals("b1", book.latestBook(trip.id)?.id)
        assertEquals(2, book.snapshot.books.size)
        assertEquals("""{"v":2,"paper":"lines","pages":[]}""", book.latestBook(trip.id)?.pages)
        // A book of a trip that isn't here is left out.
        assertNull(book.saveBook(draft("b3", "nowhere", 400)))
        assertEquals(book.snapshot, restored())
    }

    @Test
    fun aDeletedTripsBooksAreMarkedDeletedAndOthersStay() {
        val trip = book.newTrip("Cyclades, slowly", start, start.plusDays(9))
        val other = book.newTrip("Iceland")
        book.saveBook(draft("b1", trip.id, 100))
        book.saveBook(draft("b2", other.id, 100))
        clock = 5_000L
        book.removeTrip(trip.id)
        assertTrue(book.removeBooks(trip.id))
        assertNull(book.latestBook(trip.id))
        val gone = book.snapshot.books.first { it.id == "b1" }
        assertEquals(5_000L, gone.deletedAt)
        assertEquals(5_000L, gone.updatedAt)
        assertEquals("b2", book.latestBook(other.id)?.id)
        // Nothing left to delete: nothing written.
        assertFalse(book.removeBooks(trip.id))
        assertEquals(book.snapshot, restored())
    }
}
