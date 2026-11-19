package com.justdataplease.boringtravel.ui.place

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.clip.Geo
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.ui.components.DrawnScene
import com.justdataplease.boringtravel.ui.components.MediaImage
import com.justdataplease.boringtravel.ui.components.copyOnLongPress
import com.justdataplease.boringtravel.ui.components.sceneFor
import com.justdataplease.boringtravel.ui.components.highlighter
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.world.FlagSticker
import kotlin.math.PI
import kotlin.math.cos

/** What every hero needs: the place's name, country, day line, pictures and the plan items to label things with. */
internal class HeroData(
    val name: String,
    val countryCode: String?,
    val countryName: String?,
    /** "Days 13–16", or null when the trip has no dates or the place isn't in the plan. */
    val daysLabel: String?,
    /** "Days 13–16 · 6 places" for the Trail and poster lines. */
    val planLine: String?,
    /** User photos taken near the place, newest first. */
    val photos: List<Media>,
    val sights: List<PlanItem>,
    /** The located items, for the Sketchbook's map. */
    val located: List<PlanItem>,
    /** The sticky note's second line ("Plane on to Santiago, day 28"), or null. */
    val onward: String?,
    /** The rubber stamp's two lines (Market), e.g. "CARRUSEL PARADE" / "SAT 6 MAR". */
    val stamp: Pair<String, String>?,
    /** Where the place is (the anchor), for its drawn picture. */
    val lat: Double,
    val lng: Double,
)

/** The hero for the current theme, edge to edge; it leaves room at the top for the floating back and share buttons. */
@Composable
internal fun PlaceHero(style: PlaceHeroStyle, data: HeroData) {
    when (style) {
        PlaceHeroStyle.Postcard -> PostcardHero(data)
        PlaceHeroStyle.Trail -> TrailHero(data)
        PlaceHeroStyle.Sticker -> StickerHero(data)
        PlaceHeroStyle.Sketch -> SketchHero(data)
        PlaceHeroStyle.Market -> MarketHero(data)
    }
}

/**
 * The hero picture: the traveller's photo near the place, else the drawn picture of [name] (its kind from the name,
 * the country and the spot, in the theme's colours; ui/components/Pictures.kt).
 */
@Composable
internal fun PlacePicture(photo: Media?, name: String, data: HeroData, modifier: Modifier = Modifier, lat: Double = data.lat, lng: Double = data.lng) {
    if (photo != null) MediaImage(photo, modifier, contentDescription = "Photo")
    else {
        val kind = remember(name, data.countryCode, lat, lng) { sceneFor(name, data.countryCode, lat, lng) }
        DrawnScene(kind, modifier, key = name)
    }
}

