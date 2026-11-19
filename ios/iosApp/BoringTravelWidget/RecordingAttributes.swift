import ActivityKit
import AppIntents
#if BORINGTRAVEL_APP
import BoringTravelApp
#endif

/// The recording route's Live Activity (the second parity round; Android: the ongoing "Recording your route · Balanced"
/// notification with the trip and Stop). Its words are the app's (Kotlin's TrackingNotes.recording: the title and the
/// trip), handed over by RecordingActivityBridge.swift; the widget extension draws it (RecordingLiveActivity.swift).
/// This file is in both targets: the app starts, updates and ends the activity, the extension draws it and its Stop.
@available(iOS 16.1, *)
struct RecordingAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// "Recording your route · Balanced".
        var title: String
        /// The trip's name; empty when there is none.
        var trip: String
    }
}

/// The Live Activity's Stop (iOS 17): Android's notification's Stop. A Live Activity's intent runs in the app, which
/// hands it to Kotlin (Stop for today: nothing records for today's trip until tomorrow or Start recording); the
/// extension only names it (the BORINGTRAVEL_APP condition is set for the app's target only).
@available(iOS 17.0, *)
struct StopRecordingIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Stop"
    static let isDiscoverable: Bool = false

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        #if BORINGTRAVEL_APP
        IosAppKt.stopRouteForToday()
        #endif
        return .result()
    }
}
