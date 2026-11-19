package com.justdataplease.boringtravel.export

import java.net.URI
import kotlin.math.max
import kotlin.math.roundToInt

/** Small pure helpers for export files: names, the WebView's URL allow-list, photo sizes. */
object ExportFiles {
    /** "Cyclades, slowly.pdf": letters (any script), digits, spaces, dots, dashes and commas; never empty. */
    fun fileName(title: String, ext: String): String {
        val clean = title.replace(Regex("[^\\p{L}\\p{N} .,_-]+"), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(60).trim()
        return "${clean.ifEmpty { "Trip" }}.$ext"
    }

    /**
     * What the offscreen WebView may load: the bundled fonts, files inside [allowedDir] (the prepared photos) and
     * data URIs. Everything else (other files, content://, the network) is blocked.
     */
    fun isAllowedUrl(url: String, allowedDir: String): Boolean {
        if (url.startsWith("data:")) return true
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "file") return false
        val path = uri.normalize().path ?: return false
        if (path.contains("..")) return false
        if (path.startsWith("/android_res/font/")) return true
        val dir = allowedDir.trimEnd('/') + "/"
        return path.startsWith(dir)
    }

    /** Fits w × h inside a [maxPx] square, never enlarging. */
    fun fit(w: Int, h: Int, maxPx: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return w to h
        val longest = max(w, h)
        if (longest <= maxPx) return w to h
        val s = maxPx.toDouble() / longest
        return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
    }

    /**
     * The longest side for photos sent to Google Docs as data URIs: the size the photo is shown at, and smaller for
     * long trips so the upload stays well under Drive's 50 MB conversion limit.
     */
    fun docsMaxPx(size: PhotoSize, photos: Int): Int {
        val bySize = TripHtml.docsPhotoMaxPx(size)
        val byCount = when {
            photos > 120 -> 640
            photos > 60 -> 900
            photos > 30 -> 1200
            else -> 1600
        }
        return minOf(bySize, byCount)
    }
}
