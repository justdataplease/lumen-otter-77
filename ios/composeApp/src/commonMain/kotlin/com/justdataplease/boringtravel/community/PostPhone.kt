package com.justdataplease.boringtravel.community

import com.justdataplease.boringtravel.compat.File
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.export.ExportFiles
import com.justdataplease.boringtravel.google.DriveNotConnectedException
import com.justdataplease.boringtravel.google.RestDriveApi
import com.justdataplease.boringtravel.io.IOException
import com.justdataplease.boringtravel.ios.CommunityGraph
import com.justdataplease.boringtravel.ios.Images
import com.justdataplease.boringtravel.ios.MediaFiles
import com.justdataplease.boringtravel.ios.Pictures
import com.justdataplease.boringtravel.ios.VideoFrames
import com.justdataplease.boringtravel.time.nowMs
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
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/*
 * Posting's phone side (round 25), what the Android app's community/CommunityMedia.kt and the cut parts of
 * community/PostSync.kt do there, written again over this app's Drive (round 23's REST client), files and pictures:
 * a post's folder in the poster's own Drive, its photos as JPEGs for the feed, a clip's picture from 40 % in, a trip
 * as Post to community sees it, and the trip posts kept in step with their trips. The rules (PostDraft, PostUploader,
 * PostLinks, PostSyncs) are Android's own files, read in.
 */

/** A post's folder in the poster's own Drive: BoringTravel/Community/<title date>, readable by anyone with the link. */
class DrivePostStore(private val drive: () -> RestDriveApi?) : PostStore {
    private fun api(): RestDriveApi = drive() ?: throw DriveNotConnectedException()

    override suspend fun folder(name: String): String {
        val d = api()
        // The parent is found (or made) again if the traveller deleted it in Drive meanwhile.
        val id = d.intoFolder(COMMUNITY) { parent -> d.newFolder(name, parent) }
        // Files made inside take this permission, so readers open every picture with PublicDrive.
        d.shareAnyone(id)
        return id
    }

    override suspend fun photo(folderId: String, name: String, jpeg: ByteArray): String =
        api().createBytes(name, jpeg, "image/jpeg", listOf(folderId)).id

    /** [file]'s path is under the app's files (a saved clip, compat's File on them). */
    override suspend fun video(folderId: String, name: String, file: File): String =
        api().uploadFile(file.path, name, "video/mp4", listOf(folderId)).id

    override suspend fun delete(folderId: String) = api().delete(folderId)

    companion object {
        val COMMUNITY = listOf("BoringTravel", "Community")
    }
}

/**
 * A post's pictures as JPEGs sized for the feed ([CommunityLimits.PHOTO_SIDE] on the long side, quality
 * [CommunityLimits.PHOTO_QUALITY]): drawn upright and written again without their tags, which leaves the camera's
 * location and every other EXIF field behind (Android's PostPhotos). On iPhone the phone's reader writes them
 * (ImageIO: HEIC too); a picture that may be see-through (PNG, WebP, GIF) goes on white with Skia, as Android's encoder
 * puts it, and so does everything on the desktop. The key [PostPlan.CLIP_FRAME] is the clip's picture from 40 % in
 * (AVAssetImageGenerator on iPhone, the key frame there as Android's OPTION_CLOSEST_SYNC).
 */
