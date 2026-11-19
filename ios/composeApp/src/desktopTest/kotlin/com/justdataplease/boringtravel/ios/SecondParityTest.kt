package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.calendar.CalendarLinks
import com.justdataplease.boringtravel.calendar.CalendarSync
import com.justdataplease.boringtravel.calendar.MemoryPhoneCalendar
import com.justdataplease.boringtravel.calendar.TripZone
import com.justdataplease.boringtravel.clip.ClipShape
import com.justdataplease.boringtravel.data.SavedClipIndex
import com.justdataplease.boringtravel.data.SavedClips
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.repo.VideoLimits
import com.justdataplease.boringtravel.data.settings.TripPhotoStyle
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.geo.BBox
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.ui.clip.FullScreenVideo
import com.justdataplease.boringtravel.ui.clip.VideoSpot
import com.justdataplease.boringtravel.ui.map.MapData
import com.justdataplease.boringtravel.ui.map.MapMark
import com.justdataplease.boringtravel.ui.map.MapTap
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The second parity round of 19 Nov 2026 (the owner: "we need to be ios and android exactly the same"): Android's own
 * video player for a note's video, a saved clip and Watch; a note's video made 720p with "Preparing the video… 40%",
 * its X and "Stop adding the video?"; photos kept in their own format with their tags read from any format, the
 * camera's metadata kept; New trip and Edit trip's cover and "Photos in notes"; a plan item's Journal and its Write;
 * the turned map's taps; the calendar's zone from the planned places' countries; a restored clip's picture; the export
 * going on away from the screen where the phone lets it; the Info.plist's new keys.
 */
@OptIn(ExperimentalTestApi::class)
class SecondParityTest {
    private val today = SAMPLE_TODAY

    init {
        runBlocking {
            BtFontFiles.load()
            CountryOutlines.load()
            CountryZones.load()
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ComposeUiTest.exists(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** A picker that hands over what [next] holds, its files put where a picker puts them. */
    private class FakePicker(private val files: MediaFiles) : MediaPicker {
        var next: List<Pair<PickedMedia, ByteArray>> = emptyList()

        override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) = done(next.take(max).map { (p, b) -> files.write(p.path, b); p })
        override val hasCamera: Boolean = true
        override fun takePhoto(done: (PickedMedia?) -> Unit) = done(null)
        override fun recordVideo(done: (PickedMedia?) -> Unit) = done(null)
    }

    /**
     * A phone's video work in memory: what each video says about itself ([probes], by path, the written one by its
     * folder), every write asked ([asked]), "40%" at once, then waiting for [gate] when there is one; it fails with
     * [fail], else writes [made] at the place asked.
     */
    private class FakeVideoPrep(
        private val files: MediaFiles, private val probes: (String) -> VideoProbe, val fail: Boolean = false,
        val gate: CompletableDeferred<Unit>? = null, private val made: ByteArray = "720p mp4".encodeToByteArray(),
    ) : VideoPrep {
        val asked = mutableListOf<String>()

        override fun probe(path: String): VideoProbe = probes(path)

        override suspend fun transcode(source: String, out: String, probe: VideoProbe, bitrate: Int, shortSide: Int?, audioBitrate: Int, onProgress: (Float) -> Unit) {
            asked += "$source → $out at $bitrate, short side $shortSide, sound $audioBitrate"
            onProgress(0.4f)
            gate?.await()
            if (fail) throw IllegalStateException("No encoder for this video")
            files.write(out, made)
            onProgress(1f)
        }
    }

    // --- 1. The video player ------------------------------------------------------------------------------------------

    @Test
    fun aNotesVideoPlaysInAndroidsOwnPlayer() = runDesktopComposeUiTest(width = 440, height = 956) {
        val files = MemoryMediaFiles()
        files.write("media/v.mp4", ByteArray(64) { it.toByte() })
        val playback = StillVideoPlayback({ 45_000 })
        val kit = MediaKit(files, playback = playback)
        val media = Media("v", localPath = "media/v.mp4", mime = "video/mp4", createdAt = 1, updatedAt = 1)
        var closed by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalMediaKit provides kit) { BoringTravelTheme(Look.Default) { if (!closed) PlayVideo(media) { closed = true } } }
        }
        waitUntil(timeoutMillis = 10_000) { playback.opened.isNotEmpty() && exists("0:00 / 0:45") }
        val video = playback.opened.single()
        // Plays at once, once (a journal's video), with Android's controls under it.
        assertEquals(listOf("play"), video.asked)
        assertFalse(video.loop)
        onAllNodesWithContentDescription("Pause").onFirst().performClick()
        waitForIdle()
        assertEquals(listOf("play", "pause"), video.asked)
        // Paused: the play mark, and the button says Play.
        onAllNodesWithContentDescription("Play").onFirst().assertExists()
        onNodeWithContentDescription("Close").performClick()
        waitForIdle()
        assertTrue(closed)
        assertEquals("release", video.asked.last())
    }

