package com.justdataplease.boringtravel.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.backup.BackupEntry
import com.justdataplease.boringtravel.backup.BackupProgress
import com.justdataplease.boringtravel.backup.BackupText
import com.justdataplease.boringtravel.google.DriveStep
import com.justdataplease.boringtravel.google.SignInOutcome
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.launch

/*
 * Google pieces shared by the first start (this package), Me (account, Backup & sync) and the community.
 */

/** The Activity behind a Compose context (Google's sign-in sheet opens over it). */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Returns a function that connects Google Drive: Google's account and consent screens open when needed, and
 * [onResult] gets [DriveStep.Connected], [DriveStep.Cancelled] or [DriveStep.Failed].
 */
@Composable
fun rememberDriveConnect(graph: AppGraph, onResult: (DriveStep) -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        scope.launch { latest(graph.google.finishDriveConsent(r.resultCode, r.data)) }
    }
    return remember(graph, launcher, scope) {
        {
            scope.launch {
                when (val step = graph.google.connectDrive()) {
                    is DriveStep.NeedsConsent -> launcher.launch(IntentSenderRequest.Builder(step.intent).build())
                    else -> latest(step)
                }
            }
        }
    }
}

/** How one tap on a Google button ended ([rememberGoogleConnect]). */
sealed interface GoogleConnect {
    /** Signed in and Google Drive connected (in a build without sign-in: Drive connected). */
    data object Connected : GoogleConnect

    /** Signed in, but Drive wasn't connected: [message] when Google failed, null when the person said no. */
    data class NoDrive(val message: String?) : GoogleConnect

    /** Not signed in: [message] when sign-in failed, null when the account sheet was closed. */
    data class NotSignedIn(val message: String?) : GoogleConnect

    companion object {
        /** The Drive step's end, once signed in. */
        fun afterDrive(step: DriveStep): GoogleConnect = when (step) {
            DriveStep.Connected -> Connected
            DriveStep.Cancelled, is DriveStep.NeedsConsent -> NoDrive(null)
            is DriveStep.Failed -> NoDrive(step.message)
        }
    }
}

/**
 * Returns the one Google button's action (owner, 2026-11-12: "To have 1 button"): Google's sign-in sheet, then straight
 * on to its Drive permission, two of Google's sheets with nothing of ours between them. Saying no to Drive still signs
 * in (Me › Backup & sync keeps "Connect Google Drive"). A build without sign-in goes to Drive directly. Google's
 * AuthorizationClient can't return the ID token Firebase needs (no public API in play-services-auth 21.4), so the two
 * stay separate requests.
 */
@Composable
fun rememberGoogleConnect(graph: AppGraph, onResult: (GoogleConnect) -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onResult)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectDrive = rememberDriveConnect(graph) { step -> latest(GoogleConnect.afterDrive(step)) }
    return remember(graph, context, scope, connectDrive) {
        {
            val activity = context.findActivity()
            if (activity == null) {
                latest(GoogleConnect.NotSignedIn(null))
            } else {
                scope.launch {
                    when (val outcome = graph.google.signIn(activity)) {
                        is SignInOutcome.SignedIn, SignInOutcome.NotSetUp -> connectDrive()
                        SignInOutcome.Cancelled -> latest(GoogleConnect.NotSignedIn(null))
                        is SignInOutcome.Failed -> latest(GoogleConnect.NotSignedIn(outcome.message))
                    }
                }
            }
        }
    }
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

/** A thin bar with what is happening; indeterminate when there is no count. */
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
 * "Found in your Google Drive · Backup from 5 Nov 2026, 22:00 · 2 trips · 31 entries · 148 photos" with
 * Restore / Start fresh. While [progress] runs, the buttons give way to the progress line.
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
