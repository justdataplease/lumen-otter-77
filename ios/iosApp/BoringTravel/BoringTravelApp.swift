import SwiftUI
import UIKit
import BoringTravelApp

/// The iPhone app's entry point. Every screen is drawn by the Kotlin framework (composeApp); Swift holds only what
/// Kotlin can't reach from Windows: this entry point, the maps (MapLibre, MapLibreMap.swift), the home-screen widgets'
/// files (WidgetBridge.swift; the widgets themselves are the BoringTravelWidget extension), the recording route's Live
/// Activity (RecordingActivityBridge.swift: ActivityKit is Swift only) and the Home Screen quick actions (below);
/// purchases (StoreKit) later.
@main
struct BoringTravelIosApp: App {
    @UIApplicationDelegateAdaptor(QuickActionsDelegate.self) private var quickActions

    init() {
        // Route tracking, its notes and the nightly run are ready before the app has finished starting: iOS also
        // starts the app without a screen for them. (A Kotlin function outside a class is named after its file:
        // IosApp.kt gives IosAppKt.)
        IosAppKt.startBoringTravel(widget: WidgetBridge(), recording: RecordingActivityBridge(), continued: ContinuedWorkBridge())
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

/// Where the app is (its screens, the trip's tab and day, the note being written), kept by iOS with the window: it
/// comes back after the phone closed the app in the background, and is gone after the traveller closes it themselves,
/// as Android's saved state. (SwiftUI.View, not View: the Kotlin framework has a class named View, Android's, in compat.)
private struct RootView: SwiftUI.View {
    @SceneStorage("boringtravel.screens") private var screens = ""

    var body: some SwiftUI.View {
        // Edge to edge: the screens keep clear of the Dynamic Island and the home indicator themselves.
        ComposeScreen(screens: $screens).ignoresSafeArea()
            // A widget's button (boringtravel://write, camera, voice, today), and since round 24 a community post's link:
            // the app's own (boringtravel://post/#<id>) or the web one as a universal link
            // (https://justdataplease.com/apps/boringtravel/post/#<id>). Kotlin reads both (Deeplink.of).
            .onOpenURL { url in _ = IosAppKt.openAppLink(url: url.absoluteString) }
            // A universal link handed over as a browsing activity (iOS may use either way); a post already open is
            // not opened twice.
            .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                if let url = activity.webpageURL { _ = IosAppKt.openAppLink(url: url.absoluteString) }
            }
    }
}

private struct ComposeScreen: UIViewControllerRepresentable {
    @Binding var screens: String

    // Self.Context, not Context: the Kotlin framework has a class named Context (Android's, in compat), and Swift
    // takes that one here, so the type no longer fits UIViewControllerRepresentable (the Mac build of 19 Nov 2026).
    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController(
            maps: MapLibreMaps(), snapshots: MapLibreSnapshots(),
            restored: screens.isEmpty ? nil : screens, keeper: ScreensBinding(binding: $screens)
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

/// Hands Kotlin's text for where the app is to the scene storage (Kotlin's ScreensKeeper).
private final class ScreensBinding: NSObject, ScreensKeeper {
    private let binding: Binding<String>

    init(binding: Binding<String>) {
        self.binding = binding
    }

    func keepScreens(state: String) {
        binding.wrappedValue = state
    }
}

/// The Home Screen quick actions (the Info.plist's UIApplicationShortcutItems: Write, Take a photo, Voice note, New
/// trip; Android's launcher shortcuts). Each is the app's own link for the action, handed to Kotlin, which opens it once
/// the screens are ready. A cold start brings the action with the scene; a running app gets it through the scene's
/// delegate.
final class QuickActionsDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        if let item = options.shortcutItem {
            QuickActions.open(item)
        }
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = QuickActionsScene.self
        return configuration
    }
}

final class QuickActionsScene: NSObject, UIWindowSceneDelegate {
    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        completionHandler(QuickActions.open(shortcutItem))
    }
}

enum QuickActions {
    /// The item's type is the action's name ("write", "camera", "voice", "new_trip").
    @discardableResult
    static func open(_ item: UIApplicationShortcutItem) -> Bool {
        IosAppKt.openAppLink(url: "boringtravel://" + item.type)
    }
}
