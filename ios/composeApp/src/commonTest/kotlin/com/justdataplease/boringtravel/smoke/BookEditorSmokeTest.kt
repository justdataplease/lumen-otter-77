package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.R
import com.justdataplease.boringtravel.book.BookEdit
import com.justdataplease.boringtravel.book.BookFacts
import com.justdataplease.boringtravel.book.BookFormat
import com.justdataplease.boringtravel.book.BookHtml
import com.justdataplease.boringtravel.book.BookJson
import com.justdataplease.boringtravel.book.BookLayout
import com.justdataplease.boringtravel.book.BookSpec
import com.justdataplease.boringtravel.book.Face
import com.justdataplease.boringtravel.book.FreeEdit
import com.justdataplease.boringtravel.book.FreeKind
import com.justdataplease.boringtravel.book.FreeLayout
import com.justdataplease.boringtravel.book.FreeMath
import com.justdataplease.boringtravel.book.Layers
import com.justdataplease.boringtravel.book.P2
import com.justdataplease.boringtravel.book.PageBg
import com.justdataplease.boringtravel.book.Template
import com.justdataplease.boringtravel.book.TextFit
import com.justdataplease.boringtravel.book.fontRes
import com.justdataplease.boringtravel.compat.Locale
import com.justdataplease.boringtravel.compat.graphics.AndroidColor
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.ui.book.AddKind
import com.justdataplease.boringtravel.ui.book.FACE_CHOICES
import com.justdataplease.boringtravel.ui.book.LiveMove
import com.justdataplease.boringtravel.ui.book.Tool
import com.justdataplease.boringtravel.ui.book.backgroundColors
import com.justdataplease.boringtravel.ui.book.degrees
import com.justdataplease.boringtravel.ui.book.elementLabel
import com.justdataplease.boringtravel.ui.book.faceLabel
import com.justdataplease.boringtravel.ui.book.keepsAspect
import com.justdataplease.boringtravel.ui.book.mainTool
import com.justdataplease.boringtravel.ui.book.moveScript
import com.justdataplease.boringtravel.ui.book.resetScript
import com.justdataplease.boringtravel.ui.studio.groupLabel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The book's page editor (round 22), read in from the Android app (ui/book/PageEditor.kt, PageCanvas.kt, ToolPanels.kt,
 * LayerList.kt) with the editing steps under it (book/FreeEdit.kt, FreeMath.kt, Layers.kt), on a made-up trip: the live
 * move's script as WebKit gets it (numbers as Java writes them), the page's elements and their handles in the editor's
 * HTML, a page turned "arranged by hand" by a move and kept in Android's JSON, snapping, resizing and turning, the
 * layers' words with their minus sign and degree, the tools' names, the colours' checks (a regular expression), the
 * country code typed for a flag, and every face's bundled font file. Run on the desktop and on the iPhone Simulator,
 * where Kotlin/Native's regular expressions and text functions are the ones used.
 */
class BookEditorSmokeTest {
    private val facts = BookFacts(Samples.book(), Locale.UK)
    private fun layout(theme: PrintTheme = PrintTheme.Notebook): BookLayout {
        var n = 0
        return BookLayout(BookSpec(BookFormat.A5, theme), facts, TextFit.Estimate) { "p${++n}" }
    }

    @Test fun theLiveMoveIsWrittenAsJavaWritesItsNumbers() {
        val s = moveScript(LiveMove("t2", 1.0 / 3, -12.5, -7.25, 1.0, 0.5))
        assertTrue("document.querySelector('[data-fe=\"t2\"]')" in s, s)
        assertTrue("translate(0.333mm,-12.500mm) rotate(-7.250deg) scale(1.000,0.500)" in s, s)
        assertTrue("e.style.transformOrigin='50% 50%'" in s)
        val r = resetScript("t2")
        assertTrue("[data-fe=\"t2\"]" in r && "removeAttribute('data-t0')" in r, r)
    }

    @Test fun aMoveArrangesThePageByHandAndIsKeptInAndroidsForm() {
        val l = layout()
        val doc = l.auto()
        val page = doc.pages.first { it.template == Template.PhotoText }
        val n = l.number(doc, page.id)
        val els = l.pages.free.elements(page, n)
        // The editor's page carries every element's handle for the live move.
        val html = BookHtml.page(l, doc, page.id, { id, _ -> "img/$id.jpg" }, 1.5, fontBase = "fonts/")
        els.forEach { e -> assertTrue("data-fe=\"${e.id}\"" in html, e.id) }
        assertTrue(Regex("<div data-fe=\"[^\"]+\"").findAll(html).count() >= els.size)
        // A move turns the page free; everything else stays where it was.
        val photo = els.first { it.isPhoto }
        val moved = FreeEdit.move(page, l.pages, n, photo.id, photo.box.offset(5.0, 7.0), 12.0)
        assertTrue(moved.free && !page.free)
        val now = l.pages.free.elements(moved, n)
        assertEquals(els.map { it.id }, now.map { it.id })
        assertEquals(12.0, now.first { it.id == photo.id }.rot)
        // Kept and read back the same (the `books` row a backup carries).
        val edited = BookEdit.update(doc, page.id) { moved }
        val json = BookJson.encode(edited)
        assertEquals(edited.page(page.id)?.els?.map { it.id }, BookJson.decode(json).page(page.id)?.els?.map { it.id })
        assertEquals(json, BookJson.encode(BookJson.decode(json)))
        assertTrue("\"free\":true" in json, json.take(400))
    }

