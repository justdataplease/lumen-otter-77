@file:OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)

package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * The map on iPhone: MapLibre Native, which Kotlin can't bind on Windows, so the view is written in Swift
 * (iosApp/BoringTravel/MapLibreMap.swift) against these two small interfaces and handed to MainViewController by the
 * Swift entry point. Swift only loads the style JSON it is given, fills the app's sources, adds the pins' pictures,
 * moves the camera where it is told and reports back; everything else is common code (BtMap, MapLook, MapCamera).
 */

/** Makes MapLibre views (Swift: MapLibreMaps). */
interface NativeMaps {
    fun create(): NativeMap
}

/** One MapLibre view (Swift: MapLibreSurface). Everything is called on the main thread. */
interface NativeMap {
    /** The MLNMapView, put on screen with UIKitView. */
    val view: UIView

    /** Who hears what the map does; null stops it. */
    fun setListener(listener: NativeMapListener?)

    /** Loads a whole style (MLNMapView.styleJSON); [NativeMapListener.styleLoaded] or [NativeMapListener.styleFailed] follows. */
    fun setStyleJson(json: String)

    /** Puts a pin's picture into the loaded style under [name] (a PNG as base64, [scale] pixels per point). */
    fun setImage(name: String, pngBase64: String, scale: Double)

    /** Replaces the GeoJSON of the loaded style's source [id] (an MLNShapeSource). */
    fun setSourceJson(id: String, geoJson: String)

    /** Moves the camera to [lat], [lng] at [zoom], with MapLibre's animation or at once; the way it is turned stays. */
    fun moveCamera(lat: Double, lng: Double, zoom: Double, animated: Boolean)

    /** The closest zoom allowed, and whether the traveller can pan, zoom and turn it (Android's setAllGesturesEnabled). */
    fun setLimits(maxZoom: Double, interactive: Boolean)

    /** The view goes away: the map stops and lets go of its listener. */
    fun destroy()
}

/** What the Swift map tells the app (implemented here, called by Swift on the main thread). */
interface NativeMapListener {
    /** The style last given is in (didFinishLoadingStyle). */
    fun styleLoaded()

    /** It could not be loaded (didFailLoadingMap). */
    fun styleFailed()

    /**
     * The camera stopped somewhere (regionDidChange), turned to [bearing] (MLNMapView.direction, degrees clockwise from
     * north at the top); [byGesture]: the traveller moved or turned it.
     */
    fun cameraMoved(lat: Double, lng: Double, zoom: Double, bearing: Double, byGesture: Boolean)

    /** A tap at [x], [y] points in the map view, MapLibre's own double tap left alone. */
    fun tapped(x: Double, y: Double)
}

/** The iPhone's map view: MapLibre through [maps]. */
class IosMapKit(private val maps: NativeMaps) : MapKit {
    @Composable
    override fun MapView(scene: MapScene, move: MapMove?, events: MapEvents, modifier: Modifier) {
        val native = remember { maps.create() }
        val latestEvents by rememberUpdatedState(events)
        val latestScene by rememberUpdatedState(scene)
        // The style MapLibre has now, once it is in: the sources and pictures go into that one.
        var loaded by remember { mutableStateOf<String?>(null) }
        var asked by remember { mutableStateOf<String?>(null) }
        val scale = remember { UIScreen.mainScreen.scale }
        DisposableEffect(native) {
            native.setListener(object : NativeMapListener {
                override fun styleLoaded() {
                    val json = asked ?: return
                    loaded = json
                    val s = latestScene
                    for (kind in PinKind.entries) {
                        native.setImage(kind.image, Base64.encode(MapPictures.png(kind, s.colors, scale.toFloat())), scale)
                    }
                    s.sources.forEach { (id, geo) -> native.setSourceJson(id, geo) }
                    latestEvents.styleLoaded(json)
                }

                override fun styleFailed() {
                    asked?.let { latestEvents.styleFailed(it) }
                }

                override fun cameraMoved(lat: Double, lng: Double, zoom: Double, bearing: Double, byGesture: Boolean) =
                    latestEvents.cameraMoved(MapCamera(lat, lng, zoom, bearing), byGesture)

                override fun tapped(x: Double, y: Double) = latestEvents.tapped(x.toFloat(), y.toFloat())
            })
            onDispose {
                native.setListener(null)
                native.destroy()
            }
        }
        LaunchedEffect(scene.style?.json) {
            val json = scene.style?.json ?: return@LaunchedEffect
            if (json == asked) return@LaunchedEffect
            asked = json
            loaded = null
            native.setStyleJson(json)
        }
        LaunchedEffect(scene.sources, loaded) {
            if (loaded != null) scene.sources.forEach { (id, geo) -> native.setSourceJson(id, geo) }
        }
        LaunchedEffect(scene.maxZoom, scene.interactive) { native.setLimits(scene.maxZoom, scene.interactive) }
        LaunchedEffect(move?.seq) {
            val m = move ?: return@LaunchedEffect
            native.moveCamera(m.camera.lat, m.camera.lng, m.camera.zoom, m.animate)
        }
        UIKitView(
            factory = { native.view },
            modifier = modifier,
            properties = UIKitInteropProperties(isInteractive = true, isNativeAccessibilityEnabled = true),
        )
    }
}
