package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.ios.MediaFiles
import com.justdataplease.boringtravel.ios.Sha256
import com.justdataplease.boringtravel.time.civil
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.time.startOfDayMs
import kotlinx.serialization.SerializationException

/*
 * Backup files as the Android app writes and reads them (backup/BackupCodec.kt, BackupArchive.kt, BackupNames.kt's
 * texts), written again here on this app's zip ([ZipWriter], [ZipReader]) and files ([MediaFiles]) in place of Java's
 * streams and java.util.zip. The same layout (manifest.json, data.json, media/<file>, extras/…), the same checks in the
 * same order and the same messages, so a file made on either phone restores on the other.
 */

/** The manifest and the rows, as every backup zip starts (the Android app's BackupCodec). */
object BackupCodec {
    /** Drive backups and the marker. */
    const val FORMAT = BACKUP_FORMAT

    /** Backup files (Save a file): format 1 plus the photos in media/ and other files in extras/. */
    const val FILE_FORMAT = 2
    const val MANIFEST = "manifest.json"
    const val DATA = "data.json"

    /** Bound decompression and the JSON copies held in memory; oversized archives fail before any merge. */
    const val MAX_DATA_BYTES = 64L * 1024 * 1024
    const val MAX_SMALL_BYTES = 1024L * 1024

    val json = BackupJson

    fun manifest(snapshot: Snapshot, appVersion: String, createdAt: Long, format: Int = FORMAT) = BackupManifest(
        format = format,
        appVersion = appVersion,
        createdAt = createdAt,
        counts = snapshot.tableCounts(),
        items = snapshot.items(),
    )

    /** data.json's bytes, and the manifest that names their SHA-256 (what [BackupArchive.write] puts first). */
    fun rows(manifest: BackupManifest, data: BackupData): Pair<ByteArray, ByteArray> {
        val bytes = json.encodeToString(BackupData.serializer(), data).encodeToByteArray()
        if (bytes.size > MAX_DATA_BYTES) throw BackupFormatException("This backup is too big to open on this phone.")
        val checked = manifest.copy(dataSha256 = Sha256.hex(bytes))
        return json.encodeToString(BackupManifest.serializer(), checked).encodeToByteArray() to bytes
    }

    /** The manifest, if it is a BoringTravel backup of a format up to [newest]. */
    fun parseManifest(text: String?, newest: Int): BackupManifest {
        val manifest = text?.let { decode(it) { t -> json.decodeFromString(BackupManifest.serializer(), t) } }
            ?: throw BackupFormatException("This isn't a BoringTravel backup.")
        if (manifest.app != "BoringTravel" || manifest.format < 1) throw BackupFormatException("This isn't a BoringTravel backup.")
        if (manifest.format > newest) throw BackupFormatException("This backup is from a newer BoringTravel. Update the app, then restore it.")
        return manifest
    }

    /** Every reader's checks once the zip is read: manifest, checksum, rows, counts, ids, dates and places. */
    fun parse(manifestText: String?, dataBytes: ByteArray?, newest: Int): Pair<BackupManifest, BackupData> {
        val manifest = parseManifest(manifestText, newest)
        val bytes = dataBytes ?: throw BackupFormatException("This backup is missing its trips and entries.")
        val sum = manifest.dataSha256
        if (sum != null && !Sha256.hex(bytes).equals(sum, ignoreCase = true)) {
            throw BackupFormatException("This backup's checksum doesn't match. It may be damaged.")
        }
        val data = decode(bytes.decodeToString()) { json.decodeFromString(BackupData.serializer(), it) }
        validate(manifest, data)
        return manifest to data
    }

    /** The SHA-256 of [data] as data.json: the same rows and settings always give the same sum. */
    fun sum(data: BackupData): String = Sha256.hex(json.encodeToString(BackupData.serializer(), data).encodeToByteArray())

    fun encodeStamp(stamp: BackupStamp): String = json.encodeToString(BackupStamp.serializer(), stamp)

