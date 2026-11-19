package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.justdataplease.boringtravel.art.SceneKind
import com.justdataplease.boringtravel.art.SceneMath
import com.justdataplease.boringtravel.art.ScenePalette
import com.justdataplease.boringtravel.art.ScenePalettes
import com.justdataplease.boringtravel.art.ScenePick
import com.justdataplease.boringtravel.art.SceneShapes
import com.justdataplease.boringtravel.art.SvgPath
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.Look
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import com.justdataplease.boringtravel.art.Shape as SceneShape

/*
 * Drawn pictures, wherever there is no real photo (a trip without a cover, a note without photos, a trip idea), as the
 * Android app draws them (ui/components/Pictures.kt, art/SceneArt.kt): one of ten kinds of place in the current
 * theme's colours. The shapes, the palettes and which kind a trip or place gets are the Android app's own files
 * (art/SceneShapes.kt, art/Scenes.kt, compiled here); this file only paints them, with Compose's drawing instead of
 * android.graphics. A real photo never changes; a drawn one redraws when the theme changes.
 */

/** The one place a theme picks its drawn pictures' colours (the board's palettes share the themes' ids). */
fun Look.scenePalette(): ScenePalette = when (this) {
    Look.PostcardPop -> ScenePalettes.pop
    Look.Trail -> ScenePalettes.trail
    Look.StickerBook -> ScenePalettes.sticker
    Look.Sketchbook -> ScenePalettes.sketch
    Look.Market -> ScenePalettes.market
}

/**
 * A drawn picture of [kind] filling the given size (centred and cropped), no frame. [key] (a trip or entry id) mirrors
 * it for about half of them, so two trips of one kind don't look the same. Decorative unless [description].
 */
@Composable
fun DrawnScene(
    kind: SceneKind,
    modifier: Modifier = Modifier,
    key: String? = null,
    palette: ScenePalette = Bt.tokens.look.scenePalette(),
    mirror: Boolean = key?.let(ScenePick::mirrorFor) ?: false,
    description: String? = null,
) {
    val labelled = if (description != null) Modifier.semantics { contentDescription = description; role = Role.Image } else Modifier
    Spacer(
        modifier.then(labelled).drawWithCache {
            val paths = scenePaths(kind, palette)
            onDrawBehind { drawScene(paths, palette, mirror) }
        },
    )
}

/** A trip's drawn picture: from its name, then its country (ISO code), then its id. */
fun sceneFor(trip: Trip, country: String? = null): SceneKind = ScenePick.forTrip(trip.id, trip.title, trip.subtitle, country)

/** A note's drawn picture: from its place, then its title and text, then where it was written. */
fun sceneFor(entry: Entry, country: String? = null): SceneKind =
    ScenePick.forPlace(entry.placeName, "${entry.title} ${entry.plainText}", country, entry.lat, entry.lng, key = entry.id)

/** A place's drawn picture: from its name, then the country, then the spot on the map. */
fun sceneFor(placeName: String, country: String? = null, lat: Double? = null, lng: Double? = null): SceneKind =
    ScenePick.forPlace(placeName, country = country, lat = lat, lng = lng)

/** One shape ready to paint: its path (null for a line), fill and outline. */
private class Painted(val shape: SceneShape, val path: Path?, val fill: Color?, val outline: Pair<Color, Float>?)

private fun colour(hex: String): Color = Color((0xFF000000 or hex.removePrefix("#").toLong(16)).toInt())

private val parsed = HashMap<String, List<SvgPath.Op>>()

