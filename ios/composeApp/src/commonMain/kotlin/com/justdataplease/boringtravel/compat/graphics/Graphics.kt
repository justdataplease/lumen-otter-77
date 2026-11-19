package com.justdataplease.boringtravel.compat.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.ClipMode
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.IRect
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.RRect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.VertexMode
import androidx.compose.ui.graphics.ColorFilter as ComposeColorFilter
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import androidx.compose.ui.graphics.Paint as ComposePaint
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.PathEffect as ComposePathEffect
import androidx.compose.ui.graphics.Shader as ComposeShader
import org.jetbrains.skia.Bitmap as SkBitmap
import org.jetbrains.skia.Canvas as SkCanvas
import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.Paint as SkPaint
import org.jetbrains.skia.Rect as SkRect

/*
 * android.graphics as the Android app's trip video draws with it (clip/ClipRenderer.kt, GlobeGround.kt,
 * PaperVehicle3D.kt, VehicleSprites.kt and art/PlaneMarkBitmap.kt, read in at build time with only their imports
 * changed): the same names and the same meaning, drawn with Skia, the engine android.graphics itself wraps, through
 * Compose's own bridges (its paint, path, shaders and colour filters, which already speak Android's units). Only what
 * those files call is here; anything else fails to compile, so a change on Android shows at once.
 *
 * Android's rules kept: a Paint's colour carries its alpha and [Paint.alpha] changes only that; a shadow layer is a
 * blurred copy drawn first, in the shadow's colour (with the paint's alpha when that colour is opaque), its radius made
 * a blur by Android's own rule (sigma = 0.57735 × radius + 0.5); a colour matrix's offsets are in 0–255; a bitmap drawn
 * with FILTER_BITMAP_FLAG is sampled bilinearly; a new Canvas over a bitmap starts with no transform and no clip.
 */

/** An ARGB picture: either drawable into ([createBitmap]) or a fixed one (a decoded photo, a map picture: [of]). */
class Bitmap private constructor(private var fixed: SkImage?, private var pixels: SkBitmap?, val width: Int, val height: Int) {
    enum class Config { ARGB_8888, RGB_565, ALPHA_8 }

    val config: Config get() = Config.ARGB_8888

    /** Bytes the pixels take (4 a pixel). */
    val allocationByteCount: Int get() = width * height * 4

    /** Raised by each drawing into it, so a picture taken of it is taken again. */
    internal var generation = 0
    private var snapshot: SkImage? = null
    private var snapshotOf = -1
    private var sharedCanvas: SkCanvas? = null

    internal val drawable: Boolean get() = pixels != null

    /** The pixels a Canvas draws into (only a bitmap made by [createBitmap] has them). */
    internal fun pixels(): SkBitmap = pixels ?: error("This bitmap can't be drawn into")

    /** Skia's own picture of it as it is now: the fixed one, or the pixels' last state (kept until they change). */
    internal fun image(): SkImage {
        fixed?.let { return it }
        val p = pixels()
        snapshot?.takeIf { snapshotOf == generation }?.let { return it }
        snapshot?.close()
        return SkImage.makeFromBitmap(p).also { snapshot = it; snapshotOf = generation }
    }

    /** One Skia canvas per bitmap, wiped for each new [Canvas] (Android's new Canvas starts plain). */
    internal fun canvas(): SkCanvas {
        val c = sharedCanvas ?: SkCanvas(pixels(), SurfaceProps()).also {
            sharedCanvas = it
            it.save()
        }
        c.restoreToCount(1)
        c.save()
        return c
    }

    /** Drawn into straight through Skia ([canvas]): a picture taken of it is taken again. */
    internal fun drawn() {
        generation++
    }

    /**
     * The pixels as Skia keeps them, for the export's writer to copy into its own buffer (iPhone: a CVPixelBuffer);
     * null for a fixed bitmap. Valid until the bitmap is drawn into again or recycled.
     */
    internal fun peek(): org.jetbrains.skia.Pixmap? = pixels?.peekPixels()

