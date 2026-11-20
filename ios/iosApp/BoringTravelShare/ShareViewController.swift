import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// BoringTravel in other apps' share sheets (round 27; Android: MainActivity's ACTION_SEND filter for text and its
/// ACTION_SEND / ACTION_SEND_MULTIPLE filter for 1–10 pictures, "Plan from picture"): text, a link, 1–10 pictures, a file.
///
/// Apple gives a share extension no way to open its app (NSExtensionContext.open is for Today and iMessage extensions),
/// and the app's screens (Kotlin and Compose) can't run in an extension's memory. So this only writes what was shared
/// into the App Group's folder, as it came: the text and its links, the shared item's title, the pictures as files (copied,
/// never decoded) and at most 120,005 bytes of a file; then it says so and closes. The app takes it the next time it is on
/// screen and decides there, with Android's own rules, where it goes: Add to plan, the planner, Join, or "Nothing to add:
/// share a link" (composeApp: ios/ShareIn.kt). No Kotlin here, and no required-reason API (no UserDefaults, no file
/// dates: a leftover folder's age is read from its own name).
final class ShareViewController: UIViewController {
    private let state = ShareState()

    override init(nibName: String?, bundle: Bundle?) {
        super.init(nibName: nibName, bundle: bundle)
        // Over the other app, with the card at the bottom (Android's sheets); where iOS keeps its own sheet instead, the
        // same card sits at the bottom of that sheet.
        modalPresentationStyle = .overFullScreen
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        modalPresentationStyle = .overFullScreen
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        let card = UIHostingController(rootView: ShareCard(state: state, look: ShareLook.load()) { [weak self] in self?.close() })
        card.view.backgroundColor = .clear
        addChild(card)
        card.view.frame = view.bounds
        card.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(card.view)
        card.didMove(toParent: self)

        let items = (extensionContext?.inputItems ?? []).compactMap { $0 as? NSExtensionItem }
        let state = self.state
        Task {
            let kept = await ShareWriter.keep(items)
            state.phase = kept ? .sent : .failed
        }
    }

    private func close() {
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }
}

// MARK: - Writing the share into the App Group's folder (read by ShareInbox in composeApp's ios/ShareIn.kt)

/// One share, written as `Shares/<ms>-<uuid>.json` (the last thing written, atomically: the app takes only a share whose
/// file is there) with its files in `Shares/<ms>-<uuid>/`: `1.heic`, `2.jpg`… and `document.txt`.
enum ShareWriter {
    static let folderName = "Shares"

    /// Android's limit (PictureReading.MAX_PICTURES); the share sheet offers the extension for 10 at most.
    static let maxPictures = 10

    /// One byte past Android's limit for a document (ItineraryImport.MAX_BYTES), so the app can still say it is too long.
    static let fileBytes = 30_000 * 4 + 4 + 1

    /// Text is cut by the app to Android's 30,001 characters; this keeps a very long text from filling memory first.
    static let maxTextCharacters = 60_001

    /// A folder left without its share file (the extension stopped half way) goes after an hour.
    static let leftoverMs: Int64 = 60 * 60 * 1000

