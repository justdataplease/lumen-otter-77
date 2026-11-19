package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.time.CivilDay
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.startOfDayMs

/**
 * What a JPEG photo says about itself in its EXIF block, as far as the Android app reads it on import (MediaRepository
 * with ExifInterface, PhotoTime): when it was taken (DateTimeOriginal with its offset, else DateTime) and where (the GPS
 * position). Plain Kotlin over the file's bytes. A photo without EXIF, or with a damaged one, simply has neither.
 */
object Exif {
    data class Tags(
        val dateTimeOriginal: String? = null,
        val offsetTimeOriginal: String? = null,
        val dateTime: String? = null,
        val offsetTime: String? = null,
        val lat: Double? = null,
        val lng: Double? = null,
    ) {
        /** When it was taken, as epoch ms (PhotoTime.parse on the original time, else the file's time); null when neither reads. */
        fun takenAt(): Long? = parseTime(dateTimeOriginal, offsetTimeOriginal) ?: parseTime(dateTime, offsetTime)
    }

    /** The tags of [jpeg]; empty tags when it is no JPEG or has no EXIF block. */
    fun read(jpeg: ByteArray): Tags = runCatching { readOrNull(jpeg) }.getOrNull() ?: Tags()

    private fun readOrNull(b: ByteArray): Tags? {
        if (b.size < 4 || b.u8(0) != 0xFF || b.u8(1) != 0xD8) return null
        var at = 2
        while (at + 4 <= b.size) {
            if (b.u8(at) != 0xFF) return null
            val marker = b.u8(at + 1)
            if (marker == 0xD9 || marker == 0xDA) return null // end of image, or the picture itself: no EXIF before it
            val length = (b.u8(at + 2) shl 8) or b.u8(at + 3)
            if (length < 2) return null
            if (marker == 0xE1 && length >= 8 && b.ascii(at + 4, 6) == "Exif\u0000\u0000") return tiff(b, at + 10, at + 2 + length)
            at += 2 + length
        }
        return null
    }

    /** The TIFF structure inside the EXIF block: its header at [base], the block ending at [end]. */
    private fun tiff(b: ByteArray, base: Int, end: Int): Tags? {
        val little = when (b.ascii(base, 2)) {
            "II" -> true
            "MM" -> false
            else -> return null
        }
        val r = Reader(b, base, minOf(end, b.size), little)
        val ifd0 = r.entries(r.u32(4).toInt())
        val exif = ifd0[0x8769]?.let { r.entries(r.value32(it).toInt()) }.orEmpty()
        val gps = ifd0[0x8825]?.let { r.entries(r.value32(it).toInt()) }.orEmpty()
        fun position(ref: Int, value: Int, negative: Char): Double? {
            val parts = gps[value]?.let(r::rationals)?.takeIf { it.size >= 3 } ?: return null
            val v = parts[0] + parts[1] / 60.0 + parts[2] / 3600.0
            val sign = gps[ref]?.let(r::ascii)?.trim()?.firstOrNull()
            return if (sign == negative) -v else v
        }
        val lat = position(1, 2, 'S')
        val lng = position(3, 4, 'W')
        // A damaged GPS tag can read as no number at all: such a photo simply has no place (as on Android).
        val placed = lat != null && lng != null && lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
        return Tags(
            dateTimeOriginal = exif[0x9003]?.let(r::ascii),
            offsetTimeOriginal = exif[0x9011]?.let(r::ascii),
            dateTime = ifd0[0x0132]?.let(r::ascii),
            offsetTime = exif[0x9010]?.let(r::ascii),
            lat = lat.takeIf { placed },
            lng = lng.takeIf { placed },
        )
    }

    /** One IFD entry: its type, how many values, and where the value (or its offset) sits. */
    private class Entry(val type: Int, val count: Int, val at: Int)

