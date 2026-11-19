package com.justdataplease.boringtravel.ios

import kotlin.math.roundToInt

/**
 * What the export's HTML needs changed before WebKit prints it on iPhone (Android prints the same HTML with Chromium,
 * which needs none of this). Plain text in, plain text out, so the desktop tests check it against Android's own
 * stylesheet.
 */
object WebKitPrint {
    /**
     * A marker's band as Android's stylesheet writes it: clear down to a height, the colour from there, and in the long
     * form clear again below ("background:linear-gradient(transparent 52%,#F7E36A 52%,#F7E36A 92%,transparent 92%)").
     */
    private val BAND = Regex(
        """background:linear-gradient\(transparent (\d+)%,(#[0-9A-Fa-f]{3,8}|var\(--[a-z0-9-]+\)) (\d+)%(?:,(#[0-9A-Fa-f]{3,8}|var\(--[a-z0-9-]+\)) (\d+)%,transparent (\d+)%)?\)""",
    )

    /**
     * The markers' bands without a clear stop. WebKit's print to PDF draws the clear part of such a gradient black: the
     * Simulator's first printed page (19 Nov 2026) had a black bar over every day's title. The same band is one flat
     * colour, as tall as the band and placed where it began. Any other gradient is left as it is.
     */
    fun solidBands(css: String): String = BAND.replace(css) { m ->
        val g = m.groupValues
        val from = g[1].toInt()
        val long = g[4].isNotEmpty()
        val to = if (long) g[5].toInt() else 100
        val size = to - from
        val one = g[3].toInt() == from && (!long || (g[4].equals(g[2], ignoreCase = true) && g[6].toInt() == to))
        if (!one || size <= 0 || size >= 100) m.value
        else "background:linear-gradient(${g[2]},${g[2]}) no-repeat 0 ${position(from, size)}%/100% $size%"
    }

    /** background-position for a picture [size]% tall whose top sits [from]% down: the percentage is of the room left. */
    private fun position(from: Int, size: Int): String {
        val tenths = (from * 1000.0 / (100 - size)).roundToInt()
        return "${tenths / 10}.${tenths % 10}"
    }

    /**
     * Ruled paper as Android's stylesheet writes it: clear, then a faint line, repeated down the page
     * ("repeating-linear-gradient(180deg,transparent 0,transparent 6.55mm,rgba(90,96,110,.24) 6.55mm,rgba(…) 6.75mm)").
     */
    private val RULED = Regex(
        """repeating-linear-gradient\(180deg,transparent 0,transparent ([\d.]+mm),rgba\((\d+),(\d+),(\d+),(\d*\.\d+)\) ([\d.]+mm),rgba\((\d+),(\d+),(\d+),(\d*\.\d+)\) ([\d.]+mm)\)(;background-size:auto)?""",
    )

    /** The paper's own colour, which the ruled lines lie on ("body{background-color:#FCFCF9;…"). */
    private val PAPER = Regex("""body\{background-color:#([0-9A-Fa-f]{6})""")

    /**
     * Ruled paper without clear or see-through stops. WebKit's print to PDF draws a line gradient's colours solid, so
     * the clear gaps between the lines came out black and the whole page with them (the Simulator's first ruled page,
     * 19 Nov 2026). The gaps become the paper's colour and the line the colour it has on that paper. Left as it is when
     * the stylesheet names no paper colour before it. And one line's tile repeated down the page rather than a
     * repeating gradient: with solid stops WebKit printed the repeating form as a few stray bars (the next Mac run),
     * where it prints the dotted paper's small repeated tiles right.
     */
    fun solidRuled(css: String): String = RULED.replace(css) { m ->
        val g = m.groupValues
        val paper = PAPER.findAll(css.substring(0, m.range.first)).lastOrNull()?.groupValues?.get(1)
        val sameLine = g[2] == g[7] && g[3] == g[8] && g[4] == g[9] && g[5] == g[10] && g[1] == g[6]
        val alpha = g[5].toDoubleOrNull()
        if (paper == null || !sameLine || alpha == null || alpha !in 0.0..1.0) return@replace m.value
        val line = (0..2).joinToString("") { i ->
            val under = paper.substring(i * 2, i * 2 + 2).toInt(16)
            val over = g[2 + i].toInt()
            (alpha * over + (1 - alpha) * under).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
        }
        "linear-gradient(180deg,#$paper 0,#$paper ${g[1]},#$line ${g[1]},#$line ${g[11]});background-size:100% ${g[11]}"
    }

    /**
     * [html] as WebKit should print it: the bands and the ruled paper made solid, and the page's scale. WebKit lays a
     * printed page out 1.25 times as wide as the paper in CSS pixels (WebCore's printingMinimumShrinkFactor), so a CSS
     * millimetre would print 1/15 too big and the cover, as tall as the page, would run onto the next one: a zoom of
     * 15/16 makes a millimetre a millimetre again. The margins are the renderer's printable area, so a WebKit that
     * applies the CSS's @page margins too mustn't add them again.
     */
    fun forWebKit(html: String): String =
        solidRuled(solidBands(html)).replaceFirst("</head>", "<style>html{zoom:0.9375}@page{margin:0}</style>\n</head>")

    // ------------------------------------------------------------------------------------------------- the book

