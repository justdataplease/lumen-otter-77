package com.justdataplease.boringtravel.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.justdataplease.boringtravel.BuildConfig
import com.justdataplease.boringtravel.clip.ClipEditStore
import com.justdataplease.boringtravel.clip.ClipEditsJson
import com.justdataplease.boringtravel.clip.ClipMedia
import com.justdataplease.boringtravel.data.DemoTrips
import com.justdataplease.boringtravel.data.SavedClip
import com.justdataplease.boringtravel.data.SavedClips
import com.justdataplease.boringtravel.data.db.BtDatabase
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.settings.Settings
import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.google.DriveJson
import com.justdataplease.boringtravel.google.DriveNotConnectedException
import com.justdataplease.boringtravel.google.RestDriveApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZoneId

/**
 * What a running backup or restore is doing; [done] of [total] photos, or [total] 0 when there is nothing to count.
 * [file]: a backup file is being saved or restored (it can be stopped, [BackupManager.cancelFile]).
 */
data class BackupProgress(val restoring: Boolean, val label: String, val done: Int = 0, val total: Int = 0, val file: Boolean = false) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * A restore just made: the phone is as the backup of [backupAt] was. [restored]: what that backup holds (with
 * [planItems]). [changed]: whether anything on the phone differed from it. [photosDownloaded]: photos downloaded from
 * Drive, or copied from the file when [fromFile]. [photosMissing]: photos that couldn't be (a Drive restore tries again
 * later; a file didn't have them). [videos]: saved videos brought back.
 */
data class RestoreReport(
    val backupAt: Long, val restored: BackupCounts, val photosDownloaded: Int, val photosMissing: Int, val fromFile: Boolean = false,
    val planItems: Int = 0, val videos: Int = 0, val changed: Boolean = true,
)

/**
 * A Drive backup just made. [photosLeft]: photos that couldn't be saved this time (missing or damaged on the phone, or
 * Drive didn't take them); the next backup tries them again. [videos]: saved videos that went to BoringTravel › Videos
 * now; [videosLeft]: the ones still to go (no Wi‑Fi yet, or Drive didn't take them). [unchanged]: nothing was new since
 * [entry], the last backup, which is still in Drive, so no new one was written.
 */
data class BackupReport(
    val entry: BackupEntry, val photosLeft: Int = 0, val videos: Int = 0, val videosLeft: Int = 0, val unchanged: Boolean = false,
)

/** Nothing of the traveller's own is on this phone, so no backup is written ([BackupSafety.worthBackingUp]). */
class NothingToBackUpException : IOException("Nothing to back up yet.")

/** A backup file just saved: what it holds; [photosLeftOut] were in the journal but not on this phone. */
data class FileBackupReport(val items: BackupCounts, val photos: Int, val photosLeftOut: Int)

/**
 * Drive backups: the rows and settings as one zip in BoringTravel/Backups (newest [BackupNames.KEEP] kept), with
 * references to the photos and saved videos, which are kept once each, all of them, in BoringTravel/Photos and
 * BoringTravel/Videos (owner, 2026-11-12: "videos and photos we keep all; the backups are merely for the other
 * settings and references to these"); and a small marker in the app's hidden Drive folder pointing at the newest
 * backup. A restore puts the phone back as the backup was (see [BackupMerge]) and downloads the photos and videos
 * that are missing; the phone as it was is kept for [undoRestore]. A restore never deletes a photo or video file.
 *
 * Every part goes up only when it is new (owner, 15 Nov 2026: "if a trip does not update do not run backup again"):
 * a photo and a video once each, and the zip only when its rows or settings differ from the last one this phone wrote
 * and Drive still has ([BackupStamp]). A day with nothing new costs a few small questions to Drive and writes nothing,
 * so the newest [BackupNames.KEEP] backups stay ten different days, not ten copies of one.
 *
 * Backup files: the same rows plus the photos, saved videos and the trips' clip files in one zip the owner keeps
 * anywhere ([BackupArchive], format 2), saved and restored through the system's file picker, without Google. A file
 * restore works exactly like a Drive restore.
 *
 * One backup or restore runs at a time; [progress] shows it to every screen, whoever started it.
 */
