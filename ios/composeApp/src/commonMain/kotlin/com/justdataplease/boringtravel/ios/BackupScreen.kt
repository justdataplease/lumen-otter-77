package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.backup.BackupEntry
import com.justdataplease.boringtravel.backup.BackupFormatException
import com.justdataplease.boringtravel.backup.BackupManifest
import com.justdataplease.boringtravel.backup.BackupNames
import com.justdataplease.boringtravel.backup.BackupProgress
import com.justdataplease.boringtravel.backup.BackupText
import com.justdataplease.boringtravel.google.DriveStep
import com.justdataplease.boringtravel.google.GoogleAccount
import com.justdataplease.boringtravel.google.LocalGoogle
import com.justdataplease.boringtravel.google.readable
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtSwitchRow
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A line under the controls: what just happened, red when it went wrong. */
data class BackupNote(val text: String, val error: Boolean = false)

/** What a restore does, for the question before it. */
private const val RESTORE_MEANS = "This phone goes back to it: what you added or changed since is removed. You can undo it here."

/**
 * Me › Backup & sync, as the Android app's ui/me/BackupScreen.kt: the Google Drive card (connected or not, the account,
 * Connect Google Drive, Connect again, Disconnect), the last backup with "Nothing new since", Back up now with its
 * progress, Restore with the backups in Drive (each confirmed in-app), the Drive folder with Open, the daily backup and
 * Wi‑Fi switches, what Drive keeps; then the backup file (save one anywhere, restore one) with its progress and Stop,
 * and Undo restore after a restore. A backup or restore goes on when the screen is left (the app's scope; the phone's
 * background time on iPhone). [onChanged] runs after a restore changed the trips. The `…AtStart` parameters open it as
 * the pictures show it.
 */
