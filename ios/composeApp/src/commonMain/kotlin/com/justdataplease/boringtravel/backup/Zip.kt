package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.io.IOException

/*
 * The zip container of a backup file, in plain Kotlin (the Android app writes and reads it with java.util.zip, which
 * an iPhone doesn't have). Written: every entry stored as it is (method 0, with its CRC-32 and sizes in the local
 * header, as Java's ZipInputStream reads stored entries). Read: through the central directory, stored or deflated
 * entries (what Android's ZipOutputStream writes), each checked against its CRC-32 and size. No Zip64: a file or an
 * archive past 4 GB is refused.
 */

/** A zip that can't be read: damaged, incomplete, or not a zip at all. */
class ZipFormatException(message: String) : IOException(message)

/** CRC-32 (IEEE 802.3), as java.util.zip.CRC32 computes it. */
class Crc32 {
    private var crc = -1
    val value: Long get() = (crc.inv().toLong() and 0xFFFFFFFFL)

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        var c = crc
        for (i in offset until offset + length) c = TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        crc = c
    }

    companion object {
        private val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
            c
        }

        fun of(bytes: ByteArray): Long = Crc32().apply { update(bytes) }.value
    }
}

/**
 * Raw DEFLATE (RFC 1951) decompression, streaming: bytes come from [input] (-1 at the end), what they decode to goes
 * to [output] in pieces. The canonical Huffman decoding of Mark Adler's puff, with a 32 KB window.
 */
class Inflater(private val input: () -> Int, private val output: (ByteArray, Int) -> Unit) {
    private val window = ByteArray(WINDOW)
    private var wpos = 0
    private val out = ByteArray(64 * 1024)
    private var outLen = 0
    private var bitBuf = 0
    private var bitCnt = 0

    private class Huffman(val count: IntArray = IntArray(16), val symbol: IntArray)

    /** Decodes the whole stream. */
    fun run() {
        do {
            val last = bits(1)
            when (bits(2)) {
                0 -> stored()
                1 -> codes(FIXED_LEN, FIXED_DIST)
                2 -> dynamic()
                else -> throw ZipFormatException("bad block")
            }
        } while (last == 0)
        flush()
    }

    private fun byte(): Int {
        val b = input()
        if (b < 0) throw ZipFormatException("incomplete")
        return b
    }

    private fun bits(n: Int): Int {
        var v = bitBuf
        while (bitCnt < n) {
            v = v or (byte() shl bitCnt)
            bitCnt += 8
        }
        bitBuf = v ushr n
        bitCnt -= n
        return v and ((1 shl n) - 1)
    }

    private fun emit(b: Int) {
        window[wpos] = b.toByte()
        wpos = (wpos + 1) and (WINDOW - 1)
        out[outLen++] = b.toByte()
        if (outLen == out.size) flush()
    }

    private fun flush() {
        if (outLen > 0) output(out, outLen)
        outLen = 0
    }

    private fun stored() {
        bitBuf = 0
        bitCnt = 0
        val len = byte() or (byte() shl 8)
        val nlen = byte() or (byte() shl 8)
        if (len != (nlen.inv() and 0xFFFF)) throw ZipFormatException("bad stored block")
        repeat(len) { emit(byte()) }
    }

