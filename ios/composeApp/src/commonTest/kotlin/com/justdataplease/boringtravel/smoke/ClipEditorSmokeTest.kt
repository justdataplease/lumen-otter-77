package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipEdits
import com.justdataplease.boringtravel.clip.ClipEditsJson
import com.justdataplease.boringtravel.clip.ClipMedia
import com.justdataplease.boringtravel.clip.ClipOptions
import com.justdataplease.boringtravel.clip.ClipPlan
import com.justdataplease.boringtravel.clip.ClipSource
import com.justdataplease.boringtravel.clip.ClipSources
import com.justdataplease.boringtravel.clip.ClipTimeline
import com.justdataplease.boringtravel.clip.ClipVideo
import com.justdataplease.boringtravel.clip.EditHistory
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.clip.PlaceMedia
import com.justdataplease.boringtravel.clip.PreparedTimes
import com.justdataplease.boringtravel.clip.PreparedVideoFrames
import com.justdataplease.boringtravel.clip.SceneOverride
import com.justdataplease.boringtravel.clip.VideoSource
import com.justdataplease.boringtravel.clip.VoiceLine
import com.justdataplease.boringtravel.clip.VoiceRecorder
import com.justdataplease.boringtravel.clip.music.MusicCatalog
import com.justdataplease.boringtravel.compat.Locale
import com.justdataplease.boringtravel.compat.Manifest
import com.justdataplease.boringtravel.compat.SystemClock
import com.justdataplease.boringtravel.compat.uppercase
import com.justdataplease.boringtravel.compat.ZoneId
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ios.ClipVideoFiles
import com.justdataplease.boringtravel.ios.MemoryMediaFiles
import com.justdataplease.boringtravel.ios.MicAccess
import com.justdataplease.boringtravel.ios.MicRecording
import com.justdataplease.boringtravel.ios.Microphone
import com.justdataplease.boringtravel.ios.PickedMedia
import com.justdataplease.boringtravel.ios.VideoFacts
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.clip.ClipTool
import com.justdataplease.boringtravel.ui.clip.ScenePick
import com.justdataplease.boringtravel.ui.clip.TrackModel
import com.justdataplease.boringtravel.ui.clip.TrackScale
import com.justdataplease.boringtravel.ui.clip.clockPair
import com.justdataplease.boringtravel.ui.clip.percent
import com.justdataplease.boringtravel.ui.clip.previewMusicMs
import com.justdataplease.boringtravel.ui.clip.rulerLabel
import com.justdataplease.boringtravel.ui.clip.secondsLabel
import com.justdataplease.boringtravel.ui.clip.spotName
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The trip video's editor (round 19) where Kotlin/Native could differ from the JVM: the tracks of a planned clip (the
 * Android app's ui/clip/TrackLayout.kt, read in), its texts as Java writes them ("1:05", "2.5 s", "120 %", "Bottom
 * left", the media counts), the edits with videos and voice lines in Android's form, undo and redo, a phone video's
 * picture cropped and read back with Skia, the music's time under the preview, a voice line recorded and dropped
 * when too short, a picked video moved in or refused with Android's words, the leftover files swept, and compat's
 * clock and locale.
 */
class ClipEditorSmokeTest {
    private val start = localDate(2027, 6, 17)

    private fun item(id: String, day: Int?, title: String, lat: Double, lng: Double) = PlanItem(
        id = id, tripId = "t", day = day?.let { start.plusDays((it - 1).toLong()) }, position = 1.0, kind = "place", title = title,
        note = "", lat = lat, lng = lng, done = false, createdAt = 0L, updatedAt = 0L,
    )

