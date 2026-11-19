package com.justdataplease.boringtravel.tracking

import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.geo.Geo
import com.justdataplease.boringtravel.model.TrackingLevel
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.max

/** What should record right now: [level] for [tripId] until the end of [lastDay]. Off when no trip is on. */
data class TrackingTarget(val tripId: String?, val level: TrackingLevel, val lastDay: LocalDate?) {
    companion object {
        val Off = TrackingTarget(null, TrackingLevel.Off, null)
    }
}

/** How a level records: not at all, through the location foreground service, or fused updates to a receiver. */
enum class RecordMode { None, Service, Receiver }

/** What [TrackingController] does about recording right now. */
enum class Recording {
    /** No trip on, or its level is Off: nothing records. */
    Off,
    /** A level is set but location isn't allowed. */
    NoPermission,
    /** The traveller stopped it for today (Stop in the notification or in the app). */
    Stopped,
    /** Fused updates to [LocationUpdatesReceiver], no notification (only with [TrackingPlan.BACKGROUND_RECORDING]). */
    Receiver,
    /** Start the foreground service now (the app is on screen). */
    StartService,
    /** The service is already recording: give it the level and trip. */
    KeepService,
    /** The service isn't running and Android won't let it start from the background: wait until the app is opened. */
    WaitForApp,
}

/** The pure decisions behind [TrackingController]. */
object TrackingPlan {
    /**
     * The one switch for recording with the app closed and no notification ("Allow all the time"). Off for the first
     * Play version: ACCESS_BACKGROUND_LOCATION is removed in the manifest (TrackingPlanTest checks the two agree), every
     * level records only through the location foreground service with its notification, and that service starts only
     * while the app is on screen (opened during a trip, or Start recording). On again: Low and Balanced go back to the
     * receiver, Me › Route tracking asks for "Allow all the time" with Play's disclosure, and the manifest line goes.
     */
    const val BACKGROUND_RECORDING = false

    /** How [level] records. Without [background], every level that records uses the foreground service. */
    fun mode(level: TrackingLevel, background: Boolean = BACKGROUND_RECORDING): RecordMode = when {
        level == TrackingLevel.Off -> RecordMode.None
        level.usesForegroundService || !background -> RecordMode.Service
        else -> RecordMode.Receiver
    }

    /** True when [level] shows the recording notification (and so asks for the notification permission). */
    fun showsNotification(level: TrackingLevel, background: Boolean = BACKGROUND_RECORDING): Boolean =
        mode(level, background) == RecordMode.Service

    /**
     * What to do for [target]: [hasLocation] = some location allowed; [stopped] = the traveller stopped this trip
     * today; [onScreen] = the app is visible (the only time a location service may start without "Allow all the
     * time"); [serviceRunning] = the service records in this process; [hasBackground] = "Allow all the time".
     */
    fun decide(
        target: TrackingTarget,
        hasLocation: Boolean,
        stopped: Boolean,
        onScreen: Boolean,
        serviceRunning: Boolean,
        hasBackground: Boolean = false,
        background: Boolean = BACKGROUND_RECORDING,
    ): Recording = when {
        target.level == TrackingLevel.Off || target.tripId == null -> Recording.Off
        !hasLocation -> Recording.NoPermission
        stopped -> Recording.Stopped
        mode(target.level, background) == RecordMode.Receiver -> Recording.Receiver
        serviceRunning -> Recording.KeepService
        onScreen || (background && hasBackground) -> Recording.StartService
        // With background recording on, Detailed records through the receiver until the app is open again.
        background -> Recording.Receiver
        else -> Recording.WaitForApp
    }

    /** True when the traveller stopped [tripId] on [today] ([stoppedTrip] on epoch day [stoppedDay]); a new day starts again. */
    fun stoppedToday(stoppedTrip: String?, stoppedDay: Long?, tripId: String?, today: LocalDate): Boolean =
        tripId != null && stoppedTrip == tripId && stoppedDay == today.toEpochDay()

    /**
     * What [level] still needs from Android, in the order the permission flow asks: precise location for every level;
     * "Allow all the time" only for a level that records through the receiver (asked once some location is allowed:
     * Android won't grant it before); notifications for a level that records through the service, which shows one.
     */
    fun missing(
        level: TrackingLevel,
        hasPrecise: Boolean,
        hasLocation: Boolean,
        hasBackground: Boolean,
        hasNotifications: Boolean,
        background: Boolean = BACKGROUND_RECORDING,
    ): List<TrackingPermissions.Step> = buildList {
        val mode = mode(level, background)
        if (mode == RecordMode.None) return@buildList
        if (!hasPrecise) add(TrackingPermissions.Step.Precise)
        if (mode == RecordMode.Receiver && hasLocation && !hasBackground) add(TrackingPermissions.Step.Background)
        if (mode == RecordMode.Service && !hasNotifications) add(TrackingPermissions.Step.Notifications)
    }

    /** The trip's own level when it has one, else the app setting. */
    fun levelFor(trip: Trip, appLevel: TrackingLevel): TrackingLevel = trip.trackingLevel?.let { TrackingLevel.of(it) } ?: appLevel

    /** Records only while a trip is on (today within its dates). */
    fun target(current: Trip?, appLevel: TrackingLevel, today: LocalDate): TrackingTarget {
        if (current == null || current.deletedAt != null || !current.isCurrent(today)) return TrackingTarget.Off
        val level = levelFor(current, appLevel)
        return if (level == TrackingLevel.Off) TrackingTarget.Off else TrackingTarget(current.id, level, current.endDate)
    }

    /**
     * Balanced's best-fix alarm: every 20 minutes, backing off to 40 and 60 while the fixes keep landing at the same
     * spot (a night in the hotel shouldn't wake GPS three times an hour). [stillCount] = best fixes in a row at one spot.
     */
    fun bestFixDelayMs(stillCount: Int): Long = BEST_FIX_MS * (1 + stillCount.coerceIn(0, 2))

    const val BEST_FIX_MS = 20 * 60_000L

    /** Two best fixes this close count as "still at the same spot". */
    const val BEST_FIX_SAME_SPOT_M = 150.0

    /** Milliseconds from [now] until [after] past the next local midnight, when the day check runs. */
    fun msUntilNextDay(now: ZonedDateTime, after: Duration = Duration.ofMinutes(5)): Long {
        val next = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plus(after)
        return max(60_000L, Duration.between(now, next).toMillis())
    }
}

/**
 * Detailed mode rests when the phone stays put: no fix more than [radiusM] (or 1.5× its accuracy) from the anchor
 * for [stillAfterMs]. The service then swaps GPS for a slow request and waits for motion.
 */
class Stillness(private val radiusM: Double = 60.0, private val stillAfterMs: Long = 10 * 60_000L) {
    private var anchorLat = Double.NaN
    private var anchorLng = Double.NaN
    var lastMoveAt: Long = 0L
        private set

    fun reset(now: Long) {
        anchorLat = Double.NaN
        anchorLng = Double.NaN
        lastMoveAt = now
    }

    /** Feeds one fix; true when it shows movement (or is the first one). */
    fun onFix(lat: Double, lng: Double, time: Long, accuracyM: Float?): Boolean {
        val moved = anchorLat.isNaN() ||
            Geo.distanceM(anchorLat, anchorLng, lat, lng) > max(radiusM, (accuracyM ?: 0f) * 1.5)
        if (moved) {
            anchorLat = lat
            anchorLng = lng
            lastMoveAt = max(lastMoveAt, time)
        }
        return moved
    }

    fun isStill(now: Long): Boolean = now - lastMoveAt >= stillAfterMs
}
