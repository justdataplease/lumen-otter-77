package com.justdataplease.boringtravel.tracking

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Only with background recording ([TrackingPlan.BACKGROUND_RECORDING]): Low and Balanced (and Detailed when its service
 * can't start) get batches of fixes here through a PendingIntent, waking the app only for that. No service, no
 * notification. Without it nothing asks for these; requests left by an older version are removed at the first batch.
 */
class LocationUpdatesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val result = if (LocationResult.hasResult(intent)) LocationResult.extractResult(intent) else null
        val available = if (LocationAvailability.hasLocationAvailability(intent)) LocationAvailability.extractLocationAvailability(intent)?.isLocationAvailable else null
        val state = TrackingState(context)
        val graph = context.graph
        val pending = goAsync()
        graph.scope.launch(Dispatchers.IO) {
            try {
                quietly {
                    if (state.mode != TrackingState.MODE_RECEIVER) {
                        // Not recording through the receiver (the service does, or nothing): drop these requests.
                        LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(pendingIntent(context))
                    } else if (state.tripId == null || state.isPastLastDay()) {
                        // The trip ended (or tracking was switched off) and the day check hasn't run yet.
                        graph.tracking.apply()
                    } else if (result != null) {
                        TrackWriter.write(context, result.locations, state)
                    }
                    if (available == false) LocationWatch.check(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.justdataplease.boringtravel.tracking.LOCATIONS"

        /** Mutable: the fused provider adds the fixes as extras. Explicit, so only this receiver gets it. */
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, LocationUpdatesReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}

/** Turns fixes into [TrackPoint] rows for the trip that's recording. */
object TrackWriter {
    suspend fun write(context: Context, locations: List<Location>, state: TrackingState = TrackingState(context)): Int {
        val tripId = state.tripId ?: return 0
        val level = state.level.id
        val points = locations
            .filter { !it.latitude.isNaN() && !it.longitude.isNaN() && !(it.latitude == 0.0 && it.longitude == 0.0) }
            .map {
                TrackPoint(
                    tripId = tripId,
                    time = it.time,
                    lat = it.latitude,
                    lng = it.longitude,
                    accuracyM = if (it.hasAccuracy()) it.accuracy else null,
                    speedMps = if (it.hasSpeed()) it.speed else null,
                    altitudeM = if (it.hasAltitude()) it.altitude else null,
                    level = level,
                )
            }
        if (points.isEmpty()) return 0
        context.graph.tracks.add(points)
        state.setLastPointAt(points.maxOf { it.time })
        return points.size
    }
}