    private fun plan(design: ClipDesign = ClipDesign()): ClipPlan {
        val input = ClipSources.available(
            Trip("t", "Cyclades, slowly", "", start, start.plusDays(4), trackingLevel = "off", createdAt = 0L, updatedAt = 0L),
            emptyList(), emptyList(),
            listOf(
                item("a", 1, "Piraeus", 37.942, 23.646), item("b", 2, "Naxos", 37.105, 25.376),
                item("c", 3, "Paros", 37.085, 25.150), item("d", 5, "Santorini", 36.393, 25.461),
            ),
            emptyList(), ZoneId.of("UTC"),
        ).getValue(ClipSource.Planned)
        return assertNotNull(ClipPlan.build(input, design.applyTo(ClipOptions(map = MapStyle.Street))))
    }

    @Test fun aPlannedClipsTracks() {
        val design = ClipDesign()
        val p = plan(design)
        val t = ClipTimeline.of(p, design)
        val model = TrackModel.of(p, design, t)
        assertEquals("Title", model.scenes.first().title)
        assertEquals("End", model.scenes.last().title)
        // A block per place on the scenes track, each choosing its place; every name on the texts track.
        val places = p.labels.map { it.key }
        assertEquals(places, model.scenes.mapNotNull { (it.pick as? ScenePick.Place)?.key }.distinct())
        for (range in p.labels) assertTrue(model.texts.any { it.title == range.label.text }, range.label.text)
        // The blocks are in time order and within the clip.
        assertTrue(model.scenes.zipWithNext().all { (a, b) -> a.from <= b.from })
        assertTrue(model.scenes.all { it.from >= 0 && it.to <= model.frames })
        // A choice is kept by its key and read back.
        for (pick in listOf(ScenePick.Opening, ScenePick.End, ScenePick.Place(places[1]), ScenePick.Photo("p1"), ScenePick.Video("v1"))) {
            assertEquals(pick, ScenePick.of(pick.key))
        }
        assertEquals(ScenePick.Opening, ScenePick.at(t, 0))
        // The tools a chosen place has, in the bar's order; a video's.
        assertEquals(listOf(ClipTool.Text, ClipTool.Audio, ClipTool.Media, ClipTool.Camera, ClipTool.Length, ClipTool.Route), ClipTool.forPick(ScenePick.Place(places[1])))
        assertEquals(listOf(ClipTool.Media, ClipTool.Length), ClipTool.forPick(ScenePick.Video("v1")))
        // The time under the playhead and the ruler.
        val scale = TrackScale.fit(t)
        assertTrue(scale.dpPerSecond in TrackScale.FIT_MIN..TrackScale.FIT_MAX)
        assertEquals(0f, scale.scrub(10f, 10_000f, model.frames))
        assertTrue(scale.zoomed(100f).dpPerSecond <= TrackScale.MAX_DP_PER_S)
        assertEquals("1:05", rulerLabel(65))
        assertEquals("0:05", rulerLabel(5))
        assertEquals("0:01 / 0:30", clockPair(30, 900, 30))
    }

    @Test fun theEditorsTextsAreWrittenAsJavaWritesThem() {
        assertEquals("2.5 s", secondsLabel(2.5f))
        assertEquals("3 s", secondsLabel(3f))
        assertEquals("1.3 s", secondsLabel(1.25f))
        assertEquals("No pause", secondsLabel(0f, "No pause"))
        assertEquals("120 %", percent(1.2f))
        assertEquals("Bottom left", spotName(0.1f to 0.9f))
        assertEquals("Middle", spotName(0.5f to 0.5f))
        assertEquals("Right", spotName(0.9f to 0.5f))
        assertEquals("Top", spotName(0.5f to 0.1f))
        assertEquals("2 of 5 photos · 1 video", PlaceMedia.countLine(2, 5, 1))
        assertEquals("No media", PlaceMedia.countLine(0, 0, 0))
        assertEquals("ÉTÉ", "été".uppercase(Locale.ROOT))
        assertEquals("android.permission.RECORD_AUDIO", Manifest.permission.RECORD_AUDIO)
    }

