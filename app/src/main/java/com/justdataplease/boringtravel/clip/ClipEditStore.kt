package com.justdataplease.boringtravel.clip

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The scene editor's edits on this phone: files/clip-edits/<trip id>.json, one part per route source (Trip plan,
 * Saved stops, Recorded journey; see [ClipEditsJson]). Written whole to a temporary file and renamed, newest wins.
 * A trip that changed since (new photos, places gone) just finds fewer scenes to apply: unknown keys are ignored.
 */
object ClipEditStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val versions = AtomicLong()
    @Volatile private var written = 0L

    fun file(context: Context, tripId: String): File =
        File(File(context.filesDir, "clip-edits"), tripId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80) + ".json")

    /** Every route source's edits for [tripId] (empty when there are none or the file can't be read). */
    suspend fun load(context: Context, tripId: String): Map<ClipSource, ClipEdits> = withContext(Dispatchers.IO) {
        val text = runCatching { file(context, tripId).takeIf { it.isFile }?.readText() }.getOrNull()
        ClipEditsJson.decode(text).mapNotNull { (id, edits) -> ClipSource.entries.firstOrNull { it.name == id }?.let { it to edits } }.toMap()
    }

    /** Keeps [all] for [tripId] in the background (a later call wins over an earlier one still waiting). */
    fun save(context: Context, tripId: String, all: Map<ClipSource, ClipEdits>) {
        val app = context.applicationContext
        val version = versions.incrementAndGet()
        scope.launch {
            lock.withLock {
                if (version < written) return@withLock
                runCatching {
                    val target = file(app, tripId)
                    val nonEmpty = all.filterValues { !it.isEmpty }
                    if (nonEmpty.isEmpty()) {
                        target.delete()
                    } else {
                        target.parentFile?.mkdirs()
                        val part = File(target.parentFile, target.name + ".part")
                        part.writeText(ClipEditsJson.encode(nonEmpty.mapKeys { it.key.name }))
                        if (!part.renameTo(target)) {
                            target.delete()
                            part.renameTo(target)
                        }
                    }
                    written = version
                }.onFailure { Log.w("TripClip", "Couldn't keep the scene edits", it) }
            }
        }
    }
}
