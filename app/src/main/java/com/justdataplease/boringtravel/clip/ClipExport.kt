package com.justdataplease.boringtravel.clip

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/** Turning a plan into a shareable MP4, and the small file chores around it. */
object ClipExport {
    /**
     * Photos are decoded with their short side at most this: a paused photo's card is about 560 px wide in a
     * 1080 px clip and crops the photo to 4:5 (or square on polaroid looks), so the short side is what shows.
     */
    const val PHOTO_SHORT_SIDE = 600

    /** cache/exports/<trip-title>-clip.mp4, shared through the app's FileProvider. */
    fun outputFile(context: Context, title: String): File = File(File(context.cacheDir, "exports"), ClipText.fileName(title))

    /**
     * Renders every frame of [plan] and encodes it to [output] (written to a ".part" file first, so a cancelled or
     * failed run never leaves a broken clip). Runs on its own thread; cancel the calling coroutine to stop it. Phone
     * videos ([videos], by id) are decoded at the clip's size as the encoder reaches them.
     */
    suspend fun make(
        plan: ClipPlan,
        base: BaseMap,
        style: ClipStyle,
        photos: Map<String, Bitmap>,
        isPro: Boolean,
        design: ClipDesign,
        output: File,
        videos: Map<String, VideoSource> = emptyMap(),
        progress: (Float) -> Unit,
    ): File = Executors.newSingleThreadExecutor { Thread(it, "clip-encoder") }.asCoroutineDispatcher().use { thread ->
        withContext(thread) {
            val (w, h) = Mp4Encoder.supportedSize(plan.options.shape.width, plan.options.shape.height)
            val renderer = ClipRenderer(plan, base, style, photos, isPro, w, h, design, DecodedVideoFrames(videos))
            val part = ClipFiles.temporary(output)
            var canvas: Canvas? = null
            var canvasFor: Bitmap? = null
            try {
                Mp4Encoder(w, h, plan.fps).encode(
                    output = part,
                    frames = plan.frameCount,
                    draw = { f, bitmap ->
                        if (canvasFor !== bitmap) { canvas = Canvas(bitmap); canvasFor = bitmap }
                        renderer.draw(f, requireNotNull(canvas), bitmap)
                    },
                    progress = progress,
                    cancelled = { !isActive },
                )
                ensureActive()
                ClipFiles.publish(part, output)
            } finally {
                renderer.close()
                part.delete()
            }
        }
    }

    /**
     * Decodes the photos by id, the right way up (EXIF), in memory a Canvas can draw: scaled so the short side is
     * [shortSide] (never enlarged; the card shows the photo cropped, so the short side sets its sharpness) and
     * cropped around the middle to what a card can show ([photoCrop]; at most 600 × 750, 1.8 MB).
     */
    suspend fun loadPhotos(files: Map<String, File>, shortSide: Int = PHOTO_SHORT_SIDE): Map<String, Bitmap> = withContext(Dispatchers.IO) {
        files.mapNotNull { (id, file) -> runCatching { decode(file, shortSide) }.getOrNull()?.let { id to it } }.toMap()
    }

    private fun decode(file: File, shortSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val (tw, th) = photoTarget(w, h, shortSide)
            if (tw != w || th != h) decoder.setTargetSize(tw, th)
            val (cw, ch) = photoCrop(tw, th)
            if (cw < tw || ch < th) {
                val l = (tw - cw) / 2
                val t = (th - ch) / 2
                decoder.crop = android.graphics.Rect(l, t, l + cw, t + ch)
            }
        }

    /** The decoded size for a [w] × [h] photo: short side [shortSide] at most, never enlarged. */
    fun photoTarget(w: Int, h: Int, shortSide: Int): Pair<Int, Int> {
        val scale = min(1.0, shortSide.toDouble() / min(w, h).coerceAtLeast(1))
        return (w * scale).roundToInt().coerceAtLeast(1) to (h * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * What of a [w] × [h] picture a card can show: cards are 4:5 portrait (square on polaroid looks) and crop around
     * the middle, so a landscape photo keeps its middle square and a tall one its middle 4:5.
     */
    fun photoCrop(w: Int, h: Int): Pair<Int, Int> =
        if (w >= h) h to h else w to min(h, (w * 1.25).roundToInt())

    /** The system share sheet for a finished clip, with read access for the receiving app. */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }
}
