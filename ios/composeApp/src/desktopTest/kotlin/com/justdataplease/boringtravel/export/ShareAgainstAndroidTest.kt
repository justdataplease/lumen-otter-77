package com.justdataplease.boringtravel.export

import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.ios.MemoryMediaFiles
import com.justdataplease.boringtravel.ios.NoPagePrinter
import com.justdataplease.boringtravel.ios.TripExports
import com.justdataplease.boringtravel.ios.VideoFrames
import com.justdataplease.boringtravel.ios.sampleBook
import com.justdataplease.boringtravel.ios.smallTripBook
import com.justdataplease.boringtravel.model.PrintTheme
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.justdataplease.boringtravel.androidref.export.ExportOptions as AndroidOptions
import com.justdataplease.boringtravel.androidref.export.ExportTarget as AndroidTarget
import com.justdataplease.boringtravel.androidref.export.Paper as AndroidPaper
import com.justdataplease.boringtravel.androidref.export.PhotoSize as AndroidPhotoSize
import com.justdataplease.boringtravel.androidref.export.TripBook as AndroidBook
import com.justdataplease.boringtravel.androidref.export.TripDocx as AndroidDocx
import com.justdataplease.boringtravel.androidref.export.TripHtml as AndroidHtml
import com.justdataplease.boringtravel.androidref.export.TripText as AndroidText
import com.justdataplease.boringtravel.compat.ByteArrayOutputStream as CompatBytes
import com.justdataplease.boringtravel.compat.File as CompatFile
import com.justdataplease.boringtravel.compat.Locale as CompatLocale

/**
 * The same trips exported here and by the Android app's own export files exactly as they are (compiled for this test
 * from android-boringtravel/app, java.time and java.util.zip included: build.gradle.kts androidReferenceSources): the
 * text, the print and Docs HTML and every part of the Word file come out byte for byte the same. Only the Word file's
 * zip differs, by design: Android deflates its parts and dates them by the clock, this phone stores them dated 1980.
 */
class ShareAgainstAndroidTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun android(b: TripBook) = AndroidBook(b.trip, b.entries, b.plan, b.media, b.track, b.stops)

    private fun android(o: ExportOptions) = AndroidOptions(
        o.theme, AndroidPaper.valueOf(o.paper.name), AndroidPhotoSize.valueOf(o.photoSize.name), o.includePlan, o.from, o.to, o.ruled,
    )

    /** Every style, size and paper, with and without the plan, the whole trip and some of its days. */
    private fun everyOption(book: TripBook): List<ExportOptions> {
        val start = book.trip.startDate
        val ranges = listOfNotNull(null to null, start?.let { it.plusDays(3) to it.plusDays(4) }, start?.let { it to it })
        return PrintTheme.entries.flatMap { theme ->
            PhotoSize.entries.flatMap { size ->
                Paper.entries.flatMap { paper ->
                    ranges.flatMap { (from, to) ->
                        listOf(false, true).map { plan -> ExportOptions(theme, paper, size, plan, from, to, ruled = theme == PrintTheme.Notebook && plan) }
                    }
                }
            }
        }
    }

    private fun books(): List<TripBook> {
        val exports = { b: com.justdataplease.boringtravel.engine.TripBook -> TripExports(b, MemoryMediaFiles(), VideoFrames { _, _ -> null }, NoPagePrinter) }
        val small = smallTripBook()
        val sample = sampleBook()
        val fixture = ExportFixtures.book()
        return listOf(
            fixture,
            fixture.copy(trip = fixture.trip.copy(startDate = null, endDate = null)),
            ExportFixtures.book(title = "A <b>&</b> \"trip\"\u0001", caption = "cap </w:t> & co"),
            exports(small).load(small.trips().first().id),
        ) + sample.trips().map { exports(sample).load(it.id) }
    }

    @Test
    fun theTextIsAndroidsLetterForLetter() {
        var compared = 0
        for (book in books()) for (o in everyOption(book)) {
            assertEquals(
                AndroidText.build(android(book), android(o), java.util.Locale.US),
                TripText.build(book, o, CompatLocale.US),
                "${book.trip.title} $o",
            )
            compared++
        }
        assertTrue(compared > 500, "$compared")
    }

    @Test
    fun thePrintAndDocsHtmlAreAndroidsByteForByte() {
        for (book in books()) for (o in everyOption(book)) {
            val src = { m: Media -> "img/${m.id}.jpg".takeIf { m.id != "p-loose2" } }
            val aspect = { m: Media -> if (m.id == "p-ferry") 0.75 else null }
            for (target in ExportTarget.entries) {
                assertEquals(
                    AndroidHtml.build(android(book), android(o), src, AndroidTarget.valueOf(target.name), java.util.Locale.US, fontBase = "fonts/", photoAspect = aspect),
                    TripHtml.build(book, o, src, target, CompatLocale.US, fontBase = "fonts/", photoAspect = aspect),
                    "${book.trip.title} $o $target",
                )
            }
        }
    }

    private fun parts(zip: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> generateSequence { z.nextEntry }.forEach { e -> out[e.name] = z.readBytes() } }
        return out
    }

    @Test
    fun theWordFilesPartsAreAndroidsByteForByte() {
        val photos = HashMap<String, java.io.File>()
        for (book in books()) {
            // Each photo a small file of its own bytes, shaped as it is (portrait for the ferry).
            book.media.forEach { m -> photos.getOrPut(m.id) { tmp.newFile("${m.id}.jpg").apply { writeBytes("jpeg of ${m.id}".toByteArray()) } } }
            fun size(m: Media) = if (m.id == "p-ferry") 900 to 1200 else 1200 to 900
            for (o in everyOption(book).filter { it.theme in PrintTheme.SHARE }) {
                val android = java.io.ByteArrayOutputStream()
                AndroidDocx.write(android(book), android(o), android, { m -> photos[m.id]?.let { f -> size(m).let { (w, h) -> AndroidDocx.Photo(f, w, h) } } }, java.util.Locale.US)
                val here = CompatBytes()
                TripDocx.write(book, o, here, { m -> photos[m.id]?.let { f -> size(m).let { (w, h) -> TripDocx.Photo(CompatFile(f.path), w, h) } } }, CompatLocale.US)
                val a = parts(android.toByteArray())
                val h = parts(here.toByteArray())
                assertEquals(a.keys.toList(), h.keys.toList(), "${book.trip.title} $o")
                a.forEach { (name, bytes) -> assertContentEquals(bytes, h.getValue(name), "${book.trip.title} $o $name") }
            }
        }
    }
}
