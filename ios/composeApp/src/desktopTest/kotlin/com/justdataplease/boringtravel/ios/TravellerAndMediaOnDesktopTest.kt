package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.backup.BackupArchive
import com.justdataplease.boringtravel.backup.BackupExtras
import com.justdataplease.boringtravel.clip.ClipEdits
import com.justdataplease.boringtravel.clip.ClipMedia
import com.justdataplease.boringtravel.clip.ClipSource
import com.justdataplease.boringtravel.clip.ClipVideo
import com.justdataplease.boringtravel.clip.RideModel
import com.justdataplease.boringtravel.clip.Rides
import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.clip.VoiceLine
import com.justdataplease.boringtravel.clip.Who
import com.justdataplease.boringtravel.data.SavedClipIndex
import com.justdataplease.boringtravel.data.SavedClips
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 20 beside the export: Me › Your traveller & rides (the Android app's own row and sheet, kept under a backup's
 * names), the trip's Media tab with its saved clips, a deleted trip taking its video's files and clips with it, and a
 * backup file carrying the trip video's files and saved clips to another phone (extras/, Android's form).
 */
@OptIn(ExperimentalTestApi::class)
class TravellerAndMediaOnDesktopTest {
    init {
        runBlocking {
            BtFontFiles.load()
            CountryOutlines.load()
        }
    }

    @Test fun theTravellerAndRidesAreThisPhonesOwnAndGoIntoBackups() {
        val file = MemorySettingsFile()
        val settings = AppSettings(file)
        assertEquals(Traveller.DEFAULT, settings.traveller)
        assertEquals(Rides.DEFAULT, settings.rides)
        val woman = Traveller.DEFAULT.copy(who = Who.Woman)
        settings.traveller = woman
        val car = assertNotNull(Rides.DEFAULT[Vehicle.Car]).copy(model = RideModel.Suv)
        settings.rides = Rides.DEFAULT.with(car)
        // Under a backup's names, in Android's own strings.
        assertEquals(woman.encode(), file.read("traveller"))
        val prefs = settings.backupPreferences()
        assertEquals(woman.encode(), prefs.traveller)
        assertEquals(settings.rides.encode(), prefs.rides)
        // A restore on another phone brings them back.
        val other = AppSettings(MemorySettingsFile())
        other.restorePreferences(prefs, replace = true)
        assertEquals(woman, other.traveller)
        assertEquals(RideModel.Suv, other.rides[Vehicle.Car]?.model)
    }