    static func groupFolder() -> URL? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "BoringTravelAppGroup") as? String,
              let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else { return nil }
        return container.appendingPathComponent(folderName, isDirectory: true)
    }

    /// Writes what [items] hold; false when nothing could be kept (no App Group, nothing readable, a full disk).
    static func keep(_ items: [NSExtensionItem]) async -> Bool {
        guard let shares = groupFolder() else { return false }
        let manager = FileManager.default
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        sweep(shares, now: now)
        let name = String(format: "%013lld", now) + "-" + UUID().uuidString
        let folder = shares.appendingPathComponent(name, isDirectory: true)
        do {
            try manager.createDirectory(at: folder, withIntermediateDirectories: true)
        } catch {
            return false
        }

        var texts: [String] = []
        var links: [String] = []
        var pictures: [String] = []
        var file: String?
        var subject: String?
        var spoken: String?
        for item in items {
            if subject == nil, let title = item.attributedTitle?.string.trimmingCharacters(in: .whitespacesAndNewlines), !title.isEmpty {
                subject = title
            }
            if spoken == nil, let words = item.attributedContentText?.string.trimmingCharacters(in: .whitespacesAndNewlines), !words.isEmpty {
                spoken = words
            }
            for provider in item.attachments ?? [] {
                switch await piece(of: provider, into: folder, picture: pictures.count + 1, fileTaken: file != nil) {
                case .text(let words): texts.append(words)
                case .link(let link): links.append(link)
                case .picture(let name): pictures.append(name)
                case .file(let name): file = name
                case .nothing: break
                }
            }
        }
        // The words some apps give the item itself instead of an attachment (a page's title, a comment).
        if texts.isEmpty, let spoken = spoken { texts.append(spoken) }
        // The text, then each link it doesn't hold already, one a line (as the Maps app shares "name, address, link").
        var text = texts.joined(separator: "\n")
        for link in links where !text.contains(link) {
            text += (text.isEmpty ? "" : "\n") + link
        }
        if text.count > maxTextCharacters { text = String(text.prefix(maxTextCharacters)) }

        guard !text.isEmpty || !pictures.isEmpty || file != nil else {
            try? manager.removeItem(at: folder)
            return false
        }
        var manifest: [String: Any] = ["v": 1]
        if !text.isEmpty { manifest["text"] = text }
        if let subject = subject { manifest["subject"] = subject }
        if !pictures.isEmpty { manifest["pictures"] = pictures }
        if let file = file { manifest["file"] = file }
        do {
            let data = try JSONSerialization.data(withJSONObject: manifest)
            try data.write(to: shares.appendingPathComponent(name + ".json"), options: .atomic)
            return true
        } catch {
            try? manager.removeItem(at: folder)
            return false
        }
    }

    private enum Piece {
        case text(String)
        case link(String)
        case picture(String)
        case file(String)
        case nothing
    }

    /// What one attachment is: a picture (copied as a file), a web link, text, or a file (its first [fileBytes]).
    private static func piece(of provider: NSItemProvider, into folder: URL, picture number: Int, fileTaken: Bool) async -> Piece {
        if let type = imageType(provider) {
            guard number <= maxPictures else { return .nothing }
            return await copyPicture(provider, type: type, into: folder, number: number).map(Piece.picture) ?? .nothing
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier),
           !provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            switch await loadItem(provider, type: UTType.url.identifier) {
            case let url as URL:
                if !url.isFileURL { return .link(url.absoluteString) }
                return fileTaken ? .nothing : (copyDocument(url, into: folder).map(Piece.file) ?? .nothing)
            case let words as String:
                return .link(words)
            case let data as Data:
                return String(data: data, encoding: .utf8).map(Piece.link) ?? .nothing
            default:
                return .nothing
            }
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            switch await loadItem(provider, type: UTType.plainText.identifier) {
            case let words as String:
                return .text(words)
            case let words as NSAttributedString:
                return .text(words.string)
            case let url as URL:
                if !url.isFileURL { return .link(url.absoluteString) }
                return fileTaken ? .nothing : (copyDocument(url, into: folder).map(Piece.file) ?? .nothing)
            case let data as Data:
                // A named file (from Files) is a document; anything else is words.
                if let named = provider.suggestedName {
                    if fileTaken { return .nothing }
                    return writeDocument(data, ext: (named as NSString).pathExtension, into: folder).map(Piece.file) ?? .nothing
                }
                return .text(String(decoding: data, as: UTF8.self))
            default:
                return .nothing
            }
        }
        // Any other file (BoringTravel takes a file only as text, as Android does: the app says so if it isn't).
        guard !fileTaken, let type = provider.registeredTypeIdentifiers.first else { return .nothing }
        return await loadFile(provider, type: type, into: folder, base: "document", cap: fileBytes).map(Piece.file) ?? .nothing
    }

    /// The attachment's own picture type (public.heic for an iPhone photo), so the file comes as it is stored.
    private static func imageType(_ provider: NSItemProvider) -> String? {
        provider.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: .image) == true }
    }

    /// The picture's file copied as it is (a HEIC stays a HEIC); one handed over only in memory (a screenshot being
    /// marked up) is written as a JPEG.
    private static func copyPicture(_ provider: NSItemProvider, type: String, into folder: URL, number: Int) async -> String? {
        if let name = await loadFile(provider, type: type, into: folder, base: "\(number)", cap: nil) { return name }
        switch await loadItem(provider, type: type) {
        case let url as URL where url.isFileURL:
            let name = "\(number).\(fileExtension(url.pathExtension, type: type))"
            return copy(url, to: folder.appendingPathComponent(name), cap: nil) ? name : nil
        case let data as Data:
            let name = "\(number).\(fileExtension("", type: type))"
            return (try? data.write(to: folder.appendingPathComponent(name))) != nil ? name : nil
        case let image as UIImage:
            let name = "\(number).jpg"
            guard let data = image.jpegData(compressionQuality: 0.92) else { return nil }
            return (try? data.write(to: folder.appendingPathComponent(name))) != nil ? name : nil
        default:
            return nil
        }
    }

    /// The file behind [type], copied into [folder] as `<base>.<ext>` while it exists (iOS removes it after), at most [cap] bytes.
    private static func loadFile(_ provider: NSItemProvider, type: String, into folder: URL, base: String, cap: Int?) async -> String? {
        await withCheckedContinuation { (done: CheckedContinuation<String?, Never>) in
            _ = provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                guard let url = url else { return done.resume(returning: nil) }
                let name = "\(base).\(ShareWriter.fileExtension(url.pathExtension, type: type))"
                done.resume(returning: ShareWriter.copy(url, to: folder.appendingPathComponent(name), cap: cap) ? name : nil)
            }
        }
    }

    private static func loadItem(_ provider: NSItemProvider, type: String) async -> NSSecureCoding? {
        await withCheckedContinuation { (done: CheckedContinuation<NSSecureCoding?, Never>) in
            provider.loadItem(forTypeIdentifier: type, options: nil) { item, _ in done.resume(returning: item) }
        }
    }

    /// A file shared by its address (the Files app): its first [fileBytes], as "document.<ext>".
    private static func copyDocument(_ url: URL, into folder: URL) -> String? {
        let reading = url.startAccessingSecurityScopedResource()
        defer { if reading { url.stopAccessingSecurityScopedResource() } }
        let name = "document.\(fileExtension(url.pathExtension, type: UTType.plainText.identifier))"
        return copy(url, to: folder.appendingPathComponent(name), cap: fileBytes) ? name : nil
    }

    private static func writeDocument(_ data: Data, ext: String, into folder: URL) -> String? {
        let name = "document.\(fileExtension(ext, type: UTType.plainText.identifier))"
        return (try? data.prefix(fileBytes).write(to: folder.appendingPathComponent(name))) != nil ? name : nil
    }

    /// A whole copy (the file system's own), or the first [cap] bytes read through a handle (nothing more in memory).
    private static func copy(_ from: URL, to: URL, cap: Int?) -> Bool {
        guard let cap = cap else { return (try? FileManager.default.copyItem(at: from, to: to)) != nil }
        guard let handle = try? FileHandle(forReadingFrom: from) else { return false }
        defer { try? handle.close() }
        let data = (try? handle.read(upToCount: cap)) ?? Data()
        return (try? data.write(to: to)) != nil
    }

    /// A short, plain extension for the copy (the app reads only names like "1.heic"): the file's own, else its type's.
    private static func fileExtension(_ given: String, type: String) -> String {
        let plain = given.lowercased()
        if (1...8).contains(plain.count), plain.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) { return plain }
        return UTType(type)?.preferredFilenameExtension ?? "bin"
    }

    /// Folders left by a share that never finished (no share file beside them), an hour old by their name.
    private static func sweep(_ shares: URL, now: Int64) {
        let manager = FileManager.default
        guard let names = try? manager.contentsOfDirectory(atPath: shares.path) else { return }
        let finished = Set(names.filter { $0.hasSuffix(".json") }.map { String($0.dropLast(5)) })
        for name in names where !name.hasSuffix(".json") && !finished.contains(name) {
            guard let stamp = Int64(name.prefix { $0 != "-" }), now - stamp > leftoverMs else { continue }
            try? manager.removeItem(at: shares.appendingPathComponent(name, isDirectory: true))
        }
    }
}

