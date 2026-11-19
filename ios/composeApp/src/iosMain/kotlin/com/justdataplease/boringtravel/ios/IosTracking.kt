@file:OptIn(ExperimentalForeignApi::class)

package com.justdataplease.boringtravel.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLActivityTypeOther
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/*
 * Route tracking's side of the phone on iPhone (round 10), behind the common interfaces in ios/Tracking.kt and
 * ios/Widgets.kt. Apple's APIs called here: Core Location (CLLocationManager: the While Using question, standard
 * updates in the background with the blue location sign, accuracy and distance per level, Precise Location, Location
 * Services; the Always question and the significant-change service only on the Always path, which is off as Android's
 * background recording: IosTrackingPlan.ASK_ALWAYS), UserNotifications (the question, quiet notes with a passive
 * interruption level, a tap that opens the app's settings), BackgroundTasks (BGTaskScheduler: a BGProcessingTask and a
 * BGAppRefreshTask for the nightly check) and UIKit (the app's page in Settings). The widget's files are written by
 * Swift ([NativeWidget], WidgetBridge.swift), which links WidgetKit.
 */

/** Opens the app's own page in the phone's Settings (location, notifications). */
internal fun openAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}

/**
 * [RouteSensor] over Core Location. One manager for the app's life, made at the start (also in the background, so a
 * significant-change event that started the app is delivered). It records with `allowsBackgroundLocationUpdates` and
 * the blue location sign (the Info.plist's `location` background mode), never pauses by itself, and asks the level's
 * accuracy and distance; on the Always path (off by default: IosTrackingPlan.ASK_ALWAYS) the significant-change
 * service runs beside it once Always is given. [quiet]: the Simulator tour, where the app asks nothing (it never does
 * by itself anyway).
 */
internal class IosRouteSensor(private val quiet: Boolean) : RouteSensor {
    private val manager = CLLocationManager()
    private val delegate = SensorDelegate(this)
    private var listener: ((List<RouteFix>) -> Unit)? = null
    private var watcher: (() -> Unit)? = null
    private var asked: (() -> Unit)? = null
    private var bestFixWaiter: ((RouteFix) -> Boolean)? = null
    private var current: RecordRequest? = null
    private var services = true

    override var recording: Boolean = false
        private set

    init {
        manager.delegate = delegate
        manager.pausesLocationUpdatesAutomatically = false
        manager.activityType = CLActivityTypeOther
        readServices()
    }

    override fun grant(): LocationGrant = when (manager.authorizationStatus) {
        kCLAuthorizationStatusAuthorizedAlways -> LocationGrant.Always
        kCLAuthorizationStatusAuthorizedWhenInUse -> LocationGrant.WhileUsing
        kCLAuthorizationStatusNotDetermined -> LocationGrant.NotAsked
        else -> LocationGrant.Refused
    }

    override fun precise(): Boolean = manager.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy

    override fun servicesOn(): Boolean = services

    override fun askWhileUsing(done: () -> Unit) {
        if (quiet || manager.authorizationStatus != kCLAuthorizationStatusNotDetermined) return done()
        asked = done
        manager.requestWhenInUseAuthorization()
    }

    /** Called only on the Always path (RouteTracking never calls it while IosTrackingPlan.ASK_ALWAYS is off). */
    override fun askAlways(done: () -> Unit) {
        if (quiet || manager.authorizationStatus != kCLAuthorizationStatusAuthorizedWhenInUse) return done()
        // iOS shows this question once; after that nothing comes back (the step then leads to Settings).
        asked = done
        manager.requestAlwaysAuthorization()
    }

    override fun openSettings() = openAppSettings()

    override fun record(request: RecordRequest, onFixes: (List<RouteFix>) -> Unit) {
        listener = onFixes
        current = request
        apply(request)
        recording = true
    }

    private fun apply(request: RecordRequest) {
        manager.desiredAccuracy = if (request.precise) kCLLocationAccuracyBest else kCLLocationAccuracyHundredMeters
        manager.distanceFilter = request.distanceM
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        if (request.continuous) manager.startUpdatingLocation() else manager.stopUpdatingLocation()
        if (request.significantChanges) manager.startMonitoringSignificantLocationChanges() else manager.stopMonitoringSignificantLocationChanges()
    }

    override fun stopRecording() {
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        manager.allowsBackgroundLocationUpdates = false
        listener = null
        current = null
        recording = false
    }

