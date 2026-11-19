@file:OptIn(ExperimentalForeignApi::class)

package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.checkin.CheckInFix
import com.justdataplease.boringtravel.checkin.DailyCheckIn
import com.justdataplease.boringtravel.places.LatLng
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.plugins.HttpTimeout
import platform.Foundation.NSURLErrorClientCertificateRejected
import platform.Foundation.NSURLErrorClientCertificateRequired
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorServerCertificateHasBadDate
import platform.Foundation.NSURLErrorServerCertificateHasUnknownRoot
import platform.Foundation.NSURLErrorServerCertificateNotYetValid
import platform.Foundation.NSURLErrorServerCertificateUntrusted
import platform.Foundation.NSURLErrorTimedOut
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLGeocoder
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLPlacemark
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeIntervalSinceDate
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/*
 * The network and the phone's location on iPhone, behind [Net] and [PhoneLocation]. Apple's APIs called here: Ktor's
 * Darwin engine (URLSession; its NSError's code in NSURLErrorDomain sorts a failure), Network (nw_path_monitor: whether
 * there is a connection; since round 23 also whether it is a costly one, nw_path_is_expensive and nw_path_is_constrained),
 * Core Location
 * (CLLocationManager: the When In Use question and one precise fix; CLGeocoder: a point's name) and Foundation
 * (NSLocale's language, the Caches folder through [IosMediaFiles]).
 */

/** This iPhone's network side: URLSession through Ktor, its connection, Core Location, the caches folder. */
fun iosWeb(version: String, placePhotos: () -> Boolean): Web {
    val connection = Connection()
    val net = KtorNet({ follow ->
        HttpClient(Darwin) {
            followRedirects = follow
            expectSuccess = false
            install(HttpTimeout)
        }
    }, online = { connection.online }, failure = ::darwinFailure, metered = { connection.metered })
    @Suppress("USELESS_ELVIS")
    val language: String = NSLocale.currentLocale.languageCode ?: "en"
    return Web(net, IosLocation(), IosMediaFiles(NSCachesDirectory), version, language, placePhotos)
}

/**
 * Why URLSession gave no answer, by its error code (NSURLErrorDomain): too slow, a secure connection refused (a
 * certificate the phone doesn't trust: a captive portal, a network that opens HTTPS), else no connection. What Ktor
 * threw itself (its own time limits) goes by name ([failureOf]).
 */
private fun darwinFailure(e: Throwable): NetFailure {
    val error = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(8).filterIsInstance<DarwinHttpRequestException>().firstOrNull()?.origin
        ?: return failureOf(e)
    return when (error.code) {
        NSURLErrorTimedOut -> NetFailure.Timeout
        NSURLErrorSecureConnectionFailed, NSURLErrorServerCertificateHasBadDate, NSURLErrorServerCertificateUntrusted,
        NSURLErrorServerCertificateHasUnknownRoot, NSURLErrorServerCertificateNotYetValid, NSURLErrorClientCertificateRejected,
        NSURLErrorClientCertificateRequired -> NetFailure.Insecure
        else -> NetFailure.Offline
    }
}

/** Whether the phone has a usable path to the internet now (Network's path monitor, on the main queue). */
private class Connection {
    var online: Boolean = true
        private set

    /** A costly connection (cellular, a personal hotspot) or Low Data Mode: Only on Wi‑Fi waits (round 23). */
    var metered: Boolean = false
        private set
    private val monitor = nw_path_monitor_create()

    init {
        nw_path_monitor_set_update_handler(monitor) { path ->
            online = nw_path_get_status(path) == nw_path_status_satisfied
            metered = nw_path_is_expensive(path) || nw_path_is_constrained(path)
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }
}

/**
 * Where the phone is, through Core Location: the When In Use question on the traveller's tap only, then one precise fix
 * (10 s at most, no older than 2 minutes, within 500 m, as the Android app's CurrentLocation asks); a point's name
 * from CLGeocoder. Everything runs on the main thread, where the manager calls back.
 */
private class IosLocation : PhoneLocation {
    private val manager = CLLocationManager()

    /** The manager keeps only a weak hold on its delegate. */
    private var delegate: Delegate? = null

    private fun status() = manager.authorizationStatus

