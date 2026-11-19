package com.justdataplease.boringtravel.ui.world

import android.animation.ValueAnimator
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.core.content.res.ResourcesCompat
import com.justdataplease.boringtravel.globe.GlobeGeometry
import com.justdataplease.boringtravel.globe.GlobeMath
import com.justdataplease.boringtravel.globe.GlobePalette
import com.justdataplease.boringtravel.globe.GlobePin
import com.justdataplease.boringtravel.globe.GlobeRenderer
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Where the globe looks and how it moves: drag and fling, pinch zoom, a slow idle spin that stops on touch and comes
 * back a few seconds later, animated flights to a country or continent, and double-tap back home. Plain fields read
 * by the drawing each frame; [frame] is the only Compose state, so motion redraws without recomposing.
 */
@Stable
class GlobeState(homeLat: Double = 36.0, homeLon: Double = 8.0, val homeZoom: Float = 1.15f) {
    var lat = homeLat; private set
    var lon = homeLon; private set
    var zoom = homeZoom; private set
    private var homeLat = homeLat
    private var homeLon = homeLon

    internal var frame by mutableIntStateOf(0)
        private set

    /** True once the traveller has touched the globe (the hint goes away). */
    var interacted by mutableStateOf(false)
        private set

    private var vLat = 0.0
    private var vLon = 0.0
    private var flinging = false
    internal var touching = false
        private set
    /** Uptime (ms) after which the idle spin may come back. */
    private var resumeAt = 0L
    private var spinSince = 0L
    private var touched = false

    private class Flight(val fromLat: Double, val fromLon: Double, val fromZoom: Float, val toLat: Double, val dLon: Double, val toZoom: Float, val start: Long, val ms: Long)

    private var flight: Flight? = null
    internal val wake = Channel<Unit>(Channel.CONFLATED)

    /** Radius and centre of the last frame (gestures turn pixels into degrees with them). */
    internal var lastR = 1f
    internal var lastCx = 0f
    internal var lastCy = 0f

    /** Where home is: the traveller's countries. Moves the view there too if nobody has touched the globe yet. */
    fun setHome(lat: Double, lon: Double) {
        homeLat = GlobeMath.clampLat(lat)
        homeLon = GlobeMath.normLon(lon)
        if (!touched && flight == null) {
            this.lat = homeLat
            this.lon = homeLon
            invalidate()
        }
    }

    /** Turns the globe to [lat]/[lon] (and [zoom]) in [ms] milliseconds; stops the spin until it lands. */
    fun flyTo(lat: Double, lon: Double, zoom: Float = this.zoom, ms: Long = 900) {
        touched = true
        flinging = false
        val now = SystemClock.uptimeMillis()
        flight = Flight(this.lat, this.lon, this.zoom, GlobeMath.clampLat(lat), GlobeMath.lonDelta(this.lon, lon), GlobeMath.clampZoom(zoom), now, ms)
        resumeAt = now + ms + RESUME_MS
        wake.trySend(Unit)
    }

    fun reset() = flyTo(homeLat, homeLon, homeZoom, 650)

    internal fun invalidate() { frame++ }

    /** Stops any motion. Returns true when the globe was flinging or flying (that touch only stops it). */
    internal fun touchStart(): Boolean {
        val moving = flinging || flight != null
        touching = true
        touched = true
        if (!interacted) interacted = true
        flinging = false
        flight = null
        wake.trySend(Unit)
        return moving
    }

    internal fun touchEnd() {
        touching = false
        resumeAt = SystemClock.uptimeMillis() + RESUME_MS
        wake.trySend(Unit)
    }

    internal fun dragBy(dx: Float, dy: Float) {
        lon = GlobeMath.normLon(lon + GlobeMath.dragLon(dx, lastR, lat))
        lat = GlobeMath.clampLat(lat + GlobeMath.dragLat(dy, lastR))
        invalidate()
    }

    internal fun zoomBy(factor: Float) {
        zoom = GlobeMath.clampZoom(zoom * factor)
        invalidate()
    }

