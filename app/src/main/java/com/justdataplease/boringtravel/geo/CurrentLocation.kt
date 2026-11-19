package com.justdataplease.boringtravel.geo

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.justdataplease.boringtravel.tracking.TrackingPermissions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the phone is now: for a photo just taken (a quick, low-power look), or because the traveller tapped "Use my
 * location" ([get] with asked: GPS too, and a longer wait). Only when location access was already given: it never asks.
 */
object CurrentLocation {
    private const val LOOKUP_MS = 5_000L
    private const val ASKED_LOOKUP_MS = 10_000L
    private const val MAX_AGE_MS = 2 * 60_000L
    private const val LAST_FIX_MS = 10 * 60_000L
    private const val MAX_ACCURACY_M = 500f

    @SuppressLint("MissingPermission") // checked first
    suspend fun get(context: Context, asked: Boolean = false): Pair<Double, Double>? {
        if (!TrackingPermissions.hasLocation(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val wait = if (asked) ASKED_LOOKUP_MS else LOOKUP_MS
        val request = CurrentLocationRequest.Builder()
            .setPriority(if (asked) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setDurationMillis(wait)
            .setMaxUpdateAgeMillis(MAX_AGE_MS)
            .build()
        val cancel = CancellationTokenSource()
        val fix = withTimeoutOrNull(wait + 500) {
            runCatching { client.getCurrentLocation(request, cancel.token).await() }.getOrNull()
        }
        if (fix == null) cancel.cancel()
        val location = fix ?: runCatching { client.lastLocation.await() }.getOrNull()
            ?.takeIf { System.currentTimeMillis() - it.time <= LAST_FIX_MS }
        return location?.takeIf { !it.hasAccuracy() || it.accuracy <= MAX_ACCURACY_M }?.let { it.latitude to it.longitude }
    }
}
