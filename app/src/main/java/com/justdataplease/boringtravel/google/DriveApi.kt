package com.justdataplease.boringtravel.google

import java.io.File

/**
 * The owner's Google Drive, as the rest of the app sees it. Only the `drive.file` scope (files this app created) and
 * `drive.appdata` (a hidden app folder) are ever used: both are non-sensitive, so no restricted-scope review.
 *
 * [RestDriveApi] implements it over the Drive REST API ([com.justdataplease.boringtravel.AppGraph.drive]); export,
 * clips, backups and photos only call this interface.
 */
interface DriveApi {
    /** True when the owner signed in and granted Drive access. */
    suspend fun isConnected(): Boolean

    /** Finds or creates a folder path under My Drive, e.g. ["BoringTravel", "Exports"]; returns its id. */
    suspend fun folder(path: List<String>): String

    /**
     * Uploads [file] into [folderId]. With [convertTo] (e.g. [GOOGLE_DOC]) Drive converts it on upload, so an HTML
     * file becomes an editable Google Doc.
     */
    suspend fun upload(file: File, name: String, mime: String, folderId: String?, convertTo: String? = null): DriveFile

    /** Plain text of an app-authorized Google Doc. A link alone does not grant drive.file access. */
    suspend fun exportText(id: String, maxBytes: Int): ByteArray = throw DriveNotConnectedException()

    /**
     * An app-authorized Google Doc exported as [mimeType] ("text/html" for Copy from a link), handed to [read] as it
     * arrives. A link alone does not grant drive.file access; Drive exports at most 10 MB.
     */
    suspend fun <T> export(id: String, mimeType: String, read: (java.io.InputStream) -> T): T = throw DriveNotConnectedException()

    /** A link that opens the file in Drive/Docs. */
    fun openUrl(file: DriveFile): String = file.webViewLink ?: "https://drive.google.com/open?id=${file.id}"

    /**
     * Lets anyone with the link view [file] (one the app made: drive.file allows it) and returns the link, to send
     * instead of the file itself (owner, 2026-11-09: "send full video or google drive link?").
     */
    suspend fun shareLink(file: DriveFile): String = throw DriveNotConnectedException()

    companion object {
        const val GOOGLE_DOC = "application/vnd.google-apps.document"
        const val FOLDER = "application/vnd.google-apps.folder"
        val ROOT = listOf("BoringTravel")
        val EXPORTS = listOf("BoringTravel", "Exports")
        val PHOTOS = listOf("BoringTravel", "Photos")
        val BACKUPS = listOf("BoringTravel", "Backups")

        /** Saved trip videos, each once (backup/BackupVideos). */
        val VIDEOS = listOf("BoringTravel", "Videos")

        /** The video editor's own files (voice lines, phone videos placed in a trip video), each once. */
        val EDITOR_FILES = listOf("BoringTravel", "Videos", "Editor files")
    }
}

data class DriveFile(val id: String, val name: String, val mimeType: String, val webViewLink: String? = null, val modifiedTime: String? = null, val size: Long? = null)

class DriveNotConnectedException : IllegalStateException("Google Drive isn't connected. Connect it in Me › Backup & sync.")
