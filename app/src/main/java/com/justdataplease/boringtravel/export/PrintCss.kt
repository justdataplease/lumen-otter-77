package com.justdataplease.boringtravel.export

import com.justdataplease.boringtravel.model.PrintTheme

/**
 * The print styles' CSS (design canvas, board "Print themes for PDF"). Fixed print colours, not the app's look:
 * any look can print with any style. Sizes in mm and pt so A4 and Letter print the same.
 *
 * Photos are never cropped: no fixed heights with `object-fit: cover`. Their widths come from [TripHtml] (worked out
 * from each photo's shape), their heights follow.
 */
internal object PrintCss {
    const val MARGIN_TOP_MM = 16.0
    const val MARGIN_SIDE_MM = 16.0
    const val MARGIN_BOTTOM_MM = 18.0

    /** The white border on each side of a print (Notebook) or polaroid (Scrapbook) in a row of photos. */
    const val FRAME_MM = 2.0

    /** Bundled OFL fonts, loaded by the WebView from the app's resources (`file:///android_res/font/`). */
    fun fonts(base: String): String {
        require('\'' !in base && ')' !in base) { "font base must be a plain URL" }
        fun face(family: String, file: String, weight: String, style: String = "normal") =
            "@font-face{font-family:'$family';src:url('$base$file');font-weight:$weight;font-style:$style}\n"
        return face("Literata", "literata.ttf", "200 900") +
            face("Literata", "literata_italic.ttf", "200 900", "italic") +
            face("Albert Sans", "albert_sans.ttf", "100 900") +
            face("Caveat", "caveat.ttf", "400 700") +
            face("IBM Plex Mono", "plex_mono_regular.ttf", "400") +
            face("IBM Plex Mono", "plex_mono_medium.ttf", "500") +
            face("IBM Plex Mono", "plex_mono_semibold.ttf", "600") +
            face("Barlow", "barlow_regular.ttf", "400") +
            face("Barlow", "barlow_medium.ttf", "500") +
            face("Barlow", "barlow_semibold.ttf", "600") +
            face("Barlow Condensed", "barlow_condensed_semibold.ttf", "600") +
            face("Barlow Condensed", "barlow_condensed_bold.ttf", "700")
    }

    /** `@page` rules: paper, margins, the footer (title left, page number right) and no footer on the cover. */
    fun page(paper: Paper, theme: PrintTheme, footer: String): String {
        val footFont = when (theme) {
            PrintTheme.Scrapbook, PrintTheme.Notebook, PrintTheme.Minimal -> "font:600 13pt 'Caveat',cursive;color:#525C86"
            else -> "font:500 7.5pt 'IBM Plex Mono',monospace;color:#8A9096;letter-spacing:.04em"
        }
        val pageH = paper.heightMm - MARGIN_TOP_MM - MARGIN_BOTTOM_MM
        val bg = if (theme == PrintTheme.Scrapbook || theme == PrintTheme.Notebook) "background:#FCFCF9;" else ""
        return """
@page{size:${paper.css};margin:${MARGIN_TOP_MM}mm ${MARGIN_SIDE_MM}mm ${MARGIN_BOTTOM_MM}mm;$bg
@bottom-left{content:${TripHtml.cssString(footer)};$footFont;vertical-align:top;padding-top:4mm}
@bottom-right{content:counter(page);$footFont;vertical-align:top;padding-top:4mm}}
@page :first{@bottom-left{content:none}@bottom-right{content:none}}
:root{--page-h:${pageH}mm}
"""
    }

