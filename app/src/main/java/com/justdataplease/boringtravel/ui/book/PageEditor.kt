package com.justdataplease.boringtravel.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.book.BookEdit
import com.justdataplease.boringtravel.book.BookHtml
import com.justdataplease.boringtravel.book.BookLayout
import com.justdataplease.boringtravel.book.BookPage
import com.justdataplease.boringtravel.book.FreeEdit
import com.justdataplease.boringtravel.book.FreeEl
import com.justdataplease.boringtravel.book.FreeKind
import com.justdataplease.boringtravel.book.FreeLayout
import com.justdataplease.boringtravel.book.Layers
import com.justdataplease.boringtravel.book.PageLayout
import com.justdataplease.boringtravel.book.PagePlan
import com.justdataplease.boringtravel.book.PageTransform
import com.justdataplease.boringtravel.book.PhotoEl
import com.justdataplease.boringtravel.book.Slot
import com.justdataplease.boringtravel.book.Template
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioIconButton
import com.justdataplease.boringtravel.ui.studio.StudioIcons
import com.justdataplease.boringtravel.ui.studio.StudioMainButton
import com.justdataplease.boringtravel.ui.studio.StudioRoundButton
import com.justdataplease.boringtravel.ui.studio.StudioText
import com.justdataplease.boringtravel.ui.studio.StudioTool
import com.justdataplease.boringtravel.ui.studio.StudioToolGroup
import com.justdataplease.boringtravel.ui.studio.StudioToolbar
import com.justdataplease.boringtravel.ui.studio.StudioTopBar
import com.justdataplease.boringtravel.ui.studio.groupLabel
import kotlin.math.min

/** Which of the Add buttons opened the Add panel. */
internal enum class AddGroup { All, Photo, Sticker, Map }

/** The panel open under the page; null shows the list of what is on the page. */
internal sealed interface Tool {
    // ---- the page (nothing selected)
    data object Layout : Tool
    data object Paper : Tool
    data object Look : Tool
    data object Page : Tool
    data object AddPage : Tool
    data object CopyLayout : Tool
    data class Add(val group: AddGroup) : Tool

    /** Several photos at once into the page's empty spots ([target] first: an empty spot that was tapped). */
    data class AddPhotos(val target: String? = null) : Tool

    /** New photo pages after this one: pick photos, then an album layout. */
    data object AlbumPages : Tool

    // ---- what is selected
    data object Replace : Tool
    data object Crop : Tool
    data object Frame : Tool
    data object Caption : Tool
    data object Rotate : Tool
    data object Edit : Tool
    data object Font : Tool
    data object Colour : Tool
    data object Size : Tool
    data object Align : Tool

    /** A decoration's look: shape, fill and outline; line colour and weight; tape and corner colours; a flag's country. */
    data object Style : Tool
}

/** A photo element as drawn, and where its photo lives: a template's own spot keeps it in the page's slots. */
internal data class PhotoSpot(val id: String, val el: PhotoEl, val slot: Slot, val templateSpot: Boolean)

/**
 * One page being edited: its elements (bottom first) and the editing steps the panels use. [edit] is one undo step;
 * [live] shows a change at once while a panel is open, the whole visit one undo step (ended when the panel closes).
 */
