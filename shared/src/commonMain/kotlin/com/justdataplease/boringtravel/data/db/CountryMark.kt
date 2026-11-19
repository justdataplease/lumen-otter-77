package com.justdataplease.boringtravel.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A country the traveller marked by hand: "I've been here" ([SOURCE_MANUAL]), "Want to go" ([SOURCE_WISH]), or a
 * country found from the route that they removed ([SOURCE_HIDDEN]). Countries found from stops, entries, photos and
 * plan items are worked out on the fly (world/WorldModel), never stored here.
 *
 * The id is "source:code" ("manual:GR"), so marking a country twice, or on two phones, is the same row; un-marking is
 * a soft delete like everywhere else, which backups and sync carry.
 */
@Entity(tableName = "country_marks", indices = [Index("code")])
data class CountryMark(
    @PrimaryKey val id: String,
    /** ISO 3166-1 alpha-2, upper case. */
    val code: String,
    val source: String,
    val addedAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
) {
    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_WISH = "wish"
        const val SOURCE_HIDDEN = "hidden"

        fun id(source: String, code: String) = "$source:${code.uppercase()}"
    }
}
