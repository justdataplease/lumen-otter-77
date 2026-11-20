package com.justdataplease.boringtravel.compat

import com.justdataplease.boringtravel.backup.ZipWriter
import com.justdataplease.boringtravel.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/*
 * The java.io, java.util.zip and java.net calls the Android app's Word export (export/TripDocx.kt) and its file names
 * (export/ExportFiles.kt) make, in plain Kotlin under the same names (see JavaTime.kt). The zip is this app's own
 * (backup/Zip.kt): every part stored as it is, with a fixed date, so the same trip always gives the same bytes; Java
 * deflates them and dates them with the clock, which Word reads the same.
 */

/** java.io.OutputStream. */
abstract class OutputStream : AutoCloseable {
    abstract fun write(b: Int)

    open fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) write(b[i].toInt())
    }

    fun write(b: ByteArray) = write(b, 0, b.size)

    open fun flush() {}

    override fun close() {}
}

/** java.io.ByteArrayOutputStream. */
class ByteArrayOutputStream(size: Int = 32) : OutputStream() {
    private var buf = ByteArray(size.coerceAtLeast(1))
    private var count = 0

    private fun room(n: Int) {
        if (count + n <= buf.size) return
        var size = buf.size * 2
        while (size < count + n) size *= 2
        buf = buf.copyOf(size)
    }

    override fun write(b: Int) {
        room(1)
        buf[count++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        room(len)
        b.copyInto(buf, count, off, off + len)
        count += len
    }

    fun size(): Int = count

    fun toByteArray(): ByteArray = buf.copyOf(count)

    /** The bytes as text ([charsetName]: UTF-8, the only one written here). */
    @Suppress("UNUSED_PARAMETER")
    fun toString(charsetName: String): String = buf.decodeToString(0, count)
}

/** A charset by name; only UTF-8 is written (ASCII is the same bytes for ASCII text). */
class Charset internal constructor(val name: String) {
    fun name(): String = name
}

/** kotlin.text.Charsets (JVM only). */
object Charsets {
    val UTF_8 = Charset("UTF-8")
    val US_ASCII = Charset("US-ASCII")
}

/** String.toByteArray(charset): UTF-8 (the same bytes as ASCII for ASCII text). */
@Suppress("UNUSED_PARAMETER")
fun String.toByteArray(charset: Charset): ByteArray = encodeToByteArray()

/** kotlin.io's buffered(): this app's streams are read and written through buffers of their own. */
@Suppress("UNUSED_PARAMETER")
fun OutputStream.buffered(bufferSize: Int = 8192): OutputStream = this

/** java.io.FileOutputStream, over the phone's file system (kotlinx-io), written as it comes. */
class FileOutputStream(file: File) : OutputStream() {
    private val sink = SystemFileSystem.sink(Path(file.path)).buffered()

    override fun write(b: Int) {
        sink.writeByte(b.toByte())
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        sink.write(b, off, off + len)
    }

    override fun flush() = sink.flush()

    override fun close() = sink.close()
}

/** java.io.Writer. */
abstract class Writer : AutoCloseable {
    abstract fun write(str: String)

    open fun flush() {}

    override fun close() = flush()
}

/** java.io.OutputStreamWriter: text as UTF-8 bytes. */
class OutputStreamWriter(private val out: OutputStream, @Suppress("UNUSED_PARAMETER") charset: Charset) : Writer() {
    override fun write(str: String) {
        val bytes = str.encodeToByteArray()
        out.write(bytes, 0, bytes.size)
    }

    override fun flush() = out.flush()

    override fun close() {
        flush()
        out.close()
    }
}

/** java.io.BufferedWriter: the text goes on in pieces of about [size] chars, never splitting a surrogate pair. */
private class BufferedWriter(private val out: Writer, private val size: Int) : Writer() {
    private val text = StringBuilder()

    override fun write(str: String) {
        text.append(str)
        if (text.length >= size) drain()
    }

    private fun drain() {
        var n = text.length
        if (n > 0 && text[n - 1].isHighSurrogate()) n--
        if (n == 0) return
        val piece = text.substring(0, n)
        val rest = text.substring(n)
        text.clear()
        text.append(rest)
        out.write(piece)
    }

