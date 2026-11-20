package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.backup.BackupArchive
import com.justdataplease.boringtravel.backup.BackupCodec
import com.justdataplease.boringtravel.backup.BackupCounts
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupEntry
import com.justdataplease.boringtravel.backup.BackupExtras
import com.justdataplease.boringtravel.backup.BackupFormatException
import com.justdataplease.boringtravel.backup.BackupMarker
import com.justdataplease.boringtravel.backup.BackupMerge
import com.justdataplease.boringtravel.backup.BackupNames
import com.justdataplease.boringtravel.backup.BackupReport
import com.justdataplease.boringtravel.backup.BackupSafety
import com.justdataplease.boringtravel.backup.BackupSchedule
import com.justdataplease.boringtravel.backup.BackupStamp
import com.justdataplease.boringtravel.backup.BackupVideos
import com.justdataplease.boringtravel.backup.ClipRow
import com.justdataplease.boringtravel.backup.RestoreReport
import com.justdataplease.boringtravel.backup.Snapshot
import com.justdataplease.boringtravel.backup.toData
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.clip.ClipMedia
import com.justdataplease.boringtravel.data.SavedClip
import com.justdataplease.boringtravel.data.SavedClipIndex
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.google.DriveJson
import com.justdataplease.boringtravel.google.DriveNotConnectedException
import com.justdataplease.boringtravel.google.RestDriveApi
import com.justdataplease.boringtravel.io.IOException
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Where Drive backups go (the Android app's backup/BackupFolder.kt): My Drive › BoringTravel › Backups by default, made
 * at the first backup. A folder the owner renamed or moved is found again by the id the phone remembers
 * ([RestDriveApi.findFolder]); on a new phone, through the newest backup in it (the hidden marker). A deleted folder is
 * made again at the default place by the next backup.
 */
object BackupFolder {
    /** What the backup screen shows. */
    const val DEFAULT_PLACE = "My Drive › BoringTravel › Backups"

    /** The Backups folder, or null when there is none yet. */
    suspend fun find(drive: RestDriveApi): String? {
        drive.findFolder(DriveApi.BACKUPS, create = false)?.let { return it }
        val marker = try {
            drive.readAppData(BackupNames.MARKER)?.let { BackupCodec.decodeMarker(it.decodeToString()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveNotConnectedException) {
            throw e
        } catch (_: Exception) {
            null // The marker is a shortcut; without it a new folder is made at the default place.
        } ?: return null
        val folder = drive.parentOf(marker.id) ?: return null
        drive.rememberFolder(DriveApi.BACKUPS, folder)
        return folder
    }

    /** The folder in the Drive app (or the browser). */
    fun url(id: String): String = "https://drive.google.com/drive/folders/$id"
}

/** Nothing of the traveller's own is on this phone, so no backup is written ([BackupSafety.worthBackingUp]). */
class NothingToBackUpException : IOException("Nothing to back up yet.")

/** Where the phone keeps the daily backup's turn: iPhone's BGTaskScheduler; a stand-in that only notes it elsewhere. */
fun interface BackupPlanner {
    /** Asks the phone to run the daily backup at [atMs] or later; null takes the request away. */
    fun planDailyBackup(atMs: Long?)
}

/** How a daily backup's run ended (Android's Worker results). */
enum class DailyResult { Done, Skipped, Retry }

/**
 * Drive backups (the Android app's backup/BackupManager.kt, its Drive half, written again on the trip store and this
 * app's files): the rows and settings as one zip in BoringTravel › Backups (the newest [BackupNames.KEEP] kept), with
 * the photos and saved videos kept once each, all of them, in BoringTravel › Photos and Videos (the Android owner,
 * 2026-11-12: "videos and photos we keep all; the backups are merely for the other settings and references to these"),
 * and a small marker in the app's hidden Drive folder pointing at the newest backup. Every part goes up only when it is
 * new (2026-11-15: "if a trip does not update do not run backup again"): a photo and a video once each, the zip only
 * when its rows or settings differ from the last one this phone wrote and Drive still has ([BackupStamp]). A restore
 * puts the phone back as the backup was, like a database ([BackupFiles.mergeIn], with Undo), and downloads the photos
 * and videos that are missing, each photo checked against its checksum; it never deletes a file.
 *
 * One backup or restore at a time, a file's or Drive's: [files]' lock and progress (shown on every screen).
 */
class DriveBackups(
    private val files: BackupFiles,
    private val book: TripBook,
    private val settings: AppSettings,
    private val media: MediaFiles,
    val drive: RestDriveApi,
    private val appVersion: String,
    private val net: Net,
    private val frames: VideoFrames = VideoFrames { _, _ -> null },
    /** The daily backup's turn on the phone (iPhone: BGProcessingTask). */
    var planner: BackupPlanner = BackupPlanner {},
    private val now: () -> Long = ::nowMs,
    /** The phone's zone id for the backups' names (null: the phone's own). */
    private val zone: String? = null,
    private val background: CoroutineContext = Dispatchers.Default,
    /** Time to finish Back up now or a restore after the app leaves the screen (iPhone: UIApplication's background task). */
    private val work: BackgroundWork = NoBackgroundWork,
) {
    /** Whether this phone holds anything of the traveller's own: a restore would replace it. */
    fun hasOwnData(): Boolean = BackupSafety.worthBackingUp(readSnapshot())

    /** Plans or takes away the daily backup to match the settings (Android's reschedule). */
    fun reschedule() {
        val on = settings.autoBackup && settings.driveConnected
        planner.planDailyBackup(if (on) BackupSchedule.nextAt(now(), zone) else null)
    }

    /**
     * Backs up now: photos not in Drive yet, then (when anything changed since the last backup) the journal zip, the
     * marker, and old backups pruned; the saved videos and the video editor's files last.
     */
    suspend fun backupNow(): BackupReport = files.lock.withLock {
        val ticket = work.beginBackgroundWork(BACKUP_WORK_NAME)
        try {
            withContext(background) { runBackup() }
        } finally {
            files.progress = null
            ticket.endBackgroundWork()
        }
    }

    /** Backups in the owner's Drive, newest first. */
    suspend fun list(): List<BackupEntry> = withContext(background) {
        val folder = BackupFolder.find(drive) ?: return@withContext emptyList()
        BackupNames.sorted(drive.list(DriveJson.filesIn(folder), orderBy = "createdTime desc").mapNotNull { BackupNames.entry(it, zone) })
    }

    /** The Backups folder in Drive, wherever the owner moved it; null when there is none yet. */
    suspend fun backupsFolder(): String? = withContext(background) { BackupFolder.find(drive) }

    /** The newest backup in the folder or marker; a failed marker update must not hide a newer archive. */
    suspend fun latest(): BackupEntry? = withContext(background) {
        val marker = try {
            drive.readAppData(BackupNames.MARKER)?.let { BackupCodec.decodeMarker(it.decodeToString()) }
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
    suspend fun restore(entry: BackupEntry): RestoreReport = files.lock.withLock {
        val ticket = work.beginBackgroundWork(RESTORE_WORK_NAME)
        try {
            withContext(background) { runRestore(entry) }
        } finally {
            files.progress = null
            ticket.endBackgroundWork()
        }
    }

    /**
     * The daily backup (the Android app's BackupWorker): skipped quietly when backups are off, Drive needs the owner, one
     * ran in the last hours, the network is a costly one and Only on Wi‑Fi is on ([metered]), or the phone is saving
     * power ([lowPower]); a phone that never backed up asks about the backup in Drive first (Trips' restore offer), and
     * until then uploads nothing. A failure is tried again a few times ([DailyResult.Retry]), then the next night.
     */
    suspend fun daily(lowPower: Boolean = false): DailyResult {
        if (!settings.autoBackup || !settings.driveConnected) return DailyResult.Skipped
        val last = settings.lastBackupAt ?: 0L
        if (BackupSchedule.isRecent(last, now())) return DailyResult.Skipped
        if (lowPower || (settings.wifiOnly && net.metered())) return DailyResult.Skipped
        return try {
            if (settings.lastBackupAt == null && !settings.restoreAsked) {
                val inDrive = latest()
                if (BackupSafety.waitForRestoreAnswer(null, false, inDrive != null && BackupNames.hasData(inDrive))) return DailyResult.Skipped
                settings.restoreAsked = true
            }
            backupNow()
            settings.dailyBackupTries = 0
            DailyResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (_: DriveNotConnectedException) {
            DailyResult.Done // Google wants the owner to confirm; Trips and Me › Backup & sync say so.
        } catch (_: NothingToBackUpException) {
            DailyResult.Done
        } catch (_: Exception) {
            val tries = settings.dailyBackupTries + 1
            settings.dailyBackupTries = if (tries >= MAX_RETRIES) 0 else tries
            if (tries >= MAX_RETRIES) DailyResult.Done else DailyResult.Retry
        }
    }

    private fun step(restoring: Boolean, label: String, done: Int = 0, total: Int = 0) {
        files.progress = com.justdataplease.boringtravel.backup.BackupProgress(restoring, label, done, total, file = false)
    }

    private suspend fun runBackup(): BackupReport {
        step(false, "Checking Google Drive")
        if (!drive.isConnected()) throw DriveNotConnectedException()
        // A phone with nothing of the traveller's own writes no backup: an empty one would be the newest a new phone is offered.
        if (!BackupSafety.worthBackingUp(readSnapshot())) throw NothingToBackUpException()
        var photos = uploadPhotos()

        step(false, "Packing trips and entries")
        var snapshot = BackupSafety.clean(readSnapshot())
        // A photo added while the uploads ran goes up too. After two more rounds the backup goes on with what it has: a
        // photo that can't be saved now never stops the rest (the Android owner: "under no circumstances should the
        // backup fail"); it is counted, and the next backup tries it again.
        var rounds = 0
        while (rounds++ < 2 && !photos.stopped && BackupSafety.photosLeft(snapshot.media, photos.verified, photos.waiting).any { it.id !in photos.failed }) {
            photos = uploadPhotos()
            snapshot = BackupSafety.clean(readSnapshot())
        }
        val photosLeft = BackupSafety.photosLeft(snapshot.media, photos.verified, photos.waiting).size
        val createdAt = now()
        val clips = savedClips(snapshot)
        val trips = snapshot.trips.filter { it.deletedAt == null }.map { it.id }
        val clipRows = clips.map(::clipRow)
        val data = snapshot.toData(settings.backupPreferences()).copy(
            // Shared trips' state travels with the backup, so a new phone keeps syncing them (round 26).
            sharedTrips = files.sharedTrips(),
            // The clips on this phone, and those a restore listed whose videos aren't here (kept for the other phone).
            savedClips = (clipRows + book.savedClips.orEmpty().filter { r -> r.tripId in trips && clipRows.none { it.id == r.id } }).takeIf { it.isNotEmpty() },
            clipEdits = (book.clipEdits.orEmpty().filterKeys { it in trips } + BackupExtras.clipEdits(media, trips)).takeIf { it.isNotEmpty() },
        )
        // The same rows and settings as the last backup, which Drive still has: no second copy of it.
        val sum = BackupCodec.sum(data)
        val last = lastBackup(sum)
        val entry = last ?: uploadRows(snapshot, data, sum, createdAt)
        if (last != null) settings.backupCheckedAt = createdAt
        // The big files last and never in the backup's way: what doesn't go up now goes with the next backup.
        val (videos, videosLeft) = uploadVideos(clips)
        uploadEditorFiles(trips)
        return BackupReport(entry, photosLeft, videos, videosLeft, unchanged = last != null)
    }

    /**
     * The last backup this phone wrote, when its rows and settings are exactly [sum]'s and Drive still has it whole;
     * null when anything changed, the backup was deleted from Drive, or Drive can't say now (then a new one is written).
     */
    private suspend fun lastBackup(sum: String): BackupEntry? {
        val stamp = BackupCodec.decodeStamp(settings.backupStamp)?.takeIf { it.sum == sum } ?: return null
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
        val check = { task.ensureActive() }
        val manifest = BackupCodec.manifest(snapshot, appVersion, createdAt)
        val name = BackupNames.fileName(createdAt, zone)
        val zip = "$WORK/backup-$createdAt.zip"
        media.delete(zip)
        try {
            // The zip as a Drive backup is (format 1: manifest.json and data.json), written into the app's files.
            BackupArchive.write(manifest, data, emptyList(), media, { b, o, n ->
                if (!media.append(zip, if (o == 0) b else b.copyOfRange(o, o + n), n)) throw IOException("Couldn't write the backup on this phone.")
            }, createdAt, check)
            val size = media.size(zip) ?: throw IOException("Couldn't write the backup on this phone.")
            // Read back as a restore would read it: a backup that can't be restored is never uploaded as one.
            val readBack = try {
                BackupArchive.read(BackupArchive.reader(size) { o, l -> media.readRange(zip, o, l) ?: ByteArray(0) }, check)
            } catch (e: BackupFormatException) {
                throw IOException("The backup didn't pass its own check. Try Back up now again.")
            }
            if (readBack.data.toSnapshot().tableCounts() != snapshot.tableCounts()) throw IOException("The backup didn't pass its own check. Try Back up now again.")
            step(false, "Uploading the backup")
            // A folder renamed or moved since (or, on a new phone, wherever the newest backup is) before a new one.
            BackupFolder.find(drive)
            val file = drive.intoFolder(DriveApi.BACKUPS) { folder ->
                drive.uploadFile(zip, name, BackupNames.MIME, listOf(folder), appProperties = BackupNames.properties(manifest))
            }
            if (file.size != null && file.size != size) {
                // Never counted as a backup, never a reason to delete an older one.
                try {
                    drive.delete(file.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // It stays; the next backup's rotation removes it.
                }
                throw IOException("The backup didn't reach Google Drive whole. Try Back up now again.")
            }
            val entry = BackupEntry(file.id, file.name, createdAt, manifest.items, file.size ?: size)
            val marker = BackupMarker(id = file.id, name = file.name, createdAt = createdAt, items = manifest.items, appVersion = manifest.appVersion)
            writeMarker(marker)
            prune(keepId = file.id)
            settings.lastBackupAt = createdAt
            settings.backupStamp = BackupCodec.encodeStamp(BackupStamp(sum, marker))
            // This phone backs up now: there is nothing left to ask about restoring.
            settings.restoreAsked = true
            return entry
        } finally {
            media.delete(zip)
        }
    }

    /** The saved videos of the traveller's own live trips on this phone (the examples' stay out, like their rows). */
    private fun savedClips(snapshot: Snapshot): List<SavedClip> {
        val trips = snapshot.trips.filter { it.deletedAt == null }.mapTo(HashSet()) { it.id }
        return runCatching { SavedClipIndex.list(media) }.getOrDefault(emptyList()).filter { it.tripId in trips && BackupVideos.safeId(it.id) }
    }

    private fun clipRow(c: SavedClip) = ClipRow(c.id, c.tripId, c.title, c.createdAt, c.durationMs, c.width, c.height)

    private fun clipOf(r: ClipRow) = SavedClip(r.id, r.tripId, r.title, r.createdAt, r.durationMs.coerceAtLeast(0), r.width.coerceAtLeast(0), r.height.coerceAtLeast(0))

    /** Only on Wi‑Fi is on and this network is a costly one: the videos wait (a daily backup already waited for Wi‑Fi). */
    private fun videosWait(): Boolean = settings.wifiOnly && net.metered()

    /**
     * Saved videos into BoringTravel › Videos, each once ([BackupVideos]). Returns (saved now, still to go). Never fails
     * the backup: its rows and photos are in Drive already; a video that doesn't go up now goes with the next backup.
     */
    private suspend fun uploadVideos(clips: List<SavedClip>): Pair<Int, Int> {
        if (clips.isEmpty()) return 0 to 0
        var done = 0
        var todo = clips.size
        try {
            val bytes = clips.associate { it.id to (media.size(SavedClipIndex.video(it.id)) ?: 0L) }
            val remote = drive.intoFolder(DriveApi.VIDEOS) { folder -> drive.list(DriveJson.filesIn(folder), limit = MAX_LISTED) }
            val wanted = BackupVideos.toUpload(clips, bytes, remote)
            todo = wanted.size
            if (wanted.isEmpty() || videosWait()) return 0 to todo
            var failed = 0
            for ((i, c) in wanted.withIndex()) {
                step(false, "Saving videos to Drive", i, wanted.size)
                try {
                    drive.intoFolder(DriveApi.VIDEOS) { folder ->
                        drive.uploadFile(SavedClipIndex.video(c.id), BackupVideos.fileName(c), VIDEO_MIME, listOf(folder), appProperties = BackupVideos.properties(c))
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

    /** The voice lines and phone videos trip [tripId]'s edits on this phone use: their path under the app's files. */
    private fun clipMedia(tripId: String): List<String> {
        val edits = runCatching { ClipEditStore.load(media, tripId).values }.getOrDefault(emptyList())
        return (edits.flatMap { e -> e.videos.map { ClipMedia.videoDir(tripId) + "/" + it.file } } +
            edits.flatMap { e -> e.voices.map { ClipMedia.voiceDir(tripId) + "/" + it.file } }).distinct().filter { BackupExtras.entry(it) != null }
    }

    /**
     * The voice lines and phone videos the trips' video edits use, each once in BoringTravel › Videos › Editor files,
     * kept by their path on the phone. Never fails the backup.
     */
    private suspend fun uploadEditorFiles(trips: List<String>) {
        try {
            val local = LinkedHashMap<String, Long>()
            for (trip in trips) for (path in clipMedia(trip)) media.size(path)?.takeIf { it > 0 }?.let { local[path] = it }
            if (local.isEmpty() || videosWait()) return
            val remote = drive.intoFolder(DriveApi.EDITOR_FILES) { folder -> drive.list(DriveJson.filesIn(folder), limit = MAX_LISTED) }
            val wanted = BackupVideos.filesToUpload(local, remote)
            var failed = 0
            for ((i, path) in wanted.withIndex()) {
                step(false, "Saving the video editor's files", i, wanted.size)
                try {
                    val name = path.substringAfterLast('/')
                    drive.intoFolder(DriveApi.EDITOR_FILES) { folder ->
                        drive.uploadFile(path, name, if (name.endsWith(".mp4")) VIDEO_MIME else "application/octet-stream", listOf(folder), appProperties = mapOf(BackupVideos.PATH to path))
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
     * What [uploadPhotos] did: the Drive copies it found or made ([verified]), the photos left for their shared trip to
     * bring back ([waiting]), the photos it couldn't save this time ([failed]), and whether it gave up on the uploads
     * because Drive stopped answering ([stopped]).
     */
    private class PhotoRun(val verified: Set<String>, val waiting: Set<String>, val failed: Set<String>, val stopped: Boolean)

    /**
     * Uploads each photo once; a photo already in Drive (same content) is linked, not uploaded again. The example trips'
     * pictures stay on the phone, like the rest of the demo. One photo never stops the backup: a photo whose file is
     * missing or damaged, or that Drive doesn't take, is left for the next backup ([PhotoRun.failed]).
     */
    private suspend fun uploadPhotos(): PhotoRun {
        val rows = book.snapshot
        val all = BackupSafety.ownPhotos(DemoTrips.withoutDemoMedia(rows.entries, rows.media))
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
        // Linked rows go into the trip store in batches (the store is one text: one write per photo would be slow).
        val linked = ArrayList<Pair<Media, Media>>()
        fun flush() {
            if (linked.isEmpty()) return
            val current = book.snapshot.media.associateBy { it.id }
            val rowsNow = linked.mapNotNull { (was, next) ->
                current[was.id]?.takeIf { it.localPath == was.localPath && it.sha256 == was.sha256 && it.driveFileId == was.driveFileId }
                    ?.copy(driveFileId = next.driveFileId, sha256 = next.sha256)
            }
            if (rowsNow.isNotEmpty()) book.putRows(media = rowsNow)
            linked.clear()
        }
        try {
            for ((i, m) in all.withIndex()) {
                task.ensureActive()
                step(false, "Saving photos to Drive", i, all.size)
                if (m.driveFileId?.let { it in verified } == true) continue
                var checksum = m.sha256
                var driveId = checksum?.let(known::get)
                if (driveId == null) {
                    val path = m.localPath
                    val readable = BackupMerge.safePath(m).localPath == path && try {
                        files.usable(path, checksum)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                    if (!readable) {
                        // Its copy from the shared trip is on the way; otherwise its file is missing or damaged here and
                        // Drive has no copy: the rest of the backup goes on without it.
                        if (comesBackShared(m)) waiting += m.id else failed += m.id
                        continue
                    }
                    if (stopped) {
                        failed += m.id
                        continue
                    }
                    try {
                        if (checksum == null) checksum = sha256Of(path)
                        val sum: String = checksum
                        driveId = drive.intoFolder(DriveApi.PHOTOS) { folder ->
                            drive.uploadFile(path, path.substringAfterLast('/'), m.mime, listOf(folder), appProperties = mapOf("mediaId" to m.id, "sha256" to sum))
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
                val link: String = driveId
                verified += link
                checksum?.let { known[it] = link }
                // Link only the same original; keep concurrent caption and association edits and their time.
                linked += m to m.copy(driveFileId = link, sha256 = checksum)
                if (linked.size >= LINK_BATCH) flush()
            }
        } finally {
            flush()
        }
        return PhotoRun(verified, waiting, failed, stopped)
    }

    /**
     * One of the owner's photos that this phone only knows from a shared trip (its small copy there, no original and no
     * backup copy): the shared trip brings it back.
     */
    private fun comesBackShared(m: Media) = m.authorId == null && m.sharedFileId != null && m.sha256 == null && m.driveFileId == null

    private suspend fun writeMarker(marker: BackupMarker) {
        try {
            drive.writeAppData(BackupNames.MARKER, BackupCodec.encodeMarker(marker).encodeToByteArray())
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
            try {
                drive.delete(it.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Tried again next time.
            }
        }
    }

    private suspend fun runRestore(entry: BackupEntry): RestoreReport {
        step(true, "Downloading the backup")
        val zip = "$WORK/restore-${entry.id.filter { it.isLetterOrDigit() || it in "-_" }}.zip"
        try {
            val task = currentCoroutineContext()
            val check = { task.ensureActive() }
            drive.download(entry.id, zip) { bytes ->
                if (bytes > BackupCodec.MAX_DATA_BYTES + 2 * 1024 * 1024) throw BackupFormatException("This backup is too big to open on this phone.")
            }
            val size = media.size(zip) ?: throw BackupFormatException("This backup is incomplete and can't be opened.")
            val contents = BackupArchive.read(BackupArchive.reader(size) { o, l -> media.readRange(zip, o, l) ?: ByteArray(0) }, check)
            val data = contents.data
            // Never the examples (older backups carried them): the demo on this phone stays its own. A row with an
            // impossible place is mended, not a reason to refuse the rest ([BackupSafety.clean]).
            val incoming = BackupSafety.clean(DemoTrips.withoutDemo(data.toSnapshot()))
            val backupAt = contents.manifest.createdAt.takeIf { it > 0 } ?: entry.createdAt

            step(true, "Restoring trips and entries")
            val plan = files.mergeIn(data, incoming)
            val (downloaded, missing) = downloadMissingPhotos(plan.media.mapTo(HashSet()) { it.id })
            // The video editor's work, then the saved videos and the files the edits use: none of them stops a restore.
            val tripIds = incoming.trips.map { it.id }
            restoreClipEdits(data.clipEdits, tripIds)
            val videos = downloadVideos(data.savedClips, backupAt)
            downloadEditorFiles(tripIds)
            return RestoreReport(
                backupAt,
                restored = BackupCounts(incoming.trips.count { it.deletedAt == null }, incoming.entries.count { it.deletedAt == null }, incoming.media.count { it.deletedAt == null }),
                photosDownloaded = downloaded, photosMissing = missing, fromFile = false,
                planItems = incoming.planItems.count { it.deletedAt == null }, videos = videos, changed = !plan.isEmpty,
            )
        } finally {
            media.delete(zip)
            media.delete("$zip.part")
        }
    }

    /** Scene edits from the backup for trips with none on this phone; edits made on this phone always stay. */
    private fun restoreClipEdits(edits: Map<String, String>?, tripIds: List<String>) {
        edits ?: return
        for ((trip, text) in edits) {
            if (trip !in tripIds || text.isBlank()) continue
            val path = ClipEditStore.path(trip)
            if (BackupExtras.entry(path) != null && media.size(path) == null) media.write(path, text.encodeToByteArray())
        }
    }

    /**
     * Whether a restore should bring photo [m]'s file in: it is missing or empty, or the restore just changed the row
     * ([merged]) and the file no longer matches its checksum. Photos the restore didn't touch aren't hashed.
     */
    private fun needsPhoto(m: Media, target: String, merged: Set<String>): Boolean {
        val size = media.size(target) ?: return true
        if (size == 0L) return true
        return m.id in merged && !files.usable(target, m.sha256)
    }

    /**
     * Downloads every photo the journal uses that isn't on the phone but is in Drive, each checked against its checksum
     * and placed only while its row is still that photo. Returns (downloaded, missing).
     */
    private suspend fun downloadMissingPhotos(merged: Set<String>): Pair<Int, Int> {
        // Other members' photos come back through the shared trip itself, not from this Drive.
        val own = book.snapshot.media.filter { it.deletedAt == null && it.authorId == null && !comesBackShared(it) }
        step(true, "Checking photos", 0, own.size)
        val wanted = own.filterIndexed { i, m ->
            if (i % 50 == 0) step(true, "Checking photos", i, own.size)
            needsPhoto(m, BackupMerge.safePath(m).localPath, merged)
        }
        var downloaded = 0
        var missing = 0
        wanted.forEachIndexed { i, m ->
            step(true, "Downloading photos", i, wanted.size)
            val id = m.driveFileId
            val target = BackupMerge.safePath(m).localPath
            if (id == null) {
                missing++
                return@forEachIndexed
            }
            val staged = "$target.restore"
            try {
                drive.download(id, staged)
                if (!files.usable(staged, m.sha256)) throw IOException("The downloaded photo does not match its backup checksum.")
                // A photo may be changed or deleted during the download: never placed against a stale row.
                val current = book.media(m.id)
                if (current != null && current.localPath == m.localPath && current.sha256 == m.sha256 && current.driveFileId == m.driveFileId && media.replace(staged, target)) downloaded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveNotConnectedException) {
                throw e
            } catch (_: Exception) {
                missing++
            } finally {
                media.delete(staged)
                media.delete("$staged.part")
            }
        }
        return downloaded to missing
    }

    /**
     * Brings back the saved videos of the backup being restored ([inBackup]; null for a backup older than the Videos
     * folder: then every video made up to [backupAt]) that this phone doesn't have, from BoringTravel › Videos, each with
     * a picture from 2/5 in. Returns how many came back. Never fails the restore.
     */
    private suspend fun downloadVideos(inBackup: List<ClipRow>?, backupAt: Long): Int {
        var done = 0
        try {
            val folder = drive.findFolder(DriveApi.VIDEOS, create = false) ?: return 0
            val have = runCatching { SavedClipIndex.list(media) }.getOrDefault(emptyList()).mapTo(HashSet()) { it.id }
            val liveTrips = book.snapshot.trips.filter { it.deletedAt == null && !it.isDemo }.mapTo(HashSet()) { it.id }
            val rows = inBackup?.filter { BackupVideos.safeId(it.id) }?.associateBy { it.id }
            val wanted = BackupVideos.toFetch(drive.list(DriveJson.filesIn(folder), limit = MAX_LISTED), have, liveTrips, rows?.keys, backupAt)
            var failed = 0
            for ((i, pair) in wanted.withIndex()) {
                val (fromDrive, file) = pair
                step(true, "Downloading videos", i, wanted.size)
                // The backup's own record of the video (its whole title); Drive's properties when the backup has none.
                val clip = rows?.get(fromDrive.id)?.let(::clipOf) ?: fromDrive
                val target = SavedClipIndex.video(clip.id)
                val staged = "$target.restore"
                try {
                    drive.download(file.id, staged)
                    if (file.size != null && media.size(staged) != file.size) throw IOException("The video didn't download whole.")
                    if (media.replace(staged, target)) {
                        SavedClipIndex.restoreThumb(media, frames, clip)
                        // The listing last: a clip half copied never shows.
                        media.write(SavedClipIndex.meta(clip.id), SavedClipIndex.encode(clip).encodeToByteArray())
                        done++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (++failed >= 2) break
                } finally {
                    media.delete(staged)
                    media.delete("$staged.part")
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
            val wanted = LinkedHashSet<String>()
            tripIds.forEach { id -> clipMedia(id).forEach { path -> if (media.size(path) == null) wanted += path } }
            if (wanted.isEmpty()) return
            val folder = drive.findFolder(DriveApi.EDITOR_FILES, create = false) ?: return
            val found = BackupVideos.filesToFetch(wanted, drive.list(DriveJson.filesIn(folder), limit = MAX_LISTED))
            var i = 0
            for ((path, remote) in found) {
                step(true, "Downloading the video editor's files", i++, found.size)
                if (media.size(path) != null || BackupExtras.entry(path) == null) continue
                try {
                    drive.download(remote.id, path)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    media.delete("$path.part")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Restore again later to fetch them.
        }
    }

    /** Every row of the traveller's own: the example trips and their rows never go into a backup. */
    private fun readSnapshot(): Snapshot = DemoTrips.withoutDemo(book.snapshot)

    private fun sha256Of(path: String): String {
        val hash = Sha256()
        if (!media.readChunks(path) { b, n -> hash.update(b, 0, n) }) throw IOException("The photo isn't on this phone any more.")
        return hash.hex()
    }

    companion object {
        /** More files than any of the app's Drive folders holds: one listing reads the whole folder. */
        const val MAX_LISTED = 100_000
        const val VIDEO_MIME = "video/mp4"

        /** The daily backup's tries in a day (Android's Worker: three, then the next day). */
        const val MAX_RETRIES = 3

        /** The names iOS keeps with the background time of Back up now and a restore. */
        const val BACKUP_WORK_NAME = "Backing up to Google Drive"
        const val RESTORE_WORK_NAME = "Restoring from Google Drive"

        /** Photos linked to their Drive copy, written into the trip store together. */
        private const val LINK_BATCH = 25

        /** Where a backup's zip is made and a restored one downloaded, under the app's files; emptied after each. */
        const val WORK = "backup-work"
    }
}

/** The app's Drive backups for every screen; null where Google isn't set up or a screen is drawn on its own. */
val LocalDriveBackups = staticCompositionLocalOf<DriveBackups?> { null }
