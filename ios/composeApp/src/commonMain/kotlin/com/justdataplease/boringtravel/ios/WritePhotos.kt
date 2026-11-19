package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.doc.Align
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.doc.PhotoPlacement
import com.justdataplease.boringtravel.doc.draggedPhotoPosition
import com.justdataplease.boringtravel.doc.horizontalPosition
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.btCardShape
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/*
 * A photo or video in the editor, as the Android app edits it (ui/editor/PhotoEditor.kt, PhotoDragState.kt): a tap
 * selects it, with a handle to turn it, one to move it between the notes or across the page, one to resize it, and its
 * controls (angle, size, frame, caption, alignment, move up or down, remove). A video keeps the layout tools and offers
 * Play instead of turning it.
 */

private val sizes = listOf("S" to 0.45f, "M" to 0.65f, "L" to 0.85f, "Full" to 1f)

private fun frameLabel(f: PhotoFrame) = when (f) {
    PhotoFrame.None -> "None"
    PhotoFrame.Polaroid -> "Polaroid"
    PhotoFrame.Tape -> "Tape"
    PhotoFrame.Rounded -> "Rounded"
}

/** "−4°" with a real minus sign, as on the canvas. */
internal fun degrees(v: Float): String {
    val d = v.roundToInt()
    return if (d < 0) "−${abs(d)}°" else "$d°"
}

@Composable
internal fun PhotoItem(model: WriteModel, item: EdItem.Photo, drag: PhotoDragState, onPlay: (String) -> Unit) {
    val c = Bt.colors
    val selected = model.selectedPhotoKey == item.key
    val b = item.block
    val media = LocalMediaRows.current(b.mediaId)
    val aspect = rememberAspect(media)
    val video = media?.isVideo == true
    val noun = if (video) "video" else "photo"
    val focus = LocalFocusManager.current
    var center by remember { mutableStateOf(Offset.Zero) }
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val placement = PhotoPlacement.of(b, aspect)
            val w = maxWidth * placement.width
            val envelopeWidth = maxWidth * placement.envelope
            val columnPx = constraints.maxWidth.toFloat()
            Box(
                Modifier.fillMaxWidth().padding(vertical = tiltRoom(w, b.rotation) + if (selected) 16.dp else 4.dp),
                contentAlignment = BiasAlignment(b.horizontalPosition() * 2f - 1f, 0f),
            ) {
                Box(Modifier.width(envelopeWidth), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(w)) {
                        FramedPhoto(
                            media = media, aspect = aspect, frame = b.frame, rotation = b.rotation, caption = b.caption,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { center = it.boundsInRoot().center }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Image,
                                    onClickLabel = if (selected) "Done with $noun" else "Edit $noun",
                                ) {
                                    focus.clearFocus()
                                    if (!selected) model.activeKey = item.key
                                    model.selectedPhotoKey = if (selected) null else item.key
                                },
                            outline = if (selected) c.route else null,
                        )
                        if (selected) {
                            if (!video) RotateHandle(
                                angle = b.rotation,
                                center = { center },
                                onAngle = { item.block = item.block.copy(rotation = it, position = item.block.horizontalPosition()) },
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                            MoveHandle(item, drag, columnPx, aspect, Modifier.align(Alignment.BottomStart).padding(6.dp))
                            ResizeHandle(item, columnPx, aspect, Modifier.align(Alignment.BottomEnd).padding(6.dp))
                        }
                    }
                }
            }
        }
        if (selected) PhotoControls(model, item, video, onPlay = { onPlay(b.mediaId) })
    }
}

@Composable
private fun MoveHandle(item: EdItem.Photo, drag: PhotoDragState, columnPx: Float, aspect: Float, modifier: Modifier) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val c = Bt.colors
    Row(
        modifier.onGloballyPositioned { coordinates = it }
            .clip(RoundedCornerShape(50)).background(c.surface.copy(alpha = 0.96f))
            .border(1.dp, c.line, RoundedCornerShape(50))
            .semantics { contentDescription = "Move photo" }
            .pointerInput(item.key, columnPx, aspect) {
                detectDragGestures(
                    onDragStart = { point -> coordinates?.localToRoot(point)?.let { drag.begin(item, it, columnPx, aspect) } },
                    onDragEnd = drag::finish,
                    onDragCancel = drag::cancel,
                ) { change, _ ->
                    change.consume()
                    coordinates?.localToRoot(change.position)?.let(drag::move)
                }
            }.height(48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(BtIcons.Drag, null, Modifier.size(20.dp), tint = c.ink)
        Text("Move", style = Bt.text.label, color = c.ink)
    }
}