    /** Starts a fling from a finger speed in px/s. */
    internal fun fling(vx: Float, vy: Float) {
        vLon = GlobeMath.dragLon(vx, lastR, lat).coerceIn(-MAX_FLING, MAX_FLING)
        vLat = GlobeMath.dragLat(vy, lastR).coerceIn(-MAX_FLING, MAX_FLING)
        flinging = abs(vLon) + abs(vLat) > GlobeMath.FLING_STOP_DEG_PER_S
        wake.trySend(Unit)
    }

    /** True while something moves on its own (or will once the spin delay is over). */
    internal fun animating(spin: Boolean, now: Long) = flight != null || flinging || (spin && !touching && now >= resumeAt)

    internal fun msUntilSpin(spin: Boolean, now: Long): Long = if (spin && !touching) (resumeAt - now).coerceAtLeast(1) else Long.MAX_VALUE

    internal fun step(dtS: Double, spin: Boolean, now: Long) {
        val f = flight
        when {
            f != null -> {
                val t = ((now - f.start).toFloat() / f.ms).coerceIn(0f, 1f)
                val e = GlobeMath.ease(t)
                lat = f.fromLat + (f.toLat - f.fromLat) * e
                lon = GlobeMath.normLon(f.fromLon + f.dLon * e)
                zoom = f.fromZoom + (f.toZoom - f.fromZoom) * e
                if (t >= 1f) {
                    flight = null
                    resumeAt = now + RESUME_MS
                }
                spinSince = 0
            }
            flinging -> {
                lon = GlobeMath.normLon(lon + vLon * dtS)
                lat = GlobeMath.clampLat(lat + vLat * dtS)
                vLon = GlobeMath.decay(vLon, dtS)
                vLat = GlobeMath.decay(vLat, dtS)
                if (abs(vLon) + abs(vLat) < GlobeMath.FLING_STOP_DEG_PER_S) {
                    flinging = false
                    resumeAt = now + RESUME_MS
                }
                spinSince = 0
            }
            spin && !touching -> {
                if (spinSince == 0L) spinSince = now
                // West to east, as the Earth turns: the view's longitude slides back.
                lon = GlobeMath.normLon(lon - GlobeMath.spinSpeed((now - spinSince) / 1000.0) * dtS)
            }
        }
        invalidate()
    }

    companion object {
        const val RESUME_MS = 6_000L
        private const val MAX_FLING = 900.0
    }
}

/**
 * The globe on screen. [visited] are filled, [dashed] (planned and wished) outlined; [pins] get flag stickers. A tap on
 * a pin calls [onPin] straight away; a tap elsewhere on the globe gives the lat/lng to [onGlobeTap] (after the
 * double-tap wait), and a double tap flies home. [spin] = idle spin allowed now (it never runs with animations off).
 * [bottomInsetPx] keeps the globe centred in the part not covered by a sheet.
 */
