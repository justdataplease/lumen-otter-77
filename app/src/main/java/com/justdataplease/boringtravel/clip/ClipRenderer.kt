package com.justdataplease.boringtravel.clip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import com.justdataplease.boringtravel.art.PlaneMark
import com.justdataplease.boringtravel.album.AlbumLayouts
import com.justdataplease.boringtravel.art.PlaneMarkBitmap
import com.justdataplease.boringtravel.globe.GlobePalette
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.theme.BtFonts
import com.justdataplease.boringtravel.ui.theme.BtTokens
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.ui.theme.planeMark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.Locale

/** A look's colours, type and photo treatment as an Android Canvas needs them (all from the look's tokens). */
class ClipStyle private constructor(
    val look: Look,
    val bg: Int,
    val surface: Int,
    val ink: Int,
    val muted: Int,
    val primary: Int,
    val onPrimary: Int,
    val accent: Int,
    val onAccent: Int,
    val route: Int,
    val sea: Int,
    val land: Int,
    /** Shadows: the ink on light looks, the background on the dark one. */
    val shadow: Int,
    val display: Typeface,
    val displayUpper: Boolean,
    /** Display sizes scale with the look's title size (Caveat needs more size than Albert Sans). */
    val displayScale: Float,
    val body: Typeface,
    val bodyStrong: Typeface,
    val mono: Typeface,
    val pill: Boolean,
    val photoTilt: Float,
    val framePadDp: Float,
    val frameBottomDp: Float,
    val frameColor: Int,
    val photoCornerDp: Float,
    /** The paper vehicles in the theme's colours. */
    val vehicle: PaperVehicle3D.Palette,
    /** The brand mark's colours: the theme's yellow highlighter under the navy-edged plane (the app's own [planeMark]). */
    val mark: PlaneMark.Colors,
    /** The faces a clip's texts can be set in ([ClipDesign.font]); Theme is [display]. */
    val fonts: Map<ClipFont, Typeface>,
    /** The Globe map's colours: the Map tab's globe in this look ([GlobePalette.from]). */
    val globe: GlobePalette,
) {
    val polaroid: Boolean get() = framePadDp > 0f

    companion object {
        /** Resolves the look's fonts through Compose's font loader, so the clip uses exactly the app's faces. */
        fun of(context: Context, tokens: BtTokens): ClipStyle {
            val resolver = createFontFamilyResolver(context)
            fun face(family: FontFamily, weight: FontWeight): Typeface =
                runCatching { resolver.resolve(family, weight).value as? Typeface }.getOrNull() ?: Typeface.DEFAULT
            val c = tokens.colors
            val t = tokens.type
            val display = face(t.display, t.displayWeight)
            return ClipStyle(
                look = tokens.look,
                bg = c.bg.toArgb(), surface = c.surface.toArgb(), ink = c.ink.toArgb(), muted = c.muted.toArgb(),
                primary = c.primary.toArgb(), onPrimary = c.onPrimary.toArgb(), accent = c.accent.toArgb(),
                onAccent = c.onAccent.toArgb(),
                route = c.route.toArgb(), sea = c.sea.toArgb(), land = c.land.toArgb(),
                shadow = (if (tokens.look.dark) c.bg else c.ink).toArgb(),
                display = display, displayUpper = t.displayUpper, displayScale = t.h1.value / 30f,
                body = face(t.body, FontWeight.Normal), bodyStrong = face(t.body, FontWeight.SemiBold),
                mono = face(t.mono, FontWeight.Medium),
                pill = tokens.pill,
                photoTilt = tokens.photo.tiltDeg, framePadDp = tokens.photo.framePad.value,
                frameBottomDp = tokens.photo.frameBottom.value, frameColor = tokens.photo.frameColor.toArgb(),
                photoCornerDp = tokens.photo.corner.value,
                vehicle = PaperVehicle3D.Palette.of(tokens),
                mark = c.planeMark(),
                fonts = mapOf(
                    ClipFont.Theme to display,
                    ClipFont.Sans to face(BtFonts.albert, FontWeight.Bold),
                    ClipFont.Serif to face(BtFonts.fraunces, FontWeight(BtFonts.FRAUNCES.weight)),
                    ClipFont.Hand to face(BtFonts.caveat, FontWeight.Bold),
                    ClipFont.Tall to face(BtFonts.barlowCondensed, FontWeight.Bold),
                ),
                globe = GlobePalette.from(tokens),
            )
        }
    }
}

/**
 * Draws one frame of a [ClipPlan] onto a Canvas: base map, the route so far (with what's still to come as soft
 * dots), stops, the vehicle at the head, phone videos full frame ([videos]), photo cards, the place name, the opening
 * and the end card (with the map's credits), all in the chosen [design]. Sizes are in "design dp" ([u] = the short
 * side / 360), so a low-res preview looks like the final clip. Not thread-safe: one renderer per thread (several may
 * share the plan, map and photos); [close] lets go of its video reader.
 */