/** Every shape of [kind] in [palette], as paths on the 240×160 canvas. */
private fun scenePaths(kind: SceneKind, palette: ScenePalette): List<Painted> = SceneShapes.painted(kind, palette).map { shape ->
    val outline = SceneShapes.strokeOf(shape, palette)?.let { (c, w) -> colour(c) to w }
    val path = when (shape) {
        is SceneShape.Rect -> Path().apply { addRect(androidx.compose.ui.geometry.Rect(shape.x, shape.y, shape.x + shape.w, shape.y + shape.h)) }
        is SceneShape.Circle -> Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(shape.cx, shape.cy), shape.r)) }
        is SceneShape.Poly -> Path().apply {
            shape.pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
            close()
        }
        is SceneShape.PathD -> Path().apply {
            for (op in parsed.getOrPut(shape.d) { SvgPath.parse(shape.d) }) when (op) {
                is SvgPath.Op.Move -> moveTo(op.x, op.y)
                is SvgPath.Op.LineTo -> lineTo(op.x, op.y)
                is SvgPath.Op.Cubic -> cubicTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
                SvgPath.Op.Close -> close()
            }
        }
        is SceneShape.Line -> null
    }
    Painted(shape, path, shape.fill?.let(::colour), outline)
}

/** Paints [shapes] filling this size, centred and cropped (the Android app's SceneArt.draw). */
private fun DrawScope.drawScene(shapes: List<Painted>, palette: ScenePalette, mirror: Boolean) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val fit = SceneMath.slice(w, h)
    clipRect(0f, 0f, w, h) {
        translate(fit.dx, fit.dy) {
            scale(fit.scale, fit.scale, pivot = Offset.Zero) {
                scale(if (mirror) -1f else 1f, 1f, pivot = Offset(SceneMath.VIEW_W / 2f, SceneMath.VIEW_H / 2f)) {
                    SceneShapes.background(palette)?.let { drawRect(colour(it), Offset.Zero, Size(SceneMath.VIEW_W, SceneMath.VIEW_H)) }
                    for (p in shapes) {
                        val shape = p.shape
                        if (shape is SceneShape.Line) {
                            p.outline?.let { (c, width) -> drawLine(c, Offset(shape.x1, shape.y1), Offset(shape.x2, shape.y2), width, cap = StrokeCap.Round) }
                            continue
                        }
                        val path = p.path ?: continue
                        p.fill?.let { drawPath(path, it) }
                        p.outline?.let { (c, width) -> drawPath(path, c, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                    }
                }
            }
        }
    }
}

/**
 * The demo's drawn photos, as the Android app's art/DemoArt.kt keeps them: a JPEG under media/ whose name says what it
 * shows ("media/demo-lake-m-<id>.jpg": kind, mirrored or not), so the screens draw it live in the current theme
 * ([live], read by [MediaImage]) while a backup carries the file, drawn in Postcard Pop.
 */
object DemoArt {
    const val QUALITY = 88

    data class Picture(val kind: SceneKind, val shape: SceneMath.Shape = SceneMath.Shape.Wide, val mirror: Boolean = false) {
        val size: SceneMath.Size get() = SceneMath.pixelSize(shape)
    }

    /** What a demo file shows, read back from its name. */
    data class Live(val kind: SceneKind, val mirror: Boolean)

    private val name = Regex("""(?:^|/)demo-([a-z]+)-([mn])-[^/]+\.jpg$""")

    /** Where a demo picture is stored: `media/demo-<kind>-<m|n>-<id>.jpg`. */
    fun localPath(picture: Picture, id: String): String = "media/demo-${picture.kind.id}-${if (picture.mirror) "m" else "n"}-$id.jpg"

    /** The drawn picture behind a stored file, or null for every real photo. */
    fun live(localPath: String?): Live? {
        val m = name.find(localPath ?: return null) ?: return null
        val kind = SceneKind.of(m.groupValues[1]) ?: return null
        return Live(kind, m.groupValues[2] == "m")
    }

    /** [picture] as a JPEG in [palette] (Postcard Pop by default), drawn with Skia; null when it couldn't be made. */
    fun jpeg(picture: Picture, palette: ScenePalette = ScenePalettes.default): ByteArray? = runCatching {
        val size = picture.size
        val bitmap = ImageBitmap(size.width, size.height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), Size(size.width.toFloat(), size.height.toFloat())) {
            drawScene(scenePaths(picture.kind, palette), palette, picture.mirror)
        }
        val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
        try {
            image.encodeToData(EncodedImageFormat.JPEG, QUALITY)?.bytes
        } finally {
            image.close()
        }
    }.getOrNull()
}
