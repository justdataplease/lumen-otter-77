package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.LayoutDirection
import com.justdataplease.boringtravel.ui.studio.StudioColors
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.background
import com.justdataplease.boringtravel.calendar.CalendarSync
import com.justdataplease.boringtravel.compat.Configuration
import com.justdataplease.boringtravel.compat.LocalConfiguration
import com.justdataplease.boringtravel.compat.Locale
import com.justdataplease.boringtravel.compat.LocaleList
import com.justdataplease.boringtravel.compat.SystemBars
import com.justdataplease.boringtravel.calendar.PhoneCalendar
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.engine.TripFile
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.today
import com.justdataplease.boringtravel.ui.ai.rememberAiReport
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.nav.BarMode
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripNav
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.nav.Where
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app from a cold start: reads its fonts, the trips kept in [file] and the settings kept in [settingsFile], then
 * shows Welcome the first time and Trips after that. [version] is the app's version, for Me; [media] is where the
 * notes' photos and videos are kept, picked and played; [web] makes the network side, given Me › Place photos; [ai]
 * is what the AI planner needs of the phone (Me › AI's keys, App Check, this build's Firebase ids). [restored] is where
 * the app was when the phone closed it in the background ([AppRestore], kept by iOS with the window), [keep] takes the
 * same text after every change (Android: the saved instance state).
 */
@Composable
fun App(
    file: TripFile,
    settingsFile: SettingsFile,
    version: String,
    media: MediaKit = remember { MediaKit.memory() },
    web: (placePhotos: () -> Boolean) -> Web = { Web.offline() },
    calendar: PhoneCalendar? = null,
    ai: PlannerPhone = remember { PlannerPhone() },
    core: AppCore = remember(file, settingsFile) { AppCore(file, settingsFile) },
    phone: AppPhone? = null,
    restored: String? = null,
    keep: ((String) -> Unit)? = null,
    google: com.justdataplease.boringtravel.google.GoogleAccount? = null,
    backups: BackupFiles? = null,
    driveBackups: DriveBackups? = null,
    community: com.justdataplease.boringtravel.community.Community? = null,
    together: com.justdataplease.boringtravel.together.Together? = null,
) {
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // What the last run shared or printed is done with (the Android app keeps a day of it in its cache).
        media.files.delete(EXPORTS)
        BtFontFiles.load()
        // The countries' outlines, so the flags and the globe are there on the first frame.
        CountryOutlines.load()
        // Each country's time zones (the IANA table), for the phone's calendar's guess (Android: ICU's own table).
        CountryZones.load()
        ready = true
    }
    val book = core.book
    val settings = core.settings
    val network = remember(settings) { web { settings.placePhotos } }
    if (ready) BoringTravelApp(
        book, settings, version = version, media = media, followsClock = true, web = network, calendar = calendar, ai = ai,
        tracking = phone?.tracking, processor = phone?.processor, widget = phone?.widget, links = phone?.links,
        restored = restored, keep = keep, google = google, backups = backups, driveBackups = driveBackups, community = community,
        together = together, writes = core.writes, shares = phone?.shares,
    )
}

/**
 * The trips and the settings of one run of the app, made once: on iPhone before the first screen, also when iOS starts
 * the app in the background (a location event, the nightly run), so route tracking and the screens share one store.
 * Trips that can't be read are left as they are on the phone: the app then runs on an empty book in memory. An item's
 * Google Maps link is kept apart from its website, as on Android (LinkPlaces.sortLinks).
 */
class AppCore(file: TripFile, settingsFile: SettingsFile) {
    /** Goes up after every write to the trips' store, from any screen or task (round 26: a shared trip's change is sent). */
    val writes = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val watched = object : TripFile {
        override fun read(): String? = file.read()
        override fun write(text: String) {
            file.write(text)
            writes.value++
        }
    }

    /** Rows are stamped on Google's clock once a Drive answer told it (Stamps, Android's rule since shared trips). */
    val book: TripBook = runCatching { TripBook(watched, now = { com.justdataplease.boringtravel.data.repo.Stamps.now() }, links = LinkPlaces::sortLinks) }
        .getOrElse { TripBook(MemoryTripFile(), links = LinkPlaces::sortLinks) }
    val settings: AppSettings = AppSettings(settingsFile)
}

/**
 * What the phone runs beside the screens (round 10): route tracking and its stop processor, the home-screen widget's
 * snapshot and the links from widgets and Home Screen quick actions; since round 27 what other apps shared into the app
 * (the share extension's folder). Made once per run on iPhone (iosMain, IosApp).
 */
class AppPhone(
    val tracking: RouteTracking? = null,
    val processor: TrackProcessor? = null,
    val widget: HomeWidget? = null,
    val links: AppLinks? = null,
    val shares: ShareInbox? = null,
)

/** The app's version (Android's versionName, "1.21.1"), for what it writes with its name (an .ics file). */
val LocalAppVersion = staticCompositionLocalOf { "" }

/** What a screen says in passing (the app's snackbar, where Android shows a toast); nothing outside the app frame. */
val LocalSay = staticCompositionLocalOf<(String) -> Unit> { {} }

/** The app's own coroutines, which outlive a screen (a short Maps link followed after its sheet closed). */
val LocalAppScope = staticCompositionLocalOf<CoroutineScope?> { null }

/** Where the app can be. Each place is one screen; Back goes to the one under it. */
sealed interface Place {
    /** The first start (shown once). */
    data object Welcome : Place

    /** One of the bar's screens outside a trip; Trips is always at the bottom, as Android's start screen. */
    data class Home(val tab: HomeTab) : Place

    /** Notes outside trips, opened from Trips (the bar stays on Trips). */
    data object LooseNotes : Place

    /** New trip: how to start, then the form (Make it myself). */
    data object NewTrip : Place

    /** New trip starting on [start], a free day picked on the Calendar tab. */
    data class NewTripFrom(val start: LocalDate) : Place

    /** Trip ideas: the destinations. */
    data object Ideas : Place

    /** One destination's two questions and its program. */
    data class Idea(val destinationId: String) : Place

    /** A trip in trip mode; [tab] is the section it opens on (null: Plan). The bar changes the section on show. */
    data class Trip(val tripId: String, val tab: TripTab? = null) : Place
    data class EditTrip(val tripId: String) : Place

    /** One journal note, to read; [tripId] null for a note outside trips. */
    data class Note(val tripId: String?, val entryId: String) : Place

    /**
     * Writing a note for a trip (outside trips when [tripId] is null), or changing one ([entryId]); [shot]: a photo just
     * taken from Trips' Now card, which the new note starts with; [dictate]: dictation starts by itself (the Now card's mic);
     * [planItemId]: a new note about that plan item (its sheet's Write: at its place, on its day; Android's writeAbout).
     */
    data class Write(
        val tripId: String?, val entryId: String? = null, val shot: PickedMedia? = null, val dictate: Boolean = false, val pick: Boolean = false,
        val planItemId: String? = null,
    ) : Place

    /** A place of trip [tripId] (a day's title opens it): around plan item [itemId], or stop [stopId]. */
    data class PlacePage(val tripId: String, val itemId: String? = null, val stopId: String? = null) : Place

    /** Copy from a link (Add from a link, Me › Import trips from a link): into trip [tripId] when given. */
    data class CopySite(val tripId: String? = null, val address: String? = null) : Place

    /** Places I've been: every country, to add or remove (from the globe and Your world). */
    data object PlacesBeen : Place

    /** Share your world: the card, then the share sheet or Photos. */
    data object ShareWorld : Place

    /** Me › Backup & sync: Google Drive's backups (since round 23) and backup files to save and restore. */
    data object Backup : Place

    /** A trip's ⋮ › Share: its pages previewed, then a PDF, text or a Word file. */
    data class Share(val tripId: String) : Place

