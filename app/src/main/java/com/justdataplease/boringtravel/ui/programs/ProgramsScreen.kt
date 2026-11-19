package com.justdataplease.boringtravel.ui.programs

import com.justdataplease.boringtravel.links.LinkPlaces
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.repo.now
import com.justdataplease.boringtravel.programs.Destination
import com.justdataplease.boringtravel.programs.Focus
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.ProgramLibrary
import com.justdataplease.boringtravel.programs.scene
import com.justdataplease.boringtravel.programs.ProgramTrips
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.DrawnScene
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.PhotoFrame
import com.justdataplease.boringtravel.ui.components.SectionLabel
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.trip.kindIcon
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Trip ideas: ready-made day-by-day programs. First the destinations (a drawn picture, the name, a line about it, how
 * many days, the best time to go); then, for one of them, two questions as chips (how you like to travel, what matters
 * most) over the matching program, which changes as the chips do: its days with their items, the ideas, and the
 * sources. "Use this program" asks for the first day and makes it a trip of the traveller's own (this ends demo mode),
 * opened on its Plan tab. Everything is bundled ([ProgramLibrary]); nothing is fetched.
 */
@Composable
fun ProgramsScreen(graph: AppGraph, actions: AppActions, destinationId: String?) {
    val destinations = remember { ProgramLibrary.destinations }
    val opened = remember(destinationId) { ProgramLibrary.destination(destinationId)?.id }
    var chosenId by rememberSaveable { mutableStateOf(opened) }
    var paceId by rememberSaveable { mutableStateOf(Pace.Default.id) }
    var focusId by rememberSaveable { mutableStateOf(Focus.Default.id) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val chosen = ProgramLibrary.destination(chosenId)
    val pace = Pace.of(paceId)
    val focus = Focus.of(focusId)
    // Opened on one destination (from Trips), Back leaves; picked from the list, Back goes back to the list.
    BackHandler(enabled = chosen != null && opened == null) { chosenId = null }

    fun use(destination: Destination, start: LocalDate) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            val built = ProgramTrips.build(destination, destination.program(focus), pace, start, now(), links = LinkPlaces::sortLinks)
            // Saved whole even if the screen goes away; ends demo mode like any new trip of the traveller's own.
            val made = withContext(NonCancellable) { runCatching { graph.trips.createWithPlan(built.trip, built.items) } }
            made.onSuccess { actions.ideaUsed(it.id) }.onFailure {
                failed = true
                busy = false
            }
        }
    }

    BtScreen {
        if (chosen == null) {
            DestinationList(destinations, onBack = actions::back, onPick = { chosenId = it.id })
        } else {
            ProgramView(
                chosen, pace, focus,
                onBack = { if (opened == null) chosenId = null else actions.back() },
                onPace = { paceId = it.id }, onFocus = { focusId = it.id },
                onUse = { picking = true }, busy = busy, failed = failed,
            )
        }
    }
    if (picking && chosen != null) {
        StartDateDialog(
            days = chosen.program(focus).days.size,
            onDismiss = { picking = false },
            onPick = { start ->
                picking = false
                use(chosen, start)
            },
        )
    }
}

// ---- Step 1: where ----

@Composable
private fun DestinationList(destinations: List<Destination>, onBack: () -> Unit, onPick: (Destination) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BtTopBar("Trip ideas", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "intro") {
                Text(
                    "Pick a place, answer two questions, get a day-by-day plan.",
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp), style = Bt.text.sub,
                )
            }
            items(destinations, key = { it.id }) { d -> DestinationCard(d) { onPick(d) } }
        }
    }
}

