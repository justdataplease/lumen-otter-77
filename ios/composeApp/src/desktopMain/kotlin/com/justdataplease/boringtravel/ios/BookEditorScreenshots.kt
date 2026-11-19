package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.justdataplease.boringtravel.album.AlbumLayouts
import com.justdataplease.boringtravel.book.BookDoc
import com.justdataplease.boringtravel.book.BookEdit
import com.justdataplease.boringtravel.book.BookFacts
import com.justdataplease.boringtravel.book.BookFormat
import com.justdataplease.boringtravel.book.BookLayout
import com.justdataplease.boringtravel.book.BookPage
import com.justdataplease.boringtravel.book.BookSpec
import com.justdataplease.boringtravel.book.AlbumEdit
import com.justdataplease.boringtravel.book.FreeEdit
import com.justdataplease.boringtravel.book.FreeEl
import com.justdataplease.boringtravel.book.FreeKind
import com.justdataplease.boringtravel.book.FreeMath
import com.justdataplease.boringtravel.book.PageBg
import com.justdataplease.boringtravel.book.Slot
import com.justdataplease.boringtravel.book.Template
import com.justdataplease.boringtravel.book.phoneTextMeasure
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.ui.book.AddGroup
import com.justdataplease.boringtravel.ui.book.Tool
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * The book's page editor (round 22) for review, on the demo's Argentina laid out in Notebook on A5 with a few pages
 * added for the purpose (decorations on a notes page, a coloured and a photo background, an album page with photos its
 * layout has no room for, an empty two-photo page, a page arranged by hand with a hidden element): a page in the editor
 * with nothing selected (the layers, the tool row, "+ Photos"), a photo selected (its outline, corners and turn handle),
 * a move caught as it snaps (the dashed guides), a resize and a turn caught with their "W × H mm" and "N°", the page
 * after each once the finger lifts (redrawn from its edited HTML: the desktop's stand-in has no JavaScript, so the page
 * itself shows the result then), Undo and Redo, and every tool's panel. The touches are sent to the scene as a finger's.
 */

/** The demo's Argentina laid out for the editor's pictures and tests, with the extra pages they use. */
internal class EditorBook(val book: TripBook, val kit: MediaKit, val settings: AppSettings, val layout: BookLayout, val doc: BookDoc) {
    fun index(id: String): Int = doc.indexOf(id)
    fun index(t: Template, nth: Int = 0): Int = doc.pages.withIndex().filter { it.value.template == t }[nth].index
    fun page(i: Int): BookPage = doc.pages[i]
    fun elements(i: Int): List<FreeEl> = layout.pages.free.elements(page(i), layout.number(doc, page(i).id))
}

internal const val DECO = "deco-page"
internal const val COLOUR = "colour-page"
internal const val PHOTO_BG = "photo-bg-page"
internal const val TWO = "two-photo-page"

