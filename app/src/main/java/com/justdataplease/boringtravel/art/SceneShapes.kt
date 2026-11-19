package com.justdataplease.boringtravel.art

/**
 * The drawn pictures as flat shapes on a 240×160 canvas, ported one to one from the design board "Drawn pictures"
 * (scenes_board.py; checked against its scenes.json in SceneShapesTest). Pure Kotlin, so [SceneArt] only paints.
 */
sealed interface Shape {
    val fill: String?
    val stroke: String?
    val sw: Float

    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, override val fill: String, override val stroke: String? = null, override val sw: Float = 1f) : Shape
    data class Circle(val cx: Float, val cy: Float, val r: Float, override val fill: String, override val stroke: String? = null, override val sw: Float = 1f) : Shape
    data class Poly(val pts: List<Pair<Float, Float>>, override val fill: String, override val stroke: String? = null, override val sw: Float = 1f) : Shape
    data class PathD(val d: String, override val fill: String, override val stroke: String? = null, override val sw: Float = 1f) : Shape
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, override val stroke: String, override val sw: Float = 2f) : Shape {
        override val fill: String? get() = null
    }
}

object SceneShapes {
    const val PAPER = "#FCFCF9"

    fun of(kind: SceneKind, p: ScenePalette): List<Shape> = when (kind) {
        SceneKind.Mountain -> mountain(p)
        SceneKind.Lake -> lake(p)
        SceneKind.City -> city(p)
        SceneKind.Village -> village(p)
        SceneKind.Coast -> coast(p)
        SceneKind.Forest -> forest(p)
        SceneKind.Glacier -> glacier(p)
        SceneKind.Countryside -> countryside(p)
        SceneKind.Desert -> desert(p)
        SceneKind.Night -> night(p)
    }

    /** The plain colour under everything (the sketch's paper), or null. */
    fun background(p: ScenePalette): String? = if (p.sketch) PAPER else null

    /**
     * What is painted, in order, over [background]: the sketch drops the full-bleed sky bands; every shape keeps
     * its fill. Outlines come from [strokeOf].
     */
    fun painted(kind: SceneKind, p: ScenePalette): List<Shape> {
        val shapes = of(kind, p)
        if (!p.sketch) return shapes
        return shapes.filterNot { it is Shape.Rect && it.x == 0f && it.y == 0f && it.w == 240f }
    }

    /** The outline a shape is drawn with: in the sketch every shape in ink (lines keep their width, the rest 1.5). */
    fun strokeOf(shape: Shape, p: ScenePalette): Pair<String, Float>? = when {
        p.sketch -> p.ink to (if (shape is Shape.Line) shape.sw else 1.5f)
        shape.stroke != null -> shape.stroke!! to shape.sw
        else -> null
    }

