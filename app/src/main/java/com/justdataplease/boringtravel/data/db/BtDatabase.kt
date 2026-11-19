package com.justdataplease.boringtravel.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.LocalDate

class Converters {
    @TypeConverter
    fun dateToDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun dayToDate(day: Long?): LocalDate? = day?.let(LocalDate::ofEpochDay)
}

@Database(
    entities = [Trip::class, PlanItem::class, Entry::class, EntryFts::class, Media::class, TrackPoint::class, Stop::class, CountryMark::class, BookDraft::class],
    version = 9,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class BtDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun planItems(): PlanItemDao
    abstract fun entries(): EntryDao
    abstract fun media(): MediaDao
    abstract fun tracks(): TrackDao
    abstract fun stops(): StopDao
    abstract fun countryMarks(): CountryMarkDao
    abstract fun books(): BookDao

    companion object {
        const val NAME = "boringtravel.db"

        fun build(context: Context): BtDatabase =
            Room.databaseBuilder(context.applicationContext, BtDatabase::class.java, NAME).addMigrations(*Migrations.ALL).build()
    }
}