    /**
     * The scrim under words on a photo as the book's stylesheet writes it (book/BookCss.kt `.shade`, the foot of a
     * "Full page with caption"): clear at the top, black at 62 % at the foot.
     */
    private val SCRIM = Regex("""background:linear-gradient\(rgba\(0,0,0,0\),rgba\(0,0,0,(\d*\.?\d+)\)\)""")

    /**
     * The scrim as a picture of itself: on a photo there is no single paper colour to blend a gap with, so the gradient
     * is drawn once ([ScrimPicture], Skia, in common code) as a PNG 2 px wide and [ScrimPicture.STEPS] tall, clear at the
     * top and the stylesheet's black at the foot, and stretched over the box. WebKit prints a picture's own see-through
     * pixels as they are (a PNG's alpha becomes the PDF's soft mask), where it prints a gradient's clear stop black.
     * [picture] makes the PNG for an opacity; left as it is when it can't be made.
     */
    fun scrimAsPicture(css: String, picture: (Double) -> ByteArray? = ScrimPicture::png): String = SCRIM.replace(css) { m ->
        val alpha = m.groupValues[1].toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: return@replace m.value
        val png = picture(alpha) ?: return@replace m.value
        "background:url(data:image/png;base64,${com.justdataplease.boringtravel.compat.Base64.getEncoder().encodeToString(png)}) 0 0/100% 100% no-repeat"
    }

    /**
     * The book's page HTML as WebKit should draw it, on screen and on paper: every line gradient with a clear stop
     * replaced by a form WebKit prints right. The highlighter's bands (`.mk`, the mono dates' `.mk`, a note's bold
     * words in the notebooks; `var(--hl)`) become one flat colour sized and placed where the band was ([solidBands]);
     * the scrim over a photo becomes a picture of itself ([scrimAsPicture]).
     */
    fun forBook(html: String): String = scrimAsPicture(solidBands(html))

    /**
     * [forBook] for the book's own page on screen (ui/book/PagePreview.kt): the page's CSS pixels are the screen's
     * points (a phone's web view lays a page out 980 px wide without this), so the page's zoom fits it to the view.
     */
    fun forBookScreen(html: String): String = forBook(html).replaceFirst(
        "<meta charset=\"utf-8\">",
        "<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">",
    )

    /**
     * [forBook] for paper, the book's sheets [widthMm] × [heightMm] (book/PageGeometry.kt's Sheet). WebKit's print
     * lays a page out [PRINT_SHRINK] times as wide as the paper in CSS pixels, rounded down to a whole pixel, and the
     * page as tall as that width keeps the paper's shape, rounded down again; a page is cut wherever a pixel more would
     * not fit. So: a zoom that makes a CSS millimetre a printed millimetre on that rounded width ([BookPrintScale.zoom]),
     * and each page's section a pixel shorter than WebKit's page ([BookPrintScale.sectionMm]), so a section never runs
     * onto the next sheet and pushes the rest one sheet on (a sheet's last few tenths of a millimetre are the ground
     * colour the PDF is drawn on). The sheets have no margins of their own.
     */
    fun forBookPrint(html: String, widthMm: Double, heightMm: Double): String {
        val s = BookPrintScale(widthMm, heightMm)
        val css = "<style>html{zoom:${fixed(s.zoom, 6)}}@page{size:${fixed(widthMm, 2)}mm ${fixed(heightMm, 2)}mm;margin:0}" +
            ".pg{width:${fixed(widthMm, 3)}mm!important;height:${fixed(s.sectionMm, 3)}mm!important}</style>\n</head>"
        return forBook(html).replaceFirst("</head>", css)
    }

    private fun fixed(v: Double, decimals: Int): String = JavaText.fixed(v, decimals)

    /** How much wider than the paper WebKit lays a printed page out (WebCore's printingMinimumShrinkFactor). */
    const val PRINT_SHRINK = 1.25
}

/**
 * WebKit's printed page for a book sheet [widthMm] × [heightMm] ([WebKitPrint.forBookPrint]): its width in layout
 * pixels ([layoutWidth], the paper's points times [WebKitPrint.PRINT_SHRINK], rounded down), its height ([layoutHeight],
 * that width in the paper's shape, rounded down), the zoom that makes a CSS mm a printed mm on it, and the section's
 * height in CSS mm that fits a page with a pixel to spare.
 */
class BookPrintScale(val widthMm: Double, val heightMm: Double) {
    private val widthPt = widthMm * 72.0 / 25.4
    private val heightPt = heightMm * 72.0 / 25.4
    val layoutWidth: Double = kotlin.math.floor(widthPt * WebKitPrint.PRINT_SHRINK)
    val layoutHeight: Double = kotlin.math.floor(layoutWidth * heightPt / widthPt)

    /** CSS pixels to layout pixels: a CSS mm (96 / 25.4 px) becomes the layout pixels of a printed mm. */
    val zoom: Double = 0.75 * layoutWidth / widthPt

    /** Layout pixels per CSS mm at [zoom]. */
    private val pxPerMm: Double = 96.0 / 25.4 * zoom

    /** The section's height: a page's layout pixels less one, in CSS mm. */
    val sectionMm: Double = (layoutHeight - 1.0) / pxPerMm
}
