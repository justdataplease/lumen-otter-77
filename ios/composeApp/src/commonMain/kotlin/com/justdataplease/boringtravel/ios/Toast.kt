package com.justdataplease.boringtravel.ios

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.justdataplease.boringtravel.ui.theme.BtRoboto
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/*
 * Android's toasts (android.widget.Toast) on iPhone, which has none (round 28): the lines Android says in passing with a
 * toast ("Copied", "Added to the plan", "The photo couldn't be added.", a restore's result, the community's "Report sent.
 * Thank you.") show as a small dark rounded pill near the bottom of the screen, over whatever is there (the bar, a sheet),
 * in Android's words, for Android's times: 2 s for LENGTH_SHORT, 3.5 s for LENGTH_LONG, each in turn when several come
 * (Android queues an app's toasts, five at most). It is drawn the same in every theme (Android's toast is the system's,
 * not the app's), in Roboto, at most two lines, as Android 12's. What Android says with a snackbar (Demo mode off, a
 * friend joined, "Nothing to add: share a link", the plan's Moved with Undo) stays a snackbar ([LocalSay]).
 */

/** How long a toast shows, and how many may wait (Android's Toast.LENGTH_SHORT and LENGTH_LONG, its queue of five). */
object ToastTimes {
    const val SHORT_MS = 2_000L
    const val LONG_MS = 3_500L

    /** Fading in and out (Android's toast windows fade). */
    const val FADE_MS = 200

    /** At most this many wait their turn; more are dropped (Android's NotificationManagerService keeps five per app). */
    const val MAX_WAITING = 5
}

/** One toast: its words and whether it is a long one. */
data class ToastLine(val text: String, val long: Boolean = false) {
    val shownMs: Long get() = if (long) ToastTimes.LONG_MS else ToastTimes.SHORT_MS
}

/** The app's toasts, shown one at a time in the order they came ([show]; [run] shows them). */
class Toasts {
    private val waiting = Channel<ToastLine>(ToastTimes.MAX_WAITING, BufferOverflow.DROP_LATEST)

    /** The toast on screen now; null between toasts. */
    var showing by mutableStateOf<ToastLine?>(null)
        private set

    /** Android's Toast.makeText(context, text, length).show(): it shows once those before it have gone. */
    fun show(text: String, long: Boolean = false) {
        if (text.isBlank()) return
        waiting.trySend(ToastLine(text, long))
    }

    /** Shows each toast for its time, with the fade between two; runs while the app's frame is on screen. */
    suspend fun run() {
        for (line in waiting) {
            showing = line
            delay(line.shownMs)
            showing = null
            delay(ToastTimes.FADE_MS.toLong())
        }
    }
}

/**
 * Says a line as Android's Toast does ([Toasts.show]): (text, long). Nothing outside the app's frame (a screen drawn on
 * its own for a picture or a test keeps its own [ToastHost] when it shows toasts).
 */
val LocalToast = staticCompositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }

/** The toast's own colours: Android's toast is the system's, the same in every theme of the app. */
object ToastColors {
    val ground = Color(0xF0323232)
    val text = Color(0xFFFFFFFF)
}

/**
 * Where [toasts] show: a pill [bottom] above the screen's foot, centred, over everything the app shows (a popup of its
 * own, made when a toast comes, so a sheet or dialog already open stays under it, as Android's toast window is over the
 * app's). It takes no touches of its own.
 */
@Composable
fun ToastHost(toasts: Toasts, bottom: Dp) {
    LaunchedEffect(toasts) { toasts.run() }
    val now = toasts.showing
    // The last words stay drawn while they fade out.
    val words = remember { arrayOfNulls<String>(1) }
    if (now != null) words[0] = now.text
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = now != null
    val text = words[0]
    if (text == null || (!visible.currentState && !visible.targetState)) return
    val offset = with(LocalDensity.current) { bottom.roundToPx() }
    Popup(
        alignment = Alignment.BottomCenter,
        offset = IntOffset(0, -offset),
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        AnimatedVisibility(visible, enter = fadeIn(tween(ToastTimes.FADE_MS)), exit = fadeOut(tween(ToastTimes.FADE_MS))) {
            ToastPill(text)
        }
    }
}

/** The pill itself: Android's toast, dark, rounded, its words in Roboto at 14 sp, two lines at most. */
@Composable
fun ToastPill(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 360.dp)
            .shadow(6.dp, shape)
            .background(ToastColors.ground, shape)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = TextStyle(fontFamily = BtRoboto.family, fontSize = 14.sp, lineHeight = 20.sp, color = ToastColors.text, textAlign = TextAlign.Center),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
