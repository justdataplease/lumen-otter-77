package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.justdataplease.boringtravel.book.BookDoc
import com.justdataplease.boringtravel.book.BookEdit
import com.justdataplease.boringtravel.book.BookFacts
import com.justdataplease.boringtravel.book.BookHtml
import com.justdataplease.boringtravel.book.BookLayout
import com.justdataplease.boringtravel.book.BookSpec
import com.justdataplease.boringtravel.book.Template
import com.justdataplease.boringtravel.book.phoneTextMeasure
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/*
 * The book (round 21) for review, on the demo's Argentina (its journal and real photos): the dark studio with every page
 * in each of the four looks, the loading and failed states, the Book look sheet, Add a page, Photo pages, the PDF sheet,
 * one page large (the desktop's stand-in where iPhone shows WebKit's page); and the book's pages themselves as the
 * stand-in draws them from the book's own HTML and CSS (book/BookHtml.kt, book/BookCss.kt, in their WebKit forms), for
 * each look: the cover, a day's opener, a photo to the edges with its caption over the scrim, the route or the numbers,
 * and the back cover. Since round 22 also the page editor, its touches and its tools (BookEditorScreenshots.kt).
 */
fun books(out: File) {
    val trip = SampleArgentina.TRIP_ID
    val printer = DesktopPagePrinter()

    /** A fresh demo for each book, whose first visit lays it out in [theme] (the Share screen's style). */
    fun fresh(theme: PrintTheme): Triple<TripBook, MediaKit, AppSettings> {
        val (demo, kit) = demoBook()
        val settings = AppSettings.ready(Look.PostcardPop).apply { demoMode = true; printTheme = theme }
        return Triple(demo, kit, settings)
    }

    fun shot(name: String, book: TripBook, kit: MediaKit, settings: AppSettings, frames: Int = 60, start: BookStart? = null, page: Int? = null, tripId: String = trip) =
        File(out, "$name.png").writeBytes(
            render(PHONE_W, PHONE_H, PHONE_DENSITY, frames) {
                CompositionLocalProvider(LocalMediaKit provides kit, LocalMediaRows provides { id: String -> book.media(id) }, LocalPagePrinter provides printer) {
                    BoringTravelTheme(Look.PostcardPop) {
                        Box(Modifier.fillMaxSize().background(StudioColors.bg).padding(PHONE_FRAME)) {
                            BookScreen(book, tripId, settings, onBack = {}, startWith = start, pageAtStart = page)
                        }
                    }
                }
            },
        )

    // Every page, in each look; the first visit lays the book out and keeps it.
    for (theme in PrintTheme.entries) {
        val (demo, kit, settings) = fresh(theme)
        shot("book-pages-${theme.id}", demo, kit, settings)
    }
    val (demo, kit, settings) = fresh(PrintTheme.Notebook)
    shot("book-loading", demo, kit, settings, frames = 0)
    shot("book-not-here", demo, kit, settings, tripId = "gone")
    shot("book-pages", demo, kit, settings)
    shot("book-look", demo, kit, settings, start = BookStart.Look)
    shot("book-add-page", demo, kit, settings, start = BookStart.AddPage)
    shot("book-photo-pages", demo, kit, settings, start = BookStart.PhotoPages)
    shot("book-pdf", demo, kit, settings, start = BookStart.Pdf)
    shot("book-page-cover", demo, kit, settings, page = 0)
    shot("book-page-day", demo, kit, settings, page = 3)

    // Round 22: the page editor, its touches and every tool's panel.
    bookEditor(out)

    // The pages themselves, each look: cover, a day's opener, a photo with its caption over the scrim, the route or the
    // numbers, the back cover.
    for (theme in PrintTheme.entries) {
        val (book, files) = fresh(theme).let { it.first to it.second.files }
        runBlocking { pagePictures(out, book, files, theme) }
    }
}

/** The pages of [book]'s Argentina in [theme] as the stand-in draws them, a few by kind, 6 px a mm. */
private suspend fun pagePictures(out: File, book: TripBook, files: MediaFiles, theme: PrintTheme) {
    val exports = BookExports(book, files, VideoFrames { _, _ -> null }, NoPagePrinter)
    val trip = exports.loadTrip(SampleArgentina.TRIP_ID)
    val facts = BookFacts(trip)
    val spec = BookSpec(theme = theme)
    val layout = BookLayout(spec, facts, phoneTextMeasure())
    var doc: BookDoc = layout.auto()
    // A photo to the edges with its caption in big words over the scrim (Full page with caption): the first photo page made so.
    val photoPage = doc.pages.first { it.template == Template.PhotoFull || it.template == Template.Photo }
    doc = BookEdit.template(doc, photoPage.id, Template.PhotoFullCaption)
    doc = BookEdit.update(doc, photoPage.id) { p -> p.withSlot(0, p.slot(0).copy(caption = "Fitz Roy at first light")) }
    doc = layout.reflow(doc)
    val printed = layout.printed(doc)
    val photos = HashMap<String, String>()
    exports.writeFonts()
    for ((id, px) in BookHtml.photoNeeds(layout, doc)) {
        val m = facts.media(id) ?: continue
        PhotoPrep(files, VideoFrames { _, _ -> null }).scaled(m, minOf(px, 2000), quality = 88)?.let { photos[id] = it.src }
    }
    val html = WebKitPrint.forBook(BookHtml.document(layout, doc, { id, _ -> photos[id] }, fontBase = "fonts/"))
    val drawer = BookPageDrawer { src -> files.read("$EXPORTS/$src") }
    try {
        val pages = drawer.pages(html)
        fun index(t: Template, nth: Int = 0) = printed.withIndex().filter { it.value.template == t }.getOrNull(nth)?.index
        val picks = listOfNotNull(
            index(Template.Cover)?.let { "cover" to it },
            index(Template.DayOpener, 1)?.let { "day" to it },
            index(Template.PhotoFullCaption)?.let { "scrim" to it },
            (index(Template.Map) ?: index(Template.Stats))?.let { "route" to it },
            index(Template.Stats)?.let { "numbers" to it },
            index(Template.PhotoText)?.let { "text" to it },
            printed.lastIndex.takeIf { it >= 0 }?.let { "back" to it },
        ).distinctBy { it.second }
        for ((name, i) in picks) {
            val page = pages.getOrNull(i) ?: continue
            val image = drawer.draw(page, 6f)
            File(out, "book-page-${theme.id}-$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            image.close()
        }
    } finally {
        drawer.close()
    }
}
