package com.justdataplease.boringtravel.globe

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keeps flag pins readable: overlapping pins are pushed apart, and names show under their pins only where they fit
 * (flags first). Works on parallel float arrays, in place, so a frame allocates nothing. Deterministic: the same
 * anchors always give the same layout, so pins don't jitter while the globe turns.
 */
object PinLayout {
    /**
     * Moves the first [n] points of [x], [y] apart until no two are closer than [minDist] (or [iterations] passes
     * are done). Pins that sit on the very same spot fan out in a fixed direction per pair.
     */
    fun separate(x: FloatArray, y: FloatArray, n: Int, minDist: Float, iterations: Int = 8) {
        if (n < 2) return
        val min2 = minDist * minDist
        repeat(iterations) {
            var moved = false
            for (i in 0 until n - 1) {
                for (j in i + 1 until n) {
                    var dx = x[j] - x[i]
                    var dy = y[j] - y[i]
                    val d2 = dx * dx + dy * dy
                    if (d2 >= min2) continue
                    var d = sqrt(d2)
                    if (d < 1e-3f) {
                        val a = (i * 7 + j * 13) * 2.3999632f
                        dx = cos(a); dy = sin(a); d = 1f
                    } else {
                        dx /= d; dy /= d
                    }
                    val push = (minDist - (if (d2 < 1e-6f) 0f else d)) / 2f + 0.01f
                    x[i] -= dx * push; y[i] -= dy * push
                    x[j] += dx * push; y[j] += dy * push
                    moved = true
                }
            }
            if (!moved) return
        }
    }

    /**
     * Decides which names show. Entry k's label is a [w] × [h] box centred under pin k ([pinR] radius, [gap] below);
     * it shows when it overlaps no pin and no label already shown, taken in order (most important first), and stays
     * inside the view ([viewW] × [viewH]).
     */
    fun labels(
        x: FloatArray,
        y: FloatArray,
        n: Int,
        pinR: Float,
        w: FloatArray,
        h: Float,
        gap: Float,
        viewW: Float,
        viewH: Float,
        out: BooleanArray,
    ) {
        for (k in 0 until n) {
            out[k] = false
            val l = x[k] - w[k] / 2
            val t = y[k] + pinR + gap
            val r = l + w[k]
            val b = t + h
            if (l < 0 || t < 0 || r > viewW || b > viewH || w[k] <= 0f) continue
            var free = true
            for (p in 0 until n) {
                if (overlaps(l, t, r, b, x[p] - pinR, y[p] - pinR, x[p] + pinR, y[p] + pinR)) { free = false; break }
            }
            if (free) {
                for (q in 0 until k) {
                    if (!out[q]) continue
                    val ql = x[q] - w[q] / 2
                    val qt = y[q] + pinR + gap
                    if (overlaps(l, t, r, b, ql, qt, ql + w[q], qt + h)) { free = false; break }
                }
            }
            out[k] = free
        }
    }

    private fun overlaps(l1: Float, t1: Float, r1: Float, b1: Float, l2: Float, t2: Float, r2: Float, b2: Float) =
        l1 < r2 && l2 < r1 && t1 < b2 && t2 < b1
}
