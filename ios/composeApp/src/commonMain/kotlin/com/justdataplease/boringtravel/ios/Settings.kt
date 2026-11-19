package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.BackupPreferences
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.data.settings.TripsLook
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.ui.theme.FontSet
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.ui.theme.TextFont
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where a phone keeps its settings: one text per key, null when never set. NSUserDefaults on iPhone, a file on the
 * desktop, [MemorySettingsFile] in tests.
 */
interface SettingsFile {
    fun read(key: String): String?

    /** Keeps [value] under [key]; null takes the key away. */
    fun write(key: String, value: String?)
}

/** A [SettingsFile] in memory, for tests and previews. */
class MemorySettingsFile(values: Map<String, String> = emptyMap()) : SettingsFile {
    private val values = HashMap(values)

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/**
 * How a trip's Plan tab shows the plan (the Android app's data/settings/Settings.kt PlanView): the List (collapsed or
 * expanded, [AppSettings.planExpanded]) or the Calendar. Up to the Android app's 0.1.0-preview.6 there was a third
 * view, Timeline: a stored "timeline" reads as the List, expanded.
 */
enum class PlanView(val id: String, val label: String) {
    List("list", "List"), Calendar("calendar", "Calendar");

    companion object {
        /** The old Timeline's id, kept in settings and backups made before it became the expanded List. */
        const val TIMELINE = "timeline"

        fun of(id: String?): PlanView = entries.firstOrNull { it.id == id } ?: List

        /** Whether the List shows every day in full: the choice made, else what an old Timeline pick meant. */
        fun expanded(viewId: String?, expanded: Boolean?): Boolean = expanded ?: (viewId == TIMELINE)
    }
}

/**
 * The Map tab's two views (the Android app's data/settings/Settings.kt MapView), under the same ids. As on Android, the
 * globe until Map is picked.
 */
enum class MapView(val id: String) {
    Globe("globe"), Map("map");

    companion object {
        fun of(id: String?): MapView = entries.firstOrNull { it.id == id } ?: Globe
    }
}

/**
 * The trip map's look in the Android app's own form (clip/BaseMap.kt MapStylePrefs, written into a backup by
 * backup/PrefsBackup.kt): the phone's "map_style" settings file as JSON text, {"trip_map":{"t":"s","v":"terrain"}}.
 * Only "trip_map" is read and written here; the trip video's "clip" and anything else stay as they are.
 */
object MapStylePrefs {
    const val TRIP_MAP = "trip_map"