@Composable
private fun ResizeHandle(item: EdItem.Photo, columnPx: Float, aspect: Float, modifier: Modifier) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val c = Bt.colors
    Box(
        modifier.onGloballyPositioned { coordinates = it }
            .size(48.dp).clip(RoundedCornerShape(50)).background(c.surface.copy(alpha = 0.96f))
            .border(1.dp, c.line, RoundedCornerShape(50))
            .semantics { contentDescription = "Resize photo" }
            .pointerInput(item.key, columnPx, aspect) {
                var startX = 0f
                var width = 1f
                detectDragGestures(onDragStart = { point ->
                    startX = coordinates?.localToRoot(point)?.x ?: 0f
                    width = PhotoPlacement.of(item.block, aspect).width
                }) { change, _ ->
                    change.consume()
                    val x = coordinates?.localToRoot(change.position)?.x ?: return@detectDragGestures
                    if (columnPx > 0) item.block = item.block.copy(width = (width + (x - startX) / columnPx).coerceIn(0.3f, 1f), position = item.block.horizontalPosition())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(BtIcons.Resize, null, Modifier.size(20.dp), tint = c.ink)
    }
}

/** The "⟳ −4°" pill: drag it around the photo to turn the photo. */
@Composable
private fun RotateHandle(angle: Float, center: () -> Offset, onAngle: (Float) -> Unit, modifier: Modifier = Modifier) {
    val c = Bt.colors
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val current by rememberUpdatedState(angle)
    val set by rememberUpdatedState(onAngle)
    Row(
        modifier
            .onGloballyPositioned { coords = it }
            .clip(RoundedCornerShape(50))
            .background(c.route)
            .pointerInput(Unit) {
                var startAngle = 0f
                var startPointer = 0f
                detectDragGestures(onDragStart = { pos ->
                    val p = coords?.localToRoot(pos) ?: return@detectDragGestures
                    startAngle = current
                    startPointer = pointerAngle(p, center())
                }) { change, _ ->
                    change.consume()
                    val p = coords?.localToRoot(change.position) ?: return@detectDragGestures
                    var delta = pointerAngle(p, center()) - startPointer
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    val next = (startAngle + delta).roundToInt().toFloat().coerceIn(-15f, 15f)
                    if (next != current) set(next)
                }
            }
            .height(48.dp)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(BtIcons.Rotate, "Turn photo", Modifier.size(14.dp), tint = c.onPrimary)
        Text(degrees(angle), style = Bt.text.mono.copy(fontWeight = FontWeight.SemiBold), color = c.onPrimary)
    }
}

private fun pointerAngle(p: Offset, center: Offset): Float = (atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble()) * 180.0 / PI).toFloat()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoControls(model: WriteModel, item: EdItem.Photo, video: Boolean, onPlay: () -> Unit) {
    val c = Bt.colors
    val b = item.block
    val noun = if (video) "video" else "photo"
    fun set(nb: Block.Photo) {
        item.block = nb.copy(position = nb.position ?: nb.horizontalPosition())
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 10.dp)
            .clip(btCardShape())
            .background(c.surface)
            .border(1.dp, c.line, btCardShape())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (video) BtGhostButton("Play", onPlay, Modifier.fillMaxWidth(), icon = BtIcons.Play)
        if (!video) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Angle", Modifier.width(54.dp), style = Bt.text.label, color = c.muted)
            Slider(
                value = b.rotation,
                onValueChange = { set(item.block.copy(rotation = it.roundToInt().toFloat())) },
                valueRange = -15f..15f,
                steps = 29,
                thumb = { Box(Modifier.size(16.dp).clip(CircleShape).background(c.route)) },
                track = { state ->
                    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                        val y = size.height / 2
                        drawLine(c.line, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                        drawLine(c.route, Offset(0f, y), Offset(size.width * ((state.value + 15f) / 30f), y), size.height, StrokeCap.Round)
                    }
                },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = c.route, activeTrackColor = c.route, inactiveTrackColor = c.line,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                ),
            )
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .size(58.dp, 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = "Straighten") { set(item.block.copy(rotation = 0f)) }
                    .semantics { contentDescription = "Straighten photo" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    degrees(b.rotation), Modifier.clip(RoundedCornerShape(50)).background(c.chip).padding(horizontal = 10.dp, vertical = 5.dp),
                    style = Bt.text.mono.copy(fontWeight = FontWeight.SemiBold), color = c.ink,
                )
            }
        }
        ControlRow("Size") {
            val nearest = sizes.minBy { abs(it.second - b.width) }.second
            sizes.forEach { (label, w) -> BtChip(label, nearest == w, { set(item.block.copy(width = w)) }) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Frame", style = Bt.text.label, color = c.muted)
            FrameChips(b.frame) { set(item.block.copy(frame = it)) }
        }
        CaptionField(b.caption) { set(item.block.copy(caption = it)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Align", Modifier.weight(1f), style = Bt.text.label, color = c.muted)
            AlignButton(BtIcons.AlignLeft, "Align left", b.horizontalPosition() < 0.02f) { set(item.block.copy(align = Align.Start, position = null)) }
            AlignButton(BtIcons.AlignCenter, "Centre", abs(b.horizontalPosition() - 0.5f) < 0.02f) { set(item.block.copy(align = Align.Center, position = null)) }
            AlignButton(BtIcons.AlignRight, "Align right", b.horizontalPosition() > 0.98f) { set(item.block.copy(align = Align.End, position = null)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Arrange", Modifier.weight(1f), style = Bt.text.label, color = c.muted)
            AlignButton(BtIcons.Up, "Move up", false) { model.move(item, up = true) }
            AlignButton(BtIcons.Down, "Move down", false) { model.move(item, up = false) }
            AlignButton(BtIcons.Delete, "Remove $noun", false, tint = c.danger) { model.remove(item) }
        }
        Text("Drag Move to place the $noun between notes or across the page. Use a smaller size for more room.", style = Bt.text.fine)
    }
}

/**
 * The frames of a photo, a video or a whole layout as chips ([frame] is the chosen one; null: none, a layout whose
 * photos differ): all four in a row where they fit, else two by two.
 */
@Composable
internal fun FrameChips(frame: PhotoFrame?, onPick: (PhotoFrame) -> Unit) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val chipWidth = PhotoFrame.entries.maxOf {
        measurer.measure(AnnotatedString(frameLabel(it)), style = Bt.text.label, maxLines = 1).size.width
    } + with(density) { 28.dp.roundToPx() } + 2
    val gap = with(density) { 6.dp.roundToPx() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = listOf(4, 2, 1).firstOrNull { count -> count * chipWidth + (count - 1) * gap <= constraints.maxWidth } ?: 1
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PhotoFrame.entries.chunked(columns).forEach { frames ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    frames.forEach { f -> BtChip(frameLabel(f), frame == f, { onPick(f) }, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ControlRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(54.dp), style = Bt.text.label, color = Bt.colors.muted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun CaptionField(value: String, onChange: (String) -> Unit) {
    val c = Bt.colors
    Box(Modifier.fillMaxWidth().clip(btControlShape()).background(c.chip).padding(horizontal = 14.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text("Caption", style = Bt.text.body, color = c.muted)
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.replace("\n", " ")) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = Bt.text.body.copy(color = c.ink),
            cursorBrush = SolidColor(c.route),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            maxLines = 3,
        )
    }
}

@Composable
private fun AlignButton(icon: ImageVector, label: String, on: Boolean, tint: Color? = null, onClick: () -> Unit) {
    val c = Bt.colors
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) c.chipOn else Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(20.dp), tint = if (on) c.onChipOn else tint ?: c.ink)
    }
}

/** Only the photo's Move handle starts this gesture; the rest of the page scrolls as usual (the Android app's PhotoDragState). */
internal class PhotoDragState(private val model: WriteModel, private val scroll: ScrollState) {
    val bounds = mutableMapOf<String, Rect>()
    var viewport = Rect.Zero
    var key by mutableStateOf<String?>(null)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set
    var beforeKey by mutableStateOf<String?>(null)
        private set
    var pointerY = 0f
        private set
    private var photo: EdItem.Photo? = null
    private var original: Block.Photo? = null
    private var start = Offset.Zero
    private var initialScroll = 0
    private var columnWidth = 0f
    private var envelope = 1f

    fun begin(item: EdItem.Photo, pointer: Offset, width: Float, aspect: Float) {
        photo = item
        original = item.block
        key = item.key
        start = pointer
        pointerY = pointer.y
        columnWidth = width
        envelope = PhotoPlacement.of(item.block, aspect).envelope
        initialScroll = scroll.value
        offsetY = 0f
        updateTarget()
    }

    fun move(pointer: Offset) {
        val item = photo ?: return
        val from = original ?: return
        pointerY = pointer.y
        item.block = item.block.copy(position = draggedPhotoPosition(from.horizontalPosition(), pointer.x - start.x, columnWidth, envelope))
        afterScroll()
    }

    fun afterScroll() {
        if (key == null) return
        offsetY = pointerY - start.y + scroll.value - initialScroll
        updateTarget()
    }

    private fun updateTarget() {
        beforeKey = model.items.firstOrNull { item -> item.key != key && bounds[item.key]?.let { pointerY < it.center.y } == true }?.key
    }

    fun finish() {
        val moving = key
        val target = beforeKey
        clear()
        if (moving != null) model.moveBefore(moving, target)
    }

    fun cancel() {
        original?.let { photo?.block = it }
        clear()
    }

    private fun clear() {
        key = null
        offsetY = 0f
        beforeKey = null
        photo = null
        original = null
    }
}