    private fun decode(h: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..15) {
            code = code or bits(1)
            val count = h.count[len]
            if (code - count < first) return h.symbol[index + (code - first)]
            index += count
            first += count
            first = first shl 1
            code = code shl 1
        }
        throw ZipFormatException("bad code")
    }

    private fun codes(lencode: Huffman, distcode: Huffman) {
        while (true) {
            var symbol = decode(lencode)
            if (symbol < 256) {
                emit(symbol)
                continue
            }
            if (symbol == 256) return
            symbol -= 257
            if (symbol >= 29) throw ZipFormatException("bad length")
            val len = LBASE[symbol] + bits(LEXT[symbol])
            val ds = decode(distcode)
            if (ds >= 30) throw ZipFormatException("bad distance")
            val dist = DBASE[ds] + bits(DEXT[ds])
            if (dist > WINDOW) throw ZipFormatException("distance too far")
            var from = (wpos - dist) and (WINDOW - 1)
            repeat(len) {
                emit(window[from].toInt() and 0xFF)
                from = (from + 1) and (WINDOW - 1)
            }
        }
    }

    private fun dynamic() {
        val nlen = bits(5) + 257
        val ndist = bits(5) + 1
        val ncode = bits(4) + 4
        if (nlen > 286 || ndist > 30) throw ZipFormatException("bad counts")
        val lengths = IntArray(320)
        for (i in 0 until ncode) lengths[ORDER[i]] = bits(3)
        val lencode = construct(lengths, 0, 19)
        var index = 0
        while (index < nlen + ndist) {
            var symbol = decode(lencode)
            if (symbol < 16) {
                lengths[index++] = symbol
                continue
            }
            var len = 0
            when (symbol) {
                16 -> {
                    if (index == 0) throw ZipFormatException("repeat with no first length")
                    len = lengths[index - 1]
                    symbol = 3 + bits(2)
                }
                17 -> symbol = 3 + bits(3)
                else -> symbol = 11 + bits(7)
            }
            if (index + symbol > nlen + ndist) throw ZipFormatException("too many lengths")
            repeat(symbol) { lengths[index++] = len }
        }
        if (lengths[256] == 0) throw ZipFormatException("no end-of-block code")
        codes(construct(lengths, 0, nlen), construct(lengths, nlen, ndist))
    }

    companion object {
        private const val WINDOW = 32 * 1024
        private val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
        private val LBASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
        private val LEXT = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
        private val DBASE = intArrayOf(
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
            8193, 12289, 16385, 24577,
        )
        private val DEXT = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)

        private fun construct(lengths: IntArray, from: Int, n: Int): Huffman {
            val count = IntArray(16)
            for (i in 0 until n) count[lengths[from + i]]++
            val offs = IntArray(16)
            for (len in 1 until 15) offs[len + 1] = offs[len] + count[len]
            val symbol = IntArray(n)
            for (i in 0 until n) if (lengths[from + i] != 0) symbol[offs[lengths[from + i]]++] = i
            count[0] = 0
            return Huffman(count, symbol)
        }

        private val FIXED_LEN = IntArray(288) { i -> if (i < 144) 8 else if (i < 256) 9 else if (i < 280) 7 else 8 }.let { construct(it, 0, 288) }
        private val FIXED_DIST = construct(IntArray(30) { 5 }, 0, 30)

        /** All of [data] decompressed (small entries: the manifest and the rows). */
        fun inflate(data: ByteArray): ByteArray {
            var at = 0
            val result = ArrayList<ByteArray>()
            Inflater({ if (at < data.size) data[at++].toInt() and 0xFF else -1 }) { b, n -> result += b.copyOf(n) }.run()
            val all = ByteArray(result.sumOf { it.size })
            var p = 0
            result.forEach { it.copyInto(all, p); p += it.size }
            return all
        }
    }
}

/** One entry of a zip's central directory. [dataOffset] is filled in when its local header is read. */
class ZipEntry(
    val name: String,
    val method: Int,
    val crc: Long,
    val compressedSize: Long,
    val size: Long,
    val localHeader: Long,
) {
    val isDirectory: Boolean get() = name.endsWith("/")
}

/**
 * Writes a zip of stored entries to [out] (each piece in turn). [add] takes an entry's name, its CRC-32 and size (the
 * caller reads the file once for them), and [content] writes its bytes through the function it is given.
 */
class ZipWriter(private val out: (ByteArray, Int, Int) -> Unit, private val dosTime: Int = 0, private val dosDate: Int = (1 shl 5) or 1) {
    private class Written(val name: ByteArray, val crc: Long, val size: Long, val offset: Long)

