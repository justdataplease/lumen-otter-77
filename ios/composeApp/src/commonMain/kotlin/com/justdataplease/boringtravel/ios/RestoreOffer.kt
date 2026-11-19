package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.justdataplease.boringtravel.backup.BackupEntry
import com.justdataplease.boringtravel.backup.BackupNames
import com.justdataplease.boringtravel.backup.BackupText
import com.justdataplease.boringtravel.google.readable
import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A phone that has never backed up looks in Drive first (the Android app's ui/me/RestoreOffer.kt; its owner: "in a new
 * phone search for a backup first … ask the user to restore the latest"). Once Drive is connected, on Trips or on Backup
 * & sync, the newest backup that holds something is offered, once. Until the traveller answers, the daily backup doesn't
 * run (BackupSafety.waitForRestoreAnswer), so a new phone never backs up over the question. What a restore did is said
 * in passing (the app's snackbar, where Android shows a toast). [onChanged] runs after a restore. [foundAtStart] and
 * [replacesAtStart] show the question at once (the pictures).
 */
@Composable
fun RestoreOffer(settings: AppSettings, onChanged: () -> Unit = {}, foundAtStart: BackupEntry? = null, replacesAtStart: Boolean = false) {
    val backups = LocalDriveBackups.current
    val say = LocalSay.current
    val appScope = LocalAppScope.current
    var found by remember { mutableStateOf(foundAtStart) }
    // The phone already holds trips of its own: the question says a restore replaces them.
    var replaces by remember { mutableStateOf(replacesAtStart) }
    val driveConnected = settings.driveConnected

    LaunchedEffect(driveConnected, backups) {
        val b = backups ?: return@LaunchedEffect
        if (!driveConnected || foundAtStart != null) return@LaunchedEffect
        if (settings.restoreAsked || settings.lastBackupAt != null || !settings.onboardingDone) return@LaunchedEffect
        try {
            val latest = b.latest()
            if (latest == null || !BackupNames.hasData(latest)) {
                settings.restoreAsked = true
            } else {
                replaces = b.hasOwnData()
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
            val b = backups ?: return@BtConfirmDialog
            // The app's scope: the restore goes on when this screen is left.
            (appScope ?: return@BtConfirmDialog).launch {
                val text = try {
                    BackupText.restored(b.restore(entry))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.readable("The backup couldn't be restored. Try again in Me › Backup & sync.")
                }
                onChanged()
                say(text)
            }
        },
        onDismiss = {
            found = null
            // Answered: this phone's backups start; the backup stays in Me › Backup & sync › Restore.
            settings.restoreAsked = true
        },
    )
}
