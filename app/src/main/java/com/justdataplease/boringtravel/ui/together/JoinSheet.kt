package com.justdataplease.boringtravel.ui.together

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.together.ShareLink
import com.justdataplease.boringtravel.together.TogetherEngine
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.flow.first

/**
 * A trip link was opened (from a chat, the browser or pasted): join the trip, or add the friend who sent their own
 * link (the way before the mailbox). Join does in the same tap what Google needs (sign-in, Drive: rememberTogetherGoogle),
 * makes the joiner's own copy and puts its address in the trip's mailbox, so their changes reach the others by
 * themselves. Only when the others can't pick it up from there (an older app, no sign-in) is the joiner asked to send
 * their link once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinSheet(graph: AppGraph, link: ShareLink, onOpenTrip: (String) -> Unit, onClose: () -> Unit) {
    val c = Bt.colors
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val driveOn by graph.settings.driveConnected.collectAsState(initial = false)
    val state by graph.together.state.collectAsState()
    val work = rememberSheetWork(graph.scope, "Couldn't open the trip. Try again.")
    val busy = work.busy
    var outcome by remember(link) { mutableStateOf<TogetherEngine.Opened?>(null) }
    // After joining: true while Google and the copy are being set up.
    var settingUp by remember(link) { mutableStateOf(false) }
    // Whether the others pick this phone's copy up from the mailbox by themselves.
    var reaches by remember(link) { mutableStateOf(false) }
    val known = state.of(link.tripId)
    val from = link.name.ifBlank { "A friend" }
    val title = link.title.ifBlank { "a trip" }

    fun run(block: suspend () -> Unit) = work.run(block)

    /** This phone's copy in its Drive, and its address in the trip's mailbox (after Google's screens). */
    fun makeCopy() = work.runAfter {
        try {
            graph.together.share(link.tripId, graph.trips.get(link.tripId)?.title ?: link.title)
            reaches = graph.together.checkMail(link.tripId)
        } finally {
            settingUp = false
        }
    }

    val google = rememberTogetherGoogle(graph) { drive, problem ->
        if (drive) {
            makeCopy()
        } else {
            settingUp = false
            work.notice = problem
        }
    }

    fun sendMine() = run {
        val mine = graph.together.share(link.tripId, graph.trips.get(link.tripId)?.title ?: link.title)
        TogetherText.send(context, mine, invite = false)
        onOpenTrip(link.tripId)
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = if (Bt.tokens.pill) 22.dp else 10.dp, topEnd = if (Bt.tokens.pill) 22.dp else 10.dp),
        containerColor = if (c.paperDot != null) c.bg else c.surface,
        contentColor = c.ink,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.line) },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("Travel together")
            when (val o = outcome) {
                null -> if (known != null && link.memberId != known.me) {
                    Text("Add $from to “$title”?", style = Bt.text.title)
                    Text("Their changes will show on this phone.", style = Bt.text.sub)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BtGhostButton("Not now", onClick = onClose, modifier = Modifier.weight(1f))
                        BtButton("Add", enabled = !busy, modifier = Modifier.weight(1f), onClick = { run { outcome = graph.together.open(link) } })
                    }
                } else {
                    Text("Join “$title”", style = Bt.text.title)
                    Text("$from invited you to plan this trip together. You'll see the plan, the journal and the photos, and add your own.", style = Bt.text.sub)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BtGhostButton("Not now", onClick = onClose, modifier = Modifier.weight(1f))
                        BtButton("Join", enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                            run {
                                val opened = graph.together.open(link)
                                // A shared trip is the traveller's own: the demo steps aside, as for a new trip.
                                if (opened is TogetherEngine.Opened.Joined && graph.settings.demoMode.first()) graph.demo.set(false)
                                if (opened is TogetherEngine.Opened.Joined) settingUp = true
                                outcome = opened
                                // Then what Google needs for the joiner's own copy, in the same tap.
                                if (opened is TogetherEngine.Opened.Joined) google()
                            }
                        })
                    }
                }

                is TogetherEngine.Opened.Joined -> {
                    Text("You're in", style = Bt.text.title)
                    val hasCopy = state.of(o.tripId)?.fileId != null
                    when {
                        settingUp -> Text("Saving your copy of the trip to your Google Drive…", style = Bt.text.sub)
                        hasCopy && reaches -> {
                            Text("Your changes reach the others by themselves.", style = Bt.text.sub)
                            BtButton("Open the trip", onClick = { onOpenTrip(o.tripId) })
                        }
                        // Their app can't pick this copy up from the mailbox (an older app, or one not signed in).
                        hasCopy -> {
                            Text("One more step: send $from your link once, so your changes show on their phone too.", style = Bt.text.sub)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                BtGhostButton("Later", onClick = { onOpenTrip(o.tripId) }, modifier = Modifier.weight(1f))
                                BtButton("Send my link", enabled = !busy, modifier = Modifier.weight(1f), onClick = { sendMine() })
                            }
                        }
                        else -> {
                            Text("You can see the trip. To add your changes, connect Google Drive.", style = Bt.text.sub)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                BtGhostButton("Later", onClick = { onOpenTrip(o.tripId) }, modifier = Modifier.weight(1f))
                                BtButton(if (driveOn) "Try again" else "Connect Drive", enabled = !busy && !settingUp, modifier = Modifier.weight(1f), onClick = {
                                    settingUp = true
                                    work.notice = null
                                    google()
                                })
                            }
                        }
                    }
                }

                is TogetherEngine.Opened.Added -> {
                    Text("${o.name.ifBlank { from }} is in", style = Bt.text.title)
                    Text("Their changes will show here within a minute.", style = Bt.text.sub)
                    BtButton("Open the trip", onClick = { onOpenTrip(o.tripId) })
                }

                is TogetherEngine.Opened.Known -> {
                    Text("Already here", style = Bt.text.title)
                    Text(if (o.own) "This is your own link for this trip." else "$from is already in.", style = Bt.text.sub)
                    BtButton("Open the trip", onClick = { onOpenTrip(o.tripId) })
                }
            }
            if (busy || settingUp) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line)
            work.notice?.let { Text(it, style = Bt.text.sub, color = c.danger) }
        }
    }
}