    @Test fun snappingResizingAndTurning() {
        val l = layout()
        val w = BookFormat.A5.widthMm
        val h = BookFormat.A5.heightMm
        val lines = FreeMath.snapLines(w, h, l.pages.grid.content, emptyList())
        val box = com.justdataplease.boringtravel.book.Box(w / 2 - 20 + 1.5, 40.0, 40.0, 30.0)
        val s = FreeMath.snap(box, lines, 3.0)
        assertEquals(-1.5, s.dx, 1e-9)
        assertTrue(s.guides.any { it.vertical && it.at == w / 2 })
        // A corner keeps a photo's shape, never under 4 mm; a turn snaps to eighths.
        val r = FreeMath.resize(box, 0.0, 2, P2(box.right - 10, box.bottom - 2), keepAspect = true)
        assertEquals(box.w / box.h, r.w / r.h, 1e-9)
        val tiny = FreeMath.resize(box, 0.0, 2, P2(box.x - 50, box.y - 50), keepAspect = false)
        assertEquals(4.0 to 4.0, tiny.w to tiny.h)
        assertEquals(45.0, FreeMath.snapAngle(42.5))
        assertEquals(-30.0, FreeMath.snapAngle(-30.0))
        assertEquals(6.0, FreeMath.keepOnPage(box.offset(-500.0, 0.0), w, h).right, 1e-9)
        assertTrue(abs(FreeMath.angleTo(box, P2(box.cx + 10, box.cy)) - 90.0) < 1e-9)
    }

    @Test fun theLayersAndToolsSayWhatTheyAre() {
        val l = layout()
        val pl = l.pages
        val text = FreeEdit.newText(pl, "x1").copy(text = "\n  Fitz Roy at dawn\nsecond line", rot = -3.4)
        assertEquals("Fitz Roy at dawn", Layers.name(text, facts))
        assertEquals("Text · Hand, −3°", Layers.detail(text) { pl.free.face(it) })
        assertEquals("+12°", degrees(12.2))
        assertEquals("0°", degrees(0.3))
        assertEquals("Flag GR", Layers.name(FreeEdit.newFlag(pl, "f", "gr"), facts))
        assertEquals("Text", elementLabel(text))
        assertEquals("Circle", elementLabel(FreeEdit.newShape(pl, "c", oval = true)))
        assertEquals(Tool.Edit, mainTool(text))
        assertEquals(Tool.AddPhotos("p"), mainTool(FreeEdit.newPhoto(pl, "p", "m1").let { it.copy(photo = it.photo?.copy(mediaId = null)) }))
        assertTrue(keepsAspect(FreeEdit.newPhoto(pl, "p", "m1")))
        assertFalse(keepsAspect(text))
        assertEquals("Photo · The harbour a…", groupLabel("Photo", "The harbour at dawn"))
        assertEquals("Photo", groupLabel("Photo", "photo"))
        assertEquals(11, AddKind.entries.size)
        assertEquals("Washi tape", AddKind.Tape.label)
        assertEquals(listOf("Background · the theme's"), listOf(Layers.background(null, l.spec.paper, facts).second))
        assertEquals("Background photo, dimmed", Layers.background(PageBg(PageBg.PHOTO, photo = com.justdataplease.boringtravel.book.Slot("m1"), dim = 0.3), null, facts).second)
    }

    @Test fun coloursAreCheckedAndAFlagsCodeIsTwoLetters() {
        val pl = layout(PrintTheme.Scrapbook).pages
        val colours = backgroundColors(pl)
        assertTrue(colours.all { Regex("^#[0-9A-F]{6}$").matches(it) }, "$colours")
        assertEquals(colours.distinct(), colours)
        assertTrue(FreeEdit.palette(pl).all { it.length == 7 && it == it.uppercase() })
        assertEquals("#F2DE6B", FreeEdit.tapeColors(pl).first())
        // The flag panel's field: letters only, two, in capitals.
        assertEquals("GR", "g-r9e".filter { it.isLetter() }.take(2).uppercase())
        assertEquals("ÉS", "és".filter { it.isLetter() }.take(2).uppercase())
        assertEquals(0xC81E1E22.toInt(), AndroidColor.argb(200, 30, 30, 34))
        assertEquals(-1, AndroidColor.WHITE)
    }

    @Test fun everyFaceHasItsFontFile() {
        Face.entries.forEach { f -> assertEquals("${f.file}.ttf", R.fontFiles[f.fontRes()], f.name) }
        assertEquals(14, FACE_CHOICES.size)
        assertEquals("Gochi Hand", faceLabel(Face.Gochi))
        assertNotNull(FACE_CHOICES.firstOrNull { it == Face.BarlowCondensed })
        assertTrue(FreeLayout.BLEED != FreeLayout.PRINT && FreeKind.DECO.contains(FreeKind.TAPE))
    }
}