internal class PageEditing(
    val vm: BookViewModel,
    val state: BookViewModel.State,
    val layout: BookLayout,
    val page: BookPage,
    /** The page's printed number (templates mirror on left-hand pages). */
    val number: Int,
    val media: Map<String, Media>,
    val photos: List<Media>,
) {
    val pageId: String get() = page.id
    val pl: PageLayout get() = layout.pages
    val elements: List<FreeEl> by lazy { pl.free.elements(page, number) }
    val plan: PagePlan by lazy { pl.plan(page, number) }

    fun el(id: String?): FreeEl? = id?.let { elements.firstOrNull { e -> e.id == it } }

    fun edit(reflow: Boolean = false, op: (BookPage, PageLayout, Int) -> BookPage) {
        val n = number
        vm.edit(reflowPage = if (reflow) pageId else null) { d -> BookEdit.update(d, pageId) { p -> op(p, pl, n) } }
    }

    fun live(op: (BookPage, PageLayout, Int) -> BookPage) {
        val n = number
        vm.live { d -> BookEdit.update(d, pageId) { p -> op(p, pl, n) } }
    }

    fun photo(fe: FreeEl): PhotoSpot? {
        if (!fe.isPhoto) return null
        val templateSpot = !page.free && FreeLayout.isDerivedId(fe.id)
        val el = if (templateSpot) {
            plan.ids.indexOf(fe.id).takeIf { it >= 0 }?.let { plan.els[it] as? PhotoEl }
        } else {
            pl.free.toEl(fe, page) as? PhotoEl
        } ?: return null
        return PhotoSpot(fe.id, el, if (templateSpot) page.slot(el.slot) else fe.photo ?: Slot(), templateSpot)
    }

    /** A new photo, crop or caption for [spot]. */
    fun setSlot(spot: PhotoSpot, slot: Slot, live: Boolean = true) {
        val op: (BookPage, PageLayout, Int) -> BookPage =
            if (spot.templateSpot) { p, _, _ -> p.withSlot(spot.el.slot, slot) } else { p, lp, n -> FreeEdit.setPhoto(p, lp, n, spot.id, slot) }
        if (live) live(op) else edit(op = op)
    }

    /**
     * New words for text [el]: a title's or subtitle's are the page's own, a caption's its photo's, a text box's its
     * own (its box grows or shrinks with them).
     */
    fun setWords(el: FreeEl, words: String) = live { p, lp, n ->
        when {
            el.bind == FreeEl.TITLE -> p.copy(title = words)
            el.bind == FreeEl.SUBTITLE -> p.copy(subtitle = words)
            el.captionOf != null -> FreeEdit.change(p, lp, n, el.captionOf!!) { ph -> ph.copy(photo = (ph.photo ?: Slot()).copy(caption = words)) }
            else -> FreeEdit.change(p, lp, n, el.id) { cur ->
                val next = cur.copy(text = words)
                next.copy(box = next.box.copy(h = lp.free.contentHeight(next)))
            }
        }
    }

    /** [style]'s face, size, spacing, colour, alignment and emphasis on text [id]; its height follows its words. */
    fun restyle(id: String, style: FreeEl) = live { p, lp, n ->
        FreeEdit.change(p, lp, n, id) { cur ->
            val next = cur.copy(
                font = style.font, pt = style.pt, lh = style.lh, color = style.color, align = style.align,
                bold = style.bold, italic = style.italic, upper = style.upper, mark = style.mark,
            )
            next.copy(box = next.box.copy(h = lp.free.contentHeight(next.copy(text = lp.free.text(next, p)))))
        }
    }
}

/** The panel a second tap on [e] (on the page) opens: its photo, its words, its look. */
internal fun mainTool(e: FreeEl): Tool? = when (e.kind) {
    FreeKind.PHOTO -> if (e.photo?.mediaId == null) Tool.AddPhotos(e.id) else Tool.Crop
    FreeKind.TEXT, FreeKind.FLOW -> Tool.Edit
    FreeKind.SHAPE, FreeKind.LINE, FreeKind.TAPE, FreeKind.CORNERS, FreeKind.FLAGS -> Tool.Style
    else -> null
}

private data class ToolItem(val tool: Tool, val icon: ImageVector, val label: String)

/** The tools of [e]'s own kind (Arrange's tools come after them for every kind). */
private fun ownTools(e: FreeEl): List<ToolItem> = when (e.kind) {
    FreeKind.PHOTO -> {
        val has = e.photo?.mediaId != null
        buildList {
            add(if (has) ToolItem(Tool.Replace, StudioIcons.Replace, "Replace") else ToolItem(Tool.AddPhotos(e.id), BtIcons.Add, "Choose"))
            if (has) add(ToolItem(Tool.Crop, StudioIcons.Crop, "Crop"))
            if (e.style != FreeLayout.BLEED) add(ToolItem(Tool.Frame, StudioIcons.Frame, "Frame"))
            if (has) add(ToolItem(Tool.Caption, BtIcons.Note, "Caption"))
        }
    }
    FreeKind.TEXT -> listOf(
        ToolItem(Tool.Edit, BtIcons.Edit, "Edit"),
        ToolItem(Tool.Font, BtIcons.Title, "Font"),
        ToolItem(Tool.Colour, StudioIcons.Colour, "Colour"),
        ToolItem(Tool.Size, BtIcons.Format, "Size"),
        ToolItem(Tool.Align, alignIcon(e.align), "Align"),
    )
    FreeKind.FLOW -> listOf(ToolItem(Tool.Edit, BtIcons.Edit, "Edit"))
    FreeKind.SHAPE, FreeKind.LINE -> listOf(ToolItem(Tool.Style, BtIcons.Palette, "Style"))
    FreeKind.TAPE, FreeKind.CORNERS -> listOf(ToolItem(Tool.Style, StudioIcons.Colour, "Colour"))
    FreeKind.FLAGS -> listOf(ToolItem(Tool.Style, BtIcons.Flag, "Country"))
    else -> emptyList()
}