    /**
     * The pixels as BGRA bytes, premultiplied, [width] × 4 bytes a row ([into] when it is big enough, else a new array):
     * the desktop's writer and the tests read a frame this way.
     */
    fun bgra(into: ByteArray? = null): ByteArray? {
        val p = pixels ?: return null
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val bytes = p.readPixels(info, width * 4, 0, 0) ?: return null
        if (into == null || into.size < bytes.size) return bytes
        bytes.copyInto(into)
        return into
    }

    /** The whole bitmap set to [color] (0: see-through). */
    fun eraseColor(color: Int) {
        pixels().erase(color)
        generation++
    }

    /** ARGB colours (not premultiplied), as Android's getPixels gives them. */
    fun getPixels(out: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        val source = pixels ?: SkBitmap.makeFromImage(image())
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
        val bytes = source.readPixels(info, width * 4, x, y) ?: return
        if (source !== pixels) source.close()
        for (row in 0 until height) for (col in 0 until width) {
            val i = (row * width + col) * 4
            val b = bytes[i].toInt() and 0xFF
            val g = bytes[i + 1].toInt() and 0xFF
            val r = bytes[i + 2].toInt() and 0xFF
            val a = bytes[i + 3].toInt() and 0xFF
            out[offset + row * stride + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** The bitmap for Compose: the same pixels when it can be drawn into (a later drawing shows), else a copy. */
    fun asImageBitmap(): androidx.compose.ui.graphics.ImageBitmap =
        pixels?.asComposeImageBitmap() ?: image().toComposeImageBitmap()

    /** As a JPEG of [quality] (0–100); null when Skia can't encode it. */
    fun jpeg(quality: Int): ByteArray? {
        val data = image().encodeToData(EncodedImageFormat.JPEG, quality) ?: return null
        return data.bytes.also { data.close() }
    }

    /** As a PNG (pictures for review and tests); null when Skia can't encode it. */
    fun png(): ByteArray? {
        val data = image().encodeToData(EncodedImageFormat.PNG, 100) ?: return null
        return data.bytes.also { data.close() }
    }

    /** The colour (ARGB, not premultiplied) at [x], [y]. */
    fun getPixel(x: Int, y: Int): Int {
        val out = IntArray(1)
        getPixels(out, 0, 1, x, y, 1, 1)
        return out[0]
    }

    /** [jpeg] decoded into these pixels (the same size); false when it isn't a picture of this size. */
    fun decodeInto(jpeg: ByteArray): Boolean {
        val p = pixels ?: return false
        val data = Data.makeFromBytes(jpeg)
        return try {
            val codec = Codec.makeFromData(data)
            try {
                if (codec.width != width || codec.height != height) return false
                codec.readPixels(p)
                generation++
                true
            } finally {
                codec.close()
            }
        } catch (_: Exception) {
            false
        } finally {
            data.close()
        }
    }

    /** Frees the pixels and pictures now (Skia's memory is outside Kotlin's). Drawing it afterwards fails. */
    fun recycle() {
        sharedCanvas?.close()
        sharedCanvas = null
        snapshot?.close()
        snapshot = null
        fixed?.close()
        fixed = null
        pixels?.close()
        pixels = null
    }

    val isRecycled: Boolean get() = fixed == null && pixels == null

    companion object {
        /** A see-through bitmap to draw into. */
        fun createBitmap(width: Int, height: Int, @Suppress("UNUSED_PARAMETER") config: Config): Bitmap {
            val p = SkBitmap()
            p.allocN32Pixels(width.coerceAtLeast(1), height.coerceAtLeast(1), false)
            p.erase(0)
            return Bitmap(null, p, width.coerceAtLeast(1), height.coerceAtLeast(1))
        }

        /** A fixed bitmap of ARGB [colors] (not premultiplied), row by row. */
        fun createBitmap(colors: IntArray, width: Int, height: Int, @Suppress("UNUSED_PARAMETER") config: Config): Bitmap {
            val bytes = ByteArray(width * height * 4)
            for (i in 0 until width * height) {
                val c = colors[i]
                bytes[4 * i] = (c and 0xFF).toByte()
                bytes[4 * i + 1] = ((c ushr 8) and 0xFF).toByte()
                bytes[4 * i + 2] = ((c ushr 16) and 0xFF).toByte()
                bytes[4 * i + 3] = ((c ushr 24) and 0xFF).toByte()
            }
            val image = SkImage.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, width * 4)
            return Bitmap(image, null, width, height)
        }

        /** A fixed bitmap of [image] (a decoded photo or map picture); the bitmap owns it from now on. */
        fun of(image: SkImage): Bitmap = Bitmap(image, null, image.width, image.height)
    }
}

class Rect(var left: Int = 0, var top: Int = 0, var right: Int = 0, var bottom: Int = 0) {
    fun set(left: Int, top: Int, right: Int, bottom: Int) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom
    }

    fun width(): Int = right - left
    fun height(): Int = bottom - top
}

class RectF(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    fun set(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom
    }

    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun centerX(): Float = (left + right) / 2f
    fun centerY(): Float = (top + bottom) / 2f

    fun inset(dx: Float, dy: Float) {
        left += dx; top += dy; right -= dx; bottom -= dy
    }

    internal fun skia(): SkRect = SkRect.makeLTRB(left, top, right, bottom)
}

/** Android's 3×3 matrix, its values in Android's (and Skia's) order: scale x, skew x, translate x; skew y, … */
class Matrix {
    internal val v = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    fun reset() {
        floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f).copyInto(v)
    }

