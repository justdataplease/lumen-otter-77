package com.justdataplease.boringtravel.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.calendar.CalendarAccessException
import com.justdataplease.boringtravel.calendar.CalendarGoneException
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One quiet line on the Plan tab when the trip is in a calendar and the plan has changed since: "Update" brings the
 * calendar in step. Automatic connections show queued/paused state; the sheet manages access and the destination.
 */
@Composable
fun CalendarPrompt(graph: AppGraph, trip: Trip, items: List<PlanItem>, modifier: Modifier = Modifier) {
    val c = Bt.colors
    val link by remember(trip.id) { graph.calendar.link(trip.id) }.collectAsState(initial = null)
    val chosen by remember(trip.id) { graph.calendar.chosenZone(trip.id) }.collectAsState(initial = null)
    val pending by remember(trip.id) { graph.calendar.pendingUpdate(trip.id) }.collectAsState(initial = null)
    val l = link
    val behind = remember(trip, items, l, chosen) { l != null && l.isBehind(trip, items, chosen) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<CalendarStart?>(null) }

    LaunchedEffect(message) {
        if (message != null) {
            delay(3000)
            message = null
        }
    }

    fun update() {
        if (busy) return
        if (!graph.calendar.phone.canWrite()) {
            sheet = CalendarStart.Update
            return
        }
        busy = true
        graph.scope.launch(Dispatchers.Main) {
            try {
                message = updated(graph.calendar.update(trip.id))
            } catch (e: CancellationException) {
                throw e
            } catch (_: CalendarAccessException) {
                sheet = CalendarStart.Update
            } catch (_: CalendarGoneException) {
                sheet = CalendarStart.Update
            } catch (_: Exception) {
                message = "Couldn't update the calendar. Try again."
            } finally {
                busy = false
            }
        }
    }

    if (behind || pending != null || message != null || busy) {
        Row(
            modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(BtIcons.Calendar, null, Modifier.size(16.dp), tint = c.muted)
            Text(
                message ?: if (busy) "Updating the calendar…" else pending?.label
                    ?: if (l?.autoUpdate == true) "Calendar update will be queued." else "The calendar is behind the plan.",
                Modifier.weight(1f),
                style = Bt.text.fine,
            )
            if (message == null && !busy) {
                Text(
                    if (pending?.phase == "paused") "Review" else "Update",
                    Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button,
                        onClick = { if (pending?.phase == "paused") sheet = CalendarStart.Home else update() }).padding(horizontal = 8.dp, vertical = 14.dp),
                    style = Bt.text.label.copy(fontWeight = FontWeight.SemiBold),
                    color = c.accentInk,
                )
            }
        }
    }

    sheet?.let { start -> CalendarSheet(graph, trip.id, onClose = { sheet = null }, startWith = start) }
}
