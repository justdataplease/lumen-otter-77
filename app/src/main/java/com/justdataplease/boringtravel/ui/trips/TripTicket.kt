package com.justdataplease.boringtravel.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.ui.components.DemoTag
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btBorder
import com.justdataplease.boringtravel.ui.components.btCardShape
import com.justdataplease.boringtravel.ui.components.btShadow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.BtShadow
import com.justdataplease.boringtravel.ui.tripcal.TripPalette
import com.justdataplease.boringtravel.ui.world.TripFlags
import com.justdataplease.boringtravel.ui.trips.TripCountdown.Phase
import kotlin.math.max

private val StubWidth = 92.dp
private val NotchRadius = 9.dp

/**
 * Trips › Tickets: one trip as a ticket in the theme's card style. The stub on the start side carries the countdown
 * ([TripCountdown]) in the trip's Calendar colour (a pale tint once the trip is over; "Add dates" on the page's chip
 * colour for a trip without dates), then a dashed perforation between two half-circle notches, then the body: the
 * eyebrow, the title with its flags, and [footer]. A tap opens the trip; with [onAddDates] the stub is its own button.
 */
@Composable
internal fun TripTicket(
    count: TripCountdown.Count,
    color: Color,
    eyebrow: String,
    title: String,
    flags: List<String>,
    demo: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAddDates: (() -> Unit)? = null,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val t = Bt.tokens
    val c = t.colors
    val skin = t.skin
    val shape = remember(t) { TicketShape(btCardShape(t), StubWidth, NotchRadius) }
    val stroke = skin.cardBorder
    // Trail's hairline is faint on a bright stub: the perforation takes the muted ink there.
    val perforation = if (stroke.color.alpha >= 0.5f) stroke.color else c.muted.copy(alpha = 0.6f)
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .btShadow(skin.cardShadow, shape)
            .clip(shape)
            .background(skin.cardFill ?: c.surface)
            .btBorder(stroke, shape)
            .drawWithContent {
                drawContent()
                val x = stubEdge(size.width, StubWidth.toPx(), layoutDirection)
                val gap = NotchRadius.toPx() + 2.dp.toPx()
                drawLine(
                    perforation, Offset(x, gap), Offset(x, size.height - gap),
                    strokeWidth = max(stroke.width.toPx(), 1.dp.toPx()),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                )
            }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        TicketStub(count, color, onAddDates, Modifier.width(StubWidth).fillMaxHeight())
        Column(
            Modifier.weight(1f).heightIn(min = 84.dp).padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(eyebrow, Modifier.weight(1f, fill = false))
                if (demo) DemoTag(Modifier.padding(start = 8.dp))
            }
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, Modifier.weight(1f, fill = false), style = Bt.text.title.copy(fontSize = 17.sp, lineHeight = 21.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                TripFlags(flags, Modifier.padding(start = 8.dp), size = 14.dp, max = 3)
            }
            footer()
        }
    }
}

/** The stub: the trip's colour (its ink or page colour on it), a pale tint once over, the chip colour without dates. */
@Composable
private fun TicketStub(count: TripCountdown.Count, color: Color, onAddDates: (() -> Unit)?, modifier: Modifier) {
    val c = Bt.colors
    val fill = when (count.phase) {
        Phase.Now, Phase.Next -> color
        Phase.Past -> color.copy(alpha = 0.22f).compositeOver(c.surface)
        Phase.Someday -> c.chip
    }
    val ink = if (count.phase == Phase.Now || count.phase == Phase.Next) TripPalette.onColor(color, c) else c.ink
    Column(
        modifier
            .background(fill)
            .then(if (onAddDates != null) Modifier.clickable(role = Role.Button, onClick = onAddDates) else Modifier)
            .padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (count.phase == Phase.Someday) {
            Icon(BtIcons.Calendar, null, Modifier.size(22.dp), tint = ink)
            Text(count.unit, Modifier.padding(top = 4.dp), style = Bt.text.label.copy(fontWeight = FontWeight.SemiBold), color = ink, textAlign = TextAlign.Center, maxLines = 2)
        } else {
            CountdownText(
                count, ink, numberSize = 42.sp,
                unitStyle = Bt.text.fine.copy(fontWeight = FontWeight.Medium),
                wordStyle = Bt.text.item.copy(fontSize = 14.sp),
            )
        }
    }
}

/** The trip's days as a thin bar in its colour: day 14 of 31 fills 14/31. */
@Composable
internal fun TripProgress(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Bt.colors.line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

/**
 * The countdown as text: the number in the display face ([numberSize] as in Caveat, a little smaller from three
 * digits) over its unit, or the word alone ("Tomorrow", "Last day").
 */
@Composable
internal fun CountdownText(
    count: TripCountdown.Count,
    color: Color,
    numberSize: TextUnit,
    unitStyle: TextStyle,
    wordStyle: TextStyle,
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.CenterHorizontally,
) {
    val align = if (alignment == Alignment.End) TextAlign.End else TextAlign.Center
    Column(modifier, horizontalAlignment = alignment) {
        val n = count.number
        if (n != null) {
            val digits = n.toString()
            val k = when {
                digits.length >= 4 -> 0.72f
                digits.length == 3 -> 0.88f
                else -> 1f
            }
            val size = Bt.tokens.type.displaySize(numberSize * k)
            Text(
                Bt.text.display(digits), style = Bt.text.h1.copy(fontSize = size, lineHeight = size * 1.05f),
                color = color, maxLines = 1, softWrap = false,
            )
            Text(count.unit, style = unitStyle, color = color, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text(count.unit, style = wordStyle, color = color, textAlign = align, maxLines = 2)
        }
    }
}

/**
 * Trips › Pictures: the countdown as a round sticker on the corner of the Now card's photo, in the highlighter
 * colour, leaning 8°. Postcard Pop gives it the ink outline and hard shadow of its buttons.
 */
@Composable
internal fun CountdownSticker(count: TripCountdown.Count, modifier: Modifier = Modifier) {
    val c = Bt.colors
    val skin = Bt.tokens.skin
    Box(
        modifier
            .size(76.dp)
            .rotate(8f)
            .btShadow(skin.buttonShadow ?: StickerShadow, CircleShape)
            .clip(CircleShape)
            .background(c.accent)
            .btBorder(skin.buttonBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CountdownText(
            count, c.onAccent, numberSize = 32.sp,
            unitStyle = Bt.text.fine.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium),
            wordStyle = Bt.text.label.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

private val StickerShadow = BtShadow(y = 3.dp, blur = 8.dp, color = Color(0x40000000))

/** Where the stub ends: [stub] from the start side. */
private fun stubEdge(width: Float, stub: Float, direction: LayoutDirection): Float =
    if (direction == LayoutDirection.Rtl) width - stub else stub

/**
 * The ticket's outline: the theme's card shape ([card]) with a half-circle notch of [notch] cut from the top and the
 * bottom edge where the stub ([stub] wide) ends. Shadow, clip and border all follow it, so the notches show the page.
 */
internal class TicketShape(private val card: Shape, private val stub: Dp, private val notch: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val base = Path().apply { addOutline(card.createOutline(size, layoutDirection, density)) }
        val r = with(density) { notch.toPx() }
        val x = stubEdge(size.width, with(density) { stub.toPx() }, layoutDirection)
        val holes = Path().apply {
            addOval(Rect(Offset(x, 0f), r))
            addOval(Rect(Offset(x, size.height), r))
        }
        return Outline.Generic(Path().apply { op(base, holes, PathOperation.Difference) })
    }
}
