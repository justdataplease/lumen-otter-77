package com.justdataplease.boringtravel.world

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.globe.FlagBadges
import com.justdataplease.boringtravel.globe.GlobeGeometry
import com.justdataplease.boringtravel.globe.GlobeMath
import com.justdataplease.boringtravel.globe.GlobePalette
import com.justdataplease.boringtravel.globe.GlobePin
import com.justdataplease.boringtravel.globe.GlobeRenderer
import com.justdataplease.boringtravel.ui.components.drawPlaneMark
import com.justdataplease.boringtravel.ui.theme.BtTokens
import com.justdataplease.boringtravel.ui.theme.Paper
import com.justdataplease.boringtravel.ui.theme.planeMark
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The "your world" card for stories and messages, as the Android app's world/ShareCard.kt draws it: 9:16, drawn off
 * screen in the current theme (its paper and faces). The globe turned to your countries, the count, a line of numbers,
 * your flags and the brand mark. Drawn here with Compose's canvas (Skia); sharing and saving go through the phone
 * (ios/Phone.kt).
 */
object ShareCard {
    const val WIDTH = 1080
    const val HEIGHT = 1920

    /** The headline follows the count: "No countries yet", "1 country", "7 countries". */
    fun headline(countries: Int): String = when (countries) {
        0 -> "No countries yet"
        1 -> "1 country"
        else -> "$countries countries"
    }

    /** "4% of the world · 2 continents"; with none yet, the next country if one is planned. */
    fun line(world: World, nameOf: (String) -> String = { CountryInfo.name(it) }): String {
        if (world.countries == 0) return world.nextCodes.firstOrNull()?.let { "Next: ${nameOf(it)}" } ?: ""
        val k = world.continents.size
        return "${world.worldPercent}% of the world · $k ${if (k == 1) "continent" else "continents"}"
    }

    /** The theme's three faces the card writes in: its titles, its text and its mono (Bt.text's h1, body and mono). */
    data class Faces(val display: TextStyle, val body: TextStyle, val mono: TextStyle)

    /** The card as a picture of [WIDTH] × [HEIGHT]; [text] lays out its words and flags. */
    fun render(tokens: BtTokens, world: World, geometry: GlobeGeometry?, text: TextMeasurer, faces: Faces): ImageBitmap {
        val bmp = ImageBitmap(WIDTH, HEIGHT)
        val unit = WIDTH / 360f // one design dp on the card
        val cardDensity = Density(unit)
        val c = tokens.colors
        val w = WIDTH.toFloat()
        CanvasDrawScope().draw(cardDensity, LayoutDirection.Ltr, Canvas(bmp), Size(w, HEIGHT.toFloat())) {
            drawRect(c.bg)
            paper(tokens, unit)

            // The globe, turned to the traveller's countries.
            val palette = GlobePalette.from(tokens)
            if (geometry != null) {
                val renderer = GlobeRenderer(geometry, unit, pinSizeDp = 24f)
                renderer.showLabels = false
                renderer.setPalette(palette, text, faces.body.copy(fontSize = GlobeRenderer.LABEL_SP.sp))
                renderer.setCountries(world.visitedCodes, world.planned + world.wished)
                renderer.setPins(
                    world.visited.mapNotNull { s ->
                        val p = CountryInfo.labelPoint(s.code) ?: s.ownCenter?.let { it.lat to it.lng } ?: return@mapNotNull null
                        GlobePin(s.code, p.first, p.second, "")
                    },
                )
                val points = world.visited.mapNotNull { CountryInfo.labelPoint(it.code) }
                val (lat, lng) = GlobeMath.meanPoint(points)?.let { (it.first - 6.0).coerceIn(-50.0, 60.0) to it.second } ?: (36.0 to 8.0)
                renderer.draw(this, lng, lat, w / 2f, 178f * unit, 132f * unit, w, HEIGHT.toFloat())
            }

            // The title size relative to the theme's screen titles (Caveat 44 -> 46 on the card).
            val head = headline(world.countries).let { if (tokens.type.displayUpper) it.uppercase() else it }
            centred(text, head, faces.display.copy(fontSize = (46f * (tokens.type.h1.value / 44f)).sp, color = c.ink, lineHeight = 1.2.em), cardDensity, w / 2f, 400f * unit)
            val sub = line(world)
            if (sub.isNotEmpty()) centred(text, sub, faces.body.copy(fontSize = 13.sp, color = c.muted, lineHeight = 1.3.em), cardDensity, w / 2f, 432f * unit)

            flags(world, tokens, palette, unit, top = 456f * unit, text, faces.mono, cardDensity)

            // The logo, on the theme's yellow, and the name at the foot.
            val name = "BORINGTRAVEL"
            val nameStyle = faces.mono.copy(fontSize = 12.sp, letterSpacing = 0.08.em, color = c.ink, lineHeight = 1.2.em)
            val nameLayout = text.measure(AnnotatedString(name), nameStyle, density = cardDensity, softWrap = false)
            val markSize = (20f * unit).toInt()
            val gap = 8f * unit
            val total = markSize + gap + nameLayout.size.width
            val left = (w - total) / 2f
            val baseY = HEIGHT - 26f * unit
            val mark = ImageBitmap(markSize, markSize)
            CanvasDrawScope().draw(cardDensity, LayoutDirection.Ltr, Canvas(mark), Size(markSize.toFloat(), markSize.toFloat())) { drawPlaneMark(c.planeMark()) }
            drawImage(mark, IntOffset.Zero, androidx.compose.ui.unit.IntSize(markSize, markSize), dstOffset = IntOffset(left.toInt(), (baseY - markSize * 0.82f).toInt()))
            drawText(nameLayout, topLeft = Offset(left + markSize + gap, baseY - nameLayout.firstBaseline))
        }
        return bmp
    }

