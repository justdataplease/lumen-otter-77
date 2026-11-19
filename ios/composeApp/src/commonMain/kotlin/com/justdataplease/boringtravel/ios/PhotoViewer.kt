package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/*
 * Videos played in the app, as the Android app plays them (the second parity round, 19 Nov 2026): its own full-screen
 * player for a journal's video (ui/community/MediaViewer.kt ClipViewer, used by ui/journal/DocView.kt PlayVideo) and the
 * finished trip video's Watch (ui/clip/ClipPreview.kt FullScreenVideo, in ui/clip/ClipPreview.kt here). The controls
 * are Compose, Android's own; the picture and the sound are the phone's player ([VideoPlayback]: AVPlayer drawn by an
 * AVPlayerLayer on iPhone, iosMain's IosVideoPlayback). A note's photos open nothing, as on Android (its DocView makes
 * only videos tappable).
 */

/**
 * One video in the phone's player, behind the app's own controls. Everything on the main thread. Its length is known
 * once [listen]'s ready came (0 before).
 */
interface PlayingVideo {
    /** How long it is, in ms; 0 until it is ready. */
    val durationMs: Int

    /** Where it is now, in ms. */
    val positionMs: Int

    /** Playing now (not paused, not at its end). */
    val isPlaying: Boolean

    fun play()

    fun pause()

    /** Goes to [ms] (paused or playing as it was). */
    fun seekTo(ms: Int)

    /**
     * [ready] once it can play (its length known), [ended] when it reached its end (a looping one starts again instead),
     * [failed] when it can't be played. Each on the main thread; called once per player.
     */
    fun listen(ready: () -> Unit, ended: () -> Unit, failed: () -> Unit)

    /** Stops it and lets go of the file (the screen went). */
    fun release()
}

/** The phone's video player: a [PlayingVideo] for a file, and the view its pictures are drawn in. */
interface VideoPlayback {
    /** A player for the video at [path] (under the app's files) that plays again from the start at its end when [loop]; null when this phone has none. */
    fun open(path: String, loop: Boolean): PlayingVideo?

    /** [video]'s pictures, fitted in [modifier] with their own shape kept (Android's VideoView; AVPlayerLayer on iPhone). */
    @Composable
    fun Surface(video: PlayingVideo, modifier: Modifier)
}

/** No player: every video says it can't be played. */
object NoVideoPlayback : VideoPlayback {
    override fun open(path: String, loop: Boolean): PlayingVideo? = null

    @Composable
    override fun Surface(video: PlayingVideo, modifier: Modifier) = Unit
}

/**
 * A player for the desktop, previews and tests: silent, the video's first picture (the kit's [VideoFrames]) standing in
 * for its pictures, its time moving on while it "plays". [lengthOf] says how long a video is (null: it can't be played).
 * Every player opened is kept in [opened], with what it was asked ([StillVideo.asked]).
 */
class StillVideoPlayback(private val lengthOf: (String) -> Int? = { 45_000 }, private val startAt: Int = 0) : VideoPlayback {
    val opened = mutableListOf<StillVideo>()

    override fun open(path: String, loop: Boolean): PlayingVideo = StillVideo(path, lengthOf(path), loop, startAt).also { opened += it }

    @Composable
    override fun Surface(video: PlayingVideo, modifier: Modifier) {
        val kit = LocalMediaKit.current
        val path = (video as? StillVideo)?.path
        val picture by produceState<ImageBitmap?>(null, path) {
            value = path?.let { p -> withContext(MediaWork) { runCatching { kit.frames.poster(p, 1280)?.let { Pictures.decode(it, 1280) } }.getOrNull() } }
        }
        Box(modifier, contentAlignment = Alignment.Center) {
            picture?.let { Image(it, "Video", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
    }

    /** A still video: [length] null can't be played. Its clock is the real one while it plays. */
    class StillVideo(val path: String, private val length: Int?, val loop: Boolean, startAt: Int) : PlayingVideo {
        /** What it was asked, in order ("play", "pause", "seek 12000", "release"). */
        val asked = mutableListOf<String>()
        private var at = startAt
        private var since: TimeSource.Monotonic.ValueTimeMark? = null

        override val durationMs: Int get() = length ?: 0

        override val positionMs: Int
            get() {
                val run = since?.elapsedNow()?.inWholeMilliseconds?.toInt() ?: 0
                val d = durationMs.coerceAtLeast(1)
                return if (loop) (at + run) % d else (at + run).coerceAtMost(durationMs)
            }

        override val isPlaying: Boolean get() = since != null && (loop || positionMs < durationMs)

        override fun play() {
            asked += "play"
            if (since == null) since = TimeSource.Monotonic.markNow()
        }

        override fun pause() {
            asked += "pause"
            at = positionMs
            since = null
        }

        override fun seekTo(ms: Int) {
            asked += "seek $ms"
            at = ms.coerceIn(0, durationMs)
            if (since != null) since = TimeSource.Monotonic.markNow()
        }

        override fun listen(ready: () -> Unit, ended: () -> Unit, failed: () -> Unit) {
            if (length == null) failed() else ready()
        }

        override fun release() {
            asked += "release"
            since = null
        }
    }
}

/** Full screen on black: [content] with a close button (the Android app's BlackDialog); the system's Back closes it too. */
@Composable
fun BlackDialog(onClose: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, usePlatformInsets = false, scrimColor = Color.Black)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            content()
            Box(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).size(44.dp).clip(CircleShape)
                    .background(Color(0x66000000)).clickable(role = Role.Button, onClickLabel = "Close", onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(BtIcons.Close, "Close", Modifier.size(22.dp), tint = Color.White) }
        }
    }
}

