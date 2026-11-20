package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.engine.TripFile
import kotlinx.coroutines.MainScope
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidFinishLaunchingNotification
import platform.UIKit.UIApplicationState

/*
 * The app's one run on this iPhone (round 10), made when the app starts: Swift's App init calls [startBoringTravel]
 * before the first screen, and also when iOS starts the app in the background (a location event with Always, the
 * nightly run), when there is no screen at all. The trips' store, the settings, route tracking, the widget's snapshot
 * and the links from widgets and quick actions live here, so the screens (MainViewController) and the background share
 * one store.
 */

/** The parts of the app made once per run. */
object IosApp {
    /** The Simulator tour starts the app with "-tour": nothing then asks a question of its own (none does anyway). */
    val tour: Boolean = NSProcessInfo.processInfo.arguments.contains("-tour")

    /**
     * The tour's last start adds "-tourShare" (round 27): a Google Maps place comes in as if shared from the Maps app, so
     * Add to plan opens (the Simulator's unsigned build has no App Group for the share extension to write into).
     */
    private val tourShare: Boolean = NSProcessInfo.processInfo.arguments.contains("-tourShare")

    /**
     * The Android app's versionName ("1.21.1"), written by the build from Android's build file: Me › Version, a backup's
     * manifest, what the app asks the web with. The Info.plist's CFBundleShortVersionString (project.yml's
     * MARKETING_VERSION, which the App Store shows) is the same number.
     */
    val version: String = AppVersion.NAME

    val tripFile: TripFile = UserDefaultsTripFile()
    val settingsFile: SettingsFile = UserDefaultsSettingsFile()
    val core: AppCore by lazy { AppCore(tripFile, settingsFile) }

    /** The network and Core Location (place search, the geocoder that names stops), for the screens and the background. */
    val web: Web by lazy { iosWeb(version) { core.settings.placePhotos } }

    val links = AppLinks()
    val processor: TrackProcessor by lazy { TrackProcessor(core.book, web.location, levelNow = { core.settings.trackingLevel }) }
    val tracking: RouteTracking by lazy {
        RouteTracking(
            core.book, core.settings, IosRouteSensor(quiet = tour), IosPhoneNotes(quiet = tour), IosNightlyRuns,
            TrackingStore(settingsFile), MainScope(),
            onScreen = { UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground },
            quiet = tour,
            // While it records: the Live Activity (Android's ongoing notification with Stop); none on the tour.
            card = IosRecordingCard(recordingActivity.takeIf { !tour }),
        )
    }
    internal var widget: HomeWidget? = null

    /** The app's files (Application Support/BoringTravel), for the backups made in the background too. */
    private val files: IosMediaFiles by lazy { IosMediaFiles() }

    /** The backup files and Drive's backups (round 23): one per run, shared by the screens and the daily backup's task. */
    val backups: BackupFiles by lazy { BackupFiles(core.book, core.settings, files, version, frames = IosVideoFrames(files)) }

    /**
     * The account (round 23): Google's sign-in page (ASWebAuthenticationSession), Sign in with Apple (AuthenticationServices),
     * the refresh tokens in the Keychain (this device only), this build's Google iPhone client and Firebase ids.
     */
    val google: com.justdataplease.boringtravel.google.GoogleAccount by lazy {
        com.justdataplease.boringtravel.google.GoogleAccount(
            core.settings, web.net, IosKeychain(IosKeychain.ACCOUNT_SERVICE), files, IosAuthBrowser(), IosAppleSignIn(),
            bundleId = NSBundle.mainBundle.bundleIdentifier, random = ::secureRandom,
        )
    }

    /** Drive's backups on the account's Drive, the daily one through BGTaskScheduler ([IosBackupTask]). */
    val driveBackups: DriveBackups by lazy {
        DriveBackups(backups, core.book, core.settings, files, google.drive, version, web.net, IosVideoFrames(files), planner = IosBackupTask, work = IosBackgroundWork()).also { d ->
            google.onDriveChanged = { d.reschedule() }
            backups.onRestored = { d.reschedule() }
        }
    }

    /**
     * The community (round 24): Firestore's and Firebase Auth's REST APIs on this build's Firebase ids (without them it
     * isn't available in this build), its account in the Keychain beside Google's tokens, the lists, pictures and clips
     * kept in the caches, the writes made without a connection in the settings, the pictures read by link with the
     * build's Drive API key (`drive.apiKey`). No App Check token yet (the owner's Firebase iPhone app with App Attest).
     */
    val community: com.justdataplease.boringtravel.community.Community by lazy {
        com.justdataplease.boringtravel.community.Community.make(
            web.net, com.justdataplease.boringtravel.firebase.FirebaseSetup.config, IosKeychain(IosKeychain.ACCOUNT_SERVICE), google,
            web.cache, settingsFile, MainScope(), bundleId = NSBundle.mainBundle.bundleIdentifier,
            driveKey = com.justdataplease.boringtravel.google.AccountIds.DRIVE_API_KEY,
            driveConnected = { core.settings.driveConnected }, deleteDriveFile = { id -> google.drive.delete(id) },
        ).also { c -> google.onSignedOut = { c.signOut() } }
    }

