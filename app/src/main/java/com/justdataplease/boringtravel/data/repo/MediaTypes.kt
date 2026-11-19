package com.justdataplease.boringtravel.data.repo

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** File types of journal media: the extension a type gets on disk. Pure (unit tested). */
object MediaTypes {
    const val JPEG = "image/jpeg"
    const val MP4 = "video/mp4"

    /** True for a video type ("video/mp4", "video/3gpp", …). */
    fun isVideo(mime: String?): Boolean = mime?.startsWith("video/") == true

    /**
     * The extension for a file of type [mime]: the photo types the app keeps as they are, the usual one for a video
     * (mp4 when the type says only "video"), else jpg (a photo of a type nobody knows was always saved as .jpg).
     */
    fun extension(mime: String?): String = when (mime?.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        "video/mp4" -> "mp4"
        "video/3gpp" -> "3gp"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        else -> if (isVideo(mime)) "mp4" else "jpg"
    }

    private val videos = setOf("video/mp4", "video/3gpp", "video/webm", "video/quicktime", "video/x-matroska")

    /**
     * The type of another member's photo or video as this phone keeps it: one of the video types it knows (an unknown
     * video type reads as mp4), else a JPEG (photos travel as small JPEG copies; older apps send no type at all).
     */
    fun shared(mime: String?): String {
        val m = mime?.trim()?.lowercase()
        return when {
            m in videos -> m!!
            isVideo(m) -> MP4
            else -> JPEG
        }
    }
}

/**
 * How long and how big a journal video may be (see [JournalVideos]). The whole video is kept (owner, 2026-11-12: "no,
 * upload all video but have a message"), made smaller so it goes up to Drive as quickly as it can: its short side at
 * most 720 px, about 3 Mbit/s up to 10 minutes (a minute is about 22 MB), less for longer ones so the file stays at
 * about [MAX_BYTES], never under [MIN_VIDEO_BITRATE]. That floor makes [MAX_MS] (30 minutes) the longest. Pure (unit
 * tested).
 */
object VideoLimits {
    /** The longest video: at [MIN_VIDEO_BITRATE] a longer one would pass [MAX_BYTES]. */
    const val MAX_MS = 30 * 60_000L

    /** Containers round lengths up a little: a video this much over a limit still counts as within it. */
    const val SLACK_MS = 500L

    /** The short side of a compressed video, at most (720p); smaller videos keep their size. */
    const val SHORT_SIDE = 720

    /** H.264 at about this many bits a second (720p that still looks good on a phone), for videos up to [FULL_RATE_MS]. */
    const val VIDEO_BITRATE = 3_000_000

    /** Videos up to this long get [VIDEO_BITRATE]; longer ones less, to stay at about [MAX_BYTES]. */
    const val FULL_RATE_MS = 10 * 60_000L

    /** The least a long video gets. */
    const val MIN_VIDEO_BITRATE = 1_000_000

    /** AAC sound. */
    const val AUDIO_BITRATE = 128_000

    /**
     * About the biggest a video file gets: what long videos aim at, and the most a video the phone can't compress may
     * be to go in as it is. Under [HARD_MAX_BYTES], so a backup file holding it still restores.
     */
    const val MAX_BYTES = 240L * 1024 * 1024

    /**
     * No video file may be bigger: a backup file's limit for one photo or video (BackupArchive.MAX_PHOTO_BYTES) and what
     * a shared trip's member downloads at most.
     */
    const val HARD_MAX_BYTES = 256L * 1024 * 1024

    /** Room left for the container and an encoder that runs a little over its rate. */
    private const val MARGIN = 0.95

    /** True when a video of [durationMs] is longer than [MAX_MS]. Unknown lengths are never said to be. */
    fun tooLong(durationMs: Long?): Boolean = durationMs != null && durationMs > MAX_MS + SLACK_MS

    /**
     * The video bitrate for a video of [durationMs]: [VIDEO_BITRATE] up to [FULL_RATE_MS] (or when the length is
     * unknown), else what keeps picture and sound at about [MAX_BYTES], never under [MIN_VIDEO_BITRATE].
     */
    fun bitrate(durationMs: Long?): Int {
        if (durationMs == null || durationMs <= FULL_RATE_MS) return VIDEO_BITRATE
        val seconds = durationMs / 1000.0
        val fits = MAX_BYTES * 8 * MARGIN / seconds - AUDIO_BITRATE
        return fits.toInt().coerceIn(MIN_VIDEO_BITRATE, VIDEO_BITRATE)
    }

    /** About how big a compressed video of [durationMs] is, picture and sound. */
    fun estimatedBytes(durationMs: Long): Long = ((bitrate(durationMs).toLong() + AUDIO_BITRATE) * durationMs / 8000)

    /** Whether a video the phone couldn't compress may go in as it is: no bigger than [MAX_BYTES]. */
    fun canCopy(bytes: Long?): Boolean = bytes != null && bytes in 1..MAX_BYTES

    /** Whole minutes of [durationMs], at least 1, for "This video is 4 min long." */
    fun minutes(durationMs: Long): Int = ((durationMs + 30_000) / 60_000).toInt().coerceAtLeast(1)

    /**
     * The short side to scale a [width] × [height] video to: [SHORT_SIDE] when it is bigger (or its size is unknown),
     * null when it is already that small (never made bigger).
     */
    fun shortSide(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0) return SHORT_SIDE
        return SHORT_SIDE.takeIf { minOf(width, height) > it }
    }

    /** "Preparing the video… 40%": progress 0..1 as a whole percent, never past 99 until it is done. */
    fun percent(progress: Float): Int = if (!progress.isFinite()) 0 else (progress * 100f).toInt().coerceIn(0, 99)
}

/** What a video file says about itself, as Android's media reader gives it. Pure (unit tested). */
object VideoTags {
    private val formats = listOf("uuuuMMdd'T'HHmmss.SSS'Z'", "uuuuMMdd'T'HHmmss'Z'", "uuuuMMdd'T'HHmmss")
        .map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }

    /** Before this a date is the container's "unset" (1904 or 1970), not when the video was made. */
    private const val EARLIEST_MS = 365L * 24 * 3600 * 1000

    /**
     * The recording time from the date tag ("20261112T101530.000Z", in UTC), as epoch ms; null when it is missing,
     * unreadable or an unset date.
     */
    fun date(value: String?): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (f in formats) {
            val t = runCatching { LocalDateTime.parse(v, f).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() ?: continue
            return t.takeIf { it >= EARLIEST_MS }
        }
        return null
    }

    private val iso6709 = Regex("""^([+-]\d{1,2}(?:\.\d+)?)([+-]\d{1,3}(?:\.\d+)?)""")

    /**
     * The place from the location tag (ISO 6709, "+37.7749-122.4194/" or with a height after it); null when it is
     * missing, unreadable, off the Earth, or exactly 0,0 (what some phones write for "no place").
     */
    fun location(value: String?): Pair<Double, Double>? {
        val m = iso6709.find(value?.trim() ?: return null) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        if (lat == 0.0 && lng == 0.0) return null
        return lat to lng
    }
}
