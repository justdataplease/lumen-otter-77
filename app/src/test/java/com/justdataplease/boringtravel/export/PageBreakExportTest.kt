package com.justdataplease.boringtravel.export

import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.model.PrintTheme
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBreakExportTest {
    @Test fun explicitPageBreakSurvivesEveryPrintThemeAndDocsHtml() {
        val source = ExportFixtures.book()
        val doc = EntryDoc(blocks = listOf(Block.Paragraph("BeforeAuthorPageBreak"), Block.PageBreak, Block.Paragraph("AfterAuthorPageBreak")))
        val entry = ExportFixtures.entry("page-test", ExportFixtures.day4, "12:00", "Pages", doc)
        val book = source.copy(entries = listOf(entry), media = emptyList(), track = emptyList(), stops = emptyList())
        for (theme in PrintTheme.entries) for (target in ExportTarget.entries) {
            val html = TripHtml.build(book, ExportOptions(theme = theme), { null }, target)
            val start = html.indexOf("BeforeAuthorPageBreak")
            val end = html.indexOf("AfterAuthorPageBreak")
            assertTrue("$theme $target text order", start >= 0 && end > start)
            val between = html.substring(start, end)
            assertTrue("$theme $target lost author break", between.contains("page-break-after:always") || between.contains("page-break-before:always"))
            assertTrue("$theme $target body after break", html.indexOf("AfterAuthorPageBreak") >= 0)
            if (target == ExportTarget.Print) assertTrue("$theme must leave multicolumn flow for authored pages", html.contains("class=\"body authored-pages\""))
            if (target == ExportTarget.Print) assertTrue("$theme must force a break on a content-bearing section", between.contains("page-continuation\" style=\"break-before:page;page-break-before:always\""))
        }
    }
}