    override fun flush() {
        drain()
        out.flush()
    }

    override fun close() {
        flush()
        out.close()
    }
}

/** kotlin.io's Writer.buffered(bufferSize). */
fun Writer.buffered(bufferSize: Int): Writer = BufferedWriter(this, bufferSize)

/**
 * java.io.InputStream: a byte at a time ([read], -1 at the end), or the rest whole ([readBytes]). A photo's bytes as
 * TripDocx copies them, a document's for ItineraryImport, a page with its pictures inside for Copy from a link.
 */
abstract class InputStream : AutoCloseable {
    abstract fun read(): Int

    /** Up to [b]'s size into [b]; how many, or -1 at the end (Java's read(byte[])). */
    fun read(b: ByteArray): Int = read(b, 0, b.size)

    /** Up to [len] bytes into [b] from [off]; how many, or -1 at the end. */
    open fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var n = 0
        while (n < len) {
            val c = read()
            if (c < 0) break
            b[off + n++] = c.toByte()
        }
        return if (n == 0) -1 else n
    }

    open fun readBytes(): ByteArray {
        val out = ByteArrayOutputStream(8192)
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer, 0, buffer.size)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    fun copyTo(out: OutputStream): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = read(buffer, 0, buffer.size)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return total
    }

    override fun close() {}
}

/** java.io.ByteArrayInputStream: [bytes] from the start. */
class ByteArrayInputStream(private val bytes: ByteArray) : InputStream() {
    private var at = 0

    override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (at >= bytes.size) return -1
        val n = minOf(len, bytes.size - at)
        bytes.copyInto(b, off, at, at + n)
        at += n
        return n
    }

    override fun readBytes(): ByteArray = bytes.copyOfRange(at, bytes.size).also { at = bytes.size }
}

/** java.io.FileInputStream over the phone's file system (kotlinx-io, buffered). */
class FileInputStream(file: File) : InputStream() {
    private val source = SystemFileSystem.source(Path(file.path)).buffered()

    override fun read(): Int = if (source.exhausted()) -1 else source.readByte().toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = source.readAtMostTo(b, off, off + len)
        return if (n <= 0) -1 else n
    }

    override fun close() = source.close()
}

/** kotlin.io's buffered(): this app's streams read through buffers of their own. */
@Suppress("UNUSED_PARAMETER")
fun InputStream.buffered(bufferSize: Int = 8192): InputStream = this

/** kotlin.io's String.byteInputStream(): the text's UTF-8 bytes. */
fun String.byteInputStream(): InputStream = ByteArrayInputStream(encodeToByteArray())

/**
 * java.io.File: a photo made for TripDocx (whether it is there, its bytes), read from [store]: the phone's own file
 * system by default (kotlinx-io), or the app's files ([com.justdataplease.boringtravel.ios.MediaFiles]). On the phone's
 * file system also folders and files as Copy from a link keeps the pictures that come inside a page (InlinePictures):
 * made, written, moved and deleted.
 */
class File(val path: String, private val store: Store = SystemStore) {
    constructor(parent: File, child: String) : this(parent.path.trimEnd('/') + "/" + child)

    /** Where a [File] is read from. */
    interface Store {
        fun isFile(path: String): Boolean
        fun read(path: String): ByteArray?

        /** Bytes in the file at [path]; null when there is none (round 26: a shared trip's photos by their size). */
        fun size(path: String): Long? = read(path)?.size?.toLong()
    }

    val name: String get() = path.trimEnd('/').substringAfterLast('/')

    /** The folder the file is in; null at the top (java.io.File.getParentFile). */
    val parentFile: File? get() {
        val p = path.trimEnd('/', '\\')
        val at = maxOf(p.lastIndexOf('/'), p.lastIndexOf('\\'))
        return if (at <= 0) null else File(p.substring(0, at), store)
    }

    val isFile: Boolean get() = store.isFile(path)

