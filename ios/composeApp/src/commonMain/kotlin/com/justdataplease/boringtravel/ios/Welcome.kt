package com.justdataplease.boringtravel.ios

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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.backup.BackupEntry
import com.justdataplease.boringtravel.backup.BackupProgress
import com.justdataplease.boringtravel.google.AppleOutcome
import com.justdataplease.boringtravel.google.GoogleAccount
import com.justdataplease.boringtravel.google.GoogleConnect
import com.justdataplease.boringtravel.google.LocalGoogle
import com.justdataplease.boringtravel.google.readable
import com.justdataplease.boringtravel.ui.components.BrandMark
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtHeader
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The first-start steps. Every one can be skipped; nothing here blocks using the app on the phone. */
enum class WelcomeStep { Welcome, Looking, Found }

/**
 * The first start, as the Android app's Welcome (ui/onboarding/WelcomeScreen.kt): the brand mark, BoringTravel and its
 * line, then the account step: Continue with Google (Google's page for the account and Drive: one tap) or Use without
 * an account, and on iPhone Sign in with Apple beside Google (Apple 4.8); with Drive connected, "Looking for a backup",
 * then "Found in your Google Drive" with Restore / Start fresh when one is there; then Trips. Nothing here asks for
 * location. A build without the Google iPhone client says Google sign-in isn't set up and goes on with Continue (there
 * is no Drive to connect without it). [onFinish] keeps that Welcome was seen. [stepAtStart], [foundAtStart] and
 * [messageAtStart] open it as the pictures show it.
 */
@Composable
fun WelcomeScreen(
    settings: AppSettings,
    onFinish: () -> Unit,
    stepAtStart: WelcomeStep = WelcomeStep.Welcome,
    foundAtStart: BackupEntry? = null,
    messageAtStart: String? = null,
    errorAtStart: String? = null,
) {
    val google = LocalGoogle.current
    val backups = LocalDriveBackups.current
    val scope = rememberCoroutineScope()
    val appScope = LocalAppScope.current ?: scope
    var stepIndex by rememberSaveable { mutableStateOf(stepAtStart.ordinal) }
    val step = WelcomeStep.entries[stepIndex]
    fun go(to: WelcomeStep) {
        stepIndex = to.ordinal
    }
    var message by rememberSaveable { mutableStateOf(messageAtStart) }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(foundAtStart) }
    var restoreError by remember { mutableStateOf(errorAtStart) }
    val progress = LocalBackupFiles.current?.progress?.takeIf { !it.file }

    fun finish() = onFinish()

    fun connect() {
        val g = google ?: return
        busy = true
        message = null
        scope.launch {
            val result = g.signIn()
            busy = false
            when (result) {
                GoogleConnect.Connected -> {
                    message = null
                    go(WelcomeStep.Looking)
                }
                // No to Drive: signed in, trips on this phone; Me › Backup & sync can connect Drive later.
                is GoogleConnect.NoDrive -> if (result.message == null) finish() else message = result.message
                is GoogleConnect.NotSignedIn -> message = result.message
            }
        }
    }

    /** Sign in with Apple (iPhone): the account only; Drive can be connected later in Me › Backup & sync. */
    fun apple() {
        val g = google ?: return
        busy = true
        message = null
        scope.launch {
            val result = g.signInWithApple()
            busy = false
            when (result) {
                AppleOutcome.SignedIn -> finish()
                AppleOutcome.Cancelled -> Unit
                is AppleOutcome.Failed -> message = result.message
            }
        }
    }

    fun restore(entry: BackupEntry) {
        val b = backups ?: return
        restoreError = null
        // The app's scope: a restore keeps going if the screen is rebuilt meanwhile.
        appScope.launch {
            val error = try {
                b.restore(entry)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.readable("The backup couldn't be restored. Try again.")
            }
            if (error == null) finish() else restoreError = error
        }
    }

    // Looking in Drive (again after the screen was rebuilt, since the found backup isn't saved with it).
    LaunchedEffect(step) {
        when {
            step == WelcomeStep.Looking -> {
                var looked = true
                val latest = try {
                    backups?.latest()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    looked = false
                    null // Offline or Drive busy: the app looks again when it is next opened (RestoreOffer).
                }
                found = latest
                // Nothing in Drive to ask about: this phone's backups can start.
                if (latest == null && looked) settings.restoreAsked = true
                if (latest != null) go(WelcomeStep.Found) else finish()
            }
            step == WelcomeStep.Found && found == null -> go(WelcomeStep.Looking)
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
            Text(
                "Boring travel, when everything can be interesting.", Modifier.widthIn(max = 300.dp),
                style = Bt.text.body.copy(fontSize = Bt.text.body.fontSize * 1.1f, color = Bt.colors.muted),
            )
            Spacer(Modifier.weight(1f))
            when (step) {
                WelcomeStep.Welcome -> WelcomeButtons(
                    signInConfigured = google?.signInConfigured == true,
                    apple = google?.appleConfigured == true,
                    busy = busy,
                    onGoogle = ::connect,
                    onApple = ::apple,
                    onLocal = ::finish,
                )
                WelcomeStep.Looking -> {
                    SignedInRow(settings.accountEmail, settings.accountName, driveConnected = true, driveAccount = settings.driveAccount)
                    BtCard(Modifier.fillMaxWidth()) {
                        Eyebrow("Google Drive", accent = true)
                        ProgressLine(BackupProgress(restoring = true, label = "Looking for a backup"), Modifier.padding(top = 8.dp))
                    }
                }
                WelcomeStep.Found -> {
                    SignedInRow(settings.accountEmail, settings.accountName, driveConnected = true, driveAccount = settings.driveAccount)
                    found?.let { entry ->
                        BackupFoundCard(
                            entry = entry,
                            progress = progress,
                            error = restoreError,
                            onRestore = { restore(entry) },
                            onStartFresh = {
                                settings.restoreAsked = true
                                finish()
                            },
                        )
                    }
                }
            }
            message?.let { Text(it, style = Bt.text.sub, color = Bt.colors.danger) }
            // Android's line; without Google here nothing goes to Drive, so only its first half.
            Text(
                if (google?.signInConfigured == true) "Your journal lives on this phone. Backups and exports go to your own Google Drive." else "Your journal lives on this phone.",
                style = Bt.text.fine,
            )
            PrivacyLink()
        }
    }
}