    fun setValues(values: FloatArray) {
        values.copyInto(v, 0, 0, 9)
    }

    fun getValues(out: FloatArray) {
        v.copyInto(out, 0, 0, 9)
    }

    fun setScale(sx: Float, sy: Float) {
        reset()
        v[0] = sx
        v[4] = sy
    }

    fun setTranslate(dx: Float, dy: Float) {
        reset()
        v[2] = dx
        v[5] = dy
    }

    /** This matrix followed by a move of [dx], [dy]. */
    fun postTranslate(dx: Float, dy: Float): Boolean {
        for (i in 0..2) {
            v[i] += dx * v[6 + i]
            v[3 + i] += dy * v[6 + i]
        }
        return true
    }

    /** This matrix followed by a scale of [sx], [sy] about the origin. */
    fun postScale(sx: Float, sy: Float): Boolean {
        for (i in 0..2) {
            v[i] *= sx
            v[3 + i] *= sy
        }
        return true
    }

    internal fun copy(): Matrix = Matrix().also { v.copyInto(it.v) }

    internal fun skia(): Matrix33 = Matrix33(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8])
}

class Path {
    enum class Direction { CW, CCW }

    internal val c: ComposePath = ComposePath()

    fun rewind() = c.rewind()
    fun reset() = c.reset()
    fun moveTo(x: Float, y: Float) = c.moveTo(x, y)
    fun lineTo(x: Float, y: Float) = c.lineTo(x, y)
    fun close() = c.close()

    fun addRoundRect(rect: RectF, rx: Float, ry: Float, dir: Direction) {
        c.addRoundRect(
            RoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry),
            if (dir == Direction.CW) ComposePath.Direction.Clockwise else ComposePath.Direction.CounterClockwise,
        )
    }

    internal fun skia(): org.jetbrains.skia.Path = c.asSkiaPath()
}

/** A shader whose local matrix may change after it is set on a paint (Android reads it at each draw). */
abstract class Shader {
    enum class TileMode { CLAMP, REPEAT, MIRROR }

    internal var local: Matrix? = null
    internal var version = 0

    fun setLocalMatrix(matrix: Matrix?) {
        local = matrix?.copy()
        version++
    }

    internal abstract fun make(filter: Boolean): ComposeShader

    internal fun tile(t: TileMode): FilterTileMode = when (t) {
        TileMode.CLAMP -> FilterTileMode.CLAMP
        TileMode.REPEAT -> FilterTileMode.REPEAT
        TileMode.MIRROR -> FilterTileMode.MIRROR
    }
}

class BitmapShader(private val bitmap: Bitmap, private val tileX: TileMode, private val tileY: TileMode) : Shader() {
    override fun make(filter: Boolean): ComposeShader =
        bitmap.image().makeShader(tile(tileX), tile(tileY), sampling(filter), local?.skia()).asComposeShader()
}

