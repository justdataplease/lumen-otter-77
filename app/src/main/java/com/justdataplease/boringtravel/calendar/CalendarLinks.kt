package com.justdataplease.boringtravel.calendar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.util.UUID

/** Which phone calendar a trip was added to, and what was written there at the last add or update. */
@Serializable
data class CalendarLink(
    val calendarId: Long,
    val calendarName: String,
    val accountName: String = "",
    val accountType: String = "",
    val color: Int = 0,
    /** The zone the times were written in. */
    val zone: String,
    /** [TripEvents.fingerprint] of what was written; the Plan tab offers an update when the plan's differs. */
    val fingerprint: String,
    val updatedAt: Long,
    /** Event ids by key, in case a calendar's sync drops our marker from the events. */
    val events: Map<String, Remembered> = emptyMap(),
    /** New explicit connections enable this; legacy manual links remain manual until selected. */
    val autoUpdate: Boolean = false,
) {
    val accountLabel: String get() = CalendarGroup(CalendarAccounts.kind(accountType, accountName), accountName, emptyList()).title

    /** The zone the trip's times are read in now: the one picked since, else the one written. */
    fun zoneNow(chosen: String?): ZoneId = TripZone.parse(chosen) ?: TripZone.parse(zone) ?: ZoneId.systemDefault()

    /** True when the calendar no longer matches the plan (an item, the trip's dates or the zone changed). */
    fun isBehind(trip: Trip, items: List<PlanItem>, chosenZone: String?): Boolean =
        TripEvents.fingerprint(TripEvents.build(trip, items, forPhone = true), zoneNow(chosenZone)) != fingerprint

    fun matches(calendar: CalendarInfo): Boolean = calendar.id == calendarId && calendar.writable &&
        calendar.accountType == accountType && calendar.accountName.equals(accountName, ignoreCase = true)
}

data class CalendarBinding(val link: CalendarLink, val chosenZone: String?)

/** Persisted before work is scheduled; a token prevents an older completion clearing a newer edit. */
@Serializable
data class PendingCalendarUpdate(
    val fingerprint: String,
    val token: String,
    val phase: String = "pending",
    val problem: String? = null,
) {
    val label: String get() = when {
        problem == "permission" -> "Calendar access is paused. Allow access to continue."
        problem == "calendar" -> "Choose a calendar to resume updates."
        problem == "ownership" -> "Review the linked calendar event before updating."
        phase == "updating" -> "Updating your calendar…"
        problem == "retry" -> "Calendar update waiting to retry."
        else -> "Calendar update queued."
    }
}

private val Context.calendarStore: DataStore<Preferences> by preferencesDataStore("calendar_links")

/**
 * Per-trip calendar links and chosen zones. They describe this phone's calendar rows, so they stay on the phone:
 * a separate DataStore, not part of the Room database and so not in Drive backups or sync.
 */
class CalendarLinks internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.calendarStore)
    private val json = Json { ignoreUnknownKeys = true }

    private fun linkKey(tripId: String) = stringPreferencesKey("link_$tripId")
    private fun zoneKey(tripId: String) = stringPreferencesKey("zone_$tripId")
    private fun pendingKey(tripId: String) = stringPreferencesKey("pending_$tripId")

    private fun decode(s: String?): CalendarLink? = s?.let { runCatching { json.decodeFromString(CalendarLink.serializer(), it) }.getOrNull() }
    private fun decodePending(s: String?): PendingCalendarUpdate? = s?.let { runCatching { json.decodeFromString(PendingCalendarUpdate.serializer(), it) }.getOrNull() }
    private fun bindings(prefs: Preferences): Map<String, CalendarBinding> = prefs.asMap().entries.mapNotNull { (key, value) ->
        if (!key.name.startsWith("link_")) null else {
            val id = key.name.removePrefix("link_")
            decode(value as? String)?.let { id to CalendarBinding(it, prefs[zoneKey(id)]) }
        }
    }.toMap()

    fun observeBindings(): Flow<Map<String, CalendarBinding>> = store.data.map(::bindings).distinctUntilChanged()
    suspend fun bindings(): Map<String, CalendarBinding> = bindings(store.data.first())
    fun observePending(tripId: String): Flow<PendingCalendarUpdate?> = store.data.map { decodePending(it[pendingKey(tripId)]) }.distinctUntilChanged()
    suspend fun pending(tripId: String): PendingCalendarUpdate? = decodePending(store.data.first()[pendingKey(tripId)])

    fun observe(tripId: String): Flow<CalendarLink?> = store.data.map { decode(it[linkKey(tripId)]) }.distinctUntilChanged()
    suspend fun get(tripId: String): CalendarLink? = decode(store.data.first()[linkKey(tripId)])

    suspend fun put(tripId: String, link: CalendarLink?) {
        store.edit {
            if (link == null) it.remove(linkKey(tripId)) else it[linkKey(tripId)] = json.encodeToString(CalendarLink.serializer(), link)
            if (link?.autoUpdate != true) it.remove(pendingKey(tripId))
        }
    }

    suspend fun enqueue(tripId: String, fingerprint: String): Boolean {
        var changed = false
        store.edit { prefs ->
            val link = decode(prefs[linkKey(tripId)])
            if (link?.autoUpdate == true && link.fingerprint != fingerprint && decodePending(prefs[pendingKey(tripId)])?.fingerprint != fingerprint) {
                prefs[pendingKey(tripId)] = json.encodeToString(PendingCalendarUpdate.serializer(), PendingCalendarUpdate(fingerprint, UUID.randomUUID().toString()))
                changed = true
            }
        }
        return changed
    }

    suspend fun progress(tripId: String, token: String, phase: String, problem: String? = null) {
        store.edit { prefs ->
            val old = decodePending(prefs[pendingKey(tripId)])
            if (old?.token == token) prefs[pendingKey(tripId)] = json.encodeToString(PendingCalendarUpdate.serializer(), old.copy(phase = phase, problem = problem))
        }
    }

    suspend fun complete(tripId: String, token: String) {
        store.edit { if (decodePending(it[pendingKey(tripId)])?.token == token) it.remove(pendingKey(tripId)) }
    }

    suspend fun completeFingerprint(tripId: String, fingerprint: String) {
        store.edit { if (decodePending(it[pendingKey(tripId)])?.fingerprint == fingerprint) it.remove(pendingKey(tripId)) }
    }

    /** The zone picked for the trip's times (linked or not), or null when never picked. */
    fun observeZone(tripId: String): Flow<String?> = store.data.map { it[zoneKey(tripId)] }.distinctUntilChanged()
    suspend fun zone(tripId: String): String? = store.data.first()[zoneKey(tripId)]
    suspend fun setZone(tripId: String, zone: String) {
        store.edit { it[zoneKey(tripId)] = zone }
    }
}