class ClipRenderer(
    private val plan: ClipPlan,
    private val base: BaseMap,
    private val look: ClipStyle,
    private val photos: Map<String, Bitmap>,
    private val isPro: Boolean,
    val width: Int,
    val height: Int,
    private val design: ClipDesign = ClipDesign(),
    videos: ClipVideoFrames = ClipVideoFrames.NONE,
) : java.io.Closeable {
    private val videoReader = videos.reader()

    private val story = plan.options.shape == ClipShape.Story
    private val layout = ClipLayout(width, height, story, design.bars)
    private val u = layout.u
    private val spec = LineSpec.of(design.weight, design.line, u)
    /** The type's size ([ClipDesign.typeScale]: a look's small or big type, × the Text size slider). */
    private val type = design.typeScale()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    /** The pictures' tone ([ClipDesign.tone]) is a colour filter on the map's layers, the photos and the videos. */
    private val toneFilter = toneFilter(design.tone)
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { colorFilter = toneFilter }
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { colorFilter = toneFilter }
    private val grain: Grain? = if (design.grain) Grain(u) else null
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val futureDots = DashPathEffect(floatArrayOf(0.1f, spec.futureGap), 0f)
    private val dashes = spec.dash?.let { DashPathEffect(it, 0f) }
    private val routePath = Path()
    private val futurePath = Path()
    private val clipPath = Path()
    private val matrix = Matrix()
    private val groundMatrix = Matrix()
    private val rect = RectF()
    private val paperVehicles = PaperVehicle3D()
    private val vehiclePalette = look.vehicle
    /** Who walks the legs on foot ([ClipDesign.travellerShown]). */
    private val traveller = design.travellerShown
    /** How the vehicles look ([ClipDesign.ridesShown]). */
    private val rides = design.ridesShown
    private val shaders = HashMap<String, BitmapShader>()

    /** A Globe clip's ground ([GlobeGround]): the globe, the street map draped over it, the route on the sphere. */
    private val globe: GlobeGround? = base.globe?.takeIf { plan.globe }?.let {
        GlobeGround(plan, base, it, look.globe, look.body, u, width, height, toneFilter)
    }

    // The route line's colours.
    private val lineColor = when (design.lineColor) {
        LineColor.Theme -> look.route
        LineColor.White -> WHITE
        LineColor.Black -> NEAR_BLACK
        LineColor.Accent -> look.accent
    }
    private val casingColor = if (luminance(lineColor) > 0.6) withAlpha(NEAR_BLACK, 0.45) else look.surface

    /**
     * A text style (font, background, colour): its face, case, size and colours, with its own paints (a measured
     * block keeps its paint). The global one is [text0]; a place name the scene editor restyled gets its own.
     */
    private inner class TextLook(val font: ClipFont, val back: TextBack, val color: TextColor) {
        val id = "${font.id}/${back.id}/${color.id}"
        val face: Typeface = look.fonts[font] ?: look.display
        val upper = when (font) {
            ClipFont.Theme -> look.displayUpper
            ClipFont.Tall -> true
            else -> false
        }
        val fontScale = if (font == ClipFont.Theme) look.displayScale.coerceIn(0.9f, 1.5f) else font.scale
        val panelled = back != TextBack.None
        val glass = back == TextBack.Glass
        val panelColor = when (color) {
            TextColor.Light -> WHITE
            TextColor.Dark -> NEAR_BLACK
            TextColor.Theme -> look.primary
            TextColor.Accent -> look.accent
        }
        /** Letters: on a panel, the panel's own ink; without one, the chosen colour itself. */
        val inkColor = if (!panelled) panelColor else when (color) {
            TextColor.Light -> NEAR_BLACK
            TextColor.Dark -> WHITE
            TextColor.Theme -> look.onPrimary
            TextColor.Accent -> look.onAccent
        }
        /** A soft halo that keeps panel-less letters readable on any map: dark under light letters, light under dark. */
        val halo = if (luminance(inkColor) > 0.5) withAlpha(0xFF000000.toInt(), 0.55) else withAlpha(WHITE, 0.75)
        val mainPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = face }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = face }
        val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = face }
        fun display(s: String) = if (upper) s.uppercase() else s
    }

    private val looks = HashMap<String, TextLook>()
    private fun textLook(font: ClipFont, back: TextBack, color: TextColor): TextLook =
        looks.getOrPut("${font.id}/${back.id}/${color.id}") { TextLook(font, back, color) }

    /** The global text style (opening, end card title, captions, and every place name not restyled). */
    private val text0 = textLook(design.font, design.back, design.color)
    private fun labelLook(key: String?) = textLook(design.labelFont(key), design.labelBack(key), design.labelColor(key))

    private val endTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = text0.face
        textSize = 30f * u * text0.fontScale * type
    }
    private val endTitleLayout: StaticLayout by lazy { centred(text0.display(plan.endCard.title), endTitlePaint, 2, width - 48f * u) }
    private val blocks = HashMap<String, TextBlock>()

    /** Where the last frame drew each movable part, by scene key (the scene editor's drag handles). */
    private val hits = HashMap<String, SceneHit>()

    /** Where the last [draw] put the scene [key]'s place name or photo card, if it showed. */
    fun hit(key: String): SceneHit? = hits[key]

    // Frosted glass: the frame under a panel, shrunk and enlarged again.
    private var frostMid: Bitmap? = null
    private var frostSmall: Bitmap? = null
    private val frostSrc = Rect()
    private val frostDst = Rect()

    // Camera for the frame being drawn: world → pixels.
    private var cx = 0.0
    private var cy = 0.0
    private var k = 1.0
    private var projection = ReplayProjection(width.toDouble(), height.toDouble(), 0.0)
    private fun sx(x: Double) = ((x - cx) * k + width / 2.0).toFloat()
    private fun sy(y: Double) = ((y - cy) * k + height / 2.0).toFloat()

    /** Draws frame [frame]; [target] is the bitmap behind [canvas], when there is one (glass panels blur it). */
    fun draw(frame: Int, canvas: Canvas, target: Bitmap? = null) {
        val s = plan.frame(frame)
        cx = s.camera.cx
        cy = s.camera.cy
        k = width / s.camera.spanX
        projection = plan.projection(frame, width, height)
        val g = globe
        g?.begin(s.camera)
        // Around the globe: the look's paper, as on the Map tab.
        canvas.drawColor(if (g != null) look.bg else look.sea)
        canvas.save()
        groundMatrix.setValues(projection.matrixValues())
        canvas.concat(groundMatrix)
        val visibleGround = projection.groundRect(s.camera)
        if (g != null) {
            canvas.clipRect(g.groundClip(projection))
            g.drawGround(canvas, s, visibleGround)
        } else {
            canvas.clipRect(sx(visibleGround.x0), sy(visibleGround.y0), sx(visibleGround.x1), sy(visibleGround.y1))
            drawBase(canvas, s)
        }
        drawRoute(canvas, s)
        drawStops(canvas, s)
        canvas.restore()
        drawSky(canvas)
        drawMarker(canvas, s)
        plan.insertAt(frame)?.let { drawInsert(canvas, s, it) }
        hits.clear()
        // The stats line and the place name are measured and placed first, so photo cards keep clear of them.
        val stats = statsLine(s)
        val label = labelBlock(s)
        val labelBox = label?.let { (b, _) -> labelBox(s, b, stats?.box) }
        drawPhotos(canvas, s, labelBox, stats?.box, target)
        if (label != null && labelBox != null) {
            drawLabel(canvas, s, label.first, label.second, labelBox, target)
            s.labelKey?.let { drawTags(canvas, it, labelBox, (s.labelAlpha * (1 - s.endAlpha)).toFloat()) }
        }
        stats?.let { drawStats(canvas, it, target) }
        drawOpening(canvas, s, target)
        drawEnd(canvas, s)
        drawFinish(canvas, frame)
    }

    // --- Map --------------------------------------------------------------------------------------------------

    private fun drawBase(canvas: Canvas, s: FrameState) {
        if (!base.hasMap) {
            drawGrid(canvas, s)
            return
        }
        val view = projection.groundRect(s.camera)
        val layers = base.layers
        val weights = DoubleArray(layers.size) { i -> if (i == 0) 1.0 else LayerBlend.weight(layers[i].rect, view, s.zoom) }
        // Layers go from coarse to sharp: start at the sharpest one that fully covers the view (nothing under it shows).
        val from = weights.indices.lastOrNull { weights[it] >= 0.999 } ?: 0
        for (i in from until layers.size) {
            val layer = layers[i]
            val weight = weights[i]
            if (weight < 0.004 || !layer.rect.intersects(view)) continue
            matrix.setScale((layer.rect.w / layer.bitmap.width * k).toFloat(), (layer.rect.h / layer.bitmap.height * k).toFloat())
            matrix.postTranslate(sx(layer.rect.x0), sy(layer.rect.y0))
            layerPaint.alpha = (weight * 255).roundToInt()
            canvas.drawBitmap(layer.bitmap, matrix, layerPaint)
        }
        layerPaint.alpha = 255
    }

    /** A tilted view's sky: the look's paper above the horizon, the far ground fading into it like haze. */
    private fun drawSky(canvas: Canvas) {
        val horizon = projection.horizonY.toFloat()
        if (horizon <= 0.5f) return
        val haze = (ReplayProjection.HAZE * height * projection.tiltDegrees / ReplayProjection.MAX_TILT).toFloat()
        val low = blend(look.bg, look.sea, 0.35)
        fill.shader = LinearGradient(0f, 0f, 0f, horizon, look.bg, low, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), horizon, fill)
        fill.shader = LinearGradient(0f, horizon, 0f, horizon + haze, low, withAlpha(low, 0.0), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, horizon - 1f, width.toFloat(), horizon + haze, fill)
        fill.shader = null
    }

    /** No map (offline): the sea colour with a soft grid that moves with the camera, finer lines fading in. */
    private fun drawGrid(canvas: Canvas, s: FrameState) {
        val view = projection.groundRect(s.camera)
        val level = log2(s.camera.spanX / 5)
        val g = 2.0.pow(floor(level))
        val fine = 1 - (level - floor(level))
        line.pathEffect = null
        line.strokeWidth = 1.4f * u
        for ((step, alpha, skipEvery) in listOf(Triple(g, 1.0, 0.0), Triple(g / 2, fine, g))) {
            if (alpha < 0.02) continue
            line.color = withAlpha(look.land, alpha)
            var x = floor(view.x0 / step) * step
            while (x <= view.x1) {
                if (skipEvery == 0.0 || !isMultiple(x, skipEvery)) canvas.drawLine(sx(x), 0f, sx(x), height.toFloat(), line)
                x += step
            }
            var y = floor(view.y0 / step) * step
            while (y <= view.y1) {
                if (skipEvery == 0.0 || !isMultiple(y, skipEvery)) canvas.drawLine(0f, sy(y), width.toFloat(), sy(y), line)
                y += step
            }
        }
    }

    private fun isMultiple(v: Double, step: Double): Boolean {
        val r = v / step
        return kotlin.math.abs(r - r.roundToInt()) < 1e-6
    }

    // --- Route, stops, marker ---------------------------------------------------------------------------------

    private fun drawRoute(canvas: Canvas, s: FrameState) {
        val g = globe
        if (g != null) {
            g.routePaths(s, routePath, futurePath, g.groundClip(projection))
        } else {
            val xs = plan.xs
            val ys = plan.ys
            val hx = sx(s.headX)
            val hy = sy(s.headY)
            routePath.rewind()
            routePath.moveTo(sx(xs[0]), sy(ys[0]))
            for (i in 1..s.headSeg) routePath.lineTo(sx(xs[i]), sy(ys[i]))
            routePath.lineTo(hx, hy)
            futurePath.rewind()
            futurePath.moveTo(hx, hy)
            for (i in s.headSeg + 1 until xs.size) futurePath.lineTo(sx(xs[i]), sy(ys[i]))
        }

        // What's still to come: soft dots in the line's colour.
        line.pathEffect = futureDots
        line.strokeWidth = spec.futureWidth
        line.color = withAlpha(lineColor, 0.55)
        canvas.drawPath(futurePath, line)
        // The way so far (dashed when chosen; the dashes start at the route's start, so they never crawl): a soft
        // shadow, the casing, the line.
        line.pathEffect = dashes
        canvas.save()
        canvas.translate(0f, 1.6f * u)
        line.color = withAlpha(look.shadow, 0.08)
        line.strokeWidth = spec.casing + 6f * u
        canvas.drawPath(routePath, line)
        line.color = withAlpha(look.shadow, 0.14)
        line.strokeWidth = spec.casing + 1.5f * u
        canvas.drawPath(routePath, line)
        canvas.restore()
        line.color = casingColor
        line.strokeWidth = spec.casing
        canvas.drawPath(routePath, line)
        line.color = lineColor
        line.strokeWidth = spec.width
        canvas.drawPath(routePath, line)
        line.pathEffect = null
    }

    private fun drawStops(canvas: Canvas, s: FrameState) {
        for ((m, scale) in s.stops) {
            val r = (max(4.8f * u, spec.width * 0.85f) * scale).toFloat()
            if (r <= 0.2f) continue
            val g = globe
            val onGlobe = if (g != null) g.ground(m.x, m.y) ?: continue else null
            val x = onGlobe?.first?.toFloat() ?: sx(m.x)
            val y = onGlobe?.second?.toFloat() ?: sy(m.y)
            fill.color = look.surface
            canvas.drawCircle(x, y, r, fill)
            line.color = lineColor
            line.strokeWidth = 2.2f * u * min(1.0, scale).toFloat()
            canvas.drawCircle(x, y, r, line)
        }
    }

    private fun drawMarker(canvas: Canvas, s: FrameState) {
        if (s.vehicle == Vehicle.Auto) return
        val g = globe
        // On the globe the head can be round the back (a world trip seen from space): no marker there.
        val onGlobe = if (g != null) g.ground(s.headX, s.headY, minDepth = 0.05) ?: return else null
        val groundX = onGlobe?.first ?: sx(s.headX).toDouble()
        val groundY = onGlobe?.second ?: sy(s.headY).toDouble()
        val anchor = projection.project(groundX, groundY)
        val x = anchor.x.toFloat()
        val y = anchor.y.toFloat()
        // Project a tangent too: planes and ferries stay aligned with the same tilted route as their anchor.
        val heading = s.bearing * PI / 180
        val screenBearing = if (g != null) {
            (g.screenHeading(s, projection, anchor.x, anchor.y) ?: s.bearing).toFloat()
        } else {
            val ahead = projection.project(groundX + sin(heading) * 20 * u, groundY - cos(heading) * 20 * u)
            (atan2(ahead.x - anchor.x, anchor.y - ahead.y) * 180 / PI).toFloat()
        }
        // Smaller on views of a whole country or more (from space a 54 dp van covered Patagonia).
        val scale = markerScale(s.camera.spanX).toFloat()
        // The model casts its own grounded shadow; a circular pin would hide its depth and direction.
        if (s.vehicle == Vehicle.Walk) {
            // The traveller, a little larger than the vehicles so faces and clothes read; steps in pace with the ground.
            paperVehicles.drawTraveller(
                canvas = canvas, traveller = traveller, centerX = x, centerY = y, sizePx = TRAVELLER_DP * u * scale,
                screenHeadingDegrees = screenBearing, tiltDegrees = projection.tiltDegrees.toFloat(),
                stride = plan.walk.stride(s.frame), palette = vehiclePalette,
            )
            return
        }
        paperVehicles.draw(
            canvas = canvas, vehicle = s.vehicle, centerX = x, centerY = y, sizePx = 54f * u * scale,
            screenHeadingDegrees = screenBearing, tiltDegrees = projection.tiltDegrees.toFloat(),
            phase = s.frame.toFloat() / plan.fps, palette = vehiclePalette, ride = rides[s.vehicle],
        )
    }

    // --- Photos -------------------------------------------------------------------------------------------------

    private fun drawPhotos(canvas: Canvas, s: FrameState, nameBox: FrameBox?, statsBox: FrameBox?, target: Bitmap?) {
        for (p in s.photos) {
            val bmp = photos[p.pop.id] ?: continue
            if (p.alpha <= 0.01 || p.scale <= 0.01) continue
            // The photos the journey stops for may fill the frame or share an album page (ClipDesign.photoLayout);
            // the card is the rest, and every one of them when the layout is the card.
            when {
                p.pop.held && plan.options.photoLayout == PhotoLayout.Full -> drawFull(canvas, bmp, p, target)
                p.pop.held && plan.options.photoLayout == PhotoLayout.Album && p.pop.albumOf > 1 -> drawAlbumTile(canvas, bmp, p)
                else -> drawCard(canvas, bmp, p, nameBox, statsBox, target)
            }
        }
    }

    /**
     * A photo filling the frame (owner, 2026-11-09: "full screen"): cropped to fill, fading in and out; its caption at
     * the foot. The card's place and size don't apply; it can't be dragged.
     */
    private fun drawFull(canvas: Canvas, bmp: Bitmap, p: PhotoShow, target: Bitmap?) {
        val id = p.pop.id
        val key = ClipDesign.photoKey(id)
        val a = p.alpha.toFloat().coerceIn(0f, 1f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        fillWithPhoto(canvas, id, bmp, rect, 0f, a)
        hits[key] = SceneHit(FrameBox(0f, 0f, width.toFloat(), height.toFloat()), null, 0.5f..0.5f)
        design.scene(key).caption?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { drawCaption(canvas, it, width / 2f, height - layout.safeBottom, width - 2 * layout.side, a, target) }
    }

    /**
     * One photo of an album page (owner: "2 pictures, see layout in photo album"): the page's photos laid out as the
     * book's album pages are ([AlbumLayouts.exact] for their count) inside the safe bands, each a small white-edged
     * print over a soft dim of the map; the first of them draws the dim.
     */
    private fun drawAlbumTile(canvas: Canvas, bmp: Bitmap, p: PhotoShow) {
        val count = p.pop.albumOf.coerceAtMost(PhotoLayout.ALBUM_MAX)
        val page = AlbumLayouts.exact(count) ?: return
        val a = p.alpha.toFloat().coerceIn(0f, 1f)
        val left = layout.side
        val top = layout.safeTop
        val w = width - left - layout.sideRight
        val h = height - top - layout.safeBottom
        if (p.pop.album == 0) {
            fill.color = withAlpha(0xFF000000.toInt(), 0.35 * a)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }
        val cell = page.place(w.toDouble(), h.toDouble(), 8.0 * u).getOrNull(p.pop.album) ?: return
        val edge = 3f * u
        rect.set(left + cell.x.toFloat(), top + cell.y.toFloat(), left + cell.right.toFloat(), top + cell.bottom.toFloat())
        val key = ClipDesign.photoKey(p.pop.id)
        hits[key] = SceneHit(FrameBox(rect.left, rect.top, rect.right, rect.bottom), null, 0.5f..0.5f)
        fill.color = withAlpha(WHITE, a.toDouble())
        fill.setShadowLayer(7f * u, 0f, 2.5f * u, withAlpha(0xFF000000.toInt(), 0.32 * a))
        canvas.drawRoundRect(rect, 2f * u, 2f * u, fill)
        fill.clearShadowLayer()
        rect.inset(edge, edge)
        fillWithPhoto(canvas, p.pop.id, bmp, rect, 1.5f * u, a)
    }

    /** [bmp] cropped to fill [box] (rounded by [corner]) at opacity [a]. */
    private fun fillWithPhoto(canvas: Canvas, id: String, bmp: Bitmap, box: RectF, corner: Float, a: Float) {
        val shader = shaders.getOrPut(id) { BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val scale = max(box.width() / bmp.width, box.height() / bmp.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate(box.left + (box.width() - bmp.width * scale) / 2, box.top + (box.height() - bmp.height * scale) / 2)
        shader.setLocalMatrix(matrix)
        photoPaint.shader = shader
        photoPaint.alpha = (a * 255).roundToInt()
        canvas.drawRoundRect(box, corner, corner, photoPaint)
        photoPaint.shader = null
    }

    /**
     * A photo as a print: a white border (the look's polaroid frame on the paper looks), a soft blurred shadow, the
     * picture cropped to fill it with slightly rounded corners and a hairline edge; its caption under it. The scene
     * editor may set its place (a preset or dragged), size and tilt.
     */
    private fun drawCard(canvas: Canvas, bmp: Bitmap, p: PhotoShow, nameBox: FrameBox?, statsBox: FrameBox?, target: Bitmap?) {
        val id = p.pop.id
        val key = ClipDesign.photoKey(id)
        val o = design.scene(key)
        val side = design.photoSide(id)
        val row = design.photoRow(id)
        val spot = design.photoSpot(id)
        val centred = if (spot != null) abs(spot.first - 0.5f) < 0.1f else side == PhotoSide.Centre
        val cardW = layout.cardWidth(plan.options.shape, p.pop.held, centred) * design.photoScale(id)
        val a = p.alpha.toFloat()
        // The theme's print border (Me › Theme), or the photo alone (ClipDesign.photoFrame).
        val framed = design.photoFrame == PhotoFrame.Theme
        val polaroid = look.polaroid && framed
        val pad = if (!framed) 0f else if (polaroid) look.framePadDp * u * 1.4f else 4.5f * u
        val bottom = if (polaroid) look.frameBottomDp * u * 1.4f else pad
        val innerW = cardW - 2 * pad
        val innerH = if (polaroid) innerW else innerW * 1.25f
        val cardH = pad + innerH + bottom
        val (fx, fy) = if (spot != null) layout.cardAt(spot.first, spot.second, cardW, cardH)
            else layout.cardCentre(row, side, p.pop.slot, cardW, cardH, nameBox).let { (x, y) -> x to layout.clearOf(statsBox, x, y, cardW, cardH) }
        hits[key] = SceneHit(FrameBox(fx - cardW / 2, fy - cardH / 2, fx + cardW / 2, fy + cardH / 2), layout.cardRangeX(cardW), layout.cardRangeY(cardH))
        val corner = if (!framed) 5f * u else if (polaroid) look.photoCornerDp * u else 2.5f * u
        val leansLeft = when {
            spot != null -> spot.first < 0.5f
            side == PhotoSide.Left -> true
            side == PhotoSide.Right -> false
            else -> p.pop.slot % 2 == 0
        }
        val tilt = o.tilt ?: ((if (leansLeft) look.photoTilt else -look.photoTilt * 0.7f) * (if (centred) 0.5f else 1f))

        canvas.save()
        canvas.translate(fx, fy)
        canvas.rotate(tilt)
        canvas.scale(p.scale.toFloat(), p.scale.toFloat())
        canvas.translate(-cardW / 2, -cardH / 2)
        // The frame with its soft shadow (without a frame, the shadow under the photo itself).
        val frameR = if (!framed) corner else if (polaroid) 2f * u else 3.5f * u
        rect.set(0f, 0f, cardW, cardH)
        fill.color = withAlpha(if (polaroid) look.frameColor else WHITE, a.toDouble())
        fill.setShadowLayer(9f * u, 0f, 3.5f * u, withAlpha(0xFF000000.toInt(), 0.32 * a))
        canvas.drawRoundRect(rect, frameR, frameR, fill)
        fill.clearShadowLayer()
        // The picture, cropped to fill.
        rect.set(pad, pad, pad + innerW, pad + innerH)
        val shader = shaders.getOrPut(id) { BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val scale = max(innerW / bmp.width, innerH / bmp.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate(pad + (innerW - bmp.width * scale) / 2, pad + (innerH - bmp.height * scale) / 2)
        shader.setLocalMatrix(matrix)
        photoPaint.shader = shader
        photoPaint.alpha = (a * 255).roundToInt()
        canvas.drawRoundRect(rect, corner, corner, photoPaint)
        photoPaint.shader = null
        line.color = withAlpha(0xFF000000.toInt(), 0.08 * a)
        line.strokeWidth = 0.6f * u
        canvas.drawRoundRect(rect, corner, corner, line)
        canvas.restore()
        o.caption?.trim()?.takeIf { it.isNotEmpty() }?.let { drawCaption(canvas, it, fx, fy + cardH / 2 * p.scale.toFloat(), cardW, a, target) }
    }

    /** A photo's caption in the place name's style, small, overlapping the foot of its card ([cardBottom]). */
    private fun drawCaption(canvas: Canvas, caption: String, cx: Float, cardBottom: Float, cardW: Float, a: Float, target: Bitmap?) {
        val t = text0
        val b = block("caption", null, caption, t, t.captionPaint, 14f * u * t.fontScale * type, 2, max(cardW * 1.2f, 140f * u))
        val top = (cardBottom - b.height * 0.35f).coerceIn(0f, height - b.height)
        drawBlock(canvas, b, t.captionPaint, top, a, target, t, cx)
    }

    // --- Texts --------------------------------------------------------------------------------------------------

    /** A measured text block: a small line over the main text (one or more lines), and its size with padding. */
    /**
     * A measured text: [eyebrow] small over [main], then a [note] (up to two lines) and the exact [spot] (one line,
     * smallest), each optional.
     */
    private class TextBlock(
        val eyebrow: String?, val main: StaticLayout, val mainW: Float, val eyebrowW: Float,
        val note: StaticLayout? = null, val spot: String? = null, val spotW: Float = 0f, val sc: Float = 1f,
    ) {
        var width = 0f
        var height = 0f
        var padH = 0f
        var padV = 0f
        var gap = 0f
        var eyebrowH = 0f
        var subGap = 0f
        var spotH = 0f
    }

    /** The note under a name, in the look's body face. */
    private val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    /** The small lines grow and shrink with the name, a little less ([sc]: the block's type size). */
    private fun noteSize(sc: Float = type) = 12.5f * u * sc.coerceIn(0.8f, 1.4f)

    private fun spotSize(sc: Float = type) = 9.5f * u * sc.coerceIn(0.8f, 1.35f)

    private fun prepareSpot(sc: Float = type) {
        text.typeface = look.mono
        text.textSize = spotSize(sc)
        text.letterSpacing = 0.04f
        text.textAlign = Paint.Align.LEFT
    }

    private fun eyebrowSize(sc: Float = type) = 10f * u * sc.coerceIn(0.8f, 1.35f)

    private fun prepareEyebrow(sc: Float = type) {
        text.typeface = look.bodyStrong
        text.textSize = eyebrowSize(sc)
        text.letterSpacing = 0.12f
        text.textAlign = Paint.Align.LEFT
    }

    /**
     * [main] (up to [maxLines] lines at [size] px) with [eyebrow] above it in the style [t], measured once per text;
     * at most [maxWidth] wide with its panel.
     */
    private fun block(
        kind: String, eyebrow: String?, main: String, t: TextLook, paint: TextPaint, size: Float, maxLines: Int,
        maxWidth: Float = layout.blockMaxWidth, note: String? = null, spot: String? = null, sc: Float = type,
    ): TextBlock {
        val key = "$kind\u0000${t.id}\u0000${eyebrow.orEmpty()}\u0000$main\u0000$maxWidth\u0000${note.orEmpty()}\u0000${spot.orEmpty()}\u0000$size\u0000$sc"
        blocks[key]?.let { return it }
        if (blocks.size > 24) blocks.clear()
        val padH = if (t.panelled) 14f * u else 4f * u
        val padV = if (t.panelled) 8f * u else 2f * u
        val maxW = min(maxWidth, layout.blockMaxWidth) - 2 * padH
        paint.textSize = size
        val mainLayout = centred(t.display(main), paint, maxLines, maxW)
        var mainW = 0f
        for (i in 0 until mainLayout.lineCount) mainW = max(mainW, mainLayout.getLineWidth(i))
        prepareEyebrow(sc)
        val eyebrowText = eyebrow?.let { TextUtils.ellipsize(it.uppercase(), text, maxW, TextUtils.TruncateAt.END).toString() }
        val eyebrowW = eyebrowText?.let { text.measureText(it) } ?: 0f
        text.letterSpacing = 0f
        notePaint.typeface = look.body
        notePaint.textSize = noteSize(sc)
        val noteLayout = note?.let { centred(it, notePaint, 2, maxW) }
        var noteW = 0f
        if (noteLayout != null) for (i in 0 until noteLayout.lineCount) noteW = max(noteW, noteLayout.getLineWidth(i))
        prepareSpot(sc)
        val spotText = spot?.let { TextUtils.ellipsize(it, text, maxW, TextUtils.TruncateAt.END).toString() }
        val spotW = spotText?.let { text.measureText(it) } ?: 0f
        text.letterSpacing = 0f
        return TextBlock(eyebrowText, mainLayout, mainW, eyebrowW, noteLayout, spotText, spotW, sc).apply {
            this.padH = padH
            this.padV = padV
            gap = if (eyebrowText != null) 2f * u else 0f
            eyebrowH = if (eyebrowText != null) eyebrowSize(sc) * 1.3f else 0f
            subGap = 3f * u
            spotH = if (spotText != null) spotSize(sc) * 1.35f else 0f
            width = max(max(mainW, eyebrowW), max(noteW, spotW)) + 2 * padH
            height = padV + eyebrowH + gap + mainLayout.height +
                (noteLayout?.let { subGap + it.height } ?: 0f) + (if (spotText != null) subGap + spotH else 0f) + padV
        }.also { blocks[key] = it }
    }

    /** The place name's block in its own style, or null when none shows (none here, hidden, or faded out). */
    private fun labelBlock(s: FrameState): Pair<TextBlock, TextLook>? {
        val auto = s.place ?: return null
        if (s.labelAlpha * (1 - s.endAlpha) <= 0.01) return null
        val key = s.labelKey
        val place = design.label(key, auto) ?: return null
        val main = place.place ?: place.day ?: return null
        val eyebrow = if (place.place != null) place.day else null
        val t = labelLook(key)
        val sc = design.typeScale(key)
        // Two lines, so a stop's own long title ("Miradouro de Santa Luzia") isn't cut.
        return block("label", eyebrow, main, t, t.mainPaint, 24f * u * t.fontScale * sc, 2, note = design.note(key), spot = design.spot(key), sc = sc) to t
    }

    /**
     * Where the place name sits: its own spot or every name's (the thumb pad, a drag: [ClipDesign.nameSpot]), else at
     * the top or the bottom.
     */
    private fun labelBox(s: FrameState, b: TextBlock, avoid: FrameBox?): FrameBox {
        val key = s.labelKey
        var box = textBox(key, b, design.labelPlace(key))
        // A name in its own place (not moved on the pad) steps off the stats line: up from it low, down from it high.
        if (avoid != null && design.nameSpot(key) == null && box.intersects(avoid)) {
            val top = if (avoid.centerY > height / 2f) avoid.top - 8f * u - b.height else avoid.bottom + 8f * u
            box = FrameBox(box.left, top, box.right, top + b.height)
        }
        key?.let { hits[it] = SceneHit(box, layout.blockRangeX(b.width), layout.blockRangeY(b.height)) }
        return box
    }

    /** A text block's box: at [key]'s spot ([ClipDesign.nameSpot]), else centred at the top or the bottom ([place]). */
    private fun textBox(key: String?, b: TextBlock, place: NamePlace): FrameBox {
        val spot = design.nameSpot(key)
        val top = if (spot != null) layout.blockTopAt(spot.second, b.height) else layout.blockTop(place, b.height)
        val left = if (spot != null) layout.blockLeftAt(spot.first, b.width) else (width - b.width) / 2
        return FrameBox(left, top, left + b.width, top + b.height)
    }

    /** The place name ("DAY 4" small over "Naxos"), sliding in from its edge. */
    private fun drawLabel(canvas: Canvas, s: FrameState, b: TextBlock, t: TextLook, box: FrameBox, target: Bitmap?) {
        val a = (s.labelAlpha * (1 - s.endAlpha)).toFloat()
        val slide = 8f * u * (1 - a) * (if (box.centerY > height / 2f) 1 else -1)
        drawBlock(canvas, b, t.mainPaint, box.top + slide, a, target, t, box.centerX)
    }

    /**
     * The opening: the trip's title and dates over the whole route, where the place names go. The scene editor may
     * hide it (or show it when the style hides it), change the title or leave out the dates.
     */
    private fun drawOpening(canvas: Canvas, s: FrameState, target: Bitmap?) {
        if (!design.openingVisible) return
        val a = s.titleAlpha.toFloat()
        if (a <= 0.01f) return
        val o = design.scene(ClipDesign.OPENING)
        val title = o.text?.trim()?.takeIf { it.isNotEmpty() } ?: plan.title
        val dates = if (o.dates == false) null else plan.endCard.dates
        // Its own font, background and colour when the scene editor gave it some (else the clip's).
        val t = labelLook(ClipDesign.OPENING)
        val sc = design.typeScale(ClipDesign.OPENING)
        val b = block("title", dates, title, t, t.titlePaint, 30f * u * t.fontScale * sc, 3, note = design.note(ClipDesign.OPENING), sc = sc)
        val place = if (design.name == NamePlace.Bottom) NamePlace.Bottom else NamePlace.Top
        val box = textBox(ClipDesign.OPENING, b, place)
        hits[ClipDesign.OPENING] = SceneHit(box, layout.blockRangeX(b.width), layout.blockRangeY(b.height))
        drawBlock(canvas, b, t.titlePaint, box.top, a, target, t, box.centerX)
    }

    private val tagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = look.bodyStrong }

    /** A scene's tag stickers measured once ([drawTags]): each row's stickers (words and widths) and the block's size. */
    private class TagBlock(val rows: List<List<Pair<String, Float>>>, val rowWidths: FloatArray, val width: Float, val height: Float, val textSize: Float, val sc: Float)
    private val tagBlocks = HashMap<String, TagBlock?>()

    /**
     * The place [key]'s tags (its plan items', [ClipPlan.tags]) laid out in rows that fit the frame, or null without
     * tags or with them switched off; the same for every frame.
     */
    private fun tagBlock(key: String): TagBlock? = tagBlocks.getOrPut(key) {
        val tags = plan.tags[key].orEmpty()
        if (tags.isEmpty() || !design.tagsShown(key)) return@getOrPut null
        val sc = design.tagScale(key).coerceIn(ClipTags.SCALE) * type
        tagPaint.textSize = 13f * u * sc
        val padH = 8f * u * sc
        val gap = 5f * u * sc
        val maxW = width - 24f * u
        val rows = ArrayList<MutableList<Pair<String, Float>>>()
        val widths = ArrayList<Float>()
        for (t in tags) {
            val full = "#$t"
            val w = min(tagPaint.measureText(full) + 2 * padH, maxW)
            // Only a sticker wider than the frame loses its end.
            val text = if (w < maxW) full else TextUtils.ellipsize(full, tagPaint, w - 2 * padH, TextUtils.TruncateAt.END).toString()
            if (rows.isEmpty() || widths.last() + gap + w > maxW) { rows += mutableListOf(text to w); widths += w } else { rows.last() += text to w; widths[widths.lastIndex] += gap + w }
        }
        val chipH = tagPaint.textSize * 1.2f + 8f * u * sc
        TagBlock(rows, widths.toFloatArray(), widths.max(), rows.size * chipH + (rows.size - 1) * gap, tagPaint.textSize, sc)
    }

    /**
     * The scene [key]'s tags: "#tag" stickers in rows, under [under] (the text they belong to) or at their own spot,
     * kept inside the frame, at [a].
     */
    private fun drawTags(canvas: Canvas, key: String, under: FrameBox, a: Float) {
        if (a <= 0.01f) return
        val b = tagBlock(key) ?: return
        val o = design.scene(key)
        val color = design.tagColor(key)
        val padH = 8f * u * b.sc
        val padV = 4f * u * b.sc
        val gap = 5f * u * b.sc
        val edge = 12f * u
        val chipH = b.textSize * 1.2f + 2 * padV
        val cx = (o.tagX?.let { it * width } ?: under.centerX).coerceIn(edge + b.width / 2, max(edge + b.width / 2, width - edge - b.width / 2))
        var y = (o.tagY?.let { it * height - b.height / 2 } ?: (under.bottom + 6f * u)).coerceIn(edge, max(edge, height - edge - b.height))
        fill.shader = null
        fill.color = withAlpha(color.fill.toInt(), a.toDouble())
        tagPaint.textSize = b.textSize
        tagPaint.color = withAlpha(color.ink.toInt(), a.toDouble())
        b.rows.forEachIndexed { i, row ->
            var x = cx - b.rowWidths[i] / 2
            for ((text, w) in row) {
                rect.set(x, y, x + w, y + chipH)
                canvas.drawRoundRect(rect, chipH / 2, chipH / 2, fill)
                canvas.drawText(text, x + padH, y + padV - tagPaint.ascent() * 0.98f, tagPaint)
                x += w + gap
            }
            y += chipH + gap
        }
    }

    /** A measured block in the style [t], centred on [cx] (kept inside the frame), its top at [top]. */
    private fun drawBlock(canvas: Canvas, b: TextBlock, paint: TextPaint, top: Float, a: Float, target: Bitmap?, t: TextLook, cx: Float = width / 2f) {
        val edge = 8f * u
        val left = (cx - b.width / 2).coerceIn(min(edge, (width - b.width) / 2), max(width - edge - b.width, (width - b.width) / 2))
        val mid = left + b.width / 2
        rect.set(left, top, left + b.width, top + b.height)
        if (t.panelled) {
            val r = when (design.panel) {
                PanelShape.Rounded -> min(10f * u, b.height / 3)
                PanelShape.Pill -> b.height / 2
                PanelShape.Square -> 0f
            }
            if (t.glass && target != null) frost(canvas, target, rect, r, a)
            fill.color = withAlpha(t.panelColor, (if (t.glass) 0.55 else 1.0) * a)
            if (!t.glass) fill.setShadowLayer(8f * u, 0f, 2.5f * u, withAlpha(0xFF000000.toInt(), 0.22 * a))
            canvas.drawRoundRect(rect, r, r, fill)
            fill.clearShadowLayer()
            if (t.glass) {
                line.color = withAlpha(WHITE, 0.35 * a)
                line.strokeWidth = 0.8f * u
                canvas.drawRoundRect(rect, r, r, line)
            }
        }
        var y = top + b.padV
        b.eyebrow?.let { eyebrow ->
            prepareEyebrow(b.sc)
            text.color = withAlpha(t.inkColor, 0.78 * a)
            if (!t.panelled) text.setShadowLayer(3f * u, 0f, 0.8f * u, withAlpha(t.halo, a.toDouble()))
            val fm = text.fontMetrics
            canvas.drawText(eyebrow, mid - b.eyebrowW / 2, y - fm.ascent + (b.eyebrowH - (fm.descent - fm.ascent)) / 2, text)
            text.clearShadowLayer()
            text.letterSpacing = 0f
            y += b.eyebrowH + b.gap
        }
        paint.color = withAlpha(t.inkColor, a.toDouble())
        if (!t.panelled) paint.setShadowLayer(5f * u, 0f, 1.2f * u, withAlpha(t.halo, a.toDouble())) else paint.clearShadowLayer()
        canvas.save()
        canvas.translate(mid - b.main.width / 2f, y)
        b.main.draw(canvas)
        canvas.restore()
        paint.clearShadowLayer()
        y += b.main.height
        b.note?.let { note ->
            y += b.subGap
            notePaint.color = withAlpha(t.inkColor, 0.92 * a)
            if (!t.panelled) notePaint.setShadowLayer(3.5f * u, 0f, 1f * u, withAlpha(t.halo, a.toDouble())) else notePaint.clearShadowLayer()
            canvas.save()
            canvas.translate(mid - note.width / 2f, y)
            note.draw(canvas)
            canvas.restore()
            notePaint.clearShadowLayer()
            y += note.height
        }
        b.spot?.let { spot ->
            y += b.subGap
            prepareSpot(b.sc)
            text.color = withAlpha(t.inkColor, 0.72 * a)
            if (!t.panelled) text.setShadowLayer(3f * u, 0f, 0.8f * u, withAlpha(t.halo, a.toDouble()))
            val fm = text.fontMetrics
            canvas.drawText(spot, mid - b.spotW / 2, y - fm.ascent + (b.spotH - (fm.descent - fm.ascent)) / 2, text)
            text.clearShadowLayer()
            text.letterSpacing = 0f
        }
    }

    /** Frosted glass under a panel: the frame there, shrunk 12× in two steps and enlarged again (a cheap blur). */
    private fun frost(canvas: Canvas, target: Bitmap, box: RectF, radius: Float, a: Float) {
        val l = box.left.toInt().coerceIn(0, target.width - 1)
        val t = box.top.toInt().coerceIn(0, target.height - 1)
        val r = box.right.toInt().coerceIn(l + 1, target.width)
        val b = box.bottom.toInt().coerceIn(t + 1, target.height)
        val midW = max(2, (r - l) / 4)
        val midH = max(2, (b - t) / 4)
        val smallW = max(2, midW / 3)
        val smallH = max(2, midH / 3)
        val mid = frostMid?.takeIf { it.width == midW && it.height == midH }
            ?: Bitmap.createBitmap(midW, midH, Bitmap.Config.ARGB_8888).also { frostMid = it }
        val small = frostSmall?.takeIf { it.width == smallW && it.height == smallH }
            ?: Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888).also { frostSmall = it }
        frostSrc.set(l, t, r, b)
        frostDst.set(0, 0, midW, midH)
        bitmapPaint.alpha = 255
        Canvas(mid).drawBitmap(target, frostSrc, frostDst, bitmapPaint)
        frostDst.set(0, 0, smallW, smallH)
        Canvas(small).drawBitmap(mid, null, frostDst, bitmapPaint)
        clipPath.rewind()
        clipPath.addRoundRect(box, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        bitmapPaint.alpha = (a * 255).roundToInt()
        canvas.drawBitmap(small, null, box, bitmapPaint)
        bitmapPaint.alpha = 255
        canvas.restore()
    }

    // --- Stats line -----------------------------------------------------------------------------------------

    /** The stats line in its own style ([ClipDesign.statsFont], [ClipDesign.statsBack], [ClipDesign.statsColor]). */
    private val statsLook: TextLook by lazy { textLook(design.statsFont, design.statsBack, design.statsColor) }
    private val statsPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val locale: Locale = Locale.getDefault()

    /** A measured stats line: its words, its box, its padding and how it lines up in the box. */
    private class StatsLine(val text: String, val box: FrameBox, val padH: Float, val padV: Float, val align: Paint.Align, val alpha: Float)

    /**
     * The stats line for frame [s] (owner, 2026-11-09: "show some statistics like km or days"), or null when it doesn't
     * show: off, under the opening's title or a day card, or gone with the end card. It is placed by the line at the
     * journey's end (its widest), then fits the words it says now on the side it sits nearest, so that edge holds still
     * while the numbers count up.
     */
    private fun statsLine(s: FrameState): StatsLine? {
        if (!design.statsShown) return null
        if (plan.inserts.isNotEmpty() && plan.insertAt(s.frame)?.kind == InsertKind.DayCard) return null
        val opening = if (design.openingVisible) 1 - s.titleAlpha else 1.0
        val a = (opening * (1 - s.endAlpha)).toFloat().coerceIn(0f, 1f)
        if (a <= 0.01f) return null
        val t = statsLook
        statsPaint.typeface = t.face
        statsPaint.textSize = STATS_SIZE * u * t.fontScale * design.statsScale
        statsPaint.fontFeatureSettings = "tnum"
        val padH = if (t.panelled) 9f * u else 2f * u
        val padV = if (t.panelled) 5f * u else 1.5f * u
        val most = layout.blockMaxWidth - 2 * padH
        val now = t.display(ClipText.statsLine(plan.statsAt(s.frame), design.stats, locale))
        val widest = t.display(ClipText.statsLine(plan.statsAtEnd, design.stats, locale))
        val text = TextUtils.ellipsize(now, statsPaint, most, TextUtils.TruncateAt.END).toString()
        val wNow = min(statsPaint.measureText(text), most) + 2 * padH
        val wMost = max(wNow, min(statsPaint.measureText(widest), most) + 2 * padH)
        val fm = statsPaint.fontMetrics
        val h = fm.descent - fm.ascent + 2 * padV
        val spot = design.statsSpot
        val room = layout.statsBox(spot, wMost, h)
        val align = when {
            spot == null || spot.first < 0.4f -> Paint.Align.LEFT
            spot.first > 0.6f -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        val left = when (align) {
            Paint.Align.LEFT -> room.left
            Paint.Align.RIGHT -> room.right - wNow
            else -> room.centerX - wNow / 2
        }
        return StatsLine(text, FrameBox(left, room.top, left + wNow, room.bottom), padH, padV, align, a)
    }

    /** The stats line: its panel (or none, with a soft halo under the letters), the words on its side of the box. */
    private fun drawStats(canvas: Canvas, st: StatsLine, target: Bitmap?) {
        val t = statsLook
        val a = st.alpha
        val b = st.box
        rect.set(b.left, b.top, b.right, b.bottom)
        if (t.panelled) {
            val r = when (design.panel) {
                PanelShape.Rounded -> min(8f * u, b.height / 3)
                PanelShape.Pill -> b.height / 2
                PanelShape.Square -> 0f
            }
            if (t.glass && target != null) frost(canvas, target, rect, r, a)
            fill.color = withAlpha(t.panelColor, (if (t.glass) 0.55 else 1.0) * a)
            if (!t.glass) fill.setShadowLayer(6f * u, 0f, 2f * u, withAlpha(0xFF000000.toInt(), 0.2 * a))
            canvas.drawRoundRect(rect, r, r, fill)
            fill.clearShadowLayer()
            if (t.glass) {
                line.color = withAlpha(WHITE, 0.35 * a)
                line.strokeWidth = 0.8f * u
                canvas.drawRoundRect(rect, r, r, line)
            }
        }
        statsPaint.color = withAlpha(t.inkColor, a.toDouble())
        if (!t.panelled) statsPaint.setShadowLayer(4f * u, 0f, 1f * u, withAlpha(t.halo, a.toDouble())) else statsPaint.clearShadowLayer()
        val w = statsPaint.measureText(st.text)
        val x = when (st.align) {
            Paint.Align.LEFT -> b.left + st.padH
            Paint.Align.RIGHT -> b.right - st.padH - w
            else -> b.centerX - w / 2
        }
        canvas.drawText(st.text, x, b.top + st.padV - statsPaint.fontMetrics.ascent, statsPaint)
        statsPaint.clearShadowLayer()
    }

    /**
     * The app's mark ([PlaneMarkBitmap]: the navy-edged paper plane on the theme's yellow) at [alpha], over a
     * soft shadow of [shadow] opacity.
     */
    private fun drawMark(canvas: Canvas, left: Float, top: Float, size: Float, alpha: Float, shadow: Double) {
        rect.set(left, top, left + size, top + size)
        val r = size * PlaneMark.CORNER
        fill.color = withAlpha(look.mark.sky, alpha.toDouble())
        fill.setShadowLayer(size * 0.14f, 0f, size * 0.05f, withAlpha(0xFF000000.toInt(), shadow * alpha))
        canvas.drawRoundRect(rect, r, r, fill)
        fill.clearShadowLayer()
        if (alpha >= 0.995f) {
            PlaneMarkBitmap.draw(canvas, left, top, size, look.mark)
        } else {
            canvas.saveLayerAlpha(rect, (alpha * 255).roundToInt())
            PlaneMarkBitmap.draw(canvas, left, top, size, look.mark)
            canvas.restore()
        }
    }

    /** The close: the plane mark (free version), title, dates, days and distance, countries; the map credits small. */
    private fun drawEnd(canvas: Canvas, s: FrameState) {
        val a = s.endAlpha
        if (a <= 0.01) return
        fill.color = withAlpha(look.bg, 0.96 * a)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        val card = plan.endCard
        val tile = if (isPro) 0f else 56f * u
        val tileGap = if (isPro) 0f else 22f * u
        val datesH = if (card.dates != null) 26f * u else 0f
        val countriesH = if (card.countries != null) 24f * u else 0f
        val total = tile + tileGap + endTitleLayout.height + datesH + 18f * u + 22f * u + countriesH
        var y = (height - total) / 2 + 14f * u * (1 - a.toFloat())
        val cxs = width / 2f
        val ai = (a * 255).roundToInt()

        if (!isPro) drawMark(canvas, cxs - tile / 2, y, tile, a.toFloat(), shadow = 0.18)
        y += tile + tileGap

        endTitlePaint.color = look.ink
        endTitlePaint.alpha = ai
        canvas.save()
        canvas.translate((width - endTitleLayout.width) / 2f, y)
        endTitleLayout.draw(canvas)
        canvas.restore()
        y += endTitleLayout.height

        text.textAlign = Paint.Align.CENTER
        card.dates?.let {
            text.typeface = look.mono
            text.textSize = 13f * u
            text.color = withAlpha(look.muted, a)
            canvas.drawText(it.uppercase(), cxs, y + 20f * u, text)
            y += datesH
        }
        y += 18f * u
        text.typeface = look.bodyStrong
        text.textSize = 17f * u
        text.color = withAlpha(look.ink, a)
        // The closing line: the totals, or the traveller's own line (scene editor); the credits below always stay.
        val closing = design.scene(ClipDesign.END).text?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { TextUtils.ellipsize(it, text, width - 2 * layout.side, TextUtils.TruncateAt.END).toString() } ?: card.stats
        canvas.drawText(closing, cxs, y + 17f * u, text)
        y += 22f * u
        card.countries?.let {
            text.typeface = look.body
            text.textSize = 15f * u
            text.color = withAlpha(look.muted, a)
            canvas.drawText(it, cxs, y + 18f * u, text)
        }

        // The map's credits, small at the foot of the card.
        val lines = creditLines(width - 2 * layout.side)
        if (lines.isNotEmpty()) {
            text.typeface = look.body
            text.textSize = CREDIT_DP * u
            text.color = withAlpha(look.muted, 0.9 * a)
            val lineH = CREDIT_DP * 1.35f * u
            val bottom = height - layout.safeBottom
            lines.forEachIndexed { i, credit -> canvas.drawText(credit, cxs, bottom - (lines.size - 1 - i) * lineH, text) }
        }
        text.textAlign = Paint.Align.LEFT
    }

    // --- Inserts ----------------------------------------------------------------------------------------------------

    /** A full-frame insert over the still map, fading in and out quickly ([InsertFade]). */
    private fun drawInsert(canvas: Canvas, s: FrameState, insert: Insert) {
        val a = InsertFade.alpha(s.frame, insert.fromFrame, insert.toFrame, plan.fps)
        if (a <= 0.01f) return
        when (insert.kind) {
            InsertKind.Video -> drawVideo(canvas, insert, s.frame, a)
            InsertKind.DayCard -> drawDayCard(canvas, insert, a)
        }
    }

    /**
     * A day card in the clip's style: on panelled looks the panel's colour fills the frame with its ink (Postcard
     * white, Bold its accent), without panels a dark frame with light letters (Film, Minimal) or paper with dark ones;
     * "DAY 14" small, the place in the place name's face (or the traveller's own line), a short rule, the date.
     */
    private fun drawDayCard(canvas: Canvas, insert: Insert, a: Float) {
        val card = insert.card ?: return
        val t = labelLook(card.opens)
        val letters = if (t.panelled) t.inkColor else t.panelColor
        val back = when {
            t.panelled -> t.panelColor
            luminance(letters) > 0.5 -> NEAR_BLACK
            else -> look.bg
        }
        fill.color = withAlpha(back, a.toDouble())
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val o = design.scene(card.key)
        val own = o.text?.trim()?.takeIf { it.isNotEmpty() }
        val main = own ?: card.place ?: card.day ?: return
        val autoEyebrow = if (own == null && card.place == null) null else card.day
        val eyebrow = o.kicker?.let { k -> k.trim().takeIf { it.isNotEmpty() } } ?: if (o.kicker != null) null else autoEyebrow
        val note = design.note(card.key)?.let { n ->
            dayNotes.getOrPut("${card.key}\u0000${t.id}\u0000$n") {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                    typeface = look.body
                    textSize = 15f * u * type.coerceIn(0.9f, 1.2f)
                }
                CardTitle(centred(n, paint, 3, layout.blockMaxWidth), paint)
            }
        }
        val spot = design.spot(card.key)
        val title = dayCards.getOrPut("${card.key}\u0000${t.id}\u0000$main") {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = t.face
                textSize = 40f * u * t.fontScale * type
            }
            CardTitle(centred(t.display(main), paint, 2, layout.blockMaxWidth), paint)
        }
        val mainLayout = title.layout
        val eyebrowSize = 13f * u * type.coerceIn(0.9f, 1.15f)
        val dateSize = 12f * u
        val gap = 12f * u
        val ruleW = 28f * u
        val spotSize = 11f * u
        val total = (if (eyebrow != null) eyebrowSize * 1.3f + gap else 0f) + mainLayout.height + gap + 2f * u + gap + dateSize * 1.3f +
            (note?.let { gap + it.layout.height } ?: 0f) + (if (spot != null) gap * 0.6f + spotSize * 1.3f else 0f)
        var y = (height - total) / 2
        val cxs = width / 2f
        val ai = (a * 255).roundToInt()
        text.textAlign = Paint.Align.CENTER
        if (eyebrow != null) {
            prepareEyebrow()
            text.textAlign = Paint.Align.CENTER
            text.textSize = eyebrowSize
            text.color = withAlpha(letters, 0.8 * a)
            canvas.drawText(eyebrow.uppercase(), cxs, y + eyebrowSize, text)
            text.letterSpacing = 0f
            y += eyebrowSize * 1.3f + gap
        }
        title.paint.color = letters
        title.paint.alpha = ai
        canvas.save()
        canvas.translate((width - mainLayout.width) / 2f, y)
        mainLayout.draw(canvas)
        canvas.restore()
        y += mainLayout.height + gap
        fill.color = withAlpha(if (design.lineColor == LineColor.Accent || t.panelled) letters else lineColor, 0.85 * a)
        canvas.drawRect(cxs - ruleW / 2, y, cxs + ruleW / 2, y + 2f * u, fill)
        y += 2f * u + gap
        text.typeface = look.mono
        text.textSize = dateSize
        text.color = withAlpha(letters, 0.7 * a)
        canvas.drawText(com.justdataplease.boringtravel.util.Dates.dayLong(card.date).uppercase(), cxs, y + dateSize, text)
        y += dateSize * 1.3f
        if (note != null) {
            y += gap
            note.paint.color = letters
            note.paint.alpha = (ai * 0.92f).roundToInt()
            canvas.save()
            canvas.translate((width - note.layout.width) / 2f, y)
            note.layout.draw(canvas)
            canvas.restore()
            y += note.layout.height
        }
        if (spot != null) {
            y += gap * 0.6f
            text.typeface = look.mono
            text.textSize = spotSize
            text.color = withAlpha(letters, 0.65 * a)
            canvas.drawText(TextUtils.ellipsize(spot, text, layout.blockMaxWidth, TextUtils.TruncateAt.END).toString(), cxs, y + spotSize, text)
        }
        text.textAlign = Paint.Align.LEFT
    }

    /** Measured day card notes, by card, style and text. */
    private val dayNotes = HashMap<String, CardTitle>()

    /** A measured day card title with its own paint (a layout draws with its paint's size). */
    private class CardTitle(val layout: StaticLayout, val paint: TextPaint)

    /** Measured day card titles, by card, style and text. */
    private val dayCards = HashMap<String, CardTitle>()

    /** A phone video's picture, cropped to fill the frame (by its reader), in the clip's tone; dark until it comes. */
    private fun drawVideo(canvas: Canvas, insert: Insert, frame: Int, a: Float) {
        val id = insert.id ?: return
        val timeUs = (frame - insert.fromFrame).toLong() * 1_000_000L / plan.fps
        val bmp = videoReader.frame(id, timeUs, width, height)
        if (bmp == null) {
            fill.color = withAlpha(NEAR_BLACK, a.toDouble())
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            return
        }
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        photoPaint.alpha = (a * 255).roundToInt()
        canvas.drawBitmap(bmp, null, rect, photoPaint)
        photoPaint.alpha = 255
    }

    override fun close() = videoReader.close()

    // --- Finish -----------------------------------------------------------------------------------------------------

    /** Film's letterbox bars and grain, over everything (the texts keep clear of the bars, see [ClipLayout.bar]). */
    private fun drawFinish(canvas: Canvas, frame: Int) {
        if (layout.bar > 0f) {
            fill.color = NEAR_BLACK
            canvas.drawRect(0f, 0f, width.toFloat(), layout.bar, fill)
            canvas.drawRect(0f, height - layout.bar, width.toFloat(), height.toFloat(), fill)
        }
        grain?.draw(canvas, frame, width, height)
    }

    /**
     * A light film grain: a small tile of soft light and dark specks, repeated over the frame and shifted every frame
     * (the same shift for the same frame, so the preview and the export match).
     */
    private class Grain(u: Float) {
        private val shader: BitmapShader
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val shift = Matrix()
        private val scale = (u / 3f).coerceAtLeast(0.5f)

        init {
            val size = 128
            val random = java.util.Random(7L)
            val pixels = IntArray(size * size) {
                val v = random.nextGaussian()
                val a = (kotlin.math.abs(v) * 16).roundToInt().coerceIn(0, 60)
                (a shl 24) or (if (v > 0) 0xFFFFFF else 0x000000)
            }
            val tile = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
            shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            paint.shader = shader
        }

        fun draw(canvas: Canvas, frame: Int, width: Int, height: Int) {
            val h = (frame * -0x61c88647) ushr 7
            shift.setScale(scale, scale)
            shift.postTranslate((h and 127).toFloat() * scale, ((h shr 8) and 127).toFloat() * scale)
            shader.setLocalMatrix(shift)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    // --- Helpers ------------------------------------------------------------------------------------------------

    private var creditCache: Pair<Float, List<String>>? = null

    /** The map's credit lines ([ClipText.credits]), each wrapped at spaces to [maxWidth] (worked out once). */
    private fun creditLines(maxWidth: Float): List<String> {
        creditCache?.let { (w, lines) -> if (w == maxWidth) return lines }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = look.body; textSize = CREDIT_DP * u }
        val lines = ArrayList<String>()
        for (credit in ClipText.credits(base.credits)) {
            var line = ""
            for (word in credit.split(' ')) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && paint.measureText(next) > maxWidth) { lines += line; line = word } else line = next
            }
            if (line.isNotEmpty()) lines += line
        }
        creditCache = maxWidth to lines
        return lines
    }

    private fun blend(a: Int, b: Int, t: Double): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun centred(s: String, paint: TextPaint, maxLines: Int, maxWidth: Float): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, paint, maxWidth.roundToInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .build()

    private fun withAlpha(color: Int, alpha: Double): Int {
        val a = ((color ushr 24) * alpha.coerceIn(0.0, 1.0)).roundToInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun luminance(color: Int): Double {
        val r = (color shr 16 and 0xFF) / 255.0
        val g = (color shr 8 and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    internal companion object {
        /** The stats line's type size in design units at 100 % (small: a place name is 24). */
        const val STATS_SIZE = 13f

        /** Warm: reds and yellows up a little, blues down, a touch less colour (pictures only, never the texts). */
        fun toneFilter(tone: ClipTone): ColorMatrixColorFilter? = when (tone) {
            ClipTone.Natural -> null
            ClipTone.Warm -> ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(0.9f)
                postConcat(ColorMatrix(floatArrayOf(
                    1.06f, 0.03f, 0f, 0f, 6f,
                    0.01f, 1.0f, 0f, 0f, 2f,
                    0f, 0.02f, 0.86f, 0f, -4f,
                    0f, 0f, 0f, 1f, 0f,
                )))
            })
        }

        const val WHITE = 0xFFFFFFFF.toInt()
        const val NEAR_BLACK = 0xFF16161C.toInt()
        /** The end card's credit text size in design dp (≈ 22 px in a 1080 px wide clip). */
        const val CREDIT_DP = 7.5f
        /** The traveller's marker size in design dp (the vehicles' is 54). */
        const val TRAVELLER_DP = 62f

        /**
         * The marker's size for a view [spanX] wide (world units, the whole world 1): full up to [MARKER_FULL_SPAN]
         * (about 1,000 km across), down to [MARKER_MIN_SCALE] by [MARKER_SMALL_SPAN] (half the world, from space), even
         * steps of zoom between (a map-motion review: "from space the van covers Patagonia").
         */
        fun markerScale(spanX: Double): Double {
            if (!spanX.isFinite() || spanX <= MARKER_FULL_SPAN) return 1.0
            val t = (ln(spanX / MARKER_FULL_SPAN) / ln(MARKER_SMALL_SPAN / MARKER_FULL_SPAN)).coerceIn(0.0, 1.0)
            return 1.0 - (1.0 - MARKER_MIN_SCALE) * t
        }

        const val MARKER_FULL_SPAN = 0.025
        const val MARKER_SMALL_SPAN = 0.5
        const val MARKER_MIN_SCALE = 0.6
    }
}
