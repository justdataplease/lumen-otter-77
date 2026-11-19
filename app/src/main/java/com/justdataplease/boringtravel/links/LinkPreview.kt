package com.justdataplease.boringtravel.links

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What a plan item's link looks like: a picture ([imageUrl], from the page's og:image or, for a Google Maps place,
 * Wikipedia), the page's [title] and [siteName], and Google's [faviconUrl] for the site (only when Google knows its
 * icon). Kept per link in memory and on the phone, and looked at again after [retryAt]. A Wikipedia picture keeps its
 * credit with it ([imageAuthor], [imageLicense], [imageLicenseUrl], [imageFile]; see [WikiCredits]) and is shown
 * only with one ([uncredited]: from a version before credits, looked at again).
 *
 * A located item without a link has one too, kept under its [PlaceQuery.key] instead of a link: the Wikipedia photo
 * of its place, with the article's name as [title].
 */
@Serializable
data class LinkPreview(
    val url: String,
    val imageUrl: String? = null,
    /** Where [imageUrl] came from: [SOURCE_PAGE] or [SOURCE_WIKIPEDIA]. */
    val imageSource: String? = null,
    val title: String? = null,
    val siteName: String? = null,
    val faviconUrl: String? = null,
    /** The pin of a Google Maps link (short links only say it after their redirects). */
    val lat: Double? = null,
    val lng: Double? = null,
    val fetchedAt: Long = 0,
    /** Look again after this (epoch ms): minutes after no connection, weeks after a picture. */
    val retryAt: Long = 0,
    /** The last look failed (no connection, or the site refused); what was known before is kept. */
    val failed: Boolean = false,
    /** Looked at while Me › Place photos was off (Wikipedia not asked): looked at again once it is on. */
    val photosOff: Boolean = false,
    /** A Wikipedia picture's author (plain text; null for a public-domain file that names nobody). */
    val imageAuthor: String? = null,
    /** A Wikipedia picture's licence ("CC BY-SA 4.0"); every Wikipedia picture shown has one. */
    val imageLicense: String? = null,
    val imageLicenseUrl: String? = null,
    /** A Wikipedia picture's file on Commons ("Yasaka_jinja.jpg"), for its page. */
    val imageFile: String? = null,
) {
    val fromWikipedia: Boolean get() = imageSource == SOURCE_WIKIPEDIA

    /** A Wikipedia picture without its licence (kept by a version before credits): never shown, looked at again. */
    val uncredited: Boolean get() = fromWikipedia && imageUrl != null && imageLicense == null

    /** "Photo: Jane Doe, CC BY-SA 4.0 · Wikipedia" for a Wikipedia picture; null for anything else. */
    val credit: String? get() = imageLicense?.takeIf { fromWikipedia && imageUrl != null }?.let { WikiCredits.line(imageAuthor, it) }

    /** The Wikipedia picture's file page (author, licence, source), opened from its credit. */
    val creditUrl: String? get() = imageFile?.takeIf { fromWikipedia }?.let(WikiCredits::fileUrl)

    /** The same preview with [photo] as its Wikipedia picture (or none), credit and all. */
    fun withWikiPhoto(photo: WikiPhoto?): LinkPreview = copy(
        imageUrl = photo?.imageUrl, imageSource = photo?.let { SOURCE_WIKIPEDIA },
        imageAuthor = photo?.credit?.author, imageLicense = photo?.credit?.license,
        imageLicenseUrl = photo?.credit?.licenseUrl, imageFile = photo?.credit?.file,
    )

    /** Without its picture (and the picture's credit). */
    fun withoutPicture(): LinkPreview =
        copy(imageUrl = null, imageSource = null, imageAuthor = null, imageLicense = null, imageLicenseUrl = null, imageFile = null)

    companion object {
        const val SOURCE_PAGE = "page"
        const val SOURCE_WIKIPEDIA = "wikipedia"
    }
}

/** How a look at a link went. */
enum class LinkOutcome { Picture, NoPicture, Refused, Offline }

/** When to look at a link again, and what to keep when a look fails. Pure and unit tested. */
object LinkPolicy {
    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE

    fun retryAt(outcome: LinkOutcome, now: Long): Long = now + when (outcome) {
        LinkOutcome.Picture -> 30 * DAY
        LinkOutcome.NoPicture -> 7 * DAY
        LinkOutcome.Refused -> DAY
        LinkOutcome.Offline -> 15 * MINUTE
    }

    /** Kept and not due; a Wikipedia picture without its credit is due at once. */
    fun fresh(p: LinkPreview?, now: Long): Boolean = p != null && now < p.retryAt && !p.uncredited

    /** As [fresh], and with place photos on, a look made while they were off is stale (it never asked Wikipedia). */
    fun fresh(p: LinkPreview?, now: Long, photosOn: Boolean): Boolean = fresh(p, now) && !(photosOn && p!!.photosOff)

    /**
     * What to show of [p]: without place photos, no Wikipedia picture (a site's own picture stays); never a Wikipedia
     * picture without its credit.
     */
    fun shown(p: LinkPreview, photosOn: Boolean): LinkPreview =
        if (!p.fromWikipedia || (photosOn && !p.uncredited)) p else p.withoutPicture()

    /**
     * The preview to keep after a look: [new], unless the look failed and [old] knew more (an old picture or name
     * stays on screen until a look succeeds).
     */
    fun merge(old: LinkPreview?, new: LinkPreview): LinkPreview {
        if (!new.failed || old == null) return new
        val picture = if (old.imageUrl != null) old else new
        return old.copy(
            imageUrl = picture.imageUrl,
            imageSource = picture.imageSource,
            imageAuthor = picture.imageAuthor,
            imageLicense = picture.imageLicense,
            imageLicenseUrl = picture.imageLicenseUrl,
            imageFile = picture.imageFile,
            title = old.title ?: new.title,
            siteName = old.siteName ?: new.siteName,
            faviconUrl = old.faviconUrl ?: new.faviconUrl,
            lat = old.lat ?: new.lat,
            lng = old.lng ?: new.lng,
            retryAt = new.retryAt,
            failed = true,
            photosOff = new.photosOff,
        )
    }
}

/** The previews file: a JSON list, newest looks kept when it grows past [MAX]. Pure and unit tested. */
object LinkPreviewCodec {
    const val MAX = 400
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val list = ListSerializer(LinkPreview.serializer())

    fun encode(previews: Collection<LinkPreview>): String = json.encodeToString(list, trim(previews))

    /** The saved previews by link; a damaged file reads as empty (they are only a cache). */
    fun decode(text: String): Map<String, LinkPreview> =
        runCatching { json.decodeFromString(list, text) }.getOrDefault(emptyList()).associateBy { it.url }

    fun trim(previews: Collection<LinkPreview>): List<LinkPreview> =
        if (previews.size <= MAX) previews.toList() else previews.sortedByDescending { it.fetchedAt }.take(MAX)
}
