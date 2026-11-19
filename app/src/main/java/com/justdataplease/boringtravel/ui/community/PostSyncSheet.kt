package com.justdataplease.boringtravel.ui.community

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.launch

/**
 * The trip's community post, updated from the trip (owner, 17 Nov 2026: "a button in trip to … resync", then "manually
 * only, not to overprice Firestore"): when it was last updated (or why not), whether the trip changed since (worked out
 * on the phone), Update now, Open the post, and Stop updating it (the post stays as it is). From the trip's ⋮ ›
 * Community post.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostSyncSheet(graph: AppGraph, actions: AppActions, tripId: String, onClose: () -> Unit) {
    val sync = graph.postSync
    val links by sync.links.all.collectAsState()
    val running by sync.running.collectAsState()
    val link = links[tripId]
    LaunchedEffect(link == null) { if (link == null) onClose() }
    if (link == null) return
    val progress = running[tripId]
    var said by remember { mutableStateOf<String?>(null) }
    // Whether the post is behind the trip: checked on the phone when the sheet opens and after each update.
    var changed by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(link.sent, link.syncedAt) { changed = runCatching { sync.changed(tripId) }.getOrNull() }
    val c = Bt.colors
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Community post", style = Bt.text.title)
                if (link.title.isNotBlank()) Text(link.title, style = Bt.text.item)
                when {
                    progress != null -> Text(progress.label, style = Bt.text.sub)
                    link.problem != null -> Text(link.problem!!, style = Bt.text.sub, color = c.danger)
                    link.syncedAt > 0 -> Text("Last updated " + ago(link.syncedAt), style = Bt.text.sub)
                }
                if (progress == null) when (changed) {
                    true -> Text("The trip changed since then.", style = Bt.text.sub, color = c.accentInk)
                    false -> Text("The post shows the trip as it is now.", style = Bt.text.sub)
                    null -> Unit
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = c.primary, trackColor = c.line,
                )
            }
            BtButton(
                "Update now", onClick = { said = null; graph.scope.launch { said = sync.updateNow(tripId) } },
                modifier = Modifier.fillMaxWidth(), enabled = progress == null, icon = BtIcons.Send,
            )
            said?.let { Text(it, style = Bt.text.sub) }
            BtGhostButton("Open the post", onClick = { onClose(); actions.communityPost(link.postId) }, modifier = Modifier.fillMaxWidth())
            BtGhostButton("Stop updating it", onClick = { graph.scope.launch { sync.forget(tripId) }; onClose() }, modifier = Modifier.fillMaxWidth())
            Text(
                "Nothing goes up by itself. Update now sends the plan, the notes you chose and their photos; likes and the " +
                    "link stay. When you picked every note, new notes go too. Stop updating it and the post stays as it is.",
                style = Bt.text.fine,
            )
        }
    }
}

/** "just now", "5 minutes ago", "yesterday". */
private fun ago(at: Long): String {
    val now = System.currentTimeMillis()
    if (now - at < DateUtils.MINUTE_IN_MILLIS) return "just now"
    return DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString().replaceFirstChar { it.lowercase() }
}
