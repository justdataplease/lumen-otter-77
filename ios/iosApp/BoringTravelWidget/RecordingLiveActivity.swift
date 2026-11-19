import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// The recording route's Live Activity on the Lock Screen and in the Dynamic Island (the second parity round), drawn
/// as Android's ongoing notification reads: the plane and "BoringTravel", "Recording your route · Balanced", the trip,
/// and Stop (iOS 17: StopRecordingIntent; on iOS 16.1 to 16.x a tap opens Me › Route tracking, where Stop for today
/// is). Roboto, as the widgets' words and Android's system face. The desktop draws the same layout from the same words
/// (composeApp's RecordingCardPicture) for review; this SwiftUI drawing is seen only on a Mac.
@available(iOS 16.1, *)
struct RecordingLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RecordingAttributes.self) { context in
            RecordingCardView(state: context.state)
                .widgetURL(RecordingLinks.tap)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: "airplane").font(.system(size: 18, weight: .semibold))
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.state.title).font(cardFont(14, .medium)).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 8) {
                        Text(context.state.trip).font(cardFont(13)).foregroundStyle(.secondary).lineLimit(1)
                        Spacer(minLength: 8)
                        RecordingStopButton()
                    }
                }
            } compactLeading: {
                Image(systemName: "airplane")
            } compactTrailing: {
                Image(systemName: "location.fill").foregroundStyle(.blue)
            } minimal: {
                Image(systemName: "airplane")
            }
            .widgetURL(RecordingLinks.tap)
        }
    }
}

/// On the Lock Screen: the app's line, the title, the trip and Stop.
@available(iOS 16.1, *)
struct RecordingCardView: SwiftUI.View {
    let state: RecordingAttributes.ContentState

    var body: some SwiftUI.View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Image(systemName: "airplane").font(.system(size: 12, weight: .semibold))
                    Text("BoringTravel").font(cardFont(12)).foregroundStyle(.secondary)
                }
                Text(state.title).font(cardFont(15, .medium)).lineLimit(1)
                if !state.trip.isEmpty {
                    Text(state.trip).font(cardFont(14)).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            RecordingStopButton()
        }
        .padding(16)
    }
}

/// Stop, where a Live Activity can have a button (iOS 17); nothing before (the card's tap opens Route tracking then).
@available(iOS 16.1, *)
struct RecordingStopButton: SwiftUI.View {
    var body: some SwiftUI.View {
        if #available(iOS 17.0, *) {
            Button(intent: StopRecordingIntent()) {
                Text("Stop").font(cardFont(15, .medium))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Capsule().fill(Color.primary.opacity(0.14)))
            }
            .buttonStyle(.plain)
        }
    }
}

enum RecordingLinks {
    /// A tap on the card: on iOS 16 Me › Route tracking (where Stop for today is); on iOS 17 the app as it is, as
    /// Android's notification opens it (the card has its own Stop there).
    static var tap: URL? {
        if #available(iOS 17.0, *) { return nil }
        return URL(string: "boringtravel://tracking")
    }
}

/// Roboto at [size] (the widget extension bundles it), as the widgets' words.
private func cardFont(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
    weight == .medium ? .custom("Roboto-Medium", size: size) : .custom("Roboto-Regular", size: size)
}
