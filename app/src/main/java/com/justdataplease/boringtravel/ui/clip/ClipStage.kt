package com.justdataplease.boringtravel.ui.clip

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.clip.BaseMap
import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipPlan
import com.justdataplease.boringtravel.clip.ClipRenderer
import com.justdataplease.boringtravel.clip.ClipStyle
import com.justdataplease.boringtravel.clip.SceneDrag
import com.justdataplease.boringtravel.clip.SceneHit
import com.justdataplease.boringtravel.clip.SceneOverride
import com.justdataplease.boringtravel.ui.studio.StudioButton
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioIcons
import com.justdataplease.boringtravel.ui.studio.StudioNote
import com.justdataplease.boringtravel.ui.studio.StudioText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val S = StudioColors

/**
 * The preview on the studio's stage: the clip at the playhead (tap to play or pause, the corner button for full
 * screen), "Loading the map" or "Preparing" while play waits, and the export's progress over it. With a place or a
 * photo chosen ([movable], its scene key) and the preview still, its place name or card has a dashed ring and moves
 * with a finger: up and down for a name, anywhere for a card ([onDrop] keeps it). Before the map is in: preparing, or
 * why it couldn't load and Retry.
 */
@Composable
internal fun ClipStage(
    plan: ClipPlan, base: BaseMap, style: ClipStyle?, photos: Map<String, Bitmap>, isPro: Boolean, design: ClipDesign,
    cache: PreviewCache?, player: PreviewPlayer, mapStatus: ClipViewModel.MapStatus, mapLoading: Float?, mapReady: Boolean,
    working: Boolean, progress: Float?, movable: String?, still: Boolean, active: Boolean,
    onDrop: (String, SceneOverride) -> Unit, onFullScreen: () -> Unit, onRetryMap: () -> Unit, modifier: Modifier = Modifier,
) {
    Box(modifier.background(S.stage), contentAlignment = Alignment.Center) {
        if (style == null || !mapReady) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (mapStatus == ClipViewModel.MapStatus.Loading || style == null) {
                    CircularProgressIndicator(color = S.yellow, strokeWidth = 2.dp)
                    Text("Preparing your map", style = StudioText.note, textAlign = TextAlign.Center)
                } else {
                    Text("Your map couldn't load", style = StudioText.item, textAlign = TextAlign.Center)
                    StudioNote("Connect to the internet and try again.")
                    StudioButton("Retry map", onRetryMap)
                }
            }
        } else {
            StageFrame(
                plan, base, style, photos, isPro, design, cache, player, mapLoading, working, progress, movable, still, active,
                onDrop, onFullScreen,
            )
        }
    }
}

