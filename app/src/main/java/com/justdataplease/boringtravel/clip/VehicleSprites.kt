package com.justdataplease.boringtravel.clip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.justdataplease.boringtravel.graph
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.theme.BtLooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The paper vehicles for the travel picker, plan rows and the clip's leg list: the replay's own 3D models drawn once
 * at a fixed three-quarter view in the theme's colours and each vehicle's chosen ride ([Rides]), so picker and replay
 * match. Small software bitmaps, the last few kept.
 */
object VehicleSprites {
    const val MAX_SIDE = 256

    /** The picker's view: the same three-quarter angle the clip screen's live icons use. */
    const val HEADING = 35f
    const val TILT = 40f

    private val lock = Mutex()
    /** One bitmap per palette and ride (null: the walker): a theme's six, and a few rides tried in the editors. */
    private val cache = object : LinkedHashMap<Pair<PaperVehicle3D.Palette, Any>, Bitmap>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<PaperVehicle3D.Palette, Any>, Bitmap>?) = size > CACHED
    }

    /** The vehicles in the current theme, as Me › Your traveller & rides has them (read from the settings). */
    suspend fun load(context: Context): Map<Vehicle, Bitmap> = load(context, context.graph.settings.rides.first())

    /** The vehicles in the current theme as [rides] (a video's own). */
    suspend fun load(context: Context, rides: Rides): Map<Vehicle, Bitmap> {
        val look = context.graph.settings.look.first()
        return load(PaperVehicle3D.Palette.of(BtLooks.of(look)), rides)
    }

    /** Six 256-square ARGB images use at most 1.5 MiB, independent of display size and replay duration. */
    suspend fun load(palette: PaperVehicle3D.Palette, rides: Rides = Rides.DEFAULT): Map<Vehicle, Bitmap> = withContext(Dispatchers.Default) {
        lock.withLock {
            // Build the immutable topology off the UI thread; the first draw then only projects it.
            PaperVehicleMeshes.get(Vehicle.Car)
            val renderer = PaperVehicle3D()
            val drawn = Vehicle.entries.filter { it != Vehicle.Auto }.associateWith { vehicle ->
                ensureActive()
                val ride = rides[vehicle]
                val key = palette to (ride ?: vehicle)
                cache[key] ?: fitted(renderer, vehicle, ride, palette).also { cache[key] = it }
            }
            ensureActive()
            drawn
        }
    }

    /**
     * Draws [vehicle] (as [ride]) once to measure where it lands, then again scaled and centred so it fills [FILL] of
     * the square (the models differ a lot in size: a walker against a plane's wingspan).
     */
    private fun fitted(renderer: PaperVehicle3D, vehicle: Vehicle, ride: Ride?, palette: PaperVehicle3D.Palette): Bitmap {
        val s = MAX_SIDE.toFloat()
        val bmp = Bitmap.createBitmap(MAX_SIDE, MAX_SIDE, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bmp), vehicle, s / 2f, s / 2f, s, HEADING, TILT, 0f, palette, ride)
        val pixels = IntArray(MAX_SIDE * MAX_SIDE)
        bmp.getPixels(pixels, 0, MAX_SIDE, 0, 0, MAX_SIDE, MAX_SIDE)
        val box = SpriteFit.bounds(pixels, MAX_SIDE, MAX_SIDE) ?: return bmp
        val fit = SpriteFit.fit(box, s, FILL)
        bmp.eraseColor(0)
        renderer.draw(Canvas(bmp), vehicle, fit.centerX, fit.centerY, s * fit.scale, HEADING, TILT, 0f, palette, ride)
        return bmp
    }

    /** At most this many bitmaps are kept (each 256 KiB). */
    private const val CACHED = 12

    /** How much of the square the model's longest side takes. */
    const val FILL = 0.9f
}

/** Pure geometry for fitting a drawn sprite into its square. */
internal object SpriteFit {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Fit(val scale: Float, val centerX: Float, val centerY: Float)

    /** The box of pixels with any real alpha (the faint ground shadow counts), or null for an empty image. */
    fun bounds(argb: IntArray, width: Int, height: Int, minAlpha: Int = 12): Box? {
        var l = width; var t = height; var r = -1; var b = -1
        for (y in 0 until height) for (x in 0 until width) {
            if ((argb[y * width + x] ushr 24) >= minAlpha) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        return if (r < 0) null else Box(l, t, r + 1, b + 1)
    }

    /**
     * The model was drawn centred at side/2 with size = side; drawing is linear in size, so scaling by k about the
     * same origin and moving the centre puts the box's middle in the middle of the square.
     */
    fun fit(box: Box, side: Float, fill: Float): Fit {
        val w = (box.right - box.left).coerceAtLeast(1)
        val h = (box.bottom - box.top).coerceAtLeast(1)
        val k = (side * fill) / maxOf(w, h)
        val midX = (box.left + box.right) / 2f - side / 2f
        val midY = (box.top + box.bottom) / 2f - side / 2f
        return Fit(k, side / 2f - midX * k, side / 2f - midY * k)
    }
}
