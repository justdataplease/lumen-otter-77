package com.justdataplease.boringtravel.ui.editor

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Place names for entries: reverse geocoding with the phone's own Geocoder (best effort, never on the main thread). */
object Places {
    /** The shortest useful name for an address: the town, else the area, else whatever the geocoder named. */
    fun label(locality: String?, subAdminArea: String?, adminArea: String?, featureName: String?): String? =
        listOf(locality, subAdminArea, adminArea, featureName)
            .firstOrNull { !it.isNullOrBlank() && !it.trim().all { ch -> ch.isDigit() || ch == ' ' } }
            ?.trim()

    suspend fun reverse(context: Context, lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context.applicationContext)
        val address: Address? = withTimeoutOrNull(8_000) {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    runCatching {
                        geocoder.getFromLocation(
                            lat, lng, 1,
                            object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) {
                                    if (cont.isActive) cont.resume(addresses.firstOrNull())
                                }

                                override fun onError(errorMessage: String?) {
                                    if (cont.isActive) cont.resume(null)
                                }
                            },
                        )
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(lat, lng, 1)?.firstOrNull() }.getOrNull()
                }
            }
        }
        return address?.let { label(it.locality, it.subAdminArea, it.adminArea, it.featureName) }
    }
}
