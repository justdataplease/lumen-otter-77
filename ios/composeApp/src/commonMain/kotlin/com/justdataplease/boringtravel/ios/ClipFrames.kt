package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.justdataplease.boringtravel.clip.BaseMap
import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipPlan
import com.justdataplease.boringtravel.clip.ClipRenderer
import com.justdataplease.boringtravel.clip.ClipStyle
import com.justdataplease.boringtravel.clip.ClipVideoFrames
import com.justdataplease.boringtravel.clip.SceneHit
import com.justdataplease.boringtravel.compat.graphics.Bitmap
import com.justdataplease.boringtravel.compat.graphics.Canvas
import com.justdataplease.boringtravel.ui.clip.PreviewMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The trip video's frames: frame f of [plan] drawn as the Android app's ClipRenderer draws it (read in at build time,
 * on compat/graphics' Skia canvas), [width] × [height] pixels, the phone videos' pictures from [videos]. A frame is a
 * pure function of the clip and its number (the same clip and frame give the same picture), so the preview draws with
 * this and the export (round 20) will hand each frame to AVAssetWriter the same way. One per thread: the renderer
 * keeps its measured texts and pictures.
 */
class ClipFrames(
    plan: ClipPlan, base: BaseMap, style: ClipStyle, photos: Map<String, Bitmap>, isPro: Boolean, design: ClipDesign,
    val width: Int, val height: Int, videos: ClipVideoFrames = ClipVideoFrames.NONE,
) : AutoCloseable {
    private val renderer = ClipRenderer(plan, base, style, photos, isPro, width, height, design, videos)
    private var own: Bitmap? = null

    /** Frame [frame] drawn into [into] (a bitmap of [width] × [height]), which it returns. */
    fun draw(frame: Int, into: Bitmap): Bitmap {
        renderer.draw(frame, Canvas(into), into)
        return into
    }

    /** Frame [frame] in this one's own bitmap (kept until [close]). */
    fun draw(frame: Int): Bitmap = draw(frame, own ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { own = it })

    /** Frame [frame] as a JPEG of [quality] (the prepared preview's frames). */
    fun jpeg(frame: Int, quality: Int = PreviewMath.JPEG_QUALITY): ByteArray? = draw(frame).jpeg(quality)

    /** Where the last frame drew scene [key]'s place name or photo card (the editor's drag). */
    fun hit(key: String): SceneHit? = renderer.hit(key)

    override fun close() {
        renderer.close()
        own?.recycle()
        own = null
    }
}

/*
 * Skia's memory is outside Kotlin's, so what a screen makes to draw with is freed when the screen lets go of it: a
 * moment later, since a frame may still be drawing with it on another thread (a frame takes milliseconds).
 */

/** Outlives the screen that let go of its pictures. */
private val LATER = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Longer than a frame takes to draw or to show. */
const val FREE_AFTER_MS = 2_000L

/** Runs [free] a moment from now ([FREE_AFTER_MS]). */
fun freeLater(free: () -> Unit) {
    LATER.launch {
        delay(FREE_AFTER_MS)
        runCatching { free() }
    }
}

/**
 * [make]'s value for [keys] (Compose's remember), closed a moment after it is replaced or the screen leaves: a
 * renderer, a preview's frames (the editor's stage, read in from Android, where the collector frees them).
 */
@Composable
fun <T : AutoCloseable> rememberClosing(vararg keys: Any?, make: () -> T): T {
    val value = remember(*keys) { make() }
    DisposableEffect(value) { onDispose { freeLater { value.close() } } }
    return value
}

/** [count] bitmaps of [width] × [height] to draw into, freed a moment after the screen lets go of them. */
@Composable
fun rememberBitmaps(width: Int, height: Int, count: Int): Array<Bitmap> {
    val made = remember(width, height, count) { Array(count) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } }
    DisposableEffect(made) { onDispose { freeLater { made.forEach { it.recycle() } } } }
    return made
}
