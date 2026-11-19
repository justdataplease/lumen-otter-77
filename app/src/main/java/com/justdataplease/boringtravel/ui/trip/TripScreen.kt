package com.justdataplease.boringtravel.ui.trip

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ui.calendar.CalendarSheet
import com.justdataplease.boringtravel.ui.components.DemoTag
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.BtBackButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.journal.TripJournalTab
import com.justdataplease.boringtravel.ui.map.MapScreen
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.trips.DeleteTripDialog
import com.justdataplease.boringtravel.ui.world.TripFlags
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private class Loaded(val trip: Trip?)

/**
 * One trip in trip mode (design boards "Plan · day by day"): back, "Day 14 of 31" (or the dates), the trip's name in
 * the display face, Plan with AI and the trip's menu; then the section on show. The sections are the bottom bar's
 * items while a trip is open (Plan · Journal · Map · Media, see TripNav); [tab] and the plan's [planDay] live in the
 * trip's back-stack entry (AppNav).
 */
@Composable
fun TripScreen(graph: AppGraph, actions: AppActions, tripId: String, tab: TripTab, planDay: String?, onPlanDay: (String?) -> Unit) {
    val loaded by remember(tripId) { graph.trips.observeTrip(tripId).map { Loaded(it) } }.collectAsState(initial = null)
    val trip = loaded?.trip
    val world by graph.world.world.collectAsState()
    val flags = world?.tripCountries?.get(tripId).orEmpty()
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    var togetherOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    var postOpen by rememberSaveable { mutableStateOf(false) }
    // The trip's community post, when it keeps up with the trip (community/PostSync).
    val postLinks by graph.postSync.links.all.collectAsState()
    val postLink = postLinks[tripId]
    val sharing by graph.together.state.collectAsState()
    val shared = sharing.of(tripId)
    // A shared trip keeps in step with the others while it is open and in front (not with the app in the background).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(tripId, shared != null, lifecycle) {
        if (shared != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { graph.together.keepInSync(tripId) }
    }
    var closed by remember { mutableStateOf(false) }
    val gone = loaded != null && (trip == null || trip.deletedAt != null)
    LaunchedEffect(gone) {
        if (gone && !closed) {
            closed = true
            actions.back()
        }
    }
    BtScreen {
        Column(Modifier.fillMaxSize()) {
            TripHeader(
                trip, flags, actions, tripId, onCalendar = { calendarOpen = true }, onTogether = { togetherOpen = true },
                onDelete = { deleteOpen = true }, sharedWith = shared?.members?.count { !it.left }, shared = shared != null,
                onPost = if (postLink != null) ({ postOpen = true }) else null,
            )
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) },
                label = "Trip section",
            ) { section ->
                Box(Modifier.fillMaxSize()) {
                    when (section) {
                        TripTab.Plan -> PlanTab(graph, actions, tripId, trip, planDay, onPlanDay)
                        TripTab.Journal -> TripJournalTab(graph, actions, tripId)
                        TripTab.Map -> MapScreen(graph, actions, tripId = tripId)
                        TripTab.Media -> TripMediaTab(graph, actions, tripId)
                    }
                }
            }
        }
    }
    if (calendarOpen) CalendarSheet(graph, tripId, onClose = { calendarOpen = false })
    // Deleted (or left): the screen closes itself when the trip goes (above).
    if (deleteOpen && !gone) DeleteTripDialog(graph, tripId, onDismiss = { deleteOpen = false }, onDone = { deleteOpen = false })
    if (postOpen && postLink != null) {
        com.justdataplease.boringtravel.ui.community.PostSyncSheet(graph, actions, tripId, onClose = { postOpen = false })
    }
    if (togetherOpen) {
        com.justdataplease.boringtravel.ui.together.TogetherSheet(graph, tripId, onClose = { togetherOpen = false }, onLeft = { togetherOpen = false })
    }
}

