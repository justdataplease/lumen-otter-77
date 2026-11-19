package com.justdataplease.boringtravel.doc

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Fractions of the text-column width, shared by the editor, reader and print HTML. */
data class PhotoPlacement(val width: Float, val left: Float, val envelope: Float, val verticalRoom: Float) {
    companion object {
        fun of(photo: Block.Photo, aspect: Float = 1f): PhotoPlacement {
            val a = aspect.takeIf { it.isFinite() && it > 0f }?.coerceAtLeast(0.1f) ?: 1f
            val angle = Math.toRadians(photo.rotation.takeIf { it.isFinite() }?.coerceIn(-15f, 15f)?.toDouble() ?: 0.0)
            val s = abs(sin(angle)).toFloat()
            val c = abs(cos(angle)).toFloat()
            val requested = photo.width.takeIf { it.isFinite() }?.coerceIn(0.3f, 1f) ?: 1f
            // Reserve room for a frame/caption as well as the image's actual aspect ratio.
            val frameRoom = if (photo.caption.isNotBlank() || photo.frame == PhotoFrame.Polaroid) 0.2f else 0f
            val factor = max(1f, c + s * (1f / a + frameRoom))
            val width = min(requested, 1f / factor)
            val envelope = (width * factor).coerceAtMost(1f)
            val left = (1f - envelope) * photo.horizontalPosition() + (envelope - width) / 2f
            return PhotoPlacement(width, left, envelope, width * s / 2f)
        }
    }
}

fun Block.Photo.horizontalPosition(): Float = position?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: when (align) {
    Align.Start -> 0f
    Align.Center -> 0.5f
    Align.End -> 1f
}

/** Horizontal dragging uses only available room; a full-width photo stays centred until resized. */
fun draggedPhotoPosition(start: Float, deltaPx: Float, columnPx: Float, envelope: Float): Float {
    val origin = start.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
    val spare = columnPx * (1f - envelope)
    if (!spare.isFinite() || spare <= 1f || !deltaPx.isFinite()) return origin
    return (origin + deltaPx / spare).coerceIn(0f, 1f)
}

/** Move a stable block key before another key, or to the end. Unknown/stale targets leave the order untouched. */
fun <T> moveBlockBefore(items: List<T>, moving: T, before: T?): List<T> {
    if (moving !in items || moving == before || (before != null && before !in items)) return items
    val rest = items.filterNot { it == moving }.toMutableList()
    rest.add(before?.let(rest::indexOf) ?: rest.size, moving)
    return rest
}