// MARK: - The card (the app's look, read from the widgets' snapshot in the same folder; Postcard Pop's without it)

final class ShareState: ObservableObject {
    enum Phase { case working, sent, failed }
    @Published var phase: Phase = .working
}

struct ShareLook {
    let surface: Color
    let ink: Color
    let muted: Color
    let primary: Color
    let onPrimary: Color

    /// The theme the app last drew its widgets in (snapshot.json, written by the app beside the shares).
    static func load() -> ShareLook {
        var colors: [String: String] = [:]
        if let group = Bundle.main.object(forInfoDictionaryKey: "BoringTravelAppGroup") as? String,
           let folder = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group),
           let data = try? Data(contentsOf: folder.appendingPathComponent("snapshot.json")),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let saved = json["colors"] as? [String: String] {
            colors = saved
        }
        func color(_ name: String, _ fallback: String) -> Color { Color(shareArgb: colors[name] ?? fallback) }
        return ShareLook(
            surface: color("surface", "#FFFFFFFF"), ink: color("ink", "#FF1D1B3A"), muted: color("muted", "#FF6B6880"),
            primary: color("primary", "#FFFF5A3C"), onPrimary: color("onPrimary", "#FFFFFFFF")
        )
    }
}

struct ShareCard: SwiftUI.View {
    @ObservedObject var state: ShareState
    let look: ShareLook
    let close: () -> Void

