package com.justdataplease.boringtravel.clip.music

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.justdataplease.boringtravel.clip.ClipFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Puts the sound under a finished, silent clip: the video samples are copied as they are (MediaExtractor →
 * MediaMuxer) next to a new AAC track made from the chosen music (decoded, cut or looped to the video's length,
 * faded, encoded again; the sums are in [MusicMix]) with any [Sound]s laid over it (a phone video's own sound, kept
 * quietly). Written to a temporary file and moved over the clip only when complete, so a failure leaves the silent
 * clip as it was. The sound needs only the clip's length, so [prepare] can make it while the frames render and
 * [attach] puts it under the finished picture; [add] does both for a finished video.
 */
object ClipMusic {
    private const val TAG = "ClipMusic"
    private const val BIT_RATE = 128_000
    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE = 500
    /** Without music, the sounds are mixed at this rate in stereo. */
    private const val PLAIN_RATE = 44_100

    /**
     * A sound laid over the clip: [file]'s sound from [fromUs] for [lengthUs], placed [atUs] into the clip, × [gain],
     * faded in and out over [fadeUs]; [duck]: the music dips under it (a voice line).
     */
    data class Sound(
        val file: File, val atUs: Long, val fromUs: Long, val lengthUs: Long, val gain: Float, val fadeUs: Long = 150_000L,
        val duck: Boolean = false,
    )

    /** A voice line on top: full level, short ramps, the music ducked under it. */
    fun voice(file: File, atUs: Long, lengthUs: Long) = Sound(file, atUs, 0L, lengthUs, VOICE_GAIN, fadeUs = 20_000L, duck = true)

    const val VOICE_GAIN = 1.0f

    /** A phone video's sound kept under the music: quietly; a little louder with no music. */
    const val VIDEO_GAIN_UNDER_MUSIC = 0.45f
    const val VIDEO_GAIN_ALONE = 0.9f

