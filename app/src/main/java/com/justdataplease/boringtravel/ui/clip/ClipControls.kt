package com.justdataplease.boringtravel.ui.clip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.clip.Spots
import com.justdataplease.boringtravel.ui.studio.StudioChip
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioNote
import com.justdataplease.boringtravel.ui.studio.StudioSection
import com.justdataplease.boringtravel.ui.studio.StudioSlider
import java.util.Locale
import kotlin.math.roundToInt

private val S = StudioColors

/**
 * A slider that keeps its value when the finger lifts (one change, one undo step: the clip isn't made again while the
 * thumb moves). [format] names a value beside the label.
 */
@Composable
internal fun ValueSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, enabled: Boolean,
    onDone: (Float) -> Unit,
) {
    var moving by remember(value) { mutableFloatStateOf(value) }
    StudioSection(label, value = format(moving)) {
        StudioSlider(moving, { if (enabled) moving = it }, range, onDone = { if (enabled && moving != value) onDone(moving) })
    }
}

/** "120 %". */
internal fun percent(v: Float): String = "${(v * 100).roundToInt()} %"

/** "2.5 s", "0 s" as [zero] says. */
internal fun secondsLabel(v: Float, zero: String = "0 s"): String =
    if (v < 0.01f) zero else String.format(Locale.ROOT, if (v % 1f == 0f) "%.0f s" else "%.1f s", v).replace(".0 s", " s")

/**
 * The thumb pad (owner, 2026-11-09: "maybe have like a slider to change all over the screen, same for side, like a
 * playstation thumb"): a small frame of the clip's shape with a dot where the item's middle goes; drag the dot or tap
 * anywhere, bottom and corners included. [spot] is the chosen place (shares of the frame), null for the automatic
 * one ([auto], shown hollow); Auto goes back to it.
 */
@Composable
internal fun ThumbPad(
    label: String, spot: Pair<Float, Float>?, auto: Pair<Float, Float>?, aspect: Float, enabled: Boolean,
    onPick: (Pair<Float, Float>?) -> Unit,
) {
    var dragging by remember(spot) { mutableStateOf<Offset?>(null) }
    val shown = dragging?.let { it.x to it.y } ?: spot
    StudioSection(label, value = shown?.let(::spotName) ?: "Auto") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier.height(if (aspect < 1f) 150.dp else 104.dp).aspectRatio(aspect).clip(shape).background(S.raised)
                    .border(1.dp, S.edge, shape)
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        detectTapGestures { o -> onPick(Spots.share(o.x / size.width) to Spots.share(o.y / size.height)) }
                    }
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        fun at(o: Offset) = Offset((o.x / size.width).coerceIn(0f, 1f), (o.y / size.height).coerceIn(0f, 1f))
                        detectDragGestures(
                            onDragStart = { o -> dragging = at(o) },
                            onDragEnd = {
                                dragging?.let { onPick(Spots.share(it.x) to Spots.share(it.y)) }
                                dragging = null
                            },
                            onDragCancel = { dragging = null },
                        ) { change, _ ->
                            change.consume()
                            dragging = at(change.position)
                        }
                    }
                    .semantics {
                        contentDescription = "$label pad"
                        stateDescription = shown?.let(::spotName) ?: "Auto"
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val grid = S.edge
                    for (i in 1..2) {
                        drawLine(grid, Offset(w * i / 3, 0f), Offset(w * i / 3, h), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                        drawLine(grid, Offset(0f, h * i / 3), Offset(w, h * i / 3), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                    }
                    val r = 8.dp.toPx()
                    val p = shown ?: auto
                    if (p != null) {
                        // The dot stays whole inside the pad, even in a corner.
                        val c = Offset((p.first * w).coerceIn(r, w - r), (p.second * h).coerceIn(r, h - r))
                        if (shown != null) {
                            drawCircle(S.yellow, r, c)
                            drawCircle(S.bg, r * 0.35f, c)
                        } else {
                            drawCircle(S.muted, r, c, style = Stroke(2.dp.toPx()))
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioNote("Drag the dot or tap where it goes, anywhere on the frame.")
                StudioChip("Auto", spot == null, { onPick(null) }, Modifier.fillMaxWidth(), enabled = enabled)
            }
        }
    }
}

/** "Bottom left", "Middle", "Top" for a spot on the pad. */
internal fun spotName(p: Pair<Float, Float>): String {
    val row = when {
        p.second < 0.34f -> "Top"
        p.second > 0.66f -> "Bottom"
        else -> "Middle"
    }
    val side = when {
        p.first < 0.34f -> "left"
        p.first > 0.66f -> "right"
        else -> null
    }
    return when {
        side == null -> row
        row == "Middle" -> side.replaceFirstChar { it.uppercase() }
        else -> "$row $side"
    }
}