@Composable
fun BackupScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    askAtStart: BackupManifest? = null,
    noteAtStart: String? = null,
    undoAskAtStart: Boolean = false,
    driveNoteAtStart: BackupNote? = null,
    listAtStart: Boolean = false,
    restoreAskAtStart: BackupEntry? = null,
    disconnectAtStart: Boolean = false,
    progressAtStart: BackupProgress? = null,
) {
    val backups = LocalBackupFiles.current
    val drive = LocalDriveBackups.current
    val google = LocalGoogle.current
    val phone = LocalPhone.current
    val uri = LocalUriHandler.current
    val background = LocalBackgroundWork.current
    val scope = rememberCoroutineScope()
    val appScope = LocalAppScope.current ?: scope
    var fileNote by remember { mutableStateOf(noteAtStart?.let { BackupNote(it) }) }
    var toRestore by remember { mutableStateOf(askAtStart?.let { BackupFiles.PICKED to it }) }
    var askUndo by remember { mutableStateOf(undoAskAtStart) }
    var note by remember { mutableStateOf(driveNoteAtStart) }
    var connecting by remember { mutableStateOf(false) }
    var listOpen by rememberSaveable { mutableStateOf(listAtStart) }
    var entries by remember { mutableStateOf<List<BackupEntry>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var listRound by remember { mutableIntStateOf(0) }
    var driveRestore by remember { mutableStateOf(restoreAskAtStart) }
    var askDisconnect by remember { mutableStateOf(disconnectAtStart) }
    LaunchedEffect(backups) { backups?.loadUndo() }
    val progress = progressAtStart ?: backups?.progress
    val undo = backups?.undo
    val driveConnected = settings.driveConnected
    val needsReconnect = google?.needsReconnect == true
    val lastBackupAt = settings.lastBackupAt
    val now = nowMs()

    fun connect() {
        val g = google ?: return
        connecting = true
        note = null
        scope.launch {
            val step = g.connectDrive()
            connecting = false
            note = when (step) {
                DriveStep.Connected -> BackupNote("Google Drive connected.")
                is DriveStep.Failed -> BackupNote(step.message, error = true)
                DriveStep.Cancelled -> null
            }
        }
    }

    /** Runs Drive work in the app's scope with the phone's time asked for while it runs (it goes on when the screen is left). */
    fun driveWork(name: String, work: suspend () -> BackupNote) {
        appScope.launch {
            val ticket = background.beginBackgroundWork(name)
            val result = try {
                work()
            } finally {
                ticket.endBackgroundWork()
            }
            note = result
            listRound++
        }
    }

    fun backUp() {
        val d = drive ?: return
        note = null
        driveWork("Backing up to Google Drive") {
            try {
                BackupNote(BackupText.backedUp(d.backupNow()))
            } catch (e: CancellationException) {
                throw e
            } catch (_: NothingToBackUpException) {
                BackupNote("Nothing to back up yet. Your first trip or note starts the backups.")
            } catch (e: Exception) {
                BackupNote(e.readable("The backup didn't finish. Try again."), error = true)
            }
        }
    }

    fun restore(entry: BackupEntry) {
        val d = drive ?: return
        note = null
        driveWork("Restoring from Google Drive") {
            try {
                BackupNote(BackupText.restored(d.restore(entry)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupNote(e.readable("The backup couldn't be restored. Try again."), error = true)
            } finally {
                onChanged()
            }
        }
    }

    /** Opens the Backups folder in the Drive app (or the browser), wherever it is now. */
    fun openFolder() {
        val d = drive ?: return
        note = null
        scope.launch {
            val id = try {
                d.backupsFolder()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = BackupNote(e.readable("Couldn't find the folder. Try again."), error = true)
                return@launch
            }
            if (id == null) {
                note = BackupNote("No backups in your Drive yet. The first one makes the folder.")
                return@launch
            }
            if (runCatching { uri.openUri(BackupFolder.url(id)) }.isFailure) note = BackupNote("No app on this phone opens Google Drive links.", error = true)
        }
    }

    fun saveFile() {
        val b = backups ?: return
        fileNote = null
        appScope.launch {
            try {
                // Written here first, then the phone's save sheet copies it where the traveller picks (Android picks the
                // place first); a closed sheet saves nothing and leaves nothing behind.
                val (path, report) = b.saveFile()
                phone.exportFile(path) { ok ->
                    b.forget(path)
                    fileNote = if (ok) BackupNote(BackupText.saved(report)) else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fileNote = BackupNote((e as? BackupFormatException)?.message ?: "Couldn't save the backup file there. Try another place.", error = true)
            }
        }
    }

    /** The picked file's manifest first, so the restore is confirmed with its date and counts. */
    fun pickFile() {
        val b = backups ?: return
        fileNote = null
        phone.pickFile(BackupFiles.PICKED) { picked ->
            if (!picked) return@pickFile
            scope.launch {
                try {
                    toRestore = BackupFiles.PICKED to b.peekFile(BackupFiles.PICKED)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    b.forget(BackupFiles.PICKED)
                    fileNote = BackupNote((e as? BackupFormatException)?.message ?: "Couldn't read that file. Pick a BoringTravel backup file.", error = true)
                }
            }
        }
    }

    fun restoreFile(path: String) {
        val b = backups ?: return
        fileNote = null
        appScope.launch {
            fileNote = try {
                BackupNote(BackupText.restored(b.restoreFile(path)))
            } catch (e: CancellationException) {
                BackupNote("Stopped. Restore the file again to finish.")
            } catch (e: Exception) {
                BackupNote((e as? BackupFormatException)?.message ?: "The backup file couldn't be restored. Try again.", error = true)
            } finally {
                b.forget(path)
                onChanged()
            }
        }
    }

    fun undoRestore() {
        val b = backups ?: return
        fileNote = null
        appScope.launch {
            fileNote = try {
                b.undoRestore()
                BackupNote("Undone. This phone is as it was before the restore.")
            } catch (e: CancellationException) {
                BackupNote("Stopped. Restore the file again to finish.")
            } catch (e: Exception) {
                BackupNote((e as? BackupFormatException)?.message ?: "The restore couldn't be undone. Try again.", error = true)
            } finally {
                onChanged()
            }
        }
    }

    fun stopFile(restoring: Boolean) {
        backups?.cancelFile()
        fileNote = BackupNote(if (restoring) "Stopped. Restore the file again to finish." else "Stopped. No file was saved.")
    }

    LaunchedEffect(listOpen, driveConnected, listRound) {
        val d = drive
        if (!listOpen || !driveConnected || d == null) return@LaunchedEffect
        listError = null
        entries = null
        entries = try {
            d.list()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listError = e.readable("Couldn't read your backups. Try again.")
            emptyList()
        }
    }

    // Drive just connected on a phone that has never backed up: the backup found there is offered first.
    RestoreOffer(settings, onChanged)

    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Backup & sync", onBack = onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                DriveCard(
                    connected = driveConnected,
                    account = settings.driveAccount ?: settings.accountEmail.takeIf { settings.accountProvider != AppSettings.PROVIDER_APPLE },
                    needsReconnect = needsReconnect,
                    busy = connecting,
                    configured = google?.signInConfigured == true,
                    onConnect = ::connect,
                    onDisconnect = { askDisconnect = true },
                )

                Eyebrow("Backup", Modifier.padding(top = 6.dp, start = 4.dp))
                BtCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Last backup", Modifier.weight(1f), style = Bt.text.sub)
                        Text(lastBackupAt?.let { BackupText.since(it, now) } ?: "Never", style = Bt.text.item)
                    }
                    // A day with nothing new writes no backup: say that the check happened, so the date above isn't a worry.
                    BackupText.nothingNew(lastBackupAt, settings.backupCheckedAt, now)?.let { Text(it, Modifier.padding(top = 4.dp), style = Bt.text.fine) }
                    progress?.takeIf { !it.file }?.let { ProgressLine(it, Modifier.padding(top = 12.dp)) }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BtButton("Back up now", onClick = ::backUp, modifier = Modifier.weight(1f), enabled = driveConnected && progress == null && drive != null)
                        CenteredGhostButton(
                            if (listOpen) "Hide backups" else "Restore",
                            onClick = { listOpen = !listOpen },
                            modifier = Modifier.weight(1f),
                            enabled = driveConnected,
                        )
                    }
                    if (!driveConnected) Text("Connect Google Drive to back up and restore.", Modifier.padding(top = 10.dp), style = Bt.text.fine)
                    if (driveConnected) {
                        BtDivider(Modifier.padding(top = 14.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(BackupFolder.DEFAULT_PLACE, style = Bt.text.item)
                                Text("If it's deleted, the next backup makes a new one.", style = Bt.text.fine)
                            }
                            CenteredGhostButton("Open", onClick = ::openFolder)
                        }
                    }
                }

                note?.let { Text(it.text, Modifier.padding(horizontal = 4.dp), style = Bt.text.sub, color = if (it.error) Bt.colors.danger else Bt.colors.ink) }

                if (listOpen && driveConnected) {
                    BackupList(entries, listError, busy = progress != null, onPick = { driveRestore = it })
                }

                BtCard(Modifier.fillMaxWidth()) {
                    BtSwitchRow(
                        title = "Back up every day",
                        body = "Around 22:00, when the battery isn't low.",
                        checked = settings.autoBackup,
                        onChange = { on ->
                            settings.autoBackup = on
                            drive?.reschedule()
                        },
                    )
                    BtDivider()
                    BtSwitchRow(
                        title = "Only on Wi‑Fi",
                        body = "Daily backups and photo uploads wait for Wi‑Fi.",
                        checked = settings.wifiOnly,
                        onChange = { on ->
                            settings.wifiOnly = on
                            drive?.reschedule()
                        },
                    )
                }

                Text(
                    "Drive keeps the newest ${BackupNames.KEEP} backups, and every photo and saved video once, in BoringTravel › Photos and Videos. " +
                        "Your settings are saved too: theme, fonts, traveller, and your video, book and share choices.",
                    Modifier.padding(horizontal = 4.dp),
                    style = Bt.text.fine,
                )

                Eyebrow("Backup file", Modifier.padding(top = 6.dp, start = 4.dp))
                BtCard(Modifier.fillMaxWidth()) {
                    Text("Trips, entries, photos, saved videos and your settings in one zip. Keep it anywhere: this phone, an SD card, another cloud.", style = Bt.text.sub)
                    val running = progress?.takeIf { it.file }
                    if (running != null) {
                        ProgressLine(running, Modifier.padding(top = 12.dp))
                        CenteredGhostButton("Stop", onClick = { stopFile(running.restoring) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    } else {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BtButton("Save a file", onClick = ::saveFile, modifier = Modifier.weight(1f), enabled = progress == null && backups != null)
                            CenteredGhostButton("Restore a file", onClick = ::pickFile, modifier = Modifier.weight(1f), enabled = progress == null && backups != null)
                        }
                    }
                }
                fileNote?.let { Text(it.text, Modifier.padding(horizontal = 4.dp), style = Bt.text.sub, color = if (it.error) Bt.colors.danger else Bt.colors.ink) }
                // A restore keeps the phone as it was: one tap puts it back.
                undo?.let { before ->
                    BtCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Before the last restore", style = Bt.text.item)
                                Text("${BackupText.date(before.createdAt)} · ${BackupText.counts(before.items)}", style = Bt.text.sub)
                            }
                            CenteredGhostButton("Undo restore", onClick = { askUndo = true }, enabled = progress == null)
                        }
                    }
                }
            }
        }
    }

    toRestore?.let { (path, manifest) ->
        BtConfirmDialog(
            title = "Restore this file?",
            body = (if (manifest.createdAt > 0) "Backup from ${BackupText.date(manifest.createdAt)}" else "Backup") +
                " · ${BackupText.counts(manifest.items)}. $RESTORE_MEANS",
            confirm = "Restore",
            onConfirm = {
                toRestore = null
                restoreFile(path)
            },
            onDismiss = {
                toRestore = null
                backups?.forget(path)
            },
            danger = true,
        )
    }
    driveRestore?.let { entry ->
        BtConfirmDialog(
            title = "Restore this backup?",
            body = "Backup from ${BackupText.date(entry.createdAt)}" +
                (entry.items?.let { " · ${BackupText.counts(it)}" } ?: "") +
                ". $RESTORE_MEANS",
            confirm = "Restore",
            onConfirm = {
                driveRestore = null
                restore(entry)
            },
            onDismiss = { driveRestore = null },
            danger = true,
        )
    }
    if (askUndo) {
        BtConfirmDialog(
            title = "Undo the restore?",
            body = "This phone goes back to how it was before the last restore. What you added or changed since is removed.",
            confirm = "Undo restore",
            onConfirm = {
                askUndo = false
                undoRestore()
            },
            onDismiss = { askUndo = false },
            danger = true,
        )
    }
    if (askDisconnect) {
        BtConfirmDialog(
            title = "Disconnect Google Drive?",
            body = "BoringTravel stops backing up and can no longer open its Drive folder. Files already in your Drive stay there.",
            confirm = "Disconnect",
            onConfirm = {
                askDisconnect = false
                listOpen = false
                scope.launch {
                    google?.disconnectDrive()
                    note = BackupNote("Google Drive disconnected.")
                }
            },
            onDismiss = { askDisconnect = false },
        )
    }
}

