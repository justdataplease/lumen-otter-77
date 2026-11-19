package com.justdataplease.boringtravel.export

import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.PrintTheme
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToLong

/**
 * The trip as a Word file (.docx), written on the phone with no library (owner, 2026-11-12: "maybe be able to save to
 * docx"): a zip of WordprocessingML written by hand. The name, dates, cover photo and numbers; then each chosen day on
 * a new page under a heading, with its notes (title, time and place, the words with bold, italic, underline and
 * strike-through as written, headings, lists, checklists, quotes, page breaks) and its photos whole: one alone up to
 * a height, more side by side in rows ([PhotoRows]) across the page; the plan as simple lines.
 *
 * Photos come from [write]'s `photo` (JPEG files already scaled down) and are streamed into the zip one by one after
 * the text, so a long trip never sits in memory. Pure JVM.
 */
object TripDocx {
    const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** A photo ready for the file: a JPEG and its size in pixels as it shows (turned the right way up). */
    data class Photo(val file: File, val width: Int, val height: Int)

    /** Page margins all round. */
    const val MARGIN_MM = 20.0

    private const val EMU_PER_MM = 36_000.0
    private const val TWIPS_PER_MM = 1440 / 25.4

    /** Space between photos in a row, and how much narrower than the line a row is drawn, so Word never wraps it. */
    private const val GAP_MM = 2.0
    private const val ROW_SLACK = 0.98

