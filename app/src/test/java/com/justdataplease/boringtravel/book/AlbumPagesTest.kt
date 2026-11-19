package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.album.AlbumLayouts
import com.justdataplease.boringtravel.model.PrintTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Album pages in the book: where their photos go, photos moved into a layout, overflow, adding several photos, new pages. */
class AlbumPagesTest {
    private val facts = BookFixtures.facts()
    private val ids = BookFixtures.photoSizes.keys.toList()

    private fun layout(spec: BookSpec = BookSpec()) = BookLayout(spec, facts, TextFit.Estimate, BookFixtures.ids())

    private fun albumPage(id: String, album: String, photos: List<String?>, caption: (Int) -> String = { "" }) =
        BookPage(id, Template.Album.id, slots = photos.mapIndexed { i, m -> Slot(m, caption = caption(i)) }, album = album, day = BookFixtures.start.plusDays(3))

    private fun counter(prefix: String = "n"): () -> String {
        var n = 0
        return { "$prefix${++n}" }
    }

    private fun r(b: Box) = listOf(b.x, b.y, b.w, b.h).map { Math.round(it * 1e5) / 1e5 }

    private fun overlap(a: Box, b: Box) = a.x < b.right - 1e-6 && b.x < a.right - 1e-6 && a.y < b.bottom - 1e-6 && b.y < a.bottom - 1e-6

    private fun inside(inner: Box, outer: Box) =
        inner.x >= outer.x - 1e-6 && inner.y >= outer.y - 1e-6 && inner.right <= outer.right + 1e-6 && inner.bottom <= outer.bottom + 1e-6

    @Test fun everyLayoutPlacesItsPhotosInsideTheMarginsWithoutOverlapsOnEverySizeAndTheme() {
        PrintTheme.entries.forEach { theme ->
            BookFormat.entries.forEach { format ->
                listOf(false, true).forEach { bleed ->
                    val l = layout(BookSpec(format = format, theme = theme, bleed = bleed))
                    val c = l.pages.grid.content
                    AlbumLayouts.all.forEach { a ->
                        val where = "$theme $format ${a.id}"
                        val page = albumPage("a", a.id, List(a.count) { ids[it % ids.size] }) { if (it == 0) "First" else "" }
                        val photos = l.pages.plan(page, 5).photos
                        assertEquals(where, a.count, photos.size)
                        assertEquals(where, (0 until a.count).toList(), photos.map { it.slot })
                        assertEquals(where, List(a.count) { ids[it % ids.size] }, photos.map { it.mediaId })
                        val cells = l.pages.albumCells(a)
                        photos.forEachIndexed { i, p ->
                            assertTrue(where, inside(p.frame, c))
                            assertTrue(where, inside(p.frame, cells[i]))
                            assertTrue(where, inside(p.image, p.frame))
                            assertEquals(where, 0.0, p.tilt, 0.0)
                            assertFalse(where, p.empty)
                        }
                        for (i in photos.indices) for (j in i + 1 until photos.size) {
                            assertFalse("$where $i/$j", overlap(photos[i].frame, photos[j].frame))
                            // Cells of one size give photos of one size, captioned or not.
                            if (Math.abs(cells[i].h - cells[j].h) < 1e-6) assertEquals("$where $i/$j", photos[i].frame.h, photos[j].frame.h, 1e-6)
                        }
                        // A caption under a photo stays in its cell, clear of the others.
                        val cap = l.pages.plan(page, 5).els.filterIsInstance<TextEl>().firstOrNull { it.capOf == 0 }
                        if (cap != null) {
                            assertTrue(where, inside(cap.box, cells[0]))
                            photos.forEach { p -> assertFalse(where, overlap(cap.box, p.frame)) }
                        }
                    }
                }
            }
        }
    }

