import AppIntents
import SwiftUI
import UIKit
import WidgetKit

/// BoringTravel's home-screen widgets (Android: widget/TodayWidget.kt and QuickWidget.kt): "Today", "Quick bar" and
/// "Quick bar, small", drawn from the snapshot the app writes into the App Group's folder (composeApp, ios/Widgets.kt
/// decides every word, colour and layout rule; iosApp/BoringTravel/WidgetBridge.swift writes it). This links none of the
/// app's Kotlin. The snapshot holds today and the next days, so the day's line changes at midnight without the app.
@main
struct BoringTravelWidgets: WidgetBundle {
    var body: some Widget {
        TodayWidget()
        QuickBarWidget()
        QuickSmallWidget()
        // The recording route's Live Activity (the second parity round: Android's ongoing notification with Stop).
        if #available(iOS 16.1, *) {
            RecordingLiveActivity()
        }
    }
}

// MARK: - The widgets, with Android's names and descriptions (res/values/strings.xml)

struct TodayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Today", provider: DaysProvider()) { entry in
            TodayView(entry: entry)
        }
        .configurationDisplayName("Today")
        .description("Today's trip, and quick Write, Photo and Voice.")
        .supportedFamilies([.systemSmall, .systemMedium])
        .contentMarginsDisabled()
    }
}

struct QuickBarWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "QuickBar", provider: DaysProvider()) { entry in
            QuickBarView(entry: entry)
        }
        .configurationDisplayName("Quick bar")
        .description("A note or a photo in one tap, with today's trip.")
        .supportedFamilies([.systemMedium])
        .contentMarginsDisabled()
    }
}

struct QuickSmallWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "QuickSmall", provider: DaysProvider()) { entry in
            QuickSmallView(entry: entry)
        }
        .configurationDisplayName("Quick bar, small")
        .description("Note and Camera in one tap.")
        .supportedFamilies([.systemSmall])
        .contentMarginsDisabled()
    }
}

// MARK: - The snapshot (WidgetSnapshot in ios/Widgets.kt)

struct Snapshot: Decodable {
    let v: Int
    let look: String
    let colors: [String: String]
    let paper: String
    let dotStep: Double
    let edgeWidth: Double
    let noteTilt: Double
    let pill: Bool
    let quick: QuickWidths
    let days: [Day]
    let mark: String

    struct QuickWidths: Decodable {
        let context: Int
        let mark: Int
        let voice: Int
        let note: Int
        let noteMax: Int
    }

    struct Day: Decodable {
        let from: Int64
        let title: String
        let line: String
        let primary: String
        let secondary: String

        var start: Date { Date(timeIntervalSince1970: TimeInterval(from) / 1000) }
    }

    func color(_ name: String, _ fallback: String = "#FF000000") -> Color { Color(argb: colors[name] ?? fallback) }

    /// The gallery's preview (Android's widget_preview_day and widget_preview_trip), in Postcard Pop's colours.
    static let preview = Snapshot(
        v: 1, look: "pop",
        colors: ["surface": "#FFFFFFFF", "ink": "#FF1D1B3A", "muted": "#FF6B6880", "primary": "#FFFF5A3C", "onPrimary": "#FFFFFFFF",
                 "chip": "#FFFFF1DC", "paper": "#FFFFFFFF", "barInk": "#FF1D1B3A", "barMuted": "#FF6B6880", "note": "#FFFF5A3C",
                 "onNote": "#FFFFFFFF", "button": "#FFFFF1DC", "buttonEdge": "#FF1D1B3A", "onButton": "#FF1D1B3A"],
        paper: "plain", dotStep: 13, edgeWidth: 1.5, noteTilt: -3, pill: false,
        quick: QuickWidths(context: 252, mark: 310, voice: 358, note: 82, noteMax: 132),
        days: [Day(from: 0, title: "Argentina & Patagonia", line: "Day 14 of 31 · 2 written today",
                   primary: "Day 14 · El Chaltén", secondary: "Argentina & Patagonia")],
        mark: "mark.png")
}

