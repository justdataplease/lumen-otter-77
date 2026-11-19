package com.justdataplease.boringtravel.ui.community

import android.net.Uri
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.delay
import java.io.File

/** Full screen on black: [content] with a close button; back closes too. */
@Composable
private fun BlackDialog(onClose: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
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

/** A post's photos full screen, swiped through, starting at [start]; "3 / 12" at the bottom. */
@Composable
fun PhotoViewer(ids: List<String>, start: Int, onClose: () -> Unit) {
    if (ids.isEmpty()) return
    val pager = rememberPagerState(initialPage = start.coerceIn(0, ids.lastIndex)) { ids.size }
    BlackDialog(onClose) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { ids[it] }) { page ->
            DrivePicture(ids[page], Modifier.fillMaxSize().background(Color.Black), "Photo ${page + 1} of ${ids.size}", ContentScale.Fit)
        }
        if (ids.size > 1) {
            Text(
                "${pager.currentPage + 1} / ${ids.size}",
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)
                    .clip(CircleShape).background(Color(0x66000000)).padding(horizontal = 12.dp, vertical = 4.dp),
                style = Bt.text.mono, color = Color.White,
            )
        }
    }
}

/**
 * A video full screen, with sound: plays at once, a tap pauses or plays. A downloaded clip loops ([loop]); a journal
 * video plays once with [controls] (play or pause, a bar to move through it, where it is of how long).
 */
@Composable
fun ClipViewer(file: File, onClose: () -> Unit, loop: Boolean = true, controls: Boolean = false) {
    var player by remember { mutableStateOf<VideoView?>(null) }
    var paused by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    BlackDialog(onClose) {
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    val video = VideoView(ctx).apply {
                        setOnPreparedListener { mp ->
                            mp.isLooping = loop
                            duration = mp.duration.coerceAtLeast(0)
                            start()
                            paused = false
                        }
                        setOnCompletionListener {
                            paused = true
                            position = duration
                        }
                        // No system dialog: a line on the black instead.
                        setOnErrorListener { _, _, _ ->
                            failed = true
                            true
                        }
                        setVideoURI(Uri.fromFile(file))
                    }
                    player = video
                    // Fitted to the screen, its own shape kept (a 720p video fills the width like a 1080p one).
                    addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                }
            },
            onRelease = {
                player?.stopPlayback()
                player = null
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button,
                onClickLabel = if (paused) "Play" else "Pause",
            ) {
                val p = player ?: return@clickable
                if (p.isPlaying) p.pause() else p.start()
                paused = !p.isPlaying
            },
            contentAlignment = Alignment.Center,
        ) {
            if (failed) Text("This video can't be played.", style = Bt.text.body, color = Color.White)
            else if (paused) PlayMark(size = 64.dp)
        }
        if (controls && !failed) {
            LaunchedEffect(player) {
                while (true) {
                    val p = player
                    if (p != null && !dragging && p.isPlaying) position = p.currentPosition
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
                    Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = if (paused) "Play" else "Pause") {
                        val p = player ?: return@clickable
                        if (p.isPlaying) p.pause() else p.start()
                        paused = !p.isPlaying
                    },
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
                Text("${clock(position)} / ${clock(duration)}", style = Bt.text.mono, color = Color.White, maxLines = 1)
            }
        }
    }
}

/** "0:07", "1:00": minutes and whole seconds of [ms]. */
private fun clock(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** Small dots under a pager: the page on show filled. */
@Composable
fun PagerDots(state: PagerState, modifier: Modifier = Modifier) {
    if (state.pageCount < 2) return
    val c = Bt.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val shown = minOf(state.pageCount, 9)
        val first = (state.currentPage - shown / 2).coerceIn(0, state.pageCount - shown)
        for (i in first until first + shown) {
            val on = i == state.currentPage
            Box(Modifier.size(if (on) 8.dp else 6.dp).clip(CircleShape).background(if (on) c.ink else c.muted.copy(alpha = 0.45f)))
        }
    }
}