    /**
     * The AI planner for trip [tripId], or for a new trip when it is null; [start] is the step it opens at once (New
     * trip's choices: [PlannerStart]); [shared]: what was shared into the app for it (round 27: pictures, a file, a Google
     * Doc's link or a longer text), taken by its chat once.
     */
    data class Planner(
        val tripId: String? = null, val start: String? = null,
        val shared: com.justdataplease.boringtravel.ai.SharedItinerary? = null,
    ) : Place

    /** Me › AI: the built-in planner or the traveller's own key. */
    data object AiSettings : Place

    /** Me › Route tracking: what records now, the level for every trip, what the phone still needs. */
    data object Tracking : Place

    /** The trip video of trip [tripId]: the trip's ⋮ › Make a clip, the trip map's Play the route clip. */
    data class Clip(val tripId: String) : Place

    /** Trip [tripId]'s book: the trip's ⋮ › Make a book, Share's "Make a book". */
    data class Book(val tripId: String) : Place

    /** One community post (round 24): from the feed, a profile, a post's link. The bar stays on Community. */
    data class CommunityPost(val postId: String) : Place

    /** Someone's community profile (their posts, likes received, follows). The bar stays on Community. */
    data class CommunityProfile(val uid: String) : Place

    /** Someone's followers ([followers]) or whom they follow. The bar stays on Community. */
    data class CommunityFollows(val uid: String, val followers: Boolean) : Place

    /** Community profile: make or change the signed-in traveller's, their blocked people. No bar. */
    data object CommunityProfileEdit : Place

    /** Post to community (round 25): the composer for a trip ([route]: its trip, what to share). No bar. */
    data class Compose(val route: CommunityCompose) : Place

    companion object {
        val Trips: Place = Home(HomeTab.Trips)
    }
}

/**
 * The app once its fonts are read, on the trips in [book] (the engine's store) and the [settings] (the theme and fonts
 * it is drawn in, the trips' and plan's looks, demo mode, and whether Welcome was seen). As on Android, the bottom bar
 * is Trips · Calendar · Map · Community · Me outside a trip and the trip's own Plan · Journal · Map · Media inside one
 * (the Android app's TripNav decides both), and screens pushed over them (forms, a note, Write) have none.
 *
 * [frame]: room to leave above and below, for previews that show an iPhone's status bar and home indicator; on the
 * phone the screens and the bar read those from the system themselves. [startAt]: the places open at the start, over
 * Trips (previews and tests). [media]: the notes' photos and videos (files in memory and no picker by default).
 * [followsClock]: the real app, whose demo moves to the day it is when the app comes back to the front. [ai]: the AI
 * planner's parts of the phone (keys in memory and no Firebase ids by default: previews and tests never reach a model).
 * [siteCopy]: how Copy from a link paces itself (tests read without pauses). [restored] and [keep]: where the app was
 * when the phone closed it, and the keeper of where it is now ([AppRestore]).
 */
@Composable
fun BoringTravelApp(
    book: TripBook,
    settings: AppSettings = remember { AppSettings.ready() },
    today: LocalDate = today(),
    frame: PaddingValues = PaddingValues(),
    startAt: List<Place> = emptyList(),
    version: String = "",
    media: MediaKit = remember { MediaKit.memory() },
    followsClock: Boolean = false,
    web: Web = remember { Web.offline() },
    calendar: PhoneCalendar? = null,
    ai: PlannerPhone = remember { PlannerPhone() },
    siteCopy: SiteCopySetup = remember { SiteCopySetup() },
    tracking: RouteTracking? = null,
    processor: TrackProcessor? = null,
    widget: HomeWidget? = null,
    links: AppLinks? = null,
    restored: String? = null,
    keep: ((String) -> Unit)? = null,
    google: com.justdataplease.boringtravel.google.GoogleAccount? = null,
    backups: BackupFiles? = null,
    driveBackups: DriveBackups? = null,
    community: com.justdataplease.boringtravel.community.Community? = null,
    /** Travel together (round 26): the phone's (iPhone: IosApp's), else one made here on [google] and the community. */
    together: com.justdataplease.boringtravel.together.Together? = null,
    /** Goes up after every write to the trips' store (AppCore's); null: the screens' own count of changes stands in. */
    writes: kotlinx.coroutines.flow.Flow<Int>? = null,
    /** What other apps shared into this one (round 27: the share extension's folder; a folder in memory in tests). */
    shares: ShareInbox? = null,
) {
    val rows = remember(book) { { id: String -> book.media(id) } }
    val appScope = rememberCoroutineScope()
    // The community (round 24): the phone's (iPhone: IosApp's, on this build's Firebase ids), else one that isn't
    // available in this build (previews, tests, the desktop window), so nothing reaches Firebase there.
    val communityHere = community ?: remember(appScope) {
        com.justdataplease.boringtravel.community.Community(com.justdataplease.boringtravel.community.UnavailableCommunity, null, null, appScope)
    }
    remember(google, communityHere) {
        // Signing out of the app signs out of the community (Android's AppGraph: onSignedOut).
        google?.onSignedOut = { communityHere.signOut() }
    }
    // The recorded points become stops when a map opens, and for the trips on now or just ended when the app starts.
    val tracks = processor ?: remember(book, web, settings) { TrackProcessor(book, web.location, levelNow = { settings.trackingLevel }) }
    // Route tracking: the phone's (iPhone: Core Location, UserNotifications, BGTaskScheduler); else one on a phone in
    // memory that is never asked anything (previews, tests, the desktop window).
    val routes = tracking ?: remember(book, settings) {
        RouteTracking(book, settings, MemoryRouteSensor(), MemoryPhoneNotes(), MemoryNightlyRuns(), TrackingStore(MemorySettingsFile()), appScope)
    }
    LaunchedEffect(routes) { routes.start() }
    val world = remember(book) { WorldSource() }
    // The phone's calendars (EventKit on iPhone), and the backup files, kept in the app's own files.
    val calendarSync = remember(book, calendar, settings) {
        calendar?.let { CalendarSync(book, settings.calendarLinks, it, countries = { CountryOutlines.loaded }, countryZones = CountryZones::of) }
    }
    val backupFiles = backups ?: remember(book, settings, media, version) { BackupFiles(book, settings, media.files, version, frames = media.frames) }
    // Google Drive's backups (round 23): one per run on iPhone (made with the account before the first screen, so the
    // daily backup's background task shares them), else made here on the account given.
    val drive = driveBackups ?: remember(google, backupFiles) {
        google?.let { DriveBackups(backupFiles, book, settings, media.files, it.drive, version, web.net, media.frames) }
    }
    remember(google, drive, backupFiles) {
        // Drive connected or disconnected, a restore made: the daily backup is planned again (Android's reschedule).
        google?.onDriveChanged = { drive?.reschedule() }
        backupFiles.onRestored = { drive?.reschedule() }
    }
    // The AI planner: which engine answers, its process, the daily count, Me › AI's keys (nothing is asked at the start).
    val planner = remember(book, settings, web, media, ai, version) { PlannerKit(book, settings, web, media.files, ai, version) }
    // The trip videos made on this phone, for each trip's Media tab (round 20).
    val savedClips = remember(media) { com.justdataplease.boringtravel.data.SavedClips(media.files) }
    // Travel together (round 26): the phone's, else one here on the account given (previews, tests: memory and fakes),
    // its trip mailboxes the community's. Delete account takes this phone's mailbox entries out first (Android's order).
    val togetherHere = together ?: remember(book, settings, media, web, google, communityHere, appScope) {
        com.justdataplease.boringtravel.together.Together(
            book, settings, media.files, google?.drive,
            com.justdataplease.boringtravel.google.PublicDrive(web.net, "", null), appScope, PhotoPrep(media.files, media.frames), web.net,
            mailbox = { communityHere.mailAccess() },
        )
    }
    remember(communityHere, togetherHere) { communityHere.beforeDelete = { togetherHere.forgetMail() } }
    // Backups carry the shared trips' state and a restore brings the memberships back (Android's sharedTrips).
    remember(backupFiles, togetherHere) { backupFiles.sharing = togetherHere.backupState() }
    CompositionLocalProvider(
        LocalMediaKit provides media, LocalMediaRows provides rows, LocalWeb provides web, LocalAppScope provides appScope,
        LocalTrackProcessor provides tracks, LocalWorld provides world, LocalCalendar provides calendarSync,
        LocalBackupFiles provides backupFiles, LocalAppVersion provides version, LocalPlannerKit provides planner,
        com.justdataplease.boringtravel.google.LocalGoogle provides google, LocalDriveBackups provides drive,
        LocalTracking provides routes, LocalAppLinks provides links, LocalSavedClips provides savedClips,
        com.justdataplease.boringtravel.community.LocalCommunity provides communityHere,
        // The phone's language and region for what the Android screens write with them (Android's LocalConfiguration).
        LocalConfiguration provides remember { Configuration(LocaleList(listOf(Locale.getDefault()))) },
        LocalTogether provides togetherHere, LocalShareInbox provides shares,
    ) {
        AppFrame(book, settings, today, frame, startAt, version, media, followsClock, siteCopy, widget, restored, keep, writes)
    }
}

