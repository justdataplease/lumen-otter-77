package com.justdataplease.boringtravel.ui.traveller

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.justdataplease.boringtravel.clip.PaperPaint
import com.justdataplease.boringtravel.clip.PaperVehicle3D
import com.justdataplease.boringtravel.clip.Ride
import com.justdataplease.boringtravel.clip.RidePaint
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlin.math.min

/**
 * A ride as the trip video draws it (the same paper model, in the theme's paper with the ride's own colours), its
 * rotor turning when [moving]; a sideways drag turns it round when [turnable].
 */
@Composable
internal fun RideFigure(
    ride: Ride,
    modifier: Modifier = Modifier,
    moving: Boolean = true,
    turnable: Boolean = true,
    heading: Float = RIDE_FRONT,
) {
    val renderer = remember { PaperVehicle3D() }
    val tokens = Bt.tokens
    val palette = remember(tokens) { PaperVehicle3D.Palette.of(tokens) }
    var turn by remember { mutableFloatStateOf(0f) }
    val seconds = if (moving) {
        val clock = rememberInfiniteTransition(label = "ride")
        clock.animateFloat(0f, 60f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), label = "seconds").value
    } else {
        0f
    }
    Canvas(
        modifier
            .semantics { contentDescription = "${ride.model.label}, ${colourWords(ride)}" }
            .then(if (turnable) Modifier.pointerInput(Unit) { detectHorizontalDragGestures { _, dx -> turn += dx * .6f } } else Modifier),
    ) {
        // A model fills its marker's sphere; seen from this camera it is a little less tall than it is long.
        val marker = min(size.width * .9f, size.height * 1.08f)
        drawIntoCanvas { canvas ->
            renderer.drawRide(canvas.nativeCanvas, ride, size.width / 2f, size.height / 2f, marker, heading + turn, TILT, seconds, palette)
        }
    }
}

/** What the "Theme" Body and Trim swatches of [ride] show in [palette]. */
internal fun rideThemeColours(ride: Ride, palette: PaperVehicle3D.Palette): Pair<Int, Int> =
    RidePaint.themeColor(ride.model, PaperPaint.BODY, palette) to RidePaint.themeColor(ride.model, PaperPaint.TRIM, palette)

private fun colourWords(ride: Ride): String {
    val body = ride.body?.label?.lowercase() ?: "theme colours"
    return if (ride.trim == null) body else "$body with ${ride.trim.label.lowercase()} trim"
}

/** A three-quarter view from the front, the nose towards the viewer. */
internal const val RIDE_FRONT = 145f
private const val TILT = 44f