    /** Shared by all the styles. */
    const val BASE = """
*{box-sizing:border-box;-webkit-print-color-adjust:exact;print-color-adjust:exact}
html,body{margin:0;padding:0}
body{font-size:10.5pt;line-height:1.6;orphans:3;widows:3}
img{max-width:100%}
a{color:inherit}
h1,h2,h3,h4{margin:0;break-after:avoid;page-break-after:avoid}
p{margin:0 0 2.6mm}
.cover{height:var(--page-h);display:flex;flex-direction:column;overflow:hidden;break-after:page;page-break-after:always;position:relative}
.cover .foot{display:flex;align-items:center;justify-content:space-between;gap:4mm;margin-top:auto}
.day{break-before:page;page-break-before:always;position:relative}
.day::after,.entry::after{content:"";display:block;clear:both}
.entry{margin-top:6mm}
.entry:first-of-type{margin-top:0}
.page-break{display:block;clear:both;column-span:all;height:0;margin:0;padding:0;border:0;break-after:page;page-break-after:always}
/* Android WebView drops page breaks inside CSS columns. Deliberate author pages use normal page flow. */
.entry .body.authored-pages .text{columns:auto;column-count:auto;column-width:auto}
.when{font:500 7.5pt 'IBM Plex Mono',monospace;letter-spacing:.08em;text-transform:uppercase;margin:0 0 2.5mm;break-after:avoid;page-break-after:avoid}
figure{margin:0}
/* A photo alone, or placed in a note: whole, its width set from its shape, its height following. */
figure.photo,figure.alone{break-inside:avoid;page-break-inside:avoid;position:relative}
figure.photo img,figure.alone img{display:block;width:100%;height:auto}
figure.alone{margin-left:auto;margin-right:auto}
/* A photo's room above and below is padding, not margin: a margin is dropped at the top of a page, and a turned
   photo's corner or its tape would print at the foot of the page before. --room is what a turned photo needs. */
.pic{--pt:4mm;--pb:5mm;padding:calc(var(--pt) + var(--room,0%)) 0 calc(var(--pb) + var(--room,0%));clear:both;break-inside:avoid;page-break-inside:avoid}
.pic > figure{margin-top:0;margin-bottom:0}
/* Photos side by side: each grows by its own width / height, so a row's photos share one height and fill it. */
.rows{margin:4mm 0 5mm;clear:both}
.rows .row{display:flex;align-items:flex-start;gap:3mm;margin:0 auto 3mm;break-inside:avoid;page-break-inside:avoid}
.rows .row:last-child{margin-bottom:0}
.rows figure{flex:1 1 0;min-width:0;margin:0;position:relative}
.rows img{display:block;width:100%;height:auto;object-fit:contain}
.rows figcaption{margin-top:1.2mm}
/* An author's selected frame is preserved in every print style. */
.entry figure.photo.frame-none::before,.entry figure.photo.frame-rounded::before{display:none}
.entry figure.photo.frame-rounded img{border-radius:4mm}
.entry figure.photo.frame-polaroid{background:#FFFFFF;padding:2.6mm 2.6mm 0;box-shadow:0 1.8mm 3.6mm -1.6mm rgba(30,42,90,.3)}
.entry figure.photo.frame-polaroid figcaption{min-height:8.5mm;padding:1mm 1mm 1.5mm;margin:0}
.entry figure.photo.frame-polaroid:not(:has(figcaption))::after{content:"";display:block;height:7mm}
.entry figure.photo.frame-tape::before,.entry figure.photo.frame-tape::after{content:"";position:absolute;z-index:2;width:16mm;height:6mm;background:rgba(247,227,106,.82);transform:rotate(-38deg)}
.entry figure.photo.frame-tape::before{left:-2mm;top:4.5mm;margin:0}
.entry figure.photo.frame-tape::after{right:-2mm;bottom:4.5mm}
figcaption{font-size:8.5pt;line-height:1.35}
blockquote{margin:3mm 0;padding-left:4mm;border-left:2px solid currentColor;font-style:italic;display:flow-root}
hr{border:0;border-top:1px solid #DADDE0;margin:4mm 0}
p.place{font:500 7.5pt 'IBM Plex Mono',monospace;letter-spacing:.08em;text-transform:uppercase;opacity:.8}
ul.check{list-style:none;padding:0;margin:0 0 3mm;display:flow-root}
ul.check li{position:relative;padding-left:5.5mm;margin:0 0 1mm}
ul.check li::before{content:"";position:absolute;left:0;top:.45em;width:2.8mm;height:2.8mm;border:1px solid currentColor;border-radius:.6mm}
ul.check li.done::before{background:currentColor}
.plan{margin-top:7mm;clear:both}
.plan h4{font:500 7.5pt 'IBM Plex Mono',monospace;letter-spacing:.1em;text-transform:uppercase;margin-bottom:1.5mm}
.plan-list{list-style:none;padding:0;margin:0}
.plan-list li{display:grid;grid-template-columns:14mm 4mm 1fr auto;column-gap:2mm;padding:1.6mm 0;border-bottom:1px solid var(--line);break-inside:avoid;page-break-inside:avoid;font-size:9.5pt;line-height:1.4}
.plan-list .t{font:500 8.5pt 'IBM Plex Mono',monospace;padding-top:.3mm}
.plan-list .box{width:2.8mm;height:2.8mm;border:1px solid currentColor;border-radius:.6mm;margin-top:1mm}
.plan-list li.done .box{background:currentColor}
.plan-list .what b{font-weight:600}
.plan-list .note{display:block;opacity:.75;font-size:8.5pt}
.plan-list .kind{font:500 7pt 'IBM Plex Mono',monospace;letter-spacing:.06em;text-transform:uppercase;opacity:.7;padding-top:.5mm;text-align:right}
.facts{display:grid;grid-template-columns:repeat(var(--facts,3),minmax(0,1fr));border-top:1px solid var(--line);border-bottom:1px solid var(--line);padding:2.5mm 0;margin:0 0 4mm;break-inside:avoid}
.facts span{font:400 7pt 'IBM Plex Mono',monospace;text-transform:uppercase;letter-spacing:.06em;color:var(--muted)}
.facts b{display:block;font:600 12pt 'IBM Plex Mono',monospace;color:var(--ink);letter-spacing:0;text-transform:none;margin-top:.5mm}
.flags{display:inline-flex;gap:1.6mm;align-items:center;flex-wrap:wrap}
.flags .fl{display:inline-block;line-height:0;padding:.45mm;background:#FFFFFF;border-radius:.5mm;box-shadow:0 .25mm .6mm rgba(20,24,40,.28);transform:rotate(-4deg)}
.flags .fl:nth-child(even){transform:rotate(3.5deg)}
.empty{margin-top:8mm;font-style:italic;opacity:.75}
"""

