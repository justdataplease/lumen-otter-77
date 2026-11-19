package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.export.RouteSvg
import kotlin.math.cos
import kotlin.math.sin

/**
 * Country flags as small vector drawings (a 30 × 20 box), so they print sharp and look the same on every phone and
 * printer (emoji flags depend on the phone's font). Simplified: no coats of arms. Countries without a drawing print
 * their two letters in a box ([fallback]).
 */
object Flags {
    private const val W = 30.0
    private const val H = 20.0

    private fun rect(x: Double, y: Double, w: Double, h: Double, c: String) =
        "<rect x=\"${n(x)}\" y=\"${n(y)}\" width=\"${n(w)}\" height=\"${n(h)}\" fill=\"$c\"/>"

    private fun n(v: Double) = RouteSvg.n(v)

    /** Horizontal bands, heights in [weights] proportions. */
    private fun h(vararg c: String, weights: List<Double> = List(c.size) { 1.0 }): String {
        val total = weights.sum()
        var y = 0.0
        return buildString { c.forEachIndexed { i, col -> val hh = H * weights[i] / total; append(rect(0.0, y, W, hh + 0.05, col)); y += hh } }
    }

    private fun v(vararg c: String, weights: List<Double> = List(c.size) { 1.0 }): String {
        val total = weights.sum()
        var x = 0.0
        return buildString { c.forEachIndexed { i, col -> val ww = W * weights[i] / total; append(rect(x, 0.0, ww + 0.05, H, col)); x += ww } }
    }

    /** A Nordic cross, its upright a third of the way in; [inner] for a two-colour cross. */
    private fun nordic(bg: String, cross: String, inner: String? = null): String = buildString {
        append(rect(0.0, 0.0, W, H, bg))
        append(rect(8.0, 0.0, 4.5, H, cross)); append(rect(0.0, 7.75, W, 4.5, cross))
        if (inner != null) { append(rect(9.1, 0.0, 2.3, H, inner)); append(rect(0.0, 8.85, W, 2.3, inner)) }
    }

    private fun star(cx: Double, cy: Double, r: Double, c: String, rot: Double = -90.0): String {
        val pts = (0 until 10).joinToString(" ") { i ->
            val rr = if (i % 2 == 0) r else r * 0.4
            val a = Math.toRadians(rot + i * 36.0)
            "${n(cx + rr * cos(a))},${n(cy + rr * sin(a))}"
        }
        return "<polygon points=\"$pts\" fill=\"$c\"/>"
    }

    private fun circle(cx: Double, cy: Double, r: Double, c: String) = "<circle cx=\"${n(cx)}\" cy=\"${n(cy)}\" r=\"${n(r)}\" fill=\"$c\"/>"

    private fun unionJack(w: Double = W, h: Double = H): String =
        "<rect width=\"${n(w)}\" height=\"${n(h)}\" fill=\"#012169\"/>" +
            "<path d=\"M0,0L${n(w)},${n(h)}M${n(w)},0L0,${n(h)}\" stroke=\"#FFF\" stroke-width=\"${n(h * 0.2)}\"/>" +
            "<path d=\"M0,0L${n(w)},${n(h)}M${n(w)},0L0,${n(h)}\" stroke=\"#C8102E\" stroke-width=\"${n(h * 0.07)}\"/>" +
            "<path d=\"M${n(w / 2)},0V${n(h)}M0,${n(h / 2)}H${n(w)}\" stroke=\"#FFF\" stroke-width=\"${n(h * 0.33)}\"/>" +
            "<path d=\"M${n(w / 2)},0V${n(h)}M0,${n(h / 2)}H${n(w)}\" stroke=\"#C8102E\" stroke-width=\"${n(h * 0.2)}\"/>"

