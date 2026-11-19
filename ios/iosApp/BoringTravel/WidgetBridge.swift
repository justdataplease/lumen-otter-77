import Foundation
import BoringTravelApp
import WidgetKit

/// The home-screen widgets' files, written where the widgets read them (Android: WidgetUpdates.updateAll). The app
/// (Kotlin, ios/Widgets.kt) decides what the widgets say and when; this writes the snapshot and the stamp into the App
/// Group's folder and asks WidgetKit to draw the widgets again. Handed over as `NativeWidget` (composeApp, iosMain,
/// IosTracking.kt).
///
/// Without the App Group (a build without signing, as the Simulator's CI build, or before the owner sets the group up)
/// there may be no folder: nothing is written and the widgets show their empty look.
final class WidgetBridge: NSObject, NativeWidget {
    /// The App Group the app and the widgets share: the APP_GROUP build setting, through the Info.plist.
    static var group: String? { Bundle.main.object(forInfoDictionaryKey: "BoringTravelAppGroup") as? String }

    func writeWidgetFiles(snapshot: String, mark: Data?) {
        guard let group = WidgetBridge.group,
              let folder = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else { return }
        do {
            if let mark = mark {
                try mark.write(to: folder.appendingPathComponent("mark.png"), options: .atomic)
            }
            try Data(snapshot.utf8).write(to: folder.appendingPathComponent("snapshot.json"), options: .atomic)
        } catch {
            return
        }
        WidgetCenter.shared.reloadAllTimelines()
    }
}
