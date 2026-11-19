package com.justdataplease.boringtravel.ios

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.roundToInt

/**
 * The book's scrim under words on a photo (book/BookCss.kt `.shade`: `linear-gradient(rgba(0,0,0,0),rgba(0,0,0,.62))`)
 * drawn as a picture for WebKit ([WebKitPrint.scrimAsPicture]): black, each row a little less clear from the top (0) to
 * the foot ([png]'s opacity), [STEPS] rows, 2 px wide, as a PNG with its own transparency (unpremultiplied, so every
 * row keeps its exact opacity). CSS blends a gradient's stops premultiplied: on black, the same as these rows.
 */
object ScrimPicture {
    /** Rows of the picture: a step of opacity at most 0.0025, finer than a PDF's 8-bit alpha. */
    const val STEPS = 256

    private val made = HashMap<Int, ByteArray>()

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    private val busy = kotlin.concurrent.atomics.AtomicInt(0)

    /** The PNG for a scrim that ends at [alpha] (0–1); null when Skia can't make it. Made once per opacity, on any thread. */
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    fun png(alpha: Double): ByteArray? {
        val key = (alpha.coerceIn(0.0, 1.0) * 1000).roundToInt()
        while (!busy.compareAndSet(0, 1)) Unit
        try {
            made[key]?.let { return it }
            val bytes = runCatching { draw(key / 1000.0) }.getOrNull() ?: return null
            made[key] = bytes
            return bytes
        } finally {
            busy.store(0)
        }
    }

    /** The opacity of row [row] (0 at the top) for a scrim that ends at [alpha]: the gradient's value at the row's middle. */
    fun rowAlpha(row: Int, alpha: Double): Int = ((row + 0.5) / STEPS * alpha * 255).roundToInt().coerceIn(0, 255)

    private fun draw(alpha: Double): ByteArray? {
        val width = 2
        val pixels = ByteArray(width * STEPS * 4)
        for (row in 0 until STEPS) {
            val a = rowAlpha(row, alpha).toByte()
            for (x in 0 until width) {
                val i = (row * width + x) * 4
                // RGBA: black with the row's opacity.
                pixels[i] = 0
                pixels[i + 1] = 0
                pixels[i + 2] = 0
                pixels[i + 3] = a
            }
        }
        val image = Image.makeRaster(ImageInfo(width, STEPS, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), pixels, width * 4)
        try {
            return image.encodeToData(EncodedImageFormat.PNG)?.bytes
        } finally {
            image.close()
        }
    }
}
