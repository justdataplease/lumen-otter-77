package com.justdataplease.boringtravel.google

/**
 * The owner's Google Drive, as the rest of the app sees it (the Android app's google/DriveApi.kt, its files under the
 * app's own files here instead of java.io's): only the `drive.file` scope (files this app created) and `drive.appdata`
 * (a hidden app folder) are ever used, both non-sensitive. [RestDriveApi] implements it over Drive's REST API; the
 * trip video, the book, Share's Google Docs, Copy from a link and the backups only call this interface.
 */
interface DriveApi {
    /** True when the owner granted Drive access on this phone. */
    suspend fun isConnected(): Boolean

    /** Finds or creates a folder path under My Drive, e.g. ["BoringTravel", "Exports"]; returns its id. */
    suspend fun folder(path: List<String>): String

    /**
     * Uploads the file at [path] (under the app's files) into [folderId] as [name]. With [convertTo] (e.g. [GOOGLE_DOC])
     * Drive converts it on upload, so an HTML file becomes an editable Google Doc.
     */
    suspend fun upload(path: String, name: String, mime: String, folderId: String?, convertTo: String? = null): DriveFile

    /** Plain text of an app-authorized Google Doc. A link alone does not grant drive.file access. */
    suspend fun exportText(id: String, maxBytes: Int): ByteArray = throw DriveNotConnectedException()

    /**
     * An app-authorized Google Doc exported as [mimeType] ("text/html" for Copy from a link), at most [maxBytes] of it
     * (Drive exports at most 10 MB). A link alone does not grant drive.file access.
     */
    suspend fun export(id: String, mimeType: String, maxBytes: Long = EXPORT_LIMIT): ByteArray = throw DriveNotConnectedException()

    /** A link that opens the file in Drive or Docs. */
    fun openUrl(file: DriveFile): String = file.webViewLink ?: "https://drive.google.com/open?id=${file.id}"

    /**
     * Lets anyone with the link view [file] (one the app made: drive.file allows it) and returns the link, to send
     * instead of the file itself (the Android owner's "send full video or google drive link?").
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

        /** What Drive exports of a Google Doc at most. */
        const val EXPORT_LIMIT = 10L * 1024 * 1024
    }
}

data class DriveFile(val id: String, val name: String, val mimeType: String, val webViewLink: String? = null, val modifiedTime: String? = null, val size: Long? = null)

/** Drive isn't connected (or Google wants the owner to confirm it again): the Android app's words. */
class DriveNotConnectedException : IllegalStateException("Google Drive isn't connected. Connect it in Me › Backup & sync.")
