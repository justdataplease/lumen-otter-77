package com.justdataplease.boringtravel.ui.book

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.justdataplease.boringtravel.book.AlbumEdit
import com.justdataplease.boringtravel.book.FreeEdit
import com.justdataplease.boringtravel.book.FreeEl
import com.justdataplease.boringtravel.book.FreeKind
import com.justdataplease.boringtravel.book.Layers
import com.justdataplease.boringtravel.book.PageBg
import com.justdataplease.boringtravel.book.fontRes
import com.justdataplease.boringtravel.ui.components.MediaImage
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioIcons
import com.justdataplease.boringtravel.ui.studio.StudioPillButton
import com.justdataplease.boringtravel.ui.studio.StudioText

private val S = StudioColors
private val ROW = 44.dp

private fun hex(s: String?): Color? = s?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

/**
 * "On this page · top first": one row per element, the background last. Drag a row by its handle to put it in front of
 * or behind others; tap it to select it on the page (again to let go); the eye hides or shows it (hidden ones stay
 * listed and are left out of the page and the PDF). The background row opens the page's Paper.
 */
@Composable
internal fun LayerList(
    ed: PageEditing,
    selected: String?,
    onSelect: (String?) -> Unit,
    onPaper: () -> Unit,
    onAdd: () -> Unit,
) {
    val rows = remember(ed.elements) { Layers.topFirst(ed.elements) }
    val count = rows.size
    val ids = remember(rows) { rows.map { it.id } }
    val byId = remember(rows) { rows.associateBy { it.id } }
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    // While a row is dragged, the order shown; it is applied when the finger lifts.
    var dragOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging != null) dragOrder else ids
    val idsNow by rememberUpdatedState(ids)
    val moveTo by rememberUpdatedState<(String, Int) -> Unit>({ id, row ->
        ed.edit { p, lp, n -> FreeEdit.moveLayer(p, lp, n, id, Layers.zOf(row, count)) }
    })

    // Something selected on the page comes into view in the list.
    val scroll = rememberScrollState()
    LaunchedEffect(selected, ids) {
        val i = ids.indexOf(selected)
        if (i < 0) return@LaunchedEffect
        val top = (i * rowPx).toInt()
        val bottom = top + rowPx.toInt()
        val view = scroll.viewportSize
        if (top < scroll.value) scroll.animateScrollTo(top) else if (view > 0 && bottom > scroll.value + view) scroll.animateScrollTo(bottom - view)
    }

    Column(
        Modifier.fillMaxSize().background(S.panel).drawBehind { drawLine(S.raised, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.padding(top = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ON THIS PAGE · TOP FIRST", Modifier.weight(1f), style = StudioText.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            StudioPillButton("+ Add", onAdd)
        }
        // Photos the layout has no spot for: kept with the page, not on it, until they get a page of their own.
        val extras = remember(ed.page) { AlbumEdit.extras(ed.page).size }
        if (extras > 0) Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(S.raised)
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${photosText(extras)} not on the page: no room in this layout", Modifier.weight(1f),
                style = StudioText.sub, color = S.warn, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            StudioPillButton("New page", { ed.vm.edit(reflowAll = true) { AlbumEdit.extrasToNewPages(it, ed.pageId, ed.vm::newElementId) } })
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
            shown.forEachIndexed { i, id ->
                val e = byId[id] ?: return@forEachIndexed
                val lifted = id == dragging
                // Keyed by the element: a row keeps its own node when the order changes, so the finger's drag goes on
                // (by position, the row under the finger became another element after the first swap and the drag
                // stopped there) and the rows it passes can slide to their new place.
                key(id) {
                val slide = remember { Animatable(0f) }
                var at by remember { mutableIntStateOf(i) }
                LaunchedEffect(i) {
                    val from = at
                    at = i
                    if (from != i && !lifted && dragging != null) {
                        slide.snapTo((from - i) * rowPx)
                        slide.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                    } else {
                        slide.snapTo(0f)
                    }
                }
                LayerRow(
                    ed = ed,
                    e = e,
                    on = id == selected,
                    modifier = Modifier.zIndex(if (lifted) 1f else 0f).graphicsLayer {
                        if (lifted) {
                            translationY = dy
                            shadowElevation = 8.dp.toPx()
                            scaleX = 1.02f
                            scaleY = 1.02f
                        } else {
                            translationY = slide.value
                        }
                    },
                    onClick = { onSelect(if (id == selected) null else id) },
                    handle = Modifier.pointerInput(id) {
                        detectDragGestures(
                            onDragStart = {
                                dragOrder = idsNow
                                dragging = id
                                dy = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dy += amount.y
                                val at = dragOrder.indexOf(id)
                                if (dy > rowPx / 2 && at in 0 until dragOrder.lastIndex) {
                                    dragOrder = Layers.move(dragOrder, at, at + 1)
                                    dy -= rowPx
                                } else if (dy < -rowPx / 2 && at > 0) {
                                    dragOrder = Layers.move(dragOrder, at, at - 1)
                                    dy += rowPx
                                }
                            },
                            onDragEnd = {
                                val to = dragOrder.indexOf(id)
                                val from = idsNow.indexOf(id)
                                dragging = null
                                dy = 0f
                                if (to >= 0 && from >= 0 && to != from) moveTo(id, to)
                            },
                            onDragCancel = {
                                dragging = null
                                dy = 0f
                            },
                        )
                    },
                    moveUp = if (i > 0) ({ moveTo(id, i - 1) }) else null,
                    moveDown = if (i < shown.lastIndex) ({ moveTo(id, i + 1) }) else null,
                )
                }
            }
            BackgroundRow(ed, onPaper)
        }
    }
}