class LinearGradient(
    private val x0: Float, private val y0: Float, private val x1: Float, private val y1: Float,
    private val color0: Int, private val color1: Int, private val tile: TileMode,
) : Shader() {
    override fun make(filter: Boolean): ComposeShader = LinearGradientShader(
        Offset(x0, y0), Offset(x1, y1), listOf(Color(color0), Color(color1)), null,
        when (tile) {
            TileMode.CLAMP -> androidx.compose.ui.graphics.TileMode.Clamp
            TileMode.REPEAT -> androidx.compose.ui.graphics.TileMode.Repeated
            TileMode.MIRROR -> androidx.compose.ui.graphics.TileMode.Mirror
        },
    )
}

open class PathEffect internal constructor(internal val compose: ComposePathEffect)

/** Dashes along a line: [intervals] on and off, starting [phase] in. */
class DashPathEffect(intervals: FloatArray, phase: Float) : PathEffect(ComposePathEffect.dashPathEffect(intervals, phase))

open class ColorFilter internal constructor(internal val compose: ComposeColorFilter)

/** Android's 4×5 colour matrix; its offsets (the fifth column) are in 0–255. */
class ColorMatrix(values: FloatArray? = null) {
    private val m = values?.copyOf(20) ?: floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    val array: FloatArray get() = m

    fun reset() {
        floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f).copyInto(m)
    }

    /** Android's own: 0 grey, 1 as it is. */
    fun setSaturation(sat: Float) {
        reset()
        val inv = 1 - sat
        val r = 0.213f * inv
        val g = 0.715f * inv
        val b = 0.072f * inv
        m[0] = r + sat; m[1] = g; m[2] = b
        m[5] = r; m[6] = g + sat; m[7] = b
        m[10] = r; m[11] = g; m[12] = b + sat
    }

    /** This matrix, then [post] (Android's setConcat(post, this)). */
    fun postConcat(post: ColorMatrix) {
        val a = post.m
        val b = m.copyOf()
        val out = FloatArray(20)
        var index = 0
        for (j in 0 until 20 step 5) {
            for (i in 0..3) {
                out[index++] = a[j] * b[i] + a[j + 1] * b[i + 5] + a[j + 2] * b[i + 10] + a[j + 3] * b[i + 15]
            }
            out[index++] = a[j] * b[4] + a[j + 1] * b[9] + a[j + 2] * b[14] + a[j + 3] * b[19] + a[j + 4]
        }
        out.copyInto(m)
    }
}

class ColorMatrixColorFilter(matrix: ColorMatrix) : ColorFilter(ComposeColorFilter.colorMatrix(ComposeColorMatrix(matrix.array.copyOf())))

/** How Android samples a picture: bilinear with FILTER_BITMAP_FLAG, the nearest pixel without. */
internal fun sampling(filter: Boolean): SamplingMode =
    if (filter) FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE) else FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)

