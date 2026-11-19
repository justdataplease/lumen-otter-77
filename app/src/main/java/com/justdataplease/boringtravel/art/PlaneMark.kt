package com.justdataplease.boringtravel.art

import java.util.Locale

/**
 * The brand mark: a folded paper plane with navy edges (white top sheet, pale wing, navy keel, two pale rules) flying
 * up-right from a navy dotted trail, on a solid yellow tile: the theme's yellow in the app, Postcard Pop's on the
 * launcher icon. The launcher icon is the same drawing, written by docs/brand/plane_icon.py; keep the two in step
 * (PlaneMarkTest reads the script and checks).
 *
 * Coordinates are the 108-unit adaptive-icon canvas; the visible square is 18..90 and the plane is drawn at [SCALE]
 * around the centre. Pure, so books and exports (SVG) and the screens and widgets ([PlaneMarkBitmap]) share it.
 */
object PlaneMark {
    const val VIEW_MIN = 18f
    const val VIEW_SIZE = 72f
    const val SCALE = 0.84f
    const val CENTER = 54f

    /** The plane's three faces as triangles (x, y pairs), in drawing order: the wing, the top sheet over it, the keel. */
    val WING = floatArrayOf(82f, 28f, 50f, 56f, 55f, 72f)
    val BODY = floatArrayOf(82f, 28f, 27f, 48f, 50f, 56f)
    val KEEL = floatArrayOf(50f, 56f, 46f, 66f, 55f, 72f)

    /** The navy edges round every face (round joins), in canvas units. */
    const val STROKE = 2.4f

    /** Two pale rules on the top sheet, as line segments (x0, y0, x1, y1), round caps. */
    val RULES = floatArrayOf(44f, 46.5f, 58f, 41.5f, 41f, 50.5f, 51f, 46.9f)
    const val RULE_W = 1.3f

    /** The dotted trail behind the plane. */
    val DOTS = floatArrayOf(22.00f, 84.00f, 26.76f, 81.18f, 31.21f, 77.90f, 35.58f, 74.49f, 40.07f, 71.27f)
    const val DOT_R = 1.5f

    /** The tile's corner radius as a share of its side, like an app icon on a home screen. */
    const val CORNER = 0.24f

    /** Postcard Pop's yellow (its highlighter): the launcher icon and every place without a theme. */
    val POP_YELLOW: Int = 0xFFFFC93C.toInt()

    /** Postcard Pop's coral: the notification's accent (a yellow would not read on the light shade). */
    val POP_ORANGE: Int = 0xFFFF5A3C.toInt()

    /** The plane's own colours, the same on every theme. */
    val NAVY: Int = 0xFF1E2A5A.toInt()
    val WHITE: Int = 0xFFFFFFFF.toInt()
    val PALE_WING: Int = 0xFFDCE2EF.toInt()
    val RULE: Int = 0xFFB9C6E4.toInt()

    /** The mark's colours as ARGB: the tile is the theme's yellow ([sky]); the plane keeps the brand's navy and white. */
    data class Colors(
        val sky: Int,
        val ink: Int = NAVY,
        val body: Int = WHITE,
        val wing: Int = PALE_WING,
        val rule: Int = RULE,
    ) {
        /** The faces with their fills, in drawing order (each edged in [ink]). */
        val faces: List<Pair<FloatArray, Int>> get() = listOf(WING to wing, BODY to body, KEEL to ink)

        companion object {
            val Pop = Colors(POP_YELLOW)
        }
    }

    /** A canvas coordinate in the visible square (0..72), with the plane's scale applied. */
    fun local(v: Float): Float = CENTER + (v - CENTER) * SCALE - VIEW_MIN

    fun hex(c: Int): String = String.format(Locale.ROOT, "#%06X", c and 0xFFFFFF)

    private fun n(v: Float) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun tri(p: FloatArray) = "M${n(local(p[0]))},${n(local(p[1]))}L${n(local(p[2]))},${n(local(p[3]))}L${n(local(p[4]))},${n(local(p[5]))}Z"

    /**
     * The mark as inline SVG for prints and books: the rounded tile and the plane, filling its box. [pad] is empty
     * space around the tile as a share of the side.
     */
    fun svg(colors: Colors = Colors.Pop, pad: Float = 0f): String {
        val s = VIEW_SIZE
        val p = s * pad
        val r = s * CORNER
        val sb = StringBuilder()
        val o = if (p == 0f) "0" else n(-p)
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"$o $o ${n(s + 2 * p)} ${n(s + 2 * p)}\" width=\"100%\" height=\"100%\" role=\"img\" aria-label=\"BoringTravel\">")
        sb.append("<rect width=\"${n(s)}\" height=\"${n(s)}\" rx=\"${n(r)}\" fill=\"${hex(colors.sky)}\"/>")
        sb.append("<g fill=\"${hex(colors.ink)}\">")
        for (i in DOTS.indices step 2) sb.append("<circle cx=\"${n(local(DOTS[i]))}\" cy=\"${n(local(DOTS[i + 1]))}\" r=\"${n(DOT_R * SCALE)}\"/>")
        sb.append("</g>")
        sb.append("<g stroke=\"${hex(colors.ink)}\" stroke-width=\"${n(STROKE * SCALE)}\" stroke-linejoin=\"round\">")
        for ((face, fill) in colors.faces) sb.append("<path d=\"${tri(face)}\" fill=\"${hex(fill)}\"/>")
        sb.append("</g>")
        sb.append("<path d=\"")
        for (i in RULES.indices step 4) {
            sb.append("M${n(local(RULES[i]))},${n(local(RULES[i + 1]))}L${n(local(RULES[i + 2]))},${n(local(RULES[i + 3]))}")
        }
        sb.append("\" fill=\"none\" stroke=\"${hex(colors.rule)}\" stroke-width=\"${n(RULE_W * SCALE)}\" stroke-linecap=\"round\"/>")
        sb.append("</svg>")
        return sb.toString()
    }
}
