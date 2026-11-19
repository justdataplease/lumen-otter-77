package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.clip.ClipMedia
import com.justdataplease.boringtravel.data.SavedClip
import com.justdataplease.boringtravel.data.SavedClipIndex
import com.justdataplease.boringtravel.ios.ClipEditStore
import com.justdataplease.boringtravel.ios.MediaFiles

/*
 * extras/ in a backup file (round 20): the trips' files that aren't rows, the Android app's backup/BackupExtras.kt
 * (its names and checks, copied: the file is in the Android app, not the engine) with what this phone writes and reads:
 *
 * - `extras/clip-edits/<trip>.json`: the trip video's scene edits (ios/ClipStudio.kt's ClipEditStore)
 * - `extras/clip-voice/<trip>/<file>`: its voice lines (clip/ClipVoice.kt)
 * - `extras/clip-media/<trip>/<file>`: the phone videos picked for it (clip/ClipMedia.kt)
 * - `extras/clips/<id>.mp4`: a saved trip video (data/SavedClips.kt); what it is called is in data.json's savedClips
 *
 * The path under extras/ is the path under the app's files. A restore writes a file only where the phone has none, and
 * skips kinds and names it doesn't know.
 */
object BackupExtras {
    const val CLIP_EDITS = "clip-edits"
    const val CLIP_VOICE = "clip-voice"
    const val CLIP_MEDIA = "clip-media"
    const val CLIPS = "clips"

    private val TRIP = Regex("[A-Za-z0-9_-]{1,80}")
    private val FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")

    /** The entry for [relative] (a path under the app's files), or null for a name this format can't carry. */
    fun entry(relative: String): String? = (BackupArchive.EXTRAS + relative).takeIf { target(it) == relative }

    /**
     * Where entry [name] goes under the app's files ("clip-edits/t1.json"), or null when it isn't a kind and name this
     * app restores. Never a path with "..", a backslash or more folders than the kind has.
     */
    fun target(name: String): String? {
        if (!name.startsWith(BackupArchive.EXTRAS) || !BackupArchive.safeName(name)) return null
        val relative = name.removePrefix(BackupArchive.EXTRAS)
        val parts = relative.split('/')
        val ok = when (parts.firstOrNull()) {
            CLIP_EDITS -> parts.size == 2 && parts[1].endsWith(".json") && TRIP.matches(parts[1].removeSuffix(".json"))
            CLIP_VOICE, CLIP_MEDIA -> parts.size == 3 && TRIP.matches(parts[1]) && FILE.matches(parts[2]) && ".." !in parts[2]
            CLIPS -> parts.size == 2 && parts[1].endsWith(".mp4") && TRIP.matches(parts[1].removeSuffix(".mp4"))
            else -> false
        }
        return relative.takeIf { ok }
    }

    /**
     * The trip video's files of [tripIds] and the saved [clips] on this phone, as a backup file's extras: each trip's
     * scene edits with the voice lines and phone videos they use (Android's BackupManager.clipFiles), and each clip's MP4.
     */
    fun files(files: MediaFiles, tripIds: Collection<String>, clips: List<SavedClip>): List<ArchiveFile> {
        val out = ArrayList<ArchiveFile>()
        for (trip in tripIds) {
            val editsPath = ClipEditStore.path(trip)
            val editsEntry = entry(editsPath) ?: continue
            if ((files.size(editsPath) ?: 0L) <= 0L) continue
            out += ArchiveFile(editsEntry, editsPath)
            val edits = ClipEditStore.load(files, trip).values
            val used = edits.flatMap { e -> e.videos.map { ClipMedia.videoDir(trip) + "/" + it.file } } +
                edits.flatMap { e -> e.voices.map { ClipMedia.voiceDir(trip) + "/" + it.file } }
            for (path in used.distinct()) {
                if ((files.size(path) ?: 0L) > 0L) entry(path)?.let { out += ArchiveFile(it, path) }
            }
        }
        for (c in clips) {
            val video = SavedClipIndex.video(c.id)
            entry(video)?.let { out += ArchiveFile(it, video) }
        }
        return out
    }

    /** Each trip's scene edits as text, by trip id (data.json's clipEdits, as Android writes them). */
    fun clipEdits(files: MediaFiles, tripIds: Collection<String>): Map<String, String> = tripIds.mapNotNull { trip ->
        files.read(ClipEditStore.path(trip))?.decodeToString()?.takeIf { it.isNotBlank() }?.let { trip to it }
    }.toMap()
}
