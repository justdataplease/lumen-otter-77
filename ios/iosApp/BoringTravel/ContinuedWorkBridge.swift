import BackgroundTasks
import BoringTravelApp
import Foundation

/// iOS 26's continued processing tasks for Kotlin's ContinuedWork (composeApp, iosMain, IosBackground.kt): the trip
/// video's export and Copy from a link go on away from the screen with the system's own progress, as Android's go on.
/// In Swift because BGContinuedProcessingTask exists from iOS 26 only: Swift links it weakly behind #available, where a
/// Kotlin reference would keep the app from launching on iOS 16-25. Everything here runs on the main thread.
final class ContinuedWorkBridge: NSObject, NativeContinuedWork {
    private var registered = Set<String>()
    private var listeners: [String: ContinuedWorkListener] = [:]
    private var done: [String: Double] = [:]
    /// The running tasks by identifier (typed AnyObject: a stored property can't name an iOS 26 type here).
    private var tasks: [String: AnyObject] = [:]

    private static let units: Int64 = 1000

    func startContinuedWork(identifier: String, title: String, listener: ContinuedWorkListener) -> Bool {
        guard #available(iOS 26.0, *) else { return false }
        let scheduler = BGTaskScheduler.shared
        if !registered.contains(identifier) {
            let ok = scheduler.register(forTaskWithIdentifier: identifier, using: .main) { [weak self] task in
                self?.launched(identifier, task)
            }
            guard ok else { return false }
            registered.insert(identifier)
        }
        listeners[identifier] = listener
        done[identifier] = 0
        let request = BGContinuedProcessingTaskRequest(identifier: identifier, title: title, subtitle: "BoringTravel")
        request.strategy = .fail
        do {
            try scheduler.submit(request)
        } catch {
            listeners[identifier] = nil
            done[identifier] = nil
            return false
        }
        return true
    }

    func progressContinuedWork(identifier: String, done fraction: Double) {
        done[identifier] = fraction
        guard #available(iOS 26.0, *), let task = tasks[identifier] as? BGContinuedProcessingTask else { return }
        task.progress.completedUnitCount = Int64(fraction * Double(Self.units))
    }

    func finishContinuedWork(identifier: String) {
        listeners[identifier] = nil
        done[identifier] = nil
        guard #available(iOS 26.0, *), let task = tasks.removeValue(forKey: identifier) as? BGContinuedProcessingTask else { return }
        task.progress.completedUnitCount = Self.units
        task.setTaskCompleted(success: true)
    }

    /// The system runs the task: hand it to the work of that kind running now, or end it when there is none.
    @available(iOS 26.0, *)
    private func launched(_ identifier: String, _ task: BGTask) {
        guard let running = task as? BGContinuedProcessingTask, let listener = listeners[identifier] else {
            task.setTaskCompleted(success: true)
            return
        }
        tasks[identifier] = running
        running.progress.totalUnitCount = Self.units
        running.progress.completedUnitCount = Int64((done[identifier] ?? 0) * Double(Self.units))
        // Stopped by the traveller or the system: the work waits for the screen again.
        running.expirationHandler = { [weak self] in
            DispatchQueue.main.async {
                self?.tasks[identifier] = nil
                running.setTaskCompleted(success: false)
                listener.continuedWorkStopped(identifier: identifier)
            }
        }
        listener.continuedWorkRunning(identifier: identifier)
    }
}