    private fun rect(x: Number, y: Number, w: Number, h: Number, fill: String, stroke: String? = null, sw: Number = 1f) =
        Shape.Rect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), fill, stroke, sw.toFloat())

    private fun circ(cx: Number, cy: Number, r: Number, fill: String) = Shape.Circle(cx.toFloat(), cy.toFloat(), r.toFloat(), fill)

    private fun poly(fill: String, vararg xy: Number, stroke: String? = null, sw: Number = 1f) =
        Shape.Poly(xy.toList().chunked(2) { (x, y) -> x.toFloat() to y.toFloat() }, fill, stroke, sw.toFloat())

    private fun line(x1: Number, y1: Number, x2: Number, y2: Number, stroke: String, sw: Number = 2f) =
        Shape.Line(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), stroke, sw.toFloat())

    private fun sky(p: ScenePalette, h: Float = 160f): List<Shape> = listOf(
        rect(0, 0, 240, h, p.sky[2]), rect(0, 0, 240, h * .55f, p.sky[1]), rect(0, 0, 240, h * .25f, p.sky[0]),
    )

    private fun mountain(p: ScenePalette) = sky(p) + listOf(
        circ(186, 40, 18, p.sun),
        poly(p.far, 0, 110, 40, 76, 70, 92, 110, 58, 150, 86, 190, 62, 240, 96, 240, 160, 0, 160),
        poly(p.near, 0, 132, 36, 112, 62, 118, 92, 70, 108, 52, 124, 74, 146, 96, 172, 88, 204, 118, 240, 112, 240, 160, 0, 160),
        poly(p.glow, 108, 52, 124, 74, 146, 96, 136, 94, 118, 70),
        poly(p.snow, 98, 62, 108, 52, 118, 66, 110, 64, 104, 70),
        poly(p.grass, 0, 144, 60, 132, 130, 146, 190, 134, 240, 140, 240, 160, 0, 160),
    )

    private fun lake(p: ScenePalette) = sky(p) + listOf(
        circ(52, 36, 14, p.sun),
        poly(p.near, 0, 96, 44, 62, 80, 80, 122, 46, 164, 74, 204, 52, 240, 76, 240, 104, 0, 104),
        poly(p.snow, 122, 46, 134, 56, 126, 58, 122, 52, 114, 60),
        poly(p.snow, 204, 52, 216, 60, 210, 62, 204, 58, 198, 64),
        rect(0, 100, 240, 46, p.water),
        line(20, 114, 70, 114, p.water2, 2.5f), line(110, 126, 180, 126, p.water2, 2.5f), line(150, 108, 200, 108, p.water2, 2.5f),
        poly(p.ink, 150, 134, 176, 134, 172, 140, 154, 140), line(162, 134, 162, 120, p.ink, 1.5f), poly(p.buildings[0], 163, 121, 163, 133, 172, 133),
        rect(0, 146, 240, 14, p.grass),
    )

    private fun city(p: ScenePalette): List<Shape> {
        val s = sky(p).toMutableList()
        s += circ(196, 34, 16, p.sun)
        s += rect(0, 138, 240, 22, p.ground)
        val blocks = listOf(intArrayOf(0, 30, 70), intArrayOf(30, 26, 50), intArrayOf(56, 34, 92), intArrayOf(90, 24, 60), intArrayOf(114, 30, 100), intArrayOf(144, 26, 66), intArrayOf(170, 36, 84), intArrayOf(206, 34, 56))
        blocks.forEachIndexed { i, (x, w, h) ->
            s += rect(x, 138 - h, w, h, p.buildings[i % 5])
            for (wy in (138 - h + 8) until 130 step 16) for (wx in intArrayOf(x + 5, x + w - 13)) s += rect(wx, wy, 8, 8, p.win, stroke = p.ink, sw = 1)
        }
        return s
    }

    private fun village(p: ScenePalette): List<Shape> {
        val s = sky(p).toMutableList()
        s += circ(40, 34, 14, p.sun)
        s += poly(p.grass2, 0, 104, 60, 84, 130, 98, 190, 80, 240, 92, 240, 160, 0, 160)
        s += poly(p.grass, 0, 126, 80, 112, 160, 124, 240, 114, 240, 160, 0, 160)
        for ((x, y, c) in listOf(Triple(26, 108, p.buildings[3]), Triple(64, 114, p.buildings[0]), Triple(150, 112, p.buildings[2]), Triple(188, 106, p.buildings[4]))) {
            s += rect(x, y, 30, 24, c, stroke = p.ink, sw = 1)
            s += poly(if (c != p.buildings[0]) p.buildings[0] else p.ink, x - 3, y, x + 15, y - 14, x + 33, y)
            s += rect(x + 11, y + 10, 8, 14, p.ink)
        }
        s += rect(108, 70, 18, 62, p.buildings[3], stroke = p.ink, sw = 1)
        s += poly(p.ink, 104, 70, 117, 48, 130, 70)
        s += rect(113, 80, 8, 10, p.win, stroke = p.ink, sw = 1)
        for (tx in intArrayOf(6, 230, 92)) {
            s += poly(p.grass2, tx - 8, 132, tx, 112, tx + 8, 132)
            s += rect(tx - 1.5f, 132, 3, 6, p.ink)
        }
        return s
    }

    private fun coast(p: ScenePalette) = sky(p) + listOf(
        circ(176, 44, 20, p.sun),
        rect(0, 88, 240, 44, p.water),
        line(14, 100, 54, 100, p.water2, 2.5f), line(90, 112, 150, 112, p.water2, 2.5f), line(170, 98, 220, 98, p.water2, 2.5f),
        poly(p.near, 0, 60, 30, 54, 52, 70, 60, 132, 0, 132),
        poly(p.sand, 0, 132, 240, 120, 240, 160, 0, 160),
        line(170, 150, 176, 118, p.ink, 2), poly(p.buildings[0], 152, 120, 176, 108, 198, 122), poly(p.buildings[2], 164, 116, 176, 108, 186, 117),
    )

    private fun forest(p: ScenePalette): List<Shape> {
        val s = sky(p).toMutableList()
        s += circ(60, 34, 13, p.sun)
        class Row(val base: Int, val colour: String, val h: Int, val step: Int)
        for (r in listOf(Row(78, p.far, 22, 16), Row(100, p.grass2, 26, 20), Row(124, p.grass, 30, 24))) {
            for (x in -6 until 250 step r.step) s += poly(r.colour, x - r.step * .55f, r.base + 14, x, r.base - r.h, x + r.step * .55f, r.base + 14)
        }
        s += rect(0, 136, 240, 24, p.grass2)
        s += poly(p.ground, 108, 160, 118, 136, 124, 136, 138, 160)
        return s
    }

    private fun glacier(p: ScenePalette): List<Shape> {
        val s = sky(p).toMutableList()
        s += poly(p.far, 0, 66, 50, 40, 100, 58, 150, 34, 200, 54, 240, 42, 240, 80, 0, 80)
        val top = intArrayOf(0, 74, 20, 68, 36, 76, 58, 64, 80, 74, 102, 62, 124, 72, 146, 60, 168, 70, 190, 62, 214, 72, 240, 66)
        s += poly(p.snow, *(top.toTypedArray() + arrayOf(240, 112, 0, 112)), stroke = p.water2, sw = 1)
        for (x in 18 until 240 step 34) s += line(x, 80, x - 2, 108, p.water2, 1.2f)
        s += rect(0, 104, 240, 8, p.water2)
        s += rect(0, 112, 240, 48, p.water)
        s += poly(p.snow, 30, 128, 44, 124, 52, 128)
        s += poly(p.snow, 160, 140, 178, 135, 190, 140)
        s += poly(p.ink, 110, 146, 130, 146, 127, 151, 113, 151)
        return s
    }

    /** Farmland: rolling hills, a farmhouse, a wheat field with short crop rows, three round trees and a fence. */
    private fun countryside(p: ScenePalette): List<Shape> {
        val s = sky(p).toMutableList()
        s += circ(58, 40, 17, p.sun)
        s += Shape.PathD("M0 98 C40 76 92 78 132 90 S204 70 240 84 V160 H0Z", p.grass2)
        s += rect(164, 70, 28, 20, p.buildings[0])
        s += poly(p.ink, 160, 70, 178, 57, 196, 70)
        s += rect(175, 78, 7, 12, p.ink)
        s += rect(166, 74, 6, 6, p.win)
        s += Shape.PathD("M0 116 C50 100 112 102 152 114 S222 106 240 110 V160 H0Z", p.sand)
        for (x in intArrayOf(14, 52, 92, 132, 172, 208)) s += line(x, 128, x + 26, 119, p.ground, 2.2f)
        s += Shape.PathD("M0 136 C60 124 132 130 240 124 V160 H0Z", p.grass)
        for ((tx, ty, r) in listOf(Triple(30, 112, 9), Triple(224, 106, 8), Triple(120, 118, 6))) {
            s += rect(tx - 1.5f, ty, 3, 10, p.ink)
            s += circ(tx, ty - 3, r, p.grass2)
        }
        for (fx in 8 until 240 step 22) s += rect(fx, 138, 2.5f, 9, p.ink)
        s += line(0, 141, 240, 138, p.ink, 1.4f)
        return s
    }

    private fun desert(p: ScenePalette) = sky(p) + listOf(
        circ(120, 62, 30, p.sun),
        poly(p.near, 0, 96, 40, 96, 46, 80, 78, 80, 84, 96, 240, 96, 240, 110, 0, 110),
        Shape.PathD("M0 118 C50 98 100 132 160 112 S220 104 240 110 V160 H0Z", p.sand),
        Shape.PathD("M0 140 C60 124 120 150 180 134 S230 130 240 134 V160 H0Z", p.ground),
        rect(186, 104, 6, 30, p.grass2), rect(180, 112, 6, 4, p.grass2), rect(176, 104, 4, 12, p.grass2),
        rect(192, 116, 6, 4, p.grass2), rect(196, 108, 4, 12, p.grass2),
    )

    private fun night(p: ScenePalette): List<Shape> {
        val (d0, d1, d2) = p.dark
        val block = if (p.sketch) PAPER else p.ink
        val s = mutableListOf<Shape>(rect(0, 0, 240, 160, d2), rect(0, 0, 240, 100, d1), rect(0, 0, 240, 50, d0), circ(60, 36, 14, p.sun))
        for ((x, y) in listOf(120 to 20, 160 to 34, 200 to 16, 30 to 70, 220 to 50)) s += circ(x, y, 1.6f, p.win)
        val blocks = listOf(intArrayOf(0, 30, 64), intArrayOf(30, 24, 44), intArrayOf(54, 32, 80), intArrayOf(86, 22, 56), intArrayOf(108, 12, 110), intArrayOf(120, 34, 70), intArrayOf(154, 26, 90), intArrayOf(180, 30, 60), intArrayOf(210, 30, 76))
        for ((x, w, h) in blocks) {
            s += rect(x, 160 - h, w, h, block)
            for (wy in (160 - h + 8) until 154 step 14) if ((wy + x) % 3 != 0) s += rect(x + 5, wy, 6, 7, p.lit)
        }
        s += poly(block, 108, 50, 114, 34, 120, 50)
        return s
    }
}

