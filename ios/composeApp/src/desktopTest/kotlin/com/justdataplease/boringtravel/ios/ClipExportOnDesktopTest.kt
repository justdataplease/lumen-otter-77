package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.clip.ClipLength
import com.justdataplease.boringtravel.clip.ClipText
import com.justdataplease.boringtravel.clip.ExportBar
import com.justdataplease.boringtravel.clip.ExportProgress
import com.justdataplease.boringtravel.clip.ExportStage
import com.justdataplease.boringtravel.clip.music.ClipMusic
import com.justdataplease.boringtravel.clip.music.MusicCatalog
import com.justdataplease.boringtravel.clip.music.MusicMix
import com.justdataplease.boringtravel.compat.graphics.Bitmap
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.management.ManagementFactory
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The trip video's export (round 20, ios/ClipExporter.kt) end to end on the desktop, with the recording writer in place
 * of AVAssetWriter: every frame handed over in order with its number, the parts when the app leaves the screen and comes
 * back, a part the phone broke off made again, a failing writer, a cancel, a phone without room, music that can't be
 * read or added, Android's progress, the sound's mix (fades, loops, ducking), and the frames' memory.
 */
class ClipExportOnDesktopTest {
    private val trip = SampleArgentina.TRIP_ID
    private val music = requireNotNull(MusicCatalog.byId("latin_groove"))

    /** Frame [f] as one flat colour: quick to draw, a different picture for every frame. */
    private val quick: (ExportJob, VideoSpec, com.justdataplease.boringtravel.clip.ClipVideoFrames) -> ExportFrames = { _, _, _ ->
        ExportFrames { f, into -> into.eraseColor(0xFF000000.toInt() or (f * 0x01_93_1F) and 0xFFFFFF or 0xFF000000.toInt()) }
    }

    /** The demo's Argentina as a ready studio and its clip as an export job (the length set to [length]). */
    private fun <T> withJob(length: ClipLength = ClipLength.S15, voice: Boolean = false, block: suspend CoroutineScope.(ExportJob, MemoryMediaFiles) -> T): T {
        val (book, kit) = demoBook()
        val files = kit.files as MemoryMediaFiles
        return runBlocking {
            val studio = readyStudio(book, kit, trip, Look.PostcardPop, this)
            try {
                studio.setLength(length)
                studio.settle()
                val plan = requireNotNull(studio.plan.value)
                val sounds = if (!voice) emptyList() else listOf(ClipMusic.voice("memory:/clip-voice/line.m4a", 2_000_000, 1_500_000))
                val job = ExportJob(
                    plan, studio.base.value, requireNotNull(studio.style.value), studio.photos.value, false, studio.design.value,
                    emptyMap(), music, "files/" + music.asset, 0L, sounds, ClipExporter.outputPath(plan.title),
                )
                block(job, files)
            } finally {
                studio.close()
            }
        }
    }

    private fun exporter(files: MediaFiles, writer: VideoWriter, screen: MutableStateFlow<Boolean> = MutableStateFlow(true), work: BackgroundWork = NoBackgroundWork, decoder: com.justdataplease.boringtravel.clip.music.SoundDecoder = ToneDecoder()) =
        ClipExporter(files, writer, decoder, NoVideoFiles, screen, work).also { it.frames = quick }

    private fun bar(seen: MutableList<Pair<Float, ExportStage>> = mutableListOf(), sound: Boolean = true) = ExportBar(sound) { p, s -> synchronized(seen) { seen += p to s } }

    @Test fun everyFrameGoesToTheWriterInOrderWithTheSoundUnderIt() = withJob { job, files ->
        val writer = DesktopVideoWriter(files, checksums = true)
        val work = CountingBackgroundWork()
        val made = exporter(files, writer, work = work).make(job, bar())
        val total = job.plan.frameCount
        // One part, every frame in order, each a different picture.
        assertEquals(1, writer.parts.size)
        assertEquals((0 until total).toList(), writer.parts.single().frames)
        assertEquals(total, writer.parts.single().checksums.toSet().size)
        // The pictures at the spec Android encodes with: 1080 × 1920, 30 fps, 16 Mbit/s, a key frame every 2 s.
        assertEquals(VideoSpec(1080, 1920, 30, 16_174_080, 60), writer.parts.single().spec)
        // Joined with the music, cut at the last picture: 44.1 kHz stereo, as long as the clip, and not silent.
        val join = writer.joins.single()
        assertEquals(listOf(PicturePart(join.parts.single().path, 0, total)), join.parts)
        val sound = assertNotNull(join.sound)
        assertEquals(44_100 to 2, sound.sampleRate to sound.channels)
        assertEquals(MusicMix.frames(total * 1_000_000L / job.plan.fps, 44_100), sound.frames)
        assertTrue(sound.loudFrames > sound.frames * 9 / 10, "loud ${sound.loudFrames} of ${sound.frames}")
        // The finished file where it goes, its parts gone, a picture from 40 % in, the phone's time given back.
        assertEquals(job.output, made.path)
        assertTrue((files.size(made.path) ?: 0L) > 0L)
        assertTrue(files.paths.none { it.startsWith(ClipExporter.PARTS) }, files.paths.toString())
        val thumb = assertNotNull(made.thumb)
        assertEquals(0xFF.toByte() to 0xD8.toByte(), thumb[0] to thumb[1])
        assertEquals(false, made.musicFailed)
        assertEquals(total * 1000L / job.plan.fps, made.durationMs)
        assertEquals(1 to 1, work.begun to work.ended)
    }