struct DayEntry: TimelineEntry {
    let date: Date
    let snapshot: Snapshot?
    let day: Snapshot.Day?
    let mark: UIImage?
}

/// Reads the App Group's folder; nil before the app wrote anything, or without the App Group.
enum Stored {
    static var group: String? { Bundle.main.object(forInfoDictionaryKey: "BoringTravelAppGroup") as? String }

    static func load() -> (Snapshot, UIImage?)? {
        guard let group = group,
              let folder = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group),
              let data = try? Data(contentsOf: folder.appendingPathComponent("snapshot.json")),
              let snapshot = try? JSONDecoder().decode(Snapshot.self, from: data), snapshot.v == 1 else { return nil }
        let mark = UIImage(contentsOfFile: folder.appendingPathComponent((snapshot.mark as NSString).lastPathComponent).path)
        return (snapshot, mark)
    }
}

struct DaysProvider: TimelineProvider {
    func placeholder(in context: Context) -> DayEntry {
        DayEntry(date: Date(), snapshot: .preview, day: Snapshot.preview.days.first, mark: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (DayEntry) -> Void) {
        // The widget gallery shows Android's preview ("Day 14 · El Chaltén", its widget_preview_day and _trip), whatever
        // the app wrote; on the Home Screen, the app's own snapshot.
        if context.isPreview {
            completion(placeholder(in: context))
        } else {
            completion(entries().first ?? placeholder(in: context))
        }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<DayEntry>) -> Void) {
        // The app asks for a new timeline whenever what the widgets show changes (WidgetBridge: reloadAllTimelines); each
        // coming midnight is in this one, and the widgets read the snapshot again every hour (Android's
        // updatePeriodMillis of an hour).
        completion(Timeline(entries: entries(), policy: .after(Date().addingTimeInterval(DaysProvider.hour))))
    }

    /** How often the widgets read the app's snapshot again by themselves (Android's updatePeriodMillis, 3,600,000). */
    static let hour: TimeInterval = 3600

    /// Now with today's day, then one entry at each coming midnight.
    private func entries() -> [DayEntry] {
        let now = Date()
        guard let stored = Stored.load() else { return [DayEntry(date: now, snapshot: nil, day: nil, mark: nil)] }
        let (snapshot, mark) = stored
        let today = snapshot.days.last { $0.start <= now } ?? snapshot.days.first
        var entries = [DayEntry(date: now, snapshot: snapshot, day: today, mark: mark)]
        for day in snapshot.days where day.start > now {
            entries.append(DayEntry(date: day.start, snapshot: snapshot, day: day, mark: mark))
        }
        return entries
    }
}

// MARK: - Where the buttons lead (the app's links: ios/Widgets.kt Deeplink)

enum Links {
    static func url(_ action: String) -> URL { URL(string: "boringtravel://" + action)! }
}

/// A button: a link on the wide widgets; on a small one (a single tap target before iOS 17) the app's intent, so each
/// of its two buttons opens its own action.
struct ActionButton<Label: View>: View {
    let action: String
    let small: Bool
    @ViewBuilder let label: () -> Label

    var body: some View {
        if small {
            if #available(iOSApplicationExtension 17.0, *) {
                Button(intent: OpenActionIntent(action: action)) { label() }.buttonStyle(.plain)
            } else {
                label()
            }
        } else {
            Link(destination: Links.url(action)) { label() }
        }
    }
}

// MARK: - Today (Android: TodayWidget.Content)

