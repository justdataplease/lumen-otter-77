package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.justdataplease.boringtravel.clip.ClipLength
import com.justdataplease.boringtravel.clip.ClipPrefs
import com.justdataplease.boringtravel.clip.ClipStyle
import com.justdataplease.boringtravel.clip.ClipTarget
import com.justdataplease.boringtravel.clip.music.MixedSound
import com.justdataplease.boringtravel.clip.music.SoundDecoder
import com.justdataplease.boringtravel.compat.graphics.Bitmap
import com.justdataplease.boringtravel.data.SavedClips
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.ui.clip.ClipTool
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.BtLooks
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/*
 * Round 20 for review: the editor's Length, Route (with its Traveller & rides), Look and Stats panels for the whole clip
 * and for one place; the Export sheet in each of its states (ready, the map still coming in, making the video with its
 * progress, the progress over the preview with the sheet closed, adding the sound, done with its share targets, saved
 * to Photos with Drive not connected, an app that isn't on the phone, the music that couldn't go in, a failure, after a
 * cancel); Me › Your traveller & rides with its sheet; and the trip's Media tab with the clip made. Offline, the music
 * silent (the desktop's stand-in), the writer the desktop's recording one.
 */

/** A share that says [installed] are on the phone and keeps nothing (the pictures' phone). */
private class PictureShare(private val installed: Set<ClipTarget>) : VideoShare {
    override fun share(path: String): Boolean = true
    override fun saveToPhotos(path: String, done: (Boolean) -> Unit) = done(true)
    override fun installed(target: ClipTarget): Boolean = target in installed
    override fun sendTo(target: ClipTarget, path: String): Boolean = target in installed
}

/**
 * A writer that holds still: at picture [holdAtFrame] of the first part (the export shows its progress then), or when
 * the pictures are done and the sound is half in ([holdJoin]); else it is [inner].
 */
private class HoldingWriter(private val inner: VideoWriter, private val holdAtFrame: Int? = null, private val holdJoin: Boolean = false) : VideoWriter {
    @Volatile
    var holding = false

    override fun openPicture(path: String, spec: VideoSpec): PictureFile {
        val file = inner.openPicture(path, spec)
        return object : PictureFile by file {
            override suspend fun append(frame: Bitmap, index: Int) {
                if (holdAtFrame != null && index >= holdAtFrame) {
                    holding = true
                    awaitCancellation()
                }
                file.append(frame, index)
            }
        }
    }

    override suspend fun join(path: String, spec: VideoSpec, parts: List<PicturePart>, sound: MixedSound?, progress: (Float) -> Unit, stillWanted: () -> Unit) {
        if (holdJoin) {
            progress(0.5f)
            holding = true
            awaitCancellation()
        }
        inner.join(path, spec, parts, sound, progress, stillWanted)
    }

    override fun freeBytes(): Long? = inner.freeBytes()
}