@Composable
private fun LayerRow(
    ed: PageEditing,
    e: FreeEl,
    on: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    handle: Modifier,
    moveUp: (() -> Unit)?,
    moveDown: (() -> Unit)?,
) {
    val name = Layers.name(e, ed.layout.facts)
    val detail = Layers.detail(e) { ed.pl.free.face(it) }
    Row(
        modifier.fillMaxWidth().height(ROW).background(if (on) S.raised else S.panel)
            .drawBehind { if (on) drawRect(S.coral, size = Size(3.dp.toPx(), size.height)) }
            .semantics {
                selected = on
                contentDescription = "$name, $detail"
                customActions = listOfNotNull(
                    moveUp?.let { f -> CustomAccessibilityAction("Move to front") { f(); true } },
                    moveDown?.let { f -> CustomAccessibilityAction("Move back") { f(); true } },
                )
            }
            .clickable(role = Role.Button, onClickLabel = if (on) "Let go" else "Select", onClick = onClick)
            .padding(start = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(handle.width(34.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Icon(BtIcons.Drag, "Drag to reorder", Modifier.size(18.dp), tint = S.soft)
        }
        Box(Modifier.alpha(if (e.hidden) 0.45f else 1f)) { Thumb(ed, e) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).alpha(if (e.hidden) 0.55f else 1f)) {
            Text(name, style = StudioText.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = StudioText.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (FreeEdit.canHide(e)) {
            EyeButton(e.hidden) { hide -> ed.edit { p, lp, n -> FreeEdit.setHidden(p, lp, n, e.id, hide) } }
        } else {
            Spacer(Modifier.width(40.dp))
        }
    }
}

/** The background, always last: the theme's paper, a colour or a photo. Tap to change it; the eye hides it. */
@Composable
private fun BackgroundRow(ed: PageEditing, onClick: () -> Unit) {
    val bg = ed.page.bg
    val (name, detail) = Layers.background(bg, ed.plan.paper, ed.layout.facts)
    Row(
        Modifier.fillMaxWidth().height(ROW).clickable(role = Role.Button, onClickLabel = "Change the background", onClick = onClick).padding(start = 40.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(6.dp)
        Box(Modifier.alpha(if (bg?.hidden == true) 0.45f else 1f)) {
            when (bg?.kind) {
                PageBg.PHOTO -> PhotoThumb(ed, bg.photo?.mediaId)
                PageBg.COLOR -> Box(Modifier.size(30.dp).clip(shape).background(hex(bg.color) ?: S.raised).border(1.dp, S.edge, shape))
                else -> Box(Modifier.size(30.dp).clip(shape).background(hex(ed.pl.look.paperColor) ?: S.paper))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).alpha(if (bg?.hidden == true) 0.55f else 1f)) {
            Text(name, style = StudioText.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = StudioText.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (bg != null) EyeButton(bg.hidden) { hide -> ed.edit { p, _, _ -> FreeEdit.setBgHidden(p, hide) } } else Spacer(Modifier.width(40.dp))
    }
}

@Composable
private fun EyeButton(hidden: Boolean, onSet: (Boolean) -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClickLabel = if (hidden) "Show" else "Hide") { onSet(!hidden) }
            .semantics { contentDescription = if (hidden) "Hidden. Show it" else "Shown. Hide it" },
        contentAlignment = Alignment.Center,
    ) { Icon(if (hidden) StudioIcons.EyeOff else StudioIcons.Eye, null, Modifier.size(18.dp), tint = if (hidden) S.muted else S.soft) }
}

/** A row's small picture: the photo, "Aa" in the words' face, or a mark for the kind of thing. */
@Composable
private fun Thumb(ed: PageEditing, e: FreeEl) {
    val shape = RoundedCornerShape(6.dp)
    val box = Modifier.size(30.dp).clip(shape)
    when (e.kind) {
        FreeKind.PHOTO -> PhotoThumb(ed, e.photo?.mediaId)
        FreeKind.TEXT -> {
            val face = ed.pl.free.face(e.font)
            val family = remember(face) { FontFamily(Font(face.fontRes())) }
            Box(box.background(S.paper), contentAlignment = Alignment.Center) {
                Text("Aa", style = StudioText.chip.copy(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 13.sp), color = hex(e.color) ?: S.paperInk, maxLines = 1)
            }
        }
        FreeKind.FLOW -> Mark(box.background(S.paper), BtIcons.Text, S.paperInk)
        FreeKind.STAMP -> Mark(box.background(S.yellow), BtIcons.Stamp, S.onYellow)
        FreeKind.FLAGS -> Mark(box.background(S.yellow), BtIcons.Flag, S.onYellow)
        FreeKind.TAPE -> Mark(box.background(hex(e.color) ?: S.yellow), BtIcons.Tape, S.paperInk)
        FreeKind.CORNERS -> Mark(box.background(S.yellow), BtIcons.Corners, S.onYellow)
        FreeKind.ROUTE -> Mark(box.background(Color(0xFF13A89E)), BtIcons.Route, Color.White)
        FreeKind.SHAPE -> Mark(box.background(hex(e.fill) ?: S.raised).border(1.dp, S.edge, shape), BtIcons.Shapes, hex(e.color) ?: S.paperInk)
        FreeKind.SHADE -> Box(box.background(Brush.verticalGradient(listOf(S.soft, Color.Black))))
        FreeKind.LINE, FreeKind.RULE -> Mark(box.background(S.raised), BtIcons.Rule, hex(e.color) ?: S.ink)
        FreeKind.LINES -> Mark(box.background(S.paper), BtIcons.Rule, S.paperInk)
        else -> Mark(box.background(S.raised), BtIcons.More, S.ink)
    }
}

@Composable
private fun Mark(modifier: Modifier, icon: ImageVector, tint: Color) {
    Box(modifier, contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(18.dp), tint = tint) }
}

@Composable
private fun PhotoThumb(ed: PageEditing, mediaId: String?) {
    val m = mediaId?.let { ed.media[it] }
    val shape = RoundedCornerShape(6.dp)
    if (m != null) MediaImage(m, Modifier.size(30.dp).clip(shape), playBadge = false)
    else Mark(Modifier.size(30.dp).clip(shape).border(1.dp, S.edge, shape), BtIcons.Photo, S.muted)
}

/** Degrees for the panels: "−3°", "0°". */
internal fun degrees(rot: Double): String = Layers.angle(rot) ?: "0°"