struct TodayView: View {
    let entry: DayEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        let s = entry.snapshot ?? .preview
        let wide = family != .systemSmall
        let small = !wide
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                Stamp(image: entry.mark, size: 28)
                VStack(alignment: .leading, spacing: 1) {
                    Text(entry.day?.title ?? "Today")
                        .font(roboto(15, .bold))
                        .foregroundColor(s.color("ink"))
                        .lineLimit(1)
                    Text(entry.day?.line ?? "")
                        .font(roboto(12))
                        .foregroundColor(s.color("muted"))
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            HStack(spacing: 8) {
                ActionButton(action: "write", small: small) { pill("Write", s, primary: true) }
                if wide {
                    ActionButton(action: "camera", small: small) { pill("Photo", s, primary: false) }
                }
                ActionButton(action: "voice", small: small) { pill("Voice", s, primary: false) }
            }
        }
        .padding(14)
        .widgetURL(Links.url("write"))
        .widgetBackground(s.color("surface"))
    }

    private func pill(_ label: String, _ s: Snapshot, primary: Bool) -> some View {
        Text(label)
            .font(roboto(14, .medium))
            .foregroundColor(primary ? s.color("onPrimary") : s.color("ink"))
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity, minHeight: 40, maxHeight: 40)
            .background(Capsule().fill(primary ? s.color("primary") : s.color("chip")))
    }
}

// MARK: - Quick bar (Android: QuickWidget.QuickBar and QuickBarLayout)

struct QuickBarView: View {
    let entry: DayEntry

    var body: some View {
        let s = entry.snapshot ?? .preview
        GeometryReader { geo in
            // As the bar narrows, Voice goes first, then the stamp, then the text; Note and Camera stay (the widths
            // come from the app: QuickBarLayout).
            let width = Int(geo.size.width)
            let showText = width >= s.quick.context
            let showMark = width >= s.quick.mark
            let showVoice = width >= s.quick.voice
            HStack(spacing: 8) {
                if showMark {
                    Stamp(image: entry.mark, size: 32)
                        .padding(.trailing, 2)
                }
                if showText {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(entry.day?.primary ?? "")
                            .font(roboto(15, .medium))
                            .foregroundColor(s.color("barInk"))
                            .lineLimit(1)
                        Text(entry.day?.secondary ?? "")
                            .font(roboto(12))
                            .foregroundColor(s.color("barMuted"))
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                ActionButton(action: "write", small: false) { NotePill(s: s, width: CGFloat(s.quick.note)) }
                ActionButton(action: "camera", small: false) { RoundButton(s: s, symbol: "camera.fill") }
                if showVoice {
                    ActionButton(action: "voice", small: false) { RoundButton(s: s, symbol: "mic.fill") }
                }
            }
            .padding(.leading, showMark ? 10 : 14)
            .padding(.trailing, 12)
            .frame(width: geo.size.width, height: geo.size.height, alignment: showText ? .leading : .center)
        }
        .widgetURL(Links.url("today"))
        .widgetBackground(PaperView(s: s))
    }
}

/// "Quick bar, small": Note and Camera.
struct QuickSmallView: View {
    let entry: DayEntry