open class Paint(flags: Int = 0) {
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }
    enum class Align { LEFT, CENTER, RIGHT }

    class FontMetrics {
        var top = 0f
        var ascent = 0f
        var descent = 0f
        var bottom = 0f
        var leading = 0f
    }

    internal val c: ComposePaint = androidx.compose.ui.graphics.Paint()
    private var argb = 0xFF000000.toInt()

    init {
        c.isAntiAlias = flags and ANTI_ALIAS_FLAG != 0
        c.color = Color(argb)
        c.skiaPaint.isDither = flags and DITHER_FLAG != 0
    }

    var isAntiAlias: Boolean
        get() = c.isAntiAlias
        set(value) { c.isAntiAlias = value }

    var isFilterBitmap: Boolean = flags and FILTER_BITMAP_FLAG != 0

    var color: Int
        get() = argb
        set(value) {
            argb = value
            c.color = Color(value)
        }

    var alpha: Int
        get() = argb ushr 24
        set(value) {
            argb = (value.coerceIn(0, 255) shl 24) or (argb and 0xFFFFFF)
            c.color = Color(argb)
        }

    var style: Style = Style.FILL
        set(value) {
            field = value
            when (value) {
                Style.FILL -> c.style = PaintingStyle.Fill
                Style.STROKE -> c.style = PaintingStyle.Stroke
                Style.FILL_AND_STROKE -> c.skiaPaint.mode = PaintMode.STROKE_AND_FILL
            }
        }

    var strokeCap: Cap = Cap.BUTT
        set(value) {
            field = value
            c.strokeCap = when (value) {
                Cap.BUTT -> StrokeCap.Butt
                Cap.ROUND -> StrokeCap.Round
                Cap.SQUARE -> StrokeCap.Square
            }
        }

    var strokeJoin: Join = Join.MITER
        set(value) {
            field = value
            c.strokeJoin = when (value) {
                Join.MITER -> StrokeJoin.Miter
                Join.ROUND -> StrokeJoin.Round
                Join.BEVEL -> StrokeJoin.Bevel
            }
        }

    var strokeWidth: Float
        get() = c.strokeWidth
        set(value) { c.strokeWidth = value }

    var pathEffect: PathEffect? = null
        set(value) {
            field = value
            c.pathEffect = value?.compose
        }

    var colorFilter: ColorFilter? = null
        set(value) {
            field = value
            c.colorFilter = value?.compose
        }

    /** Read again at each draw: Android takes the shader's local matrix as it is then. */
    var shader: Shader? = null

    private var madeFrom: Shader? = null
    private var madeVersion = -1
    private var madeFilter = false

    // The shadow layer.
    internal var shadowRadius = 0f
    internal var shadowDx = 0f
    internal var shadowDy = 0f
    internal var shadowColor = 0

    fun setShadowLayer(radius: Float, dx: Float, dy: Float, shadowColor: Int) {
        shadowRadius = radius
        shadowDx = dx
        shadowDy = dy
        this.shadowColor = shadowColor
    }

    fun clearShadowLayer() {
        shadowRadius = 0f
    }

    internal val hasShadow: Boolean get() = shadowRadius > 0f

    /** The shadow's colour as Android draws it: the paint's alpha when the shadow's colour is opaque. */
    internal val shadowArgb: Int
        get() = if (shadowColor ushr 24 == 0xFF) (shadowColor and 0xFFFFFF) or (argb and 0xFF000000.toInt()) else shadowColor

    // Text (TextPaint's, kept here as Android's Paint keeps them).
    var typeface: Typeface? = null
    var textSize: Float = 12f
    var letterSpacing: Float = 0f
    var textAlign: Align = Align.LEFT
    var fontFeatureSettings: String? = null

    internal val text: TextMeasure by lazy { TextMeasure() }

    fun measureText(text: String): Float = this.text.width(this, text)

    fun measureText(text: CharSequence, start: Int, end: Int): Float = measureText(text.subSequence(start, end).toString())

    val fontMetrics: FontMetrics get() = text.metrics(this)

    fun getFontMetrics(metrics: FontMetrics): Float {
        val m = fontMetrics
        metrics.top = m.top; metrics.ascent = m.ascent; metrics.descent = m.descent; metrics.bottom = m.bottom; metrics.leading = m.leading
        return m.descent - m.ascent + m.leading
    }

    fun ascent(): Float = fontMetrics.ascent

    fun descent(): Float = fontMetrics.descent

    /** Skia's paint for a draw now, its shader made from the current local matrix when that changed. */
    internal fun forDraw(): SkPaint {
        val s = shader
        if (s == null) {
            if (madeFrom != null) {
                c.shader = null
                madeFrom = null
            }
        } else if (s !== madeFrom || s.version != madeVersion || isFilterBitmap != madeFilter) {
            c.shader = s.make(isFilterBitmap)
            madeFrom = s
            madeVersion = s.version
            madeFilter = isFilterBitmap
        }
        return c.skiaPaint
    }

    companion object {
        const val ANTI_ALIAS_FLAG = 1
        const val FILTER_BITMAP_FLAG = 2
        const val DITHER_FLAG = 4
        const val SUBPIXEL_TEXT_FLAG = 128
    }
}

/** A canvas over a bitmap, or over the Skia canvas of a picture being made ([of]). */
class Canvas private constructor(internal val skia: SkCanvas, private val target: Bitmap?) {
    constructor(bitmap: Bitmap) : this(bitmap.canvas(), bitmap)

    enum class VertexMode { TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN }

    private fun drew() {
        target?.let { it.generation++ }
    }

