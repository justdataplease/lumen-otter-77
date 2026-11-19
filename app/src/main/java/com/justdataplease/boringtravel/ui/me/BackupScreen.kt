package com.justdataplease.boringtravel.ui.me

import com.justdataplease.boringtravel.backup.BackupNames
import com.justdataplease.boringtravel.backup.NothingToBackUpException
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.backup.BackupArchive
import com.justdataplease.boringtravel.backup.BackupEntry
import com.justdataplease.boringtravel.backup.BackupFolder
import com.justdataplease.boringtravel.backup.BackupFormatException
import com.justdataplease.boringtravel.backup.BackupManifest
import com.justdataplease.boringtravel.backup.BackupProgress
import com.justdataplease.boringtravel.backup.BackupText
import com.justdataplease.boringtravel.backup.FileBackupReport
import com.justdataplease.boringtravel.backup.RestoreReport
import com.justdataplease.boringtravel.google.DriveStep
import com.justdataplease.boringtravel.google.readable
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtSwitchRow
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.onboarding.CenteredGhostButton
import com.justdataplease.boringtravel.ui.onboarding.ProgressLine
import com.justdataplease.boringtravel.ui.onboarding.rememberDriveConnect
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** A line under the controls: what just happened, or what went wrong. */
private data class Note(val text: String, val error: Boolean = false)

/** The types a backup file may come as from the system's file picker (some apps call a zip a plain binary). */
private val ZIP_TYPES = arrayOf(BackupArchive.MIME, "application/x-zip-compressed", "application/octet-stream")

/**
 * Me › Backup & sync: Drive connection, last backup, Back up now, Restore (a list of backups, confirmed in-app), the
 * Drive folder with an Open button, the daily backup and Wi‑Fi switches; then the backup file: save one anywhere
 * through the system's file picker, or restore one (confirmed in-app), with progress and Stop.
 */
