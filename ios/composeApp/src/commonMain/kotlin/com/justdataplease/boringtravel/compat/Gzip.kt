package com.justdataplease.boringtravel.compat

import com.justdataplease.boringtravel.backup.Crc32
import com.justdataplease.boringtravel.backup.Inflater
import com.justdataplease.boringtravel.io.IOException

/*
 * java.util.zip's GZIPOutputStream and GZIPInputStream as the Android app's shared trips use them (together/ShareCodec.kt,
 * read in since round 26: a member's trip.btshare is gzip JSON), in plain Kotlin. Writing deflates with LZ77 and the
 * fixed Huffman codes of RFC 1951 (one block): any gzip reader opens it, Android's GZIPInputStream included, and a trip's
 * JSON shrinks about as much as Java's level 6 makes it (within a quarter). Reading is backup/Zip.kt's Inflater (Mark
 * Adler's puff), the header and the CRC-32 and size at the end checked as Java checks them.
 */

/** java.util.zip.GZIPOutputStream: what is written is kept, and deflated into [out] as one gzip member on [close]. */
class GZIPOutputStream(private val out: OutputStream) : OutputStream() {
    private val buffer = ByteArrayOutputStream(64 * 1024)
    private var closed = false

    override fun write(b: Int) = buffer.write(b)

    override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)

    override fun close() {
        if (closed) return
        closed = true
        out.write(Gzip.compress(buffer.toByteArray()))
        out.close()
    }
}

/**
 * java.util.zip.GZIPInputStream over [input]: the whole member is read and inflated on the first read (shared trips are
 * small: Android reads at most 8 MB of gzip), at most [Gzip.MAX_INFLATED] bytes of it; beyond that the stream ends there
 * (the reader's own limit then says the file is too big, as on Android). A damaged stream, a wrong CRC-32 or size, or
 * something that isn't gzip throws [IOException].
 */
class GZIPInputStream(private val input: InputStream) : InputStream() {
    private var data: ByteArray? = null
    private var at = 0

    private fun ready(): ByteArray = data ?: Gzip.decompress(input.readBytes()).also { data = it }

    override fun read(): Int {
        val d = ready()
        return if (at < d.size) d[at++].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val d = ready()
        if (at >= d.size) return -1
        val n = minOf(len, d.size - at)
        d.copyInto(b, off, at, at + n)
        at += n
        return n
    }

    override fun close() = input.close()
}

/** A gzip stream that can't be read (java.util.zip.ZipException's place). */
class GzipException(message: String) : IOException(message)

/** Gzip in plain Kotlin: [compress] writes one member, [decompress] reads one. */
object Gzip {
    /** What [decompress] inflates at most: more than any shared trip may hold (ShareCodec.MAX_JSON_BYTES is 24 MB). */
    const val MAX_INFLATED = 32 * 1024 * 1024

    /** [data] as one gzip member: the header Java writes (no name, no time), DEFLATE, then its CRC-32 and size. */
    fun compress(data: ByteArray): ByteArray {
        val bits = BitWriter(data.size / 3 + 64)
        // Java's header: ID1 ID2, deflate, no flags, no time, no extra flags, OS 0 (as java.util.zip writes it).
        byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0).forEach { bits.byte(it.toInt() and 0xFF) }
        Deflate.fixed(data, bits)
        bits.align()
        val crc = Crc32.of(data)
        repeat(4) { bits.byte(((crc ushr (8 * it)) and 0xFF).toInt()) }
        repeat(4) { bits.byte((data.size ushr (8 * it)) and 0xFF) }
        return bits.bytes()
    }

    /** One gzip member's content; throws [GzipException] when [bytes] aren't one, or are damaged. */
    fun decompress(bytes: ByteArray): ByteArray {
        if (bytes.size < 18 || (bytes[0].toInt() and 0xFF) != 0x1f || (bytes[1].toInt() and 0xFF) != 0x8b) throw GzipException("Not in GZIP format")
        if (bytes[2].toInt() != 8) throw GzipException("Unsupported compression method")
        val flags = bytes[3].toInt() and 0xFF
        var at = 10
        fun need(n: Int) {
            if (at + n > bytes.size) throw GzipException("Unexpected end of ZLIB input stream")
        }
        if (flags and 4 != 0) {
            need(2)
            val extra = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
            at += 2
            need(extra)
            at += extra
        }
        // A file name and a comment, each ending with a zero.
        for (flag in listOf(8, 16)) if (flags and flag != 0) {
            while (true) {
                need(1)
                if (bytes[at++].toInt() == 0) break
            }
        }
        if (flags and 2 != 0) {
            need(2)
            at += 2
        }
        val out = ByteArrayOutputStream(maxOf(64, bytes.size * 4))
        var over = false
        var read = at
        try {
            Inflater({ if (read < bytes.size) bytes[read++].toInt() and 0xFF else throw GzipException("Unexpected end of ZLIB input stream") }) { b, n ->
                val room = MAX_INFLATED - out.size()
                if (n > room) {
                    out.write(b, 0, room.coerceAtLeast(0))
                    throw TooMuch()
                }
                out.write(b, 0, n)
            }.run()
        } catch (_: TooMuch) {
            over = true
        } catch (e: IOException) {
            throw GzipException(e.message ?: "Invalid stored block lengths")
        } catch (e: RuntimeException) {
            throw GzipException(e.message ?: "invalid code lengths set")
        }
        val content = out.toByteArray()
        // Past the limit the rest isn't read: the reader's own limit says it is too big.
        if (over) return content
        // The trailer: whatever whole bytes the inflater left, then CRC-32 and the size (mod 2^32), little-endian.
        if (read + 8 > bytes.size) throw GzipException("Unexpected end of ZLIB input stream")
        fun le(i: Int): Long = (0 until 4).fold(0L) { v, k -> v or ((bytes[i + k].toLong() and 0xFF) shl (8 * k)) }
        if (le(read) != Crc32.of(content)) throw GzipException("Corrupt GZIP trailer")
        if (le(read + 4) != (content.size.toLong() and 0xFFFFFFFFL)) throw GzipException("Corrupt GZIP trailer")
        return content
    }

    private class TooMuch : RuntimeException()
}

