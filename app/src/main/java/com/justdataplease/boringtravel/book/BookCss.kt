package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.model.PrintTheme

/**
 * The book's paint: page boxes, text, prints and tape, stamps, flags, per theme. Geometry is [PageLayout]'s; this
 * only colours and sets type. Fixed print colours, not the app's look.
 */
internal object BookCss {
    /** Bundled fonts the book can use, loaded from the app ([BookHtml.FONT_BASE]) and embedded in the PDF. */
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
            face("Barlow Condensed", "barlow_condensed_bold.ttf", "700") +
            face("Kalam", "kalam_regular.ttf", "400") +
            face("Kalam", "kalam_bold.ttf", "700") +
            face("Source Serif 4", "source_serif_4.ttf", "200 900") +
            face("Courier Prime", "courier_prime_regular.ttf", "400") +
            face("Courier Prime", "courier_prime_bold.ttf", "700") +
            face("Architects Daughter", "architects_daughter_regular.ttf", "400") +
            face("Work Sans", "work_sans.ttf", "100 900") +
            face("JetBrains Mono", "jetbrains_mono.ttf", "100 800") +
            face("Gochi Hand", "gochi_hand_regular.ttf", "400") +
            face("Lora", "lora.ttf", "400 700")
    }

    const val BASE = """
*{box-sizing:border-box;-webkit-print-color-adjust:exact;print-color-adjust:exact}
html,body{margin:0;padding:0}
body{color:var(--text)}
.pg{position:relative;overflow:hidden;background:#FFFFFF;break-after:page;page-break-after:always;break-inside:avoid}
.pg:last-child{break-after:auto;page-break-after:auto}
.trim{position:absolute}
.art{position:absolute;background:var(--paper)}
.paper{position:absolute;display:block;overflow:visible}
.marks{position:absolute;left:0;top:0;width:100%;height:100%;display:block}
.e{position:absolute}
.t{position:absolute;margin:0;overflow-wrap:anywhere;color:var(--ink);white-space:normal}
.t.up{text-transform:uppercase}
.t.mono{font-family:var(--mono);font-weight:500;letter-spacing:.09em;color:var(--muted)}
.t.mono.up{text-transform:uppercase}
.t.folio{letter-spacing:.04em}
.mk{background:linear-gradient(transparent 52%,var(--hl) 52%,var(--hl) 92%,transparent 92%);-webkit-box-decoration-break:clone;box-decoration-break:clone;padding:0 .7mm}
.t.mono .mk{background:linear-gradient(transparent 14%,var(--hl) 14%,var(--hl) 96%,transparent 96%);padding:0 1.1mm}
.ph{position:absolute}
.ph .im{position:absolute;overflow:hidden;background:#E9ECEF}
.ph .im img{position:absolute;display:block;max-width:none}
.ph.empty{border:.35mm dashed #9AA3AE;background:rgba(255,255,255,.55);display:flex;flex-direction:column;gap:calc(var(--L)*.35);align-items:center;justify-content:center;font:500 7pt 'IBM Plex Mono',monospace;color:#6A7076;letter-spacing:.08em;text-transform:uppercase}
.ph.empty .add{position:relative;display:block;flex:none;width:calc(var(--L)*1.9);height:calc(var(--L)*1.9);border-radius:50%;background:#FF5A3C;box-shadow:0 .3mm .8mm rgba(0,0,0,.25)}
.ph.empty .add::before,.ph.empty .add::after{content:"";position:absolute;left:50%;top:50%;width:46%;height:9%;margin:-4.5% 0 0 -23%;border-radius:1mm;background:#FFFFFF}
.ph.empty .add::after{transform:rotate(90deg)}
.tape{position:absolute;display:block;background:var(--tape);clip-path:polygon(0 8%,4% 0,8% 10%,12% 0,88% 0,92% 8%,96% 0,100% 10%,100% 92%,96% 100%,92% 90%,88% 100%,12% 100%,8% 92%,4% 100%,0 90%)}
.ph.print{background:#FFFFFF;box-shadow:0 .35mm .9mm rgba(30,42,90,.24),0 0 0 .1mm rgba(30,42,90,.10)}
.dim{position:absolute}
.washi{opacity:.9;clip-path:polygon(0 8%,3% 0,6% 10%,9% 0,91% 0,94% 8%,97% 0,100% 10%,100% 92%,97% 100%,94% 90%,91% 100%,9% 100%,6% 92%,3% 100%,0 90%)}
.corners i{position:absolute;display:block;width:var(--cs);height:var(--cs);background:var(--cc)}
.corners .tl{left:0;top:0;clip-path:polygon(0 0,100% 0,0 100%)}
.corners .tr{right:0;top:0;clip-path:polygon(0 0,100% 0,100% 100%)}
.corners .br{right:0;bottom:0;clip-path:polygon(100% 0,100% 100%,0 100%)}
.corners .bl{left:0;bottom:0;clip-path:polygon(0 0,100% 100%,0 100%)}
.line i{position:absolute;display:block;left:0;right:0;top:50%;border-radius:9mm}
.shade{background:linear-gradient(rgba(0,0,0,0),rgba(0,0,0,.62))}
.t.over{color:#FFFFFF;text-shadow:0 .2mm .9mm rgba(0,0,0,.35)}
.band{position:absolute;display:flex;align-items:center;justify-content:center;text-align:center;overflow:hidden;padding:0 1.5mm}
.flow{position:absolute;box-sizing:content-box;overflow:hidden;column-fill:auto;orphans:1;widows:1;color:var(--text)}
.flow>*{margin:0;position:relative}
.flow p{top:var(--sb)}
.flow p.ind{text-indent:var(--ind)}
.flow .title{color:var(--ink);break-after:avoid;break-inside:avoid;overflow-wrap:anywhere}
.flow .title:not(:first-child){margin-top:var(--L)}
.flow .meta{font-family:var(--mono);font-weight:500;letter-spacing:.09em;text-transform:uppercase;color:var(--muted);break-after:avoid;top:var(--sm)}
.flow .head{color:var(--ink);break-after:avoid;break-inside:avoid}
.flow .q{font-style:italic;padding-left:var(--qi)}
.flow .li{padding-left:var(--li)}
.flow .li::before{content:"";position:absolute;left:calc(var(--li) * .3);top:calc(var(--L) * .48 - var(--sb));width:1.1mm;height:1.1mm;border-radius:50%;background:currentColor;opacity:.75}
.flow .li.num::before{content:attr(data-n) ".";width:auto;height:auto;border-radius:0;background:none;top:0;left:0;opacity:.8}
.flow .ck{padding-left:var(--ci)}
.flow .ck::before{content:"";position:absolute;left:0;top:calc(var(--L) * .3 - var(--sb));width:2.5mm;height:2.5mm;border:.25mm solid currentColor;border-radius:.5mm}
.flow .ck.done::after{content:"";position:absolute;left:.85mm;top:calc(var(--L) * .3 - var(--sb) + .2mm);width:.9mm;height:1.7mm;border:solid currentColor;border-width:0 .35mm .35mm 0;transform:rotate(45deg)}
.flow .li.cont::before,.flow .ck.cont::before{content:none}
.flow .place{font-family:var(--mono);font-weight:500;font-size:var(--mono-pt);letter-spacing:.09em;text-transform:uppercase;color:var(--muted);top:var(--sm)}
.flow a{color:inherit;text-decoration:none}
.route-box svg,.stamp svg,.lines svg{display:block;width:100%;height:100%;overflow:visible}
.stamp{opacity:.92}
.stamp.brand{opacity:1}
.flags{display:flex;align-items:center;flex-wrap:wrap;gap:1.3mm}
.flags .fl{display:inline-block;line-height:0}
.flags .fl svg{display:block}
.flags.flat .fl{box-shadow:0 0 0 .12mm rgba(0,0,0,.28)}
.flags.sticker .fl{padding:.45mm;background:#FFFFFF;border-radius:.5mm;box-shadow:0 .25mm .6mm rgba(20,24,40,.28);transform:rotate(-4deg)}
.flags.sticker .fl:nth-child(even){transform:rotate(3.5deg)}
.rule{border-top:.16mm solid var(--line);height:0}
.tag{background:#FFFFFF;color:var(--ink);box-shadow:0 .3mm .9mm rgba(20,24,40,.25);display:flex;align-items:center;justify-content:center;white-space:nowrap;overflow:hidden;padding:0 3mm}
.postage{background:#FFFFFF;border:.5mm dashed var(--red);display:flex;flex-direction:column;align-items:center;justify-content:center;font:600 6.2pt 'IBM Plex Mono',monospace;color:var(--red);letter-spacing:.06em;text-transform:uppercase;line-height:1.25;text-align:center}
.postage b{font:700 19pt/1.05 'Literata',Georgia,serif;letter-spacing:0}
"""

    /** Screen-only: pages on a grey desk for checking in a browser; the editor shows one page edge to edge. */
    const val PREVIEW = """
@media screen{body.desk{background:#E2E4E0;padding:8mm 0}body.desk .pg{margin:0 auto 8mm;box-shadow:0 1.5mm 5mm -2mm rgba(20,24,28,.45)}}
"""

    fun theme(theme: PrintTheme): String = when (theme) {
        PrintTheme.Notebook -> NOTEBOOK
        PrintTheme.Scrapbook -> SCRAPBOOK
        PrintTheme.Postcard -> POSTCARD
        PrintTheme.Minimal -> MINIMAL
    }

    private const val NOTEBOOK = """
.bk{--paper:#FCFCF9;--ink:#1E2A5A;--text:#1E2A5A;--muted:#525C86;--line:#C9CEDB;--red:#C4423A;--hl:#F7E36A;--tape:rgba(232,224,196,.86)}
.ph.print{background:#FFFFFF;box-shadow:0 .35mm .9mm rgba(30,42,90,.24),0 0 0 .1mm rgba(30,42,90,.10)}
.ph.print .im{background:#EEF0F5}
.flow b,.flow strong{font-weight:inherit;background:linear-gradient(transparent 55%,var(--hl) 55%,var(--hl) 92%,transparent 92%)}
.flow .q{color:var(--muted)}
.t.cap{color:var(--ink)}
.t.place,.t.places,.t.note,.t.cover-sub,.t.sub{color:var(--muted)}
.t.notes-title{color:var(--muted)}
.t.stat-label{color:var(--muted)}
.tag{transform-origin:left center}
"""

    private const val SCRAPBOOK = NOTEBOOK + """
.bk{--tape:rgba(247,227,106,.82)}
.ph.print{box-shadow:0 .5mm 1.2mm rgba(30,42,90,.32)}
.band{font-weight:600;color:var(--ink)}
"""

    private const val POSTCARD = """
.bk{--paper:#FFFFFF;--ink:#22262A;--text:#2B3035;--muted:#6A7076;--line:#DADDE0;--red:#C4423A;--hl:#F3E3A0;--tape:transparent}
.flow b,.flow strong{font-weight:600}
.t.cap{color:var(--muted)}
.t.place,.t.places,.t.note,.t.cover-sub,.t.sub{color:#3F464C}
.t.notes-title,.t.stat-label{color:var(--muted)}
.flow .q{color:#3F464C}
"""

    private const val MINIMAL = """
.bk{--paper:#FFFFFF;--ink:#1C1F22;--text:#2E3439;--muted:#6A7076;--line:#DFE3DE;--red:#C4423A;--hl:#D6F2E4;--tape:transparent}
.flow b,.flow strong{font-weight:600}
.t.cap{color:var(--muted)}
.t.place,.t.places,.t.note,.t.cover-sub,.t.sub{color:#3F464C}
.t.notes-title,.t.stat-label{color:var(--muted)}
"""
}
