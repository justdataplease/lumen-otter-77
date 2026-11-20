package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString

/**
 * What the screens ask of the phone besides photos: its clock and its week (the Android app reads both from the
 * phone's region), the share sheet and the clipboard. iPhone: [iosPhone] in iosMain (Foundation's region and
 * UIActivityViewController); the desktop and the tests keep a 24-hour clock, Monday first, no share sheet, and
 * Compose's own clipboard.
 */
class Phone(
    /** Times on a 24-hour clock in the clock pickers ("13:10"); false: AM and PM. */
    val uses24Hour: Boolean = true,
    /** The first day of a calendar week where the phone is: 1 = Monday … 7 = Sunday. */
    val firstDayOfWeek: Int = 1,
    /**
     * Hands [text] to the phone's share sheet with [subject] for the apps that take one (a mail's subject: Android's
     * EXTRA_SUBJECT; none when blank, as Android's place page sends none); false when it can't.
     */
    val share: (subject: String, text: String) -> Boolean = { _, _ -> false },
    /** Puts [text] on the clipboard; null: Compose's clipboard (UIPasteboard on iPhone). */
    val copy: ((text: String) -> Unit)? = null,
    /** The clipboard's text, read only on a tap (Paste a link); null: Compose's clipboard (UIPasteboard on iPhone). */
    val paste: (() -> String?)? = null,
    /**
     * Hands a file to the share sheet ([name] such as "boringtravel-world.png", its [mime] type and [bytes]) with
     * [subject] for the apps that take one (blank: none); false when it can't (the Android app's ACTION_SEND with a
     * FileProvider address, its EXTRA_SUBJECT the trip's title where Android gives one).
     */
    val shareFile: (name: String, mime: String, bytes: ByteArray, subject: String) -> Boolean = { _, _, _, _ -> false },
    /**
     * Adds a PNG picture to the phone's photo library (iPhone: Photos, add-only access, asked the first time); [done]
     * says whether it was kept (the Android app's MediaStore Pictures/BoringTravel).
     */
    val saveImage: (png: ByteArray, done: (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    /**
     * Hands the file at [path] under the app's files to the phone's save sheet (iPhone: the Files app's, to a folder
     * the traveller picks); [done] says whether it was saved (the Android app's "create document" picker).
     */
    val exportFile: (path: String, done: (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    /**
     * Lets the traveller pick a zip with the phone's file picker and copies it to [to] under the app's files; [done]
     * says whether one was picked (the Android app's "open document" picker).
     */
    val pickFile: (to: String, done: (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    /**
     * Opens [url] with the app that takes it (a mailto: link in the mail app); [done] says whether one did (iPhone:
     * UIApplication's openURL; the desktop and tests: none).
     */
    val openLink: (url: String, done: (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
)

/** Reads the clipboard's text on a tap ([Phone.paste], else Compose's clipboard); null when it holds none. */
@Composable
fun rememberPaste(): () -> String? {
    val phone = LocalPhone.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    return {
        val own = phone.paste
        if (own != null) own() else runCatching { clipboard.getText()?.text }.getOrNull()
    }
}

/** The phone the screens run on. */
val LocalPhone = staticCompositionLocalOf { Phone() }

/**
 * Copies a text as the Android app's copyText does (ui/components/CopyText.kt): onto the clipboard, with a haptic tick,
 * and "Copied" as Android's toast (round 28). Android 13 and later show the system's own "Copied" chip instead, older
 * Androids this toast; an iPhone shows nothing of its own when an app copies, so the toast says it.
 */
@Composable
fun rememberCopy(): (String) -> Unit {
    val copy = rememberCopyWith()
    val toast = LocalToast.current
    return { text ->
        copy(text)
        toast("Copied", false)
    }
}

/**
 * Copies a text onto the clipboard and nothing more, where the Android app writes to its ClipboardManager itself and says
 * what it says on its own (Share's Text › Copy: "Copied." on the screen; a report that couldn't be mailed).
 */
@Composable
fun rememberPlainCopy(): (String) -> Unit = rememberCopyWith()

/**
 * A long press copies [text] ([rememberCopy]); a blank [text] leaves the element as it is. A tap still reaches the
 * element and whatever holds it, and a scroll still scrolls: the press is only taken once it has lasted long enough,
 * and then nothing under it opens on release. A screen reader offers it as the long-press action "Copy [label]" (the
 * Android app's ui/components/CopyText.kt).
 */
fun Modifier.copyOnLongPress(text: String?, label: String): Modifier = composed {
    if (text.isNullOrBlank()) return@composed this
    val copy = rememberCopy()
    val latest by rememberUpdatedState(text)
    this
        .semantics { onLongClick("Copy ${label.lowercase()}") { copy(latest); true } }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                copy(latest)
                held.consume()
                // The rest of this press is used up: a card under it must not open when the finger lifts.
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
}

@Composable
private fun rememberCopyWith(): (String) -> Unit {
    val phone = LocalPhone.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    return { text ->
        val copy = phone.copy
        if (copy != null) copy(text) else runCatching { clipboard.setText(AnnotatedString(text)) }
        runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
}