    @Test fun leavingTheScreenEndsAPartAndTheNextGoesOnWhenBack() = withJob { job, files ->
        val screen = MutableStateFlow(true)
        val writer = DesktopVideoWriter(files, checksums = false)
        val work = CountingBackgroundWork()
        // The app leaves after frame 200 of the first part, and is back a moment later.
        writer.failAppend = { part, index ->
            if (part == 1 && index == 200) {
                screen.value = false
                launch { delay(150); screen.value = true }
            }
            false
        }
        val made = exporter(files, writer, screen, work).make(job, bar())
        val total = job.plan.frameCount
        assertEquals(2, made.parts)
        assertEquals((0..200).toList(), writer.parts[0].frames)
        assertEquals((0 until total - 201).toList(), writer.parts[1].frames)
        assertEquals(listOf(0 to 201, 201 to total - 201), writer.joins.single().parts.map { it.firstFrame to it.frames })
        // Time was asked again when the app came back, and every ask was given back.
        assertEquals(2, work.begun)
        assertEquals(2, work.ended)
    }

    @Test fun whereThePhoneLetsTheExportGoOnAwayItStaysOnePart() = withJob { job, files ->
        // iOS 26's continued processing: the app leaves after frame 200 and the pictures go on into the same part.
        val screen = MutableStateFlow(true)
        val writer = DesktopVideoWriter(files, checksums = false)
        val work = ContinuedBackgroundWork()
        writer.failAppend = { part, index ->
            if (part == 1 && index == 200) screen.value = false
            false
        }
        val made = exporter(files, writer, screen, work).make(job, bar())
        val total = job.plan.frameCount
        assertEquals(1, made.parts)
        assertEquals((0 until total).toList(), writer.parts.single().frames)
        // The system's own progress: the frames up to 0.9, then the rest.
        assertEquals(listOf(ClipExporter.TASK_NAME), work.names)
        val seen = synchronized(work.progress) { work.progress.toList() }
        assertEquals(ClipExporter.FRAMES_SHARE, seen[seen.size - 2])
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a }, "progress goes forward")
        assertEquals(0.99f, seen.last())
    }

    @Test fun anEncoderThatStopsAwayAnywayMakesThePartAgainWhenBack() = withJob { job, files ->
        // The phone let it go on, but its encoder stopped away from the screen: the part is made again once back.
        val screen = MutableStateFlow(true)
        val writer = DesktopVideoWriter(files, checksums = false)
        writer.failAppend = { part, index ->
            if (part == 1 && index == 150) {
                screen.value = false
                launch { delay(150); screen.value = true }
                true
            } else false
        }
        val made = exporter(files, writer, screen, ContinuedBackgroundWork()).make(job, bar())
        val total = job.plan.frameCount
        assertTrue(writer.parts[0].cancelled)
        assertEquals((0 until total).toList(), writer.parts[1].frames)
        assertTrue((files.size(made.path) ?: 0L) > 0L)
    }

    @Test fun aPartThePhoneBrokeOffIsMadeAgainFromItsFirstFrame() = withJob { job, files ->
        val screen = MutableStateFlow(true)
        val writer = DesktopVideoWriter(files, checksums = false)
        // The encoder stops as the app leaves in the middle of frame 150 (an iPhone's does in the background).
        writer.failAppend = { part, index ->
            if (part == 1 && index == 150) {
                screen.value = false
                launch { delay(150); screen.value = true }
                true
            } else false
        }
        val made = exporter(files, writer, screen).make(job, bar())
        val total = job.plan.frameCount
        assertTrue(writer.parts[0].cancelled)
        assertEquals((0 until total).toList(), writer.parts[1].frames)
        assertEquals(listOf(0 to total), writer.joins.single().parts.map { it.firstFrame to it.frames })
        assertTrue((files.size(made.path) ?: 0L) > 0L)
    }

    @Test fun aWriterThatKeepsFailingOnScreenEndsTheExportAndKeepsTheLastClip() = withJob { job, files ->
        files.write(job.output, "the last good clip".encodeToByteArray())
        val writer = DesktopVideoWriter(files, checksums = false, failAppend = { _, index -> index == 10 })
        assertFailsWith<ExportFailed> { exporter(files, writer).make(job, bar()) }
        assertEquals(ClipExporter.MAX_FAILURES, writer.parts.size)
        assertEquals("the last good clip", files.read(job.output)?.decodeToString())
        assertTrue(files.paths.none { it.startsWith(ClipExporter.PARTS) }, files.paths.toString())
    }

    @Test fun cancellingLeavesNoPartsAndKeepsTheLastClip() = withJob { job, files ->
        files.write(job.output, "the last good clip".encodeToByteArray())
        val writer = DesktopVideoWriter(files, checksums = false)
        val exporter = exporter(files, writer)
        val going = async(Dispatchers.Default) { exporter.make(job, bar()) }
        withTimeout(30_000) { while (writer.parts.firstOrNull()?.frames.orEmpty().size < 100) delay(5) }
        going.cancel()
        assertFailsWith<CancellationException> { going.await() }
        assertEquals("the last good clip", files.read(job.output)?.decodeToString())
        assertTrue(files.paths.none { it.startsWith(ClipExporter.PARTS) }, files.paths.toString())
        assertTrue(writer.joins.isEmpty())
    }

    @Test fun aPhoneWithoutRoomSaysSoBeforeDrawing() = withJob { job, files ->
        val writer = DesktopVideoWriter(files, checksums = false, free = 20L * 1024 * 1024)
        val failed = assertFailsWith<ExportFailed> { exporter(files, writer).make(job, bar()) }
        assertTrue("space" in failed.reason, failed.reason)
        assertTrue(writer.parts.isEmpty())
        // A 15 s clip needs about 2.5 × its 33 MB and the spare 50 MB.
        val spec = ClipExporter.spec(1080, 1920, 30)
        assertEquals(33_623_040L, ClipExporter.estimateBytes(spec, 450, hasSound = true))
    }

    @Test fun musicThatCantBeReadLeavesTheVideoSilent() = withJob { job, files ->
        val writer = DesktopVideoWriter(files, checksums = false)
        val made = exporter(files, writer, decoder = ToneDecoder(failMusic = true)).make(job, bar())
        assertTrue(made.musicFailed)
        // Nothing to add: the one part is the video (Android: "Couldn't add the music, so this video is silent.").
        assertTrue(writer.joins.isEmpty())
        assertTrue((files.size(made.path) ?: 0L) > 0L)
    }

    @Test fun soundThatCantGoInIsJoinedSilent() = withJob(voice = true) { job, files ->
        val screen = MutableStateFlow(true)
        val writer = DesktopVideoWriter(files, checksums = false, failJoin = { _, withSound -> withSound })
        // Two parts: the silent join still has to put them together.
        writer.failAppend = { part, index ->
            if (part == 1 && index == 99) {
                screen.value = false
                launch { delay(100); screen.value = true }
            }
            false
        }
        val made = exporter(files, writer, screen).make(job, bar())
        assertTrue(made.musicFailed)
        assertEquals(null, writer.joins.single().sound)
        assertEquals(2, writer.joins.single().parts.size)
    }

    @Test fun theProgressNeverGoesBackAndStopsShortOfDone() = withJob { job, files ->
        val seen = mutableListOf<Pair<Float, ExportStage>>()
        exporter(files, DesktopVideoWriter(files, checksums = false)).make(job, bar(seen))
        val values = seen.map { it.first }
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a }, values.toString())
        assertTrue(values.last() <= ExportProgress.LAST)
        val stages = seen.map { it.second }.distinct()
        assertEquals(listOf(ExportStage.Picture, ExportStage.Sound, ExportStage.Finishing), stages)
        // Whole percents only, then the stages' changes.
        assertTrue(seen.size <= 110, "${seen.size} updates")
    }

    @Test fun theSoundIsAndroidsMixFadedAndDuckedUnderAVoice() = runBlocking {
        // A 10 s clip, a silent voice line from 3 s for 2 s: the music dips under it.
        val voice = ClipMusic.voice("memory:/voice.m4a", 3_000_000, 2_000_000)
        val mixed = assertNotNull(ClipMusic.prepare(ToneDecoder(voiceAmplitude = 0), 10_000_000, music, "files/" + music.asset, 0L, listOf(voice)))
        assertEquals(44_100 to 2, mixed.sampleRate to mixed.channels)
        assertEquals(441_000L, mixed.totalFrames)
        val open = rms(mixed, 1.0, 2.5)
        // Faded in from silence and out to it (Android's 0.3 s and 1.8 s).
        val first = ShortArray(2).also { mixed.render(0, 1, it) }
        val last = ShortArray(2).also { mixed.render(mixed.totalFrames - 1, 1, it) }
        assertEquals(0, abs(first[0].toInt()) + abs(last[0].toInt()))
        assertTrue(rms(mixed, 0.0, 0.1) < 0.5 * open && rms(mixed, 9.5, 10.0) < 0.5 * open)
        // Under the voice the music is at Android's 0.3 (about −10.5 dB); after it, back up.
        assertEquals(MusicMix.DUCK_GAIN.toDouble(), rms(mixed, 3.2, 4.8) / open, 0.03)
        assertEquals(1.0, rms(mixed, 6.0, 7.5) / open, 0.05)
    }

    @Test fun aShortTrackLoopsWithoutAGap() = runBlocking {
        // A 10 s clip over a 4 s track: it loops, each pass crossfading into the next, never silent.
        val mixed = assertNotNull(ClipMusic.prepare(ToneDecoder(musicSeconds = 4.0), 10_000_000, music, "files/" + music.asset, 0L, emptyList()))
        val open = rms(mixed, 1.0, 2.5)
        val windows = (1 until 16).map { i -> rms(mixed, i * 0.5, i * 0.5 + 0.5) }
        assertTrue(windows.all { it > 0.6 * open }, windows.toString())
    }

    private fun rms(mixed: com.justdataplease.boringtravel.clip.music.MixedSound, fromS: Double, toS: Double): Double {
        val from = (fromS * mixed.sampleRate).toLong()
        val n = ((toS - fromS) * mixed.sampleRate).toInt()
        val out = ShortArray(n * mixed.channels)
        mixed.render(from, n, out)
        return sqrt(out.sumOf { it.toDouble() * it } / out.size)
    }

    @Test fun theFileIsNamedAsAndroidNamesItInTheAppsExports() {
        assertEquals("exports/" + ClipText.fileName("Argentina & Patagonia"), ClipExporter.outputPath("Argentina & Patagonia"))
        assertEquals("exports/Argentina-Patagonia-clip.mp4", ClipExporter.outputPath("Argentina & Patagonia"))
        assertEquals("exports/trip-clip.mp4", ClipExporter.outputPath("!!!"))
    }

    /**
     * The clip's own frames (the renderer the preview draws with), drawn for the whole export: the frames handed over
     * are ClipFrames' pixels, and the process's memory stays flat however many frames go by (a frame or a picture kept
     * per frame would add 8 MB each). Skia's memory is outside the JVM's heap, so the process's committed memory is read.
     */
    @Test fun theClipsOwnFramesGoOutAndMemoryStaysFlat() = withJob { job, files ->
        val writer = DesktopVideoWriter(files, checksums = false)
        val exporter = ClipExporter(files, writer, ToneDecoder(), NoVideoFiles, MutableStateFlow(true))
        val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val total = job.plan.frameCount
        val sums = HashMap<Int, Long>()
        val memory = HashMap<Int, Long>()
        val own = exporter.frames
        exporter.frames = { j, s, v ->
            val drawn = own(j, s, v)
            object : ExportFrames {
                override fun draw(frame: Int, into: Bitmap) {
                    drawn.draw(frame, into)
                    if (frame in setOf(0, total / 2, total - 1)) sums[frame] = crc(into)
                    if (frame == 60 || frame == total - 2) {
                        System.gc()
                        memory[frame] = os.committedVirtualMemorySize
                    }
                }

                override fun close() = drawn.close()
            }
        }
        val started = System.nanoTime()
        exporter.make(job, bar())
        val ms = (System.nanoTime() - started) / 1_000_000
        // The same pixels as ClipFrames on its own.
        ClipFrames(job.plan, job.base, job.style, job.photos, job.noMark, job.design, 1080, 1920).use { frames ->
            for ((f, sum) in sums) assertEquals(crc(frames.draw(f)), sum, "frame $f")
        }
        val grown = memory.getValue(total - 2) - memory.getValue(60)
        println("Export on this PC: $total frames of 1080 × 1920 in $ms ms (${ms / total} ms a frame); the process ${memory.getValue(60) / 1024 / 1024} MB at frame 60, ${memory.getValue(total - 2) / 1024 / 1024} MB at frame ${total - 2}")
        assertTrue(grown < 300L * 1024 * 1024, "the process grew by ${grown / 1024 / 1024} MB over ${total - 62} frames")
    }

    private fun crc(bitmap: Bitmap): Long = CRC32().apply { update(requireNotNull(bitmap.bgra())) }.value
}