    @Test fun albumPagesLookTheSameWhenTurnedFree() {
        PrintTheme.entries.forEach { theme ->
            listOf(BookFormat.A5, BookFormat.Square).forEach { format ->
                val l = layout(BookSpec(format = format, theme = theme))
                AlbumLayouts.all.forEach { a ->
                    val page = albumPage("a", a.id, List(a.count) { if (it == 1) null else ids[it % ids.size] }) { if (it == 0) "Halki" else "" }
                    val before = l.pages.plan(page, 4)
                    val frozen = FreeEdit.freeze(page, l.pages, 4)
                    val after = l.pages.plan(frozen, 4)
                    assertEquals("$theme ${a.id}", before.ids, after.ids)
                    before.els.zip(after.els).forEach { (x, y) ->
                        if (x is PhotoEl && y is PhotoEl) {
                            assertEquals("$theme ${a.id}", listOf(x.mediaId, r(x.frame), r(x.image), x.tilt, x.captionText, x.captionInFrame, x.tapes, x.empty),
                                listOf(y.mediaId, r(y.frame), r(y.image), y.tilt, y.captionText, y.captionInFrame, y.tapes, y.empty))
                        }
                        // Words start in the same place (a caption's box may grow to fit its words once placed by hand).
                        if (x is TextEl && y is TextEl) assertEquals("$theme ${a.id}", listOf(x.text, r(x.box).take(3)), listOf(y.text, r(y.box).take(3)))
                    }
                    assertEquals(before.els.map { it::class }, after.els.map { it::class })
                }
            }
        }
    }

    @Test fun photosMoveIntoANewLayoutInTheirOrderAndNoneIsLost() {
        val l = layout()
        val four = BookPage("p", Template.PhotoFour.id, slots = listOf(Slot("ferry", caption = "Blue Star"), Slot("chora"), Slot("beach"), Slot("hozo")))
        val two = AlbumEdit.apply(four, "2-side")
        assertEquals(Template.Album, two.template)
        assertEquals("2-side", two.album)
        assertEquals(2, two.spots)
        assertEquals(listOf("ferry", "chora"), l.pages.plan(two, 5).photos.map { it.mediaId })
        assertEquals("Blue Star", two.slot(0).caption)
        assertEquals(listOf("beach", "hozo"), AlbumEdit.extras(two).map { it.mediaId })
        // A bigger layout shows them again, with its extra spot empty after them.
        val six = AlbumEdit.apply(two, "6-big")
        assertEquals(listOf("ferry", "chora", "beach", "hozo", null, null), l.pages.plan(six, 5).photos.map { it.mediaId })
        assertTrue(AlbumEdit.extras(six).isEmpty())
        // Back to one of the book's own layouts: the album id goes, the photos stay.
        val back = BookEdit.template(BookDoc(pages = listOf(six)), "p", Template.PhotoTwo).pages.single()
        assertNull(back.album)
        assertEquals(listOf("ferry", "chora"), l.pages.plan(back, 5).photos.map { it.mediaId })
        // Empty spots move after the photos.
        val gappy = BookPage("g", Template.PhotoFour.id, slots = listOf(Slot(), Slot("chora"), Slot(), Slot("beach")))
        assertEquals(listOf("chora", "beach", null), l.pages.plan(AlbumEdit.apply(gappy, "3-top"), 5).photos.map { it.mediaId })
    }

    @Test fun aPageArrangedByHandGoesIntoTheLayoutAndKeepsWhatWasAdded() {
        val l = layout()
        val page = FreeEdit.freeze(BookPage("p", Template.PhotoTwo.id, slots = listOf(Slot("chora"), Slot("beach"))), l.pages, 5)
        val withText = FreeEdit.add(page, FreeEdit.newText(l.pages, "w1").copy(text = "Naxos"))
        val album = AlbumEdit.apply(withText, "3-top")
        assertFalse(album.free)
        assertEquals(listOf("chora", "beach", null), l.pages.plan(album, 5).photos.map { it.mediaId })
        assertEquals(listOf("w1"), album.els.map { it.id })
    }

