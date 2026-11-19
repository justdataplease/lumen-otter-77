package com.justdataplease.boringtravel.ui.calendar

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.calendar.CalendarAccessException
import com.justdataplease.boringtravel.calendar.CalendarAccounts
import com.justdataplease.boringtravel.calendar.CalendarGoneException
import com.justdataplease.boringtravel.calendar.CalendarGroup
import com.justdataplease.boringtravel.calendar.CalendarLink
import com.justdataplease.boringtravel.calendar.CalendarOwnershipException
import com.justdataplease.boringtravel.calendar.ImportCandidate
import com.justdataplease.boringtravel.calendar.SyncResult
import com.justdataplease.boringtravel.calendar.TripEvents
import com.justdataplease.boringtravel.calendar.TripZone
import com.justdataplease.boringtravel.calendar.ZoneChoice
import com.justdataplease.boringtravel.tracking.TrackingPermissions
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtRadioRow
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where the calendar sheet opens: its home, or straight into adding, updating or importing. */
enum class CalendarStart { Home, Add, Update, Import }

private enum class Step { Home, Ask, Denied, Pick, Zone, Import }
private enum class Goal { Add, Update, Remove, Import }

/** Wraps the observed link so "still loading" and "no link" differ. */
private class LinkState(val link: CalendarLink?)