internal fun alignIcon(align: String): ImageVector = when (align) {
    "center" -> BtIcons.AlignCenter
    "right" -> BtIcons.AlignRight
    else -> BtIcons.AlignLeft
}

/**
 * One page in the studio: the top bar (close, page n of N, previous / next, PDF), the page large on the stage (tap to
 * select, drag to move, corners to resize, the round handle to turn; undo and redo in its corner), "On this page" or
 * the open tool's panel, and the tools for what is selected (the page's own and Add when nothing is).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PageEditor(
    graph: AppGraph,
    vm: BookViewModel,
    state: BookViewModel.State,
    layout: BookLayout,
    pageId: String,
    printed: List<BookPage>,
    media: Map<String, Media>,
    photos: List<Media>,
    selected: String?,
    tool: Tool?,
    onSelect: (String?) -> Unit,
    onTool: (Tool?) -> Unit,
    onOpen: (String?) -> Unit,
    onPdf: () -> Unit,
) {
    val doc = state.doc
    val index = doc.indexOf(pageId)
    val page = doc.pages.getOrNull(index) ?: return
    val n = printed.indexOfFirst { it.id == pageId }.let { if (it < 0) null else it + 1 }
    val num = remember(doc, layout, pageId) { layout.number(doc, pageId) }
    val ed = remember(state, layout, page, num, media, photos) { PageEditing(vm, state, layout, page, num, media, photos) }
    val pl = layout.pages
    val elements = ed.elements
    val sel = ed.el(selected)
    LaunchedEffect(sel, selected) { if (selected != null && sel == null) onSelect(null) }
    var live by remember(pageId) { mutableStateOf<LiveMove?>(null) }
    val routes = remember(layout, page.day) {
        listOfNotNull(
            AddKind.DayRoute.takeIf { layout.facts.day(page.day)?.route?.let { !it.isEmpty } == true },
            AddKind.TripRoute.takeIf { !layout.facts.regionRoute.isEmpty },
        )
    }

    fun select(id: String?) {
        onSelect(id)
        // An empty photo spot asks for its photos straight away (it gets the first one picked).
        val e = ed.el(id)
        if (e != null && e.isPhoto && e.photo?.mediaId == null && !e.hidden) onTool(Tool.AddPhotos(e.id))
    }

    fun add(kind: AddKind, arg: String?) {
        val id = vm.newElementId()
        val el = when (kind) {
            AddKind.Text -> FreeEdit.newText(pl, id)
            AddKind.Photo -> FreeEdit.newPhoto(pl, id, arg ?: return)
            AddKind.Tape -> FreeEdit.newTape(pl, id)
            AddKind.Corners -> FreeEdit.newCorners(pl, id)
            AddKind.Stamp -> FreeEdit.newStamp(pl, id)
            AddKind.Flag -> FreeEdit.newFlag(pl, id, arg ?: return)
            AddKind.DayRoute -> FreeEdit.newRoute(pl, id, FreeLayout.DAY)
            AddKind.TripRoute -> FreeEdit.newRoute(pl, id, FreeLayout.TRIP)
            AddKind.Box -> FreeEdit.newShape(pl, id, oval = false)
            AddKind.Circle -> FreeEdit.newShape(pl, id, oval = true)
            AddKind.Line -> FreeEdit.newLine(pl, id)
        }
        ed.edit { p, _, _ -> FreeEdit.add(p, el) }
        onSelect(id)
        // New words open straight away for typing.
        onTool(if (kind == AddKind.Text) Tool.Edit else null)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        val prev = doc.pages.getOrNull(index - 1)
        val next = doc.pages.getOrNull(index + 1)
        StudioTopBar(
            title = if (n != null) "Page $n of ${printed.size}" else "Hidden page",
            subtitle = listOfNotNull(layout.facts.trip.title.takeIf { it.isNotBlank() }, "Book").joinToString(" · "),
            onClose = { onOpen(null) },
            closeLabel = "All pages",
        ) {
            StudioRoundButton(BtIcons.Back, "Previous page", { prev?.let { onOpen(it.id) } }, enabled = prev != null)
            StudioRoundButton(BtIcons.Forward, "Next page", { next?.let { onOpen(it.id) } }, enabled = next != null)
            StudioMainButton("PDF", onPdf, enabled = !state.busy)
        }

        // The stage: the page as it prints, large, on near-black.
        Box(Modifier.weight(1.05f).fillMaxWidth().background(StudioColors.stage)) {
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    // A tap beside the page lets go of what is selected.
                    .pointerInput(Unit) { detectTapGestures { onSelect(null) } }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val sheetGeo = pl.sheet
                val pageWpx = sheetGeo.pageW * CSS_PX_PER_MM
                val pageHpx = sheetGeo.pageH * CSS_PX_PER_MM
                val zoom = min(maxWidth.value / pageWpx, maxHeight.value / pageHpx)
                val w = (pageWpx * zoom).toFloat().dp
                val h = (pageHpx * zoom).toFloat().dp
                val html = remember(page, doc, state.previews, layout, zoom, n) {
                    BookHtml.page(layout, doc, pageId, { id, _ -> state.previews[id] }, zoom)
                }
                val density = LocalDensity.current
                val wPx = with(density) { w.toPx() }.toDouble()
                val scale = wPx / sheetGeo.pageW // px per mm
                val tf = PageTransform(scale, sheetGeo.trim.x * scale, sheetGeo.trim.y * scale)
                Box(Modifier.size(w, h).shadow(10.dp, RectangleShape)) {
                    PagePreview(html, graph.books.photoDir, Color(android.graphics.Color.parseColor(pl.look.paperColor)), Modifier.fillMaxSize(), live)
                    PageCanvas(
                        elements = elements,
                        tf = tf,
                        pageW = layout.spec.format.widthMm,
                        pageH = layout.spec.format.heightMm,
                        margins = pl.grid.content,
                        selected = selected,
                        onSelect = ::select,
                        onEdit = { id -> ed.el(id)?.let(::mainTool)?.let(onTool) },
                        onLive = { live = it },
                        onCommit = { el, box, rot ->
                            live = null
                            ed.edit(reflow = el.isFlow) { p, lp, number -> FreeEdit.move(p, lp, number, el.id, box, rot) }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(2.dp)) {
                StudioIconButton(BtIcons.Undo, "Undo", vm::undo, enabled = state.canUndo)
                StudioIconButton(BtIcons.Redo, "Redo", vm::redo, enabled = state.canRedo)
            }
            // Photos, always at hand: they fill the page's empty spots first.
            AddPhotosButton(Modifier.align(Alignment.BottomStart).padding(8.dp), on = tool is Tool.AddPhotos) {
                onSelect(null)
                onTool(if (tool == Tool.AddPhotos()) null else Tool.AddPhotos())
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = StudioColors.yellow, trackColor = Color.Transparent)
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(StudioColors.bg)) {
            if (tool == null) {
                LayerList(ed, selected, onSelect = { id -> if (id == null) onSelect(null) else select(id) }, onPaper = { onSelect(null); onTool(Tool.Paper) }, onAdd = { onSelect(null); onTool(Tool.Add(AddGroup.All)) })
            } else {
                ToolPanel(tool, ed, sel, printed, routes, onTool = onTool, onSelect = onSelect, onOpen = onOpen, onAdd = ::add)
            }
        }

        // The keyboard needs the room while words are typed; the tools come back when it goes.
        if (!WindowInsets.isImeVisible) {
            EditorToolbar(ed, sel, tool, routes, onTool = onTool, onSelect = onSelect, onAdd = ::add)
        }
    }
}

/** The tool row: the page's tools and Add with nothing selected; what is selected's own tools and Arrange otherwise. */
@Composable
private fun EditorToolbar(
    ed: PageEditing,
    sel: FreeEl?,
    tool: Tool?,
    routes: List<AddKind>,
    onTool: (Tool?) -> Unit,
    onSelect: (String?) -> Unit,
    onAdd: (AddKind, String?) -> Unit,
) {
    fun toggle(t: Tool) = onTool(if (tool == t) null else t)
    StudioToolbar {
        if (sel == null) {
            val t = ed.page.template
            StudioToolGroup("Page") {
                if (t != Template.Cover && t != Template.BackCover) StudioTool(BtIcons.Layout, "Layout", { toggle(Tool.Layout) }, selected = tool == Tool.Layout)
                StudioTool(StudioIcons.Paper, "Paper", { toggle(Tool.Paper) }, selected = tool == Tool.Paper)
                StudioTool(BtIcons.Palette, "Look", { toggle(Tool.Look) }, selected = tool == Tool.Look)
                StudioTool(BtIcons.More, "Page", { toggle(Tool.Page) }, selected = tool == Tool.Page || tool == Tool.AddPage || tool == Tool.CopyLayout || tool == Tool.AlbumPages)
            }
            StudioToolGroup("Add", last = true) {
                StudioTool(BtIcons.Photo, "Photo", { toggle(Tool.Add(AddGroup.Photo)) }, selected = tool == Tool.Add(AddGroup.Photo))
                StudioTool(BtIcons.Title, "Text", { onAdd(AddKind.Text, null) })
                StudioTool(StudioIcons.Sticker, "Sticker", { toggle(Tool.Add(AddGroup.Sticker)) }, selected = tool == Tool.Add(AddGroup.Sticker))
                if (routes.isNotEmpty()) StudioTool(BtIcons.Map, "Map", {
                    if (routes.size == 1) onAdd(routes.single(), null) else toggle(Tool.Add(AddGroup.Map))
                }, selected = tool == Tool.Add(AddGroup.Map))
            }
        } else {
            val id = sel.id
            val label = groupLabel(Layers.kindWord(sel), Layers.name(sel, ed.layout.facts))
            val own = ownTools(sel)
            if (own.isNotEmpty()) StudioToolGroup(label, highlight = true) {
                own.forEach { item -> StudioTool(item.icon, item.label, { toggle(item.tool) }, selected = tool == item.tool) }
            }
            StudioToolGroup(if (own.isEmpty()) label else "Arrange", highlight = own.isEmpty(), last = true) {
                StudioTool(BtIcons.Rotate, "Rotate", { toggle(Tool.Rotate) }, selected = tool == Tool.Rotate)
                StudioTool(BtIcons.BringForward, "Forward", {
                    ed.edit { p, lp, number -> FreeEdit.order(p, lp, number, id, up = true) }
                }, enabled = FreeEdit.canOrder(ed.page, ed.pl, ed.number, id, up = true))
                StudioTool(BtIcons.SendBack, "Back", {
                    ed.edit { p, lp, number -> FreeEdit.order(p, lp, number, id, up = false) }
                }, enabled = FreeEdit.canOrder(ed.page, ed.pl, ed.number, id, up = false))
                if (FreeEdit.canRemove(sel)) {
                    StudioTool(BtIcons.Duplicate, "Copy", {
                        val copy = ed.vm.newElementId()
                        ed.edit { p, lp, number -> FreeEdit.duplicate(p, lp, number, id, copy) }
                        onSelect(copy)
                    })
                    StudioTool(BtIcons.Delete, "Delete", {
                        ed.edit { p, lp, number -> FreeEdit.delete(p, lp, number, id) }
                        onSelect(null)
                    }, danger = true)
                }
            }
        }
    }
}

/** The coral "+ Photos" on the stage's corner: several photos at once, into the page's empty spots first. */
@Composable
private fun AddPhotosButton(modifier: Modifier, on: Boolean, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 40.dp).shadow(6.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
            .background(if (on) StudioColors.yellow else StudioColors.coral)
            .semantics { selected = on }
            .clickable(role = Role.Button, onClickLabel = "Add photos", onClick = onClick)
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (on) StudioColors.onYellow else Color.White
        Icon(BtIcons.Add, null, Modifier.size(18.dp), tint = fg)
        Text("Photos", style = StudioText.button, color = fg, maxLines = 1)
    }
}