/** A fresh demo with the editor's book laid out and kept as the trip's draft (a visit to the book opens it). */
internal fun editorBook(): EditorBook {
    val (book, kit) = demoBook()
    val settings = AppSettings.ready(Look.PostcardPop).apply { demoMode = true; printTheme = PrintTheme.Notebook }
    val exports = BookExports(book, kit.files, kit.frames, NoPagePrinter)
    val trip = runBlocking { exports.loadTrip(SampleArgentina.TRIP_ID) }
    val facts = BookFacts(trip)
    val spec = BookSpec(format = BookFormat.A5, theme = PrintTheme.Notebook)
    var n = 0
    val layout = BookLayout(spec, facts, phoneTextMeasure()) { "shot-${n++}" }
    var doc = layout.auto()
    val pl = layout.pages
    val photos = trip.media.filter { it.deletedAt == null && !it.isVideo }.map { it.id }
    val day = doc.pages.lastOrNull { it.day != null }?.day
    // Before the back cover: decorations on a notes page, a coloured background, a photo background, an empty
    // two-photo page, and an album page of four photos given the two-photo arrangement (two kept for a bigger layout).
    val deco = BookEdit.blank(Template.Notes, DECO, day).let { p ->
        listOf(
            FreeEdit.newShape(pl, "deco-box", oval = false).let { it.copy(box = it.box.offset(-28.0, -44.0)) },
            FreeEdit.newShape(pl, "deco-circle", oval = true).let { it.copy(box = it.box.offset(26.0, -42.0)) },
            FreeEdit.newLine(pl, "deco-line").let { it.copy(box = it.box.offset(0.0, -12.0)) },
            FreeEdit.newTape(pl, "deco-tape").let { it.copy(box = it.box.offset(-26.0, 14.0)) },
            FreeEdit.newCorners(pl, "deco-corners").let { it.copy(box = it.box.offset(0.0, 60.0)) },
            FreeEdit.newFlag(pl, "deco-flag", "AR").let { it.copy(box = it.box.offset(30.0, 12.0)) },
            FreeEdit.newStamp(pl, "deco-stamp").let { it.copy(box = it.box.offset(32.0, 34.0)) },
            FreeEdit.newText(pl, "deco-text").copy(text = "Patagonia, at last").let { it.copy(box = it.box.copy(h = pl.free.contentHeight(it.copy(text = "Patagonia, at last"))).offset(0.0, -72.0)) },
        ).fold(p) { acc, e -> FreeEdit.add(acc, e) }
    }
    val colour = FreeEdit.setBg(BookEdit.blank(Template.Notes, COLOUR, day), PageBg(PageBg.COLOR, color = "#E4EDF4"))
    val photoBg = FreeEdit.setBg(BookEdit.blank(Template.Notes, PHOTO_BG, day), PageBg(PageBg.PHOTO, photo = Slot(photos[1]), dim = 0.3))
    val two = BookEdit.blank(Template.PhotoTwo, TWO, day)
    val album = AlbumEdit.pages(photos.take(4).map { Slot(it) }, AlbumLayouts.forCount(4).first(), day, null) { "album-${n++}" }
        .map { AlbumEdit.apply(it, AlbumLayouts.forCount(2).first().id) }
    val at = AlbumEdit.beforeBack(doc)
    doc = AlbumEdit.insertAt(doc, at, listOf(deco, colour, photoBg, two) + album)
    // A photo page arranged by hand: its photo moved and turned, a text box added and hidden (the second photo page).
    val handPage = doc.pages.withIndex().filter { it.value.template == Template.PhotoText }.getOrNull(1)?.value
    if (handPage != null) {
        val number = layout.number(doc, handPage.id)
        val els = pl.free.elements(handPage, number)
        var p: BookPage = handPage
        els.firstOrNull { it.isPhoto }?.let { ph -> p = FreeEdit.move(p, pl, number, ph.id, ph.box.offset(4.0, 6.0), -4.0) }
        p = FreeEdit.add(p, FreeEdit.newText(pl, "hand-text").copy(text = "Seven glasses").let { it.copy(box = it.box.offset(0.0, 60.0)) })
        p = FreeEdit.setHidden(p, pl, number, "hand-text", true)
        val done = p
        doc = BookEdit.update(doc, handPage.id) { done }
    }
    doc = layout.reflow(doc)
    exports.save(exports.newDraft(SampleArgentina.TRIP_ID, spec, doc))
    return EditorBook(book, kit, settings, layout, doc)
}

/** The book screen on [page] with what [start] asks for selected and open. */
@Composable
internal fun EditorShot(b: EditorBook, page: Int, start: EditorStart?) {
    CompositionLocalProvider(LocalMediaKit provides b.kit, LocalMediaRows provides { id: String -> b.book.media(id) }, LocalPagePrinter provides NoPagePrinter) {
        BoringTravelTheme(Look.PostcardPop) {
            Box(Modifier.fillMaxSize().background(StudioColors.bg).padding(PHONE_FRAME)) {
                BookScreen(b.book, SampleArgentina.TRIP_ID, b.settings, onBack = {}, pageAtStart = page, editorAtStart = start)
            }
        }
    }
}