    @Test fun editsWithVideosAndVoicesReadBackInAndroidsForm() {
        val video = ClipVideo("v1", "v1.mov", "label:item:b", 12_000).trimmed(3_000, 4_000)
        val edits = ClipEdits(
            scenes = mapOf("label:item:b" to SceneOverride(text = "Νάξος", x = 0.3f, y = 0.6f)),
            videos = listOf(video.copy(sound = true, source = "media-1")),
            voices = listOf(VoiceLine("label:item:c", "line.m4a", 4_200)),
            musicStartS = 32.5, skipped = setOf("d"), picks = mapOf("label:item:b" to listOf("p2", "p1")),
        )
        val back = ClipEditsJson.decode(ClipEditsJson.encode(mapOf("Planned" to edits))).getValue("Planned")
        assertEquals(edits, back)
        assertEquals(3_000L to 4_000L, back.videos.single().let { it.startMs to it.lengthMs })
        // A file name with a folder in it is never taken.
        val bad = ClipEditsJson.encode(mapOf("Planned" to ClipEdits(voices = listOf(VoiceLine("k", "../x.m4a", 1_000)))))
        assertTrue(ClipEditsJson.decode(bad).isEmpty())
        // Undo and redo, typing as one step.
        var h = EditHistory(0)
        h = h.edit(1).edit(2, "t").edit(3, "t")
        assertEquals(listOf(0, 1), h.past)
        h = h.undo()
        assertEquals(1, h.present)
        h = h.redo()
        assertEquals(3, h.present)
    }

    /** A [w] × [h] PNG, red on the left half and blue on the right. */
    private fun halves(w: Int, h: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(w, h)
        val paint = Paint()
        paint.color = Color.RED
        surface.canvas.drawRect(Rect.makeXYWH(0f, 0f, w / 2f, h.toFloat()), paint)
        paint.color = Color.BLUE
        surface.canvas.drawRect(Rect.makeXYWH(w / 2f, 0f, w / 2f, h.toFloat()), paint)
        val image = surface.makeImageSnapshot()
        val data = image.encodeToData(EncodedImageFormat.PNG)!!
        val bytes = data.bytes
        data.close(); image.close(); paint.close(); surface.close()
        return bytes
    }

    @Test fun aVideosPicturesAreCroppedToTheFrameAndRead() {
        // A wide picture cropped to fill a tall frame keeps its middle: red then blue across it.
        val jpeg = assertNotNull(PreparedVideoFrames.cropToFill(halves(400, 200), 60, 120))
        val times = PreparedTimes.of(1_000_000, PreparedVideoFrames.FPS)
        assertEquals(PreparedVideoFrames.FPS, times.size)
        assertEquals(3, PreparedTimes.index(times, 400_000))
        val frames = PreparedVideoFrames(mapOf("v" to PreparedVideoFrames.Prepared("k", times, List(times.size) { jpeg })))
        val reader = frames.reader()
        val bitmap = assertNotNull(reader.frame("v", 0, 540, 960))
        assertEquals(60 to 120, bitmap.width to bitmap.height)
        val left = bitmap.getPixel(4, 60)
        val right = bitmap.getPixel(55, 60)
        assertTrue((left shr 16 and 0xFF) > 180 && (left and 0xFF) < 80, "left ${left.toString(16)}")
        assertTrue((right and 0xFF) > 180 && (right shr 16 and 0xFF) < 80, "right ${right.toString(16)}")
        // The same picture again is the same bitmap; none for a video it doesn't have.
        assertSame(bitmap, reader.frame("v", 10_000, 540, 960))
        assertNull(reader.frame("other", 0, 540, 960))
        reader.close()
        assertTrue(bitmap.isRecycled)
    }

    @Test fun theMusicsTimeUnderThePreview() {
        val track = MusicCatalog.byId("latin_groove")!!
        val start = MusicCatalog.startFor(track.id, null)
        assertEquals((start * 1000).toInt() + 1_000, previewMusicMs(30, 30, start, track.durationMs))
        assertEquals((start * 1000).toInt(), previewMusicMs(0, 30, start, track.durationMs))
    }

