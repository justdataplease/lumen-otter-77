package com.justdataplease.boringtravel.ui.editor

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri

/**
 * The system camera recording a video (ACTION_VIDEO_CAPTURE) into [input], as long as the traveller wants (the whole
 * video is kept). Its result: null when it was closed without one, else the video it reports (or [Uri.EMPTY] when it
 * reports none: then it is in [input]). Some camera apps write the video into the gallery instead: the import reads it
 * from there.
 */
class RecordVideo : ActivityResultContract<Uri, Uri?>() {
    override fun createIntent(context: Context, input: Uri): Intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        .putExtra(MediaStore.EXTRA_OUTPUT, input)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data ?: Uri.EMPTY else null
}

/** A video the camera just recorded: in our own cache file ([own], deleted once it is in the note) or the camera's. */
data class Recording(val uri: Uri, val own: Boolean) {
    companion object {
        /** Keeps a recording that waits to be added across the activity being recreated. */
        val Saver: Saver<Recording?, Any> = listSaver(
            save = { r -> r?.let { listOf(it.uri.toString(), it.own.toString()) } ?: emptyList() },
            restore = { l -> if (l.size == 2) Recording(l[0].toUri(), l[1].toBoolean()) else null },
        )
    }
}

/**
 * "Record a video": the system camera writing to a file of ours (no CAMERA permission: the camera app records), like
 * [rememberCameraShot] for photos. [onVideo] gets the recording, or null when the camera was closed without one (its
 * empty file is deleted). The target survives the app being stopped while the camera is open.
 */
@Composable
fun rememberCameraVideo(onVideo: (Recording?) -> Unit): CameraLauncher {
    val context = LocalContext.current
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val deliver by rememberUpdatedState(onVideo)
    val launcher = rememberLauncherForActivityResult(RecordVideo()) { result ->
        val uri = target?.toUri()
        target = null
        when {
            uri == null -> deliver(null)
            result == null -> {
                CameraShots.discard(context, uri)
                deliver(null)
            }
            CameraShots.hasPhoto(context, uri) -> deliver(Recording(uri, own = true))
            result != Uri.EMPTY && result != uri -> {
                CameraShots.discard(context, uri)
                deliver(Recording(result, own = false))
            }
            else -> {
                CameraShots.discard(context, uri)
                deliver(null)
            }
        }
    }
    return remember(launcher) {
        CameraLauncher {
            val uri = CameraShots.newTarget(context, ext = "mp4")
            target = uri.toString()
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
