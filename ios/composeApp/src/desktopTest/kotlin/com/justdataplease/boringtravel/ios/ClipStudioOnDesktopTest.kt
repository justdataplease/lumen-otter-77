package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.clip.ClipEdits
import com.justdataplease.boringtravel.clip.ClipMedia
import com.justdataplease.boringtravel.clip.ClipPrefs
import com.justdataplease.boringtravel.clip.ClipShape
import com.justdataplease.boringtravel.clip.ClipSource
import com.justdataplease.boringtravel.clip.ClipVideo
import com.justdataplease.boringtravel.clip.ClipVideoFrames
import com.justdataplease.boringtravel.clip.InsertKind
import com.justdataplease.boringtravel.clip.PreparedVideoFrames
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.clip.VoiceLine
import com.justdataplease.boringtravel.clip.music.MusicCatalog
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.clip.PreviewAudio
import com.justdataplease.boringtravel.ui.clip.previewMusicMs
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trip video's state (ios/ClipStudio.kt, Android's ClipViewModel written again) on the desktop, offline, on the
 * demo's Argentina (round 19): every change one undo step within Android's 100, typing as one step, the clip's choices
 * kept for the next clip and the trip's edits in Android's form per route, a phone video copied in with its preview
 * pictures drawn into the frames, a journal video added, moved and taken out, the limits and their messages, the
 * leftover files swept, the music under the preview in step, a leg's travel saved on the trip.
 */
class ClipStudioOnDesktopTest {
    private val trip = SampleArgentina.TRIP_ID

    private fun <T> studio(device: (MemoryMediaFiles) -> ClipDevice = { ClipDevice(FakeSounds(), NoMicrophone, DrawnVideoFiles(it)) }, block: suspend (ClipStudio, MemoryMediaFiles, AppSettings) -> T): T {
        val (book, kit) = demoBook()
        val files = kit.files as MemoryMediaFiles
        val settings = AppSettings.ready()
        return runBlocking {
            val s = readyStudio(book, kit, trip, Look.PostcardPop, this, settings, device = device(files))
            try {
                block(s, files, settings)
            } finally {
                s.close()
            }
        }
    }

    @Test fun everyChangeIsOneUndoStepAndAHundredAreKept() = studio { s, _, settings ->
        assertTrue(!s.history.value.canUndo)
        // The clip's choices: a shape, kept for the next clip; undo puts the last one back, there too.
        s.setShape(ClipShape.Square)
        assertEquals(ClipShape.Square, ClipPrefs.shape(settings.prefs))
        s.undo()
        assertEquals(ClipShape.Story, ClipPrefs.shape(settings.prefs))
        s.redo()
        assertEquals(ClipShape.Square, ClipPrefs.shape(settings.prefs))
        // Typing (the same merge key in a row) is one step.
        val key = requireNotNull(s.plan.value).labels[1].key
        val before = s.history.value.past.size
        for (text in listOf("G", "Gl", "Gla", "Glacier")) s.editScenes("text:$key") { e -> e.change(key) { it.copy(text = text) } }
        assertEquals(before + 1, s.history.value.past.size)
        assertEquals("Glacier", s.design.value.edits.scene(key).text)
        s.undo()
        assertNull(s.design.value.edits.scene(key).text)
        // At most 100 steps.
        repeat(110) { i -> s.setDesign { it.copy(textScale = 1f + (i % 5 + 1) / 100f) } }
        assertEquals(100, s.history.value.past.size)
    }

    @Test fun theTripsEditsAreKeptPerRouteInAndroidsFormAndReadAgain() {
        val (book, kit) = demoBook()
        val files = kit.files as MemoryMediaFiles
        runBlocking {
            val first = readyStudio(book, kit, trip, Look.PostcardPop, this)
            val key = requireNotNull(first.plan.value).labels[2].key
            first.editScenes { e -> e.change(key) { it.copy(text = "Glacier walk", note = "Blue ice") } }
            first.setDayCards(true)
            ClipEditStore.flush()
            first.close()
            val text = assertNotNull(files.read(ClipEditStore.path(trip))).decodeToString()
            assertTrue(text.startsWith("{\"v\":1,\"sources\":{\"Planned\":{\"scenes\":{"), text)
            assertTrue("\"text\":\"Glacier walk\"" in text && "\"note\":\"Blue ice\"" in text, text)
            // A new studio on the same phone opens with them (the day cards are the clip's own choice, in its settings).
            val again = readyStudio(book, kit, trip, Look.PostcardPop, this)
            assertEquals("Glacier walk", again.design.value.edits.scene(key).text)
            assertEquals(ClipSource.Planned, again.source.value)
            again.close()
        }
    }

