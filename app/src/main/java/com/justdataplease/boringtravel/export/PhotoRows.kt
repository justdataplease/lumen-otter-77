package com.justdataplease.boringtravel.export

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Photos laid out whole, never cropped (owner, 2026-11-12: "sometimes images are cropped … more compact auto layout of
 * the images nicely"). Several photos go side by side in rows: in a row every photo is as tall as the others and as
 * wide as its own shape makes it, and the row fills the width (a justified row). How many go in each row is chosen so
 * the rows come close to a target height. A photo alone is as wide as it may be, up to a height. Pure; any one unit
 * (mm for print and Word, px for Google Docs).
 */
object PhotoRows {
    /** Most photos side by side; with more, each gets too small to see. */
    const val MAX_PER_ROW = 6

    /** Shapes this far out are treated as this far (a 10:1 strip is drawn whole inside a 5:1 box). */
    const val MIN_ASPECT = 0.25
    const val MAX_ASPECT = 5.0

    /** A photo whose shape isn't known: the usual camera's 4:3. */
    const val UNKNOWN_ASPECT = 4.0 / 3.0

    /**
     * One row: photos [from] until [to] (exclusive), [height] tall, [width] wide with its gaps and frames: the full
     * width, or less for a row that stops at its height instead of growing (a last row with few photos).
     */
    data class Row(val from: Int, val to: Int, val height: Double, val width: Double) {
        val count: Int get() = to - from
    }

    /** How big photos print at one [PhotoSize]: a row's target height, the tallest a photo alone may be, its share of the width. */
    data class Scale(val rowMm: Double, val singleMm: Double, val share: Double)

    /** Large keeps about two landscape photos in a row, Medium three, Small four (as the old grids did). */
    fun scale(size: PhotoSize): Scale = when (size) {
        PhotoSize.Small -> Scale(rowMm = 36.0, singleMm = 70.0, share = 0.6)
        PhotoSize.Medium -> Scale(rowMm = 50.0, singleMm = 95.0, share = 0.8)
        PhotoSize.Large -> Scale(rowMm = 72.0, singleMm = 120.0, share = 1.0)
    }

    /** [a] made safe to divide by and kept within [MIN_ASPECT]..[MAX_ASPECT]. */
    fun clean(a: Double?): Double = a?.takeIf { it.isFinite() && it > 0 }?.coerceIn(MIN_ASPECT, MAX_ASPECT) ?: UNKNOWN_ASPECT

    /** The height photos with aspects summing to [sum] reach when [count] of them fill [width] side by side. */
    private fun fill(sum: Double, count: Int, width: Double, gap: Double, extra: Double): Double =
        max(width - gap * (count - 1) - extra * count, 1.0) / sum

    /**
     * Rows for photos of [aspects] (width / height) across [width], [gap] between photos and [extra] added to each
     * photo's width by its frame. The rows are chosen together to come closest to [target] in height (two even rows
     * beat a full row and an orphan). A last row that would grow taller than [target] stays at it, centred; no row is
     * taller than [maxHeight].
     */
    fun rows(aspects: List<Double>, width: Double, target: Double, gap: Double, maxHeight: Double = target * 1.35, extra: Double = 0.0): List<Row> {
        val a = aspects.map(::clean)
        val n = a.size
        if (n == 0) return emptyList()
        val best = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        val start = IntArray(n + 1)
        best[0] = 0.0
        for (end in 1..n) {
            var sum = 0.0
            for (from in end - 1 downTo max(0, end - MAX_PER_ROW)) {
                sum += a[from]
                val h = fill(sum, end - from, width, gap, extra)
                val c = best[from] + cost(h, target, last = end == n)
                if (c < best[end]) {
                    best[end] = c
                    start[end] = from
                }
            }
        }
        val out = ArrayList<Row>()
        var end = n
        while (end > 0) {
            val from = start[end]
            out += row(a, from, end, width, gap, extra, if (end == n) min(target, maxHeight) else maxHeight)
            end = from
        }
        return out.asReversed()
    }

    /**
     * All of [aspects] in one row across [width], no taller than [maxHeight] (then narrower, centred): the few photos
     * on a cover.
     */
    fun strip(aspects: List<Double>, width: Double, gap: Double, maxHeight: Double, extra: Double = 0.0): Row? =
        if (aspects.isEmpty()) null else row(aspects.map(::clean), 0, aspects.size, width, gap, extra, maxHeight)

    /**
     * The share of [width] a photo alone takes: [share] of it, or less when the photo would be taller than
     * [maxHeight] (a portrait), never more than all of it.
     */
    fun single(aspect: Double?, width: Double, maxHeight: Double, share: Double = 1.0): Double =
        minOf(share, maxHeight * clean(aspect) / width, 1.0).coerceAtLeast(0.05)

    private fun row(a: List<Double>, from: Int, to: Int, width: Double, gap: Double, extra: Double, cap: Double): Row {
        var sum = 0.0
        for (i in from until to) sum += a[i]
        val count = to - from
        val h = min(fill(sum, count, width, gap, extra), cap)
        return Row(from, to, h, min(width, h * sum + gap * (count - 1) + extra * count))
    }

    /**
     * How far a row of height [h] is from [target]: the log of their ratio, squared, so a strip at half the height
     * costs as much as a row at twice it (tiny photos look worse than big ones). A row drawn taller than the target
     * takes more of the page and costs half as much again; the last row is drawn at the target at most, so it is
     * judged as if stretched: a lone photo after a full row costs as much as the gap it leaves.
     */
    private fun cost(h: Double, target: Double, last: Boolean): Double {
        val off = ln(h / target)
        return off * off * (if (off > 0 && !last) 1.5 else 1.0)
    }
}
