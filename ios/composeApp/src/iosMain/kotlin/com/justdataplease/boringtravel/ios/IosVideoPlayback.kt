@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

/*
 * The app's video player on iPhone (the second parity round): AVPlayer for the picture and the sound, drawn by an
 * AVPlayerLayer in a plain UIView that Compose puts on screen (UIKitView), under the app's own controls in common code
 * (ClipViewer, FullScreenVideo: Android's). Apple's calls here (AVPlayer, AVPlayerItem and its status, its end and
 * failure notifications, AVPlayerLayer, CATransaction, NSTimer, AVAudioSession's playback category) were only
 * type-checked on Windows.
 */

/** Videos under the app's files [files], played by AVPlayer. */
class IosVideoPlayback(private val files: IosMediaFiles) : VideoPlayback {
    override fun open(path: String, loop: Boolean): PlayingVideo? = openAt(files.absolute(path), loop)

    /** The video at [fullPath] (also outside the app's files: a community clip in the caches, round 24). */
    override fun openAt(fullPath: String, loop: Boolean): PlayingVideo? {
        val full = fullPath
        if (!NSFileManager.defaultManager.fileExistsAtPath(full)) return null
        // Sound even with the phone on silent, as a video someone chose to play (Android plays it on the media volume).
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        return IosPlayingVideo(NSURL.fileURLWithPath(full), loop)
    }

    @Composable
    override fun Surface(video: PlayingVideo, modifier: Modifier) {
        val player = (video as? IosPlayingVideo)?.player ?: return
        UIKitView(
            factory = { PlayerView(player) },
            modifier = modifier,
            // The taps are the app's (play, pause, the bar), drawn over it.
            properties = UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false),
        )
    }
}

/** A UIView whose AVPlayerLayer fills it: the picture fitted, its own shape kept, on black (Android's VideoView). */
private class PlayerView(player: AVPlayer) : UIView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0)) {
    private val pictures: AVPlayerLayer = AVPlayerLayer.playerLayerWithPlayer(player)

    init {
        backgroundColor = UIColor.blackColor
        pictures.videoGravity = AVLayerVideoGravityResizeAspect
        layer.addSublayer(pictures)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        // The layer follows the view's size at once (no implicit animation).
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        pictures.frame = bounds
        CATransaction.commit()
    }
}

/**
 * One video in AVPlayer. Ready is read from the item's status (looked at ten times a second until it is known), the end
 * and a failure from the item's notifications; a looping one starts again from the start at its end.
 */
private class IosPlayingVideo(url: NSURL, private val loop: Boolean) : PlayingVideo {
    private val item = AVPlayerItem(uRL = url)
    val player = AVPlayer(playerItem = item)
    private var timer: NSTimer? = null
    private val observers = mutableListOf<Any>()
    private var atEnd = false

    override val durationMs: Int
        get() = CMTimeGetSeconds(item.duration).let { if (it.isFinite() && it > 0) (it * 1000).toInt() else 0 }

    override val positionMs: Int
        get() = CMTimeGetSeconds(player.currentTime()).let { if (it.isFinite() && it > 0) (it * 1000).toInt() else 0 }

    override val isPlaying: Boolean get() = player.rate != 0f

    override fun play() {
        // At its end, play starts it again from the start (as Android's VideoView does after completing).
        if (atEnd) {
            atEnd = false
            player.seekToTime(CMTimeMake(0, 1))
        }
        player.play()
    }

    override fun pause() = player.pause()

    override fun seekTo(ms: Int) {
        atEnd = false
        player.seekToTime(CMTimeMake(ms.coerceAtLeast(0).toLong(), 1000), toleranceBefore = CMTimeMake(0, 1), toleranceAfter = CMTimeMake(0, 1))
    }

    override fun listen(ready: () -> Unit, ended: () -> Unit, failed: () -> Unit) {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, `object` = item, queue = NSOperationQueue.mainQueue) { _ ->
            if (loop) {
                player.seekToTime(CMTimeMake(0, 1))
                player.play()
            } else {
                atEnd = true
                ended()
            }
        }?.let { observers += it }
        center.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification, `object` = item, queue = NSOperationQueue.mainQueue) { _ ->
            failed()
        }?.let { observers += it }
        timer = NSTimer.scheduledTimerWithTimeInterval(0.1, repeats = true) { t ->
            when (item.status) {
                AVPlayerItemStatusReadyToPlay -> {
                    t?.invalidate()
                    timer = null
                    ready()
                }
                AVPlayerItemStatusFailed -> {
                    t?.invalidate()
                    timer = null
                    failed()
                }
                else -> Unit
            }
        }
    }

    override fun release() {
        timer?.invalidate()
        timer = null
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers.clear()
        player.pause()
        if (player.currentItem != null) player.replaceCurrentItemWithPlayerItem(null)
    }
}