@Composable
private fun TripHeader(
    trip: Trip?,
    flags: List<String>,
    actions: AppActions,
    tripId: String,
    onCalendar: () -> Unit,
    onTogether: () -> Unit,
    onDelete: () -> Unit,
    sharedWith: Int?,
    shared: Boolean,
    onPost: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Back leaves the trip (to Trips, or wherever it was opened from: the Calendar, the Map); tabs never stack up.
        BtBackButton(actions::back)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(dateLine(trip), Modifier.weight(1f, fill = false))
                if (trip?.isDemo == true) DemoTag(Modifier.padding(start = 6.dp))
                TripFlags(flags, Modifier.padding(start = 6.dp), size = 14.dp, max = 3)
            }
            Text(
                Bt.text.display(trip?.title.orEmpty()),
                style = Bt.text.h2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // A shared trip shows who it is shared with; the button opens Travel together.
        if (sharedWith != null) SharedBadge(sharedWith, onTogether)
        BtIconButton(BtIcons.Sparkle, "Plan with AI", { actions.planner(tripId, chat = true) })
        Box {
            BtIconButton(BtIcons.More, "Trip options", { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Bt.colors.surface) {
                DropdownMenuItem(text = { Text("Edit trip") }, leadingIcon = { Icon(BtIcons.Edit, null) }, onClick = {
                    menuOpen = false; actions.editTrip(tripId)
                })
                // A Google Maps list, a blog or a Google Doc into this trip (Copy from a link).
                DropdownMenuItem(text = { Text("Add from a link") }, leadingIcon = { Icon(BtIcons.Link, null) }, onClick = {
                    menuOpen = false; actions.copySite(tripId = tripId)
                })
                // Owner, 2026-11-11: "in 3 dots add post to community second".
                DropdownMenuItem(text = { Text("Post to community") }, leadingIcon = { Icon(BtIcons.Community, null) }, onClick = {
                    menuOpen = false; actions.postToCommunity(tripId)
                })
                // The trip's post: update it now (only by hand), open it, stop updating it.
                if (onPost != null) {
                    DropdownMenuItem(text = { Text("Community post") }, leadingIcon = { Icon(BtIcons.Share, null) }, onClick = {
                        menuOpen = false; onPost()
                    })
                }
                DropdownMenuItem(text = { Text("Make a clip") }, leadingIcon = { Icon(BtIcons.Movie, null) }, onClick = {
                    menuOpen = false; actions.clip(tripId)
                })
                // Owner, 2026-11-10: "have in menu make clip, make book, print".
                DropdownMenuItem(text = { Text("Make a book") }, leadingIcon = { Icon(BtIcons.Journal, null) }, onClick = {
                    menuOpen = false; actions.book(tripId)
                })
                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(BtIcons.Share, null) }, onClick = {
                    menuOpen = false; actions.export(tripId)
                })
                DropdownMenuItem(text = { Text("Calendar") }, leadingIcon = { Icon(BtIcons.Calendar, null) }, onClick = {
                    menuOpen = false; onCalendar()
                })
                DropdownMenuItem(text = { Text("Travel together") }, leadingIcon = { Icon(BtIcons.Me, null) }, onClick = {
                    menuOpen = false; onTogether()
                })
                // Owner, 2026-11-11: "also make able to delete trip". Last, in the danger colour; a shared trip is left.
                val danger = Bt.colors.danger
                DropdownMenuItem(
                    text = { Text(if (shared) "Leave trip" else "Delete trip", color = danger) },
                    leadingIcon = { Icon(BtIcons.Delete, null, tint = danger) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/** "Day 14 of 31" while the trip is on, else its dates ("1–31 Mar 2027 · 31 days"). */
private fun dateLine(trip: Trip?, today: LocalDate = LocalDate.now()): String {
    val s = trip?.startDate
    val e = trip?.endDate
    val days = trip?.days
    return when {
        trip == null -> ""
        s == null || e == null || days == null -> "Dates to be decided"
        trip.isCurrent(today) -> "Day ${trip.dayNumber(today)} of $days"
        else -> "${Dates.range(s, e)} · $days ${if (days == 1) "day" else "days"}"
    }
}

/** "Shared" with the number of others, in the trip header. */
@Composable
private fun SharedBadge(others: Int, onClick: () -> Unit) {
    val c = Bt.colors
    androidx.compose.foundation.layout.Row(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(c.accent.copy(alpha = 0.22f))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Travel together", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(BtIcons.Me, null, Modifier.size(16.dp), tint = c.ink)
        Text(if (others > 0) "+$others" else "Shared", style = Bt.text.label, color = c.ink)
    }
}
