package com.justdataplease.boringtravel.album

/*
 * Photo-album arrangements: 1, 2, 3, 4, 6 or 9 photos of different sizes on a grid, with the same gutter between
 * every two. Pure geometry in whatever unit the caller works in (mm on a book page, dp or px in the journal); no app
 * types, so the book ([com.justdataplease.boringtravel.book]) and the journal can both lay photos out with them.
 */

/** A rectangle, [x] and [y] from the box's top-left, in the box's units. */
data class AlbumRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val right: Double get() = x + w
    val bottom: Double get() = y + h

    /** Whether this and [o] share any area (touching edges don't count). */
    fun overlaps(o: AlbumRect, eps: Double = 1e-9): Boolean =
        x < o.right - eps && o.x < right - eps && y < o.bottom - eps && o.y < bottom - eps
}

/** One photo's place on a layout's grid: its first column and row, and how many columns and rows it spans. */
data class AlbumCell(val col: Int, val row: Int, val cols: Int = 1, val rows: Int = 1)

/**
 * An arrangement of [count] photos: [cells] (in photo order) on a grid of [colWeights].size columns and
 * [rowWeights].size rows; the weights share out what is left of the box after the gutters (2, 1 = two thirds and one
 * third). [minAspect]..[maxAspect] bound the whole arrangement's shape (width / height): in a box outside them the
 * arrangement is made narrower or shorter and centred, so photos never become thin strips on a tall or wide page.
 */
data class AlbumLayout(
    val id: String,
    val name: String,
    val colWeights: List<Double>,
    val rowWeights: List<Double>,
    val cells: List<AlbumCell>,
    val minAspect: Double = 0.0,
    val maxAspect: Double = Double.POSITIVE_INFINITY,
) {
    val count: Int get() = cells.size
    val columns: Int get() = colWeights.size
    val rows: Int get() = rowWeights.size

    /** The photos' rectangles in a [w] × [h] box with [gutter] between neighbours, in photo order. */
    fun place(w: Double, h: Double, gutter: Double): List<AlbumRect> {
        if (w <= 0.0 || h <= 0.0) return cells.map { AlbumRect(0.0, 0.0, 0.0, 0.0) }
        val a = w / h
        val target = a.coerceIn(minAspect, maxAspect)
        val bw = if (target < a) h * target else w
        val bh = if (target > a) w / target else h
        val xs = tracks(colWeights, bw, gutter, (w - bw) / 2)
        val ys = tracks(rowWeights, bh, gutter, (h - bh) / 2)
        return cells.map { c ->
            val x0 = xs[c.col].first
            val x1 = xs[c.col + c.cols - 1].let { it.first + it.second }
            val y0 = ys[c.row].first
            val y1 = ys[c.row + c.rows - 1].let { it.first + it.second }
            AlbumRect(x0, y0, x1 - x0, y1 - y0)
        }
    }

    /** [place] as fractions of the box (0..1 across and down). */
    fun fractions(w: Double, h: Double, gutter: Double): List<AlbumRect> =
        place(w, h, gutter).map { AlbumRect(it.x / w, it.y / h, it.w / w, it.h / h) }

    /**
     * The height a box [w] wide needs (a column that grows downwards, like the journal's): a grid cell of weight one
     * gets [unitAspect] (width / height), kept within [minAspect]..[maxAspect] for the whole arrangement.
     */
    fun heightFor(w: Double, gutter: Double, unitAspect: Double = 4.0 / 3.0): Double {
        if (w <= 0.0) return 0.0
        val unitW = (w - gutter * (columns - 1)).coerceAtLeast(0.0) / colWeights.sum()
        val h = rowWeights.sum() * unitW / unitAspect + gutter * (rows - 1)
        val a = (w / h).coerceIn(minAspect, maxAspect)
        return w / a
    }

    private fun tracks(weights: List<Double>, total: Double, gutter: Double, origin: Double): List<Pair<Double, Double>> {
        val free = (total - gutter * (weights.size - 1)).coerceAtLeast(0.0)
        val sum = weights.sum()
        var pos = origin
        return weights.map { wt ->
            val size = free * wt / sum
            (pos to size).also { pos += size + gutter }
        }
    }
}

/** Every arrangement, grouped by how many photos it holds; the first of each group is that count's usual one. */
object AlbumLayouts {
    private fun cell(col: Int, row: Int, cols: Int = 1, rows: Int = 1) = AlbumCell(col, row, cols, rows)
    private fun grid(cols: Int, rows: Int): List<AlbumCell> = (0 until rows).flatMap { r -> (0 until cols).map { c -> AlbumCell(c, r) } }
    private fun even(n: Int): List<Double> = List(n) { 1.0 }

