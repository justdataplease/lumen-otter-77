package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * Android's BackHandler (androidx.activity.compose): [onBack] runs on the system's Back while [enabled], the one
 * composed last winning, as Android's OnBackPressedDispatcher picks the newest callback. On iPhone the Back is the swipe
 * from the screen's left edge, which Compose Multiplatform turns into a navigation event (UIKit's screen-edge gesture,
 * the same that pops a UINavigationController); Compose's own dialogs, sheets and menus close on it first, as Android's
 * do. The desktop's Back is the Escape key; the tests send one through the navigation event dispatcher.
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val back by rememberUpdatedState(onBack)
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(state, isBackEnabled = enabled, onBackCompleted = { back() })
}