    /** The trip map's look in [text]: Street when there is none or it isn't one of the trip map's three. */
    fun tripMap(text: String?): MapStyle {
        val saved = parse(text)?.get(TRIP_MAP) as? JsonObject
        val id = (saved?.get("v") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return MapStyle.of(id).takeIf { it in MapStyle.tripMap } ?: MapStyle.Street
    }

    /** [text] with the trip map's look set to [style], every other key kept. */
    fun withTripMap(text: String?, style: MapStyle): String {
        val kept = parse(text).orEmpty()
        val entry = JsonObject(mapOf("t" to JsonPrimitive("s"), "v" to JsonPrimitive(style.id)))
        return JsonObject((kept + (TRIP_MAP to entry)).entries.sortedBy { it.key }.associate { it.key to it.value }).toString()
    }

    /** Whether [text] is such a file (a JSON object, not too long), as PrefsBackup reads one. */
    fun isText(text: String): Boolean = text.length <= 200_000 && parse(text) != null

    private fun parse(text: String?): JsonObject? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
}

/**
 * The app's settings, as the Android app keeps them (data/settings/Settings.kt) as far as this phone has them: the
 * theme, the two font choices, Trips look, Plan view and the List expanded or not, demo mode, and whether the first
 * start is done. Each is Compose state, so the screens follow a change at once, and is written to [file] when it
 * changes.
 *
 * Keys are the names a backup gives them (the engine's [BackupPreferences]: "look", "fontSet", "textFont",
 * "tripsLook", "planView", "planExpanded"), values the ids the Android app writes ("pop", "notebook", "phone",
 * "tickets", "list"), so a backup can carry them either way unchanged. Like on Android, a setting never chosen is not
 * stored and reads as the default. "onboardingDone" and the demo's keys are this phone's own and never go into a
 * backup (a restore turns demo mode off on Android too).
 */
class AppSettings(private val file: SettingsFile) {
    private object K {
        const val LOOK = "look"
        const val FONT_SET = "fontSet"
        const val TEXT_FONT = "textFont"
        const val TRIPS_LOOK = "tripsLook"
        const val PLAN_VIEW = "planView"
        const val PLAN_EXPANDED = "planExpanded"
        const val PLACE_PHOTOS = "placePhotos"
        const val MAP_VIEW = "mapView"
        const val MAP_STYLE = "mapStyle"
        const val DICTATION_LANGUAGE = "dictationLanguage"
        const val PRINT_THEME = "printTheme"
        const val PRINT_PHOTOS = "printPhotos"
        const val PRINT_PAPER = "printPaper"
        const val PRINT_RULED = "printRuled"
        const val PRINT_PLAN = "printPlan"
        const val ONBOARDING_DONE = "onboardingDone"
        const val DEMO_MODE = "demoMode"
        const val DEMO_ANCHORED_DAY = "demoAnchoredDay"
        const val SAMPLE_VERSION = "sampleVersion"
        const val CARRIED = "carriedPreferences"
        const val TRACKING_LEVEL = "trackingLevel"
        const val CLIP_STYLE = "clipStyle"
        const val CLIP_MUSIC = "clipMusic"
        const val TRAVELLER = "traveller"
        const val RIDES = "rides"
        const val TRIP_PHOTOS = "tripPhotos"
        // How this phone backs up (a backup's "autoBackup" and "wifiOnly": they travel, but a restore only fills them).
        const val AUTO_BACKUP = "autoBackup"
        const val WIFI_ONLY = "wifiOnly"
        // This phone's own (never in a backup, as Android's): the account, Drive, the backups' state.
        const val ACCOUNT_EMAIL = "accountEmail"
        const val ACCOUNT_NAME = "accountName"
        const val ACCOUNT_PROVIDER = "accountProvider"
        const val DRIVE_CONNECTED = "driveConnected"
        const val DRIVE_ACCOUNT = "driveAccount"
        const val LAST_BACKUP_AT = "lastBackupAt"
        const val BACKUP_STAMP = "backupStamp"
        const val BACKUP_CHECKED_AT = "backupCheckedAt"
        const val RESTORE_ASKED = "restoreAsked"
        const val DRIVE_FOLDERS = "driveFolders"
        const val DAILY_TRIES = "dailyBackupTries"
        // The community's rules agreed to on this phone (Android's community_rules_accepted: never in a backup).
        const val COMMUNITY_RULES = "communityRulesAccepted"
    }

    private var communityRulesState by mutableStateOf(file.read(K.COMMUNITY_RULES) == "true")

    /** The community's rules, agreed to once before the first profile or post (round 24; this phone's own answer). */
    var communityRulesAccepted: Boolean
        get() = communityRulesState
        set(value) {
            communityRulesState = value
            file.write(K.COMMUNITY_RULES, if (value) "true" else null)
        }

    private var accountEmailState by mutableStateOf(file.read(K.ACCOUNT_EMAIL))
    private var accountNameState by mutableStateOf(file.read(K.ACCOUNT_NAME))
    private var accountProviderState by mutableStateOf(file.read(K.ACCOUNT_PROVIDER))
    private var driveConnectedState by mutableStateOf(file.read(K.DRIVE_CONNECTED) == "true")
    private var driveAccountState by mutableStateOf(file.read(K.DRIVE_ACCOUNT))
    private var autoBackupState by mutableStateOf(file.read(K.AUTO_BACKUP)?.toBooleanStrictOrNull())
    private var wifiOnlyState by mutableStateOf(file.read(K.WIFI_ONLY)?.toBooleanStrictOrNull())
    private var lastBackupAtState by mutableStateOf(file.read(K.LAST_BACKUP_AT)?.toLongOrNull())
    private var backupCheckedAtState by mutableStateOf(file.read(K.BACKUP_CHECKED_AT)?.toLongOrNull())
    private var restoreAskedState by mutableStateOf(file.read(K.RESTORE_ASKED) == "true")

    /**
     * The account the app is signed in with (null: local only; the Android app's Settings.accountEmail and accountName):
     * a Google account's email and name, or Sign in with Apple's (its email when Apple shared one). This phone's own.
     */
    val accountEmail: String? get() = accountEmailState
    val accountName: String? get() = accountNameState

    /** Who signed in: [PROVIDER_GOOGLE], [PROVIDER_APPLE] (new on iPhone), null when no one. */
    val accountProvider: String? get() = accountProviderState

