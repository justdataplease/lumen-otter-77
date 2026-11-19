package com.justdataplease.boringtravel.ui.clip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.justdataplease.boringtravel.clip.music.MusicTrack
import com.justdataplease.boringtravel.ios.ClipSounds
import com.justdataplease.boringtravel.ios.LocalClipDevice
import com.justdataplease.boringtravel.ios.TrackPlayer
import com.justdataplease.boringtravel.ios.resources.Res
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/*
 * The trip video's music in the editor (the Android app's ui/clip/MusicPreview.kt, written again over the phone's
 * [ClipSounds]: AVAudioPlayer on iPhone, where Android plays with MediaPlayer): Audio's Listen, one track at a time, and
 * the chosen track under the preview while it plays, in step with the playhead. The same rules: it starts where the
 * clip's music starts, loops back to it past the track's end, waits while the preview waits, and jumps back in step
 * only when it drifts more than [PreviewAudio.DRIFT_MS].
 */

/** The bundled track's file as the phone opens it: the app's own resources (the app bundle on iPhone). */
internal fun musicUri(track: MusicTrack): String = Res.getUri("files/${track.asset}")

/**
 * Plays one bundled track at a time from the editor's Music panel ([MusicList]); [playing] is its id while it plays.
 */
@Stable
internal class MusicPreview(private val sounds: ClipSounds) {
    var playing by mutableStateOf<String?>(null)
        private set
    private var player: TrackPlayer? = null

    fun toggle(track: MusicTrack, fromMs: Int = 0) = if (playing == track.id) stop() else play(track, fromMs)

    /** Plays [track] from [fromMs] into it. */
    fun play(track: MusicTrack, fromMs: Int = 0) {
        stop()
        var opened: TrackPlayer? = null
        opened = runCatching { sounds.open(musicUri(track)) { if (opened != null && player === opened) stop() } }.getOrNull()
        val p = opened
        if (p == null) {
            playing = null
            return
        }
        player = p
        playing = track.id
        if (runCatching { p.play(fromMs) }.isFailure) stop()
    }

    fun stop() {
        val p = player ?: return
        player = null
        playing = null
        runCatching { p.release() }
    }
}

/**
 * The clip's music under the preview while it plays (a panel of editors: "the preview is silent, you can't hear a
 * cut"): [track] from [startS] into it at the playhead's time, looping back to [startS] past its end; paused while the
 * preview waits for frames or stops, put back in step when it drifts. The export mixes the real thing (round 20); this
 * is only to hear where things fall.
 */
@Composable
internal fun PreviewMusic(player: PreviewPlayer, fps: Int, track: MusicTrack?, startS: Double, active: Boolean) {
    val sounds = LocalClipDevice.current.sounds
    val audio = remember(track?.id, sounds) { track?.let { PreviewAudio(sounds, it) } } ?: return
    DisposableEffect(audio) { onDispose { audio.release() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, audio) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) audio.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val playing = player.playing && active
    LaunchedEffect(audio, playing, startS, fps) {
        if (!playing) {
            audio.pause()
            return@LaunchedEffect
        }
        var last = -1
        var stillFor = 0
        while (isActive) {
            val f = player.frame
            if (f == last) {
                // The preview is waiting for frames (or holding the end card): the music waits too.
                if (++stillFor >= 2) audio.pause()
            } else {
                stillFor = 0
                audio.playAt(previewMusicMs(f, fps, startS, audio.track.durationMs))
            }
            last = f
            delay(150)
        }
    }
}

/**
 * Where the music is at clip frame [frame]: [startS] into the track plus the clip's time, looping back to [startS]
 * past the track's end ([durationMs]), in ms (Android's wantMs).
 */
internal fun previewMusicMs(frame: Int, fps: Int, startS: Double, durationMs: Int): Int {
    val loopS = (durationMs / 1000.0 - startS).coerceAtLeast(1.0)
    return ((startS + (frame.toDouble() / fps.coerceAtLeast(1)) % loopS) * 1000).toInt()
}

/** One player for [track], opened once; [playAt] starts it, or moves it when it is more than a little off. */
internal class PreviewAudio(sounds: ClipSounds, val track: MusicTrack) {
    private val player: TrackPlayer? = runCatching { sounds.open(musicUri(track)) }.getOrNull()

    fun playAt(ms: Int) {
        val p = player ?: return
        runCatching {
            if (!p.isPlaying) {
                p.play(ms)
            } else if (abs(p.positionMs - ms) > DRIFT_MS) {
                p.seekTo(ms)
            }
        }
    }

    fun pause() {
        val p = player ?: return
        runCatching { if (p.isPlaying) p.pause() }
    }

    fun release() {
        runCatching { player?.release() }
    }

    internal companion object {
        /** Off by more than this, it jumps back in step (a jump is heard, so not for less). */
        const val DRIFT_MS = 220
    }
}