/** What the screens say in passing (the Android app's AppNav snackbars). */
object DemoNotice {
    const val TEXT = "You're looking at the demo. Turn it off in Me › Demo mode."
    const val ACTION = "Turn off"
    const val LEFT = "Demo mode off"
}

@Composable
private fun AppFrame(
    book: TripBook, settings: AppSettings, today: LocalDate, frame: PaddingValues, startAt: List<Place>, version: String, media: MediaKit,
    followsClock: Boolean, siteCopy: SiteCopySetup, widget: HomeWidget?, restored: String?, keep: ((String) -> Unit)?,
    writes: kotlinx.coroutines.flow.Flow<Int>? = null,
) {
    // Where the phone closed the app in the background, when it did (a start with screens asked for is a preview's).
    val kept = remember { if (settings.onboardingDone && startAt.isEmpty()) AppRestore.decode(restored, book) else null }
    // The screens, as Android's back stack (Trips at the bottom), and each home tab's pages while another tab shows.
    val stack = remember {
        kept?.let { AppStack(it.stack, it.tabs) } ?: AppStack(listOf(if (settings.onboardingDone) Place.Trips else Place.Welcome) + startAt)
    }
    // The section on show in the open trip, and the plan's day to show (the Calendar's day, Add to plan's day or Ideas:
    // an epoch day or PLAN_IDEAS, Android's TripState.DAY): at most one trip is open (TripNav.open), so one of each is enough.
    var tripTab by remember {
        mutableStateOf(kept?.tripTab ?: TripNav.landingTab(startAt.filterIsInstance<Place.Trip>().lastOrNull()?.tab?.key))
    }
    var tripDay by remember { mutableStateOf(kept?.tripDayKey) }
    // The note each Write on the stack is writing, by its entry's id: a restore reopens that note (Android's KEY_ID).
    val noteIds = remember { mutableStateMapOf<Int, String>() }
    // A shared trip's join link (round 26) waiting for its sheet: opened from outside (kept with the window, so it survives
    // the app being stopped, and waiting for Welcome, as Android's pendingJoin), or pasted on Trips (Android's typedJoin).
    var pendingJoin by remember { mutableStateOf(AppRestore.joinOf(restored)) }
    var typedJoin by remember { mutableStateOf<com.justdataplease.boringtravel.together.ShareLink?>(null) }
    // What another app shared (round 27; Android's pendingPlace and pendingImport): a link for Add to plan (or a few words
    // without one), and an itinerary or pictures for the planner. Both wait for Welcome and are kept with the window.
    var pendingPlace by remember { mutableStateOf(AppRestore.sharedPlaceOf(restored)) }
    var pendingImport by remember { mutableStateOf(AppRestore.sharedImportOf(restored)) }
    // The book isn't observable: a screen says when it changed something, and what is on screen is read again.
    var revision by remember { mutableIntStateOf(0) }
    val changed: () -> Unit = { revision++ }
    // Each place keeps what it remembers (a scrolled list, a form) while another one is on top of it.
    val saved = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun say(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }
    val demo = remember(book, settings) { DemoMode(book, settings, media.files) }
    // Demo mode is decided once, and the demo is kept around today (on every start; Android also on each return).
    LaunchedEffect(demo) {
        demo.chooseOnce()
        demo.anchor(force = false, today = today)
        changed()
    }
    // Back in front (another day, maybe): the demo moves around the day it is now, once a day at most. Only the real
    // app follows the clock; previews and tests stay on their own day.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (followsClock) {
            demo.anchor(force = false, today = com.justdataplease.boringtravel.time.today())
            changed()
        }
    }
    // Today's trip and those that ended in the last three days: their recorded points become stops (Android does this
    // every night; this phone when it starts).
    val tracks = LocalTrackProcessor.current
    LaunchedEffect(tracks) { if (tracks?.processRecent(today) == true) changed() }
    // A calendar kept updated follows the plan: after each change (a moment later, so typing doesn't write each
    // letter) and when the app comes back to the front (Android runs a background job).
    val calendarSync = LocalCalendar.current
    LaunchedEffect(calendarSync, revision) {
        if (calendarSync == null) return@LaunchedEffect
        kotlinx.coroutines.delay(1_500)
        calendarSync.reconcileAutomatic()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { calendarSync?.let { scope.launch { it.reconcileAutomatic() } } }
    // Route tracking: the app on screen may start recording (the only time it can without Always); leaving the screen
    // puts what was recorded into the store. Points written while the screens show something are read again.
    val tracking = LocalTracking.current
    LifecycleEventEffect(Lifecycle.Event.ON_START) { tracking?.cameOnScreen() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { tracking?.leftScreen() }
    val written = tracking?.written ?: 0
    LaunchedEffect(written) { if (written > 0) changed() }
    /** A trip deleted here: its events leave the phone's calendar too, quietly, and its recorded route goes. */
    fun tripDeleted(tripId: String) {
        calendarSync?.let { scope.launch { it.removeDeleted(tripId) } }
        tracking?.tripDeleted(tripId)
    }
    // The home-screen widgets follow the trips, notes, plan, stops, the theme and demo mode (a moment after a change, so
    // typing doesn't write each letter), and are written at every start (the Android app's WidgetUpdates).
    val widgetWriter = remember(widget) { widget?.let { WidgetWriter(it) } }
    LaunchedEffect(widgetWriter, revision, settings.look, settings.demoMode) {
        val writer = widgetWriter ?: return@LaunchedEffect
        if (revision > 0) kotlinx.coroutines.delay(1_500)
        writer.write(book, settings.look, settings.demoMode, if (followsClock) com.justdataplease.boringtravel.time.today() else today)
    }
    // A backup file being saved or restored shows on every screen.
    val backupFiles = LocalBackupFiles.current
    // The daily backup when the app is opened and 22:00 has passed without one (an iPhone runs its background task only
    // when iOS gives it time; Android's WorkManager runs it then). Only the real app follows the clock.
    val driveBackups = LocalDriveBackups.current
    val backgroundWork0 = LocalBackgroundWork.current
    fun dailyOnOpen() {
        val d = driveBackups ?: return
        if (!followsClock || !settings.onboardingDone || !settings.autoBackup || !settings.driveConnected) return
        if (backupFiles?.progress != null) return
        if (!com.justdataplease.boringtravel.backup.BackupSchedule.dueOnOpen(settings.lastBackupAt, com.justdataplease.boringtravel.time.nowMs())) return
        scope.launch {
            val ticket = backgroundWork0.beginBackgroundWork("Backing up to Google Drive")
            try {
                d.daily()
            } finally {
                ticket.endBackgroundWork()
                changed()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { dailyOnOpen() }
    // A reminder each time the app starts in demo mode, with the way out.
    var demoNoticeShown by remember { mutableStateOf(false) }
    LaunchedEffect(settings.onboardingDone) {
        if (demoNoticeShown || !settings.onboardingDone || !settings.demoMode) return@LaunchedEffect
        demoNoticeShown = true
        val result = snackbar.showSnackbar(DemoNotice.TEXT, actionLabel = DemoNotice.ACTION, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) {
            demo.set(false, today)
            changed()
        }
    }
    /** Demo mode on or off (Me › Demo mode, Exit demo): on seeds the examples that are missing. */
    fun setDemo(on: Boolean) {
        scope.launch {
            demo.set(on, today)
            changed()
        }
    }
    /** A trip of the traveller's own is coming: demo mode ends, and says so. */
    fun leaveDemo() {
        if (demo.leave()) {
            changed()
            say(DemoNotice.LEFT)
        }
    }

    // Copy from a link, one for the app (Android's AppGraph.siteImport): it reads and copies in the frame's own
    // coroutines, so leaving its screen doesn't stop it, and asks the phone for time while it works in the background.
    val web = LocalWeb.current
    val kit = LocalPlannerKit.current
    val backgroundWork = LocalBackgroundWork.current
    val google = com.justdataplease.boringtravel.google.LocalGoogle.current
    val siteImport = remember(book, web, kit, media, siteCopy, google) {
        siteImporter(scope, book, media.files, web, kit, onOwnTrip = { if (!it.isDemo) leaveDemo() }, changed = changed, setup = siteCopy, drive = google?.drive)
    }
    LaunchedEffect(siteImport, backgroundWork) { keepCopyAlive(siteImport, backgroundWork) }

    // Each planner on the stack keeps its chat while another screen covers it (Android's ViewModel on its back stack
    // entry); leaving it drops the chat and what wasn't saved, once the screen has faded out.
    val chats = remember { HashMap<String, PlannerChat>() }
    val leaving = remember { HashMap<String, PlannerChat>() }
    // Each entry's view-models (the community's screens, read in from Android: compat's ViewModel), kept while the entry
    // is on the stack or among a tab's pages, cleared once it is gone and has faded out.
    val viewModels = remember { HashMap<String, com.justdataplease.boringtravel.compat.ViewModelStore>() }
    /** A screen off the stack for good: its remembered state, its note and its chat go (the chat after the fade). */
    fun forget(entry: StackEntry) {
        saved.removeState(entry.key)
        noteIds.remove(entry.id)
        chats.remove(entry.key)?.let { chat ->
            leaving[entry.key] = chat
            scope.launch {
                kotlinx.coroutines.delay(SCREEN_FADE_OUT_MS + 100L)
                leaving.remove(entry.key)?.close()
            }
        }
        viewModels.remove(entry.key)?.let { store ->
            scope.launch {
                kotlinx.coroutines.delay(SCREEN_FADE_OUT_MS + 100L)
                store.clear()
            }
        }
    }
    fun back() {
        stack.pop()?.let(::forget)
    }
    fun backTo(place: Place) {
        while (stack.size > 1 && stack.last.place != place) back()
    }
    fun open(place: Place) {
        stack.push(place)
    }

    /**
     * Android's tab(route) (AppActions): everything over Trips comes off, kept as the pages of the tab they belong to
     * when they start with a tab (Android's popUpTo(start) { saveState = true }; pages straight over Trips aren't kept,
     * as Android never brings them back); then [to]'s kept pages come back (restoreState), else [to] opens.
     */
    fun showTab(to: HomeTab) {
        val above = stack.popToBottom()
        val from = (above.firstOrNull()?.place as? Place.Home)?.tab
        if (from != null && from != to) stack.tabPages[from] = above else above.forEach(::forget)
        val pages = stack.tabPages.remove(to)
        when {
            pages != null -> stack.entries.addAll(pages)
            to != HomeTab.Trips -> open(Place.Home(to))
        }
    }

    /**
     * Opens trip [tripId] on [tab] (else Plan), its plan on [day] when given (an epoch day, or PLAN_IDEAS), as Android's
     * AppActions.openTrip and openPlanDay: never two trips on the stack.
     */
    fun openTrip(tripId: String, tab: TripTab? = null, day: String? = null) {
        val openIndex = stack.indexOfLast { it is Place.Trip }
        val open = (stack.entries.getOrNull(openIndex)?.place as? Place.Trip)?.tripId
        when (TripNav.open(tripId, open)) {
            TripNav.Open.Reuse -> {
                while (stack.size - 1 > openIndex) back()
                if (tab != null) tripTab = tab
                if (day != null) tripDay = day
            }
            TripNav.Open.Replace, TripNav.Open.Push -> {
                if (openIndex >= 0) while (stack.size > openIndex) back()
                tripTab = TripNav.landingTab(tab?.key)
                tripDay = day
                open(Place.Trip(tripId, tab))
            }
        }
    }

    /**
     * The trip's plan on [day] (null: its Ideas), as Android's AppActions.openPlanDay: after a shared link was added there,
     * or a day picked on the Calendar tab.
     */
    fun openPlanDay(tripId: String, day: LocalDate?) {
        openTrip(tripId, TripTab.Plan, day?.toEpochDay()?.toString() ?: PLAN_IDEAS)
    }

    /** Write today (the Android app's AppActions.writeInTrip): the trip's Journal, then Write over it, dictating ([dictate]). */
    fun writeInTrip(tripId: String, dictate: Boolean = false) {
        openTrip(tripId, TripTab.Journal)
        open(Place.Write(tripId, dictate = dictate))
    }

    /**
     * The camera now; the photo becomes a new note in [tripId] (outside trips when null), opened in Write. Closed
     * without a photo: nothing, and the app stays where it was (a widget's Camera too).
     */
    fun takePhoto(tripId: String?) {
        if (!media.picker.hasCamera) {
            say("No camera app on this phone.")
            return
        }
        media.picker.takePhoto { shot -> if (shot != null) open(Place.Write(tripId, shot = shot)) }
    }

    /** The open trip is gone (deleted): back to where it was opened from. */
    fun closeTrip() {
        while (stack.size > 1 && stack.places.any { it is Place.Trip }) back()
    }

    /** What the bar sees of the screen on top (the Android app's AppNav.whereOf). */
    fun where(): Where = when (val p = stack.last.place) {
        is Place.Home -> Where.Home(p.tab)
        Place.LooseNotes -> Where.Under(HomeTab.Trips)
        is Place.Trip -> Where.InTrip(p.tripId, tripTab)
        // A place page keeps the bar it opened over: its trip's, else the tab's (the Map tab).
        is Place.PlacePage -> Where.Place(
            p.tripId,
            stack.places.lastOrNull { it is Place.Trip }?.let { Where.InTrip((it as Place.Trip).tripId, tripTab) }
                ?: (stack.places.lastOrNull { it is Place.Home } as? Place.Home)?.let { Where.Home(it.tab) },
        )
        // A post, a profile and a follow list belong under Community (Android's AppNav.whereOf).
        is Place.CommunityPost, is Place.CommunityProfile, is Place.CommunityFollows -> Where.Under(HomeTab.Community)
        else -> Where.Elsewhere
    }

    /**
     * A tap on the bar outside a trip (the Android app's AppActions.homeTab): switch, the tab left keeping its pages and
     * the tapped one coming back with its own ([showTab]), or back to the tab from a page under it.
     */
    fun homeTab(tapped: HomeTab, where: Where) {
        when (TripNav.homeTap(where, tapped)) {
            TripNav.HomeTap.Stay -> Unit
            // Back to the tab when it is under the page (Android's popBackStack(route)); a post opened from a link over
            // another tab has none under it: the tab then shows as a switch would (Android's tab(route)).
            TripNav.HomeTap.BackToTab -> if (tapped == HomeTab.Trips || stack.places.contains(Place.Home(tapped))) backTo(Place.Home(tapped)) else showTab(tapped)
            // Trips stays at the bottom, as Android's start screen: back from another tab goes there.
            TripNav.HomeTap.Switch -> showTab(tapped)
        }
    }

    /** A tap on the trip's bar: the trip's sections switch in place (one trip on the stack, back leaves it). */
    fun tripTabTap(tapped: TripTab, where: Where) {
        when (TripNav.tripTap(where, tapped)) {
            TripNav.TripTap.Stay -> Unit
            TripNav.TripTap.SetTab -> tripTab = tapped
            TripNav.TripTap.BackToTrip -> {
                while (stack.size > 1 && stack.last.place !is Place.Trip) back()
                tripTab = tapped
            }
        }
    }

    /**
     * The planner saved into trip [tripId] (the Android app's AppActions.plannerDone): back to the trip it came from, on
     * its Plan; else the trip in the planner's place, and in New trip's too when it started there.
     */
    fun plannerDone(tripId: String) {
        back()
        val under = stack.last.place
        if (under is Place.Trip && under.tripId == tripId) {
            tripTab = TripTab.Plan
            return
        }
        if (under == Place.NewTrip || under is Place.NewTripFrom) back()
        openTrip(tripId, TripTab.Plan)
    }

    /** Copy from a link's Done: back to Trips (the Android app's actions.tab(TripsHome), [showTab]). */
    fun toTrips() {
        showTab(HomeTab.Trips)
    }

    // A widget's button or a Home Screen quick action (the Android app's AppNav pendingAction): Write for today's trip
    // (outside trips when none is on), the camera, Write dictating, New trip, or today's trip on its Journal. In demo
    // mode today's trip is the example's. Before the first start is done they are dropped, as on Android.
    val links = LocalAppLinks.current
    val pendingLink = links?.pending
    LaunchedEffect(pendingLink) {
        val action = pendingLink ?: return@LaunchedEffect
        links.take()
        // A shared trip's join link (round 26): its sheet waits for Welcome, as on Android (post links before it are dropped).
        Deeplink.joinOf(action)?.let { link ->
            com.justdataplease.boringtravel.together.ShareLink.find(link)?.let { pendingJoin = it }
            return@LaunchedEffect
        }
        if (!settings.onboardingDone) return@LaunchedEffect
        // A community post's link (a universal link, or boringtravel://post/#<id>): the post opens (Android's AppNav).
        Deeplink.postOf(action)?.let { postId ->
            if (stack.last.place != Place.CommunityPost(postId)) open(Place.CommunityPost(postId))
            return@LaunchedEffect
        }
        val day = if (followsClock) com.justdataplease.boringtravel.time.today() else today
        val trip = com.justdataplease.boringtravel.ui.trips.HomeStats.sections(TripsHomeData.visibleTrips(book, settings.demoMode), day).now
        when (action) {
            Deeplink.WRITE -> open(Place.Write(trip?.id))
            Deeplink.CAMERA, Deeplink.PHOTO -> takePhoto(trip?.id)
            Deeplink.VOICE -> open(Place.Write(trip?.id, dictate = true))
            Deeplink.NEW_TRIP -> open(Place.NewTrip)
            Deeplink.TODAY -> if (trip != null) openTrip(trip.id, TripTab.Journal)
            Deeplink.TRACKING -> open(Place.Tracking)
        }
    }

    // Sharing into the app (round 27). What the share extension left in the App Group's folder is taken at the start,
    // each time the app is back on screen (ON_RESUME: also after a share sheet shown over the app itself) and when the
    // scene says it is active (Swift's scenePhase), then sorted as Android's MainActivity sorts an intent: a join link
    // for the Join sheet, a link for Add to plan, an itinerary or pictures for the planner. A newer share of the same kind
    // takes the older one's place, as a new intent does on Android.
    val shareInbox = LocalShareInbox.current
    fun takeShares() {
        val inbox = shareInbox ?: return
        scope.launch {
            val arrivals = kotlinx.coroutines.withContext(MediaWork) { inbox.take() }
            for (a in arrivals) when (a) {
                is ShareArrival.Join -> pendingJoin = a.link
                is ShareArrival.Place -> pendingPlace = a.route
                is ShareArrival.Import -> {
                    // A share for the planner replaced before it was opened: its copies go.
                    pendingImport?.let { old -> (old.pictures + listOfNotNull(old.uri)).filter { it.startsWith("picked/") }.forEach(media.files::delete) }
                    pendingImport = a.share
                }
            }
        }
    }
    LaunchedEffect(shareInbox, shareInbox?.asked) { takeShares() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { takeShares() }
    // A share opens once Welcome is done (Android's AppNav: shareReady).
    val shareReady = stack.last.place != Place.Welcome
    // An itinerary or pictures: the planner, as a new trip, with them in its box (Android: navigate(Planner()) with the
    // share in its saved state).
    LaunchedEffect(pendingImport, shareReady) {
        val share = pendingImport ?: return@LaunchedEffect
        if (!shareReady) return@LaunchedEffect
        pendingImport = null
        open(Place.Planner(shared = share))
    }
    // A few words without a link: a short note, nothing opens (Android's snackbar).
    LaunchedEffect(pendingPlace, shareReady) {
        if (pendingPlace != ShareRoute.NoLink || !shareReady) return@LaunchedEffect
        pendingPlace = null
        say("Nothing to add: share a link")
    }

    // The community (round 24): its screens are Android's, read in; what they reach of Android's AppGraph and AppActions
    // is this app's (ios/CommunityNav.kt). Writes made without a connection go each time the app comes back.
    val community = com.justdataplease.boringtravel.community.LocalCommunity.current
    val worldSource = LocalWorld.current
    val savedClipsHere = LocalSavedClips.current
    val appScopeHere = LocalAppScope.current
    val work = LocalBackgroundWork.current
    // Posting (round 25) reads the app's photos and saved clips, and keeps the trip-to-post links under its files.
    remember(community, media, book, work) { community?.usePhone(media.files, media.frames, { id -> book.media(id) }, work) }
    val communityGraph = remember(community, settings, book, google, media) {
        community?.let {
            CommunityGraph(
                it, CommunitySettings(settings),
                CommunityTrips(
                    book, onNewOwnTrip = { leaveDemo() }, changed = changed, world = worldSource,
                    clips = { savedClipsHere?.listNow().orEmpty() }, demoMode = { settings.demoMode },
                ),
                google, media.files, appScopeHere ?: scope,
            )
        }
    }
    val communityActions = CommunityActions(
        goBack = ::back, open = ::open, top = { stack.entries.lastOrNull()?.place },
        openTripOn = { id, tab -> openTrip(id, tab) }, say = ::say,
    )
    LifecycleEventEffect(Lifecycle.Event.ON_START) { community?.let { c -> scope.launch { c.flush() } } }

    // Travel together (round 26): the shared trips sync and send as on Android (together/Together.kt); their sheets are
    // Android's own (ui/together, read in) on this app's AppGraph (ios/TogetherNav.kt).
    val together = LocalTogether.current
    remember(together, scope) { together?.onRowsChanged = { scope.launch { changed() } } }
    LaunchedEffect(together) {
        val t = together ?: return@LaunchedEffect
        // Each change to a shared trip from now on queues its send; sends a closed app left go now.
        t.start(writes ?: androidx.compose.runtime.snapshotFlow { revision })
        // Shared trips catch up with the other members whenever the app opens (Android's MainActivity: syncAll).
        if (followsClock) t.syncAll()
    }
    // Back on screen, a send waiting after a failure goes at once; away, the sends still waiting ask for time.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { together?.cameBack() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { together?.wentAway() }
    // Someone joined a shared trip by its link: they are added without a tap here, so say who (Android's AppNav).
    LaunchedEffect(together) {
        together?.joined?.collect { j ->
            val title = book.snapshot.trips.firstOrNull { it.id == j.tripId }?.title.orEmpty()
            say(com.justdataplease.boringtravel.ui.together.TogetherText.joined(j.names, title))
        }
    }
    val deletions = remember(book, media, together, calendarSync) {
        TripDeletions(book, media.files, together, calendarGone = { calendarSync?.removeDeleted(it) }, tripGone = ::tripDeleted, changed = changed)
    }
    val togetherGraph = remember(together, settings, book, google, deletions) {
        together?.let {
            TogetherGraph(
                it, TogetherSettings(settings), TogetherTrips(book) { revision }, deletions,
                TogetherDemo { on ->
                    demo.set(on, today)
                    changed()
                },
                scope, google,
            )
        }
    }

    // Add to plan's AppGraph (round 27): the trips and plans as they change, the links' look, the frame's day.
    val shareGraph = remember(book, web, scope) {
        ShareGraph(book, web, scope, { revision }, changed) { if (followsClock) com.justdataplease.boringtravel.time.today() else today }
    }

    val actions = Actions(
        changed = changed, back = ::back, open = ::open,
        // A day picked on the Calendar tab: the trip's plan on that day (Android's openPlanDay); else the trip as it opens.
        openTrip = { id, day -> if (day != null) openPlanDay(id, day) else openTrip(id) }, closeTrip = ::closeTrip,
        welcomed = {
            settings.onboardingDone = true
            stack.reset(Place.Trips).forEach(::forget)
        },
        // Android's ideaUsed: the new trip takes the place of Trip ideas and of the New trip that led there, so Back
        // goes where that started (Trips or the Calendar).
        leaveNewTrip = {
            while (stack.size > 1 && stack.last.place.let { it is Place.Idea || it == Place.Ideas || it == Place.NewTrip || it is Place.NewTripFrom }) back()
        },
        writeInTrip = { writeInTrip(it) }, dictateInTrip = { writeInTrip(it, dictate = true) }, takePhoto = ::takePhoto, setDemo = ::setDemo, leaveDemo = ::leaveDemo,
        say = ::say, toTrips = ::toTrips,
        openTripOn = { id, tab -> openTrip(id, tab) },
        newTrip = { open(Place.NewTrip) },
        tripDeleted = ::tripDeleted,
        plannerDone = ::plannerDone,
        chat = { key, make -> chats[key] ?: leaving[key] ?: chats.getOrPut(key, make) },
        scope = scope,
        noteId = { entry, note -> noteIds[entry] = note },
        community = communityGraph,
        communityActions = communityActions,
        // New trip's "Start from a community trip": the Community tab (Android's actions.tab(CommunityHome)).
        toCommunity = { showTab(HomeTab.Community) },
        // Trips' "Join a friend's trip": the pasted link opens the Join sheet (Android's AppActions.join).
        join = { typedJoin = it },
        together = together,
    )

    // The status bar's icons on the theme's page (Android's MainActivity.SystemBars): light on Trail, dark on the four
    // light themes. The dark studios turn them light while they show, through their own Android code (ui/studio/Studio.kt).
    DisposableEffect(settings.look) {
        SystemBars.set(!settings.look.dark)
        onDispose { }
    }

    // Where the app is, for the phone to keep with its window (iPhone: SwiftUI's scene storage), after every change.
    if (keep != null) {
        val text by remember {
            derivedStateOf { AppRestore.encodeKey(stack, tripTab, tripDay, noteIds, pendingJoin?.url, pendingPlace, pendingImport) }
        }
        LaunchedEffect(text) { keep(text) }
    }

    // The screens' context (compat's, for the community's screens read in from Android): their toasts are the app's
    // snackbar, their share intent the phone's share sheet.
    val phone = LocalPhone.current
    val screenContext = remember(phone) {
        com.justdataplease.boringtravel.compat.ScreenContext(MemorySettingsFile(), { say(it) }) { subject, text -> phone.share(subject, text) }
    }
    CompositionLocalProvider(
        LocalSiteImport provides siteImport, LocalSay provides ::say, com.justdataplease.boringtravel.compat.LocalContext provides screenContext,
        LocalTogetherGraph provides togetherGraph,
    ) { BoringTravelTheme(settings.look, settings.fontSet, settings.textFont) {
        val where = where()
        val bar = TripNav.bar(where)
        // The page's paper runs under the status bar, the bar and the home indicator. Sideways the screens keep clear of
        // the Dynamic Island's side (iPhone's safe area; Android's camera hole is a dot its screens draw over), the
        // paper, or a studio's dark, running on under it.
        val studio = stack.last.place.let { it is Place.Clip || it is Place.Book }
        val ground = if (studio) Modifier.background(StudioColors.bg) else Modifier.paper(Bt.tokens)
        Box(Modifier.fillMaxSize().then(ground).padding(top = frame.calculateTopPadding())) {
            Column(
                Modifier.fillMaxSize()
                    .padding(start = frame.calculateLeftPadding(LayoutDirection.Ltr), end = frame.calculateRightPadding(LayoutDirection.Ltr))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .then(if (bar == BarMode.Hidden) Modifier.padding(bottom = frame.calculateBottomPadding()) else Modifier),
                ) {
                    // The system's Back (iPhone: the swipe from the left edge) goes back a screen, as Android's NavHost;
                    // a screen's own Back handlers, composed after this one, come first (Write, New trip, the editors).
                    BackHandler(enabled = stack.size > 1) { back() }
                    // Screens fade into each other as Android's NavHost fades them: 160 ms in, 120 ms out.
                    AnimatedContent(
                        targetState = stack.last,
                        contentKey = { it.id },
                        transitionSpec = { (fadeIn(tween(SCREEN_FADE_IN_MS)) togetherWith fadeOut(tween(SCREEN_FADE_OUT_MS))).using(null) },
                        label = "Screens",
                    ) { entry ->
                        saved.SaveableStateProvider(entry.key) {
                            // The entry's view-models: the same while it is on the stack (also while it fades out).
                            val store = remember(entry.key) {
                                viewModels.getOrPut(entry.key) { com.justdataplease.boringtravel.compat.ViewModelStore(scope.coroutineContext) }
                            }
                            CompositionLocalProvider(com.justdataplease.boringtravel.compat.LocalViewModelStore provides store) {
                                Screen(entry.place, book, settings, today, revision, tripTab, tripDay, version, actions, entry.key, entry.id)
                            }
                        }
                    }
                    BtSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                    // A shared trip's link (round 26): join it, or add the friend who sent theirs back; it waits for
                    // Welcome (Android's AppNav: shareReady).
                    val join = pendingJoin ?: typedJoin
                    if (join != null && togetherGraph != null && stack.last.place != Place.Welcome) {
                        com.justdataplease.boringtravel.ui.together.JoinSheet(
                            togetherGraph, join,
                            onOpenTrip = { tripId ->
                                pendingJoin = null
                                typedJoin = null
                                changed()
                                openTrip(tripId)
                            },
                            onClose = {
                                pendingJoin = null
                                typedJoin = null
                            },
                        )
                    }
                    // A link shared from another app (round 27): Android's own Add to plan, over the screen; it waits for
                    // Welcome (Android's AppNav: shareReady).
                    val shared = pendingPlace
                    if (shared is ShareRoute.Place && shareReady) {
                        com.justdataplease.boringtravel.ui.share.ShareToPlanSheet(
                            shareGraph, shared.link,
                            onAdded = { tripId, day ->
                                pendingPlace = null
                                openPlanDay(tripId, day)
                            },
                            onNewTrip = {
                                pendingPlace = null
                                open(Place.NewTrip)
                            },
                            onDismiss = { pendingPlace = null },
                            onCopy = {
                                pendingPlace = null
                                open(Place.CopySite(address = shared.link.url))
                            },
                            onList = { tripId ->
                                pendingPlace = null
                                open(Place.CopySite(tripId, shared.link.url))
                            },
                        )
                    }
                    backupFiles?.progress?.let { p ->
                        if (stack.last.place != Place.Backup) {
                            ProgressLine(p, Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Bt.colors.surface).padding(horizontal = 20.dp, vertical = 8.dp))
                        }
                    }
                }
                if (bar != BarMode.Hidden) BtNavBar(
                    bar,
                    onSelect = { i ->
                        when (bar) {
                            is BarMode.Trip -> tripTabTap(TripTab.entries[i], where)
                            is BarMode.Outside -> homeTab(HomeTab.entries[i], where)
                            BarMode.Hidden -> Unit
                        }
                    },
                    bottom = frame.calculateBottomPadding(),
                )
            }
        }
    } }
}

/** What the screens can ask of the app's frame. */
private class Actions(
    val changed: () -> Unit,
    val back: () -> Unit,
    val open: (Place) -> Unit,
    val openTrip: (String, LocalDate?) -> Unit,
    val closeTrip: () -> Unit,
    val welcomed: () -> Unit,
    val leaveNewTrip: () -> Unit,
    val writeInTrip: (String) -> Unit,
    val dictateInTrip: (String) -> Unit,
    val takePhoto: (String) -> Unit,
    val setDemo: (Boolean) -> Unit,
    val leaveDemo: () -> Unit,
    /** A line in passing (Android's toasts: "Added to the plan"). */
    val say: (String) -> Unit,
    val toTrips: () -> Unit,
    val openTripOn: (String, TripTab?) -> Unit,
    val newTrip: () -> Unit,
    /** A trip was deleted here (its calendar events go). */
    val tripDeleted: (String) -> Unit,
    /** The planner saved into a trip: that trip on its Plan, where the planner was. */
    val plannerDone: (String) -> Unit,
    /** The chat of the planner at stack entry key, made by the second argument the first time. */
    val chat: (String, () -> PlannerChat) -> PlannerChat,
    /** The frame's coroutines, which outlive a covered screen (a planner's chat). */
    val scope: CoroutineScope,
    /** The note a Write on the stack (its entry's id) is writing, kept for a restore. */
    val noteId: (entry: Int, note: String) -> Unit = { _, _ -> },
    /** The community's AppGraph (round 24); null where the app has no community at all. */
    val community: CommunityGraph? = null,
    /** Where the community's buttons go (Android's AppActions, as its screens call them). */
    val communityActions: CommunityActions? = null,
    /** The Community tab, as the bar switches to it. */
    val toCommunity: () -> Unit = {},
    /** A friend's trip link pasted on Trips: the Join sheet (round 26). */
    val join: (com.justdataplease.boringtravel.together.ShareLink) -> Unit = {},
    /** The shared trips (round 26): a trip left here isn't deleted with its route and files. */
    val together: com.justdataplease.boringtravel.together.Together? = null,
) {
    /** Where the globe and Your world go, and how they say the trips changed. */
    val world = WorldNav(
        openTrip = { id -> openTrip(id, null) },
        newTrip = newTrip,
        placesBeen = { open(Place.PlacesBeen) },
        shareWorld = { open(Place.ShareWorld) },
        changed = changed,
    )

    /** What the maps open: a trip's Map tab, a place page, a note, the trip video. */
    val maps = MapActions(
        openTripMap = { id -> openTripOn(id, TripTab.Map) },
        openPlace = { tripId, itemId, stopId -> open(Place.PlacePage(tripId, itemId = itemId, stopId = stopId)) },
        openNote = { tripId, entryId -> open(Place.Note(tripId, entryId)) },
        clip = { tripId -> open(Place.Clip(tripId)) },
    )
}

/** What a button for something still to come says (the book, the video's export). */
const val NOT_ON_IPHONE = "Not on iPhone yet."

/** Android's NavHost fades: a screen fades in over 160 ms and the one it covers out over 120 ms. */
const val SCREEN_FADE_IN_MS = 160
const val SCREEN_FADE_OUT_MS = 120

/** The screen for [place]; what its buttons do comes from [BoringTravelApp]. */
@Composable
private fun Screen(
    place: Place,
    book: TripBook,
    settings: AppSettings,
    today: LocalDate,
    revision: Int,
    tripTab: TripTab,
    tripDay: String?,
    version: String,
    a: Actions,
    key: String,
    entryId: Int,
) {
    when (place) {
        Place.Welcome -> WelcomeScreen(settings, onFinish = a.welcomed)
        is Place.Home -> when (place.tab) {
            HomeTab.Trips -> TripsHome(
                book, today, revision,
                onNewTrip = { a.open(Place.NewTrip) },
                onOpenIdeas = { a.open(Place.Ideas) },
                onOpenIdea = { a.open(Place.Idea(it)) },
                onOpenTrip = { a.openTrip(it, null) },
                onLooseNotes = { a.open(Place.LooseNotes) },
                settings = settings,
                onEditTrip = { a.open(Place.EditTrip(it)) },
                onWriteToday = a.writeInTrip,
                onTakePhoto = a.takePhoto,
                onExitDemo = { a.setDemo(false) },
                onDictate = a.dictateInTrip,
                onOpenBackup = { a.open(Place.Backup) },
                onRestored = a.changed,
                onJoin = a.join,
            )
            HomeTab.Calendar -> TripsCalendarScreen(
                book, today, revision,
                onOpenDay = { tripId, day -> a.openTrip(tripId, day) },
                onOpenTrip = { a.openTrip(it, null) },
                onAddDates = { a.open(Place.EditTrip(it)) },
                onNewTrip = { a.open(Place.NewTrip) },
                onNewTripFrom = { a.open(Place.NewTripFrom(it)) },
                demoMode = settings.demoMode,
                onExitDemo = { a.setDemo(false) },
            )
            HomeTab.Map -> MapHome(book, settings, today, revision, a.changed, a.maps, a.world)
            // The community (round 24): the Android app's own screen, read in.
            HomeTab.Community -> {
                val g = a.community
                val acts = a.communityActions
                if (g != null && acts != null) com.justdataplease.boringtravel.ui.community.CommunityScreen(g, acts) else TabToCome("Community")
            }
            HomeTab.Me -> MeScreen(
                settings, version, onDemo = a.setDemo, onImportFromLink = { a.open(Place.CopySite()) }, onBackup = { a.open(Place.Backup) },
                onAi = { a.open(Place.AiSettings) }, onTracking = { a.open(Place.Tracking) },
                onCommunityProfile = { a.communityActions?.editCommunityProfile() },
            )
        }
        Place.LooseNotes -> LooseNotesScreen(book, revision, onBack = a.back, onOpenNote = { a.open(Place.Note(null, it)) })
        Place.NewTrip, is Place.NewTripFrom -> TripFormScreen(
            book, tripId = null,
            onBack = a.back,
            onSaved = { tripId ->
                a.changed()
                a.back()
                a.openTrip(tripId, null)
            },
            onReadyMade = { a.open(Place.Ideas) },
            onPlanner = { start -> a.open(Place.Planner(null, start)) },
            onCommunity = a.toCommunity,
            startOn = (place as? Place.NewTripFrom)?.start,
            // Whatever opened New trip, demo mode ends first ("Demo mode off").
            onNewTripShown = a.leaveDemo,
            settings = settings,
        )
        Place.Ideas -> TripIdeasScreen(onBack = a.back, onPick = { a.open(Place.Idea(it)) })
        is Place.Idea -> TripIdea(
            book, place.destinationId,
            onBack = a.back,
            onUsed = { tripId ->
                // A trip of the traveller's own: demo mode ends.
                a.leaveDemo()
                a.changed()
                a.leaveNewTrip()
                a.openTrip(tripId, null)
            },
        )
        is Place.Trip -> TripPage(
            book, place.tripId, today, revision,
            onBack = a.back,
            onChanged = a.changed,
            onEdit = { a.open(Place.EditTrip(place.tripId)) },
            onOpenNote = { a.open(Place.Note(place.tripId, it)) },
            onWrite = { a.open(Place.Write(place.tripId)) },
            onDeleted = {
                // A shared trip left here keeps its route and files for joining again (its calendar events went already).
                if (a.together?.isLeft(place.tripId) != true) a.tripDeleted(place.tripId)
                a.changed()
                a.closeTrip()
            },
            tab = tripTab,
            planDay = tripDay,
            settings = settings,
            onPlace = { itemId -> a.open(Place.PlacePage(place.tripId, itemId = itemId)) },
            onAddFromLink = { a.open(Place.CopySite(place.tripId)) },
            maps = a.maps,
            onWriteNote = { entryId -> a.open(Place.Write(place.tripId, entryId)) },
            onShare = { a.open(Place.Share(place.tripId)) },
            onSay = a.say,
            onChat = { a.open(Place.Planner(place.tripId)) },
            onClip = { a.open(Place.Clip(place.tripId)) },
            // The Media tab's Add media: a new note with the photo library open (Android's write with attachPhotos).
            onAddMedia = { a.open(Place.Write(place.tripId, pick = true)) },
            onBook = { a.open(Place.Book(place.tripId)) },
            onWriteAbout = { itemId -> a.open(Place.Write(place.tripId, planItemId = itemId)) },
            // Round 25: Post to community, and the trip's Community post when this phone posted it (Android's TripScreen).
            onPostToCommunity = { a.communityActions?.postToCommunity(place.tripId) },
            postSheet = postSheetOf(a, place.tripId),
        )
        // The book (round 21): photos brought in from the phone join the trip, so the trip's screens read it again.
        is Place.Book -> BookScreen(book, place.tripId, settings, onBack = a.back, onChanged = a.changed)
        // The community's pages (round 24): the Android app's own screens, read in.
        is Place.CommunityPost -> communityPage(a) { g, acts -> com.justdataplease.boringtravel.ui.community.PostScreen(g, acts, place.postId) }
        is Place.CommunityProfile -> communityPage(a) { g, acts -> com.justdataplease.boringtravel.ui.community.ProfileScreen(g, acts, place.uid) }
        is Place.CommunityFollows -> communityPage(a) { g, acts -> com.justdataplease.boringtravel.ui.community.FollowListScreen(g, acts, place.uid, place.followers) }
        Place.CommunityProfileEdit -> communityPage(a) { g, acts -> com.justdataplease.boringtravel.ui.community.ProfileEditScreen(g, acts) }
        // Post to community (round 25): the Android app's own composer, read in.
        is Place.Compose -> communityPage(a) { g, acts -> com.justdataplease.boringtravel.ui.community.ComposeScreen(g, acts, place.route) }
        is Place.Clip -> ClipScreen(
            book, place.tripId, settings,
            onBack = a.back,
            onOpenPlan = { a.openTripOn(place.tripId, TripTab.Plan) },
            onSay = a.say,
            // A leg's travel saved from the video (the Route tool) shows in the plan, and one changed on the plan
            // re-plans the video.
            onChanged = a.changed,
            revision = revision,
            // Export's Connect Drive: Me › Backup & sync, as Android opens its backup settings.
            onConnectDrive = { a.open(Place.Backup) },
        )
        is Place.Share -> ShareScreen(book, place.tripId, settings, revision, onBack = a.back, onSay = a.say, onMakeBook = { a.open(Place.Book(place.tripId)) })
        is Place.Planner -> {
            val kit = LocalPlannerKit.current ?: return
            val files = LocalMediaKit.current.files
            val side = remember(kit, book, files) { KitPlannerSide(kit, book, files) }
            val chat = a.chat(key) { PlannerChat(side, place.tripId, a.scope) }
            PlannerScreen(
                side, place.tripId, place.start, settings.dictationLanguage,
                onBack = a.back,
                onAiSettings = { a.open(Place.AiSettings) },
                onCopySite = { address, tripId -> a.open(Place.CopySite(tripId, address)) },
                onSaved = { tripId, summary ->
                    // A trip of the traveller's own: demo mode ends (only the examples show in it).
                    if (book.trip(tripId)?.isDemo == false) a.leaveDemo()
                    a.changed()
                    a.say(summary)
                    a.plannerDone(tripId)
                },
                revision = revision,
                onReport = rememberAiReport(a.say),
                chatAtStart = chat,
                shared = place.shared,
            )
        }
        Place.AiSettings -> LocalPlannerKit.current?.let { kit -> AiScreen(kit.ownAi, onBack = a.back, onSay = a.say) }
        Place.Tracking -> LocalTracking.current?.let { tracking -> TrackingScreen(tracking, settings, onBack = a.back) }
        is Place.PlacePage -> PlaceScreen(
            book, place.tripId, place.itemId, place.stopId, revision,
            onBack = a.back,
            onChanged = a.changed,
            onSay = a.say,
            onOpenNote = { a.open(Place.Note(place.tripId, it)) },
            onWriteAbout = { itemId -> a.open(Place.Write(place.tripId, planItemId = itemId)) },
        )
        Place.PlacesBeen -> PlacesBeenScreen(book, today, revision, settings.demoMode, onBack = a.back, onChanged = a.changed)
        Place.ShareWorld -> ShareWorldScreen(book, today, revision, settings.demoMode, onBack = a.back)
        Place.Backup -> BackupScreen(settings, onBack = a.back, onChanged = a.changed)
        is Place.CopySite -> LocalSiteImport.current?.let { importer ->
            CopySiteScreen(
                importer, book, revision, address = place.address, tripId = place.tripId,
                onBack = a.back,
                onOpenTrip = { id, notes -> a.openTripOn(id, if (notes) TripTab.Journal else TripTab.Plan) },
                onDone = a.toTrips,
            )
        }
        is Place.EditTrip -> TripFormScreen(
            book, place.tripId,
            onBack = a.back,
            onSaved = {
                a.changed()
                a.back()
            },
            onDeleted = {
                if (a.together?.isLeft(place.tripId) != true) a.tripDeleted(place.tripId)
                a.changed()
                a.closeTrip()
            },
            settings = settings,
        )
        is Place.Note -> NoteScreen(
            book, place.entryId, revision,
            onBack = a.back,
            onEdit = { a.open(Place.Write(place.tripId, place.entryId)) },
            onDeleted = {
                a.changed()
                a.back()
            },
        )
        is Place.Write -> WriteScreen(
            book, place.tripId, place.entryId, today,
            onDone = {
                a.changed()
                a.back()
            },
            shot = place.shot,
            dictate = place.dictate,
            pickAtStart = place.pick,
            dictationLanguage = settings.dictationLanguage,
            onNoteId = { a.noteId(entryId, it) },
            planItemId = place.planItemId,
            // New photos sit as the note's trip says (Edit trip › Photos in notes; Android's PhotoDefaults.forTrip).
            photoDefaults = { trip -> PhotoDefaults.of(settings.tripPhotoStyles.of(trip)) },
        )
    }
}

/**
 * The trip's Community post sheet (Android's PostSyncSheet, read in) when this phone posted trip [tripId] and keeps it
 * linked to its post (the links stay on this phone); null otherwise, and the trip's ⋮ has no Community post.
 */
@Composable
private fun postSheetOf(a: Actions, tripId: String): (@Composable (onClose: () -> Unit) -> Unit)? {
    val g = a.community ?: return null
    val acts = a.communityActions ?: return null
    val links by g.postSync.links.all.collectAsState()
    if (links[tripId] == null) return null
    return { onClose -> com.justdataplease.boringtravel.ui.community.PostSyncSheet(g, acts, tripId, onClose) }
}

/** A community page with the app's community and its buttons; nothing where the app has none. */
@Composable
private fun communityPage(a: Actions, content: @Composable (CommunityGraph, CommunityActions) -> Unit) {
    val g = a.community ?: return
    val acts = a.communityActions ?: return
    content(g, acts)
}

/** Snackbars in reverse colours, the page colour on ink (the Android app's SnackbarHost in AppNav and PlanTab). */
@Composable
fun BtSnackbarHost(host: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(host, modifier) { data ->
        val c = Bt.colors
        Snackbar(
            modifier = Modifier.padding(12.dp),
            action = data.visuals.actionLabel?.let { label ->
                { TextButton(onClick = data::performAction) { Text(label, style = Bt.text.button, color = c.bg) } }
            },
            containerColor = c.ink,
            contentColor = c.bg,
        ) { Text(data.visuals.message, style = Bt.text.body, color = c.bg) }
    }
}
