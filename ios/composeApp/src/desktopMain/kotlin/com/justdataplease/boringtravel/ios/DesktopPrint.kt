package com.justdataplease.boringtravel.ios

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.export.Paper
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.jetbrains.skia.paragraph.TypefaceFontProvider
import org.jetbrains.skia.svg.SVGDOM
import java.io.ByteArrayOutputStream

/*
 * The desktop's stand-in for the iPhone's printing (iosMain/…/IosPrint.kt: WebKit lays the print HTML out and draws
 * it into a PDF). Windows has no WebKit, so the same HTML is drawn here by a small page-drawer of its own: the cover,
 * each day on a new page, headings, the notes' words, photos alone or side by side at their widths, the day stamp's
 * SVG, the plan, page breaks and the footer with the page number, on the paper and in the margins the HTML is laid out
 * for. It is near the real pages, not the same: they come from WebKit. The pages are written as a real PDF (one picture
 * per page) and the HTML beside it ("….pdf.html"), to open in a browser and print from there.
 */
class DesktopPagePrinter : PagePrinter {
    private val made = HashMap<String, List<ByteArray>>()

    /** The prints asked for, as (job, PDF): there is no printer on the desktop. */
    val printed = ArrayList<Pair<String, String>>()

    override suspend fun writePdf(files: MediaFiles, htmlPath: String, html: String, pdfPath: String, paper: Paper, theme: PrintTheme, footer: String): Int {
        val folder = htmlPath.substringBeforeLast('/', "")
        val pages = PageDrawer(paper, theme, footer) { src -> files.read(if (folder.isEmpty()) src else "$folder/$src") }.draw(html)
        val jpegs = pages.map { it.encodeToData(EncodedImageFormat.JPEG, 88)!!.bytes }
        pages.forEach { it.close() }
        if (jpegs.isEmpty()) return 0
        if (!files.write(pdfPath, PagesPdf.write(jpegs, paper))) throw ExportException("The PDF came out empty. Try again.")
        files.write("$pdfPath.html", html.encodeToByteArray())
        made[pdfPath] = jpegs
        return jpegs.size
    }

    override suspend fun open(files: MediaFiles, pdfPath: String): PdfPages? {
        val jpegs = made[pdfPath] ?: files.read(pdfPath)?.let { PagesPdf.read(it) } ?: return null
        if (jpegs.isEmpty()) return null
        val first = Image.makeFromEncoded(jpegs.first())
        val aspect = first.width.toFloat() / first.height
        first.close()
        return object : PdfPages {
            override val count = jpegs.size
            override val aspect = aspect

            override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = jpegs.getOrNull(index)?.let { bytes ->
                val image = Image.makeFromEncoded(bytes)
                val w = widthPx.coerceIn(64, PdfPages.MAX_PX)
                val h = (w * image.height / image.width.toFloat()).toInt().coerceAtLeast(1)
                val surface = Surface.makeRasterN32Premul(w, h)
                surface.canvas.drawImageRect(image, Rect.makeWH(w.toFloat(), h.toFloat()))
                val out = surface.makeImageSnapshot().toComposeImageBitmap()
                image.close()
                surface.close()
                out
            }
        }
    }

    override fun print(files: MediaFiles, pdfPath: String, jobName: String): Boolean {
        printed += jobName to pdfPath
        return false
    }

    /**
     * A book's pages: each section of the HTML drawn by the stand-in ([BookPageDrawer], in the form WebKit gets it,
     * WebKitPrint.forBook) on a sheet of [sheet]'s size, on [ground], into a PDF of one picture per sheet, with the HTML
     * beside it ("….pdf.html") in WebKit's print form.
     */
    override suspend fun writeBook(files: MediaFiles, htmlPath: String, html: String, pdfPath: String, sheet: BookSheetSize, ground: Long): Int {
        val folder = htmlPath.substringBeforeLast('/', "")
        val drawer = BookPageDrawer { src -> files.read(if (folder.isEmpty()) src else "$folder/$src") }
        val jpegs = try {
            drawer.pages(WebKitPrint.forBook(html)).map { page ->
                val image = drawer.draw(page, BOOK_PX_PER_MM)
                val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(image.width, image.height)
                surface.canvas.clear(ground.toInt())
                surface.canvas.drawImage(image, 0f, 0f)
                val shot = surface.makeImageSnapshot()
                val jpeg = shot.encodeToData(EncodedImageFormat.JPEG, 88)!!.bytes
                shot.close()
                surface.close()
                image.close()
                jpeg
            }
        } finally {
            drawer.close()
        }
        if (jpegs.isEmpty()) return 0
        if (!files.write(pdfPath, PagesPdf.write(jpegs, sheet.widthMm, sheet.heightMm))) throw ExportException("The PDF came out empty. Try again.")
        files.write("$pdfPath.html", WebKitPrint.forBookPrint(html, sheet.widthMm, sheet.heightMm).encodeToByteArray())
        made[pdfPath] = jpegs
        bookPrints += pdfPath
        return jpegs.size
    }

