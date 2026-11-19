package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The photos' and videos' pictures, read from the app's files and made only as big as where they are drawn: what Coil
 * does for the Android app. Decoded with Skia (the drawing library Compose uses on iPhone), turned the way the camera
 * says (EXIF orientation), a JPEG read at a half, quarter or eighth of its size when that is still big enough. A video's
 * picture is its first frame from the phone ([VideoFrames]). The last few are kept in memory.
 */
class Pictures(private val kit: MediaKit) {
    private val kept = LinkedHashMap<String, ImageBitmap>()

    /** The picture of [media] already made for [maxPx], if any. */
    fun cached(media: Media, maxPx: Int): ImageBitmap? = kept[key(media, bucket(maxPx))]

    /** The picture of [media] no bigger than about [maxPx] on its longest side; null when its file can't be read. */
    suspend fun load(media: Media, maxPx: Int): ImageBitmap? {
        val size = bucket(maxPx)
        val key = key(media, size)
        kept[key]?.let { return it }
        val made = withContext(MediaWork) {
            runCatching {
                val bytes = if (media.isVideo) kit.frames.poster(media.localPath, size) else kit.files.read(media.localPath)
                bytes?.let { decode(it, size) }
            }.getOrNull()
        } ?: return null
        kept[key] = made
        while (kept.size > KEEP) kept.remove(kept.keys.first())
        return made
    }

    private fun key(media: Media, size: Int) = "${media.localPath}@$size"

    companion object {
        /** How many pictures stay in memory. */
        private const val KEEP = 48

        /** Sizes are rounded up to these, so a list and a note share pictures. */
        fun bucket(px: Int): Int = listOf(160, 320, 640, 1280, 2048).firstOrNull { it >= px } ?: 2048

        /**
         * Width and height of the picture in [bytes] as it is shown (turned as its EXIF says); null when unreadable. A
         * format Skia can't read (HEIC, AVIF) is measured by the phone's reader ([Images]).
         */
        fun size(bytes: ByteArray): Pair<Int, Int>? = runCatching {
            val data = Data.makeFromBytes(bytes)
            val codec = Codec.makeFromData(data)
            try {
                if (codec.encodedOrigin.swapsWidthHeight()) codec.height to codec.width else codec.width to codec.height
            } finally {
                codec.close()
                data.close()
            }
        }.getOrNull()?.takeIf { it.first > 0 && it.second > 0 }
            ?: runCatching { Images.phone.facts(bytes) }.getOrNull()?.let { it.width to it.height }?.takeIf { it.first > 0 && it.second > 0 }

        /**
         * The picture in [bytes], upright, at most [maxPx] on its longest side. A HEIC or AVIF photo (kept as Android keeps
         * it) is read by the phone's reader first ([Images.drawable]).
         */
        fun decode(bytes: ByteArray, maxPx: Int): ImageBitmap? {
            val data = Data.makeFromBytes(Images.drawable(bytes, maxPx) ?: bytes)
            val codec = Codec.makeFromData(data)
            try {
                val bitmap = readSmall(codec, maxPx)
                val image = Image.makeFromBitmap(bitmap)
                val bw = bitmap.width
                val bh = bitmap.height
                val origin = codec.encodedOrigin
                val swap = origin.swapsWidthHeight()
                val ow = if (swap) bh else bw
                val oh = if (swap) bw else bh
                val scale = min(1f, maxPx.toFloat() / max(ow, oh))
                val tw = max(1, (ow * scale).roundToInt())
                val th = max(1, (oh * scale).roundToInt())
                val surface = Surface.makeRasterN32Premul(tw, th)
                val canvas = surface.canvas
                canvas.scale(tw.toFloat() / ow, th.toFloat() / oh)
                canvas.concat(origin.toMatrix(bw, bh))
                canvas.drawImageRect(
                    image, Rect.makeWH(bw.toFloat(), bh.toFloat()), Rect.makeWH(bw.toFloat(), bh.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE), Paint(), true,
                )
                val out = surface.makeImageSnapshot().toComposeImageBitmap()
                image.close()
                bitmap.close()
                surface.close()
                return out
            } finally {
                codec.close()
                data.close()
            }
        }

        /**
         * The codec's pixels, smaller when it can: a JPEG decodes straight to a half, quarter or eighth of its size (the
         * smallest that is still at least [maxPx] on its longest side), which saves most of the memory of a 12 MP photo.
         */
        internal fun readSmall(codec: Codec, maxPx: Int): Bitmap {
            val w = codec.width
            val h = codec.height
            if (codec.encodedImageFormat == EncodedImageFormat.JPEG) {
                for (d in listOf(8, 4, 2)) {
                    val sw = ceil(w / d.toDouble()).toInt()
                    val sh = ceil(h / d.toDouble()).toInt()
                    if (max(sw, sh) < maxPx) continue
                    val small = Bitmap()
                    if (!small.allocPixels(codec.imageInfo.withWidthHeight(sw, sh))) continue
                    val read = runCatching { codec.readPixels(small) }.isSuccess
                    if (read) return small
                    small.close()
                }
            }
            return codec.readPixels()
        }
    }
}

