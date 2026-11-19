package com.justdataplease.boringtravel.ios

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The pictures of link previews, favicons and Wikipedia photos, as the Android app loads them with Coil
 * (links/LinkPreviews.kt imageLoader): with the app's own User-Agent, a few at a time, kept in memory and in a 40 MB
 * folder in the caches (the oldest used go first), decoded only as big as where they are drawn, fading in.
 */

/** Pictures from the web: memory, then the disk cache, then the network. */
class WebImages(private val net: Net, private val files: MediaFiles, private val userAgent: String, private val scope: CoroutineScope) {
    // Replaced whole on [work], read without a lock from the screens.
    private var memory: Map<String, ImageBitmap> = emptyMap()
    private val gate = Semaphore(4)
    private val work = Dispatchers.Default.limitedParallelism(1)

    /** The disk cache's index: file key → (bytes, last used), read once, written after each change. */
    private var index: MutableMap<String, Pair<Long, Long>>? = null

    /** The picture at [url] made already for [maxPx], from memory only. */
    fun cached(url: String, maxPx: Int): ImageBitmap? = memory[memoryKey(url, maxPx)]

    /** The picture at [url], at most about [maxPx] on its longest side; null when it can't be had (offline, not a picture). */
    suspend fun load(url: String, maxPx: Int): ImageBitmap? {
        val size = Pictures.bucket(maxPx)
        val mk = memoryKey(url, size)
        memory[mk]?.let { return it }
        val bytes = withContext(work) { fromDisk(url) } ?: fetch(url)?.also { withContext(work) { toDisk(url, it) } } ?: return null
        return withContext(work) {
            val picture = runCatching { Pictures.decode(bytes, size) }.getOrNull() ?: return@withContext null
            val next = LinkedHashMap(memory)
            next.remove(mk)
            next[mk] = picture
            while (next.size > KEEP) next.remove(next.keys.first())
            memory = next
            picture
        }
    }

    private suspend fun fetch(url: String): ByteArray? = gate.withPermit {
        val target = LinkUrls.forFetch(url) ?: return null
        val r = runCatching { net.ask(NetRequest(target, mapOf("User-Agent" to userAgent, "Accept" to "image/*"), maxBytes = MAX_BYTES, timeoutMs = 20_000)) }.getOrNull()
            ?: return null
        if (!r.ok) return null
        val type = r.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
        if (type.isNotEmpty() && !type.startsWith("image/") && type != "application/octet-stream") return null
        r.body.takeIf { it.isNotEmpty() }
    }

    private fun fileKey(url: String) = Sha256.hex(url.encodeToByteArray()).take(40)

    private fun memoryKey(url: String, size: Int) = "$url@$size"

    private fun index(): MutableMap<String, Pair<Long, Long>> = index ?: LinkedHashMap<String, Pair<Long, Long>>().also { map ->
        files.read(INDEX)?.decodeToString()?.lineSequence()?.forEach { line ->
            val parts = line.split(' ')
            if (parts.size == 3) map[parts[0]] = (parts[1].toLongOrNull() ?: 0L) to (parts[2].toLongOrNull() ?: 0L)
        }
        index = map
    }

    private fun fromDisk(url: String): ByteArray? {
        val key = fileKey(url)
        val entry = index()[key] ?: return null
        val bytes = files.read("$FOLDER/$key") ?: run {
            index().remove(key)
            return null
        }
        index()[key] = entry.first to com.justdataplease.boringtravel.time.nowMs()
        return bytes
    }

    private fun toDisk(url: String, bytes: ByteArray) {
        val key = fileKey(url)
        if (!files.write("$FOLDER/$key", bytes)) return
        val all = index()
        all[key] = bytes.size.toLong() to com.justdataplease.boringtravel.time.nowMs()
        // Over the limit: the pictures used longest ago go first.
        var total = all.values.sumOf { it.first }
        if (total > DISK_BYTES) {
            for ((k, v) in all.entries.sortedBy { it.value.second }) {
                if (total <= DISK_BYTES) break
                files.delete("$FOLDER/$k")
                all.remove(k)
                total -= v.first
            }
        }
        files.write(INDEX, all.entries.joinToString("\n") { "${it.key} ${it.value.first} ${it.value.second}" }.encodeToByteArray())
    }

    companion object {
        private const val KEEP = 64
        private const val FOLDER = "link_images"
        private const val INDEX = "link_images/index.txt"
        private const val DISK_BYTES = 40L * 1024 * 1024
        private const val MAX_BYTES = 8L * 1024 * 1024
    }
}

/**
 * A picture from the web filling [modifier], cropped by [contentScale]; it fades in once loaded (nothing moves
 * meanwhile) and [onState] says whether it came (true) or not (false), so a fallback under it can stay or go.
 */
@Composable
fun WebImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    onState: (Boolean) -> Unit = {},
) {
    val images = LocalWeb.current.images
    BoxWithConstraints(modifier.then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)) {
        if (url == null) return@BoxWithConstraints
        val px = with(LocalDensity.current) { max(maxWidth.toPx(), maxHeight.toPx()).let { if (it.isFinite()) it.roundToInt() else 640 } }
        var picture by remember(url, px) { mutableStateOf(images.cached(url, px)) }
        LaunchedEffect(url, px) {
            if (picture == null) {
                picture = images.load(url, px)
                onState(picture != null)
            } else onState(true)
        }
        val alpha by animateFloatAsState(if (picture != null) 1f else 0f, tween(220), label = "fade in")
        picture?.let { Image(it, null, Modifier.matchParentSize().alpha(alpha), contentScale = contentScale) }
    }
}
