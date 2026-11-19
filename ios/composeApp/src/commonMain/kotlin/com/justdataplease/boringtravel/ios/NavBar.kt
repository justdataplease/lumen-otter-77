package com.justdataplease.boringtravel.ios

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.ui.components.btBorder
import com.justdataplease.boringtravel.ui.components.btShadow
import com.justdataplease.boringtravel.ui.components.highlighter
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.BarMode
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripNav
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.BtFonts
import com.justdataplease.boringtravel.ui.theme.NavBarSpec
import com.justdataplease.boringtravel.ui.theme.NavBars
import com.justdataplease.boringtravel.ui.theme.NavFace
import com.justdataplease.boringtravel.ui.theme.NavMarker

/*
 * The bottom bar, as the Android app draws it (ui/nav/AppNav.kt: BtNavBar, NavItem, the markers): which items it shows
 * and what a tap does are the Android app's own TripNav, compiled here as it is.
 */

/** One item on the bottom bar. */
private data class BarItem(val label: String, val icon: ImageVector)

private val homeItems = HomeTab.entries.map { t ->
    BarItem(
        t.label,
        when (t) {
            HomeTab.Trips -> BtIcons.Trips
            HomeTab.Calendar -> BtIcons.Calendar
            HomeTab.Map -> BtIcons.Map
            HomeTab.Community -> BtIcons.Community
            HomeTab.Me -> BtIcons.Me
        },
    )
}

private val tripItems = TripTab.entries.map { t ->
    BarItem(
        t.label,
        when (t) {
            TripTab.Plan -> BtIcons.Checklist
            TripTab.Journal -> BtIcons.Journal
            TripTab.Map -> BtIcons.Map
            TripTab.Media -> BtIcons.Gallery
        },
    )
}

/**
 * The bottom bar, drawn per theme from [NavBars]: Postcard Pop's coral pill on a plain white bar, Trail's luggage tag,
 * Sticker Book's highlighter, Sketchbook's pencil loop (no bar, the ruling runs through) and Market's dot sticker.
 * [bottom] is room kept under it besides the phone's own (the previews' home indicator).
 */
@Composable
fun BtNavBar(mode: BarMode, onSelect: (Int) -> Unit, bottom: Dp = 0.dp) {
    val t = Bt.tokens
    val spec = NavBars.of(t.look)
    val shape = RoundedCornerShape(spec.corner)
    val items: @Composable () -> Unit = {
        // Entering or leaving a trip swaps the items with a quick crossfade (the bar itself stays); a tab change
        // within a mode only moves the marker.
        AnimatedContent(
            targetState = mode,
            contentKey = { it is BarMode.Trip },
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
            label = "Bar items",
        ) { m ->
            val list = if (m is BarMode.Trip) tripItems else homeItems
            val selected = TripNav.selectedIndex(m)
            Row(Modifier.fillMaxWidth().height(spec.height)) {
                list.forEachIndexed { i, item ->
                    NavItem(item, i == selected, spec, Modifier.weight(1f).fillMaxHeight()) { onSelect(i) }
                }
            }
        }
    }
    if (spec.floating) {
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = bottom)
                .padding(start = spec.inset, end = spec.inset, bottom = spec.inset)
                .height(spec.height),
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .btShadow(spec.shadow, shape)
                    .clip(shape)
                    .then(if (spec.fill != null) Modifier.background(spec.fill) else Modifier)
                    .btBorder(spec.border, shape)
                    .drawBehind { barDecor(spec) },
            )
            items()
        }
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (spec.fill != null) Modifier.background(spec.fill) else Modifier)
                .drawBehind { spec.topLine?.let { drawRect(it, size = Size(size.width, 1.dp.toPx())) } }
                .navigationBarsPadding()
                .padding(bottom = bottom),
        ) { items() }
    }
}

/** The stitch inside a slab or kraft strip. */
private fun DrawScope.barDecor(spec: NavBarSpec) {
    spec.stitch?.let { s ->
        val inset = spec.stitchInset.toPx()
        val w = s.width.toPx()
        val r = (spec.corner.toPx() - inset).coerceAtLeast(0f)
        drawRoundRect(
            s.color, topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
            cornerRadius = CornerRadius(r),
            style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
        )
    }
}

/** Postcard Pop's pill: every tab's icon sits in a slot this size, so the active pill stays in line with the rest. */
private val PILL_W = 46.dp
private val PILL_H = 28.dp
private val PILL_CORNER = 10.dp

