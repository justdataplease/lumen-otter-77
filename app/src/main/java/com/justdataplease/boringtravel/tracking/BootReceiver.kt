package com.justdataplease.boringtravel.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.justdataplease.boringtravel.graph
import kotlinx.coroutines.launch

/**
 * Location requests, alarms and the recording service don't survive a reboot or an app update. Without background
 * recording nothing starts here: if the route was recording, a note asks to open the app ([TrackingController.apply],
 * [Recording.WaitForApp]). With it, the receiver's requests are armed again. The day check is a WorkManager job and
 * comes back by itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        context.graph.scope.launch {
            try {
                quietly { context.graph.tracking.apply(force = true) }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Location switched off during a trip: a gentle note, cleared when it's back. Called when providers change (while the
 * app runs), when the fused provider reports no location, from the best-fix alarm and from the day check.
 */
object LocationWatch {
    fun check(context: Context) {
        val state = TrackingState(context)
        if (state.active && !TrackingPermissions.locationEnabled(context)) TrackingNotifications.showLocationOff(context, state.tripTitle)
        else TrackingNotifications.clearLocationOff(context)
    }

    /** Registered by [TrackingController] while the app process runs. */
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LocationManager.PROVIDERS_CHANGED_ACTION) return
            check(context)
            // Back on: make sure the requests are in place.
            if (TrackingPermissions.locationEnabled(context)) context.graph.tracking.refresh(force = false)
        }
    }
}