/**
 * The trip and the phone's calendars: add (pick a calendar, grouped by account), update, remove, change the zone the
 * times are read in, share an .ics file, import events into the plan. Calendar access is asked only here, with one
 * plain line first; refused, the .ics file is offered instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSheet(graph: AppGraph, tripId: String, onClose: () -> Unit, startWith: CalendarStart = CalendarStart.Home) {
    val c = Bt.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cal = graph.calendar

    val trip by remember(tripId) { graph.trips.observeTrip(tripId) }.collectAsState(initial = null)
    val items by remember(tripId) { graph.plan.observeItems(tripId) }.collectAsState(initial = null)
    val linkState by remember(tripId) { cal.link(tripId).map { LinkState(it) } }.collectAsState(initial = null)
    val chosen by remember(tripId) { cal.chosenZone(tripId) }.collectAsState(initial = null)
    val link = linkState?.link
    val pending by remember(tripId) { cal.pendingUpdate(tripId) }.collectAsState(initial = null)

    var zones by remember { mutableStateOf<ZoneChoice?>(null) }
    LaunchedEffect(tripId, chosen, link?.zone) { zones = runCatching { cal.zones(tripId) }.getOrNull() }

    var step by remember { mutableStateOf(Step.Home) }
    var back by remember { mutableStateOf(Step.Home) }
    var goal by remember { mutableStateOf(Goal.Add) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var groups by remember { mutableStateOf<List<CalendarGroup>>(emptyList()) }
    var pickedId by remember { mutableStateOf<Long?>(null) }
    var candidates by remember { mutableStateOf<List<ImportCandidate>>(emptyList()) }
    var ticked by remember { mutableStateOf(setOf<String>()) }
    var confirmRemove by remember { mutableStateOf(false) }
    var settingsNext by remember { mutableStateOf(false) }

    val t = trip
    // Everything for the .ics file; for the phone's calendar, items imported from it stay out (they're there already).
    val events = remember(t, items) { if (t != null && items != null) TripEvents.build(t, items.orEmpty()) else emptyList() }
    val phoneEvents = remember(t, items) { if (t != null && items != null) TripEvents.build(t, items.orEmpty(), forPhone = true) else emptyList() }
    val imported = events.size - phoneEvents.size
    val title = t?.title.orEmpty()

    fun has(g: Goal) = if (g == Goal.Import) cal.phone.canRead() else cal.phone.canWrite()
    fun perms(g: Goal) = if (g == Goal.Import) arrayOf(Manifest.permission.READ_CALENDAR)
    else arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    /** Runs one calendar job in the app's scope (a closed sheet doesn't cut a write in half). */
    fun run(failure: String, work: suspend () -> String?) {
        if (busy) return
        busy = true
        notice = null
        graph.scope.launch(Dispatchers.Main) {
            try {
                notice = work()
            } catch (e: CancellationException) {
                throw e
            } catch (_: CalendarAccessException) {
                step = Step.Ask
            } catch (e: CalendarOwnershipException) {
                notice = e.message
            } catch (_: Exception) {
                notice = failure
            } finally {
                busy = false
            }
        }
    }

    fun shareIcs() = run("Couldn't make the file. Try again.") {
        val file = cal.icsFile(tripId)
        cal.shareIcs(context, file, title.ifBlank { "Trip" })
        null
    }

    fun proceed(g: Goal) {
        when (g) {
            Goal.Add -> run("Couldn't read the phone's calendars.") {
                val found = cal.groups()
                groups = found
                if (found.isEmpty()) {
                    step = Step.Home
                    "No calendar on this phone takes new events. Share an .ics file instead."
                } else {
                    pickedId = CalendarAccounts.defaultChoice(found, link?.calendarId, graph.settings.accountEmail.first())?.id
                    step = Step.Pick
                    null
                }
            }
            Goal.Update -> run("Couldn't update the calendar. Try again.") {
                try {
                    step = Step.Home
                    updated(cal.update(tripId))
                } catch (_: CalendarGoneException) {
                    val found = cal.groups()
                    groups = found
                    pickedId = CalendarAccounts.defaultChoice(found, null, graph.settings.accountEmail.first())?.id
                    step = if (found.isEmpty()) Step.Home else Step.Pick
                    "${link?.calendarName ?: "That calendar"} is no longer on this phone. Choose another."
                }
            }
            Goal.Remove -> run("Couldn't remove the events. Try again.") {
                step = Step.Home
                val name = link?.calendarName ?: "the calendar"
                val n = cal.remove(tripId)
                if (n == 0) "Removed from $name." else "Removed ${count(n, "event")} from $name."
            }
            Goal.Import -> run("Couldn't read the phone's calendars.") {
                val found = cal.importCandidates(tripId)
                candidates = found
                ticked = found.filter { it.guessed && !it.inPlan }.map { it.externalId }.toSet()
                step = Step.Import
                null
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (has(goal)) {
            proceed(goal)
        } else {
            // Refused for good ("Don't allow" twice): the next step is the app's settings page.
            val activity = context.findActivity()
            settingsNext = activity != null && perms(goal).any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }
            step = Step.Denied
        }
    }

    fun start(g: Goal) {
        goal = g
        notice = null
        confirmRemove = false
        if (has(g)) proceed(g) else step = Step.Ask
    }

    // Back from the app's settings with access allowed: carry on.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (step == Step.Denied && has(goal)) proceed(goal) }

    LaunchedEffect(Unit) {
        when (startWith) {
            CalendarStart.Home -> Unit
            CalendarStart.Add -> start(Goal.Add)
            CalendarStart.Update -> start(Goal.Update)
            CalendarStart.Import -> start(Goal.Import)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = if (Bt.tokens.pill) 22.dp else 10.dp, topEnd = if (Bt.tokens.pill) 22.dp else 10.dp),
        containerColor = if (c.paperDot != null) c.bg else c.surface,
        contentColor = c.ink,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.line) },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val zoneLine: @Composable () -> Unit = {
                val z = zones
                if (z != null && events.any { it.time is com.justdataplease.boringtravel.calendar.EventTime.At }) {
                    ZoneLine(z.current, t?.startDate ?: LocalDate.now(), onChange = { back = step; step = Step.Zone })
                }
            }
            when (step) {
                Step.Home -> {
                    Eyebrow("Calendar")
                    if (link == null && t != null && events.isEmpty()) {
                        Text("Nothing to add yet. Give the trip dates, or put plan items on days.", style = Bt.text.sub)
                    } else if (link != null) {
                        LinkedRow(link, onChange = { start(Goal.Add) })
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Keep calendar updated", style = Bt.text.item)
                                Text("Plan changes update this calendar automatically.", style = Bt.text.fine)
                            }
                            Switch(checked = link.autoUpdate, enabled = !busy,
                                modifier = Modifier.semantics { contentDescription = "Keep calendar updated" }, onCheckedChange = { enabled ->
                                run("Couldn't change calendar updates.") { cal.setAutomatic(tripId, enabled); null }
                            })
                        }
                        val behind = t != null && items != null && link.isBehind(t, items.orEmpty(), chosen)
                        if (pending != null) Text(pending!!.label, style = Bt.text.sub)
                        else if (behind) Text(if (link.autoUpdate) "Calendar update will be queued." else "The calendar is behind the plan.", style = Bt.text.sub)
                        Text("Your phone's calendar account handles online sync. Calendar edits aren't imported back automatically.", style = Bt.text.fine)
                        BtButton("Update calendar", onClick = { start(Goal.Update) }, modifier = Modifier.fillMaxWidth(), icon = BtIcons.Calendar, enabled = !busy)
                        if (imported > 0) Text("Items imported from a calendar aren't added again.", style = Bt.text.fine)
                        if (!confirmRemove) {
                            BtGhostButton("Remove from calendar", onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth(), enabled = !busy, danger = true)
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Remove the trip's events from ${link.calendarName}?", Modifier.weight(1f), style = Bt.text.item)
                                TextButton(onClick = { confirmRemove = false }) { Text("Keep") }
                                TextButton(onClick = { start(Goal.Remove) }) { Text("Remove", color = c.danger) }
                            }
                        }
                    } else if (linkState != null) {
                        Text("The trip as an all-day event, and each planned item on its day.", style = Bt.text.sub)
                        Text("Stays up to date: when you change the plan, the calendar changes too.", style = Bt.text.fine)
                        BtButton("Add to calendar", onClick = { start(Goal.Add) }, modifier = Modifier.fillMaxWidth(), icon = BtIcons.Calendar, enabled = !busy && phoneEvents.isNotEmpty())
                        if (imported > 0) Text("Items imported from a calendar aren't added again.", style = Bt.text.fine)
                    }
                    if (events.isNotEmpty()) {
                        BtGhostButton("Share .ics file", onClick = ::shareIcs, modifier = Modifier.fillMaxWidth(), icon = BtIcons.Share, enabled = !busy)
                        Text("For Apple Calendar, Outlook or anyone else. A copy: it doesn't update.", style = Bt.text.fine)
                    }
                    if (t?.startDate != null) {
                        BtGhostButton("Import from calendar", onClick = { start(Goal.Import) }, modifier = Modifier.fillMaxWidth(), icon = BtIcons.Download, enabled = !busy)
                    }
                    zoneLine()
                }

                Step.Ask -> {
                    Eyebrow("Calendar access")
                    Text(
                        when (goal) {
                            Goal.Import -> "To list the trip's events, BoringTravel reads your calendars."
                            Goal.Add -> "To add the trip, BoringTravel needs your calendars. It only changes events it adds."
                            else -> "To change the trip's events, BoringTravel needs your calendars. It only touches events it added."
                        },
                        style = Bt.text.body,
                    )
                    BtButton("Allow", onClick = { launcher.launch(perms(goal)) }, modifier = Modifier.fillMaxWidth())
                    if (goal != Goal.Import && events.isNotEmpty()) BtGhostButton("Share .ics file instead", onClick = ::shareIcs, modifier = Modifier.fillMaxWidth(), icon = BtIcons.Share)
                    BackLink { step = Step.Home }
                }

                Step.Denied -> {
                    Eyebrow("Calendar access")
                    Text("No calendar access.", style = Bt.text.item)
                    Text(
                        if (goal == Goal.Import) "Allow it to import events. Nothing else needs it."
                        else "Share an .ics file instead; any calendar app can open it.",
                        style = Bt.text.sub,
                    )
                    if (goal != Goal.Import && events.isNotEmpty()) BtButton("Share .ics file", onClick = ::shareIcs, modifier = Modifier.fillMaxWidth(), icon = BtIcons.Share)
                    if (settingsNext) {
                        BtGhostButton("Open settings", onClick = { context.openAppSettings() }, modifier = Modifier.fillMaxWidth())
                    } else {
                        BtGhostButton("Try again", onClick = { launcher.launch(perms(goal)) }, modifier = Modifier.fillMaxWidth())
                    }
                    BackLink { step = Step.Home }
                }

                Step.Pick -> {
                    Eyebrow("Choose a calendar")
                    groups.forEach { g ->
                        Column {
                            Text(g.title, style = Bt.text.eyebrow, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            g.calendars.forEach { calInfo ->
                                CalendarRow(calInfo.name, calInfo.color, calInfo.id == pickedId) { pickedId = calInfo.id }
                            }
                        }
                    }
                    zoneLine()
                    Text("After adding, plan changes update this calendar automatically. You can turn this off at any time.", style = Bt.text.fine)
                    val picked = groups.flatMap { it.calendars }.firstOrNull { it.id == pickedId }
                    val moving = link != null && picked != null && picked.id != link.calendarId
                    BtButton(
                        when {
                            moving -> "Move to ${picked.name}"
                            else -> "Add ${count(phoneEvents.size, "event")}"
                        },
                        onClick = {
                            val p = picked ?: return@BtButton
                            val zone = zones?.current ?: return@BtButton
                            goal = Goal.Add
                            run("Couldn't add the events. Try again.") {
                                val r = cal.add(tripId, p, zone)
                                step = Step.Home
                                if (moving) "Moved to ${r.calendarName}." else "Added to ${r.calendarName}."
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && picked != null && zones != null && phoneEvents.isNotEmpty(),
                    )
                    BackLink { step = Step.Home }
                }

                Step.Zone -> {
                    Eyebrow("Time zone")
                    Text("Plan times are local times where you'll be.", style = Bt.text.sub)
                    val z = zones
                    val on = t?.startDate ?: LocalDate.now()
                    z?.options?.forEach { zone ->
                        BtRadioRow(TripZone.city(zone), "${TripZone.offsetLabel(zone, on)} · ${zone.id}", zone == z.current, onClick = {
                            zones = z.copy(current = zone)
                            scope.launch { cal.setZone(tripId, zone) }
                            step = back
                        })
                    }
                    BackLink { step = back }
                }

                Step.Import -> {
                    Eyebrow("Import from calendar")
                    if (candidates.isEmpty()) {
                        Text("No events in the trip's dates.", style = Bt.text.sub)
                    } else {
                        Text("Events in the trip's dates. Ticked ones become plan items.", style = Bt.text.sub)
                        Column {
                            candidates.forEach { cand ->
                                ImportRow(cand, cand.externalId in ticked) { on ->
                                    ticked = if (on) ticked + cand.externalId else ticked - cand.externalId
                                }
                            }
                        }
                    }
                    val chosenOnes = candidates.filter { !it.inPlan && it.externalId in ticked }
                    BtButton(
                        if (chosenOnes.isEmpty()) "Add to the plan" else "Add ${count(chosenOnes.size, "item")} to the plan",
                        onClick = {
                            run("Couldn't add them to the plan. Try again.") {
                                val n = cal.import(tripId, chosenOnes)
                                step = Step.Home
                                if (n == 0) "Nothing new to add." else "Added ${count(n, "item")} to the plan."
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && chosenOnes.isNotEmpty(),
                    )
                    BackLink { step = Step.Home }
                }
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.route, trackColor = c.line)
            notice?.let { Text(it, style = Bt.text.item) }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** "Calendar updated: 2 new, 1 changed." */
internal fun updated(r: SyncResult): String {
    if (r.nothing) return "${r.calendarName} is up to date."
    val parts = listOfNotNull(
        r.added.takeIf { it > 0 }?.let { "$it new" },
        r.changed.takeIf { it > 0 }?.let { "$it changed" },
        r.removed.takeIf { it > 0 }?.let { "$it removed" },
    )
    return if (parts.isEmpty()) "${r.calendarName} is up to date." else "Calendar updated: ${parts.joinToString(", ")}."
}

private fun count(n: Int, word: String) = "$n ${if (n == 1) word else word + "s"}"

/** The linked calendar: colour, name, account, when it was last written; "Change" picks another. */
@Composable
private fun LinkedRow(link: CalendarLink, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ColorDot(link.color)
        Column(Modifier.weight(1f)) {
            Text(link.calendarName, style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val at = Instant.ofEpochMilli(link.updatedAt).atZone(ZoneId.systemDefault())
            Text("${link.accountLabel} · updated ${Dates.dayMonth(at.toLocalDate())}, ${Dates.time(at.hour * 60 + at.minute)}", style = Bt.text.sub, maxLines = 2)
        }
        SmallAction("Change", onChange)
    }
}

@Composable
private fun ZoneLine(zone: ZoneId, on: LocalDate, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Times in ${TripZone.label(zone, on)}", Modifier.weight(1f), style = Bt.text.sub)
        SmallAction("Change", onChange)
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        style = Bt.text.label.copy(fontWeight = FontWeight.SemiBold),
        color = Bt.colors.accentInk,
    )
}

@Composable
private fun BackLink(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SmallAction("Back", onClick) }
}

/** A calendar's own colour (providers store ARGB, sometimes without alpha). */
@Composable
private fun ColorDot(argb: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).clip(CircleShape).background(if (argb == 0) Bt.colors.muted else Color(argb or 0xFF000000.toInt())))
}

