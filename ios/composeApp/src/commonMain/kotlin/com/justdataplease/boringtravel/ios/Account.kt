package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.backup.BackupEntry
import com.justdataplease.boringtravel.backup.BackupProgress
import com.justdataplease.boringtravel.backup.BackupText
import com.justdataplease.boringtravel.google.AppleOutcome
import com.justdataplease.boringtravel.google.GoogleAccount
import com.justdataplease.boringtravel.google.GoogleConnect
import com.justdataplease.boringtravel.google.LocalGoogle
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btCardShape
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The account's pieces, shared by the first start, Me and Backup & sync (the Android app's ui/onboarding/GoogleUi.kt
 * and ui/me/AccountCard.kt): the round initial, "Signed in with Google · Google Drive connected", the progress line, the
 * backup found in Drive, the outline button for pairs, the Account card, and Sign in with Apple's button beside Google's
 * (new on iPhone, Apple 4.8).
 */

/** The app's public pages (the Android app's ui/me/AboutApp.kt). */
object AccountPages {
    /** "How to delete everything": the account, the trips on the phone and the Drive backups. */
    const val DELETE_URL = "https://justdataplease.com/apps/boringtravel/delete-account.html"
}

/** The outline button with its label centred, for pairs of equal buttons ("Restore · Start fresh"). */
@Composable
fun CenteredGhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Bt.colors
    val shape = btControlShape()
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .border(1.dp, c.line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Bt.text.button, color = if (enabled) c.ink else c.muted.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round initial on the accent, as on the design canvas. */
@Composable
fun AccountAvatar(nameOrEmail: String?, size: Dp = 40.dp) {
    val c = Bt.colors
    val initial = nameOrEmail?.trim()?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "G"
    Box(Modifier.size(size).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
        Text(
            initial,
            style = Bt.text.item.copy(fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp),
            color = if (Bt.tokens.look.dark) c.onPrimary else c.ink,
        )
    }
}

/**
 * "Signed in with Google · Google Drive connected" above the first-start cards. [email] is the signed-in account;
 * without sign-in, [driveAccount] names the account Drive is connected with.
 */
@Composable
fun SignedInRow(email: String?, name: String?, driveConnected: Boolean, driveAccount: String? = null, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AccountAvatar(name ?: email ?: driveAccount)
        Column(Modifier.weight(1f)) {
            val title = when {
                email != null -> "Signed in with Google"
                driveConnected -> "Google Drive connected"
                else -> "Google Drive"
            }
            val sub = when {
                email != null && driveConnected -> "Google Drive connected"
                email != null -> email
                else -> driveAccount.orEmpty()
            }
            Text(title, style = Bt.text.item)
            if (sub.isNotEmpty()) Text(sub, style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A thin bar with what is happening ("Saving photos to Drive · 3 of 12"); indeterminate when there is no count. */
@Composable
fun ProgressLine(progress: BackupProgress, modifier: Modifier = Modifier) {
    val c = Bt.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val count = if (progress.total > 0) " · ${minOf(progress.done + 1, progress.total)} of ${progress.total}" else ""
        Text(progress.label + count, style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val barModifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50))
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = barModifier, color = c.route, trackColor = c.chip, drawStopIndicator = {})
        } else {
            LinearProgressIndicator(modifier = barModifier, color = c.route, trackColor = c.chip)
        }
    }
}

/**
 * "Found in your Google Drive · Backup from 5 Nov 2026, 22:00 · 2 trips · 31 entries · 148 photos" with Restore / Start
 * fresh. While [progress] runs, the buttons give way to the progress line.
 */
