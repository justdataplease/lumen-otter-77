import UIKit
import CoreLocation
import MapLibre
import BoringTravelApp

/// The maps on iPhone: MapLibre Native views for the Kotlin screens. Kotlin decides everything (the style JSON in the
/// look's colours, what each of the app's sources holds, where the camera goes, what a tap hit; composeApp's BtMap,
/// MapLook and MapCamera); this file only draws it and says what happened (NativeMaps, NativeMap, NativeMapListener in
/// composeApp's iosMain, IosMap.kt).
final class MapLibreMaps: NSObject, NativeMaps {
    func create() -> NativeMap {
        MapLibreSurface()
    }
}

/// One MapLibre view, as the Android app's BtMap sets its MapView up: no logo, no compass, no tilt; two fingers turn it
/// (Android's map turns too), and Kotlin hears the direction with every camera move; the attribution "i" top right
/// under the fit button. All on the main thread.
final class MapLibreSurface: NSObject, NativeMap, MLNMapViewDelegate, UIGestureRecognizerDelegate {
    private let mapView: MLNMapView
    /// Held until the Kotlin side lets go (setListener(nil) when the screen leaves).
    private var listener: NativeMapListener?
    /// Set when the traveller starts moving the map; reported with the move that follows.
    private var movedByGesture = false

    private static let gestures: MLNCameraChangeReason = [
        .gesturePan, .gesturePinch, .gestureRotate, .gestureZoomIn, .gestureZoomOut, .gestureOneFingerZoom, .gestureTilt,
    ]

    var view: UIView { mapView }

    override init() {
        mapView = MLNMapView(frame: .zero)
        super.init()
        mapView.delegate = self
        mapView.logoView.isHidden = true
        mapView.showsCompassView = false
        mapView.isPitchEnabled = false
        mapView.isRotateEnabled = true
        mapView.attributionButtonPosition = .topRight
        mapView.attributionButtonMargins = CGPoint(x: 20, y: 60)
        // Kotlin works out where the camera goes for the whole view: no safe-area insets of MapLibre's own.
        mapView.automaticallyAdjustsContentInset = false
        mapView.contentInset = .zero
        mapView.setCenter(CLLocationCoordinate2D(latitude: 30, longitude: 10), zoomLevel: 1, animated: false)
        // A single tap goes to Kotlin, which finds the pin or route under it; MapLibre keeps its double tap to zoom.
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        for recognizer in mapView.gestureRecognizers ?? [] {
            if let other = recognizer as? UITapGestureRecognizer, other.numberOfTapsRequired == 2 {
                tap.require(toFail: other)
            }
        }
        tap.delegate = self
        mapView.addGestureRecognizer(tap)
    }

    func setListener(listener: NativeMapListener?) {
        self.listener = listener
    }

    func setStyleJson(json: String) {
        mapView.styleJSON = json
    }

    func setImage(name: String, pngBase64: String, scale: Double) {
        guard let style = mapView.style,
              let data = Data(base64Encoded: pngBase64),
              let image = UIImage(data: data, scale: CGFloat(scale)) else { return }
        style.setImage(image, forName: name)
    }

    func setSourceJson(id: String, geoJson: String) {
        guard let source = mapView.style?.source(withIdentifier: id) as? MLNShapeSource,
              let data = geoJson.data(using: .utf8),
              let shape = try? MLNShape(data: data, encoding: String.Encoding.utf8.rawValue) else { return }
        source.shape = shape
    }

    func moveCamera(lat: Double, lng: Double, zoom: Double, animated: Bool) {
        mapView.setCenter(CLLocationCoordinate2D(latitude: lat, longitude: lng), zoomLevel: zoom, animated: animated)
    }

    func setLimits(maxZoom: Double, interactive: Bool) {
        mapView.maximumZoomLevel = maxZoom
        mapView.isScrollEnabled = interactive
        mapView.isZoomEnabled = interactive
        mapView.isRotateEnabled = interactive
    }

    func destroy() {
        listener = nil
        mapView.delegate = nil
    }

    @objc private func handleTap(_ sender: UITapGestureRecognizer) {
        guard sender.state == .ended else { return }
        let point = sender.location(in: mapView)
        listener?.tapped(x: Double(point.x), y: Double(point.y))
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }

    // MARK: MLNMapViewDelegate