    /** Postcard: white page, serif text in two columns, one big photo a day, the day as a stamp. */
    const val POSTCARD = """
body{--ink:#22262A;--muted:#6A7076;--line:#DADDE0;background:#FFFFFF;color:#22262A;font-family:'Literata',Georgia,serif}
.cover .cover-photo{display:block;height:auto;align-self:center;margin:0 0 7mm}
.cover h1{font:600 34pt/1.08 'Literata',Georgia,serif;margin:2mm 32mm 2mm 0}
.cover .sub{font-style:italic;font-size:12pt;color:#3F464C;margin-bottom:6mm}
.cover .foot{border-top:1px solid var(--line);padding-top:3mm}
.cover .stats{font:500 8pt 'IBM Plex Mono',monospace;letter-spacing:.08em;text-transform:uppercase;color:var(--muted);margin:0}
.meta{display:flex;justify-content:space-between;gap:4mm;font:500 7.5pt 'IBM Plex Mono',monospace;letter-spacing:.1em;text-transform:uppercase;color:var(--muted);border-bottom:1px solid var(--line);padding-bottom:2.5mm;margin:0 0 5mm}
.stamp{position:absolute;right:5mm;top:5mm;width:21mm;min-height:24mm;background:#FFFFFF;border:2px dashed #C4423A;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:1.5mm 1mm;font:600 6.5pt 'IBM Plex Mono',monospace;color:#C4423A;letter-spacing:.06em;text-transform:uppercase;text-align:center;line-height:1.25;overflow-wrap:anywhere}
.stamp b{font:700 19pt/1.05 'Literata',Georgia,serif;letter-spacing:0}
.no-hero .stamp{position:static;float:right;margin:0 0 3mm 5mm}
.cover .stamp{top:5mm;right:5mm}
.cover.no-photo .stamp{position:absolute;top:0;right:0}
.hero{margin:0 auto 5mm}
.hero img{display:block;width:100%;height:auto}
.entry h3{font:600 21pt/1.12 'Literata',Georgia,serif;margin:0 0 1.5mm}
.entry .when{color:var(--muted)}
.entry .text{columns:2;column-gap:7mm;column-fill:balance}
.entry .body h2,.entry .body h3,.entry .body h4{font-weight:600;font-size:12pt;margin:1mm 0 1.5mm}
.pic{--pt:2mm;--pb:4mm}
.entry .body figcaption,.rows figcaption,figure.alone figcaption{font-style:italic;color:var(--muted);margin-top:1.2mm}
.plan h4,.ideas h2{color:var(--muted)}
.ideas h2{font:600 21pt/1.12 'Literata',Georgia,serif;color:var(--ink);margin-bottom:4mm}
"""

