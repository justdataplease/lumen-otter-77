package com.justdataplease.boringtravel.community

import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.RouteLegSource
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.SavedClip
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.places.Geo
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import com.justdataplease.boringtravel.model.PlanSpan

/**
 * A trip on this phone as Post to community sees it. Only the traveller's own notes and photos count (not what another
 * member added to a shared trip), and never the recorded route: its length is a number, nothing more.
 */
data class PostSource(
    val trip: Trip,
    val items: List<PlanItem> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val photos: List<Media> = emptyList(),
    /** The trip's saved clips, newest first. */
    val clips: List<SavedClip> = emptyList(),
    /** ISO codes of the trip's countries, in travel order. */
    val countries: List<String> = emptyList(),
    /** The recorded route's length (TripStats.meters); 0 when none was recorded. */
    val recordedMeters: Double = 0.0,
) {
    /** Own photos of the trip, oldest first. */
    val ownPhotos: List<Media> by lazy {
        photos.filter { it.deletedAt == null && it.authorId == null && it.tripId == trip.id && it.mime.startsWith("image/") }
            .sortedWith(compareBy<Media>({ it.takenAt ?: it.createdAt }, { it.id }))
    }

    /** Own notes of the trip with something in them, in time order. */
    val ownNotes: List<Entry> by lazy {
        val withPhotos = ownPhotos.mapNotNullTo(HashSet()) { it.entryId }
        entries.filter { it.deletedAt == null && it.authorId == null && it.tripId == trip.id }
            .filter { it.title.isNotBlank() || it.plainText.isNotBlank() || it.id in withPhotos }
            .sortedWith(compareBy<Entry>({ it.occurredAt }, { it.id }))
    }

    /** The photos of note [entryId], oldest first (media ids). */
    fun photosOf(entryId: String): List<String> = ownPhotos.filter { it.entryId == entryId }.map { it.id }

    /** The live plan items. */
    val liveItems: List<PlanItem> get() = items.filter { it.deletedAt == null && it.tripId == trip.id && it.title.isNotBlank() }
}

/**
 * What the traveller picked in Post to community. [cover] is a media id, [PostPlan.CLIP_FRAME] (a frame of the clip) or
 * null (a drawn picture of the place, as trips without photos have).
 */
data class PostDraft(
    val kind: PostKind,
    val title: String,
    val caption: String = "",
    /** Trip posts: the chosen notes go along (off by default). */
    val includeNotes: Boolean = false,
    val noteIds: Set<String> = emptySet(),
    /** Album posts: the picked photos, in the order picked. */
    val albumIds: List<String> = emptyList(),
    /** Clip posts: the saved clip. */
    val clipId: String? = null,
    val cover: String? = null,
)