    @Test
    fun aVideoThePhoneCantPlaySaysSoAndAMissingOneIsSaid() = runDesktopComposeUiTest(width = 440, height = 956) {
        val files = MemoryMediaFiles()
        files.write("media/bad.mp4", ByteArray(8))
        val kit = MediaKit(files, playback = StillVideoPlayback({ null }))
        val said = mutableListOf<String>()
        var closedMissing = false
        setContent {
            CompositionLocalProvider(LocalMediaKit provides kit, LocalSay provides { said += it }) {
                BoringTravelTheme(Look.Default) {
                    PlayVideo(Media("bad", localPath = "media/bad.mp4", mime = "video/mp4", createdAt = 1, updatedAt = 1)) {}
                    PlayVideo(Media("gone", localPath = "media/gone.mp4", mime = "video/mp4", createdAt = 1, updatedAt = 1)) { closedMissing = true }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { exists("This video can't be played.") && closedMissing }
        assertEquals(listOf("This video isn't on this phone yet. Try again in a moment."), said)
    }

    @Test
    fun watchIsAndroidsFullScreenVideoWithItsControls() = runDesktopComposeUiTest(width = 440, height = 956) {
        val files = MemoryMediaFiles()
        files.write("exports/clip.mp4", ByteArray(64))
        val playback = StillVideoPlayback({ 42_000 })
        val spot = VideoSpot()
        var closed = false
        setContent {
            CompositionLocalProvider(LocalMediaKit provides MediaKit(files, playback = playback)) {
                BoringTravelTheme(Look.Default) { FullScreenVideo("exports/clip.mp4", ClipShape.entries.first(), spot) { closed = true } }
            }
        }
        waitUntil(timeoutMillis = 10_000) { playback.opened.isNotEmpty() }
        // It doesn't start by itself (Android's FullScreenVideo): it shows the first picture with the play mark.
        waitForIdle()
        val video = playback.opened.single()
        assertEquals(listOf("seek 1"), video.asked)
        onAllNodesWithContentDescription("Play video").onFirst().performClick()
        waitForIdle()
        assertTrue("play" in video.asked)
        onNodeWithContentDescription("Close full screen").performClick()
        waitForIdle()
        assertTrue(closed)
        // Its place handed back to the inline one.
        assertEquals(1, spot.handedBack)
    }

    // --- 2. A note's video made 720p ----------------------------------------------------------------------------------

    @Test
    fun aNotesVideoIsWrittenAgainAs720pWithAndroidsLimits() = runBlocking<Unit> {
        val files = MemoryMediaFiles()
        val source = kotlin.random.Random(5).let { r -> ByteArray(50_000) { r.nextInt().toByte() } }
        files.write("picked/v.mov", source)
        val tagged = VideoProbe(61_000, 1080, 1920, takenAt = 1_794_478_530_000L, lat = 37.7749, lng = -122.4194)
        val prep = FakeVideoPrep(files, { if (it.startsWith("journal-videos/")) VideoProbe(61_000, 720, 1280) else tagged })
        val progress = mutableListOf<Float>()
        val v = MediaImporter(files, prep) { "v1" }.prepareVideo(PickedMedia("picked/v.mov", "video/quicktime"), "t", "e") { progress += it }
        // Android's: an MP4, H.264 at 3 Mbit/s for a minute's video, its short side 720, AAC at 128 kbit/s.
        assertEquals(listOf("picked/v.mov → journal-videos/v1.mp4 at 3000000, short side 720, sound 128000"), prep.asked)
        assertEquals("media/v1.mp4", v.media.localPath)
        assertEquals("video/mp4", v.media.mime)
        assertEquals(720 to 1280, v.media.width to v.media.height)
        // Its time and place from its own tags (the MP4 date, the ISO 6709 location).
        assertEquals(1_794_478_530_000L, v.media.takenAt)
        assertEquals(37.7749, v.media.lat)
        assertEquals(61_000L, v.durationMs)
        assertEquals(sha("720p mp4".encodeToByteArray()), v.media.sha256)
        assertEquals(listOf(0.4f, 1f), progress)
        // Nothing else stays: the picked copy and the work folder are gone.
        assertEquals(listOf("media/v1.mp4"), files.paths)
        assertEquals(40, VideoLimits.percent(progress.first()))
    }

    @Test
    fun aVideoThePhoneCantWriteAgainGoesInAsItIsWithinAndroidsSize() = runBlocking<Unit> {
        val files = MemoryMediaFiles()
        files.write("picked/v.mov", ByteArray(3_000) { 1 })
        val prep = FakeVideoPrep(files, { VideoProbe(20_000, 1920, 1080) }, fail = true)
        val v = MediaImporter(files, prep) { "v2" }.prepareVideo(PickedMedia("picked/v.mov", "video/quicktime"), "t", "e") {}
        assertEquals("media/v2.mov", v.media.localPath)
        assertEquals("video/quicktime", v.media.mime)
        assertEquals(1920 to 1080, v.media.width to v.media.height)
        assertEquals(listOf("media/v2.mov"), files.paths)
        // Too big to go in as it is (over 240 MB): Android's words, nothing left.
        val big = object : MediaFiles by files {
            override fun size(path: String): Long? = if (path == "picked/big.mov") 241L * 1024 * 1024 else files.size(path)
        }
        files.write("picked/big.mov", ByteArray(10))
        val e = assertFailsWith<MediaImportException> {
            MediaImporter(big, FakeVideoPrep(files, { VideoProbe(20_000, 1920, 1080) }, fail = true)) { "v3" }.prepareVideo(PickedMedia("picked/big.mov", "video/quicktime"), "t", "e") {}
        }
        assertEquals("This video couldn't be prepared on this phone.", e.message)
        // Over 30 minutes: Android's words before any work.
        files.write("picked/long.mov", ByteArray(10))
        val long = assertFailsWith<MediaImportException> {
            MediaImporter(files, FakeVideoPrep(files, { VideoProbe(31 * 60_000L, 1920, 1080) })) { "v4" }.prepareVideo(PickedMedia("picked/long.mov", "video/quicktime"), "t", "e") {}
        }
        assertEquals("This video is too long. Videos can be up to 30 minutes.", long.message)
        assertEquals(listOf("media/v2.mov"), files.paths)
    }

    @Test
    fun stoppingAVideoBeingPreparedLeavesNothingBehind() = runBlocking<Unit> {
        val files = MemoryMediaFiles()
        files.write("picked/v.mov", ByteArray(100))
        val gate = CompletableDeferred<Unit>()
        val prep = FakeVideoPrep(files, { VideoProbe(20_000, 1920, 1080) }, gate = gate)
        val job = launch { MediaImporter(files, prep) { "v5" }.prepareVideo(PickedMedia("picked/v.mov", "video/quicktime"), "t", "e") {} }
        while (prep.asked.isEmpty()) kotlinx.coroutines.delay(5)
        job.cancel()
        job.join()
        assertEquals(emptyList(), files.paths)
    }

    @Test
    fun writeSaysPreparingTheVideoWithItsXAndAsksBeforeLeaving() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val files = MemoryMediaFiles()
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos", today.minusDays(1), today.plusDays(3))
        val gate = CompletableDeferred<Unit>()
        val prep = FakeVideoPrep(files, { VideoProbe(20_000, 1920, 1080) }, gate = gate)
        val picker = FakePicker(files).apply { next = listOf(PickedMedia("picked/v.mov", "video/quicktime") to ByteArray(100)) }
        var left = false
        setContent {
            CompositionLocalProvider(LocalMediaKit provides MediaKit(files, picker, videos = prep)) {
                BoringTravelTheme(Look.Default) { WriteScreen(book, trip.id, null, today, onDone = { left = true }) }
            }
        }
        onNodeWithContentDescription("Choose photos and videos").performClick()
        waitUntil(timeoutMillis = 10_000) { exists("Preparing the video… 40%") }
        onNodeWithContentDescription("Stop adding the video").assertExists()
        // The screen stays on meanwhile (Android's keepScreenOn).
        assertTrue(ScreenKeptOn.now)
        // Leaving asks first; Wait goes on.
        onNodeWithContentDescription("Back").performClick()
        waitUntil(timeoutMillis = 5_000) { exists("Stop adding the video?") }
        onNodeWithText("It isn't in the note yet. Wait, or stop and leave.").assertExists()
        onNodeWithText("Wait").performClick()
        waitForIdle()
        assertFalse(left)
        assertTrue(exists("Preparing the video… 40%"))
        // Stop: nothing of it stays, and the note (empty) is left.
        onNodeWithContentDescription("Back").performClick()
        waitUntil(timeoutMillis = 5_000) { exists("Stop adding the video?") }
        onNodeWithText("Stop").performClick()
        waitUntil(timeoutMillis = 5_000) { left }
        assertTrue(files.paths.none { it.startsWith("media/") || it.startsWith("picked/") || it.startsWith("journal-videos/") }, files.paths.toString())
        assertFalse(ScreenKeptOn.now)
        assertTrue(book.snapshot.media.isEmpty())
    }

    /**
     * Round 23: with Drive connected and Back up every day on, a video that went into a note says it takes a while to
     * upload, with its length when it is over a minute (Android's JournalLogic.videoAdded); without Drive, nothing.
     */
    @Test
    fun aVideoThatGoesToDriveSaysItTakesAWhileToUpload() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val files = MemoryMediaFiles()
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos", today.minusDays(1), today.plusDays(3))
        val prep = FakeVideoPrep(files, { VideoProbe(150_000, 1920, 1080) })
        val picker = FakePicker(files).apply { next = listOf(PickedMedia("picked/v.mov", "video/quicktime") to ByteArray(100)) }
        val settings = AppSettings.ready()
        val account = FakeGoogle().account(settings, files)
        runBlocking { account.connectDrive() }
        setContent {
            CompositionLocalProvider(
                LocalMediaKit provides MediaKit(files, picker, videos = prep),
                com.justdataplease.boringtravel.google.LocalGoogle provides account,
            ) {
                BoringTravelTheme(Look.Default) { WriteScreen(book, trip.id, null, today, onDone = {}) }
            }
        }
        onNodeWithContentDescription("Choose photos and videos").performClick()
        waitUntil(timeoutMillis = 10_000) { exists("This video is 3 min long. It will take a while to upload.") }
        // The words themselves: a short video, and none without Drive.
        assertEquals("Videos take a while to upload.", videoAdded(40_000, uploads = true))
        assertEquals("Videos take a while to upload.", videoAdded(null, uploads = true))
        assertNull(videoAdded(150_000, uploads = false))
        settings.autoBackup = false
        assertFalse(account.backsUpMedia)
    }

    // --- 3. Photos in their own format, tags from any format ------------------------------------------------------------

    /** A phone's reader that knows a made-up "HEIC" (bytes starting "HEIC"): its size, tags and a JPEG of it. */
    private class FakeHeicReader(private val jpeg: ByteArray) : PhoneImages {
        override fun facts(bytes: ByteArray): ImageFacts? = if (!bytes.isHeic()) null else ImageFacts(
            "image/heic", 3024, 4032,
            Exif.Tags(dateTimeOriginal = "2027:06:19 13:10:05", offsetTimeOriginal = "+03:00", dateTime = "2027:06:20 08:00:00", lat = 37.1, lng = 25.4),
        )

        override fun jpeg(bytes: ByteArray, maxSide: Int?, quality: Int): ByteArray? = if (bytes.isHeic()) jpeg else null

        private fun ByteArray.isHeic() = size > 4 && decodeToString(0, 4) == "HEIC"
    }

    private fun <T> withHeic(block: () -> T): T {
        val before = Images.phone
        Images.phone = FakeHeicReader(samplePhoto(Scene.Sea, 300, 400))
        try {
            return block()
        } finally {
            Images.phone = before
        }
    }

    @Test
    fun aHeicPhotoStaysAHeicWithItsTimePlaceAndSizeAndIsDrawn() = withHeic<Unit> {
        val files = MemoryMediaFiles()
        val heic = "HEIC".encodeToByteArray() + ByteArray(200) { 7 }
        files.write("picked/p.heic", heic)
        val m = MediaImporter(files) { "p1" }.prepare(PickedMedia("picked/p.heic", "image/heic"), "t", "e")
        // Kept as Android keeps it: the file itself, .heic, its type.
        assertEquals("media/p1.heic", m.localPath)
        assertEquals("image/heic", m.mime)
        assertTrue(heic.contentEquals(files.read("media/p1.heic")))
        assertEquals(sha(heic), m.sha256)
        // Its size as shown and its time and place, read by the phone's reader (any format, as ExifInterface).
        assertEquals(3024 to 4032, m.width to m.height)
        assertEquals(Exif.parseTime("2027:06:19 13:10:05", "+03:00"), m.takenAt)
        assertEquals(37.1, m.lat)
        // Drawn through the phone's reader's JPEG (Skia reads no HEIC).
        val picture = assertNotNull(Pictures.decode(heic, 200))
        assertEquals(150 to 200, picture.width to picture.height)
        assertEquals(3024 to 4032, Pictures.size(heic))
    }

    @Test
    fun copyFromALinkKeepsAHeicAndMakesAnAvifAJpegAsAndroidDoes() = withHeic<Unit> {
        runBlocking<Unit> {
            val files = MemoryMediaFiles()
            val heic = "HEIC".encodeToByteArray() + ByteArray(100) { 3 }
            val avifJpeg = samplePhoto(Scene.Town, 200, 100)
            val reader = Images.phone
            // AVIF: the phone reads it, the app doesn't keep that type: a JPEG of it, its tags left behind.
            Images.phone = object : PhoneImages {
                override fun facts(bytes: ByteArray): ImageFacts? =
                    if (bytes.size > 4 && bytes.decodeToString(0, 4) == "AVIF") ImageFacts("image/avif", 200, 100, Exif.Tags(dateTimeOriginal = "2027:06:19 13:10:05")) else reader.facts(bytes)

                override fun jpeg(bytes: ByteArray, maxSide: Int?, quality: Int): ByteArray? =
                    if (bytes.size > 4 && bytes.decodeToString(0, 4) == "AVIF") avifJpeg else reader.jpeg(bytes, maxSide, quality)
            }
            val net = object : Net {
                override suspend fun get(request: NetRequest): NetAnswer = when (request.url) {
                    "https://example.org/p.heic" -> NetAnswer(200, request.url, mapOf("Content-Type" to "image/heic"), heic)
                    else -> NetAnswer(200, request.url, mapOf("Content-Type" to "image/avif"), "AVIF".encodeToByteArray() + ByteArray(40))
                }
            }
            val store = BookSiteStore(TripBook(MemoryTripFile()), files, net, "BoringTravel/test", { _, _ -> emptyList() }, inlineDir = null)
            val kept = store.savePicture("https://example.org/p.heic", "h1", null).media
            assertEquals("media/h1.heic", kept.localPath)
            // Android's decoder names a HEIC "image/heif"; its time from DateTimeOriginal only, its place.
            assertEquals("image/heif", kept.mime)
            assertEquals(Exif.parseTime("2027:06:19 13:10:05", "+03:00"), kept.takenAt)
            assertEquals(25.4, kept.lng)
            val made = store.savePicture("https://example.org/p.avif", "a1", null).media
            assertEquals("media/a1.jpg", made.localPath)
            assertEquals("image/jpeg", made.mime)
            assertEquals(200 to 100, made.width to made.height)
            assertNull(made.takenAt)
        }
    }

    // --- 4. New trip and Edit trip: the cover and Photos in notes ---------------------------------------------------------

    @Test
    fun newTripTakesACoverAndItsPhotosInNotesAsAndroidKeepsThem() = runDesktopComposeUiTest(width = 440, height = 2000) {
        val files = MemoryMediaFiles()
        val book = TripBook(MemoryTripFile())
        val settings = AppSettings.ready()
        val picker = FakePicker(files).apply { next = listOf(PickedMedia("picked/c.jpg", MediaTypes.JPEG) to samplePhoto(Scene.Olive, 400, 300)) }
        var saved: String? = null
        setContent {
            CompositionLocalProvider(LocalMediaKit provides MediaKit(files, picker), LocalMediaRows provides { id: String -> book.media(id) }) {
                BoringTravelTheme(Look.Default) { TripFormScreen(book, null, {}, { saved = it }, manualAtStart = true, settings = settings) }
            }
        }
        // Android's defaults: Full and Rounded, a new trip starting from the last choice (none yet).
        onNodeWithText("Photos in notes", ignoreCase = true).performScrollTo()
        onNodeWithText("New photos and videos in this trip's notes start like this; a layout takes the frame. You can change each one.").assertExists()
        onNodeWithText("Medium").performClick()
        onNodeWithText("Polaroid").performClick()
        onAllNodes(hasSetTextAction())[0].performTextInput("Lake Como")
        onAllNodesWithText("Add a cover photo").onFirst().performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { exists("Change photo") }
        onNodeWithText("Remove").assertExists()
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 5_000) { saved != null }
        val trip = assertNotNull(book.trip(assertNotNull(saved)))
        val cover = assertNotNull(book.media(assertNotNull(trip.coverMediaId)))
        // The photo picked before the trip existed is filed under it now.
        assertEquals(trip.id, cover.tripId)
        assertEquals(TripPhotoStyle(0.65f, PhotoFrame.Polaroid), settings.tripPhotoStyles.of(trip.id))
        assertEquals(TripPhotoStyle(0.65f, PhotoFrame.Polaroid), settings.tripPhotoStyles.forForm(null))
        // Kept under a backup's name, in Android's own text, and back on another phone.
        val prefs = settings.backupPreferences()
        assertEquals("${trip.id}=0.65|polaroid;*=0.65|polaroid", prefs.tripPhotos)
        val other = AppSettings(MemorySettingsFile())
        other.restorePreferences(prefs, replace = true)
        assertEquals(TripPhotoStyle(0.65f, PhotoFrame.Polaroid), other.tripPhotoStyles.of(trip.id))
    }