    /** A microphone that records a few bytes into [files]. */
    private class Mic(private val files: MemoryMediaFiles) : Microphone {
        override val access: MicAccess = MicAccess.Allowed
        override fun ask(done: (Boolean) -> Unit) = done(true)
        override fun record(absolutePath: String, maxMs: Long): MicRecording = object : MicRecording {
            val path = absolutePath.removePrefix("memory:/")
            override fun stop(): Boolean = files.write(path, ByteArray(12) { 1 })
            override fun cancel() = files.delete(path)
        }
    }

    @Test fun aVoiceLineIsKeptOrDroppedWhenTooShort() {
        val files = MemoryMediaFiles()
        val recorder = VoiceRecorder(files, "trip/1?", Mic(files))
        assertTrue(recorder.start())
        assertNull(recorder.stop())
        assertTrue(files.paths.none { it.startsWith("clip-voice/") })
        assertTrue(recorder.start())
        val mark = TimeSource.Monotonic.markNow()
        while (mark.elapsedNow() < 450.milliseconds) { /* a line held a little longer */ }
        val (file, ms) = assertNotNull(recorder.stop())
        assertTrue(file.endsWith(".m4a") && '/' !in file)
        assertTrue(ms in 400..20_000)
        assertNotNull(files.read("clip-voice/trip_1_/$file"))
        // SystemClock only goes forward.
        val a = SystemClock.elapsedRealtime()
        assertTrue(SystemClock.elapsedRealtime() >= a && a > 0)
    }

    /** Videos whose name ends in ".mov" are 8 s long. */
    private object Movs : ClipVideoFiles {
        override fun probe(path: String): VideoFacts? = if (path.endsWith(".mov")) VideoFacts(8_000, 1080, 1920) else null
        override fun pictures(path: String, timesUs: LongArray, width: Int, height: Int, each: (Int, ByteArray?) -> Boolean) = Unit
    }

    @Test fun aPickedVideoIsMovedInOrRefusedWithAndroidsWords() = runTest {
        val files = MemoryMediaFiles()
        files.write("picked/a.mov", ByteArray(100) { 2 })
        val video = ClipMedia.importVideo(files, Movs, "t1", PickedMedia("picked/a.mov", "video/quicktime"), "label:item:b")
        assertEquals(8_000, video.durationMs)
        assertEquals(ClipVideo.DEFAULT_MS, video.lengthMs)
        assertTrue(video.file.endsWith(".mov"))
        assertNotNull(files.read("clip-media/t1/${video.file}"))
        assertNull(files.read("picked/a.mov"))
        // An mp4 the phone can't read: refused, nothing left behind.
        files.write("picked/b.mp4", ByteArray(100) { 2 })
        val failed = try {
            ClipMedia.importVideo(files, Movs, "t1", PickedMedia("picked/b.mp4", "video/mp4"), "label:item:b")
            null
        } catch (e: ClipMedia.AddFailed) {
            e
        }
        assertEquals("This file has no video the phone can play", assertNotNull(failed).message)
        assertEquals(listOf(video.file), files.list("clip-media/t1"))
        assertNull(files.read("picked/b.mp4"))
        // What plays of it, in microseconds.
        assertEquals(VideoSource(video.id, "clip-media/t1/${video.file}", 0, 5_000_000), ClipMedia.sources("t1", listOf(video)).getValue(video.id))
    }

    @Test fun theLeftoversAreSweptButNotWhatAnEditUsesOrWhatIsNew() = runTest {
        val files = MemoryMediaFiles()
        val now = com.justdataplease.boringtravel.time.nowMs()
        files.now = { now - 2 * 3_600_000L }
        files.write("clip-media/t1/old.mov", ByteArray(1))
        files.write("clip-media/t1/used.mov", ByteArray(1))
        files.now = { now }
        files.write("clip-voice/t1/new.m4a", ByteArray(1))
        ClipMedia.sweep(files, "t1", setOf("used.mov"))
        assertEquals(listOf("used.mov"), files.list("clip-media/t1"))
        assertEquals(listOf("new.m4a"), files.list("clip-voice/t1"))
    }
}
