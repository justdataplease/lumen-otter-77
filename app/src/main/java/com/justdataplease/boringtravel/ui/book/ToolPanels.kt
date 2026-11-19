package com.justdataplease.boringtravel.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.book.AlbumEdit
import com.justdataplease.boringtravel.book.BookEdit
import com.justdataplease.boringtravel.book.BookPage
import com.justdataplease.boringtravel.book.Crop
import com.justdataplease.boringtravel.book.CropMath
import com.justdataplease.boringtravel.book.Face
import com.justdataplease.boringtravel.book.Flags
import com.justdataplease.boringtravel.book.FreeEdit
import com.justdataplease.boringtravel.book.FreeEl
import com.justdataplease.boringtravel.book.FreeKind
import com.justdataplease.boringtravel.book.FreeLayout
import com.justdataplease.boringtravel.book.PageBg
import com.justdataplease.boringtravel.book.PageLayout
import com.justdataplease.boringtravel.book.Slot
import com.justdataplease.boringtravel.book.Template
import com.justdataplease.boringtravel.book.fontRes
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.studio.StudioButton
import com.justdataplease.boringtravel.ui.studio.StudioChip
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioField
import com.justdataplease.boringtravel.ui.studio.StudioGrid
import com.justdataplease.boringtravel.ui.studio.StudioNote
import com.justdataplease.boringtravel.ui.studio.StudioPanel
import com.justdataplease.boringtravel.ui.studio.StudioSection
import com.justdataplease.boringtravel.ui.studio.StudioSegmented
import com.justdataplease.boringtravel.ui.studio.StudioSlider
import com.justdataplease.boringtravel.ui.studio.StudioIcons
import com.justdataplease.boringtravel.ui.studio.StudioSwatches
import com.justdataplease.boringtravel.ui.studio.StudioText
import kotlin.math.abs
import kotlin.math.roundToInt

private val S = StudioColors

/** What can be added to a page. */
internal enum class AddKind(val label: String, val icon: ImageVector) {
    Text("Text", BtIcons.Title),
    Photo("Photo", BtIcons.Photo),
    Tape("Washi tape", BtIcons.Tape),
    Corners("Photo corners", BtIcons.Corners),
    Stamp("Stamp", BtIcons.Stamp),
    Flag("Flag", BtIcons.Flag),
    DayRoute("Day's route", BtIcons.Route),
    TripRoute("Trip route", BtIcons.Map),
    Box("Box", BtIcons.Shapes),
    Circle("Circle", BtIcons.Shapes),
    Line("Line", BtIcons.Rule),
}

private val STICKERS = listOf(AddKind.Tape, AddKind.Corners, AddKind.Stamp, AddKind.Flag, AddKind.Box, AddKind.Circle, AddKind.Line)

/** Faces offered for words: handwriting, serif, sans, typewriter. */
internal val FACE_CHOICES = listOf(
    Face.Caveat, Face.Kalam, Face.Architects, Face.Gochi, Face.Literata, Face.SourceSerif, Face.Lora,
    Face.Albert, Face.WorkSans, Face.Barlow, Face.BarlowCondensed, Face.PlexMono, Face.CourierPrime, Face.JetBrains,
)

internal fun faceLabel(f: Face): String = when (f) {
    Face.Literata, Face.LiterataItalic -> "Literata"
    Face.Albert -> "Albert Sans"
    Face.Caveat -> "Caveat"
    Face.PlexMono -> "Plex Mono"
    Face.Kalam -> "Kalam"
    Face.SourceSerif -> "Source Serif"
    Face.CourierPrime -> "Courier"
    Face.Architects -> "Architects"
    Face.WorkSans -> "Work Sans"
    Face.JetBrains -> "JetBrains"
    Face.Gochi -> "Gochi Hand"
    Face.Lora -> "Lora"
    Face.Barlow -> "Barlow"
    Face.BarlowCondensed -> "Barlow Condensed"
}

/** Light and deep colours for backgrounds, then the theme's. */
internal fun backgroundColors(pl: PageLayout): List<String> =
    (listOf("#FFFFFF", "#F6F1E7", "#F4E6DC", "#E9F0E4", "#E4EDF4", "#F2E9F4", "#1E2A5A", "#22262A", "#2F4A3A") + FreeEdit.palette(pl)).distinct()

/**
 * The open tool's panel. Page tools work with nothing selected; the others on [sel] (closed when it goes). Changes
 * show on the page at once; a panel's visit is one undo step.
 */
