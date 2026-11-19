package com.justdataplease.boringtravel.ui.journal

import com.justdataplease.boringtravel.ui.journal.HtmlRuns.Run
import com.justdataplease.boringtravel.ui.journal.HtmlRuns.Size
import com.justdataplease.boringtravel.ui.journal.HtmlRuns.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HtmlRunsTest {
    @Test fun boldAndEntities() {
        assertEquals(
            listOf(Run("Forty "), Run("late", Style(bold = true)), Run(" & ok")),
            HtmlRuns.parse("Forty <b>late</b> &amp; ok"),
        )
    }

    @Test fun nestedStylesCombine() {
        assertEquals(listOf(Run("x", Style(bold = true, italic = true))), HtmlRuns.parse("<b><i>x</i></b>"))
        assertEquals(listOf(Run("y", Style(underline = true, strike = true))), HtmlRuns.parse("<u><s>y</s></u>"))
    }

    @Test fun linksKeepSafeUrlsOnly() {
        assertEquals(
            listOf(Run("l", Style(href = "https://a.b/c?d=1&e=2")), Run("j")),
            HtmlRuns.parse("""<a href="https://a.b/c?d=1&amp;e=2">l</a><a href="javascript:x">j</a>"""),
        )
    }

    @Test fun spanColoursAndSizes() {
        val runs = HtmlRuns.parse(
            """<span style="color: rgba(196, 66, 58, 1.0); font-size: 1.3em">a</span>""" +
                """<span style="color: #0e8a5c; font-size: 20.0px">b</span><span style="background-color: #ff0">c</span>""",
        )
        assertEquals(0xFFC4423A, runs[0].style.color)
        assertEquals(Size.Em(1.3f), runs[0].style.size)
        assertEquals(0xFF0E8A5C, runs[1].style.color)
        assertEquals(Size.Px(20f), runs[1].style.size)
        assertEquals(0xFFFFFF00, runs[2].style.background)
    }

    @Test fun cssWeightsAndDecorations() {
        val run = HtmlRuns.parse("""<span style="font-weight: 700; font-style: italic; text-decoration: underline line-through">x</span>""").single()
        assertEquals(Style(bold = true, italic = true, underline = true, strike = true), run.style)
    }

    @Test fun breaksAndSpaces() {
        assertEquals("a b\nc", HtmlRuns.plain(HtmlRuns.parse("a \n  b <br> c")))
        assertEquals("one two", HtmlRuns.plain(HtmlRuns.parse(" one <b> two</b> ")))
    }

    @Test fun numericAndNamedEntities() {
        assertEquals("a  b A <", HtmlRuns.plain(HtmlRuns.parse("a&#32;&#32;b &#x41;&nbsp;&lt;")))
        assertEquals("&lt;", HtmlRuns.decode("&amp;lt;"))
    }

    @Test fun unknownAndUnclosedTagsAreHarmless() {
        assertEquals("ab", HtmlRuns.plain(HtmlRuns.parse("<x>a</y><b>b")))
    }

    @Test fun colours() {
        assertEquals(0xFF112233, HtmlRuns.parseColor("#112233"))
        assertEquals(0x80112233, HtmlRuns.parseColor("#11223380"))
        assertEquals(0xFFFF0000, HtmlRuns.parseColor("red"))
        assertEquals(0xFF0A141E, HtmlRuns.parseColor("rgb(10, 20, 30)"))
        assertNull(HtmlRuns.parseColor("var(--x)"))
        assertNull(HtmlRuns.parseColor("#12"))
    }

    @Test fun sizes() {
        assertEquals(Size.Px(20f), HtmlRuns.parseSize("20px"))
        assertEquals(Size.Em(1.25f), HtmlRuns.parseSize("1.25em"))
        assertEquals(Size.Em(1.2f), HtmlRuns.parseSize("120%"))
        assertEquals(Size.Em(1.2f), HtmlRuns.parseSize("large"))
        assertNull(HtmlRuns.parseSize("999px"))
        assertNull(HtmlRuns.parseSize("calc(2em)"))
    }
}
