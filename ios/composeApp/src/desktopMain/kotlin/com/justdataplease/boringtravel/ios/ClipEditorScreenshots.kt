package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipView
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.clip.PhotoLayout
import com.justdataplease.boringtravel.clip.VoiceLine
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.ui.clip.ClipTool
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/*
 * The trip video's editor (round 19) for review: the tracks under the preview in two themes (the studio stays dark,
 * the picture follows the theme), a place, a photo card and a phone video chosen (the dashed ring on the stage, the
 * scene's own tools first), each tool's panel for the whole clip and for one scene (Text with a place's own words,
 * the stops' list, a day card; Audio with a track playing, a recording in progress and its lines; Media with a place's
 * photos, the Add media sheet and a phone video; Camera), and Undo and Redo after an edit. Offline: the map's
 * pictures are the countries drawn, the phone videos' pictures drawn (DesktopClipDevice.kt), the music silent.
 * Panels that run long are drawn taller than the phone, so the whole panel can be read.
 */

/** A journal video in the demo's trip (a drawn one on the desktop), for the Add media sheet's video tiles. */
private fun addJournalVideo(book: TripBook, files: MediaFiles, tripId: String): String {
    val id = "demo-clip-video"
    files.write("media/$id.mov", ByteArray(64 * 1024) { (it % 251).toByte() })
    val takenAt = book.items(tripId).firstNotNullOfOrNull { it.day }?.let { com.justdataplease.boringtravel.time.startOfDayMs(it.plusDays(3), null) + 15 * 3_600_000L }
    book.addMedia(Media(id, tripId = tripId, localPath = "media/$id.mov", mime = "video/quicktime", width = 1080, height = 1920, takenAt = takenAt, createdAt = 1L, updatedAt = 1L))
    return id
}

