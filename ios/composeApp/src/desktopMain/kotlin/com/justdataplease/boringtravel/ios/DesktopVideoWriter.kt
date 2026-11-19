package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.clip.music.MixedSound
import com.justdataplease.boringtravel.compat.graphics.Bitmap
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.max

/*
 * The trip video's writer on the desktop (round 20), where the iPhone's AVAssetWriter can't run: it records what the
 * export hands it (each picture's number and a checksum of its pixels, the parts, the sound's samples), so a test can
 * check a whole export end to end, and, with [DesktopVideoWriter.realFile], it writes a video the desktop can play: a
 * Motion-JPEG AVI with the mixed sound as 16-bit PCM (what the JVM can write without a codec library). The pictures and
 * the sound in it are the iPhone's, frame for frame and sample for sample; only their encoding differs (H.264 and AAC
 * there).
 */

/** What one picture part was handed: the frames' numbers in order, and a checksum of each frame's pixels. */
class RecordedPart(val path: String, val spec: VideoSpec) {
    val frames = mutableListOf<Int>()
    val checksums = mutableListOf<Long>()
    var finished = false
    var cancelled = false
}

/** What a join was handed: the parts in order, and the sound as rendered (its frames, peak and checksum). */
class RecordedJoin(val path: String, val parts: List<PicturePart>, val sound: SoundRecord?)

/** A mixed sound as the writer took it. */
data class SoundRecord(val sampleRate: Int, val channels: Int, val frames: Long, val peak: Int, val checksum: Long, val loudFrames: Long)

/**
 * The desktop's [VideoWriter]. [realFile]: the parts keep each picture as a JPEG and the join writes a playable AVI;
 * otherwise a part and the finished file hold only what was recorded (fast, small: the tests). [free]: the bytes it says
 * are free (null: it can't say). [failAppend] lets a test break a part off at a frame (the app leaving the screen).
 */
