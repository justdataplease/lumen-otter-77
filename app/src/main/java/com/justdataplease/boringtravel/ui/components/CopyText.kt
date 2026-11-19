package com.justdataplease.boringtravel.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics

/**
 * Puts [text] on the clipboard, named [label] (owner, 2026-11-12: "let me copy things when I long press like names
 * addresses"), with a haptic tick. Android 13 and later show their own "Copied" chip; older phones get a short notice.
 */
fun copyText(context: Context, haptics: HapticFeedback?, text: String, label: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/**
 * A long press copies [text] ([copyText]); a blank [text] leaves the element as it is. A tap still reaches the element
 * and whatever holds it (a card that opens a link), and a scroll still scrolls: the press is only taken once it has
 * lasted long enough, and then nothing under it opens on release. TalkBack offers it as the long-press action
 * "Copy [label]".
 */
fun Modifier.copyOnLongPress(text: String?, label: String): Modifier = composed {
    if (text.isNullOrBlank()) return@composed this
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val latest by rememberUpdatedState(text)
    this
        .semantics { onLongClick("Copy ${label.lowercase()}") { copyText(context, haptics, latest, label); true } }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                copyText(context, haptics, latest, label)
                held.consume()
                // The rest of this press is used up: a card under it must not open when the finger lifts.
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
}
