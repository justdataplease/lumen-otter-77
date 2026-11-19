package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.export.Geo
import com.justdataplease.boringtravel.export.GeoPoint
import com.justdataplease.boringtravel.export.Pt
import com.justdataplease.boringtravel.export.RouteGeo
import com.justdataplease.boringtravel.export.RouteSvg
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow

/** The book's drawings as inline SVG: day stamps, the route in pen, paper dots and rules, crop marks. Sizes in mm. */
object BookSvg {
    private fun n(v: Double) = RouteSvg.n(v)
    private fun esc(s: String) = RichHtml.escape(s)

    /**
     * A round date stamp: a thick and a thin ring, [top] along the upper band, [bottom] along the lower band, [small]
     * and the big [middle] in the centre. [id] makes the arc ids unique in the document.
     */
    fun dayStamp(id: String, color: String, top: String, small: String, middle: String, bottom: String, font: String): String {
        val f = esc(font)
        val big = if (middle.length <= 2) 34 else 24
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\" width=\"100%\" height=\"100%\" role=\"img\" aria-label=\"${esc("$small $middle $bottom")}\">" +
            "<defs><path id=\"$id-t\" d=\"M14.5,50A35.5,35.5 0 0 1 85.5,50\"/><path id=\"$id-b\" d=\"M9,50A41,41 0 0 0 91,50\"/></defs>" +
            "<g fill=\"none\" stroke=\"$color\"><circle cx=\"50\" cy=\"50\" r=\"46\" stroke-width=\"3.4\"/><circle cx=\"50\" cy=\"50\" r=\"31\" stroke-width=\"1.2\"/></g>" +
            "<g fill=\"$color\" font-family=\"$f\" font-weight=\"700\" text-anchor=\"middle\">" +
            "<text font-size=\"10.5\" letter-spacing=\"1.6\"><textPath href=\"#$id-t\" startOffset=\"50%\">${esc(top)}</textPath></text>" +
            "<text font-size=\"9.5\" letter-spacing=\"1.4\"><textPath href=\"#$id-b\" startOffset=\"50%\">${esc(bottom)}</textPath></text>" +
            "<text x=\"50\" y=\"38\" font-size=\"9\" letter-spacing=\"2\">${esc(small)}</text>" +
            "<text x=\"50\" y=\"${if (big == 34) 70 else 66}\" font-size=\"$big\">${esc(middle)}</text></g></svg>"
    }

    /** Style of a pen route: line colour and width, label face and size (mm), whether labels are upper-case mono. */
    data class Pen(val color: String, val width: Double, val ink: String, val paper: String, val labelFont: String, val labelSize: Double, val labelUpper: Boolean)