    private class Reader(val b: ByteArray, val base: Int, val end: Int, val little: Boolean) {
        fun u16(off: Int): Int {
            val i = base + off
            require(i >= 0 && i + 2 <= end)
            return if (little) b.u8(i) or (b.u8(i + 1) shl 8) else (b.u8(i) shl 8) or b.u8(i + 1)
        }

        fun u32(off: Int): Long {
            val i = base + off
            require(i >= 0 && i + 4 <= end)
            val v = if (little) b.u8(i).toLong() or (b.u8(i + 1).toLong() shl 8) or (b.u8(i + 2).toLong() shl 16) or (b.u8(i + 3).toLong() shl 24)
            else (b.u8(i).toLong() shl 24) or (b.u8(i + 1).toLong() shl 16) or (b.u8(i + 2).toLong() shl 8) or b.u8(i + 3).toLong()
            return v
        }

        /** The entries of the IFD at [off], by tag. */
        fun entries(off: Int): Map<Int, Entry> {
            val n = u16(off)
            require(n in 0..512)
            return (0 until n).associate { k ->
                val e = off + 2 + 12 * k
                u16(e) to Entry(u16(e + 2), u32(e + 4).toInt(), e + 8)
            }
        }

        /** Where an entry's data starts: in the entry itself when it fits in four bytes, else at its offset. */
        private fun data(e: Entry, bytes: Int): Int = if (bytes <= 4) e.at else u32(e.at).toInt()

        fun value32(e: Entry): Long = if (e.type == 3) u16(e.at).toLong() else u32(e.at)

        fun ascii(e: Entry): String? {
            if (e.type != 2 || e.count <= 0 || e.count > 256) return null
            val start = data(e, e.count)
            require(start >= 0 && base + start + e.count <= end)
            return b.ascii(base + start, e.count).substringBefore('\u0000').trim().ifEmpty { null }
        }

        fun rationals(e: Entry): List<Double> {
            if (e.type != 5 || e.count <= 0 || e.count > 8) return emptyList()
            val start = data(e, e.count * 8)
            return (0 until e.count).map { k ->
                val num = u32(start + 8 * k)
                val den = u32(start + 8 * k + 4)
                if (den == 0L) Double.NaN else num.toDouble() / den.toDouble()
            }
        }
    }

    private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

    private fun ByteArray.ascii(at: Int, n: Int): String = CharArray(n) { (this[at + it].toInt() and 0xFF).toChar() }.concatToString()

    private val TIME = Regex("""^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})$""")
    private val OFFSET = Regex("""^([+-])(\d{2}):?(\d{2})$""")

    /**
     * "2026:11:18 13:10:05" with its offset ("+02:00") as epoch ms; without an offset, in the phone's own zone. Null for
     * a bad date, or a bad offset (as the Android app's PhotoTime.parse: never invent a date for a bad tag).
     */
    fun parseTime(value: String?, offset: String?): Long? {
        val m = TIME.matchEntire(value?.trim() ?: return null) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        if (!CivilDay.isValid(y.toInt(), mo.toInt(), d.toInt()) || h.toInt() > 23 || mi.toInt() > 59 || s.toInt() > 59) return null
        val seconds = h.toInt() * 3600L + mi.toInt() * 60L + s.toInt()
        val o = offset?.trim()?.takeIf { it.isNotEmpty() }
        if (o == null) return startOfDayMs(localDate(y.toInt(), mo.toInt(), d.toInt()), null) + seconds * 1000
        val om = if (o == "Z") null else (OFFSET.matchEntire(o) ?: return null)
        val shift = om?.let { (if (it.groupValues[1] == "-") -1 else 1) * (it.groupValues[2].toInt() * 3600L + it.groupValues[3].toInt() * 60L) } ?: 0L
        if (shift !in -18 * 3600L..18 * 3600L) return null
        val day = CivilDay(y.toInt(), mo.toInt(), d.toInt()).epochDay
        return (day * 86_400L + seconds - shift) * 1000
    }
}
