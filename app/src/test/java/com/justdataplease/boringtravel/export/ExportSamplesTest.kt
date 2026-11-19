package com.justdataplease.boringtravel.export

import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.model.PrintTheme
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Writes a trip whose notes hold 1, 2, 3, 5 and 8 photos of every shape (portrait, landscape, square, panorama, a
 * phone screenshot) in every style and photo size, as print HTML, Docs HTML, plain text and a Word file, when
 * BT_EXPORT_SAMPLES names a folder. BT_EXPORT_PHOTOS is a folder of JPEGs named by the ids below (their real sizes
 * are read); BT_EXPORT_FONTS the fonts folder (URL). Print the HTML with a browser to look at the pages.
 */
class ExportSamplesTest {
    private val shapes = listOf("l1", "l2", "l3", "l4", "p1", "p2", "p3", "p4", "s1", "s2", "w1", "w2", "t1", "q1")

    private fun book(photoDir: File): TripBook {
        val base = ExportFixtures.book()
        val start = ExportFixtures.start
        fun media(id: String, day: Int, entry: String?): Media {
            val img = ImageIO.read(File(photoDir, "$id.jpg"))
            return ExportFixtures.media(id, ExportFixtures.at(start.plusDays(day.toLong()), "12:00"), entry).copy(width = img.width, height = img.height)
        }
        fun p(text: String) = Block.Paragraph(text)
        val words = "We walked down to the harbour before the heat and sat with coffee while the boats came in. Nobody was in a hurry."
        val one = EntryDoc(blocks = listOf(p(words), Block.Photo("l1"), p("Then lunch."), Block.Photo("p1", caption = "The church, late"), p(words)))
        val two = EntryDoc(blocks = listOf(p(words), Block.Album("2-side", listOf("l2", "p2")), p(words)))
        val three = EntryDoc(blocks = listOf(Block.Album("3-row", listOf("s1", "l3", "w1")), p(words)))
        val five = EntryDoc(blocks = listOf(p(words), Block.Album("5-mix", listOf("p3", "l4", "s2", "q1", "p4")), p(words)))
        val eight = EntryDoc(blocks = listOf(p(words), Block.Album("4-grid", listOf("l1", "p1", "l2", "p2")), p(""), Block.Album("4-grid", listOf("w2", "s1", "t1", "l3"))))
        val placed = EntryDoc(blocks = listOf(
            p(words),
            Block.Photo("w1", rotation = -3f, width = 0.9f, frame = PhotoFrame.Polaroid, caption = "Wide one"),
            p(words),
            Block.Photo("t1", rotation = 4f, width = 0.6f, frame = PhotoFrame.Tape, caption = "Screenshot of the ferry times"),
            p(words),
        ))
        val entries = listOf(
            ExportFixtures.entry("x1", start, "10:00", "One photo, then another", one, "Parikia, Paros"),
            ExportFixtures.entry("x2", start.plusDays(1), "10:00", "Two photos", two, "Naousa, Paros"),
            ExportFixtures.entry("x3", start.plusDays(1), "18:00", "Three photos", three, "Naousa, Paros"),
            ExportFixtures.entry("x5", start.plusDays(2), "11:00", "Five photos", five, "Halki, Naxos"),
            ExportFixtures.entry("x8", start.plusDays(4), "11:00", "Eight photos", eight, "Chora, Naxos"),
            ExportFixtures.entry("xp", start.plusDays(5), "11:00", "Placed photos", placed, "Amorgos"),
        )
        val inNotes = mapOf("x1" to listOf("l1", "p1"), "x2" to listOf("l2", "p2"), "x3" to listOf("s1", "l3", "w1"), "x5" to listOf("p3", "l4", "s2", "q1", "p4"), "x8" to listOf("w2", "t1"))
        val dayOf = mapOf("x1" to 0, "x2" to 1, "x3" to 1, "x5" to 2, "x8" to 4)
        val media = inNotes.flatMap { (e, ids) -> ids.map { media(it, dayOf.getValue(e), e) } } +
            listOf(media("q1", 4, null).copy(id = "loose-q1"), media("p3", 4, null).copy(id = "loose-p3"), media("l4", 4, null).copy(id = "loose-l4"), media("w2", 5, null).copy(id = "loose-w2"))
        return base.copy(entries = entries, media = media)
    }

    private fun file(photoDir: File, m: Media) = File(photoDir, m.id.removePrefix("loose-") + ".jpg")

    @Test fun writeSamplesWhenAsked() {
        val dir = System.getenv("BT_EXPORT_SAMPLES")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val photoDir = System.getenv("BT_EXPORT_PHOTOS")?.let(::File)?.takeIf { it.isDirectory } ?: return
        if (shapes.any { !File(photoDir, "$it.jpg").isFile }) return
        dir.mkdirs()
        val fonts = System.getenv("BT_EXPORT_FONTS") ?: TripHtml.FONT_BASE
        val book = book(photoDir)
        val src: (Media) -> String? = { m -> file(photoDir, m).toURI().toString() }
        PrintTheme.entries.forEach { theme ->
            PhotoSize.entries.forEach { size ->
                val opts = ExportOptions(theme = theme, photoSize = size, includePlan = true)
                File(dir, "shapes-${theme.id}-${size.id}.html").writeText(TripHtml.build(book, opts, src, locale = Locale.UK, fontBase = fonts))
            }
            File(dir, "shapes-${theme.id}-letter.html").writeText(TripHtml.build(book, ExportOptions(theme = theme, paper = Paper.Letter), src, locale = Locale.UK, fontBase = fonts))
            File(dir, "shapes-${theme.id}-docs.html").writeText(TripHtml.build(book, ExportOptions(theme = theme, includePlan = true), src, ExportTarget.Docs, locale = Locale.UK))
        }
        File(dir, "shapes.txt").writeText(TripText.build(book, ExportOptions(includePlan = true), Locale.UK))
        PhotoSize.entries.forEach { size ->
            File(dir, "shapes-${size.id}.docx").outputStream().use { out ->
                TripDocx.write(book, ExportOptions(photoSize = size, includePlan = true), out, { m ->
                    val f = file(photoDir, m)
                    ImageIO.read(f)?.let { TripDocx.Photo(f, it.width, it.height) }
                }, Locale.UK)
            }
        }
    }
}
