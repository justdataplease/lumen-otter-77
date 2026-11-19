package com.justdataplease.boringtravel.ui.clip

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import com.justdataplease.boringtravel.clip.ClipRenderer
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.min

/**
 * The preview prepared ahead (owner: "do some preprocessing before showing … now seems sluggish"): every [step]th
 * frame of the clip drawn at [width] × [height] by a few background threads (one [ClipRenderer] each, from
 * [newRenderer]) and kept as JPEGs within [budget] bytes, so playing only decodes a small picture at a steady rate.
 * Frames past the budget (a long clip on a small phone) are left out and drawn live when played.
 *
 * After a scene edit, a cache can start from the one before it ([seed], same clip timing and size): its frames are
 * kept except those in [dirty] (the edited scenes' frames, see [ClipScenes.dirtyFrames]), so only those are drawn again.
 */
internal class PreviewCache(
    private val newRenderer: () -> ClipRenderer,
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
    private val frames = AtomicReferenceArray<ByteArray?>(count)
    private val used = AtomicLong()
    private val done = AtomicInteger()
    private val next = AtomicInteger()
    private val running = AtomicInteger()
    @Volatile private var from = 0
    @Volatile private var closed = false
    @Volatile private var startedAt = 0L
    private var pool: ExecutorService? = null
    /** Prepared frames still to draw, in the order to draw them (set by [start]). */
    private var todo = IntArray(0)

    /** Frames taken over from the seed. */
    val reused: Int

    init {
        var kept = 0
        if (seed != null && seed.frameCount == frameCount && seed.step == step && seed.width == width && seed.height == height) {
            for (i in 0 until count) {
                val f = clipFrame(i)
                if (dirty.any { f in it }) continue
                val jpeg = seed.get(i) ?: continue
                frames.set(i, jpeg)
                used.addAndGet(jpeg.size.toLong())
                kept++
            }
        }
        reused = kept
        done.set(kept)
    }

    /** No more frames will be prepared (all done, the budget is used up, or it was closed). */
    @Volatile var finished = false
        private set

    val ready: Int get() = done.get()
    val progress: Float get() = ready.toFloat() / count
    val bytes: Long get() = used.get()

    /** Frames still to prepare (0 once [finished]). */
    val remaining: Int get() = if (finished) 0 else count - ready

    /** Frames still to prepare from prepared frame [i] to the end of the clip (0 once [finished]). */
    fun remainingFrom(i: Int): Int {
        if (finished) return 0
        var n = 0
        for (j in i.coerceAtLeast(0) until count) if (frames.get(j) == null) n++
        return n
    }

    /** Frames prepared a second so far. */
    fun renderFps(): Double {
        val s = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        return if (s <= 0.05) 0.0 else (ready - reused) / s
    }

    /** The clip frame shown by prepared frame [i]. */
    fun clipFrame(i: Int): Int = min(i * step, frameCount - 1)

    fun get(i: Int): ByteArray? = if (i in 0 until count) frames.get(i) else null

    /** Frames ready in a row from [i] (looping), at most [limit]. */
    fun readyFrom(i: Int, limit: Int = count): Int {
        var n = 0
        while (n < limit && n < count && frames.get((i + n) % count) != null) n++
        return n
    }

    /** Starts preparing from prepared frame [index] on (looping to the start). */
    fun start(index: Int = 0) {
        if (pool != null) return
        from = index.coerceIn(0, count - 1)
        todo = IntArray(count) { (from + it) % count }.filter { frames.get(it) == null }.toIntArray()
        startedAt = SystemClock.elapsedRealtime()
        if (todo.isEmpty()) {
            finished = true
            EditorPerf.log("all frames kept")
            return
        }
        val threads = Executors.newFixedThreadPool(workers) { r -> Thread(r, "clip-preview").apply { priority = Thread.NORM_PRIORITY - 1 } }
        pool = threads
        running.set(workers)
        repeat(workers) { threads.execute(::work) }
        threads.shutdown()
    }

    fun close() {
        closed = true
        finished = true
        pool?.shutdownNow()
    }

    private fun work() {
        var bitmap: Bitmap? = null
        try {
            val renderer = newRenderer()
            val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = b
            val canvas = Canvas(b)
            val out = ByteArrayOutputStream((PreviewMath.frameBytes(width, height) * 2).toInt())
            while (!closed) {
                val n = next.getAndIncrement()
                if (n >= todo.size) break
                val i = todo[n]
                renderer.draw(clipFrame(i), canvas, b)
                out.reset()
                b.compress(Bitmap.CompressFormat.JPEG, PreviewMath.JPEG_QUALITY, out)
                val jpeg = out.toByteArray()
                if (used.addAndGet(jpeg.size.toLong()) > budget) {
                    used.addAndGet(-jpeg.size.toLong())
                    next.set(count)
                    break
                }
                frames.set(i, jpeg)
                done.incrementAndGet()
                // About a second of play ready from where preparing began (EditorPerf).
                if (!firstSecond && readyFrom(from, FIRST_SECOND) >= min(FIRST_SECOND, count)) {
                    firstSecond = true
                    EditorPerf.log("first second prepared")
                }
            }
        } catch (t: Throwable) {
            // Out of memory or a drawing failure: stop preparing; playing draws the missing frames live.
            if (!closed) Log.w("TripClip", "Preview preparation stopped", t)
            next.set(count)
        } finally {
            bitmap?.recycle()
            if (running.decrementAndGet() == 0) {
                finished = true
                if (!closed) EditorPerf.log("all ${ready - reused} frames prepared")
            }
        }
    }

    @Volatile private var firstSecond = false

    private companion object {
        /** Prepared frames in about a second of play (15 fps). */
        const val FIRST_SECOND = 15
    }
}