    @Test fun meYourTravellerAndRidesChangesEveryVideosDefault() = runDesktopComposeUiTest(width = 440, height = 956) {
        val settings = AppSettings.ready()
        setContent { BoringTravelApp(TripBook(MemoryTripFile()), settings, SAMPLE_TODAY, startAt = listOf(Place.Home(HomeTab.Me)), version = "1.0") }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Your traveller & rides").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Man · walks and rides your trip videos").onFirst().assertExistsCompat()
        onAllNodesWithText("Your traveller & rides").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Walks the legs on foot in your trip videos.").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("A video can have its own in its Traveller tool.").onFirst().assertExistsCompat()
        onAllNodesWithText("Drag to turn").onFirst().assertExistsCompat()
        onAllNodesWithText("Woman").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 10_000) { settings.traveller.who == Who.Woman }
        onAllNodesWithText("Rides").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("How each vehicle looks in your trip videos.").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("SUV").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 10_000) { settings.rides[Vehicle.Car]?.model == RideModel.Suv }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Back to the default look").fetchSemanticsNodes().isNotEmpty() }
    }

    /** A trip of the traveller's own with a clip's edits, a voice line, a phone video and a saved clip on the phone. */
    private fun ownTripWithVideo(files: MemoryMediaFiles): Pair<TripBook, String> {
        var n = 0
        val book = TripBook(MemoryTripFile(), ids = { "own-${n++}" })
        val trip = book.newTrip("Naxos", localDate(2027, 6, 17), localDate(2027, 6, 26)).id
        val edits = ClipEdits()
            .withVoice(VoiceLine("label:item:x", "line-1.m4a", 4_000))
            .withVideo(ClipVideo(id = "v1", file = "v1.mov", sceneKey = "label:item:x", durationMs = 5_000))
        ClipEditStore.save(files, trip, mapOf(ClipSource.Planned to edits))
        files.write(ClipMedia.voiceDir(trip) + "/line-1.m4a", "AAC voice line".encodeToByteArray())
        files.write(ClipMedia.videoDir(trip) + "/v1.mov", ByteArray(4_000) { it.toByte() })
        files.write("exports/Naxos-clip.mp4", ByteArray(9_000) { (it * 7).toByte() })
        runBlocking { SavedClips(files).keepNow(trip, "Naxos", "exports/Naxos-clip.mp4", 42_000, 1080, 1920, byteArrayOf(-1, -40, 1)) }
        return book to trip
    }

    @Test fun aDeletedTripTakesItsVideosFilesAndClipsWithIt() {
        val files = MemoryMediaFiles()
        val (book, trip) = ownTripWithVideo(files)
        assertEquals(1, SavedClipIndex.list(files).size)
        assertTrue(removeTripAndFiles(book, files, trip))
        assertNull(files.size(ClipEditStore.path(trip)))
        assertTrue(files.paths.none { it.startsWith("clip-voice/") || it.startsWith("clip-media/") || it.startsWith("clips/") }, files.paths.toString())
        assertTrue(SavedClipIndex.list(files).isEmpty())
    }

    @Test fun aBackupFileCarriesTheTripVideosFilesAndSavedClips() = runBlocking {
        val files = MemoryMediaFiles()
        val (book, trip) = ownTripWithVideo(files)
        val clip = SavedClipIndex.list(files).single()
        val (path, _) = BackupFiles(book, AppSettings.ready(), files, "1.0", now = { 1_796_000_000_000L }).saveFile()
        val zip = BackupArchive.reader(files.size(path)!!) { o, l -> files.readRange(path, o, l)!! }
        val names = zip.entries.map { it.name }
        // Android's extras/: the edits, the voice line and video they use, the clip's MP4; the rows in data.json.
        for (entry in listOf("extras/clip-edits/$trip.json", "extras/clip-voice/$trip/line-1.m4a", "extras/clip-media/$trip/v1.mov", "extras/clips/${clip.id}.mp4")) {
            assertTrue(entry in names, names.toString())
            assertEquals(entry.removePrefix("extras/"), BackupExtras.target(entry))
        }
        val contents = BackupArchive.read(zip)
        assertEquals(listOf(clip.id), contents.data.savedClips?.map { it.id })
        assertTrue(contents.data.clipEdits?.get(trip)?.contains("line-1.m4a") == true)
        // Restored on another phone: every file where it was, the clip listed again (without its picture).
        val other = MemoryMediaFiles()
        other.write(BackupFiles.PICKED, files.read(path)!!)
        BackupFiles(TripBook(MemoryTripFile()), AppSettings.ready(), other, "1.0").restoreFile(BackupFiles.PICKED)
        assertEquals(files.read(ClipEditStore.path(trip))?.decodeToString(), other.read(ClipEditStore.path(trip))?.decodeToString())
        assertTrue(other.read(ClipMedia.voiceDir(trip) + "/line-1.m4a")!!.contentEquals(files.read(ClipMedia.voiceDir(trip) + "/line-1.m4a")!!))
        assertTrue(other.read(ClipMedia.videoDir(trip) + "/v1.mov")!!.contentEquals(files.read(ClipMedia.videoDir(trip) + "/v1.mov")!!))
        val back = SavedClipIndex.list(other).single()
        assertEquals(clip, back)
        assertTrue(other.read(SavedClipIndex.video(back.id))!!.contentEquals(files.read(SavedClipIndex.video(clip.id))!!))
        // Names it doesn't know, or that would leave its folders, are skipped.
        assertNull(BackupExtras.target("extras/clip-media/../media/x.jpg"))
        assertNull(BackupExtras.target("extras/books/b1.json"))
    }

    @Test fun theTripsMediaTabShowsItsClipsWithShareAndDelete() = runDesktopComposeUiTest(width = 440, height = 956) {
        val files = MemoryMediaFiles()
        val (book, trip) = ownTripWithVideo(files)
        val kit = MediaKit(files)
        val share = RecordingShare()
        val device = ClipDevice(FakeSounds(), NoMicrophone, DrawnVideoFiles(files), share = share)
        setContent {
            CompositionLocalProvider(LocalClipDevice provides device) {
                BoringTravelApp(book, AppSettings.ready(), SAMPLE_TODAY, startAt = listOf(Place.Trip(trip, TripTab.Media)), version = "1.0", media = kit)
            }
        }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("CLIPS · 1").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("0:42").onFirst().assertExistsCompat()
        onAllNodesWithText("Photos and videos you add to this trip's journal show here.").onFirst().assertExistsCompat()
        onAllNodesWithContentDescription("Share clip").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(SavedClipIndex.video(SavedClipIndex.list(files).single().id)), share.shared)
        onAllNodesWithContentDescription("Delete clip").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Delete this clip?").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Are you sure you want to delete it? It is removed from this phone. Copies you shared stay.").onFirst().assertExistsCompat()
        onAllNodesWithText("Delete").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 10_000) { SavedClipIndex.list(files).isEmpty() }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("CLIPS · 1").fetchSemanticsNodes().isEmpty() }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() = assertExists()
}
