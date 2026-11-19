package com.justdataplease.boringtravel.ui.together

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.together.ShareLink
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt

/** A quiet row at the end of Trips: paste a friend's trip link to join it. */
@Composable
fun JoinTripRow(onJoin: (ShareLink) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { open = true }.padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Bt.colors.chip), contentAlignment = Alignment.Center) {
            Icon(BtIcons.Me, null, Modifier.size(19.dp), tint = Bt.colors.ink)
        }
        Column(Modifier.weight(1f)) {
            Text("Join a friend's trip", style = Bt.text.item, maxLines = 1)
            Text("Paste the link they sent you", style = Bt.text.sub, maxLines = 1)
        }
        Icon(BtIcons.Forward, null, Modifier.size(20.dp), tint = Bt.colors.muted)
    }
    if (open) JoinDialog(onDismiss = { open = false }, onJoin = { open = false; onJoin(it) })
}

@Composable
private fun JoinDialog(onDismiss: () -> Unit, onJoin: (ShareLink) -> Unit) {
    val c = Bt.colors
    var text by rememberSaveable { mutableStateOf("") }
    val link = ShareLink.find(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Join a trip", style = Bt.text.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(4_000) },
                    placeholder = { Text("Paste the link or the whole message", style = Bt.text.sub) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line, cursorColor = c.ink),
                )
                if (text.isNotBlank() && link == null) Text("That doesn't look like a BoringTravel trip link.", style = Bt.text.sub, color = c.danger)
            }
        },
        confirmButton = {
            TextButton(onClick = { link?.let(onJoin) }, enabled = link != null) { Text("Next", style = Bt.text.button, color = if (link != null) c.ink else c.muted) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = Bt.text.button, color = c.ink) } },
    )
}
