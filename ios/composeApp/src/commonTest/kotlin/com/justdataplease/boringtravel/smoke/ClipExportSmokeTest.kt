package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.backup.BackupExtras
import com.justdataplease.boringtravel.clip.ClipTarget
import com.justdataplease.boringtravel.clip.ClipText
import com.justdataplease.boringtravel.clip.DecodedVideo
import com.justdataplease.boringtravel.clip.DecodedVideoFrames
import com.justdataplease.boringtravel.clip.ExportBar
import com.justdataplease.boringtravel.clip.ExportProgress
import com.justdataplease.boringtravel.clip.ExportStage
import com.justdataplease.boringtravel.clip.Person
import com.justdataplease.boringtravel.clip.RideModel
import com.justdataplease.boringtravel.clip.Rides
import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.clip.VideoSource
import com.justdataplease.boringtravel.clip.Who
import com.justdataplease.boringtravel.clip.music.ClipMusic
import com.justdataplease.boringtravel.clip.music.MusicCatalog
import com.justdataplease.boringtravel.clip.music.MusicMix
import com.justdataplease.boringtravel.clip.music.Pcm
import com.justdataplease.boringtravel.clip.music.SoundDecoder
import com.justdataplease.boringtravel.data.SavedClip
import com.justdataplease.boringtravel.data.SavedClipIndex
import com.justdataplease.boringtravel.ios.ClipExporter
import com.justdataplease.boringtravel.ios.ClipVideoFiles
import com.justdataplease.boringtravel.ios.MemoryMediaFiles
import com.justdataplease.boringtravel.ios.VideoFacts
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.traveller.RideChoices
import com.justdataplease.boringtravel.ui.traveller.TravellerChoices
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 20's code read in from Android or written here for the export, run on the desktop's JVM and on Kotlin/Native in
 * the iPhone Simulator, where regular expressions, text functions and Skia can behave differently: the traveller's and
 * rides' rows, the file's name (a Unicode regular expression), the saved clips' sidecar (JSON), the backup's extras'
 * names (regular expressions), the export's progress, the sound's mix, a phone video's pictures picked and turned.
 */
class ClipExportSmokeTest {
    @Test fun theTravellersAndRidesRowsAndStringsReadTheSame() {
        assertEquals(
            listOf("skin", "hair", "face", "hairColour", "top", "topColour", "bottom", "bottomColour", "hat", "glasses", "bag"),
            TravellerChoices.rows(Person.MAN).map { it.key },
        )
        assertEquals("Couple, hand in hand", TravellerChoices.summary(Traveller.DEFAULT.copy(who = Who.Couple)))
        val car = assertNotNull(Rides.DEFAULT[Vehicle.Car])
        assertEquals(listOf("Model", "Body", "Trim"), RideChoices.rows(car, 0, 0).map { it.label })
        assertEquals("Boat", RideChoices.label(Vehicle.Ferry))
        assertNull(RideChoices.summary(Rides.DEFAULT))
        val rides = Rides.DEFAULT.with(car.copy(model = RideModel.Suv))
        assertEquals("SUV", RideChoices.summary(rides))
        // Their strings, as a backup carries them, read back the same.
        val woman = Traveller.DEFAULT.copy(who = Who.Woman)
        assertEquals(woman, Traveller.decode(woman.encode()))
        assertEquals(rides, Rides.decode(rides.encode()))
    }

    @Test fun theFilesNameKeepsLettersOfEveryLanguage() {
        assertEquals("Argentina-Patagonia-clip.mp4", ClipText.fileName("Argentina & Patagonia"))
        assertEquals("Αθήνα-Ναύπλιο-clip.mp4", ClipText.fileName("Αθήνα & Ναύπλιο"))
        assertEquals("trip-clip.mp4", ClipText.fileName("!!!"))
        assertEquals("exports/Kyoto-Osaka-clip.mp4", ClipExporter.outputPath("Kyoto & Osaka"))
    }

    @Test fun aSavedClipsSidecarReadsBackAndListsOnlyWholeClips() {
        val clip = SavedClip("1730000000000-ab12cd34", "t1", "Argentina", 1_000, 42_000, 1080, 1920)
        assertEquals(clip, SavedClipIndex.decode(SavedClipIndex.encode(clip)))
        assertNull(SavedClipIndex.decode("""{"id":"a","trip":"t1","at":"yesterday"}"""))
        assertEquals("1730000000000-ab12cd34", SavedClipIndex.newId(1_730_000_000_000, "ab12-cd34-ef56"))
        assertEquals("1:05", SavedClipIndex.length(65_400))
        val files = MemoryMediaFiles()
        files.write(SavedClipIndex.video(clip.id), byteArrayOf(1, 2, 3))
        files.write(SavedClipIndex.meta(clip.id), SavedClipIndex.encode(clip).encodeToByteArray())
        files.write(SavedClipIndex.meta("orphan"), SavedClipIndex.encode(clip.copy(id = "orphan")).encodeToByteArray())
        assertEquals(listOf(clip), SavedClipIndex.list(files))
        assertEquals(listOf(clip.id), SavedClipIndex.deleteTrip(files, "t1").filter { it == clip.id })
        assertTrue(SavedClipIndex.list(files).isEmpty())
    }

