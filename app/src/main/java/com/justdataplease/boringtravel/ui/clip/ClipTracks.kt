package com.justdataplease.boringtravel.ui.clip

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.clip.ClipText
import com.justdataplease.boringtravel.ui.components.MediaImage
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioIcons
import com.justdataplease.boringtravel.ui.studio.StudioText
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private val S = StudioColors

/** The tracks' colours: map scenes by day, then the other kinds (Editor mockup). */
private object TrackColors {
    val days = listOf(Color(0xFF4C6FD8), Color(0xFF13A89E), Color(0xFF5E8C3A), Color(0xFFB8566E))
    val video = Color(0xFF8A5CC9)
    val dayCard = Color(0xFFC7861F)
    val bookend = Color(0xFF3A3766)
    val text = Color(0xFF3A3766)
    val music = Color(0xFF0F6E68)
    val wave = Color(0xFF2DD4C5)
    val voice = Color(0xFFB8433A)
    val closeUp = Color(0xFFFFC93C)
}

private val LABEL_W = 40.dp
private val RULER_H = 18.dp
private val SCENES_H = 44.dp
private val PHOTOS_H = 32.dp
private val TEXT_H = 24.dp
private val MUSIC_H = 28.dp
private val VOICE_H = 26.dp
private val ROW_GAP = 4.dp
private val THUMB = 28.dp
/** The tracks' whole height: the ruler and five tracks with their gaps. */
private val TRACKS_H = 2.dp + RULER_H + SCENES_H + PHOTOS_H + TEXT_H + MUSIC_H + VOICE_H + ROW_GAP * 5 + 6.dp

/**
 * The clip's tracks under a fixed white playhead (owner: "video editor tracks"): a time ruler, the map scenes (one
 * block per place, coloured by day, the traveller's videos and the day cards between, close-ups marked), the photos at
 * their pause points (those left out dimmed), the texts, the music with its beats and the voice lines with "+ Record".
 * Drag to scrub (pinch to zoom the time); tap a block to choose its scene ([onPick]), the music to open its panel
 * ([onMusic]), "+ Record" to record a line for the place at the playhead ([onRecord]). [recording]: where a line is
 * being recorded and since when (elapsed realtime).
 */