    /** Whether someone is signed in (an email, or an Apple account that shared none). */
    val signedIn: Boolean get() = accountProviderState != null || accountEmailState != null

    fun setAccount(email: String?, name: String?, provider: String?) {
        accountEmailState = email
        accountNameState = name
        accountProviderState = provider
        file.write(K.ACCOUNT_EMAIL, email)
        file.write(K.ACCOUNT_NAME, name)
        file.write(K.ACCOUNT_PROVIDER, provider)
    }

    /** Google Drive connected on this phone (the Android app's Settings.driveConnected). */
    var driveConnected: Boolean
        get() = driveConnectedState
        set(value) {
            driveConnectedState = value
            file.write(K.DRIVE_CONNECTED, if (value) "true" else null)
        }

    /** The Google account Drive is connected with, when known (Android's DriveAuth.account). */
    var driveAccount: String?
        get() = driveAccountState
        set(value) {
            driveAccountState = value
            file.write(K.DRIVE_ACCOUNT, value)
        }

    /** Backup & sync › Back up every day (on by default); a restore made before only passed it on ([carried]). */
    var autoBackup: Boolean
        get() = autoBackupState ?: carried().autoBackup ?: true
        set(value) {
            autoBackupState = value
            file.write(K.AUTO_BACKUP, value.toString())
        }

    /** Backup & sync › Only on Wi‑Fi (on by default): the daily backup and the videos wait for Wi‑Fi. */
    var wifiOnly: Boolean
        get() = wifiOnlyState ?: carried().wifiOnly ?: true
        set(value) {
            wifiOnlyState = value
            file.write(K.WIFI_ONLY, value.toString())
        }

    /** When this phone last wrote a backup to Drive. */
    var lastBackupAt: Long?
        get() = lastBackupAtState
        set(value) {
            lastBackupAtState = value
            file.write(K.LAST_BACKUP_AT, value?.toString())
        }

    /** When a backup last found nothing new since [lastBackupAt] and that backup still in Drive. */
    var backupCheckedAt: Long?
        get() = backupCheckedAtState
        set(value) {
            backupCheckedAtState = value
            file.write(K.BACKUP_CHECKED_AT, value?.toString())
        }

    /** When Drive last held everything on this phone: the later of the last backup and the last check. */
    val backupFreshAt: Long? get() = lastBackupAtState?.let { maxOf(it, backupCheckedAtState ?: 0L) }

    /**
     * The last backup this phone wrote and what was in it (the engine's BackupStamp as text), so a backup with nothing
     * new isn't written again; this phone's own, never in a backup.
     */
    var backupStamp: String?
        get() = file.read(K.BACKUP_STAMP)
        set(value) = file.write(K.BACKUP_STAMP, value)

    /**
     * Whether the traveller has been asked about restoring the backup in their Drive (or there was none to ask about):
     * until then a phone that has never backed up doesn't back up by itself (BackupSafety.waitForRestoreAnswer).
     */
    var restoreAsked: Boolean
        get() = restoreAskedState
        set(value) {
            restoreAskedState = value
            file.write(K.RESTORE_ASKED, if (value) "true" else null)
        }

    /** The app's Drive folder ids by path (Android's DriveFolderMemory, its own small file): ids only. */
    val driveFolders: com.justdataplease.boringtravel.google.FolderMemory = object : com.justdataplease.boringtravel.google.FolderMemory {
        private fun all(): Map<String, String> = file.read(K.DRIVE_FOLDERS)?.let { text ->
            runCatching { (Json.parseToJsonElement(text) as JsonObject).mapValues { (it.value as JsonPrimitive).content } }.getOrNull()
        }.orEmpty()

        override fun get(path: String): String? = all()[path]

        override fun put(path: String, id: String?) {
            val now = all()
            if (now[path] == id) return
            val next = if (id == null) now - path else now + (path to id)
            file.write(K.DRIVE_FOLDERS, if (next.isEmpty()) null else JsonObject(next.mapValues { JsonPrimitive(it.value) }).toString())
        }
    }

    /** The daily backup's tries since its last success (it is tried again a few times, then the next night). */
    var dailyBackupTries: Int
        get() = file.read(K.DAILY_TRIES)?.toIntOrNull() ?: 0
        set(value) = file.write(K.DAILY_TRIES, if (value == 0) null else value.toString())