@Composable
internal fun ToolPanel(
    tool: Tool,
    ed: PageEditing,
    sel: FreeEl?,
    printed: List<BookPage>,
    routes: List<AddKind>,
    onTool: (Tool?) -> Unit,
    onSelect: (String?) -> Unit,
    onOpen: (String?) -> Unit,
    onAdd: (AddKind, String?) -> Unit,
) {
    val done = { onTool(null) }
    when (tool) {
        Tool.Layout -> LayoutPanel(ed, done, onSelect)
        is Tool.AddPhotos -> AddPhotosPanel(ed, tool.target, onTool, onSelect, onOpen)
        Tool.AlbumPages -> AlbumPagesPanel(ed, onTool, onOpen)
        Tool.Paper -> PaperPanel(ed, done)
        Tool.Look -> StudioPanel("Book look", done) {
            LookContent(
                ed.state.spec, ed.state.doc.padTo4, ed.photos,
                onSpec = ed.vm::setSpec,
                onPad = { on -> ed.vm.edit { it.copy(padTo4 = on) } },
                onReset = {
                    onTool(null)
                    onOpen(null)
                    ed.vm.resetLayout()
                },
            )
        }
        Tool.Page -> PagePanel(ed, onTool, onSelect, onOpen)
        Tool.AddPage -> AddPagePanel(ed, onTool, onOpen)
        Tool.CopyLayout -> CopyLayoutPanel(ed, printed, onTool)
        is Tool.Add -> AddPanel(tool.group, ed, routes, onTool, onAdd)
        else -> {
            if (sel == null) {
                LaunchedEffect(Unit) { onTool(null) }
                return
            }
            when (tool) {
                Tool.Replace -> ed.photo(sel)?.let { ReplacePanel(ed, it, done) }
                Tool.Crop -> ed.photo(sel)?.let { CropPanel(ed, it, done) }
                Tool.Frame -> FramePanel(ed, sel, done)
                Tool.Caption -> ed.photo(sel)?.let { CaptionPanel(ed, it, done) }
                Tool.Rotate -> RotatePanel(ed, sel, done)
                Tool.Edit -> if (sel.isFlow) PageTextPanel(ed, done) else EditPanel(ed, sel, done)
                Tool.Font -> FontPanel(ed, sel, done)
                Tool.Colour -> TextStylePanel("Colour", ed, sel, done) { cur, push ->
                    StudioSwatches(FreeEdit.palette(ed.pl), cur.color, { push(cur.copy(color = it)) }, none = "Theme colour")
                }
                Tool.Size -> SizePanel(ed, sel, done)
                Tool.Align -> TextStylePanel("Align", ed, sel, done) { cur, push ->
                    StudioGrid(listOf("left" to "Left", "center" to "Centre", "right" to "Right"), 3) { (v, label) ->
                        StudioChip(label, cur.align == v, { push(cur.copy(align = v)) }, Modifier.fillMaxWidth(), icon = alignIcon(v))
                    }
                }
                Tool.Style -> DecoPanel(ed, sel, done)
            }
        }
    }
}

// ---------------------------------------------------------------- photos

@Composable
private fun ReplacePanel(ed: PageEditing, spot: PhotoSpot, onDone: () -> Unit) {
    val has = spot.slot.mediaId != null
    StudioPanel(if (has) "Replace" else "Choose a photo", onDone, scroll = false, header = {
        if (has) StudioChip("Remove", false, {
            ed.setSlot(spot, Slot(), live = false)
            onDone()
        })
    }) {
        PhotoPicker(ed.photos, spot.slot.mediaId, { m -> ed.setSlot(spot, spot.slot.copy(mediaId = m.id, crop = Crop())) }, Modifier.weight(1f))
    }
}

@Composable
private fun CropPanel(ed: PageEditing, spot: PhotoSpot, onDone: () -> Unit) {
    val m = ed.media[spot.slot.mediaId]
    StudioPanel("Crop", onDone, scroll = false, header = {
        if (!spot.slot.crop.isDefault) StudioChip("Reset", false, { ed.setSlot(spot, spot.slot.copy(crop = Crop())) })
    }) {
        if (m == null) {
            StudioNote("This photo isn't on the phone.")
            return@StudioPanel
        }
        val aspect = spot.el.image.aspect
        CropBox(m, aspect, spot.slot.crop, Modifier.weight(1f).fillMaxWidth()) { c -> ed.setSlot(spot, spot.slot.copy(crop = c)) }
        val dpi = CropMath.effectiveDpi(m.width, m.height, spot.el.image.w, spot.el.image.h, spot.slot.crop)
        if (dpi < 180) StudioNote("This photo may print soft at this size.", warn = true) else StudioNote("Drag to move it, pinch to zoom.")
    }
}

@Composable
private fun FramePanel(ed: PageEditing, fe: FreeEl, onDone: () -> Unit) {
    StudioPanel("Frame", onDone) {
        StudioGrid(listOf(FreeLayout.PRINT to "White border", FreeLayout.FLAT to "None"), 2) { (style, label) ->
            StudioChip(label, (fe.style == FreeLayout.PRINT) == (style == FreeLayout.PRINT), {
                ed.live { p, lp, n -> FreeEdit.change(p, lp, n, fe.id) { it.copy(style = style, pad = null, band = false) } }
            }, Modifier.fillMaxWidth())
        }
        StudioNote("A white border prints like a photo print.")
    }
}