@Composable
fun GlobeView(
    geometry: GlobeGeometry?,
    state: GlobeState,
    visited: Set<String>,
    dashed: Set<String>,
    pins: List<GlobePin>,
    modifier: Modifier = Modifier,
    spin: Boolean = true,
    interactive: Boolean = true,
    bottomInsetPx: Float = 0f,
    description: String = "Globe",
    onPin: (String) -> Unit = {},
    onGlobeTap: (lat: Double, lng: Double) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val tokens = Bt.tokens
    val palette = remember(tokens) { GlobePalette.from(tokens) }
    val typeface: Typeface? = remember(tokens.type) {
        runCatching { ResourcesCompat.getFont(context, tokens.type.bodyFace.res) }.getOrNull()
    }
    val renderer = remember(geometry, density) { geometry?.let { GlobeRenderer(it, density) } }
    // Inputs go to the renderer when they change (a recomposition redraws anyway); the draw below just reads them.
    LaunchedEffect(renderer, palette, typeface) { renderer?.setPalette(palette, typeface); state.invalidate() }
    LaunchedEffect(renderer, pins) { renderer?.setPins(pins); state.invalidate() }
    LaunchedEffect(renderer, visited, dashed) { renderer?.setCountries(visited, dashed); state.invalidate() }

    val animatorsOn = remember { runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true) }
    val spinNow by rememberUpdatedState(spin && animatorsOn)
    val currentOnPin by rememberUpdatedState(onPin)
    val currentOnTap by rememberUpdatedState(onGlobeTap)
    val inset by rememberUpdatedState(bottomInsetPx)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, lifecycle) {
      lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        var last = 0L
        while (isActive) {
            val now = SystemClock.uptimeMillis()
            if (!state.animating(spinNow, now)) {
                last = 0L
                val wait = state.msUntilSpin(spinNow, now)
                withTimeoutOrNull(if (wait == Long.MAX_VALUE) 1_000 else wait) { state.wake.receive() }
                continue
            }
            withFrameNanos { t ->
                val dt = if (last == 0L) 0.0 else ((t - last) / 1e9).coerceIn(0.0, 0.05)
                last = t
                state.step(dt, spinNow, SystemClock.uptimeMillis())
            }
        }
      }
    }

    val gestures = if (!interactive || renderer == null) Modifier else Modifier.pointerInput(state, renderer) {
        val slop = viewConfiguration.touchSlop
        val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
        val tapSlop = 12 * density
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val stopped = state.touchStart()
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var dragging = false
            var pinched = false
            var moved = 0f
            var finger = down.id
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    pinched = true
                    dragging = true
                    val z = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (z != 1f) state.zoomBy(z)
                    if (pan != Offset.Zero) state.dragBy(pan.x, pan.y)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    tracker.resetTracking()
                } else {
                    val ch = pressed[0]
                    if (ch.id != finger) { finger = ch.id; tracker.resetTracking() }
                    val d = ch.positionChange()
                    if (!dragging) {
                        moved += d.getDistance()
                        if (moved > slop) dragging = true
                    }
                    if (dragging) {
                        state.dragBy(d.x, d.y)
                        ch.consume()
                    }
                    tracker.addPosition(ch.uptimeMillis, ch.position)
                }
            }
            if (dragging) {
                if (!pinched) {
                    val v = tracker.calculateVelocity()
                    state.fling(v.x, v.y)
                }
                state.touchEnd()
                return@awaitEachGesture
            }
            // A tap on a moving globe only stops it. Otherwise a pin answers at once; elsewhere wait for a double tap.
            if (stopped) {
                state.touchEnd()
                return@awaitEachGesture
            }
            val pos = down.position
            val pin = renderer.pinAt(pos.x, pos.y, 8 * density)
            if (pin != null) {
                state.touchEnd()
                currentOnPin(pin)
                return@awaitEachGesture
            }
            val second = withTimeoutOrNull(doubleTapMs) { awaitFirstDown(requireUnconsumed = false) }
            if (second != null && (second.position - pos).getDistance() < tapSlop * 4) {
                second.consume()
                state.reset()
                state.touchEnd()
                return@awaitEachGesture
            }
            state.touchEnd()
            val out = DoubleArray(2)
            if (renderer.unproject(pos.x, pos.y, out)) currentOnTap(out[0], out[1])
        }
    }

    val semantics = Modifier.semantics {
        contentDescription = description
        if (pins.isNotEmpty()) customActions = pins.map { p -> CustomAccessibilityAction(p.label) { currentOnPin(p.code); true } }
    }

    Box(
        modifier
            .then(semantics)
            .then(gestures)
            .drawBehind {
                state.frame // redraw on every frame tick
                val w = size.width
                val h = size.height
                val usable = (h - inset).coerceAtLeast(h * 0.35f)
                val cx = w / 2f
                val cy = usable / 2f
                val r = GlobeMath.baseRadius(w, usable) * state.zoom
                state.lastR = r
                state.lastCx = cx
                state.lastCy = cy
                val rend = renderer
                if (rend == null) {
                    drawCircle(androidx.compose.ui.graphics.Color(palette.sea), radius = GlobeMath.baseRadius(w, usable) * state.zoom, center = Offset(cx, cy))
                    return@drawBehind
                }
                drawIntoCanvas { c -> rend.draw(c.nativeCanvas, state.lon, state.lat, cx, cy, r, w, h) }
            },
    )
}