    /**
     * Balanced's best fix: GPS with no distance asked for [timeoutMs] at most (Android's getCurrentLocation at high
     * accuracy), the first fix no worse than [maxAccuracyM] and newer than the question; then the level's own request
     * again.
     */
    override suspend fun bestFix(timeoutMs: Long, maxAccuracyM: Float): RouteFix? {
        val request = current ?: return null
        val asked = nowMsIos()
        val fix = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                bestFixWaiter = { f ->
                    val good = f.time >= asked - 2_000 && f.accuracyM != null && f.accuracyM <= maxAccuracyM
                    if (good && cont.isActive) cont.resume(f)
                    good
                }
                manager.desiredAccuracy = kCLLocationAccuracyBest
                manager.distanceFilter = kCLDistanceFilterNone
                if (!request.continuous) manager.startUpdatingLocation()
            }
        }
        bestFixWaiter = null
        current?.let { apply(it) }
        return fix
    }

    override fun watchChanges(onChange: () -> Unit) {
        watcher = onChange
    }

    /** Whether Location Services are on, read off the main thread (Apple: the call can keep the screen waiting). */
    private fun readServices() {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val on = CLLocationManager.locationServicesEnabled()
            dispatch_async(dispatch_get_main_queue()) {
                if (on != services) {
                    services = on
                    watcher?.invoke()
                }
            }
        }
    }

    internal fun authorizationChanged() {
        readServices()
        if (manager.authorizationStatus != kCLAuthorizationStatusNotDetermined) {
            asked?.let {
                asked = null
                it()
            }
        }
        watcher?.invoke()
    }

    internal fun located(found: List<CLLocation>) {
        val fixes = found.map(::fixOf)
        bestFixWaiter?.let { waiter -> fixes.firstOrNull { waiter(it) }?.let { bestFixWaiter = null } }
        if (fixes.isNotEmpty()) listener?.invoke(fixes)
    }

    private fun fixOf(l: CLLocation): RouteFix {
        val time = (l.timestamp.timeIntervalSince1970 * 1000).toLong()
        return l.coordinate.useContents {
            RouteFix(
                time, latitude, longitude,
                accuracyM = l.horizontalAccuracy.takeIf { it >= 0 }?.toFloat(),
                speedMps = l.speed.takeIf { it >= 0 }?.toFloat(),
                altitudeM = l.altitude.takeIf { l.verticalAccuracy >= 0 },
            )
        }
    }
}

private fun nowMsIos(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/** Core Location's calls, handed to [IosRouteSensor] (the manager keeps only a weak hold on its delegate). */
private class SensorDelegate(private val sensor: IosRouteSensor) : NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) = sensor.authorizationChanged()

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) =
        sensor.located(didUpdateLocations.filterIsInstance<CLLocation>())

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        // No fix this time (kCLErrorLocationUnknown) or refused (the authorization callback says so): nothing to do.
    }
}

/**
 * [PhoneNotes] over UserNotifications: asked only from route tracking's steps; each note quiet (no sound, no badge, a
 * passive interruption level), in the "route_notes" thread, under its own id so a new one replaces the old.
 */
internal class IosPhoneNotes(private val quiet: Boolean) : PhoneNotes {
    private val center = UNUserNotificationCenter.currentNotificationCenter()
    private var grant = NoteGrant.NotAsked

    override fun noteGrant(): NoteGrant = grant

    override fun readNoteGrant(done: () -> Unit) {
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val read = when (settings?.authorizationStatus) {
                UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral -> NoteGrant.Allowed
                UNAuthorizationStatusDenied -> NoteGrant.Refused
                else -> NoteGrant.NotAsked
            }
            dispatch_async(dispatch_get_main_queue()) {
                grant = read
                done()
            }
        }
    }

    override fun askNotes(done: (Boolean) -> Unit) {
        if (quiet) return done(false)
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert) { granted, _ ->
            dispatch_async(dispatch_get_main_queue()) {
                grant = if (granted) NoteGrant.Allowed else NoteGrant.Refused
                done(granted)
            }
        }
    }

    override fun postNote(note: RouteNote) {
        // Posted whatever was last read: iOS keeps it only when notes are allowed.
        val content = UNMutableNotificationContent()
        content.setTitle(note.title)
        content.setBody(note.text)
        content.setThreadIdentifier(TrackingNotes.THREAD)
        content.setUserInfo(mapOf<Any?, Any?>(OPENS to note.opens.name))
        content.setInterruptionLevel(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelPassive)
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(note.id, content, null), null)
    }

    override fun cancelNote(id: String) {
        center.removeDeliveredNotificationsWithIdentifiers(listOf(id))
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
    }
}