    fun drawColor(color: Int) {
        val p = SkPaint().apply { this.color = color }
        skia.drawPaint(p)
        p.close()
        drew()
    }

    fun save(): Int = skia.save()

    fun restore() {
        skia.restore()
    }

    fun restoreToCount(count: Int) {
        skia.restoreToCount(count)
    }

    fun saveLayerAlpha(bounds: RectF?, alpha: Int): Int {
        val p = SkPaint().apply { this.alpha = alpha.coerceIn(0, 255) }
        // No bounds: the whole canvas (a huge rectangle under a 3D tilt's perspective would come out empty).
        val n = skia.saveLayer(bounds?.skia(), p)
        p.close()
        return n
    }

    fun concat(matrix: Matrix?) {
        if (matrix != null) skia.concat(matrix.skia())
    }

    fun translate(dx: Float, dy: Float) {
        skia.translate(dx, dy)
    }

    fun rotate(degrees: Float) {
        skia.rotate(degrees)
    }

    fun scale(sx: Float, sy: Float) {
        skia.scale(sx, sy)
    }

    fun clipRect(rect: RectF): Boolean = clipRect(rect.left, rect.top, rect.right, rect.bottom)

    fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        skia.clipRect(left, top, right, bottom, ClipMode.INTERSECT, false)
        return true
    }

    fun clipPath(path: Path): Boolean {
        skia.clipPath(path.skia(), ClipMode.INTERSECT, true)
        return true
    }

    /** [draw] with [paint], its shadow layer first when it has one (a blurred copy in the shadow's colour). */
    private inline fun shape(paint: Paint, draw: (SkPaint) -> Unit) {
        val p = paint.forDraw()
        if (paint.hasShadow) {
            val shadow = p.makeClone()
            shadow.shader = null
            shadow.colorFilter = null
            shadow.color = paint.shadowArgb
            shadow.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, blurSigma(paint.shadowRadius), true)
            skia.save()
            skia.translate(paint.shadowDx, paint.shadowDy)
            draw(shadow)
            skia.restore()
            shadow.close()
        }
        draw(p)
        drew()
    }

    fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) =
        shape(paint) { skia.drawLine(startX, startY, stopX, stopY, it) }

    fun drawPath(path: Path, paint: Paint) = shape(paint) { skia.drawPath(path.skia(), it) }

    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) = shape(paint) { skia.drawCircle(cx, cy, radius, it) }

    fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) =
        shape(paint) { skia.drawOval(left, top, right, bottom, it) }

    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) =
        shape(paint) { skia.drawRect(left, top, right, bottom, it) }

    fun drawRect(rect: RectF, paint: Paint) = drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)

    fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) =
        shape(paint) { skia.drawRRect(RRect.makeLTRB(rect.left, rect.top, rect.right, rect.bottom, rx, ry), it) }

    /** The same by its sides (the book's page editor draws its size label so, round 22). */
    fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) =
        shape(paint) { skia.drawRRect(RRect.makeLTRB(left, top, right, bottom, rx, ry), it) }

    fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
        skia.save()
        skia.concat(matrix.skia())
        drawImage(bitmap, null, SkRect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat()), paint)
        skia.restore()
    }

    fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) =
        drawImage(bitmap, null, SkRect.makeXYWH(left, top, bitmap.width.toFloat(), bitmap.height.toFloat()), paint)

    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) = drawImage(bitmap, src, dst.skia(), paint)

    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) =
        drawImage(bitmap, src, SkRect.makeLTRB(dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat()), paint)

    /**
     * [bitmap] (its part [src], else all of it) into [dst]. A bitmap still being drawn into (the frame under a glass
     * panel) is copied for the draw, only the part asked for.
     */
    private fun drawImage(bitmap: Bitmap, src: Rect?, dst: SkRect, paint: Paint?) {
        val p = paint?.forDraw()
        val filter = paint?.isFilterBitmap ?: false
        if (bitmap.drawable && src != null) {
            val part = SkBitmap()
            val l = src.left.coerceIn(0, bitmap.width)
            val t = src.top.coerceIn(0, bitmap.height)
            val r = src.right.coerceIn(l, bitmap.width)
            val b = src.bottom.coerceIn(t, bitmap.height)
            if (r > l && b > t && bitmap.pixels().extractSubset(part, IRect.makeLTRB(l, t, r, b))) {
                val image = SkImage.makeFromBitmap(part)
                skia.drawImageRect(image, SkRect.makeWH((r - l).toFloat(), (b - t).toFloat()), dst, sampling(filter), p, true)
                image.close()
            }
            part.close()
        } else {
            val image = bitmap.image()
            val from = src?.let { SkRect.makeLTRB(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
                ?: SkRect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat())
            skia.drawImageRect(image, from, dst, sampling(filter), p, true)
        }
        drew()
    }

    /** [text] on one line, its baseline at [y], placed by the paint's alignment at [x]. */
    fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        paint.text.draw(this, paint, text, x, y)
        drew()
    }

    /**
     * A mesh of triangles: [vertexCount] / 2 points in [verts], their places in the paint's shader's picture in [texs],
     * joined by [indices]. Only what the globe's draped map needs (no colours).
     */
    fun drawVertices(
        mode: VertexMode, vertexCount: Int, verts: FloatArray, vertOffset: Int, texs: FloatArray?, texOffset: Int,
        colors: IntArray?, colorOffset: Int, indices: ShortArray?, indexOffset: Int, indexCount: Int, paint: Paint,
    ) {
        require(colors == null) { "Colours per vertex aren't drawn here" }
        val positions = verts.copyOfRange(vertOffset, vertOffset + vertexCount)
        val tex = texs?.copyOfRange(texOffset, texOffset + vertexCount)
        val idx = indices?.copyOfRange(indexOffset, indexOffset + indexCount)
        skia.drawVertices(
            when (mode) {
                VertexMode.TRIANGLES -> org.jetbrains.skia.VertexMode.TRIANGLES
                VertexMode.TRIANGLE_STRIP -> org.jetbrains.skia.VertexMode.TRIANGLE_STRIP
                VertexMode.TRIANGLE_FAN -> org.jetbrains.skia.VertexMode.TRIANGLE_FAN
            },
            positions, null, tex, idx, BlendMode.MODULATE, paint.forDraw(),
        )
        drew()
    }

    internal fun touched() = drew()

    companion object {
        /** A canvas drawing into [skia] (a surface's, or the screen's picture). */
        fun of(skia: SkCanvas): Canvas = Canvas(skia, null)
    }
}

