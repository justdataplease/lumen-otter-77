package com.justdataplease.boringtravel.ui.export

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.ui.components.MediaImage
import com.justdataplease.boringtravel.ui.theme.Bt

/*
 * Small pages that look like the three print themes (design canvas, "Export"). The sheets use the print themes'
 * own fixed colours, so they look the same in every app look; the frame around the chosen one is the look's route
 * colour. The trip's own photos fill the picture spots when there are any.
 */

private val PaperWhite = Color(0xFFFFFFFF)
private val ScrapPaper = Color(0xFFFCFCF9)
private val Navy = Color(0xFF1E2A5A)
private val LineGrey = Color(0xFFC9CDD3)
private val LineDark = Color(0xFF3A3F45)
private val StampRed = Color(0xFFC4423A)
private val Tape = Color(0xD9F7E36A)
private val PaperTape = Color(0xDDE8E0C4)
private val RedPen = Color(0xFFC4423A)

private fun stops(vararg s: Pair<Float, Long>) = Brush.verticalGradient(colorStops = s.map { it.first to Color(it.second) }.toTypedArray())

private val sea = stops(0f to 0xFFC4E5F5, .36f to 0xFF93CDEA, .37f to 0xFF2E88BA, .63f to 0xFF1F6E9C, .64f to 0xFFEAD8A9, 1f to 0xFFD9C08A)
private val hill = stops(0f to 0xFFF7DDB6, .6f to 0xFFEFB88E, .61f to 0xFF7E9A69, 1f to 0xFF5D7B54)
private val town = stops(0f to 0xFF9DD0EE, .44f to 0xFF9DD0EE, .45f to 0xFFFDFDFB, .77f to 0xFFE3E9EC, .78f to 0xFF2F6FA3, .82f to 0xFF2F6FA3, .83f to 0xFFE8E2D2, 1f to 0xFFE8E2D2)
private val dusk = stops(0f to 0xFF2B3A67, .46f to 0xFFB5577A, .7f to 0xFFF6A96B, .71f to 0xFF3C3141, 1f to 0xFF231D2A)

