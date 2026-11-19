package com.justdataplease.boringtravel.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** A shared file was taken off its link, deleted, or never shared: nobody but its owner can read it now. */
class NotSharedException : IOException("This file isn't shared any more.")

/**
 * Reads Drive files someone shared as "anyone with the link can view" — another member's shared trip, a community
 * post's pictures — without signing in to their Drive. `drive.file` cannot do this: it opens only the files this
 * app made for the signed-in account, so a friend's file answers 404 even after they shared it.
 *
 * With [apiKey] (a Cloud key restricted to the Drive API and this app's packages and certificates, sent with the
 * X-Android-Package and X-Android-Cert headers, as JustPhotoViewer does) it uses the Drive API. Without one it uses
 * Google's public download address, which serves the same link-shared files.
 */
class PublicDrive(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val packageName: String,
    private val certSha1: String?,
    private val apiBase: String = "https://www.googleapis.com",
    private val downloadBase: String = "https://drive.usercontent.google.com",
    private val onServerTime: (dateHeader: String?) -> Unit = {},
) {
    val usesKey: Boolean get() = apiKey.isNotBlank()

    /** The content of file [id], at most [maxBytes]. Throws [NotSharedException] when it is not readable by link. */
    suspend fun bytes(id: String, maxBytes: Long): ByteArray = retrying {
        open(id).use { r ->
            check(r)
            withContext(Dispatchers.IO) {
                val body = r.body ?: throw IOException("Google Drive sent an empty file.")
                if (body.contentLength() > maxBytes) throw IOException("This file is too big.")
                val out = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        if (out.size() + n > maxBytes) throw IOException("This file is too big.")
                        out.write(buf, 0, n)
                    }
                }
                out.toByteArray()
            }
        }
    }

    /** Downloads file [id] into [target] through a .part file, at most [maxBytes]; a failed download leaves nothing. */
    suspend fun download(id: String, target: File, maxBytes: Long) {
        val part = File(target.path + ".part")
        try {
            retrying {
                open(id).use { r ->
                    check(r)
                    withContext(Dispatchers.IO) {
                        target.parentFile?.mkdirs()
                        val body = r.body ?: throw IOException("Google Drive sent an empty file.")
                        part.outputStream().use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > maxBytes) throw IOException("This file is too big.")
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                    }
                }
            }
            withContext(Dispatchers.IO) {
                if (!part.renameTo(target)) part.copyTo(target, overwrite = true)
            }
        } finally {
            // Gone after the rename; what is left is a broken or stopped download (up to maxBytes).
            part.delete()
        }
    }

    /**
     * Drive's MD5 of file [id], without downloading it, so an unchanged file isn't fetched again. Null without an API
     * key (the public download address has no such answer) or when Drive doesn't say. Throws [NotSharedException]
     * when the file isn't readable by link.
     */
    suspend fun checksum(id: String): String? {
        if (!usesKey) return null
        requireId(id)
        val url = "$apiBase/drive/v3/files/$id".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "md5Checksum")
            .addQueryParameter("key", apiKey)
            .build()
        return retrying {
            val r = http.newCall(Request.Builder().url(url).apply { keyHeaders(this) }.build()).await()
            onServerTime(r.header("Date"))
            r.use {
                check(it)
                val body = withContext(Dispatchers.IO) { it.body?.string().orEmpty() }
                runCatching { (DriveJson.parse(body)["md5Checksum"] as? JsonPrimitive)?.contentOrNull }.getOrNull()?.takeIf { sum -> sum.isNotBlank() }
            }
        }
    }

    /** A request for file [id]'s content, with the headers it needs (for image and video loaders). */
    fun request(id: String): Request {
        requireId(id)
        return Request.Builder().url(url(id)).apply { if (usesKey) keyHeaders(this) }.build()
    }

    /** The address of file [id]'s content and the headers to send with it. */
    fun address(id: String): Pair<String, Map<String, String>> {
        val r = request(id)
        return r.url.toString() to r.headers.toMap()
    }

    private fun url(id: String): HttpUrl = if (usesKey) {
        "$apiBase/drive/v3/files/$id".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .addQueryParameter("key", apiKey)
            .build()
    } else {
        "$downloadBase/download".toHttpUrl().newBuilder()
            .addQueryParameter("id", id)
            .addQueryParameter("export", "download")
            .build()
    }

    private fun keyHeaders(b: Request.Builder) {
        b.header("X-Android-Package", packageName)
        certSha1?.let { b.header("X-Android-Cert", it) }
    }

    private suspend fun open(id: String): Response {
        val r = http.newCall(request(id)).await()
        onServerTime(r.header("Date"))
        return r
    }

    private fun check(r: Response) {
        val body = if (r.isSuccessful) "" else r.peekBody(4096).string()
        // The app's own key was refused (its signature, the API switched off): not the link's fault, and said so.
        if ((r.code == 400 || r.code == 403) && KEY_TROUBLE.containsMatchIn(body)) {
            throw DriveException(r.code, "Shared trips can't be read right now. Update BoringTravel, or try again later.")
        }
        if (r.code == 404 || r.code == 403 && !DriveJson.busy(r.code, body) || r.code == 401) throw NotSharedException()
        if (!r.isSuccessful) throw DriveException(r.code, "Google Drive returned error ${r.code}.")
        // Without a key, a file that isn't shared answers with Google's sign-in page instead of an error.
        if (!usesKey && r.header("Content-Type").orEmpty().startsWith("text/html")) throw NotSharedException()
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{5,200}")
        private val KEY_TROUBLE = Regex("API key|API_KEY|keyInvalid|accessNotConfigured|SERVICE_DISABLED|has not been used in project|are blocked", RegexOption.IGNORE_CASE)
        fun requireId(id: String) = require(ID.matches(id)) { "Invalid Drive file id" }
        fun isId(id: String?): Boolean = id != null && ID.matches(id)
    }
}
