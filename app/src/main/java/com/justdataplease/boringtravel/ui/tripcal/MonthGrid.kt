package com.justdataplease.boringtravel.ui.tripcal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.tripcal.TripGrid
import com.justdataplease.boringtravel.tripcal.TripSpan
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.world.FlagSticker
import com.justdataplease.boringtravel.util.Dates
import java.time.LocalDate
import java.time.YearMonth

private val NUMBER_H = 26.dp
private val LANE_H = 18.dp
private const val MAX_LANES = 3

/**
 * One month with every trip as a coloured bar over its days: the flag of where it is that week and its title. A day
 * inside one trip opens that trip on the day; a day two trips share asks which; an empty day offers a new trip from
 * there, unless [onNewTrip] is null (demo mode). Today is a filled circle.
 */
@Composable
internal fun MonthGrid(
    layout: TripGrid.MonthLayout,
    trips: Map<String, TripSpan>,
    dayFlags: Map<String, List<List<String>>>,
    colorOf: (String) -> Color,
    today: LocalDate,
    onOpen: (TripSpan, LocalDate) -> Unit,
    onNewTrip: ((LocalDate) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menuDay by remember { mutableStateOf<LocalDate?>(null) }
    val spans = remember(trips) { trips.values.toList() }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            layout.weeks.first().forEach { d ->
                Text(
                    Dates.weekday(d).uppercase(), Modifier.weight(1f), style = Bt.text.eyebrow.copy(letterSpacing = 0.sp),
                    textAlign = TextAlign.Center, maxLines = 1,
                )
            }
        }
        layout.weeks.forEachIndexed { w, week ->
            val bars = layout.bars.filter { it.week == w }
            val lanes = layout.lanesIn(w).coerceIn(1, MAX_LANES)
            // Days whose trips don't all fit: "+1" under the bars.
            val hidden = IntArray(7)
            bars.filter { it.lane >= MAX_LANES }.forEach { b -> for (i in b.from..b.to) hidden[i]++ }
            val rowHeight = NUMBER_H + LANE_H * lanes + (if (hidden.any { it > 0 }) 12.dp else 4.dp)
            BoxWithConstraints(Modifier.fillMaxWidth().height(rowHeight)) {
                val cell = maxWidth / 7
                Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                    week.forEachIndexed { i, d ->
                        val inMonth = YearMonth.from(d) == layout.month
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(
                                    if (inMonth) Modifier.clickable(role = Role.Button, onClickLabel = Dates.dayLong(d)) {
                                        val on = TripGrid.tripsOn(d, spans, layout.lanes)
                                        if (on.size == 1) onOpen(on.first(), d) else if (on.isNotEmpty() || onNewTrip != null) menuDay = d
                                    } else Modifier,
                                ),
                        ) {
                            DayNumber(d, inMonth, d == today, Modifier.align(Alignment.TopCenter).padding(top = 2.dp))
                            if (hidden[i] > 0) {
                                Text(
                                    "+${hidden[i]}", Modifier.align(Alignment.BottomCenter),
                                    style = Bt.text.mono.copy(fontSize = 9.sp, lineHeight = 10.sp), maxLines = 1,
                                )
                            }
                            if (menuDay == d) DayMenu(d, TripGrid.tripsOn(d, spans, layout.lanes), colorOf, onDismiss = { menuDay = null }, onOpen = { t ->
                                menuDay = null
                                onOpen(t, d)
                            }, onNewTrip = {
                                menuDay = null
                                onNewTrip?.invoke(d)
                            })
                        }
                    }
                }
                bars.filter { it.lane < MAX_LANES }.forEach { b ->
                    val trip = trips[b.tripId] ?: return@forEach
                    // The flags of where the trip is on this bar's days (two at most, one on a short bar).
                    val first = (week[b.from].toEpochDay() - trip.start.toEpochDay()).toInt()
                    val days = dayFlags[b.tripId].orEmpty()
                    val flags = (first until first + b.length).flatMap { days.getOrNull(it).orEmpty() }.distinct()
                        .ifEmpty { trip.countries.take(1) }.take(if (b.length >= 3) 2 else 1)
                    TripBar(
                        trip.title, flags, colorOf(b.tripId), b,
                        Modifier
                            .offset(x = cell * b.from + 1.dp, y = NUMBER_H + LANE_H * b.lane)
                            .width(cell * b.length - 2.dp)
                            .height(LANE_H - 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayNumber(d: LocalDate, inMonth: Boolean, today: Boolean, modifier: Modifier) {
    val c = Bt.colors
    Box(
        modifier.size(22.dp).clip(CircleShape).background(if (today && inMonth) c.chipOn else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            d.dayOfMonth.toString(),
            style = Bt.text.label.copy(fontSize = 12.5.sp, lineHeight = 14.sp, fontWeight = if (today) FontWeight.Bold else FontWeight.Medium),
            color = when {
                !inMonth -> c.muted.copy(alpha = 0.45f)
                today -> c.onChipOn
                else -> c.ink
            },
            maxLines = 1,
        )
    }
}

/**
 * A trip's bar in one week: its flags at the start, then its title; rounded where the trip starts and ends, square
 * where it goes on in the next row.
 */
@Composable
private fun TripBar(title: String, flags: List<String>, fill: Color, bar: TripGrid.Bar, modifier: Modifier) {
    val r = 6.dp
    val shape = RoundedCornerShape(
        topStart = if (bar.opensBefore) 0.dp else r, bottomStart = if (bar.opensBefore) 0.dp else r,
        topEnd = if (bar.runsOn) 0.dp else r, bottomEnd = if (bar.runsOn) 0.dp else r,
    )
    val ink = TripPalette.onColor(fill, Bt.colors)
    Row(
        modifier.clip(shape).background(fill).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (flags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy((-2).dp)) { flags.forEach { FlagSticker(it, 11.dp, tilt = false) } }
        }
        if (bar.length > 1 || flags.isEmpty()) {
            Text(
                title, Modifier.weight(1f, fill = false),
                style = Bt.text.label.copy(fontSize = 10.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold),
                color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
            )
        }
    }
}

/** A tapped day: the trips on it to open, or "New trip from here" when it is free. */
@Composable
private fun DayMenu(
    day: LocalDate,
    trips: List<TripSpan>,
    colorOf: (String) -> Color,
    onDismiss: () -> Unit,
    onOpen: (TripSpan) -> Unit,
    onNewTrip: () -> Unit,
) {
    val c = Bt.colors
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, containerColor = c.surface) {
        Text(Dates.dayLong(day).uppercase(), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = Bt.text.eyebrow, maxLines = 1)
        if (trips.isEmpty()) {
            DropdownMenuItem(
                text = { Text("New trip from here", style = Bt.text.item, color = c.ink) },
                leadingIcon = { Icon(BtIcons.Add, null, Modifier.size(18.dp), tint = c.ink) },
                onClick = onNewTrip,
            )
        } else {
            trips.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.title, style = Bt.text.item, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(colorOf(t.id))) },
                    onClick = { onOpen(t) },
                )
            }
        }
    }
}
