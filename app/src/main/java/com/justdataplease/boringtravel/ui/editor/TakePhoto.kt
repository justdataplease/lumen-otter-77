package com.justdataplease.boringtravel.ui.editor

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.repo.PhotoTime
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.data.repo.now
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.util.TimeZone

/**
 * Photos from the system camera. The camera writes into the app's cache through its FileProvider (so no CAMERA
 * permission: the camera app takes the picture), then the shot is imported like a picked photo (EXIF time and
 * place) and the cache file is deleted.
 */
object CameraShots {
    private const val DIR = "camera"
    private const val STALE_MS = 24 * 60 * 60_000L

    private fun dir(context: Context) = File(context.cacheDir, DIR)

    /**
     * A new, empty target for the camera ([ext]: "mp4" for a video). Leftovers from a camera that never came back are
     * cleared first.
     */
    fun newTarget(context: Context, ext: String = "jpg"): Uri {
        val dir = dir(context).apply { mkdirs() }
        dir.listFiles()?.filter { now() - it.lastModified() > STALE_MS }?.forEach { it.delete() }
        val file = File(dir, "shot-${now()}.$ext")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private fun file(context: Context, uri: Uri): File? = uri.lastPathSegment?.let { File(dir(context), it) }

    fun hasPhoto(context: Context, uri: Uri): Boolean = (file(context, uri)?.length() ?: 0L) > 0L

    fun discard(context: Context, uri: Uri) {
        file(context, uri)?.delete()
    }
}

/** A photo the camera just took: where it is and when the camera was open. */
data class Shot(val uri: Uri, val launchedAt: Long, val returnedAt: Long) {
    companion object {
        /** Keeps a shot that waits to be added across the activity being recreated. */
        val Saver: Saver<Shot?, Any> = listSaver(
            save = { shot -> shot?.let { listOf(it.uri.toString(), it.launchedAt.toString(), it.returnedAt.toString()) } ?: emptyList() },
            restore = { l -> if (l.size == 3) Shot(Uri.parse(l[0]), l[1].toLong(), l[2].toLong()) else null },
        )
    }
}

/** Opens the camera; false when the phone has no camera app. */
fun interface CameraLauncher {
    fun launch(): Boolean
}

/**
 * The system camera ([ActivityResultContracts.TakePicture]) writing to a file of ours. [onShot] gets the photo, or
 * null when the camera was closed without one (its empty file is deleted). The target survives the app being
 * stopped while the camera is open.
 */
@Composable
fun rememberCameraShot(onShot: (Shot?) -> Unit): CameraLauncher {
    val context = LocalContext.current
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var launchedAt by rememberSaveable { mutableLongStateOf(0L) }
    val deliver by rememberUpdatedState(onShot)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = target?.toUri()
        target = null
        when {
            uri == null -> deliver(null)
            ok && CameraShots.hasPhoto(context, uri) -> deliver(Shot(uri, launchedAt, now()))
            else -> {
                CameraShots.discard(context, uri)
                deliver(null)
            }
        }
    }
    return remember(launcher) {
        CameraLauncher {
            val uri = CameraShots.newTarget(context)
            target = uri.toString()
            launchedAt = now()
            try {
                launcher.launch(uri)
                true
            } catch (_: ActivityNotFoundException) {
                CameraShots.discard(context, uri)
                target = null
                Toast.makeText(context, "No camera app on this phone.", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }
}

/** A new journal entry made from a camera shot. */
object PhotoEntries {
    /**
     * Imports [shot] and saves a new entry in [tripId] (or today's trip) with the photo as its only block, at the
     * photo's time (see [PhotoTime.forShot]) and its EXIF place if the camera wrote one. Returns the entry id.
     */
    suspend fun create(graph: AppGraph, shot: Shot, tripId: String?): String {
        val trip = tripId ?: graph.trips.currentOn(LocalDate.now())?.id
        val defaults = PhotoDefaults.forTrip(graph, trip)
        val id = newId()
        try {
            val media = graph.media.import(shot.uri, trip, id)
            val t = now()
            val entry = Entry(
                id = id, tripId = trip, occurredAt = PhotoTime.forShot(media.takenAt, shot.launchedAt, shot.returnedAt),
                zoneId = TimeZone.getDefault().id, lat = media.lat, lng = media.lng, createdAt = t, updatedAt = t,
            )
            val photo = Block.Photo(media.id, rotation = defaults.tiltFor(0), width = defaults.width, frame = defaults.frame)
            graph.journal.save(entry, EntryDoc(blocks = listOf(photo)))
        } finally {
            CameraShots.discard(graph.app, shot.uri)
        }
        return id
    }
}

/**
 * "Take a photo" from the widget, a launcher shortcut or the Now card: the camera straight away; a photo becomes a
 * new entry in the trip (or today's trip) and [onEntry] opens it in Write; a closed camera creates nothing and calls
 * [onCancel] with the launch's `leaveOnCancel` (the widget tap that opened the app goes back to the home screen).
 */
@Composable
fun rememberQuickCamera(
    graph: AppGraph,
    onEntry: (String) -> Unit,
    onCancel: (leave: Boolean) -> Unit,
): (tripId: String?, leaveOnCancel: Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tripId by rememberSaveable { mutableStateOf<String?>(null) }
    var leave by rememberSaveable { mutableStateOf(false) }
    val entry by rememberUpdatedState(onEntry)
    val cancel by rememberUpdatedState(onCancel)
    val camera = rememberCameraShot { shot ->
        if (shot == null) {
            cancel(leave)
            return@rememberCameraShot
        }
        val trip = tripId
        scope.launch {
            // Saved on the app's scope, so the photo is kept even if this screen goes away meanwhile.
            val id = runCatching { graph.scope.async { PhotoEntries.create(graph, shot, trip) }.await() }.getOrNull()
            if (id != null) {
                entry(id)
            } else {
                Toast.makeText(context, "The photo couldn't be added.", Toast.LENGTH_SHORT).show()
                cancel(leave)
            }
        }
    }
    return remember(camera) {
        { trip, leaveOnCancel ->
            tripId = trip
            leave = leaveOnCancel
            if (!camera.launch()) cancel(leaveOnCancel)
        }
    }
}
