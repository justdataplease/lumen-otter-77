package com.justdataplease.boringtravel.ui.tripcal

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.BoringTravelApp
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.tripcal.TripAnalytics
import com.justdataplease.boringtravel.tripcal.TripColors
import com.justdataplease.boringtravel.tripcal.TripDays
import com.justdataplease.boringtravel.tripcal.TripGrid
import com.justdataplease.boringtravel.tripcal.TripSpan
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
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.world.TripFlags
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The Calendar tab: every trip on a calendar and nothing else (owner, after preview 6: "only the calendar month and
 * year", no statistics). Month · Year (arrows or a swipe; it opens on this month), each trip a bar in its own colour;
 * overlapping trips flagged; trips without dates listed. It shows what Trips shows (the examples in demo mode). A day
 * in a trip opens the trip on that day; an empty day offers a new trip from there. No New trip or Plan with AI
 * buttons here (owner): only the empty state has New trip. In demo mode nothing offers a new trip (owner, 2026-11-11:
 * "in order to create a new trip should not be in demo"): the empty state has Exit demo, as on Trips.
 */
@Composable
fun TripsCalendarScreen(actions: AppActions) {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as BoringTravelApp).graph }
    TripsCalendar(graph, actions)
}

/** One page of the calendar: a month ([key] = [TripGrid.index]) or a year ([key] = the year). */
private data class Page(val years: Boolean, val key: Int)

@Composable
private fun TripsCalendar(graph: AppGraph, actions: AppActions) {
    val trips by remember(graph) { graph.trips.observeTrips() }.collectAsStateWithLifecycle(initialValue = null)
    // Countries come from the same place as the flags on Trips (and are the demo's in demo mode).
    val world by graph.world.world.collectAsStateWithLifecycle()
    val demoMode by graph.settings.demoMode.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    val exitDemo: () -> Unit = { scope.launch { graph.demo.set(false) } }
    val today = remember { LocalDate.now() }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val monthFormat = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }

    val w = world
    val split = remember(trips, w) { TripSpan.split(trips.orEmpty(), w?.tripCountries.orEmpty(), w?.tripDayCountries.orEmpty()) }
    val spans = split.spans
    val byId = remember(spans) { spans.associateBy { it.id } }
    val dayFlags = remember(spans) { spans.associate { it.id to TripDays.countries(it) } }
    val tokens = Bt.tokens
    val colorIndex = remember(spans) { TripColors.assign(spans) }
    val colorOf = remember(colorIndex, tokens) { { id: String -> TripPalette.color(tokens, colorIndex[id] ?: TripColors.preferred(id)) } }

    var yearView by rememberSaveable { mutableStateOf(false) }
    val todayIndex = TripGrid.index(YearMonth.from(today))
    var monthIndex by rememberSaveable { mutableIntStateOf(todayIndex) }
    val month = TripGrid.monthOf(monthIndex)
    val shownYear = month.year
    fun step(n: Int) {
        monthIndex += if (yearView) 12 * n else n
    }

    val overlaps = remember(spans) { TripAnalytics.overlaps(spans) }
    val flags = w?.tripCountries.orEmpty()

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
            if (trips == null) return@LazyColumn
            if (spans.isEmpty() && split.undated.isEmpty()) {
                item(key = "empty") {
                    if (demoMode) EmptyCalendar("No example trips on the calendar.", "Exit demo", danger = true, onClick = exitDemo)
                    else EmptyCalendar("No trips on the calendar yet.", "New trip", onClick = actions::newTrip)
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
                val away = if (yearView) shownYear != today.year else monthIndex != todayIndex
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (yearView) Bt.text.display(shownYear.toString()) else monthFormat.format(month).replaceFirstChar { it.titlecase(locale) },
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
                        YearGrid(page.key, firstDay, spans, colorOf, today, onMonth = { m ->
                            monthIndex = TripGrid.index(m)
                            yearView = false
                        }, Modifier.padding(horizontal = 8.dp))
                    } else {
                        val layout = remember(page.key, firstDay, spans) { TripGrid.month(TripGrid.monthOf(page.key), firstDay, spans) }
                        MonthGrid(
                            layout, byId, dayFlags, colorOf, today,
                            onOpen = { t, d -> actions.openPlanDay(t.id, d) },
                            onNewTrip = if (demoMode) null else actions::newTripFrom,
                        )
                    }
                }
            }
            if (overlaps.isNotEmpty()) item(key = "overlaps") { OverlapLines(overlaps, Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) }
            if (split.undated.isNotEmpty()) {
                item(key = "undated") { SectionLabel("No dates yet", Modifier.padding(start = 20.dp, top = 22.dp, bottom = 2.dp)) }
                items(split.undated, key = { "undated-${it.id}" }) { t ->
                    UndatedRow(t, flags[t.id].orEmpty(), colorOf(t.id), onOpen = { actions.openTrip(t.id) }, onDates = { actions.editTrip(t.id) })
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
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(trip.title, Modifier.weight(1f, fill = false), style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TripFlags(flags, size = 14.dp, max = 3)
        }
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