    private val defs: Map<String, () -> String> = mapOf(
        "GR" to {
            buildString {
                for (i in 0 until 9) append(rect(0.0, i * H / 9, W, H / 9 + 0.05, if (i % 2 == 0) "#0D5EAF" else "#FFFFFF"))
                val c = H * 5 / 9
                append(rect(0.0, 0.0, c, c, "#0D5EAF"))
                append(rect(c * 0.4, 0.0, c * 0.2, c, "#FFFFFF")); append(rect(0.0, c * 0.4, c, c * 0.2, "#FFFFFF"))
            }
        },
        "IT" to { v("#009246", "#FFFFFF", "#CE2B37") },
        "FR" to { v("#0055A4", "#FFFFFF", "#EF4135") },
        "IE" to { v("#169B62", "#FFFFFF", "#FF883E") },
        "BE" to { v("#1D1D1B", "#FDDA24", "#EF3340") },
        "RO" to { v("#002B7F", "#FCD116", "#CE1126") },
        "MT" to { v("#FFFFFF", "#CF142B") },
        "PE" to { v("#D91023", "#FFFFFF", "#D91023") },
        "NG" to { v("#008751", "#FFFFFF", "#008751") },
        "DE" to { h("#1D1D1B", "#DD0000", "#FFCE00") },
        "NL" to { h("#AE1C28", "#FFFFFF", "#21468B") },
        "AT" to { h("#ED2939", "#FFFFFF", "#ED2939") },
        "HU" to { h("#CE2939", "#FFFFFF", "#477050") },
        "BG" to { h("#FFFFFF", "#00966E", "#D62612") },
        "RU" to { h("#FFFFFF", "#0039A6", "#D52B1E") },
        "LU" to { h("#EA141D", "#FFFFFF", "#51ADDA") },
        "EE" to { h("#0072CE", "#1D1D1B", "#FFFFFF") },
        "LT" to { h("#FDB913", "#006A44", "#C1272D") },
        "LV" to { h("#9E3039", "#FFFFFF", "#9E3039", weights = listOf(2.0, 1.0, 2.0)) },
        "PL" to { h("#FFFFFF", "#DC143C") },
        "UA" to { h("#0057B7", "#FFD700") },
        "ID" to { h("#CE1126", "#FFFFFF") },
        "MC" to { h("#CE1126", "#FFFFFF") },
        "AM" to { h("#D90012", "#0033A0", "#F2A800") },
        "CO" to { h("#FCD116", "#003893", "#CE1126", weights = listOf(2.0, 1.0, 1.0)) },
        "ES" to { h("#AA151B", "#F1BF00", "#AA151B", weights = listOf(1.0, 2.0, 1.0)) },
        "HR" to { h("#FF0000", "#FFFFFF", "#171796") },
        "SI" to { h("#FFFFFF", "#0000FF", "#FF0000") },
        "SK" to { h("#FFFFFF", "#0B4EA2", "#EE1C25") },
        "RS" to { h("#C6363C", "#0C4076", "#FFFFFF") },
        "BO" to { h("#D52B1E", "#F9E300", "#007934") },
        "GA" to { h("#009E60", "#FCD116", "#3A75C4") },
        "TH" to { h("#A51931", "#F4F5F8", "#2D2A4A", "#F4F5F8", "#A51931", weights = listOf(1.0, 1.0, 2.0, 1.0, 1.0)) },
        "EG" to { h("#CE1126", "#FFFFFF", "#1D1D1B") + circle(W / 2, H / 2, 1.6, "#C09300") },
        "IN" to { h("#FF9933", "#FFFFFF", "#138808") + "<circle cx=\"15\" cy=\"10\" r=\"2.4\" fill=\"none\" stroke=\"#000080\" stroke-width=\"0.5\"/>" + circle(15.0, 10.0, 0.5, "#000080") },
        "AR" to { h("#74ACDF", "#FFFFFF", "#74ACDF") + circle(15.0, 10.0, 1.9, "#F6B40E") },
        "PT" to { v("#046A38", "#DA291C", weights = listOf(2.0, 3.0)) + circle(12.0, 10.0, 3.6, "#FFE900") + circle(12.0, 10.0, 2.2, "#DA291C") },
        "MX" to { v("#006847", "#FFFFFF", "#CE1126") + circle(15.0, 10.0, 2.4, "#8C6B3E") },
        "CH" to { rect(0.0, 0.0, W, H, "#DA291C") + rect(13.0, 4.5, 4.0, 11.0, "#FFFFFF") + rect(9.5, 8.0, 11.0, 4.0, "#FFFFFF") },
        "DK" to { nordic("#C8102E", "#FFFFFF") },
        "SE" to { nordic("#006AA7", "#FECC00") },
        "NO" to { nordic("#BA0C2F", "#FFFFFF", "#00205B") },
        "FI" to { nordic("#FFFFFF", "#002F6C") },
        "IS" to { nordic("#02529C", "#FFFFFF", "#DC1E35") },
        "FO" to { nordic("#FFFFFF", "#0065BD", "#EF303E") },
        "GB" to { unionJack() },
        "US" to {
            buildString {
                for (i in 0 until 13) append(rect(0.0, i * H / 13, W, H / 13 + 0.05, if (i % 2 == 0) "#B22234" else "#FFFFFF"))
                append(rect(0.0, 0.0, 12.0, H * 7 / 13, "#3C3B6E"))
                for (r in 0 until 5) for (c in 0 until 6) append(circle(1.1 + c * 2.0, 1.1 + r * 2.15, 0.42, "#FFFFFF"))
            }
        },
        "JP" to { rect(0.0, 0.0, W, H, "#FFFFFF") + circle(15.0, 10.0, 6.0, "#BC002D") },
        "KR" to {
            rect(0.0, 0.0, W, H, "#FFFFFF") + "<path d=\"M10,10a5,5 0 0 1 10,0z\" fill=\"#CD2E3A\"/><path d=\"M10,10a5,5 0 0 0 10,0z\" fill=\"#0047A0\"/>" +
                circle(12.5, 10.0, 2.5, "#CD2E3A") + circle(17.5, 10.0, 2.5, "#0047A0")
        },
        "CN" to { rect(0.0, 0.0, W, H, "#EE1C25") + star(5.0, 5.0, 3.0, "#FFFF00") + star(10.0, 2.0, 1.0, "#FFFF00") + star(12.0, 4.0, 1.0, "#FFFF00") + star(12.0, 7.0, 1.0, "#FFFF00") + star(10.0, 9.0, 1.0, "#FFFF00") },
        "VN" to { rect(0.0, 0.0, W, H, "#DA251D") + star(15.0, 10.5, 6.0, "#FFFF00") },
        "MA" to { rect(0.0, 0.0, W, H, "#C1272D") + "<polygon points=\"15,4.6 16.7,12.9 9.6,7.6 20.4,7.6 13.3,12.9\" fill=\"none\" stroke=\"#006233\" stroke-width=\"0.8\"/>" },
        "TR" to { rect(0.0, 0.0, W, H, "#E30A17") + circle(11.0, 10.0, 5.0, "#FFFFFF") + circle(12.25, 10.0, 4.0, "#E30A17") + star(17.3, 10.0, 2.2, "#FFFFFF", rot = 180.0) },
        "TN" to { rect(0.0, 0.0, W, H, "#E70013") + circle(15.0, 10.0, 5.0, "#FFFFFF") + circle(15.6, 10.0, 3.6, "#E70013") + circle(16.6, 10.0, 3.0, "#FFFFFF") + star(16.4, 10.0, 1.6, "#E70013") },
        "IL" to {
            rect(0.0, 0.0, W, H, "#FFFFFF") + rect(0.0, 2.0, W, 2.6, "#0038B8") + rect(0.0, 15.4, W, 2.6, "#0038B8") +
                "<path d=\"M15,6.2L18.3,11.9H11.7zM15,13.8L11.7,8.1H18.3z\" fill=\"none\" stroke=\"#0038B8\" stroke-width=\"0.7\"/>"
        },
        "CZ" to { h("#FFFFFF", "#D7141A") + "<path d=\"M0,0L15,10L0,20z\" fill=\"#11457E\"/>" },
        "PH" to { h("#0038A8", "#CE1126") + "<path d=\"M0,0L17,10L0,20z\" fill=\"#FFFFFF\"/>" + circle(5.5, 10.0, 2.0, "#FCD116") },
        "CU" to { h("#002A8F", "#FFFFFF", "#002A8F", "#FFFFFF", "#002A8F") + "<path d=\"M0,0L17,10L0,20z\" fill=\"#CF142B\"/>" + star(5.6, 10.0, 2.6, "#FFFFFF") },
        "BR" to { rect(0.0, 0.0, W, H, "#009C3B") + "<path d=\"M15,2L28,10L15,18L2,10z\" fill=\"#FFDF00\"/>" + circle(15.0, 10.0, 4.4, "#002776") },
        "CA" to {
            v("#D80621", "#FFFFFF", "#D80621", weights = listOf(1.0, 2.0, 1.0)) +
                "<path d=\"M15,3.6L16.3,6.3L18.4,5.6L17.6,9.6L19.8,8.4L20.3,9.9L22,9.4L21.2,11.6L22,12.4L18.4,14.6L18.8,15.9L15.4,15.4L15.4,18H14.6L14.6,15.4L11.2,15.9L11.6,14.6L8,12.4L8.8,11.6L8,9.4L9.7,9.9L10.2,8.4L12.4,9.6L11.6,5.6L13.7,6.3z\" fill=\"#D80621\"/>"
        },
        "AU" to {
            rect(0.0, 0.0, W, H, "#012169") + "<svg width=\"15\" height=\"10\" viewBox=\"0 0 30 20\">${unionJack()}</svg>" +
                star(7.5, 15.0, 2.2, "#FFFFFF") + star(22.5, 4.0, 1.1, "#FFFFFF") + star(19.0, 9.0, 1.1, "#FFFFFF") + star(25.5, 8.0, 1.1, "#FFFFFF") + star(22.5, 16.5, 1.1, "#FFFFFF")
        },
        "NZ" to {
            rect(0.0, 0.0, W, H, "#012169") + "<svg width=\"15\" height=\"10\" viewBox=\"0 0 30 20\">${unionJack()}</svg>" +
                star(22.5, 4.5, 1.2, "#C8102E") + star(19.5, 9.0, 1.2, "#C8102E") + star(25.0, 8.0, 1.0, "#C8102E") + star(22.5, 15.5, 1.4, "#C8102E")
        },
        "ZA" to {
            rect(0.0, 0.0, W, H, "#FFFFFF") + rect(0.0, 0.0, W, 6.7, "#E03C31") + rect(0.0, 13.3, W, 6.7, "#001489") +
                "<path d=\"M0,0L13,10L0,20\" fill=\"none\" stroke=\"#007749\" stroke-width=\"0\"/>" +
                "<path d=\"M-1,-1L12.5,8H31V12H12.5L-1,21z\" fill=\"#007749\"/>" + "<path d=\"M0,2.5L10,10L0,17.5z\" fill=\"#FFB81C\"/>" + "<path d=\"M0,4.5L7.4,10L0,15.5z\" fill=\"#1D1D1B\"/>"
        },
        "SG" to { h("#EF3340", "#FFFFFF") + circle(6.0, 5.0, 3.0, "#FFFFFF") + circle(7.2, 5.0, 2.8, "#EF3340") },
        "CY" to { rect(0.0, 0.0, W, H, "#FFFFFF") + "<path d=\"M8,9C10,6 16,5.5 22,6.5L23.5,7.5L19,9.5L13,10.5z\" fill=\"#D57800\"/>" + "<path d=\"M11,14Q15,16 19,14\" fill=\"none\" stroke=\"#4E5B31\" stroke-width=\"0.8\"/>" },
        "AL" to { rect(0.0, 0.0, W, H, "#E41E20") + circle(15.0, 10.0, 4.2, "#1D1D1B") },
        "ME" to { rect(0.0, 0.0, W, H, "#D3AE3B") + rect(1.0, 1.0, W - 2, H - 2, "#C40308") + circle(15.0, 10.0, 3.0, "#D3AE3B") },
        "MK" to { rect(0.0, 0.0, W, H, "#D20000") + circle(15.0, 10.0, 3.4, "#FFE600") },
        "BA" to { rect(0.0, 0.0, W, H, "#002395") + "<path d=\"M8,0H22V20z\" fill=\"#FECB00\"/>" },
        "GE" to { rect(0.0, 0.0, W, H, "#FFFFFF") + rect(13.0, 0.0, 4.0, H, "#FF0000") + rect(0.0, 8.0, W, 4.0, "#FF0000") },
        "AE" to { h("#00732F", "#FFFFFF", "#1D1D1B") + rect(0.0, 0.0, 7.5, H, "#FF0000") },
        "JO" to { h("#1D1D1B", "#FFFFFF", "#007A3D") + "<path d=\"M0,0L14,10L0,20z\" fill=\"#CE1126\"/>" },
        "KE" to { h("#1D1D1B", "#BB0000", "#006600") + rect(0.0, 6.2, W, 0.8, "#FFFFFF") + rect(0.0, 13.0, W, 0.8, "#FFFFFF") },
        "CL" to { h("#FFFFFF", "#D52B1E") + rect(0.0, 0.0, 10.0, 10.0, "#0039A6") + star(5.0, 5.0, 2.4, "#FFFFFF") },
        "UY" to { h("#FFFFFF", "#0038A8", "#FFFFFF", "#0038A8", "#FFFFFF", "#0038A8", "#FFFFFF", "#0038A8", "#FFFFFF") + rect(0.0, 0.0, 11.0, 11.0, "#FFFFFF") + circle(5.5, 5.5, 2.6, "#FCD116") },
    )

