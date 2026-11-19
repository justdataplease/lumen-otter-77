import AppIntents
#if BORINGTRAVEL_APP
import BoringTravelApp
#endif

/// A button on a small widget, where a widget is one tap target before iOS 17: it opens the app at its action (Write,
/// Camera, Voice). This file is in both the widget and the app: the system runs the intent in the app, which hands the
/// action to Kotlin (the BORINGTRAVEL_APP condition is set for the app's target only; the widget just names it).
@available(iOS 17.0, *)
struct OpenActionIntent: AppIntent {
    static let title: LocalizedStringResource = "Open BoringTravel"
    static let openAppWhenRun: Bool = true
    static let isDiscoverable: Bool = false

    @Parameter(title: "Action")
    var action: String

    init() {}

    init(action: String) {
        self.action = action
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        #if BORINGTRAVEL_APP
        _ = IosAppKt.openAppLink(url: "boringtravel://" + action)
        #endif
        return .result()
    }
}