    private val written = ArrayList<Written>()
    private var position = 0L

    private fun put(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        out(bytes, offset, length)
        position += length
    }

    fun add(name: String, crc: Long, size: Long, content: ((ByteArray, Int, Int) -> Unit) -> Unit) {
        if (size > MAX_32 || position > MAX_32) throw ZipFormatException("too big")
        val n = name.encodeToByteArray()
        val header = Bytes(30 + n.size)
            .int(0x04034b50).short(20).short(FLAG_UTF8).short(0).short(dosTime).short(dosDate)
            .int(crc.toInt()).int(size.toInt()).int(size.toInt()).short(n.size).short(0).bytes(n)
        val offset = position
        put(header.array)
        var counted = 0L
        content { b, off, len ->
            put(b, off, len)
            counted += len
        }
        if (counted != size) throw ZipFormatException("size changed")
        written += Written(n, crc, size, offset)
    }

    /** A small entry whose bytes are at hand. */
    fun add(name: String, bytes: ByteArray) = add(name, Crc32.of(bytes), bytes.size.toLong()) { it(bytes, 0, bytes.size) }

    /** The central directory and its end. */
    fun finish() {
        val start = position
        for (w in written) {
            val entry = Bytes(46 + w.name.size)
                .int(0x02014b50).short(20).short(20).short(FLAG_UTF8).short(0).short(dosTime).short(dosDate)
                .int(w.crc.toInt()).int(w.size.toInt()).int(w.size.toInt()).short(w.name.size).short(0).short(0)
                .short(0).short(0).int(0).int(w.offset.toInt()).bytes(w.name)
            put(entry.array)
        }
        val size = position - start
        if (written.size > 0xFFFF || position > MAX_32) throw ZipFormatException("too big")
        put(Bytes(22).int(0x06054b50).short(0).short(0).short(written.size).short(written.size).int(size.toInt()).int(start.toInt()).short(0).array)
    }

    private class Bytes(n: Int) {
        val array = ByteArray(n)
        private var at = 0
        fun short(v: Int) = apply { array[at++] = v.toByte(); array[at++] = (v shr 8).toByte() }
        fun int(v: Int) = apply { short(v and 0xFFFF); short((v ushr 16) and 0xFFFF) }
        fun bytes(b: ByteArray) = apply { b.copyInto(array, at); at += b.size }
    }

    companion object {
        private const val FLAG_UTF8 = 0x0800
        private const val MAX_32 = 0xFFFFFFFEL

        /** MS-DOS time and date of the wall clock ([hour], [minute], [second]; [year] from 1980). */
        fun dos(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Pair<Int, Int> =
            ((hour shl 11) or (minute shl 5) or (second / 2)) to (((year - 1980).coerceIn(0, 127) shl 9) or (month shl 5) or day)
    }
}

/**
 * Reads a zip of [size] bytes through [read] (bytes at an offset): its central directory, then each entry's content.
 * Throws [ZipFormatException] for a file that isn't a whole zip.
 */
class ZipReader(private val size: Long, private val read: (offset: Long, length: Int) -> ByteArray) {
    val entries: List<ZipEntry> = directory()

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

    private fun bytes(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > size) throw ZipFormatException("incomplete")
        val b = read(offset, length)
        if (b.size != length) throw ZipFormatException("incomplete")
        return b
    }

