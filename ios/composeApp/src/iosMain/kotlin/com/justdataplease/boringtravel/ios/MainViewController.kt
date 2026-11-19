package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.justdataplease.boringtravel.compat.SystemBars
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController

/**
 * The whole app as one view controller; the Swift entry point (iosApp) puts it on screen. [maps] makes the MapLibre
 * views (Swift, MapLibreMap.swift); null: the maps say "Map not available". [snapshots] takes the trip video's map
 * pictures (Swift, MapLibreSnapshots); null: the video has no street map (as offline). The trips, the settings, route
 * tracking, the widget's snapshot and the links from outside are the run's own ([IosApp], made before the first screen).
 * [restored] is where the app was when iOS closed it in the background, which [keeper] keeps with the window (SwiftUI's
 * scene storage) after every change ([AppRestore]). The status bar follows [SystemBars] as Android's window does: the
 * theme's (light icons on Trail) and the dark studios' (light icons).
 */
fun MainViewController(maps: NativeMaps?, snapshots: NativeMapSnapshots?, restored: String?, keeper: ScreensKeeper?): UIViewController {
    val screen = ComposeUIViewController {
        val mapKit = remember { maps?.let { IosMapKit(it) } }
        val mapSnapshots = remember { IosMapSnapshots(snapshots, IosApp.web.net) }
        val media = remember { iosMediaKit() }
        val files = media.files as IosMediaFiles
        CompositionLocalProvider(
            LocalPhone provides remember { iosPhone(files) }, LocalMapKit provides mapKit, LocalSpeech provides remember { IosSpeech() },
            LocalPagePrinter provides remember { IosPagePrinter() },
            // A long Copy from a link asks for time to finish when the app leaves the screen.
            LocalBackgroundWork provides remember { IosBackgroundWork() },
            LocalMapSnapshots provides mapSnapshots,
            // The trip video's editor: the bundled music, a voice line's microphone and the phone videos' pictures.
            LocalClipDevice provides remember { iosClipDevice(files) },
            // Sign in with Apple's own button (round 23).
            LocalAppleButton provides IosAppleButton,
        ) {
            // The AI planner: Me › AI's keys in the Keychain, this build's Firebase ids; no App Check token yet (it needs the
            // owner's Firebase iPhone app with App Attest and Firebase's App Check SDK, which Swift would hand over).
            val ai = remember { PlannerPhone.ofBuild(IosKeychain(), bundleId = NSBundle.mainBundle.bundleIdentifier) }
            App(
                IosApp.tripFile, IosApp.settingsFile, IosApp.version, media, web = { IosApp.web }, calendar = remember { IosCalendar() }, ai = ai,
                core = IosApp.core, phone = remember { IosApp.phone },
                restored = restored, keep = keeper?.let { k -> { text: String -> k.keepScreens(text) } },
                // The account, Drive and the backups (round 23): the run's own, which the daily backup's task shares.
                google = IosApp.google, backups = IosApp.backups, driveBackups = IosApp.driveBackups,
            )
        }
    }
    SystemBars.apply = { light -> applyStatusBar(screen, light) }
    // The window exists only once the screen shows: the look goes on it then, and on every return to the app.
    NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
        applyStatusBar(screen, SystemBars.lightStatusBars)
    }
    return screen
}

/**
 * The status bar's icons, light or dark: the window takes the light or dark interface style, which the status bar's
 * default style follows (SwiftUI's root controller asks no child for one), and the controllers are asked again.
 */
private fun applyStatusBar(screen: UIViewController, light: Boolean) {
    val window = screen.view.window ?: return
    window.overrideUserInterfaceStyle = if (light) UIUserInterfaceStyle.UIUserInterfaceStyleLight else UIUserInterfaceStyle.UIUserInterfaceStyleDark
    window.rootViewController?.setNeedsStatusBarAppearanceUpdate()
    screen.setNeedsStatusBarAppearanceUpdate()
}

/**
 * Swift's keeper of where the app is (SwiftUI's @SceneStorage, iosApp's BoringTravelApp.swift): iOS keeps the text with
 * the window and hands it back when it opens the app again after closing it in the background.
 */
interface ScreensKeeper {
    fun keepScreens(state: String)
}
