package com.justdataplease.boringtravel.geo

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Android's Geocoder for a place name and a country code (usually needs a network; best effort, never throws). */
object ReverseGeocoder {
    data class Place(val name: String?, val countryCode: String?)

    suspend fun lookup(context: Context, lat: Double, lng: Double, timeoutMs: Long = 6_000): Place? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address = withTimeoutOrNull(timeoutMs) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<Address?> { cont ->
                    geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(lat, lng, 1)?.firstOrNull() }.getOrNull()
                }
            }
        } ?: return null
        val name = listOf(address.locality, address.subAdminArea, address.featureName?.takeIf { f -> f.any(Char::isLetter) }, address.adminArea)
            .firstOrNull { !it.isNullOrBlank() }
        return Place(name, address.countryCode?.uppercase(Locale.ROOT))
    }
}
