package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.clip.ClipLength
import com.justdataplease.boringtravel.clip.ClipPrefs
import com.justdataplease.boringtravel.clip.ClipTarget
import com.justdataplease.boringtravel.data.SavedClipIndex
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Export sheet driven on the desktop (round 20), on the demo's Argentina at 15 s, the recording writer in place of
 * AVAssetWriter and a share that records: Export starts the video with "Making the video · N%" over the preview and
 * every control off, then "Your video is ready" with Watch, Send video, TikTok (missing: Android's words), Instagram
 * (on the phone), Save to phone (Photos) and Drive (not connected); the clip kept in the trip's Media with its picture;
 * a failed export with Try again; Cancel back to Create video.
 */
@OptIn(ExperimentalTestApi::class)
class ClipExportScreenOnDesktopTest {
    init {
        runBlocking {
            BtFontFiles.load()
            CountryOutlines.load()
        }
    }

    private val trip = SampleArgentina.TRIP_ID

    private class Phone(val book: TripBook, val files: MemoryMediaFiles, val settings: AppSettings, val kit: MediaKit, val device: ClipDevice, val share: RecordingShare)

    private fun phone(writer: (MemoryMediaFiles) -> VideoWriter = { DesktopVideoWriter(it, checksums = false) }): Phone {
        val (book, demo) = demoBook()
        val files = demo.files as MemoryMediaFiles
        val share = RecordingShare(installed = setOf(ClipTarget.Instagram))
        val device = ClipDevice(FakeSounds(), NoMicrophone, DrawnVideoFiles(files), writer(files), ToneDecoder(), share)
        val settings = AppSettings.ready().apply { demoMode = true }
        // A short clip: the whole export is drawn for real.
        ClipPrefs.saveLength(settings.prefs, ClipLength.S15)
        return Phone(book, files, settings, MediaKit(files), device, share)
    }

    private fun studio(
        p: Phone, google: com.justdataplease.boringtravel.google.GoogleAccount? = null, phone: com.justdataplease.boringtravel.ios.Phone = com.justdataplease.boringtravel.ios.Phone(),
        block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 440, height = 956) {
        setContent {
            CompositionLocalProvider(LocalClipDevice provides p.device, LocalPhone provides phone) {
                BoringTravelApp(p.book, p.settings, SAMPLE_TODAY, startAt = listOf(Place.Trip(trip), Place.Clip(trip)), version = "1.0", media = p.kit, google = google)
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 60_000) { onAllNodesWithContentDescription("Clip preview").fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 60_000) { onAllNodesWithContentDescription("Map scenes").fetchSemanticsNodes().isNotEmpty() }
        block()
    }

    private fun ComposeUiTest.waitForText(text: String, timeout: Long = 30_000) =
        waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun ComposeUiTest.tapText(text: String) = onAllNodesWithText(text).onFirst().tap()

    /**
     * Round 23: with Drive connected (the fake Drive), Save to Drive puts the made video in BoringTravel › Exports and
     * says so with Open; Send Drive link makes a link anyone can watch and hands it to the share sheet (Android's
     * DriveRow).
     */
    @Test fun aMadeVideoIsSavedToDriveAndItsLinkSent() {
        val p = phone()
        val google = FakeGoogle()
        val account = google.account(p.settings, p.files)
        runBlocking { account.connectDrive() }
        val links = ArrayList<Pair<String, String>>()
        studio(p, account, com.justdataplease.boringtravel.ios.Phone(share = { subject, text -> links += subject to text; true })) {
            tapText("Export")
            waitForText("9:16 · 0:")
            waitForText("Your video is ready", 180_000)
            tapText("Save to Drive")
            waitForText("Saved to Drive, in BoringTravel › Exports.")
            waitForText("Open")
            val video = google.cloud.filesIn(com.justdataplease.boringtravel.google.DriveApi.EXPORTS).single()
            assertEquals("video/mp4", video.mimeType)
            assertEquals(ClipExporter.outputPath("Argentina & Patagonia").substringAfterLast('/'), video.name)
            assertEquals((p.files.size(ClipExporter.outputPath("Argentina & Patagonia")) ?: 0L).toInt(), video.content.size)
            tapText("Send Drive link")
            waitUntil(timeoutMillis = 10_000) { links.isNotEmpty() }
            assertEquals("https://drive.google.com/file/d/${video.id}/view", links.single().second)
            assertTrue(video.anyone)
            waitForText("Anyone with the link can watch it; the video stays in your Drive.")
        }
    }

    @Test fun exportMakesTheVideoThenItIsSharedSavedAndKeptInTheTripsMedia() {
        val p = phone()
        studio(p) {
            tapText("Export")
            waitForText("9:16 · 0:")
            // Working: the sheet and the preview say so, the controls are off.
            waitForText("Making the video ·")
            assertTrue(onAllNodesWithText("You can close this; it carries on.").fetchSemanticsNodes().isNotEmpty())
            onNodeWithContentDescription("Undo").assertIsNotEnabled()
            onAllNodesWithContentDescription("Play").onFirst().assertIsNotEnabled()
            // Done.
            waitForText("Your video is ready", 180_000)
            waitForText("It's also kept in the trip's Media.")
            val output = ClipExporter.outputPath("Argentina & Patagonia")
            assertTrue((p.files.size(output) ?: 0L) > 0L, p.files.paths.filter { it.startsWith("exports") }.toString())
            // The share sheet with the video.
            tapText("Send video")
            assertEquals(listOf(output), p.share.shared)
            // TikTok always (it isn't on this phone: Android's words), Instagram because it is, WhatsApp not at all.
            tapText("TikTok")
            waitForText("TikTok isn't on this phone. Save the video to your phone and add it there.")
            tapText("Instagram")
            assertEquals(listOf(ClipTarget.Instagram to output), p.share.sent)
            assertTrue(onAllNodesWithText("WhatsApp").fetchSemanticsNodes().isEmpty())
            // Save to phone: into Photos.
            tapText("Save to phone")
            waitForText(SAVED_TO_PHOTOS)
            assertEquals(listOf(output), p.share.savedToPhotos)
            // Drive waits for the owner's Google setup, as Android says without an account.
            tapText("Save to Drive")
            waitForText("Google Drive isn't connected.")
            waitForText("Connect Drive")
            // Kept in the trip's Media, with its picture from 40 % in.
            waitUntil(timeoutMillis = 10_000) { SavedClipIndex.list(p.files).size == 1 }
            val clip = SavedClipIndex.list(p.files).single()
            assertEquals(trip, clip.tripId)
            // 15 s of journey, with the photos' pauses and the end card.
            assertTrue(clip.durationMs in 15_000L..40_000L, "${clip.durationMs} ms")
            assertTrue((p.files.size(SavedClipIndex.thumb(clip.id)) ?: 0L) > 0L)
        }
    }

    @Test fun aFailedExportSaysAndroidsWordsWithTryAgain() {
        val p = phone { NoVideoWriter }
        studio(p) {
            tapText("Export")
            waitForText("Couldn't make the video on this phone. Try another size or a faster speed.", 60_000)
            waitForText("Try again")
        }
    }

    @Test fun cancelGoesBackToCreateVideo() {
        val p = phone()
        studio(p) {
            tapText("Export")
            waitForText("Making the video ·")
            tapText("Cancel")
            waitForText("Create video")
            waitUntil(timeoutMillis = 10_000) { p.files.paths.none { it.startsWith(ClipExporter.PARTS) } }
            assertTrue(SavedClipIndex.list(p.files).isEmpty())
        }
    }
}