    @Test fun theRestGoesOnANewPageRightAfterInTheSameLayout() {
        val six = listOf("ferry", "parikia", "windmill", "kolymb", "naousa", "chora")
        val page = AlbumEdit.apply(albumPage("p", "6-grid", six) { if (it == 5) "Chora" else "" }, "4-grid")
        val text = BookPage("t", Template.PhotoText.id, slots = listOf(Slot("hozo")), paras = listOf(Para(ParaKind.P, "x", "e8")), entryId = "e8")
        val cont = BookPage("c", Template.Text.id, start = false, paras = listOf(Para(ParaKind.P, "y", "e8")), entryId = "e8")
        val doc = BookDoc(pages = listOf(BookPage("cover", Template.Cover.id), page, BookPage("z", Template.Notes.id), text, cont))
        val moved = AlbumEdit.extrasToNewPages(doc, "p", counter())
        assertEquals(listOf("cover", "p", "n1", "z", "t", "c"), moved.pages.map { it.id })
        assertEquals(six.take(4), moved.page("p")!!.slots.map { it.mediaId })
        val added = moved.page("n1")!!
        assertEquals(Template.Album, added.template)
        assertEquals("2-side", added.album) // the same layout would leave two gaps: the one for two
        assertEquals(listOf("naousa", "chora"), added.slots.map { it.mediaId })
        assertEquals("Chora", added.slots[1].caption)
        assertEquals(page.day, added.day)
        assertEquals(six.sorted(), moved.pages.flatMap { it.mediaIds }.filter { it in six }.sorted())
        assertTrue(AlbumEdit.extras(moved.page("p")!!).isEmpty())
        // After a text page, the new pages come after the text that runs on from it.
        val textPage = BookPage("t", Template.PhotoText.id, slots = listOf(Slot("hozo"), Slot("beach")), paras = listOf(Para(ParaKind.P, "x", "e8")))
        val d2 = BookDoc(pages = listOf(textPage, cont, BookPage("z", Template.Notes.id)))
        assertEquals(listOf("t", "c", "n1", "z"), AlbumEdit.extrasToNewPages(d2, "t", counter()).pages.map { it.id })
        // Nothing to move: nothing changes.
        assertEquals(doc, AlbumEdit.extrasToNewPages(doc, "cover", counter()))
    }

    @Test fun addingSeveralPhotosFillsTheEmptySpotsFirstTheTappedOneFirstOfAll() {
        val l = layout()
        val page = albumPage("p", "4-grid", listOf("ferry", null, "chora", null))
        assertEquals(2, AlbumEdit.emptySpots(page, l.pages, 5).size)
        val f = AlbumEdit.fill(page, l.pages, 5, listOf("beach", "hozo", "amorgos"))
        assertEquals(listOf("ferry", "beach", "chora", "hozo"), f.page.slots.map { it.mediaId })
        assertEquals(listOf("amorgos"), f.rest)
        assertFalse(f.page.free)
        // The spot that was tapped gets the first photo.
        val plan = l.pages.plan(page, 5)
        val last = plan.ids[plan.els.indexOfFirst { it is PhotoEl && it.slot == 3 }]!!
        val t = AlbumEdit.fill(page, l.pages, 5, listOf("beach"), target = last)
        assertEquals(listOf("ferry", null, "chora", "beach"), t.page.slots.map { it.mediaId })
        assertTrue(t.rest.isEmpty())
        // A spot whose photo is gone from the trip counts as empty.
        val gone = albumPage("q", "2-side", listOf("ferry", "deleted"))
        val g = AlbumEdit.fill(gone, l.pages, 5, listOf("beach"), has = { facts.media(it) != null })
        assertEquals(listOf("ferry", "beach"), g.page.slots.map { it.mediaId })
        // A page arranged by hand: its empty photos, in drawing order.
        val free = FreeEdit.freeze(page, l.pages, 5)
        val ff = AlbumEdit.fill(free, l.pages, 5, listOf("beach", "hozo"))
        assertEquals(listOf("ferry", "beach", "chora", "hozo"), ff.page.els.filter { it.isPhoto }.map { it.photo?.mediaId })
        assertTrue(ff.page.free)
        // A text page has no photo spot to fill.
        val text = BookPage("x", Template.Text.id, paras = listOf(Para(ParaKind.P, "Hello")))
        assertEquals(listOf("beach"), AlbumEdit.fill(text, l.pages, 5, listOf("beach")).rest)
    }

