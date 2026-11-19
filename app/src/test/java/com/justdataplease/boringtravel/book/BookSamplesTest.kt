package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.album.AlbumLayouts
import com.justdataplease.boringtravel.export.ExportOptions
import com.justdataplease.boringtravel.export.TripHtml
import com.justdataplease.boringtravel.model.PrintTheme
import org.junit.Test
import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.File
import java.util.Locale

/**
 * Writes the sample trip as books (every page as its own HTML at the page's size, plus the whole book on a grey desk)
 * when BT_BOOK_SAMPLES names a folder, for checking in a browser or rendering pages to images. Text is measured with
 * the bundled fonts through AWT, as the phone measures them. BT_BOOK_PHOTOS is the folder (URL) holding the sample
 * photos, BT_EXPORT_FONTS the fonts folder (URL).
 */
class BookSamplesTest {
    /** Measures with the real font files (the variable fonts' default instance; bold is a little wider). */
    private class AwtMeasure(dir: File) : TextMeasure {
        private val frc = FontRenderContext(null, true, true)
        private val fonts = HashMap<Face, Font>()
        private val base = dir

        override fun widthMm(text: String, face: Face, pt: Double, bold: Boolean): Double {
            val f = fonts.getOrPut(face) { Font.createFont(Font.TRUETYPE_FONT, File(base, "${face.file}.ttf")).deriveFont(100f) }
            val em = f.getStringBounds(text, frc).width / 100.0
            return em * (if (bold && face in setOf(Face.Caveat, Face.Literata, Face.Albert, Face.LiterataItalic)) 1.04 else 1.0) * pt * TextFit.PT_MM
        }
    }

    /** A few pages changed by hand the way the editor changes them. */
    private fun handMade(l: BookLayout, base: BookDoc): BookDoc {
        val pl = l.pages
        var doc = base
        // Two photos: the first turned and moved, words, tape, the stamp and a flag added.
        val two = doc.pages.first { it.templateId == Template.PhotoTwo.id }
        val n2 = l.number(doc, two.id)
        val first = pl.free.elements(two, n2).first { it.isPhoto }
        doc = BookEdit.update(doc, two.id) { p ->
            var q = FreeEdit.move(p, pl, n2, first.id, first.box.offset(-4.0, 6.0), -8.0)
            q = FreeEdit.add(q, FreeEdit.newText(pl, "w1").copy(text = "Kolymbithres,\nbetter at six", box = Box(20.0, 150.0, 80.0, 10.0), rot = 4.0, color = "#C4423A"))
            q = FreeEdit.add(q, FreeEdit.newTape(pl, "tp").copy(box = Box(88.0, 120.0, 34.0, 9.0), rot = 35.0))
            q = FreeEdit.add(q, FreeEdit.newStamp(pl, "st").copy(box = Box(104.0, 160.0, 26.0, 26.0)))
            q = FreeEdit.add(q, FreeEdit.newFlag(pl, "fg", "GR").copy(box = Box(24.0, 178.0, 14.0, 9.0)))
            FreeEdit.add(q, FreeEdit.newShape(pl, "sh", oval = true).copy(box = Box(70.0, 176.0, 20.0, 20.0), fill = null, color = "#1E2A5A", stroke = 0.5))
        }
        // A plain colour page with big words and a line.
        val words = BookPage("new-words", Template.Notes.id, bg = PageBg(PageBg.COLOR, color = "#1E2A5A"), els = listOf(
            FreeEl("t", FreeKind.TEXT, Box(14.0, 70.0, 120.0, 30.0), text = "Ten days,\nthree islands", font = "Caveat", pt = 40.0, lh = 1.1, align = "center", color = "#FFFFFF", bold = true),
            FreeEl("l", FreeKind.LINE, Box(44.0, 118.0, 60.0, 4.0), color = "#F7E36A", stroke = 0.8),
            FreeEl("s", FreeKind.TEXT, Box(14.0, 126.0, 120.0, 8.0), text = "PAROS · NAXOS · AMORGOS", font = "PlexMono", pt = 8.0, align = "center", color = "#F7E36A"),
        ))
        // A photo to the edges, dimmed, with words over it and photo corners round a second photo.
        val over = BookPage("new-over", Template.Notes.id, bg = PageBg(PageBg.PHOTO, photo = Slot("chora"), dim = 0.35), els = listOf(
            FreeEl("p", FreeKind.PHOTO, Box(30.0, 40.0, 88.0, 59.0), -3.0, photo = Slot("portara2"), style = FreeLayout.FLAT),
            FreeEl("c", FreeKind.CORNERS, Box(30.0, 40.0, 88.0, 59.0), -3.0, color = "#FFFFFF"),
            FreeEl("t", FreeKind.TEXT, Box(14.0, 150.0, 120.0, 14.0), text = "Naxos", font = "display", pt = 34.0, align = "center", color = "#FFFFFF"),
        ))
        doc = BookEdit.insert(doc, 3, words)
        doc = BookEdit.insert(doc, 4, over)
        // The new templates.
        listOf(Template.PhotoFour, Template.PhotoTextSide, Template.PhotoFullCaption, Template.PhotoThreeUp, Template.PhotoTwoSide).forEachIndexed { i, t ->
            val ids = BookFixtures.photoSizes.keys.toList()
            val page = BookPage("new-t$i", t.id, slots = List(t.photos) { j -> Slot(ids[(i * 3 + j) % ids.size], caption = if (j == 0) "Caption ${j + 1}" else "") },
                entryId = if (t.text) "e5" else null, day = BookFixtures.start.plusDays(3),
                paras = if (t.text) listOf(Para(ParaKind.TITLE, "The bus to Halki", "e5"), Para(ParaKind.P, BookFixtures.longWalk[0], "e5"), Para(ParaKind.P, BookFixtures.longWalk[1], "e5")) else emptyList())
            doc = BookEdit.insert(doc, 5 + i, page)
        }
        return l.reflow(doc)
    }