@Composable
private fun CalendarRow(name: String, color: Int, selected: Boolean, onClick: () -> Unit) {
    val c = Bt.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).border(if (selected) 6.dp else 2.dp, if (selected) c.primary else c.muted, CircleShape))
        ColorDot(color)
        Text(name, Modifier.weight(1f), style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Tick box, calendar colour, title, then day, time, kind and calendar; "In the plan" ones can't be ticked. */
@Composable
private fun ImportRow(cand: ImportCandidate, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val c = Bt.colors
    val enabled = !cand.inPlan
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Checkbox) { onToggle(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = checked && enabled,
            onCheckedChange = if (enabled) onToggle else null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = c.primary, uncheckedColor = c.muted, checkmarkColor = c.onPrimary,
                disabledCheckedColor = c.line, disabledUncheckedColor = c.line,
            ),
        )
        ColorDot(cand.calendar?.color ?: 0)
        Column(Modifier.weight(1f)) {
            Text(cand.title, style = Bt.text.item, color = if (enabled) c.ink else c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(
                Dates.dayShort(cand.day),
                cand.timeMinutes?.let(Dates::time),
                cand.kind.label.takeIf { cand.guessed },
                cand.calendar?.name,
            ).joinToString(" · ")
            Text(line, style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (cand.inPlan) Text("In the plan", style = Bt.text.fine)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openAppSettings() {
    try {
        startActivity(TrackingPermissions.appSettingsIntent(this))
    } catch (_: ActivityNotFoundException) {
    }
}