    /**
     * Adds [trackId] and [sounds] to [video] in place: [prepare] for the video's own length, then [attach]. True when
     * the video has them, or there is nothing to add ([trackId] null or unknown and no sounds); false when it failed
     * and the video stayed silent. Cancelling the caller stops it the same way. [progress] gets 0–1 as it goes.
     */
    suspend fun add(
        context: Context, video: File, trackId: String?, sounds: List<Sound> = emptyList(), startS: Double = 0.0,
        progress: (Float) -> Unit = {},
    ): Boolean {
        val track = MusicCatalog.byId(trackId)
        if (track == null && sounds.isEmpty()) return true
        val audio = try {
            val durationUs = withContext(Dispatchers.Default) { videoDuration(video) }
            prepare(context, durationUs, track, startUs(track, startS), sounds, progress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't make ${track?.id ?: "the sound"} for the clip", e)
            return false
        }
        return audio == null || attach(video, audio, progress)
    }

    /** Where in [track] a clip's music starts, [startS] seconds in as the editor chose it (kept within the track); 0 without one. */
    fun startUs(track: MusicTrack?, startS: Double): Long = track?.let { (MusicCatalog.startOf(it, startS) * 1_000_000).toLong() } ?: 0L

    /**
     * The sound for a clip of [durationUs], ready to [attach]: [track] from [startUs] (it loops back there), cut or
     * looped to the length and faded, with [sounds] laid over it, encoded to AAC and kept in memory (about 1 MB a
     * minute). It needs only the clip's length, so it can be made while the frames render. Null when there is nothing
     * to add (no track and no sounds). Throws when it fails; cancelling the caller stops it. [progress] gets up to
     * 0.85 (the rest is [attach]'s): 0.15 once the song is decoded, 0.25 with the sounds, then as it encodes.
     */
    suspend fun prepare(
        context: Context, durationUs: Long, track: MusicTrack?, startUs: Long, sounds: List<Sound>, progress: (Float) -> Unit = {},
    ): Encoded? {
        if (track == null && sounds.isEmpty()) return null
        check(durationUs > 0) { "The clip has no length" }
        return withContext(Dispatchers.Default) { mixAndEncode(context, durationUs, track, startUs, sounds, progress) { ensureActive() } }
    }

    /**
     * Puts [audio] ([prepare]) under [video] in place: the video's samples copied as they are, the sound beside them,
     * written to a temporary file and moved over the video only when complete. True when done; false when it failed
     * and the video stayed as it was. Cancelling the caller stops it the same way. [progress] gets 1 when done.
     */
    suspend fun attach(video: File, audio: Encoded, progress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.Default) {
        var part: File? = null
        try {
            val temporary = ClipFiles.temporary(video).also { part = it }
            mux(video, audio, temporary) { ensureActive() }
            ensureActive()
            ClipFiles.publish(temporary, video)
            progress(1f)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't add the sound to the clip", e)
            false
        } finally {
            part?.delete()
        }
    }

    private class Pcm(val samples: ShortArray, val frames: Int, val sampleRate: Int, val channels: Int)

    /** A clip's sound encoded to AAC ([prepare]), waiting to go under the video ([attach]). */
    class Encoded internal constructor(internal val format: MediaFormat, internal val samples: List<Sample>)

    internal class Sample(val bytes: ByteArray, val timeUs: Long, val flags: Int)

    /** [startUs]: where in [track] the music starts (it loops back there). */
    private fun mixAndEncode(
        context: Context, durationUs: Long, track: MusicTrack?, startUs: Long, sounds: List<Sound>, progress: (Float) -> Unit,
        stillWanted: () -> Unit,
    ): Encoded {
        val pcm = track?.let { t ->
            decode({ ex -> context.assets.openFd(t.asset).use { ex.setDataSource(it) } }, startUs, durationUs, stillWanted)
                ?: error("${t.asset} has no audio")
        }
        progress(0.15f)
        val rate = pcm?.sampleRate ?: PLAIN_RATE
        val channels = pcm?.channels ?: 2
        val plan = pcm?.let { MusicMix.plan(durationUs, it.frames.toLong(), it.sampleRate, midSong = startUs > 0) }
        val total = plan?.totalFrames ?: MusicMix.frames(durationUs, rate)
        val ducked = ArrayList<LongRange>()
        val overlays = sounds.mapNotNull { s ->
            val decoded = runCatching { decode({ ex -> ex.setDataSource(s.file.path) }, s.fromUs, s.lengthUs, stillWanted) }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "A sound couldn't be read", it) }.getOrNull()
                ?: return@mapNotNull null
            val converted = MusicMix.convert(decoded.samples, decoded.frames, decoded.sampleRate, decoded.channels, rate, channels)
            val o = MusicMix.Overlay(converted, (converted.size / channels).toLong(), MusicMix.frames(s.atUs, rate), s.gain, MusicMix.frames(s.fadeUs, rate))
            if (s.duck) ducked += o.at until o.at + o.frames
            o
        }
        progress(0.25f)
        val duck = MusicMix.Duck.of(ducked, rate)
        return encode(
            rate, channels, total, { from, count, out -> MusicMix.mix(pcm?.samples, channels, plan, overlays, from, count, out, duck) },
            stillWanted, progress = { progress(0.25f + 0.6f * it) },
        )
    }

    /** The video track of the file [extractor] reads. */
    private fun videoTrack(extractor: MediaExtractor): Int = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
    } ?: error("The clip has no video track")

    /** [video]'s length. */
    private fun videoDuration(video: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(video.path)
            val index = videoTrack(extractor)
            val format = extractor.getTrackFormat(index)
            extractor.selectTrack(index)
            return videoDuration(extractor, format)
        } finally {
            extractor.release()
        }
    }

    /** The video track's length; from its format, else from the last sample plus one frame. */
    private fun videoDuration(extractor: MediaExtractor, format: MediaFormat): Long {
        if (format.containsKey(MediaFormat.KEY_DURATION)) return format.getLong(MediaFormat.KEY_DURATION)
        var last = 0L
        var previous = 0L
        var gap = 0L
        while (extractor.sampleTime >= 0) {
            previous = last
            last = extractor.sampleTime
            gap = last - previous
            extractor.advance()
        }
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        return last + gap
    }

    /**
     * The audio [open] gives decoded to 16-bit PCM from [fromUs] for [lengthUs] (no more than that), or null when it
     * has no sound.
     */
    private fun decode(open: (MediaExtractor) -> Unit, fromUs: Long, lengthUs: Long, stillWanted: () -> Unit): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            open(extractor)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(index)
            extractor.selectTrack(index)
            if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floats = false
            val trackUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else fromUs + lengthUs
            var samples = ShortArray((MusicMix.frames(min(trackUs - fromUs, lengthUs).coerceAtLeast(0), rate) * channels + 8192).toInt())
            var size = 0
            val endUs = fromUs + lengthUs

            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                stillWanted()
                if (!inputDone) {
                    val input = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (input >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(input))
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(input, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floats = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    output >= 0 -> {
                        idle = 0
                        val buffer = requireNotNull(decoder.getOutputBuffer(output)).order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val count = if (floats) info.size / 4 else info.size / 2
                        // Skip what comes before [fromUs] (the seek lands on the packet before it).
                        val skip = if (fromUs > 0 && info.presentationTimeUs < fromUs) {
                            (MusicMix.frames(fromUs - info.presentationTimeUs, rate) * channels).toInt().coerceIn(0, count)
                        } else 0
                        val take = count - skip
                        if (take > 0) {
                            if (size + take > samples.size) samples = samples.copyOf(max(samples.size * 2, size + take))
                            if (floats) {
                                val f = buffer.asFloatBuffer()
                                for (n in 0 until take) samples[size + n] = (f.get(skip + n).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                            } else {
                                val s = buffer.asShortBuffer()
                                s.position(skip)
                                s.get(samples, size, take)
                            }
                            size += take
                        }
                        decoder.releaseOutputBuffer(output, false)
                        val enough = size / channels >= MusicMix.frames(lengthUs, rate) || info.presentationTimeUs >= endUs
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || enough) break
                    }
                    inputDone && ++idle > MAX_IDLE -> error("The audio decoder stopped answering")
                }
            }
            val frames = min(size / channels, MusicMix.frames(lengthUs, rate).toInt())
            if (frames <= 0) return null
            return Pcm(samples, frames, rate, channels)
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            extractor.release()
        }
    }

    /**
     * [total] frames of sound from [render] encoded to AAC-LC, kept in memory (about 1 MB a minute) until the muxer has
     * both tracks. [progress] gets the share of the frames fed in, on each whole percent.
     */
    private fun encode(
        rate: Int, channels: Int, total: Long, render: (from: Long, count: Int, out: ShortArray) -> Unit, stillWanted: () -> Unit,
        progress: (Float) -> Unit = {},
    ): Encoded {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val samples = ArrayList<Sample>()
            var outFormat: MediaFormat? = null
            val info = MediaCodec.BufferInfo()
            var chunk = ShortArray(0)
            var next = 0L
            var percent = 0L
            var inputDone = false
            var idle = 0
            while (true) {
                stillWanted()
                if (!inputDone) {
                    val input = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (input >= 0) {
                        val buffer = requireNotNull(encoder.getInputBuffer(input)).order(ByteOrder.nativeOrder())
                        buffer.clear()
                        val frames = min((buffer.capacity() / (2 * channels)).toLong(), total - next).toInt()
                        val time = MusicMix.presentationTimeUs(next, rate)
                        if (frames <= 0) {
                            encoder.queueInputBuffer(input, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            if (chunk.size < frames * channels) chunk = ShortArray(frames * channels)
                            render(next, frames, chunk)
                            buffer.asShortBuffer().put(chunk, 0, frames * channels)
                            next += frames
                            if (next * 100 / total > percent) {
                                percent = next * 100 / total
                                progress(next.toFloat() / total)
                            }
                            val last = next >= total
                            encoder.queueInputBuffer(input, 0, frames * channels * 2, time,
                                if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            inputDone = last
                        }
                    }
                }
                val output = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = encoder.outputFormat
                    output >= 0 -> {
                        idle = 0
                        val buffer = requireNotNull(encoder.getOutputBuffer(output))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size).also { buffer.get(it) }
                            samples += Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                        }
                        encoder.releaseOutputBuffer(output, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    inputDone && ++idle > MAX_IDLE -> error("The music encoder stopped answering")
                }
            }
            check(samples.isNotEmpty()) { "The music encoder made nothing" }
            return Encoded(requireNotNull(outFormat) { "The music encoder gave no format" }, samples)
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    /**
     * Copies every sample of [video]'s picture untouched to [output] and interleaves [audio] by time. The sound may have
     * been made for the planned length: what of it starts after the video's real end is left out, so it never runs on
     * past the picture.
     */
    private fun mux(video: File, audio: Encoded, output: File, stillWanted: () -> Unit) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(video.path)
            val videoIndex = videoTrack(extractor)
            val videoFormat = extractor.getTrackFormat(videoIndex)
            extractor.selectTrack(videoIndex)
            val durationUs = videoDuration(extractor, videoFormat)
            check(durationUs > 0) { "The clip has no length" }
            mux(extractor, videoFormat, audio, MusicMix.samplesWithin(audio.samples.map { it.timeUs }, durationUs), output, stillWanted)
        } finally {
            extractor.release()
        }
    }

    /** Copies every video sample untouched and interleaves the first [audioSamples] of [audio] by time. */
    private fun mux(extractor: MediaExtractor, videoFormat: MediaFormat, audio: Encoded, audioSamples: Int, output: File, stillWanted: () -> Unit) {
        check(audioSamples > 0) { "No sound within the video" }
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val videoTrack = muxer.addTrack(videoFormat)
            val audioTrack = muxer.addTrack(audio.format)
            muxer.start()
            started = true
            val capacity = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val videoBuffer = ByteBuffer.allocateDirect(max(capacity, 4 * 1024 * 1024))
            val audioBuffer = ByteBuffer.allocateDirect(max(audio.samples.maxOf { it.bytes.size }, 1))
            val info = MediaCodec.BufferInfo()
            var a = 0
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                stillWanted()
                val videoTime = extractor.sampleTime
                while (a < audioSamples && (videoTime < 0 || audio.samples[a].timeUs <= videoTime)) {
                    val s = audio.samples[a++]
                    audioBuffer.clear()
                    audioBuffer.put(s.bytes)
                    audioBuffer.flip()
                    info.set(0, s.bytes.size, s.timeUs, s.flags)
                    muxer.writeSampleData(audioTrack, audioBuffer, info)
                }
                if (videoTime < 0) break
                videoBuffer.clear()
                val size = extractor.readSampleData(videoBuffer, 0)
                if (size < 0) break
                val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, videoTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(videoTrack, videoBuffer, info)
                extractor.advance()
            }
            muxer.stop()
            started = false
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