/** The flag and the country (and the day line when [withDays]): "🇦🇷 Argentina · days 8–12". */
@Composable
internal fun CountryLine(data: HeroData, color: Color = Bt.colors.ink, withDays: Boolean = true) {
    val code = data.countryCode ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FlagSticker(code, 18.dp, tilt = false)
        val text = listOfNotNull(data.countryName, data.daysLabel?.lowercase()?.takeIf { withDays }).joinToString(" · ")
        Text(text, style = Bt.text.item.copy(fontSize = 14.sp), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The place's name, large, in the display face; a long press copies it. */
@Composable
internal fun PlaceName(name: String, modifier: Modifier = Modifier, color: Color = Bt.colors.ink, scale: Float = 1.22f) {
    val base = Bt.text.h1
    Text(
        Bt.text.display(name),
        modifier.copyOnLongPress(name, "Name"),
        style = base.copy(fontSize = base.fontSize * scale, lineHeight = base.fontSize * scale * 1.02f),
        color = color, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

/** A hand-drawn wavy line under a title. */
@Composable
internal fun WavyUnderline(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(10.dp)) {
        val path = Path()
        val amp = size.height * 0.3f
        val mid = size.height / 2
        val step = 18.dp.toPx()
        path.moveTo(0f, mid)
        var x = 0f
        while (x < size.width) {
            path.quadraticTo(x + step / 4, mid - amp, x + step / 2, mid)
            path.quadraticTo(x + step * 3 / 4, mid + amp, x + step, mid)
            x += step
        }
        drawPath(path, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

private val TOP_ROOM = 64.dp

/** Dark ink on light fills, white on dark ones, so labels stay readable in every theme. */
internal fun readableOn(fill: Color): Color = if (fill.luminance() > 0.45f) Color(0xFF1C1B22) else Color.White

// ---------------------------------------------------------------- Postcard Pop

/** A white postcard, tilted a little, with the red ink stamp pressed on its corner as a postmark. */
@Composable
private fun PostcardHero(data: HeroData) {
    val c = Bt.colors
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = TOP_ROOM, start = 20.dp, end = 20.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().padding(top = 6.dp).rotate(-1.5f)
                    .shadow(10.dp, RectangleShape).background(Color.White).padding(7.dp),
            ) {
                PlacePicture(data.photos.firstOrNull(), data.name, data, Modifier.fillMaxWidth().height(196.dp))
            }
            Postmark(c.route, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp).size(80.dp).rotate(-14f))
        }
        Spacer(Modifier.height(16.dp))
        CountryLine(data, withDays = false)
        PlaceName(data.name, Modifier.padding(top = 4.dp))
        WavyUnderline(c.route, Modifier.width(150.dp))
        Labels(data.sights)
    }
}

/** Rotated block labels in the theme's strong colours ("LA BOCA", "SAN TELMO", "TANGO"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Labels(sights: List<PlanItem>) {
    if (sights.isEmpty()) return
    val c = Bt.colors
    val fills = listOf(c.route to readableOn(c.route), c.plan to readableOn(c.plan), c.ink to c.bg)
    FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sights.take(3).forEachIndexed { i, item ->
            val (bg, ink) = fills[i % fills.size]
            Text(
                PlaceGrouping.sticker(item.title).uppercase(),
                Modifier.rotate(listOf(-4f, 3f, -2f)[i % 3]).background(bg).padding(horizontal = 10.dp, vertical = 6.dp),
                style = Bt.text.label.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.06.em),
                color = ink, maxLines = 1,
            )
        }
    }
}

/** The app's red ink stamp as a postmark: two rings, a half sun and the horizon lines. */
@Composable
internal fun Postmark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)
        val ink = color.copy(alpha = 0.88f)
        drawCircle(ink, r - 2.dp.toPx(), center, style = Stroke(2.5.dp.toPx()))
        drawCircle(ink, r * 0.74f, center, style = Stroke(1.6.dp.toPx()))
        val sun = r * 0.34f
        drawArc(ink, 180f, 180f, false, Offset(center.x - sun, center.y - sun * 0.6f), Size(sun * 2, sun * 2), style = Stroke(2.dp.toPx()))
        for (i in 0..2) {
            val y = center.y + sun * 0.45f + i * r * 0.14f
            val half = r * (0.5f - i * 0.1f)
            drawLine(ink, Offset(center.x - half, y), Offset(center.x + half, y), 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------- Trail

/** A full-bleed scene, the name in big outdoor type, a slanted sheet and two taped photo stickers. */
@Composable
private fun TrailHero(data: HeroData) {
    val c = Bt.colors
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(380.dp)) {
            PlacePicture(data.photos.firstOrNull(), data.name, data, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 150.dp, start = 20.dp, end = 20.dp)) {
            CountryLine(data, color = Color.White, withDays = false)
            PlaceName(data.name, Modifier.padding(top = 4.dp), color = Color.White, scale = 1.15f)
            data.planLine?.let {
                Text(it.uppercase(), Modifier.padding(top = 4.dp), style = Bt.text.eyebrow.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.12.em), color = c.accent)
            }
        }
        // The slanted sheet the page continues on.
        Box(
            Modifier.fillMaxWidth().padding(top = 330.dp).height(90.dp)
                .background(c.bg, GenericShape { s, _ -> moveTo(0f, s.height * 0.42f); lineTo(s.width, 0f); lineTo(s.width, s.height); lineTo(0f, s.height); close() }),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 318.dp, start = 22.dp, end = 22.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val a = data.sights.getOrNull(0)
            val b = data.sights.getOrNull(1)
            TapedSticker(data.photos.getOrNull(1), a, data, -4f, c.accent, c.onAccent)
            TapedSticker(data.photos.getOrNull(2), b, data, 5f, c.route, readableOn(c.route), Modifier.padding(top = 18.dp))
        }
    }
}

