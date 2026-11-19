package com.justdataplease.boringtravel.data.repo

import com.justdataplease.boringtravel.data.db.BtDatabase
import com.justdataplease.boringtravel.data.db.CountryMark
import kotlinx.coroutines.flow.Flow

/** Countries marked by hand: been there, want to go, or a found one removed. */
class CountryRepository(private val db: BtDatabase) {
    fun observe(): Flow<List<CountryMark>> = db.countryMarks().observeLive()

    /** Turns a mark on (a new row, or the deleted one back) or off (soft delete). */
    suspend fun set(code: String, source: String, on: Boolean) {
        val c = code.trim().uppercase()
        if (c.isEmpty()) return
        val id = CountryMark.id(source, c)
        val existing = db.countryMarks().get(id)
        val t = now()
        if (existing == null) {
            if (on) db.countryMarks().upsert(CountryMark(id, c, source, addedAt = t, updatedAt = t))
        } else if (on && existing.deletedAt != null) {
            db.countryMarks().upsert(existing.copy(addedAt = t, updatedAt = t, deletedAt = null))
        } else if (!on && existing.deletedAt == null) {
            db.countryMarks().upsert(existing.copy(updatedAt = t, deletedAt = t))
        }
    }

    /** "I've been here": also un-hides it, if it was removed before. */
    suspend fun markVisited(code: String) {
        set(code, CountryMark.SOURCE_HIDDEN, false)
        set(code, CountryMark.SOURCE_MANUAL, true)
        set(code, CountryMark.SOURCE_WISH, false)
    }

    /** Removes a country from the list: the hand-made mark goes, and one found from the route is hidden. */
    suspend fun remove(code: String, foundFromTrips: Boolean) {
        set(code, CountryMark.SOURCE_MANUAL, false)
        if (foundFromTrips) set(code, CountryMark.SOURCE_HIDDEN, true)
    }

    suspend fun setWish(code: String, on: Boolean) = set(code, CountryMark.SOURCE_WISH, on)
}
