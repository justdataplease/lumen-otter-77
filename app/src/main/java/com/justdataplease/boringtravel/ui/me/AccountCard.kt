package com.justdataplease.boringtravel.ui.me

import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.btCardShape
import com.justdataplease.boringtravel.ui.onboarding.AccountAvatar
import com.justdataplease.boringtravel.ui.onboarding.GoogleConnect
import com.justdataplease.boringtravel.ui.onboarding.findActivity
import com.justdataplease.boringtravel.ui.onboarding.rememberGoogleConnect
import com.justdataplease.boringtravel.ui.theme.Bt
import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import com.justdataplease.boringtravel.ui.icons.BtIcons

/** Me's account card: who is signed in, whether Drive is connected, Sign in / Sign out, Delete account. */
@Composable
fun AccountCard(graph: AppGraph, modifier: Modifier = Modifier) {
    val email by graph.settings.accountEmail.collectAsState(initial = null)
    val name by graph.settings.accountName.collectAsState(initial = null)
    val driveConnected by graph.settings.driveConnected.collectAsState(initial = false)
    val needsReconnect by graph.google.needsReconnect.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var askSignOut by remember { mutableStateOf(false) }
    var askRemove by remember { mutableStateOf(false) }
    // One tap: Google's sign-in, then its Drive permission; a no to Drive still signs in, without a word.
    val signIn = rememberGoogleConnect(graph) { result ->
        busy = false
        message = when (result) {
            GoogleConnect.Connected -> null
            is GoogleConnect.NoDrive -> result.message
            is GoogleConnect.NotSignedIn -> result.message
        }
    }

    val drive = when {
        driveConnected && needsReconnect -> "Google Drive needs you to connect again"
        driveConnected -> "Google Drive connected"
        else -> "Google Drive not connected"
    }

    BtCard(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (email != null) AccountAvatar(name ?: email, size = 44.dp) else Box(
                Modifier.size(44.dp).clip(CircleShape).background(Bt.colors.chip),
                contentAlignment = Alignment.Center,
            ) { Icon(BtIcons.Me, contentDescription = null, tint = Bt.colors.muted, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f)) {
                val signedIn = email
                if (signedIn != null) {
                    Text(name ?: signedIn, style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (name != null) "Signed in as $signedIn" else "Signed in with Google", style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(drive, style = Bt.text.sub)
                } else {
                    Text("Not signed in", style = Bt.text.item)
                    Text(if (driveConnected) drive else "Your trips are saved on this phone.", style = Bt.text.sub)
                    if (!graph.google.signInConfigured) Text("Google sign-in isn't set up in this build.", style = Bt.text.fine)
                }
            }
            when {
                email != null -> BtGhostButton("Sign out", onClick = { askSignOut = true }, enabled = !busy)
                graph.google.signInConfigured -> BtButton(
                    "Sign in",
                    small = true,
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = null
                        signIn()
                    },
                )
            }
        }
        message?.let { Text(it, Modifier.padding(top = 8.dp), style = Bt.text.sub, color = Bt.colors.danger) }
        if (email != null || driveConnected) {
            Text(
                "Delete account",
                Modifier.padding(top = 8.dp).heightIn(min = 40.dp).clickable(role = Role.Button, enabled = !busy) { askRemove = true }
                    .padding(vertical = 10.dp),
                style = Bt.text.sub,
                color = Bt.colors.danger,
            )
        }
    }

    if (askRemove) {
        AlertDialog(
            onDismissRequest = { askRemove = false },
            confirmButton = {
                BtButton("Delete", small = true, danger = true, onClick = {
                    askRemove = false
                    busy = true
                    message = null
                    val activity = context.findActivity()
                    scope.launch {
                        // Runs to the end even if Me is left meanwhile (half a deletion would leave data behind).
                        val done = withContext(NonCancellable) {
                            // The community first (profile, username, posts, likes; posts' Drive folders while Drive
                            // is still connected). If that fails the account stays, so it can be tried again.
                            val community = if (graph.community.available && activity != null) {
                                try {
                                    graph.community.deleteAccount(activity)
                                    true
                                } catch (e: Exception) {
                                    Log.w("AccountCard", "Community data not deleted", e)
                                    false
                                }
                            } else {
                                true
                            }
                            if (community) {
                                graph.ownAi.clearAll() // Me › AI: own keys, choices and their encryption key.
                                graph.google.removeAccount()
                            }
                            community
                        }
                        if (!done) message = "Couldn't delete your community data. Check the connection and try again."
                        busy = false
                    }
                })
            },
            dismissButton = { BtGhostButton("Cancel", onClick = { askRemove = false }) },
            title = { Text("Delete account?", style = Bt.text.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "BoringTravel signs out, gives its Google Drive access back to Google and deletes your Google name, " +
                            "email and own AI keys from this phone. Your community profile, username, posts, likes and follows are " +
                            "deleted too.",
                        style = Bt.text.body,
                    )
                    Text(
                        "Your trips stay on this phone, and backups stay in the BoringTravel folder of your Drive. " +
                            "To remove those too, see the page below.",
                        style = Bt.text.body,
                    )
                    Text(
                        "How to delete everything",
                        Modifier.heightIn(min = 40.dp).clickable(role = Role.Button) { openDeletionPage(context) }.padding(vertical = 10.dp),
                        style = Bt.text.sub,
                        color = Bt.colors.accentInk,
                    )
                }
            },
            shape = btCardShape(),
            containerColor = Bt.colors.surface,
            titleContentColor = Bt.colors.ink,
            textContentColor = Bt.colors.ink,
        )
    }

    if (askSignOut) {
        ConfirmDialog(
            title = "Sign out?",
            body = "Backups to Google Drive stop until you sign in again. Your trips and journal stay on this phone.",
            confirm = "Sign out",
            onConfirm = {
                askSignOut = false
                busy = true
                scope.launch {
                    graph.google.signOut()
                    busy = false
                }
            },
            onDismiss = { askSignOut = false },
        )
    }
}

/** A plain in-app question in the current look. */
@Composable
internal fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) =
    BtConfirmDialog(title, body, confirm, onConfirm, onDismiss, danger = danger)