    /**
     * The route as if drawn with a pen in a [w] × [h] mm box: smooth curve, rings at the stops, a dot at the end,
     * place names, numbered day ends ([markers]) and a scale bar.
     */
    fun route(route: RouteGeo, w: Double, h: Double, pen: Pen, labels: Boolean, markers: List<Pair<Int, GeoPoint>>, scaleBar: Boolean, title: String): String? {
        if (route.isEmpty) return null
        val k = 10.0 // drawing units per mm
        val W = w * k
        val H = h * k
        val pad = minOf(W, H) * 0.1 + pen.labelSize * k * 0.6
        val all = route.line + route.places.map { it.point } + markers.map { it.second }
        val pts = RouteSvg.project(all, W, H, pad)
        val line = RouteSvg.simplify(pts.take(route.line.size), 2.0)
        val sparse = route.line.size <= 12
        val places = pts.subList(route.line.size, route.line.size + route.places.size)
        val ends = pts.subList(route.line.size + route.places.size, pts.size)
        val sw = pen.width * k
        val r = sw * 1.9
        val label = pen.labelSize * k
        return buildString {
            append("<svg class=\"route\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${n(W)} ${n(H)}\" width=\"100%\" height=\"100%\" role=\"img\" aria-label=\"${esc(title)}\">")
            append("<title>").append(esc(title)).append("</title>")
            if (line.size >= 2) {
                append("<path d=\"").append(if (sparse) arcPath(line) else penPath(line)).append("\" fill=\"none\" stroke=\"").append(pen.color).append("\" stroke-width=\"").append(n(sw))
                    .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\"")
                if (route.planned) append(" stroke-dasharray=\"").append(n(sw * 0.1)).append(' ').append(n(sw * 2.6)).append('"')
                append("/>")
            }
            val start = line.firstOrNull() ?: places.firstOrNull()
            val end = line.lastOrNull() ?: places.lastOrNull()
            val rings = (places + listOfNotNull(start)).distinctBy { "${n(it.x)},${n(it.y)}" }.filter { end == null || hypot(it.x - end.x, it.y - end.y) > r }
            rings.forEach { p ->
                append("<circle cx=\"${n(p.x)}\" cy=\"${n(p.y)}\" r=\"${n(r)}\" fill=\"${pen.paper}\" stroke=\"${pen.color}\" stroke-width=\"${n(sw * 0.8)}\"/>")
            }
            if (end != null) append("<circle cx=\"${n(end.x)}\" cy=\"${n(end.y)}\" r=\"${n(r * 1.15)}\" fill=\"${pen.color}\"/>")
            ends.forEachIndexed { i, p ->
                val num = markers[i].first.toString()
                val mr = label * 0.62
                val near = ends.subList(0, i).any { hypot(it.x - p.x, it.y - p.y) < mr * 2.2 }
                if (near) return@forEachIndexed
                val ox = p.x + mr * 0.9
                val oy = p.y - mr * 1.3
                append("<g><circle cx=\"${n(ox)}\" cy=\"${n(oy)}\" r=\"${n(mr)}\" fill=\"${pen.color}\"/>")
                append("<text x=\"${n(ox)}\" y=\"${n(oy + mr * 0.42)}\" text-anchor=\"middle\" font-family=\"'Barlow Condensed',sans-serif\" font-weight=\"700\" font-size=\"${n(mr * 1.2)}\" fill=\"${pen.paper}\">$num</text></g>")
            }
            if (labels) {
                // Each label tries the right of its place, then the left, then above; it is left out if all would
                // touch another label or another place's ring.
                val boxes = ArrayList<DoubleArray>()
                val rings2 = places + listOfNotNull(start, end)
                var last: String? = null
                route.places.forEachIndexed { i, rp ->
                    val p = places[i]
                    val name = rp.name.trim().let { if (pen.labelUpper) it.uppercase() else it }
                    if (name.isEmpty() || name == last) return@forEachIndexed
                    last = name
                    val tw = name.length * label * (if (pen.labelUpper) 0.66 else 0.47)
                    val gap = r + label * 0.35
                    val tries = listOf(
                        Triple(p.x + gap, p.y + label * 0.33, "start"),
                        Triple(p.x - gap, p.y + label * 0.33, "end"),
                        Triple(p.x, p.y - gap - label * 0.15, "middle"),
                        Triple(p.x, p.y + gap + label * 0.8, "middle"),
                    )
                    val pick = tries.firstOrNull { (x, y, anchor) ->
                        val x0 = when (anchor) { "start" -> x; "end" -> x - tw; else -> x - tw / 2 }
                        val b = doubleArrayOf(x0, y - label * 0.8, x0 + tw, y + label * 0.2)
                        b[0] >= -label && b[2] <= W + label && b[1] >= -label * 0.5 && b[3] <= H + label * 0.5 &&
                            boxes.none { o -> b[0] < o[2] && b[2] > o[0] && b[1] < o[3] && b[3] > o[1] } &&
                            rings2.none { q -> (q.x != p.x || q.y != p.y) && q.x + r > b[0] && q.x - r < b[2] && q.y + r > b[1] && q.y - r < b[3] }
                    } ?: return@forEachIndexed
                    val (lx, ly, anchor) = pick
                    val x0 = when (anchor) { "start" -> lx; "end" -> lx - tw; else -> lx - tw / 2 }
                    boxes += doubleArrayOf(x0, ly - label * 0.8, x0 + tw, ly + label * 0.2)
                    append("<text x=\"${n(lx)}\" y=\"${n(ly)}\" text-anchor=\"$anchor\" fill=\"${pen.ink}\" font-family=\"${esc(pen.labelFont)}\" ")
                    append("font-size=\"${n(label)}\"").append(if (pen.labelUpper) " letter-spacing=\"${n(label * 0.06)}\"" else "").append(">")
                    append(esc(name)).append("</text>")
                }
            }
            if (scaleBar) scale(route, pts.take(route.line.size + route.places.size), all, W, H, pen, k)?.let(::append)
            append("</svg>")
        }
    }

    /** A smooth line through the points: quadratic curves between the midpoints, like a steady hand. */
    fun penPath(points: List<Pt>): String = buildString {
        if (points.isEmpty()) return@buildString
        append('M').append(n(points[0].x)).append(' ').append(n(points[0].y))
        if (points.size == 2) { append('L').append(n(points[1].x)).append(' ').append(n(points[1].y)); return@buildString }
        for (i in 1 until points.size - 1) {
            val p = points[i]
            val q = points[i + 1]
            val mx = (p.x + q.x) / 2
            val my = (p.y + q.y) / 2
            append('Q').append(n(p.x)).append(' ').append(n(p.y)).append(' ').append(n(mx)).append(' ').append(n(my))
        }
        val last = points.last()
        append('L').append(n(last.x)).append(' ').append(n(last.y))
    }

    /**
     * A route joined stop to stop as a hand would draw it: each leg a gentle arc (bending alternately), so the line
     * still passes through every stop.
     */
    fun arcPath(points: List<Pt>): String = buildString {
        if (points.isEmpty()) return@buildString
        append('M').append(n(points[0].x)).append(' ').append(n(points[0].y))
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val bend = if (i % 2 == 0) 0.12 else -0.12
            val cx = (a.x + b.x) / 2 - dy * bend
            val cy = (a.y + b.y) / 2 + dx * bend
            append('Q').append(n(cx)).append(' ').append(n(cy)).append(' ').append(n(b.x)).append(' ').append(n(b.y))
        }
    }

    /** A scale bar with a round length (1, 2, 5 × 10ⁿ km) between 12 and 30 mm long, bottom left. */
    private fun scale(route: RouteGeo, projected: List<Pt>, geo: List<GeoPoint>, W: Double, H: Double, pen: Pen, k: Double): String? {
        val pts = route.line + route.places.map { it.point }
        if (pts.size < 2) return null
        // The two points furthest apart on the drawing give km per drawing unit.
        var best = 0.0
        var a = 0
        var b = 1
        val step = (pts.size / 60).coerceAtLeast(1)
        for (i in pts.indices step step) for (j in i + 1 until pts.size step step) {
            val d = hypot(projected[i].x - projected[j].x, projected[i].y - projected[j].y)
            if (d > best) { best = d; a = i; b = j }
        }
        if (best < 1) return null
        val kmPerUnit = Geo.distanceKm(pts[a], pts[b]) / best
        if (kmPerUnit <= 0) return null
        val nice = generateSequence(0.1) { it * 10 }.take(6).flatMap { sequenceOf(it, it * 2, it * 5) }.toList()
        val km = nice.firstOrNull { it / kmPerUnit >= 12 * k } ?: return null
        val len = km / kmPerUnit
        if (len > 34 * k) return null
        val x = k * 1.0
        val y = H - k * 2.0
        val text = if (km < 1) "${(km * 1000).toInt()} m" else "${if (km == floor(km)) km.toInt().toString() else km.toString()} km"
        val sw = k * 0.25
        return "<g stroke=\"${pen.ink}\" stroke-width=\"${n(sw)}\" fill=\"none\"><path d=\"M${n(x)},${n(y - k)}V${n(y)}H${n(x + len)}V${n(y - k)}\"/></g>" +
            "<text x=\"${n(x + len + k * 1.5)}\" y=\"${n(y)}\" font-family=\"'IBM Plex Mono',monospace\" font-size=\"${n(k * 2.3)}\" fill=\"${pen.ink}\">${esc(text)}</text>"
    }

    /**
     * The paper: dots at every grid crossing or a rule on every grid line, over [art] (trim coordinates, bleed
     * included), aligned to the grid's origin ([ox], [oy]) and [pitch].
     */
    fun paper(paper: BookPaper, art: Box, ox: Double, oy: Double, pitch: Double, dot: String, rule: String, scale: Double): String {
        if (paper == BookPaper.Plain) return ""
        val x0 = ox - (floor((ox - art.x) / pitch) + 0) * pitch
        val y0 = oy - floor((oy - art.y) / pitch) * pitch
        return buildString {
            append("<svg class=\"paper\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"${n2(art.x)} ${n2(art.y)} ${n2(art.w)} ${n2(art.h)}\" ")
            append("style=\"left:${n2(art.x)}mm;top:${n2(art.y)}mm;width:${n2(art.w)}mm;height:${n2(art.h)}mm\" aria-hidden=\"true\">")
            if (paper == BookPaper.Dots) {
                val d = 0.36 * scale.pow(0.5)
                append("<path fill=\"none\" stroke=\"$dot\" stroke-width=\"${n2(d)}\" stroke-linecap=\"round\" stroke-dasharray=\"0 ${n2(pitch)}\" d=\"")
                var y = y0
                while (y <= art.bottom + 0.01) {
                    append('M').append(n2(x0)).append(' ').append(n2(y)).append('H').append(n2(art.right + pitch))
                    y += pitch
                }
                append("\"/>")
            } else {
                append("<path fill=\"none\" stroke=\"$rule\" stroke-width=\"${n2(0.16 * scale)}\" d=\"")
                var y = y0
                while (y <= art.bottom + 0.01) {
                    append('M').append(n2(art.x)).append(' ').append(n2(y)).append('H').append(n2(art.right))
                    y += pitch
                }
                append("\"/>")
            }
            append("</svg>")
        }
    }

    /** Writing lines (or dots) in a box, for notes pages on plain paper. */
    fun lines(box: Box, paper: BookPaper, pitch: Double, dot: String, rule: String, scale: Double): String =
        paper(if (paper == BookPaper.Dots) BookPaper.Dots else BookPaper.Lines, Box(0.0, 0.0, box.w, box.h), 0.0, pitch, pitch, dot, rule, scale)
            .replace("left:0mm;top:0mm;", "left:0;top:0;")

    /** Crop marks over the whole sheet. */
    fun cropMarks(sheet: Sheet): String {
        val marks = sheet.cropMarks()
        if (marks.isEmpty()) return ""
        return "<svg class=\"marks\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${n2(sheet.pageW)} ${n2(sheet.pageH)}\" aria-hidden=\"true\">" +
            "<path fill=\"none\" stroke=\"#000\" stroke-width=\"0.12\" d=\"" +
            marks.joinToString("") { "M${n2(it.x1)} ${n2(it.y1)}L${n2(it.x2)} ${n2(it.y2)}" } + "\"/></svg>"
    }

    /** Millimetres with two decimals at most. */
    fun n2(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else java.lang.String.format(java.util.Locale.ROOT, "%.2f", r).trimEnd('0').trimEnd('.')
    }
}
