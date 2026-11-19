package com.justdataplease.boringtravel.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.justdataplease.boringtravel.backup.BackupPreferences
import com.justdataplease.boringtravel.clip.Rides
import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.ui.theme.FontSet
import com.justdataplease.boringtravel.ui.theme.TextFont
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store: DataStore<Preferences> by preferencesDataStore("settings")

/** App settings (DataStore). Each value is a Flow plus a suspend setter. */
class Settings internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.store)

    private object K {
        val look = stringPreferencesKey("look")
        val fontSet = stringPreferencesKey("font_set")
        val textFont = stringPreferencesKey("text_font")
        val mapView = stringPreferencesKey("map_view")
        val tripsLook = stringPreferencesKey("trips_look")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val trackingLevel = stringPreferencesKey("tracking_level")
        val printTheme = stringPreferencesKey("print_theme")
        val printPhotos = stringPreferencesKey("print_photos")
        val printPaper = stringPreferencesKey("print_paper")
        val printRuled = booleanPreferencesKey("print_ruled")
        val printPlan = booleanPreferencesKey("print_plan")
        val tripPhotos = stringPreferencesKey("trip_photos")
        val accountEmail = stringPreferencesKey("account_email")
        val accountName = stringPreferencesKey("account_name")
        val driveConnected = booleanPreferencesKey("drive_connected")
        val autoBackup = booleanPreferencesKey("auto_backup")
        val lastBackupAt = longPreferencesKey("last_backup_at")
        val backupStamp = stringPreferencesKey("backup_stamp")
        val backupCheckedAt = longPreferencesKey("backup_checked_at")
        val restoreAsked = booleanPreferencesKey("restore_asked")
        val syncEnabled = booleanPreferencesKey("sync_enabled")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val dictationLanguage = stringPreferencesKey("dictation_language")
        val sampleSeeded = booleanPreferencesKey("sample_seeded")
        val sampleVersion = intPreferencesKey("sample_version")
        val demoMode = booleanPreferencesKey("demo_mode")
        val demoAnchoredDay = longPreferencesKey("demo_anchored_day")
        val placePhotos = booleanPreferencesKey("place_photos")
        val planView = stringPreferencesKey("plan_view")
        val planExpanded = booleanPreferencesKey("plan_expanded")
        val traveller = stringPreferencesKey("traveller")
        val rides = stringPreferencesKey("rides")
        val communityRules = booleanPreferencesKey("community_rules_accepted")
    }

    private fun <T> value(key: Preferences.Key<T>): Flow<T?> = store.data.map { it[key] }.distinctUntilChanged()
    private suspend fun <T> put(key: Preferences.Key<T>, v: T?) {
        store.edit { if (v == null) it.remove(key) else it[key] = v }
    }

    val look: Flow<Look> = value(K.look).map { Look.of(it) }
    suspend fun setLook(look: Look) = put(K.look, look.id)

    /** Fonts picked apart from the look; "Look's own" (the default) follows the look. */
    val fontSet: Flow<FontSet> = value(K.fontSet).map { FontSet.of(it) }
    suspend fun setFontSet(set: FontSet) = put(K.fontSet, set.id)

    /** Me › Fonts › Text: the face of running text (the phone's own by default). */
    val textFont: Flow<TextFont> = value(K.textFont).map { TextFont.of(it) }
    suspend fun setTextFont(font: TextFont) = put(K.textFont, font.id)

    /** The Map tab's last view: the globe (the default, as before) or the map. */
    val mapView: Flow<MapView> = value(K.mapView).map { MapView.of(it) }
    suspend fun setMapView(view: MapView) = put(K.mapView, view.id)

    /** Me › Trips look: the Trips tab as Tickets (the default) or Pictures. */
    val tripsLook: Flow<TripsLook> = value(K.tripsLook).map { TripsLook.of(it) }
    suspend fun setTripsLook(look: TripsLook) = put(K.tripsLook, look.id)

    val onboardingDone: Flow<Boolean> = value(K.onboardingDone).map { it == true }
    suspend fun setOnboardingDone(done: Boolean) = put(K.onboardingDone, done)

    /**
     * Me › Route tracking, the level for every trip. Off until picked (owner, 2026-11-12: "keep it off by default"):
     * the first start no longer asks. Everyone who finished the first start before 1.12.0 picked a level there, and
     * that choice stays; an id this version doesn't know is Balanced, as before.
     */
    val trackingLevel: Flow<TrackingLevel> = value(K.trackingLevel).map { id -> id?.let(TrackingLevel::of) ?: TrackingLevel.Off }
    suspend fun setTrackingLevel(level: TrackingLevel) = put(K.trackingLevel, level.id)

    val printTheme: Flow<PrintTheme> = value(K.printTheme).map { PrintTheme.of(it) }
    suspend fun setPrintTheme(theme: PrintTheme) = put(K.printTheme, theme.id)

    /** The Share screen's last photo size, paper, notebook paper and "Include the plan" (each null until chosen). */
    val printChoices: Flow<PrintChoices> =
        store.data.map { PrintChoices(it[K.printPhotos], it[K.printPaper], it[K.printRuled], it[K.printPlan]) }.distinctUntilChanged()

    /** Each trip's defaults for new photos and videos in its notes ([TripPhotoStyles]). */
    val tripPhotoStyles: Flow<TripPhotoStyles> = value(K.tripPhotos).map { TripPhotoStyles.decode(it) }
    suspend fun setTripPhotoStyle(tripId: String, style: TripPhotoStyle) {
        store.edit { prefs -> prefs[K.tripPhotos] = TripPhotoStyles.decode(prefs[K.tripPhotos]).with(tripId, style).encode() }
    }

    /** Keeps the choices [c] names; a null one stays as it is. */
    suspend fun setPrintChoices(c: PrintChoices) {
        store.edit { prefs ->
            c.photoSize?.let { prefs[K.printPhotos] = it }
            c.paper?.let { prefs[K.printPaper] = it }
            c.ruled?.let { prefs[K.printRuled] = it }
            c.plan?.let { prefs[K.printPlan] = it }
        }
    }

    /** The Google account the app is signed in with (null = local only). */
    val accountEmail: Flow<String?> = value(K.accountEmail)
    val accountName: Flow<String?> = value(K.accountName)
    suspend fun setAccount(email: String?, name: String?) {
        store.edit {
            if (email == null) it.remove(K.accountEmail) else it[K.accountEmail] = email
            if (name == null) it.remove(K.accountName) else it[K.accountName] = name
        }
    }

    val driveConnected: Flow<Boolean> = value(K.driveConnected).map { it == true }
    suspend fun setDriveConnected(connected: Boolean) = put(K.driveConnected, connected)

    val autoBackup: Flow<Boolean> = value(K.autoBackup).map { it != false }
    suspend fun setAutoBackup(on: Boolean) = put(K.autoBackup, on)

    val lastBackupAt: Flow<Long?> = value(K.lastBackupAt)
    suspend fun setLastBackupAt(at: Long) = put(K.lastBackupAt, at)

    /**
     * The last backup this phone wrote and what was in it (backup/BackupStamp as text), so a backup with nothing new
     * isn't written again; this phone's own, never in a backup.
     */
    val backupStamp: Flow<String?> = value(K.backupStamp)
    suspend fun setBackupStamp(stamp: String?) = put(K.backupStamp, stamp)

    /** When a backup last found nothing new since [lastBackupAt] and that backup still in Drive. */
    val backupCheckedAt: Flow<Long?> = value(K.backupCheckedAt)
    suspend fun setBackupCheckedAt(at: Long) = put(K.backupCheckedAt, at)

    /** When Drive last held everything on this phone: the later of the last backup and the last check. */
    val backupFreshAt: Flow<Long?> = combine(lastBackupAt, backupCheckedAt) { backup, check ->
        if (backup == null) null else maxOf(backup, check ?: 0L)
    }

    /**
     * Whether the traveller has been asked about restoring the backup in their Drive (or there was none to ask about):
     * until then a phone that has never backed up doesn't back up by itself (backup/BackupSafety).
     */
    val restoreAsked: Flow<Boolean> = value(K.restoreAsked).map { it == true }
    suspend fun setRestoreAsked(asked: Boolean) = put(K.restoreAsked, asked)

    val syncEnabled: Flow<Boolean> = value(K.syncEnabled).map { it == true }
    suspend fun setSyncEnabled(on: Boolean) = put(K.syncEnabled, on)

    /** Daily sync and photo uploads only on Wi‑Fi (default on). */
    val wifiOnly: Flow<Boolean> = value(K.wifiOnly).map { it != false }
    suspend fun setWifiOnly(on: Boolean) = put(K.wifiOnly, on)

    val lastSyncAt: Flow<Long?> = value(K.lastSyncAt)
    suspend fun setLastSyncAt(at: Long) = put(K.lastSyncAt, at)

    /** BCP-47 tag for dictation, null = the phone's language. */
    val dictationLanguage: Flow<String?> = value(K.dictationLanguage)
    suspend fun setDictationLanguage(tag: String?) = put(K.dictationLanguage, tag)

    suspend fun sampleSeeded(): Boolean = store.data.first()[K.sampleSeeded] == true
    suspend fun setSampleSeeded() = put(K.sampleSeeded, true)

    /** Which set of examples this install has (see SampleData.VERSION); 0 = the first ones, or none. */
    suspend fun sampleVersion(): Int = store.data.first()[K.sampleVersion] ?: 0
    suspend fun setSampleVersion(version: Int) = put(K.sampleVersion, version)

    /**
     * Me › Demo mode: the app shows the example trips instead of the traveller's own (see DemoTrips). Unset until the
     * first start decides it (DemoMode.start); reads as off until then.
     */
    val demoMode: Flow<Boolean> = value(K.demoMode).map { it == true }
    suspend fun demoModeChosen(): Boolean = store.data.first()[K.demoMode] != null
    suspend fun setDemoMode(on: Boolean) = put(K.demoMode, on)

    /** The day (epoch day) the demo was last moved around today. */
    suspend fun demoAnchoredDay(): Long? = store.data.first()[K.demoAnchoredDay]
    suspend fun setDemoAnchoredDay(day: Long) = put(K.demoAnchoredDay, day)

    /**
     * Me › Place photos: pictures from Wikipedia for plan items with a place (and Google Maps links). On by default;
     * off, the app never asks Wikipedia.
     */
    val placePhotos: Flow<Boolean> = value(K.placePhotos).map { it != false }
    suspend fun setPlacePhotos(on: Boolean) = put(K.placePhotos, on)

    /** Me › Plan view, also flipped on the Plan tab: List (the default) or Calendar. */
    val planView: Flow<PlanView> = value(K.planView).map { PlanView.of(it) }
    suspend fun setPlanView(view: PlanView) {
        store.edit {
            keepTimelineAsExpanded(it)
            it[K.planView] = view.id
        }
    }

    /**
     * The List on the Plan tab: expanded (every day in full, with travel between places) or collapsed (one line per
     * item, the default). An install or backup that picked the old Timeline reads as expanded.
     */
    val planExpanded: Flow<Boolean> = store.data.map { PlanView.expanded(it[K.planView], it[K.planExpanded]) }.distinctUntilChanged()
    suspend fun setPlanExpanded(on: Boolean) = put(K.planExpanded, on)

    /**
     * Me › Your traveller: who walks the legs on foot in every trip video (the man, the woman or a couple, each with
     * their own look), unless a video has its own. Unset: [Traveller.DEFAULT].
     */
    val traveller: Flow<Traveller> = value(K.traveller).map { Traveller.decode(it) }
    suspend fun setTraveller(t: Traveller) = put(K.traveller, t.encode())

    /** Whether the traveller was ever picked in Me › Your traveller (the community avatar starts as it then). */
    val travellerChosen: Flow<Boolean> = value(K.traveller).map { it != null }

    /**
     * Me › Your traveller & rides: each vehicle's model and colours in every trip video, unless a video has its own.
     * Unset: [Rides.DEFAULT] (today's look).
     */
    val rides: Flow<Rides> = value(K.rides).map { Rides.decode(it) }
    suspend fun setRides(r: Rides) = put(K.rides, r.encode())

    /** The community's rules, agreed to once before the first post (never in backups: it is this phone's answer). */
    val communityRulesAccepted: Flow<Boolean> = value(K.communityRules).map { it == true }
    suspend fun setCommunityRulesAccepted(on: Boolean) = put(K.communityRules, on)

    /** Before "timeline" is overwritten: it meant the List expanded, so that choice is kept as one. */
    private fun keepTimelineAsExpanded(prefs: MutablePreferences) {
        if (prefs[K.planView] == PlanView.TIMELINE && prefs[K.planExpanded] == null) prefs[K.planExpanded] = true
    }

    /** One consistent read. Device permissions, active tracking, account and calendar links never travel. */
    suspend fun backupPreferences(): BackupPreferences = store.data.first().let {
        BackupPreferences(
            look = it[K.look], fontSet = it[K.fontSet], textFont = it[K.textFont], printTheme = it[K.printTheme],
            tripsLook = it[K.tripsLook],
            dictationLanguage = it[K.dictationLanguage], autoBackup = it[K.autoBackup], wifiOnly = it[K.wifiOnly],
            placePhotos = it[K.placePhotos], planView = it[K.planView], planExpanded = it[K.planExpanded],
            traveller = it[K.traveller], rides = it[K.rides],
            mapView = it[K.mapView], printPhotos = it[K.printPhotos], printPaper = it[K.printPaper],
            printRuled = it[K.printRuled], printPlan = it[K.printPlan], tripPhotos = it[K.tripPhotos],
        )
    }

    /**
     * The backup's preferences on this phone. By default they only fill what was never chosen here (a choice on this
     * phone wins). With [replace], what the backup holds comes back as it was (a restore the traveller asked for puts
     * the phone back, settings too: owner, 2026-11-14); a choice the backup doesn't hold stays. How this phone backs up
     * (every day, only on Wi‑Fi) is always this phone's own.
     */
    suspend fun restorePreferences(saved: BackupPreferences?, replace: Boolean = false) {
        if (saved == null) return
        store.edit { prefs ->
            fun <T> fill(key: Preferences.Key<T>, value: T?) {
                if (prefs[key] == null && value != null) prefs[key] = value
            }
            fun <T> put(key: Preferences.Key<T>, value: T?) {
                if (value != null && (replace || prefs[key] == null)) prefs[key] = value
            }
            // This phone's old Timeline is its own choice of the expanded List: the backup's doesn't override it.
            if (!replace) keepTimelineAsExpanded(prefs)
            put(K.look, saved.look)
            put(K.tripsLook, saved.tripsLook)
            put(K.fontSet, saved.fontSet)
            put(K.textFont, saved.textFont)
            put(K.printTheme, saved.printTheme)
            put(K.dictationLanguage, saved.dictationLanguage)
            fill(K.autoBackup, saved.autoBackup)
            fill(K.wifiOnly, saved.wifiOnly)
            put(K.placePhotos, saved.placePhotos)
            put(K.planView, saved.planView)
            put(K.planExpanded, saved.planExpanded)
            // An older backup's Timeline was the List, expanded.
            if (replace && saved.planView == PlanView.TIMELINE && saved.planExpanded == null) prefs[K.planExpanded] = true
            put(K.traveller, saved.traveller?.takeIf { it.length <= 2_000 })
            put(K.rides, saved.rides?.takeIf { it.length <= 2_000 })
            put(K.mapView, saved.mapView?.takeIf { it.length <= 40 })
            put(K.printPhotos, saved.printPhotos?.takeIf { it.length <= 40 })
            put(K.printPaper, saved.printPaper?.takeIf { it.length <= 40 })
            put(K.printRuled, saved.printRuled)
            put(K.printPlan, saved.printPlan)
            put(K.tripPhotos, saved.tripPhotos?.takeIf { it.length <= TripPhotoStyles.MAX_CHARS })
        }
    }
}

/** The Share screen's remembered choices, by id (export/PhotoSize, export/Paper); null: never chosen. */
data class PrintChoices(val photoSize: String? = null, val paper: String? = null, val ruled: Boolean? = null, val plan: Boolean? = null)

/** The Map tab's two views. */
enum class MapView(val id: String) {
    Globe("globe"), Map("map");

    companion object {
        fun of(id: String?): MapView = entries.firstOrNull { it.id == id } ?: Globe
    }
}

/**
 * How a trip's Plan tab shows the plan: the List (collapsed or expanded, see [Settings.planExpanded]) or the Calendar.
 * Up to 0.1.0-preview.6 there was a third view, Timeline: a stored "timeline" now reads as the List, expanded.
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