@Composable
private fun CaptionPanel(ed: PageEditing, spot: PhotoSpot, onDone: () -> Unit) {
    var text by remember(spot.id) { mutableStateOf(spot.slot.caption) }
    StudioPanel("Caption", onDone) {
        StudioField(text, { v ->
            text = v.take(120)
            ed.setSlot(spot, spot.slot.copy(caption = text))
        }, "Caption", singleLine = true, focus = text.isEmpty())
        StudioNote("For the book only. The journal stays as it is.")
    }
}

// ---------------------------------------------------------------- arrange

@Composable
private fun RotatePanel(ed: PageEditing, fe: FreeEl, onDone: () -> Unit) {
    fun turnTo(rot: Double) = ed.edit(reflow = fe.isFlow) { p, lp, n -> FreeEdit.move(p, lp, n, fe.id, fe.box, rot) }
    fun nudge(dx: Double, dy: Double) = ed.edit(reflow = fe.isFlow) { p, lp, n -> FreeEdit.nudge(p, lp, n, fe.id, dx, dy) }
    StudioPanel("Rotate", onDone) {
        StudioSection("Turn", value = degrees(fe.rot)) {
            StudioGrid(listOf(-15, -5, 0, 5, 15), 5) { d ->
                StudioChip(
                    if (d == 0) "0°" else degrees(d.toDouble()),
                    d == 0 && fe.rot == 0.0,
                    { turnTo(if (d == 0) 0.0 else fe.rot + d) },
                    Modifier.fillMaxWidth(),
                )
            }
        }
        StudioSection("Nudge · ${FreeEdit.NUDGE} mm") {
            StudioGrid(
                listOf(
                    Triple(BtIcons.ArrowLeft, "Left", -FreeEdit.NUDGE to 0.0),
                    Triple(BtIcons.Up, "Up", 0.0 to -FreeEdit.NUDGE),
                    Triple(BtIcons.Down, "Down", 0.0 to FreeEdit.NUDGE),
                    Triple(BtIcons.ArrowRight, "Right", FreeEdit.NUDGE to 0.0),
                ),
                4,
            ) { (icon, label, d) -> StudioChip(label, false, { nudge(d.first, d.second) }, Modifier.fillMaxWidth(), icon = icon) }
        }
        StudioNote("Or drag the round handle above it on the page.")
    }
}

// ---------------------------------------------------------------- words

/** A text box's words (a title's and a caption's are the page's own). A new box closed empty goes away. */
@Composable
private fun EditPanel(ed: PageEditing, fe: FreeEl, onDone: () -> Unit) {
    var words by remember(fe.id) { mutableStateOf(ed.pl.free.text(fe, ed.page)) }
    val latest by rememberUpdatedState(ed)
    DisposableEffect(fe.id) {
        onDispose {
            // Closed with nothing in it: a new text box goes away.
            val e = latest.vm.state.value.doc.page(latest.pageId)?.el(fe.id)
            if (e != null && e.bind == null && !e.derived && e.text.isBlank()) {
                latest.vm.endLive()
                latest.edit { p, lp, n -> FreeEdit.delete(p, lp, n, fe.id) }
            }
        }
    }
    val title = when {
        fe.bind == FreeEl.TITLE -> "Title"
        fe.bind == FreeEl.SUBTITLE -> "Subtitle"
        fe.captionOf != null -> "Caption"
        else -> "Text"
    }
    StudioPanel(title, onDone) {
        StudioField(words, { v ->
            words = v.take(2000)
            ed.setWords(fe, words)
        }, "Your words", minHeight = 72.dp, focus = true)
        if (fe.bind != null) StudioNote("For the book only. The trip and journal stay as they are.")
    }
}

/**
 * A text element's look in one panel ([title]): [content] gets the look as it is now and a way to change it; each
 * change shows at once.
 */
@Composable
private fun TextStylePanel(
    title: String,
    ed: PageEditing,
    fe: FreeEl,
    onDone: () -> Unit,
    content: @Composable (FreeEl, (FreeEl) -> Unit) -> Unit,
) {
    var cur by remember(fe.id, title) { mutableStateOf(fe) }
    StudioPanel(title, onDone) {
        content(cur) { next ->
            cur = next
            ed.restyle(fe.id, next)
        }
    }
}