    /**
     * Edit trip › Photos in notes (since the second parity round this phone's own, under a backup's "tripPhotos", in the
     * Android app's own text, TripPhotoStyles.encode): each trip's size and frame for new photos and videos in its
     * notes, and the last one chosen, which a new trip starts from. A restore made before only passed it on
     * ([carried]), which still counts until one is chosen here.
     */
    val tripPhotoStyles: com.justdataplease.boringtravel.data.settings.TripPhotoStyles
        get() = com.justdataplease.boringtravel.data.settings.TripPhotoStyles.decode(tripPhotosText)

    /** Keeps [style] for trip [tripId] (and as the last one chosen), as the Android app's Settings.setTripPhotoStyle. */
    fun setTripPhotoStyle(tripId: String, style: com.justdataplease.boringtravel.data.settings.TripPhotoStyle) {
        tripPhotosText = tripPhotoStyles.with(tripId, style).encode()
        file.write(K.TRIP_PHOTOS, tripPhotosText)
    }

    private var tripPhotosText by mutableStateOf(file.read(K.TRIP_PHOTOS) ?: carried().tripPhotos)

    /**
     * The Android app's small settings files the trip video reads and writes (clip/ClipPrefs.kt "clip", music/MusicPrefs.kt
     * "clip_music", BaseMap's MapStylePrefs "map_style"), each kept here as a backup carries it ([K.CLIP_STYLE],
     * [K.CLIP_MUSIC], [K.MAP_STYLE]): compat/AndroidContext.kt.
     */
    val prefs: com.justdataplease.boringtravel.compat.Context get() = com.justdataplease.boringtravel.compat.Context(file)

    /**
     * The trip video's remembered choices that a restore made before this phone kept them only passed on become its
     * own (since round 18 the video reads and writes them here).
     */
    fun settleClipPrefs() {
        val c = carried()
        if (file.read(K.CLIP_STYLE) == null) c.clipStyle?.takeIf { MapStylePrefs.isText(it) }?.let { file.write(K.CLIP_STYLE, it) }
        if (file.read(K.CLIP_MUSIC) == null) c.clipMusic?.takeIf { MapStylePrefs.isText(it) }?.let { file.write(K.CLIP_MUSIC, it) }
    }

    /**
     * Me › Your traveller (since round 20 this phone's own, under a backup's "traveller", in the Android app's own string,
     * Traveller.encode): who walks the legs on foot in every trip video unless a video has its own; a restore made
     * before only passed it on ([carried]), which still counts until one is picked here.
     */
    var traveller: com.justdataplease.boringtravel.clip.Traveller
        get() = com.justdataplease.boringtravel.clip.Traveller.decode(travellerText)
        set(value) {
            travellerText = value.encode()
            file.write(K.TRAVELLER, travellerText)
        }

    /** Me › Your traveller & rides' rides (this phone's own since round 20, a backup's "rides"), as [traveller] is kept. */
    var rides: com.justdataplease.boringtravel.clip.Rides
        get() = com.justdataplease.boringtravel.clip.Rides.decode(ridesText)
        set(value) {
            ridesText = value.encode()
            file.write(K.RIDES, ridesText)
        }

    /** Whether the traveller was ever picked (Android's Settings.travellerChosen: a new community picture starts as it). */
    val travellerChosen: Boolean get() = travellerText != null

    private var travellerText by mutableStateOf(file.read(K.TRAVELLER) ?: carried().traveller)
    private var ridesText by mutableStateOf(file.read(K.RIDES) ?: carried().rides)

    private var lookState by mutableStateOf(Look.of(file.read(K.LOOK)))
    private var fontSetState by mutableStateOf(FontSet.of(file.read(K.FONT_SET)))
    private var textFontState by mutableStateOf(TextFont.of(file.read(K.TEXT_FONT)))
    private var tripsLookState by mutableStateOf(TripsLook.of(file.read(K.TRIPS_LOOK)))
    private var planViewState by mutableStateOf(PlanView.of(file.read(K.PLAN_VIEW)))
    private var planExpandedState by mutableStateOf(PlanView.expanded(file.read(K.PLAN_VIEW), file.read(K.PLAN_EXPANDED)?.toBooleanStrictOrNull()))
    private var placePhotosState by mutableStateOf(file.read(K.PLACE_PHOTOS) != "false")
    private var mapViewState by mutableStateOf(MapView.of(file.read(K.MAP_VIEW)))
    private var tripMapStyleState by mutableStateOf(MapStylePrefs.tripMap(file.read(K.MAP_STYLE)))
    private var dictationLanguageState by mutableStateOf(file.read(K.DICTATION_LANGUAGE))
    private var onboardingState by mutableStateOf(file.read(K.ONBOARDING_DONE) == "true")
    private var demoState by mutableStateOf(file.read(K.DEMO_MODE) == "true")
    private var trackingLevelState by mutableStateOf(trackingLevelOf(file.read(K.TRACKING_LEVEL)))