    val isDirectory: Boolean get() = runCatching { SystemFileSystem.metadataOrNull(Path(path))?.isDirectory == true }.getOrDefault(false)

    fun exists(): Boolean = if (store !== SystemStore) store.isFile(path) else runCatching { SystemFileSystem.exists(Path(path)) }.getOrDefault(false)

    /** Bytes in the file; 0 when there is none. */
    fun length(): Long =
        if (store !== SystemStore) store.size(path)?.takeIf { it >= 0 } ?: 0L
        else runCatching { SystemFileSystem.metadataOrNull(Path(path))?.size }.getOrNull()?.takeIf { it >= 0 } ?: 0L

    /** The folder and those above it (the app's files make their folders themselves). */
    fun mkdirs(): Boolean = if (store !== SystemStore) true else runCatching { SystemFileSystem.createDirectories(Path(path), mustCreate = false) }.isSuccess && isDirectory

    /** True when it was there and is gone (a folder only when empty, as Java's). */
    fun delete(): Boolean = exists() && runCatching { SystemFileSystem.delete(Path(path), mustExist = true) }.isSuccess

    /** The folder and everything in it, or the file (kotlin.io's deleteRecursively). */
    fun deleteRecursively(): Boolean {
        if (!exists()) return true
        if (isDirectory) runCatching { SystemFileSystem.list(Path(path)) }.getOrDefault(emptyList()).forEach { File(it.toString()).deleteRecursively() }
        return delete()
    }

    /** Moves the file to [dest] (on the same disk); false when it couldn't. */
    fun renameTo(dest: File): Boolean = runCatching { SystemFileSystem.atomicMove(Path(path), Path(dest.path)) }.isSuccess

    fun resolve(child: String): File = File(this, child)

    fun inputStream(): InputStream = if (store === SystemStore) FileInputStream(this) else ByteArrayInputStream(readBytes())

    fun outputStream(): OutputStream = FileOutputStream(this)

    fun readBytes(): ByteArray = store.read(path) ?: throw IOException("Can't read $path")

    fun readText(): String = readBytes().decodeToString()

    fun writeBytes(bytes: ByteArray) = outputStream().use { it.write(bytes) }

    override fun equals(other: Any?): Boolean = other is File && other.path == path

    override fun hashCode(): Int = path.hashCode()

    override fun toString(): String = path

    /** The phone's file system. */
    object SystemStore : Store {
        override fun isFile(path: String): Boolean = runCatching { SystemFileSystem.metadataOrNull(Path(path))?.isRegularFile == true }.getOrDefault(false)

        override fun read(path: String): ByteArray? =
            runCatching { SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() } }.getOrNull()
    }

    companion object {
        /** java.io.File.createTempFile: a new empty file in [directory] named [prefix], a random part, [suffix]. */
        fun createTempFile(prefix: String, suffix: String?, directory: File?): File {
            val dir = directory ?: throw IOException("No folder for a temporary file")
            repeat(100) {
                val name = prefix + kotlin.random.Random.nextLong().toULong().toString() + (suffix ?: ".tmp")
                val file = File(dir, name)
                if (!file.exists()) {
                    file.writeBytes(ByteArray(0))
                    return file
                }
            }
            throw IOException("Couldn't make a temporary file")
        }
    }
}

/** java.util.zip.ZipEntry: a part's name. */
class ZipEntry(val name: String)

/**
 * java.util.zip.ZipOutputStream over this app's zip writer ([ZipWriter]): each part is kept until it is closed, then
 * written stored, with its CRC-32 and sizes in its header and a fixed date (1 Jan 1980).
 */
class ZipOutputStream(private val out: OutputStream) : OutputStream() {
    private val writer = ZipWriter({ b, off, len -> out.write(b, off, len) })
    private var current: String? = null
    private var data = ByteArrayOutputStream(64 * 1024)
    private var finished = false

    fun putNextEntry(e: ZipEntry) {
        closeEntry()
        current = e.name
        data = ByteArrayOutputStream(64 * 1024)
    }

    override fun write(b: Int) {
        if (current == null) throw IOException("No current zip entry")
        data.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (current == null) throw IOException("No current zip entry")
        data.write(b, off, len)
    }

