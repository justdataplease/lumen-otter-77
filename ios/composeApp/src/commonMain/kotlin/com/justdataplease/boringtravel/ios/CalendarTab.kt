package com.justdataplease.boringtravel.ios

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.civil
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtSegmented
import com.justdataplease.boringtravel.ui.components.DemoTag
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.H1
import com.justdataplease.boringtravel.ui.components.SectionLabel
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import com.justdataplease.boringtravel.compat.uppercase

/** One page of the calendar: a month ([key] = [CalMonth.index]) or a year ([key] = the year). */
private data class Page(val years: Boolean, val key: Int)

/**
 * The Calendar tab, as the Android app's (ui/tripcal/TripsCalendarScreen.kt): every trip on a calendar and nothing
 * else. Month · Year (arrows or a swipe; it opens on this month), each trip a bar in its own colour; overlapping trips
 * flagged; trips without dates listed. A day in a trip opens the trip on that day ([onOpenDay]); a day two trips share
 * asks which; an empty day offers a new trip from there ([onNewTripFrom]). With no trips: one line and New trip. It
 * shows what Trips shows: the examples in [demoMode], with the DEMO tag, and then nothing offers a new trip (the empty
 * state has Exit demo). The week starts where the phone's region says ([Phone.firstDayOfWeek]). Each bar has the flags of where the trip
 * is that week, a row without dates its countries ([rememberWorld]). [yearAtStart] opens on the year (the previews).
 */
@Composable
fun TripsCalendarScreen(
    book: TripBook,
    today: LocalDate,
    revision: Int,
    onOpenDay: (String, LocalDate) -> Unit,
    onOpenTrip: (String) -> Unit,
    onAddDates: (String) -> Unit,
    onNewTrip: () -> Unit,
    onNewTripFrom: (LocalDate) -> Unit,
    yearAtStart: Boolean = false,
    demoMode: Boolean = false,
    onExitDemo: () -> Unit = {},
) {
    val firstDay = LocalPhone.current.firstDayOfWeek
    // The month's title in the phone's language (Android: "LLLL yyyy" in the configuration's locale, its first letter a capital).
    val locale = screenLocale()
    // Countries come from the same place as the flags on Trips (and are the demo's in demo mode).
    val world = rememberWorld(book, revision, demoMode, today)
    val split = remember(book, revision, demoMode, world) {
        TripSpan.split(book.snapshot.trips.filter { DemoTrips.visible(it.id, demoMode) }, world.tripCountries, world.tripDayCountries)
    }
    val spans = split.spans
    val byId = remember(spans) { spans.associateBy { it.id } }
    val dayFlags = remember(spans) { spans.associate { it.id to TripDays.countries(it) } }
    val tokens = Bt.tokens
    val colorIndex = remember(spans) { TripColors.assign(spans) }
    val colorOf = remember(colorIndex, tokens) { { id: String -> TripPalette.color(tokens, colorIndex[id] ?: TripColors.preferred(id)) } }

    var yearView by rememberSaveable { mutableStateOf(yearAtStart) }
    val todayIndex = CalMonth.of(today).index
    var monthIndex by rememberSaveable { mutableIntStateOf(todayIndex) }
    val month = CalMonth.ofIndex(monthIndex)
    val shownYear = month.year
    fun step(n: Int) {
        monthIndex += if (yearView) 12 * n else n
    }

    val overlaps = remember(spans) { TripAnalytics.overlaps(spans) }

    BtScreen(statusScrim = true) {
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item(key = "header") {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("${Dates.weekday(today)} ${Dates.dayMonth(today)}")
                        H1("Calendar")
                    }
                    if (demoMode) DemoTag(Modifier.padding(bottom = 10.dp))
                }
            }
            if (spans.isEmpty() && split.undated.isEmpty()) {
                item(key = "empty") {
                    if (demoMode) EmptyCalendar("No example trips on the calendar.", "Exit demo", danger = true, onClick = onExitDemo)
                    else EmptyCalendar("No trips on the calendar yet.", "New trip", onClick = onNewTrip)
                }
                return@LazyColumn
            }
            item(key = "switch") {
                BtSegmented(
                    listOf("Month", "Year"), if (yearView) 1 else 0, { yearView = it == 1 },
                    Modifier.padding(horizontal = 20.dp).padding(top = 10.dp),
                )
            }
            item(key = "period") {
                val away = if (yearView) shownYear != today.civil.year else monthIndex != todayIndex
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (yearView) Bt.text.display(shownYear.toString()) else PhoneTexts.monthTitle(month.year, month.month, locale),
                        Modifier.weight(1f), style = Bt.text.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (away) BtChip("Today", selected = false, onClick = { monthIndex = todayIndex })
                    BtIconButton(BtIcons.Back, if (yearView) "Previous year" else "Previous month", { step(-1) })
                    BtIconButton(BtIcons.Forward, if (yearView) "Next year" else "Next month", { step(1) })
                }
            }
            item(key = "grid") {
                var drag by remember { mutableFloatStateOf(0f) }
                AnimatedContent(
                    targetState = Page(yearView, if (yearView) shownYear else monthIndex),
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { drag = 0f },
                                onDragEnd = { if (drag > 80f) step(-1) else if (drag < -80f) step(1) },
                            ) { _, dx -> drag += dx }
                        }
                        .padding(horizontal = 12.dp)
                        .padding(top = 6.dp),
                    transitionSpec = {
                        if (initialState.years != targetState.years) {
                            fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                        } else {
                            val forward = targetState.key > initialState.key
                            (slideInHorizontally(tween(220)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(180)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(160)))
                        }
                    },
                    label = "calendar page",
                ) { page ->
                    if (page.years) {
                        YearGrid(page.key, spans, colorOf, today, firstDay, onMonth = { m ->
                            monthIndex = m.index
                            yearView = false
                        }, Modifier.padding(horizontal = 8.dp))
                    } else {
                        val layout = remember(page.key, spans, firstDay) { TripGrid.month(CalMonth.ofIndex(page.key), firstDay, spans) }
                        MonthGrid(layout, byId, dayFlags, colorOf, today, onOpen = { t, d -> onOpenDay(t.id, d) }, onNewTrip = if (demoMode) null else onNewTripFrom)
                    }
                }
            }
            if (overlaps.isNotEmpty()) item(key = "overlaps") { OverlapLines(overlaps, Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) }
            if (split.undated.isNotEmpty()) {
                item(key = "undated") { SectionLabel("No dates yet", Modifier.padding(start = 20.dp, top = 22.dp, bottom = 2.dp)) }
                items(split.undated, key = { "undated-${it.id}" }) { t ->
                    UndatedRow(t, world.tripCountries[t.id].orEmpty(), colorOf(t.id), onOpen = { onOpenTrip(t.id) }, onDates = { onAddDates(t.id) })
                }
            }
        }
    }
}