/** A white-bordered photo sticker with a strip of tape across the top carrying a label. */
@Composable
private fun TapedSticker(photo: Media?, sight: PlanItem?, data: HeroData, tilt: Float, tape: Color, tapeInk: Color, modifier: Modifier = Modifier) {
    val label = sight?.let { PlaceGrouping.sticker(it.title, 14) }
    Box(modifier.rotate(tilt), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.padding(top = 12.dp).shadow(10.dp, RoundedCornerShape(10.dp)).background(Color.White, RoundedCornerShape(10.dp)).padding(5.dp)) {
            PlacePicture(
                photo, sight?.title ?: data.name, data, Modifier.size(132.dp, 124.dp).clip(RoundedCornerShape(7.dp)),
                lat = sight?.lat ?: data.lat, lng = sight?.lng ?: data.lng,
            )
        }
        if (label != null) {
            Text(
                label.uppercase(),
                Modifier.rotate(-3f).background(tape.copy(alpha = 0.95f)).padding(horizontal = 10.dp, vertical = 4.dp).widthIn(max = 130.dp),
                style = Bt.text.label.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.08.em),
                color = tapeInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- Sticker Book

private val WASHI_PINK = Color(0xFFFF8FB1)
private val WASHI_MINT = Color(0xFF7FD8B2)

/** A polaroid with two strips of washi tape, the name over a highlighter stroke, and sticker chips. */
@Composable
private fun StickerHero(data: HeroData) {
    val c = Bt.colors
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = TOP_ROOM, start = 22.dp, end = 22.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp).rotate(1.5f).shadow(8.dp, RectangleShape).background(Color.White).padding(start = 9.dp, end = 9.dp, top = 9.dp)) {
                PlacePicture(data.photos.firstOrNull(), data.name, data, Modifier.fillMaxWidth().height(200.dp))
                Text(
                    data.sights.firstOrNull()?.let { PlaceGrouping.sticker(it.title, 28) } ?: data.name,
                    Modifier.padding(vertical = 8.dp),
                    style = Bt.text.h2.copy(fontSize = Bt.tokens.type.displaySize(22.sp)), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Washi(WASHI_PINK, Modifier.align(Alignment.TopStart).offset(x = (-14).dp, y = 2.dp).rotate(-22f))
            Washi(WASHI_MINT, Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = 6.dp).rotate(24f))
        }
        Spacer(Modifier.height(16.dp))
        CountryLine(data)
        // The name over the Sticker Book's highlighter bar, leaning −2° as on the board.
        PlaceName(data.name, Modifier.padding(top = 4.dp).highlighter(c.accent, from = 0.5f, to = 0.92f, tiltDeg = -2f))
        StickerChips(data.sights)
    }
}

/** A strip of striped washi tape. */
@Composable
private fun Washi(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(84.dp, 26.dp)) {
        drawRect(color.copy(alpha = 0.82f))
        val stripe = 8.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawRect(Color.White.copy(alpha = 0.22f), Offset(x, 0f), Size(stripe / 2, size.height))
            x += stripe
        }
    }
}