    /**
     * Me › Route tracking: the level for every trip (a trip's own level wins), Off until one is picked, so nothing is
     * asked at the first start (the Android app's Settings.trackingLevel; an id this version doesn't know is Balanced).
     * This phone's own: never in a backup, as on Android.
     */
    var trackingLevel: com.justdataplease.boringtravel.model.TrackingLevel
        get() = trackingLevelState
        set(value) {
            trackingLevelState = value
            file.write(K.TRACKING_LEVEL, value.id)
        }

    private fun trackingLevelOf(id: String?) =
        id?.let { com.justdataplease.boringtravel.model.TrackingLevel.of(it) } ?: com.justdataplease.boringtravel.model.TrackingLevel.Off

    /** Me › Theme. */
    var look: Look
        get() = lookState
        set(value) {
            lookState = value
            file.write(K.LOOK, value.id)
        }

    /** Me › Titles and dates: "Theme's own" (the default) follows the theme. */
    var fontSet: FontSet
        get() = fontSetState
        set(value) {
            fontSetState = value
            file.write(K.FONT_SET, value.id)
        }

    /** Me › Text: the face of running text, the phone's own by default. */
    var textFont: TextFont
        get() = textFontState
        set(value) {
            textFontState = value
            file.write(K.TEXT_FONT, value.id)
        }

    /** Me › Trips look: Tickets (the default) or Pictures. */
    var tripsLook: TripsLook
        get() = tripsLookState
        set(value) {
            tripsLookState = value
            file.write(K.TRIPS_LOOK, value.id)
        }

    /** Me › Plan view, also flipped on the Plan tab: List (the default) or Calendar. */
    var planView: PlanView
        get() = planViewState
        set(value) {
            keepTimelineAsExpanded()
            planViewState = value
            file.write(K.PLAN_VIEW, value.id)
        }

    /** The List on the Plan tab: expanded (every day in full, with travel) or collapsed (one line per item, the default). */
    var planExpanded: Boolean
        get() = planExpandedState
        set(value) {
            planExpandedState = value
            file.write(K.PLAN_EXPANDED, value.toString())
        }

    /** Me › Place photos: pictures from Wikipedia for places in the plans, on by default; off, Wikipedia is never asked. */
    var placePhotos: Boolean
        get() = placePhotosState
        set(value) {
            placePhotosState = value
            file.write(K.PLACE_PHOTOS, value.toString())
        }

    /** The Map tab's last choice, Globe or Map (the Android app's Settings.mapView). */
    var mapView: MapView
        get() = mapViewState
        set(value) {
            mapViewState = value
            file.write(K.MAP_VIEW, value.id)
        }

    /**
     * The trip map's look, Street · Terrain · Earth, kept on this phone for every trip map (Street until one is picked).
     * Kept as Android keeps its map looks in a backup ([BackupPreferences.mapStyle]: the phone's "map_style" settings
     * file as text, the trip map's under "trip_map"), so the trip video's own choice in it travels on untouched.
     */
    var tripMapStyle: MapStyle
        get() = tripMapStyleState
        set(value) {
            tripMapStyleState = value
            file.write(K.MAP_STYLE, MapStylePrefs.withTripMap(file.read(K.MAP_STYLE), value))
        }

    /** The Share screen's style, Notebook until one is picked (an older Postcard or Scrapbook stays as it was saved). */
    var printTheme: PrintTheme
        get() = PrintTheme.of(file.read(K.PRINT_THEME) ?: carried().printTheme)
        set(value) = file.write(K.PRINT_THEME, value.id)

    /** The Share screen's last photo size, paper, notebook paper and "Include the plan" (each null until chosen). */
    val printChoices: PrintChoices
        get() = carried().let { c ->
            // A restore made before this phone kept them only passed them on: those still count.
            PrintChoices(
                file.read(K.PRINT_PHOTOS) ?: c.printPhotos, file.read(K.PRINT_PAPER) ?: c.printPaper,
                file.read(K.PRINT_RULED)?.toBooleanStrictOrNull() ?: c.printRuled, file.read(K.PRINT_PLAN)?.toBooleanStrictOrNull() ?: c.printPlan,
            )
        }

