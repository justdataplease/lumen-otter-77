package com.justdataplease.boringtravel.backup

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A file for a backup file: its [name] in the zip ("media/abc.jpg") and where it is on the phone. */
data class ArchiveFile(val name: String, val source: File)

/**
 * A backup file's first read: its rows, its small extras ([BackupExtras.isText]) by name in the zip, and the size of
 * each photo under media/ (so a restore copies only photos the phone lacks, without hashing the ones it has).
 */
class ArchiveContents(
    val manifest: BackupManifest,
    val data: BackupData,
    val texts: Map<String, ByteArray>,
    val mediaSizes: Map<String, Long> = emptyMap(),
)

/**
 * Backup files (Me › Backup & sync › Save a backup file), format 2: format 1's manifest.json and data.json, then each
 * photo as media/<file> and the trips' other files under extras/ ([BackupExtras]). Written and read as a stream, so the
 * archive is never held in memory. A restore reads a file twice: [read] checks every entry and takes the rows, then
 * [eachFile] hands over the files to copy. docs/BACKUP-FORMAT.md describes the layout. Plain Kotlin, unit-tested.
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

    /** Files outside media/ and extras/ (unknown, from a later app): bounded like format 1's. */
    private const val MAX_OTHER_BYTES = 1024L * 1024

    /** The small extras read in the first pass, all together. */
    private const val MAX_TEXT_BYTES = 32L * 1024 * 1024

    private val MEDIA_NAME = Regex("^media/[A-Za-z0-9][A-Za-z0-9._-]{0,199}$")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    /** "BoringTravel-2026-11-11.zip", in the phone's time. */
    fun fileName(at: Long, zone: ZoneId): String = "BoringTravel-" + stamp.format(Instant.ofEpochMilli(at).atZone(zone)) + ".zip"

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

    /** The most bytes entry [name] may unpack to. */
    fun limit(name: String): Long = when {
        name == BackupCodec.MANIFEST -> BackupCodec.MAX_SMALL_BYTES
        name == BackupCodec.DATA -> BackupCodec.MAX_DATA_BYTES
        name.startsWith(MEDIA) -> MAX_PHOTO_BYTES
        name.startsWith(EXTRAS) -> BackupExtras.limit(name)
        else -> MAX_OTHER_BYTES
    }

    /**
     * Writes a backup file: the rows as format 1 writes them, then [files] in order (photos and clip files stored as they
     * are, JSON compressed). A file gone from the phone by its turn is left out; the names left out are returned.
     */
    fun write(
        manifest: BackupManifest,
        data: BackupData,
        files: List<ArchiveFile>,
        out: OutputStream,
        checkActive: () -> Unit = {},
        onFile: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<String> {
        val zip = ZipOutputStream(out)
        BackupCodec.putRows(zip, manifest, data, checkActive)
        val names = hashSetOf(BackupCodec.MANIFEST, BackupCodec.DATA)
        val leftOut = ArrayList<String>()
        val buffer = ByteArray(64 * 1024)
        files.forEachIndexed { i, f ->
            checkActive()
            onFile(i, files.size)
            require(safeName(f.name) && (f.name.startsWith(MEDIA) || f.name.startsWith(EXTRAS))) { "Not a backup file name: ${f.name}" }
            if (!names.add(f.name)) return@forEachIndexed
            val input = try {
                f.source.inputStream()
            } catch (_: FileNotFoundException) {
                leftOut += f.name
                return@forEachIndexed
            }
            input.use {
                // Photos, videos and sound are compressed already; deflating them again only costs time.
                zip.setLevel(if (BackupExtras.isText(f.name)) Deflater.DEFAULT_COMPRESSION else Deflater.NO_COMPRESSION)
                zip.putNextEntry(ZipEntry(f.name))
                while (true) {
                    checkActive()
                    val n = it.read(buffer)
                    if (n < 0) break
                    zip.write(buffer, 0, n)
                }
                zip.closeEntry()
            }
        }
        onFile(files.size, files.size)
        zip.finish()
        zip.flush()
        return leftOut
    }

    /** Only the manifest, for "Backup from 5 Nov 2026 · 2 trips…" before a restore; throws when it isn't one to restore. */
    fun peek(input: InputStream, checkActive: () -> Unit = {}): BackupManifest = zipped {
        val zip = ZipInputStream(input)
        var count = 0
        while (true) {
            checkActive()
            val entry = zip.nextEntry ?: break
            check(entry.name, ++count)
            if (entry.name == BackupCodec.MANIFEST) {
                return@zipped BackupCodec.parseManifest(BackupCodec.readEntry(zip, BackupCodec.MAX_SMALL_BYTES, checkActive).toString(Charsets.UTF_8), BackupCodec.FILE_FORMAT)
            }
            drain(zip, limit(entry.name), checkActive)
            zip.closeEntry()
        }
        throw BackupFormatException("This isn't a BoringTravel backup.")
    }

    /**
     * The first pass: every entry is checked (safe name, no duplicates, within its size, intact), the rows and the
     * small extras are kept, the rest is only read through. Formats 1 and 2. Throws [BackupFormatException] before
     * anything on the phone changes.
     */
    fun read(input: InputStream, checkActive: () -> Unit = {}): ArchiveContents {
        var manifestText: String? = null
        var dataBytes: ByteArray? = null
        val texts = LinkedHashMap<String, ByteArray>()
        val mediaSizes = HashMap<String, Long>()
        zipped {
            val zip = ZipInputStream(input)
            val names = HashSet<String>()
            var total = 0L
            var textBytes = 0L
            while (true) {
                checkActive()
                val entry = zip.nextEntry ?: break
                val name = entry.name
                check(name, names.size + 1)
                if (!names.add(name)) throw BackupFormatException("This backup contains duplicate or excessive files and can't be opened.")
                when {
                    entry.isDirectory -> Unit
                    name == BackupCodec.MANIFEST -> manifestText = BackupCodec.readEntry(zip, BackupCodec.MAX_SMALL_BYTES, checkActive).toString(Charsets.UTF_8)
                    name == BackupCodec.DATA -> dataBytes = BackupCodec.readEntry(zip, BackupCodec.MAX_DATA_BYTES, checkActive)
                    BackupExtras.isText(name) -> {
                        val bytes = BackupCodec.readEntry(zip, limit(name), checkActive)
                        textBytes += bytes.size
                        if (textBytes > MAX_TEXT_BYTES) throw BackupFormatException("This backup is too big to open on this phone.")
                        texts[name] = bytes
                    }
                    // Read to the end all the same: the zip checks each entry's CRC there, so a damaged photo stops the
                    // restore before any row is merged.
                    else -> {
                        val size = drain(zip, limit(name), checkActive)
                        total += size
                        if (name.startsWith(MEDIA)) mediaSizes[name] = size
                    }
                }
                if (total > MAX_TOTAL_BYTES) throw BackupFormatException("This backup is too big to open on this phone.")
                drain(zip, limit(name), checkActive)
                zip.closeEntry()
            }
        }
        val (manifest, data) = BackupCodec.parse(manifestText, dataBytes, BackupCodec.FILE_FORMAT, checkActive)
        return ArchiveContents(manifest, data, texts, mediaSizes)
    }

    /**
     * The second pass: [visit] gets each file under media/ and extras/ with its content, bounded to [limit]; what it
     * doesn't read is read through. Closing the content does nothing.
     */
    suspend fun eachFile(input: InputStream, checkActive: () -> Unit = {}, visit: suspend (name: String, content: InputStream) -> Unit) {
        zipped {
            val zip = ZipInputStream(input)
            var count = 0
            while (true) {
                checkActive()
                val entry = zip.nextEntry ?: break
                val name = entry.name
                check(name, ++count)
                if (!entry.isDirectory && (name.startsWith(MEDIA) || name.startsWith(EXTRAS))) {
                    visit(name, Bounded(zip, limit(name), checkActive))
                }
                drain(zip, limit(name), checkActive)
                zip.closeEntry()
            }
        }
    }

    private fun check(name: String, count: Int) {
        if (!safeName(name)) throw BackupFormatException("This backup contains unsafe file names and can't be opened.")
        if (count > MAX_ENTRIES) throw BackupFormatException("This backup contains duplicate or excessive files and can't be opened.")
    }

    /** Reads the rest of the current entry; throws past [limit]. Returns the bytes read. */
    private fun drain(zip: ZipInputStream, limit: Long, checkActive: () -> Unit): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val n = zip.read(buffer)
            if (n < 0) return total
            total += n
            if (total > limit) throw BackupFormatException("This backup is too big to open on this phone.")
        }
    }

    private inline fun <T> zipped(block: () -> T): T = try {
        block()
    } catch (_: java.util.zip.ZipException) {
        throw BackupFormatException("This backup is damaged and can't be opened.")
    } catch (_: java.io.EOFException) {
        throw BackupFormatException("This backup is incomplete and can't be opened.")
    }

    /** One entry's content, at most [limit] bytes; [close] leaves the zip open. */
    private class Bounded(private val zip: ZipInputStream, private val limit: Long, private val checkActive: () -> Unit) : InputStream() {
        private var total = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkActive()
            val n = zip.read(b, off, len)
            if (n > 0) {
                total += n
                if (total > limit) throw BackupFormatException("This backup is too big to open on this phone.")
            }
            return n
        }

        override fun close() = Unit
    }
}
