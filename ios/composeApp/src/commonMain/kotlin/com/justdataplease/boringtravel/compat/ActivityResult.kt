package com.justdataplease.boringtravel.compat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.justdataplease.boringtravel.ios.ClipDevice
import com.justdataplease.boringtravel.ios.LocalClipDevice
import com.justdataplease.boringtravel.ios.LocalMediaKit
import com.justdataplease.boringtravel.ios.MediaPicker
import com.justdataplease.boringtravel.ios.MicAccess
import com.justdataplease.boringtravel.ios.PickedMedia

/*
 * androidx.activity's result launchers as the trip video's editor uses them (ui/clip/ClipTools.kt's
 * rememberEditorMedia, read in at build time): the microphone's permission, asked at the moment a recording starts,
 * and the photo picker for one video. Here they go to the phone's own: the microphone's question (iPhone:
 * AVAudioSession's recordPermission) and PHPicker (videos only), through [LocalClipDevice] and [LocalMediaKit]. Their
 * answers come back on the main thread, as Android's do.
 */

/** What a launcher asks the phone, with input [I] and answer [O]. */
abstract class ActivityResultContract<I, O> {
    internal abstract fun ask(input: I, device: ClipDevice, picker: MediaPicker, done: (O) -> Unit)
}

object ActivityResultContracts {
    /** A permission by its Android name; true when the app has it after asking. */
    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun ask(input: String, device: ClipDevice, picker: MediaPicker, done: (Boolean) -> Unit) {
            if (input != Manifest.permission.RECORD_AUDIO) return done(false)
            val mic = device.microphone
            when (mic.access) {
                MicAccess.Allowed -> done(true)
                MicAccess.Refused -> done(false)
                // The phone's own question, once: it never shows again after an answer (Settings changes it then).
                MicAccess.NotAsked -> mic.ask(done)
            }
        }
    }

    /** One photo or video from the phone's picker (Android's Photo Picker); null when the traveller backed out. */
    class PickVisualMedia : ActivityResultContract<PickVisualMediaRequest, PickedMedia?>() {
        sealed interface VisualMediaType
        data object ImageOnly : VisualMediaType
        data object VideoOnly : VisualMediaType
        data object ImageAndVideo : VisualMediaType

        override fun ask(input: PickVisualMediaRequest, device: ClipDevice, picker: MediaPicker, done: (PickedMedia?) -> Unit) {
            val one: (List<PickedMedia>) -> Unit = { done(it.firstOrNull()) }
            when (input.mediaType) {
                VideoOnly -> picker.pickVideos(1, one)
                ImageOnly -> picker.pickPhotos(1, one)
                ImageAndVideo -> picker.pickMedia(1, one)
            }
        }
    }
}

/** What the picker is asked for (Android: PickVisualMediaRequest(mediaType)). */
class PickVisualMediaRequest(val mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo)

/** Starts the contract's question; its answer goes to the callback given to [rememberLauncherForActivityResult]. */
class ManagedActivityResultLauncher<I, O> internal constructor(private val start: (I) -> Unit) {
    fun launch(input: I) = start(input)
}

/** androidx.activity's rememberLauncherForActivityResult: the launcher for [contract], [onResult] its latest callback. */
@Composable
fun <I, O> rememberLauncherForActivityResult(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): ManagedActivityResultLauncher<I, O> {
    val device = LocalClipDevice.current
    val picker = LocalMediaKit.current.picker
    val latest by rememberUpdatedState(onResult)
    return remember(contract::class, device, picker) {
        ManagedActivityResultLauncher { input -> contract.ask(input, device, picker) { latest(it) } }
    }
}