    var body: some View {
        let s = entry.snapshot ?? .preview
        GeometryReader { geo in
            // The Note pill takes the room beside Camera, at most Android's widest (QuickBarLayout.NOTE_MAX).
            let note = min(max(geo.size.width - 2 - 20 - 8 - 40, 40), CGFloat(s.quick.noteMax))
            HStack(spacing: 8) {
                ActionButton(action: "write", small: true) { NotePill(s: s, width: note) }
                ActionButton(action: "camera", small: true) { RoundButton(s: s, symbol: "camera.fill") }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .widgetURL(Links.url("write"))
        .widgetBackground(PaperView(s: s))
    }
}

/// The Note pill: the theme's active-tab colour leaning a little with small corners, or round ends in Trail.
struct NotePill: View {
    let s: Snapshot
    let width: CGFloat

    var body: some View {
        ZStack {
            if s.pill {
                Capsule().fill(s.color("note"))
            } else {
                RoundedRectangle(cornerRadius: 8).fill(s.color("note"))
                    .padding(.vertical, 2.5)
                    .padding(.horizontal, 1.5)
                    .rotationEffect(.degrees(s.noteTilt))
            }
            HStack(spacing: 6) {
                Image(systemName: "square.and.pencil").font(.system(size: 15, weight: .semibold))
                Text("Note").font(roboto(14, .medium)).lineLimit(1)
            }
            .foregroundColor(s.color("onNote"))
        }
        .frame(width: width, height: 40)
    }
}

/// A round button: the theme's button colour with its edge (Postcard Pop's ink, a hairline on paper).
struct RoundButton: View {
    let s: Snapshot
    let symbol: String

    var body: some View {
        ZStack {
            Circle().fill(s.color("button"))
            if let edge = s.colors["buttonEdge"] {
                Circle().strokeBorder(Color(argb: edge), lineWidth: CGFloat(s.edgeWidth))
            }
            Image(systemName: symbol).font(.system(size: 17, weight: .semibold)).foregroundColor(s.color("onButton"))
        }
        .frame(width: 40, height: 40)
    }
}

/// The plane on the theme's yellow, as the app drew it; a yellow square before it has.
struct Stamp: SwiftUI.View {
    let image: UIImage?
    let size: CGFloat

    var body: some SwiftUI.View {
        if let image = image {
            Image(uiImage: image).resizable().interpolation(.high).frame(width: size, height: size)
        } else {
            // The gallery's preview and a widget placed before the app drew its stamp: the mark in Postcard Pop's yellow,
            // as Android's preview layouts show it (ic_brand_plane).
            PlaneMarkView(size: size)
        }
    }
}

/// The brand mark as the app draws it (art/PlaneMark.kt, its numbers here): the rounded yellow tile, the navy dotted
/// trail, the plane's three faces edged in navy and the two pale rules, on the 108-unit canvas scaled to [size].
struct PlaneMarkView: SwiftUI.View {
    let size: CGFloat

    private static let wing: [CGFloat] = [82, 28, 50, 56, 55, 72]
    private static let sheet: [CGFloat] = [82, 28, 27, 48, 50, 56]
    private static let keel: [CGFloat] = [50, 56, 46, 66, 55, 72]
    private static let rules: [CGFloat] = [44, 46.5, 58, 41.5, 41, 50.5, 51, 46.9]
    private static let dots: [CGFloat] = [22.00, 84.00, 26.76, 81.18, 31.21, 77.90, 35.58, 74.49, 40.07, 71.27]

    /// A canvas coordinate in the visible square (PlaneMark.local), at this size.
    private func at(_ v: CGFloat) -> CGFloat { (54 + (v - 54) * 0.84 - 18) * size / 72 }

    private func face(_ p: [CGFloat]) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: at(p[0]), y: at(p[1])))
        path.addLine(to: CGPoint(x: at(p[2]), y: at(p[3])))
        path.addLine(to: CGPoint(x: at(p[4]), y: at(p[5])))
        path.closeSubpath()
        return path
    }

    var body: some SwiftUI.View {
        let k = size / 72
        let navy = Color(argb: "#FF1E2A5A")
        let edge = StrokeStyle(lineWidth: 2.4 * 0.84 * k, lineJoin: .round)
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.24).fill(Color(argb: "#FFFFC93C"))
            Path { path in
                let r = 1.5 * 0.84 * k
                for i in stride(from: 0, to: PlaneMarkView.dots.count, by: 2) {
                    path.addEllipse(in: CGRect(x: at(PlaneMarkView.dots[i]) - r, y: at(PlaneMarkView.dots[i + 1]) - r, width: 2 * r, height: 2 * r))
                }
            }.fill(navy)
            face(PlaneMarkView.wing).fill(Color(argb: "#FFDCE2EF"))
            face(PlaneMarkView.wing).stroke(navy, style: edge)
            face(PlaneMarkView.sheet).fill(Color(argb: "#FFFFFFFF"))
            face(PlaneMarkView.sheet).stroke(navy, style: edge)
            face(PlaneMarkView.keel).fill(navy)
            face(PlaneMarkView.keel).stroke(navy, style: edge)
            Path { path in
                for i in stride(from: 0, to: PlaneMarkView.rules.count, by: 4) {
                    path.move(to: CGPoint(x: at(PlaneMarkView.rules[i]), y: at(PlaneMarkView.rules[i + 1])))
                    path.addLine(to: CGPoint(x: at(PlaneMarkView.rules[i + 2]), y: at(PlaneMarkView.rules[i + 3])))
                }
            }.stroke(Color(argb: "#FFB9C6E4"), style: StrokeStyle(lineWidth: 1.3 * 0.84 * k, lineCap: .round))
        }
        .frame(width: size, height: size)
    }
}