/** The composer's rules: what a draft starts as, what it posts and why it can't yet. Pure (PostDraftTest). */
object PostDrafts {
    private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT)

    /** Why this trip can't be posted at all, or null. */
    fun blocked(trip: Trip): String? = when {
        trip.isDemo -> "Demo trips can't be posted. Post a trip of your own."
        trip.deletedAt != null -> "This trip was deleted."
        else -> null
    }

    /**
     * The first draft: [kind] ("trip", "clip", "album"), the trip's title, a clip ([clipId], else the newest) or the
     * photos picked in Media ([mediaIds]), and a cover to match.
     */
    fun start(source: PostSource, kind: String?, clipId: String? = null, mediaIds: List<String> = emptyList()): PostDraft {
        val k = PostKind.of(kind) ?: PostKind.Trip
        val own = source.ownPhotos.mapTo(HashSet()) { it.id }
        val album = mediaIds.filter { it in own }.distinct().take(CommunityLimits.PHOTOS)
        val draft = PostDraft(
            kind = k,
            title = source.trip.title.trim().take(CommunityLimits.TITLE),
            albumIds = album,
            clipId = clipId?.takeIf { id -> source.clips.any { it.id == id } } ?: source.clips.firstOrNull()?.id,
        )
        return draft.copy(cover = defaultCover(source, draft))
    }

    /** Another kind: the cover follows it. */
    fun withKind(source: PostSource, draft: PostDraft, kind: PostKind): PostDraft {
        if (draft.kind == kind) return draft
        val next = draft.copy(kind = kind)
        return next.copy(cover = defaultCover(source, next))
    }

    /** Notes on: every note is picked to start with; off keeps the picks for when they go on again. */
    fun withNotes(source: PostSource, draft: PostDraft, on: Boolean): PostDraft = when {
        !on -> draft.copy(includeNotes = false)
        draft.noteIds.isEmpty() -> draft.copy(includeNotes = true, noteIds = source.ownNotes.mapTo(LinkedHashSet()) { it.id })
        else -> draft.copy(includeNotes = true)
    }

    fun toggleNote(draft: PostDraft, entryId: String): PostDraft =
        draft.copy(noteIds = if (entryId in draft.noteIds) draft.noteIds - entryId else draft.noteIds + entryId)

    /** Picks or drops an album photo (at most [CommunityLimits.PHOTOS]); the cover moves when its photo goes. */
    fun toggleAlbum(draft: PostDraft, mediaId: String): PostDraft {
        val picked = if (mediaId in draft.albumIds) {
            draft.albumIds - mediaId
        } else {
            if (draft.albumIds.size >= CommunityLimits.PHOTOS) return draft
            draft.albumIds + mediaId
        }
        val cover = draft.cover?.takeIf { it in picked } ?: picked.firstOrNull()
        return draft.copy(albumIds = picked, cover = cover)
    }

    fun withClip(source: PostSource, draft: PostDraft, clipId: String): PostDraft =
        if (source.clips.none { it.id == clipId }) draft else draft.copy(clipId = clipId)

    fun withCover(source: PostSource, draft: PostDraft, cover: String?): PostDraft =
        if (cover in coverChoices(source, draft)) draft.copy(cover = cover) else draft

    /** What the cover row offers: a trip: the drawn picture and its photos; an album: the picked photos; a clip: its frame and the photos. */
    fun coverChoices(source: PostSource, draft: PostDraft): List<String?> = when (draft.kind) {
        PostKind.Trip -> listOf<String?>(null) + source.ownPhotos.map { it.id }
        PostKind.Album -> draft.albumIds
        PostKind.Clip -> listOf<String?>(PostPlan.CLIP_FRAME) + source.ownPhotos.map { it.id }
    }

    private fun defaultCover(source: PostSource, draft: PostDraft): String? = when (draft.kind) {
        PostKind.Trip -> source.trip.coverMediaId?.takeIf { id -> source.ownPhotos.any { it.id == id } } ?: source.ownPhotos.firstOrNull()?.id
        PostKind.Album -> draft.albumIds.firstOrNull()
        PostKind.Clip -> PostPlan.CLIP_FRAME
    }

    /** The notes that go along with a trip post, in time order. */
    fun notes(source: PostSource, draft: PostDraft): List<Entry> =
        if (draft.kind != PostKind.Trip || !draft.includeNotes) emptyList() else source.ownNotes.filter { it.id in draft.noteIds }

    /** Every picture the post uploads, the cover first, at most [CommunityLimits.PHOTOS]. */
    fun pictures(source: PostSource, draft: PostDraft): List<String> = allPictures(source, draft).take(CommunityLimits.PHOTOS)

    /** Whether posting puts anything in the poster's Drive (photos, a clip and its frame); a drawn cover doesn't. */
    fun needsDrive(source: PostSource, draft: PostDraft): Boolean =
        draft.kind == PostKind.Clip || pictures(source, draft).isNotEmpty()

    /** Note photos that don't fit under the cap. */
    fun leftOut(source: PostSource, draft: PostDraft): Int = (allPictures(source, draft).size - CommunityLimits.PHOTOS).coerceAtLeast(0)

    private fun allPictures(source: PostSource, draft: PostDraft): List<String> = when (draft.kind) {
        PostKind.Trip -> (listOfNotNull(draft.cover) + notes(source, draft).flatMap { source.photosOf(it.id) }).distinct()
        PostKind.Album -> draft.albumIds.distinct()
        PostKind.Clip -> listOf(draft.cover ?: PostPlan.CLIP_FRAME)
    }

    /** The plan and the chosen notes (their photos by media id, as many as fit), or null when there is nothing to share. */
    fun sharedTrip(source: PostSource, draft: PostDraft): SharedTrip? {
        if (draft.kind != PostKind.Trip) return null
        val going = pictures(source, draft).toHashSet()
        val notes = notes(source, draft).map { SharedTrips.NoteInput(it, source.photosOf(it.id).filter { p -> p in going }) }
        val shared = SharedTrips.build(source.trip, source.liveItems, notes)
        return shared.takeIf { it.items.isNotEmpty() || it.notes.isNotEmpty() }
    }

    /** Days of the trip: its dates, else the plan's span. */
    fun days(source: PostSource): Int =
        source.trip.days ?: SharedTrips.build(source.trip, source.liveItems).days

    /**
     * Kilometres: the recorded route when there is one, else the planned places in order (straight lines between
     * them, ideas left out), each leg counted as the plan counts it (a flight only when switched on,
     * [TravelDefault.countsKm]); 0 when neither says anything.
     */
    fun km(source: PostSource): Int {
        val recorded = source.recordedMeters / 1000.0
        if (recorded >= 1.0) return recorded.roundToInt()
        val path = PlanSpan.ordered(source.liveItems.filter { it.day != null && it.lat != null && it.lng != null })
        val vehicles = TravelModes.choices(source.trip.travelModes)
        val counts = TravelModes.kmChoices(source.trip.travelModes)
        var m = 0.0
        for (i in 1 until path.size) {
            val a = path[i - 1]
            val b = path[i]
            val d = Geo.distanceM(a.lat!!, a.lng!!, b.lat!!, b.lng!!)
            val key = RouteLegKey(RouteLegSource.Planned, a.id, b.id)
            val vehicle = vehicles[key] ?: TravelDefault.between(b.title, samePlace = false, distanceM = d, travelItem = b.kind == PlanKind.Transport.id)
            if (TravelDefault.countsKm(vehicle, counts[key])) m += d
        }
        return (m / 1000.0).roundToInt()
    }

    /** The trip's first month ("2027-03"): its start, else its first planned day; null for a trip without either. */
    fun startMonth(source: PostSource): String? {
        val first = source.trip.startDate ?: source.liveItems.mapNotNull { it.day }.minOrNull()
        return first?.let { MONTH.format(it) }
    }

    /** What to upload, for [PostUploader]. [clipFile] finds a saved clip's video on the phone. */
    fun plan(id: String, source: PostSource, draft: PostDraft, clipFile: (SavedClip) -> File?): PostPlan {
        val clip = if (draft.kind == PostKind.Clip) source.clips.firstOrNull { it.id == draft.clipId }?.let(clipFile) else null
        val pictures = pictures(source, draft)
        return PostPlan(
            id = id, kind = draft.kind, title = draft.title.trim(), caption = draft.caption.trim(),
            countries = source.countries, days = days(source), km = km(source), startMonth = startMonth(source),
            pictures = pictures, cover = draft.cover?.takeIf { it in pictures }, clip = clip, trip = sharedTrip(source, draft),
        )
    }

    /** The post as the feed will show it, for the composer's preview (pictures are drawn from the phone). */
    fun preview(id: String, source: PostSource, draft: PostDraft, uid: String, username: String, nowMs: Long): Post {
        val pictures = pictures(source, draft)
        return Post(
            id = id, uid = uid, username = username, kind = draft.kind,
            title = draft.title.trim().ifEmpty { source.trip.title }, caption = draft.caption.trim(),
            countries = source.countries.take(CommunityLimits.COUNTRIES), days = days(source), km = km(source),
            startMonth = startMonth(source), month = PostText.monthKey(nowMs),
            coverFileId = draft.cover?.let { "cover" }, photoFileIds = pictures.indices.map { "photo$it" },
            clipFileId = if (draft.kind == PostKind.Clip) "clip" else null, hasTrip = draft.kind == PostKind.Trip,
            createdAt = nowMs, updatedAt = nowMs,
        )
    }

    /** Why [draft] can't be posted yet, in the composer's words; null when it can. */
    fun problem(source: PostSource, draft: PostDraft, clipFile: (SavedClip) -> File? = { null }): String? {
        blocked(source.trip)?.let { return it }
        when (draft.kind) {
            PostKind.Trip -> if (sharedTrip(source, draft) == null) return "This trip has no plan to share yet."
            PostKind.Album -> if (draft.albumIds.isEmpty()) return if (source.ownPhotos.isEmpty()) "This trip has no photos yet." else "Pick at least one photo."
            PostKind.Clip -> if (source.clips.isEmpty()) return "Make a clip of this trip first." else if (draft.clipId == null) return "Pick a clip."
        }
        if (draft.title.isBlank()) return "Give it a title."
        val plan = plan("check", source, draft) { clip -> clipFile(clip) ?: File(clip.id) }
        return PostRules.problem(plan)
    }
}
