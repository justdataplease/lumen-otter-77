package com.justdataplease.boringtravel.ui.me

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.backup.BackupEntry
import com.justdataplease.boringtravel.backup.BackupNames
import com.justdataplease.boringtravel.backup.BackupText
import com.justdataplease.boringtravel.google.readable
import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A phone that has never backed up looks in Drive first (owner, 2026-11-11: "in a new phone search for a backup first …
 * ask the user to restore the latest"). Once Drive is connected, on Trips or on Backup & sync, the newest backup that
 * holds something is offered, once. Until the traveller answers, the daily backup doesn't run
 * ([com.justdataplease.boringtravel.backup.BackupSafety.waitForRestoreAnswer]), so a new phone never backs up over the
 * question. The first start asks the same when the traveller signs in there (ui/onboarding/WelcomeScreen).
 */
@Composable
fun RestoreOffer(graph: AppGraph) {
    val context = LocalContext.current
    val driveConnected by graph.settings.driveConnected.collectAsState(initial = false)
    var found by remember { mutableStateOf<BackupEntry?>(null) }
    // The phone already holds trips of its own: the question says a restore replaces them.
    var replaces by remember { mutableStateOf(false) }

    LaunchedEffect(driveConnected) {
        if (!driveConnected) return@LaunchedEffect
        val settings = graph.settings
        if (settings.restoreAsked.first() || settings.lastBackupAt.first() != null || !settings.onboardingDone.first()) return@LaunchedEffect
        try {
            val latest = graph.backups.latest()
            if (latest == null || !BackupNames.hasData(latest)) {
                settings.setRestoreAsked(true)
            } else {
                replaces = graph.backups.hasOwnData()
                found = latest
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or Drive busy: it looks again the next time the app is opened.
        }
    }

    val entry = found ?: return
    BtConfirmDialog(
        title = "Restore your backup?",
        body = "A backup from ${BackupText.date(entry.createdAt)} is in your Google Drive" +
            (entry.items?.let { " · ${BackupText.counts(it)}" } ?: "") + "." +
            (if (replaces) " It replaces what's on this phone now. You can undo it in Me › Backup & sync." else ""),
        confirm = "Restore",
        dismiss = "Not now",
        danger = replaces,
        onConfirm = {
            found = null
            // The app's scope: the restore goes on when this screen is left.
            graph.scope.launch {
                val text = try {
                    BackupText.restored(graph.backups.restore(entry))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.readable("The backup couldn't be restored. Try again in Me › Backup & sync.")
                }
                withContext(Dispatchers.Main) { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
            }
        },
        onDismiss = {
            found = null
            // Answered: this phone's backups start; the backup stays in Me › Backup & sync › Restore.
            graph.scope.launch { graph.settings.setRestoreAsked(true) }
        },
    )
}