    /**
     * Travel together (round 26): the shared trips on the run's store, the account's Drive and the build's Drive API key
     * (other members' copies by link), the trip mailboxes in the community's Firestore, the sends kept in the settings
     * and the background turn through BGTaskScheduler ([IosTogetherRuns]); the store read and written on the main thread,
     * where the screens write it. Backups carry its state, and Delete account takes its mailbox entries out first.
     */
    val together: com.justdataplease.boringtravel.together.Together by lazy {
        com.justdataplease.boringtravel.together.Together(
            core.book, core.settings, files, google.drive,
            com.justdataplease.boringtravel.google.PublicDrive(web.net, com.justdataplease.boringtravel.google.AccountIds.DRIVE_API_KEY, NSBundle.mainBundle.bundleIdentifier),
            MainScope(), PhotoPrep(files, IosVideoFrames(files)), web.net,
            bookContext = kotlinx.coroutines.Dispatchers.Main, runs = IosTogetherRuns, background = IosBackgroundWork(), keep = settingsFile,
            driveAccount = { google.driveAccount }, mailbox = { community.mailAccess() },
        ).also { t ->
            backups.sharing = t.backupState()
            community.beforeDelete = { t.forgetMail() }
        }
    }

    /** The Live Activity's Swift side (RecordingActivityBridge), handed over at the start. */
    internal var recordingActivity: NativeRecordingActivity? = null

    /**
     * What other apps shared into this one (round 27): the share extension (iosApp/BoringTravelShare) leaves each share in
     * the App Group's container (the widgets' group, the Info.plist's BoringTravelAppGroup), under Shares/; the screens
     * take it from there. Without the App Group (the Simulator's unsigned build) there is no folder and nothing comes in.
     */
    val shares: ShareInbox by lazy {
        val group = (NSBundle.mainBundle.objectForInfoDictionaryKey("BoringTravelAppGroup") as? String)
            ?.let { NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(it)?.path }
        val planted = if (tourShare) listOf(ShareManifest(text = "Kawai Kanjiro's House\n$TOUR_SHARE_LINK")) else emptyList()
        ShareInbox(group?.let { IosMediaFiles(folder = it) }, files, planted)
    }

    /**
     * A Google Maps place with its pin in the link (nothing to follow on the network), as the tour shares it: one the
     * tour's Kyoto trip doesn't have already, so the Plan after Add shows the new one.
     */
    private const val TOUR_SHARE_LINK = "https://www.google.com/maps/place/Kawai+Kanjiro+Memorial+House/@34.9944,135.7737,17z"

    /** What the screens get of the phone's side (App's [AppPhone]). */
    val phone: AppPhone get() = AppPhone(tracking, processor, widget, links, shares)
}

/**
 * Called once from Swift's App init, before the app has finished starting (iOS asks for the background tasks to be
 * registered by then, and starts the app without a screen for a location event or the nightly run): the notes' answers,
 * the nightly run, route tracking, the widget's files through Swift's [widget] (WidgetBridge.swift) and the recording
 * route's Live Activity through Swift's [recording] (RecordingActivityBridge.swift); iOS 26's continued tasks through
 * Swift's [continued] (ContinuedWorkBridge.swift).
 */
fun startBoringTravel(widget: NativeWidget?, recording: NativeRecordingActivity?, continued: NativeContinuedWork?) {
    widget?.let { IosApp.widget = IosHomeWidget(it) }
    nativeContinuedWork = continued
    IosApp.recordingActivity = recording
    startPhoneNotes()
    IosNightlyRuns.register { IosApp.tracking.nightly(IosApp.processor) }
    // The daily Drive backup (round 23), registered before the app has finished starting as iOS asks; never on the tour.
    IosBackupTask.register(
        work = { if (IosApp.tour) DailyResult.Skipped else IosApp.driveBackups.daily(lowPowerMode()) },
        next = { IosApp.driveBackups.reschedule() },
    )
    // Travel together's background turn (round 26): the sends left waiting, then the shared trips' catch-up every 3 h.
    IosTogetherRuns.register { if (!IosApp.tour) IosApp.together.backgroundTurn(lowPowerMode()) }
    // Route tracking's first decision once the app has finished starting: SwiftUI makes the App before UIApplication's
    // own start, and the decision asks whether the app is on screen. A location event that started the app in the
    // background is handed over once the significant-change service is asked again (Apple's rule), which this does.
    if (launchObserver == null) {
        launchObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidFinishLaunchingNotification, `object` = null, queue = NSOperationQueue.mainQueue,
        ) { _ -> IosApp.tracking.start() }
    }
}

/** The end of the app's start, waited for once. */
private var launchObserver: Any? = null

/**
 * A link into the app from outside: a widget's button or a Home Screen quick action ("boringtravel://write"). It waits
 * until the screens can take it; false when it isn't one of the app's links.
 */
fun openAppLink(url: String): Boolean = IosApp.links.open(url)

/**
 * The app's scene is active again (Swift's scenePhase; round 27): what the share extension left in the App Group's folder
 * meanwhile is taken by the screens (they also look at the start and each time the app is back on screen).
 */
fun checkShares() {
    IosApp.shares.ask()
}

/**
 * The recording card's Stop (the Live Activity's button, an App Intent that runs in the app; Android's notification's
 * Stop, RouteRecordingService.ACTION_STOP): nothing records for today's trip until tomorrow or Start recording.
 */
fun stopRouteForToday() {
    IosApp.tracking.stopForToday()
}

/**
 * The traveller's trips (the engine's data.json text) in the app's UserDefaults: they stay on the phone and are part
 * of its backups. They move to a file of their own with the photos.
 */
internal class UserDefaultsTripFile(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : TripFile {
    override fun read(): String? = defaults.stringForKey(KEY)

    override fun write(text: String) {
        defaults.setObject(text, forKey = KEY)
    }

    private companion object {
        const val KEY = "boringtravel.trips"
    }
}

/** The app's settings in its UserDefaults, each under "boringtravel.settings." and its name ([AppSettings]). */
internal class UserDefaultsSettingsFile(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : SettingsFile {
    override fun read(key: String): String? = defaults.stringForKey(PREFIX + key)

    override fun write(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(PREFIX + key) else defaults.setObject(value, forKey = PREFIX + key)
    }

    private companion object {
        const val PREFIX = "boringtravel.settings."
    }
}