    @Test fun photosThatDontFitMakeTheLayoutBiggerOrArePlacedByHand() {
        val l = layout()
        val two = albumPage("p", "2-side", listOf("ferry", "chora"))
        assertEquals("6-grid", AlbumEdit.grown(two, 3)?.id)
        val grown = AlbumEdit.grow(two, listOf("beach", "hozo", "amorgos"))!!
        assertEquals("6-grid", grown.album)
        assertEquals(listOf("ferry", "chora", "beach", "hozo", "amorgos", null), l.pages.plan(grown, 5).photos.map { it.mediaId })
        // A photo template grows into an album too; more than nine photos don't fit any.
        val photo = BookPage("q", Template.Photo.id, slots = listOf(Slot("ferry")))
        assertEquals("2-side", AlbumEdit.grow(photo, listOf("chora"))?.album)
        assertNull(AlbumEdit.grow(photo, ids.take(9)))
        // Text pages and the cover keep their layout; their photos are placed by hand.
        val text = BookPage("t", Template.PhotoText.id, slots = listOf(Slot("hozo")), paras = listOf(Para(ParaKind.P, "x")))
        assertFalse(AlbumEdit.canGrow(text))
        assertFalse(AlbumEdit.canGrow(BookPage("c", Template.Cover.id)))
        assertNull(AlbumEdit.grow(text, listOf("chora")))
        val placed = AlbumEdit.byHand(text, l.pages, listOf("chora", "beach"), counter("h"))
        assertEquals(listOf("h1", "h2"), placed.els.map { it.id })
        assertEquals(listOf("chora", "beach"), placed.els.map { it.photo?.mediaId })
        assertFalse(placed.free)
        assertTrue(placed.els[1].box.x > placed.els[0].box.x)
    }

    @Test fun photoPagesTakeAsManyPagesAsThePhotosNeedWithNoGaps() {
        val eleven = ids.take(11).map { Slot(it) }
        val pages = AlbumEdit.pages(eleven, AlbumLayouts.of("4-grid"), BookFixtures.start, null, counter())
        assertEquals(listOf("4-grid", "4-grid", "3-top"), pages.map { it.album })
        assertEquals(ids.take(11), pages.flatMap { p -> p.slots.map { it.mediaId } })
        assertTrue(pages.all { it.template == Template.Album && it.day == BookFixtures.start && it.spots == it.slots.size })
        // Put after the page they were made from (past its text), or before the back cover from the overview.
        val l = layout()
        val doc = l.auto()
        val at = AlbumEdit.beforeBack(doc)
        assertEquals(Template.BackCover, doc.pages[at].template)
        val withPages = AlbumEdit.insertAt(doc, at, pages)
        assertEquals(Template.BackCover, withPages.pages.last().template)
        assertEquals(pages.map { it.id }, withPages.pages.subList(at, at + 3).map { it.id })
        // Reflowing the book keeps them as they are.
        assertEquals(pages, l.reflow(withPages).pages.filter { it.template == Template.Album })
        // A blank album page has the layout's empty spots.
        val blank = BookEdit.blank(Template.Album, "b", null, "6-big")
        assertEquals(6, blank.slots.size)
        assertTrue(blank.slots.all { it.mediaId == null })
    }