/** Bits written least significant first into bytes, as DEFLATE packs them. */
internal class BitWriter(capacity: Int) {
    private var buf = ByteArray(capacity.coerceAtLeast(64))
    private var size = 0
    private var acc = 0L
    private var count = 0

    private fun room(n: Int) {
        if (size + n <= buf.size) return
        var s = buf.size * 2
        while (s < size + n) s *= 2
        buf = buf.copyOf(s)
    }

    /** The lowest [n] bits of [value] (n ≤ 24). */
    fun bits(value: Int, n: Int) {
        acc = acc or ((value.toLong() and ((1L shl n) - 1)) shl count)
        count += n
        while (count >= 8) {
            room(1)
            buf[size++] = (acc and 0xFF).toByte()
            acc = acc ushr 8
            count -= 8
        }
    }

    /** A Huffman code of [n] bits, written from its most significant bit (RFC 1951 3.1.1). */
    fun code(code: Int, n: Int) {
        var r = 0
        var c = code
        repeat(n) {
            r = (r shl 1) or (c and 1)
            c = c ushr 1
        }
        bits(r, n)
    }

    /** Pads to a whole byte. */
    fun align() {
        if (count > 0) bits(0, 8 - count)
    }

    /** A whole byte (after [align]). */
    fun byte(v: Int) = bits(v and 0xFF, 8)

    fun bytes(): ByteArray = buf.copyOf(size)
}

/** DEFLATE with the fixed Huffman codes: LZ77 over a 32 KB window, greedy, hash chains of three-byte prefixes. */
internal object Deflate {
    private const val WINDOW = 32 * 1024
    private const val MIN_MATCH = 3
    private const val MAX_MATCH = 258
    private const val HASH_BITS = 15
    private const val MAX_CHAIN = 64

    private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
        8193, 12289, 16385, 24577,
    )
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)

    /** [data] as one final block of fixed Huffman codes. */
    fun fixed(data: ByteArray, out: BitWriter) {
        out.bits(1, 1) // BFINAL
        out.bits(1, 2) // BTYPE 01: fixed Huffman codes
        val head = IntArray(1 shl HASH_BITS) { -1 }
        val prev = IntArray(WINDOW)
        fun hash(i: Int): Int {
            val h = ((data[i].toInt() and 0xFF) shl 10) xor ((data[i + 1].toInt() and 0xFF) shl 5) xor (data[i + 2].toInt() and 0xFF)
            return (h * 0x9E3779B1.toInt() ushr (32 - HASH_BITS)) and ((1 shl HASH_BITS) - 1)
        }
        fun insert(i: Int) {
            if (i + MIN_MATCH > data.size) return
            val h = hash(i)
            prev[i and (WINDOW - 1)] = head[h]
            head[h] = i
        }
        var i = 0
        while (i < data.size) {
            var bestLen = 0
            var bestDist = 0
            if (i + MIN_MATCH <= data.size) {
                var cand = head[hash(i)]
                var chain = MAX_CHAIN
                val limit = minOf(MAX_MATCH, data.size - i)
                while (cand >= 0 && i - cand <= WINDOW && chain-- > 0) {
                    if (data[cand + bestLen] == data[i + bestLen] || bestLen == 0) {
                        var n = 0
                        while (n < limit && data[cand + n] == data[i + n]) n++
                        if (n > bestLen) {
                            bestLen = n
                            bestDist = i - cand
                            if (n == limit) break
                        }
                    }
                    val next = prev[cand and (WINDOW - 1)]
                    if (next >= cand) break
                    cand = next
                }
            }
            if (bestLen >= MIN_MATCH) {
                length(bestLen, out)
                distance(bestDist, out)
                for (k in 0 until bestLen) insert(i + k)
                i += bestLen
            } else {
                literal(data[i].toInt() and 0xFF, out)
                insert(i)
                i++
            }
        }
        literal(256, out) // end of block
    }

    /** A literal byte or the end of the block (256) in the fixed code. */
    private fun literal(v: Int, out: BitWriter) = when {
        v < 144 -> out.code(0x30 + v, 8)
        v < 256 -> out.code(0x190 + (v - 144), 9)
        v < 280 -> out.code(v - 256, 7)
        else -> out.code(0xC0 + (v - 280), 8)
    }

    private fun length(len: Int, out: BitWriter) {
        var k = LENGTH_BASE.size - 1
        while (LENGTH_BASE[k] > len) k--
        literal(257 + k, out)
        if (LENGTH_EXTRA[k] > 0) out.bits(len - LENGTH_BASE[k], LENGTH_EXTRA[k])
    }

    private fun distance(dist: Int, out: BitWriter) {
        var k = DIST_BASE.size - 1
        while (DIST_BASE[k] > dist) k--
        out.code(k, 5)
        if (DIST_EXTRA[k] > 0) out.bits(dist - DIST_BASE[k], DIST_EXTRA[k])
    }
}