@Composable
fun ThemeThumbs(selected: PrintTheme, photos: List<Media>, onSelect: (PrintTheme) -> Unit, modifier: Modifier = Modifier) {
    val c = Bt.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PrintTheme.entries.forEach { t ->
            val on = t == selected
            Column(
                Modifier.weight(1f).selectable(on, role = Role.RadioButton) { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.border(2.dp, if (on) c.route else Color.Transparent, RoundedCornerShape(7.dp)).padding(5.dp)) {
                    MiniSheet(t, photos, Modifier.widthIn(max = 92.dp).fillMaxWidth().aspectRatio(92f / 128f))
                }
                Text(
                    t.label,
                    style = Bt.text.label.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (on) c.ink else c.muted,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One mini page, laid out in the canvas's 92 × 128 units. */
@Composable
private fun MiniSheet(theme: PrintTheme, photos: List<Media>, modifier: Modifier) {
    val shape = RoundedCornerShape(3.dp)
    BoxWithConstraints(
        modifier
            .shadow(5.dp, shape)
            .clip(shape)
            .background(if (theme == PrintTheme.Scrapbook || theme == PrintTheme.Notebook) ScrapPaper else PaperWhite)
            .border(1.dp, Bt.colors.line, shape),
    ) {
        val u = maxWidth / 92f
        fun at(x: Float, y: Float, w: Float, h: Float) = Modifier.offset(u * x, u * y).size(u * w, u * h)
        when (theme) {
            PrintTheme.Notebook -> {
                // Dotted paper, a taped white print, the red ink stamp, a handwritten title and the route in red pen.
                Box(Modifier.matchParentSize().drawBehind {
                    val step = 5.dp.toPx()
                    var y = step / 2
                    while (y < size.height) {
                        var x = step / 2
                        while (x < size.width) { drawCircle(Navy.copy(alpha = 0.16f), 0.45.dp.toPx(), Offset(x, y)); x += step }
                        y += step
                    }
                })
                Print(photos.getOrNull(0), sea, at(9f, 9f, 70f, 52f), -3f, u)
                Box(at(13f, 6f, 16f, 6f).rotate(-34f).background(PaperTape))
                Box(at(60f, 6f, 16f, 6f).rotate(32f).background(PaperTape))
                Box(at(60f, 48f, 21f, 21f).rotate(-12f).drawBehind {
                    val r = size.minDimension / 2
                    drawCircle(RedPen, r - 1.dp.toPx(), style = Stroke(width = 1.4.dp.toPx()))
                    drawCircle(RedPen, r * 0.72f, style = Stroke(width = 0.6.dp.toPx()))
                    drawLine(RedPen, Offset(size.width * 0.28f, size.height * 0.56f), Offset(size.width * 0.72f, size.height * 0.56f), 1.dp.toPx())
                })
                Line(u, 76f, 50f, color = Navy, height = 6f)
                Box(at(10f, 84f, 24f, 4f).background(Color(0x99F7E36A)))
                Box(at(10f, 96f, 72f, 22f).drawBehind {
                    val p = Path().apply {
                        moveTo(size.width * 0.03f, size.height * 0.7f)
                        quadraticTo(size.width * 0.25f, size.height * 0.05f, size.width * 0.48f, size.height * 0.45f)
                        quadraticTo(size.width * 0.7f, size.height * 0.85f, size.width * 0.95f, size.height * 0.25f)
                    }
                    drawPath(p, RedPen, style = Stroke(width = 1.1.dp.toPx(), cap = StrokeCap.Round))
                    drawCircle(RedPen, 1.6.dp.toPx(), Offset(size.width * 0.95f, size.height * 0.25f))
                })
            }
            PrintTheme.Postcard -> {
                Photo(photos.getOrNull(0), sea, at(8f, 8f, 76f, 56f).clip(RoundedCornerShape(2.dp)))
                Box(
                    at(66f, 12f, 14f, 16f).background(PaperWhite).drawBehind {
                        drawRect(StampRed, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.5.dp.toPx()))))
                    },
                )
                Line(u, 74f, 50f, dark = true)
                Line(u, 86f, 70f)
                Line(u, 94f, 64f)
                Line(u, 102f, 68f)
            }
            PrintTheme.Scrapbook -> {
                Box(Modifier.matchParentSize().drawBehind {
                    val step = 6.dp.toPx()
                    var y = step / 2
                    while (y < size.height) {
                        var x = step / 2
                        while (x < size.width) { drawCircle(Navy.copy(alpha = 0.13f), 0.5.dp.toPx(), Offset(x, y)); x += step }
                        y += step
                    }
                })
                Polaroid(photos.getOrNull(0), hill, at(10f, 12f, 50f, 52f), -6f, u)
                Polaroid(photos.getOrNull(1), sea, at(37f, 40f, 46f, 48f), 5f, u)
                Box(at(20f, 8f, 22f, 8f).rotate(-12f).background(Tape))
                Line(u, 90f, 44f, color = Navy, height = 5f)
                Line(u, 102f, 70f)
                Line(u, 110f, 60f)
            }
            PrintTheme.Minimal -> {
                val fills = listOf(town, sea, hill, dusk)
                for (i in 0 until 4) {
                    val x = 8f + (i % 2) * 39.5f
                    val y = 8f + (i / 2) * 37f
                    Photo(photos.getOrNull(i), fills[i], at(x, y, 36.5f, 34f))
                }
                Line(u, 86f, 36f, dark = true)
                Line(u, 98f, 70f)
                Line(u, 106f, 56f)
            }
        }
    }
}

@Composable
private fun Photo(media: Media?, fallback: Brush, modifier: Modifier) {
    if (media != null) MediaImage(media, modifier, contentDescription = "Trip photo", playBadge = false) else Box(modifier.background(fallback))
}

/** A white print with an even border, tilted (Notebook). */
@Composable
private fun Print(media: Media?, fallback: Brush, modifier: Modifier, angle: Float, u: Dp) {
    Box(modifier.rotate(angle).shadow(1.5.dp, RectangleShape).background(PaperWhite).padding(u * 2.6f)) {
        Photo(media, fallback, Modifier.matchParentSize())
    }
}

@Composable
private fun Polaroid(media: Media?, fallback: Brush, modifier: Modifier, angle: Float, u: Dp) {
    Box(modifier.rotate(angle).shadow(2.dp, RectangleShape).background(PaperWhite).padding(start = u * 3, top = u * 3, end = u * 3, bottom = u * 9)) {
        Photo(media, fallback, Modifier.matchParentSize())
    }
}

@Composable
private fun Line(u: Dp, top: Float, width: Float, dark: Boolean = false, color: Color? = null, height: Float? = null) {
    val h = height ?: if (dark) 5f else 3f
    Box(
        Modifier
            .offset(u * 10, u * top)
            .size(u * width, u * h)
            .clip(RoundedCornerShape(2.dp))
            .background(color ?: if (dark) LineDark else LineGrey),
    )
}
