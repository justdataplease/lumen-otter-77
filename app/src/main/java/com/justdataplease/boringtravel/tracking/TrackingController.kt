package com.justdataplease.boringtravel.tracking

import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.location.LocationServices
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.TrackingLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/** What the tracker is doing, for the settings screen and the trip map. */
data class TrackingStatus(
    val target: TrackingTarget = TrackingTarget.Off,
    val tripTitle: String = "",
    /** False when a level is chosen for today's trip but location isn't allowed. */
    val allowed: Boolean = true,
    /** Recording right now: the service runs (or, with background recording, the receiver is armed). */
    val recording: Boolean = false,
    /** The traveller stopped today's trip (Stop in the notification or the app) until tomorrow or Start recording. */
    val stopped: Boolean = false,
)

/**
 * Starts and stops recording ([TrackingPlan.decide]). The level comes from the trip (its own setting) or the app
 * setting, and applies only while a trip is on. While [TrackingPlan.BACKGROUND_RECORDING] is off every level records
 * through [RouteRecordingService] (a location foreground service with its notification), started only while the app is
 * on screen: when it is opened during a trip, or from Start recording. Nothing starts at boot, from an alarm or from the
 * nightly job; when the service stops without the traveller asking, a note says to open the app. With background
 * recording on, Low and Balanced go to fused updates to [LocationUpdatesReceiver] (no service) as before.
 * Re-evaluated on app start and open, when the level or a trip changes, after a reboot or update ([BootReceiver]) and
 * every night ([TrackProcessingWorker]).
 */