    fun closeEntry() {
        val name = current ?: return
        writer.add(name, data.toByteArray())
        current = null
        data = ByteArrayOutputStream()
    }

    fun finish() {
        if (finished) return
        closeEntry()
        writer.finish()
        finished = true
    }

    override fun flush() = out.flush()

    override fun close() {
        finish()
        out.close()
    }
}

/**
 * java.net.URI as ExportFiles and the AI planner's TripDraftParser read an address: its scheme, its host, and its path
 * with "." and ".." worked out ([normalize]) and %-escapes decoded. Characters an address can't hold are refused, as
 * Java refuses them.
 */
class URI private constructor(parts: Pair<String?, String?>, text: String?) {
    constructor(text: String) : this(parse(text), text)

    private constructor(parts: Pair<String?, String?>) : this(parts, null)

    val scheme: String? = parts.first
    private val rawPath: String? = parts.second

    /**
     * The host of a server address ("www.example.com"), as Java reads it: null when there is no "//" authority, or when
     * it isn't a host name, an IPv4 or a bracketed IPv6 address (Java then takes it as a registry name, without host).
     */
    val host: String? = text?.let(::hostOf)

    /** The path, decoded; null for an address without one ("data:…"). */
    val path: String? get() = rawPath?.let(::decode)

    /** The same address with "." and ".." worked out in its path. */
    fun normalize(): URI {
        val p = rawPath ?: return this
        val out = ArrayList<String>()
        val segments = p.split('/')
        for ((i, s) in segments.withIndex()) {
            when (s) {
                "." -> if (i == segments.lastIndex) out += ""
                ".." -> if (out.size > 1 && out.last() != "..") out.removeAt(out.lastIndex) else out += ".."
                else -> out += s
            }
        }
        val joined = out.joinToString("/").ifEmpty { if (p.startsWith("/")) "/" else "" }
        return URI(scheme to joined)
    }

    private fun decode(s: String): String {
        if ('%' !in s) return s
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            val v = if (s[i] == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (v != null) {
                bytes += v.toByte()
                i += 3
            } else {
                s[i].toString().encodeToByteArray().forEach { bytes += it }
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private companion object {
        val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        /** Java's hostname: labels of letters, digits and inner hyphens; the last one starts with a letter when there are several. */
        val HOSTNAME = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\\.?$")
        val IPV4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
        val PORT = Regex(":\\d*$")

        fun hostOf(text: String): String? {
            val colon = SCHEME.find(text)?.value ?: return null
            val rest = text.substring(colon.length)
            if (!rest.startsWith("//")) return null
            val authority = rest.drop(2).takeWhile { it != '/' && it != '?' && it != '#' }
            val server = authority.substringAfterLast('@')
            if (server.startsWith("[")) return server.substringBefore(']', "").takeIf { it.isNotEmpty() }?.plus("]")
            val host = server.replace(PORT, "")
            if (host.isEmpty()) return null
            if (IPV4.matches(host)) return host.takeIf { h -> h.split('.').all { it.toInt() <= 255 } }
            if (!HOSTNAME.matches(host)) return null
            val labels = host.trimEnd('.').split('.')
            return host.takeIf { labels.size == 1 || labels.last().first().isLetter() }
        }

        /** The scheme ("file") and the path still escaped; no path for an opaque address ("data:…", "mailto:…"). */
        fun parse(text: String): Pair<String?, String?> {
            require(text.none { it <= ' ' || it in "\"<>\\^`{|}" }) { "Illegal character in $text" }
            val colon = SCHEME.find(text)?.value
            val rest = if (colon != null) text.substring(colon.length) else text
            val noFragment = rest.substringBefore('#').substringBefore('?')
            val path = when {
                colon != null && !rest.startsWith("/") -> null
                noFragment.startsWith("//") -> noFragment.drop(2).let { a -> a.indexOf('/').let { if (it < 0) "" else a.substring(it) } }
                else -> noFragment
            }
            return colon?.dropLast(1) to path
        }
    }
}
