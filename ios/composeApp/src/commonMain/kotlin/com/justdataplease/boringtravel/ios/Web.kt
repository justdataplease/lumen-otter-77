package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.checkin.CheckInFix
import com.justdataplease.boringtravel.places.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/*
 * What the places and links screens need of the phone: the network ([Net]), where the phone is ([PhoneLocation]), a
 * folder for what is kept between starts ([MediaFiles] under the caches), the app's version for its User-Agent and the
 * phone's language. One [Web] per app, handed to every screen as [LocalWeb].
 */

/** What the phone's own geocoder says of a point (CLGeocoder's placemark on iPhone, Android's Address there). */
data class GeoAddress(
    val locality: String? = null,
    val subLocality: String? = null,
    val subAdminArea: String? = null,
    val adminArea: String? = null,
    val featureName: String? = null,
    val countryName: String? = null,
    val countryCode: String? = null,
)

/**
 * Where the phone is, asked only when the traveller taps "Use my location" (Core Location on iPhone; a fixed spot on the
 * desktop; tests choose). Never asked at the start.
 */
interface PhoneLocation {
    /** Location access is given now. */
    fun allowed(): Boolean

    /** Asks for access now (the system's question, once); [done] says whether it was given. */
    fun ask(done: (Boolean) -> Unit)

    /** Where the phone is now (a fresh, precise fix, waited for a few seconds), or null. Only when [allowed]. */
    suspend fun current(): LatLng?

    /** What the phone's geocoder calls the point (usually needs the network), within [timeoutMs]; null when nothing. */
    suspend fun address(lat: Double, lng: Double, timeoutMs: Long): GeoAddress?

    /**
     * One fresh fix for the daily check-in, with when it was taken and how accurate it is (the Android app's
     * CheckInLocation: about 12 s at most, no older than five minutes); null when there is none. Only when [allowed].
     */
    suspend fun fix(): CheckInFix?
}

/** A phone that has no location and no geocoder: previews and tests that don't need one. */
object NoLocation : PhoneLocation {
    override fun allowed(): Boolean = false
    override fun ask(done: (Boolean) -> Unit) = done(false)
    override suspend fun current(): LatLng? = null
    override suspend fun address(lat: Double, lng: Double, timeoutMs: Long): GeoAddress? = null
    override suspend fun fix(): CheckInFix? = null
}

/**
 * A phone always at [at] (the desktop), allowed and without a geocoder of its own unless given [named]; its fixes are
 * [accuracyM] metres accurate and taken now ([clock]).
 */
class FixedLocation(
    private val at: LatLng?,
    private val named: GeoAddress? = null,
    private val accuracyM: Float? = 10f,
    private val clock: () -> Long = { com.justdataplease.boringtravel.time.nowMs() },
) : PhoneLocation {
    override fun allowed(): Boolean = true
    override fun ask(done: (Boolean) -> Unit) = done(true)
    override suspend fun current(): LatLng? = at
    override suspend fun address(lat: Double, lng: Double, timeoutMs: Long): GeoAddress? = named
    override suspend fun fix(): CheckInFix? = at?.let { CheckInFix(clock(), it.lat, it.lng, accuracyM) }
}

/**
 * The network side of the app: the place search ([places], Photon), link previews and Wikipedia photos ([links]), the
 * pictures they show ([images]). [version] goes into every User-Agent; [language] is the phone's ("en"); [cache] keeps
 * the previews and pictures between starts (the caches folder: the phone may empty it).
 */
class Web(
    val net: Net,
    val location: PhoneLocation = NoLocation,
    val cache: MediaFiles = MemoryMediaFiles(),
    val version: String = "1.0",
    val language: String = "en",
    /** Me › Place photos, read when a look is made (off: Wikipedia is never asked). */
    val placePhotos: () -> Boolean = { true },
    /** Where looks that outlive a screen run (a preview still loading when its row scrolls away). */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val clock: () -> Long = { com.justdataplease.boringtravel.time.nowMs() },
) {
    /** "BoringTravel/1.0 (iPhone)": what the place search is asked with (Android: "… (Android)"). */
    val searchAgent: String get() = "BoringTravel/$version (iPhone)"

    val places: PhotonPlaceSearch by lazy { PhotonPlaceSearch(net, location, searchAgent, language) }

    val links: LinkPreviews by lazy { LinkPreviews(this) }

    val images: WebImages by lazy { WebImages(net, cache, LinkFetcher.userAgent(version), scope) }

    companion object {
        /** No network and no location: every look fails at once and nothing is asked. */
        fun offline(): Web = Web(OfflineNet)
    }
}

/** The app's network side, for every screen. */
val LocalWeb = staticCompositionLocalOf { Web.offline() }
