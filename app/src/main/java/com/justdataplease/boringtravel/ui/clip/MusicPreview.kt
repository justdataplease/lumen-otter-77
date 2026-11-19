package com.justdataplease.boringtravel.ui.clip

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.justdataplease.boringtravel.clip.music.MusicTrack

/**
 * Plays one bundled track at a time from the editor's Music panel ([MusicList]); [playing] is its id while it plays.
 */
@Stable
internal class MusicPreview(private val context: Context) {
    var playing by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null

    fun toggle(track: MusicTrack, fromMs: Int = 0) = if (playing == track.id) stop() else play(track, fromMs)

    /** Plays [track] from [fromMs] into it. */
    fun play(track: MusicTrack, fromMs: Int = 0) {
        stop()
        val mp = MediaPlayer()
        try {
            context.assets.openFd(track.asset).use { mp.setDataSource(it) }
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            mp.setOnPreparedListener {
                if (player === it) {
                    if (fromMs > 0) it.seekTo(fromMs)
                    it.start()
                }
            }
            mp.setOnCompletionListener { if (player === it) stop() }
            mp.setOnErrorListener { p, _, _ -> if (player === p) stop(); true }
            player = mp
            playing = track.id
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w("MusicPreview", "Couldn't play ${track.id}", e)
            if (player === mp) player = null
            mp.release()
            playing = null
        }
    }

    fun stop() {
        val mp = player ?: return
        player = null
        playing = null
        runCatching { mp.stop() }
        mp.release()
    }
}

/**
 * The clip's music under the preview while it plays (a panel of editors: "the preview is silent, you can't hear a
 * cut"): [track] from [startS] into it at the playhead's time, looping back to [startS] past its end; paused while
 * the preview waits for frames or stops, put back in step when it drifts. The export mixes the real thing
 * ([com.justdataplease.boringtravel.clip.music.ClipMusic]); this is only to hear where things fall.
 */
@Composable
internal fun PreviewMusic(player: PreviewPlayer, fps: Int, track: MusicTrack?, startS: Double, active: Boolean) {
    val context = LocalContext.current
    val audio = remember(track?.id) { track?.let { PreviewAudio(context.applicationContext, it) } } ?: return
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
        val loopS = (audio.track.durationMs / 1000.0 - startS).coerceAtLeast(1.0)
        fun wantMs(frame: Int): Int = ((startS + (frame.toDouble() / fps.coerceAtLeast(1)) % loopS) * 1000).toInt()
        var last = -1
        var stillFor = 0
        while (isActive) {
            val f = player.frame
            if (f == last) {
                // The preview is waiting for frames (or holding the end card): the music waits too.
                if (++stillFor >= 2) audio.pause()
            } else {
                stillFor = 0
                audio.playAt(wantMs(f))
            }
            last = f
            delay(150)
        }
    }
}

/** One MediaPlayer for [track], prepared once; [playAt] starts it, or seeks when it is more than a little off. */
internal class PreviewAudio(context: Context, val track: MusicTrack) {
    private val mp = MediaPlayer()
    private var ready = false
    private var pendingMs: Int? = null

    init {
        try {
            context.assets.openFd(track.asset).use { mp.setDataSource(it) }
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            mp.isLooping = false
            mp.setOnPreparedListener {
                ready = true
                pendingMs?.let { ms -> it.seekTo(ms); it.start() }
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w("MusicPreview", "Couldn't prepare ${track.id} for the preview", e)
        }
    }

    fun playAt(ms: Int) {
        if (!ready) {
            pendingMs = ms
            return
        }
        runCatching {
            if (!mp.isPlaying) {
                mp.seekTo(ms)
                mp.start()
            } else if (kotlin.math.abs(mp.currentPosition - ms) > DRIFT_MS) {
                mp.seekTo(ms)
            }
        }
    }

    fun pause() {
        pendingMs = null
        if (ready) runCatching { if (mp.isPlaying) mp.pause() }
    }

    fun release() {
        ready = false
        runCatching { mp.release() }
    }

    private companion object {
        /** Off by more than this, it jumps back in step (a jump is heard, so not for less). */
        const val DRIFT_MS = 220
    }
}
