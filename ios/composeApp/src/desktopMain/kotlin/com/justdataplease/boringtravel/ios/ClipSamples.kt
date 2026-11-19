package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.clip.ClipPlan
import com.justdataplease.boringtravel.clip.ClipStyle
import com.justdataplease.boringtravel.clip.InsertKind
import com.justdataplease.boringtravel.clip.PreparedVideoFrames
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.ui.theme.BtLooks
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/*
 * The trip video made the way its screen makes it (ClipStudio: the route, the plan, the map's pictures, the photos),
 * for the desktop's tests and the screenshots, offline: the map's pictures are the countries drawn ([DrawnSnapshots]),
 * never tiles.
 */

/**
 * A studio for [tripId] of [book] in [look], once its plan, map and photos are all in ([settings]: the remembered
 * choices it starts from). Runs in [scope], which must keep running while the studio is used.
 */
suspend fun readyStudio(
    book: TripBook, kit: MediaKit, tripId: String, look: Look, scope: CoroutineScope,
    settings: AppSettings = AppSettings.ready(look), snapshots: MapSnapshots = DrawnSnapshots(), timeoutMs: Long = 120_000,
    device: ClipDevice = ClipDevice.SILENT,
): ClipStudio {
    BtFontFiles.load()
    CountryOutlines.load()
    val studio = ClipStudio(book, tripId, ClipKit(settings.prefs, kit.files, Web.offline(), snapshots, device), scope, settings.traveller, settings.rides)
    studio.useStyle(ClipStyle.of(settings.prefs, BtLooks.of(look, settings.fontSet, settings.textFont)))
    studio.settle(timeoutMs)
    return studio
}

/**
 * Waits until the studio's plan, map, photos and tracks are in again after a change (and the phone videos' pictures,
 * once the plan plays any).
 */
suspend fun ClipStudio.settle(timeoutMs: Long = 120_000) {
    delay(30)
    withTimeout(timeoutMs) {
        while (true) {
            val plan = plan.value
            val settled = plan != null && load.value == ClipStudio.Load.Ready && mapStatus.value != ClipStudio.MapStatus.Loading && !replanning.value
            val photosIn = !photosLoading.value
            val tracksIn = plan != null && timeline.value?.frames == plan.frameCount
            val videosIn = plan == null || plan.inserts.none { it.kind == InsertKind.Video } ||
                (videoFrames.value as? PreparedVideoFrames)?.ids?.containsAll(plan.inserts.filter { it.kind == InsertKind.Video }.mapNotNull { it.id }) == true
            if (load.value == ClipStudio.Load.NoRoute || load.value == ClipStudio.Load.Failed) break
            if (settled && photosIn && tracksIn && videosIn && mediaWork.value != ClipStudio.MediaWork.Adding) break
            delay(20)
        }
    }
}

/** Frame numbers of [plan] worth looking at, by what shows in them (for the screenshots and the tests). */
object ClipMoments {
    /** The opening: the title over the map, the camera diving. */
    fun opening(plan: ClipPlan): Int = (plan.drawStart * 0.3).toInt()

    /** The route being drawn, halfway along the journey, between photos and place names when it can. */
    fun midway(plan: ClipPlan): Int {
        val target = (plan.drawStart + plan.drawEnd) / 2
        return (target until plan.drawEnd).firstOrNull { f -> plan.photos.none { f in it.fromFrame until it.toFrame } && plan.insertAt(f) == null } ?: target
    }

    /** A place name fully in: the middle of the [n]th place name. */
    fun label(plan: ClipPlan, n: Int = 1): Int? = plan.labels.getOrNull(n)?.let { (it.from + it.to) / 2 }

    /** A photo showing: the middle of the first photo the journey stops for (else the first card). */
    fun photo(plan: ClipPlan): Int? = (plan.photos.firstOrNull { it.held } ?: plan.photos.firstOrNull())?.let { (it.fromFrame + it.toFrame) / 2 }

    /** A day card, when the clip has them. */
    fun dayCard(plan: ClipPlan): Int? = plan.inserts.firstOrNull { it.card != null }?.let { (it.fromFrame + it.toFrame) / 2 }

    /** The end card with the mark, once it has faded in. */
    fun end(plan: ClipPlan): Int = plan.frameCount - 1
}

/** [block] with a studio of [tripId] in [look], its work stopped and its pictures freed afterwards. */
fun <T> withStudio(book: TripBook, kit: MediaKit, tripId: String, look: Look, settings: AppSettings = AppSettings.ready(look), block: (ClipStudio) -> T): T =
    runBlocking {
        val studio = readyStudio(book, kit, tripId, look, this, settings)
        try {
            block(studio)
        } finally {
            studio.close()
        }
    }