fun clipExport(out: File) {
    val (demo, demoKit) = demoBook()
    val files = demoKit.files
    val trip = SampleArgentina.TRIP_ID
    val everyApp = PictureShare(ClipTarget.entries.toSet())
    fun device(writer: VideoWriter = DesktopVideoWriter(files, checksums = false), decoder: SoundDecoder = SilentDecoder(), share: VideoShare = everyApp) =
        ClipDevice(SilentSounds(), PlaceholderMicrophone(), DrawnVideoFiles(files), writer, decoder, share)

    fun shot(
        name: String, studio: ClipStudio, tall: Float = 1f, tool: ClipTool? = null, chosen: String? = null, frame: Int? = null,
        export: Boolean = false, taps: List<String> = emptyList(), device: ClipDevice = device(), frames: Int = 30,
    ) {
        File(out, "$name.png").writeBytes(
            renderTapping(PHONE_W, (PHONE_H * tall).toInt(), PHONE_DENSITY, frames = frames, taps = taps) {
                CompositionLocalProvider(
                    LocalMediaKit provides demoKit, LocalMediaRows provides { id: String -> demo.media(id) }, LocalClipDevice provides device,
                ) {
                    BoringTravelTheme(Look.PostcardPop) {
                        Box(Modifier.fillMaxSize().background(StudioColors.bg).padding(PHONE_FRAME)) {
                            ClipScreen(
                                demo, trip, AppSettings.ready(), {}, {}, {}, studioAtStart = studio, frameAtStart = frame,
                                toolAtStart = tool?.name, chosenAtStart = chosen, exportAtStart = export,
                            )
                        }
                    }
                }
            },
        )
    }

    /** A studio of the demo's Argentina on [device], 15 s long, its plan, map and photos in (unless [settle] is false). */
    suspend fun studio(
        scope: CoroutineScope, device: ClipDevice, snapshots: MapSnapshots = DrawnSnapshots(), settings: AppSettings = AppSettings.ready(), settle: Boolean = true,
        drive: com.justdataplease.boringtravel.google.DriveApi? = null,
    ): ClipStudio {
        ClipPrefs.saveLength(settings.prefs, ClipLength.S15)
        val kit = ClipKit(settings.prefs, files, Web.offline(), snapshots, device, saved = SavedClips(files), drive = drive)
        val s = ClipStudio(demo, trip, kit, scope, settings.traveller, settings.rides)
        s.useStyle(ClipStyle.of(settings.prefs, BtLooks.of(Look.PostcardPop, settings.fontSet, settings.textFont)))
        if (settle) s.settle() else withTimeout(120_000) { while (s.plan.value == null) delay(20) }
        return s
    }

    suspend fun ClipStudio.until(what: String, done: (ClipStudio.Make) -> Boolean) = withTimeout(300_000) {
        while (!done(make.value)) delay(50)
        println("Export pictures: $what")
    }

    runBlocking {
        // The four tools round 20 opens, for the whole clip and for one place.
        val tools = studio(this, device())
        val plan = requireNotNull(tools.plan.value)
        val place = plan.labels[2.coerceAtMost(plan.labels.lastIndex)].key
        val at = ClipMoments.label(plan, 2)
        shot("clip-tool-length", tools, tool = ClipTool.Length, frame = at, tall = 1.3f)
        shot("clip-tool-route", tools, tool = ClipTool.Route, frame = at, tall = 3.2f)
        shot("clip-tool-route-rides", tools, tool = ClipTool.Route, frame = at, tall = 3.0f, taps = listOf("Rides"))
        shot("clip-tool-look", tools, tool = ClipTool.Look, frame = at, tall = 1.6f)
        shot("clip-tool-stats", tools, tool = ClipTool.Stats, frame = at, tall = 2.0f)
        shot("clip-tool-length-place", tools, tool = ClipTool.Length, chosen = place, frame = at, tall = 1.3f)
        shot("clip-tool-route-place", tools, tool = ClipTool.Route, chosen = place, frame = at, tall = 1.4f)
        // A look picked, then changed: Custom….
        tools.setLook(com.justdataplease.boringtravel.clip.ClipLook.Film)
        tools.settle()
        tools.setDesign { it.copy(grain = false) }
        tools.settle()
        shot("clip-tool-look-custom", tools, tool = ClipTool.Look, frame = at, tall = 1.6f)
        // The Export sheet before anything is made.
        shot("clip-export-sheet", tools, frame = at, export = true)
        tools.close()

        // The map still coming in: Create video waits for it.
        val slow = studio(this, device(), SlowSnapshots(), streetSettings(), settle = false)
        shot("clip-export-map-coming", slow, export = true, frames = 20)
        slow.close()

        // Making the video: the sheet's progress, then the progress over the preview with the sheet closed; then a
        // cancel, back to Create video.
        val holding = HoldingWriter(DesktopVideoWriter(files, checksums = false), holdAtFrame = 170)
        val working = studio(this, device(holding))
        working.makeClip()
        withTimeout(300_000) { while (!holding.holding) delay(50) }
        delay(300)
        shot("clip-export-making", working, export = true, device = device(holding))
        shot("clip-export-making-preview", working, device = device(holding))
        working.cancelClip()
        working.until("cancelled") { it == ClipStudio.Make.Idle }
        shot("clip-export-cancelled", working, export = true)
        working.close()

        // Adding the sound: the pictures done, the sound half in.
        val sounding = HoldingWriter(DesktopVideoWriter(files, checksums = false), holdJoin = true)
        val adding = studio(this, device(sounding))
        adding.makeClip()
        withTimeout(300_000) { while (!sounding.holding) delay(50) }
        delay(300)
        shot("clip-export-adding-sound", adding, export = true, device = device(sounding))
        adding.cancelClip()
        adding.close()

        // Done: Watch, Send video, the apps (all three on this phone), Save to phone, Drive; then saved to Photos with
        // Drive not connected; then on a phone without TikTok, its button says so.
        val done = studio(this, device())
        done.makeClip()
        done.until("done") { it is ClipStudio.Make.Done || it == ClipStudio.Make.Failed }
        shot("clip-export-done", done, export = true, tall = 1.15f)
        done.saveToPhone()
        done.saveToDrive()
        delay(100)
        shot("clip-export-done-saved", done, export = true, tall = 1.2f)
        shot("clip-export-done-no-tiktok", done, export = true, tall = 1.2f, taps = listOf("TikTok"), device = device(share = PictureShare(emptySet())))
        done.close()

        // Round 23: with Google Drive connected (the fake Drive): saved in BoringTravel › Exports with Open, then the
        // link anyone can watch made and sent.
        val google = FakeGoogle()
        val account = google.account(AppSettings.ready(), files)
        account.connectDrive()
        val drived = studio(this, device(), drive = account.drive)
        drived.makeClip()
        drived.until("done for Drive") { it is ClipStudio.Make.Done || it == ClipStudio.Make.Failed }
        drived.saveToDrive()
        withTimeout(60_000) { while (drived.drive.value !is ClipStudio.Drive.Saved) delay(50) }
        shot("clip-export-drive-saved", drived, export = true, tall = 1.2f)
        drived.shareDriveLink()
        withTimeout(60_000) { while ((drived.drive.value as? ClipStudio.Drive.Saved)?.link == null) delay(50) }
        shot("clip-export-drive-link", drived, export = true, tall = 1.2f)
        drived.close()

        // The trip's Media with the clip just made (its picture from 40 % in), the photos by day under it.
        File(out, "trip-media-clips.png").writeBytes(
            render(PHONE_W, PHONE_H, PHONE_DENSITY, 40) {
                BoringTravelApp(
                    demo, AppSettings.ready().apply { demoMode = true }, SAMPLE_TODAY, PHONE_FRAME,
                    startAt = listOf(Place.Trip(trip, TripTab.Media)), version = PREVIEW_VERSION, media = demoKit,
                )
            },
        )

        // The music that couldn't be read: the video is silent, and the sheet says so.
        val silent = studio(this, device(decoder = SoundDecoder.NONE))
        silent.makeClip()
        silent.until("silent") { it is ClipStudio.Make.Done || it == ClipStudio.Make.Failed }
        shot("clip-export-music-failed", silent, export = true, tall = 1.15f)
        silent.close()

        // A phone that couldn't make it.
        val failing = studio(this, device(NoVideoWriter))
        failing.makeClip()
        failing.until("failed") { it == ClipStudio.Make.Failed }
        shot("clip-export-failed", failing, export = true)
        failing.close()
    }

    // Me › Your traveller & rides: the row, the sheet's People and Rides.
    fun meShot(name: String, look: Look, taps: List<String>, tall: Float = 1f) = File(out, "$name.png").writeBytes(
        renderTapping(PHONE_W, (PHONE_H * tall).toInt(), PHONE_DENSITY, frames = 30, taps = taps) {
            BoringTravelApp(TripBook(com.justdataplease.boringtravel.engine.MemoryTripFile()), AppSettings.ready(look), SAMPLE_TODAY, PHONE_FRAME, startAt = listOf(Place.Home(HomeTab.Me)), version = PREVIEW_VERSION)
        },
    )
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        // Drawn taller: Me's rows run past the phone's height.
        meShot("me-traveller-row-${look.id}", look, emptyList(), tall = 1.8f)
        meShot("me-traveller-people-${look.id}", look, listOf("Your traveller & rides"))
        meShot("me-traveller-rides-${look.id}", look, listOf("Your traveller & rides", "Rides"))
    }
}
