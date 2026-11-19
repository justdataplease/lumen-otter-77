package com.justdataplease.boringtravel.backup

/**
 * extras/ in a backup file: the trips' files that aren't rows and that a reinstall would lose. Each kind has its own
 * folder, and the path under extras/ is the path under the app's files folder on the phone:
 *
 * - `extras/clip-edits/<trip>.json`: the trip video's scene edits (clip/ClipEditStore)
 * - `extras/clip-voice/<trip>/<file>`: its voice lines (clip/ClipVoice)
 * - `extras/clip-media/<trip>/<file>`: the phone videos picked for it (clip/ClipMedia)
 *
 * `<trip>` is the trip id as those classes write it (letters, digits, "_" and "-"). A restore writes a file only where
 * the phone has none, and skips kinds and names it doesn't know. New kinds get a folder of their own here. Plain Kotlin.
 */
object BackupExtras {
    const val CLIP_EDITS = "clip-edits"
    const val CLIP_VOICE = "clip-voice"
    const val CLIP_MEDIA = "clip-media"

    /** `extras/clips/<id>.mp4`: a saved trip video (data/SavedClips); what it is called is in data.json's savedClips. */
    const val CLIPS = "clips"

    /** A scene-edits file (they are a few KB). */
    const val MAX_EDITS_BYTES = 1L * 1024 * 1024

    /** A voice line: at most 20 s of AAC. */
    const val MAX_VOICE_BYTES = 16L * 1024 * 1024

    /** A clip video, as clip/ClipMedia.MAX_VIDEO_BYTES; also the bound for kinds a later app adds. */
    const val MAX_VIDEO_BYTES = 1_000_000_000L

    private val TRIP = Regex("[A-Za-z0-9_-]{1,80}")
    private val FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")

    /** The entry for a trip's scene-edits file named [fileName] ("t1.json"), or null for a name this format can't carry. */
    fun clipEdits(fileName: String): String? = entry("$CLIP_EDITS/$fileName")

    /** The entry for a clip file: [kind] is [CLIP_VOICE] or [CLIP_MEDIA], [tripDir] the trip's folder name. */
    fun clipFile(kind: String, tripDir: String, fileName: String): String? = entry("$kind/$tripDir/$fileName")

    /** The entry for saved video [id] ("extras/clips/<id>.mp4"), or null for an id this format can't carry. */
    fun clipVideo(id: String): String? = entry("$CLIPS/$id.mp4")

    private fun entry(relative: String): String? = (BackupArchive.EXTRAS + relative).takeIf { target(it) == relative }

    /**
     * Where entry [name] goes under the app's files folder ("clip-edits/t1.json"), or null when it isn't a kind and
     * name this app restores. Never a path with "..", a backslash or more folders than the kind has.
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

    /** The kind of entry [name] ("clip-edits"), or null outside extras/. */
    fun kind(name: String): String? = name.takeIf { it.startsWith(BackupArchive.EXTRAS) }?.removePrefix(BackupArchive.EXTRAS)?.substringBefore('/')

    /** Small JSON read with the rows in a restore's first pass (the scene edits). */
    fun isText(name: String): Boolean = kind(name) == CLIP_EDITS

    /** The most bytes entry [name] may unpack to. */
    fun limit(name: String): Long = when (kind(name)) {
        CLIP_EDITS -> MAX_EDITS_BYTES
        CLIP_VOICE -> MAX_VOICE_BYTES
        else -> MAX_VIDEO_BYTES
    }
}