/** android.graphics.Color's parseColor: "#RRGGBB" or "#AARRGGBB" (and Android's few colour names); throws otherwise. */
object AndroidColor {
    private val NAMES = mapOf(
        "black" to 0xFF000000.toInt(), "darkgray" to 0xFF444444.toInt(), "gray" to 0xFF888888.toInt(),
        "lightgray" to 0xFFCCCCCC.toInt(), "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF00FF00.toInt(), "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(),
        "cyan" to 0xFF00FFFF.toInt(), "magenta" to 0xFFFF00FF.toInt(), "transparent" to 0,
    )

    fun parseColor(colorString: String): Int {
        if (colorString.startsWith("#")) {
            val hex = colorString.substring(1)
            val v = hex.toLongOrNull(16) ?: throw IllegalArgumentException("Unknown color")
            return when (hex.length) {
                6 -> (v or 0xFF000000L).toInt()
                8 -> v.toInt()
                else -> throw IllegalArgumentException("Unknown color")
            }
        }
        return NAMES[colorString.lowercase()] ?: throw IllegalArgumentException("Unknown color")
    }

    /** android.graphics.Color.WHITE. */
    const val WHITE: Int = -0x1

    /** android.graphics.Color.argb: each part 0–255. */
    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha and 0xFF shl 24) or (red and 0xFF shl 16) or (green and 0xFF shl 8) or (blue and 0xFF)
}

/** androidx.core.graphics' String.toColorInt: [AndroidColor.parseColor]. */
fun String.toColorInt(): Int = AndroidColor.parseColor(this)

/** Android's (and Compose's) blur radius as the sigma Skia takes. */
internal fun blurSigma(radius: Float): Float = if (radius > 0f) 0.57735f * radius + 0.5f else 0f