    override fun allowed(): Boolean = status().let { it == kCLAuthorizationStatusAuthorizedWhenInUse || it == kCLAuthorizationStatusAuthorizedAlways }

    override fun ask(done: (Boolean) -> Unit) {
        if (allowed()) return done(true)
        if (status() != kCLAuthorizationStatusNotDetermined) return done(false)
        val d = Delegate(onAuthorization = {
            if (status() != kCLAuthorizationStatusNotDetermined) {
                delegate = null
                manager.delegate = null
                done(allowed())
            }
        })
        delegate = d
        manager.delegate = d
        manager.requestWhenInUseAuthorization()
    }

    override suspend fun current(): LatLng? {
        if (!allowed()) return null
        return withTimeoutOrNull(WAIT_MS) {
            suspendCancellableCoroutine { cont ->
                val d = Delegate(
                    onLocations = { found ->
                        val fix = found.lastOrNull()
                        val good = fix?.takeIf {
                            val age = -it.timestamp.timeIntervalSinceDate(NSDate())
                            age * 1000 <= MAX_AGE_MS && (it.horizontalAccuracy < 0 || it.horizontalAccuracy <= MAX_ACCURACY_M)
                        }
                        if (cont.isActive) cont.resume(good?.coordinate?.useContents { LatLng(latitude, longitude) })
                    },
                    onError = { if (cont.isActive) cont.resume(null) },
                )
                delegate = d
                manager.delegate = d
                manager.desiredAccuracy = kCLLocationAccuracyBest
                manager.requestLocation()
                cont.invokeOnCancellation { manager.stopUpdatingLocation() }
            }
        }
    }

    /**
     * The daily check-in's one fix (the Android app's CheckInLocation): precise, waited for 12.5 s at most, no older than
     * five minutes, with its time and accuracy (CLLocation's timestamp and horizontalAccuracy; a negative one is none).
     */
    override suspend fun fix(): CheckInFix? {
        if (!allowed()) return null
        return withTimeoutOrNull(FIX_WAIT_MS) {
            suspendCancellableCoroutine { cont ->
                val d = Delegate(
                    onLocations = { found ->
                        val f = found.lastOrNull()
                        val age = f?.let { -it.timestamp.timeIntervalSinceDate(NSDate()) * 1000 }
                        val fix = f?.takeIf { age != null && age <= DailyCheckIn.FRESH_MS }?.let {
                            val time = (it.timestamp.timeIntervalSince1970 * 1000).toLong()
                            it.coordinate.useContents { CheckInFix(time, latitude, longitude, it.horizontalAccuracy.takeIf { a -> a >= 0 }?.toFloat()) }
                        }
                        if (cont.isActive) cont.resume(fix)
                    },
                    onError = { if (cont.isActive) cont.resume(null) },
                )
                delegate = d
                manager.delegate = d
                manager.desiredAccuracy = kCLLocationAccuracyBest
                manager.requestLocation()
                cont.invokeOnCancellation { manager.stopUpdatingLocation() }
            }
        }
    }

    override suspend fun address(lat: Double, lng: Double, timeoutMs: Long): GeoAddress? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val geocoder = CLGeocoder()
            geocoder.reverseGeocodeLocation(CLLocation(latitude = lat, longitude = lng)) { placemarks, _ ->
                val p = placemarks?.firstOrNull() as? CLPlacemark
                if (cont.isActive) {
                    cont.resume(
                        p?.let {
                            GeoAddress(
                                locality = it.locality, subLocality = it.subLocality, subAdminArea = it.subAdministrativeArea,
                                adminArea = it.administrativeArea, featureName = it.name, countryName = it.country, countryCode = it.ISOcountryCode,
                            )
                        },
                    )
                }
            }
            cont.invokeOnCancellation { geocoder.cancelGeocode() }
        }
    }

    private class Delegate(
        private val onAuthorization: () -> Unit = {},
        private val onLocations: (List<CLLocation>) -> Unit = {},
        private val onError: () -> Unit = {},
    ) : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) = onAuthorization()

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) =
            onLocations(didUpdateLocations.filterIsInstance<CLLocation>())

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) = onError()
    }

    private companion object {
        const val WAIT_MS = 10_500L
        const val FIX_WAIT_MS = 12_500L
        const val MAX_AGE_MS = 2 * 60_000.0
        const val MAX_ACCURACY_M = 500.0
    }
}