class DesktopVideoWriter(
    private val files: MediaFiles,
    private val realFile: Boolean = false,
    var free: Long? = null,
    var failAppend: (part: Int, index: Int) -> Boolean = { _, _ -> false },
    var failJoin: (attempt: Int, withSound: Boolean) -> Boolean = { _, _ -> false },
    /** Checksums of every frame's pixels (slower: a frame's pixels are read back). */
    private val checksums: Boolean = true,
) : VideoWriter {
    val parts = mutableListOf<RecordedPart>()
    val joins = mutableListOf<RecordedJoin>()
    private var joinAttempts = 0

    override fun openPicture(path: String, spec: VideoSpec): PictureFile {
        files.delete(path)
        val record = RecordedPart(path, spec).also { synchronized(parts) { parts += it } }
        val number = parts.size
        return object : PictureFile {
            private var bgra: ByteArray? = null

            override suspend fun append(frame: Bitmap, index: Int) {
                check(frame.width == spec.width && frame.height == spec.height) { "A frame of the wrong size" }
                if (failAppend(number, index)) throw VideoWriteFailed("The encoder stopped (test)")
                record.frames += index
                if (checksums) {
                    val bytes = frame.bgra(bgra)?.also { bgra = it }
                    record.checksums += bytes?.let { CRC32().apply { update(it) }.value } ?: 0L
                }
                if (realFile) {
                    val jpeg = frame.jpeg(JPEG_QUALITY) ?: throw VideoWriteFailed("The frame couldn't be encoded")
                    if (!files.append(path, int(jpeg.size) + jpeg)) throw VideoWriteFailed("The disk is full")
                } else if (!files.append(path, int(index))) {
                    throw VideoWriteFailed("The disk is full")
                }
            }

            override suspend fun finish(frames: Int): Boolean {
                if (frames == 0) files.write(path, ByteArray(0))
                record.finished = true
                return true
            }

            override fun cancel() {
                record.cancelled = true
                files.delete(path)
            }
        }
    }

    override suspend fun join(path: String, spec: VideoSpec, parts: List<PicturePart>, sound: MixedSound?, progress: (Float) -> Unit, stillWanted: () -> Unit) {
        joinAttempts++
        if (failJoin(joinAttempts, sound != null)) throw VideoWriteFailed("The writer stopped (test)")
        files.delete(path)
        val record = sound?.let { record(it, stillWanted) }
        joins += RecordedJoin(path, parts, record)
        if (realFile) {
            Avi.write(files, path, spec, parts, sound, progress, stillWanted)
        } else {
            val text = buildString {
                appendLine("parts=" + parts.joinToString(",") { "${it.firstFrame}+${it.frames}" })
                record?.let { appendLine("sound=${it.sampleRate}x${it.channels}:${it.frames}") }
            }
            if (!files.write(path, text.encodeToByteArray())) throw VideoWriteFailed("The disk is full")
            progress(1f)
        }
    }

    override fun freeBytes(): Long? = free

    /** The whole sound rendered once, as the writer would encode it, summed up. */
    private fun record(sound: MixedSound, stillWanted: () -> Unit): SoundRecord {
        val chunk = 4096
        val out = ShortArray(chunk * sound.channels)
        val crc = CRC32()
        var peak = 0
        var loud = 0L
        var at = 0L
        val bytes = ByteArray(chunk * sound.channels * 2)
        while (at < sound.totalFrames) {
            stillWanted()
            val n = minOf(chunk.toLong(), sound.totalFrames - at).toInt()
            sound.render(at, n, out)
            for (i in 0 until n * sound.channels) {
                val v = out[i].toInt()
                peak = max(peak, abs(v))
                bytes[2 * i] = v.toByte()
                bytes[2 * i + 1] = (v shr 8).toByte()
            }
            for (f in 0 until n) if ((0 until sound.channels).any { c -> abs(out[f * sound.channels + c].toInt()) > LOUD }) loud++
            crc.update(bytes, 0, n * sound.channels * 2)
            at += n
        }
        return SoundRecord(sound.sampleRate, sound.channels, sound.totalFrames, peak, crc.value, loud)
    }

    companion object {
        const val JPEG_QUALITY = 88

        /** A sample this loud or louder counts as sound (not silence) in [SoundRecord.loudFrames]. */
        const val LOUD = 64

        internal fun int(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    }
}

/**
 * A Motion-JPEG AVI with 16-bit PCM sound (RIFF, the format the JVM can write without a codec): the parts' JPEGs as
 * its video chunks in order, the sound interleaved a frame's worth at a time, and its index.
 */
internal object Avi {
    private fun fourcc(s: String) = s.encodeToByteArray()
    private fun i32(v: Int) = DesktopVideoWriter.int(v)
    private fun i32(v: Long) = DesktopVideoWriter.int(v.toInt())
    private fun i16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    private class Entry(val id: String, val offset: Long, val size: Int)

    fun write(files: MediaFiles, path: String, spec: VideoSpec, parts: List<PicturePart>, sound: MixedSound?, progress: (Float) -> Unit, stillWanted: () -> Unit) {
        val movi = "$path.movi"
        files.delete(movi)
        val index = ArrayList<Entry>()
        var at = 4L // offsets count from the "movi" word
        var biggest = 0
        fun chunk(id: String, data: ByteArray) {
            val padded = if (data.size % 2 == 1) data + 0 else data
            if (!files.append(movi, fourcc(id) + i32(data.size) + padded)) throw VideoWriteFailed("The disk is full")
            index += Entry(id, at, data.size)
            at += 8 + padded.size
            biggest = max(biggest, data.size)
        }
        val frames = parts.sumOf { it.frames }
        var written = 0
        var soundAt = 0L
        val soundBuffer = sound?.let { ShortArray((it.sampleRate / spec.fps + 2) * it.channels) }
        for (part in parts) {
            val bytes = files.read(part.path) ?: throw VideoWriteFailed("A part is missing")
            var p = 0
            while (p + 4 <= bytes.size) {
                stillWanted()
                val n = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[p + 2].toInt() and 0xFF) shl 16) or ((bytes[p + 3].toInt() and 0xFF) shl 24)
                chunk("00dc", bytes.copyOfRange(p + 4, p + 4 + n))
                p += 4 + n
                written++
                // The sound up to this frame's end, as the iPhone's writer interleaves it.
                if (sound != null && soundBuffer != null) {
                    val until = minOf(sound.totalFrames, written.toLong() * sound.sampleRate / spec.fps)
                    val count = (until - soundAt).toInt()
                    if (count > 0) {
                        sound.render(soundAt, count, soundBuffer)
                        val pcm = ByteArray(count * sound.channels * 2)
                        for (i in 0 until count * sound.channels) {
                            pcm[2 * i] = soundBuffer[i].toByte()
                            pcm[2 * i + 1] = (soundBuffer[i].toInt() shr 8).toByte()
                        }
                        chunk("01wb", pcm)
                        soundAt = until
                    }
                    progress(written.toFloat() / frames.coerceAtLeast(1))
                }
            }
        }
        val streams = if (sound != null) 2 else 1
        val usPerFrame = 1_000_000 / spec.fps
        val avih = i32(usPerFrame) + i32(spec.bitRate / 8) + i32(0) + i32(0x10) + i32(written) + i32(0) + i32(streams) +
            i32(biggest) + i32(spec.width) + i32(spec.height) + ByteArray(16)
        val videoHeader = fourcc("vids") + fourcc("MJPG") + i32(0) + i16(0) + i16(0) + i32(0) + i32(1) + i32(spec.fps) + i32(0) +
            i32(written) + i32(biggest) + i32(-1) + i32(0) + i16(0) + i16(0) + i16(spec.width) + i16(spec.height)
        val videoFormat = i32(40) + i32(spec.width) + i32(spec.height) + i16(1) + i16(24) + fourcc("MJPG") + i32(spec.width * spec.height * 3) +
            i32(0) + i32(0) + i32(0) + i32(0)
        fun list(type: String, body: ByteArray) = fourcc("LIST") + i32(body.size + 4) + fourcc(type) + body
        fun ck(id: String, body: ByteArray) = fourcc(id) + i32(body.size) + body
        var strls = list("strl", ck("strh", videoHeader) + ck("strf", videoFormat))
        if (sound != null) {
            val align = sound.channels * 2
            val audioHeader = fourcc("auds") + i32(0) + i32(0) + i16(0) + i16(0) + i32(0) + i32(align) + i32(sound.sampleRate * align) + i32(0) +
                i32(soundAt) + i32(sound.sampleRate * align) + i32(-1) + i32(align) + i16(0) + i16(0) + i16(0) + i16(0)
            val audioFormat = i16(1) + i16(sound.channels) + i32(sound.sampleRate) + i32(sound.sampleRate * align) + i16(align) + i16(16) + i16(0)
            strls += list("strl", ck("strh", audioHeader) + ck("strf", audioFormat))
        }
        val hdrl = list("hdrl", ck("avih", avih) + strls)
        val moviSize = (files.size(movi) ?: 0L) + 4
        val idx = index.fold(ByteArray(0)) { acc, e -> acc + fourcc(e.id) + i32(0x10) + i32(e.offset) + i32(e.size) }
        val riffSize = 4 + hdrl.size + 8 + moviSize + 8 + idx.size
        if (!files.write(path, fourcc("RIFF") + i32(riffSize) + fourcc("AVI ") + hdrl + fourcc("LIST") + i32(moviSize) + fourcc("movi"))) {
            throw VideoWriteFailed("The disk is full")
        }
        var ok = true
        files.readChunks(movi) { buffer, n -> if (ok) ok = files.append(path, buffer, n) }
        files.delete(movi)
        if (!ok || !files.append(path, fourcc("idx1") + i32(idx.size) + idx)) {
            files.delete(path)
            throw VideoWriteFailed("The disk is full")
        }
        progress(1f)
    }
}
