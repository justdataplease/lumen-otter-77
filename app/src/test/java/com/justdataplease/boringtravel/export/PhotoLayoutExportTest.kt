package com.justdataplease.boringtravel.export

import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.model.PrintTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

/** A journal photo layout in the print/PDF HTML and in the Google Docs HTML: whole photos in rows, never cropped. */
class PhotoLayoutExportTest {
    private val ids = listOf("k1", "k2", "k3", "k4")

    /** The fixture trip with "Kolymbithres, twice" holding four photos (a landscape, two portraits, a panorama). */
    private fun book(): TripBook {
        val base = ExportFixtures.book()
        val day3 = ExportFixtures.start.plusDays(2)
        val doc = EntryDoc(blocks = listOf(Block.Paragraph("Same rocks in the morning and again at six."), Block.Album("4-big", ids), Block.Paragraph("After.")))
        val e3 = ExportFixtures.entry("e3", day3, "19:30", "Kolymbithres, twice", doc, "Kolymbithres, Paros")
        val sizes = listOf(4000 to 3000, 3000 to 4000, 3000 to 4000, 4000 to 1000)
        return base.copy(
            entries = base.entries.map { if (it.id == "e3") e3 else it },
            media = base.media + ids.mapIndexed { i, id ->
                ExportFixtures.media(id, ExportFixtures.at(day3, "1$i:00"), "e3").copy(width = sizes[i].first, height = sizes[i].second)
            },
        )
    }

    private fun count(s: String, part: String) = Regex(Regex.escape(part)).findAll(s).count()

    @Test fun printShowsEveryPhotoWholeInRowsInEveryTheme() {
        for (theme in PrintTheme.entries) {
            val out = TripHtml.build(book(), ExportOptions(theme = theme), { m -> "file:///img/${m.id}.jpg" }, ExportTarget.Print, locale = Locale.UK)
            val note = out.substringAfter("Same rocks").substringBefore("After.")
            assertTrue(theme.id, note.contains("<div class=\"rows\">"))
            ids.forEach { assertEquals("${theme.id} $it", 1, count(note, "src=\"file:///img/$it.jpg\"")) }
            // Each photo grows by its own shape, so the row's photos share one height.
            assertTrue(note.contains("flex-grow:1.333\""))
            assertTrue(note.contains("flex-grow:0.75\""))
            assertTrue(note.contains("aspect-ratio:4\""))
            // Nothing is cut to fit.
            assertFalse(theme.id, out.contains("object-fit:cover"))
            assertFalse(theme.id, out.contains("photo-layout"))
        }
    }

    @Test fun docsShowTheLayoutAsRowsOfWholePhotos() {
        for (theme in PrintTheme.entries) {
            val out = TripHtml.build(book(), ExportOptions(theme = theme), { m -> "${m.id}.jpg" }, ExportTarget.Docs, locale = Locale.UK)
            val note = out.substringAfter("Same rocks").substringBefore("After.")
            ids.forEach { assertEquals("${theme.id} $it", 1, count(note, "src=\"$it.jpg\"")) }
            Regex("<table[^>]*><tr>(.*?)</tr></table>").findAll(note).forEach { row ->
                val sizes = Regex("""src="(k\d)\.jpg" width="(\d+)" height="(\d+)"""").findAll(row.groupValues[1]).map { Triple(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.toList()
                // One height per row, each width from the photo's own shape.
                assertEquals(1, sizes.map { it.third }.distinct().size)
                sizes.forEach { (id, w, h) ->
                    val a = mapOf("k1" to 4 / 3.0, "k2" to 0.75, "k3" to 0.75, "k4" to 4.0).getValue(id)
                    assertTrue("$id $w×$h", abs(w / a - h) <= 1.5)
                }
                // A row never needs more than the column.
                assertTrue(sizes.sumOf { it.second } <= TripHtml.DOCS_TEXT_WIDTH_PX)
            }
        }
    }
}