/**
 * Google Drive's card (the Android app's DriveCard). Without the Google iPhone client in this build ([configured]
 * false) it says Google sign-in isn't set up and has nothing to connect.
 */
@Composable
private fun DriveCard(
    connected: Boolean,
    account: String?,
    needsReconnect: Boolean,
    busy: Boolean,
    configured: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val c = Bt.colors
    BtCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (connected) BtIcons.CloudDone else BtIcons.CloudOff, null, Modifier.size(24.dp), tint = if (connected) c.route else c.muted)
            Column(Modifier.weight(1f)) {
                Text(if (connected) "Google Drive connected" else "Google Drive not connected", style = Bt.text.item)
                Text(
                    when {
                        connected && account != null -> account
                        connected -> "Backups go to the BoringTravel folder."
                        else -> "Back up trips, entries and photos to your own Drive."
                    },
                    style = Bt.text.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (connected && needsReconnect) {
            Text("Google wants you to confirm Drive access again.", Modifier.padding(top = 10.dp), style = Bt.text.sub, color = c.danger)
        }
        if (!configured && !connected) {
            Text(GoogleAccount.NOT_SET_UP, Modifier.padding(top = 10.dp), style = Bt.text.fine)
        } else Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                !connected -> BtButton(if (busy) "Connecting…" else "Connect Google Drive", onClick = onConnect, modifier = Modifier.weight(1f), enabled = !busy)
                needsReconnect -> {
                    BtButton(if (busy) "Connecting…" else "Connect again", onClick = onConnect, modifier = Modifier.weight(1f), enabled = !busy)
                    CenteredGhostButton("Disconnect", onClick = onDisconnect, modifier = Modifier.weight(1f), enabled = !busy)
                }
                else -> BtGhostButton("Disconnect", onClick = onDisconnect, enabled = !busy)
            }
        }
    }
}

/** "Backups in your Drive": looking, the failure, none yet, or a row per backup with its date and counts. */
@Composable
private fun BackupList(backups: List<BackupEntry>?, error: String?, busy: Boolean, onPick: (BackupEntry) -> Unit) {
    BtCard(Modifier.fillMaxWidth()) {
        Eyebrow("Backups in your Drive")
        when {
            backups == null -> ProgressLine(BackupProgress(restoring = true, label = "Looking in your Drive"), Modifier.padding(top = 10.dp))
            error != null -> Text(error, Modifier.padding(top = 8.dp), style = Bt.text.sub, color = Bt.colors.danger)
            backups.isEmpty() -> Text("No backups in your Drive yet.", Modifier.padding(top = 8.dp), style = Bt.text.sub)
            else -> Column(Modifier.padding(top = 4.dp)) {
                backups.forEachIndexed { i, entry ->
                    if (i > 0) BtDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Restore") { onPick(entry) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(BackupText.date(entry.createdAt), style = Bt.text.item)
                            Text(entry.items?.let(BackupText::counts) ?: BackupText.size(entry.size), style = Bt.text.sub)
                        }
                        Icon(BtIcons.Forward, null, tint = Bt.colors.muted)
                    }
                }
            }
        }
    }
}
