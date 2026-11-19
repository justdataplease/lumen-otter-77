@file:OptIn(ExperimentalAtomicApi::class)

package com.justdataplease.boringtravel.ui.clip

import com.justdataplease.boringtravel.ios.ClipFrames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.min
import kotlin.time.TimeSource

/**
 * The preview prepared ahead (the Android app's ui/clip/PreviewCache.kt, written again: Android draws on a thread pool
 * with java.util.concurrent, here on coroutines with Kotlin's atomics): every [step]th frame drawn at [width] × [height]
 * by a few background workers (one [ClipFrames] each, from [newFrames]) and kept as JPEGs within [budget] bytes, so
 * playing only decodes a small picture at a steady rate. Frames past the budget are left out and drawn live. After a
 * scene edit a cache can start from the one before it ([seed]): its frames are kept except those in [dirty], so only
 * those are drawn again.
 */
internal class PreviewCache(
    private val newFrames: () -> ClipFrames,
    val frameCount: Int,
    val step: Int,
    val width: Int,
    val height: Int,
    private val budget: Long,
    private val workers: Int,
    seed: PreviewCache? = null,
    dirty: List<IntRange> = emptyList(),
) {
    val count: Int = PreviewMath.count(frameCount, step)
    private val frames = arrayOfNulls<ByteArray>(count)
    private val used = AtomicLong(0L)
    private val done = AtomicInt(0)
    private val next = AtomicInt(0)
    private val running = AtomicInt(0)
    private var from = 0
    private var closed = false
    private var startedAt = TimeSource.Monotonic.markNow()
    private var todo = IntArray(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Frames taken over from the seed. */
    val reused: Int

    init {
        var kept = 0
        if (seed != null && seed.frameCount == frameCount && seed.step == step && seed.width == width && seed.height == height) {
            for (i in 0 until count) {
                val f = clipFrame(i)
                if (dirty.any { f in it }) continue
                val jpeg = seed.get(i) ?: continue
                frames[i] = jpeg
                used.addAndFetch(jpeg.size.toLong())
                kept++
            }
        }
        reused = kept
        done.store(kept)
    }

    /** No more frames will be prepared (all done, the budget is used up, or it was closed). */
    var finished = false
        private set

    val ready: Int get() = done.load()
    val progress: Float get() = ready.toFloat() / count
    val bytes: Long get() = used.load()

    /** Frames still to prepare from prepared frame [i] to the end of the clip (0 once [finished]). */
    fun remainingFrom(i: Int): Int {
        if (finished) return 0
        var n = 0
        for (j in i.coerceAtLeast(0) until count) if (frames[j] == null) n++
        return n
    }

    /** Frames prepared a second so far. */
    fun renderFps(): Double {
        val s = startedAt.elapsedNow().inWholeMilliseconds / 1000.0
        return if (s <= 0.05) 0.0 else (ready - reused) / s
    }

    /** The clip frame shown by prepared frame [i]. */
    fun clipFrame(i: Int): Int = min(i * step, frameCount - 1)

    fun get(i: Int): ByteArray? = if (i in 0 until count) frames[i] else null

    /** Frames ready in a row from [i] (looping), at most [limit]. */
    fun readyFrom(i: Int, limit: Int = count): Int {
        var n = 0
        while (n < limit && n < count && frames[(i + n) % count] != null) n++
        return n
    }

    /** Starts preparing from prepared frame [index] on (looping to the start). */
    fun start(index: Int = 0) {
        if (todo.isNotEmpty() || finished) return
        from = index.coerceIn(0, count - 1)
        todo = IntArray(count) { (from + it) % count }.filter { frames[it] == null }.toIntArray()
        startedAt = TimeSource.Monotonic.markNow()
        if (todo.isEmpty()) {
            finished = true
            return
        }
        running.store(workers)
        repeat(workers) { scope.launch { work() } }
    }

    fun close() {
        closed = true
        finished = true
        scope.cancel()
    }

    private fun work() {
        var made: ClipFrames? = null
        try {
            val f = newFrames()
            made = f
            while (!closed && scope.isActive) {
                val n = next.fetchAndIncrement()
                if (n >= todo.size) break
                val i = todo[n]
                val jpeg = f.jpeg(clipFrame(i)) ?: continue
                if (used.addAndFetch(jpeg.size.toLong()) > budget) {
                    used.addAndFetch(-jpeg.size.toLong())
                    next.store(count + todo.size)
                    break
                }
                frames[i] = jpeg
                done.incrementAndFetch()
            }
        } catch (_: Throwable) {
            // A drawing failure (or no memory): stop preparing; playing draws the missing frames live.
            next.store(count + todo.size)
        } finally {
            made?.close()
            if (running.decrementAndFetch() == 0) finished = true
        }
    }
}
