package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.album.AlbumLayouts
import com.justdataplease.boringtravel.book.BookDoc
import com.justdataplease.boringtravel.book.BookJson
import com.justdataplease.boringtravel.book.BookPage
import com.justdataplease.boringtravel.book.FreeEdit
import com.justdataplease.boringtravel.book.FreeEl
import com.justdataplease.boringtravel.book.FreeKind
import com.justdataplease.boringtravel.book.FreeLayout
import com.justdataplease.boringtravel.book.FreeMath
import com.justdataplease.boringtravel.book.Layers
import com.justdataplease.boringtravel.book.PageBg
import com.justdataplease.boringtravel.book.Template
import com.justdataplease.boringtravel.ui.book.AddGroup
import com.justdataplease.boringtravel.ui.book.BookViewModel
import com.justdataplease.boringtravel.ui.book.Tool
import com.justdataplease.boringtravel.ui.book.elementLabel
import com.justdataplease.boringtravel.ui.book.moveScript
import com.justdataplease.boringtravel.ui.book.resetScript
import com.justdataplease.boringtravel.ui.book.LiveMove
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.justdataplease.boringtravel.androidref.book.BookJson as AndroidJson

/**
 * The book's page editor on the desktop (round 22): Android's PageEditor, PageCanvas, ToolPanels and LayerList, read in,
 * on the demo's Argentina laid out in Notebook on A5 with a few pages added (desktopMain's editorBook: decorations on a
 * notes page, a coloured and a photo background, an empty two-photo page, an album page with photos its layout has no
 * room for, a page arranged by hand with a hidden text). Each test taps, drags and types as a finger would, then reads
 * the book the trip store kept (the `books` row a backup carries): selecting, moving with snapping, resizing, turning,
 * the page turned "arranged by hand", the layers list, every tool, Undo and Redo, and the stored JSON as Android's own
 * BookJson writes it.
 */
@OptIn(ExperimentalTestApi::class)
class BookEditorOnDesktopTest {
    init {
        runBlocking { BtFontFiles.load() }
    }

    private val trip = SampleArgentina.TRIP_ID

    private fun saved(b: EditorBook): BookDoc = BookJson.decode(assertNotNull(b.book.latestBook(trip)).pages)

    private fun page(doc: BookDoc, id: String): BookPage = assertNotNull(doc.page(id), "page $id")

