package com.justdataplease.boringtravel.tracking

import android.content.Context
import androidx.core.content.edit
import com.justdataplease.boringtravel.model.TrackingLevel
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/**
 * Runs [block] and swallows failures (a full disk, Play services missing): work in receivers, the service and
 * background jobs must never crash the app. Cancellation still propagates.
 */
internal inline fun <T> quietly(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/**
 * What is recording now, kept in plain SharedPreferences so the location receiver, the alarm and the service can read
 * it synchronously in a cold process (DataStore and Room are for the app's data; this is the tracker's own note).
 */
class TrackingState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tracking_state", Context.MODE_PRIVATE)

    val tripId: String? get() = prefs.getString(K_TRIP, null)
    val tripTitle: String get() = prefs.getString(K_TITLE, null).orEmpty()
    val level: TrackingLevel get() = prefs.getString(K_LEVEL, null)?.let { TrackingLevel.of(it) } ?: TrackingLevel.Off
    val lastDay: LocalDate? get() = prefs.getLong(K_LAST_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
    /**
     * [MODE_IDLE], [MODE_RECEIVER] (fused updates to a PendingIntent), [MODE_SERVICE] (the recording service) or
     * [MODE_WAITING] (the service stopped without the traveller asking, and waits for the app to be opened).
     */
    val mode: String get() = prefs.getString(K_MODE, MODE_IDLE) ?: MODE_IDLE
    /** Wall-clock time the location requests were last made (they don't survive a reboot or an app update). */
    val armedAt: Long get() = prefs.getLong(K_ARMED, 0L)
    val lastPointAt: Long get() = prefs.getLong(K_LAST_POINT, 0L)
    /** Epoch day of the last "background location is off" notice, so it shows at most once a day. */
    val lastNoticeDay: Long get() = prefs.getLong(K_NOTICE_DAY, Long.MIN_VALUE)

    /** The trip the traveller stopped (Stop in the notification or the app), and the epoch day they did. */
    val stoppedTrip: String? get() = prefs.getString(K_STOPPED_TRIP, null)
    val stoppedDay: Long? get() = prefs.getLong(K_STOPPED_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

    fun setStopped(tripId: String, day: LocalDate) = prefs.edit {
        putString(K_STOPPED_TRIP, tripId); putLong(K_STOPPED_DAY, day.toEpochDay())
    }

    fun clearStopped() = prefs.edit { remove(K_STOPPED_TRIP); remove(K_STOPPED_DAY) }

    /** The last best fix (Balanced's alarm) and how many in a row landed at the same spot. */
    val bestFix: Pair<Double, Double>? get() =
        if (prefs.contains(K_FIX_LAT)) Double.fromBits(prefs.getLong(K_FIX_LAT, 0)) to Double.fromBits(prefs.getLong(K_FIX_LNG, 0)) else null
    val bestFixStill: Int get() = prefs.getInt(K_FIX_STILL, 0)

    fun setBestFix(lat: Double, lng: Double, still: Int) = prefs.edit {
        putLong(K_FIX_LAT, lat.toRawBits()); putLong(K_FIX_LNG, lng.toRawBits()); putInt(K_FIX_STILL, still)
    }

    fun resetBestFixStill() {
        if (bestFixStill != 0) prefs.edit { putInt(K_FIX_STILL, 0) }
    }

    val target: TrackingTarget
        get() = if (tripId == null || level == TrackingLevel.Off) TrackingTarget.Off else TrackingTarget(tripId, level, lastDay)

    /**
     * Recording now: the receiver is armed, or the recording service runs in this process (a cold process after a
     * restart has none), for a trip that hasn't ended.
     */
    val active: Boolean
        get() = target.level != TrackingLevel.Off && !isPastLastDay() &&
            (mode == MODE_RECEIVER || (mode == MODE_SERVICE && RouteRecordingService.running))

    /** Recording for a trip that has ended (the day check may run late; the receiver checks this too). */
    fun isPastLastDay(today: LocalDate = LocalDate.now()): Boolean = lastDay?.let { today.isAfter(it) } ?: false

    fun save(target: TrackingTarget, title: String, mode: String, armedAt: Long) = prefs.edit {
        putString(K_TRIP, target.tripId)
        putString(K_TITLE, title)
        putString(K_LEVEL, target.level.id)
        putLong(K_LAST_DAY, target.lastDay?.toEpochDay() ?: Long.MIN_VALUE)
        putString(K_MODE, mode)
        putLong(K_ARMED, armedAt)
    }

    fun setMode(mode: String) = prefs.edit { putString(K_MODE, mode) }

    fun setLastPointAt(at: Long) {
        if (at > lastPointAt) prefs.edit { putLong(K_LAST_POINT, at) }
    }

    fun setLastNoticeDay(day: Long) = prefs.edit { putLong(K_NOTICE_DAY, day) }

    fun clear() = prefs.edit {
        remove(K_TRIP); remove(K_TITLE); remove(K_LEVEL); remove(K_LAST_DAY); remove(K_ARMED)
        putString(K_MODE, MODE_IDLE)
    }

    companion object {
        const val MODE_IDLE = "idle"
        const val MODE_RECEIVER = "receiver"
        const val MODE_SERVICE = "service"
        const val MODE_WAITING = "waiting"

        private const val K_TRIP = "trip"
        private const val K_TITLE = "title"
        private const val K_LEVEL = "level"
        private const val K_LAST_DAY = "last_day"
        private const val K_MODE = "mode"
        private const val K_ARMED = "armed_at"
        private const val K_LAST_POINT = "last_point_at"
        private const val K_NOTICE_DAY = "notice_day"
        private const val K_FIX_LAT = "best_fix_lat"
        private const val K_FIX_LNG = "best_fix_lng"
        private const val K_FIX_STILL = "best_fix_still"
        private const val K_STOPPED_TRIP = "stopped_trip"
        private const val K_STOPPED_DAY = "stopped_day"
    }
}
