package com.justdataplease.boringtravel.tracking

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.AlarmManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.justdataplease.boringtravel.geo.Geo
import com.justdataplease.boringtravel.graph
import com.justdataplease.boringtravel.model.TrackingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Balanced's "best fix", armed while Balanced records ([RouteRecordingService], or the receiver with background
 * recording): about every 20 minutes (inexact, allowed while idle, so no exact-alarm permission) it asks
 * for one current position, keeps it if it's accurate enough, and arms itself again. Skipped when a point arrived in
 * the last 10 minutes; backs off to 40 and 60 minutes while the fixes stay at one spot. Stops re-arming once Balanced
 * is no longer recording.
 */
class BestFixAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val state = TrackingState(context)
        // Only while Balanced records (the service in this process, or the receiver); a cold process stops here.
        if (state.level != TrackingLevel.Balanced || state.tripId == null || !state.active) return
        val pending = goAsync()
        context.graph.scope.launch(Dispatchers.IO) {
            try {
                quietly {
                    if (System.currentTimeMillis() - state.lastPointAt <= FRESH_MS) {
                        state.resetBestFixStill() // points are coming in: on the move
                    } else if (TrackingPermissions.hasLocation(context)) {
                        lookup(context)?.let { fix ->
                            TrackWriter.write(context, listOf(fix), state)
                            val same = state.bestFix?.let { (lat, lng) ->
                                Geo.distanceM(lat, lng, fix.latitude, fix.longitude) <= TrackingPlan.BEST_FIX_SAME_SPOT_M
                            } == true
                            state.setBestFix(fix.latitude, fix.longitude, if (same) state.bestFixStill + 1 else 0)
                        }
                    }
                    LocationWatch.check(context)
                }
            } finally {
                arm(context, TrackingPlan.bestFixDelayMs(state.bestFixStill))
                pending.finish()
            }
        }
    }

    @SuppressLint("MissingPermission") // checked by the caller
    private suspend fun lookup(context: Context): android.location.Location? {
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(LOOKUP_MS)
            .setMaxUpdateAgeMillis(2 * 60_000L)
            .build()
        val cancel = CancellationTokenSource()
        val fix = withTimeoutOrNull(LOOKUP_MS + 500) {
            runCatching { LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, cancel.token).await() }.getOrNull()
        }
        if (fix == null) cancel.cancel()
        return fix?.takeIf { !it.hasAccuracy() || it.accuracy <= MAX_ACCURACY_M }
    }

    companion object {
        const val ACTION = "com.justdataplease.boringtravel.tracking.BEST_FIX"
        private const val FRESH_MS = 10 * 60_000L
        /** Kept short: a broadcast has about 10 s. */
        private const val LOOKUP_MS = 8_000L
        private const val MAX_ACCURACY_M = 200f

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1, Intent(context, BestFixAlarm::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun arm(context: Context, delayMs: Long = TrackingPlan.BEST_FIX_MS) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            AlarmManagerCompat.setAndAllowWhileIdle(
                am, AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pendingIntent(context),
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }
    }
}