    /** The stamp, or null when there is none or it can't be read (then the next backup is written as usual). */
    fun decodeStamp(text: String?): BackupStamp? {
        if (text.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(BackupStamp.serializer(), text) }.getOrNull()?.takeIf { it.sum.isNotBlank() && it.marker.id.isNotBlank() }
    }

    fun encodeMarker(marker: BackupMarker): String = json.encodeToString(BackupMarker.serializer(), marker)

    /** The marker, or null when it is missing, damaged or from a newer app. */
    fun decodeMarker(text: String?): BackupMarker? {
        if (text.isNullOrBlank()) return null
        val marker = runCatching { json.decodeFromString(BackupMarker.serializer(), text) }.getOrNull() ?: return null
        return marker.takeIf { it.format in 1..FORMAT && it.id.isNotBlank() }
    }

    private fun validate(manifest: BackupManifest, data: BackupData) {
        // Conversion validates dates before anything on the phone changes.
        val snapshot = data.toSnapshot()
        val actual = snapshot.tableCounts()
        if (manifest.counts.any { (table, count) -> count < 0 || (actual[table]?.let { it != count } == true) }) {
            throw BackupFormatException("This backup's row counts don't match its contents.")
        }
        val ids = listOf(data.trips.map { it.id }, data.planItems.map { it.id }, data.entries.map { it.id },
            data.media.map { it.id }, data.stops.map { it.id }, data.countryMarks.map { it.id }, data.books.map { it.id })
        if (ids.any { rows -> rows.any(String::isBlank) || rows.toSet().size != rows.size }) {
            throw BackupFormatException("This backup contains missing or duplicate row IDs.")
        }
    }

    private fun <T> decode(text: String, block: (String) -> T): T = try {
        block(text)
    } catch (_: SerializationException) {
        throw BackupFormatException("This backup is damaged and can't be opened.")
    } catch (_: IllegalArgumentException) {
        throw BackupFormatException("This backup is damaged and can't be opened.")
    }
}

/** A file for a backup file: its [name] in the zip ("media/abc.jpg") and its path under the app's files. */
data class ArchiveFile(val name: String, val path: String)

/**
 * A backup file's first read: its rows, its small extras by name, and the size of each photo under media/ (so a restore
 * copies only photos the phone lacks, without hashing the ones it has).
 */
class ArchiveContents(
    val manifest: BackupManifest,
    val data: BackupData,
    val texts: Map<String, ByteArray>,
    val mediaSizes: Map<String, Long> = emptyMap(),
)

/**
 * Backup files (Me › Backup & sync › Save a file), format 2: the manifest and data.json, then each photo as
 * media/<file> and the trips' other files under extras/ (the Android app's BackupArchive).
 */
object BackupArchive {
    const val MEDIA = "media/"
    const val EXTRAS = "extras/"
    const val MIME = "application/zip"

    /** Entries in one file: one per photo and clip file, plus a few. */
    const val MAX_ENTRIES = 250_000

    /** The biggest photo a backup file may carry. */
    const val MAX_PHOTO_BYTES = 256L * 1024 * 1024

    /** Everything a backup file may unpack to; a damaged or hostile zip stops here, not when the phone is full. */
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024 * 1024

    /** Files outside media/ and extras/ (unknown, from a later app). */
    private const val MAX_OTHER_BYTES = 1024L * 1024

    /** The small extras read in the first pass, all together. */
    private const val MAX_TEXT_BYTES = 32L * 1024 * 1024

    /** An extras/ file (the trip video's files; its scene edits are small JSON). */
    private const val MAX_EXTRA_BYTES = 1_000_000_000L
    private const val MAX_EDITS_BYTES = 1L * 1024 * 1024

    private val MEDIA_NAME = Regex("^media/[A-Za-z0-9][A-Za-z0-9._-]{0,199}$")

    /** "BoringTravel-2026-11-11.zip", in the phone's time. */
    fun fileName(at: Long, zone: String? = null): String = "BoringTravel-" + localDateAt(at, zone).civil.iso() + ".zip"

    /** The zip entry for a photo at [localPath] ("media/abc.jpg" stays as it is), or null when it isn't a plain media file. */
    fun mediaEntry(localPath: String): String? = localPath.takeIf { MEDIA_NAME.matches(it) && ".." !in it }