@Composable
fun BackupScreen(graph: AppGraph, actions: AppActions) {
    val settings = graph.settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val driveConnected by settings.driveConnected.collectAsState(initial = false)
    val autoBackup by settings.autoBackup.collectAsState(initial = true)
    val wifiOnly by settings.wifiOnly.collectAsState(initial = true)
    val lastBackupAt by settings.lastBackupAt.collectAsState(initial = null)
    val checkedAt by settings.backupCheckedAt.collectAsState(initial = null)
    val email by settings.accountEmail.collectAsState(initial = null)
    val needsReconnect by graph.google.needsReconnect.collectAsState()
    val progress by graph.backups.progress.collectAsState()

    var note by remember { mutableStateOf<Note?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var listOpen by rememberSaveable { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<BackupEntry>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var listRound by remember { mutableIntStateOf(0) }
    var toRestore by remember { mutableStateOf<BackupEntry?>(null) }
    var askDisconnect by remember { mutableStateOf(false) }
    var fileNote by remember { mutableStateOf<Note?>(null) }
    var fileToRestore by remember { mutableStateOf<Pair<Uri, BackupManifest>?>(null) }
    val undo by graph.backups.undo.collectAsState()
    var askUndo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { graph.backups.loadUndo() }

    val connect = rememberDriveConnect(graph) { result ->
        connecting = false
        note = when (result) {
            DriveStep.Connected -> Note("Google Drive connected.")
            is DriveStep.Failed -> Note(result.message, error = true)
            DriveStep.Cancelled, is DriveStep.NeedsConsent -> null
        }
    }

    fun startConnect() {
        connecting = true
        note = null
        connect()
    }

    fun backUp() {
        note = null
        // The app's scope: leaving the screen doesn't stop the backup.
        graph.scope.launch {
            val result = try {
                Note(BackupText.backedUp(graph.backups.backupNow()))
            } catch (e: CancellationException) {
                throw e
            } catch (_: NothingToBackUpException) {
                Note("Nothing to back up yet. Your first trip or note starts the backups.")
            } catch (e: Exception) {
                Note(e.readable("The backup didn't finish. Try again."), error = true)
            }
            withContext(Dispatchers.Main) {
                note = result
                listRound++
            }
        }
    }

    fun restore(entry: BackupEntry) {
        note = null
        graph.scope.launch {
            val result = try {
                restoredNote(graph.backups.restore(entry))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Note(e.readable("The backup couldn't be restored. Try again."), error = true)
            }
            withContext(Dispatchers.Main) { note = result }
        }
    }

    /** Opens the Backups folder in the Drive app (or the browser), wherever it is now. */
    fun openFolder() {
        note = null
        scope.launch {
            val id = try {
                graph.backups.backupsFolder()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = Note(e.readable("Couldn't find the folder. Try again."), error = true)
                return@launch
            }
            if (id == null) {
                note = Note("No backups in your Drive yet. The first one makes the folder.")
                return@launch
            }
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, BackupFolder.url(id).toUri()))
            } catch (_: ActivityNotFoundException) {
                note = Note("No app on this phone opens Google Drive links.", error = true)
            }
        }
    }

    fun saveFile(uri: Uri) {
        fileNote = null
        graph.scope.launch {
            val result = try {
                savedNote(graph.backups.saveFile(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Note((e as? BackupFormatException)?.message ?: "Couldn't save the backup file there. Try another place.", error = true)
            }
            withContext(Dispatchers.Main) { fileNote = result }
        }
    }

    /** Reads the picked file's manifest first, so the restore is confirmed with its date and counts. */
    fun checkFile(uri: Uri) {
        fileNote = null
        scope.launch {
            try {
                fileToRestore = uri to graph.backups.peekFile(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fileNote = Note((e as? BackupFormatException)?.message ?: "Couldn't read that file. Pick a BoringTravel backup file.", error = true)
            }
        }
    }

    fun restoreFile(uri: Uri) {
        fileNote = null
        graph.scope.launch {
            val result = try {
                restoredNote(graph.backups.restoreFile(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Note((e as? BackupFormatException)?.message ?: "The backup file couldn't be restored. Try again.", error = true)
            }
            withContext(Dispatchers.Main) { fileNote = result }
        }
    }

    fun undoRestore() {
        fileNote = null
        graph.scope.launch {
            val result = try {
                graph.backups.undoRestore()
                Note("Undone. This phone is as it was before the restore.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Note((e as? BackupFormatException)?.message ?: "The restore couldn't be undone. Try again.", error = true)
            }
            withContext(Dispatchers.Main) { fileNote = result }
        }
    }

    fun stopFile(restoring: Boolean) {
        graph.backups.cancelFile()
        fileNote = Note(if (restoring) "Stopped. Restore the file again to finish." else "Stopped. No file was saved.")
    }

    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupArchive.MIME)) { uri ->
        if (uri != null) saveFile(uri)
    }
    val openFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) checkFile(uri)
    }

    LaunchedEffect(listOpen, driveConnected, listRound) {
        if (!listOpen || !driveConnected) return@LaunchedEffect
        listError = null
        backups = null
        backups = try {
            graph.backups.list()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listError = e.readable("Couldn't read your backups. Try again.")
            emptyList()
        }
    }

    // Drive just connected on a phone that has never backed up: the backup found there is offered first.
    RestoreOffer(graph)

    BtScreen {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BtTopBar("Backup & sync", onBack = actions::back)
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DriveCard(
                    connected = driveConnected,
                    account = graph.google.driveAccount ?: email,
                    needsReconnect = needsReconnect,
                    busy = connecting,
                    onConnect = ::startConnect,
                    onDisconnect = { askDisconnect = true },
                )

                Eyebrow("Backup", Modifier.padding(top = 6.dp, start = 4.dp))
                BtCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Last backup", Modifier.weight(1f), style = Bt.text.sub)
                        Text(lastBackupAt?.let { BackupText.since(it, System.currentTimeMillis()) } ?: "Never", style = Bt.text.item)
                    }
                    // A day with nothing new writes no backup: say that the check happened, so the date above isn't a worry.
                    BackupText.nothingNew(lastBackupAt, checkedAt, System.currentTimeMillis())?.let { Text(it, Modifier.padding(top = 4.dp), style = Bt.text.fine) }
                    progress?.takeIf { !it.file }?.let { ProgressLine(it, Modifier.padding(top = 12.dp)) }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BtButton("Back up now", onClick = ::backUp, modifier = Modifier.weight(1f), enabled = driveConnected && progress == null)
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
                    BackupList(backups, listError, busy = progress != null, onPick = { toRestore = it })
                }

                BtCard(Modifier.fillMaxWidth()) {
                    BtSwitchRow(
                        title = "Back up every day",
                        body = "Around 22:00, when the battery isn't low.",
                        checked = autoBackup,
                        onChange = { on -> scope.launch { settings.setAutoBackup(on); graph.backups.reschedule() } },
                    )
                    BtDivider()
                    BtSwitchRow(
                        title = "Only on Wi‑Fi",
                        body = "Daily backups and photo uploads wait for Wi‑Fi.",
                        checked = wifiOnly,
                        onChange = { on -> scope.launch { settings.setWifiOnly(on); graph.backups.reschedule() } },
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
                            BtButton(
                                "Save a file",
                                onClick = { saveTo.launch(BackupArchive.fileName(System.currentTimeMillis(), ZoneId.systemDefault())) },
                                modifier = Modifier.weight(1f),
                                enabled = progress == null,
                            )
                            CenteredGhostButton("Restore a file", onClick = { openFrom.launch(ZIP_TYPES) }, modifier = Modifier.weight(1f), enabled = progress == null)
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
                // Daily sync between phones (Wave 3G) adds its own section below this one.
            }
        }
    }

    fileToRestore?.let { (uri, manifest) ->
        ConfirmDialog(
            title = "Restore this file?",
            body = (if (manifest.createdAt > 0) "Backup from ${BackupText.date(manifest.createdAt)}" else "Backup") +
                " · ${BackupText.counts(manifest.items)}. $RESTORE_MEANS",
            confirm = "Restore",
            onConfirm = {
                fileToRestore = null
                restoreFile(uri)
            },
            onDismiss = { fileToRestore = null },
            danger = true,
        )
    }

    toRestore?.let { entry ->
        ConfirmDialog(
            title = "Restore this backup?",
            body = "Backup from ${BackupText.date(entry.createdAt)}" +
                (entry.items?.let { " · ${BackupText.counts(it)}" } ?: "") +
                ". $RESTORE_MEANS",
            confirm = "Restore",
            onConfirm = {
                toRestore = null
                restore(entry)
            },
            onDismiss = { toRestore = null },
            danger = true,
        )
    }
    if (askUndo) {
        ConfirmDialog(
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
        ConfirmDialog(
            title = "Disconnect Google Drive?",
            body = "BoringTravel stops backing up and can no longer open its Drive folder. Files already in your Drive stay there.",
            confirm = "Disconnect",
            onConfirm = {
                askDisconnect = false
                listOpen = false
                scope.launch {
                    graph.google.disconnectDrive()
                    note = Note("Google Drive disconnected.")
                }
            },
            onDismiss = { askDisconnect = false },
        )
    }
}