/** Rounded stickers with a white edge, in pastel colours, slightly tilted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StickerChips(sights: List<PlanItem>) {
    if (sights.isEmpty()) return
    val c = Bt.colors
    val fills = listOf(WASHI_MINT, WASHI_PINK, c.accent)
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sights.take(3).forEachIndexed { i, item ->
            val shape = RoundedCornerShape(50)
            Text(
                PlaceGrouping.sticker(item.title, 24),
                Modifier.rotate(listOf(-4f, 3f, -2f)[i % 3]).shadow(3.dp, shape).background(Color.White, shape).padding(3.dp)
                    .background(fills[i % fills.size], shape).padding(horizontal = 14.dp, vertical = 6.dp),
                style = Bt.text.h2.copy(fontSize = Bt.tokens.type.displaySize(19.sp), lineHeight = Bt.tokens.type.displaySize(21.sp)),
                color = Color(0xFF1E2A5A), maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------- Sketchbook

/** A hand-drawn map of the place's stops with dashed routes, a sticky note with the days, then the name underlined. */
@Composable
private fun SketchHero(data: HeroData) {
    val c = Bt.colors
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = TOP_ROOM - 8.dp, start = 20.dp, end = 20.dp)) {
        Box(Modifier.fillMaxWidth().height(230.dp)) {
            SketchMap(data.located, Modifier.fillMaxSize())
            if (data.daysLabel != null || data.onward != null) {
                Column(
                    Modifier.align(Alignment.TopEnd).padding(top = 4.dp).rotate(4f).shadow(6.dp, RectangleShape).background(c.accent)
                        .width(126.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    val hand = Bt.text.h2.copy(fontSize = Bt.tokens.type.displaySize(19.sp), lineHeight = Bt.tokens.type.displaySize(20.sp))
                    data.daysLabel?.let { Text(it, style = hand, color = c.onAccent) }
                    data.onward?.let { Text(it, style = hand, color = c.onAccent, maxLines = 3) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        CountryLine(data)
        PlaceName(data.name, Modifier.padding(top = 2.dp))
        WavyUnderline(c.route, Modifier.width(170.dp))
    }
}

/**
 * The place's located items as pen dots on paper, joined in plan order by dashed arcs in the route colour, with
 * their names in the hand face; a coastline and a mountain zigzag for the hand-drawn look.
 */
@Composable
private fun SketchMap(items: List<PlanItem>, modifier: Modifier = Modifier) {
    val c = Bt.colors
    val labelStyle = Bt.text.h2.copy(fontSize = Bt.tokens.type.displaySize(17.sp))
    val points = remember(items) { sketchPoints(items) }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val ink = c.ink
            sketchCoast(ink)
            val pts = points.map { (_, p) -> Offset(size.width * (0.12f + p.first * 0.4f), size.height * (0.14f + p.second * 0.62f)) }
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2 - 28.dp.toPx())
                drawPath(
                    Path().apply { moveTo(a.x, a.y); quadraticTo(mid.x, mid.y, b.x, b.y) }, c.route,
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))),
                )
            }
            pts.forEach { p ->
                drawCircle(c.bg, 6.dp.toPx(), p)
                drawCircle(ink, 6.dp.toPx(), p, style = Stroke(1.8.dp.toPx()))
            }
        }
        // Labels as text so they use the hand face.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            points.forEach { (item, p) ->
                val x = maxWidth * (0.12f + p.first * 0.4f)
                val y = maxHeight * (0.14f + p.second * 0.62f)
                Text(
                    PlaceGrouping.sticker(item.title, 16), Modifier.offset(x = x + 10.dp, y = y + 2.dp).widthIn(max = 116.dp),
                    style = labelStyle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Up to four located items at least 1.5 km apart, scaled into a unit box (x east, y south) keeping the map's
 * proportions, so their hand-written names don't pile up.
 */
private fun sketchPoints(items: List<PlanItem>): List<Pair<PlanItem, Pair<Float, Float>>> {
    val distinct = ArrayList<PlanItem>()
    for (i in items) {
        if (distinct.none { d -> Geo.distanceM(d.lat!!, d.lng!!, i.lat!!, i.lng!!) < 1_500.0 }) distinct += i
        if (distinct.size == 4) break
    }
    if (distinct.isEmpty()) return emptyList()
    val minLat = distinct.minOf { it.lat!! }
    val maxLat = distinct.maxOf { it.lat!! }
    val minLng = distinct.minOf { it.lng!! }
    val maxLng = distinct.maxOf { it.lng!! }
    val k = cos(((minLat + maxLat) / 2) * PI / 180).coerceAtLeast(0.2)
    val span = maxOf((maxLng - minLng) * k, maxLat - minLat, 1e-6)
    return distinct.map { i ->
        val x = (((i.lng!! - minLng) * k) / span).toFloat()
        val y = ((maxLat - i.lat!!) / span).toFloat()
        i to (x to y)
    }
}

private fun DrawScope.sketchCoast(ink: Color) {
    val w = size.width
    val h = size.height
    val coast = Path().apply {
        moveTo(w * 0.06f, h * 0.02f)
        cubicTo(w * 0.02f, h * 0.3f, w * 0.1f, h * 0.55f, w * 0.05f, h * 0.98f)
    }
    drawPath(coast, ink, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
    for (i in 0..2) {
        val y = h * (0.25f + i * 0.22f)
        drawLine(ink, Offset(w * 0.0f, y), Offset(w * 0.025f, y - 3.dp.toPx()), 1.4.dp.toPx(), cap = StrokeCap.Round)
    }
    val zig = Path().apply {
        moveTo(w * 0.6f, h * 0.96f)
        var x = w * 0.6f
        var up = true
        while (x < w * 0.95f) {
            x += w * 0.07f
            lineTo(x, if (up) h * 0.68f else h * 0.84f)
            up = !up
        }
    }
    drawPath(zig, ink, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
}

// ---------------------------------------------------------------- Market

private val DOTS = listOf(Color(0xFFF2C14E), Color(0xFFF4A26B), Color(0xFF9ED3CB), Color(0xFFE8B4D8))

/** A torn poster of the place, a rubber stamp, the name in the hand face and price-dot stickers. */
@Composable
private fun MarketHero(data: HeroData) {
    val c = Bt.colors
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = TOP_ROOM - 8.dp, start = 18.dp, end = 18.dp)) {
        Box(Modifier.fillMaxWidth()) {
            PlacePicture(data.photos.firstOrNull(), data.name, data, Modifier.fillMaxWidth().height(224.dp).clip(TornEdges))
            data.stamp?.let { (a, b) ->
                Column(
                    Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 10.dp).rotate(7f)
                        .background(c.surface.copy(alpha = 0.92f)).border(2.dp, c.route).padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val stamp = Bt.text.mono.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.1.em)
                    Text(a, style = stamp, color = c.route, maxLines = 1)
                    Text(b, style = stamp, color = c.route, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        CountryLine(data)
        PlaceName(data.name, Modifier.padding(top = 2.dp))
        PriceDots(data.sights)
    }
}

/** Round stickers, like price dots on a market stall, with an item each. */
@Composable
private fun PriceDots(sights: List<PlanItem>) {
    if (sights.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sights.take(4).forEachIndexed { i, item ->
            Box(
                Modifier.weight(1f, fill = false).size(78.dp).rotate(listOf(-3f, 4f, -2f, 3f)[i % 4]).shadow(4.dp, CircleShape)
                    .background(DOTS[i % DOTS.size], CircleShape).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    PlaceGrouping.sticker(item.title, 22),
                    style = Bt.text.label.copy(fontSize = 12.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = Color(0xFF3A2A1C), textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Zigzag top and bottom edges, like a poster torn off a wall. */
private val TornEdges = GenericShape { s, _ ->
    val teeth = 22
    val step = s.width / teeth
    val depth = 7f * (s.width / 360f).coerceAtLeast(1f)
    moveTo(0f, depth)
    for (i in 0 until teeth) {
        lineTo(step * i + step / 2, if (i % 2 == 0) 0f else depth * 1.6f)
        lineTo(step * (i + 1), depth)
    }
    lineTo(s.width, s.height - depth)
    for (i in teeth - 1 downTo 0) {
        lineTo(step * i + step / 2, if (i % 2 == 0) s.height else s.height - depth * 1.6f)
        lineTo(step * i, s.height - depth)
    }
    close()
}