/** The key in a note's userInfo saying what a tap opens ([NoteOpens]). */
private const val OPENS = "opens"

/** The notes' delegate, kept for the app's life (the center keeps a weak hold). */
private var notesDelegate: NotesDelegate? = null

/**
 * Called at the start: route notes show in the list while the app is in front (no banner, no sound), and a tap on one
 * that says so opens the app's settings (Android's PendingIntent to the settings page); else the app just opens.
 */
internal fun startPhoneNotes() {
    if (notesDelegate != null) return
    val d = NotesDelegate()
    notesDelegate = d
    UNUserNotificationCenter.currentNotificationCenter().delegate = d
}

private class NotesDelegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) = withCompletionHandler(UNNotificationPresentationOptionList)

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val opens = didReceiveNotificationResponse.notification.request.content.userInfo[OPENS] as? String
        if (opens == NoteOpens.Settings.name) dispatch_async(dispatch_get_main_queue()) { openAppSettings() }
        withCompletionHandler()
    }
}

/**
 * [NightlyRuns] over BGTaskScheduler: the nightly check as a BGProcessingTask (longer, usually overnight, when the phone
 * gives it time) and the same check as a BGAppRefreshTask (short, more often), each from a few minutes after midnight.
 * Both identifiers are in the Info.plist's BGTaskSchedulerPermittedIdentifiers; both run [register]'s work.
 */
internal object IosNightlyRuns : NightlyRuns {
    const val NIGHT = "com.justdataplease.boringtravel.tracking.night"
    const val CHECK = "com.justdataplease.boringtravel.tracking.check"

    private var registered = false
    private var scheduled: Long? = null

    /** Before the app has finished starting (iOS's rule): [work] for both tasks. */
    fun register(work: suspend () -> Unit) {
        if (registered) return
        registered = true
        val scheduler = BGTaskScheduler.sharedScheduler
        for (id in listOf(NIGHT, CHECK)) {
            scheduler.registerForTaskWithIdentifier(id, usingQueue = null) { task -> task?.let { run(it, work) } }
        }
    }

    /** One run on the main thread; the phone's time running out cancels it; the next night is asked for after it. */
    private fun run(task: BGTask, work: suspend () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) {
            scheduled = null
            val job = MainScope().launch {
                try {
                    work()
                } finally {
                    task.setTaskCompletedWithSuccess(true)
                }
            }
            task.expirationHandler = { job.cancel() }
        }
    }

    override fun scheduleNight(atMs: Long) {
        if (scheduled == atMs) return
        scheduled = atMs
        val at = NSDate.dateWithTimeIntervalSince1970(atMs / 1000.0)
        val scheduler = BGTaskScheduler.sharedScheduler
        // The Simulator has no background tasks: the request is refused there, quietly.
        val night = BGProcessingTaskRequest(NIGHT)
        night.earliestBeginDate = at
        night.requiresNetworkConnectivity = false
        night.requiresExternalPower = false
        scheduler.submitTaskRequest(night, null)
        val check = BGAppRefreshTaskRequest(CHECK)
        check.earliestBeginDate = at
        scheduler.submitTaskRequest(check, null)
    }
}

/**
 * What Swift does for the home-screen widgets (iosApp/BoringTravel/WidgetBridge.swift): writes the snapshot and the
 * stamp into the App Group's folder and asks WidgetKit to draw the widgets again. Swift sees it as the protocol
 * `NativeWidget`. Without the App Group (an unsigned build, or none set up yet) Swift writes nothing.
 */
interface NativeWidget {
    /** [snapshot] is JSON; [mark] a PNG, null when it didn't change. */
    fun writeWidgetFiles(snapshot: String, mark: NSData?)
}

/** The app's [HomeWidget] over Swift's [NativeWidget]. */
internal class IosHomeWidget(private val native: NativeWidget) : HomeWidget {
    override fun saveWidget(snapshot: String, mark: ByteArray?) {
        native.writeWidgetFiles(snapshot, mark?.let(::widgetData))
    }
}

private fun widgetData(bytes: ByteArray): NSData? =
    if (bytes.isEmpty()) null else bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
