package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.clip.ClipLook
import com.justdataplease.boringtravel.clip.ClipShape
import com.justdataplease.boringtravel.clip.ClipView
import com.justdataplease.boringtravel.clip.LayerSpec
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.clip.PhotoLayout
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.CoroutineContext

/*
 * The trip video (round 18) for review: the studio in its states, and frames of the video itself as Android's renderer
 * draws them (read in, on Skia), for each theme, each ground (the Globe from space and draped, Street, Terrain, Earth,
 * the offline grid), the clip's looks (Postcard, Film, Minimal, Bold), the photos' layouts, a day card, the 3D and
 * flat cameras and the shapes. Offline: the map's pictures are the countries drawn (DrawnSnapshots), never tiles.
 */

internal const val PHONE_W = 1320
internal const val PHONE_H = 2868
internal const val PHONE_DENSITY = 3f
internal val PHONE_FRAME = PaddingValues(top = 62.dp, bottom = 34.dp)

/** A coroutine dispatcher that never runs anything: a studio on it stays "Opening your trip". */
private object Never : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
}

/** Map pictures that never come: the stage stays on "Preparing your map". */
internal class SlowSnapshots : MapSnapshots {
    override fun online(): Boolean = true
    override suspend fun take(
        json: String?, styleUrl: String, spec: LayerSpec, south: Double, west: Double, north: Double, east: Double,
        aLat: Double, aLng: Double, bLat: Double, bLng: Double,
    ): MapSnapshot? = awaitCancellation()
}

/** The phone's files, where reading the trip's photos fails: the studio says it couldn't open the journey. */
private class BrokenFiles(private val files: MediaFiles) : MediaFiles by files {
    override fun size(path: String): Long? = throw IllegalStateException("Couldn't read the phone's files")
}

fun clips(out: File, demo: TripBook, demoKit: MediaKit) {
    ClipMaps.forget()
    val trip = SampleArgentina.TRIP_ID
    fun studioShot(name: String, look: Look = Look.PostcardPop, frames: Int = 40, kit: MediaKit = demoKit, book: TripBook = demo, content: @Composable () -> Unit) {
        File(out, "$name.png").writeBytes(
            render(PHONE_W, PHONE_H, PHONE_DENSITY, frames) {
                CompositionLocalProvider(LocalMediaKit provides kit, LocalMediaRows provides { id: String -> book.media(id) }) {
                    BoringTravelTheme(look) {
                        // The studio is dark edge to edge, under the status bar and the home indicator too.
                        Box(Modifier.fillMaxSize().background(StudioColors.bg).padding(PHONE_FRAME)) { content() }
                    }
                }
            },
        )
    }

    // The studio's states: opening the trip, a trip without a route, a journey that couldn't open.
    studioShot("clip-opening") {
        val studio = androidx.compose.runtime.remember {
            ClipStudio(demo, trip, ClipKit(AppSettings.ready().prefs, demoKit.files, Web.offline(), DrawnSnapshots()), CoroutineScope(Job() + Never))
        }
        ClipScreen(demo, trip, AppSettings.ready(), {}, {}, {}, studioAtStart = studio)
    }
    val bare = TripBook(MemoryTripFile()).also { b ->
        val t = b.newTrip("Weekend in Nafplio", SAMPLE_TODAY.plusDays(3), SAMPLE_TODAY.plusDays(4))
        b.addItem(t.id, "Old town", SAMPLE_TODAY.plusDays(3), PlanKind.Place)
    }
    val bareTrip = bare.trips(SAMPLE_TODAY).single().id
    studioShot("clip-no-route", book = bare, kit = MediaKit.memory()) { ClipScreen(bare, bareTrip, AppSettings.ready(), {}, {}, {}) }
    val broken = MediaKit(BrokenFiles(demoKit.files))
    // The studio that couldn't open the journey, made first (its work runs off the picture's frames) and shown as it is.
    withStudio(demo, broken, trip, Look.PostcardPop) { failed ->
        check(failed.load.value == ClipStudio.Load.Failed) { "The broken files' studio is ${failed.load.value}" }
        studioShot("clip-failed", kit = broken) { ClipScreen(demo, trip, AppSettings.ready(), {}, {}, {}, studioAtStart = failed) }
    }

    // The map still coming in, and the map that couldn't load (Street, offline: no globe under it).
    val street = streetSettings()
    studioShot("clip-map-preparing") {
        CompositionLocalProvider(LocalMapSnapshots provides SlowSnapshots()) { ClipScreen(demo, trip, street, {}, {}, {}) }
    }
    studioShot("clip-map-failed") {
        CompositionLocalProvider(LocalMapSnapshots provides DrawnSnapshots(online = false)) { ClipScreen(demo, trip, street, {}, {}, {}) }
    }

    // The studio with the preview (paused on a frame), in two themes (the picture follows the theme, the studio stays
    // dark), the size menu, full screen, and the other shapes.
    val opening = withStudio(demo, demoKit, trip, Look.PostcardPop) { ClipMoments.opening(requireNotNull(it.plan.value)) }
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        studioShot("clip-studio-${look.id}", look) { ClipScreen(demo, trip, AppSettings.ready(look), {}, {}, {}, frameAtStart = opening) }
    }
    studioShot("clip-size-menu") { ClipScreen(demo, trip, AppSettings.ready(), {}, {}, {}, frameAtStart = opening, sizeMenuAtStart = true) }
    studioShot("clip-full-screen") { ClipScreen(demo, trip, AppSettings.ready(), {}, {}, {}, frameAtStart = 0, fullScreenAtStart = true) }
    for (shape in listOf(ClipShape.Portrait, ClipShape.Square, ClipShape.Wide)) {
        val settings = AppSettings.ready().apply { com.justdataplease.boringtravel.clip.ClipPrefs.saveShape(prefs, shape) }
        studioShot("clip-studio-${shape.id}") { ClipScreen(demo, trip, settings, {}, {}, {}, frameAtStart = 0) }
    }

    clipFrames(out, demo, demoKit)
    clipEditor(out)
    clipExport(out)
    println("Trip video pictures in ${out.absolutePath}")
}