@Composable
private fun WelcomeButtons(signInConfigured: Boolean, apple: Boolean, busy: Boolean, onGoogle: () -> Unit, onApple: () -> Unit, onLocal: () -> Unit) {
    if (signInConfigured) {
        BtCard(Modifier.fillMaxWidth()) {
            Eyebrow("Why Google", accent = true)
            Text("We need access to your Google Drive for backups", Modifier.padding(top = 6.dp), style = Bt.text.item)
            Text(
                "Your trips and journal are saved to your own Drive, so a new phone can bring them back. Google asks for your account, then for Drive. The app only sees the files it makes.",
                style = Bt.text.sub,
            )
        }
        BtButton(if (busy) "Connecting…" else "Continue with Google", onClick = onGoogle, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        // Sign in with Apple beside Google (Apple 4.8): the account, without Drive.
        if (apple) AppleSignInButton(onClick = { if (!busy) onApple() })
        CenteredGhostButton("Use without an account", onClick = onLocal, modifier = Modifier.fillMaxWidth(), enabled = !busy)
    } else {
        // A build without the Google iPhone client: nothing to connect (Android's build without sign-in still connects
        // Drive, which an iPhone can't without the client).
        Text(GoogleAccount.NOT_SET_UP, style = Bt.text.sub)
        if (apple) AppleSignInButton(onClick = { if (!busy) onApple() })
        BtButton("Continue", onClick = onLocal, modifier = Modifier.fillMaxWidth(), enabled = !busy)
    }
}

/** A section of the app that is not on this phone yet: one quiet line, centred (a trip's Map and Media). */
@Composable
fun NotOnIphoneYet(modifier: Modifier = Modifier) {
    Text(
        "Not on iPhone yet.", modifier.fillMaxWidth().padding(28.dp).wrapContentWidth(Alignment.CenterHorizontally),
        style = Bt.text.sub, textAlign = TextAlign.Center,
    )
}

/**
 * A tab of the bar outside trips whose screen is not on this phone yet (Calendar, Map, Community): its title as the
 * Android tab has it, and the same quiet line. The tab is there so the bar is the Android app's.
 */
@Composable
fun TabToCome(title: String) {
    BtScreen(statusScrim = true) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BtHeader(title)
            NotOnIphoneYet()
        }
    }
}