/**
 * A stored photo (media/...), cropped to fill by default; a quiet colour block while it loads or when its file is
 * missing (the Android app's ui/components/MediaImage). A video shows its first frame with the play mark ([VideoBadge])
 * unless [playBadge] is off. [background]: behind it (the look's chip colour; black full screen).
 */
@Composable
fun MediaImage(
    media: Media?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    playBadge: Boolean = true,
    background: Color = Color.Unspecified,
) {
    // A demo picture (see DemoArt) is drawn live in the current theme instead of showing its file.
    val drawn = remember(media?.localPath) { DemoArt.live(media?.localPath) }
    if (media != null && drawn != null) {
        val ratio = if (media.width > 0 && media.height > 0) media.width.toFloat() / media.height else 0f
        if (ratio > 0f && (contentScale == ContentScale.Fit || contentScale == ContentScale.Inside)) {
            // Fitted like the photo would be: its own shape inside the box.
            Box(modifier, contentAlignment = Alignment.Center) {
                DrawnScene(drawn.kind, Modifier.aspectRatio(ratio), mirror = drawn.mirror, description = contentDescription ?: "Photo")
            }
        } else {
            DrawnScene(drawn.kind, modifier, mirror = drawn.mirror, description = contentDescription ?: "Photo")
        }
        return
    }
    val description = if (media?.isVideo == true && (contentDescription == null || contentDescription == "Photo")) "Video" else contentDescription ?: "Photo"
    val behind = if (background.isSpecified) background else Bt.colors.chip
    BoxWithConstraints(modifier.background(behind).semantics { this.contentDescription = description }) {
        if (media != null) {
            val pictures = LocalMediaKit.current.pictures
            val px = with(LocalDensity.current) { max(maxWidth.toPx(), maxHeight.toPx()).let { if (it.isFinite()) it.roundToInt() else 1280 } }
            val picture by produceState(pictures.cached(media, px), media.localPath, px) { if (value == null) value = pictures.load(media, px) }
            picture?.let { Image(it, null, Modifier.matchParentSize(), contentScale = contentScale) }
            if (playBadge && media.isVideo) VideoBadge(Modifier.matchParentSize())
        }
    }
}

/** Same, by media id (its row looked up in the trips' store). */
@Composable
fun MediaImage(
    mediaId: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    playBadge: Boolean = true,
) {
    val rows = LocalMediaRows.current
    MediaImage(mediaId?.let(rows), modifier, contentDescription, contentScale, playBadge)
}

/**
 * The play mark on a video's picture: a dark round mark in the middle, about a third of the picture's short side, so it
 * reads on a list's thumbnail and on a full-width note alike.
 */
@Composable
fun VideoBadge(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val d = (minOf(maxWidth, maxHeight) * 0.3f).coerceIn(18.dp, 52.dp)
        Box(Modifier.size(d).clip(CircleShape).background(Color(0x8C000000)), contentAlignment = Alignment.Center) {
            Icon(BtIcons.Play, null, Modifier.size(d * 0.52f), tint = Color.White)
        }
    }
}
