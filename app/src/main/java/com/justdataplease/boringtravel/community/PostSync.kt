package com.justdataplease.boringtravel.community

import android.content.Context
import androidx.work.WorkManager
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.SavedClips
import com.justdataplease.boringtravel.data.repo.now
import com.justdataplease.boringtravel.stats.TripStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The trip [tripId] as Post to community sees it (its plan, own notes and photos, clips, countries, route length); null when it is gone. */
suspend fun postSource(graph: AppGraph, tripId: String): PostSource? {
    val trip = graph.trips.get(tripId)?.takeIf { it.deletedAt == null } ?: return null
    val items = graph.plan.items(tripId)
    val entries = graph.journal.forTrip(tripId)
    val photos = graph.media.observeForTrip(tripId).first()
    val saved = SavedClips.of(graph.app).observe(tripId).first()
    val meters = withContext(Dispatchers.Default) { runCatching { TripStats.meters(graph.tracks.forTrip(tripId)) }.getOrDefault(0.0) }
    val countries = withTimeoutOrNull(4_000) { graph.world.world.filterNotNull().first() }?.tripCountries?.get(tripId).orEmpty()
    return PostSource(trip, items, entries, photos, saved, countries, meters)
}

/** What an update of a trip post sends, and how it is remembered. Pure (PostSyncTest). */
object PostSyncs {
    /** The link a trip post starts with, from what was picked ([draft]), what went up ([state]) and what was posted ([plan]). */
    fun link(tripId: String, post: Post, draft: PostDraft, source: PostSource, state: UploadState, plan: PostPlan, at: Long): PostLink {
        val own = source.ownNotes.map { it.id }
        return PostLink(
            tripId = tripId, postId = post.id, title = post.title,
            includeNotes = draft.includeNotes, noteIds = draft.noteIds.toList(),
            allNotes = draft.includeNotes && own.isNotEmpty() && own.all { it in draft.noteIds },
            cover = draft.cover, drawnCover = draft.cover == null && source.ownPhotos.isNotEmpty(),
            folderId = state.folderId, files = state.files.toMap(), sent = fingerprint(plan), syncedAt = at,
        )
    }

    /**
     * The draft an update posts: the notes picked when posting that are still there (every note, written since too,
     * when all were picked), the cover picked (another when its photo went; a photo once there are some, unless the
     * drawn picture was chosen over them).
     */
    fun draft(source: PostSource, link: PostLink, title: String, caption: String = ""): PostDraft {
        val own = source.ownNotes.map { it.id }
        val notes = if (link.allNotes) own.toCollection(LinkedHashSet()) else link.noteIds.filterTo(LinkedHashSet()) { it in own }
        val photos = source.ownPhotos.mapTo(HashSet()) { it.id }
        val cover = when {
            link.cover != null && link.cover in photos -> link.cover
            link.drawnCover -> null
            else -> source.trip.coverMediaId?.takeIf { it in photos } ?: source.ownPhotos.firstOrNull()?.id
        }
        return PostDraft(PostKind.Trip, title, caption, includeNotes = link.includeNotes, noteIds = notes, cover = cover)
    }

    /**
     * What a post holds, as a short text that changes when anything it shows changes: the numbers, the cover and
     * pictures (by media id), and the plan with its notes. The words (title, caption) are the post's own and left out.
     */
    fun fingerprint(plan: PostPlan): String {
        val text = listOf(
            plan.kind.id, plan.countries.joinToString(","), plan.days, plan.km, plan.startMonth.orEmpty(),
            plan.cover.orEmpty(), plan.pictures.joinToString(","), plan.trip?.let(SharedTrips::encode).orEmpty(),
        ).joinToString("\u0001")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}

/**
 * Trip posts kept in step with their trips (owner, 17 Nov 2026), by hand only (owner, later the same day: "the update
 * to community happens manually only, not to overprice Firestore"). A trip post remembers its trip ([links]);
 * [update] sends what the trip holds now to the same post: new pictures into its Drive folder, the plan and the chosen
 * notes, the numbers; pictures it no longer shows leave the folder. The likes, the link, the posting month and the
 * words stay. Nothing goes up by itself: only the trip's ⋮ › Community post › Update now writes to Firestore.
 */
class PostSync(private val graph: AppGraph) {
    private val app: Context = graph.app
    val links = PostLinks(File(app.filesDir, "community/post-links.json"))
    private val lock = Mutex()

    private val _running = MutableStateFlow<Map<String, UploadProgress>>(emptyMap())
    /** Updates on their way, by trip id. */
    val running: StateFlow<Map<String, UploadProgress>> = _running.asStateFlow()

    sealed interface Outcome {
        data class Sent(val post: Post) : Outcome
        data object Unchanged : Outcome