/// The theme's paper with its dots, rules or squares (Android: WidgetArt.bar with PaperGrid), centred on the widget.
struct PaperView: View {
    let s: Snapshot

    var body: some View {
        ZStack {
            s.color("paper")
            if s.paper != "plain", let ink = s.colors["texture"] {
                PaperTexture(kind: s.paper, dotStep: s.dotStep).fill(Color(argb: ink))
            }
        }
    }
}

/// Where the dots or rules go: the Android app's PaperGrid (dots and rules centred, none closer than half a step to an edge).
struct PaperTexture: Shape {
    let kind: String
    let dotStep: Double

    func path(in rect: CGRect) -> Path {
        var path = Path()
        switch kind {
        case "dots":
            let r: CGFloat = dotStep < 13 ? 0.95 * 0.9 : 0.95
            for y in PaperTexture.dots(Double(rect.height), dotStep) {
                for x in PaperTexture.dots(Double(rect.width), dotStep) {
                    path.addEllipse(in: CGRect(x: rect.minX + CGFloat(x) - r, y: rect.minY + CGFloat(y) - r, width: 2 * r, height: 2 * r))
                }
            }
        case "lines":
            for y in PaperTexture.rules(Double(rect.height), 15) {
                path.addRect(CGRect(x: rect.minX, y: rect.minY + CGFloat(y.rounded()), width: rect.width, height: 0.75))
            }
        case "grid":
            for y in PaperTexture.rules(Double(rect.height), 12) {
                path.addRect(CGRect(x: rect.minX, y: rect.minY + CGFloat(y.rounded()), width: rect.width, height: 0.75))
            }
            for x in PaperTexture.rules(Double(rect.width), 12) {
                path.addRect(CGRect(x: rect.minX + CGFloat(x.rounded()), y: rect.minY, width: 0.75, height: rect.height))
            }
        default:
            break
        }
        return path
    }

    static func dots(_ length: Double, _ step: Double) -> [Double] {
        guard step > 0, length > 0 else { return [] }
        let n = max(1, Int((length / step).rounded(.down)))
        let first = (length - Double(n - 1) * step) / 2
        return (0..<n).map { first + Double($0) * step }
    }

    static func rules(_ length: Double, _ step: Double) -> [Double] {
        guard step > 0, length > step else { return [] }
        let n = Int((length / step).rounded(.down))
        let first = (length - Double(n - 1) * step) / 2
        return (0..<n).map { first + Double($0) * step }.filter { $0 >= step / 2 - 0.01 && $0 <= length - step / 2 + 0.01 }
    }
}

// MARK: - Helpers

extension Color {
    /// "#AARRGGBB", as the snapshot writes colours.
    init(argb: String) {
        let value = UInt32(argb.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0xFF000000
        self.init(.sRGB,
                  red: Double((value >> 16) & 0xFF) / 255,
                  green: Double((value >> 8) & 0xFF) / 255,
                  blue: Double(value & 0xFF) / 255,
                  opacity: Double((value >> 24) & 0xFF) / 255)
    }
}

extension View {
    /// The widget's background: iOS 17's container background, a plain background before it.
    @ViewBuilder
    func widgetBackground<Background: View>(_ background: Background) -> some View {
        if #available(iOSApplicationExtension 17.0, *) {
            containerBackground(for: .widget) { background }
        } else {
            self.background(background)
        }
    }
}

/// Roboto, the face Android's widgets draw their words in (Glance's text has no family: the system's, Roboto), from the
/// widget's own bundle (project.yml adds Google's files and lists them in UIAppFonts), at Android's sizes in sp, which
/// grow with the phone's text size as Android's do.
private func roboto(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
    if weight == .bold { return .custom("Roboto-Bold", size: size) }
    if weight == .medium { return .custom("Roboto-Medium", size: size) }
    return .custom("Roboto-Regular", size: size)
}
