package com.justdataplease.boringtravel.community

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import com.justdataplease.boringtravel.data.repo.MediaRepository
import com.justdataplease.boringtravel.export.ExportFiles
import com.justdataplease.boringtravel.google.RestDriveApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** A post's folder in the poster's own Drive: BoringTravel/Community/<title date>, readable by anyone with the link. */
class DrivePostStore(private val drive: RestDriveApi) : PostStore {
    override suspend fun folder(name: String): String {
        // The parent is found (or made) again if the traveller deleted it in Drive meanwhile.
        val id = drive.intoFolder(COMMUNITY) { parent -> drive.newFolder(name, parent) }
        // Files made inside take this permission, so readers open every picture with PublicDrive.
        drive.shareAnyone(id)
        return id
    }

    override suspend fun photo(folderId: String, name: String, jpeg: ByteArray): String =
        drive.createBytes(name, jpeg, "image/jpeg", listOf(folderId)).id

    override suspend fun video(folderId: String, name: String, file: File): String =
        drive.uploadFile(file, name, "video/mp4", listOf(folderId)).id

    override suspend fun delete(folderId: String) = drive.delete(folderId)

    companion object {
        val COMMUNITY = listOf("BoringTravel", "Community")
    }
}

/**
 * Debug builds on the Firebase emulators only (Community.usesEmulator): a post's pictures and clip go to the small
 * picture server beside the emulators (android-boringtravel/firebase/test/pictures.js) instead of the poster's Drive, so the
 * whole of posting can be tried on an emulator without a Google account.
 */
class EmulatorPostStore(private val http: okhttp3.OkHttpClient, private val base: String) : PostStore {
    private fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(24)

    private suspend fun put(id: String, folderId: String?, body: okhttp3.RequestBody) = withContext(Dispatchers.IO) {
        val url = "$base/$id" + (folderId?.let { "?folder=$it" } ?: "")
        http.newCall(okhttp3.Request.Builder().url(url).put(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("The picture server said ${r.code}.")
        }
    }

    override suspend fun folder(name: String): String = newId().also { put(it, null, name.toByteArray().toRequestBody()) }

    override suspend fun photo(folderId: String, name: String, jpeg: ByteArray): String =
        newId().also { put(it, folderId, jpeg.toRequestBody("image/jpeg".toMediaType())) }

    override suspend fun video(folderId: String, name: String, file: File): String =
        newId().also { put(it, folderId, file.asRequestBody("video/mp4".toMediaType())) }

    override suspend fun delete(folderId: String) {
        withContext(Dispatchers.IO) {
            http.newCall(okhttp3.Request.Builder().url("$base/$folderId").delete().build()).execute().close()
        }
    }
}

/**
 * A post's pictures as JPEGs sized for the feed ([CommunityLimits.PHOTO_SIDE] on the long side): decoded with their
 * rotation applied and saved again, which leaves the camera's location and every other EXIF field behind. The key
 * [PostPlan.CLIP_FRAME] is a frame from the middle of [clip].
 */
class PostPhotos(
    private val context: Context,
    private val media: MediaRepository,
    private val clip: () -> File? = { null },
) : PostPictures {
    override suspend fun jpeg(key: String): ByteArray? = withContext(Dispatchers.IO) {
        if (key == PostPlan.CLIP_FRAME) return@withContext clip()?.let(::clipFrame)
        val m = media.get(key)?.takeIf { it.deletedAt == null } ?: return@withContext null
        val file = File(context.filesDir, m.localPath)
        if (!file.isFile) return@withContext null
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val (w, h) = ExportFiles.fit(info.size.width, info.size.height, CommunityLimits.PHOTO_SIDE)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return@withContext null
        encode(bitmap)
    }

    private fun clipFrame(file: File): ByteArray? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // The same moment the Media tab's thumbnail shows: the route under way, not the opening or the end card.
            val frame = r.getFrameAtTime(ms * 1000L * 2 / 5, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@use null
            val scale = CommunityLimits.PHOTO_SIDE.toFloat() / max(frame.width, frame.height).coerceAtLeast(1)
            val sized = if (scale < 1f) {
                Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt().coerceAtLeast(1), (frame.height * scale).roundToInt().coerceAtLeast(1), true)
                    .also { frame.recycle() }
            } else {
                frame
            }
            encode(sized)
        }
    }.getOrNull()

    private fun encode(bitmap: Bitmap): ByteArray? {
        val opaque = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                val canvas = android.graphics.Canvas(it)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                bitmap.recycle()
            }
        } else {
            bitmap
        }
        return try {
            ByteArrayOutputStream(256 * 1024).use { buf ->
                if (!opaque.compress(Bitmap.CompressFormat.JPEG, CommunityLimits.PHOTO_QUALITY, buf)) null else buf.toByteArray()
            }
        } finally {
            opaque.recycle()
        }
    }
}
