package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.backup.ArchiveContents
import com.justdataplease.boringtravel.backup.ArchiveFile
import com.justdataplease.boringtravel.backup.BackupArchive
import com.justdataplease.boringtravel.backup.BackupCodec
import com.justdataplease.boringtravel.backup.BackupCounts
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupFormatException
import com.justdataplease.boringtravel.backup.BackupManifest
import com.justdataplease.boringtravel.backup.BackupMerge
import com.justdataplease.boringtravel.backup.BackupPreferences
import com.justdataplease.boringtravel.backup.BackupProgress
import com.justdataplease.boringtravel.backup.BackupSafety
import com.justdataplease.boringtravel.backup.FileBackupReport
import com.justdataplease.boringtravel.backup.MergePlan
import com.justdataplease.boringtravel.backup.RestoreReport
import com.justdataplease.boringtravel.backup.Snapshot
import com.justdataplease.boringtravel.backup.ZipEntry
import com.justdataplease.boringtravel.backup.ZipReader
import com.justdataplease.boringtravel.backup.toData
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.io.IOException
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Travel together's part of a backup (round 26): the engine's exportState, importState and the trips shared now. */
interface SharedTripsState {
    suspend fun export(): String?
    suspend fun import(json: String)
    fun sharedNow(): Set<String>
}

/**
 * Backup files on this phone, as the Android app's BackupManager makes and restores them (its runSaveFile,
 * runRestoreFile, mergeIn, keepForUndo, undoRestore): Me › Backup & sync › Save a file and Restore a file, without
 * Google. A file is the engine's backup format exactly ([BackupArchive]: manifest.json, data.json, each photo and video
 * under media/), so one made on Android restores here and the other way round.
 *
 * - **Save a file** packs the traveller's own rows (never the examples), this phone's settings and the photos and videos
 *   into exports/ under the app's files; the phone's own exporter (the Files app's save sheet on iPhone) copies it where
 *   the traveller picks, and the copy here is removed. A stopped save leaves no half file.
 * - **Restore a file** reads it whole first (every entry checked, nothing on the phone changes before that), then puts
 *   this phone back as the file was ([BackupMerge]: like a database), keeping the phone as it was for [undoRestore], and
 *   copies in the photos it lacks, each checked against its checksum. The settings come back too, and demo mode ends.
 * - **Undo restore** is itself a restore, of the copy kept: it can be undone the same way. None is kept when the phone
 *   held nothing of its own.
 *
 * One save or restore at a time; [progress] shows it to every screen.
 */