@Composable
private fun StageFrame(
    plan: ClipPlan, base: BaseMap, style: ClipStyle, photos: Map<String, Bitmap>, isPro: Boolean, design: ClipDesign,
    cache: PreviewCache?, player: PreviewPlayer, mapLoading: Float?, working: Boolean, progress: Float?, movable: String?,
    still: Boolean, active: Boolean, onDrop: (String, SceneOverride) -> Unit, onFullScreen: () -> Unit,
) {
    val shape = plan.options.shape
    val (w, h) = remember(shape) { PreviewMath.size(shape, PreviewMath.FULL_SHORT) }
    // While a finger drags the place name or a card, its change shows at once and is kept when the finger lifts.
    var dragging by remember(movable) { mutableStateOf<SceneOverride?>(null) }
    val shown = if (movable != null) dragging?.let { design.copy(overrides = design.overrides + (movable to it)) } ?: design else design
    val playing = player.playing
    val editable = movable != null && still && !playing && !working && active
    val moving = rememberPreviewFrame(plan, base, style, photos, isPro, design, cache, w, h, player, paused = working, active = active, mapLoading = mapLoading)
    val stillFrame = rememberStillFrame(plan, base, style, photos, isPro, shown, w, h, player.frame, movable, active = editable)
    val fresh = editable && stillFrame.frame == player.frame && stillFrame.key == movable
    val hit = stillFrame.hit.takeIf { fresh }
    val latestHit by rememberUpdatedState(hit)
    val latestDesign by rememberUpdatedState(design)
    val corner = RoundedCornerShape(6.dp)
    BoxWithConstraints(Modifier.fillMaxSize().padding(vertical = 6.dp, horizontal = 12.dp), contentAlignment = Alignment.Center) {
        val aspect = shape.aspect.toFloat()
        val frameModifier = if (maxWidth / maxHeight > aspect) Modifier.height(maxHeight).aspectRatio(aspect) else Modifier.width(maxWidth).aspectRatio(aspect)
        val label = if (playing) "Pause preview" else "Play preview"
        Box(
            frameModifier.clip(corner).background(S.raised)
                .pointerInput(movable, editable) {
                    val key = movable ?: return@pointerInput
                    if (!editable) return@pointerInput
                    var start: Offset? = null
                    var moved = Offset.Zero
                    var current: SceneOverride? = null
                    detectDragGestures(
                        onDragStart = {
                            val box = latestHit?.box
                            start = box?.let { b -> Offset(b.centerX / w, b.centerY / h) }
                            moved = Offset.Zero
                            current = null
                        },
                        onDrag = { change, amount ->
                            val target = latestHit ?: return@detectDragGestures
                            val from = start ?: return@detectDragGestures
                            change.consume()
                            moved += amount
                            val y = SceneDrag.rounded(SceneDrag.move(from.y, moved.y, size.height.toFloat(), target.yRange))
                            val x = target.xRange?.let { SceneDrag.rounded(SceneDrag.move(from.x, moved.x, size.width.toFloat(), it)) }
                            val o = latestDesign.scene(key)
                            current = if (x != null) o.copy(x = x, y = y) else o.copy(y = y)
                            dragging = current
                        },
                        onDragEnd = {
                            current?.let { onDrop(key, it) }
                            dragging = null
                        },
                        onDragCancel = {
                            current?.let { onDrop(key, it) }
                            dragging = null
                        },
                    )
                }
                .clickable(interactionSource = null, indication = null, enabled = !working, role = Role.Button, onClickLabel = label) {
                    player.playing = !player.playing
                }
                .semantics { contentDescription = "Clip preview" },
        ) {
            val image = if (fresh) stillFrame.image ?: moving.image else moving.image
            image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (hit != null) {
                val ring = S.coral
                Canvas(Modifier.fillMaxSize()) {
                    val sx = size.width / w
                    val sy = size.height / h
                    val pad = 4.dp.toPx()
                    drawRoundRect(
                        ring,
                        topLeft = Offset(hit.box.left * sx - pad, hit.box.top * sy - pad),
                        size = Size(hit.box.width * sx + 2 * pad, hit.box.height * sy + 2 * pad),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))),
                    )
                }
            }
            if (!working) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(34.dp).clip(CircleShape).background(S.bg.copy(alpha = 0.72f))
                        .clickable(role = Role.Button, onClickLabel = "Full screen", onClick = onFullScreen)
                        .semantics { contentDescription = "Full screen" },
                    contentAlignment = Alignment.Center,
                ) { Icon(StudioIcons.FullScreen, null, Modifier.size(16.dp), tint = S.ink) }
            }
            // Playing waits for the map or the prepared frames; still, the map's last pictures may be coming in.
            if (playing) moving.preparing?.let { PreparingBadge(it, moving.map) } else mapLoading?.let { PreparingBadge(it, map = true) }
            if (progress != null) MakingOverlay(progress)
        }
    }
}

/** A still frame drawn for the editor ([frame] of scene [key]'s view), and where that scene's movable part is in it. */
private class StillFrame(val image: ImageBitmap?, val hit: SceneHit?, val frame: Int = -1, val key: String? = null)

/**
 * The frame [frame] of [plan] in [design], drawn live while [active] (the preview still, a scene chosen), with where
 * the scene [key]'s place name or card landed. Two buffers: a new picture never draws into the one on screen.
 */
@Composable
private fun rememberStillFrame(
    plan: ClipPlan, base: BaseMap, style: ClipStyle, photos: Map<String, Bitmap>, isPro: Boolean, design: ClipDesign,
    width: Int, height: Int, frame: Int, key: String?, active: Boolean,
): StillFrame {
    val videos = LocalClipVideos.current
    val renderer = remember(plan, base, style, photos, isPro, design, videos, width, height) {
        ClipRenderer(plan, base, style, photos, isPro, width, height, design, videos)
    }
    val buffers = remember(width, height) { Array(2) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } }
    val onScreen = remember { intArrayOf(-1) }
    val lock = remember { Mutex() }
    var still by remember { mutableStateOf(StillFrame(null, null)) }
    LaunchedEffect(renderer, frame, key, active) {
        if (!active || key == null) return@LaunchedEffect
        lock.withLock {
            val i = (onScreen[0] + 1).mod(buffers.size)
            val bitmap = buffers[i]
            val hit = withContext(Dispatchers.Default) {
                renderer.draw(frame, android.graphics.Canvas(bitmap), bitmap)
                renderer.hit(key)
            }
            onScreen[0] = i
            still = StillFrame(bitmap.asImageBitmap(), hit, frame, key)
        }
    }
    return still
}
