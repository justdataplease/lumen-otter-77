package com.justdataplease.boringtravel.ui.community

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.community.ReportReason
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * The community's sheets and small dialogs: the rules agreed once before the first profile or post, Report (posts and
 * profiles), Block, the ⋮ menu, and Copy this trip's first-day picker.
 */

/**
 * The community's rules (Google Play's user-generated content policy), shown once before the first profile save or
 * post. [onAgree] stores the answer and carries on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesSheet(onAgree: () -> Unit, onDismiss: () -> Unit) {
    val c = Bt.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Community rules", style = Bt.text.h2)
            RuleLine(BtIcons.Heart, "Be kind.")
            RuleLine(BtIcons.Photo, "Post only your own photos and clips.")
            RuleLine(BtIcons.Block, "No nudity, hate, violence or spam.")
            RuleLine(BtIcons.Delete, "Posts that break these rules are removed, and so are the people who post them.")
            Text(
                "Your photos stay in your Google Drive, in a folder anyone with its link can view.",
                Modifier.padding(top = 4.dp), style = Bt.text.sub,
            )
            BtButton("Agree", onClick = onAgree, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            BtGhostButton("Not now", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RuleLine(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Bt.colors.chip), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(17.dp), tint = Bt.colors.ink)
        }
        Text(text, Modifier.weight(1f), style = Bt.text.body)
    }
}

/**
 * Report a post or a profile ([what]: "this post", "@alexa"): a tap on a reason sends it. [send] returns false when
 * the report waits for a connection (it goes then), and throws when it couldn't; the sheet then says so and stays.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(what: String, send: suspend (ReportReason) -> Boolean, onDone: () -> Unit) {
    val c = Bt.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("Report $what", style = Bt.text.h2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Why? We look at every report.", Modifier.padding(top = 2.dp, bottom = 8.dp), style = Bt.text.sub)
            ReportReason.entries.forEach { reason ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(btRowShape)
                        .clickable(enabled = !busy, role = Role.Button) {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    val sent = send(reason)
                                    Toast.makeText(context, if (sent) "Report sent. Thank you." else QUEUED, Toast.LENGTH_SHORT).show()
                                    onDone()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = communityMessage(e, "Couldn't send the report. Try again.")
                                    busy = false
                                }
                            }
                        }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(reason.label, Modifier.weight(1f), style = Bt.text.body)
                    Icon(BtIcons.Forward, null, Modifier.size(18.dp), tint = c.muted)
                }
            }
            if (busy) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(20.dp), color = c.muted, strokeWidth = 2.dp)
            error?.let { Text(it, Modifier.padding(top = 8.dp), style = Bt.text.sub, color = c.danger) }
        }
    }
}

private val btRowShape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)

/** One line of a ⋮ menu. */
data class MenuLine(val label: String, val icon: ImageVector, val danger: Boolean = false, val onClick: () -> Unit)

/** The ⋮ button and its menu, in the theme's surface. [onImage] draws the button for a photo behind it. */
@Composable
fun MoreMenu(lines: List<MenuLine>, modifier: Modifier = Modifier, description: String = "More") {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        BtIconButton(BtIcons.More, description, { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Bt.colors.surface) {
            lines.forEach { line ->
                val tint = if (line.danger) Bt.colors.danger else Bt.colors.ink
                DropdownMenuItem(
                    text = { Text(line.label, style = Bt.text.body, color = tint) },
                    leadingIcon = { Icon(line.icon, null, tint = tint) },
                    onClick = {
                        open = false
                        line.onClick()
                    },
                )
            }
        }
    }
}

/**
 * Copy this trip, asked the way a trip idea asks (ProgramsScreen's StartDateDialog): what it copies and how long it is
 * on top, "Pick the first day" until one is picked, then the whole trip ("12–23 Mar 2027"); Copy stays faded until
 * then. Without dates makes every item an idea.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopyTripDialog(days: Int, onDismiss: () -> Unit, onCopy: (LocalDate?) -> Unit) {
    val c = Bt.colors
    val state = rememberDatePickerState()
    val start = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = start != null, onClick = { onCopy(start) }) {
                Text("Copy", style = Bt.text.button, color = if (start != null) c.primary else c.muted.copy(alpha = 0.45f))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onCopy(null) }) { Text("Without dates", style = Bt.text.button, color = c.ink) }
                TextButton(onClick = onDismiss) { Text("Cancel", style = Bt.text.button, color = c.muted) }
            }
        },
    ) {
        DatePicker(
            state,
            title = {
                Text(
                    "The plan, without the journal · ${Dates.days(days)}",
                    Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp), style = Bt.text.sub,
                )
            },
            headline = {
                Text(
                    start?.let { Dates.range(it, it.plusDays((days - 1).coerceAtLeast(0).toLong())) } ?: "Pick the first day",
                    Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp), style = Bt.text.title,
                )
            },
        )
    }
}