/** A destination as the trip cards show a trip: its drawn picture in the theme's frame, then the facts. */
@Composable
private fun DestinationCard(d: Destination, onClick: () -> Unit) {
    val days = remember(d) { d.default.days.size }
    BtCard(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        padding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
        onClick = onClick,
    ) {
        PhotoFrame(Modifier.fillMaxWidth().padding(bottom = 10.dp), tiltDeg = -1.5f) {
            DrawnScene(ProgramLibrary.scene(d), Modifier.fillMaxWidth().height(130.dp), key = d.id)
        }
        Eyebrow(Dates.days(days))
        Text(d.title, Modifier.padding(top = 4.dp), style = Bt.text.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (d.blurb.isNotBlank()) Text(d.blurb, Modifier.padding(top = 2.dp), style = Bt.text.sub, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (d.bestTime.isNotBlank()) Text(d.bestTime, Modifier.padding(top = 6.dp), style = Bt.text.fine, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---- Steps 2 and 3: two questions, and the program they pick ----

@Composable
private fun ProgramView(
    destination: Destination,
    pace: Pace,
    focus: Focus,
    onBack: () -> Unit,
    onPace: (Pace) -> Unit,
    onFocus: (Focus) -> Unit,
    onUse: () -> Unit,
    busy: Boolean,
    failed: Boolean,
) {
    val program = destination.program(focus)
    val days = remember(program, pace) { ProgramPreview.days(program, pace) }
    val ideas = remember(program) { ProgramPreview.ideas(program) }
    val sources = remember(program) { ProgramPreview.sources(program.sources) }
    Column(Modifier.fillMaxSize()) {
        BtTopBar(destination.title, onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                item(key = "pace") {
                    Question("How do you like to travel?", Pace.entries, pace, { it.label }, pace.blurb, onPace)
                }
                item(key = "focus") {
                    Question("What matters most?", Focus.entries, focus, { it.label }, focus.blurb, onFocus)
                }
                item(key = "program") {
                    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
                        BtDivider()
                        Eyebrow(ProgramPreview.facts(program), Modifier.padding(top = 16.dp))
                        Text(program.title, Modifier.padding(top = 4.dp), style = Bt.text.title)
                        if (program.blurb.isNotBlank()) Text(program.blurb, Modifier.padding(top = 2.dp), style = Bt.text.sub)
                        if (destination.bestTime.isNotBlank()) Text(destination.bestTime, Modifier.padding(top = 6.dp), style = Bt.text.fine)
                    }
                }
                for (day in days) {
                    item(key = "day-${day.number}") {
                        Text(
                            day.label, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
                            style = Bt.text.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    itemsIndexed(day.rows, key = { i, _ -> "day-${day.number}-$i" }) { _, row -> ItemRow(row) }
                }
                if (ideas.isNotEmpty()) {
                    item(key = "ideas") {
                        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp)) {
                            SectionLabel("Ideas")
                            Text("Not on a day. They go to Ideas in the plan.", Modifier.padding(top = 2.dp), style = Bt.text.fine)
                        }
                    }
                    itemsIndexed(ideas, key = { i, _ -> "idea-$i" }) { _, row -> ItemRow(row) }
                }
                if (sources != null) {
                    item(key = "sources") {
                        Text(sources, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp), style = Bt.text.fine)
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                if (failed) {
                    Text(
                        "Couldn't make the trip. Try again.",
                        Modifier.fillMaxWidth().padding(bottom = 6.dp), style = Bt.text.fine, color = Bt.colors.danger,
                    )
                }
                BtButton("Use this program", onClick = onUse, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
    }
}

/** A question and its answers as chips; the picked answer's one line under them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Question(title: String, options: List<T>, selected: T, label: (T) -> String, blurb: String, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
        Text(title, style = Bt.text.item)
        FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> BtChip(label(o), o == selected, { onPick(o) }) }
        }
        Text(blurb, style = Bt.text.sub)
    }
}

/** An item as the Plan shows it: the kind in a circle, the title, and "07:30 · note". */
@Composable
private fun ItemRow(row: ProgramPreview.Row) {
    val c = Bt.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 5.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.chip), contentAlignment = Alignment.Center) {
            Icon(kindIcon(row.kind, row.vehicle), null, Modifier.size(17.dp), tint = c.ink)
        }
        Column(Modifier.weight(1f)) {
            Text(row.title, style = Bt.text.item, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row.line.isNotBlank()) Text(row.line, Modifier.padding(top = 1.dp), style = Bt.text.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The first day of the trip; the headline asks for it, then shows the whole trip once a day is picked ("3–14 Oct
 * 2027"). "Make the trip" stays faded until then (it looked tappable and did nothing), then takes the accent colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDateDialog(days: Int, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState()
    val start = state.selectedDateMillis?.let(::utcDay)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            // The label's colour is set here: the theme's label style carries its own ink, which hid the disabled look.
            TextButton(enabled = start != null, onClick = { start?.let(onPick) }) {
                Text("Make the trip", color = if (start != null) Bt.colors.primary else Bt.colors.muted.copy(alpha = 0.45f))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(
            state,
            title = { Text(Dates.days(days), Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp), style = Bt.text.sub) },
            headline = {
                Text(
                    start?.let { Dates.range(it, it.plusDays((days - 1).coerceAtLeast(0).toLong())) } ?: "Pick the first day",
                    Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp), style = Bt.text.title,
                )
            },
        )
    }
}

private fun utcDay(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