    /** The book open on page [index], with what [start] asks for selected and open; waits for the editor. */
    private fun ComposeUiTest.open(b: EditorBook, index: Int, start: EditorStart? = null) {
        setContent {
            CompositionLocalProvider(LocalMediaKit provides b.kit, LocalMediaRows provides { id: String -> b.book.media(id) }) {
                BookScreen(b.book, trip, b.settings, onBack = {}, pageAtStart = index, editorAtStart = start)
            }
        }
        waitUntil(timeoutMillis = 60_000) { onAllNodesWithContentDescription("All pages").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    /** Waits for the kept book to meet [check] (a save comes 0.4 s after a change) and gives it. */
    private fun ComposeUiTest.savedWhen(b: EditorBook, check: (BookDoc) -> Boolean): BookDoc {
        var last = saved(b)
        waitUntil(timeoutMillis = 20_000) {
            last = saved(b)
            check(last)
        }
        return last
    }

    private fun clickLabel(label: String) = SemanticsMatcher("click label $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    /** A tool in the tool row (the last thing with that label: a panel's tile of the same name comes before it). */
    private fun ComposeUiTest.tool(label: String): SemanticsNodeInteraction = onAllNodes(clickLabel(label)).onLast().also { it.performScrollTo() }

    /** Page mm to the test window's pixels, from where the selected element's box sits (its semantics box on the page). */
    private class Tf(val k: Double, val ox: Double, val oy: Double) {
        fun at(x: Double, y: Double) = Offset((ox + x * k).toFloat(), (oy + y * k).toFloat())
    }

    private fun ComposeUiTest.tf(e: FreeEl): Tf {
        val r = onNodeWithContentDescription(elementLabel(e) + ", selected").fetchSemanticsNode().boundsInRoot
        val mm = FreeMath.bounds(e.box, e.rot)
        val k = r.width / mm.w
        return Tf(k, r.left - mm.x * k, r.top - mm.y * k)
    }

    /** A finger down at [from], along a straight line to [to] in [steps], and up. */
    private fun ComposeUiTest.drag(from: Offset, to: Offset, steps: Int = 12) {
        onRoot().performTouchInput {
            down(from)
            for (i in 1..steps) moveTo(Offset(from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps))
            up()
        }
        waitForIdle()
    }

    private fun near(a: Double, b: Double, tol: Double = 0.01) = abs(a - b) <= tol

    /** The kept JSON is Android's: its own BookJson reads it and writes it back the same. */
    private fun assertAndroidForm(b: EditorBook) {
        val pages = assertNotNull(b.book.latestBook(trip)).pages
        assertEquals(pages, AndroidJson.encode(AndroidJson.decode(pages)))
    }

    @Test
    fun aPageOpensWithItsLayersItsToolsAndPhotos() {
        val b = editorBook()
        val i = b.index(Template.PhotoText)
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, i)
            val n = b.layout.printed(b.doc).indexOfFirst { it.id == b.page(i).id } + 1
            onNodeWithText("Page $n of ${b.layout.printed(b.doc).size}").assertExists()
            onNodeWithText("Argentina & Patagonia · Book").assertExists()
            // "On this page · top first": a row per element with its name and detail, the background last.
            onNodeWithText("ON THIS PAGE · TOP FIRST").assertExists()
            onNodeWithText("+ Add").assertExists()
            val face: (String?) -> com.justdataplease.boringtravel.book.Face = { b.layout.pages.free.face(it) }
            Layers.topFirst(b.elements(i)).forEach { e ->
                onAllNodesWithContentDescription("${Layers.name(e, b.layout.facts)}, ${Layers.detail(e, face)}").onFirst().assertExists()
            }
            assertTrue(b.elements(i).any { it.isPhoto && Layers.detail(it, face).startsWith("Photo · frame") })
            onNodeWithText("Dotted paper").assertExists()
            onNodeWithText("Background · the theme's").assertExists()
            // The page's text has no eye (it can't hide); the photo has one.
            assertEquals(b.elements(i).count { FreeEdit.canHide(it) }, onAllNodesWithContentDescription("Shown. Hide it").fetchSemanticsNodes().size)
            // The tool row with nothing selected: Page (Layout, Paper, Look, Page) and Add (Photo, Text, Sticker, Map).
            listOf("PAGE", "ADD").forEach { onNodeWithText(it).assertExists() }
            listOf("Layout", "Paper", "Look", "Page", "Photo", "Text", "Sticker").forEach { onAllNodes(clickLabel(it)).onLast().assertExists() }
            // "+ Photos", Undo and Redo on the stage; nothing to undo yet.
            onNode(clickLabel("Add photos")).assertExists()
            onNodeWithContentDescription("Undo").assertIsNotEnabled()
            onNodeWithContentDescription("Redo").assertIsNotEnabled()
            onNode(clickLabel("Add photos")).performClick()
            onNodeWithText("Add photos").assertExists()
            onNodeWithText("Not in book").assertExists()
            onNodeWithText("Pick photos").assertExists()
        }
    }

    @Test
    fun aTapSelectsASecondOpensItsToolAndATapBesideThePageLetsGo() {
        val b = editorBook()
        val i = b.index(Template.PhotoText)
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, i)
            // The first "Photo" in the tree is the element on the page (the layers list comes after it).
            onAllNodesWithContentDescription("Photo").onFirst().performClick()
            waitForIdle()
            onNodeWithContentDescription("Photo, selected").assertExists()
            // Its own tools, then Arrange's.
            onNodeWithText("PHOTO", substring = true).assertExists()
            listOf("Replace", "Crop", "Frame", "Caption", "Rotate", "Forward", "Back", "Copy", "Delete").forEach { tool(it).assertExists() }
            // Again: its main tool (a photo's crop).
            onNodeWithContentDescription("Photo, selected").performClick()
            waitForIdle()
            assertTrue(
                onAllNodesWithText("Drag to move it, pinch to zoom.").fetchSemanticsNodes().isNotEmpty() ||
                    onAllNodesWithText("This photo may print soft at this size.").fetchSemanticsNodes().isNotEmpty(),
            )
            // A tap on the stage beside the page lets go, and the panel closes with it.
            val r = onNodeWithContentDescription("Photo, selected").fetchSemanticsNode().boundsInRoot
            onRoot().performTouchInput { click(Offset(6f, r.center.y)) }
            waitForIdle()
            assertTrue(onAllNodesWithContentDescription("Photo, selected").fetchSemanticsNodes().isEmpty())
            onNodeWithText("ON THIS PAGE · TOP FIRST").assertExists()
            // Done closes a panel; All pages goes back to every page.
            onAllNodesWithContentDescription("Photo").onFirst().performClick()
            tool("Frame").performClick()
            onNodeWithText("A white border prints like a photo print.").assertExists()
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
            onNodeWithContentDescription("Photo, selected").assertExists()
            onNodeWithContentDescription("All pages").performClick()
            waitForIdle()
            onNodeWithText("Tap a page to change it. Hold and drag to move it.").assertExists()
        }
        // An empty photo spot asks for its photos straight away.
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, b.index(TWO))
            onAllNodesWithContentDescription("Photo").onFirst().performClick()
            waitForIdle()
            onNodeWithText("Choose photos").assertExists()
            onNodeWithText("PHOTO · NO PHOTO YET").assertExists()
        }
    }

    @Test
    fun aDragMovesSnapsAndTheFirstChangeArrangesThePageByHand() {
        val b = editorBook()
        val i = b.index(Template.PhotoText)
        val id = b.page(i).id
        val before = b.elements(i)
        val photo = before.first { it.isPhoto }
        val pageW = b.layout.spec.format.widthMm
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, i, EditorStart({ photo.id }))
            val t = tf(photo)
            // The photo's middle dragged to two pixels beside the page's middle, and down: it snaps onto the middle.
            val from = t.at(photo.box.cx, photo.box.cy)
            drag(from, Offset(t.at(pageW / 2, 0.0).x + 2f, from.y + 60f))
            val moved = savedWhen(b) { page(it, id).free }
            val p = page(moved, id)
            val el = assertNotNull(p.el(photo.id))
            assertTrue(near(el.box.cx, pageW / 2), "snapped onto the page's middle: ${el.box.cx}")
            assertTrue(el.box.cy - photo.box.cy > 20.0, "moved down ${el.box.cy - photo.box.cy} mm")
            assertEquals(photo.box.w, el.box.w, 0.01)
            // Arranged by hand, looking the same: the template's other elements as they were drawn, in the same order.
            val now = b.layout.pages.free.elements(p, b.layout.number(moved, id))
            assertEquals(before.map { it.id }, now.map { it.id })
            before.zip(now).filter { it.first.id != photo.id }.forEach { (a, c) ->
                assertEquals(a.kind, c.kind)
                assertTrue(near(a.box.x, c.box.x) && near(a.box.y, c.box.y) && near(a.box.w, c.box.w) && near(a.box.h, c.box.h), "${a.id}: ${a.box} → ${c.box}")
                assertEquals(a.rot, c.rot, 0.01)
            }
            assertAndroidForm(b)
            // Undo puts the template page back; Redo the page arranged by hand.
            onNodeWithContentDescription("Undo").assertIsEnabled().performClick()
            assertFalse(page(savedWhen(b) { !page(it, id).free }, id).free)
            onNodeWithContentDescription("Redo").assertIsEnabled().performClick()
            assertTrue(near(assertNotNull(page(savedWhen(b) { page(it, id).free }, id).el(photo.id)).box.cx, pageW / 2))
            // Dragged off the page, 6 mm of it stay on.
            val far = t.at(el.box.cx, el.box.cy)
            drag(far, Offset(0f, far.y), steps = 20)
            val off = assertNotNull(page(savedWhen(b) { d -> page(d, id).el(photo.id)!!.box.x < 0 }, id).el(photo.id))
            assertTrue(near(off.box.right, 6.0, 0.05), "6 mm stay on the page: ${off.box.right}")
        }
    }

    @Test
    fun cornersResizeAndTheRoundHandleTurnsSnappingToEighths() {
        val b = editorBook()
        val i = b.index(Template.PhotoText)
        val id = b.page(i).id
        val photo = b.elements(i).first { it.isPhoto }
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, i, EditorStart({ photo.id }))
            val t = tf(photo)
            // A photo keeps its shape from a corner.
            val c = FreeMath.corners(photo.box, photo.rot)[2]
            drag(t.at(c.x, c.y), t.at(c.x - 20, c.y - 10))
            val p = assertNotNull(page(savedWhen(b) { d -> page(d, id).el(photo.id)?.box?.w?.let { it < photo.box.w } == true }, id).el(photo.id))
            assertEquals(photo.box.w / photo.box.h, p.box.w / p.box.h, 0.001)
            assertTrue(p.box.w < photo.box.w - 5)
        }
        val deco = b.index(DECO)
        val box = b.elements(deco).first { it.id == "deco-box" }
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, deco, EditorStart({ box.id }))
            val t = tf(box)
            // The round handle, dragged round to 43° from upright, lands on 45°.
            val gap = 30.0 / t.k
            val h = FreeMath.rotateHandle(box.box, box.rot, gap)
            val r = gap + box.box.h / 2
            val a = Math.toRadians(43.0)
            drag(t.at(h.x, h.y), t.at(box.box.cx + r * sin(a), box.box.cy - r * cos(a)), steps = 16)
            assertEquals(45.0, assertNotNull(page(savedWhen(b) { d -> page(d, DECO).el(box.id)?.rot == 45.0 }, DECO).el(box.id)).rot)
        }
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, deco, EditorStart({ box.id }))
            val now = assertNotNull(page(saved(b), DECO).el(box.id))
            val t = tf(now)
            // A corner dragged past the opposite one: never smaller than 4 mm.
            val c = FreeMath.corners(now.box, now.rot)[2]
            val o = FreeMath.corners(now.box, now.rot)[0]
            drag(t.at(c.x, c.y), t.at(o.x - 10, o.y - 10), steps = 16)
            val small = assertNotNull(page(savedWhen(b) { d -> page(d, DECO).el(box.id)!!.box.w < 5 }, DECO).el(box.id))
            assertEquals(4.0, small.box.w, 0.01)
            assertEquals(4.0, small.box.h, 0.01)
        }
        assertAndroidForm(b)
    }

    @Test
    fun theLayersListSelectsReordersHidesAndOpensThePaper() {
        val b = editorBook()
        val i = b.index(Template.PhotoText, 1)
        val id = b.page(i).id
        val els = b.elements(i)
        val face: (String?) -> com.justdataplease.boringtravel.book.Face = { b.layout.pages.free.face(it) }
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, i)
            // The hidden text is listed, dimmed, with its eye shut; the turned photo says so.
            onNodeWithContentDescription("Seven glasses, Text · Hand, hidden").assertExists()
            onNodeWithContentDescription("Hidden. Show it").assertExists()
            val photo = els.first { it.isPhoto }
            onAllNodesWithContentDescription("${Layers.name(photo, b.layout.facts)}, ${Layers.detail(photo, face)}").onFirst().assertExists()
            assertTrue(Layers.detail(photo, face).endsWith("−4°"))
            // A row selects on the page; again lets go.
            onAllNodesWithContentDescription("${Layers.name(photo, b.layout.facts)}, ${Layers.detail(photo, face)}").onFirst().performClick()
            waitForIdle()
            onNodeWithContentDescription("Photo, selected").assertExists()
            // The eye shows the hidden text again, and hides the photo from the page and the PDF.
            onNodeWithContentDescription("Hidden. Show it").performClick()
            assertFalse(assertNotNull(page(savedWhen(b) { d -> page(d, id).el("hand-text")?.hidden == false }, id).el("hand-text")).hidden)
            onAllNodesWithContentDescription("Shown. Hide it").onLast().performClick()
            savedWhen(b) { d -> page(d, id).els.count { it.hidden } == 1 }
            // A row's handle drags it back a place: the text on top goes below the next.
            val top = Layers.topFirst(page(saved(b), id).els).first().id
            val handle = onAllNodesWithContentDescription("Drag to reorder", useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
            drag(handle.center, Offset(handle.center.x, handle.center.y + 66f), steps = 10)
            val reordered = savedWhen(b) { d -> Layers.topFirst(page(d, id).els).first().id != top }
            assertEquals(top, Layers.topFirst(page(reordered, id).els)[1].id)
            // The background row opens the page's Paper.
            onNodeWithText("Background · the theme's").performScrollTo().performClick()
            waitForIdle()
            onNodeWithText("Theme's").assertExists()
            onNodeWithText("The theme's own paper.").assertExists()
        }
        assertAndroidForm(b)
    }

    @Test
    fun photosWithNoRoomGoToANewPageAndTheLayoutPanelChangesTheArrangement() {
        val b = editorBook()
        val album = b.doc.pages.indexOfFirst { it.id.startsWith("album-") }
        val id = b.page(album).id
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, album)
            onNodeWithText("2 photos not on the page: no room in this layout").assertExists()
            onNodeWithText("New page").performClick()
            val d = savedWhen(b) { it.pages.size == b.doc.pages.size + 1 }
            val at = d.indexOf(id)
            assertTrue(page(d, id).slots.drop(page(d, id).spots).none { it.mediaId != null })
            assertEquals(Template.Album, d.pages[at + 1].template)
            assertEquals(2, d.pages[at + 1].slots.count { it.mediaId != null })
        }
        val fresh = editorBook()
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(fresh, album, EditorStart(tool = Tool.Layout))
            onNodeWithText("2 photos more than this layout has room for. They stay with the page until you pick a bigger layout or move them.").assertExists()
            onNodeWithText("Put the rest on a new page").assertExists()
            // A four-photo arrangement takes all four.
            val four = AlbumLayouts.forCount(4).first()
            onNode(clickLabel("${four.name}, 4 photos")).performScrollTo().performClick()
            val d = savedWhen(fresh) { page(it, id).album == four.id }
            assertTrue(com.justdataplease.boringtravel.book.AlbumEdit.extras(page(d, id)).isEmpty())
            // "More layouts": the book's own templates (the panel stays open).
            Template.choosable.forEach { onAllNodes(clickLabel(it.label)).onFirst().performScrollTo().assertExists() }
            onAllNodes(clickLabel(Template.PhotoFour.label)).onFirst().performScrollTo().performClick()
            assertEquals(Template.PhotoFour, page(savedWhen(fresh) { page(it, id).template == Template.PhotoFour }, id).template)
        }
        assertAndroidForm(fresh)
    }

    @Test
    fun paperIsTheThemesAColourOrAPhotoAndAPanelVisitIsOneUndoStep() {
        val b = editorBook()
        val i = b.index(Template.PhotoText)
        val id = b.page(i).id
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, i, EditorStart(tool = Tool.Paper))
            onNodeWithText("The theme's own paper.").assertExists()
            onNodeWithText("Colour").performClick()
            onNodeWithContentDescription("Colour #FFFFFF").performClick()
            onNodeWithContentDescription("Colour #1E2A5A").performScrollTo().performClick()
            onNodeWithContentDescription("Done").performClick()
            val coloured = page(savedWhen(b) { page(it, id).bg?.color == "#1E2A5A" }, id)
            assertEquals(PageBg.COLOR, coloured.bg?.kind)
            // Two colours in one visit: one step back to the theme's paper.
            onNodeWithContentDescription("Undo").performClick()
            assertNull(page(savedWhen(b) { page(it, id).bg == null }, id).bg)
            onNodeWithContentDescription("Redo").performClick()
            savedWhen(b) { page(it, id).bg?.color == "#1E2A5A" }
            // A photo to the edges, dimmed.
            tool("Paper").performClick()
            onAllNodesWithText("Photo").onFirst().performClick()
            onAllNodes(clickLabel("Use this photo")).onFirst().performClick()
            val withPhoto = page(savedWhen(b) { page(it, id).bg?.kind == PageBg.PHOTO }, id)
            assertNotNull(withPhoto.bg?.photo?.mediaId)
            onNodeWithText("DIM").assertExists()
        }
        // The background row says what is under the page.
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, b.index(COLOUR))
            onNodeWithText("Colour").assertExists()
            onNodeWithText("Background").assertExists()
            onAllNodesWithContentDescription("Shown. Hide it").onLast().performClick()
            assertTrue(page(savedWhen(b) { page(it, COLOUR).bg?.hidden == true }, COLOUR).bg!!.hidden)
            onNodeWithText("Background, hidden").assertExists()
        }
        assertAndroidForm(b)
    }

    @Test
    fun thePagePanelDuplicatesHidesDeletesCopiesAndGoesBackToTheLayout() {
        val b = editorBook()
        val i = b.index(Template.PhotoText, 1)
        val id = b.page(i).id
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, i, EditorStart(tool = Tool.Page))
            listOf("Duplicate page", "Use this layout on other pages", "Add a page after", "Add photo pages", "Back to the layout", "Hide entry", "Delete page")
                .forEach { onNodeWithText(it).performScrollTo().assertExists() }
            onNodeWithText("Back to the layout puts its photos and text back in place; what you added stays.").performScrollTo().assertExists()
            // Back to the layout: a template page again, with what was added by hand on top.
            onNodeWithText("Back to the layout").performScrollTo().performClick()
            val back = page(savedWhen(b) { !page(it, id).free }, id)
            assertNotNull(back.el("hand-text"))
            // Hide entry: the note's pages leave the book.
            tool("Page").performClick()
            onNodeWithText("Hide entry").performScrollTo().performClick()
            val entry = assertNotNull(back.entryId ?: back.paras.firstOrNull { it.entry != null }?.entry)
            savedWhen(b) { entry in it.hidden }
            tool("Page").performClick()
            onNodeWithText("Show entry").performScrollTo().performClick()
            savedWhen(b) { entry !in it.hidden }
            // Duplicate page opens the copy, right after it.
            val count = saved(b).pages.size
            tool("Page").performClick()
            onNodeWithText("Duplicate page").performScrollTo().performClick()
            val dup = savedWhen(b) { it.pages.size == count + 1 }
            val copy = dup.pages[dup.indexOf(id) + 1]
            assertEquals(page(dup, id).templateId, copy.templateId)
            assertNotEquals(id, copy.id)
            // Delete page asks: Keep, or Delete.
            tool("Page").performClick()
            onNodeWithText("Delete page").performScrollTo().performClick()
            onNodeWithText("Keep").assertExists()
            onAllNodesWithText("Delete").onFirst().performClick()
            savedWhen(b) { it.pages.size == count && it.page(copy.id) == null }
            // The page after the copy opened; the one before it is the page this began on.
            onNodeWithContentDescription("Previous page").performClick()
            waitForIdle()
            // Use this layout on other pages: none picked, then those with the same layout.
            tool("Page").performClick()
            onNodeWithText("Use this layout on other pages").performScrollTo().performClick()
            onNodeWithText("Use this layout on").assertExists()
            onNodeWithText("None").performClick()
            onNodeWithText("Pick pages").performScrollTo().assertIsNotEnabled()
            onNodeWithText("Same layout").performScrollTo().performClick()
            onNodeWithText("Use on ", substring = true).performScrollTo().assertIsEnabled()
        }
        assertAndroidForm(b)
    }

    @Test
    fun addPutsNewThingsInTheMiddleSelectedAndAnEmptyTextBoxGoes() {
        val b = editorBook()
        val i = b.index(DECO)
        val content = b.layout.pages.grid.content
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, i)
            val before = page(saved(b), DECO).els.map { it.id }.toSet()
            fun added(d: BookDoc) = page(d, DECO).els.filter { it.id !in before }
            // Text: a box ready to type; closed empty, it goes.
            tool("Text").performClick()
            waitForIdle()
            onNodeWithText("Your words").assertExists()
            onNodeWithContentDescription("Text, selected").assertExists()
            savedWhen(b) { added(it).size == 1 }
            onNodeWithContentDescription("Done").performClick()
            savedWhen(b) { added(it).isEmpty() }
            // Typed in, it stays, in the middle of the type area.
            tool("Text").performClick()
            onNode(hasSetTextAction()).performTextInput("Hola")
            onNodeWithContentDescription("Done").performClick()
            val text = savedWhen(b) { d -> added(d).any { it.text == "Hola" } }.let { added(it).single() }
            assertTrue(near(text.box.cx, content.cx), "centred: ${text.box.cx} vs ${content.cx}")
            // A tap beside the page lets it go: the page's tools again.
            val beside = onNodeWithContentDescription("Text, selected").fetchSemanticsNode().boundsInRoot.center.y
            onRoot().performTouchInput { click(Offset(6f, beside)) }
            waitForIdle()
            // Sticker: the stickers; a box comes selected.
            tool("Sticker").performClick()
            onNodeWithText("Add a sticker").assertExists()
            listOf("Washi tape", "Photo corners", "Stamp", "Flag", "Box", "Circle", "Line").forEach { onAllNodes(clickLabel(it)).onFirst().assertExists() }
            onAllNodes(clickLabel("Box")).onFirst().performClick()
            waitForIdle()
            onNodeWithContentDescription("Box, selected").assertExists()
            val shape = savedWhen(b) { d -> added(d).any { it.kind == FreeKind.SHAPE } }.let { d -> added(d).first { it.kind == FreeKind.SHAPE } }
            assertTrue(near(shape.box.cx, content.cx) && near(shape.box.cy, content.cy))
            // + Add: everything, the routes when the trip has them.
            val r = onNodeWithContentDescription("Box, selected").fetchSemanticsNode().boundsInRoot
            onRoot().performTouchInput { click(Offset(6f, r.center.y)) }
            waitForIdle()
            onNodeWithText("+ Add").performClick()
            onNodeWithText("Add").assertExists()
            listOf("Text", "Photo", "Washi tape", "Photo corners", "Stamp", "Flag", "Box", "Circle", "Line").forEach { onAllNodes(clickLabel(it)).onFirst().assertExists() }
            // Flag: at once on a one-country trip, else its countries or a code.
            val countries = b.layout.facts.countries
            onAllNodes(clickLabel("Flag")).onFirst().performClick()
            waitForIdle()
            if (countries.size != 1) {
                onNodeWithText("Country code, like GR").assertExists()
                onNode(hasSetTextAction()).performTextInput("gr")
                onAllNodesWithText("Add").onLast().performClick()
            }
            val flag = savedWhen(b) { d -> added(d).any { it.kind == FreeKind.FLAGS } }.let { d -> added(d).first { it.kind == FreeKind.FLAGS } }
            assertEquals(listOf(if (countries.size == 1) countries.single() else "GR"), flag.codes)
            onNodeWithContentDescription("Flags, selected").assertExists()
            // Photo: "Add a photo", and the photo comes selected.
            onRoot().performTouchInput { click(Offset(6f, r.center.y)) }
            waitForIdle()
            tool("Photo").performClick()
            onNodeWithText("Add a photo").assertExists()
            onAllNodes(clickLabel("Add this photo")).onFirst().performClick()
            waitForIdle()
            onNodeWithContentDescription("Photo, selected").assertExists()
            savedWhen(b) { d -> added(d).any { it.isPhoto } }
        }
        assertAndroidForm(b)
    }

    @Test
    fun aPhotosToolsReplaceFrameAndCaptionItForTheBookOnly() {
        val b = editorBook()
        val i = b.index(Template.PhotoText)
        val id = b.page(i).id
        val photo = b.elements(i).first { it.isPhoto }
        val was = b.page(i).slot(0).mediaId
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, i, EditorStart({ photo.id }, Tool.Replace))
            onNodeWithText("Remove").assertExists()
            // Another of the trip's photos in its place (a template's spot keeps it in the page's slots).
            val other = onAllNodes(clickLabel("Use this photo")).fetchSemanticsNodes().size
            assertTrue(other > 1)
            onAllNodes(clickLabel("Use this photo"))[1].performClick()
            savedWhen(b) { page(it, id).slot(0).mediaId != was }
            onNodeWithContentDescription("Done").performClick()
            // A caption for the book, 120 letters at most.
            tool("Caption").performClick()
            onNodeWithText("For the book only. The journal stays as it is.").assertExists()
            onNode(hasSetTextAction()).performTextInput("x".repeat(130))
            assertEquals(120, page(savedWhen(b) { page(it, id).slot(0).caption.isNotEmpty() }, id).slot(0).caption.length)
            onNodeWithContentDescription("Done").performClick()
            // Frame: the white border off (the page is arranged by hand then).
            tool("Frame").performClick()
            onNodeWithText("None").performClick()
            assertEquals(FreeLayout.FLAT, page(savedWhen(b) { page(it, id).el(photo.id)?.style == FreeLayout.FLAT }, id).el(photo.id)?.style)
            // Crop: the frame to drag and pinch in.
            tool("Crop").performClick()
            assertTrue(
                onAllNodesWithText("Drag to move it, pinch to zoom.").fetchSemanticsNodes().isNotEmpty() ||
                    onAllNodesWithText("This photo may print soft at this size.").fetchSemanticsNodes().isNotEmpty(),
            )
        }
        assertAndroidForm(b)
    }

    @Test
    fun wordsTheirFontSizeAlignAndThePagesOwnText() {
        val b = editorBook()
        val deco = b.index(DECO)
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, deco, EditorStart({ "deco-text" }, Tool.Font))
            onNodeWithText("Titles").assertExists()
            onNodeWithText("Text").assertExists()
            listOf("Caveat", "Kalam", "Architects", "Gochi Hand", "Literata", "Source Serif", "Lora", "Albert Sans", "Work Sans", "Barlow", "Barlow Condensed", "Plex Mono", "Courier", "JetBrains")
                .forEach { onNodeWithText(it).performScrollTo().assertExists() }
            onNodeWithText("Lora").performScrollTo().performClick()
            onNodeWithText("Italic").performScrollTo().performClick()
            onNodeWithText("Highlight").performScrollTo().performClick()
            val styled = assertNotNull(page(savedWhen(b) { page(it, DECO).el("deco-text")?.mark == true }, DECO).el("deco-text"))
            assertEquals("Lora" to true, styled.font to styled.italic)
            onNodeWithContentDescription("Done").performClick()
            tool("Size").performClick()
            listOf("Tight", "Normal", "Wide", "Double").forEach { onNodeWithText(it).assertExists() }
            onNodeWithText("Double").performClick()
            assertEquals(2.0, assertNotNull(page(savedWhen(b) { page(it, DECO).el("deco-text")?.lh == 2.0 }, DECO).el("deco-text")).lh)
            onNodeWithContentDescription("Done").performClick()
            tool("Align").performClick()
            onNodeWithText("Right").performClick()
            savedWhen(b) { page(it, DECO).el("deco-text")?.align == "right" }
            onNodeWithContentDescription("Done").performClick()
            tool("Colour").performClick()
            onNodeWithContentDescription("Theme colour").assertExists()
            onAllNodesWithContentDescription("Colour #", substring = true).onFirst().performClick()
            savedWhen(b) { page(it, DECO).el("deco-text")?.color != null }
            onNodeWithContentDescription("Done").performClick()
            tool("Edit").performClick()
            onNode(hasSetTextAction()).performTextInput(" and back")
            savedWhen(b) { page(it, DECO).el("deco-text")?.text?.contains(" and back") == true }
        }
        // The page's own text, per note: kept when the panel closes, what doesn't fit moves on.
        val i = b.index(Template.PhotoText)
        val id = b.page(i).id
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, i, EditorStart({ els -> els.first { it.isFlow }.id }, Tool.Edit))
            onNodeWithText("Page text").assertExists()
            onNodeWithText("For the book only; the journal stays as it is. Text that doesn't fit moves to the next page.").performScrollTo().assertExists()
            onAllNodes(hasSetTextAction()).onLast().performTextInput("Written for the book. ")
            onNodeWithContentDescription("Done").performClick()
            savedWhen(b) { d -> page(d, id).paras.any { "Written for the book." in it.html } }
        }
        assertAndroidForm(b)
    }

    @Test
    fun shapesStickersAndArrange() {
        val b = editorBook()
        val deco = b.index(DECO)
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, deco, EditorStart({ "deco-box" }, Tool.Style))
            onNodeWithText("Circle").performClick()
            onNodeWithText("FILL").assertExists()
            onNodeWithContentDescription("No fill").performClick()
            onNodeWithContentDescription("No outline").assertExists()
            val s = assertNotNull(page(savedWhen(b) { page(it, DECO).el("deco-box")?.fill == null }, DECO).el("deco-box"))
            assertEquals("oval", s.style)
            onNodeWithContentDescription("Done").performClick()
            // Arrange: turn by +5°, nudge right 0.5 mm, a copy 5 mm down and right, delete it.
            tool("Rotate").performClick()
            listOf("−15°", "−5°", "0°", "+5°", "+15°").forEach { onAllNodesWithText(it).onFirst().assertExists() }
            onNodeWithText("Nudge · 0.5 mm".uppercase()).assertExists()
            onNodeWithText("+5°").performClick()
            savedWhen(b) { page(it, DECO).el("deco-box")?.rot == 5.0 }
            val x = page(saved(b), DECO).el("deco-box")!!.box.x
            onNodeWithText("Right").performClick()
            savedWhen(b) { near(page(it, DECO).el("deco-box")!!.box.x, x + 0.5, 0.001) }
            onNodeWithContentDescription("Done").performClick()
            val count = page(saved(b), DECO).els.size
            tool("Copy").performClick()
            val withCopy = savedWhen(b) { page(it, DECO).els.size == count + 1 }
            val box = page(withCopy, DECO).el("deco-box")!!
            val copy = page(withCopy, DECO).els[page(withCopy, DECO).els.indexOfFirst { it.id == "deco-box" } + 1]
            assertTrue(near(copy.box.x, box.box.x + FreeEdit.COPY_OFFSET) && near(copy.box.y, box.box.y + FreeEdit.COPY_OFFSET), "${copy.box}")
            tool("Delete").performClick()
            savedWhen(b) { page(it, DECO).els.size == count }
        }
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, deco, EditorStart({ "deco-line" }, Tool.Style))
            listOf("Thin", "Medium", "Thick").forEach { onNodeWithText(it).assertExists() }
            onNodeWithText("Thick").performClick()
            savedWhen(b) { page(it, DECO).el("deco-line")?.stroke == 1.2 }
        }
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, deco, EditorStart({ "deco-flag" }, Tool.Style))
            onNodeWithText("Sticker").assertExists()
            onNode(hasSetTextAction()).performTextInput("zz")
            onNodeWithText("No flag drawn for this one yet; its letters print instead.").assertExists()
            onNodeWithText("Flat").performClick()
            val flag = assertNotNull(page(savedWhen(b) { page(it, DECO).el("deco-flag")?.style == FreeLayout.FLAT }, DECO).el("deco-flag"))
            assertEquals(listOf("ZZ"), flag.codes)
        }
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, deco, EditorStart({ "deco-tape" }, Tool.Style))
            val colours = FreeEdit.tapeColors(b.layout.pages)
            onNodeWithContentDescription("Colour ${colours.last()}").performScrollTo().performClick()
            savedWhen(b) { page(it, DECO).el("deco-tape")?.color == colours.last() }
        }
        assertAndroidForm(b)
    }

    @Test
    fun addPhotosFillsTheEmptySpotsAndOffersRoomForTheRest() {
        val b = editorBook()
        val i = b.index(TWO)
        val count = b.doc.pages.size
        runDesktopComposeUiTest(width = 440, height = 956) {
            open(b, i)
            onNode(clickLabel("Add photos")).performClick()
            onNodeWithText("Not in book").assertExists()
            // Three picked in order (numbered): two fill the spots, one has no room.
            repeat(3) { onAllNodesWithContentDescription("Photo, in the book").onFirst().performClick() }
            onNodeWithContentDescription("Photo, picked 3, in the book").assertExists()
            onNodeWithText("Add 3 photos").performClick()
            onNodeWithText("No room for 1 photo").assertExists()
            onNodeWithText("2 photos went into the empty spots. One doesn't fit on this page.").assertExists()
            onNodeWithText("Or start new pages after this one, in a layout:").assertExists()
            val one = AlbumLayouts.forCount(1).first()
            onNode(clickLabel("${one.name}, 1 photo")).performScrollTo().performClick()
            val d = savedWhen(b) { it.pages.size == count + 1 }
            assertEquals(2, page(d, TWO).slots.count { it.mediaId != null })
            assertEquals(Template.Album, d.pages[d.indexOf(TWO) + 1].template)
        }
        assertAndroidForm(b)
    }

    @Test
    fun undoKeepsThirtyStepsAndALiveVisitIsOneOfThem() = runBlocking {
        val b = editorBook()
        val scope = CoroutineScope(coroutineContext + Job())
        val vm = BookViewModel(BookExports(b.book, b.kit.files, b.kit.frames, NoPagePrinter), trip, { com.justdataplease.boringtravel.model.PrintTheme.Notebook }, scope)
        withTimeout(60_000) { vm.state.first { !it.loading } }
        val first = vm.state.value.doc
        // A panel's visit: many changes shown live, one step.
        repeat(5) { n -> vm.live { d -> com.justdataplease.boringtravel.book.BookEdit.update(d, DECO) { it.copy(bg = PageBg(PageBg.COLOR, color = "#00000$n")) } } }
        vm.endLive()
        assertEquals("#000004", page(vm.state.value.doc, DECO).bg?.color)
        vm.undo()
        assertEquals(first, vm.state.value.doc)
        assertFalse(vm.state.value.canUndo)
        // A visit that changed nothing leaves no step.
        vm.live { it }
        vm.endLive()
        assertFalse(vm.state.value.canUndo)
        // Thirty-five steps; thirty come back.
        repeat(35) { n -> vm.edit { d -> com.justdataplease.boringtravel.book.BookEdit.update(d, DECO) { it.copy(bg = PageBg(PageBg.COLOR, color = "#0000" + n.toString().padStart(2, '0'))) } } }
        var undone = 0
        while (vm.state.value.canUndo) { vm.undo(); undone++ }
        assertEquals(30, undone)
        assertEquals("#000004", page(vm.state.value.doc, DECO).bg?.color)
        scope.cancel()
    }

    @Test
    fun thePageFollowsTheFingerThroughItsScripts() {
        // The live move WebKit runs while the finger moves (Android's own scripts, read in): the element by its handle,
        // its own transform kept to put back.
        val m = LiveMove("t1", 2.5, -1.25, 15.0, 1.1, 0.9)
        val s = moveScript(m)
        assertTrue("document.querySelector('[data-fe=\"t1\"]')" in s)
        assertTrue("translate(2.500mm,-1.250mm) rotate(15.000deg) scale(1.100,0.900)" in s, s)
        assertTrue("data-t0" in resetScript("t1"))
        // The page's elements carry those handles in the editor's HTML.
        val b = editorBook()
        val i = b.index(Template.PhotoText)
        val html = com.justdataplease.boringtravel.book.BookHtml.page(b.layout, b.doc, b.page(i).id, { _, _ -> null }, 1.0, fontBase = "fonts/")
        b.elements(i).filter { !it.hidden }.forEach { e -> assertTrue("data-fe=\"${e.id}\"" in html, e.id) }
    }
}
