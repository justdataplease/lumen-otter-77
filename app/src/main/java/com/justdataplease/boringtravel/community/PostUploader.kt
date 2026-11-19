package com.justdataplease.boringtravel.community

import kotlinx.coroutines.CancellationException
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where a post's pictures and clip go: a new link-shared folder in the poster's Drive (BoringTravel/Community/…). */
interface PostStore {
    /** A new folder [name] under BoringTravel/Community, shared "anyone with the link can view"; its id. */
    suspend fun folder(name: String): String

    /** A JPEG in [folderId]; its Drive id. */
    suspend fun photo(folderId: String, name: String, jpeg: ByteArray): String

    /** An MP4 in [folderId]; its Drive id. */
    suspend fun video(folderId: String, name: String, file: File): String

    /** Deletes a post's folder and what is in it (gone already counts as done). */
    suspend fun delete(folderId: String)
}

/** A picture to post, as a JPEG sized for the feed with no location in it; null when it can't be read. */
fun interface PostPictures {
    suspend fun jpeg(key: String): ByteArray?
}

/**
 * What to post. [pictures] are keys [PostPictures] understands (media ids, or [CLIP_FRAME] for a frame of the clip),
 * in the order the post page swipes through them; [cover] is one of them, or null for a drawn picture. The [trip]'s
 * notes name their photos by the same keys; they become Drive ids on the way.
 */
data class PostPlan(
    val id: String,
    val kind: PostKind,
    val title: String,
    val caption: String = "",
    val countries: List<String> = emptyList(),
    val days: Int = 0,
    val km: Int = 0,
    val startMonth: String? = null,
    val pictures: List<String> = emptyList(),
    val cover: String? = null,
    val clip: File? = null,
    val trip: SharedTrip? = null,
) {
    companion object {
        const val CLIP_FRAME = "clip-frame"
    }
}

/** Where an upload stands, kept between tries so a retry carries on instead of starting again. */
class UploadState {
    var folderId: String? = null
    /** Picture key → Drive id. */
    val files = LinkedHashMap<String, String>()
    /** Keys that could not be read (left out of the post). */
    val skipped = LinkedHashSet<String>()
    var clipFileId: String? = null
    var posted: Post? = null
}

/** "Uploading photos · 3 of 12". */
data class UploadProgress(val label: String, val done: Int, val total: Int) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** Why a post can't go yet, in the composer's words; null when it can. Pure. */
object PostRules {
    fun problem(plan: PostPlan): String? = when {
        plan.title.isBlank() -> "Give it a title."
        plan.title.length > CommunityLimits.TITLE -> "The title is too long."
        plan.caption.length > CommunityLimits.CAPTION -> "The caption is too long."
        plan.pictures.size > CommunityLimits.PHOTOS -> "Up to ${CommunityLimits.PHOTOS} photos."
        plan.kind == PostKind.Album && plan.pictures.isEmpty() -> "Pick at least one photo."
        plan.kind == PostKind.Clip && plan.clip == null -> "Pick a clip."
        plan.kind == PostKind.Trip && plan.trip == null -> "This trip has nothing to share yet."
        plan.cover != null && plan.cover !in plan.pictures -> "Pick the cover again."
        else -> null
    }

    /** The post's folder name in Drive: "Patagonia 2026-11-11" (no slashes, at most 100 characters). */
    fun folderName(title: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).format(Instant.ofEpochMilli(nowMs).atZone(zone))
        val clean = title.replace(Regex("[\\\\/\\p{Cntrl}]+"), " ").replace(Regex("\\s+"), " ").trim().take(80)
        return "${clean.ifEmpty { "Post" }} $date"
    }
}

/**
 * Posts in three steps, each kept in [UploadState] so a retry carries on: a folder in the poster's Drive (link-shared),
 * the pictures (and the clip) into it, then the post in Firestore. A post with nothing to upload (a plan with a drawn
 * cover) makes no folder and needs no Drive. [run] with `update` sends a post again ([PostSync]): [UploadState] then
 * starts with the post's folder and the pictures already in it, so only new ones go up. Pure apart from what it is
 * given (PostUploaderTest).
 */