        /** The post or the trip is gone: the link went too. */
        data object Gone : Outcome
        data class Problem(val message: String) : Outcome
    }

    /**
     * From app start on: 1.19.0 queued an update after each change ("Keep it updated"); any such job left on the
     * phone is dropped, so nothing reaches Firestore without the traveller asking.
     */
    fun start() {
        runCatching { WorkManager.getInstance(app).cancelAllWorkByTag(OLD_WORKER) }
    }

    /** Trip [link]'s post from now on (just posted). */
    suspend fun linked(link: PostLink) = links.put(link)

    /** The post stays as it is and isn't updated any more. */
    suspend fun forget(tripId: String) = links.remove(tripId)

    /**
     * Whether trip [tripId] holds something its post doesn't show yet (worked out on the phone, nothing asked of the
     * network); null when it can't tell (no link, the trip gone).
     */
    suspend fun changed(tripId: String): Boolean? {
        val link = links.of(tripId) ?: return null
        val source = postSource(graph, tripId) ?: return null
        val draft = PostSyncs.draft(source, link, link.title.ifBlank { source.trip.title.ifBlank { "Trip" } })
        return PostSyncs.fingerprint(PostDrafts.plan(link.postId, source, draft) { null }) != link.sent
    }

    /** Update now, from the trip's Community post sheet: what happened, in plain words. */
    suspend fun updateNow(tripId: String): String = try {
        when (val o = update(tripId)) {
            is Outcome.Sent -> "Updated."
            Outcome.Unchanged -> "Already up to date."
            Outcome.Gone -> "The post is gone, so it isn't updated any more."
            is Outcome.Problem -> o.message
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        message(e).also { m -> links.edit(tripId) { it.copy(problem = m) } }
    }

    /** Sends what trip [tripId] holds now to its post, unless the post holds it already. */
    suspend fun update(tripId: String): Outcome = lock.withLock {
        val link = links.of(tripId) ?: return Outcome.Gone
        val source = postSource(graph, tripId)
        if (source == null) {
            links.remove(tripId)
            return Outcome.Gone
        }
        // What the post would hold, before any network: nothing new, nothing asked.
        val draft = PostSyncs.draft(source, link, link.title.ifBlank { source.trip.title.ifBlank { "Trip" } })
        PostDrafts.problem(source, draft)?.let { return problem(link, it) }
        val first = PostDrafts.plan(link.postId, source, draft) { null }
        val print = PostSyncs.fingerprint(first)
        if (print == link.sent) {
            if (link.problem != null) links.edit(tripId) { it.copy(problem = null) }
            return Outcome.Unchanged
        }
        val community = graph.community
        if (!community.available) return problem(link, "The community isn't available in this version.")
        val user = withTimeoutOrNull(USER_WAIT_MS) { community.user.filterNotNull().first() }
            ?: return problem(link, "Sign in to the community to update the post.")
        val post = community.repo.post(link.postId)
        if (post == null || post.uid != user.uid) {
            links.remove(tripId)
            return Outcome.Gone
        }
        if (PostDrafts.needsDrive(source, draft) && !community.driveReady()) {
            return problem(link, "Connect Google Drive (Me › Backup & sync) to update the post's photos.")
        }
        val plan = first.copy(title = post.title, caption = post.caption)
        val state = UploadState().apply {
            folderId = link.folderId
            files.putAll(link.files.filterKeys { it in plan.pictures })
        }
        _running.update { it + (tripId to UploadProgress("Getting ready", 0, 1)) }
        try {
            val saved = community.updatePost(plan, state) { p -> _running.update { it + (tripId to p) } }
            // Pictures the post no longer shows leave its folder (best effort: a leftover only takes space).
            community.dropFiles(link.files.filterKeys { it !in plan.pictures }.values)
            links.put(link.copy(title = saved.title, folderId = state.folderId, files = state.files.toMap(), sent = print, syncedAt = now(), problem = null))
            return Outcome.Sent(saved)
        } finally {
            _running.update { it - tripId }
        }
    }

    private suspend fun problem(link: PostLink, message: String): Outcome.Problem {
        links.edit(link.tripId) { it.copy(problem = message) }
        return Outcome.Problem(message)
    }

    private fun message(e: Exception): String = when (e) {
        is CommunityOfflineException, is NotSignedInException, is NoProfileException, is CommunityRefusedException -> e.message
        is IOException -> "No internet connection. Try again once you are online."
        else -> null
    } ?: "Couldn't update the post. Try again."

    private companion object {
        /** The worker 1.19.0 queued updates with (WorkManager tags each job with its worker's class name). */
        const val OLD_WORKER = "com.justdataplease.boringtravel.community.PostSyncWorker"

        /** How long an update waits for Firebase to say who is signed in. */
        const val USER_WAIT_MS = 15_000L
    }
}
