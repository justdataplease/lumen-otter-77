package com.justdataplease.boringtravel.ios

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.NoteText
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.ui.journal.HtmlRuns
import com.justdataplease.boringtravel.ui.theme.BtLooks
import com.justdataplease.boringtravel.ui.theme.Look
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor's rich text without the screen: a note's text blocks read in and written out unchanged, typing and
 * deleting as an editor does (looks carried, lists continued and ended), the toolbar's formatting written as the
 * Android editor's HTML, and every line laid out as its own paragraph.
 */
class RichTextTest {
    /** The text blocks of a note written on Android, in its rich-text library's HTML as the entry keeps it. */
    private val android = listOf(
        Block.Paragraph("Forty minutes <b>late</b>, then <i>slow</i> <u>ferry</u> and <s>no</s> coffee."),
        Block.Heading("Halki", 2),
        Block.Paragraph("<span style=\"font-size: 1.3em\">Big</span> and <span style=\"color: rgba(196, 98, 45, 1.0)\">orange</span><br>a second line"),
        Block.Paragraph("Coffee", ListStyle.Bullet),
        Block.Paragraph("<b>Kitron</b>", ListStyle.Bullet),
        Block.Paragraph("Ferry", ListStyle.Number),
        Block.Paragraph("Bus &amp; walk", ListStyle.Number),
        Block.Heading("Later", 3),
        Block.Paragraph("<span style=\"font-size: 20.8px\">Round-tripped</span> by <a href=\"https://naxos.gr/?a=1&amp;b=2\">Android</a> &#39;twice&#39;"),
    )

    private fun state(v: RichValue, caret: Int = v.text.length) = RichTextState(v).apply { setValue(v, caret) }

    /** Types [s] at the caret, as the text field reports it. */
    private fun RichTextState.type(s: String) {
        val t = text
        val at = selection.max
        onFieldChange(TextFieldValue(t.substring(0, selection.min) + s + t.substring(at), TextRange(selection.min + s.length)))
    }

    private fun RichTextState.backspace() {
        val t = text
        val at = selection.min
        onFieldChange(TextFieldValue(t.substring(0, at - 1) + t.substring(at), TextRange(at - 1)))
    }

    private fun RichTextState.select(start: Int, end: Int) = onFieldChange(TextFieldValue(text, TextRange(start, end)))

    @Test
    fun anAndroidNotesTextIsWrittenBackExactlyAsItWasStored() {
        val v = RichValue.of(android)
        assertEquals(android, v.toBlocks())
        // Read as the editor shows it: paragraphs a line each, the <br> a line inside its paragraph.
        assertEquals(10, v.lines.size)
        assertEquals(listOf(false, false, false, true, false, false, false, false, false, false), v.lines.map { it.soft })
        assertEquals("Big and orange\na second line", v.lineText(2) + "\n" + v.lineText(3))
    }

    @Test
    fun aChangedParagraphIsWrittenAgainAndTheOthersStayByteForByte() {
        val s = state(RichValue.of(android))
        // The caret after "late" in the first paragraph.
        val at = s.text.indexOf("late") + 4
        s.select(at, at)
        s.type("r than ever")
        val blocks = s.value.toBlocks()
        assertEquals("Forty minutes <b>later than ever</b>, then <i>slow</i> <u>ferry</u> and <s>no</s> coffee.", (blocks[0] as Block.Paragraph).html)
        assertEquals(android.drop(1), blocks.drop(1))
    }

