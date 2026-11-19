package com.justdataplease.boringtravel.ui.together

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.together.Member
import com.justdataplease.boringtravel.together.ShareLink
import com.justdataplease.boringtravel.together.SyncStatus
import com.justdataplease.boringtravel.together.TripMail
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.onboarding.findActivity
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Travel together, from the trip's menu: invite people to plan and write the trip with you, see who is in, sync now,
 * leave. Shared trips sit in each member's own Google Drive (see together/); the trip's mailbox tells the others where
 * your copy is, so a friend only opens the link and joins. Only when that can't work (an older app, no sign-in, or
 * nobody picked it up after a while) a small line offers your link to send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TogetherSheet(graph: AppGraph, tripId: String, onClose: () -> Unit, onLeft: () -> Unit) {
    val c = Bt.colors
    val context = LocalContext.current
    val together = graph.together
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val trip by remember(tripId) { graph.trips.observeTrip(tripId) }.collectAsState(initial = null)
    val state by together.state.collectAsState()
    val statuses by together.status.collectAsState()
    val driveOn by graph.settings.driveConnected.collectAsState(initial = false)
    val shared = state.of(tripId)
    val status = statuses[tripId] ?: SyncStatus.Idle
    val title = trip?.title.orEmpty()

    val work = rememberSheetWork(graph.scope, "Something went wrong. Try again.")
    // Google's screens are open (sign-in, Drive): the buttons wait for them.
    var googling by remember { mutableStateOf(false) }
    val busy = work.busy || googling
    var confirmLeave by remember { mutableStateOf(false) }
    // What to do once Google is ready: send an invite, or only make this phone's copy.
    var pendingInvite by remember { mutableStateOf(true) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // The member ids last read in the trip's mailbox (null: not read), for "Send your link".
    var inBox by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(Unit) { together.engine.load() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(tripId) {
        withContext(Dispatchers.IO) { together.mail }?.seen?.collect { inBox = it[tripId] }
    }
    // A look at the mailbox (when the trip's screen isn't watching it already), after a quiet Firebase sign-in for a
    // trip shared before the mailbox existed.
    val isShared = shared != null
    LaunchedEffect(isShared) {
        if (!isShared) return@LaunchedEffect
        together.signInForMail(context.findActivity() ?: context, quiet = true, onceARun = true)
        together.checkMail(tripId, read = !together.mailWatched(tripId))
    }

    /**
     * After Google's screens: makes this phone's copy if needed (first time: folder, file, link); with [invite], hands
     * the link to the share sheet.
     */
    fun go(invite: Boolean) = work.runAfter {
        val link = together.share(tripId, title)
        if (invite) TogetherText.send(context, link, invite = true) else together.checkMail(tripId, read = false)
    }

    val google = rememberTogetherGoogle(graph) { drive, problem ->
        googling = false
        if (drive) go(pendingInvite) else if (problem != null) work.notice = problem
    }

    fun withGoogle(invite: Boolean) {
        pendingInvite = invite
        googling = true
        work.notice = null
        google()
    }

    /** This phone's link, for a member whose app can't pick it up from the mailbox. */
    fun sendMine() = work.run {
        val link = together.engine.linkFor(tripId, title) ?: together.share(tripId, title)
        TogetherText.send(context, link, invite = false)
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
            Eyebrow("Travel together")
            when {
                trip?.isDemo == true -> Text("Example trips can't be shared. Make a trip of your own first.", style = Bt.text.sub)

                confirmLeave -> {
                    Text("Leave this trip?", style = Bt.text.title)
                    Text(TogetherText.LEAVE, style = Bt.text.sub)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BtGhostButton("Cancel", onClick = { confirmLeave = false }, modifier = Modifier.weight(1f))
                        BtButton("Leave", enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                            work.run {
                                graph.deletions.leaveTrip(tripId)
                                onLeft()
                            }
                        })
                    }
                }

                // Not shared yet, or a first share that stopped before its file was made (finished by the next sync).
                shared == null || (shared.fileId == null && shared.members.isEmpty()) -> {
                    Text("Plan and write this trip with friends", style = Bt.text.title)
                    Text(
                        "Everyone can add places, change the plan, write and add photos, and make their own videos. " +
                            "Friends only open your link and join. Changes show up in about half a minute while you " +
                            "both have the trip open.",
                        style = Bt.text.sub,
                    )
                    Note(
                        "Your copy of the trip goes to your Google Drive. Anyone with the link can see it, so send it " +
                            "only to the people you travel with.",
                    )
                    BtButton(if (driveOn) "Invite" else "Connect Drive and invite", enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { withGoogle(invite = true) })
                }

                else -> {
                    MemberRow(name = shared.myName.ifBlank { "You" }, line = if (shared.fileId == null) "Viewing only" else "You", initial = shared.myName)
                    shared.members.forEach { m ->
                        MemberRow(m.name.ifBlank { "Someone" }, memberLine(m, m.id in shared.unreachable, now), m.name)
                    }
                    BtDivider()
                    SyncLine(status, shared.syncedAt, now)
                    val syncNow: () -> Unit = {
                        work.run {
                            together.syncNow(tripId)
                            together.checkMail(tripId)
                        }
                    }
                    val syncing = busy || status is SyncStatus.Working
                    when {
                        // Joined without Drive, or its copy isn't made yet.
                        shared.fileId == null -> {
                            Text(
                                if (driveOn) "You can see the trip. Your changes reach the others once your copy is in your Google Drive."
                                else "You can see the trip. To add your changes, connect Google Drive.",
                                style = Bt.text.sub,
                            )
                            BtButton(if (driveOn) "Try again" else "Connect Drive", enabled = !busy, onClick = { withGoogle(invite = false) })
                        }
                        else -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                BtButton("Invite", enabled = !busy, modifier = Modifier.weight(1f), onClick = { withGoogle(invite = true) })
                                BtGhostButton("Sync now", enabled = !syncing, modifier = Modifier.weight(1f), onClick = syncNow)
                            }
                            if (shared.members.isEmpty()) Text("Friends who open your link and join show up here.", style = Bt.text.sub)
                            // Only when the mailbox can't be counted on for this phone's copy.
                            if (TripMail.offerLink(shared, inBox, now)) {
                                LinkLine("Not showing up for the others?", "Send your link", enabled = !busy, onClick = { sendMine() })
                            }
                        }
                    }
                    BtGhostButton("Leave trip", enabled = !busy, onClick = { confirmLeave = true })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line)
            work.notice?.let { Text(it, style = Bt.text.sub, color = c.danger) }
        }
    }
}