    /** Keeps the choices [c] names; a null one stays as it is. */
    fun setPrintChoices(c: PrintChoices) {
        c.photoSize?.let { file.write(K.PRINT_PHOTOS, it) }
        c.paper?.let { file.write(K.PRINT_PAPER, it) }
        c.ruled?.let { file.write(K.PRINT_RULED, it.toString()) }
        c.plan?.let { file.write(K.PRINT_PLAN, it.toString()) }
    }

    /** Which trips and days the daily check-in asked about: this phone's own, never in a backup. */
    val checkIns: CheckInStore = CheckInStore(file)

    /** Each trip's link to a calendar on this phone: this phone's own, never in a backup (as Android's). */
    val calendarLinks: com.justdataplease.boringtravel.calendar.CalendarLinks = com.justdataplease.boringtravel.calendar.CalendarLinks(file)

    /**
     * The AI planner's daily count and free plans (the Android app's PlannerUsage, its own "ai_planner" preferences):
     * this phone's own, never in a backup.
     */
    val plannerUsage: com.justdataplease.boringtravel.ai.PlannerUsage = com.justdataplease.boringtravel.ai.PlannerUsage(file)

    /** Me › Voice notes: the language dictation listens for, as a tag ("el-GR"); null: the phone's language. */
    var dictationLanguage: String?
        get() = dictationLanguageState
        set(value) {
            dictationLanguageState = value
            file.write(K.DICTATION_LANGUAGE, value)
        }

    /** Whether the first start (Welcome) is done; it shows until then. */
    var onboardingDone: Boolean
        get() = onboardingState
        set(value) {
            onboardingState = value
            file.write(K.ONBOARDING_DONE, if (value) "true" else null)
        }

    /** Me › Demo mode: the example trips on show instead of the traveller's own. Off until chosen ([demoModeChosen]). */
    var demoMode: Boolean
        get() = demoState
        set(value) {
            demoState = value
            file.write(K.DEMO_MODE, value.toString())
        }

    /** Whether demo mode was ever set on this phone (the Android app decides it once, at the first start). */
    val demoModeChosen: Boolean get() = file.read(K.DEMO_MODE) != null

    /** The day the demo was last moved around today (an epoch day), so it moves at most once a day. */
    var demoAnchoredDay: Long?
        get() = file.read(K.DEMO_ANCHORED_DAY)?.toLongOrNull()
        set(value) = file.write(K.DEMO_ANCHORED_DAY, value?.toString())

    /** The set of examples this phone has (the Android app's SampleData.VERSION when they were made); 0 before any. */
    var sampleVersion: Int
        get() = file.read(K.SAMPLE_VERSION)?.toIntOrNull() ?: 0
        set(value) = file.write(K.SAMPLE_VERSION, value.toString())

    /** Before "timeline" is overwritten: it meant the List expanded, so that choice is kept as one. */
    private fun keepTimelineAsExpanded() {
        if (file.read(K.PLAN_VIEW) == PlanView.TIMELINE && file.read(K.PLAN_EXPANDED) == null) file.write(K.PLAN_EXPANDED, "true")
    }

    /**
     * What a backup holds of these, as the Android app writes it: only what was chosen on this phone. The settings this
     * phone doesn't have yet (the trip video's, the trips' photo defaults, the traveller, backups on
     * Wi-Fi only…) travel on as the last restore brought them ([carried]), so a backup made here keeps them for Android.
     */
    fun backupPreferences(): BackupPreferences = carried().let { c -> c.copy(
        look = file.read(K.LOOK), fontSet = file.read(K.FONT_SET), textFont = file.read(K.TEXT_FONT),
        tripsLook = file.read(K.TRIPS_LOOK), planView = file.read(K.PLAN_VIEW),
        planExpanded = file.read(K.PLAN_EXPANDED)?.toBooleanStrictOrNull(),
        placePhotos = file.read(K.PLACE_PHOTOS)?.toBooleanStrictOrNull(),
        mapView = file.read(K.MAP_VIEW), mapStyle = file.read(K.MAP_STYLE), dictationLanguage = file.read(K.DICTATION_LANGUAGE),
        // The Share screen's (kept here since round 13); before, a restore's were only carried.
        printTheme = file.read(K.PRINT_THEME) ?: c.printTheme, printPhotos = file.read(K.PRINT_PHOTOS) ?: c.printPhotos,
        printPaper = file.read(K.PRINT_PAPER) ?: c.printPaper, printRuled = file.read(K.PRINT_RULED)?.toBooleanStrictOrNull() ?: c.printRuled,
        printPlan = file.read(K.PRINT_PLAN)?.toBooleanStrictOrNull() ?: c.printPlan,
        // The trip video's (kept here since round 18); before, a restore's were only carried.
        clipStyle = file.read(K.CLIP_STYLE) ?: c.clipStyle, clipMusic = file.read(K.CLIP_MUSIC) ?: c.clipMusic,
        // Me › Your traveller & rides (kept here since round 20).
        traveller = file.read(K.TRAVELLER) ?: c.traveller, rides = file.read(K.RIDES) ?: c.rides,
        // Edit trip › Photos in notes (kept here since the second parity round).
        tripPhotos = file.read(K.TRIP_PHOTOS) ?: c.tripPhotos,
        // How this phone backs up (kept here since round 23).
        autoBackup = file.read(K.AUTO_BACKUP)?.toBooleanStrictOrNull() ?: c.autoBackup,
        wifiOnly = file.read(K.WIFI_ONLY)?.toBooleanStrictOrNull() ?: c.wifiOnly,
    ) }

