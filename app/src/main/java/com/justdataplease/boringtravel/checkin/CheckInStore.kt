package com.justdataplease.boringtravel.checkin

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate

private val Context.checkInStore by preferencesDataStore("journal_check_ins")

/** Device-local UI history, deliberately excluded from portable backups; the confirmed Entry is backed up. */
class CheckInStore internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.checkInStore)

    /** Claim on presentation, not only on dismissal, so navigation/relaunch cannot ask the same question again. */
    suspend fun claim(tripId: String, day: LocalDate): Boolean {
        val token = "${day.toEpochDay()}:$tripId"
        var claimed = false
        store.edit { prefs ->
            val previous = prefs[ASKED].orEmpty()
            if (token !in previous) {
                // Retain a generous local-day window, including future dates after travel across time zones.
                prefs[ASKED] = previous.filter { it.substringBefore(':').toLongOrNull()?.let { d ->
                    d >= day.toEpochDay() - 60
                } == true }.toSet() + token
                claimed = true
            }
        }
        return claimed
    }

    private companion object { val ASKED = stringSetPreferencesKey("asked_trip_days") }
}