    /** Inner SVG for [code] (0 0 30 20), or null when there is no drawing for it. */
    fun body(code: String): String? = defs[code.uppercase()]?.invoke()

    fun has(code: String): Boolean = code.uppercase() in defs

    /** A whole `<svg>` for [code], [heightMm] tall; letters in a box for countries without a drawing. */
    fun svg(code: String, heightMm: Double): String {
        val c = code.uppercase().filter { it in 'A'..'Z' }.take(2)
        val w = heightMm * 1.5
        val inner = body(c) ?: fallback(c)
        return "<svg class=\"flag\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 30 20\" width=\"${n(w)}mm\" height=\"${n(heightMm)}mm\" " +
            "preserveAspectRatio=\"none\" role=\"img\" aria-label=\"$c\">$inner</svg>"
    }

    private fun fallback(c: String): String =
        "<rect width=\"30\" height=\"20\" fill=\"#FFFFFF\"/><rect x=\"0.5\" y=\"0.5\" width=\"29\" height=\"19\" fill=\"none\" stroke=\"#8A9096\" stroke-width=\"1\"/>" +
            "<text x=\"15\" y=\"14\" text-anchor=\"middle\" font-family=\"'IBM Plex Mono',monospace\" font-weight=\"600\" font-size=\"11\" fill=\"#3F464C\">$c</text>"
}
