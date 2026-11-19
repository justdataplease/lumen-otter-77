package com.justdataplease.boringtravel.tracking

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.justdataplease.boringtravel.graph
import com.justdataplease.boringtravel.model.TrackingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Route recording: a foreground service (type location) with a quiet notification and a Stop button. While
 * [TrackingPlan.BACKGROUND_RECORDING] is off it records every level; [TrackingController] starts it only while the app
 * is on screen (opened during a trip, or Start recording), and it goes on after the traveller leaves the app until Stop,
 * the trip's last day or a level of Off. Low and Balanced: balanced-power requests at the level's interval (Balanced
 * also arms [BestFixAlarm]). Detailed: GPS at its interval; when the phone stays put for 10 minutes it rests on a slow,
 * low-power request and wakes on the significant-motion sensor (or on the first fix that shows movement, where the phone
 * has no such sensor). The level and trip come from [TrackingState]; [update] applies a change.
 */
class RouteRecordingService : Service() {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val stillness = Stillness()
    /** The level the location requests are made for; null before the first and after they're removed. Main thread. */
    private var level: TrackingLevel? = null
    private var resting = false
    private var sensors: SensorManager? = null
    private var motion: Sensor? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) = onFixes(result.locations)
    }

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            handler.post { if (resting) wake() }
        }
    }

    private val check = object : Runnable {
        override fun run() {
            if (level == TrackingLevel.Detailed && !resting && stillness.isStill(System.currentTimeMillis())) rest()
            handler.postDelayed(this, CHECK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's Stop: nothing records for this trip until tomorrow or Start recording.
            graph.scope.launch(Dispatchers.IO) { quietly { graph.tracking.stopForToday() } }
            return START_NOT_STICKY
        }
        val state = TrackingState(this)
        // Started with startForegroundService: go foreground first, whatever happens next.
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this, TrackingNotifications.ID_RECORDING, TrackingNotifications.recording(this, state.tripTitle, state.level),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        }.isSuccess
        if (!foreground || !TrackingPermissions.hasLocation(this)) {
            // Android refused: a restart from the background (after Android ended the process) without "Allow all the
            // time". The controller waits for the app to be opened.
            graph.scope.launch { quietly { graph.tracking.serviceFailed() } }
            stopSelf()
            return START_NOT_STICKY
        }
        if (state.tripId == null || TrackingPlan.mode(state.level) != RecordMode.Service || state.isPastLastDay()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (current == null) {
            current = this
            // Recording here now: a receiver's requests (background recording, or an older version) go.
            runCatching { fused.removeLocationUpdates(LocationUpdatesReceiver.pendingIntent(this)) }
            sensors = getSystemService(SensorManager::class.java)
            motion = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
            handler.postDelayed(check, CHECK_MS)
        }
        configure()
        graph.tracking.serviceChanged()
        return START_STICKY
    }

    /** The level or trip in [TrackingState] changed: apply it (requests and notification), on the main thread. */
    fun update() {
        handler.post { if (current === this) configure() }
    }

    /** Main thread: the requests for the state's level, and the notification saying what records. */
    private fun configure() {
        val state = TrackingState(this)
        val wanted = state.level
        TrackingNotifications.updateRecording(this, state.tripTitle, wanted)
        if (wanted == level) return
        removeRequests()
        level = wanted
        when (wanted) {
            TrackingLevel.Detailed -> {
                stillness.reset(System.currentTimeMillis())
                request(TrackingLevel.Detailed)
            }
            TrackingLevel.Low, TrackingLevel.Balanced -> request(wanted)
            TrackingLevel.Off -> level = null
        }
        if (wanted == TrackingLevel.Balanced) {
            state.resetBestFixStill()
            BestFixAlarm.arm(this)
        } else {
            BestFixAlarm.cancel(this)
        }
    }

    private fun onFixes(locations: List<Location>) {
        if (locations.isEmpty()) return
        graph.scope.launch(Dispatchers.IO) { quietly { TrackWriter.write(this@RouteRecordingService, locations) } }
        if (level != TrackingLevel.Detailed) return
        var moved = false
        for (l in locations) moved = stillness.onFix(l.latitude, l.longitude, l.time, if (l.hasAccuracy()) l.accuracy else null) || moved
        if (resting && moved) wake()
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun request(level: TrackingLevel) {
        runCatching { fused.requestLocationUpdates(requestFor(level), callback, Looper.getMainLooper()) }
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun requestSlow() {
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, REST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(REST_DISTANCE_M)
            .setWaitForAccurateLocation(false)
            .build()
        runCatching { fused.requestLocationUpdates(request, callback, Looper.getMainLooper()) }
    }

    private fun removeRequests() {
        if (level != null) runCatching { fused.removeLocationUpdates(callback) }
        if (resting) motion?.let { runCatching { sensors?.cancelTriggerSensor(motionListener, it) } }
        resting = false
    }

    /** Detailed, the phone still for a while: a slow request, and the motion sensor to wake. */
    private fun rest() {
        resting = true
        runCatching { fused.removeLocationUpdates(callback) }
        requestSlow()
        motion?.let { runCatching { sensors?.requestTriggerSensor(motionListener, it) } }
    }

    private fun wake() {
        resting = false
        motion?.let { runCatching { sensors?.cancelTriggerSensor(motionListener, it) } }
        runCatching { fused.removeLocationUpdates(callback) }
        stillness.reset(System.currentTimeMillis())
        request(TrackingLevel.Detailed)
    }

    override fun onDestroy() {
        if (current === this) current = null
        handler.removeCallbacksAndMessages(null)
        removeRequests()
        level = null
        // Balanced's alarm was this service's, unless the receiver records now (background recording).
        if (TrackingState(this).mode != TrackingState.MODE_RECEIVER) BestFixAlarm.cancel(this)
        quietly { graph.tracking.serviceChanged() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.justdataplease.boringtravel.tracking.STOP"
        private const val CHECK_MS = 2 * 60_000L
        private const val REST_INTERVAL_MS = 5 * 60_000L
        private const val REST_DISTANCE_M = 50f

        /** The service recording in this process, if any. */
        @Volatile var current: RouteRecordingService? = null
            private set

        /** True while the service records in this process. */
        val running: Boolean get() = current != null

        /** The fused request for [level]: high accuracy for Detailed, balanced power for Low and Balanced. */
        fun requestFor(level: TrackingLevel): LocationRequest {
            val priority = if (level == TrackingLevel.Detailed) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            return LocationRequest.Builder(priority, level.intervalMs)
                .setMinUpdateIntervalMillis(level.intervalMs / 2)
                .setMinUpdateDistanceMeters(level.minDistanceM)
                .setMaxUpdateDelayMillis(level.maxDelayMs)
                .setWaitForAccurateLocation(false)
                .build()
        }
    }
}