@Composable
private fun FontPanel(ed: PageEditing, fe: FreeEl, onDone: () -> Unit) {
    TextStylePanel("Font", ed, fe, onDone) { cur, push ->
        val fonts = ed.pl.fonts
        val choices = listOf(Triple("display", "Titles", fonts.display), Triple("body", "Text", fonts.body)) +
            FACE_CHOICES.map { Triple(it.name, faceLabel(it), it) }
        StudioSection("Font") {
            StudioGrid(choices, 2) { (token, label, face) ->
                val family = remember(face) { FontFamily(Font(face.fontRes())) }
                val on = cur.font == token || token == "body" && cur.font == null
                StudioChip(label, on, { push(cur.copy(font = token)) }, Modifier.fillMaxWidth(), fontFamily = family)
            }
        }
        StudioSection("Style") {
            StudioGrid(listOf("Bold", "Italic", "Capitals", "Highlight"), 2) { label ->
                val on = when (label) { "Bold" -> cur.bold; "Italic" -> cur.italic; "Capitals" -> cur.upper; else -> cur.mark }
                StudioChip(label, on, {
                    push(when (label) {
                        "Bold" -> cur.copy(bold = !cur.bold)
                        "Italic" -> cur.copy(italic = !cur.italic)
                        "Capitals" -> cur.copy(upper = !cur.upper)
                        else -> cur.copy(mark = !cur.mark)
                    })
                }, Modifier.fillMaxWidth(), icon = when (label) { "Bold" -> BtIcons.Bold; "Italic" -> BtIcons.Italic; else -> null })
            }
        }
    }
}