    @Test fun writeSamplesWhenAsked() {
        val dir = System.getenv("BT_BOOK_SAMPLES")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val fontsUrl = System.getenv("BT_EXPORT_FONTS") ?: TripHtml.FONT_BASE
        val photos = System.getenv("BT_BOOK_PHOTOS") ?: "photos/"
        val measure = AwtMeasure(File("src/main/res/font"))
        val book = BookFixtures.book()
        val facts = BookFacts(book, Locale.UK)
        val src: (String, Int) -> String? = { id, _ -> "$photos$id.jpg" }
        val index = StringBuilder()
        val variants = listOf(
            "notebook-a5" to BookSpec(BookFormat.A5, PrintTheme.Notebook),
            "notebook-a5-ruled" to BookSpec(BookFormat.A5, PrintTheme.Notebook, paper = BookPaper.Lines),
            "notebook-a4" to BookSpec(BookFormat.A4, PrintTheme.Notebook),
            "notebook-sq-bleed" to BookSpec(BookFormat.Square, PrintTheme.Notebook, bleed = true),
            "postcard-a5" to BookSpec(BookFormat.A5, PrintTheme.Postcard),
            "postcard-a4" to BookSpec(BookFormat.A4, PrintTheme.Postcard),
            "scrapbook-a5" to BookSpec(BookFormat.A5, PrintTheme.Scrapbook),
            "minimal-a5" to BookSpec(BookFormat.A5, PrintTheme.Minimal),
        )
        variants.forEach { (name, spec) ->
            val l = BookLayout(spec, facts, measure, BookFixtures.ids())
            val doc = l.auto()
            val out = File(dir, name).apply { mkdirs() }
            File(out, "book.html").writeText(BookHtml.document(l, doc, src, BookHtml.Mode.Desk, fontsUrl))
            val printed = l.printed(doc)
            printed.forEachIndexed { i, p ->
                val f = File(out, "page-%02d.html".format(i + 1))
                f.writeText(BookHtml.document(l, doc, src, BookHtml.Mode.Print, fontsUrl, listOf(p), i + 1))
                index.append(name).append('\t').append(f.name).append('\t').append(p.templateId).append('\t')
                    .append(l.pages.sheet.pageW).append('\t').append(l.pages.sheet.pageH).append('\n')
            }
            File(out, "print.html").writeText(BookHtml.document(l, doc, src, BookHtml.Mode.Print, fontsUrl))
        }
        // Pages arranged by hand: elements moved and turned, things added, backgrounds, the new templates.
        listOf("free-notebook" to BookSpec(BookFormat.A5, PrintTheme.Notebook), "free-postcard" to BookSpec(BookFormat.A5, PrintTheme.Postcard)).forEach { (name, spec) ->
            val l = BookLayout(spec, facts, measure, BookFixtures.ids())
            val doc = handMade(l, l.auto())
            val out = File(dir, name).apply { mkdirs() }
            val printed = l.printed(doc).filter { it.free || it.els.isNotEmpty() || it.bg != null || it.id.startsWith("new-") }
            printed.forEachIndexed { i, p ->
                val f = File(out, "page-%02d.html".format(i + 1))
                f.writeText(BookHtml.document(l, doc, src, BookHtml.Mode.Print, fontsUrl, listOf(p), l.number(doc, p.id)))
                index.append(name).append('\t').append(f.name).append('\t').append(p.templateId).append('\t')
                    .append(l.pages.sheet.pageW).append('\t').append(l.pages.sheet.pageH).append('\n')
            }
        }
        // Album pages: every arrangement, in a theme with prints on a tall page and a flat one on a square page.
        listOf("album-notebook-a5" to BookSpec(BookFormat.A5, PrintTheme.Notebook), "album-postcard-sq" to BookSpec(BookFormat.Square, PrintTheme.Postcard)).forEach { (name, spec) ->
            val l = BookLayout(spec, facts, measure, BookFixtures.ids())
            val ids = BookFixtures.photoSizes.keys.toList()
            val pages = AlbumLayouts.all.mapIndexed { i, a ->
                BookPage(
                    "album-${a.id}", Template.Album.id, album = a.id, day = BookFixtures.start.plusDays(3),
                    slots = List(a.count) { j -> Slot(ids[(i + j) % ids.size], caption = if (j == 0 && i % 3 == 0) "Halki at noon" else "") },
                )
            }
            val doc = BookDoc(pages = pages, padTo4 = false)
            val out = File(dir, name).apply { mkdirs() }
            pages.forEachIndexed { i, p ->
                val f = File(out, "page-%02d.html".format(i + 1))
                f.writeText(BookHtml.document(l, doc, src, BookHtml.Mode.Print, fontsUrl, listOf(p), i + 3))
                index.append(name).append('\t').append(f.name).append('\t').append(p.album).append('\t')
                    .append(l.pages.sheet.pageW).append('\t').append(l.pages.sheet.pageH).append('\n')
            }
            // As the editor shows a page with empty spots: a "+" to tap in each.
            val gaps = BookPage("album-gaps", Template.Album.id, album = "6-big", slots = listOf(Slot(ids[0]), Slot(), Slot(ids[2]), Slot(), Slot(), Slot(ids[5])))
            File(out, "editor-empty.html").writeText(BookHtml.page(l, BookDoc(pages = listOf(gaps), padTo4 = false), gaps.id, src, 1.0, fontsUrl))
        }
        File(dir, "pages.tsv").writeText(index.toString())
        // The flowing PDF export in the Notebook theme, dotted and ruled.
        listOf(false, true).forEach { ruled ->
            val html = TripHtml.build(book, ExportOptions(theme = PrintTheme.Notebook, includePlan = false, ruled = ruled), { m -> "$photos${m.id}.jpg" }, locale = Locale.UK, fontBase = fontsUrl)
            File(dir, if (ruled) "export-notebook-ruled.html" else "export-notebook.html").writeText(html)
        }
    }
}