    /** Scrapbook: dotted paper, handwritten titles, photos as polaroids with tape, highlighter, ticket stubs. */
    const val SCRAPBOOK = """
body{--ink:#1E2A5A;--muted:#525C86;--line:#D8DCEA;color:#1E2A5A;font-family:'Literata',Georgia,serif;line-height:1.65;background-color:#FCFCF9;background-image:radial-gradient(circle,rgba(30,42,90,.16) .26mm,transparent .38mm);background-size:5mm 5mm}
.cover h1{font:700 54pt/1 'Caveat',cursive;margin:4mm 0 1mm}
.cover .sub{font:600 20pt/1.2 'Caveat',cursive;color:var(--muted);margin-bottom:6mm}
.cover .cover-pol{align-self:center;margin:auto 0;transform:rotate(-3deg)}
.cover .stats{font:600 17pt 'Caveat',cursive;color:var(--muted);margin:0}
.dayno{font:500 7.5pt 'IBM Plex Mono',monospace;letter-spacing:.1em;text-transform:uppercase;color:var(--muted);margin:0}
.date{font:600 20pt/1.2 'Caveat',cursive;color:var(--muted);margin:0 0 4mm}
.tickets{margin:0 0 4mm}
.ticket{display:inline-block;border:1.5px solid #1E2A5A;padding:1.4mm 3mm;font:500 7.5pt 'IBM Plex Mono',monospace;letter-spacing:.06em;text-transform:uppercase;background:#FFFFFF;transform:rotate(2.5deg);margin:1mm 5mm 2mm 0}
.ticket:nth-child(even){transform:rotate(-2deg)}
.entry h3{font:700 32pt/1 'Caveat',cursive;margin:0 0 1mm}
.entry .when{color:var(--muted)}
.entry .body h2,.entry .body h3,.entry .body h4{font:700 20pt/1.1 'Caveat',cursive;margin:2mm 0 1mm}
.body b,.body strong{font-weight:inherit;background:linear-gradient(transparent 55%,#F7E36A 55%)}
.pic{--pt:6mm;--pb:7mm}
figure.photo::before{content:"";position:absolute;z-index:2;left:50%;top:1.2mm;width:22mm;height:6mm;margin-left:-11mm;background:rgba(247,227,106,.82);transform:rotate(-6deg)}
figure.photo figcaption{text-align:center;font:600 13pt/1.15 'Caveat',cursive;margin-top:1.5mm}
figure.frame-polaroid{background:#FFFFFF;padding:2.6mm 2.6mm 0;box-shadow:0 1.8mm 3.6mm -1.6mm rgba(30,42,90,.45)}
figure.frame-polaroid figcaption{min-height:8.5mm;padding:1mm 1mm 1.5mm;margin:0}
figure.frame-polaroid:not(:has(figcaption))::after{content:"";display:block;height:7mm}
figure.frame-tape::before{left:-2mm;top:4.5mm;margin:0;width:16mm;transform:rotate(-38deg)}
figure.frame-tape::after{content:"";position:absolute;z-index:2;right:-2mm;bottom:4.5mm;width:16mm;height:6mm;background:rgba(247,227,106,.82);transform:rotate(-38deg)}
figure.frame-rounded img{border-radius:4mm}
figure.frame-rounded::before{display:none}
figure.alone{transform:rotate(-2deg)}
.rows .row{gap:4mm;padding-top:1.5mm;margin-bottom:3.5mm}
.rows figure{flex-basis:4mm;background:#FFFFFF;padding:2mm 2mm 6mm;box-shadow:0 1.6mm 3mm -1.4mm rgba(30,42,90,.45)}
.rows figure:nth-child(odd){transform:rotate(-1deg)}
.rows figure:nth-child(even){transform:rotate(.8deg)}
.rows figcaption{text-align:center;font:600 12pt/1.1 'Caveat',cursive;margin:1.2mm 0 -3mm}
.plan h4{color:var(--muted)}
.plan-list li{border-bottom-style:dashed}
.ideas h2{font:700 32pt/1 'Caveat',cursive;margin-bottom:4mm}
"""