@Composable
private fun NavItem(tab: BarItem, on: Boolean, spec: NavBarSpec, modifier: Modifier, onClick: () -> Unit) {
    val ty = Bt.tokens.type
    val face = { f: NavFace ->
        when (f) {
            NavFace.Albert -> BtFonts.albert
            NavFace.Body -> ty.body
            NavFace.Hand -> ty.hand
        }
    }
    val hand = on && spec.activeFace == NavFace.Hand
    val size = if (on) spec.activeLabelSize else spec.labelSize
    val labelStyle = TextStyle(
        fontFamily = face(if (on) spec.activeFace else spec.labelFace),
        fontWeight = if (on) spec.activeLabelWeight else spec.labelWeight,
        fontSize = size,
        lineHeight = if (hand) size else size * 1.25f,
        letterSpacing = spec.letterSpacing,
        color = if (on) spec.activeLabel else spec.label,
    )
    val label = if (spec.upper) tab.label.uppercase() else tab.label
    // The active label's width: Trail's tag grows to the left for a long one ("COMMUNITY") so no letter falls off it.
    var labelWidth by remember { mutableStateOf(0f) }
    Column(
        modifier
            .semantics { this.selected = on }
            .clickable(role = Role.Tab, onClickLabel = tab.label, onClick = onClick)
            .then(if (on) Modifier.drawBehind { markerBehind(spec, labelWidth) } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        val tint = if (on) spec.activeIcon else spec.icon
        if (spec.marker == NavMarker.Pill) {
            Box(
                Modifier.size(PILL_W, PILL_H)
                    .then(if (on) Modifier.clip(RoundedCornerShape(PILL_CORNER)).background(spec.markerColor) else Modifier),
                contentAlignment = Alignment.Center,
            ) { Icon(tab.icon, null, Modifier.size(20.dp), tint = tint) }
        } else {
            Icon(tab.icon, null, Modifier.size(20.dp), tint = tint)
        }
        val labelModifier = if (on && spec.marker == NavMarker.Highlighter) {
            Modifier.rotate(spec.markerTilt).highlighter(spec.markerColor, from = 0.40f, to = 0.94f).padding(horizontal = HIGHLIGHTER_PAD)
        } else {
            Modifier
        }
        // Five tabs on a narrow phone leave little room per label, less with a larger font: a label that doesn't fit
        // steps down from its size (to [labelMinSize] at most) instead of losing its last letters ("Community").
        Text(
            label, labelModifier, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = labelMinSize(size), maxFontSize = size, stepSize = 0.25.sp),
            onTextLayout = { labelWidth = it.size.width.toFloat() },
        )
    }
}

/** Sticker Book's marker swipe reaches this far past the active label on each side. */
private val HIGHLIGHTER_PAD = 4.dp

/** The smallest a bar label may get: 60 % of its size, never under 8 sp. */
internal fun labelMinSize(size: TextUnit): TextUnit {
    val scaled = size * 0.6f
    return if (scaled > 8.sp) scaled else 8.sp
}

/** The marker drawn behind the active tab: Trail's tag, Sketchbook's loop, Market's dot. */
private fun DrawScope.markerBehind(spec: NavBarSpec, labelWidth: Float = 0f) {
    val cx = size.width / 2
    when (spec.marker) {
        NavMarker.LuggageTag -> {
            // `.ltag`: 66 x 48 at top 7, from 29 left of centre; the point 16 dp deep on the left; -5 degrees.
            // Wider for a label that would reach past the point (12 dp clear of it), never narrower.
            val left = minOf(cx - 29.dp.toPx(), cx - labelWidth / 2 - 12.dp.toPx())
            val top = 7.dp.toPx()
            val w = cx + 37.dp.toPx() - left
            val h = 48.dp.toPx()
            val point = 16.dp.toPx()
            val tag = Path().apply {
                moveTo(left + point, top)
                lineTo(left + w, top)
                lineTo(left + w, top + h)
                lineTo(left + point, top + h)
                lineTo(left, top + h / 2)
                close()
            }
            rotate(spec.markerTilt, Offset(left + w / 2, top + h / 2)) { drawPath(tag, spec.markerColor) }
            // `.lhole`: the punched hole, 6 dp, 21 left of centre, 27 down.
            drawCircle(spec.activeIcon, 3.dp.toPx(), Offset(left + 11.dp.toPx(), 30.dp.toPx()))
        }
        NavMarker.PencilLoop -> {
            // `.loop`: a 62 x 40 hand-drawn loop at top 4, centred (the board's SVG path, smooth curves expanded).
            val u = 1.dp.toPx()
            val ox = cx - 31 * u
            val oy = 4 * u
            fun p(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
            val loop = Path().apply {
                val start = p(10f, 24f)
                moveTo(start.x, start.y)
                cubic(p(7f, 12f), p(22f, 5f), p(34f, 5f))
                cubic(p(46f, 5f), p(57f, 11f), p(55f, 21f))
                cubic(p(53f, 31f), p(40f, 36f), p(27f, 35f))
                cubic(p(14f, 34f), p(6f, 28f), p(12f, 15f))
            }
            drawPath(loop, spec.markerColor, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        NavMarker.DotSticker -> {
            // `.pdot`: a 40 dp sticker at top 3, 5 dp right of centre, with a small soft shadow.
            val r = 20.dp.toPx()
            val c = Offset(cx + 5.dp.toPx(), 3.dp.toPx() + r)
            drawCircle(Color(0x403A2A1C), r, c + Offset(0f, 2.dp.toPx()))
            drawCircle(spec.markerColor, r, c)
        }
        NavMarker.Pill, NavMarker.Highlighter -> Unit
    }
}

private fun Path.cubic(a: Offset, b: Offset, c: Offset) = cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)
