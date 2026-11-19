package com.justdataplease.boringtravel.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A trip laid out as a printed book (Print & export › Make a book). [pages] is the book's own JSON
 * ([com.justdataplease.boringtravel.book.BookJson], versioned): the pages, their photos, crops and the book's copies
 * of the texts, so re-opening keeps every edit and the journal is never changed. [format], [theme] and [fontSet] are
 * stored by id; unknown ids fall back to the defaults.
 */
@Entity(tableName = "books", indices = [Index("tripId")])
data class BookDraft(
    @PrimaryKey val id: String,
    val tripId: String,
    val format: String,
    val theme: String,
    val fontSet: String,
    val bleed: Boolean,
    val pages: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)