    @Test
    fun aNewPhotoInANoteSitsAsItsTripSays() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val files = MemoryMediaFiles()
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos", today.minusDays(1), today.plusDays(3))
        val settings = AppSettings.ready().apply { setTripPhotoStyle(trip.id, TripPhotoStyle(0.45f, PhotoFrame.Tape)) }
        val picker = FakePicker(files).apply { next = listOf(PickedMedia("picked/a.jpg", MediaTypes.JPEG) to samplePhoto(Scene.Sea, 400, 300)) }
        setContent { BoringTravelApp(book, settings, today, startAt = listOf(Place.Trip(trip.id, TripTab.Journal), Place.Write(trip.id)), media = MediaKit(files, picker)) }
        onAllNodes(hasSetTextAction())[0].performTextInput("Harbour")
        onNodeWithContentDescription("Choose photos and videos").performClick()
        waitUntil(timeoutMillis = 10_000) { book.snapshot.media.isNotEmpty() && book.entries(trip.id).isNotEmpty() }
        onNodeWithContentDescription("Done").performClick()
        waitForIdle()
        val photo = EntryDoc.parse(book.entries(trip.id).single().body).blocks.filterIsInstance<Block.Photo>().single()
        assertEquals(0.45f, photo.width)
        assertEquals(PhotoFrame.Tape, photo.frame)
    }

    // --- 5. A plan item's Journal and its Write --------------------------------------------------------------------------

    @Test
    fun anItemsSheetEndsWithItsJournalAndWritePlacesANoteAtIt() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Madrid", today.minusDays(2), today.plusDays(2))
        val added = assertNotNull(book.addItem(trip.id, "Museo del Prado", today, PlanKind.Place))
        val item = assertNotNull(book.saveItem(added.copy(placeName = "Madrid", lat = 40.4138, lng = -3.6921)))
        setContent { BoringTravelApp(book, AppSettings.ready().apply { planExpanded = true }, today, startAt = listOf(Place.Trip(trip.id)), version = "1.0") }
        onAllNodesWithText("Museo del Prado").onFirst().performClick()
        waitUntil(timeoutMillis = 10_000) { exists("Nothing written about it yet.") }
        onNodeWithText("Nothing written about it yet.").performScrollTo().assertExists()
        onNodeWithText("Write").performScrollTo().performClick()
        // Write opens on a new note placed at the item: "Museo del Prado (Madrid)".
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() && exists("Start writing") }
        onAllNodes(hasSetTextAction())[0].performTextInput("A morning with Goya")
        onNodeWithContentDescription("Done").performClick()
        waitForIdle()
        val note = book.entries(trip.id).single()
        assertEquals("Museo del Prado (Madrid)", note.placeName)
        assertEquals(40.4138, note.lat)
        assertEquals(listOf(note.id), ItemNotes.of(item, book.entries(trip.id)).map { it.id })
        // Back on the plan, the item's sheet lists it.
        onAllNodesWithText("Museo del Prado").onFirst().performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Journal · 1 note", ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("A morning with Goya").assertExists()
    }

    // --- 6. The turned map's taps ----------------------------------------------------------------------------------------

    @Test
    fun aTapOnATurnedMapReachesThePinWhereItIsDrawn() {
        val pin = MapMark("p1", "t", 37.10, 25.40, "Chora")
        val data = MapData(plans = listOf(pin))
        for (bearing in listOf(0.0, 45.0, 90.0, 200.0)) {
            val p = MapProjection(MapCamera(37.09, 25.39, 13.0, bearing), 400f, 800f)
            val placed = MapPins.placed(data, p).single()
            val at = p.at(pin.lat, pin.lng)
            // The pin stands upright on its point, wherever the turn puts it.
            assertEquals(at.x, placed.centerX, 0.01f)
            assertEquals(at.y, placed.bottom, 0.01f)
            assertEquals(MapTap.Plan("p1"), MapHits.hit(data, listOf(placed), p, placed.centerX, placed.centerY))
        }
        // A fit keeps the way the map is turned.
        val fitter = MapFitter()
        fitter.size(400f, 800f)
        fitter.cameraMoved(MapCamera(37.0, 25.0, 10.0, bearing = 30.0), byGesture = true)
        fitter.data(MapData(plans = listOf(pin, MapMark("p2", "t", 37.3, 25.6, "Oia"))))
        fitter.styleReady()
        assertEquals(30.0, assertNotNull(fitter.move).camera.bearing)
        val box = BBox(37.1, 25.4, 37.3, 25.6)
        assertTrue(MapFit.camera(box, 400f, 800f, MapInsets(), 30.0).zoom < MapFit.camera(box, 400f, 800f, MapInsets()).zoom + 1e-9)
    }

    // --- 7. The calendar's zone from the planned places' countries ------------------------------------------------------

    @Test
    fun theCalendarsZoneIsThePlannedCountrysAsAndroidGuessesIt() {
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Copenhagen", today.plusDays(10), today.plusDays(14))
        for ((name, lat, lng) in listOf(Triple("Nyhavn", 55.6798, 12.5911), Triple("Tivoli", 55.6737, 12.5681))) {
            val i = assertNotNull(book.addItem(trip.id, name, today.plusDays(10)))
            book.saveItem(i.copy(lat = lat, lng = lng))
        }
        val sync = CalendarSync(book, CalendarLinks(MemorySettingsFile()), MemoryPhoneCalendar(), countries = { CountryOutlines.loaded }, countryZones = CountryZones::of)
        val choice = sync.zones(trip.id)
        // ICU gives Denmark Europe/Copenhagen (zone.tab's own row; zone1970.tab would say Europe/Berlin).
        assertEquals("Europe/Copenhagen", choice.current)
        assertEquals("Copenhagen", TripZone.city(choice.current))
        assertEquals(setOf("Europe/Copenhagen"), CountryZones.of("DK"))
        assertEquals(setOf("Europe/Athens"), CountryZones.of("gr"))
        assertEquals(setOf("Asia/Calcutta"), CountryZones.of("IN"))
        assertTrue(CountryZones.of("US").size > 20)
    }

    // --- 8. A restored clip's picture --------------------------------------------------------------------------------------

    @Test
    fun aRestoredClipGetsItsPictureFromTwoFifthsIn() = runBlocking<Unit> {
        val files = MemoryMediaFiles()
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos", today.plusDays(1), today.plusDays(5)).id
        files.write("exports/Naxos-clip.mp4", ByteArray(9_000) { (it * 7).toByte() })
        SavedClips(files).keepNow(trip, "Naxos", "exports/Naxos-clip.mp4", 42_000, 1080, 1920, null)
        val clip = SavedClipIndex.list(files).single()
        val (path, _) = BackupFiles(book, AppSettings.ready(), files, "1.0", now = { 1_796_000_000_000L }).saveFile()
        val other = MemoryMediaFiles()
        other.write(BackupFiles.PICKED, files.read(path)!!)
        val asked = mutableListOf<String>()
        val frames = object : VideoFrames {
            override fun poster(path: String, maxPx: Int): ByteArray? = null
            override fun durationMs(path: String): Long? = 40_000
            override fun frameAt(path: String, atMs: Long, maxPx: Int, quality: Int): ByteArray? {
                asked += "$path at $atMs, $maxPx px, quality $quality"
                return byteArrayOf(-1, -40, -1, 1)
            }
        }
        BackupFiles(TripBook(MemoryTripFile()), AppSettings.ready(), other, "1.0", frames = frames).restoreFile(BackupFiles.PICKED)
        // The video's own length (40 s): 2/5 in is 16 s; 480 px, quality 82 (Android's SavedClips.restore).
        assertEquals(listOf("clips/${clip.id}.mp4 at 16000, 480 px, quality 82"), asked)
        assertTrue(byteArrayOf(-1, -40, -1, 1).contentEquals(other.read(SavedClipIndex.thumb(clip.id))))
        assertNotNull(SavedClips(other).thumbnail(SavedClipIndex.list(other).single()))
    }

    // --- 12. The export going on away from the screen where the phone lets it ---------------------------------------------

    @Test
    fun copyFromALinksProgressIsHandedToThePhone() = runBlocking<Unit> {
        val shown = mutableListOf<Float>()
        val work = object : BackgroundWork {
            override fun beginBackgroundWork(name: String): BackgroundTicket {
                assertEquals(COPY_WORK_NAME, name)
                return object : BackgroundTicket {
                    override fun endBackgroundWork() {}
                    override fun progressBackgroundWork(done: Float) {
                        shown += done
                    }
                }
            }
        }
        assertEquals(0.55f, assertNotNull(copyProgress(com.justdataplease.boringtravel.site.SiteImportState.Copying("a", 1, 2, 0))), 1e-6f)
        assertEquals("Making the video", ClipExporter.TASK_NAME)
        assertFalse(work.beginBackgroundWork(COPY_WORK_NAME).keepsGoingAway)
    }

    // --- 10, 11, 12. The project's keys ------------------------------------------------------------------------------------

    @Test
    fun theAppDeclaresItsLanguageItsLiveActivityAndItsContinuedWork() {
        val yml = File("../iosApp/project.yml").readText()
        // iOS's own sheets in the phone's language, for the app and its widgets.
        assertEquals(2, Regex("CFBundleAllowMixedLocalizations: true").findAll(yml).count())
        assertTrue("NSSupportsLiveActivities: true" in yml)
        assertTrue("com.justdataplease.boringtravel.continued.export" in yml)
        assertTrue("com.justdataplease.boringtravel.continued.copy" in yml)
        assertTrue("BoringTravelWidget/RecordingAttributes.swift" in yml)
        val activity = File("../iosApp/BoringTravelWidget/RecordingLiveActivity.swift").readText()
        assertTrue("RecordingStopButton" in activity && "Button(intent: StopRecordingIntent())" in activity)
        // The Swift view draws the same words as Kotlin hands it.
        assertEquals("Recording your route · Balanced", TrackingNotes.recordingTitle(com.justdataplease.boringtravel.model.TrackingLevel.Balanced))
    }
}