class PostPhotos(
    private val files: MediaFiles,
    private val frames: VideoFrames,
    private val media: (String) -> Media?,
    private val clip: () -> File? = { null },
) : PostPictures {
    override suspend fun jpeg(key: String): ByteArray? = withContext(Dispatchers.Default) {
        if (key == PostPlan.CLIP_FRAME) return@withContext clip()?.let(::clipFrame)
        val m = media(key)?.takeIf { it.deletedAt == null } ?: return@withContext null
        val bytes = files.read(m.localPath) ?: return@withContext null
        encode(bytes)
    }

    private fun clipFrame(file: File): ByteArray? = runCatching {
        val ms = frames.durationMs(file.path) ?: 0L
        // The same moment the Media tab's picture shows: the route under way, not the opening or the end card.
        frames.frameAt(file.path, ms * 2 / 5, CommunityLimits.PHOTO_SIDE, CommunityLimits.PHOTO_QUALITY)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        /** [bytes] as the feed's JPEG; null when the picture can't be read. */
        fun encode(bytes: ByteArray): ByteArray? {
            val format = Images.skiaFormat(bytes)
            val seeThrough = format == EncodedImageFormat.PNG || format == EncodedImageFormat.WEBP || format == EncodedImageFormat.GIF
            if (!seeThrough) {
                // The phone's reader (ImageIO on iPhone; nothing on the desktop): upright, at most the feed's size, no tags.
                val side = Pictures.size(bytes)?.let { (w, h) -> ExportFiles.fit(w, h, CommunityLimits.PHOTO_SIDE).let { maxOf(it.first, it.second) } }
                if (side != null) runCatching { Images.phone.jpeg(bytes, side, CommunityLimits.PHOTO_QUALITY) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return skia(bytes)
        }

        /** Skia's JPEG of [bytes]: decoded small, turned as its EXIF says, on white, without tags. */
        private fun skia(bytes: ByteArray): ByteArray? = runCatching {
            val data = Data.makeFromBytes(Images.drawable(bytes, CommunityLimits.PHOTO_SIDE) ?: bytes)
            val codec = Codec.makeFromData(data)
            try {
                val bitmap = Pictures.readSmall(codec, CommunityLimits.PHOTO_SIDE)
                val image = Image.makeFromBitmap(bitmap)
                val origin = codec.encodedOrigin
                val swap = origin.swapsWidthHeight()
                val ow = if (swap) bitmap.height else bitmap.width
                val oh = if (swap) bitmap.width else bitmap.height
                val (tw, th) = ExportFiles.fit(ow, oh, CommunityLimits.PHOTO_SIDE)
                val surface = Surface.makeRasterN32Premul(tw, th)
                val canvas = surface.canvas
                // JPEG has no transparency: a see-through picture goes on white, as Android draws it.
                canvas.clear(0xFFFFFFFF.toInt())
                canvas.scale(tw.toFloat() / ow, th.toFloat() / oh)
                canvas.concat(origin.toMatrix(bitmap.width, bitmap.height))
                canvas.drawImageRect(
                    image, Rect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat()), Rect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE), Paint(), true,
                )
                val out = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, CommunityLimits.PHOTO_QUALITY)?.bytes
                image.close()
                bitmap.close()
                surface.close()
                out
            } finally {
                codec.close()
                data.close()
            }
        }.getOrNull()
    }
}

/** The trip [tripId] as Post to community sees it (its plan, own notes and photos, clips, countries, route length); null when it is gone. */
suspend fun postSource(graph: CommunityGraph, tripId: String): PostSource? = graph.trips.postSource(tripId)

/**
 * Trip posts kept in step with their trips (Android's PostSync, owner, 17 Nov 2026), by hand only ("the update to
 * community happens manually only, not to overprice Firestore"). A trip post remembers its trip ([links], on this
 * phone only); [update] sends what the trip holds now to the same post: new pictures into its Drive folder, the plan
 * and the chosen notes, the numbers; pictures it no longer shows leave the folder. The likes, the link, the posting
 * month and the words stay. Nothing goes up by itself: only the trip's ⋮ › Community post › Update now writes to
 * Firestore.
 */
class PostSync(private val graph: CommunityGraph) {
    val links: PostLinks get() = graph.community.postLinks
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

    /** Android drops 1.19.0's queued updates here (WorkManager); an iPhone never had them. */
    fun start() = Unit

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
            links.put(link.copy(title = saved.title, folderId = state.folderId, files = state.files.toMap(), sent = print, syncedAt = nowMs(), problem = null))
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
        /** How long an update waits for Firebase to say who is signed in. */
        const val USER_WAIT_MS = 15_000L
    }
}
