package com.justdataplease.boringtravel.ui.clip

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.justdataplease.boringtravel.clip.BaseMap
import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipPlan
import com.justdataplease.boringtravel.clip.ClipRenderer
import com.justdataplease.boringtravel.clip.ClipScenes
import com.justdataplease.boringtravel.clip.ClipShape
import com.justdataplease.boringtravel.clip.ClipStyle
import com.justdataplease.boringtravel.clip.ClipText
import com.justdataplease.boringtravel.clip.ClipVideoFrames
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioRoundButton
import com.justdataplease.boringtravel.ui.studio.StudioSystemBars
import com.justdataplease.boringtravel.ui.studio.StudioText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val S = StudioColors

/** Where the preview is in the clip and whether it plays: one place for the inline and the full-screen preview. */
@Stable
internal class PreviewPlayer {
    var frame by mutableIntStateOf(0)
    var playing by mutableStateOf(false)
}

/** The full-screen video starts where the inline one was and hands its place back when it closes. */
@Stable
internal class VideoSpot {
    var positionMs by mutableIntStateOf(0)
    var handedBack by mutableIntStateOf(0)
}

@Composable
internal fun BoxScope.PlayBadge(label: String, onClick: () -> Unit) {
    Box(
        Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(S.ink.copy(alpha = 0.95f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(BtIcons.Play, label, Modifier.size(26.dp), tint = S.bg) }
}

/**
 * What the preview shows: the picture, and how far preparing is while play waits for it (null when not waiting);
 * [map]: it waits for the map's pictures, before the frames.
 */
internal class PreviewFrame(val image: ImageBitmap?, val preparing: Float?, val map: Boolean = false)

/**
 * The phone videos' pictures for every preview renderer under it ([ClipViewModel.videoFrames]); the clip screen (and
 * the editor that replaces it) provides them, so the previews' signatures stay as they were.
 */
internal val LocalClipVideos = staticCompositionLocalOf { ClipVideoFrames.NONE }

/** What a prepared preview was made from, so the next one can keep the frames an edit didn't touch. */
private class PreparedFrom(
    val plan: ClipPlan, val base: BaseMap, val style: ClipStyle, val photos: Map<String, Bitmap>, val isPro: Boolean,
    val design: ClipDesign, val videos: ClipVideoFrames, val cache: PreviewCache,
)

/**
 * The prepared preview for [plan] in [design] (see [PreviewCache]): started [PreviewMath.DEBOUNCE_MS] after the last
 * change once [settled] (the detailed map is in, nothing is re-planning or exporting), closed when anything changes.
 * Null until then; the preview draws live meanwhile. After a scene edit (same map, look and timing) it starts from the
 * previous one and draws again only the frames the edit changed ([ClipScenes.dirtyFrames]). It prepares from the
 * [playhead] on (owner, 2026-11-09: "when I select something it creates a whole clip, no need"): a change shows where
 * the clip is, and playing goes on from there while the rest is prepared.
 */
@Composable
internal fun rememberPreviewCache(
    plan: ClipPlan, base: BaseMap, style: ClipStyle, photos: Map<String, Bitmap>, isPro: Boolean, design: ClipDesign,
    settled: Boolean, playhead: () -> Int = { 0 },
): PreviewCache? {
    val context = LocalContext.current
    val videos = LocalClipVideos.current
    var cache by remember { mutableStateOf<PreviewCache?>(null) }
    val last = remember { arrayOfNulls<PreparedFrom>(1) }
    LaunchedEffect(plan, base, style, photos, isPro, design, videos, settled) {
        cache = null
        if (!settled) return@LaunchedEffect
        delay(PreviewMath.DEBOUNCE_MS)
        val (w, h) = PreviewMath.size(plan.options.shape, PreviewMath.FULL_SHORT)
        val memoryClass = context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 192
        val budget = PreviewMath.budgetBytes(memoryClass)
        val before = last[0]?.takeIf { it.base === base && it.style === style && it.isPro == isPro }
        val dirty = before?.let { b ->
            val changed = (b.photos.keys + photos.keys).filterTo(HashSet()) { b.photos[it] !== photos[it] }
            // New video pictures (prepared after a change) only touch the videos' own frames.
            val videoFrames = if (b.videos === videos) emptyList() else plan.inserts.map { it.fromFrame until it.toFrame }
            ClipScenes.dirtyFrames(b.plan, plan, b.design, design, changed)?.let { ClipScenes.merge(it + videoFrames) }
        }
        val prepared = PreviewCache(
            newRenderer = { ClipRenderer(plan, base, style, photos, isPro, w, h, design, videos) },
            frameCount = plan.frameCount,
            step = PreviewMath.previewStep(plan.frameCount, plan.fps, w, h, budget),
            width = w, height = h, budget = budget,
            workers = PreviewMath.workers(Runtime.getRuntime().availableProcessors()),
            seed = before?.cache?.takeIf { dirty != null },
            dirty = dirty.orEmpty(),
        )
        prepared.start(PreviewMath.index(playhead(), prepared.step, prepared.count))
        EditorPerf.log("preparing ${prepared.count - prepared.reused} of ${prepared.count} frames")
        last[0] = PreparedFrom(plan, base, style, photos, isPro, design, videos, prepared)
        cache = prepared
        try {
            awaitCancellation()
        } finally {
            prepared.close()
        }
    }
    return cache
}

/**
 * Frames of [plan] for the preview at [width] × [height]. With a [cache], playing shows its prepared frames at a
 * steady rate (waiting with a "Preparing" state until they can play through without stalling). Without one, playing
 * first waits while the map's pictures still come in ([mapLoading], their share so far; owner: "maybe preprocess even
 * before show"), so a close-up never plays soft and sharpens later, then draws live at about 10 a second until the
 * prepared frames take over. A still frame when paused or scrubbed: prepared when there is one, else drawn live.
 * Nothing is drawn while not [active] (the other preview is showing).
 */
@Composable
internal fun rememberPreviewFrame(
    plan: ClipPlan, base: BaseMap, style: ClipStyle, photos: Map<String, Bitmap>, isPro: Boolean, design: ClipDesign,
    cache: PreviewCache?, width: Int, height: Int, player: PreviewPlayer, paused: Boolean, active: Boolean,
    mapLoading: Float? = null,
): PreviewFrame {
    val videos = LocalClipVideos.current
    val renderer = remember(plan, base, style, photos, isPro, design, videos, width, height) {
        ClipRenderer(plan, base, style, photos, isPro, width, height, design, videos)
    }
    val buffers = remember(width, height) { Array(3) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } }
    val lock = remember { Mutex() }
    var shown by remember(width, height) { mutableStateOf<ImageBitmap?>(null) }
    var preparing by remember { mutableStateOf<Float?>(null) }
    var preparingMap by remember { mutableStateOf(false) }
    val waitingMap by rememberUpdatedState(mapLoading)
    val slot = remember { intArrayOf(0) }
    // The renderer whose first picture was timed ([EditorPerf]).
    val timed = remember { arrayOfNulls<Any>(1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    suspend fun live(frame: Int): ImageBitmap {
        val bitmap = buffers[slot[0]++ % buffers.size]
        lock.withLock { withContext(Dispatchers.Default) { renderer.draw(frame, android.graphics.Canvas(bitmap), bitmap) } }
        return bitmap.asImageBitmap()
    }

    suspend fun prepared(jpeg: ByteArray): ImageBitmap? {
        val bitmap = buffers[slot[0]++ % buffers.size]
        return withContext(Dispatchers.Default) { decodeInto(jpeg, bitmap) }?.asImageBitmap()
    }

    val run = player.playing && !paused && foreground && active
    LaunchedEffect(renderer, cache, run, active, if (run) -1 else player.frame) {
        preparing = null
        preparingMap = false
        if (!active) return@LaunchedEffect
        val c = cache
        if (!run || c == null) {
            // A still frame, or playing live while there is nothing prepared yet (once the map is all in).
            var drawn = -1
            while (isActive) {
                val f = player.frame
                if (f != drawn) {
                    val jpeg = c?.let { at -> PreviewMath.index(f, at.step, at.count).let { i -> if (at.clipFrame(i) == f) at.get(i) else null } }
                    shown = jpeg?.let { prepared(it) } ?: live(f)
                    drawn = f
                    if (timed[0] !== renderer) {
                        timed[0] = renderer
                        EditorPerf.log("picture at ${ClipText.clock(f, plan.fps)} (${if (jpeg != null) "prepared" else "drawn live"})")
                    }
                }
                if (!run) break
                val loading = waitingMap
                if (loading != null) {
                    preparing = loading
                    preparingMap = true
                    delay(120)
                    continue
                }
                preparing = null
                preparingMap = false
                // About 10 fps, in step with the clip's time; hold the end card a moment before looping.
                delay(if (f >= plan.frameCount - 1) 900 else 100)
                player.frame = PreviewMath.nextFrame(f, plan.frameCount, plan.fps)
            }
            return@LaunchedEffect
        }
        val playFps = plan.fps.toDouble() / c.step
        val period = 1000.0 / playFps
        var i = PreviewMath.index(player.frame, c.step, c.count)
        // Wait until the rest of the clip from here can be prepared before playing reaches it (frames before the
        // playhead come after, while it plays).
        while (isActive && !PreviewMath.canStart(c.readyFrom(i), c.remainingFrom(i), c.renderFps(), playFps, minBuffer = playFps.toInt().coerceAtLeast(1))) {
            preparing = c.progress
            delay(80)
        }
        preparing = null
        var t0 = SystemClock.uptimeMillis()
        var n = 0L
        while (isActive) {
            val jpeg = c.get(i)
            if (jpeg == null && !c.finished) {
                // Played faster than prepared after all: wait for it, then carry on from here.
                preparing = c.progress
                delay(60)
                t0 = SystemClock.uptimeMillis()
                n = 0
                continue
            }
            preparing = null
            shown = jpeg?.let { prepared(it) } ?: live(c.clipFrame(i))
            player.frame = c.clipFrame(i)
            if (i >= c.count - 1) {
                // Hold the end card a moment, then loop.
                delay(900)
                i = 0
                t0 = SystemClock.uptimeMillis()
                n = 0
                continue
            }
            i++
            n++
            val wait = (t0 + n * period - SystemClock.uptimeMillis()).toLong()
            if (wait > 0) delay(wait) else if (wait < -3 * period) { t0 = SystemClock.uptimeMillis(); n = 0 }
        }
    }
    return PreviewFrame(shown, preparing, preparingMap)
}

/** Decodes a prepared frame into [into] (reused), or into a new bitmap when it can't be reused. */
private fun decodeInto(jpeg: ByteArray, into: Bitmap): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inMutable = true
        inBitmap = into
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return try {
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    } catch (_: IllegalArgumentException) {
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }
}

/** "Preparing · 42%" over the bottom of the preview while play waits for the prepared frames ("Loading the map" first). */
@Composable
internal fun BoxScope.PreparingBadge(progress: Float, map: Boolean = false) {
    Row(
        Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(CircleShape)
            .background(S.bg.copy(alpha = 0.88f)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.size(14.dp),
            color = S.yellow, strokeWidth = 2.dp, trackColor = S.raised,
        )
        Text("${if (map) "Loading the map" else "Preparing"} · ${(progress * 100).roundToInt()}%", style = StudioText.sub, color = S.ink)
    }
}

/** Scrubbing lands on prepared frames, so the picture follows the finger without drawing. */
private fun snap(frame: Float, cache: PreviewCache?): Int {
    val f = frame.roundToInt()
    return cache?.let { it.clipFrame(PreviewMath.index(f, it.step, it.count)) } ?: f
}

/** The preview full screen, from the same prepared frames, sharing its place in the clip with the inline one. */
@Composable
internal fun FullScreenPreview(
    plan: ClipPlan, base: BaseMap, style: ClipStyle, photos: Map<String, Bitmap>, isPro: Boolean, design: ClipDesign,
    cache: PreviewCache?, player: PreviewPlayer, paused: Boolean, mapLoading: Float?, onClose: () -> Unit,
) {
    val (w, h) = remember(plan.options.shape) { PreviewMath.size(plan.options.shape, PreviewMath.FULL_SHORT) }
    val frame = rememberPreviewFrame(plan, base, style, photos, isPro, design, cache, w, h, player, paused, active = true, mapLoading = mapLoading)
    FullScreenFrame(
        onClose = onClose,
        controls = {
            PlayControls(
                position = player.frame.toFloat(), length = (plan.frameCount - 1).coerceAtLeast(1).toFloat(),
                playing = player.playing, enabled = !paused,
                label = "${ClipText.clock(player.frame, plan.fps)} / ${ClipText.clock(plan.frameCount, plan.fps)}",
                onToggle = { player.playing = !player.playing },
                onSeek = { player.playing = false; player.frame = snap(it, cache) },
            )
        },
    ) {
        frame.image?.let {
            Image(
                it, contentDescription = "Clip preview",
                modifier = Modifier.fillMaxSize().clickable(
                    enabled = !paused, role = Role.Button,
                    onClickLabel = if (player.playing) "Pause preview" else "Play preview",
                ) { player.playing = !player.playing },
                contentScale = ContentScale.Fit,
            )
        }
        if (!player.playing && !paused) PlayBadge("Play preview") { player.playing = true }
        frame.preparing?.let { PreparingBadge(it, frame.map) }
    }
}

/**
 * A full-screen dialog: black, the picture fitted in the middle, a close button, and the play controls on a strip at
 * the bottom. Back closes it.
 */
@Composable
private fun FullScreenFrame(onClose: () -> Unit, controls: @Composable () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        StudioSystemBars()
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.Center) {
                content()
                StudioRoundButton(BtIcons.Close, "Close full screen", onClose, Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
            Box(Modifier.fillMaxWidth().background(S.bg).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
                controls()
            }
        }
    }
}

@Composable
internal fun BoxScope.MakingOverlay(progress: Float) {
    Box(Modifier.matchParentSize().background(S.stage.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Making the video · ${(progress * 100).roundToInt()}%", style = StudioText.item, textAlign = TextAlign.Center)
            Canvas(Modifier.width(140.dp).height(4.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(S.raised, cornerRadius = r)
                drawRoundRect(S.yellow, size = size.copy(width = size.width * progress), cornerRadius = r)
            }
        }
    }
}

/** Play/pause, a bar to scrub along, and where it is ("0:12 / 1:05"); for the preview (frames) and the video (ms). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayControls(
    position: Float, length: Float, playing: Boolean, enabled: Boolean, label: String,
    onToggle: () -> Unit, onSeek: (Float) -> Unit, modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button,
                onClickLabel = if (playing) "Pause" else "Play", onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) { Icon(if (playing) BtIcons.Pause else BtIcons.Play, if (playing) "Pause" else "Play", Modifier.size(24.dp), tint = if (enabled) S.ink else S.muted) }
        Slider(
            value = position.coerceIn(0f, length),
            onValueChange = onSeek,
            valueRange = 0f..length.coerceAtLeast(1f),
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = S.yellow, activeTrackColor = S.yellow, inactiveTrackColor = S.raised),
            thumb = { Box(Modifier.size(16.dp).clip(CircleShape).background(if (enabled) S.yellow else S.muted)) },
            track = { state ->
                val fraction = ((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val y = size.height / 2
                    drawLine(S.raised, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                    drawLine(if (enabled) S.yellow else S.muted, Offset(0f, y), Offset(size.width * fraction, y), size.height, StrokeCap.Round)
                }
            },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Position" },
        )
        Text(label, style = StudioText.value, maxLines = 1)
    }
}

/** Pauses the [player] video when the screen leaves the foreground. */
@Composable
private fun PauseOnLeave(player: () -> VideoView?, onPaused: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { player()?.pause(); onPaused() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** The finished clip full screen: from where the inline one was, with play and a bar to scrub; back hands the place back. */
@Composable
internal fun FullScreenVideo(file: File, shape: ClipShape, spot: VideoSpot, onClose: () -> Unit) {
    var player by remember { mutableStateOf<VideoView?>(null) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(spot.positionMs) }
    var playing by remember { mutableStateOf(false) }
    PauseOnLeave({ player }) { playing = false }
    LaunchedEffect(playing) {
        while (playing) {
            val p = player ?: break
            position = p.currentPosition
            if (!p.isPlaying) playing = false
            delay(100)
        }
    }
    val close = {
        player?.let { spot.positionMs = it.currentPosition; it.pause() }
        spot.handedBack++
        onClose()
    }
    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.start()
        playing = p.isPlaying
    }
    FullScreenFrame(
        onClose = close,
        controls = {
            PlayControls(
                position = position.toFloat(), length = duration.coerceAtLeast(1).toFloat(),
                playing = playing, enabled = duration > 0, label = PreviewMath.clockMs(position, duration),
                onToggle = ::toggle,
                onSeek = { ms -> position = ms.roundToInt(); player?.seekTo(position) },
            )
        },
    ) {
        Box(Modifier.align(Alignment.Center).aspectRatio(shape.aspect.toFloat())) {
            AndroidView(
                factory = { ctx -> VideoView(ctx).apply {
                    player = this
                    setOnPreparedListener { mp ->
                        mp.isLooping = false
                        duration = mp.duration
                        seekTo(spot.positionMs.coerceAtLeast(1))
                    }
                    setOnCompletionListener { playing = false; position = duration }
                    setVideoPath(file.path)
                } },
                onRelease = { it.stopPlayback(); player = null },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.matchParentSize().clickable(enabled = duration > 0, role = Role.Button,
                    onClickLabel = if (playing) "Pause video" else "Play video") { toggle() },
                contentAlignment = Alignment.Center,
            ) {
                if (duration > 0 && !playing) Box(
                    Modifier.size(56.dp).clip(CircleShape).background(S.ink.copy(alpha = 0.95f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(BtIcons.Play, "Play video", Modifier.size(26.dp), tint = S.bg) }
            }
        }
    }
}
