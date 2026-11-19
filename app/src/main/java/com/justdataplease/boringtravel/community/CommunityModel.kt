package com.justdataplease.boringtravel.community

import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The community (owner, 2026-11-11: "anyone can share a clip or a photo album, create a community, see other people's
 * trips and notes, add likes, a profile with a username and a leaderboard with the most liked trips").
 *
 * Firestore keeps the words and numbers (profiles, usernames, posts, likes, reports, blocks; see android-boringtravel/firebase);
 * the pictures and clips stay in the poster's own Google Drive, in a link-shared folder, and other phones read them
 * with PublicDrive. Everything in this file is pure (no Android), so it runs in JVM tests.
 */

/** What a post shares. [id] is what Firestore stores. */
enum class PostKind(val id: String, val label: String) {
    Trip("trip", "Trip"),
    Clip("clip", "Clip"),
    Album("album", "Album");

    companion object {
        fun of(id: String?): PostKind? = entries.firstOrNull { it.id == id }
    }
}

/** Sizes the app and the Firestore rules agree on (firebase/firestore.rules checks the same numbers). */
object CommunityLimits {
    const val TITLE = 80
    const val CAPTION = 500
    const val NAME = 40
    const val BIO = 150
    const val AVATAR = 2_000
    const val PHOTOS = 30
    const val COUNTRIES = 30
    const val MAX_DAYS = 400
    const val MAX_KM = 100_000

    /** The trip (plan and chosen notes) as JSON, in bytes. */
    const val TRIP_BYTES = 300_000

    /** One note's text in a shared trip, in characters (longer notes are cut with "…"). */
    const val NOTE_TEXT = 6_000

    /** Posts per page in the feed and the leaderboard. */
    const val PAGE = 20

    /** A post's pictures for the feed: long side and JPEG quality. */
    const val PHOTO_SIDE = 1600
    const val PHOTO_QUALITY = 85

    /** A clip's size in Drive that phones will still download to play. */
    const val CLIP_BYTES = 200L * 1024 * 1024
}

/** A community profile (`profiles/{uid}`). [avatar] is a Traveller.encode() string, or null for the initial. */
data class Profile(
    val uid: String,
    val username: String,
    val name: String = "",
    val bio: String = "",
    val avatar: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** A post (`posts/{id}`); the trip itself (plan and notes) lives beside it, read only on the post page. */
data class Post(
    val id: String,
    val uid: String,
    val username: String,
    val kind: PostKind,
    val title: String,
    val caption: String = "",
    /** ISO 3166-1 alpha-2 codes, in travel order. */
    val countries: List<String> = emptyList(),
    val days: Int = 0,
    val km: Int = 0,
    /** The trip's first month ("2027-03"), or null for a trip without dates. */
    val startMonth: String? = null,
    /** The month it was posted in, UTC ("2026-11"): the leaderboard's "This month". */
    val month: String = "",
    val coverFileId: String? = null,
    val photoFileIds: List<String> = emptyList(),
    val clipFileId: String? = null,
    /** The post's folder in the poster's Drive (deleted with the post). */
    val folderId: String? = null,
    /** True when the post carries a plan (and maybe notes) to show and copy. */
    val hasTrip: Boolean = false,
    val likes: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /** Every picture to swipe through on the post page: the cover first, then the rest without repeating it. */
    val pictures: List<String> get() = (listOfNotNull(coverFileId) + photoFileIds).distinct()

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")

        /** Ids the app makes (Firestore's own) and the rules accept; lists and links key posts by them. */
        fun isId(id: String?): Boolean = id != null && ID.matches(id)
    }
}

/** Why someone reports a post or a profile. [id] is what Firestore stores. */
enum class ReportReason(val id: String, val label: String) {
    Spam("spam", "Spam"),
    Inappropriate("inappropriate", "Inappropriate"),
    Hate("hate", "Hate or harassment"),
    Other("other", "Other"),
}

/** Someone the signed-in traveller blocked: their posts and profile are hidden everywhere for them. */
data class BlockedUser(val uid: String, val username: String, val at: Long = 0)

/** A page of a list and where the next one starts ([next] is the repository's own; null = the end). */
data class Page<T>(val items: List<T>, val next: Any? = null, val fromCache: Boolean = false)

/** The leaderboard's two ranges. */
enum class TopRange(val label: String) { Month("This month"), AllTime("All time") }

/** Texts of a post, as the feed and the post page show them. */
object PostText {
    private val MONTH_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT)

    /** "12 days · 1,240 km"; days or km left out when 0; "" when both are. */
    fun facts(days: Int, km: Int, locale: Locale = Locale.getDefault()): String {
        val parts = ArrayList<String>(2)
        if (days > 0) parts += if (days == 1) "1 day" else "$days days"
        if (km > 0) parts += "${NumberFormat.getIntegerInstance(locale).format(km)} km"
        return parts.joinToString(" · ")
    }

    fun facts(post: Post, locale: Locale = Locale.getDefault()): String = facts(post.days, post.km, locale)

    /** "1 like", "12 likes", "1.2k likes". */
    fun likes(n: Int): String = "${count(n)} ${if (n == 1) "like" else "likes"}"

    /** 999, "1.2k", "12k", "1.2m". */
    fun count(n: Int): String = when {
        n < 1_000 -> n.coerceAtLeast(0).toString()
        n < 10_000 -> trimZero("%.1f".format(Locale.ROOT, n / 1_000.0)) + "k"
        n < 1_000_000 -> "${n / 1_000}k"
        else -> trimZero("%.1f".format(Locale.ROOT, n / 1_000_000.0)) + "m"
    }

    private fun trimZero(s: String) = s.removeSuffix(".0")

    /** "2026-11" for an epoch-ms time, in UTC (what the rules check against the server's clock). */
    fun monthKey(epochMs: Long): String = MONTH_KEY.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))

    /** "Nov 2026" for "2026-11"; the key itself when it can't be read. */
    fun monthLabel(key: String?, locale: Locale = Locale.getDefault()): String? {
        if (key.isNullOrBlank()) return null
        return runCatching { YearMonth.parse(key, MONTH_KEY).format(DateTimeFormatter.ofPattern("MMM yyyy", locale)) }.getOrDefault(key)
    }

    /** "@alexa". */
    fun handle(username: String): String = "@$username"

    /** How long ago, short: "now", "5m", "3h", "2d", "4w", then the date ("12 Mar 2026"). */
    fun ago(thenMs: Long, nowMs: Long, locale: Locale = Locale.getDefault()): String {
        val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "now"
            s < 3_600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3_600}h"
            s < 7 * 86_400 -> "${s / 86_400}d"
            s < 35 * 86_400 -> "${s / (7 * 86_400)}w"
            else -> DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(Instant.ofEpochMilli(thenMs).atZone(ZoneOffset.UTC))
        }
    }
}
