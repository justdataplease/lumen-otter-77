package com.justdataplease.boringtravel.ui.tripcal

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.tripcal.TripGrid
import com.justdataplease.boringtravel.tripcal.TripSpan
import com.justdataplease.boringtravel.ui.theme.Bt
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle

/** A whole year: twelve small months, three across, each trip a band in its colour over its days. A month opens it. */
@Composable
internal fun YearGrid(
    year: Int,
    firstDay: DayOfWeek,
    trips: List<TripSpan>,
    colorOf: (String) -> Color,
    today: LocalDate,
    onMonth: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layouts = remember(year, firstDay, trips) { (1..12).map { TripGrid.month(YearMonth.of(year, it), firstDay, trips) } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        layouts.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { layout -> MiniMonth(layout, colorOf, today, Modifier.weight(1f)) { onMonth(layout.month) } }
            }
        }
    }
}

@Composable
private fun MiniMonth(layout: TripGrid.MonthLayout, colorOf: (String) -> Color, today: LocalDate, modifier: Modifier, onClick: () -> Unit) {
    val c = Bt.colors
    val locale = LocalConfiguration.current.locales[0]
    val current = layout.month == YearMonth.from(today)
    val name = remember(layout.month, locale) { layout.month.month.getDisplayName(TextStyle.SHORT, locale) }
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val numberStyle = Bt.text.label.copy(fontSize = 8.5.sp, lineHeight = 9.sp, fontWeight = FontWeight.Medium)
    val numbers = remember(numberStyle, measurer) { (1..31).map { measurer.measure(it.toString(), numberStyle) } }
    val todayNumber = remember(numberStyle, measurer) { measurer.measure(today.dayOfMonth.toString(), numberStyle.copy(fontWeight = FontWeight.Bold)) }
    // Each day's top band colour, for the number's own colour on it.
    val topFill = remember(layout, colorOf) {
        val out = HashMap<LocalDate, Color>()
        layout.bars.sortedByDescending { it.lane }.forEach { b ->
            val week = layout.weeks[b.week]
            for (i in b.from..b.to) out[week[i]] = colorOf(b.tripId)
        }
        out
    }
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = name, onClick = onClick).padding(2.dp),
    ) {
        Text(
            name.uppercase(locale), Modifier.padding(start = 2.dp, bottom = 3.dp),
            style = Bt.text.eyebrow.copy(fontWeight = if (current) FontWeight.Bold else FontWeight.Medium),
            color = if (current) c.accentInk else c.muted, maxLines = 1,
        )
        Canvas(Modifier.fillMaxWidth().aspectRatio(7f / 6f)) {
            val cw = size.width / 7f
            val ch = size.height / 6f
            val lanes = layout.laneCount.coerceAtLeast(1)
            val padY = ch * 0.08f
            val band = (ch - 2 * padY) / lanes
            for (b in layout.bars) {
                val top = b.week * ch + padY + b.lane * band
                val r = CornerRadius(minOf(band, cw) / 2f)
                val zero = CornerRadius.Zero
                val rect = RoundRect(
                    left = b.from * cw + 0.5f, top = top + 0.5f, right = (b.to + 1) * cw - 0.5f, bottom = top + band - 0.5f,
                    topLeftCornerRadius = if (b.opensBefore) zero else r, bottomLeftCornerRadius = if (b.opensBefore) zero else r,
                    topRightCornerRadius = if (b.runsOn) zero else r, bottomRightCornerRadius = if (b.runsOn) zero else r,
                )
                drawPath(Path().apply { addRoundRect(rect) }, colorOf(b.tripId))
            }
            layout.weeks.forEachIndexed { w, week ->
                week.forEachIndexed { i, d ->
                    if (YearMonth.from(d) != layout.month) return@forEachIndexed
                    val isToday = d == today
                    val text = if (isToday) todayNumber else numbers[d.dayOfMonth - 1]
                    val fill = topFill[d]
                    val ink = fill?.let { TripPalette.onColor(it, c) } ?: if (isToday) c.accentInk else c.ink
                    val cx = i * cw + cw / 2f
                    val cy = w * ch + ch / 2f
                    drawText(text, color = ink, topLeft = Offset(cx - text.size.width / 2f, cy - text.size.height / 2f))
                    if (isToday) drawCircle(ink, radius = minOf(cw, ch) / 2f - 0.5f, center = Offset(cx, cy), style = Stroke(1.2.dp.toPx()))
                }
            }
        }
    }
}
