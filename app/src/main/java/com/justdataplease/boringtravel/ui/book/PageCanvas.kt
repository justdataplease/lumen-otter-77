package com.justdataplease.boringtravel.ui.book

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.book.Box as MmBox
import com.justdataplease.boringtravel.book.FreeEl
import com.justdataplease.boringtravel.book.FreeKind
import com.justdataplease.boringtravel.book.FreeMath
import com.justdataplease.boringtravel.book.Guide
import com.justdataplease.boringtravel.book.P2
import com.justdataplease.boringtravel.book.PageTransform
import com.justdataplease.boringtravel.book.SnapLines
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.studio.StudioColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a drag on the page does. */
private sealed interface Drag {
    data class Move(val el: FreeEl) : Drag
    data class Resize(val el: FreeEl, val corner: Int) : Drag
    data class Turn(val el: FreeEl) : Drag
}

/** Photos keep their shape when resized from a corner. */
internal fun keepsAspect(e: FreeEl): Boolean = e.kind == FreeKind.PHOTO || e.kind == FreeKind.STAMP

/** A short name for an element, for TalkBack and the toolbar. */
internal fun elementLabel(e: FreeEl): String = when (e.kind) {
    FreeKind.PHOTO -> "Photo"
    FreeKind.TEXT -> when {
        e.bind == FreeEl.TITLE -> "Title"
        e.bind == FreeEl.SUBTITLE -> "Subtitle"
        e.captionOf != null -> "Caption"
        else -> "Text"
    }
    FreeKind.FLOW -> "Page text"
    FreeKind.STAMP -> "Stamp"
    FreeKind.FLAGS -> "Flags"
    FreeKind.ROUTE -> "Route"
    FreeKind.RULE, FreeKind.LINE -> "Line"
    FreeKind.LINES -> "Writing lines"
    FreeKind.TAPE -> "Washi tape"
    FreeKind.CORNERS -> "Photo corners"
    FreeKind.SHAPE -> if (e.style == "oval") "Circle" else "Box"
    FreeKind.SHADE -> "Shade"
    else -> "Element"
}

/**
 * The editing layer over a page: tap an element to select it (tap it again to change it), drag it to move it, drag a
 * corner to resize it, the round handle above it to turn it. Edges and centres snap to the page's centre, its margins
 * and the other elements, with guides; turns snap to eighths. [tf] maps screen pixels to page mm.
 *
 * While dragging, [onLive] shows the change on the page itself; [onCommit] sends the new box and turn when the finger
 * lifts. Nothing is changed until then.
 */
