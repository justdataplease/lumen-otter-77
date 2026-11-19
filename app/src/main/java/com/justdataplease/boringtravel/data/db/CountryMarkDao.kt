package com.justdataplease.boringtravel.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CountryMarkDao {
    @Query("SELECT * FROM country_marks WHERE deletedAt IS NULL ORDER BY addedAt")
    fun observeLive(): Flow<List<CountryMark>>

    @Query("SELECT * FROM country_marks WHERE id = :id")
    suspend fun get(id: String): CountryMark?

    /** Every row, deleted ones too (backups). */
    @Query("SELECT * FROM country_marks")
    suspend fun all(): List<CountryMark>

    @Upsert
    suspend fun upsert(mark: CountryMark)

    @Upsert
    suspend fun upsertAll(marks: List<CountryMark>)
}