    /**
     * The notebook's pages, shared by Notebook and Minimal: ink-blue text, Caveat titles, white prints and a round red
     * day stamp. The paper and the highlighter are [NOTEBOOK_PAPER]; Minimal has neither ([MINIMAL]).
     */
    const val NOTEBOOK_PAGES = """
body{--ink:#1E2A5A;--muted:#525C86;--line:#D3D8E6;color:#1E2A5A;font-family:'Literata',Georgia,serif;line-height:1.65}
.cover h1{font:700 52pt/1 'Caveat',cursive;margin:2mm 0 1mm}
.cover .sub{font:600 19pt/1.2 'Caveat',cursive;color:var(--muted);margin:0 0 2mm}
.cover .dates{font:500 7.5pt 'IBM Plex Mono',monospace;letter-spacing:.1em;text-transform:uppercase;margin:0 0 5mm}
.cover .nb-print{align-self:center;margin:auto 0;--rot:-2deg}
.cover .stats{font:600 16pt 'Caveat',cursive;color:var(--muted);margin:0}
.nb-print{position:relative;background:#FFFFFF;padding:2.4mm;box-shadow:0 .35mm .9mm rgba(30,42,90,.24),0 0 0 .1mm rgba(30,42,90,.1);transform:rotate(var(--rot,0deg));break-inside:avoid;page-break-inside:avoid}
.nb-print img{display:block;width:100%;height:auto}
.nb-print figcaption{text-align:center;font:600 13pt/1.15 'Caveat',cursive;margin:1.6mm 0 0}
figure.nb-print.alone{--rot:-1.5deg}
.tape{position:absolute;z-index:2;width:17mm;height:5.4mm;background:rgba(232,224,196,.86);clip-path:polygon(0 8%,4% 0,8% 10%,12% 0,88% 0,92% 8%,96% 0,100% 10%,100% 92%,96% 100%,92% 90%,88% 100%,12% 100%,8% 92%,4% 100%,0 90%)}
.tape.l{left:-4mm;top:-1.4mm;transform:rotate(-36deg)}
.tape.r{right:-4mm;top:-1.4mm;transform:rotate(34deg)}
.tape.c{left:50%;top:-2.6mm;margin-left:-8.5mm;transform:rotate(-4deg)}
.brand-mark{position:absolute;right:-7mm;bottom:-8mm;width:19mm;height:19mm;transform:rotate(-8deg);z-index:3;filter:drop-shadow(0 .3mm .6mm rgba(30,42,90,.25))}
.brand-mark svg,.daystamp svg{display:block;width:100%;height:100%}
.nb-head{position:relative;min-height:31mm;margin:0 0 3mm}
.daystamp{position:absolute;right:0;top:-2mm;width:31mm;height:31mm;transform:rotate(-9deg);opacity:.92}
.dayno{font:500 7.5pt 'IBM Plex Mono',monospace;letter-spacing:.1em;text-transform:uppercase;color:var(--muted);margin:0 36mm 1mm 0}
.nb-head .date{font:700 28pt/1.08 'Caveat',cursive;margin:0 36mm 1.5mm 0}
.nb-head .place{display:flex;align-items:center;gap:2.2mm;font:600 16pt/1.2 'Caveat','Literata',cursive;color:var(--muted);margin:0 36mm 0 0;text-transform:none;letter-spacing:0;opacity:1}
.entry h3{font:700 30pt/1.02 'Caveat',cursive;margin:0 0 1mm}
.entry .when{color:var(--muted)}
.entry .body h2,.entry .body h3,.entry .body h4{font:700 19pt/1.1 'Caveat',cursive;margin:2mm 0 1mm}
.pic{--pt:6mm;--pb:7mm}
figure.photo figcaption{text-align:center;font:600 13pt/1.15 'Caveat',cursive;margin-top:1.5mm}
figure.frame-polaroid{background:#FFFFFF;padding:2.6mm 2.6mm 0;box-shadow:0 .35mm .9mm rgba(30,42,90,.24)}
figure.frame-polaroid figcaption{min-height:8.5mm;padding:1mm 1mm 1.5mm;margin:0}
figure.frame-tape::before,figure.frame-tape::after{content:"";position:absolute;z-index:2;width:16mm;height:5.4mm;background:rgba(232,224,196,.86)}
figure.frame-tape::before{left:-3mm;top:2mm;transform:rotate(-36deg)}
figure.frame-tape::after{right:-3mm;bottom:2mm;transform:rotate(-36deg)}
.entry figure.photo.frame-tape::before,.entry figure.photo.frame-tape::after{background:rgba(232,224,196,.86)}
figure.frame-rounded img{border-radius:4mm}
/* Room above each row for the tape, as padding so it never prints on the page before. */
.rows .row{gap:4mm;padding-top:2.6mm;margin-bottom:2.4mm}
.rows{margin-top:4mm}
.rows figure{flex-basis:4mm;background:#FFFFFF;padding:2mm;box-shadow:0 .35mm .9mm rgba(30,42,90,.24),0 0 0 .1mm rgba(30,42,90,.1)}
.rows .tape.c{width:12mm;height:4.6mm;margin-left:-6mm;top:-2.2mm}
.rows figcaption{text-align:center;font:600 12pt/1.1 'Caveat',cursive;margin:1.2mm 0 0}
.plan h4{color:var(--muted)}
.plan-list li{border-bottom-style:dashed}
.ideas h2{font:700 30pt/1 'Caveat',cursive;margin-bottom:4mm}
"""