class BackupManager(
    context: Context,
    private val db: BtDatabase,
    private val settings: Settings,
    private val drive: RestDriveApi,
    private val sharedTrips: suspend () -> String? = { null },
    private val restoreSharedTrips: suspend (String) -> Unit = {},
    /** The trips shared now: a restore doesn't roll those back ([BackupMerge]). */
    private val sharedNow: suspend () -> Set<String> = { emptySet() },
) {
    private val app = context.applicationContext
    private val lock = Mutex()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val _progress = MutableStateFlow<BackupProgress?>(null)
    val progress: StateFlow<BackupProgress?> = _progress.asStateFlow()

    private val undoFile = File(app.filesDir, "restore-undo/before-restore.zip")
    private val _undo = MutableStateFlow<BackupManifest?>(null)

    /** The phone as it was before the last restore (when, and what it held); null when there is nothing to undo. */
    val undo: StateFlow<BackupManifest?> = _undo.asStateFlow()

    /** Reads [undo] from the phone (a screen asks once; every restore keeps it current). */
    suspend fun loadUndo() = withContext(Dispatchers.IO) {
        _undo.value = runCatching { undoFile.takeIf { it.isFile }?.inputStream()?.buffered()?.use { BackupArchive.peek(it) } }.getOrNull()
    }

    /** Puts the phone back as it was before the last restore. Itself a restore: it can be undone the same way. */
    suspend fun undoRestore(): RestoreReport = fileRun {
        // From a copy: the restore keeps the phone as it is now in the undo file's place.
        val copy = File(app.cacheDir, "restore/undo.zip")
        try {
            copy.parentFile?.mkdirs()
            undoFile.copyTo(copy, overwrite = true)
            runRestoreFile(Uri.fromFile(copy))
        } finally {
            copy.delete()
        }
    }

    /** Whether this phone holds anything of the traveller's own: a restore would replace it. */
    suspend fun hasOwnData(): Boolean = withContext(Dispatchers.IO) { BackupSafety.worthBackingUp(readSnapshot()) }

    /** Plans or cancels the daily backup to match the settings. */
    suspend fun reschedule() {
        val on = settings.autoBackup.first() && settings.driveConnected.first()
        BackupSchedule.apply(app, on, settings.wifiOnly.first())
    }

    /**
     * Backs up now: photos not in Drive yet, then (when anything changed since the last backup) the journal zip, the
     * marker, and old backups pruned.
     */
    suspend fun backupNow(): BackupReport = lock.withLock {
        try {
            withContext(Dispatchers.IO) { runBackup() }
        } finally {
            _progress.value = null
        }
    }

    /** Backups in the owner's Drive, newest first. */
    suspend fun list(): List<BackupEntry> = withContext(Dispatchers.IO) {
        val folder = BackupFolder.find(drive) ?: return@withContext emptyList()
        BackupNames.sorted(drive.list(DriveJson.filesIn(folder), orderBy = "createdTime desc").mapNotNull { BackupNames.entry(it, zone) })
    }

    /** The Backups folder in Drive, wherever the owner moved it ([BackupFolder]); null when there is none yet. */
    suspend fun backupsFolder(): String? = withContext(Dispatchers.IO) { BackupFolder.find(drive) }

    /**
     * Saves a backup file to [uri] (from the system's "create document"): the rows, the photos and the trips' clip
     * files in one zip ([BackupArchive]). No Google needed. On failure or [cancelFile] the half-written file is removed.
     */
    suspend fun saveFile(uri: Uri): FileBackupReport = fileRun { runSaveFile(uri) }

    /** The manifest of the backup file at [uri], to confirm before [restoreFile]; throws when it can't be restored. */
    suspend fun peekFile(uri: Uri): BackupManifest = withContext(Dispatchers.IO) {
        val task = currentCoroutineContext()
        openInput(uri).buffered().use { BackupArchive.peek(it) { task.ensureActive() } }
    }

    /** Puts this phone back as the backup file at [uri] was, like a Drive restore, and copies in the photos it needs. */
    suspend fun restoreFile(uri: Uri): RestoreReport = fileRun { runRestoreFile(uri) }

    /** Stops a backup file being saved or restored (Drive backups run on). */
    fun cancelFile() {
        fileJob?.cancel()
    }

    @Volatile private var fileJob: Job? = null

    private suspend fun <T> fileRun(block: suspend () -> T): T = lock.withLock {
        fileJob = currentCoroutineContext()[Job]
        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            fileJob = null
            _progress.value = null
        }
    }

    /** The newest backup in the folder or marker; a failed marker update must not hide a newer archive. */
    suspend fun latest(): BackupEntry? = withContext(Dispatchers.IO) {
        val marker = try {
            drive.readAppData(BackupNames.MARKER)?.let { BackupCodec.decodeMarker(it.toString(Charsets.UTF_8)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveNotConnectedException) {
            throw e
        } catch (_: Exception) {
            null // The marker is a shortcut; the folder below is the truth.
        }
        val marked = marker?.takeIf { drive.fileOrNull(it.id) != null }?.let(BackupNames::fromMarker)
        // The newest that holds something: an empty backup never hides the full one before it.
        BackupNames.best(list() + listOfNotNull(marked))
    }

    /** Puts this phone back as backup [entry] was and downloads the photos and videos it needs. */
    suspend fun restore(entry: BackupEntry): RestoreReport = lock.withLock {
        try {
            withContext(Dispatchers.IO) { runRestore(entry) }
        } finally {
            _progress.value = null
        }
    }

    private fun step(restoring: Boolean, label: String, done: Int = 0, total: Int = 0, file: Boolean = false) {
        _progress.value = BackupProgress(restoring, label, done, total, file)
    }

    private suspend fun runBackup(): BackupReport {
        step(false, "Checking Google Drive")
        if (!drive.isConnected()) throw DriveNotConnectedException()
        // A phone with nothing of the traveller's own writes no backup: an empty one would be the newest a new phone is offered.
        if (!BackupSafety.worthBackingUp(readSnapshot())) throw NothingToBackUpException()
        var photos = uploadPhotos()

        step(false, "Packing trips and entries")
        var snapshot = BackupSafety.clean(readSnapshot())
        // A photo added while the uploads ran goes up too. After two more rounds the backup goes on with what it has:
        // a photo that can't be saved now never stops the rest (owner, 2026-11-11: "under no circumstances should the
        // backup fail"); it is counted, and the next backup tries it again.
        var rounds = 0
        while (rounds++ < 2 && !photos.stopped && BackupSafety.photosLeft(snapshot.media, photos.verified, photos.waiting).any { it.id !in photos.failed }) {
            photos = uploadPhotos()
            snapshot = BackupSafety.clean(readSnapshot())
        }
        val photosLeft = BackupSafety.photosLeft(snapshot.media, photos.verified, photos.waiting).size
        val createdAt = System.currentTimeMillis()
        val clips = savedClips(snapshot)
        val data = snapshot.toData(backupPreferences())
            .copy(sharedTrips = sharedTrips(), savedClips = clips.map(::clipRow), clipEdits = clipEdits(snapshot))
        // The same rows and settings as the last backup, which Drive still has: no second copy of it.
        val sum = BackupCodec.sum(data)
        val last = lastBackup(sum)
        val entry = last ?: uploadRows(snapshot, data, sum, createdAt)
        if (last != null) settings.setBackupCheckedAt(createdAt)
        // The big files last and never in the backup's way: what doesn't go up now goes with the next backup.
        val (videos, videosLeft) = uploadVideos(clips)
        uploadEditorFiles(snapshot)
        return BackupReport(entry, photosLeft, videos, videosLeft, unchanged = last != null)
    }

    /**
     * The last backup this phone wrote, when its rows and settings are exactly [sum]'s and Drive still has it whole;
     * null when anything changed, the backup was deleted from Drive, or Drive can't say now (then a new one is written).
     */
    private suspend fun lastBackup(sum: String): BackupEntry? {
        val stamp = BackupCodec.decodeStamp(settings.backupStamp.first())?.takeIf { it.sum == sum } ?: return null
        val file = try {
            drive.fileOrNull(stamp.marker.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveNotConnectedException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val size = file?.size?.takeIf { it > 0 } ?: return null
        return BackupNames.fromMarker(stamp.marker).copy(size = size)
    }

    /** Writes [data] as a new backup zip in Drive, checked, with its marker; old backups pruned. */
    private suspend fun uploadRows(snapshot: Snapshot, data: BackupData, sum: String, createdAt: Long): BackupEntry {
        val task = currentCoroutineContext()
        val manifest = BackupCodec.manifest(snapshot, BuildConfig.VERSION_NAME, createdAt)
        val name = BackupNames.fileName(createdAt, zone)
        val zip = File(app.cacheDir, "backup/backup-$createdAt.zip")
        zip.parentFile?.mkdirs()
        try {
            zip.outputStream().buffered().use { BackupCodec.write(manifest, data, it) { task.ensureActive() } }
            // Read back as a restore would read it: a backup that can't be restored is never uploaded as one.
            val readBack = zip.inputStream().buffered().use { BackupCodec.read(it) { task.ensureActive() } }.second.toSnapshot()
            if (readBack.tableCounts() != snapshot.tableCounts()) throw IOException("The backup didn't pass its own check. Try Back up now again.")
            step(false, "Uploading the backup")
            // A folder renamed or moved since (or, on a new phone, wherever the newest backup is) before a new one.
            BackupFolder.find(drive)
            val file = drive.intoFolder(DriveApi.BACKUPS) { folder ->
                drive.uploadFile(zip, name, BackupNames.MIME, listOf(folder), appProperties = BackupNames.properties(manifest))
            }
            if (file.size != null && file.size != zip.length()) {
                // Never counted as a backup, never a reason to delete an older one.
                try { drive.delete(file.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* It stays; the next backup's rotation removes it. */ }
                throw IOException("The backup didn't reach Google Drive whole. Try Back up now again.")
            }
            val entry = BackupEntry(file.id, file.name, createdAt, manifest.items, file.size ?: zip.length())
            val marker = BackupMarker(id = file.id, name = file.name, createdAt = createdAt, items = manifest.items, appVersion = manifest.appVersion)
            writeMarker(marker)
            prune(keepId = file.id)
            settings.setLastBackupAt(createdAt)
            settings.setBackupStamp(BackupCodec.encodeStamp(BackupStamp(sum, marker)))
            // This phone backs up now: there is nothing left to ask about restoring.
            settings.setRestoreAsked(true)
            return entry
        } finally {
            zip.delete()
        }
    }

    /** The portable settings, with the trip video's remembered choices ([PrefsBackup]). */
    private suspend fun backupPreferences(): BackupPreferences = settings.backupPreferences().copy(
        clipStyle = PrefsBackup.read(app, PrefsBackup.CLIP),
        mapStyle = PrefsBackup.read(app, PrefsBackup.MAP_STYLE),
        clipMusic = PrefsBackup.read(app, PrefsBackup.MUSIC),
    )

    /** The saved videos of the traveller's own live trips on this phone (the examples' stay out, like their rows). */
    private fun savedClips(snapshot: Snapshot): List<SavedClip> {
        val trips = snapshot.trips.filter { it.deletedAt == null }.mapTo(HashSet()) { it.id }
        return SavedClips.of(app).listNow().filter { it.tripId in trips && BackupVideos.safeId(it.id) }
    }

    private fun clipRow(c: SavedClip) = ClipRow(c.id, c.tripId, c.title, c.createdAt, c.durationMs, c.width, c.height)

    private fun clipOf(r: ClipRow) = SavedClip(r.id, r.tripId, r.title, r.createdAt, r.durationMs.coerceAtLeast(0), r.width.coerceAtLeast(0), r.height.coerceAtLeast(0))

    /** Each live trip's video scene edits as text, for the rows of a Drive backup; null when there are none. */
    private fun clipEdits(snapshot: Snapshot): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        for (trip in snapshot.trips) {
            if (trip.deletedAt != null) continue
            val file = ClipEditStore.file(app, trip.id)
            if (!file.isFile || file.length() == 0L || file.length() > BackupExtras.MAX_EDITS_BYTES) continue
            runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { out[trip.id] = it }
        }
        return out.ifEmpty { null }
    }

    /** Only on Wi‑Fi is on and this network is metered: the videos wait (a daily backup already waited for Wi‑Fi). */
    private suspend fun videosWait(): Boolean = settings.wifiOnly.first() && runCatching {
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(false)

    /**
     * Saved videos into BoringTravel › Videos, each once ([BackupVideos]). Returns (saved now, still to go). Never fails
     * the backup: its rows and photos are in Drive already; a video that doesn't go up now goes with the next backup.
     */
    private suspend fun uploadVideos(clips: List<SavedClip>): Pair<Int, Int> {
        if (clips.isEmpty()) return 0 to 0
        val store = SavedClips.of(app)
        var done = 0
        var todo = clips.size
        try {
            val bytes = clips.associate { it.id to store.video(it).length() }
            val remote = drive.intoFolder(DriveApi.VIDEOS) { folder -> drive.list(DriveJson.filesIn(folder), limit = MAX_LISTED) }
            val wanted = BackupVideos.toUpload(clips, bytes, remote)
            todo = wanted.size
            if (wanted.isEmpty() || videosWait()) return 0 to todo
            var failed = 0
            for ((i, c) in wanted.withIndex()) {
                step(false, "Saving videos to Drive", i, wanted.size)
                try {
                    drive.intoFolder(DriveApi.VIDEOS) { folder ->
                        drive.uploadFile(store.video(c), BackupVideos.fileName(c), VIDEO_MIME, listOf(folder), appProperties = BackupVideos.properties(c))
                    }
                    done++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Twice in a row is the connection, not the video: the rest waits for the next backup.
                    if (++failed >= 2) break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The folder couldn't be read or made now.
        }
        return done to (todo - done).coerceAtLeast(0)
    }

    /**
     * The voice lines and phone videos the trips' video edits use, each once in BoringTravel › Videos › Editor files,
     * kept by their path on the phone. Never fails the backup.
     */
    private suspend fun uploadEditorFiles(snapshot: Snapshot) {
        try {
            val files = LinkedHashMap<String, File>()
            for (trip in snapshot.trips) {
                if (trip.deletedAt != null) continue
                clipMedia(trip.id).forEach { (entry, file) ->
                    if (file.isFile && file.length() > 0) BackupExtras.target(entry)?.let { files[it] = file }
                }
            }
            if (files.isEmpty() || videosWait()) return
            val remote = drive.intoFolder(DriveApi.EDITOR_FILES) { folder -> drive.list(DriveJson.filesIn(folder), limit = MAX_LISTED) }
            val wanted = BackupVideos.filesToUpload(files.mapValues { it.value.length() }, remote)
            var failed = 0
            for ((i, path) in wanted.withIndex()) {
                step(false, "Saving the video editor's files", i, wanted.size)
                val file = files.getValue(path)
                try {
                    drive.intoFolder(DriveApi.EDITOR_FILES) { folder ->
                        drive.uploadFile(file, file.name, if (file.name.endsWith(".mp4")) VIDEO_MIME else "application/octet-stream", listOf(folder), appProperties = mapOf(BackupVideos.PATH to path))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (++failed >= 2) break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // They go with the next backup.
        }
    }

    /**
     * What [uploadPhotos] did: the Drive copies it found or made ([verified]), the photos left for their shared trip
     * to bring back ([waiting]), the photos it couldn't save this time ([failed]), and whether it gave up on the
     * uploads because Drive stopped answering ([stopped]).
     */
    private class PhotoRun(val verified: Set<String>, val waiting: Set<String>, val failed: Set<String>, val stopped: Boolean)

    /**
     * Uploads each photo once; a photo already in Drive (same content) is linked, not uploaded again. The example
     * trips' pictures stay on the phone, like the rest of the demo. One photo never stops the backup: a photo whose
     * file is missing or damaged, or that Drive doesn't take, is left for the next backup ([PhotoRun.failed]).
     */
    private suspend fun uploadPhotos(): PhotoRun {
        val all = BackupSafety.ownPhotos(DemoTrips.withoutDemoMedia(db.entries().all(), db.media().all()))
        val known = HashMap<String, String>()
        val verified = HashSet<String>()
        val waiting = HashSet<String>()
        val failed = HashSet<String>()
        val missingRemote = HashSet<String>()
        val task = currentCoroutineContext()
        // One look at the Photos folder instead of a request per photo; a photo moved out of it is asked for by id.
        val inFolder: Set<String> = try {
            drive.findFolder(DriveApi.PHOTOS, create = false)
                ?.let { folder -> drive.list(DriveJson.filesIn(folder), limit = MAX_LISTED).filter { (it.size ?: 0L) > 0 }.mapTo(HashSet()) { it.id } }
                ?: emptySet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveNotConnectedException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }
        for (m in all) {
            task.ensureActive()
            val id = m.driveFileId ?: continue
            if (id !in verified && id !in missingRemote) {
                val there = id in inFolder || try {
                    drive.fileOrNull(id)?.size?.let { it > 0 } == true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DriveNotConnectedException) {
                    throw e
                } catch (_: Exception) {
                    true // Drive couldn't say now: keep the copy it had (never a second upload, never a stopped backup).
                }
                if (there) verified += id else missingRemote += id
            }
            if (id in verified) m.sha256?.let { known[it] = id }
        }
        var refused = 0
        var stopped = false
        for ((i, m) in all.withIndex()) {
            task.ensureActive()
            step(false, "Saving photos to Drive", i, all.size)
            if (m.driveFileId?.let { it in verified } == true) continue
            var checksum = m.sha256
            var driveId = checksum?.let(known::get)
            if (driveId == null) {
                val file = File(app.filesDir, m.localPath)
                val readable = BackupMerge.safePath(m).localPath == m.localPath && try {
                    BackupFiles.usable(file, checksum) { task.ensureActive() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!readable) {
                    // Its copy from the shared trip is on the way (the next sync fetches it); otherwise its file is
                    // missing or damaged here and Drive has no copy: the rest of the backup goes on without it.
                    if (comesBackShared(m)) waiting += m.id else failed += m.id
                    continue
                }
                if (stopped) {
                    failed += m.id
                    continue
                }
                try {
                    if (checksum == null) checksum = BackupFiles.sha256(file) { task.ensureActive() }
                    val sum = requireNotNull(checksum)
                    driveId = drive.intoFolder(DriveApi.PHOTOS) { folder ->
                        drive.uploadFile(file, file.name, m.mime, listOf(folder), appProperties = mapOf("mediaId" to m.id, "sha256" to sum))
                    }.id
                    refused = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DriveNotConnectedException) {
                    throw e
                } catch (_: Exception) {
                    failed += m.id
                    // Three in a row is the connection, not the photos: stop asking, the next backup takes them.
                    if (++refused >= 3) stopped = true
                    continue
                }
            }
            val linked: String = driveId
            verified += linked
            checksum?.let { known[it] = linked }
            // Link only the same original; keep concurrent caption/association edits and their timestamp.
            db.withTransaction {
                db.media().get(m.id)?.takeIf { it.localPath == m.localPath && it.sha256 == m.sha256 && it.driveFileId == m.driveFileId }
                    ?.let { db.media().upsert(it.copy(driveFileId = linked, sha256 = checksum)) }
            }
        }
        return PhotoRun(verified, waiting, failed, stopped)
    }

    /**
     * One of the owner's photos that this phone only knows from a shared trip: added after the backup it was restored
     * from, so neither its original nor a backup copy is here, only the small copy in the trip's shared folder.
     * Together fetches that copy into its path; from then on it is backed up like any photo.
     */
    private fun comesBackShared(m: Media) = m.authorId == null && m.sharedFileId != null && m.sha256 == null && m.driveFileId == null

    private suspend fun writeMarker(marker: BackupMarker) {
        try {
            drive.writeAppData(BackupNames.MARKER, BackupCodec.encodeMarker(marker).toByteArray(Charsets.UTF_8))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Without drive.appdata (or offline at that moment) a new phone still finds the backup in the folder.
        }
    }

    private suspend fun prune(keepId: String) {
        val old = try {
            BackupNames.toPrune(list())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // Pruning waits for the next backup.
        }
        old.filter { it.id != keepId }.forEach {
            try { drive.delete(it.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Retry next time. */ }
        }
    }

    private suspend fun runRestore(entry: BackupEntry): RestoreReport {
        step(true, "Downloading the backup")
        val zip = File(app.cacheDir, "restore/${entry.id.filter { it.isLetterOrDigit() || it in "-_" }}.zip")
        try {
            val task = currentCoroutineContext()
            drive.download(entry.id, zip) { bytes ->
                if (bytes > BackupCodec.MAX_DATA_BYTES + 2 * 1024 * 1024) throw BackupFormatException("This backup is too big to open on this phone.")
            }
            val (manifest, data) = zip.inputStream().buffered().use { BackupCodec.read(it) { task.ensureActive() } }
            // Never the examples (older backups carried them): the demo on this phone stays its own. A row with an
            // impossible place is mended, not a reason to refuse the rest ([BackupSafety.clean]).
            val incoming = BackupSafety.clean(DemoTrips.withoutDemo(data.toSnapshot()))
            val backupAt = manifest.createdAt.takeIf { it > 0 } ?: entry.createdAt

            step(true, "Restoring trips and entries")
            val plan = mergeIn(data, incoming)
            val (downloaded, missing) = downloadMissingPhotos(plan.media.mapTo(HashSet()) { it.id })
            // The video editor's work, then the saved videos and the files the edits use: none of them stops a restore.
            val tripIds = incoming.trips.map { it.id }
            data.clipEdits?.let { edits ->
                runCatching {
                    restoreClipEdits(
                        edits.mapNotNull { (trip, text) -> BackupExtras.clipEdits(ClipEditStore.file(app, trip).name)?.let { it to text.toByteArray(Charsets.UTF_8) } }.toMap(),
                        tripIds,
                    )
                }
            }
            val videos = downloadVideos(data.savedClips, backupAt)
            downloadEditorFiles(tripIds)
            return report(backupAt, incoming, plan, downloaded, missing, videos, fromFile = false)
        } finally {
            zip.delete()
            File(zip.path + ".part").delete()
        }
    }

    /**
     * Puts this phone back as the backup was ([BackupMerge]), keeping the phone as it is for [undoRestore] in the same
     * step; then the backup's settings and shared trips. The same for a Drive backup and a backup file. [incoming] is
     * [data]'s rows without the examples.
     */
    private suspend fun mergeIn(data: BackupData, incoming: Snapshot): MergePlan {
        val task = currentCoroutineContext()
        // Read before the transaction (neither is in the database): what the copy kept for Undo carries besides rows.
        val prefs = backupPreferences()
        val sharing = sharedTrips()
        val staged = File(undoFile.path + ".part")
        try {
            val plan = BackupTransactions.replace(
                incoming = incoming,
                shared = sharedNow(),
                transaction = { work -> db.withTransaction { work() } },
                read = ::readSnapshotRows,
                keep = { local -> keepForUndo(local, prefs, sharing, staged) },
                write = { plan ->
                    plan.clearRoutes.forEach { trip -> if (trip == null) db.tracks().deleteOutsideTrips() else db.tracks().deleteForTrip(trip) }
                    if (plan.trips.isNotEmpty()) db.trips().upsertAll(plan.trips)
                    if (plan.planItems.isNotEmpty()) db.planItems().upsertAll(plan.planItems)
                    if (plan.entries.isNotEmpty()) db.entries().upsertAll(plan.entries)
                    if (plan.media.isNotEmpty()) db.media().upsertAll(plan.media)
                    if (plan.stops.isNotEmpty()) db.stops().upsertAll(plan.stops)
                    if (plan.countryMarks.isNotEmpty()) db.countryMarks().upsertAll(plan.countryMarks)
                    if (plan.books.isNotEmpty()) db.books().upsertAll(plan.books)
                    plan.trackPoints.chunked(5_000).forEach { task.ensureActive(); db.tracks().insertAll(it) }
                },
            )
            if (!plan.isEmpty) {
                // Written: the copy made in the same step is what Undo restores (none when the phone held nothing).
                if (staged.isFile) Files.move(staged.toPath(), undoFile.toPath(), StandardCopyOption.REPLACE_EXISTING) else undoFile.delete()
                loadUndo()
            }
            // The settings come back with the rows (owner, 2026-11-14): theme, fonts, traveller, the video's, the
            // Share screen's and each trip's photo defaults. Undo restore brings this phone's back the same way.
            settings.restorePreferences(data.preferences, replace = true)
            data.preferences?.let {
                PrefsBackup.replace(app, PrefsBackup.CLIP, it.clipStyle)
                PrefsBackup.replace(app, PrefsBackup.MAP_STYLE, it.mapStyle)
                PrefsBackup.replace(app, PrefsBackup.MUSIC, it.clipMusic)
            }
            // A backup was restored here: the question about restoring is answered.
            settings.setRestoreAsked(true)
            data.sharedTrips?.let { runCatching { restoreSharedTrips(it) } }
            // The restored trips are the traveller's own: show them, not the demo.
            settings.setDemoMode(false)
            reschedule()
            return plan
        } finally {
            staged.delete()
        }
    }

    /**
     * The phone as it is ([local]) as a backup file without photos, for [undoRestore]: a restore never deletes a photo
     * or video file, so the rows are all it takes to go back. Nothing is kept for a phone with nothing of its own.
     */
    private fun keepForUndo(local: Snapshot, prefs: BackupPreferences, sharing: String?, to: File) {
        val own = BackupSafety.clean(DemoTrips.withoutDemo(local))
        if (!BackupSafety.worthBackingUp(own)) return
        to.parentFile?.mkdirs()
        val manifest = BackupCodec.manifest(own, BuildConfig.VERSION_NAME, System.currentTimeMillis(), BackupCodec.FILE_FORMAT)
        to.outputStream().buffered().use { BackupArchive.write(manifest, own.toData(prefs).copy(sharedTrips = sharing), emptyList(), it) }
    }

    private fun report(backupAt: Long, incoming: Snapshot, plan: MergePlan, photos: Int, photosMissing: Int, videos: Int, fromFile: Boolean) = RestoreReport(
        backupAt,
        restored = BackupCounts(incoming.trips.count { it.deletedAt == null }, incoming.entries.count { it.deletedAt == null }, incoming.media.count { it.deletedAt == null }),
        photosDownloaded = photos, photosMissing = photosMissing, fromFile = fromFile,
        planItems = incoming.planItems.count { it.deletedAt == null }, videos = videos, changed = !plan.isEmpty,
    )

    /**
     * Brings back the saved videos of the backup being restored ([inBackup]; null for a backup older than the Videos
     * folder: then every video made up to [backupAt]) that this phone doesn't have, from BoringTravel › Videos. Returns
     * how many came back. Never fails the restore.
     */
    private suspend fun downloadVideos(inBackup: List<ClipRow>?, backupAt: Long): Int {
        var done = 0
        try {
            val folder = drive.findFolder(DriveApi.VIDEOS, create = false) ?: return 0
            val store = SavedClips.of(app)
            val have = store.listNow().mapTo(HashSet()) { it.id }
            val liveTrips = db.trips().all().filter { it.deletedAt == null && !it.isDemo }.mapTo(HashSet()) { it.id }
            val rows = inBackup?.filter { BackupVideos.safeId(it.id) }?.associateBy { it.id }
            val wanted = BackupVideos.toFetch(drive.list(DriveJson.filesIn(folder), limit = MAX_LISTED), have, liveTrips, rows?.keys, backupAt)
            var failed = 0
            for ((i, pair) in wanted.withIndex()) {
                val (fromDrive, file) = pair
                step(true, "Downloading videos", i, wanted.size)
                // The backup's own record of the video (its whole title); Drive's properties when the backup has none.
                val clip = rows?.get(fromDrive.id)?.let(::clipOf) ?: fromDrive
                val staged = File(store.dir, "${clip.id}.restore")
                try {
                    drive.download(file.id, staged)
                    if (file.size != null && staged.length() != file.size) throw IOException("The video didn't download whole.")
                    if (store.restore(clip, staged)) done++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (++failed >= 2) break
                } finally {
                    staged.delete()
                    File(staged.path + ".part").delete()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Restore again later to fetch them.
        }
        return done
    }

    /** The voice lines and phone videos the restored trips' video edits use and the phone lacks, from Drive. Never fails the restore. */
    private suspend fun downloadEditorFiles(tripIds: List<String>) {
        try {
            val wanted = LinkedHashMap<String, File>()
            tripIds.forEach { id ->
                clipMedia(id).forEach { (entry, file) -> if (!file.exists()) BackupExtras.target(entry)?.let { wanted[it] = file } }
            }
            if (wanted.isEmpty()) return
            val folder = drive.findFolder(DriveApi.EDITOR_FILES, create = false) ?: return
            val found = BackupVideos.filesToFetch(wanted.keys, drive.list(DriveJson.filesIn(folder), limit = MAX_LISTED))
            var i = 0
            for ((path, remote) in found) {
                step(true, "Downloading the video editor's files", i++, found.size)
                val target = wanted.getValue(path)
                if (target.exists() || !inFiles(target)) continue
                try {
                    drive.download(remote.id, target)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    File(target.path + ".part").delete()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Restore again later to fetch them.
        }
    }

    private suspend fun runSaveFile(uri: Uri): FileBackupReport {
        val task = currentCoroutineContext()
        step(false, "Packing trips and entries", file = true)
        val snapshot = BackupSafety.clean(readSnapshot())
        val videos = savedClips(snapshot)
        val data = snapshot.toData(backupPreferences()).copy(sharedTrips = sharedTrips(), savedClips = videos.map(::clipRow))
        val manifest = BackupCodec.manifest(snapshot, BuildConfig.VERSION_NAME, System.currentTimeMillis(), BackupCodec.FILE_FORMAT)
        val (photos, notOnPhone) = photoFiles(snapshot)
        // The editor's work, then (after the photos) its sound and videos and the saved videos themselves.
        val store = SavedClips.of(app)
        val clip = clipFiles(snapshot) + videos.mapNotNull { c ->
            val file = store.video(c)
            BackupExtras.clipVideo(c.id)?.takeIf { file.isFile && file.length() > 0 }?.let { ArchiveFile(it, file) }
        }
        // The small scene edits first, then the photos, then the clips' sound and videos.
        val files = clip.filter { BackupExtras.isText(it.name) } + photos + clip.filterNot { BackupExtras.isText(it.name) }
        var saved = false
        try {
            val leftOut = openOutput(uri).buffered().use { out ->
                BackupArchive.write(manifest, data, files, out, { task.ensureActive() }) { done, total ->
                    step(false, "Saving photos and files", done, total, file = true)
                }
            }
            saved = true
            val gone = leftOut.count { it.startsWith(BackupArchive.MEDIA) }
            return FileBackupReport(manifest.items, photos.size - gone, notOnPhone + gone)
        } finally {
            // A half-written backup file must not look like a good one.
            if (!saved) runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
        }
    }

    /**
     * The traveller's own photos for media/, and how many aren't on the phone. Other members' copies in shared trips
     * stay out, as in Drive backups: they come back through the shared trip.
     */
    private fun photoFiles(snapshot: Snapshot): Pair<List<ArchiveFile>, Int> {
        val files = LinkedHashMap<String, ArchiveFile>()
        var missing = 0
        for (m in snapshot.media) {
            if (m.deletedAt != null || m.authorId != null) continue
            val name = BackupArchive.mediaEntry(m.localPath)
            val file = name?.let { File(app.filesDir, m.localPath) }
            if (name == null || file == null || !file.isFile || file.length() == 0L) {
                missing++
                continue
            }
            files.getOrPut(name) { ArchiveFile(name, file) }
        }
        return files.values.toList() to missing
    }

    /** Each own trip's clip scene edits and the voice lines and videos they use (extras/), where the phone has them. */
    private suspend fun clipFiles(snapshot: Snapshot): List<ArchiveFile> {
        val out = ArrayList<ArchiveFile>()
        for (trip in snapshot.trips) {
            if (trip.deletedAt != null) continue
            val editsFile = ClipEditStore.file(app, trip.id)
            val entry = BackupExtras.clipEdits(editsFile.name) ?: continue
            if (!editsFile.isFile) continue
            out += ArchiveFile(entry, editsFile)
            clipMedia(trip.id).forEach { (name, file) -> if (file.isFile && file.length() > 0) out += ArchiveFile(name, file) }
        }
        return out
    }

    /** The voice lines and videos trip [tripId]'s edits on this phone use: their entry in a backup file and their file. */
    private suspend fun clipMedia(tripId: String): Map<String, File> {
        val edits = ClipEditStore.load(app, tripId).values
        val files = edits.flatMap { e -> e.videos.map { BackupExtras.CLIP_MEDIA to ClipMedia.videoFile(app, tripId, it) } } +
            edits.flatMap { e -> e.voices.map { BackupExtras.CLIP_VOICE to ClipMedia.voiceFile(app, tripId, it) } }
        return files.mapNotNull { (kind, f) -> BackupExtras.clipFile(kind, f.parentFile?.name.orEmpty(), f.name)?.let { it to f } }.toMap()
    }

    private suspend fun runRestoreFile(uri: Uri): RestoreReport {
        val task = currentCoroutineContext()
        step(true, "Reading the backup file", file = true)
        // First pass: every entry checked, the rows read. Nothing on the phone changes before it passes.
        val contents = openInput(uri).buffered().use { BackupArchive.read(it) { task.ensureActive() } }
        val incoming = BackupSafety.clean(DemoTrips.withoutDemo(contents.data.toSnapshot()))
        step(true, "Restoring trips and entries", file = true)
        val plan = mergeIn(contents.data, incoming)
        restoreClipEdits(contents.texts, incoming.trips.map { it.id })
        // Second pass: the photos, clip files and saved videos this phone needs.
        val copy = copyFromFile(uri, contents, plan.media.mapTo(HashSet()) { it.id }, incoming.trips.map { it.id })
        return report(contents.manifest.createdAt, incoming, plan, copy.photos, copy.photosMissing, copy.videos, fromFile = true)
    }

    /** What the second pass over a backup file brought in. */
    private class FileCopy(val photos: Int, val photosMissing: Int, val videos: Int)

    /** Scene edits from the file for trips with none on this phone; edits made on this phone always stay. */
    private fun restoreClipEdits(texts: Map<String, ByteArray>, tripIds: List<String>) {
        val names = tripIds.mapTo(HashSet()) { ClipEditStore.file(app, it).name }
        for ((name, bytes) in texts) {
            val relative = BackupExtras.target(name) ?: continue
            if (BackupExtras.kind(name) != BackupExtras.CLIP_EDITS || relative.substringAfter('/') !in names) continue
            if (ClipEditsJson.decode(bytes.toString(Charsets.UTF_8)).isEmpty()) continue
            placeFile(File(app.filesDir, relative), bytes.inputStream())
        }
    }

    /**
     * Second pass over the file: copies in the photos of this backup the phone lacks (or holds damaged), each checked
     * against its checksum, and the clip files the phone's edits use but doesn't have. [merged]: the photos the restore
     * just changed. Returns (copied, missing).
     */
    private suspend fun copyFromFile(uri: Uri, contents: ArchiveContents, merged: Set<String>, tripIds: List<String>): FileCopy {
        val task = currentCoroutineContext()
        val inFile = contents.data.media.associate { it.id to it.localPath }
        val photos = HashMap<String, MutableList<Media>>()
        var wanted = 0
        // Other members' photos come back through the shared trip itself, as after a Drive restore.
        val own = db.media().all().filter { it.deletedAt == null && it.authorId == null }
        step(true, "Checking photos", 0, own.size, file = true)
        for ((i, m) in own.withIndex()) {
            task.ensureActive()
            if (i % 50 == 0) step(true, "Checking photos", i, own.size, file = true)
            val path = inFile[m.id] ?: continue
            val entry = BackupArchive.mediaEntry(path)
            val file = File(app.filesDir, BackupMerge.safePath(m).localPath)
            if (!needsPhoto(m, file, merged, entry?.let(contents.mediaSizes::get))) continue
            wanted++
            entry?.let { photos.getOrPut(it) { ArrayList() } += m }
        }
        val clips = HashMap<String, File>()
        tripIds.forEach { id -> clipMedia(id).forEach { (name, file) -> if (!file.exists()) clips[name] = file } }
        // The file's saved videos this phone doesn't have, of trips it has now.
        val store = SavedClips.of(app)
        val haveVideos = store.listNow().mapTo(HashSet()) { it.id }
        val liveTrips = db.trips().all().filter { it.deletedAt == null && !it.isDemo }.mapTo(HashSet()) { it.id }
        val videos = HashMap<String, SavedClip>()
        contents.data.savedClips.orEmpty().forEach { row ->
            if (row.id in haveVideos || row.tripId !in liveTrips) return@forEach
            BackupExtras.clipVideo(row.id)?.let { videos[it] = clipOf(row) }
        }
        if (photos.isEmpty() && clips.isEmpty() && videos.isEmpty()) return FileCopy(0, wanted, 0)
        var copied = 0
        var done = 0
        var videosBack = 0
        step(true, "Copying photos", 0, wanted, file = true)
        openInput(uri).buffered().use { input ->
            BackupArchive.eachFile(input, { task.ensureActive() }) { name, content ->
                photos.remove(name)?.let { rows ->
                    copied += placePhoto(rows, content)
                    done += rows.size
                    step(true, "Copying photos", done, wanted, file = true)
                }
                clips.remove(name)?.let { placeFile(it, content) }
                videos.remove(name)?.let { clip ->
                    val staged = File(store.dir, "${clip.id}.restore")
                    try {
                        staged.parentFile?.mkdirs()
                        staged.outputStream().use { content.copyTo(it, 64 * 1024) }
                        if (store.restore(clip, staged)) videosBack++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // This video stays out; the rest of the file still comes in.
                    } finally {
                        staged.delete()
                    }
                }
            }
        }
        return FileCopy(copied, wanted - copied, videosBack)
    }

    /** One photo from the file for [rows] (almost always one row); returns how many rows got it. */
    private suspend fun placePhoto(rows: List<Media>, content: InputStream): Int {
        val targets = rows.map { it to File(app.filesDir, BackupMerge.safePath(it).localPath) }.filter { inFiles(it.second) }
        val (first, firstTarget) = targets.firstOrNull() ?: return 0
        firstTarget.parentFile?.mkdirs()
        val staged = File(firstTarget.path + ".restore")
        try {
            staged.outputStream().use { content.copyTo(it, 64 * 1024) }
            var placed = 0
            for ((m, target) in targets.drop(1)) {
                val own = File(target.path + ".restore")
                Files.copy(staged.toPath(), own.toPath(), StandardCopyOption.REPLACE_EXISTING)
                if (publishIfCurrent(m, own, target)) placed++
            }
            if (publishIfCurrent(first, staged, firstTarget)) placed++
            return placed
        } finally {
            staged.delete()
        }
    }

    /**
     * Whether a restore should bring photo [m]'s file in: it is missing or empty, its size differs from the backup
     * file's copy ([archived], a file restore only), or the restore just changed the row ([merged]) and the file no
     * longer matches its checksum. Photos the restore didn't touch aren't hashed: on a phone full of photos that took
     * minutes before anything moved.
     */
    private suspend fun needsPhoto(m: Media, file: File, merged: Set<String>, archived: Long? = null): Boolean {
        val task = currentCoroutineContext()
        if (!file.isFile || file.length() == 0L) return true
        if (archived != null && archived != file.length()) return true
        return m.id in merged && !BackupFiles.usable(file, m.sha256) { task.ensureActive() }
    }

    /** Moves [staged] to [target] if it matches [m]'s checksum and [m] is still that photo. */
    private suspend fun publishIfCurrent(m: Media, staged: File, target: File): Boolean {
        val task = currentCoroutineContext()
        try {
            if (!BackupFiles.usable(staged, m.sha256) { task.ensureActive() }) return false
            return placeIfCurrent(m, staged, target)
        } finally {
            staged.delete()
        }
    }

    /**
     * Moves the checked [staged] file to [target] if [m] is still that photo (not deleted, same path and checksum, and
     * [same] for anything else the caller relies on). A photo changed or deleted meanwhile keeps what it has.
     */
    private suspend fun placeIfCurrent(m: Media, staged: File, target: File, same: (Media) -> Boolean = { true }): Boolean {
        val task = currentCoroutineContext()
        var placed = false
        db.withTransaction {
            val current = db.media().get(m.id)
            if (current != null && current.deletedAt == null && current.localPath == m.localPath && current.sha256 == m.sha256 && same(current)) {
                // The hash was checked outside the transaction; the staged file is private to this restore.
                BackupFiles.publish(staged, target, null) { task.ensureActive() }
                placed = true
            }
        }
        return placed
    }

    /** Copies [content] to [target] when the phone still has no file there (a file on the phone always stays). */
    private fun placeFile(target: File, content: InputStream) {
        if (target.exists() || !inFiles(target)) return
        val dir = target.parentFile ?: return
        if (!dir.isDirectory && !dir.mkdirs()) return
        val part = File(dir, target.name + ".part")
        try {
            part.outputStream().use { content.copyTo(it, 64 * 1024) }
            if (!target.exists()) part.renameTo(target)
        } finally {
            part.delete()
        }
    }

    /** Whether [file] is inside the app's files folder (never a path that leaves it). */
    private fun inFiles(file: File): Boolean = file.canonicalPath.startsWith(app.filesDir.canonicalPath + File.separator)

    private fun openInput(uri: Uri): InputStream = app.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open that file.")

    /** "wt" empties a file picked again; some providers only know "w" (a new document is empty anyway). */
    private fun openOutput(uri: Uri): OutputStream {
        val resolver = app.contentResolver
        val truncating = try {
            resolver.openOutputStream(uri, "wt")
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: FileNotFoundException) {
            null
        }
        return truncating ?: resolver.openOutputStream(uri, "w") ?: throw IOException("Couldn't write to that place.")
    }

    /**
     * Downloads every photo the journal uses that isn't on the phone but is in Drive. [merged]: the photos the restore
     * just changed (see [needsPhoto]). Returns (downloaded, missing).
     */
    private suspend fun downloadMissingPhotos(merged: Set<String>): Pair<Int, Int> {
        val task = currentCoroutineContext()
        // Other members' photos come back through the shared trip itself, not from this Drive; so do the owner's own
        // that only a shared trip has ([comesBackShared]).
        val own = db.media().all().filter { it.deletedAt == null && it.authorId == null && !comesBackShared(it) }
        step(true, "Checking photos", 0, own.size)
        val wanted = own.filterIndexed { i, m ->
            if (i % 50 == 0) step(true, "Checking photos", i, own.size)
            needsPhoto(m, File(app.filesDir, BackupMerge.safePath(m).localPath), merged)
        }
        var downloaded = 0
        var missing = 0
        wanted.forEachIndexed { i, m ->
            step(true, "Downloading photos", i, wanted.size)
            val id = m.driveFileId
            val target = File(app.filesDir, BackupMerge.safePath(m).localPath)
            if (id == null) {
                missing++
                return@forEachIndexed
            }
            val staged = File(target.path + ".restore")
            try {
                drive.download(id, staged)
                if (!BackupFiles.usable(staged, m.sha256) { task.ensureActive() }) throw IOException("The downloaded photo does not match its backup checksum.")
                // A photo may be changed/deleted during the network request. Never publish against a stale row.
                if (placeIfCurrent(m, staged, target) { it.driveFileId == m.driveFileId }) downloaded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveNotConnectedException) {
                throw e
            } catch (_: Exception) {
                missing++
            } finally {
                staged.delete()
                File(staged.path + ".part").delete()
            }
        }
        return downloaded to missing
    }

    private companion object {
        /** More files than any of the app's Drive folders holds: one listing reads the whole folder. */
        const val MAX_LISTED = 100_000
        const val VIDEO_MIME = "video/mp4"
    }

    /** Every row of the traveller's own: the example trips and their rows never go into a backup. */
    private suspend fun readSnapshot(): Snapshot = DemoTrips.withoutDemo(db.withTransaction { readSnapshotRows() })

    private suspend fun readSnapshotRows() = Snapshot(
        trips = db.trips().all(),
        planItems = db.planItems().all(),
        entries = db.entries().all(),
        media = db.media().all(),
        trackPoints = db.tracks().all(),
        stops = db.stops().all(),
        countryMarks = db.countryMarks().all(),
        books = db.books().all(),
    )
}
