package com.justdataplease.boringtravel.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.justdataplease.boringtravel.R
import com.justdataplease.boringtravel.art.PlaneMark
import com.justdataplease.boringtravel.model.TrackingLevel

/**
 * The tracker's notifications: the recording service's quiet ongoing one (with Stop), and gentle notes when location
 * stops working or the recording stopped without the traveller asking.
 */
object TrackingNotifications {
    private const val CHANNEL_RECORDING = "route_recording"
    private const val CHANNEL_NOTES = "route_notes"
    const val ID_RECORDING = 3101
    private const val ID_LOCATION_OFF = 3102
    private const val ID_PERMISSION = 3103
    private const val ID_PAUSED = 3104

    fun ensureChannels(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_RECORDING, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Route recording")
                    .setDescription("Shown while BoringTravel records your route.")
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_NOTES, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Route notes")
                    .setDescription("When your route stops recording during a trip.")
                    .setShowBadge(false)
                    .build(),
            ),
        )
    }

    private fun openApp(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun base(context: Context, channel: String) = NotificationCompat.Builder(context, channel)
        // The paper plane as a one-colour silhouette, accented in Postcard Pop's coral (a yellow would not read on the light shade).
        .setSmallIcon(R.drawable.ic_brand_plane_mono)
        .setColor(PlaneMark.POP_ORANGE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setSilent(true)

    /** The recording service's notification: what records at which level, and Stop (until tomorrow). */
    fun recording(context: Context, tripTitle: String, level: TrackingLevel): Notification {
        val stop = PendingIntent.getService(
            context, 2, Intent(context, RouteRecordingService::class.java).setAction(RouteRecordingService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return base(context, CHANNEL_RECORDING)
            .setContentTitle(recordingTitle(level))
            .setContentText(tripTitle.ifBlank { null })
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .addAction(0, "Stop", stop)
            .build()
    }

    /** "Recording your route · Balanced". */
    fun recordingTitle(level: TrackingLevel): String = "Recording your route · ${level.label}"

    /** The running service's notification again, after its level or trip changed (or notifications were allowed). */
    fun updateRecording(context: Context, tripTitle: String, level: TrackingLevel) =
        post(context, ID_RECORDING, recording(context, tripTitle, level))

    @SuppressLint("MissingPermission") // checked by canNotify()
    private fun post(context: Context, id: Int, notification: Notification) {
        if (canNotify(context)) NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun canNotify(context: Context) =
        TrackingPermissions.hasNotifications(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun showLocationOff(context: Context, tripTitle: String) {
        val open = PendingIntent.getActivity(context, 3, TrackingPermissions.locationSettingsIntent(), PendingIntent.FLAG_IMMUTABLE)
        val text = listOfNotNull(tripTitle.ifBlank { null }, "The route pauses until you turn it on.").joinToString(" · ")
        post(
            context, ID_LOCATION_OFF,
            base(context, CHANNEL_NOTES)
                .setContentTitle("Location is off")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clearLocationOff(context: Context) = NotificationManagerCompat.from(context).cancel(ID_LOCATION_OFF)

    /**
     * The route stopped recording without the traveller asking (the phone restarted, the app was updated, or Android
     * ended it): opening the app starts it again. Never starts anything itself.
     */
    fun showPaused(context: Context, tripTitle: String) {
        val text = listOfNotNull(tripTitle.ifBlank { null }, "Open BoringTravel to go on.").joinToString(" · ")
        post(
            context, ID_PAUSED,
            base(context, CHANNEL_NOTES)
                .setContentTitle("Your route isn't recording")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clearPaused(context: Context) = NotificationManagerCompat.from(context).cancel(ID_PAUSED)

    /** Location isn't allowed (or, with background recording, only while the app is open) during a trip that records. */
    fun showPermissionNote(context: Context, tripTitle: String, noLocation: Boolean) {
        val open = PendingIntent.getActivity(context, 4, TrackingPermissions.appSettingsIntent(context), PendingIntent.FLAG_IMMUTABLE)
        val text = if (noLocation) "Location is not allowed for BoringTravel, so the route isn't kept."
        else "The route is kept only while the app is open. Choose “Allow all the time” to keep it in the background."
        post(
            context, ID_PERMISSION,
            base(context, CHANNEL_NOTES)
                .setContentTitle(tripTitle.ifBlank { "Route tracking" })
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clearPermissionNote(context: Context) = NotificationManagerCompat.from(context).cancel(ID_PERMISSION)
}
