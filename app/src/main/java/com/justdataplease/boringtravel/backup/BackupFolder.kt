package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.google.DriveNotConnectedException
import com.justdataplease.boringtravel.google.RestDriveApi
import kotlinx.coroutines.CancellationException

/**
 * Where Drive backups go: My Drive › BoringTravel › Backups by default, made at the first backup. The app may open the
 * folders it made (drive.file) wherever they are, so a folder the owner renamed or moved is found again by the id the
 * phone remembers ([RestDriveApi.findFolder]); on a new phone, through the newest backup in it (the hidden marker).
 * A deleted folder (or one in the trash) is made again at the default place by the next backup.
 */
internal object BackupFolder {
    /** What the backup screen shows. */
    const val DEFAULT_PLACE = "My Drive › BoringTravel › Backups"

    /** The Backups folder, or null when there is none yet. */
    suspend fun find(drive: RestDriveApi): String? {
        drive.findFolder(DriveApi.BACKUPS, create = false)?.let { return it }
        val marker = try {
            drive.readAppData(BackupNames.MARKER)?.let { BackupCodec.decodeMarker(it.toString(Charsets.UTF_8)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveNotConnectedException) {
            throw e
        } catch (_: Exception) {
            null // The marker is a shortcut; without it a new folder is made at the default place.
        } ?: return null
        val folder = drive.parentOf(marker.id) ?: return null
        drive.rememberFolder(DriveApi.BACKUPS, folder)
        return folder
    }

    /** The folder in the Drive app (or the browser). */
    fun url(id: String): String = "https://drive.google.com/drive/folders/$id"
}