@Composable
internal fun ClipTracks(
    model: TrackModel,
    player: PreviewPlayer,
    cache: PreviewCache?,
    chosen: String?,
    initialDpPerSecond: Float,
    music: String?,
    recordAt: Int?,
    recording: Pair<Int, Long>?,
    enabled: Boolean,
    onScrubbing: (Boolean) -> Unit,
    onPick: (ScenePick?) -> Unit,
    onMusic: () -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var dpPerSecond by rememberSaveable { mutableFloatStateOf(initialDpPerSecond) }
    // While a finger scrubs, the tracks follow it smoothly; the preview shows the nearest prepared frame.
    var dragFrame by remember { mutableStateOf<Float?>(null) }
    val scale = TrackScale(model.fps, dpPerSecond)
    val px = TrackScale(model.fps, dpPerSecond * density.density)
    val latestScrubbing by rememberUpdatedState(onScrubbing)
    val latestCache by rememberUpdatedState(cache)
    fun current(): Float = dragFrame ?: player.frame.toFloat()

    BoxWithConstraints(
        modifier.height(TRACKS_H).background(S.panel)
            .pointerInput(model.frames, model.fps, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var started = false
                    var pan = 0f
                    var zoom = 1f
                    do {
                        val event = awaitPointerEvent()
                        val z = event.calculateZoom()
                        val dx = event.calculatePan().x
                        if (!started) {
                            pan += dx
                            zoom *= z
                            if (abs(pan) > viewConfiguration.touchSlop || abs(1f - zoom) > 0.06f) {
                                started = true
                                player.playing = false
                                dragFrame = player.frame.toFloat()
                                latestScrubbing(true)
                            }
                        }
                        if (started) {
                            if (z != 1f) dpPerSecond = TrackScale(model.fps, dpPerSecond).zoomed(z).dpPerSecond
                            if (dx != 0f) {
                                val step = TrackScale(model.fps, dpPerSecond * density.density)
                                val next = step.scrub(current(), dx, model.frames)
                                dragFrame = next
                                player.frame = snapFrame(next, latestCache)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (started) {
                        dragFrame = null
                        latestScrubbing(false)
                    }
                }
            },
    ) {
        val areaW = maxWidth - LABEL_W
        val playheadPx = with(density) { (areaW / 2).toPx() }
        // Frame [f] on screen within a track, in px.
        fun at(f: Float): Int = px.screenX(f, current(), playheadPx).roundToInt()
        fun frameAt(x: Float): Float = px.frameAt(x, current(), playheadPx)
        val minPhotoFrames = scale.frames(THUMB.value)

        Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            Ruler(model, scale, px, playheadPx, ::current)

            TrackRow(StudioIcons.FoldedMap, "Map scenes", SCENES_H, onTap = { x -> onPick(TrackModel.hit(model.scenes, frameAt(x))?.pick) }) {
                model.scenes.forEach { b ->
                    SceneBlock(b, chosen, scale, Modifier.offset { IntOffset(at(b.from.toFloat()), 0) }, onPick)
                }
                model.closeUps.forEach { r ->
                    Box(
                        Modifier.offset { IntOffset(at(r.first.toFloat()), 0) }.align(Alignment.BottomStart)
                            .span(scale.width(r.first, r.last + 1).dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(TrackColors.closeUp.copy(alpha = 0.85f)),
                    )
                }
            }

            TrackRow(StudioIcons.Picture, "Photos", PHOTOS_H, onTap = { x -> TrackModel.hit(model.photos, frameAt(x), minPhotoFrames)?.let { onPick(it.pick) } }) {
                model.photos.forEach { b ->
                    PhotoBlock(b, chosen, scale, Modifier.offset { IntOffset(at(b.from.toFloat()), 0) }, onPick)
                }
            }

            TrackRow(StudioIcons.Words, "Texts", TEXT_H, onTap = { x -> TrackModel.hit(model.texts, frameAt(x))?.let { onPick(it.pick) } }) {
                model.texts.forEach { b ->
                    TextBlock(b, chosen, scale, Modifier.offset { IntOffset(at(b.from.toFloat()), 0) }, onPick)
                }
            }

            TrackRow(StudioIcons.Music, "Music", MUSIC_H, onTap = { onMusic() }) {
                MusicBar(model, music, scale, Modifier.offset { IntOffset(at(0f), 0) }, onMusic)
            }

            TrackRow(StudioIcons.Voice, "Voice", VOICE_H, onTap = { x -> TrackModel.hit(model.voices, frameAt(x))?.let { onPick(it.pick) } }) {
                model.voices.forEach { b ->
                    VoiceBlock(b, chosen, scale, Modifier.offset { IntOffset(at(b.from.toFloat()), 0) }, onPick)
                }
                if (recording != null) {
                    RecordingBlock(recording.second, scale, model.fps, Modifier.offset { IntOffset(at(recording.first.toFloat()), 0) })
                } else if (recordAt != null && enabled) {
                    RecordChip(Modifier.offset { IntOffset(at(recordAt.toFloat()), 0) }, onRecord)
                }
            }
        }

        // The playhead: still in the middle of the tracks, a knob on the ruler.
        val headX = LABEL_W + areaW / 2
        Box(Modifier.padding(top = 8.dp, bottom = 6.dp).offset(x = headX - 1.dp).width(2.dp).fillMaxHeight().background(S.ink, RoundedCornerShape(1.dp)))
        Box(Modifier.offset(x = headX - 6.dp, y = 2.dp).size(12.dp, 8.dp).background(S.ink, RoundedCornerShape(3.dp, 3.dp, 6.dp, 6.dp)))
    }
}

/**
 * [width] wide whatever the track's own width (a block runs on past the edge, clipped there), from its left edge: the
 * blocks' places come from their offsets alone.
 */
private fun Modifier.span(width: Dp): Modifier = wrapContentWidth(Alignment.Start, unbounded = true).width(width)

/** Scrubbing lands on prepared frames, so the picture follows the finger without drawing. */
private fun snapFrame(frame: Float, cache: PreviewCache?): Int {
    val f = frame.roundToInt()
    return cache?.let { it.clipFrame(PreviewMath.index(f, it.step, it.count)) } ?: f
}

/** One track: its icon in the left column, then its blocks, clipped, with taps at [onTap] (x in px within the track). */
@Composable
private fun TrackRow(icon: ImageVector, name: String, height: Dp, onTap: (Float) -> Unit, content: @Composable BoxScope.() -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(LABEL_W).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Icon(icon, name, Modifier.size(18.dp), tint = S.yellow)
        }
        Box(
            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                .pointerInput(Unit) { detectTapGestures { tap(it.x) } },
            content = content,
        )
    }
}