/** A trip without dates: its colour, name and flags; a tap opens it, "Add dates" edits it. */
@Composable
private fun UndatedRow(trip: Trip, flags: List<String>, color: Color, onOpen: () -> Unit, onDates: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(trip.title, Modifier.weight(1f), style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TripFlags(flags, size = 14.dp, max = 3)
        Text(
            "Add dates",
            Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onDates).padding(horizontal = 12.dp, vertical = 12.dp),
            style = Bt.text.button, color = Bt.colors.accentInk, maxLines = 1,
        )
    }
}

/** Nothing yet: one line and New trip (Exit demo in demo mode). */
@Composable
private fun EmptyCalendar(line: String, button: String, danger: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(line, style = Bt.text.body, textAlign = TextAlign.Center)
        BtButton(button, onClick = onClick, icon = if (danger) BtIcons.Close else BtIcons.Add, danger = danger)
    }
}

/** Overlapping trips, one small line each ("Kyoto and Lisbon overlap · 3–5 Apr 2027"). */
@Composable
private fun OverlapLines(overlaps: List<TripAnalytics.Overlap>, modifier: Modifier = Modifier) {
    if (overlaps.isEmpty()) return
    val c = Bt.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        overlaps.take(3).forEach { o ->
            Text(
                "${o.first.title} and ${o.second.title} overlap · ${Dates.range(o.from, o.to)}",
                style = Bt.text.fine, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (overlaps.size > 3) Text("${overlaps.size - 3} more overlaps", style = Bt.text.fine, color = c.danger)
    }
}

private val NUMBER_H = 26.dp
private val LANE_H = 18.dp
private const val MAX_LANES = 3

/**
 * One month with every trip as a coloured bar over its days, with its title (the Android app's MonthGrid). A day
 * inside one trip opens that trip on the day; a day two trips share asks which; an empty day offers a new trip from
 * there. Today is a filled circle.
 */
@Composable
private fun MonthGrid(
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
                        val inMonth = d in layout.month
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(
                                    if (inMonth) Modifier.clickable(role = Role.Button, onClickLabel = Dates.dayLong(d)) {
                                        val on = TripGrid.tripsOn(d, spans, layout.lanes)
                                        // An empty day offers a new trip, except in demo mode.
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
            d.civil.day.toString(),
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

/** A whole year: twelve small months, three across, each trip a band in its colour over its days. A month opens it. */
@Composable
private fun YearGrid(
    year: Int,
    trips: List<TripSpan>,
    colorOf: (String) -> Color,
    today: LocalDate,
    firstDay: Int,
    onMonth: (CalMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layouts = remember(year, trips, firstDay) { (1..12).map { TripGrid.month(CalMonth(year, it), firstDay, trips) } }
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
    val current = layout.month == CalMonth.of(today)
    // The month's short name in the phone's language (Android's YearGrid: Month.getDisplayName(TextStyle.SHORT, locale)).
    val locale = screenLocale()
    val name = remember(layout.month, locale) { PhoneTexts.shortMonth(layout.month.month, locale) }
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val numberStyle = Bt.text.label.copy(fontSize = 8.5.sp, lineHeight = 9.sp, fontWeight = FontWeight.Medium)
    val numbers = remember(numberStyle, measurer) { (1..31).map { measurer.measure(it.toString(), numberStyle) } }
    val todayNumber = remember(numberStyle, measurer) { measurer.measure(today.civil.day.toString(), numberStyle.copy(fontWeight = FontWeight.Bold)) }
    // Each day's top band colour, for the number's own colour on it.
    val topFill = remember(layout, colorOf) {
        val out = HashMap<Long, Color>()
        layout.bars.sortedByDescending { it.lane }.forEach { b ->
            val week = layout.weeks[b.week]
            for (i in b.from..b.to) out[week[i].toEpochDay()] = colorOf(b.tripId)
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
                    if (d !in layout.month) return@forEachIndexed
                    val isToday = d == today
                    val text = if (isToday) todayNumber else numbers[d.civil.day - 1]
                    val fill = topFill[d.toEpochDay()]
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