fun clipEditor(out: File) {
    val (demo, demoKit) = demoBook()
    val files = demoKit.files
    val device = desktopClipDevice(files)
    val drawn = device.videos as DrawnVideoFiles
    // The kit with the journal video's tile drawn (the iPhone's is the video's first frame).
    val kit = MediaKit(files, frames = VideoFrames { path, max -> drawn.poster(path, max) })
    val trip = SampleArgentina.TRIP_ID
    addJournalVideo(demo, files, trip)

    fun shot(
        name: String, studio: ClipStudio, look: Look = Look.PostcardPop, tall: Float = 1f, tool: ClipTool? = null, chosen: String? = null,
        frame: Int? = null, taps: List<String> = emptyList(),
    ) {
        File(out, "$name.png").writeBytes(
            renderTapping(PHONE_W, (PHONE_H * tall).toInt(), PHONE_DENSITY, frames = 30, taps = taps) {
                CompositionLocalProvider(
                    LocalMediaKit provides kit, LocalMediaRows provides { id: String -> demo.media(id) }, LocalClipDevice provides device,
                ) {
                    BoringTravelTheme(look) {
                        Box(Modifier.fillMaxSize().background(StudioColors.bg).padding(PHONE_FRAME)) {
                            ClipScreen(
                                demo, trip, AppSettings.ready(look), {}, {}, {}, studioAtStart = studio, frameAtStart = frame,
                                toolAtStart = tool?.name, chosenAtStart = chosen,
                            )
                        }
                    }
                }
            },
        )
    }

    runBlocking {
        // The tracks, nothing chosen, in a light and a dark theme (the preview's picture follows the theme).
        for (look in listOf(Look.PostcardPop, Look.Trail)) {
            val s = readyStudio(demo, kit, trip, look, this, device = device)
            val p = requireNotNull(s.plan.value)
            shot("clip-editor-tracks-${look.id}", s, look, frame = ClipMoments.label(p, 2))
            s.close()
        }

        val studio = readyStudio(demo, kit, trip, Look.PostcardPop, this, device = device)
        val plan = requireNotNull(studio.plan.value)
        val place = plan.labels[2.coerceAtMost(plan.labels.lastIndex)].key
        // The place with the most photos, for its media.
        val photoPlace = studio.design.value.let { d ->
            com.justdataplease.boringtravel.clip.ClipScenes.list(plan, d).filter { it.photoId != null && it.placeKey != null }
                .groupBy { it.placeKey!! }.maxByOrNull { it.value.size }?.key
        } ?: place

        // A place chosen: its tools first, Skip stop and Let go, its name ringed on the stage to drag.
        shot("clip-editor-place-chosen", studio, chosen = place)
        // Text for the whole clip (tall: the panel runs long), and the stops' list opened.
        shot("clip-editor-text", studio, tool = ClipTool.Text, tall = 2.2f)
        shot("clip-editor-text-stops", studio, tool = ClipTool.Text, tall = 1.6f, taps = listOf("Edit stops"))
        // The text editor for one place: its own words show on the stage.
        studio.editScenes { e -> e.change(place) { it.copy(text = "Glacier walk", note = "Ice and blue light at noon") } }
        studio.settle()
        shot("clip-editor-text-place", studio, tool = ClipTool.Text, chosen = place, tall = 2.2f)
        // Undo after that edit (the place's own words on the stage), then Redo after undoing it (its plan's name back).
        val atPlace = ClipMoments.label(requireNotNull(studio.plan.value), 2)
        shot("clip-editor-undo", studio, frame = atPlace)
        studio.undo()
        studio.settle()
        shot("clip-editor-redo", studio, frame = atPlace)
        studio.redo()
        studio.settle()

        // Day cards: on for the clip, one day's card chosen.
        studio.setDayCards(true)
        studio.settle()
        requireNotNull(studio.plan.value).dayStarts.getOrNull(1)?.let { day ->
            shot("clip-editor-text-day-card", studio, tool = ClipTool.Text, chosen = day.key, tall = 1.4f)
        }
        studio.setDayCards(false)
        studio.settle()

        // Audio: the music, where it starts, on the beat, the tracks with Listen playing one, and the voice lines.
        shot("clip-editor-audio", studio, tool = ClipTool.Audio, tall = 2.4f)
        shot("clip-editor-audio-listening", studio, tool = ClipTool.Audio, tall = 2.4f, taps = listOf("Play Afro Groove"))
        // A recording in progress: the place's Voice line with Stop and Cancel, and on the tracks "● 0:02".
        studio.startVoice(place)
        shot("clip-editor-audio-recording", studio, tool = ClipTool.Audio, chosen = place)
        shot("clip-editor-recording-tracks", studio, chosen = place)
        studio.cancelVoice()
        // A line kept: the Voice track's block and the panel's Lines.
        studio.editScenes { it.withVoice(VoiceLine(place, "demo-line.m4a", 4_200)) }
        studio.settle()
        shot("clip-editor-audio-lines", studio, tool = ClipTool.Audio, tall = 2.4f)

        // Media for the whole clip, a place's photos, the Add media sheet, one photo.
        shot("clip-editor-media", studio, tool = ClipTool.Media, tall = 1.6f)
        shot("clip-editor-media-place", studio, tool = ClipTool.Media, chosen = photoPlace, tall = 1.4f)
        shot("clip-editor-add-media", studio, tool = ClipTool.Media, chosen = photoPlace, tall = 1.4f, taps = listOf("Add media"))
        // A photo in the clip (a held one: the journey stops for it).
        val photo = requireNotNull(studio.plan.value).photos.let { ps -> ps.firstOrNull { it.held } ?: ps.firstOrNull() }?.id
        if (photo != null) shot("clip-editor-photo", studio, tool = ClipTool.Media, chosen = ClipDesign.photoKey(photo), tall = 2.0f)
        // A photo card chosen on the stage (Card layout: a card moves anywhere), its ring.
        studio.setDesign { it.copy(photoLayout = PhotoLayout.Card) }
        studio.settle()
        requireNotNull(studio.plan.value).photos.let { ps -> ps.firstOrNull { it.held } ?: ps.firstOrNull() }?.id
            ?.let { shot("clip-editor-photo-card-chosen", studio, chosen = ClipDesign.photoKey(it)) }
        studio.setDesign { it.copy(photoLayout = PhotoLayout.Full) }
        studio.settle()

        // A phone video from the phone's library, after the place: its block on the tracks, its picture on the stage,
        // and its own panel (trim, sound, place, Remove).
        files.write("picked/clip-video.mov", ByteArray(64 * 1024) { (it % 241).toByte() })
        studio.addVideo(PickedMedia("picked/clip-video.mov", "video/quicktime"), place)
        studio.settle()
        val video = studio.design.value.videos.firstOrNull()
        if (video != null) {
            shot("clip-editor-video", studio, chosen = "video:${video.id}")
            shot("clip-editor-video-panel", studio, tool = ClipTool.Media, chosen = "video:${video.id}", tall = 1.4f)
        }

        // Camera for the whole clip (the map, flat or 3D, how close, zoom out, close-ups), and for one place.
        shot("clip-editor-camera", studio, tool = ClipTool.Camera, tall = 2.1f)
        shot("clip-editor-camera-place", studio, tool = ClipTool.Camera, chosen = place, tall = 1.2f)
        studio.setMapStyle(MapStyle.Street)
        studio.setView(ClipView.Flat)
        studio.settle()
        shot("clip-editor-camera-street-flat", studio, tool = ClipTool.Camera, tall = 2.1f)
        studio.close()
    }
}

/**
 * [content] drawn as [render] draws it, after tapping each of [taps] in turn (the first clickable thing whose text,
 * description or click label is that), so a sheet or a list Android's own panels open on a tap shows too.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun renderTapping(width: Int, height: Int, density: Float, frames: Int, taps: List<String>, content: @Composable () -> Unit): ByteArray {
    com.justdataplease.boringtravel.compat.SystemBars.set(true)
    val scene = ImageComposeScene(width, height, Density(density)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            content()
            PhoneStatusBar(landscape = width > height)
        }
    }
    var t = 0L
    fun frames(n: Int) = repeat(n) {
        Thread.sleep(50)
        t++
        scene.render(t * 50_000_000L).close()
    }
    try {
        scene.render(0).close()
        frames(frames)
        for (label in taps) {
            val node = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                .firstOrNull { it.says(label) && it.config.getOrNull(SemanticsActions.OnClick) != null }
                ?: error("Nothing to tap called \"$label\"")
            node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
            frames(frames / 2 + 6)
        }
        t++
        return scene.render(t * 50_000_000L).use { last -> last.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
    } finally {
        scene.close()
        System.gc()
    }
}

private fun SemanticsNode.says(label: String): Boolean {
    val texts = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    val described = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    return label in texts || label in described || config.getOrNull(SemanticsActions.OnClick)?.label == label
}