    @Test
    fun typingDeletingAndTheLooksOfNewText() {
        val s = state(RichValue.Empty)
        s.type("Swam at ")
        s.toggleBold()
        s.type("Agia Anna")
        s.toggleBold()
        s.type(".")
        assertEquals(listOf(Block.Paragraph("Swam at <b>Agia Anna</b>.")), s.value.toBlocks())
        s.backspace()
        s.backspace()
        assertEquals(listOf(Block.Paragraph("Swam at <b>Agia Ann</b>")), s.value.toBlocks())
        // Typing after a link doesn't make the link longer.
        val linked = state(RichValue.of(listOf(Block.Paragraph("<a href=\"https://naxos.gr\">Naxos</a>"))))
        linked.type(" town")
        assertEquals("<a href=\"https://naxos.gr\">Naxos</a> town", (linked.value.toBlocks().single() as Block.Paragraph).html)
        // Two spaces stay two (HTML would join them).
        val spaced = state(RichValue.Empty).apply { type("a  b") }
        assertEquals("a b".length + 1, HtmlRuns.plain(HtmlRuns.parse((spaced.value.toBlocks().single() as Block.Paragraph).html)).length)
    }

    @Test
    fun theToolbarsLooksAreTheAndroidEditorsHtml() {
        val c = BtLooks.of(Look.Default).colors
        val s = state(RichValue.plain("Big small red marked struck under italic"))
        fun select(word: String) = s.text.indexOf(word).let { s.select(it, it + word.length) }
        select("Big"); s.toggleSize(LARGER)
        select("small"); s.toggleSize(SMALLER)
        select("red"); s.restyle { it.copy(color = c.route.argb()) }
        select("marked"); s.toggleHighlight(c.accent.argb())
        select("struck"); s.toggleStrike()
        select("under"); s.toggleUnderline()
        select("italic"); s.toggleItalic()
        val html = (s.value.toBlocks().single() as Block.Paragraph).html
        assertEquals(
            "<span style=\"font-size: 1.3em\">Big</span> <span style=\"font-size: 0.85em\">small</span> " +
                "<span style=\"color: ${RichValue.cssColor(c.route.argb())}\">red</span> " +
                "<span style=\"background-color: ${RichValue.cssColor(c.accent.argb())}\">marked</span> <s>struck</s> <u>under</u> <i>italic</i>",
            html,
        )
        // What the entry keeps: unchanged by the sanitizer, read back with every look.
        assertEquals(html, RichHtml.sanitize(html))
        val runs = HtmlRuns.parse(html)
        assertEquals(HtmlRuns.Size.Em(1.3f), runs.first { it.text == "Big" }.style.size)
        assertEquals(c.route.argb(), runs.first { it.text == "red" }.style.color)
        assertEquals(c.accent.argb(), runs.first { it.text == "marked" }.style.background)
        assertTrue(runs.first { it.text == "struck" }.style.strike)
        // A look is taken off again where every selected letter has it.
        select("Big"); s.toggleSize(LARGER)
        assertNull(s.value.inks[0].size)
    }

    @Test
    fun listsContinueEndOnAnEmptyItemAndHeadingsAreParagraphs() {
        val s = state(RichValue.Empty)
        s.type("Coffee")
        s.toggleList(ListStyle.Bullet)
        s.type("\nKitron")
        s.type("\n")
        // Enter on the empty item: the list ends there, nothing more is added.
        s.type("\n")
        s.type("After the list")
        assertEquals(
            listOf(Block.Paragraph("Coffee", ListStyle.Bullet), Block.Paragraph("Kitron", ListStyle.Bullet), Block.Paragraph("After the list")),
            s.value.toBlocks(),
        )
        assertEquals(listOf(0 to "•", 7 to "•"), listMarkers(s.value))
        // Backspace at the start of an item: it stops being one first, and only then joins the line above.
        val kitron = s.text.indexOf("Kitron")
        s.select(kitron, kitron)
        s.backspace()
        assertEquals(Block.Paragraph("Kitron"), s.value.toBlocks()[1])
        s.backspace()
        assertEquals(Block.Paragraph("CoffeeKitron", ListStyle.Bullet), s.value.toBlocks()[0])
        // Numbers count each run of items again.
        val n = state(RichValue.of(listOf(Block.Paragraph("a", ListStyle.Number), Block.Paragraph("b", ListStyle.Number), Block.Paragraph("c"), Block.Paragraph("d", ListStyle.Number))))
        assertEquals(listOf("1.", "2.", "1."), listMarkers(n.value).map { it.second })
        // A heading: one paragraph, plain text, its level; Enter after it is text again.
        val h = state(RichValue.Empty)
        h.type("Day two")
        h.setHeading(2)
        h.type("\nRain all day")
        assertEquals(listOf(Block.Heading("Day two", 2), Block.Paragraph("Rain all day")), h.value.toBlocks())
        // Enter at the start of the heading: the heading goes down with its text.
        h.select(0, 0)
        h.type("\n")
        assertEquals(listOf(Block.Heading("Day two", 2), Block.Paragraph("Rain all day")), h.value.toBlocks())
        assertEquals(0, h.value.lines[0].heading)
        assertEquals(2, h.value.lines[1].heading)
    }