@Composable
internal fun PageCanvas(
    elements: List<FreeEl>,
    tf: PageTransform,
    pageW: Double,
    pageH: Double,
    margins: MmBox,
    selected: String?,
    onSelect: (String?) -> Unit,
    onEdit: (String) -> Unit,
    onLive: (LiveMove?) -> Unit,
    onCommit: (FreeEl, MmBox, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val handleR = with(density) { 22.dp.toPx() }.toDouble()
    val turnGap = with(density) { 30.dp.toPx() }.toDouble()
    val slopPx = with(density) { 10.dp.toPx() }.toDouble()
    val snapPx = with(density) { 7.dp.toPx() }.toDouble()
    var dragged by remember { mutableStateOf<FreeEl?>(null) }
    var turning by remember { mutableStateOf(false) }
    var guides by remember { mutableStateOf<List<Guide>>(emptyList()) }
    val els by rememberUpdatedState(elements)
    val sel by rememberUpdatedState(selected)
    val t by rememberUpdatedState(tf)
    val pw by rememberUpdatedState(pageW)
    val ph by rememberUpdatedState(pageH)
    val mg by rememberUpdatedState(margins)
    val select by rememberUpdatedState(onSelect)
    val edit by rememberUpdatedState(onEdit)
    val showLive by rememberUpdatedState(onLive)
    val commit by rememberUpdatedState(onCommit)

    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tfm = t
                val p0 = down.position
                val pm = tfm.toPage(p0.x.toDouble(), p0.y.toDouble())
                // A hidden element can be selected from the list but not moved: it is not on the page.
                val current = els.firstOrNull { it.id == sel && !it.hidden }
                val slop = tfm.mm(slopPx)
                fun near(a: P2, r: Double = handleR): Boolean = FreeMath.dist(tfm.toScreen(a), P2(p0.x.toDouble(), p0.y.toDouble())) <= r
                // Corners of a small element take less room, so its middle still moves it.
                val cornerR = current?.let { minOf(handleR, 0.35 * minOf(it.box.w, it.box.h) * tfm.scale).coerceAtLeast(handleR * 0.45) } ?: handleR
                val corner = current?.let { e -> FreeMath.corners(e.box, e.rot).indexOfFirst { near(it, cornerR) } } ?: -1
                val drag: Drag? = when {
                    current != null && near(FreeMath.rotateHandle(current.box, current.rot, tfm.mm(turnGap))) -> Drag.Turn(current)
                    current != null && corner >= 0 -> Drag.Resize(current, corner)
                    current != null && FreeMath.hit(current.box, current.rot, pm.x, pm.y, slop) -> Drag.Move(current)
                    else -> els.lastOrNull { !it.hidden && FreeMath.hit(it.box, it.rot, pm.x, pm.y, slop) }?.let { Drag.Move(it) }
                }
                val target = when (drag) { is Drag.Move -> drag.el; is Drag.Resize -> drag.el; is Drag.Turn -> drag.el; null -> null }
                // Lines to snap to: the page, its margins and everything else on it.
                val lines: SnapLines? = target?.let { e ->
                    FreeMath.snapLines(pw, ph, mg, els.filter { it.id != e.id && !it.hidden }.map { FreeMath.bounds(it.box, it.rot) })
                }
                val tol = tfm.mm(snapPx)
                var moved = false
                var result: FreeEl? = null
                var finished = false
                try { while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (moved || target != null) change.consume()
                        break
                    }
                    val pos = change.position
                    if (!moved && drag != null && (abs(pos.x - p0.x) > viewConfiguration.touchSlop || abs(pos.y - p0.y) > viewConfiguration.touchSlop)) {
                        moved = true
                        if (target != null && target.id != sel) select(target.id)
                        turning = drag is Drag.Turn
                    }
                    if (!moved || drag == null || target == null) continue
                    change.consume()
                    val p = tfm.toPage(pos.x.toDouble(), pos.y.toDouble())
                    val now: FreeEl = when (drag) {
                        is Drag.Move -> {
                            val box = target.box.offset(p.x - pm.x, p.y - pm.y)
                            val s = lines?.let { FreeMath.snap(FreeMath.bounds(box, target.rot), it, tol) }
                            guides = s?.guides.orEmpty()
                            target.copy(box = FreeMath.keepOnPage(box.offset(s?.dx ?: 0.0, s?.dy ?: 0.0), pw, ph))
                        }
                        is Drag.Resize -> {
                            var q = p
                            val g = ArrayList<Guide>()
                            if (target.rot == 0.0 && lines != null) {
                                lines.xs.minByOrNull { abs(it - p.x) }?.takeIf { abs(it - p.x) <= tol }?.let { q = q.copy(x = it); g += Guide(true, it) }
                                lines.ys.minByOrNull { abs(it - p.y) }?.takeIf { abs(it - p.y) <= tol }?.let { q = q.copy(y = it); g += Guide(false, it) }
                            }
                            guides = g
                            target.copy(box = FreeMath.resize(target.box, target.rot, drag.corner, q, keepsAspect(target), minSize(target)))
                        }
                        is Drag.Turn -> {
                            guides = emptyList()
                            target.copy(rot = FreeMath.snapAngle(FreeMath.angleTo(target.box, p)))
                        }
                    }
                    result = now
                    dragged = now
                    showLive(
                        LiveMove(
                            target.id, now.box.cx - target.box.cx, now.box.cy - target.box.cy, now.rot,
                            if (target.box.w > 0) now.box.w / target.box.w else 1.0, if (target.box.h > 0) now.box.h / target.box.h else 1.0,
                        ),
                    )
                }
                    finished = true
                } finally {
                    guides = emptyList()
                    dragged = null
                    turning = false
                    // A drag cut short (the gesture was taken away) puts the element back.
                    if (!finished) showLive(null)
                }
                val r = result
                if (moved && target != null && r != null) {
                    if (r.box != target.box || r.rot != target.rot) commit(target, r.box, r.rot) else showLive(null)
                } else if (!moved) {
                    // A tap: select what is on top there; tapping the selected element again opens its settings.
                    val hit = els.lastOrNull { !it.hidden && FreeMath.hit(it.box, it.rot, pm.x, pm.y, slop) }
                    when {
                        hit == null -> select(null)
                        hit.id == sel -> edit(hit.id)
                        else -> select(hit.id)
                    }
                }
            }
        },
    ) {
        val turnPainter = rememberVectorPainter(BtIcons.Rotate)
        // The studio's coral, whatever the app's theme.
        val accent = StudioColors.coral
        Canvas(Modifier.fillMaxSize()) {
            val halo = Color.White.copy(alpha = 0.85f)
            fun s(p: P2) = tf.toScreen(p).let { Offset(it.x.toFloat(), it.y.toFloat()) }
            // Boxes of words with nothing in them yet, so they can be found.
            elements.filter { it.kind == FreeKind.TEXT && !it.hidden && it.text.isBlank() && it.id != (dragged?.id ?: selected) }.forEach { e ->
                val path = Path().apply { FreeMath.corners(e.box, e.rot).map(::s).let { cs -> moveTo(cs[0].x, cs[0].y); cs.drop(1).forEach { lineTo(it.x, it.y) }; close() } }
                drawPath(path, accent.copy(alpha = 0.7f), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
            }
            guides.forEach { g ->
                val effect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                if (g.vertical) {
                    val x = tf.toScreen(g.at, 0.0).x.toFloat()
                    drawLine(accent, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx(), pathEffect = effect)
                } else {
                    val y = tf.toScreen(0.0, g.at).y.toFloat()
                    drawLine(accent, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = effect)
                }
            }
            val e = dragged ?: elements.firstOrNull { it.id == selected } ?: return@Canvas
            val cs = FreeMath.corners(e.box, e.rot).map(::s)
            val outline = Path().apply { moveTo(cs[0].x, cs[0].y); cs.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            if (e.hidden) {
                // Hidden: only where it would be, dashed, without handles.
                drawPath(outline, halo, style = Stroke(3.dp.toPx()))
                drawPath(outline, accent, style = Stroke(1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                return@Canvas
            }
            drawPath(outline, halo, style = Stroke(3.5.dp.toPx()))
            drawPath(outline, accent, style = Stroke(1.6.dp.toPx()))
            val top = s(FreeMath.rotate(P2(e.box.cx, e.box.y), e.rot, P2(e.box.cx, e.box.cy)))
            val turn = s(FreeMath.rotateHandle(e.box, e.rot, tf.mm(turnGap)))
            drawLine(halo, top, turn, 3.dp.toPx())
            drawLine(accent, top, turn, 1.4.dp.toPx())
            cs.forEach { p ->
                drawCircle(Color.White, 6.5.dp.toPx(), p)
                drawCircle(accent, 6.5.dp.toPx(), p, style = Stroke(1.8.dp.toPx()))
            }
            val r = 12.dp.toPx()
            drawCircle(Color.White, r, turn)
            drawCircle(accent, r, turn, style = Stroke(1.8.dp.toPx()))
            translate(turn.x - r * 0.7f, turn.y - r * 0.7f) {
                with(turnPainter) { draw(androidx.compose.ui.geometry.Size(r * 1.4f, r * 1.4f), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(accent)) }
            }
            // Size or turn while dragging.
            dragged?.let { d ->
                val label = if (turning) "${d.rot.roundToInt()}°" else "${d.box.w.roundToInt()} × ${d.box.h.roundToInt()} mm"
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                        textSize = 11.dp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    val bottom = cs.maxOf { it.y } + 18.dp.toPx()
                    val cx = cs.sumOf { it.x.toDouble() }.toFloat() / 4
                    val w = paint.measureText(label) + 14.dp.toPx()
                    val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(200, 30, 30, 34) }
                    drawRoundRect(cx - w / 2, bottom - 13.dp.toPx(), cx + w / 2, bottom + 5.dp.toPx(), 9.dp.toPx(), 9.dp.toPx(), bg)
                    drawText(label, cx, bottom, paint)
                }
            }
        }
        // TalkBack: each element on the page as a button (tap to select, again to change).
        elements.filter { !it.hidden }.forEach { e ->
            val b = FreeMath.bounds(e.box, e.rot)
            val p = tf.toScreen(b.x, b.y)
            with(density) {
                Box(
                    Modifier.offset(p.x.toFloat().toDp(), p.y.toFloat().toDp())
                        .size((b.w * tf.scale).toFloat().toDp(), (b.h * tf.scale).toFloat().toDp())
                        .semantics {
                            contentDescription = elementLabel(e) + if (e.id == selected) ", selected" else ""
                            onClick(label = if (e.id == selected) "Change" else "Select") {
                                if (e.id == selected) onEdit(e.id) else onSelect(e.id)
                                true
                            }
                        },
                )
            }
        }
    }
}

/** Smallest size of an element; a line can be thin. */
private fun minSize(e: FreeEl): Double = if (e.kind == FreeKind.LINE || e.kind == FreeKind.RULE) 1.0 else FreeMath.MIN_SIZE