    /**
     * Notebook (the default look on paper): [NOTEBOOK_PAGES] on dotted or quietly ruled paper, with the highlighter
     * under dates and bold words.
     */
    const val NOTEBOOK_PAPER = """
body{background-color:#FCFCF9;background-image:radial-gradient(circle,rgba(30,42,90,.24) .19mm,transparent .3mm);background-size:5mm 5mm}
body.paper-ruled{background-image:repeating-linear-gradient(180deg,transparent 0,transparent 6.55mm,rgba(90,96,110,.24) 6.55mm,rgba(90,96,110,.24) 6.75mm);background-size:auto}
.mk{background:linear-gradient(transparent 52%,#F7E36A 52%,#F7E36A 92%,transparent 92%);padding:0 .8mm;-webkit-box-decoration-break:clone;box-decoration-break:clone}
.cover .dates .mk{background:linear-gradient(transparent 18%,#F7E36A 18%,#F7E36A 96%,transparent 96%);padding:0 1.2mm}
.body b,.body strong{font-weight:inherit;background:linear-gradient(transparent 55%,#F7E36A 55%,#F7E36A 92%,transparent 92%)}
"""

    /**
     * Minimal (owner, 2026-11-14: "like notebook but white background, minimal, no yellows, underlines"):
     * [NOTEBOOK_PAGES] on plain white. No dots or rules, no highlighter (bold is bold), no tape; a note's own Tape
     * frame is grey.
     */
    const val MINIMAL = """
body{background:#FFFFFF}
.body b,.body strong{font-weight:700}
.rows .row{padding-top:0;margin-bottom:3mm}
figure.frame-tape::before,figure.frame-tape::after,.entry figure.photo.frame-tape::before,.entry figure.photo.frame-tape::after{background:rgba(160,166,178,.5)}
"""

