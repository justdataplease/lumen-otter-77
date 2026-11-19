package com.justdataplease.boringtravel.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    /** The trip's newest book draft that isn't deleted. */
    @Query("SELECT * FROM books WHERE tripId = :tripId AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestForTrip(tripId: String): BookDraft?

    @Query("SELECT * FROM books WHERE tripId = :tripId AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatest(tripId: String): Flow<BookDraft?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookDraft?

    /** Every row, deleted ones too (backups). */
    @Query("SELECT * FROM books")
    suspend fun all(): List<BookDraft>

    /**
     * Deletes the trip's books (owner, 2026-11-08: "I must be able to delete the books"): marked deleted at [now], so
     * a backup carries the deletion; the journal and the photos are untouched. Make a book then starts a new one.
     */
    @Query("UPDATE books SET deletedAt = :now, updatedAt = :now WHERE tripId = :tripId AND deletedAt IS NULL")
    suspend fun deleteForTrip(tripId: String, now: Long)

    @Upsert
    suspend fun upsert(book: BookDraft)

    @Upsert
    suspend fun upsertAll(books: List<BookDraft>)
}
