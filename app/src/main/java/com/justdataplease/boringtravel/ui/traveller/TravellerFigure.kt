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
import com.justdataplease.boringtravel.clip.PaperVehicle3D
import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.clip.Who
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The traveller as the trip video draws them (the same paper figure, in the theme's paper for shoes and the bag),
 * walking on the spot when [walking]; a sideways drag turns them round when [turnable].
 */
@Composable
internal fun TravellerFigure(
    traveller: Traveller,
    modifier: Modifier = Modifier,
    walking: Boolean = true,
    turnable: Boolean = true,
    heading: Float = FRONT,
) {
    val renderer = remember { PaperVehicle3D() }
    val tokens = Bt.tokens
    val palette = remember(tokens) { PaperVehicle3D.Palette.of(tokens) }
    var turn by remember { mutableFloatStateOf(0f) }
    val phase = if (walking) {
        val cycle = rememberInfiniteTransition(label = "walk")
        cycle.animateFloat(0f, 1f, infiniteRepeatable(tween(CYCLE_MS, easing = LinearEasing), RepeatMode.Restart), label = "step").value
    } else {
        0.2f
    }
    val described = when (traveller.who) {
        Who.Couple -> "A couple walking hand in hand"
        Who.Man -> "The man walking"
        Who.Woman -> "The woman walking"
    }
    Canvas(
        modifier
            .semantics { contentDescription = described }
            .then(if (turnable) Modifier.pointerInput(Unit) { detectHorizontalDragGestures { _, dx -> turn += dx * .6f } } else Modifier),
    ) {
        // Seen from this camera a figure is about 0.73 of its marker size tall; a couple are also wider.
        val marker = min(size.height * 1.2f, size.width * if (traveller.who == Who.Couple) 1.45f else 2.4f)
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        drawIntoCanvas { canvas ->
            renderer.drawTraveller(
                canvas.nativeCanvas, traveller, centerX, centerY, marker,
                heading + turn, TILT, sin(phase * 2f * PI.toFloat()) * if (walking) 1f else .5f, palette,
            )
        }
    }
}

/** A three-quarter view from the front (the clip shows them from every side as they walk). */
internal const val FRONT = 155f
/** The 3D clip camera at its lowest ([com.justdataplease.boringtravel.clip.ReplayProjection.MAX_TILT]), so faces read. */
private const val TILT = 48f
private const val CYCLE_MS = 1200
