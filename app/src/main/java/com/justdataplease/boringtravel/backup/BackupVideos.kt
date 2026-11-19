package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.data.SavedClip
import com.justdataplease.boringtravel.google.RemoteFile

/**
 * Saved videos in Drive (owner, 2026-11-11: "create a Videos folder"): BoringTravel › Videos holds each saved trip
 * video once, as "<title>.mp4", with what the Media tab lists in the file's private properties. The backup's rows name
 * the videos it was made with ([ClipRow]), so restoring a backup brings back exactly its videos, deleted since or not
 * (owner: "restore everything, so deleted too"). The app never deletes anything in the folder, and leaves alone the
 * files the traveller puts there. Pure, unit tested; [BackupManager] does the uploads and downloads.
 */
object BackupVideos {
    const val CLIP = "clipId"
    const val TRIP = "tripId"
    const val TITLE = "title"
    const val AT = "at"
    const val MS = "ms"
    const val W = "w"
    const val H = "h"

    /** A file kept by the path it has under the app's files folder ("clip-voice/<trip>/<file>"): the editor's files. */
    const val PATH = "path"

    /** Drive allows 124 bytes for a private property's key and value together. */
    const val PROPERTY_BYTES = 124

    /** A clip id doubles as its file name on the phone: letters, digits, "_" and "-" only. */
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,80}")

    private val UNSAFE_NAME = Regex("[\\\\/:*?\"<>|\\p{Cntrl}\\s]+")

    fun safeId(id: String): Boolean = SAFE_ID.matches(id)

    /** The Drive file's private properties for clip [c]. */
    fun properties(c: SavedClip): Map<String, String> = linkedMapOf(
        CLIP to c.id,
        TRIP to fit(TRIP, c.tripId),
        AT to c.createdAt.toString(),
        MS to c.durationMs.toString(),
        W to c.width.toString(),
        H to c.height.toString(),
        TITLE to fit(TITLE, c.title),
    )

    /** Whether [key] and [value] fit one Drive property. */
    fun fits(key: String, value: String): Boolean = key.toByteArray(Charsets.UTF_8).size + value.toByteArray(Charsets.UTF_8).size <= PROPERTY_BYTES

    /** [value] cut (never inside a character) so that [key] and it fit Drive's property limit. */
    fun fit(key: String, value: String): String {
        if (fits(key, value)) return value
        val room = PROPERTY_BYTES - key.toByteArray(Charsets.UTF_8).size
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val chars = String(Character.toChars(cp))
            val n = chars.toByteArray(Charsets.UTF_8).size
            if (used + n > room) break
            out.append(chars)
            used += n
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** "Argentina & Patagonia.mp4": the video's title, safe as a file name anywhere it is downloaded. */
    fun fileName(c: SavedClip): String = c.title.replace(UNSAFE_NAME, " ").trim().take(100).trim().ifEmpty { "Trip video" } + ".mp4"

    /** The clip a Drive copy stands for, or null for a file this app didn't put there (or a damaged one). */
    fun clipOf(f: RemoteFile): SavedClip? {
        val p = f.appProperties
        val id = p[CLIP]?.takeIf(::safeId) ?: return null
        val trip = p[TRIP]?.takeIf { it.isNotBlank() } ?: return null
        val at = p[AT]?.toLongOrNull() ?: return null
        return SavedClip(
            id = id, tripId = trip, title = p[TITLE] ?: f.name.removeSuffix(".mp4"), createdAt = at,
            durationMs = (p[MS]?.toLongOrNull() ?: 0).coerceAtLeast(0),
            width = (p[W]?.toIntOrNull() ?: 0).coerceAtLeast(0), height = (p[H]?.toIntOrNull() ?: 0).coerceAtLeast(0),
        )
    }

    /** The [local] clips (each with its video's size in [bytes]) that Drive has no whole copy of yet. */
    fun toUpload(local: List<SavedClip>, bytes: Map<String, Long>, remote: List<RemoteFile>): List<SavedClip> {
        val there = remote.filterNot { it.trashed }.mapNotNull { f -> clipOf(f)?.let { it.id to (f.size ?: 0L) } }.groupBy({ it.first }, { it.second })
        return local.filter { c ->
            val size = bytes[c.id] ?: 0L
            safeId(c.id) && size > 0 && there[c.id].orEmpty().none { it == size }
        }
    }

    /**
     * The Drive copies a restore fetches: the videos the backup was made with ([inBackup], its [ClipRow]s; null for a
     * backup older than the Videos folder: then every video made up to [backupAt]), that this phone doesn't [have],
     * of trips it has after the restore ([liveTrips]). One copy each (the biggest), oldest first.
     */
    fun toFetch(remote: List<RemoteFile>, have: Set<String>, liveTrips: Set<String>, inBackup: Set<String>?, backupAt: Long): List<Pair<SavedClip, RemoteFile>> =
        remote.filterNot { it.trashed || (it.size ?: 0L) <= 0L }
            .mapNotNull { f -> clipOf(f)?.let { it to f } }
            .filter { (c, _) -> c.id !in have && c.tripId in liveTrips && (inBackup?.contains(c.id) ?: (c.createdAt <= backupAt)) }
            .groupBy { it.first.id }
            .map { (_, list) -> list.maxBy { it.second.size ?: 0L } }
            .sortedBy { it.first.createdAt }

    /** The files (by [PATH]) that Drive has no whole copy of: [local] is path → size on the phone. */
    fun filesToUpload(local: Map<String, Long>, remote: List<RemoteFile>): List<String> {
        val there = remote.filterNot { it.trashed }.mapNotNull { f -> f.appProperties[PATH]?.let { it to (f.size ?: 0L) } }.groupBy({ it.first }, { it.second })
        return local.filter { (path, size) -> size > 0 && fits(PATH, path) && there[path].orEmpty().none { it == size } }.keys.toList()
    }

    /** The Drive copy of each of the [wanted] paths, the biggest when there are several. */
    fun filesToFetch(wanted: Set<String>, remote: List<RemoteFile>): Map<String, RemoteFile> =
        remote.filterNot { it.trashed || (it.size ?: 0L) <= 0L }
            .mapNotNull { f -> f.appProperties[PATH]?.takeIf { it in wanted }?.let { it to f } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, files) -> files.maxBy { it.size ?: 0L } }
}
