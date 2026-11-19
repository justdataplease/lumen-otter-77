package com.justdataplease.boringtravel.compat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.ios.MemorySettingsFile

/*
 * The few Android view names the studio's frame (ui/studio/Studio.kt, read in at build time) reaches for to make the
 * status bar's icons light while an editor shows. The window's insets controller here is [SystemBars], which the
 * phone applies (iPhone: MainViewController), so the studio's own Android code turns the iPhone's status bar light and
 * back, as on Android; the app sets the theme's look under it (App.kt, as MainActivity's SystemBars).
 */

/**
 * The status bar's look, as Android's window insets controller keeps it: [lightStatusBars] is Android's
 * isAppearanceLightStatusBars (dark icons for a light page; false: light icons, Trail and the dark studios). [apply] is
 * the phone's (iPhone: the window's light or dark interface style, which the status bar's default style follows).
 */
object SystemBars {
    var lightStatusBars: Boolean by mutableStateOf(true)
        private set

    /** The phone's side, told each change. */
    var apply: ((light: Boolean) -> Unit)? = null

    fun set(light: Boolean) {
        lightStatusBars = light
        apply?.invoke(light)
    }
}

/** The view a composition draws in: its context is the app's one activity, whose window has the status bar. */
class View(val context: Context) {
    val isInEditMode: Boolean get() = false
    val parent: Any? get() = null
}

val LocalView = staticCompositionLocalOf { View(Activity(MemorySettingsFile())) }

/** The app's window: its insets controller is [SystemBars]. */
class Window

/** A dialog's window (none here). */
interface DialogWindowProvider {
    val window: Window
}

/** The bars' icons, light or dark ([SystemBars]; the navigation bar's is the home indicator's, which iOS colours itself). */
class WindowInsetsControllerCompat {
    var isAppearanceLightStatusBars: Boolean
        get() = SystemBars.lightStatusBars
        set(value) = SystemBars.set(value)

    var isAppearanceLightNavigationBars: Boolean = true
}

object WindowCompat {
    @Suppress("UNUSED_PARAMETER")
    fun getInsetsController(window: Window, view: View): WindowInsetsControllerCompat = WindowInsetsControllerCompat()
}

/** The app's one activity: its [window] has the status bar ([SystemBars]). */
class Activity(settings: com.justdataplease.boringtravel.ios.SettingsFile) : Context(settings) {
    val window: Window? get() = Window()
}

open class ContextWrapper(val baseContext: Context) : Context(baseContext.settings)