@Composable
private fun Note(text: String) {
    val c = Bt.colors
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.chip).padding(12.dp), style = Bt.text.sub)
}

/** A quiet line with one action in it, for what only some people need ("Not showing up…? Send your link"). */
@Composable
internal fun LinkLine(text: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Bt.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, Modifier.weight(1f, fill = false), style = Bt.text.sub)
        Text(
            action,
            Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
            style = Bt.text.sub.copy(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline),
            color = if (enabled) c.ink else c.muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun MemberRow(name: String, line: String, initial: String) {
    val c = Bt.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            Text(
                initial.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•",
                style = Bt.text.item.copy(fontWeight = FontWeight.Bold),
                color = if (Bt.tokens.look.dark) c.onPrimary else c.ink,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = Bt.text.item, maxLines = 1)
            Text(line, style = Bt.text.sub, maxLines = 1)
        }
    }
}

@Composable
private fun SyncLine(status: SyncStatus, syncedAt: Long, now: Long) {
    val text = when (status) {
        SyncStatus.Working -> "Syncing…"
        is SyncStatus.Failed -> status.message
        else -> if (syncedAt > 0) "Up to date · ${TogetherText.ago(syncedAt, now)}" else "Not synced yet"
    }
    Text(text, style = Bt.text.sub, color = if (status is SyncStatus.Failed) Bt.colors.danger else Bt.colors.muted)
}

private fun memberLine(m: Member, unreachable: Boolean, now: Long): String = when {
    m.left -> "Left the trip"
    unreachable -> "Can't reach their copy"
    m.fileId == null -> "Viewing only"
    m.seenAt > 0 -> "Changed ${TogetherText.ago(m.seenAt, now)}"
    else -> "Joined"
}

/** Texts for shared trips: the invite, a member's own link (when the mailbox can't carry it), leaving, times. */
object TogetherText {
    /** What leaving a shared trip does (here and in Edit trip › Delete). */
    const val LEAVE = "It goes from this phone. The others keep it, with what you added. Your copy in Drive stops being shared."

    /** "Maria joined Spain 2027", for the word shown when the mailbox adds someone ([names] may be blank). */
    fun joined(names: List<String>, title: String): String {
        val known = names.map { it.trim() }.filter { it.isNotEmpty() }
        val who = when {
            known.isEmpty() -> if (names.size > 1) "${names.size} friends" else "A friend"
            known.size == 1 -> known[0]
            known.size == 2 -> "${known[0]} and ${known[1]}"
            else -> "${known[0]} and ${known.size - 1} more"
        }
        return "$who joined ${title.trim().ifEmpty { "your trip" }}"
    }

    fun invite(link: ShareLink): String =
        "Join my trip “${link.title.ifBlank { "Trip" }}” on BoringTravel, so we can plan it together:\n${link.url}"

    fun sendBack(link: ShareLink): String =
        "I joined “${link.title.ifBlank { "the trip" }}”. Open this so my changes show on your phone too:\n${link.url}"

    fun send(context: Context, link: ShareLink, invite: Boolean) {
        val text = if (invite) invite(link) else sendBack(link)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, link.title.ifBlank { "BoringTravel trip" })
        val chooser = Intent.createChooser(send, if (invite) "Invite to the trip" else "Send your link")
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /** "just now", "2 min ago", "3 h ago", "2 days ago". */
    fun ago(at: Long, now: Long): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            s < 2 * 86_400 -> "yesterday"
            else -> "${s / 86_400} days ago"
        }
    }
}