    val all: List<AlbumLayout> = listOf(
        // One photo: the whole area, a landscape band, a square.
        AlbumLayout("1-full", "Full", even(1), even(1), grid(1, 1)),
        AlbumLayout("1-wide", "Landscape", even(1), even(1), grid(1, 1), minAspect = 1.5, maxAspect = 1.5),
        AlbumLayout("1-square", "Square", even(1), even(1), grid(1, 1), minAspect = 1.0, maxAspect = 1.0),
        // Two.
        AlbumLayout("2-side", "Side by side", even(2), even(1), grid(2, 1), minAspect = 1.4),
        AlbumLayout("2-stack", "Stacked", even(1), even(2), grid(1, 2), maxAspect = 0.8),
        AlbumLayout("2-big", "Big and small", even(1), listOf(2.0, 1.0), grid(1, 2), maxAspect = 0.8),
        // Three.
        AlbumLayout("3-top", "Big on top", even(2), listOf(2.0, 1.0), listOf(cell(0, 0, cols = 2), cell(0, 1), cell(1, 1)), maxAspect = 0.95),
        AlbumLayout("3-bottom", "Big below", even(2), listOf(1.0, 2.0), listOf(cell(0, 0), cell(1, 0), cell(0, 1, cols = 2)), maxAspect = 0.95),
        AlbumLayout("3-side", "Big beside", listOf(2.0, 1.0), even(2), listOf(cell(0, 0, rows = 2), cell(1, 0), cell(1, 1)), minAspect = 1.0),
        AlbumLayout("3-stack", "Stacked", even(1), even(3), grid(1, 3), maxAspect = 0.55),
        // Four.
        AlbumLayout("4-grid", "Grid", even(2), even(2), grid(2, 2)),
        AlbumLayout("4-big", "Big and three", even(3), listOf(2.0, 1.0), listOf(cell(0, 0, cols = 3), cell(0, 1), cell(1, 1), cell(2, 1))),
        AlbumLayout(
            "4-mix", "Wide and tall", even(2), listOf(2.0, 3.0, 2.0),
            listOf(cell(0, 0, cols = 2), cell(0, 1), cell(1, 1), cell(0, 2, cols = 2)), maxAspect = 0.8,
        ),
        // Six.
        AlbumLayout("6-grid", "Two across", even(2), even(3), grid(2, 3), maxAspect = 0.9),
        AlbumLayout("6-wide", "Three across", even(3), even(2), grid(3, 2), minAspect = 1.0),
        AlbumLayout("6-big", "Big and five", even(3), even(3), listOf(cell(0, 0, 2, 2), cell(2, 0), cell(2, 1), cell(0, 2), cell(1, 2), cell(2, 2))),
        // Nine.
        AlbumLayout("9-grid", "Grid", even(3), even(3), grid(3, 3)),
        AlbumLayout(
            "9-big", "Big and eight", even(3), even(4),
            listOf(cell(0, 0, 2, 2), cell(2, 0), cell(2, 1)) + (2..3).flatMap { r -> (0..2).map { c -> cell(c, r) } },
        ),
    )

    private val byId: Map<String, AlbumLayout> = all.associateBy { it.id }

    /** How many photos the arrangements hold, smallest first. */
    val counts: List<Int> = all.map { it.count }.distinct().sorted()

    /** The most photos one arrangement holds. */
    val max: Int get() = counts.last()

    fun of(id: String?): AlbumLayout? = id?.let { byId[it] }

    /** The arrangements for exactly [n] photos. */
    fun forCount(n: Int): List<AlbumLayout> = all.filter { it.count == n }

    /** The usual arrangement for exactly [n] photos, if there is one. */
    fun exact(n: Int): AlbumLayout? = all.firstOrNull { it.count == n }

    /** The usual arrangement of the smallest group that holds [n] photos (null: more than any holds). */
    fun fitting(n: Int): AlbumLayout? = counts.firstOrNull { it >= n }?.let(::exact)

    /**
     * Arrangements that take [n] photos with no spot left empty: [first] as often as it fills up, then for what is
     * left the arrangement for exactly that many, or the biggest that is smaller (and so on).
     */
    fun split(n: Int, first: AlbumLayout? = null): List<AlbumLayout> {
        val out = ArrayList<AlbumLayout>()
        var left = n
        while (left > 0) {
            val l = first?.takeIf { left >= it.count } ?: exact(left) ?: exact(counts.last { it < left })!!
            out += l
            left -= l.count
        }
        return out
    }
}