/** Absolute SVG path data (M, L, H, V, C, S, Z) as drawing steps; enough for the board's paths. */
object SvgPath {
    sealed interface Op {
        data class Move(val x: Float, val y: Float) : Op
        data class LineTo(val x: Float, val y: Float) : Op
        data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Op
        data object Close : Op
    }

    fun parse(d: String): List<Op> {
        val tokens = Regex("[MLHVCSZmlhvcsz]|-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
        val ops = ArrayList<Op>()
        var i = 0
        var cmd = 'M'
        var x = 0f
        var y = 0f
        var lastCtrl: Pair<Float, Float>? = null
        fun num() = tokens[i++].toFloat()
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.length == 1 && t[0].isLetter()) {
                cmd = t[0]
                i++
                if (cmd == 'Z' || cmd == 'z') { ops += Op.Close; lastCtrl = null }
                continue
            }
            when (cmd) {
                'M' -> { x = num(); y = num(); ops += Op.Move(x, y); cmd = 'L'; lastCtrl = null }
                'L' -> { x = num(); y = num(); ops += Op.LineTo(x, y); lastCtrl = null }
                'H' -> { x = num(); ops += Op.LineTo(x, y); lastCtrl = null }
                'V' -> { y = num(); ops += Op.LineTo(x, y); lastCtrl = null }
                'C' -> {
                    val c = Op.Cubic(num(), num(), num(), num(), num(), num())
                    ops += c; x = c.x; y = c.y; lastCtrl = c.x2 to c.y2
                }
                'S' -> {
                    val (rx, ry) = lastCtrl?.let { (cx, cy) -> 2 * x - cx to 2 * y - cy } ?: (x to y)
                    val c = Op.Cubic(rx, ry, num(), num(), num(), num())
                    ops += c; x = c.x; y = c.y; lastCtrl = c.x2 to c.y2
                }
                else -> throw IllegalArgumentException("Unsupported path command $cmd")
            }
        }
        return ops
    }
}