/** The play mark over a paused video (the Android app's PlayMark in ui/community/CommunityParts.kt). */
@Composable
fun PlayMark(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(modifier.size(size).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Icon(BtIcons.Play, null, Modifier.size(size * 0.46f), tint = Color.White)
    }
}

/**
 * A video full screen, with sound: plays at once, a tap pauses or plays (the Android app's ClipViewer). A downloaded
 * clip loops ([loop]); a journal video plays once with [controls] (play or pause, a bar to move through it, where it is
 * of how long). "This video can't be played." on the black when the phone can't play it.
 */
@Composable
fun ClipViewer(path: String, onClose: () -> Unit, loop: Boolean = true, controls: Boolean = false) {
    val playback = LocalMediaKit.current.playback
    val player = remember(path) { playback.open(path, loop) }
    var paused by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(player == null) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        player?.listen(
            ready = {
                duration = player.durationMs.coerceAtLeast(0)
                player.play()
                paused = false
            },
            ended = {
                paused = true
                position = duration
            },
            // No system dialog: a line on the black instead.
            failed = { failed = true },
        )
        onDispose { player?.release() }
    }
    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        paused = !p.isPlaying
    }
    BlackDialog(onClose) {
        // Fitted to the screen, its own shape kept (a 720p video fills the width like a 1080p one).
        if (player != null && !failed) playback.Surface(player, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button,
                onClickLabel = if (paused) "Play" else "Pause",
            ) { toggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (failed) Text("This video can't be played.", style = Bt.text.body, color = Color.White)
            else if (paused) PlayMark(size = 64.dp)
        }
        if (controls && !failed) {
            LaunchedEffect(player) {
                while (true) {
                    val p = player
                    if (p != null && !dragging && p.isPlaying) position = p.positionMs
                    delay(200)
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .padding(start = 8.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = if (paused) "Play" else "Pause") { toggle() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (paused) BtIcons.Play else BtIcons.Pause, if (paused) "Play" else "Pause", Modifier.size(24.dp), tint = Color.White) }
                Slider(
                    value = position.coerceIn(0, duration.coerceAtLeast(1)).toFloat(),
                    onValueChange = {
                        dragging = true
                        position = it.toInt()
                    },
                    onValueChangeFinished = {
                        player?.seekTo(position)
                        dragging = false
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    enabled = duration > 0,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                Text("${videoClock(position)} / ${videoClock(duration)}", style = Bt.text.mono, color = Color.White, maxLines = 1)
            }
        }
    }
}

/** "0:07", "1:00": minutes and whole seconds of [ms] (the Android app's MediaViewer clock). */
fun videoClock(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * Journal video [media] full screen with sound and simple controls (the Android app's PlayVideo: the community's clip
 * player, once, with controls). A video whose file isn't on this phone says so instead ("This video isn't on this phone
 * yet. Try again in a moment.", Android's toast; here the app's snackbar).
 */
@Composable
fun PlayVideo(media: Media, onClose: () -> Unit) {
    val kit = LocalMediaKit.current
    val say = LocalSay.current
    val ready by produceState<Boolean?>(null, media.localPath) {
        value = withContext(MediaWork) { (kit.files.size(media.localPath) ?: 0L) > 0L }
    }
    when (ready) {
        null -> Unit
        true -> ClipViewer(media.localPath, onClose, loop = false, controls = true)
        false -> LaunchedEffect(media.localPath) {
            say("This video isn't on this phone yet. Try again in a moment.")
            onClose()
        }
    }
}