    @Test fun aPhoneVideoIsCopiedInAndItsPicturesPlayInThePreview() = studio { s, files, _ ->
        val place = requireNotNull(s.plan.value).labels[2].key
        files.write("picked/v.mov", ByteArray(4096) { it.toByte() })
        s.addVideo(PickedMedia("picked/v.mov", "video/quicktime"), place)
        s.settle()
        val video = s.design.value.videos.single()
        assertEquals(place, video.sceneKey)
        assertEquals(ClipVideo.DEFAULT_MS, video.lengthMs)
        assertNotNull(files.read(ClipMedia.videoFile(trip, video)))
        // The plan plays it after its place; the preview's pictures are ready for it.
        val plan = requireNotNull(s.plan.value)
        val insert = plan.inserts.single { it.kind == InsertKind.Video }
        val frames = assertIs<PreparedVideoFrames>(s.videoFrames.value)
        assertEquals(setOf(video.id), frames.ids)
        frames.reader().use { r -> assertNotNull(r.frame(video.id, 1_000_000, 540, 960)) }
        // Drawn into the clip's frame in the middle of the video: its picture, not the map under it.
        val style = requireNotNull(s.style.value)
        val at = (insert.fromFrame + insert.toFrame) / 2
        fun pixels(videos: ClipVideoFrames): IntArray = ClipFrames(plan, s.base.value, style, s.photos.value, s.noMark, s.design.value, 270, 480, videos).use { f ->
            val b = f.draw(at)
            IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        }
        val with = pixels(frames)
        val without = pixels(ClipVideoFrames.NONE)
        assertTrue(with.indices.count { with[it] != without[it] } > with.size / 2, "the video's picture fills the frame")
        // Trimmed and with its sound: one step each; taken out, its file stays until the edits no longer need it.
        s.trimVideo(video.id, 2_000, 3_000)
        assertEquals(2_000L to 3_000L, s.design.value.videos.single().let { it.startMs to it.lengthMs })
        s.removeVideo(video.id)
        assertTrue(s.design.value.videos.isEmpty())
        s.undo()
        assertEquals(1, s.design.value.videos.size)
    }

    @Test fun aJournalVideoIsAddedMovedAndTakenOut() {
        val (book, kit) = demoBook()
        val files = kit.files as MemoryMediaFiles
        files.write("media/jv.mov", ByteArray(9000) { (it % 7).toByte() })
        book.addMedia(Media("jv", tripId = trip, localPath = "media/jv.mov", mime = "video/quicktime", createdAt = 1, updatedAt = 1))
        runBlocking {
            val s = readyStudio(book, kit, trip, Look.PostcardPop, this, device = ClipDevice(FakeSounds(), NoMicrophone, DrawnVideoFiles(files)))
            assertEquals(listOf("jv"), s.tripVideos().map { it.id })
            val places = requireNotNull(s.plan.value).labels.map { it.key }
            s.addJournalVideo("jv", places[1])
            s.settle()
            val copy = s.design.value.videos.single()
            assertEquals("jv", copy.source)
            // A copy of its own, byte for byte.
            assertTrue(files.read(ClipMedia.videoFile(trip, copy))!!.contentEquals(files.read("media/jv.mov")!!))
            s.moveVideo(copy.id, places[3])
            assertEquals(places[3], s.design.value.videos.single().sceneKey)
            s.removeVideo(copy.id)
            assertTrue(s.design.value.videos.isEmpty())
            s.close()
        }
    }

    @Test fun badVideosAndTheEighthAreRefusedWithAndroidsWords() = studio { s, files, _ ->
        val place = requireNotNull(s.plan.value).labels[1].key
        // Not a video the phone can play.
        files.write("picked/notes.mov", ByteArray(0))
        s.addVideo(PickedMedia("picked/notes.mov", "video/quicktime"), place)
        s.settle()
        assertEquals(ClipStudio.MediaWork.Failed("This file has no video the phone can play"), s.mediaWork.value)
        assertNull(files.read("picked/notes.mov"))
        s.dismissMediaNote()
        // Eight in, then the ninth says so.
        repeat(ClipVideo.MAX_VIDEOS) { i ->
            files.write("picked/v$i.mov", ByteArray(100) { 1 })
            s.addVideo(PickedMedia("picked/v$i.mov", "video/quicktime"), place)
            s.settle()
        }
        assertEquals(ClipVideo.MAX_VIDEOS, s.design.value.videos.size)
        files.write("picked/one-more.mov", ByteArray(100) { 1 })
        s.addVideo(PickedMedia("picked/one-more.mov", "video/quicktime"), place)
        assertEquals(ClipStudio.MediaWork.Failed("A clip takes up to 8 videos."), s.mediaWork.value)
        assertNull(files.read("picked/one-more.mov"))
    }