@Composable
fun BackupFoundCard(
    entry: BackupEntry,
    progress: BackupProgress?,
    error: String?,
    onRestore: () -> Unit,
    onStartFresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BtCard(modifier.fillMaxWidth()) {
        Eyebrow("Found in your Google Drive", accent = true)
        Text("Backup from ${BackupText.date(entry.createdAt)}", Modifier.padding(top = 6.dp), style = Bt.text.item)
        entry.items?.let { Text(BackupText.counts(it), style = Bt.text.sub) }
        if (error != null) Text(error, Modifier.padding(top = 8.dp), style = Bt.text.sub, color = Bt.colors.danger)
        if (progress != null) {
            ProgressLine(progress, Modifier.padding(top = 12.dp))
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BtButton(if (error != null) "Try again" else "Restore", onClick = onRestore, modifier = Modifier.weight(1f))
                CenteredGhostButton("Start fresh", onClick = onStartFresh, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Sign in with Apple's button: Apple's own on iPhone (ASAuthorizationAppleIDButton, which Apple's rules ask for), white
 * on the dark theme, black on the light ones; a drawn stand-in elsewhere ([DrawnAppleButton]). The tap is Compose's.
 */
interface AppleButton {
    @Composable
    fun Draw(dark: Boolean, modifier: Modifier, onClick: () -> Unit)
}

/** Apple's button drawn by Compose (the desktop, the tests and the pictures): its shape, colours and words. */
object DrawnAppleButton : AppleButton {
    @Composable
    override fun Draw(dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
        val fill = if (dark) Color.White else Color.Black
        val ink = if (dark) Color.Black else Color.White
        Box(
            modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp)).background(fill)
                .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = APPLE_LABEL; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            Text(APPLE_LABEL, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center), color = ink)
        }
    }
}

/** Apple's words on its button. */
const val APPLE_LABEL = "Sign in with Apple"

/** The phone's Sign in with Apple button. */
val LocalAppleButton = staticCompositionLocalOf<AppleButton> { DrawnAppleButton }

/** Sign in with Apple's button in the theme's contrast. */
@Composable
fun AppleSignInButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    LocalAppleButton.current.Draw(Bt.tokens.look.dark, modifier, onClick)
}

/**
 * Me's account card (the Android app's ui/me/AccountCard.kt): who is signed in, whether Drive is connected, Sign in /
 * Sign out, Delete account. On iPhone also Sign in with Apple beside Google's Sign in (Apple 4.8), and its account in
 * the card ("Signed in with Apple"); Delete account then revokes Apple's token too (Apple 5.1.1(v)). [deleteAtStart]
 * and [signOutAtStart] open its questions (the pictures).
 */
@Composable
fun AccountCard(settings: AppSettings, modifier: Modifier = Modifier, deleteAtStart: Boolean = false, signOutAtStart: Boolean = false, messageAtStart: String? = null) {
    val google = LocalGoogle.current
    val kit = LocalPlannerKit.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val appScope = LocalAppScope.current ?: scope
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(messageAtStart) }
    var askSignOut by remember { mutableStateOf(signOutAtStart) }
    var askRemove by remember { mutableStateOf(deleteAtStart) }
    val email = settings.accountEmail
    val name = settings.accountName
    val apple = settings.accountProvider == AppSettings.PROVIDER_APPLE
    val signedIn = settings.signedIn
    val driveConnected = settings.driveConnected
    val needsReconnect = google?.needsReconnect == true
    val configured = google?.signInConfigured == true

    // One tap: Google's page for the account and Drive; a no to Drive still signs in, without a word.
    fun signIn() {
        val g = google ?: return
        busy = true
        message = null
        scope.launch {
            val result = g.signIn()
            busy = false
            message = when (result) {
                GoogleConnect.Connected -> null
                is GoogleConnect.NoDrive -> result.message
                is GoogleConnect.NotSignedIn -> result.message
            }
        }
    }

    fun signInWithApple() {
        val g = google ?: return
        busy = true
        message = null
        scope.launch {
            val result = g.signInWithApple()
            busy = false
            message = (result as? AppleOutcome.Failed)?.message
        }
    }

    val drive = when {
        driveConnected && needsReconnect -> "Google Drive needs you to connect again"
        driveConnected -> "Google Drive connected"
        else -> "Google Drive not connected"
    }

    BtCard(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (signedIn) AccountAvatar(name ?: email ?: if (apple) "Apple" else null, size = 44.dp) else Box(
                Modifier.size(44.dp).clip(CircleShape).background(Bt.colors.chip),
                contentAlignment = Alignment.Center,
            ) { Icon(BtIcons.Me, contentDescription = null, tint = Bt.colors.muted, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f)) {
                when {
                    apple -> {
                        Text(name ?: email ?: "Apple ID", style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Signed in with Apple", style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(drive, style = Bt.text.sub)
                    }
                    email != null -> {
                        Text(name ?: email, style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (name != null) "Signed in as $email" else "Signed in with Google", style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(drive, style = Bt.text.sub)
                    }
                    else -> {
                        Text("Not signed in", style = Bt.text.item)
                        Text(if (driveConnected) drive else "Your trips are saved on this phone.", style = Bt.text.sub)
                        if (!configured) Text(GoogleAccount.NOT_SET_UP, style = Bt.text.fine)
                    }
                }
            }
            when {
                signedIn -> BtGhostButton("Sign out", onClick = { askSignOut = true }, enabled = !busy)
                configured -> BtButton("Sign in", small = true, enabled = !busy, onClick = ::signIn)
            }
        }
        // Sign in with Apple beside Google's (Apple 4.8), while no one is signed in.
        if (!signedIn && google?.appleConfigured == true) {
            AppleSignInButton(onClick = { if (!busy) signInWithApple() }, modifier = Modifier.padding(top = 12.dp))
        }
        message?.let { Text(it, Modifier.padding(top = 8.dp), style = Bt.text.sub, color = Bt.colors.danger) }
        if (signedIn || driveConnected) {
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
                    appScope.launch {
                        // Runs to the end even if Me is left meanwhile (half a deletion would leave data behind).
                        val failed = withContext(NonCancellable) {
                            // The Apple account first (Apple's sheet once more, its token revoked, the Firebase account
                            // deleted). If that fails the account stays, so it can be tried again.
                            val apple = google?.deleteAppleAccount()
                            if (apple == null) {
                                kit?.ownAi?.clearAll() // Me › AI: own keys and choices.
                                google?.removeAccount()
                            }
                            apple
                        }
                        message = failed
                        busy = false
                    }
                })
            },
            dismissButton = { BtGhostButton("Cancel", onClick = { askRemove = false }) },
            title = { Text("Delete account?", style = Bt.text.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (apple) {
                            "BoringTravel signs out, deletes your Sign in with Apple account (Apple asks you once more) and your own AI " +
                                "keys from this phone, and gives its Google Drive access back to Google."
                        } else {
                            "BoringTravel signs out, gives its Google Drive access back to Google and deletes your Google name, " +
                                "email and own AI keys from this phone."
                        },
                        style = Bt.text.body,
                    )
                    Text(
                        "Your trips stay on this phone, and backups stay in the BoringTravel folder of your Drive. " +
                            "To remove those too, see the page below.",
                        style = Bt.text.body,
                    )
                    Text(
                        "How to delete everything",
                        Modifier.heightIn(min = 40.dp).clickable(role = Role.Button) { runCatching { uri.openUri(AccountPages.DELETE_URL) } }.padding(vertical = 10.dp),
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
        BtConfirmDialog(
            title = "Sign out?",
            body = "Backups to Google Drive stop until you sign in again. Your trips and journal stay on this phone.",
            confirm = "Sign out",
            onConfirm = {
                askSignOut = false
                busy = true
                scope.launch {
                    google?.signOut()
                    busy = false
                }
            },
            onDismiss = { askSignOut = false },
        )
    }
}