/** The ruler: a mark and its time every few seconds, moving with the tracks. */
@Composable
private fun Ruler(model: TrackModel, scale: TrackScale, px: TrackScale, playheadPx: Float, current: () -> Float) {
    val measurer = rememberTextMeasurer()
    val style = StudioText.label.copy(fontSize = 9.sp, letterSpacing = 0.sp)
    val ticks = remember(model.frames, model.fps, scale.tickSeconds) { scale.ticks(model.frames) }
    Row(Modifier.fillMaxWidth().height(RULER_H)) {
        Spacer(Modifier.width(LABEL_W))
        Canvas(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val now = current()
            for (s in ticks) {
                val x = px.screenX((s * model.fps).toFloat(), now, playheadPx)
                if (x < -60f || x > size.width + 60f) continue
                drawLine(S.edge, Offset(x, size.height - 4.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
                drawText(measurer, rulerLabel(s), Offset(x + 3.dp.toPx(), 0f), style)
            }
        }
    }
}

private fun Modifier.blockSemantics(b: TrackBlock, chosen: String?, what: String, onPick: (ScenePick?) -> Unit): Modifier = semantics {
    contentDescription = what
    role = Role.Tab
    selected = b.pick != null && b.pick.key == chosen
    onClick(label = "Choose") { onPick(b.pick); true }
}

/** A scene on the main track: a place (by day colour; "Day 14" over "El Chaltén · close-up"), a video, a day card, the title, the end. */
@Composable
private fun SceneBlock(b: TrackBlock, chosen: String?, scale: TrackScale, modifier: Modifier, onPick: (ScenePick?) -> Unit) {
    val on = b.pick != null && b.pick.key == chosen
    val color = when (b.kind) {
        BlockKind.Map -> TrackColors.days[b.tone.mod(TrackColors.days.size)]
        BlockKind.Video -> TrackColors.video
        BlockKind.DayCard -> TrackColors.dayCard
        else -> TrackColors.bookend
    }
    val width = (scale.width(b.from, b.to) - 2f).coerceAtLeast(1f).dp
    val what = listOfNotNull(b.title, b.sub).joinToString(", ")
    Box(
        modifier.span(width).fillMaxHeight().padding(vertical = 1.dp).clip(RoundedCornerShape(6.dp)).background(color)
            .then(if (on) Modifier.border(2.dp, S.ink, RoundedCornerShape(6.dp)) else Modifier)
            .blockSemantics(b, chosen, what, onPick)
            .padding(horizontal = 5.dp, vertical = 3.dp),
    ) {
        val icon = when (b.kind) {
            BlockKind.Video -> StudioIcons.Video
            BlockKind.DayCard -> StudioIcons.Card
            else -> null
        }
        if (width >= 14.dp) Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (icon != null) Icon(icon, null, Modifier.size(12.dp), tint = Color.White)
                Text(b.title, style = blockText.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Clip)
            }
            b.sub?.let { Text(it, style = blockText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private val blockText: TextStyle
    @Composable get() = StudioText.sub.copy(fontSize = 10.sp, lineHeight = 12.sp, color = Color.White)

/** A photo: its thumbnail where its card shows, a bar for as long as it shows; one left out is dimmed. */
@Composable
private fun PhotoBlock(b: TrackBlock, chosen: String?, scale: TrackScale, modifier: Modifier, onPick: (ScenePick?) -> Unit) {
    val on = b.pick != null && b.pick.key == chosen
    val bar = scale.width(b.from, b.to).dp
    Box(
        modifier.wrapContentWidth(Alignment.Start, unbounded = true).fillMaxHeight()
            .blockSemantics(b, chosen, if (b.dim) "Photo, not in the clip" else "Photo", onPick),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (bar > THUMB) {
            Box(Modifier.width(bar).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (b.kind == BlockKind.PhotoPause) S.edge else S.raised))
        }
        MediaImage(
            b.photoId,
            Modifier.size(THUMB).alpha(if (b.dim) 0.35f else 1f).clip(RoundedCornerShape(5.dp))
                .then(if (on) Modifier.border(2.dp, S.ink, RoundedCornerShape(5.dp)) else Modifier),
        )
    }
}

/** A text on the video: the title, a place name (dimmed when hidden), a day card's line, the closing line. */
@Composable
private fun TextBlock(b: TrackBlock, chosen: String?, scale: TrackScale, modifier: Modifier, onPick: (ScenePick?) -> Unit) {
    val on = b.pick != null && b.pick.key == chosen
    val width = (scale.width(b.from, b.to) - 2f).coerceAtLeast(1f).dp
    Box(
        modifier.span(width).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(TrackColors.text.copy(alpha = if (b.dim) 0.45f else 1f))
            .then(if (on) Modifier.border(2.dp, S.ink, RoundedCornerShape(5.dp)) else Modifier)
            .blockSemantics(b, chosen, if (b.dim) "${b.title}, hidden" else b.title, onPick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (width >= 14.dp) Text(b.title, style = blockText.copy(color = if (b.dim) S.muted else S.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The music under the whole clip: its name on a wave, a dot on each beat; "+ Music" when there is none. */
@Composable
private fun MusicBar(model: TrackModel, music: String?, scale: TrackScale, modifier: Modifier, onMusic: () -> Unit) {
    val width = scale.width(0, model.frames).coerceAtLeast(1f).dp
    val shape = RoundedCornerShape(6.dp)
    if (music == null) {
        Box(
            modifier.span(width).fillMaxHeight().padding(vertical = 2.dp).clip(shape)
                .clickable(role = Role.Button, onClickLabel = "Add music", onClick = onMusic),
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    S.muted, cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                )
            }
            Text("+ Music", Modifier.padding(start = 8.dp), style = blockText.copy(color = S.soft))
        }
        return
    }
    val beats = model.beats
    val perFrame = scale.dpPerFrame
    Box(
        modifier.span(width).fillMaxHeight().padding(vertical = 2.dp).clip(shape).background(TrackColors.music)
            .semantics { contentDescription = "Music: $music" },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val mid = size.height / 2
            val step = 6.dp.toPx()
            val path = Path()
            var x = 0f
            var i = 0
            path.moveTo(0f, mid)
            while (x < size.width) {
                // Waveform-ish: a steady wobble, not the track's real loudness.
                val a = (0.25f + 0.35f * abs(sin(i * 1.7f)) + 0.15f * abs(sin(i * 0.37f))) * size.height * 0.5f
                path.lineTo(x, if (i % 2 == 0) mid - a else mid + a)
                x += step
                i++
            }
            drawPath(path, TrackColors.wave, style = Stroke(1.5.dp.toPx()))
            val r = 2.dp.toPx()
            val y = size.height - 3.dp.toPx()
            for (b in beats) drawCircle(TrackColors.closeUp, r, Offset(b * perFrame * density, y))
        }
        Text(music, Modifier.padding(start = 8.dp), style = blockText.copy(fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/** A recorded voice line, as long as it plays. */
@Composable
private fun VoiceBlock(b: TrackBlock, chosen: String?, scale: TrackScale, modifier: Modifier, onPick: (ScenePick?) -> Unit) {
    val on = b.pick != null && b.pick.key == chosen
    val width = (scale.width(b.from, b.to) - 2f).coerceAtLeast(6f).dp
    Box(
        modifier.span(width).fillMaxHeight().padding(vertical = 1.dp).clip(RoundedCornerShape(5.dp)).background(TrackColors.voice)
            .then(if (on) Modifier.border(2.dp, S.ink, RoundedCornerShape(5.dp)) else Modifier)
            .blockSemantics(b, chosen, b.title, onPick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(b.title, style = blockText, maxLines = 1, overflow = TextOverflow.Clip) }
}

/** The line being recorded, growing as it goes. */
@Composable
private fun RecordingBlock(startedAt: Long, scale: TrackScale, fps: Int, modifier: Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(100)
        }
    }
    val ms = (now - startedAt).coerceAtLeast(0)
    val frames = (ms * fps / 1000).toInt()
    Box(
        modifier.span(scale.width(0, frames).coerceAtLeast(40f).dp).fillMaxHeight().padding(vertical = 1.dp)
            .clip(RoundedCornerShape(5.dp)).background(TrackColors.voice.copy(alpha = 0.75f)).padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text("● " + ClipText.clock(frames, fps), style = blockText, maxLines = 1) }
}

/** "+ Record": a voice line for the place at the playhead, from its start. */
@Composable
private fun RecordChip(modifier: Modifier, onRecord: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier.wrapContentWidth(Alignment.Start, unbounded = true).fillMaxHeight().padding(vertical = 1.dp).clip(shape)
            .clickable(role = Role.Button, onClickLabel = "Record a voice line", onClick = onRecord),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(
                S.muted, topLeft = Offset.Zero, size = Size(size.width, size.height), cornerRadius = CornerRadius(5.dp.toPx()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
            )
        }
        Text("+ Record", Modifier.padding(horizontal = 8.dp), style = blockText.copy(color = S.soft), maxLines = 1)
    }
}
