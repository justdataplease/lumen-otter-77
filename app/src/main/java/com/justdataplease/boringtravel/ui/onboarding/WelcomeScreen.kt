package com.justdataplease.boringtravel.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.justdataplease.boringtravel.ui.me.PrivacyLink
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.R
import com.justdataplease.boringtravel.backup.BackupEntry
import com.justdataplease.boringtravel.backup.BackupProgress
import com.justdataplease.boringtravel.google.readable
import com.justdataplease.boringtravel.ui.components.BrandMark
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The first-start steps. Every one can be skipped; nothing here blocks using the app on the phone. */
private enum class Step { Welcome, Looking, Found }

/**
 * First start: Welcome → Continue with Google (Google's sign-in, then its Drive permission: one tap) or use without an
 * account → "Found in your Google Drive" Restore / Start fresh, when a backup is there → Trips. Nothing here asks for
 * location (owner, 2026-11-12): route tracking stays off until it is switched on in Me › Route tracking.
 */
@Composable
fun WelcomeScreen(graph: AppGraph, actions: AppActions) {
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(Step.Welcome) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<BackupEntry?>(null) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    val email by graph.settings.accountEmail.collectAsState(initial = null)
    val name by graph.settings.accountName.collectAsState(initial = null)
    val progress by graph.backups.progress.collectAsState()

    fun finish() = scope.launch {
        graph.settings.setOnboardingDone(true)
        actions.finishOnboarding()
    }

    val connect = rememberGoogleConnect(graph) { result ->
        busy = false
        when (result) {
            GoogleConnect.Connected -> {
                message = null
                step = Step.Looking
            }
            // No to Drive: signed in, trips on this phone; Me › Backup & sync can connect Drive later.
            is GoogleConnect.NoDrive -> if (result.message == null) finish() else message = result.message
            is GoogleConnect.NotSignedIn -> message = result.message
        }
    }

    fun restore(entry: BackupEntry) {
        restoreError = null
        // The app's scope: a restore keeps going if the screen is rebuilt meanwhile.
        graph.scope.launch {
            val error = try {
                graph.backups.restore(entry)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.readable("The backup couldn't be restored. Try again.")
            }
            withContext(Dispatchers.Main) {
                if (error == null) finish() else restoreError = error
            }
        }
    }

    // Looking in Drive (again after the screen was rebuilt, since the found backup isn't saved with it).
    LaunchedEffect(step) {
        when {
            step == Step.Looking -> {
                var looked = true
                val latest = try {
                    graph.backups.latest()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    looked = false
                    null // Offline or Drive busy: the app looks again when it is next opened (ui/me/RestoreOffer).
                }
                found = latest
                // Nothing in Drive to ask about: this phone's backups can start.
                if (latest == null && looked) graph.settings.setRestoreAsked(true)
                if (latest != null) step = Step.Found else finish()
            }
            step == Step.Found && found == null -> step = Step.Looking
        }
    }

    BtScreen {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            BrandMark()
            Text(Bt.text.display("BoringTravel"), style = Bt.text.h1.copy(fontSize = Bt.tokens.type.h1 * 1.25f))
            Text(stringResource(R.string.tagline), Modifier.widthIn(max = 300.dp), style = Bt.text.body.copy(fontSize = Bt.text.body.fontSize * 1.1f, color = Bt.colors.muted))
            Spacer(Modifier.weight(1f))
            when (step) {
                Step.Welcome -> WelcomeButtons(
                    signInConfigured = graph.google.signInConfigured,
                    busy = busy,
                    onGoogle = {
                        busy = true
                        message = null
                        connect()
                    },
                    onLocal = { finish() },
                )
                Step.Looking -> {
                    SignedInRow(email, name, driveConnected = true, driveAccount = graph.google.driveAccount)
                    BtCard(Modifier.fillMaxWidth()) {
                        Eyebrow("Google Drive", accent = true)
                        ProgressLine(BackupProgress(restoring = true, label = "Looking for a backup"), Modifier.padding(top = 8.dp))
                    }
                }
                Step.Found -> {
                    SignedInRow(email, name, driveConnected = true, driveAccount = graph.google.driveAccount)
                    found?.let { entry ->
                        BackupFoundCard(
                            entry = entry,
                            progress = progress,
                            error = restoreError,
                            onRestore = { restore(entry) },
                            onStartFresh = {
                                graph.scope.launch { graph.settings.setRestoreAsked(true) }
                                finish()
                            },
                        )
                    }
                }
            }
            message?.let { Text(it, style = Bt.text.sub, color = Bt.colors.danger) }
            Text("Your journal lives on this phone. Backups and exports go to your own Google Drive.", style = Bt.text.fine)
            PrivacyLink()
        }
    }
}

@Composable
private fun WelcomeButtons(signInConfigured: Boolean, busy: Boolean, onGoogle: () -> Unit, onLocal: () -> Unit) {
    BtCard(Modifier.fillMaxWidth()) {
        Eyebrow("Why Google", accent = true)
        Text("We need access to your Google Drive for backups", Modifier.padding(top = 6.dp), style = Bt.text.item)
        Text(
            "Your trips and journal are saved to your own Drive, so a new phone can bring them back. Google asks for your account, then for Drive. The app only sees the files it makes.",
            style = Bt.text.sub,
        )
    }
    if (!signInConfigured) {
        Text("Google sign-in isn't set up in this build. You can still connect Google Drive.", style = Bt.text.sub)
    }
    val label = when {
        busy -> "Connecting…"
        signInConfigured -> "Continue with Google"
        else -> "Continue"
    }
    BtButton(label, onClick = onGoogle, modifier = Modifier.fillMaxWidth(), enabled = !busy)
    CenteredGhostButton("Use without an account", onClick = onLocal, modifier = Modifier.fillMaxWidth(), enabled = !busy)
}