    func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
        listener?.styleLoaded()
    }

    func mapViewDidFailLoadingMap(_ mapView: MLNMapView, withError error: Error) {
        listener?.styleFailed()
    }

    func mapView(_ mapView: MLNMapView, regionWillChangeWith reason: MLNCameraChangeReason, animated: Bool) {
        if !reason.intersection(Self.gestures).isEmpty { movedByGesture = true }
    }

    func mapView(_ mapView: MLNMapView, regionDidChangeWith reason: MLNCameraChangeReason, animated: Bool) {
        let center = mapView.centerCoordinate
        let byGesture = movedByGesture || !reason.intersection(Self.gestures).isEmpty
        movedByGesture = false
        listener?.cameraMoved(lat: center.latitude, lng: center.longitude, zoom: mapView.zoomLevel, bearing: mapView.direction, byGesture: byGesture)
    }
}

/// The trip video's map pictures (the Android app's clip/BaseMap.kt BaseMaps, MapLibre's MapSnapshotter there). Kotlin
/// says what to take (composeApp's iosMain, IosClipMaps.kt: the rectangle, the size, the style); this takes it with
/// MLNMapSnapshotter, cuts off the band at the foot where MapLibre writes its logo and credits (the video credits the map
/// on its end card, as Android's QuietSnapshotter leaves that overlay out), and says where two points landed, so Kotlin
/// can measure the picture. One at a time, on the main thread.
final class MapLibreSnapshots: NSObject, NativeMapSnapshots {
    private var running: MLNMapSnapshotter?
    /// Points added under the picture for MapLibre's logo and credits, then cut off.
    private static let creditBand: CGFloat = 28

    func takeMapSnapshot(
        styleJson: String, styleUrl: String, south: Double, west: Double, north: Double, east: Double,
        widthPoints: Double, heightPoints: Double, pixelRatio: Double,
        aLat: Double, aLng: Double, bLat: Double, bLng: Double, reply: NativeMapSnapshotReply
    ) {
        running?.cancel()
        running = nil
        let url: URL
        if styleJson.isEmpty {
            guard let remote = URL(string: styleUrl) else { reply.mapSnapshotFailed(); return }
            url = remote
        } else {
            // A built style (Terrain, Earth, the globe's tinted street map) goes to MapLibre as a file.
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent("clip-styles", isDirectory: true)
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            // (magnitude, not abs: abs traps on the one hash that has no positive twin.)
            let file = folder.appendingPathComponent("style-\(styleJson.hashValue.magnitude).json")
            do {
                try styleJson.write(to: file, atomically: true, encoding: .utf8)
            } catch {
                reply.mapSnapshotFailed()
                return
            }
            url = file
        }
        let band = Self.creditBand
        let size = CGSize(width: widthPoints, height: heightPoints + Double(band))
        let options = MLNMapSnapshotOptions(styleURL: url, camera: MLNMapCamera(), size: size)
        options.coordinateBounds = MLNCoordinateBounds(
            sw: CLLocationCoordinate2D(latitude: south, longitude: west),
            ne: CLLocationCoordinate2D(latitude: north, longitude: east)
        )
        options.scale = CGFloat(pixelRatio)
        options.showsLogo = false
        let snapshotter = MLNMapSnapshotter(options: options)
        running = snapshotter
        NSLog("BT-DIAG map snapshot started: %@", url.absoluteString)
        snapshotter.start { [weak self] snapshot, error in
            self?.running = nil
            guard let snapshot = snapshot else {
                NSLog("BT-DIAG map snapshot failed: %@", error.map { String(describing: $0) } ?? "no error")
                reply.mapSnapshotFailed()
                return
            }
            let image = snapshot.image
            let scale = image.scale
            let keep = CGRect(x: 0, y: 0, width: image.size.width * scale, height: max(1, (image.size.height - band) * scale))
            guard let cropped = image.cgImage?.cropping(to: keep),
                  let png = UIImage(cgImage: cropped, scale: scale, orientation: .up).pngData() else {
                reply.mapSnapshotFailed()
                return
            }
            let a = snapshot.point(for: CLLocationCoordinate2D(latitude: aLat, longitude: aLng))
            let b = snapshot.point(for: CLLocationCoordinate2D(latitude: bLat, longitude: bLng))
            reply.mapSnapshotTaken(png: png, ax: Double(a.x), ay: Double(a.y), bx: Double(b.x), by: Double(b.y))
        }
    }

    func cancelMapSnapshot() {
        NSLog("BT-DIAG map snapshot cancelled")
        running?.cancel()
        running = nil
    }
}