    /** The settings of a restored backup that this phone keeps only to pass on (never shown, never changed here). */
    private fun carried(): BackupPreferences =
        file.read(K.CARRIED)?.let { runCatching { BackupJson.decodeFromString(BackupPreferences.serializer(), it) }.getOrNull() } ?: BackupPreferences()

    /** [saved]'s settings that this phone doesn't have, as [carried] keeps them. */
    private fun carriedOf(saved: BackupPreferences): BackupPreferences = BackupPreferences(
        autoBackup = saved.autoBackup, wifiOnly = saved.wifiOnly, traveller = saved.traveller,
        rides = saved.rides, clipStyle = saved.clipStyle, clipMusic = saved.clipMusic, tripPhotos = saved.tripPhotos,
    )

    /**
     * A backup's choices on this phone, by the Android app's rule (Settings.restorePreferences): by default they only
     * fill what was never chosen here; with [replace] (a restore the traveller asked for) what the backup holds comes
     * back. A choice the backup doesn't hold stays.
     */
    fun restorePreferences(saved: BackupPreferences?, replace: Boolean = false) {
        if (saved == null) return
        // The Share screen's choices an older restore only passed on become this phone's own before the carried
        // settings are written again.
        carried().let { old ->
            fun settle(key: String, value: String?) {
                if (value != null && file.read(key) == null) file.write(key, value)
            }
            settle(K.PRINT_THEME, old.printTheme)
            settle(K.PRINT_PHOTOS, old.printPhotos)
            settle(K.PRINT_PAPER, old.printPaper)
            settle(K.PRINT_RULED, old.printRuled?.toString())
            settle(K.PRINT_PLAN, old.printPlan?.toString())
            settle(K.TRAVELLER, old.traveller)
            settle(K.RIDES, old.rides)
            settle(K.TRIP_PHOTOS, old.tripPhotos)
            settle(K.AUTO_BACKUP, old.autoBackup?.toString())
            settle(K.WIFI_ONLY, old.wifiOnly?.toString())
        }
        // How this phone backs up is always its own: a backup only fills what was never chosen here (Android's fill).
        saved.autoBackup?.let { if (file.read(K.AUTO_BACKUP) == null) file.write(K.AUTO_BACKUP, it.toString()) }
        saved.wifiOnly?.let { if (file.read(K.WIFI_ONLY) == null) file.write(K.WIFI_ONLY, it.toString()) }
        autoBackupState = file.read(K.AUTO_BACKUP)?.toBooleanStrictOrNull()
        wifiOnlyState = file.read(K.WIFI_ONLY)?.toBooleanStrictOrNull()
        // Kept as the backup wrote them, as Android keeps them (an older id still reads as its theme).
        fun keep(key: String, value: String?) {
            if (value != null && (replace || file.read(key) == null)) file.write(key, value)
        }
        // This phone's old Timeline is its own choice of the expanded List: the backup's doesn't override it.
        if (!replace) keepTimelineAsExpanded()
        keep(K.LOOK, saved.look)
        keep(K.FONT_SET, saved.fontSet)
        keep(K.TEXT_FONT, saved.textFont)
        keep(K.TRIPS_LOOK, saved.tripsLook)
        keep(K.PLAN_VIEW, saved.planView)
        keep(K.PLAN_EXPANDED, saved.planExpanded?.toString())
        keep(K.PLACE_PHOTOS, saved.placePhotos?.toString())
        keep(K.MAP_VIEW, saved.mapView?.takeIf { it.length <= 40 })
        keep(K.DICTATION_LANGUAGE, saved.dictationLanguage)
        keep(K.PRINT_THEME, saved.printTheme)
        keep(K.PRINT_PHOTOS, saved.printPhotos?.takeIf { it.length <= 40 })
        keep(K.PRINT_PAPER, saved.printPaper?.takeIf { it.length <= 40 })
        keep(K.PRINT_RULED, saved.printRuled?.toString())
        keep(K.PRINT_PLAN, saved.printPlan?.toString())
        // The map looks file comes back whole on a restore asked for; otherwise only when this phone has none. So do
        // the trip video's own files (round 18).
        keep(K.MAP_STYLE, saved.mapStyle?.takeIf { MapStylePrefs.isText(it) })
        keep(K.CLIP_STYLE, saved.clipStyle?.takeIf { MapStylePrefs.isText(it) })
        keep(K.CLIP_MUSIC, saved.clipMusic?.takeIf { MapStylePrefs.isText(it) })
        // Me › Your traveller & rides (Android's limit on their length, Settings.restorePreferences).
        keep(K.TRAVELLER, saved.traveller?.takeIf { it.length <= 2_000 })
        keep(K.RIDES, saved.rides?.takeIf { it.length <= 2_000 })
        // Edit trip › Photos in notes (Android's limit on its length, Settings.restorePreferences).
        keep(K.TRIP_PHOTOS, saved.tripPhotos?.takeIf { it.length <= com.justdataplease.boringtravel.data.settings.TripPhotoStyles.MAX_CHARS })
        // An older backup's Timeline was the List, expanded.
        if (replace && saved.planView == PlanView.TIMELINE && saved.planExpanded == null) file.write(K.PLAN_EXPANDED, "true")
        // What this phone only passes on: the backup's, when restored; otherwise only what was never there.
        val incoming = carriedOf(saved)
        val kept = if (replace) incoming else carried().let { old ->
            BackupPreferences(
                autoBackup = old.autoBackup ?: incoming.autoBackup,
                wifiOnly = old.wifiOnly ?: incoming.wifiOnly, traveller = old.traveller ?: incoming.traveller, rides = old.rides ?: incoming.rides,
                clipStyle = old.clipStyle ?: incoming.clipStyle, clipMusic = old.clipMusic ?: incoming.clipMusic,
                tripPhotos = old.tripPhotos ?: incoming.tripPhotos,
            )
        }
        file.write(K.CARRIED, if (kept == BackupPreferences()) null else BackupJson.encodeToString(BackupPreferences.serializer(), kept))
        lookState = Look.of(file.read(K.LOOK))
        fontSetState = FontSet.of(file.read(K.FONT_SET))
        textFontState = TextFont.of(file.read(K.TEXT_FONT))
        tripsLookState = TripsLook.of(file.read(K.TRIPS_LOOK))
        planViewState = PlanView.of(file.read(K.PLAN_VIEW))
        planExpandedState = PlanView.expanded(file.read(K.PLAN_VIEW), file.read(K.PLAN_EXPANDED)?.toBooleanStrictOrNull())
        placePhotosState = file.read(K.PLACE_PHOTOS) != "false"
        mapViewState = MapView.of(file.read(K.MAP_VIEW))
        tripMapStyleState = MapStylePrefs.tripMap(file.read(K.MAP_STYLE))
        dictationLanguageState = file.read(K.DICTATION_LANGUAGE)
        travellerText = file.read(K.TRAVELLER) ?: carried().traveller
        ridesText = file.read(K.RIDES) ?: carried().rides
        tripPhotosText = file.read(K.TRIP_PHOTOS) ?: carried().tripPhotos
    }

    companion object {
        /** [accountProvider]'s values. */
        const val PROVIDER_GOOGLE = "google"
        const val PROVIDER_APPLE = "apple"

        /** Settings in memory with the first start done and [look] picked: previews and tests of the screens behind it. */
        fun ready(look: Look = Look.Default): AppSettings = AppSettings(MemorySettingsFile()).apply {
            onboardingDone = true
            if (look != Look.Default) this.look = look
        }
    }
}

/** The Share screen's remembered choices, by id (export/PhotoSize, export/Paper); null: never chosen (the Android app's). */
data class PrintChoices(val photoSize: String? = null, val paper: String? = null, val ruled: Boolean? = null, val plan: Boolean? = null)