    @Test fun theBackupsExtrasNamesAreCheckedAsOnAndroid() {
        assertEquals("clip-edits/t-1_x.json", BackupExtras.target("extras/clip-edits/t-1_x.json"))
        assertEquals("clip-voice/t1/line-1.m4a", BackupExtras.target("extras/clip-voice/t1/line-1.m4a"))
        assertEquals("clips/1730000000000-ab12cd34.mp4", BackupExtras.target("extras/clips/1730000000000-ab12cd34.mp4"))
        assertNull(BackupExtras.target("extras/clip-media/t1/../x.mp4"))
        assertNull(BackupExtras.target("extras/clip-media/t1/.hidden"))
        assertNull(BackupExtras.target("extras/clips/a/b.mp4"))
        assertNull(BackupExtras.target("media/x.jpg"))
        assertEquals("extras/clip-media/t1/v1.mov", BackupExtras.entry("clip-media/t1/v1.mov"))
    }

    @Test fun theExportsBarNeverGoesBackAndStopsShortOfDone() {
        val seen = mutableListOf<Pair<Float, ExportStage>>()
        val bar = ExportBar(hasSound = true) { p, s -> seen += p to s }
        for (f in 1..300) bar.frames(f / 300f)
        bar.sound(0.25f)
        bar.framesDone()
        bar.sound(0.6f)
        bar.sound(1f)
        bar.finishing()
        assertTrue(seen.map { it.first }.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(ExportProgress.LAST, seen.last().first)
        assertEquals(listOf(ExportStage.Picture, ExportStage.Sound, ExportStage.Finishing), seen.map { it.second }.distinct())
        assertEquals("TikTok isn't on this phone. Save the video to your phone and add it there.", ClipTarget.missing(ClipTarget.TikTok))
        assertEquals(listOf(ClipTarget.TikTok, ClipTarget.WhatsApp), ClipTarget.shown { it == ClipTarget.WhatsApp })
    }

    @Test fun theSoundIsMixedAsAndroidMixesIt() = runTest {
        // Music as a steady tone (44.1 kHz stereo), a silent voice line from 1 s for 1 s: faded, ducked under the voice.
        val decoder = SoundDecoder { location, _, lengthUs, _ ->
            val music = "music/" in location
            val rate = if (music) 44_100 else 22_050
            val channels = if (music) 2 else 1
            val frames = (lengthUs * rate / 1_000_000).toInt()
            Pcm(ShortArray(frames * channels) { if (music) (if (it % 4 < 2) 6_000 else -6_000).toShort() else 0 }, frames, rate, channels)
        }
        val track = assertNotNull(MusicCatalog.byId("latin_groove"))
        val mixed = assertNotNull(
            ClipMusic.prepare(decoder, 4_000_000, track, "files/" + track.asset, 0, listOf(ClipMusic.voice("voice.m4a", 1_000_000, 1_000_000))),
        )
        assertEquals(MusicMix.frames(4_000_000L, 44_100), mixed.totalFrames)
        fun rms(fromS: Double, toS: Double): Double {
            val n = ((toS - fromS) * 44_100).toInt()
            val out = ShortArray(n * 2)
            mixed.render((fromS * 44_100).toLong(), n, out)
            return sqrt(out.sumOf { it.toDouble() * it } / out.size)
        }
        val first = ShortArray(2).also { mixed.render(0, 1, it) }
        assertEquals(0, abs(first[0].toInt()))
        assertEquals(MusicMix.DUCK_GAIN.toDouble(), rms(1.2, 1.9) / rms(0.4, 0.8), 0.03)
    }

    /**
     * A video of three pictures stored on its side (to be turned 90° clockwise): at 0 its left half red and its right
     * half blue, at 40 ms green, at 80 ms blue.
     */
    private class ThreePictures : DecodedVideo {
        private var next = 0
        override val rotation: Int = 90
        override fun next(use: (picture: Image, timeUs: Long) -> Unit): Boolean {
            if (next >= 3) return false
            val surface = Surface.makeRasterN32Premul(40, 20)
            surface.canvas.clear(listOf(Color.BLUE, Color.GREEN, Color.BLUE)[next])
            if (next == 0) surface.canvas.drawRect(org.jetbrains.skia.Rect.makeWH(20f, 20f), org.jetbrains.skia.Paint().apply { color = Color.RED })
            val image = surface.makeImageSnapshot()
            try {
                use(image, next * 40_000L)
            } finally {
                image.close()
                surface.close()
            }
            next++
            return true
        }

        override fun close() {}
    }

    @Test fun aPhoneVideosPicturesArePickedByTimeAndTurnedAsAndroidDoes() {
        val files = object : ClipVideoFiles {
            override fun probe(path: String): VideoFacts? = null
            override fun pictures(path: String, timesUs: LongArray, width: Int, height: Int, each: (Int, ByteArray?) -> Boolean) {}
            override fun open(path: String, fromUs: Long, lengthUs: Long): DecodedVideo = ThreePictures()
        }
        val frames = DecodedVideoFrames(mapOf("v" to VideoSource("v", "clip-media/t/v.mov", 0, 120_000)), files)
        frames.reader().use { reader ->
            // The last picture at or before each time; the clip's frame (a tall 20 × 40) filled by the turned picture: the
            // stored picture's left side on top (turned clockwise, as Android's KEY_ROTATION and the iPhone's transform).
            val at0 = assertNotNull(reader.frame("v", 0, 20, 40))
            assertEquals(20 to 40, at0.width to at0.height)
            assertEquals(Color.RED, at0.getPixel(10, 5))
            assertEquals(Color.BLUE, at0.getPixel(10, 35))
            assertEquals(Color.GREEN, assertNotNull(reader.frame("v", 50_000, 20, 40)).getPixel(10, 20))
            assertEquals(Color.BLUE, assertNotNull(reader.frame("v", 100_000, 20, 40)).getPixel(10, 20))
        }
    }
}
