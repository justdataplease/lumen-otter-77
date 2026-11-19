package com.justdataplease.boringtravel.export

import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.model.PrintTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class TripHtmlTest {
    private val photo: (Media) -> String? = { "file:///img/${it.id}.jpg" }

    private fun html(
        options: ExportOptions = ExportOptions(),
        book: TripBook = ExportFixtures.book(),
        target: ExportTarget = ExportTarget.Print,
        photoSrc: (Media) -> String? = photo,
    ) = TripHtml.build(book, options, photoSrc, target, Locale.UK)

    private fun count(s: String, part: String) = Regex(Regex.escape(part)).findAll(s).count()

    @Test fun oneSectionPerDayWithContent() {
        // Day 3 (Kolymbithres) and day 4 (Naxos) have entries; the other eight days are empty and left out.
        PrintTheme.entries.forEach { theme ->
            val out = html(ExportOptions(theme = theme))
            assertEquals(theme.id, 2, count(out, "<section class=\"day"))
            assertTrue(out.contains("id=\"day-2027-06-18\""))
            assertTrue(out.contains("id=\"day-2027-06-19\""))
        }
    }

    @Test fun planAddsPlannedDaysAndIdeas() {
        val out = html(ExportOptions(includePlan = true))
        // Day 1 (ferry from Piraeus) and day 7 (ferry to Amorgos) only have plan items; plus the ideas section.
        assertEquals(5, count(out, "<section class=\"day"))
        assertTrue(out.contains("Ferry Naxos → Amorgos"))
        assertTrue(out.contains("<section class=\"day ideas\">"))
        assertTrue(out.contains("Kitron tasting in Halki"))
        val without = html(ExportOptions(includePlan = false))
        assertFalse(without.contains("class=\"plan-list\""))
        assertFalse(without.contains("Kitron tasting"))
    }

    @Test fun dayRangeKeepsOnlyThoseDays() {
        val d = ExportFixtures.day4
        val out = html(ExportOptions(includePlan = true, from = d, to = d))
        assertEquals(1, count(out, "<section class=\"day"))
        assertTrue(out.contains("id=\"day-2027-06-19\""))
        // Ideas belong to the whole trip only.
        assertFalse(out.contains("<section class=\"day ideas\">"))
        // Cover dates follow the range.
        assertTrue(out.contains("19 Jun 2027"))
        assertFalse(out.contains("16–25 Jun 2027"))
    }

    @Test fun everythingFromTheDatabaseIsEscaped() {
        val evil = "<script>alert(\"x\")</script>"
        val base = ExportFixtures.book(title = "Trip $evil & co", caption = "cap $evil")
        val book = base.copy(
            entries = base.entries.map { it.copy(title = it.title + evil, placeName = evil) },
            plan = base.plan.map { it.copy(title = it.title + evil, note = evil) },
            stops = base.stops.map { it.copy(name = it.name + evil) },
        )
        PrintTheme.entries.forEach { theme ->
            listOf(ExportTarget.Print, ExportTarget.Docs).forEach { target ->
                val out = html(ExportOptions(theme = theme, includePlan = true), book, target)
                assertFalse("$theme $target", out.contains("<script"))
                assertTrue(out.contains("&lt;script&gt;"))
                assertFalse(out.contains("cap <"))
                assertTrue(out.contains("cap &lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;"))
                assertTrue(out.contains("<title>Trip &lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; co</title>"))
            }
        }
    }

    @Test fun footerTitleCannotCloseTheStyleBlock() {
        val out = html(book = ExportFixtures.book(title = "A \"B\" </style><b>x"))
        val style = out.substringAfter("<style>").substringBefore("</style>")
        assertTrue(style.contains("content:\"A \\\"B\\\" \\3C /style\\3E \\3C b\\3E x\""))
        assertEquals(1, count(out, "</style>"))
    }

    @Test fun cssStringEscapes() {
        assertEquals("\"a\\\\b\\\"c\\A d\\3C \"", TripHtml.cssString("a\\b\"c\nd<"))
    }

    @Test fun themeCssAndFontsArePresent() {
        val postcard = html(ExportOptions(theme = PrintTheme.Postcard))
        assertTrue(postcard.contains("class=\"theme-postcard"))
        assertTrue(postcard.contains(".stamp{"))
        assertTrue(postcard.contains("<div class=\"stamp\"><span>Day</span><b>4</b><span>Naxos</span></div>"))
        assertTrue(postcard.contains("src:url('file:///android_res/font/literata.ttf')"))
        assertTrue(postcard.contains("src:url('file:///android_res/font/plex_mono_medium.ttf')"))

        val scrap = html(ExportOptions(theme = PrintTheme.Scrapbook))
        assertTrue(scrap.contains("class=\"theme-scrapbook"))
        assertTrue(scrap.contains("'Caveat',cursive"))
        assertTrue(scrap.contains("<span class=\"ticket\">Ferry Paros → Naxos · 07:30</span>"))

        val minimal = html(ExportOptions(theme = PrintTheme.Minimal))
        assertTrue(minimal.contains("class=\"theme-minimal"))
    }

    /** Owner, 2026-11-14: Minimal is "like notebook but white background, minimal, no yellows, underlines". */
    @Test fun minimalIsTheNotebookOnPlainWhite() {
        val out = html(ExportOptions(theme = PrintTheme.Minimal, ruled = true))
        // The notebook's pages: the day stamp, the handwritten date, white prints.
        assertTrue(out.contains("<span class=\"daystamp\">"))
        assertTrue(out.contains("<h2 class=\"date\"><span class=\"mk\">Saturday, 19 June</span></h2>"))
        assertTrue(out.contains("<figure class=\"nb-print cover-print\""))
        assertTrue(out.contains("'Caveat',cursive"))
        // No paper, no highlighter, no tape, no yellow mark.
        assertTrue(out.contains("body{background:#FFFFFF}"))
        assertFalse(out.contains("#FCFCF9"))
        assertFalse(out.contains("radial-gradient"))
        assertFalse(out.contains(" paper-ruled\""))
        assertFalse(out.contains("#F7E36A"))
        assertFalse(out.contains("#FFC93C"))
        // A note's own Tape frame is grey here, set after the shared yellow one.
        assertTrue(out.indexOf("rgba(160,166,178,.5)") > out.indexOf("rgba(247,227,106,.82)"))
        assertFalse(out.contains("<i class=\"tape"))
        assertFalse(out.contains("brand-mark\">"))
        // Google Docs: the same fonts, bold left bold.
        val docs = html(ExportOptions(theme = PrintTheme.Minimal), target = ExportTarget.Docs)
        assertTrue(docs.contains("'Caveat', cursive"))
        assertFalse(docs.contains("#F7E36A"))
        // The Share screen offers Notebook and Minimal; a style saved before opens as Notebook.
        assertEquals(listOf(PrintTheme.Notebook, PrintTheme.Minimal), PrintTheme.SHARE)
        assertEquals(PrintTheme.Notebook, PrintTheme.forShare(PrintTheme.Postcard))
        assertEquals(PrintTheme.Minimal, PrintTheme.forShare(PrintTheme.Minimal))
    }

    @Test fun notebookHasStampsHighlighterPrintsAndPaper() {
        val out = html(ExportOptions(theme = PrintTheme.Notebook))
        assertTrue(out.contains("class=\"theme-notebook"))
        assertTrue(out.contains("<span class=\"daystamp\">"))
        // The cover's logo is the navy-edged paper plane on Postcard Pop's solid yellow, not the old red stamp.
        assertTrue(out.contains("<span class=\"brand-mark\"><svg"))
        assertTrue(out.contains("rx=\"17.28\" fill=\"#FFC93C\""))
        assertTrue(out.contains("stroke=\"#1E2A5A\""))
        assertFalse(out.contains("linearGradient"))
        assertFalse(out.contains("brand-stamp"))
        assertTrue(out.contains("<h2 class=\"date\"><span class=\"mk\">Saturday, 19 June</span></h2>"))
        assertTrue(out.contains("<figure class=\"nb-print cover-print\""))
        // The day's loose photos are taped prints in a row.
        assertTrue(out.substringAfter("id=\"day-2027-06-19\"").contains("<i class=\"tape c\"></i><img src=\"file:///img/p-loose1.jpg\""))
        // No route sketch on the days any more (owner: "the plan trip sketch does not make sense").
        assertFalse(out.contains("dayroute"))
        assertFalse(out.contains("class=\"route\""))
        assertTrue(out.contains("class=\"flag\""))
        assertTrue(out.contains("'Caveat',cursive"))
        assertFalse(out.contains(" paper-ruled\""))
        assertTrue(html(ExportOptions(theme = PrintTheme.Notebook, ruled = true)).contains(" paper-ruled\""))
        // Proposed as the default print theme; a saved choice still wins.
        assertEquals(PrintTheme.Notebook, PrintTheme.of(null))
        assertEquals(PrintTheme.Postcard, PrintTheme.of("postcard"))
    }

    @Test fun pageSizeFollowsThePaper() {
        assertTrue(html(ExportOptions(paper = Paper.A4)).contains("@page{size:A4;"))
        assertTrue(html(ExportOptions(paper = Paper.Letter)).contains("@page{size:letter;"))
        assertTrue(html().contains("@bottom-right{content:counter(page)"))
        assertTrue(html().contains("@page :first{@bottom-left{content:none}"))
    }

    @Test fun photoSizeSetsTheBodyClassAndThePhotosWidths() {
        PhotoSize.entries.forEach { size -> assertTrue(html(ExportOptions(photoSize = size)).contains("photos-${size.id} ")) }
        // The ferry photo (80% wide in its note) prints narrower at a smaller size.
        fun ferry(size: PhotoSize) = Regex("""style="width:([0-9.]+)%[^"]*"><img src="file:///img/p-ferry.jpg"""")
            .find(html(ExportOptions(theme = PrintTheme.Postcard, photoSize = size)))!!.groupValues[1].toDouble()
        assertTrue(ferry(PhotoSize.Small) < ferry(PhotoSize.Medium))
        assertTrue(ferry(PhotoSize.Medium) < ferry(PhotoSize.Large))
    }

    @Test fun scrapbookKeepsAngleFrameAndCaption() {
        val out = html(ExportOptions(theme = PrintTheme.Scrapbook))
        // Half the column at the start, as placed in the note.
        assertTrue(Regex("""<figure class="photo placed frame-polaroid" style="width:50%;margin-left:[0-9.]+%;""").containsMatchIn(out))
        assertTrue(out.contains("transform:rotate(-5deg)\"><img src=\"file:///img/p-halki.jpg\""))
        assertTrue(out.contains("<figcaption>Halki square, 11:40</figcaption>"))
        assertTrue(out.contains("frame-tape"))
        // Loose photos of the day are polaroids side by side.
        val rows = out.substringAfter("id=\"day-2027-06-19\"").substringAfter("<div class=\"rows\">").substringBefore("</div>\n")
        assertTrue(rows.contains("p-loose1.jpg") && rows.contains("p-loose2.jpg"))
    }

    @Test fun postcardShowsTheHeroOnce() {
        val out = html(ExportOptions(theme = PrintTheme.Postcard))
        // Authored photos keep their place in the entry; only loose photos become a hero.
        assertEquals(1, count(out, "p-ferry.jpg"))
        assertTrue(Regex("""<figure class="hero" style="width:[0-9.]+%"><img src="file:///img/p-loose1.jpg"""").containsMatchIn(out))
        assertTrue(out.contains("<figcaption>the ferry</figcaption>"))
        // The bus entry keeps its own photo in the text.
        assertEquals(1, count(out, "p-halki.jpg"))
        // All loose memories remain in the album after the hero and written entries.
        assertEquals(1, count(out, "p-loose1.jpg"))
        assertEquals(1, count(out, "p-loose2.jpg"))
        assertTrue(out.contains("<img class=\"cover-photo\" src=\"file:///img/p-beach.jpg\""))
    }

    @Test fun minimalKeepsAuthoredPhotosAndGridsTheLooseAlbumPhotos() {
        val out = html(ExportOptions(theme = PrintTheme.Minimal))
        val day4 = out.substringAfter("id=\"day-2027-06-19\"").substringBefore("</section>")
        listOf("p-halki", "p-ferry", "p-loose1", "p-loose2").forEach { assertEquals(it, 1, count(day4, "$it.jpg")) }
        // The loose photos stand side by side, as plain prints.
        val rows = day4.substringAfter("<div class=\"rows\">").substringBefore("</div>\n")
        assertTrue(rows.contains("p-loose1.jpg") && rows.contains("p-loose2.jpg"))
        assertFalse(day4.contains("<svg class=\"route\""))
    }

    @Test fun everyThemeKeepsLooseAlbumPhotosInPrintAndDocs() {
        PrintTheme.entries.forEach { theme ->
            listOf(ExportTarget.Print, ExportTarget.Docs).forEach { target ->
                val out = html(ExportOptions(theme = theme), target = target)
                // The cover shows the cover photo; inspect the day itself.
                val memories = if (target == ExportTarget.Print) out.substringAfter("id=\"day-2027-06-19\"") else out
                listOf("p-loose1.jpg", "p-loose2.jpg").forEach { photo ->
                    assertEquals("$theme / $target must include $photo exactly once in its day", 1, count(memories, photo))
                }
            }
        }
    }

    @Test fun themesNeverMoveAuthoredPhotosAcrossTextOrPageBreaks() {
        val original = ExportFixtures.book()
        val doc = EntryDoc(blocks = listOf(
            Block.Paragraph("BeforeAuthoredPhoto"), Block.PageBreak,
            Block.Photo("p-halki", rotation = 7f, width = .65f, position = .3f),
            Block.Paragraph("AfterAuthoredPhoto"),
        ))
        val entry = ExportFixtures.entry("author-layout", ExportFixtures.day4, "12:00", "Authored layout", doc)
        val book = original.copy(entries = listOf(entry), media = original.media.filter { it.id == "p-halki" }.map { it.copy(entryId = entry.id) })
        for (theme in PrintTheme.entries) for (target in ExportTarget.entries) {
            val out = html(ExportOptions(theme = theme), book, target)
            val body = out.substringAfter("<h3>Authored layout</h3>")
            val before = body.indexOf("BeforeAuthoredPhoto")
            val image = body.indexOf("p-halki.jpg")
            val after = body.indexOf("AfterAuthoredPhoto")
            assertTrue("$theme / $target must preserve text-photo order", before >= 0 && image > before && after > image)
            assertTrue("$theme / $target must keep the author page break before the photo", body.substring(before, image).contains("page-break-"))
        }
    }

    @Test fun aVideoPrintsAsItsFrameButIsNotCountedAsAPhoto() {
        val book = ExportFixtures.book()
        val withVideo = book.copy(media = book.media.map { if (it.id == "p-ferry") it.copy(mime = "video/mp4", localPath = "media/p-ferry.mp4") else it })
        fun photos(out: String) = Regex("""(\d+) photos""").find(out.substringAfter("<p class=\"stats\">"))?.groupValues?.get(1)?.toInt()
        val before = requireNotNull(photos(html(ExportOptions(theme = PrintTheme.Minimal))))
        val out = html(ExportOptions(theme = PrintTheme.Minimal), book = withVideo)
        assertEquals(before - 1, photos(out) ?: 0)
        // Its frame (PhotoPrep makes a JPEG of it) is printed where the video is in the note.
        assertTrue(out.contains("p-ferry.jpg"))
    }

    @Test fun missingPhotosAreLeftOut() {
        val out = html(photoSrc = { if (it.id == "p-halki") null else "file:///img/${it.id}.jpg" }, options = ExportOptions(theme = PrintTheme.Scrapbook))
        assertFalse(out.contains("p-halki"))
        assertTrue(out.contains("p-ferry.jpg"))
    }

    @Test fun coverHasPhotoTitleDatesFlagsAndNumbersButNoRoute() {
        PrintTheme.entries.forEach { theme ->
            val out = html(ExportOptions(theme = theme))
            val cover = out.substringAfter("<section class=\"cover").substringBefore("</section>")
            assertTrue(theme.id, cover.contains("<h1>Cyclades, slowly</h1>"))
            assertTrue(theme.id, cover.contains("16–25 Jun 2027"))
            assertTrue(theme.id, cover.contains("p-beach.jpg"))
            assertTrue(theme.id, cover.contains("<span class=\"flags\">"))
            assertTrue(theme.id, cover.contains("10") && cover.contains("days", ignoreCase = true))
            // The route sketch left the cover (owner: "the plan trip sketch does not make sense").
            assertFalse(theme.id, cover.contains("route"))
        }
    }

    @Test fun docsVersionIsPlain() {
        val out = html(ExportOptions(theme = PrintTheme.Postcard, includePlan = true), target = ExportTarget.Docs)
        assertFalse(out.contains("@font-face"))
        assertFalse(out.contains("@page"))
        assertFalse(out.contains("<svg"))
        assertFalse(out.contains("--rot"))
        assertTrue(out.contains("<hr style=\"page-break-before:always;display:none;\">"))
        assertFalse(out.contains("data:image/png"))
        assertTrue(Regex("<img src=\"file:///img/p-ferry.jpg\" width=\"\\d+\"").containsMatchIn(out))
        assertTrue(out.contains("<h2>Day 4 of 10 · Sat 19 Jun 2027 · Halki, Naxos</h2>"))
        assertTrue(out.contains("<table"))
        assertTrue(out.contains("font-family:'Literata'"))
    }

    @Test fun docsPhotoWidthsFollowSize() {
        val small = html(ExportOptions(theme = PrintTheme.Scrapbook, photoSize = PhotoSize.Small), target = ExportTarget.Docs)
        val large = html(ExportOptions(theme = PrintTheme.Scrapbook, photoSize = PhotoSize.Large), target = ExportTarget.Docs)
        fun width(s: String) = Regex("p-ferry.jpg\" width=\"(\\d+)\"").find(s)!!.groupValues[1].toInt()
        assertTrue(width(small) < width(large))
        assertEquals((624 * 0.8).toInt(), width(large))
    }

    @Test fun emptyTripStillMakesACover() {
        val b = ExportFixtures.book()
        val out = html(book = b.copy(entries = emptyList(), media = emptyList(), plan = emptyList(), track = emptyList(), stops = emptyList()))
        assertTrue(out.contains("<section class=\"cover"))
        assertEquals(0, count(out, "<section class=\"day"))
        assertTrue(out.contains("Nothing written for these days yet."))
        assertTrue(out.trimEnd().endsWith("</html>"))
    }

    @Test fun entryFontBecomesAClass() {
        val out = html()
        assertTrue(out.contains("<article class=\"entry font-literata\">"))
    }

    @Test fun photosWithNothingWrittenBetweenStandTogether() {
        val doc = EntryDoc(blocks = listOf(
            Block.Paragraph("a"), Block.Photo("x"), Block.Paragraph(""), Block.Album("2", listOf("y", "z")),
            Block.Paragraph("b"), Block.Paragraph(""), Block.Photo("w"),
        ))
        val parts = TripHtml.noteParts(doc)
        assertEquals(listOf(listOf(Block.Paragraph("a")), emptyList(), listOf(Block.Paragraph("b"), Block.Paragraph("")), emptyList()), parts.map { it.text })
        assertEquals(listOf(0, 2, 0, 1), parts.map { it.photos.size })
    }

    @Test fun aTallPhotoAloneGetsNarrowerInsteadOfCropped() {
        val base = ExportFixtures.book()
        val tall = base.copy(media = base.media.map { if (it.id == "p-ferry") it.copy(width = 3000, height = 4000) else it })
        val doc = EntryDoc(blocks = listOf(Block.Paragraph("Before"), Block.Photo("p-ferry")))
        val book = tall.copy(entries = tall.entries.map { if (it.id == "e2") it.copy(body = doc.toJson()) else it })
        val out = html(ExportOptions(theme = PrintTheme.Postcard, photoSize = PhotoSize.Large), book)
        val width = Regex("""<figure class="photo placed frame-none" style="width:([0-9.]+)%""").find(out)!!.groupValues[1].toDouble()
        // 120 mm tall at most: 90 mm of the 178 mm column.
        assertEquals(120.0 * 0.75 / 178 * 100, width, 0.01)
        // The shape the prepared copy really has wins over the saved size.
        val turned = TripHtml.build(book, ExportOptions(theme = PrintTheme.Postcard), photo, ExportTarget.Print, Locale.UK, photoAspect = { 4 / 3.0 })
        val wide = Regex("""<figure class="photo placed frame-none" style="width:([0-9.]+)%""").find(turned)!!.groupValues[1].toDouble()
        assertEquals(120.0 * 4 / 3 / 178 * 100, wide, 0.01)
    }

    /** Writes the sample trip in every theme when BT_EXPORT_SAMPLES names a folder (for eyeballing in a browser). */
    @Test fun writeSamplesWhenAsked() {
        val dir = System.getenv("BT_EXPORT_SAMPLES")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        dir.mkdirs()
        val fonts = System.getenv("BT_EXPORT_FONTS") ?: TripHtml.FONT_BASE
        val photos = listOf("p-halki", "p-ferry", "p-loose1", "p-loose2", "p-beach")
        PrintTheme.entries.forEach { theme ->
            PhotoSize.entries.forEach { size ->
                val opts = ExportOptions(theme = theme, photoSize = size, includePlan = true)
                val out = TripHtml.build(ExportFixtures.book(), opts, { m -> "${m.id}.jpg".takeIf { m.id in photos } }, locale = Locale.UK, fontBase = fonts)
                File(dir, "${theme.id}-${size.id}.html").writeText(out)
            }
            val docs = TripHtml.build(ExportFixtures.book(), ExportOptions(theme = theme, includePlan = true), { m -> "${m.id}.jpg" }, ExportTarget.Docs, locale = Locale.UK)
            File(dir, "${theme.id}-docs.html").writeText(docs)
        }
    }
}