    /** Note fonts chosen in the editor (EntryDoc.font) win over the style's body font. */
    const val ENTRY_FONTS = """
.font-literata .body{font-family:'Literata',Georgia,serif}
.font-albert .body{font-family:'Albert Sans',sans-serif}
.font-caveat .body{font-family:'Caveat',cursive;font-size:13.5pt;line-height:1.4}
.font-mono .body{font-family:'IBM Plex Mono',monospace;font-size:9.5pt}
.font-barlow .body{font-family:'Barlow',sans-serif}
"""

    fun theme(theme: PrintTheme): String = when (theme) {
        PrintTheme.Notebook -> NOTEBOOK_PAGES + NOTEBOOK_PAPER
        PrintTheme.Postcard -> POSTCARD
        PrintTheme.Scrapbook -> SCRAPBOOK
        PrintTheme.Minimal -> NOTEBOOK_PAGES + MINIMAL
    }

    /**
     * Google Docs keeps headings, text, colours, fonts by name and images, not layout. Plain element rules only;
     * fonts are Google Fonts names Docs knows.
     */
    fun docs(theme: PrintTheme): String {
        val (body, head, ink, muted) = when (theme) {
            PrintTheme.Postcard -> listOf("'Literata', Georgia, serif", "'Literata', Georgia, serif", "#22262A", "#6A7076")
            PrintTheme.Scrapbook, PrintTheme.Notebook, PrintTheme.Minimal -> listOf("'Literata', Georgia, serif", "'Caveat', cursive", "#1E2A5A", "#525C86")
        }
        val hand = theme != PrintTheme.Postcard
        val big = if (hand) 1.35 else 1.0
        fun pt(v: Double) = RouteSvg.n(v * big) + "pt"
        // Minimal has no highlighter: its bold stays bold.
        val mark = if (hand && theme != PrintTheme.Minimal) ".entry b,.entry strong{font-weight:normal;background-color:#F7E36A}" else ""
        return """
body{font-family:$body;font-size:11pt;line-height:1.5;color:$ink}
h1{font-family:$head;font-size:${pt(28.0)};font-weight:bold;color:$ink;margin:0 0 4pt}
h2{font-family:$head;font-size:${pt(18.0)};font-weight:bold;color:$ink;margin:14pt 0 2pt}
h3{font-family:$head;font-size:${pt(14.0)};font-weight:bold;color:$ink;margin:12pt 0 2pt}
h4{font-family:'IBM Plex Mono', monospace;font-size:8pt;font-weight:normal;color:$muted;text-transform:uppercase;margin:12pt 0 2pt}
p{margin:0 0 6pt}
.meta,.when,.place,.facts{font-family:'IBM Plex Mono', monospace;font-size:8pt;color:$muted}
.sub{font-style:italic;color:$muted}
.cap,figcaption{font-style:italic;font-size:9pt;color:$muted;text-align:center}
.center{text-align:center}
td{vertical-align:top;padding:3pt 6pt 3pt 0;font-size:10pt}
td.t{font-family:'IBM Plex Mono', monospace;font-size:9pt;color:$muted}
td.kind{font-family:'IBM Plex Mono', monospace;font-size:8pt;color:$muted}
$mark
"""
    }
}