@Composable
private fun SizePanel(ed: PageEditing, fe: FreeEl, onDone: () -> Unit) {
    TextStylePanel("Size", ed, fe, onDone) { cur, push ->
        var size by remember(fe.id) { mutableFloatStateOf(cur.pt.toFloat().takeIf { it > 0 } ?: FreeLayout.DEFAULT_PT.toFloat()) }
        StudioSection("Size", value = "${size.roundToInt()} pt") {
            StudioSlider(size, { size = it }, 6f..72f, onDone = { push(cur.copy(pt = (size.toDouble() * 2).roundToInt() / 2.0)) })
        }
        StudioSection("Line spacing") {
            StudioGrid(listOf(1.0 to "Tight", 1.25 to "Normal", 1.5 to "Wide", 2.0 to "Double"), 4) { (v, label) ->
                StudioChip(label, abs(cur.lh - v) < 0.06, { push(cur.copy(lh = v)) }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** The page's own text, per entry. Kept when the panel closes; what doesn't fit moves to the next page. */
@Composable
private fun PageTextPanel(ed: PageEditing, onDone: () -> Unit) {
    val page = ed.page
    val parts = remember(page.id) {
        BookEdit.entriesOn(page).ifEmpty { listOf(null) }.map { e -> TextPart(e, BookEdit.titleOf(page, e), BookEdit.textOf(page, e)) }
    }
    var edited by remember(page.id) { mutableStateOf(parts) }
    val latest by rememberUpdatedState(edited)
    val vm = ed.vm
    val pageId = page.id
    DisposableEffect(pageId) {
        onDispose {
            val now = latest
            if (now != parts) vm.edit(reflowPage = pageId) { d -> now.fold(d) { acc, part -> BookEdit.setText(acc, pageId, part.entry, part.title, part.text) } }
        }
    }
    StudioPanel("Page text", onDone) {
        edited.forEachIndexed { i, part ->
            if (part.title != null) StudioField(part.title, { v -> edited = edited.toMutableList().also { it[i] = part.copy(title = v) } }, "Title", singleLine = true)
            StudioField(part.text, { v -> edited = edited.toMutableList().also { it[i] = part.copy(text = v) } }, "Text", minHeight = 140.dp)
        }
        StudioNote("For the book only; the journal stays as it is. Text that doesn't fit moves to the next page.")
    }
}

// ---------------------------------------------------------------- decorations

/** Shape, fill and outline; line colour and weight; tape and corner colours; a flag's country and look. */
@Composable
private fun DecoPanel(ed: PageEditing, fe: FreeEl, onDone: () -> Unit) {
    var cur by remember(fe.id) { mutableStateOf(fe) }
    fun push(e: FreeEl) {
        cur = e
        ed.live { p, lp, n -> FreeEdit.change(p, lp, n, fe.id) { now -> e.copy(box = now.box, rot = now.rot, hidden = now.hidden) } }
    }
    val palette = FreeEdit.palette(ed.pl)
    val title = when (fe.kind) {
        FreeKind.FLAGS -> "Country"
        FreeKind.TAPE, FreeKind.CORNERS -> "Colour"
        else -> "Style"
    }
    StudioPanel(title, onDone) {
        when (fe.kind) {
            FreeKind.SHAPE -> {
                StudioGrid(listOf("rect" to "Box", "oval" to "Circle"), 2) { (v, label) ->
                    StudioChip(label, (cur.style == "oval") == (v == "oval"), { push(cur.copy(style = v)) }, Modifier.fillMaxWidth())
                }
                StudioSection("Fill") { StudioSwatches(palette, cur.fill, { push(cur.copy(fill = it)) }, none = "No fill") }
                StudioSection("Outline") {
                    StudioSwatches(palette, cur.color.takeIf { cur.stroke > 0 }, { v ->
                        push(if (v == null) cur.copy(stroke = 0.0) else cur.copy(color = v, stroke = cur.stroke.takeIf { it > 0 } ?: 0.6))
                    }, none = "No outline")
                    if (cur.stroke > 0) Weights(cur.stroke) { push(cur.copy(stroke = it)) }
                }
            }
            FreeKind.LINE -> {
                StudioSection("Colour") { StudioSwatches(palette, cur.color, { push(cur.copy(color = it ?: ed.pl.look.ink)) }) }
                StudioSection("Weight") { Weights(cur.stroke) { push(cur.copy(stroke = it)) } }
            }
            FreeKind.TAPE -> StudioSwatches(FreeEdit.tapeColors(ed.pl), cur.color, { push(cur.copy(color = it)) })
            FreeKind.CORNERS -> StudioSwatches(listOf("#2B2B2B", "#FFFFFF", "#8B6B4A", "#C4423A", "#1E2A5A"), cur.color, { push(cur.copy(color = it)) })
            FreeKind.FLAGS -> {
                var code by remember(fe.id) { mutableStateOf(cur.codes.firstOrNull().orEmpty()) }
                val countries = ed.layout.facts.countries
                if (countries.isNotEmpty()) StudioGrid(countries, 4) { cc ->
                    StudioChip(cc, cur.codes == listOf(cc), { code = cc; push(cur.copy(codes = listOf(cc))) }, Modifier.fillMaxWidth())
                }
                StudioField(code, { v ->
                    code = v.filter { it.isLetter() }.take(2).uppercase()
                    if (code.length == 2) push(cur.copy(codes = listOf(code)))
                }, "Country code, like GR", singleLine = true)
                if (code.length == 2 && !Flags.has(code)) StudioNote("No flag drawn for this one yet; its letters print instead.")
                StudioGrid(listOf(FreeLayout.STICKER to "Sticker", FreeLayout.FLAT to "Flat"), 2) { (v, label) ->
                    StudioChip(label, (cur.style == FreeLayout.STICKER) == (v == FreeLayout.STICKER), { push(cur.copy(style = v)) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Weights(value: Double, onPick: (Double) -> Unit) {
    StudioGrid(listOf(0.3 to "Thin", 0.6 to "Medium", 1.2 to "Thick"), 3) { (v, label) ->
        StudioChip(label, abs(value - v) < 0.05, { onPick(v) }, Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- the page

/**
 * The page's layout: album layouts by how many photos they hold, then the book's other layouts. Photos keep their
 * order; those a smaller layout has no spot for stay with the page (unseen) until "Put the rest on a new page".
 */
@Composable
private fun LayoutPanel(ed: PageEditing, onDone: () -> Unit, onSelect: (String?) -> Unit) {
    val page = ed.page
    val extras = remember(page) { AlbumEdit.extras(page) }
    StudioPanel("Layout", onDone) {
        if (extras.isNotEmpty()) {
            StudioNote("${photosText(extras.size)} more than this layout has room for. They stay with the page until you pick a bigger layout or move them.", warn = true)
            StudioButton("Put the rest on a new page", {
                ed.vm.edit(reflowAll = true) { AlbumEdit.extrasToNewPages(it, page.id, ed.vm::newElementId) }
            }, Modifier.fillMaxWidth(), icon = BtIcons.Add)
        }
        AlbumGroups(ed.layout, page.albumLayout?.id?.takeIf { !page.free }, AlbumEdit.photoCount(page)) { a ->
            onSelect(null)
            ed.vm.edit(reflowAll = true) { AlbumEdit.apply(it, page.id, a.id) }
        }
        StudioSection("More layouts") {
            TemplateGrid(
                page.template.takeIf { !page.free && it != Template.Album }, ed.layout, ed.media, key = page,
                preview = { t -> ed.pl.plan(FreeEdit.applyTemplate(page, t), ed.number) },
                onPick = { t ->
                    onSelect(null)
                    ed.vm.edit(reflowAll = true) { BookEdit.template(it, page.id, t) }
                },
            )
        }
    }
}

/**
 * Several photos at once: picked in order, they fill the page's empty spots ([target] first). Those that find no spot:
 * a bigger album layout on this page (a page of photos), placed by hand on it (other pages), or new pages after it.
 */
@Composable
private fun AddPhotosPanel(ed: PageEditing, target: String?, onTool: (Tool?) -> Unit, onSelect: (String?) -> Unit, onOpen: (String?) -> Unit) {
    var picked by remember(ed.pageId, target) { mutableStateOf(emptyList<String>()) }
    var onlyNew by remember(ed.pageId) { mutableStateOf(false) }
    var over by remember(ed.pageId, target) { mutableStateOf<AlbumEdit.Filled?>(null) }
    val used = remember(ed.state.doc) { ed.state.doc.pages.flatMapTo(HashSet()) { it.mediaIds } }
    val facts = ed.layout.facts
    val has: (String?) -> Boolean = { facts.media(it) != null }
    fun close() {
        onSelect(null)
        onTool(null)
    }
    val f = over
    if (f == null) {
        StudioPanel(if (target != null) "Choose photos" else "Add photos", { onTool(null) }, scroll = false, header = {
            StudioChip("Not in book", onlyNew, { onlyNew = !onlyNew })
        }) {
            MultiPhotoPicker(ed.photos, picked, used, { id -> picked = if (id in picked) picked - id else picked + id }, Modifier.weight(1f), onlyNew)
            StudioButton(if (picked.isEmpty()) "Pick photos" else "Add ${photosText(picked.size)}", {
                val filled = AlbumEdit.fill(ed.page, ed.pl, ed.number, picked, target, has)
                if (filled.rest.isEmpty()) {
                    ed.vm.edit { d -> BookEdit.update(d, ed.pageId) { filled.page } }
                    close()
                } else {
                    over = filled
                }
            }, Modifier.fillMaxWidth(), icon = BtIcons.Add, primary = true, enabled = picked.isNotEmpty())
        }
        return
    }
    val page = ed.page
    val placed = picked.size - f.rest.size
    val grown = remember(f) { AlbumEdit.grown(f.page, f.rest.size) }
    StudioPanel("No room for ${photosText(f.rest.size)}", { onTool(null) }, header = {
        StudioChip("Back", false, { over = null })
    }) {
        val left = if (f.rest.size == 1) "One doesn't fit on this page." else "${f.rest.size} don't fit on this page."
        StudioNote((if (placed > 0) "${photosText(placed)} went into the empty spots. " else "") + left)
        if (grown != null) {
            StudioSection("On this page") {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(S.raised)
                        .clickable(role = Role.Button, onClickLabel = "Use a bigger layout") {
                            ed.vm.edit(reflowAll = true) { d -> BookEdit.update(d, ed.pageId) { AlbumEdit.grow(f.page, f.rest) ?: f.page } }
                            close()
                        }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AlbumThumb(ed.layout, grown, Int.MAX_VALUE, Modifier.width(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text("A bigger layout", style = StudioText.item)
                        Text("${grown.name} · ${photosText(grown.count)}", style = StudioText.sub)
                    }
                }
            }
        } else if (!AlbumEdit.canGrow(page)) {
            StudioButton("Place them on this page", {
                ed.vm.edit { d -> BookEdit.update(d, ed.pageId) { AlbumEdit.byHand(f.page, ed.pl, f.rest, ed.vm::newElementId) } }
                close()
            }, Modifier.fillMaxWidth(), icon = BtIcons.Photo)
        }
        StudioNote("Or start new pages after this one, in a layout:")
        AlbumGroups(ed.layout, null, Int.MAX_VALUE) { a ->
            val added = AlbumEdit.pages(f.rest.map { Slot(it) }, a, page.day, null, ed.vm::newElementId)
            ed.vm.edit(reflowAll = true) { d -> AlbumEdit.insert(BookEdit.update(d, ed.pageId) { f.page }, ed.pageId, added) }
            close()
            added.firstOrNull()?.let { onOpen(it.id) }
        }
    }
}

/** "Add photo pages": pick photos, then an album layout; the pages go after this one, as many as the photos need. */
@Composable
private fun AlbumPagesPanel(ed: PageEditing, onTool: (Tool?) -> Unit, onOpen: (String?) -> Unit) {
    var picked by remember(ed.pageId) { mutableStateOf(emptyList<String>()) }
    var choosing by remember(ed.pageId) { mutableStateOf(false) }
    var onlyNew by remember(ed.pageId) { mutableStateOf(true) }
    val used = remember(ed.state.doc) { ed.state.doc.pages.flatMapTo(HashSet()) { it.mediaIds } }
    StudioPanel("Add photo pages", { onTool(Tool.Page) }, scroll = choosing, header = {
        if (choosing) StudioChip("Back", false, { choosing = false }) else StudioChip("Not in book", onlyNew, { onlyNew = !onlyNew })
    }) {
        AlbumPagesPicker(
            ed.layout, ed.photos, used, null, picked, { picked = it }, choosing, { choosing = it }, onlyNew,
        ) { ids, a ->
            val added = AlbumEdit.pages(ids.map { Slot(it) }, a, ed.page.day, null, ed.vm::newElementId)
            ed.vm.edit(reflowAll = true) { d -> AlbumEdit.insert(d, ed.pageId, added) }
            onTool(null)
            added.firstOrNull()?.let { onOpen(it.id) }
        }
    }
}

/** The page's background: the theme's paper, a colour, or a photo to the edges with a dim. Shown at once. */
@Composable
private fun PaperPanel(ed: PageEditing, onDone: () -> Unit) {
    val bg = ed.page.bg
    var tab by remember { mutableIntStateOf(when (bg?.kind) { PageBg.COLOR -> 1; PageBg.PHOTO -> 2; else -> 0 }) }
    var dim by remember { mutableFloatStateOf((bg?.dim ?: 0.0).toFloat()) }
    fun push(b: PageBg?) = ed.live { p, _, _ -> FreeEdit.setBg(p, b) }
    StudioPanel("Paper", onDone, scroll = tab != 2) {
        StudioSegmented(listOf("Theme's", "Colour", "Photo"), tab, onSelect = { i ->
            tab = i
            if (i == 0) push(null)
        })
        when (tab) {
            0 -> StudioNote("The theme's own paper.")
            1 -> StudioSwatches(backgroundColors(ed.pl), bg?.takeIf { it.kind == PageBg.COLOR }?.color, { v -> push(v?.let { PageBg(PageBg.COLOR, color = it) }) })
            else -> {
                val chosen = bg?.takeIf { it.kind == PageBg.PHOTO }
                if (chosen != null) StudioSection("Dim", value = "${(dim * 100).roundToInt()} %") {
                    StudioSlider(dim, { dim = it }, 0f..PageBg.MAX_DIM.toFloat(), onDone = { push(chosen.copy(dim = dim.toDouble())) })
                }
                PhotoPicker(ed.photos, chosen?.photo?.mediaId, { m -> push(PageBg(PageBg.PHOTO, photo = Slot(m.id), dim = dim.toDouble())) }, Modifier.weight(1f))
            }
        }
        if (bg?.hidden == true) StudioNote("Hidden: the theme's paper shows. Show it again from the list.")
    }
}

/** What can be done to the whole page. */
@Composable
private fun PagePanel(ed: PageEditing, onTool: (Tool?) -> Unit, onSelect: (String?) -> Unit, onOpen: (String?) -> Unit) {
    val page = ed.page
    val doc = ed.state.doc
    val vm = ed.vm
    val index = doc.indexOf(page.id)
    val t = page.template
    val cover = t == Template.Cover || t == Template.BackCover
    val entry = page.entryId ?: page.paras.firstOrNull { it.entry != null }?.entry
    var confirm by remember { mutableStateOf(false) }
    StudioPanel("Page", { onTool(null) }) {
        if (!cover) StudioButton("Duplicate page", {
            onTool(null)
            vm.edit(reflowAll = true) { FreeEdit.duplicatePage(it, page.id, vm::newElementId) }
            vm.state.value.doc.pages.getOrNull(index + 1)?.let { onOpen(it.id) }
        }, Modifier.fillMaxWidth(), icon = BtIcons.Duplicate)
        if (!cover) StudioButton("Use this layout on other pages", { onTool(Tool.CopyLayout) }, Modifier.fillMaxWidth(), icon = BtIcons.CopyLayout)
        StudioButton("Add a page after", { onTool(Tool.AddPage) }, Modifier.fillMaxWidth(), icon = BtIcons.Add)
        StudioButton("Add photo pages", { onTool(Tool.AlbumPages) }, Modifier.fillMaxWidth(), icon = BtIcons.Photo)
        if (page.free) StudioButton("Back to the layout", {
            onTool(null)
            onSelect(null)
            vm.edit(reflowAll = true) { BookEdit.template(it, page.id, page.template ?: Template.Notes, page.album) }
        }, Modifier.fillMaxWidth(), icon = BtIcons.Layout)
        if (entry != null) {
            val hidden = entry in doc.hidden
            StudioButton(if (hidden) "Show entry" else "Hide entry", {
                onTool(null)
                vm.edit(reflowAll = true) { BookEdit.hideEntry(it, entry, !hidden) }
            }, Modifier.fillMaxWidth(), icon = if (hidden) StudioIcons.Eye else StudioIcons.EyeOff)
        }
        if (t != Template.Cover) {
            if (!confirm) {
                StudioButton("Delete page", { confirm = true }, Modifier.fillMaxWidth(), icon = BtIcons.Delete, danger = true)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioButton("Keep", { confirm = false }, Modifier.weight(1f))
                    StudioButton("Delete", {
                        onTool(null)
                        val nextId = doc.pages.getOrNull(index + 1)?.id ?: doc.pages.getOrNull(index - 1)?.id
                        vm.edit(reflowAll = true) { BookEdit.delete(it, page.id) }
                        onOpen(nextId)
                    }, Modifier.weight(1f), icon = BtIcons.Delete, primary = true, danger = true)
                }
            }
        }
        if (page.free) StudioNote("Back to the layout puts its photos and text back in place; what you added stays.")
    }
}

@Composable
private fun AddPagePanel(ed: PageEditing, onTool: (Tool?) -> Unit, onOpen: (String?) -> Unit) {
    val doc = ed.state.doc
    val index = doc.indexOf(ed.pageId)
    val day = ed.page.day
    StudioPanel("Add a page after", { onTool(Tool.Page) }) {
        TemplateGrid(
            null, ed.layout, ed.media, key = day,
            preview = { t -> ed.pl.plan(BookEdit.blank(t, "preview", day), index + 2) },
            onPick = { t ->
                val page = ed.vm.newPage(t, day)
                onTool(null)
                ed.vm.edit(reflowAll = true) { BookEdit.insert(it, index + 1, page) }
                onOpen(page.id)
            },
        )
    }
}

/** Pick pages to give this page's layout. Pages with the same layout are picked to start with. */
@Composable
private fun CopyLayoutPanel(ed: PageEditing, printed: List<BookPage>, onTool: (Tool?) -> Unit) {
    val source = ed.page
    val doc = ed.state.doc
    val layout = ed.layout
    val numbers = remember(printed) { printed.mapIndexed { i, p -> p.id to i + 1 }.toMap() }
    val choices = remember(doc.pages, source.id) {
        doc.pages.filter { it.id != source.id && it.template != Template.Cover && it.template != Template.BackCover }
    }
    val same: (BookPage) -> Boolean = { it.templateId == source.templateId && it.album == source.album }
    var picked by remember(source.id) { mutableStateOf(choices.filter(same).map { it.id }.toSet()) }
    StudioPanel("Use this layout on", { onTool(Tool.Page) }) {
        StudioGrid(listOf("Same layout", "All", "None"), 3) { label ->
            StudioChip(label, false, {
                picked = when (label) {
                    "All" -> choices.map { it.id }.toSet()
                    "None" -> emptySet()
                    else -> choices.filter(same).map { it.id }.toSet()
                }
            }, Modifier.fillMaxWidth())
        }
        StudioGrid(choices, 4, spacing = 10.dp) { p ->
            val on = p.id in picked
            val n = numbers[p.id]
            Column(
                Modifier.fillMaxWidth().semantics { selected = on }
                    .clickable(role = Role.Checkbox, onClickLabel = "Page ${n ?: ""}") { picked = if (on) picked - p.id else picked + p.id },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val plan = remember(p, layout, n) { layout.pages.plan(p, n ?: 1) }
                PageThumb(plan, layout, ed.media, Modifier.fillMaxWidth().border(if (on) 3.dp else 0.dp, if (on) S.yellow else Color.Transparent), dim = !on)
                Text(n?.toString() ?: "Hidden", Modifier.padding(top = 3.dp), style = StudioText.sub)
            }
        }
        StudioButton(
            if (picked.isEmpty()) "Pick pages" else "Use on ${picked.size} ${if (picked.size == 1) "page" else "pages"}",
            {
                onTool(null)
                ed.vm.edit(reflowAll = true) { d -> FreeEdit.copyLayout(d, source.id, picked, layout.pages) { id -> layout.number(d, id) } }
            },
            Modifier.fillMaxWidth(), icon = BtIcons.CopyLayout, primary = true, enabled = picked.isNotEmpty(),
        )
    }
}

/** Add to the page: words, a photo from the trip, stickers (tape, corners, the stamp, a flag, shapes, a line), a route. */
@Composable
private fun AddPanel(group: AddGroup, ed: PageEditing, routes: List<AddKind>, onTool: (Tool?) -> Unit, onAdd: (AddKind, String?) -> Unit) {
    var flagPicking by remember(group) { mutableStateOf(false) }
    val countries = ed.layout.facts.countries
    val title = when (group) {
        AddGroup.All -> "Add"
        AddGroup.Photo -> "Add a photo"
        AddGroup.Sticker -> "Add a sticker"
        AddGroup.Map -> "Add a map"
    }
    StudioPanel(title, { onTool(null) }, scroll = group != AddGroup.Photo) {
        if (group == AddGroup.Photo) {
            PhotoPicker(ed.photos, null, { m -> onAdd(AddKind.Photo, m.id) }, Modifier.weight(1f), label = "Add this photo")
            return@StudioPanel
        }
        val kinds = when (group) {
            AddGroup.Sticker -> STICKERS
            AddGroup.Map -> routes
            else -> AddKind.entries.filter { it !in setOf(AddKind.DayRoute, AddKind.TripRoute) || it in routes }
        }
        StudioGrid(kinds, 4) { k ->
            AddTile(k, on = k == AddKind.Flag && flagPicking) {
                when {
                    k == AddKind.Photo -> onTool(Tool.Add(AddGroup.Photo))
                    k == AddKind.Flag && countries.size != 1 -> flagPicking = !flagPicking
                    k == AddKind.Flag -> onAdd(k, countries.single())
                    else -> onAdd(k, null)
                }
            }
        }
        if (flagPicking) StudioSection("Country") {
            var code by remember { mutableStateOf("") }
            if (countries.isNotEmpty()) StudioGrid(countries, 4) { cc -> StudioChip(cc, false, { onAdd(AddKind.Flag, cc) }, Modifier.fillMaxWidth()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { StudioField(code, { code = it.filter { ch -> ch.isLetter() }.take(2).uppercase() }, "Country code, like GR", singleLine = true) }
                StudioButton("Add", { onAdd(AddKind.Flag, code) }, enabled = code.length == 2, primary = true)
            }
        }
    }
}

@Composable
private fun AddTile(k: AddKind, on: Boolean, onClick: () -> Unit) {
    val fg = if (on) S.onYellow else S.ink
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) S.yellow else S.raised)
            .clickable(role = Role.Button, onClickLabel = k.label, onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(k.icon, null, Modifier.size(22.dp), tint = fg)
        Spacer(Modifier.height(4.dp))
        Text(k.label, style = StudioText.tool, color = fg, textAlign = TextAlign.Center, maxLines = 2)
    }
}
