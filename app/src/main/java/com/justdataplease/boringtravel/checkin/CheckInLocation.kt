package com.justdataplease.boringtravel.checkin

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.justdataplease.boringtravel.tracking.TrackingPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** One user-requested foreground fix. Never starts tracking, persists GPS, or requests background access. */
object CheckInLocation {
    @SuppressLint("MissingPermission") // Foreground permission is checked immediately before the request.
    suspend fun current(context: Context): CheckInFix? {
        if (!TrackingPermissions.hasLocation(context)) return null
        val cancel = CancellationTokenSource()
        return try {
            withTimeoutOrNull(12_500L) {
                val request = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setDurationMillis(12_000L)
                    .setMaxUpdateAgeMillis(DailyCheckIn.FRESH_MS)
                    .build()
                LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, cancel.token).await()
                    ?.let { CheckInFix(it.time, it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null) }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        finally { cancel.cancel() }
    }
}