    fun write(
        book: TripBook,
        options: ExportOptions,
        out: OutputStream,
        photo: (Media) -> Photo?,
        locale: Locale = Locale.getDefault(),
        labels: PrintLabels = PrintLabels(),
    ) {
        val zip = ZipOutputStream(out)
        val doc = DocWriter(book, options, photo, locale, labels)
        zip.part("[Content_Types].xml", CONTENT_TYPES)
        zip.part("_rels/.rels", ROOT_RELS)
        zip.part("docProps/core.xml", core(book.trip.title))
        zip.part("word/styles.xml", styles(options.theme, locale))
        zip.putNextEntry(ZipEntry("word/document.xml"))
        val w = OutputStreamWriter(NoClose(zip), Charsets.UTF_8).buffered(64 * 1024)
        doc.document(w)
        w.flush()
        zip.closeEntry()
        zip.part("word/_rels/document.xml.rels", doc.rels())
        doc.images.values.forEach { img ->
            zip.putNextEntry(ZipEntry("word/media/${img.name}"))
            img.photo.file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    private fun ZipOutputStream.part(name: String, xml: String) {
        putNextEntry(ZipEntry(name))
        write(xml.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /** Lets a writer flush into the zip entry without closing the zip. */
    private class NoClose(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
        override fun close() = out.flush()
    }

    private class Image(val rid: String, val name: String, val photo: Photo)

    /** A stretch of text with its marks, or a line break. */
    internal data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val strike: Boolean = false, val br: Boolean = false)

    private class DocWriter(
        val book: TripBook,
        val o: ExportOptions,
        val photoOf: (Media) -> Photo?,
        val locale: Locale,
        val l: PrintLabels,
    ) {
        val images = LinkedHashMap<String, Image>()
        private val prepared = HashMap<String, Photo?>()
        private var drawings = 0
        private val trip = book.trip
        private val days = book.days(o)
        private val textMm = o.paper.widthMm - 2 * MARGIN_MM
        private val scale = PhotoRows.scale(o.photoSize)
        private val longDate = DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale)
        private val hhmm = DateTimeFormatter.ofPattern("HH:mm", locale)
        private lateinit var w: Writer

        private fun photo(m: Media): Photo? = prepared.getOrPut(m.id) { photoOf(m)?.takeIf { it.width > 0 && it.height > 0 && it.file.isFile } }
        private fun aspect(p: Photo) = PhotoRows.clean(p.width.toDouble() / p.height)

        fun document(out: Writer) {
            w = out
            w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            w.write("\n<w:document xmlns:w=\"$W_NS\" xmlns:r=\"$R_NS\" xmlns:wp=\"$WP_NS\" xmlns:a=\"$A_NS\" xmlns:pic=\"$PIC_NS\"><w:body>")
            cover()
            days.forEach(::day)
            val ideas = if (o.includePlan && o.wholeTrip) book.ideas() else emptyList()
            if (ideas.isNotEmpty()) {
                para("Heading1", pageBreak = true) { text(l.ideas) }
                planLines(ideas)
            }
            val (pw, ph) = when (o.paper) {
                Paper.A4 -> 11906 to 16838
                Paper.Letter -> 12240 to 15840
            }
            val m = twips(MARGIN_MM)
            w.write("<w:sectPr><w:pgSz w:w=\"$pw\" w:h=\"$ph\"/><w:pgMar w:top=\"$m\" w:right=\"$m\" w:bottom=\"$m\" w:left=\"$m\" w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/></w:sectPr>")
            w.write("</w:body></w:document>")
        }

        fun rels(): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
            append("<Relationships xmlns=\"$REL_NS\">")
            append("<Relationship Id=\"rIdStyles\" Type=\"$OD_REL/styles\" Target=\"styles.xml\"/>")
            images.values.forEach { append("<Relationship Id=\"").append(it.rid).append("\" Type=\"$OD_REL/image\" Target=\"media/").append(it.name).append("\"/>") }
            append("</Relationships>")
        }

        // ------------------------------------------------------------ parts of the trip

        private fun cover() {
            para("Title") { text(trip.title) }
            if (trip.subtitle.isNotBlank()) para("Subtitle") { text(trip.subtitle) }
            dateRange()?.let { para("Meta") { text(it) } }
            val cover = trip.coverMediaId?.let(book::media)?.takeIf { photo(it) != null }
                ?: days.asSequence().flatMap { it.photos }.firstOrNull { photo(it) != null }
            cover?.let { alone(it, "", maxMm = 150.0, share = 1.0) }
            val stats = buildList {
                val n = if (o.wholeTrip) trip.days ?: days.size else days.size
                if (n > 0) add("$n ${l.days.lowercase(locale)}")
                val p = days.sumOf { d -> d.photos.count { !it.isVideo && photo(it) != null } }
                if (p > 0) add("$p ${l.photos.lowercase(locale)}")
            }
            if (stats.isNotEmpty()) para("Meta") { text(stats.joinToString(" · ")) }
            if (days.isEmpty()) para(null) { text(l.nothingYet) }
        }

        private fun day(d: BookDay) {
            val number = d.number?.let { n -> if (d.total != null && d.total > 0) "${l.day} $n ${l.of} ${d.total}" else "${l.day} $n" }
            para("Heading1", pageBreak = true) { text(listOfNotNull(number, longDate.format(d.date), d.place).joinToString(" · ")) }
            d.entries.forEach { be ->
                val e = be.entry
                if (e.title.isNotBlank()) para("Heading2") { text(e.title) }
                val zone = runCatching { ZoneId.of(e.zoneId) }.getOrDefault(book.zone)
                para("Meta") { text(listOf(hhmm.format(Instant.ofEpochMilli(e.occurredAt).atZone(zone)), e.placeName.trim()).filter { it.isNotEmpty() }.joinToString(" · ")) }
                note(be.doc)
            }
            photos(d.loosePhotos.map { it to "" })
            if (o.includePlan && d.plan.isNotEmpty()) {
                para("Heading3") { text(l.plan) }
                planLines(d.plan)
            }
        }

        private fun planLines(items: List<PlanItem>) = items.forEach { p ->
            val what = listOfNotNull(p.title.trim(), PlanSpan.until(p), p.placeName.trim().takeIf { it.isNotEmpty() && it != p.title.trim() })
                .filter { it.isNotEmpty() }.joinToString(" · ")
            para("Plan") {
                val time = spanTime(p)
                if (time.isNotEmpty()) run(Run(time, bold = true))
                if (time.isNotEmpty() && what.isNotEmpty()) w.write("<w:r><w:tab/></w:r>")
                text(what)
            }
        }

        /** A note's blocks in order; its photos whole, standing together in rows when nothing is written between them. */
        private fun note(doc: EntryDoc) {
            var number = 0
            TripHtml.noteParts(doc).forEach { part ->
                if (part.photos.isNotEmpty()) {
                    val single = part.photos.singleOrNull() as? Block.Photo
                    if (single != null) {
                        book.media(single.mediaId)?.let { alone(it, single.caption, scale.singleMm, single.width.coerceIn(0.3f, 1f) * scale.share) }
                    } else {
                        photos(part.photos.flatMap { b ->
                            when (b) {
                                is Block.Photo -> listOfNotNull(book.media(b.mediaId)?.let { it to b.caption })
                                is Block.Album -> b.mediaIds.mapNotNull { id -> book.media(id)?.let { it to "" } }
                                else -> emptyList()
                            }
                        })
                    }
                    number = 0
                    return@forEach
                }
                part.text.forEach { b ->
                    number = if (b is Block.Paragraph && b.list == ListStyle.Number) number + 1 else 0
                    when (b) {
                        is Block.Paragraph -> when (b.list) {
                            null -> para(null) { runs(b.html) }
                            ListStyle.Bullet -> para("List") { text("•"); w.write("<w:r><w:tab/></w:r>"); runs(b.html) }
                            ListStyle.Number -> para("List") { text("$number."); w.write("<w:r><w:tab/></w:r>"); runs(b.html) }
                        }
                        is Block.Heading -> para("Heading3") { text(b.text) }
                        is Block.Quote -> para("Quote") { runs(b.html) }
                        is Block.Checklist -> b.items.forEach { item -> para("List") { text(if (item.done) "☑" else "☐"); w.write("<w:r><w:tab/></w:r>"); text(item.text) } }
                        is Block.Place -> para("Meta") { text(b.name) }
                        Block.Divider -> para("Divider") {}
                        Block.PageBreak -> para(null) { w.write("<w:r><w:br w:type=\"page\"/></w:r>") }
                        is Block.Photo, is Block.Album, is Block.Unknown -> Unit
                    }
                }
            }
        }

        // ------------------------------------------------------------ photos

        /** One photo by itself, centred: [share] of the line at most, never taller than [maxMm]. */
        private fun alone(m: Media, caption: String, maxMm: Double, share: Double) {
            val p = photo(m) ?: return
            val wMm = textMm * PhotoRows.single(aspect(p), textMm, maxMm, share)
            para("Photo") { drawing(m, p, wMm, wMm / aspect(p)) }
            if (caption.isNotBlank()) para("Caption") { text(caption) }
        }

        /** Photos side by side, each row a centred line of pictures of one height; captions under the row. */
        private fun photos(list: List<Pair<Media, String>>) {
            val shown = list.filter { photo(it.first) != null }
            if (shown.isEmpty()) return
            if (shown.size == 1) return alone(shown[0].first, shown[0].second, scale.singleMm, scale.share)
            val width = textMm * ROW_SLACK
            PhotoRows.rows(shown.map { aspect(photo(it.first)!!) }, width, scale.rowMm, GAP_MM).forEach { r ->
                para("Photo") {
                    for (i in r.from until r.to) {
                        val (m, _) = shown[i]
                        val p = photo(m)!!
                        if (i > r.from) w.write("<w:r><w:rPr><w:sz w:val=\"12\"/></w:rPr><w:t xml:space=\"preserve\"> </w:t></w:r>")
                        drawing(m, p, r.height * aspect(p), r.height)
                    }
                }
                val caps = (r.from until r.to).map { shown[it].second }.filter { it.isNotBlank() }
                if (caps.isNotEmpty()) para("Caption") { text(caps.joinToString(" · ")) }
            }
        }

        private fun drawing(m: Media, p: Photo, wMm: Double, hMm: Double) {
            val img = images.getOrPut(m.id) { Image("rIdImg${images.size + 1}", "image${images.size + 1}.jpeg", p) }
            val n = ++drawings
            val cx = (wMm * EMU_PER_MM).roundToLong().coerceAtLeast(1)
            val cy = (hMm * EMU_PER_MM).roundToLong().coerceAtLeast(1)
            w.write("<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>")
            w.write("<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:docPr id=\"$n\" name=\"Picture $n\"/>")
            w.write("<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
            w.write("<a:graphic><a:graphicData uri=\"$PIC_NS\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"${img.name}\"/><pic:cNvPicPr/></pic:nvPicPr>")
            w.write("<pic:blipFill><a:blip r:embed=\"${img.rid}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
            w.write("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
            w.write("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>")
        }

        // ------------------------------------------------------------ paragraphs and runs

        private fun para(style: String?, pageBreak: Boolean = false, body: () -> Unit) {
            w.write("<w:p>")
            if (style != null || pageBreak) {
                w.write("<w:pPr>")
                if (style != null) w.write("<w:pStyle w:val=\"$style\"/>")
                if (pageBreak) w.write("<w:pageBreakBefore/>")
                w.write("</w:pPr>")
            }
            body()
            w.write("</w:p>")
        }

        private fun text(s: String) = s.split('\n').forEachIndexed { i, line ->
            if (i > 0) run(Run("", br = true))
            if (line.isNotEmpty()) run(Run(line))
        }

        private fun runs(html: String) = richRuns(html).forEach { run(it) }

        private fun run(r: Run) {
            if (r.br) { w.write("<w:r><w:br/></w:r>"); return }
            w.write("<w:r>")
            if (r.bold || r.italic || r.underline || r.strike) {
                w.write("<w:rPr>")
                if (r.bold) w.write("<w:b/>")
                if (r.italic) w.write("<w:i/>")
                if (r.strike) w.write("<w:strike/>")
                if (r.underline) w.write("<w:u w:val=\"single\"/>")
                w.write("</w:rPr>")
            }
            w.write("<w:t xml:space=\"preserve\">")
            w.write(xml(r.text))
            w.write("</w:t></w:r>")
        }

        private fun dateRange(): String? {
            val s = trip.startDate ?: days.firstOrNull()?.date ?: return null
            val e = trip.endDate ?: days.lastOrNull()?.date ?: s
            val from = o.from?.takeIf { it.isAfter(s) } ?: s
            val to = o.to?.takeIf { it.isBefore(e) } ?: e
            return TripHtml.range(from, to, locale)
        }
    }

    private val tag = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*>")
    private val numericEntity = Regex("&#(x[0-9a-fA-F]+|\\d+);")

    /** A note's sanitized HTML ([RichHtml]) as runs: bold, italic, underline and strike-through as marked; br as a break. */
    internal fun richRuns(html: String): List<Run> {
        val out = ArrayList<Run>()
        var b = 0
        var i = 0
        var u = 0
        var s = 0
        var last = 0
        fun flush(text: String) {
            val t = decode(text)
            if (t.isNotEmpty()) out += Run(t, bold = b > 0, italic = i > 0, underline = u > 0, strike = s > 0)
        }
        for (m in tag.findAll(html)) {
            flush(html.substring(last, m.range.first))
            last = m.range.last + 1
            val step = if (m.groupValues[1] == "/") -1 else 1
            when (m.groupValues[2].lowercase()) {
                "b", "strong" -> b = (b + step).coerceAtLeast(0)
                "i", "em" -> i = (i + step).coerceAtLeast(0)
                "u" -> u = (u + step).coerceAtLeast(0)
                "s", "del" -> s = (s + step).coerceAtLeast(0)
                "br" -> if (step > 0) out += Run("", br = true)
            }
        }
        flush(html.substring(last))
        return out
    }

    private fun decode(s: String): String = RichHtml.decodeEntities(
        numericEntity.replace(s) { m ->
            val v = m.groupValues[1]
            val code = if (v.startsWith("x")) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else ""
        },
    )

    /** Text made safe for XML: the five entities escaped, characters XML 1.0 can't hold dropped. */
    internal fun xml(s: String): String = buildString(s.length + 16) {
        var k = 0
        while (k < s.length) {
            val c = s[k]
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\t' -> append(' ')
                c.code < 0x20 || c == '￾' || c == '￿' -> Unit
                c.isHighSurrogate() && k + 1 < s.length && s[k + 1].isLowSurrogate() -> { append(c).append(s[k + 1]); k++ }
                c.isSurrogate() -> Unit
                else -> append(c)
            }
            k++
        }
    }

    private fun twips(mm: Double) = (mm * TWIPS_PER_MM).roundToLong()

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OD_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpeg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""

    private fun core(title: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${xml(title)}</dc:title></cp:coreProperties>"""

    /** The styles the document uses: a serif face (sans for Minimal), headings, grey small lines, captions, lists. */
    private fun styles(theme: PrintTheme, locale: Locale): String {
        val font = if (theme == PrintTheme.Minimal) "Arial" else "Georgia"
        val lang = locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" } ?: "en-GB"
        fun para(id: String, name: String, ppr: String, rpr: String) =
            "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"$name\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr>$ppr</w:pPr><w:rPr>$rpr</w:rPr></w:style>"
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="$W_NS"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="$font" w:hAnsi="$font" w:eastAsia="$font" w:cs="$font"/><w:sz w:val="21"/><w:szCs w:val="21"/><w:lang w:val="${xml(lang)}"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            para("Title", "Title", "<w:spacing w:after=\"80\"/>", "<w:b/><w:color w:val=\"22262A\"/><w:sz w:val=\"56\"/><w:szCs w:val=\"56\"/>") +
            para("Subtitle", "Subtitle", "<w:spacing w:after=\"80\"/>", "<w:i/><w:color w:val=\"3F464C\"/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/>") +
            para("Heading1", "heading 1", "<w:keepNext/><w:spacing w:before=\"0\" w:after=\"200\"/><w:outlineLvl w:val=\"0\"/>", "<w:b/><w:sz w:val=\"34\"/><w:szCs w:val=\"34\"/>") +
            para("Heading2", "heading 2", "<w:keepNext/><w:spacing w:before=\"280\" w:after=\"40\"/><w:outlineLvl w:val=\"1\"/>", "<w:b/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/>") +
            para("Heading3", "heading 3", "<w:keepNext/><w:spacing w:before=\"200\" w:after=\"60\"/><w:outlineLvl w:val=\"2\"/>", "<w:b/><w:sz w:val=\"23\"/><w:szCs w:val=\"23\"/>") +
            para("Meta", "Meta", "<w:keepNext/><w:spacing w:after=\"100\"/>", "<w:color w:val=\"6A7076\"/><w:sz w:val=\"17\"/><w:szCs w:val=\"17\"/>") +
            para("Photo", "Photo", "<w:spacing w:before=\"120\" w:after=\"120\" w:line=\"240\" w:lineRule=\"auto\"/><w:jc w:val=\"center\"/>", "") +
            para("Caption", "Photo caption", "<w:spacing w:after=\"160\"/><w:jc w:val=\"center\"/>", "<w:i/><w:color w:val=\"6A7076\"/><w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/>") +
            para("List", "Note list", "<w:spacing w:after=\"60\"/><w:ind w:left=\"360\" w:hanging=\"360\"/>", "") +
            para("Quote", "Note quote", "<w:pBdr><w:left w:val=\"single\" w:sz=\"12\" w:space=\"8\" w:color=\"9AA0A6\"/></w:pBdr><w:ind w:left=\"284\"/>", "<w:i/>") +
            para("Divider", "Note divider", "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"1\" w:color=\"DADDE0\"/></w:pBdr><w:spacing w:after=\"200\"/>", "") +
            para("Plan", "Plan line", "<w:tabs><w:tab w:val=\"left\" w:pos=\"1418\"/></w:tabs><w:spacing w:after=\"40\"/><w:ind w:left=\"1418\" w:hanging=\"1418\"/>", "<w:sz w:val=\"19\"/><w:szCs w:val=\"19\"/>") +
            "</w:styles>"
    }
}
