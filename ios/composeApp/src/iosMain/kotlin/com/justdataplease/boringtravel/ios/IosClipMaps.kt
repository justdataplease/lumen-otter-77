@file:OptIn(ExperimentalForeignApi::class)

package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.clip.LayerSpec
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.posix.memcpy
import kotlin.coroutines.resume

/*
 * The trip video's map pictures on iPhone: MapLibre's MLNMapSnapshotter, which Kotlin can't bind on Windows, so a short
 * Swift class takes them (iosApp/BoringTravel/MapLibreMap.swift, MapLibreSnapshots) against the two interfaces here,
 * as the map's view does (IosMap.kt). Kotlin decides the rectangle, the size and the style; Swift only takes the
 * picture and says where two points landed in it.
 */

/** Takes map pictures (Swift: MapLibreSnapshots). Called on the main thread, one at a time. */
interface NativeMapSnapshots {
    /**
     * A picture of south…north, west…east (degrees) [widthPoints] × [heightPoints] points at [pixelRatio] pixels a point,
     * in the style [styleJson] (empty: the style at [styleUrl]), without MapLibre's logo; then [reply] hears the PNG
     * and where (aLat, aLng) and (bLat, bLng) landed in it, in the picture's points.
     */
    fun takeMapSnapshot(
        styleJson: String, styleUrl: String, south: Double, west: Double, north: Double, east: Double,
        widthPoints: Double, heightPoints: Double, pixelRatio: Double,
        aLat: Double, aLng: Double, bLat: Double, bLng: Double, reply: NativeMapSnapshotReply,
    )

    /** Stops the picture being taken (its reply then never comes). */
    fun cancelMapSnapshot()
}

/** What the Swift snapshotter tells Kotlin (implemented here, called by Swift on the main thread). */
interface NativeMapSnapshotReply {
    /** The picture as a PNG, and the two points in its points (x right, y down). */
    fun mapSnapshotTaken(png: NSData, ax: Double, ay: Double, bx: Double, by: Double)

    fun mapSnapshotFailed()
}

/** The video's map pictures through [native] (none: no map, as offline); [net] knows whether the phone is online. */
class IosMapSnapshots(private val native: NativeMapSnapshots?, private val net: Net) : MapSnapshots {
    override fun online(): Boolean = native != null && net.online()

    override suspend fun take(
        json: String?, styleUrl: String, spec: LayerSpec, south: Double, west: Double, north: Double, east: Double,
        aLat: Double, aLng: Double, bLat: Double, bLng: Double,
    ): MapSnapshot? {
        val maps = native ?: return null
        val (w, h) = spec.pointSize()
        val reply = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Taken?> { cont ->
                cont.invokeOnCancellation { maps.cancelMapSnapshot() }
                maps.takeMapSnapshot(
                    json.orEmpty(), styleUrl, south, west, north, east, w.toDouble(), h.toDouble(), spec.pixelRatio.toDouble(),
                    aLat, aLng, bLat, bLng,
                    object : NativeMapSnapshotReply {
                        override fun mapSnapshotTaken(png: NSData, ax: Double, ay: Double, bx: Double, by: Double) {
                            if (cont.isActive) cont.resume(Taken(png.copyBytes(), ax, ay, bx, by))
                        }

                        override fun mapSnapshotFailed() {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }
        } ?: return null
        val image = runCatching { Image.makeFromEncoded(reply.png) }.getOrNull() ?: return null
        return MapSnapshot(image, reply.ax, reply.ay, reply.bx, reply.by)
    }

    private class Taken(val png: ByteArray, val ax: Double, val ay: Double, val bx: Double, val by: Double)
}

/** NSData's bytes as a ByteArray. */
private fun NSData.copyBytes(): ByteArray {
    val n = length.toInt()
    val out = ByteArray(n)
    if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