class BackupFiles(
    private val book: TripBook,
    private val settings: AppSettings,
    private val files: MediaFiles,
    private val appVersion: String,
    private val now: () -> Long = ::nowMs,
    private val background: CoroutineContext = Dispatchers.Default,
    /** A restored trip video's picture for the Media tab (AVAssetImageGenerator on iPhone; none on the desktop). */
    private val frames: VideoFrames = VideoFrames { _, _ -> null },
) {
    /** One backup or restore at a time, a file's or Drive's ([DriveBackups] takes the same lock). */
    internal val lock = Mutex()
    private var job: Job? = null

    /** What a running save or restore is doing, a file's or Drive's; null when none runs. */
    var progress by mutableStateOf<BackupProgress?>(null)
        internal set

    /** Runs after a restore put the phone back (the daily backup is planned again: Android's reschedule). */
    var onRestored: suspend () -> Unit = {}

    /**
     * Travel together's state (round 26; Android's AppGraph hands its BackupManager the same three): what a backup
     * carries of the shared trips, what a restore brings back (memberships, so a new phone keeps syncing them), and the
     * trips shared now, which a restore never rolls back. Null: the last restore's text goes on as it was.
     */
    var sharing: SharedTripsState? = null

    /** The shared trips' state as a backup's `sharedTrips` (null when none is or was shared here). */
    internal suspend fun sharedTrips(): String? = sharing?.let { it.export() } ?: book.sharedTrips

    /** The phone as it was before the last restore (when, and what it held); null when there is nothing to undo. */
    var undo by mutableStateOf<BackupManifest?>(null)
        private set

    /** Reads [undo] from the phone (a screen asks once; every restore keeps it current). */
    fun loadUndo() {
        undo = runCatching { if (files.size(UNDO) == null) null else BackupArchive.peek(reader(UNDO)) }.getOrNull()
    }

    /**
     * Writes a backup file: the rows, the settings and the photos in one zip under exports/. Returns its path under the
     * app's files and what it holds; the caller hands it to the phone's exporter, then [forget]s it.
     */
    suspend fun saveFile(): Pair<String, FileBackupReport> = fileRun { runSaveFile() }

    /** The manifest of the backup file at [path], to confirm before [restoreFile]; throws when it can't be restored. */
    suspend fun peekFile(path: String): BackupManifest = withContext(background) { BackupArchive.peek(reader(path)) }

    /** Puts this phone back as the backup file at [path] was, and copies in the photos it needs. */
    suspend fun restoreFile(path: String): RestoreReport = fileRun { runRestoreFile(path) }

    /** Puts the phone back as it was before the last restore. Itself a restore: it can be undone the same way. */
    suspend fun undoRestore(): RestoreReport = fileRun {
        // From a copy: the restore keeps the phone as it is now in the undo file's place.
        val copy = RESTORE + "undo.zip"
        try {
            files.delete(copy)
            if (!copyFile(UNDO, copy)) throw IOException("Nothing to undo.")
            runRestoreFile(copy)
        } finally {
            files.delete(copy)
        }
    }

    /** Stops a backup file being saved or restored. */
    fun cancelFile() {
        job?.cancel()
    }

    /** Removes a file the phone handed on (a saved backup file, a picked one). */
    fun forget(path: String) = files.delete(path)

    private suspend fun <T> fileRun(block: suspend () -> T): T = lock.withLock {
        try {
            withContext(background) {
                job = currentCoroutineContext()[Job]
                block()
            }
        } finally {
            job = null
            progress = null
        }
    }

    private fun step(restoring: Boolean, label: String, done: Int = 0, total: Int = 0) {
        progress = BackupProgress(restoring, label, done, total, file = true)
    }

    private suspend fun runSaveFile(): Pair<String, FileBackupReport> {
        val task = currentCoroutineContext()
        val checkActive = { task.ensureActive() }
        step(false, "Packing trips and entries")
        val at = now()
        val snapshot = BackupSafety.clean(DemoTrips.withoutDemo(book.snapshot))
        // The trip videos' files and the saved clips go along (extras/, since round 20), as Android's backup file has them.
        val trips = snapshot.trips.filter { it.deletedAt == null }.map { it.id }
        val clips = com.justdataplease.boringtravel.data.SavedClipIndex.list(files).filter { it.tripId in trips }
        val clipRows = clips.map { com.justdataplease.boringtravel.backup.ClipRow(it.id, it.tripId, it.title, it.createdAt, it.durationMs, it.width, it.height) }
        val data = snapshot.toData(settings.backupPreferences()).copy(
            sharedTrips = sharedTrips(),
            // The clips on this phone, and those a restore listed whose videos aren't here (kept for the other phone).
            savedClips = (clipRows + book.savedClips.orEmpty().filter { r -> clipRows.none { it.id == r.id } }).takeIf { it.isNotEmpty() } ?: book.savedClips,
            clipEdits = (book.clipEdits.orEmpty() + com.justdataplease.boringtravel.backup.BackupExtras.clipEdits(files, trips)).takeIf { it.isNotEmpty() },
        )
        val manifest = BackupCodec.manifest(snapshot, appVersion, at, BackupCodec.FILE_FORMAT)
        val (pictures, notOnPhone) = photoFiles(snapshot)
        val photos = pictures + com.justdataplease.boringtravel.backup.BackupExtras.files(files, trips, clips)
        val name = EXPORTS + BackupArchive.fileName(at)
        val part = "$name.part"
        files.delete(part)
        var saved = false
        try {
            val out = Appender(files, part)
            val leftOut = BackupArchive.write(manifest, data, photos, files, out::write, at, checkActive) { done, total ->
                step(false, "Saving photos and files", done, total)
            }
            out.flush()
            files.delete(name)
            if (!files.move(part, name)) throw IOException("Couldn't save the backup file.")
            saved = true
            val gone = leftOut.count { it.startsWith(BackupArchive.MEDIA) }
            return name to FileBackupReport(manifest.items, pictures.size - gone, notOnPhone + gone)
        } finally {
            // A half-written backup file must not look like a good one.
            if (!saved) files.delete(part)
        }
    }

    /** The traveller's own photos for media/, and how many aren't on the phone (other members' stay out). */
    private fun photoFiles(snapshot: Snapshot): Pair<List<ArchiveFile>, Int> {
        val out = LinkedHashMap<String, ArchiveFile>()
        var missing = 0
        for (m in snapshot.media) {
            if (m.deletedAt != null || m.authorId != null) continue
            val name = BackupArchive.mediaEntry(m.localPath)
            if (name == null || (files.size(m.localPath) ?: 0L) == 0L) {
                missing++
                continue
            }
            out.getOrPut(name) { ArchiveFile(name, m.localPath) }
        }
        return out.values.toList() to missing
    }

    private suspend fun runRestoreFile(path: String): RestoreReport {
        val task = currentCoroutineContext()
        val checkActive = { task.ensureActive() }
        step(true, "Reading the backup file")
        // First pass: every entry checked, the rows read. Nothing on the phone changes before it passes.
        val zip = reader(path)
        val contents = BackupArchive.read(zip, checkActive)
        val incoming = BackupSafety.clean(DemoTrips.withoutDemo(contents.data.toSnapshot()))
        step(true, "Restoring trips and entries")
        val plan = mergeIn(contents.data, incoming)
        // Second pass: the photos this phone needs.
        val (copied, missing) = copyFromFile(zip, contents, plan.media.mapTo(HashSet()) { it.id }, checkActive)
        // The trip videos' files and saved clips (extras/), where this phone has none.
        placeExtras(zip, contents, checkActive)
        return RestoreReport(
            contents.manifest.createdAt,
            restored = BackupCounts(incoming.trips.count { it.deletedAt == null }, incoming.entries.count { it.deletedAt == null }, incoming.media.count { it.deletedAt == null }),
            photosDownloaded = copied, photosMissing = missing, fromFile = true,
            planItems = incoming.planItems.count { it.deletedAt == null }, videos = 0, changed = !plan.isEmpty,
        )
    }

    /**
     * Puts this phone back as the backup was ([BackupMerge]), keeping the phone as it is for [undoRestore] first; then
     * the backup's settings. [incoming] is [data]'s rows without the examples.
     */
    internal suspend fun mergeIn(data: BackupData, incoming: Snapshot): MergePlan {
        val prefs = settings.backupPreferences()
        val sharing = sharedTrips()
        val local = book.snapshot
        // The trips shared now only gain (they belong to everyone in them: Android's sharedNow).
        val plan = BackupMerge.replace(local, incoming, this.sharing?.sharedNow().orEmpty())
        val staged = "$UNDO.part"
        files.delete(staged)
        try {
            if (!plan.isEmpty) {
                // The copy kept for Undo is made before anything changes; a phone it can't keep a copy of isn't changed.
                keepForUndo(local, prefs, sharing, staged)
                book.restoreRows(
                    trips = plan.trips, planItems = plan.planItems, entries = plan.entries, media = plan.media, stops = plan.stops,
                    countryMarks = plan.countryMarks, books = plan.books, trackPoints = plan.trackPoints, clearRoutes = plan.clearRoutes,
                    sharedTrips = data.sharedTrips ?: book.sharedTrips,
                    savedClips = data.savedClips ?: book.savedClips,
                    // The video edits on this phone always stay; the backup's fill in the trips that have none.
                    clipEdits = (data.clipEdits.orEmpty() + book.clipEdits.orEmpty()).takeIf { it.isNotEmpty() },
                )
                files.delete(UNDO)
                if (files.size(staged) != null) files.move(staged, UNDO)
                loadUndo()
            }
            // The settings come back with the rows (owner, 2026-11-14). Undo restore brings this phone's back the same way.
            settings.restorePreferences(data.preferences, replace = true)
            // A backup was restored here: the question about restoring is answered.
            settings.restoreAsked = true
            // Its shared trips come back (round 26): this phone carries on as the member it was in each.
            data.sharedTrips?.let { json -> runCatching { this.sharing?.import(json) } }
            // The restored trips are the traveller's own: show them, not the demo.
            settings.demoMode = false
            onRestored()
            return plan
        } finally {
            files.delete(staged)
        }
    }

    /**
     * The phone as it is ([local]) as a backup file without photos, for [undoRestore]: a restore never deletes a photo
     * or video file, so the rows are all it takes to go back. Nothing is kept for a phone with nothing of its own.
     */
    internal fun keepForUndo(local: Snapshot, prefs: BackupPreferences, sharing: String?, to: String) {
        val own = BackupSafety.clean(DemoTrips.withoutDemo(local))
        if (!BackupSafety.worthBackingUp(own)) return
        val at = now()
        val manifest = BackupCodec.manifest(own, appVersion, at, BackupCodec.FILE_FORMAT)
        val out = Appender(files, to)
        BackupArchive.write(manifest, own.toData(prefs).copy(sharedTrips = sharing, savedClips = book.savedClips, clipEdits = book.clipEdits), emptyList(), files, out::write, at)
        out.flush()
    }

    /**
     * Copies in the photos of this backup the phone lacks (or holds damaged), each checked against its checksum.
     * [merged]: the photos the restore just changed. Returns (copied, missing).
     */
    private fun copyFromFile(zip: ZipReader, contents: ArchiveContents, merged: Set<String>, checkActive: () -> Unit): Pair<Int, Int> {
        val inFile = contents.data.media.associate { it.id to it.localPath }
        val photos = LinkedHashMap<String, MutableList<Media>>()
        var wanted = 0
        // Other members' photos come back through the shared trip itself.
        val own = book.snapshot.media.filter { it.deletedAt == null && it.authorId == null }
        step(true, "Checking photos", 0, own.size)
        for ((i, m) in own.withIndex()) {
            checkActive()
            if (i % 50 == 0) step(true, "Checking photos", i, own.size)
            val path = inFile[m.id] ?: continue
            val entry = BackupArchive.mediaEntry(path)
            val target = BackupMerge.safePath(m).localPath
            if (!needsPhoto(m, target, merged, entry?.let(contents.mediaSizes::get))) continue
            wanted++
            entry?.let { photos.getOrPut(it) { ArrayList() } += m }
        }
        if (photos.isEmpty()) return 0 to wanted
        var copied = 0
        var done = 0
        step(true, "Copying photos", 0, wanted)
        for (entry in zip.entries) {
            val rows = photos.remove(entry.name) ?: continue
            copied += placePhoto(zip, entry, rows, checkActive)
            done += rows.size
            step(true, "Copying photos", done, wanted)
        }
        return copied to (wanted - copied)
    }

    /**
     * Whether photo [m]'s file should come in: it is missing or empty, its size differs from the file's copy
     * ([archived]), or the restore just changed the row ([merged]) and the file no longer matches its checksum.
     */
    private fun needsPhoto(m: Media, target: String, merged: Set<String>, archived: Long?): Boolean {
        val size = files.size(target) ?: return true
        if (size == 0L) return true
        if (archived != null && archived != size) return true
        return m.id in merged && !usable(target, m.sha256)
    }

    internal fun usable(path: String, sha256: String?): Boolean {
        if ((files.size(path) ?: 0L) == 0L) return false
        if (sha256 == null) return true
        val hash = Sha256()
        files.readChunks(path) { b, n -> hash.update(b, 0, n) }
        return hash.hex().equals(sha256, ignoreCase = true)
    }

    /** One photo from the file for [rows] (almost always one row); returns how many rows got it. */
    private fun placePhoto(zip: ZipReader, entry: ZipEntry, rows: List<Media>, checkActive: () -> Unit): Int {
        val staged = RESTORE + "photo.restore"
        files.delete(staged)
        try {
            val out = Appender(files, staged)
            zip.content(entry, BackupArchive.limit(entry.name), checkActive) { b, n -> out.write(b, 0, n) }
            out.flush()
            var placed = 0
            for (m in rows) {
                checkActive()
                val target = BackupMerge.safePath(m).localPath
                // Only a photo that is still that photo (not deleted, same file and checksum) takes it.
                val current = book.media(m.id) ?: continue
                if (current.localPath != m.localPath || current.sha256 != m.sha256) continue
                if (!usable(staged, m.sha256)) continue
                val own = "$target.restore"
                files.delete(own)
                if (!copyFile(staged, own)) continue
                files.delete(target)
                if (files.move(own, target)) placed++ else files.delete(own)
            }
            return placed
        } finally {
            files.delete(staged)
        }
    }

    /**
     * The backup's extras/ (round 20; the Android app's BackupExtras): the trip videos' scene edits, voice lines and phone
     * videos, and the saved clips with their listing (data.json's savedClips), each written only where the phone has
     * none; names this app doesn't know are skipped. A saved clip gets a new picture from 2/5 of the way in, as Android's
     * restore makes one ([SavedClipIndex.restoreThumb]); its listing is written last, so a clip half copied never shows.
     */
    private fun placeExtras(zip: ZipReader, contents: ArchiveContents, checkActive: () -> Unit) {
        val extras = com.justdataplease.boringtravel.backup.BackupExtras
        for ((name, bytes) in contents.texts) {
            val target = extras.target(name) ?: continue
            if (files.size(target) == null) files.write(target, bytes)
        }
        // A Drive backup's scene edits come in data.json.
        contents.data.clipEdits?.forEach { (trip, text) ->
            val path = ClipEditStore.path(trip)
            if (extras.entry(path) != null && files.size(path) == null) files.write(path, text.encodeToByteArray())
        }
        val rows = contents.data.savedClips.orEmpty().associateBy { it.id }
        val wanted = zip.entries.filter { e ->
            !e.isDirectory && e.name !in contents.texts && extras.target(e.name)?.let { (files.size(it) ?: 0L) == 0L } == true
        }
        wanted.forEachIndexed { i, entry ->
            checkActive()
            step(true, "Copying photos", i, wanted.size)
            val target = extras.target(entry.name) ?: return@forEachIndexed
            val clipId = target.takeIf { it.startsWith(extras.CLIPS + "/") }?.removePrefix(extras.CLIPS + "/")?.removeSuffix(".mp4")
            val row = clipId?.let(rows::get)
            // A video without its listing would never show: left out.
            if (clipId != null && row == null) return@forEachIndexed
            val staged = "$target.restore"
            files.delete(staged)
            try {
                val out = Appender(files, staged)
                zip.content(entry, BackupArchive.limit(entry.name), checkActive) { b, n -> out.write(b, 0, n) }
                out.flush()
                if (!files.replace(staged, target)) return@forEachIndexed
                if (row != null) {
                    val clip = com.justdataplease.boringtravel.data.SavedClip(row.id, row.tripId, row.title, row.createdAt, row.durationMs, row.width, row.height)
                    com.justdataplease.boringtravel.data.SavedClipIndex.restoreThumb(files, frames, clip)
                    files.write(com.justdataplease.boringtravel.data.SavedClipIndex.meta(row.id), com.justdataplease.boringtravel.data.SavedClipIndex.encode(clip).encodeToByteArray())
                }
            } finally {
                files.delete(staged)
            }
        }
    }

    private fun copyFile(from: String, to: String): Boolean {
        val out = Appender(files, to)
        if (!files.readChunks(from) { b, n -> out.write(b, 0, n) }) return false
        out.flush()
        return true
    }

    /** A zip under the app's files, read a part at a time. */
    private fun reader(path: String): ZipReader {
        val size = files.size(path) ?: throw BackupFormatException("Couldn't read that file. Pick a BoringTravel backup file.")
        return BackupArchive.reader(size) { offset, length -> files.readRange(path, offset, length) ?: ByteArray(0) }
    }

    /** Writes into a file a megabyte at a time (a backup file is written in many small pieces). */
    private class Appender(private val files: MediaFiles, private val path: String) {
        private val buffer = ByteArray(1024 * 1024)
        private var used = 0

        fun write(bytes: ByteArray, offset: Int, length: Int) {
            var at = offset
            var left = length
            while (left > 0) {
                if (used == buffer.size) flush()
                val n = minOf(left, buffer.size - used)
                bytes.copyInto(buffer, used, at, at + n)
                used += n
                at += n
                left -= n
            }
        }

        fun flush() {
            if (used > 0 && !files.append(path, buffer, used)) throw IOException("Couldn't write the backup file.")
            used = 0
        }
    }

    companion object {
        /** The phone as it was before the last restore, a backup file without photos. */
        const val UNDO = "restore-undo/before-restore.zip"

        /** Backup files made here, until the phone's exporter has copied them on. */
        const val EXPORTS = "exports/"

        /** A file picked to restore, copied in by the phone; and what a restore stages. */
        const val RESTORE = "restore/"

        /** Where the phone puts a backup file the traveller picks. */
        const val PICKED = RESTORE + "picked.zip"
    }
}

/** The app's one [BackupFiles]; null where a screen is drawn on its own. */
val LocalBackupFiles = staticCompositionLocalOf<BackupFiles?> { null }

/** Never thrown: keeps the import list of the cancellation the runner relies on. */
@Suppress("unused")
private typealias Cancelled = CancellationException