    @Test fun albumIdsSurviveTheBookJsonAndOldBooksReadUnchanged() {
        val page = albumPage("a", "9-big", ids.take(9)) { if (it == 2) "Portara" else "" }
        val doc = BookDoc(pages = listOf(page, BookPage("p", Template.PhotoTwo.id, slots = listOf(Slot("ferry")))))
        val json = BookJson.encode(doc)
        assertEquals(doc, BookJson.decode(json))
        val pages = Json.parseToJsonElement(json).jsonObject["pages"]!!.jsonArray
        assertEquals("\"album\"", pages[0].jsonObject["t"].toString())
        assertEquals("\"9-big\"", pages[0].jsonObject["album"].toString())
        assertNull(pages[1].jsonObject["album"])
        // A book saved before albums: no album key anywhere, read and written back the same.
        val old = """{"v":2,"paper":"dots","pad4":true,"pages":[{"id":"x","t":"photo-4","photos":[{"m":"ferry"},{"m":"chora"}]}]}"""
        val read = BookJson.decode(old)
        assertNull(read.pages.single().album)
        assertFalse(BookJson.encode(read).contains("album"))
        // An arrangement from a newer version is kept, and laid out as the usual one for its photos meanwhile.
        val newer = BookJson.decode("""{"v":2,"pages":[{"id":"y","t":"album","album":"5-star","photos":[{"m":"ferry"},{"m":"chora"},{"m":"beach"}]}]}""")
        val y = newer.pages.single()
        assertEquals("5-star", y.album)
        assertEquals("3-top", y.albumLayout?.id)
        assertTrue(BookJson.encode(newer).contains("\"album\":\"5-star\""))
        // Ids that could reach HTML are checked.
        assertNull(BookJson.decode("""{"v":2,"pages":[{"id":"z","t":"album","album":"<b>"}]}""").pages.single().album)
    }

    @Test fun emptySpotsShowAPlusInTheEditorAndStayOutOfThePdf() {
        val l = layout(BookSpec(theme = PrintTheme.Postcard))
        val page = albumPage("a", "4-big", listOf("ferry", null, "chora", null))
        val doc = BookDoc(pages = listOf(page), padTo4 = false)
        val photo: (String, Int) -> String? = { id, _ -> "file:///img/$id.jpg" }
        val editor = BookHtml.page(l, doc, "a", photo, 1.0)
        assertEquals(2, Regex("class=\"ph empty\"").findAll(editor).count())
        assertEquals(2, Regex("<i class=\"add\"></i>").findAll(editor).count())
        val pdf = BookHtml.document(l, doc, photo)
        assertFalse(pdf.contains("ph empty"))
        assertTrue(pdf.contains("data-t=\"album\""))
        // The photos print where the layout puts them, in mm.
        val first = l.pages.plan(page, 1).photos.first()
        assertTrue(pdf.contains("left:${BookSvg.n2(first.frame.x)}mm;top:${BookSvg.n2(first.frame.y)}mm;width:${BookSvg.n2(first.frame.w)}mm"))
    }

    @Test fun layoutChangesAreUndoneAndCopiedToOtherPages() {
        val l = layout()
        val doc = BookDoc(pages = listOf(albumPage("a", "4-grid", ids.take(4)), BookPage("b", Template.PhotoTwo.id, slots = listOf(Slot("hozo"), Slot("beach")))))
        val history = EditHistory<BookDoc>()
        history.push(doc)
        val changed = AlbumEdit.apply(doc, "a", "2-stack")
        assertEquals(doc, history.undo(changed))
        assertEquals(changed, history.redo(doc))
        // "Use this layout on other pages" takes the album arrangement along.
        val copied = FreeEdit.copyLayout(doc, "a", listOf("b"), l.pages) { 3 }
        assertEquals("4-grid", copied.page("b")!!.album)
        assertEquals(Template.Album, copied.page("b")!!.template)
        assertNotNull(copied.page("b")!!.albumLayout)
        assertEquals(listOf("hozo", "beach", null, null), l.pages.plan(copied.page("b")!!, 3).photos.map { it.mediaId })
    }
}