    /** The card as PNG bytes, for the share sheet and the photo library. */
    fun png(card: ImageBitmap): ByteArray = Image.makeFromBitmap(card.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: ByteArray(0)

    /** [s] centred on [x] with its baseline at [baseline]. */
    private fun DrawScope.centred(text: TextMeasurer, s: String, style: TextStyle, density: Density, x: Float, baseline: Float) {
        val layout = text.measure(AnnotatedString(s), style, density = density, softWrap = false)
        drawText(layout, topLeft = Offset(x - layout.size.width / 2f, baseline - layout.firstBaseline))
    }

    private fun DrawScope.paper(tokens: BtTokens, unit: Float) {
        val dot = tokens.colors.paperDot ?: return
        when (tokens.paper) {
            Paper.Dots -> {
                // The page's own dots: 16 dp on Sticker Book, Market's fine 9 dp kraft fibres.
                val step = tokens.paperStep.value * unit
                val r = tokens.paperDotRadius.value * unit
                val points = ArrayList<Offset>()
                var y = step / 2
                while (y < HEIGHT) {
                    var x = step / 2
                    while (x < WIDTH) { points += Offset(x, y); x += step }
                    y += step
                }
                drawPoints(points, PointMode.Points, dot, strokeWidth = 2 * r, cap = StrokeCap.Round)
            }
            Paper.Lines -> {
                val step = tokens.paperStep.value * unit
                var y = step
                while (y < HEIGHT) { drawLine(dot, Offset(0f, y), Offset(WIDTH.toFloat(), y), strokeWidth = 0.8f * unit); y += step }
                tokens.margin?.let { m -> drawLine(m, Offset(14f * unit, 0f), Offset(14f * unit, HEIGHT.toFloat()), strokeWidth = 1.2f * unit) }
            }
            Paper.Grid -> {
                val step = 18f * unit
                var y = step
                while (y < HEIGHT) { drawLine(dot, Offset(0f, y), Offset(WIDTH.toFloat(), y), strokeWidth = 0.8f * unit); y += step }
                var x = step
                while (x < WIDTH) { drawLine(dot, Offset(x, 0f), Offset(x, HEIGHT.toFloat()), strokeWidth = 0.8f * unit); x += step }
            }
            Paper.Plain -> Unit
        }
    }

    /** Up to two rows of flags; past that, "+N". */
    private fun DrawScope.flags(world: World, tokens: BtTokens, palette: GlobePalette, unit: Float, top: Float, text: TextMeasurer, mono: TextStyle, density: Density) {
        val codes = world.visited.map { it.code }
        if (codes.isEmpty()) return
        val perRow = 8
        val size = (26f * unit).toInt()
        val gap = 6f * unit
        val maxShown = perRow * 2
        val shown = if (codes.size > maxShown) codes.take(maxShown - 1) else codes
        val extra = codes.size - shown.size
        val spec = FlagBadges.Spec(size, 2f * unit, 1.5f * unit, palette.pinTilt, palette.chip, palette.ink)
        val margin = FlagBadges.margin(spec)
        val slots = shown.size + if (extra > 0) 1 else 0
        val rows = (slots + perRow - 1) / perRow
        val more = mono.copy(fontSize = 10.sp, color = tokens.colors.ink, lineHeight = 1.2.em)
        var i = 0
        for (r in 0 until rows) {
            val inRow = minOf(perRow, slots - r * perRow)
            val rowW = inRow * size + (inRow - 1) * gap
            var x = (WIDTH - rowW) / 2f
            val y = top + r * (size + gap)
            repeat(inRow) {
                if (i < shown.size) {
                    val b = FlagBadges.badge(shown[i], spec, text)
                    drawImage(b, Offset(x - margin, y - margin))
                } else {
                    val layout = text.measure(AnnotatedString("+$extra"), more, density = density, softWrap = false)
                    drawText(layout, topLeft = Offset(x + size / 2f - layout.size.width / 2f, y + size / 2f - layout.size.height / 2f))
                }
                i++
                x += size + gap
            }
        }
    }
}
