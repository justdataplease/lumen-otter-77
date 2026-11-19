package com.justdataplease.boringtravel.backup

import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Writes and reads Drive backup zips (manifest.json + data.json), and the rows of backup files ([BackupArchive]).
 * Plain Kotlin, unit-tested. The layout and the rules for changing it: docs/BACKUP-FORMAT.md.
 */
object BackupCodec {
    /** Drive backups and the marker: manifest.json + data.json, as 1.0 to 1.5.0 write and read them. */
    const val FORMAT = BACKUP_FORMAT

    /** Backup files (Save a backup file): format 1 plus the photos in media/ and other files in extras/. */
    const val FILE_FORMAT = 2
    const val MANIFEST = "manifest.json"
    const val DATA = "data.json"

    /** Bound decompression and the JSON copies held in memory; oversized archives fail before any merge. */
    internal const val MAX_DATA_BYTES = 64L * 1024 * 1024
    internal const val MAX_SMALL_BYTES = 1024L * 1024
    private const val MAX_ENTRIES = 32

    /** The engine's ([BackupJson]), so both phones write and read the rows the same way. */
    val json = BackupJson

    fun manifest(snapshot: Snapshot, appVersion: String, createdAt: Long, format: Int = FORMAT) = BackupManifest(
        format = format,
        appVersion = appVersion,
        createdAt = createdAt,
        counts = snapshot.tableCounts(),
        items = snapshot.items(),
    )

    /** A Drive backup: manifest.json and data.json, nothing else. */
    fun write(manifest: BackupManifest, data: BackupData, out: OutputStream, checkActive: () -> Unit = {}) {
        val zip = ZipOutputStream(out)
        putRows(zip, manifest, data, checkActive)
        zip.finish()
        zip.flush()
    }

    /** manifest.json (with data.json's SHA-256), then data.json: the start of every backup zip. */
    internal fun putRows(zip: ZipOutputStream, manifest: BackupManifest, data: BackupData, checkActive: () -> Unit) {
        checkActive()
        val bytes = json.encodeToString(BackupData.serializer(), data).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_DATA_BYTES) throw BackupFormatException("This backup is too big to open on this phone.")
        checkActive()
        val checkedManifest = manifest.copy(dataSha256 = digest(bytes))
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(json.encodeToString(BackupManifest.serializer(), checkedManifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(DATA))
        var offset = 0
        while (offset < bytes.size) {
            checkActive()
            val count = minOf(64 * 1024, bytes.size - offset)
            zip.write(bytes, offset, count)
            offset += count
        }
        zip.closeEntry()
    }

    /**
     * Reads a Drive backup zip (format 1: Drive only ever holds what this app wrote there); throws
     * [BackupFormatException] when it isn't one this app can restore. Backup files go through [BackupArchive.read].
     */
    fun read(input: InputStream, checkActive: () -> Unit = {}): Pair<BackupManifest, BackupData> {
        var manifestText: String? = null
        var dataBytes: ByteArray? = null
        try {
            val zip = ZipInputStream(input)
            val names = HashSet<String>()
            while (true) {
                checkActive()
                val entry = zip.nextEntry ?: break
                if (!names.add(entry.name) || names.size > MAX_ENTRIES) {
                    throw BackupFormatException("This backup contains duplicate or excessive files and can't be opened.")
                }
                when (entry.name) {
                    MANIFEST -> manifestText = readEntry(zip, MAX_SMALL_BYTES, checkActive).toString(Charsets.UTF_8)
                    DATA -> dataBytes = readEntry(zip, MAX_DATA_BYTES, checkActive)
                    // Even unknown extension entries must be bounded; closeEntry alone would decompress indefinitely.
                    else -> readEntry(zip, MAX_SMALL_BYTES, checkActive)
                }
                zip.closeEntry()
            }
        } catch (_: java.util.zip.ZipException) {
            throw BackupFormatException("This backup is damaged and can't be opened.")
        } catch (_: java.io.EOFException) {
            throw BackupFormatException("This backup is incomplete and can't be opened.")
        }
        return parse(manifestText, dataBytes, FORMAT, checkActive)
    }

    /** The manifest, if it is a BoringTravel backup of a format up to [newest]. */
    internal fun parseManifest(text: String?, newest: Int): BackupManifest {
        val manifest = text?.let { decode(it) { json.decodeFromString(BackupManifest.serializer(), it) } }
            ?: throw BackupFormatException("This isn't a BoringTravel backup.")
        if (manifest.app != "BoringTravel" || manifest.format < 1) throw BackupFormatException("This isn't a BoringTravel backup.")
        if (manifest.format > newest) throw BackupFormatException("This backup is from a newer BoringTravel. Update the app, then restore it.")
        return manifest
    }

    /** Every reader's checks once the zip is read: manifest, checksum, rows, counts, ids, dates and places. */
    internal fun parse(manifestText: String?, dataBytes: ByteArray?, newest: Int, checkActive: () -> Unit): Pair<BackupManifest, BackupData> {
        val manifest = parseManifest(manifestText, newest)
        val bytes = dataBytes
            ?: throw BackupFormatException("This backup is missing its trips and entries.")
        if (manifest.dataSha256 != null && !digest(bytes).equals(manifest.dataSha256, ignoreCase = true)) {
            throw BackupFormatException("This backup's checksum doesn't match. It may be damaged.")
        }
        checkActive()
        val data = decode(bytes.toString(Charsets.UTF_8)) { json.decodeFromString(BackupData.serializer(), it) }
        validate(manifest, data)
        checkActive()
        return manifest to data
    }

    /** The SHA-256 of [data] as data.json: the same rows and settings always give the same sum ([BackupManifest.dataSha256]). */
    fun sum(data: BackupData): String = digest(json.encodeToString(BackupData.serializer(), data).toByteArray(Charsets.UTF_8))

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

    internal fun readEntry(zip: ZipInputStream, limit: Long, checkActive: () -> Unit): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val n = zip.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw BackupFormatException("This backup is too big to open on this phone.")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun validate(manifest: BackupManifest, data: BackupData) {
        // Conversion validates dates before the manager can write any row. FTS is derived, not a separate payload.
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
        // A place that can't be on Earth is not a reason to refuse everything else: the restore mends such rows
        // (BackupSafety.clean), as a backup does before it writes them.
    }

    private fun <T> decode(text: String, block: (String) -> T): T = try {
        block(text)
    } catch (_: SerializationException) {
        throw BackupFormatException("This backup is damaged and can't be opened.")
    } catch (_: IllegalArgumentException) {
        throw BackupFormatException("This backup is damaged and can't be opened.")
    }
}