    @Test
    fun aTextAreaSplitsAndJoinsByParagraph() {
        val v = RichValue.of(android)
        val parts = v.split()
        assertEquals(9, parts.size)
        assertEquals(android, parts.reduce { a, b -> a + b }.toBlocks())
        // Cut in the middle of a line: both halves keep the line's kind.
        val at = v.text.indexOf("Kitron") + 3
        assertEquals(listOf(Block.Paragraph("<b>Kit</b>", ListStyle.Bullet)), v.slice(v.lineStart(v.lineAt(at)), at).toBlocks())
        assertEquals(Block.Paragraph("<b>ron</b>", ListStyle.Bullet), v.slice(at, v.text.length).toBlocks().first())
    }

    @Test
    fun aNotesPlainTextIsTheAndroidOnes() {
        val doc = EntryDoc(blocks = RichValue.of(android).toBlocks())
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos")
        val saved = book.saveNote(com.justdataplease.boringtravel.data.db.Entry("n", trip.id, 1L, "UTC", createdAt = 0L, updatedAt = 0L), doc)!!
        assertEquals(NoteText.plainText(doc), saved.plainText)
        assertTrue(saved.plainText.startsWith("Forty minutes late, then slow ferry and no coffee.\n\nHalki\n\nBig and orange\na second line\n\n• Coffee\n\n• Kitron\n\n1. Ferry\n\n2. Bus & walk"))
    }

    @Test
    fun everyLineIsLaidOutAsItsOwnParagraphListItemsIndented() {
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val base = TextStyle(fontSize = 16.sp, lineHeight = 26.sp)
        val c = BtLooks.of(Look.Default).colors
        fun layout(v: RichValue) = measurer.measure(RichTransformation(v, base, c).filter(androidx.compose.ui.text.AnnotatedString(v.text)).text, base, constraints = Constraints.fixedWidth(400))
        val v = RichValue.of(listOf(Block.Paragraph("One"), Block.Paragraph("Two", ListStyle.Bullet), Block.Paragraph("Three")))
        val l = layout(v)
        assertEquals(3, l.lineCount)
        assertEquals(0f, l.getLineLeft(0))
        assertEquals(26f, l.getLineLeft(1))
        assertEquals(0f, l.getLineLeft(2))
        // An empty last line has its own line, for the caret.
        val empty = RichValue.plain("One\n")
        val e = layout(empty)
        assertEquals(2, e.lineCount)
        assertTrue(e.getCursorRect(4).top > e.getCursorRect(0).top)
        // A heading is larger: its letters taller, its paragraph taller than a line of text.
        val h = layout(RichValue.of(listOf(Block.Heading("Halki", 2), Block.Paragraph("Halki"))))
        assertEquals(2, h.lineCount)
        assertTrue(h.getHorizontalPosition(5, true) > h.getHorizontalPosition(11, true) * 1.25f)
        assertTrue(h.getBoundingBox(0).height > h.getBoundingBox(6).height)
        assertFalse(RichTransformation(v, base, c) != RichTransformation(v, base, c))
    }
}