    /** The books' PDFs made, in order. */
    val bookPrints = ArrayList<String>()

    private companion object {
        /** The book's pages drawn at 6 px a mm (152 dpi): enough to look at; WebKit's are vector. */
        const val BOOK_PX_PER_MM = 6f
    }
}

/** A PDF of one JPEG picture per page, filling the paper (the stand-in's pages), and the pictures read back from one. */
object PagesPdf {
    private fun pt(mm: Double) = mm * 72.0 / 25.4

    fun write(jpegs: List<ByteArray>, paper: Paper): ByteArray = write(jpegs, paper.widthMm, paper.heightMm)

    /** Sheets [widthMm] × [heightMm] (a book's own size). */
    fun write(jpegs: List<ByteArray>, widthMm: Double, heightMm: Double): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun text(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(body: () -> Unit) {
            offsets += out.size()
            text("${offsets.size} 0 obj\n")
            body()
            text("\nendobj\n")
        }
        val w = "%.2f".format(java.util.Locale.ROOT, pt(widthMm))
        val h = "%.2f".format(java.util.Locale.ROOT, pt(heightMm))
        text("%PDF-1.4\n%âãÏÓ\n")
        obj { text("<< /Type /Catalog /Pages 2 0 R >>") }
        val kids = jpegs.indices.joinToString(" ") { "${3 + it * 3} 0 R" }
        obj { text("<< /Type /Pages /Kids [$kids] /Count ${jpegs.size} >>") }
        jpegs.forEachIndexed { i, jpeg ->
            val page = 3 + i * 3
            val image = Image.makeFromEncoded(jpeg)
            val (iw, ih) = image.width to image.height
            image.close()
            obj { text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] /Resources << /XObject << /P$i ${page + 2} 0 R >> >> /Contents ${page + 1} 0 R >>") }
            val draw = "q $w 0 0 $h 0 0 cm /P$i Do Q"
            obj { text("<< /Length ${draw.length} >>\nstream\n$draw\nendstream") }
            obj {
                text("<< /Type /XObject /Subtype /Image /Width $iw /Height $ih /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
                out.write(jpeg)
                text("\nendstream")
            }
        }
        val xref = out.size()
        text("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { text(it.toString().padStart(10, '0') + " 00000 n \n") }
        text("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    /** The pages' pictures of a PDF [write] made, in order. */
    fun read(pdf: ByteArray): List<ByteArray> {
        val text = String(pdf, Charsets.ISO_8859_1)
        return Regex("/Filter /DCTDecode /Length (\\d+) >>\nstream\n").findAll(text).map { m ->
            val start = m.range.last + 1
            pdf.copyOfRange(start, start + m.groupValues[1].toInt())
        }.toList()
    }
}

/**
 * Draws print HTML (export/TripHtml.kt's) on pages of [paper] at [PX_PER_MM]: block by block down the text column,
 * a new page for each section, the footer on every page but the first. [image] reads a photo by the address in the HTML.
 */
private class PageDrawer(private val paper: Paper, private val theme: PrintTheme, private val footer: String, private val image: (String) -> ByteArray?) {
    private val pageW = (paper.widthMm * PX_PER_MM).toInt()
    private val pageH = (paper.heightMm * PX_PER_MM).toInt()
    private val left = (PrintPages.MARGINS_MM.second * PX_PER_MM).toFloat()
    private val top = (PrintPages.MARGINS_MM.first * PX_PER_MM).toFloat()
    private val columnW = (pageW - 2 * left)
    private val bottom = (pageH - PrintPages.MARGINS_MM.third * PX_PER_MM).toFloat()

    private val pages = ArrayList<Surface>()
    private lateinit var canvas: Canvas
    private var y = 0f

    private val fonts = FontCollection().apply {
        val provider = TypefaceFontProvider()
        fun add(file: String, family: String) {
            BtFontFiles.file(file)?.let { provider.registerTypeface(FontMgr.default.makeFromData(Data.makeFromBytes(it)), family) }
        }
        add("literata.ttf", "Literata")
        add("caveat.ttf", "Caveat")
        add("plex_mono_medium.ttf", "Plex Mono")
        add("albert_sans.ttf", "Albert Sans")
        add("barlow_condensed_bold.ttf", "Barlow Condensed")
        setAssetFontManager(provider)
        setDefaultFontManager(FontMgr.default)
    }

    private data class Look(val family: String, val sizePt: Float, val color: Int = INK, val bold: Boolean = false, val italic: Boolean = false, val upper: Boolean = false, val center: Boolean = false)

    fun draw(html: String): List<Image> {
        newPage()
        for (block in Blocks.of(html)) when (block) {
            is Blocks.Block.Page -> if (y > top + 1) newPage()
            is Blocks.Block.Words -> words(block.text, lookOf(block.kind))
            is Blocks.Block.Photos -> photos(block)
            is Blocks.Block.Svg -> svg(block.svg)
        }
        return pages.mapIndexed { i, s ->
            if (i > 0) foot(s.canvas, i + 1)
            s.makeImageSnapshot().also { s.close() }
        }
    }

    private fun newPage() {
        val s = Surface.makeRasterN32Premul(pageW, pageH)
        s.canvas.clear(PrintPages.pageColor(theme).toInt())
        pages += s
        canvas = s.canvas
        y = top
    }

    private fun room(h: Float) {
        if (y + h > bottom && y > top + 1) newPage()
    }

    private fun lookOf(kind: String): Look = when (kind) {
        "h1" -> Look("Literata", 26f, bold = true)
        "sub" -> Look("Literata", 12f, MUTED, italic = true)
        "dates" -> Look("Caveat", 16f, INK)
        "stats", "meta", "facts" -> Look("Plex Mono", 8f, MUTED, upper = true)
        "dayno" -> Look("Plex Mono", 8f, RED, upper = true)
        "date" -> Look("Caveat", 22f)
        "place" -> Look("Literata", 9.5f, MUTED)
        "h2" -> Look("Literata", 16f, bold = true)
        "h3" -> Look("Literata", 13f, bold = true)
        "h4" -> Look("Albert Sans", 9f, MUTED, bold = true, upper = true)
        "when" -> Look("Plex Mono", 7.5f, MUTED, upper = true)
        "caption" -> Look("Literata", 8.5f, MUTED, italic = true, center = true)
        "plan" -> Look("Albert Sans", 9f)
        "quote" -> Look("Literata", 10.5f, MUTED, italic = true)
        "empty" -> Look("Literata", 11f, MUTED, italic = true)
        else -> Look("Literata", 10.5f)
    }

    private fun paragraph(text: String, look: Look, width: Float): Paragraph {
        val style = TextStyle().setFontFamilies(arrayOf(look.family)).setFontSize(look.sizePt * PX_PER_PT).setColor(look.color)
            .setFontStyle(
                when {
                    look.bold && look.italic -> FontStyle.BOLD_ITALIC
                    look.bold -> FontStyle.BOLD
                    look.italic -> FontStyle.ITALIC
                    else -> FontStyle.NORMAL
                },
            )
        val ps = ParagraphStyle().apply {
            textStyle = style
            if (look.center) alignment = org.jetbrains.skia.paragraph.Alignment.CENTER
        }
        return ParagraphBuilder(ps, fonts).run {
            pushStyle(style)
            addText(if (look.upper) text.uppercase() else text)
            popStyle()
            build()
        }.layout(width)
    }

    private fun words(text: String, look: Look) {
        if (text.isBlank()) return
        val p = paragraph(text, look, columnW)
        var shown = 0f
        val lines = p.lineMetrics
        val gap = 2.6f * PX_PER_MM.toFloat()
        // Line by line: what doesn't fit goes on the next page.
        while (shown < p.height - 0.5f) {
            room(minOf(p.height - shown, (lines.firstOrNull()?.height ?: 20.0).toFloat()))
            val fits = lines.map { (it.baseline + it.descent).toFloat() }.filter { it - shown <= bottom - y + 0.5f }.maxOrNull() ?: p.height
            val upTo = if (fits <= shown) p.height else fits
            canvas.save()
            canvas.clipRect(Rect.makeLTRB(left, y, left + columnW, y + (upTo - shown)))
            p.paint(canvas, left, y - shown)
            canvas.restore()
            y += upTo - shown
            shown = upTo
            if (shown < p.height - 0.5f) newPage()
        }
        y += gap
    }

    private fun photos(block: Blocks.Block.Photos) {
        val pics = block.photos.mapNotNull { (src, grow) -> image(src)?.let { Image.makeFromEncoded(it) to grow } }
        if (pics.isEmpty()) return
        val width = (columnW * block.width).coerceAtMost(columnW)
        val gap = if (pics.size > 1) 3f * PX_PER_MM.toFloat() else 0f
        // A row: each picture as wide as its shape asks, all the same height, filling the row.
        val aspects = pics.map { (img, _) -> img.width.toFloat() / img.height }
        val h = (width - gap * (pics.size - 1)) / aspects.sum()
        room(h)
        var x = left + (columnW - width) / 2
        pics.forEachIndexed { i, (img, _) ->
            val w = h * aspects[i]
            canvas.drawImageRect(img, Rect.makeXYWH(x, y, w, h))
            x += w + gap
            img.close()
        }
        y += h + 3f * PX_PER_MM.toFloat()
        if (block.caption.isNotBlank()) words(block.caption, lookOf("caption"))
    }

    private fun svg(text: String) {
        val size = 22f * PX_PER_MM.toFloat()
        room(size)
        runCatching {
            val dom = SVGDOM(Data.makeFromBytes(text.encodeToByteArray()))
            dom.setContainerSize(size, size)
            canvas.save()
            canvas.translate(left, y)
            dom.render(canvas)
            canvas.restore()
            dom.close()
        }
        y += size + 2f * PX_PER_MM.toFloat()
    }

    /** The trip's name on the left and the page's number on the right, under the text column (not on the cover). */
    private fun foot(c: Canvas, number: Int) {
        val look = if (theme == PrintTheme.Postcard) Look("Plex Mono", 7.5f, PrintPages.footerColor(theme).toInt()) else Look("Caveat", 13f, PrintPages.footerColor(theme).toInt())
        val at = bottom + 4f * PX_PER_MM.toFloat()
        paragraph(footer, look, columnW * 0.8f).paint(c, left, at)
        val n = paragraph(number.toString(), look, columnW)
        n.paint(c, left + columnW - n.longestLine, at)
    }

    companion object {
        const val PX_PER_MM = 4.0
        const val PX_PER_PT = (4.0 * 25.4 / 72.0).toFloat()
        const val INK = 0xFF22262A.toInt()
        const val MUTED = 0xFF6A7076.toInt()
        const val RED = 0xFFC4423A.toInt()
    }
}

/** Print HTML read into the blocks [PageDrawer] draws: words with their kind, photos, the day stamp, page breaks. */
private object Blocks {
    sealed interface Block {
        data object Page : Block
        data class Words(val text: String, val kind: String) : Block
        data class Photos(val photos: List<Pair<String, Float>>, val width: Float, val caption: String = "") : Block
        data class Svg(val svg: String) : Block
    }

    private val TAG = Regex("<(/?)([a-zA-Z0-9]+)([^>]*)>")
    private val CLASS = Regex("class=\"([^\"]*)\"")
    private val SRC = Regex("src=\"([^\"]*)\"")
    private val WIDTH = Regex("width:([0-9.]+)%")

    fun of(html: String): List<Block> {
        val body = html.substringAfter("<body").substringAfter('>').substringBefore("</body>")
        val out = ArrayList<Block>()
        val open = ArrayList<Pair<String, String>>()
        val text = StringBuilder()
        var photos: MutableList<Pair<String, Float>>? = null
        var photoWidth = 1f
        var caption = StringBuilder()
        var inCaption = false
        // The day stamp's SVG is kept from [svgStart]; the flags' and the plane mark's are skipped.
        var svgStart = -1
        var skipping = false
        fun kind(): String {
            for ((tag, cls) in open.asReversed()) {
                val c = cls.split(' ')
                when {
                    tag == "h1" -> return "h1"
                    "sub" in c -> return "sub"
                    "dates" in c -> return "dates"
                    "stats" in c || "meta" in c || "facts" in c -> return "stats"
                    "dayno" in c -> return "dayno"
                    "date" in c -> return "date"
                    "place" in c -> return "place"
                    "when" in c -> return "when"
                    "empty" in c -> return "empty"
                    "plan-list" in c || "plan" in c && tag == "section" -> return "plan"
                    tag == "blockquote" -> return "quote"
                    tag in setOf("h2", "h3", "h4") -> return tag
                }
            }
            return "p"
        }
        fun flush() {
            val t = RichHtml.decodeEntities(text.toString()).replace(Regex("\\s+"), " ").trim()
            if (t.isNotEmpty()) out += Block.Words(t, kind())
            text.clear()
        }
        var at = 0
        for (m in TAG.findAll(body)) {
            val chunk = body.substring(at, m.range.first)
            at = m.range.last + 1
            if (svgStart >= 0 || skipping) {
                if (m.groupValues[1] == "/" && m.groupValues[2] == "svg") {
                    if (svgStart >= 0) out += Block.Svg(body.substring(svgStart, m.range.last + 1))
                    svgStart = -1
                    skipping = false
                }
                continue
            }
            if (inCaption) caption.append(chunk) else text.append(chunk)
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            val cls = CLASS.find(attrs)?.groupValues?.get(1).orEmpty()
            when {
                tag == "svg" && !closing -> {
                    flush()
                    if (open.any { it.second.contains("daystamp") }) svgStart = m.range.first else skipping = true
                }
                tag == "span" -> if (closing) {
                    text.append("  ")
                    if (open.lastOrNull()?.first == "span") open.removeAt(open.lastIndex)
                } else {
                    // A span starts a new part of the line (the plan's time, title, note and kind).
                    text.append(' ')
                    open += tag to cls
                }
                tag == "br" -> text.append('\n')
                tag == "img" -> {
                    val src = SRC.find(attrs)?.groupValues?.get(1)?.let(RichHtml::decodeEntities)
                    if (src != null) {
                        val list = photos ?: mutableListOf<Pair<String, Float>>().also { photos = it }
                        list += src to 1f
                        WIDTH.find(attrs)?.groupValues?.get(1)?.toFloatOrNull()?.let { photoWidth = it / 100f }
                    }
                }
                tag == "figcaption" -> inCaption = !closing
                !closing && (tag == "section") && (cls.split(' ').contains("day") || cls.split(' ').contains("cover")) -> {
                    flush()
                    out += Block.Page
                }
                !closing && tag == "hr" && attrs.contains("page-break-before") -> {
                    flush()
                    out += Block.Page
                }
                !closing && attrs.contains("break-before:page") -> {
                    flush()
                    out += Block.Page
                }
                !closing && tag == "figure" -> {
                    WIDTH.find(attrs)?.groupValues?.get(1)?.toFloatOrNull()?.let { photoWidth = it / 100f }
                    if (open.none { it.second.split(' ').contains("row") }) {
                        flush()
                    }
                }
                !closing && cls.split(' ').contains("row") -> {
                    flush()
                    photos = mutableListOf()
                    photoWidth = WIDTH.find(attrs)?.groupValues?.get(1)?.toFloatOrNull()?.div(100f) ?: 1f
                    open += tag to cls
                }
                closing && (tag == "figure" || tag == "div") -> {
                    val inRow = open.lastOrNull()?.second?.split(' ')?.contains("row") == true
                    if (tag == "figure" && !inRow || tag == "div" && inRow) {
                        photos?.takeIf { it.isNotEmpty() }?.let { out += Block.Photos(it.toList(), photoWidth, RichHtml.decodeEntities(caption.toString()).trim()) }
                        photos = null
                        photoWidth = 1f
                        caption = StringBuilder()
                    }
                    if (tag == "div") {
                        flush()
                        if (open.isNotEmpty()) open.removeAt(open.lastIndex)
                    }
                }
                tag in BLOCKS -> {
                    flush()
                    if (closing) {
                        val i = open.indexOfLast { it.first == tag }
                        if (i >= 0) while (open.size > i) open.removeAt(open.lastIndex)
                    } else open += tag to cls
                }
            }
        }
        flush()
        return out
    }

    private val BLOCKS = setOf("p", "h1", "h2", "h3", "h4", "li", "ul", "ol", "blockquote", "section", "article", "header", "div", "table", "tr", "td")
}