fun bookEditor(out: File) {
    val b = editorBook()
    val photoPage = b.index(Template.PhotoText)
    val dayPage = b.index(Template.DayOpener)
    val handPage = b.doc.pages.withIndex().filter { it.value.template == Template.PhotoText }.getOrNull(1)?.index ?: photoPage
    val deco = b.index(DECO)
    val album = b.doc.pages.indexOfFirst { it.id.startsWith("album-") }
    val photo: (List<FreeEl>) -> String? = { els -> els.firstOrNull { it.isPhoto && !it.hidden }?.id }
    val words: (List<FreeEl>) -> String? = { els -> (els.firstOrNull { it.bind == FreeEl.TITLE } ?: els.firstOrNull { it.isText && it.text.isNotBlank() })?.id }
    val flow: (List<FreeEl>) -> String? = { els -> els.firstOrNull { it.isFlow }?.id }
    fun id(name: String): (List<FreeEl>) -> String? = { name }

    fun shot(name: String, page: Int, start: EditorStart? = null) =
        File(out, "$name.png").writeBytes(render(PHONE_W, PHONE_H, PHONE_DENSITY, 60) { EditorShot(b, page, start) })

    // The editor: nothing selected (the layers and the page's tools), a photo selected, words, the page's text.
    shot("book-editor", photoPage)
    shot("book-editor-selected", photoPage, EditorStart(photo))
    shot("book-editor-words-selected", dayPage, EditorStart(words))
    shot("book-editor-page-text-selected", photoPage, EditorStart(flow))
    // A page arranged by hand: the photo moved and turned, an element hidden (dimmed in the list, dashed when selected).
    shot("book-editor-by-hand", handPage)
    shot("book-editor-hidden-selected", handPage, EditorStart({ els -> els.lastOrNull { it.hidden }?.id }))
    // Photos the layout has no room for, and the background row at the end of the list.
    shot("book-editor-extras", album)

    // The page's own tools.
    shot("book-tool-layout", album, EditorStart(tool = Tool.Layout))
    shot("book-tool-paper", photoPage, EditorStart(tool = Tool.Paper))
    shot("book-tool-paper-colour", b.index(COLOUR), EditorStart(tool = Tool.Paper))
    shot("book-tool-paper-photo", b.index(PHOTO_BG), EditorStart(tool = Tool.Paper))
    shot("book-tool-look", photoPage, EditorStart(tool = Tool.Look))
    shot("book-tool-page", photoPage, EditorStart(tool = Tool.Page))
    shot("book-tool-add-page", photoPage, EditorStart(tool = Tool.AddPage))
    shot("book-tool-copy-layout", photoPage, EditorStart(tool = Tool.CopyLayout))
    shot("book-tool-photo-pages", photoPage, EditorStart(tool = Tool.AlbumPages))
    shot("book-tool-add", photoPage, EditorStart(tool = Tool.Add(AddGroup.All)))
    shot("book-tool-add-photo", photoPage, EditorStart(tool = Tool.Add(AddGroup.Photo)))
    shot("book-tool-add-sticker", photoPage, EditorStart(tool = Tool.Add(AddGroup.Sticker)))
    shot("book-tool-add-map", dayPage, EditorStart(tool = Tool.Add(AddGroup.Map)))
    shot("book-tool-add-photos", photoPage, EditorStart(tool = Tool.AddPhotos()))
    // An empty photo spot selected asks for its photos at once ("Choose photos").
    shot("book-tool-choose-photos", b.index(TWO), EditorStart({ els -> els.firstOrNull { it.isPhoto }?.id }, Tool.AddPhotos(b.elements(b.index(TWO)).firstOrNull { it.isPhoto }?.id)))
    // A photo's tools, then Arrange's Rotate.
    shot("book-tool-replace", photoPage, EditorStart(photo, Tool.Replace))
    shot("book-tool-crop", photoPage, EditorStart(photo, Tool.Crop))
    shot("book-tool-frame", photoPage, EditorStart(photo, Tool.Frame))
    shot("book-tool-caption", photoPage, EditorStart(photo, Tool.Caption))
    shot("book-tool-rotate", photoPage, EditorStart(photo, Tool.Rotate))
    // Words' tools, and the page's own text.
    shot("book-tool-edit", deco, EditorStart(id("deco-text"), Tool.Edit))
    shot("book-tool-font", deco, EditorStart(id("deco-text"), Tool.Font))
    shot("book-tool-colour", deco, EditorStart(id("deco-text"), Tool.Colour))
    shot("book-tool-size", deco, EditorStart(id("deco-text"), Tool.Size))
    shot("book-tool-align", deco, EditorStart(id("deco-text"), Tool.Align))
    shot("book-tool-page-text", photoPage, EditorStart(flow, Tool.Edit))
    // Shapes and stickers.
    shot("book-tool-style-box", deco, EditorStart(id("deco-box"), Tool.Style))
    shot("book-tool-style-line", deco, EditorStart(id("deco-line"), Tool.Style))
    shot("book-tool-colour-tape", deco, EditorStart(id("deco-tape"), Tool.Style))
    shot("book-tool-colour-corners", deco, EditorStart(id("deco-corners"), Tool.Style))
    shot("book-tool-country", deco, EditorStart(id("deco-flag"), Tool.Style))
    shot("book-editor-stickers", deco, EditorStart(id("deco-stamp")))

    // Touches: a move caught as it snaps, a resize and a turn caught mid-way, then a move let go, Undo and Redo.
    // Each on a book of its own: a finger still down when a scene closes lifts, and the change is kept.
    val photoEl = b.elements(photoPage).first { it.isPhoto }
    val pageW = b.layout.spec.format.widthMm
    fun touched(name: String, act: EditorDriver.(Transform) -> Unit) {
        val fresh = editorBook()
        EditorDriver { EditorShot(fresh, photoPage, EditorStart(photo)) }.use { d ->
            d.frames(60)
            val tf = d.transform(photoEl) ?: error("The selected photo isn't on the screen")
            d.act(tf)
            File(out, "$name.png").writeBytes(d.png())
            // The finger lifts: the change is kept as one step and the page drawn again from its edited HTML.
            d.release()
            d.frames(60)
            File(out, "$name-done.png").writeBytes(d.png())
        }
    }
    touched("book-editor-snap") { tf ->
        // The photo's middle dragged to a few pixels beside the page's middle and down: it snaps onto the middle line.
        val from = tf.screen(photoEl.box.cx, photoEl.box.cy)
        press(from)
        moveTo(Offset(tf.screen(pageW / 2, 0.0).x + 9f, from.y + 150f), steps = 10)
        frames(6)
    }
    touched("book-editor-resize") { tf ->
        val corner = FreeMath.corners(photoEl.box, photoEl.rot)[2]
        val from = tf.screen(corner.x, corner.y)
        press(from)
        moveTo(Offset(from.x - 120f, from.y - 80f), steps = 10)
        frames(6)
    }
    touched("book-editor-turn") { tf ->
        val c = tf.screen(photoEl.box.cx, photoEl.box.cy)
        val handle = FreeMath.rotateHandle(photoEl.box, photoEl.rot, 30.0 * PHONE_DENSITY / tf.scale)
        val h = tf.screen(handle.x, handle.y)
        val r = hypot((h.x - c.x).toDouble(), (h.y - c.y).toDouble())
        // The handle of a photo near the page's top reaches above the page: pressed on its part over the page (the
        // editing layer is the page's size; a turn begun on the part beside it shows while the finger moves, but on
        // the desktop it isn't kept when the finger lifts).
        press(Offset(h.x, maxOf(h.y, tf.screen(0.0, 0.0).y + 8f)))
        // Along the circle to 22° clockwise from upright.
        for (step in 1..10) {
            val deg = photoEl.rot + (22.0 - photoEl.rot) * step / 10
            val a = Math.toRadians(deg - 90.0)
            move(Offset((c.x + r * cos(a)).toFloat(), (c.y + r * sin(a)).toFloat()))
        }
        frames(6)
    }
    val moving = editorBook()
    EditorDriver { EditorShot(moving, photoPage, EditorStart(photo)) }.use { d ->
        d.frames(60)
        val tf = d.transform(photoEl) ?: error("The selected photo isn't on the screen")
        val from = tf.screen(photoEl.box.cx, photoEl.box.cy)
        d.press(from)
        d.moveTo(Offset(from.x - 70f, from.y + 210f), steps = 10)
        d.release()
        // The move kept as one step, the page turned "arranged by hand" and drawn again from its HTML.
        d.frames(60)
        File(out, "book-editor-moved.png").writeBytes(d.png())
        d.tap("Undo")
        d.frames(60)
        File(out, "book-editor-undo.png").writeBytes(d.png())
        d.tap("Redo")
        d.frames(60)
        File(out, "book-editor-redo.png").writeBytes(d.png())
    }
}