class PostUploader(
    private val store: PostStore,
    private val pictures: PostPictures,
    private val repo: CommunityRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun run(plan: PostPlan, state: UploadState, update: Boolean = false, onProgress: (UploadProgress) -> Unit = {}): Post {
        state.posted?.let { return it }
        PostRules.problem(plan)?.let { throw IllegalArgumentException(it) }
        val uploads = plan.pictures.isNotEmpty() || plan.clip != null
        val fresh = plan.pictures.count { it !in state.files }
        val total = (if (uploads) 1 else 0) + (if (update) fresh else plan.pictures.size) + (if (plan.clip != null) 1 else 0) + 1
        var done = 0
        fun step(label: String) = onProgress(UploadProgress(label, done, total))

        val folder: String? = if (uploads) {
            step("Making a folder in your Drive")
            (state.folderId ?: store.folder(PostRules.folderName(plan.title, now(), zone)).also { state.folderId = it })
                .also { done++ }
        } else {
            null
        }

        var n = 0
        if (folder != null) plan.pictures.forEachIndexed { i, key ->
            // An update counts only the pictures that go up now.
            if (update && key in state.files) return@forEachIndexed
            n++
            val of = if (update) fresh else plan.pictures.size
            step(if (of == 1) "Uploading the photo" else "Uploading photos · $n of $of")
            if (key !in state.files && key !in state.skipped) {
                val jpeg = try {
                    pictures.jpeg(key)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (jpeg == null) state.skipped += key else state.files[key] = store.photo(folder, "photo-${i + 1}.jpg", jpeg)
            }
            done++
        }
        if (plan.kind == PostKind.Album && state.files.isEmpty()) throw IllegalStateException("The photos couldn't be read.")

        val clip = plan.clip
        if (clip != null && folder != null) {
            step("Uploading the clip")
            if (state.clipFileId == null) state.clipFileId = store.video(folder, "clip.mp4", clip)
            done++
        }

        step(if (update) "Updating the post" else "Posting")
        val photoIds = plan.pictures.mapNotNull { state.files[it] }
        val trip = plan.trip?.let { t -> t.copy(notes = t.notes.map { n -> n.copy(photos = n.photos.mapNotNull { state.files[it] }) }) }
        val fitted = trip?.let { SharedTrips.fit(it) ?: throw IllegalStateException("This trip's plan is too long to post.") }
        val post = Post(
            id = plan.id, uid = "", username = "", kind = plan.kind,
            title = plan.title.trim(), caption = plan.caption.trim(),
            countries = plan.countries.map { it.uppercase(Locale.ROOT) }.filter { it.matches(Regex("[A-Z]{2}")) }.distinct()
                .take(CommunityLimits.COUNTRIES),
            days = plan.days.coerceIn(0, CommunityLimits.MAX_DAYS), km = plan.km.coerceIn(0, CommunityLimits.MAX_KM),
            startMonth = plan.startMonth, month = PostText.monthKey(now()),
            coverFileId = plan.cover?.let { state.files[it] } ?: if (plan.kind == PostKind.Clip) photoIds.firstOrNull() else null,
            photoFileIds = photoIds, clipFileId = state.clipFileId, folderId = folder, hasTrip = fitted != null,
        )
        val saved = if (update) repo.updatePost(post, fitted?.let(SharedTrips::encode)) else repo.createPost(post, fitted?.let(SharedTrips::encode))
        done++
        onProgress(UploadProgress(if (update) "Updated" else "Posted", done, total))
        state.posted = saved
        return saved
    }

    /** Gives up on an upload that didn't finish: its Drive folder goes (nothing was posted). */
    suspend fun discard(state: UploadState) {
        if (state.posted != null) return
        state.folderId?.let { runCatching { store.delete(it) } }
        state.folderId = null
        state.files.clear()
        state.skipped.clear()
        state.clipFileId = null
    }
}
