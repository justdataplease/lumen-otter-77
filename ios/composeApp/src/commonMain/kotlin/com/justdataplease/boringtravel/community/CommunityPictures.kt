package com.justdataplease.boringtravel.community

import androidx.compose.ui.graphics.ImageBitmap
import com.justdataplease.boringtravel.google.PublicDrive
import com.justdataplease.boringtravel.ios.MediaFiles
import com.justdataplease.boringtravel.ios.MediaWork
import com.justdataplease.boringtravel.ios.Pictures
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Other people's pictures and clips (round 24), as the Android app's Community.images and clipFile keep them: read from
 * each poster's Drive by link ([drive], with the app's API key or Google's public address, never the viewer's own Drive
 * token), a few at a time, kept in memory and in a 150 MB folder in the caches ([files], "community_images": the least
 * recently used go first), decoded only as big as where they are drawn; a clip downloaded once into
 * "community_clips/<id>.mp4" (at most [CommunityLimits.CLIP_BYTES]), the newest six or 300 MB kept.
 */
class CommunityPictures(
    private val drive: PublicDrive,
    private val files: MediaFiles,
    private val now: () -> Long = ::nowMs,
) {
    // Replaced whole after each picture, read without a lock from the screens.
    private var memory: Map<String, ImageBitmap> = emptyMap()
    private val gate = Semaphore(4)
    private val disk = Mutex()
    private val clips = Mutex()
    private var index: MutableMap<String, Pair<Long, Long>>? = null

    /** Where picture [fileId] is read from and the headers to send (Android's Community.pictureAddress). */
    fun address(fileId: String): Pair<String, Map<String, String>> = drive.address(fileId)

    /** Picture [fileId] made already for [maxPx], from memory only. */
    fun cached(fileId: String, maxPx: Int): ImageBitmap? = memory[memoryKey(fileId, Pictures.bucket(maxPx))]

    /**
     * Picture [fileId], at most about [maxPx] on its longest side; null when it can't be had (not a Drive id, not shared,
     * offline and not kept, not a picture).
     */
    suspend fun load(fileId: String, maxPx: Int): ImageBitmap? {
        if (!PublicDrive.isId(fileId)) return null
        val size = Pictures.bucket(maxPx)
        val key = memoryKey(fileId, size)
        memory[key]?.let { return it }
        val bytes = disk.withLock { withContext(MediaWork) { fromDisk(fileId) } } ?: fetch(fileId)?.also { b -> disk.withLock { withContext(MediaWork) { toDisk(fileId, b) } } } ?: return null
        val picture = withContext(MediaWork) { runCatching { Pictures.decode(bytes, size) }.getOrNull() } ?: return null
        val next = LinkedHashMap(memory)
        next.remove(key)
        next[key] = picture
        while (next.size > KEEP) next.remove(next.keys.first())
        memory = next
        return picture
    }

    private suspend fun fetch(fileId: String): ByteArray? = gate.withPermit {
        try {
            drive.bytes(fileId, MAX_PICTURE)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Clip [fileId] in the caches (downloaded once, at most [CommunityLimits.CLIP_BYTES]): its path under [files]. Throws
     * when it can't be had (offline, not shared, too big).
     */
    suspend fun clip(fileId: String): String = clips.withLock {
        PublicDrive.requireId(fileId)
        val path = "$CLIPS/$fileId.mp4"
        if ((files.size(path) ?: 0L) > 0L) return@withLock path
        withContext(Dispatchers.Default) { prune() }
        drive.download(fileId, files, path, CommunityLimits.CLIP_BYTES)
        path
    }

    /** The full path of [path] under [files] (what the phone's player opens). */
    fun absolute(path: String): String = files.absolute(path)

    /** Keeps the clip cache small: the newest few (Android's Community.prune). */
    private fun prune() {
        val all = files.list(CLIPS).filter { it.endsWith(".mp4") }.map { "$CLIPS/$it" }
            .sortedByDescending { files.modifiedMs(it) ?: 0L }
        var total = 0L
        all.forEachIndexed { i, f ->
            total += files.size(f) ?: 0L
            if (i >= 6 || total > 300L * 1024 * 1024) files.delete(f)
        }
        files.list(CLIPS).filter { it.endsWith(".part") }.forEach { files.delete("$CLIPS/$it") }
    }

    private fun memoryKey(fileId: String, size: Int) = "$fileId@$size"

    private fun index(): MutableMap<String, Pair<Long, Long>> = index ?: LinkedHashMap<String, Pair<Long, Long>>().also { map ->
        files.read(INDEX)?.decodeToString()?.lineSequence()?.forEach { line ->
            val parts = line.split(' ')
            if (parts.size == 3) map[parts[0]] = (parts[1].toLongOrNull() ?: 0L) to (parts[2].toLongOrNull() ?: 0L)
        }
        index = map
    }

    private fun fromDisk(fileId: String): ByteArray? {
        val entry = index()[fileId] ?: return null
        val bytes = files.read("$IMAGES/$fileId") ?: run {
            index().remove(fileId)
            return null
        }
        index()[fileId] = entry.first to now()
        return bytes
    }

    private fun toDisk(fileId: String, bytes: ByteArray) {
        if (!files.write("$IMAGES/$fileId", bytes)) return
        val all = index()
        all[fileId] = bytes.size.toLong() to now()
        // Over the limit: the pictures used longest ago go first.
        var total = all.values.sumOf { it.first }
        if (total > DISK_BYTES) {
            for ((k, v) in all.entries.sortedBy { it.value.second }.toList()) {
                if (total <= DISK_BYTES) break
                files.delete("$IMAGES/$k")
                all.remove(k)
                total -= v.first
            }
        }
        files.write(INDEX, all.entries.joinToString("\n") { "${it.key} ${it.value.first} ${it.value.second}" }.encodeToByteArray())
    }

    companion object {
        private const val KEEP = 64
        private const val IMAGES = "community_images"
        private const val INDEX = "community_images/index.txt"
        private const val CLIPS = "community_clips"

        /** Android's community image cache. */
        const val DISK_BYTES = 150L * 1024 * 1024

        /** A post's photo is 1600 px at quality 85: far under this. */
        private const val MAX_PICTURE = 15L * 1024 * 1024
    }
}
