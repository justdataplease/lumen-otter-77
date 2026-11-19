package com.justdataplease.boringtravel.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.justdataplease.boringtravel.model.TrackingLevel

/** What each level needs from Android, and the settings pages to fix it. */
object TrackingPermissions {
    /** One thing to ask for, in the order the permission flow asks. */
    enum class Step { Precise, Background, Notifications }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Any location at all (approximate is enough for countries; the route wants precise). */
    fun hasLocation(context: Context) =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPrecise(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /**
     * "Allow all the time" (minSdk 29, so the permission always exists). Asked only while
     * [TrackingPlan.BACKGROUND_RECORDING] is on; the manifest removes the permission while it is off.
     */
    fun hasBackground(context: Context) = hasLocation(context) && granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasNotifications(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun locationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /**
     * What [level] still needs ([TrackingPlan.missing]): precise location; notifications for the recording service's
     * notification; "Allow all the time" only while [TrackingPlan.BACKGROUND_RECORDING] is on.
     */
    fun missing(context: Context, level: TrackingLevel): List<Step> = TrackingPlan.missing(
        level,
        hasPrecise = hasPrecise(context),
        hasLocation = hasLocation(context),
        hasBackground = TrackingPlan.BACKGROUND_RECORDING && hasBackground(context),
        hasNotifications = hasNotifications(context),
    )

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun locationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The list of apps and their battery optimisation (no special permission needed to open it). */
    fun batterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