/** Page mm to scene pixels, from where an element's semantics box sits. */
internal class Transform(val scale: Double, val ox: Double, val oy: Double) {
    fun screen(x: Double, y: Double): Offset = Offset((ox + x * scale).toFloat(), (oy + y * scale).toFloat())
}

/**
 * A scene drawn frame by frame with a finger on it (the screenshots' render() with touches): each frame's picture is
 * closed when the next is drawn, the scene when done, and what the screen drew is collected after it.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class EditorDriver(content: @Composable () -> Unit) : AutoCloseable {
    private val scene = ImageComposeScene(PHONE_W, PHONE_H, Density(PHONE_DENSITY)) { content() }
    private var ms = 0L
    private var image: Image = scene.render(0)
    private var finger: Offset = Offset.Zero

    fun frames(n: Int) = repeat(n) {
        Thread.sleep(50)
        ms += 50
        image.close()
        image = scene.render(ms * 1_000_000)
    }

    fun press(at: Offset) {
        finger = at
        scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ms, type = PointerType.Touch)
        frames(1)
    }

    fun move(to: Offset) {
        finger = to
        scene.sendPointerEvent(PointerEventType.Move, to, timeMillis = ms, type = PointerType.Touch)
        frames(1)
    }

    fun moveTo(to: Offset, steps: Int) {
        val from = finger
        for (i in 1..steps) move(Offset(from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps))
    }

    fun release() {
        scene.sendPointerEvent(PointerEventType.Release, finger, timeMillis = ms, type = PointerType.Touch)
        frames(1)
    }

    /** A tap on what is labelled [description]. */
    fun tap(description: String) {
        val r = bounds(description) ?: error("Nothing labelled $description")
        press(r.center)
        release()
    }

    fun bounds(description: String): Rect? {
        fun find(n: SemanticsNode): Rect? {
            if (n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true) return n.boundsInRoot
            n.children.forEach { c -> find(c)?.let { return it } }
            return null
        }
        scene.semanticsOwners.forEach { o -> find(o.unmergedRootSemanticsNode)?.let { return it } }
        return null
    }

    /** Page mm to pixels, from the selected element's box on the page (the editor's own semantics box for it). */
    fun transform(selected: FreeEl): Transform? {
        val label = when (selected.kind) {
            FreeKind.PHOTO -> "Photo"
            else -> return null
        }
        val r = bounds("$label, selected") ?: return null
        val mm = FreeMath.bounds(selected.box, selected.rot)
        val scale = r.width / mm.w
        return Transform(scale, r.left - mm.x * scale, r.top - mm.y * scale)
    }

    fun png(): ByteArray = image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }

    override fun close() {
        image.close()
        scene.close()
        System.gc()
    }
}
