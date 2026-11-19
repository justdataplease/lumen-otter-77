import ActivityKit
import Foundation
import BoringTravelApp

/// The recording route's Live Activity, started, brought up to date and ended as Kotlin's route tracking says
/// (composeApp's iosMain, IosRecordingCard.kt: `NativeRecordingActivity`; the words are Kotlin's). Handed over at the
/// start (BoringTravelApp.swift). A Live Activity can only be started while the app is on screen: the recording starts
/// there, and every return to the screen shows it again if iOS ended it (after eight hours). Needs iOS 16.1 and Live
/// Activities allowed for the app (Settings); without them nothing shows, as Android shows no notification when
/// notifications are off.
final class RecordingActivityBridge: NSObject, NativeRecordingActivity {
    func showRecordingActivity(title: String, trip: String) {
        guard #available(iOS 16.1, *) else { return }
        RecordingActivities.show(RecordingAttributes.ContentState(title: title, trip: trip))
    }

    func endRecordingActivity() {
        guard #available(iOS 16.1, *) else { return }
        RecordingActivities.end()
    }
}

@available(iOS 16.1, *)
enum RecordingActivities {
    static func show(_ state: RecordingAttributes.ContentState) {
        if let running = Activity<RecordingAttributes>.activities.first(where: { $0.activityState == .active }) {
            Task {
                if #available(iOS 16.2, *) {
                    await running.update(ActivityContent(state: state, staleDate: nil))
                } else {
                    await running.update(using: state)
                }
            }
            return
        }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        do {
            if #available(iOS 16.2, *) {
                _ = try Activity.request(attributes: RecordingAttributes(), content: ActivityContent(state: state, staleDate: nil), pushType: nil)
            } else {
                _ = try Activity.request(attributes: RecordingAttributes(), contentState: state, pushType: nil)
            }
        } catch {
            NSLog("BT-DIAG recording card not started: %@", String(describing: error))
        }
    }

    static func end() {
        for activity in Activity<RecordingAttributes>.activities {
            Task {
                if #available(iOS 16.2, *) {
                    await activity.end(nil, dismissalPolicy: .immediate)
                } else {
                    await activity.end(dismissalPolicy: .immediate)
                }
            }
        }
    }
}
