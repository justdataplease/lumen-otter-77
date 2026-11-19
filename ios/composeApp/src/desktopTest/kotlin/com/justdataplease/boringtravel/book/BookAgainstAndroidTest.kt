package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.justdataplease.boringtravel.androidref.book.BookFacts as AndroidFacts
import com.justdataplease.boringtravel.androidref.book.BookFonts as AndroidFonts
import com.justdataplease.boringtravel.androidref.book.BookFormat as AndroidFormat
import com.justdataplease.boringtravel.androidref.book.BookHtml as AndroidHtml
import com.justdataplease.boringtravel.androidref.book.BookJson as AndroidJson
import com.justdataplease.boringtravel.androidref.book.BookLayout as AndroidLayout
import com.justdataplease.boringtravel.androidref.book.BookPaper as AndroidPaper
import com.justdataplease.boringtravel.androidref.book.BookSpec as AndroidSpec
import com.justdataplease.boringtravel.androidref.book.Face as AndroidFace
import com.justdataplease.boringtravel.androidref.book.TextMeasure as AndroidMeasure
import com.justdataplease.boringtravel.androidref.export.TripBook as AndroidBook

/**
 * The book laid out here and by the Android app's own book files exactly as they are (compiled for this test from
 * android-boringtravel/app, java.time and String.format included: build.gradle.kts androidReferenceSources): for every
 * look, size, font set, paper and bleed, the first layout's stored JSON and the page HTML in each mode come out byte for
 * byte the same, measured alike; and each side reads the other's JSON back to the same text (a book carried between the
 * phones by a backup is the same book).
 */
class BookAgainstAndroidTest {
    init {
        runBlocking { BtFontFiles.load() }
    }

    private val book = BookFixtures.book()
    private val androidBook = AndroidBook(book.trip, book.entries, book.plan, book.media, book.track, book.stops)

    private fun android(measure: TextMeasure) = AndroidMeasure { t, f, pt, bold -> measure.widthMm(t, Face.valueOf(f.name), pt, bold) }

    private fun compare(spec: BookSpec, measure: TextMeasure) {
        val here = BookLayout(spec, BookFacts(book, com.justdataplease.boringtravel.compat.Locale.UK), measure, BookFixtures.ids())
        val there = AndroidLayout(
            AndroidSpec(AndroidFormat.valueOf(spec.format.name), spec.theme, AndroidFonts.valueOf(spec.fonts.name), AndroidPaper.valueOf(spec.paper.name), spec.bleed),
            AndroidFacts(androidBook, java.util.Locale.UK), android(measure), BookFixtures.ids(),
        )
        val doc = here.auto()
        val androidDoc = there.auto()
        val json = BookJson.encode(doc)
        assertEquals(AndroidJson.encode(androidDoc), json, "$spec")
        val src = { id: String, px: Int -> "img/$id-$px.jpg".takeIf { id != "beach" } }
        for (mode in BookHtml.Mode.entries) {
            assertEquals(
                AndroidHtml.document(there, androidDoc, src, AndroidHtml.Mode.valueOf(mode.name), fontBase = "fonts/"),
                BookHtml.document(here, doc, src, mode, fontBase = "fonts/"),
                "$spec $mode",
            )
        }
        // One page for the editor at the screen's zoom, the PDF's photo sizes.
        val page = doc.pages[doc.pages.size / 2].id
        assertEquals(AndroidHtml.page(there, androidDoc, page, src, 0.37), BookHtml.page(here, doc, page, src, 0.37), "$spec page")
        assertEquals(AndroidHtml.photoNeeds(there, androidDoc), BookHtml.photoNeeds(here, doc))
        // Each reads the other's stored book to the same text.
        assertEquals(json, AndroidJson.encode(AndroidJson.decode(json)))
        assertEquals(json, BookJson.encode(BookJson.decode(AndroidJson.encode(androidDoc))))
    }

    @Test
    fun everyLookSizeAndFontSetIsAndroidsByteForByte() {
        var compared = 0
        for (theme in PrintTheme.entries) for (format in BookFormat.entries) for (fonts in BookFonts.choosable) {
            compare(BookSpec(format, theme, fonts), TextFit.Estimate)
            compared++
        }
        for (paper in BookPaper.entries) for (bleed in listOf(false, true)) {
            compare(BookSpec(BookFormat.A4, PrintTheme.Notebook, BookFonts.Theme, paper, bleed), TextFit.Estimate)
            compare(BookSpec(BookFormat.Letter, PrintTheme.Postcard, BookFonts.NightRoute, paper, bleed), TextFit.Estimate)
            compared += 2
        }
        assertTrue(compared >= 130, "$compared")
    }

    @Test
    fun measuredWithTheBundledFontsTheLayoutIsAndroidsToo() {
        // The same measure on both sides (this app's Skia measure of the bundled fonts): the same pages.
        val skia = phoneTextMeasure()
        for (theme in PrintTheme.entries) for (format in listOf(BookFormat.A5, BookFormat.A4)) compare(BookSpec(format, theme), skia)
    }

    @Test
    fun aBookEditedByHandReadsTheSameOnBothPhones() {
        val layout = BookLayout(BookSpec(), BookFixtures.facts(), TextFit.Estimate, BookFixtures.ids())
        var doc = layout.auto()
        val pl = layout.pages
        val two = doc.pages.first { it.templateId == Template.PhotoTwo.id }
        val n = layout.number(doc, two.id)
        val first = pl.free.elements(two, n).first { it.isPhoto }
        doc = BookEdit.update(doc, two.id) { p ->
            var q = FreeEdit.move(p, pl, n, first.id, first.box.offset(-4.0, 6.0), -8.0)
            q = FreeEdit.add(q, FreeEdit.newText(pl, "w1").copy(text = "Kolymbithres,\nbetter at six", box = Box(20.0, 150.0, 80.0, 10.0), rot = 4.0, color = "#C4423A"))
            FreeEdit.add(q, FreeEdit.newTape(pl, "tp").copy(box = Box(88.0, 120.0, 34.0, 9.0), rot = 35.0))
        }
        doc = BookEdit.hideEntry(doc, "e3", true).copy(padTo4 = false, paper = BookPaper.Lines)
        val json = BookJson.encode(doc)
        assertTrue(json.contains("\"els\":[") && json.contains("\"hidden\":[\"e3\"]"), json.take(200))
        assertEquals(json, AndroidJson.encode(AndroidJson.decode(json)))
        assertEquals(json, BookJson.encode(BookJson.decode(AndroidJson.encode(AndroidJson.decode(json)))))
        // Every face measured by this app's own measure has Android's metrics and file names.
        assertEquals(AndroidFace.entries.map { "${it.name} ${it.file} ${it.css}" }, Face.entries.map { "${it.name} ${it.file} ${it.css}" })
    }
}