    private var title: String {
        switch state.phase {
        case .working: return "Sending to BoringTravel"
        case .sent: return "Sent to BoringTravel"
        case .failed: return "Couldn't send this to BoringTravel"
        }
    }

    private var line: String {
        switch state.phase {
        case .working: return "One moment."
        case .sent: return "Open the app to finish."
        case .failed: return "Try again."
        }
    }

    var body: some SwiftUI.View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(0.32)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { if state.phase != .working { close() } }
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(roboto(20, medium: true)).foregroundColor(look.ink)
                Text(line).font(roboto(15)).foregroundColor(look.muted)
                Group {
                    if state.phase == .working {
                        ProgressView().tint(look.primary).frame(maxWidth: .infinity, minHeight: 48)
                    } else {
                        Button(action: close) {
                            Text("OK")
                                .font(roboto(16, medium: true))
                                .foregroundColor(look.onPrimary)
                                .frame(maxWidth: .infinity, minHeight: 48)
                                .background(look.primary)
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                    }
                }
                .padding(.top, 12)
            }
            .padding(.horizontal, 20)
            .padding(.top, 24)
            .padding(.bottom, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(TopRounded(radius: 24).fill(look.surface).ignoresSafeArea(edges: .bottom))
        }
    }

    private func roboto(_ size: CGFloat, medium: Bool = false) -> Font {
        .custom(medium ? "Roboto-Medium" : "Roboto-Regular", size: size)
    }
}

/// A shape with its top corners rounded (as Android's sheets: 24 dp), for iOS 16 (UnevenRoundedRectangle is iOS 17).
struct TopRounded: Shape {
    let radius: CGFloat

    func path(in rect: CGRect) -> SwiftUI.Path {
        SwiftUI.Path(UIBezierPath(roundedRect: rect, byRoundingCorners: [.topLeft, .topRight], cornerRadii: CGSize(width: radius, height: radius)).cgPath)
    }
}

extension Color {
    /// "#AARRGGBB", as the widgets' snapshot writes colours.
    init(shareArgb: String) {
        let value = UInt32(shareArgb.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0xFF000000
        self.init(.sRGB,
                  red: Double((value >> 16) & 0xFF) / 255,
                  green: Double((value >> 8) & 0xFF) / 255,
                  blue: Double(value & 0xFF) / 255,
                  opacity: Double((value >> 24) & 0xFF) / 255)
    }
}