    @Test fun leftoverVideosAndLinesGoWhenTheEditorOpensAgain() {
        val (book, kit) = demoBook()
        val files = kit.files as MemoryMediaFiles
        // Two hours ago: a video and a line no edit uses, and a line one does.
        files.now = { com.justdataplease.boringtravel.time.nowMs() - 2 * 3_600_000L }
        files.write(ClipMedia.videoDir(trip) + "/old.mov", ByteArray(10))
        files.write(ClipMedia.voiceDir(trip) + "/old.m4a", ByteArray(10))
        files.write(ClipMedia.voiceDir(trip) + "/kept.m4a", ByteArray(10))
        ClipEditStore.save(files, trip, mapOf(ClipSource.Planned to ClipEdits(voices = listOf(VoiceLine("label:x", "kept.m4a", 2_000)))))
        // Just now: a video whose edit may still be on its way to the file.
        files.now = { com.justdataplease.boringtravel.time.nowMs() }
        files.write(ClipMedia.videoDir(trip) + "/new.mov", ByteArray(10))
        runBlocking {
            val s = readyStudio(book, kit, trip, Look.PostcardPop, this)
            s.close()
        }
        assertNull(files.read(ClipMedia.videoDir(trip) + "/old.mov"))
        assertNull(files.read(ClipMedia.voiceDir(trip) + "/old.m4a"))
        // What an edit uses stays, and so does a file from the last hour.
        assertNotNull(files.read(ClipMedia.voiceDir(trip) + "/kept.m4a"))
        assertNotNull(files.read(ClipMedia.videoDir(trip) + "/new.mov"))
    }

    @Test fun aVoiceLineIsRecordedIntoTheTripsFolderAndKept() = studio(device = { files -> ClipDevice(FakeSounds(), FakeMicrophone(files, MicAccess.Allowed), NoVideoFiles) }) { s, files, _ ->
        val place = requireNotNull(s.plan.value).labels[3].key
        s.startVoice(place)
        assertIs<ClipStudio.Voice.Recording>(s.voice.value)
        delay(600)
        s.stopVoice()
        assertEquals(ClipStudio.Voice.Idle, s.voice.value)
        val line = s.design.value.voice(place)!!
        assertTrue(line.file.endsWith(".m4a"))
        assertNotNull(files.read(ClipMedia.voiceFile(trip, line)))
        // The music goes quieter under it in the export (round 20): its file is where the export reads it.
        assertEquals(ClipMedia.voiceDir(trip) + "/" + line.file, s.voiceFile(line))
    }

    @Test fun theMusicUnderThePreviewStartsInStepAndOnlyJumpsWhenItDrifts() {
        val track = MusicCatalog.byId("latin_groove")!!
        val start = MusicCatalog.startFor(track.id, null)
        // Frame 60 at 30 fps: two seconds after where the clip's music starts.
        assertEquals((start * 1000).toInt() + 2_000, previewMusicMs(60, 30, start, track.durationMs))
        // Past the track's end it loops back to where it starts.
        val loopS = track.durationMs / 1000.0 - start
        assertTrue(previewMusicMs((loopS * 30).toInt() + 1, 30, start, track.durationMs) - (start * 1000).toInt() in 0..80)
        val sounds = FakeSounds()
        val audio = PreviewAudio(sounds, track)
        audio.playAt(10_000)
        audio.playAt(10_150)
        assertEquals(listOf("open latin_groove.m4a", "play 10000"), sounds.events)
        sounds.players.single().positionMs = 10_000
        audio.playAt(10_400)
        assertEquals("seek 10400", sounds.events.last())
        audio.pause()
        audio.release()
        assertEquals(listOf("pause", "release"), sounds.events.takeLast(2))
    }

    @Test fun aLegsTravelChosenInTheVideoIsTheTripsAndReplans() {
        val (book, kit) = demoBook()
        runBlocking {
            val s = readyStudio(book, kit, trip, Look.PostcardPop, this)
            val plan = requireNotNull(s.plan.value)
            val (key, leg) = plan.labels.mapNotNull { l -> s.legInto(l.key)?.let { l.key to it } }.first { it.second.vehicle != Vehicle.Train }
            assertNotEquals(Vehicle.Train, leg.vehicle)
            s.setLegVehicle(leg, Vehicle.Train)
            s.settle()
            assertEquals(Vehicle.Train, TravelModes.choices(book.trip(trip)!!.travelModes)[leg.key])
            assertEquals(Vehicle.Train, s.legInto(key)?.vehicle)
            assertTrue(!s.legSaveFailed.value)
            s.close()
        }
    }
}