class TrackingController(private val graph: AppGraph) {
    private val app = graph.app
    private val state = TrackingState(app)
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(app) }
    private val mutex = Mutex()
    private val _status = MutableStateFlow(TrackingStatus())
    val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    /** Spike filter → stops → countries for a trip's points. */
    val processor by lazy { TrackProcessor(graph) }

    private var started = false
    private var forcedInProcess = false

    /** Called once from the Application (main thread). */
    fun start() {
        if (started) return
        started = true
        TrackingNotifications.ensureChannels(app)
        // A protected system broadcast (only Android sends it); exported so older versions deliver it without the
        // permission ContextCompat adds for not-exported receivers.
        ContextCompat.registerReceiver(app, LocationWatch.receiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
        // Level or trips changed (a trip's dates or its own level): apply again.
        graph.scope.launch {
            combine(graph.settings.trackingLevel, graph.trips.observeOwnTrips()) { level, trips ->
                level to trips.map { Triple(it.id, it.startDate to it.endDate, it.trackingLevel to it.deletedAt) }
            }.distinctUntilChanged().collect { quietly { apply() } }
        }
        // The app comes on screen: the recording service may start now (the only time it can without "Allow all the
        // time"), and the receiver is armed again once per process (a force stop clears requests without telling us).
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    val force = !forcedInProcess
                    forcedInProcess = true
                    refresh(force)
                }
            },
        )
        TrackProcessingWorker.schedule(app)
    }

    fun refresh(force: Boolean = true) {
        graph.scope.launch { quietly { apply(force) } }
    }

    /** Brings the requests, alarm and service in line with today's trip and level. */
    suspend fun apply(force: Boolean = false) = mutex.withLock {
        val today = LocalDate.now()
        // The traveller's own trip, in demo mode too: the demo is never recorded.
        val trip = graph.trips.ownCurrentOn(today)
        val appLevel = graph.settings.trackingLevel.first()
        val target = TrackingPlan.target(trip, appLevel, today)
        val title = trip?.title.orEmpty()
        val allowed = TrackingPermissions.hasLocation(app)
        val stopped = TrackingPlan.stoppedToday(state.stoppedTrip, state.stoppedDay, target.tripId, today)
        val decision = TrackingPlan.decide(
            target, allowed, stopped, onScreen = onScreen(), serviceRunning = RouteRecordingService.running,
            hasBackground = TrackingPlan.BACKGROUND_RECORDING && TrackingPermissions.hasBackground(app),
        )
        when (decision) {
            Recording.Off, Recording.NoPermission, Recording.Stopped -> {
                if (state.mode != TrackingState.MODE_IDLE || RouteRecordingService.running || force) stopAll()
                state.clear()
                TrackingNotifications.clearPaused(app)
            }
            Recording.Receiver -> {
                val bootedSince = state.armedAt < System.currentTimeMillis() - SystemClock.elapsedRealtime()
                val changed = target != state.target || state.mode != TrackingState.MODE_RECEIVER
                if (force || changed || bootedSince) armReceiver(target, title)
            }
            Recording.StartService -> {
                // Save first: the service reads the state as it starts.
                state.save(target, title, TrackingState.MODE_SERVICE, System.currentTimeMillis())
                if (startService()) TrackingNotifications.clearPaused(app) else state.setMode(TrackingState.MODE_WAITING)
            }
            Recording.KeepService -> {
                state.save(target, title, TrackingState.MODE_SERVICE, state.armedAt)
                RouteRecordingService.current?.update()
                TrackingNotifications.clearPaused(app)
            }
            Recording.WaitForApp -> {
                // It was recording and stopped without the traveller asking (a restart, an update): say so, once.
                val interrupted = state.mode == TrackingState.MODE_SERVICE && state.tripId == target.tripId
                if (state.mode == TrackingState.MODE_RECEIVER) stopAll()
                state.save(target, title, TrackingState.MODE_WAITING, state.armedAt)
                if (interrupted) TrackingNotifications.showPaused(app, title)
            }
        }
        publish(target, title, allowed, stopped)
        TrackProcessingWorker.schedule(app)
        LocationWatch.check(app)
    }

    /** Start recording (the app's button): today's trip records again after a Stop, or starts now. */
    suspend fun startNow() {
        state.clearStopped()
        apply(force = true)
    }

    /** Stop (the notification's or the app's): nothing records for today's trip until tomorrow or Start recording. */
    suspend fun stopForToday() {
        val tripId = status.value.target.tripId ?: state.tripId
        if (tripId != null) state.setStopped(tripId, LocalDate.now())
        apply(force = true)
    }

    /** The service went foreground or stopped: what the screens show follows. */
    fun serviceChanged() {
        if (RouteRecordingService.running) {
            if (state.mode == TrackingState.MODE_WAITING) state.setMode(TrackingState.MODE_SERVICE)
            TrackingNotifications.clearPaused(app)
        }
        _status.update { it.copy(recording = recordingNow()) }
    }

    /** The service couldn't go foreground (Android refused a start from the background): wait for the app. */
    suspend fun serviceFailed() = mutex.withLock {
        val target = state.target
        if (target.level == TrackingLevel.Off) return@withLock
        if (TrackingPlan.BACKGROUND_RECORDING && TrackingPlan.mode(target.level) == RecordMode.Service) {
            // Detailed with background recording: the receiver records until the app is open again.
            request(target.level)
            state.setMode(TrackingState.MODE_RECEIVER)
        } else if (state.mode != TrackingState.MODE_WAITING) {
            state.setMode(TrackingState.MODE_WAITING)
            if (!onScreen()) TrackingNotifications.showPaused(app, state.tripTitle)
        }
        _status.update { it.copy(recording = recordingNow()) }
    }

    private fun publish(target: TrackingTarget, title: String, allowed: Boolean, stopped: Boolean) {
        _status.value = TrackingStatus(
            target, title, allowed = allowed || target.level == TrackingLevel.Off,
            recording = recordingNow(), stopped = stopped && target.level != TrackingLevel.Off,
        )
    }

    private fun recordingNow(): Boolean = RouteRecordingService.running || state.mode == TrackingState.MODE_RECEIVER

    /** With background recording: fused updates to the receiver, no service ([RecordMode.Receiver]). */
    private suspend fun armReceiver(target: TrackingTarget, title: String) {
        app.stopService(Intent(app, RouteRecordingService::class.java))
        request(target.level)
        if (target.level == TrackingLevel.Balanced) {
            state.resetBestFixStill()
            BestFixAlarm.arm(app)
        } else BestFixAlarm.cancel(app)
        state.save(target, title, TrackingState.MODE_RECEIVER, System.currentTimeMillis())
    }

    /** A trip's own level (null = follow the app setting). */
    suspend fun setTripLevel(tripId: String, level: TrackingLevel?) {
        val trip: Trip = graph.trips.get(tripId) ?: return
        if (trip.trackingLevel != level?.id) graph.trips.save(trip.copy(trackingLevel = level?.id))
    }

    /** Process the trips that may have new points: today's, and any that ended in the last three days. */
    suspend fun processRecent() {
        val today = LocalDate.now()
        graph.trips.observeOwnTrips().first()
            .filter { t -> t.startDate != null && t.endDate != null && !today.isBefore(t.startDate) && !today.isAfter(t.endDate!!.plusDays(3)) }
            .forEach { processor.process(it.id) }
    }

    /**
     * The nightly reminder: a trip records but location is off for the app (or, with background recording, Low or
     * Balanced are allowed only while it's open). At most once a day.
     */
    fun checkPermissionNote() {
        val today = LocalDate.now().toEpochDay()
        val s = status.value
        val level = s.target.level
        val receiver = TrackingPlan.mode(level) == RecordMode.Receiver
        val missing = level != TrackingLevel.Off && s.target.tripId != null &&
            (!TrackingPermissions.hasLocation(app) || (receiver && !TrackingPermissions.hasBackground(app)))
        if (!missing) {
            TrackingNotifications.clearPermissionNote(app)
            return
        }
        if (state.lastNoticeDay == today) return
        state.setLastNoticeDay(today)
        TrackingNotifications.showPermissionNote(app, s.tripTitle, noLocation = !TrackingPermissions.hasLocation(app))
    }

    /** The app is on screen: the only time the location service may start without "Allow all the time". */
    private fun onScreen(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun startService(): Boolean = runCatching {
        ContextCompat.startForegroundService(app, Intent(app, RouteRecordingService::class.java))
    }.isSuccess

    @SuppressLint("MissingPermission") // apply() checks hasLocation first
    private suspend fun request(level: TrackingLevel) {
        runCatching { fused.requestLocationUpdates(RouteRecordingService.requestFor(level), LocationUpdatesReceiver.pendingIntent(app)).await() }
    }

    private suspend fun stopAll() {
        runCatching { fused.removeLocationUpdates(LocationUpdatesReceiver.pendingIntent(app)).await() }
        BestFixAlarm.cancel(app)
        app.stopService(Intent(app, RouteRecordingService::class.java))
    }
}
