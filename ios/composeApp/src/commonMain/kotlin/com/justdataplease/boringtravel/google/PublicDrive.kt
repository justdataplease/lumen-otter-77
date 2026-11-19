package com.justdataplease.boringtravel.google

import com.justdataplease.boringtravel.io.IOException
import com.justdataplease.boringtravel.ios.MediaFiles
import com.justdataplease.boringtravel.ios.Net
import com.justdataplease.boringtravel.ios.NetAnswer
import com.justdataplease.boringtravel.ios.NetCall
import com.justdataplease.boringtravel.ios.UrlCodec
import com.justdataplease.boringtravel.ios.call
import com.justdataplease.boringtravel.ios.fetch

/** A shared file was taken off its link, deleted, or never shared: nobody but its owner can read it now. */
class NotSharedException : IOException("This file isn't shared any more.")

/**
 * The Android app's google/PublicDrive.kt over this app's [Net] (round 24): Drive files someone shared as "anyone with
 * the link can view" (a community post's pictures and clips; later another member's shared trip), read without
 * signing in to their Drive (drive.file opens only the files this app made for the signed-in account).
 *
 * With [apiKey] (the build value `drive.apiKey`: a Cloud key restricted to the Drive API and this app's bundle id, sent
 * with the X-Ios-Bundle-Identifier header, as Android sends its package and certificate) it uses the Drive API. Without
 * one it uses Google's public download address, which serves the same link-shared files.
 */
class PublicDrive(
    private val net: Net,
    private val apiKey: String,
    private val bundleId: String?,
    private val apiBase: String = "https://www.googleapis.com",
    private val downloadBase: String = "https://drive.usercontent.google.com",
    private val retryWait: Long = 1_000,
) {
    val usesKey: Boolean get() = apiKey.isNotBlank()

    /** The address of file [id]'s content and the headers to send with it. */
    fun address(id: String): Pair<String, Map<String, String>> {
        requireId(id)
        return if (usesKey) {
            "$apiBase/drive/v3/files/$id?alt=media&key=" + UrlCodec.queryComponent(apiKey) to keyHeaders()
        } else {
            "$downloadBase/download?id=$id&export=download" to emptyMap()
        }
    }

    /** The content of file [id], at most [maxBytes]. Throws [NotSharedException] when it is not readable by link. */
    suspend fun bytes(id: String, maxBytes: Long): ByteArray = retrying(firstWait = retryWait) {
        val (url, headers) = address(id)
        val answer = net.call(NetCall("GET", url, headers, maxBytes = maxBytes + 1, timeoutMs = 60_000))
        check(answer)
        if (answer.body.size > maxBytes) throw IOException("This file is too big.")
        answer.body
    }

    /**
     * Downloads file [id] into [path] under [files] through a .part file, at most [maxBytes]; a failed download leaves
     * nothing behind.
     */
    suspend fun download(id: String, files: MediaFiles, path: String, maxBytes: Long) {
        val part = "$path.part"
        try {
            retrying(firstWait = retryWait) {
                files.delete(part)
                val (url, headers) = address(id)
                var total = 0L
                val answer = net.fetch(NetCall("GET", url, headers, timeoutMs = NO_LIMIT_MS)) { bytes, n ->
                    total += n
                    if (total > maxBytes) throw IOException("This file is too big.")
                    if (!files.append(part, bytes, n)) throw IOException("The clip couldn't be kept on this phone.")
                }
                // An error's body comes whole; without a key, a file that isn't shared answers with Google's sign-in page.
                check(answer)
            }
            if (!files.move(part, path)) throw IOException("The clip couldn't be kept on this phone.")
        } finally {
            files.delete(part)
        }
    }

    private fun keyHeaders(): Map<String, String> = buildMap { bundleId?.let { put("X-Ios-Bundle-Identifier", it) } }

    private fun check(r: NetAnswer) {
        val body = if (r.ok) "" else r.body.decodeToString().take(4096)
        // The app's own key was refused (its bundle id, the API switched off): not the link's fault, and said so.
        if ((r.code == 400 || r.code == 403) && KEY_TROUBLE.containsMatchIn(body)) {
            throw DriveException(r.code, "Shared trips can't be read right now. Update BoringTravel, or try again later.")
        }
        if (r.code == 404 || r.code == 403 && !DriveJson.busy(r.code, body) || r.code == 401) throw NotSharedException()
        if (!r.ok) throw DriveException(r.code, "Google Drive returned error ${r.code}.")
        if (!usesKey && r.header("Content-Type").orEmpty().startsWith("text/html")) throw NotSharedException()
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{5,200}")
        private val KEY_TROUBLE = Regex("API key|API_KEY|keyInvalid|accessNotConfigured|SERVICE_DISABLED|has not been used in project|are blocked", RegexOption.IGNORE_CASE)
        private const val NO_LIMIT_MS = Long.MAX_VALUE

        fun requireId(id: String) = require(ID.matches(id)) { "Invalid Drive file id" }

        fun isId(id: String?): Boolean = id != null && ID.matches(id)
    }
}