    /**
     * A name that can't point outside the folder it is unpacked into: relative, "/" between parts, no empty, "." or
     * ".." part, no backslash, drive letter or control character.
     */
    fun safeName(name: String): Boolean {
        if (name.isEmpty() || name.length > 400 || name.startsWith("/")) return false
        if (name.any { it == '\\' || it == ':' || it < ' ' || it == '\u007f' }) return false
        return name.removeSuffix("/").split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun isText(name: String) = name.startsWith(EXTRAS + "clip-edits/")

    /** The most bytes entry [name] may unpack to. */
    fun limit(name: String): Long = when {
        name == BackupCodec.MANIFEST -> BackupCodec.MAX_SMALL_BYTES
        name == BackupCodec.DATA -> BackupCodec.MAX_DATA_BYTES
        name.startsWith(MEDIA) -> MAX_PHOTO_BYTES
        isText(name) -> MAX_EDITS_BYTES
        name.startsWith(EXTRAS) -> MAX_EXTRA_BYTES
        else -> MAX_OTHER_BYTES
    }

    /**
     * Writes a backup file to [out]: the rows as format 1 writes them, then [files] in order, each read from [from]. A
     * file gone from the phone by its turn is left out; the names left out are returned. [onFile] gets the progress.
     */
    fun write(
        manifest: BackupManifest,
        data: BackupData,
        files: List<ArchiveFile>,
        from: MediaFiles,
        out: (ByteArray, Int, Int) -> Unit,
        at: Long,
        checkActive: () -> Unit = {},
        onFile: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<String> {
        val day = localDateAt(at, null).civil
        val seconds = ((at - startOfDayMs(localDateAt(at, null), null)) / 1000).toInt().coerceIn(0, 86_399)
        val (time, date) = ZipWriter.dos(day.year, day.month, day.day, seconds / 3600, seconds / 60 % 60, seconds % 60)
        val zip = ZipWriter(out, time, date)
        checkActive()
        val (manifestBytes, dataBytes) = BackupCodec.rows(manifest, data)
        zip.add(BackupCodec.MANIFEST, manifestBytes)
        zip.add(BackupCodec.DATA, dataBytes)
        val names = hashSetOf(BackupCodec.MANIFEST, BackupCodec.DATA)
        val leftOut = ArrayList<String>()
        files.forEachIndexed { i, f ->
            checkActive()
            onFile(i, files.size)
            require(safeName(f.name) && (f.name.startsWith(MEDIA) || f.name.startsWith(EXTRAS))) { "Not a backup file name: ${f.name}" }
            if (!names.add(f.name)) return@forEachIndexed
            // The CRC and size first (a stored entry carries them before its bytes), then the bytes.
            val crc = Crc32()
            var size = 0L
            val there = from.readChunks(f.path) { b, n ->
                checkActive()
                crc.update(b, 0, n)
                size += n
            }
            if (!there || size == 0L) {
                leftOut += f.name
                return@forEachIndexed
            }
            zip.add(f.name, crc.value, size) { write -> from.readChunks(f.path) { b, n -> checkActive(); write(b, 0, n) } }
        }
        onFile(files.size, files.size)
        zip.finish()
        return leftOut
    }

    /** A zip of [size] bytes read through [read]; one that isn't a whole zip is refused in Android's words. */
    fun reader(size: Long, read: (offset: Long, length: Int) -> ByteArray): ZipReader = zipped { ZipReader(size, read) }

    /** Only the manifest, for "Backup from 5 Nov 2026 · 2 trips…" before a restore; throws when it isn't one to restore. */
    fun peek(zip: ZipReader): BackupManifest = zipped {
        zip.entries.forEachIndexed { i, entry -> check(entry.name, i + 1) }
        val entry = zip.entries.firstOrNull { it.name == BackupCodec.MANIFEST } ?: throw BackupFormatException("This isn't a BoringTravel backup.")
        BackupCodec.parseManifest(zip.bytesOf(entry, BackupCodec.MAX_SMALL_BYTES).decodeToString(), BackupCodec.FILE_FORMAT)
    }

    /**
     * The first pass: every entry is checked (safe name, no duplicates, within its size, intact: each one read through
     * and its CRC-32 checked, so a damaged photo stops the restore before any row is merged), the rows and the small
     * extras are kept. Formats 1 and 2. Throws [BackupFormatException] before anything on the phone changes.
     */
    fun read(zip: ZipReader, checkActive: () -> Unit = {}): ArchiveContents = zipped {
        var manifestText: String? = null
        var dataBytes: ByteArray? = null
        val texts = LinkedHashMap<String, ByteArray>()
        val mediaSizes = HashMap<String, Long>()
        val names = HashSet<String>()
        var total = 0L
        var textBytes = 0L
        zip.entries.forEachIndexed { i, entry ->
            checkActive()
            val name = entry.name
            check(name, i + 1)
            if (!names.add(name)) throw BackupFormatException("This backup contains duplicate or excessive files and can't be opened.")
            when {
                entry.isDirectory -> Unit
                name == BackupCodec.MANIFEST -> manifestText = zip.bytesOf(entry, BackupCodec.MAX_SMALL_BYTES, checkActive).decodeToString()
                name == BackupCodec.DATA -> dataBytes = zip.bytesOf(entry, BackupCodec.MAX_DATA_BYTES, checkActive)
                isText(name) -> {
                    val bytes = zip.bytesOf(entry, limit(name), checkActive)
                    textBytes += bytes.size
                    if (textBytes > MAX_TEXT_BYTES) throw BackupFormatException("This backup is too big to open on this phone.")
                    texts[name] = bytes
                }
                else -> {
                    var size = 0L
                    zip.content(entry, limit(name), checkActive) { _, n -> size += n }
                    total += size
                    if (name.startsWith(MEDIA)) mediaSizes[name] = size
                }
            }
            if (total > MAX_TOTAL_BYTES) throw BackupFormatException("This backup is too big to open on this phone.")
        }
        val (manifest, data) = BackupCodec.parse(manifestText, dataBytes, BackupCodec.FILE_FORMAT)
        ArchiveContents(manifest, data, texts, mediaSizes)
    }

    private fun check(name: String, count: Int) {
        if (!safeName(name)) throw BackupFormatException("This backup contains unsafe file names and can't be opened.")
        if (count > MAX_ENTRIES) throw BackupFormatException("This backup contains duplicate or excessive files and can't be opened.")
    }

    /** A zip that can't be read reads as Android's messages: too big, incomplete, else damaged. */
    private inline fun <T> zipped(block: () -> T): T = try {
        block()
    } catch (e: ZipFormatException) {
        throw BackupFormatException(
            when (e.message) {
                "too big", "zip64" -> "This backup is too big to open on this phone."
                "incomplete" -> "This backup is incomplete and can't be opened."
                "not a zip" -> "This isn't a BoringTravel backup."
                else -> "This backup is damaged and can't be opened."
            },
        )
    }
}

/**
 * A restore just made: the phone is as the backup of [backupAt] was. [restored]: what that backup holds (with
 * [planItems]). [changed]: whether anything on the phone differed from it. [photosDownloaded]: photos copied from the
 * file. [photosMissing]: photos the file didn't have. [videos]: saved videos brought back.
 */
data class RestoreReport(
    val backupAt: Long, val restored: BackupCounts, val photosDownloaded: Int, val photosMissing: Int, val fromFile: Boolean = false,
    val planItems: Int = 0, val videos: Int = 0, val changed: Boolean = true,
)

/** A backup file just saved: what it holds; [photosLeftOut] were in the journal but not on this phone. */
data class FileBackupReport(val items: BackupCounts, val photos: Int, val photosLeftOut: Int)

/** What a running backup file save or restore is doing; [done] of [total], or [total] 0 when there is nothing to count. */
data class BackupProgress(val restoring: Boolean, val label: String, val done: Int = 0, val total: Int = 0, val file: Boolean = true) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/* The backup texts (BackupText) are in BackupNames.kt, with the Drive backups' names (round 23). */
