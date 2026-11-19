package com.justdataplease.boringtravel.places

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.justdataplease.boringtravel.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Place search over Photon (komoot's free OpenStreetMap geocoder, no key), with names for points from Android's
 * [Geocoder] (Photon's reverse endpoint when the phone has no geocoder). Results lean towards [LatLng] when given.
 */
class PhotonPlaceSearch(
    private val context: Context,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://photon.komoot.io",
) : PlaceSearch {
    private val userAgent = "BoringTravel/${BuildConfig.VERSION_NAME} (Android)"

    override suspend fun search(query: String, near: LatLng?): List<PlaceResult> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val url = "$baseUrl/api/".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("limit", "8")
            .apply {
                if (near != null) {
                    addQueryParameter("lat", near.lat.toString())
                    addQueryParameter("lon", near.lng.toString())
                }
                language()?.let { addQueryParameter("lang", it) }
            }
            .build()
        val body = get(url)
        return withContext(Dispatchers.Default) { PhotonParser.parse(body) }
    }

    override suspend fun reverse(lat: Double, lng: Double): PlaceResult? =
        orNull { withTimeoutOrNull(8_000) { geocoderReverse(lat, lng) } } ?: orNull { photonReverse(lat, lng) }

    /** Runs [block], turning failures into null but letting cancellation through. */
    private suspend fun <T> orNull(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun photonReverse(lat: Double, lng: Double): PlaceResult? {
        val url = "$baseUrl/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", lat.toString())
            .addQueryParameter("lon", lng.toString())
            .addQueryParameter("limit", "1")
            .apply { language()?.let { addQueryParameter("lang", it) } }
            .build()
        return PhotonParser.parse(get(url)).firstOrNull()
    }

    private suspend fun geocoderReverse(lat: Double, lng: Double): PlaceResult? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address: Address? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(
                    lat, lng, 1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses.firstOrNull())
                        override fun onError(errorMessage: String?) = cont.resume(null)
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
            }
        }
        return address?.let { a ->
            val name = a.locality ?: a.subLocality ?: a.subAdminArea ?: a.adminArea ?: a.featureName ?: return null
            PlaceResult(
                name = name,
                displayName = PlaceNames.compact(name, listOf(a.subAdminArea, a.adminArea), a.countryName),
                lat = lat,
                lng = lng,
                countryCode = a.countryCode?.uppercase(),
            )
        }
    }

    /** Photon has English, German and French names; other languages get the local names, which read fine. */
    private fun language(): String? = Locale.getDefault().language.takeIf { it in setOf("en", "de", "fr") }

    private suspend fun get(url: HttpUrl): String = suspendCancellableCoroutine { cont ->
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw IOException("Place search answered ${it.code}")
                            it.body?.string().orEmpty()
                        }
                    }
                    if (cont.isActive) cont.resumeWith(result)
                }
            },
        )
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
