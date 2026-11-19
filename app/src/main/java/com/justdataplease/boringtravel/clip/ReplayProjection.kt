package com.justdataplease.boringtravel.clip

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A point in the output image, or in its unpitched ground plane. */
data class ReplayPoint(val x: Double, val y: Double)

/**
 * A pinhole camera looking at a flat, georeferenced ground plane. This is perspective, not elevation terrain.
 * The same homography transforms the geographic image and the route, preventing alignment drift in a replay.
 * HUD, photos and the vehicle badge remain screen-aligned; their anchor uses [project].
 *
 * Work in image pixels rather than Android's Camera density units so previews and exported frames agree.
 *
 * [sky]: a band of sky at the top of a tilted view ([horizonY]); off by default (owner, on 1.4.0's videos: "there is
 * a gradient glare below the top place sign, can you remove it by default?"), so the ground reaches the top edge.
 */
class ReplayProjection(val width: Double, val height: Double, val tiltDegrees: Double, val sky: Boolean = false) {
    init {
        require(width > 0 && height > 0 && width.isFinite() && height.isFinite())
        require(tiltDegrees in 0.0..MAX_TILT)
    }

    private val centerX = width / 2
    private val centerY = height / 2
    private val angle = tiltDegrees * PI / 180.0
    private val cosine = cos(angle)
    private val perspective = sin(angle) / (height * CAMERA_DISTANCE_HEIGHTS)
    // Keep the near edge within its flat-view width; the current route head therefore stays on screen.
    private val scale = 1 - perspective * height / 2

    /**
     * With [sky], screen rows above this are sky in a tilted view (the far ground fades into it, as in a flyover);
     * leaving out the farthest ground also keeps the map pictures to fetch about half as large. 0 when flat or
     * without sky: the ground reaches the top of the frame.
     */
    val horizonY: Double = if (sky) height * SKY * tiltDegrees / MAX_TILT else 0.0

    /** How large the ground is drawn across the middle of the picture compared with the flat view (1 when flat). */
    val centreScale: Double get() = scale

    fun project(x: Double, y: Double): ReplayPoint {
        val localY = y - centerY
        val divisor = 1 - perspective * localY
        return ReplayPoint(centerX + scale * (x - centerX) / divisor, centerY + scale * cosine * localY / divisor)
    }

    fun unproject(x: Double, y: Double): ReplayPoint {
        val localY = (y - centerY) / (scale * cosine + perspective * (y - centerY))
        return ReplayPoint(centerX + (x - centerX) * (1 - perspective * localY) / scale, centerY + localY)
    }

    /** Row-major values for android.graphics.Matrix, including its perspective row. */
    fun matrixValues(): FloatArray = floatArrayOf(
        scale.toFloat(), (-centerX * perspective).toFloat(), (centerX * (1 + perspective * centerY - scale)).toFloat(),
        0f, (scale * cosine - centerY * perspective).toFloat(), (centerY * (1 + perspective * centerY - scale * cosine)).toFloat(),
        0f, (-perspective).toFloat(), (1 + perspective * centerY).toFloat(),
    )

    /** All ground visible through this tilted camera (below [horizonY]), used for both tile coverage and detail selection. */
    fun groundRect(camera: Camera): WorldRect {
        val corners = listOf(unproject(0.0, horizonY), unproject(width, horizonY), unproject(0.0, height), unproject(width, height))
        val world = corners.map { p ->
            ReplayPoint(camera.cx + (p.x / width - 0.5) * camera.spanX,
                camera.cy + (p.y / height - 0.5) * camera.spanY(width / height))
        }
        return WorldRect(world.minOf { it.x }, world.minOf { it.y }, world.maxOf { it.x }, world.maxOf { it.y })
    }

    companion object {
        const val MAX_TILT = 48.0
        /**
         * The most a tilted view leans without [sky]. The top row then shows ground the band used to hide, and every
         * map picture must cover it: past about 32° the pictures the clip can fetch no longer keep a close view sharp
         * (a city walk drops under the planned sharpness; 36° to 48° were tried on 2026-11-10), so it leans less than
         * [MAX_TILT].
         */
        const val MAX_TILT_NO_SKY = 32.0
        private const val CAMERA_DISTANCE_HEIGHTS = 2.4
        /** Share of the picture's height that is sky at the full tilt. */
        const val SKY = 0.2
        /** The haze under the horizon, as a share of the picture's height. */
        const val HAZE = 0.07
    }
}