    private fun directory(): List<ZipEntry> {
        if (size < 22) throw ZipFormatException("not a zip")
        val tailLength = minOf(size, 22L + 0xFFFF).toInt()
        val tail = bytes(size - tailLength, tailLength)
        var at = tail.size - 22
        while (at >= 0 && u32(tail, at) != 0x06054b50L) at--
        // A zip cut short (it starts as one, its end is missing) is incomplete; anything else isn't a zip.
        if (at < 0) throw ZipFormatException(if (u32(bytes(0, 4), 0) == 0x04034b50L) "incomplete" else "not a zip")
        val count = u16(tail, at + 10)
        val dirSize = u32(tail, at + 12)
        val dirOffset = u32(tail, at + 16)
        if (count == 0xFFFF || dirSize == 0xFFFFFFFFL || dirOffset == 0xFFFFFFFFL) throw ZipFormatException("zip64")
        if (dirOffset + dirSize > size) throw ZipFormatException("incomplete")
        val dir = bytes(dirOffset, dirSize.toInt())
        val out = ArrayList<ZipEntry>(count)
        var p = 0
        repeat(count) {
            if (p + 46 > dir.size || u32(dir, p) != 0x02014b50L) throw ZipFormatException("damaged directory")
            val method = u16(dir, p + 10)
            val crc = u32(dir, p + 16)
            val csize = u32(dir, p + 20)
            val usize = u32(dir, p + 24)
            val nameLen = u16(dir, p + 28)
            val extraLen = u16(dir, p + 30)
            val commentLen = u16(dir, p + 32)
            val local = u32(dir, p + 42)
            if (csize == 0xFFFFFFFFL || usize == 0xFFFFFFFFL || local == 0xFFFFFFFFL) throw ZipFormatException("zip64")
            if (p + 46 + nameLen > dir.size) throw ZipFormatException("damaged directory")
            val name = dir.copyOfRange(p + 46, p + 46 + nameLen).decodeToString()
            out += ZipEntry(name, method, crc, csize, usize, local)
            p += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    /**
     * Streams [entry]'s content to [sink] in pieces, at most [limit] bytes, checking its CRC-32 and size at the end;
     * [checkActive] runs between pieces (a stopped restore stops here).
     */
    fun content(entry: ZipEntry, limit: Long, checkActive: () -> Unit = {}, sink: (ByteArray, Int) -> Unit) {
        if (entry.size > limit) throw ZipFormatException("too big")
        val header = bytes(entry.localHeader, 30)
        if (u32(header, 0) != 0x04034b50L) throw ZipFormatException("damaged entry")
        var at = entry.localHeader + 30 + u16(header, 26) + u16(header, 28)
        val end = at + entry.compressedSize
        if (end > size) throw ZipFormatException("incomplete")
        val crc = Crc32()
        var total = 0L
        val counted: (ByteArray, Int) -> Unit = { b, n ->
            total += n
            if (total > limit || total > entry.size) throw ZipFormatException("too big")
            crc.update(b, 0, n)
            sink(b, n)
        }
        when (entry.method) {
            0 -> while (at < end) {
                checkActive()
                val n = minOf(CHUNK.toLong(), end - at).toInt()
                val piece = bytes(at, n)
                at += n
                counted(piece, n)
            }
            8 -> {
                var buffer = ByteArray(0)
                var index = 0
                Inflater({
                    if (index >= buffer.size) {
                        if (at >= end) -1 else {
                            checkActive()
                            val n = minOf(CHUNK.toLong(), end - at).toInt()
                            buffer = bytes(at, n)
                            at += n
                            index = 0
                            buffer[index++].toInt() and 0xFF
                        }
                    } else buffer[index++].toInt() and 0xFF
                }, counted).run()
            }
            else -> throw ZipFormatException("unknown method")
        }
        if (total != entry.size || crc.value != entry.crc) throw ZipFormatException("damaged entry")
    }

    /** All of a small entry. */
    fun bytesOf(entry: ZipEntry, limit: Long, checkActive: () -> Unit = {}): ByteArray {
        val parts = ArrayList<ByteArray>()
        content(entry, limit, checkActive) { b, n -> parts += b.copyOf(n) }
        val all = ByteArray(parts.sumOf { it.size })
        var p = 0
        parts.forEach { it.copyInto(all, p); p += it.size }
        return all
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