/**
 * Frames of the video itself, as the export would draw them (the 9:16 ones at 1080 × 1920, the export's size, the
 * rest at the preview's 540 × 960): the demo's Argentina, its default clip (the Globe, 3D, Postcard) at each moment,
 * then each theme, ground, look and layout at its telling moments.
 */
private fun clipFrames(out: File, demo: TripBook, demoKit: MediaKit) {
    val trip = SampleArgentina.TRIP_ID
    fun save(name: String, studio: ClipStudio, frame: Int, w: Int = 540, h: Int = 960) {
        val plan = requireNotNull(studio.plan.value)
        val style = requireNotNull(studio.style.value)
        ClipFrames(plan, studio.base.value, style, studio.photos.value, studio.noMark, studio.design.value, w, h).use { frames ->
            File(out, "$name.png").writeBytes(requireNotNull(frames.draw(frame.coerceIn(0, plan.frameCount - 1)).png()))
        }
    }

    runBlocking {
        // The default clip, full size, at every moment.
        val studio = readyStudio(demo, demoKit, trip, Look.PostcardPop, this)
        val plan = requireNotNull(studio.plan.value)
        save("clip-frame-1-globe-from-space", studio, 0, 1080, 1920)
        save("clip-frame-2-opening", studio, ClipMoments.opening(plan), 1080, 1920)
        ClipMoments.label(plan, 1)?.let { save("clip-frame-3-stop-title", studio, it, 1080, 1920) }
        ClipMoments.photo(plan)?.let { save("clip-frame-4-photo", studio, it, 1080, 1920) }
        save("clip-frame-5-route-and-vehicle", studio, ClipMoments.midway(plan), 1080, 1920)
        val late = plan.labels.getOrNull(plan.labels.size - 3)
        late?.let { save("clip-frame-6-stats-late", studio, (it.from + it.to) / 2, 1080, 1920) }
        save("clip-frame-7-end-mark", studio, ClipMoments.end(plan), 1080, 1920)

        // Day cards on, and the photos as cards and as album pages.
        studio.setDesign { it.copy(dayCards = true) }
        studio.settle()
        studio.plan.value?.let { p -> ClipMoments.dayCard(p)?.let { save("clip-frame-day-card", studio, it) } }
        studio.setDesign { it.copy(dayCards = false, photoLayout = PhotoLayout.Card) }
        studio.settle()
        studio.plan.value?.let { p -> ClipMoments.photo(p)?.let { save("clip-frame-photo-card", studio, it) } }
        studio.setDesign { it.copy(photoLayout = PhotoLayout.Album) }
        studio.settle()
        studio.plan.value?.let { p -> (p.photos.firstOrNull { it.held && it.albumOf > 1 } ?: p.photos.firstOrNull())?.let { save("clip-frame-photo-album", studio, (it.fromFrame + it.toFrame) / 2) } }
        studio.setDesign { it.copy(photoLayout = PhotoLayout.Full) }

        // The clip's looks: their text, line, bars, tone and grain, at a stop's title.
        for (look in ClipLook.entries) {
            studio.setDesign { look.apply(it) }
            studio.settle()
            studio.plan.value?.let { p -> ClipMoments.label(p, 3)?.let { save("clip-frame-look-${look.id}", studio, it) } }
        }
        studio.setDesign { ClipLook.Postcard.apply(it) }

        // The grounds: Street, Terrain, Earth (their pictures drawn here), flat and 3D.
        for (map in listOf(MapStyle.Street, MapStyle.Terrain, MapStyle.Earth)) {
            studio.setMapStyle(map)
            studio.settle()
            studio.plan.value?.let { p -> save("clip-frame-ground-${map.id}", studio, ClipMoments.midway(p)) }
        }
        studio.setView(ClipView.Flat)
        studio.settle()
        studio.plan.value?.let { p -> save("clip-frame-ground-street-flat", studio, ClipMoments.midway(p)) }
        studio.close()

        // Offline: no map at all, the sea's colour with its moving grid (Android's drawGrid).
        val offline = streetSettings()
        val grid = readyStudio(demo, demoKit, trip, Look.PostcardPop, this, offline, DrawnSnapshots(online = false))
        grid.plan.value?.let { p -> save("clip-frame-ground-offline-grid", grid, ClipMoments.midway(p)) }
        grid.close()

        // Each theme: the globe from space, a stop with its title, and the end card with the mark.
        for (look in Look.entries) {
            val s = readyStudio(demo, demoKit, trip, look, this)
            val p = requireNotNull(s.plan.value)
            save("clip-frame-theme-${look.id}-globe", s, 0)
            ClipMoments.label(p, 2)?.let { save("clip-frame-theme-${look.id}-stop", s, it) }
            save("clip-frame-theme-${look.id}-end", s, ClipMoments.end(p))
            s.close()
        }
    }
}

/**
 * Settings whose last clip was on Street (a first clip's defaults are settled first: Android forgets an older map
 * choice once, ClipPrefs.newDefaults).
 */
internal fun streetSettings(): AppSettings = AppSettings.ready().apply {
    com.justdataplease.boringtravel.clip.ClipPrefs.newDefaults(prefs)
    com.justdataplease.boringtravel.clip.MapStylePrefs.save(prefs, com.justdataplease.boringtravel.clip.MapStylePrefs.CLIP, MapStyle.Street)
}