private fun restoredNote(report: RestoreReport): Note = Note(BackupText.restored(report))

/** What a restore does, for the question before it. */
private const val RESTORE_MEANS = "This phone goes back to it: what you added or changed since is removed. You can undo it here."

private fun savedNote(report: FileBackupReport): Note {
    val left = when (report.photosLeftOut) {
        0 -> ""
        1 -> " 1 photo wasn't on this phone, so it isn't in the file."
        else -> " ${report.photosLeftOut} photos weren't on this phone, so they aren't in the file."
    }
    return Note("Saved: ${BackupText.counts(report.items)}.$left")
}

@Composable
private fun DriveCard(
    connected: Boolean,
    account: String?,
    needsReconnect: Boolean,
    busy: Boolean,
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
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            Text(entry.items?.let(BackupText::counts) ?: sizeText(entry.size), style = Bt.text.sub)
                        }
                        Icon(BtIcons.Forward, null, tint = Bt.colors.muted)
                    }
                }
            }
        }
    }
}

private fun sizeText(bytes: Long?): String = when {
    bytes == null -> "Backup"
    bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}


/**
 * "Back up to Drive" with the last backup's time, for Print & export (Wave 2E places it there). Without Drive it
 * opens Me › Backup & sync.
 */
@Composable
fun BackupToDriveButton(graph: AppGraph, actions: AppActions, modifier: Modifier = Modifier) {
    val connected by graph.settings.driveConnected.collectAsState(initial = false)
    val last by graph.settings.lastBackupAt.collectAsState(initial = null)
    val progress by graph.backups.progress.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier) {
        BtGhostButton(
            text = if (progress?.let { !it.restoring && !it.file } == true) "Backing up…" else "Back up to Drive",
            icon = BtIcons.Cloud,
            trailing = last?.let { BackupText.short(it, System.currentTimeMillis()) },
            enabled = progress == null,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (!connected) {
                    actions.backupSettings()
                } else {
                    error = null
                    graph.scope.launch {
                        val failed = try {
                            graph.backups.backupNow()
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.readable("The backup didn't finish. Try again.")
                        }
                        withContext(Dispatchers.Main) { error = failed }
                    }
                }
            },
        )
        error?.let { Text(it, Modifier.padding(top = 6.dp), style = Bt.text.sub, color = Bt.colors.danger) }
    }
}
