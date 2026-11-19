package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.places.LatLng
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.util.Locale

/*
 * The network side on the desktop, where the iPhone's screens are previewed (`gradlew :composeApp:run`): Java's own
 * HTTP client through Ktor, a fixed spot for "Use my location" (Syntagma Square, Athens) and no geocoder of its own
 * (points are named by the place search), and the previews and pictures kept in [root]/cache.
 */

/** The spot "Use my location" finds on the desktop. */
val DESKTOP_HERE = LatLng(37.9755, 23.7348)

fun desktopWeb(root: File, version: String, placePhotos: () -> Boolean): Web = Web(
    KtorNet({ follow ->
        HttpClient(Java) {
            followRedirects = follow
            expectSuccess = false
            install(HttpTimeout)
        }
    }),
    FixedLocation(DESKTOP_HERE),
    DiskMediaFiles(File(root, "cache")),
    version,
    Locale.getDefault().language,
    placePhotos,
)
