package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.TrackPointRow
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.geo.Geo
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.tracking.Recording
import com.justdataplease.boringtravel.tracking.Stillness
import com.justdataplease.boringtravel.tracking.TrackingPermissions.Step
import com.justdataplease.boringtravel.tracking.TrackingPlan
import com.justdataplease.boringtravel.tracking.TrackingTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/*
 * Route tracking on iPhone (round 10): the Android app's tracking/TrackingController.kt, RouteRecordingService.kt,
 * BestFixAlarm.kt, LocationUpdatesReceiver.kt (TrackWriter), BootReceiver.kt (LocationWatch) and
 * TrackingNotifications.kt written again on the phone's location ([RouteSensor]: Core Location on iPhone), its
 * notifications ([PhoneNotes]: UserNotifications) and its background runs ([NightlyRuns]: BGTaskScheduler). The rules
 * themselves are the Android app's tracking/TrackingPlan.kt, read in: what records today, a trip's own level, the stop
 * for today, Balanced's best fix and Detailed's rest.
 *
 * How it records on iPhone (Android: a location foreground service with its notification, started only while the app
 * is on screen, because BACKGROUND_RECORDING is off there): Core Location's standard updates, started while the app is
 * on screen (opened during a trip, or Start recording) and going on in the background until Stop, the trip's last day
 * or Off, with the blue location sign instead of Android's notification (`allowsBackgroundLocationUpdates`, the
 * `location` background mode; While Using is enough). Each level asks for the least that gives Android's spacing:
 * Low and Balanced Wi-Fi and cell towers (about 100 m, Android's balanced power) with Android's distances, Detailed
 * GPS every 15 m; the fixes are then thinned to Android's spacing ([PointSpacing]: no closer than half the level's
 * interval, and its distance). The steps are Android's: precise location (While Using), then notifications.
 *
 * Always, off as Android's background recording is ([IosTrackingPlan.ASK_ALWAYS], like Android's
 * TrackingPlan.BACKGROUND_RECORDING): built and tested, one constant away. With it on, the steps end with Always and,
 * once it is given, the significant-change service runs beside the recording (and alone for Low, the lightest of all),
 * so iOS starts the app again after it was closed and the recording goes on. With it off, an Always given by hand in
 * Settings counts as While Using: nothing of the Always path starts.
 */

/** What Core Location allows the app: not asked yet, refused, while the app is in use, or always. */
enum class LocationGrant { NotAsked, Refused, WhileUsing, Always }

/** How the phone is asked to record at one level (Android's LocationRequest for the level, RouteRecordingService.requestFor). */
data class RecordRequest(
    val level: TrackingLevel,
    /** GPS ("best"); else Wi-Fi and cell towers, about 100 m (Android: balanced power). */
    val precise: Boolean,
    /** Metres the phone waits to move before the next fix (Core Location's distanceFilter; Android's min distance). */
    val distanceM: Double,
    /** The standard updates (off: only the significant-change service, Low with Always). */
    val continuous: Boolean,
    /** The significant-change service as well: iOS starts the app again for it after it was closed (Always only). */
    val significantChanges: Boolean,
)

/** One fix from the phone, as Android's Location gives it. */
data class RouteFix(
    val time: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val altitudeM: Double? = null,
)

/**
 * What route tracking needs of the phone's location: Core Location on iPhone (iosMain, IosRouteSensor), a stand-in on
 * the desktop and in the tests ([MemoryRouteSensor]). Everything is called, and calls back, on the main thread.
 */
interface RouteSensor {
    fun grant(): LocationGrant

    /** Precise Location is on (iOS's full accuracy; off, the phone gives only an area). */
    fun precise(): Boolean

    /** Location Services are on for the phone. */
    fun servicesOn(): Boolean

    /** The system's While Using question (shown the first time only); [done] once it is answered. */
    fun askWhileUsing(done: () -> Unit)

    /** The system's Always question, once While Using is given (iOS shows it once); [done] once answered, or at once. */
    fun askAlways(done: () -> Unit)

    /** The app's page in the phone's Settings (location, notifications). */
    fun openSettings()

    /** Records as [request] asks, from now (a running recording changes to it); every fix goes to [onFixes]. */
    fun record(request: RecordRequest, onFixes: (List<RouteFix>) -> Unit)

    fun stopRecording()

    /** Recording now ([record] and not stopped). */
    val recording: Boolean

    /** One accurate fix now (Balanced's best fix): GPS for [timeoutMs] at most, no worse than [maxAccuracyM]; else null. */
    suspend fun bestFix(timeoutMs: Long, maxAccuracyM: Float): RouteFix?

    /** [onChange] whenever the grant or Location Services change (while the app runs, in the background too). */
    fun watchChanges(onChange: () -> Unit)
}

/**
 * A phone's location for the desktop, previews and tests: as [grant] says, recording what it is asked ([requests]) and
 * handing on what a test [deliver]s.
 */
class MemoryRouteSensor(
    var grantNow: LocationGrant = LocationGrant.NotAsked,
    var preciseNow: Boolean = true,
    var servicesNow: Boolean = true,
    /** What the system's questions answer: the grant after While Using, and after Always. */
    var answerWhileUsing: LocationGrant = LocationGrant.WhileUsing,
    var answerAlways: LocationGrant = LocationGrant.Always,
    /** Balanced's best fix: what the phone finds. */
    var nextBestFix: RouteFix? = null,
) : RouteSensor {
    val requests = ArrayList<RecordRequest>()

    /** The questions asked, Settings opened and best fixes taken, in order (recording is in [requests] and [stops]). */
    val calls = ArrayList<String>()
    var stops = 0
        private set
    private var listener: ((List<RouteFix>) -> Unit)? = null
    private var watcher: (() -> Unit)? = null
    override var recording: Boolean = false
        private set

    override fun grant() = grantNow
    override fun precise() = preciseNow
    override fun servicesOn() = servicesNow

    override fun askWhileUsing(done: () -> Unit) {
        calls += "askWhileUsing"
        if (grantNow == LocationGrant.NotAsked) grantNow = answerWhileUsing
        done()
    }

    override fun askAlways(done: () -> Unit) {
        calls += "askAlways"
        if (grantNow == LocationGrant.WhileUsing) grantNow = answerAlways
        done()
    }

    override fun openSettings() {
        calls += "openSettings"
    }

    override fun record(request: RecordRequest, onFixes: (List<RouteFix>) -> Unit) {
        requests += request
        listener = onFixes
        recording = true
    }

    override fun stopRecording() {
        stops++
        recording = false
        listener = null
    }

    override suspend fun bestFix(timeoutMs: Long, maxAccuracyM: Float): RouteFix? {
        calls += "bestFix"
        return nextBestFix?.takeIf { (it.accuracyM ?: 0f) <= maxAccuracyM }
    }

    override fun watchChanges(onChange: () -> Unit) {
        watcher = onChange
    }

    /** Fixes from the phone, as Core Location hands them over. */
    fun deliver(vararg fixes: RouteFix) {
        listener?.invoke(fixes.toList())
    }

    /** The grant or Location Services changed in Settings. */
    fun changed() {
        watcher?.invoke()
    }
}

/** Whether the app may post notifications: not asked yet, refused, or allowed. */
enum class NoteGrant { NotAsked, Refused, Allowed }

/** What a tap on a route note opens: the app, or the app's page in the phone's Settings. */
enum class NoteOpens { App, Settings }

/** One of route tracking's notes (Android's TrackingNotifications): quiet, no sound, no badge. */
data class RouteNote(val id: String, val title: String, val text: String, val opens: NoteOpens = NoteOpens.App)

/**
 * The phone's notifications, for route tracking's notes: UserNotifications on iPhone (iosMain, IosPhoneNotes), a
 * recording stand-in elsewhere ([MemoryPhoneNotes]). Asked only by the route tracking's steps, never at the start.
 */
interface PhoneNotes {
    /** The permission as last read ([readNoteGrant] refreshes it: the phone answers later). */
    fun noteGrant(): NoteGrant

    /** Reads the permission again; [done] once it is known. */
    fun readNoteGrant(done: () -> Unit)

    /** The system's question (the first time only); [done] says whether notes are allowed. */
    fun askNotes(done: (Boolean) -> Unit)

    /** Posts [note], replacing the one with its id. Nothing when notes aren't allowed. */
    fun postNote(note: RouteNote)

    /** Takes the note with [id] away, if it is there. */
    fun cancelNote(id: String)
}

/** Notifications that are only kept: tests, previews, the desktop. */
class MemoryPhoneNotes(var grantNow: NoteGrant = NoteGrant.NotAsked, var answer: Boolean = true) : PhoneNotes {
    /** What shows now, by id. */
    val shown = LinkedHashMap<String, RouteNote>()
    val posted = ArrayList<RouteNote>()
    val asked = ArrayList<String>()

    override fun noteGrant() = grantNow
    override fun readNoteGrant(done: () -> Unit) = done()

    override fun askNotes(done: (Boolean) -> Unit) {
        asked += "notes"
        if (grantNow == NoteGrant.NotAsked) grantNow = if (answer) NoteGrant.Allowed else NoteGrant.Refused
        done(grantNow == NoteGrant.Allowed)
    }

    override fun postNote(note: RouteNote) {
        if (grantNow != NoteGrant.Allowed) return
        posted += note
        shown[note.id] = note
    }

    override fun cancelNote(id: String) {
        shown.remove(id)
    }
}

/**
 * The phone's runs in the background for the nightly check (Android: TrackProcessingWorker, a few minutes after
 * midnight): BGTaskScheduler on iPhone (iosMain, IosNightlyRuns), best effort; a stand-in elsewhere.
 */
interface NightlyRuns {
    /** Asks the phone to run the nightly check at [atMs] or later; an earlier request is replaced. */
    fun scheduleNight(atMs: Long)
}

/** Nightly runs that are only noted: tests, previews, the desktop. */
class MemoryNightlyRuns : NightlyRuns {
    var scheduledAt: Long? = null
    override fun scheduleNight(atMs: Long) {
        scheduledAt = atMs
    }
}

/**
 * What shows outside the app while the route records (the Android app's ongoing "Recording your route · Balanced"
 * notification with the trip and Stop: TrackingNotifications.recording). iPhone has no ongoing notification: it is a
 * Live Activity on the Lock Screen and in the Dynamic Island with the same words and Stop (ActivityKit, Swift's
 * RecordingActivityBridge, through iosMain's IosRecordingCard); a stand-in that keeps what it shows elsewhere.
 */
interface RecordingCard {
    /** Shows [card] (or brings the one showing up to date). */
    fun showRecordingCard(card: RecordingCardText)

    /** Takes it away, if it is there. */
    fun hideRecordingCard()
}

/** The recording card's words: the title, the trip's name (empty: none) and the button. */
data class RecordingCardText(val title: String, val trip: String, val stop: String = "Stop")

/** A recording card that is only kept: tests, previews, the desktop. */
class MemoryRecordingCard : RecordingCard {
    var showing: RecordingCardText? = null
        private set

    /** Every card shown, in order. */
    val shown = ArrayList<RecordingCardText>()

    override fun showRecordingCard(card: RecordingCardText) {
        if (showing != card) shown += card
        showing = card
    }

    override fun hideRecordingCard() {
        showing = null
    }
}

/** Route tracking's notes in the Android app's words (tracking/TrackingNotifications.kt). */
object TrackingNotes {
    const val PAUSED = "route-paused"
    const val LOCATION_OFF = "route-location-off"
    const val PERMISSION = "route-permission"

    /** "Recording your route · Balanced" (Android's TrackingNotifications.recordingTitle). */
    fun recordingTitle(level: TrackingLevel): String = "Recording your route · ${level.label}"

    /** The recording card while [tripTitle] records at [level] (Android's recording notification: title, text, Stop). */
    fun recording(tripTitle: String, level: TrackingLevel) = RecordingCardText(recordingTitle(level), tripTitle.trim())

    /** "Route notes", Android's channel: what a note's thread is called. */
    const val THREAD = "route_notes"

    /**
     * The route stopped recording without the traveller asking (iOS ended the app, the phone restarted): opening the
     * app starts it again. Never starts anything itself.
     */
    fun paused(tripTitle: String) = RouteNote(
        PAUSED, "Your route isn't recording", listOfNotNull(tripTitle.ifBlank { null }, "Open BoringTravel to go on.").joinToString(" · "),
    )

    /** Location Services switched off during a trip that records (a tap: the app's settings; iPhone can't open Location Services). */
    fun locationOff(tripTitle: String) = RouteNote(
        LOCATION_OFF, "Location is off",
        listOfNotNull(tripTitle.ifBlank { null }, "The route pauses until you turn it on.").joinToString(" · "), NoteOpens.Settings,
    )

    /** The nightly reminder: a trip records but location isn't allowed for the app. */
    fun permission(tripTitle: String) = RouteNote(
        PERMISSION, tripTitle.ifBlank { "Route tracking" }, "Location is not allowed for BoringTravel, so the route isn't kept.", NoteOpens.Settings,
    )
}

/**
 * The iPhone's side of the Android app's TrackingPlan (read in): how a level records here, what it asks for, and what
 * to do about recording now. Android's own `decide` and `missing` are for its two ways of recording (a service with a
 * notification, a receiver with "Allow all the time"); this phone has one, Core Location's background updates.
 */
object IosTrackingPlan {
    /**
     * The one switch for the Always path, off as Android's TrackingPlan.BACKGROUND_RECORDING is (Android removes its
     * background permission and never shows "Allow all the time"). Off: the steps end after precise location and the
     * notes, the app never asks for Always, the significant-change service is never started, and recording runs as
     * Android's does today (started while the app is on screen, going on in the background with the blue location
     * sign); an Always given by hand in Settings is taken as While Using. On: the steps end with Always ("Allow all the
     * time", with Android's disclosure), and with Always given the significant-change service runs beside the recording,
     * so iOS starts the app again after it was closed (App Review then needs its background-location notes). Every rule
     * below takes it as a parameter, so the tests run both ways.
     */
    const val ASK_ALWAYS = false

    /** Balanced's best fix: GPS for at most this long (Android's BestFixAlarm.LOOKUP_MS). */
    const val BEST_FIX_LOOKUP_MS = 8_000L

    /** A best fix less accurate than this is not kept (BestFixAlarm.MAX_ACCURACY_M). */
    const val BEST_FIX_MAX_ACCURACY_M = 200f

    /** A point this recent: Balanced is moving, no best fix needed (BestFixAlarm.FRESH_MS). */
    const val FRESH_MS = 10 * 60_000L

    /** Detailed checks for stillness this often (RouteRecordingService.CHECK_MS). */
    const val REST_CHECK_MS = 2 * 60_000L

    /** Detailed at rest: a fix only after this many metres (RouteRecordingService.REST_DISTANCE_M). */
    const val REST_DISTANCE_M = 50.0

    /**
     * What to do now, as Android's TrackingPlan.decide, where "Allow all the time" becomes iPhone's Always:
     * [hasLocation] = While Using or Always; [stopped] = the traveller stopped today's trip; [onScreen] = the app is in
     * front (the only time recording may start without the Always path); [recording] = it records; [hasAlways] = Always
     * is given, which counts only with [askAlways] on (Android: `background && hasBackground`).
     */
    fun decide(
        target: TrackingTarget, hasLocation: Boolean, stopped: Boolean, onScreen: Boolean, recording: Boolean, hasAlways: Boolean,
        askAlways: Boolean = ASK_ALWAYS,
    ): Recording = when {
        target.level == TrackingLevel.Off || target.tripId == null -> Recording.Off
        !hasLocation -> Recording.NoPermission
        stopped -> Recording.Stopped
        recording -> Recording.KeepService
        onScreen || (askAlways && hasAlways) -> Recording.StartService
        else -> Recording.WaitForApp
    }

    /**
     * What [level] still needs, in the order the steps ask: precise location While Using (Android: precise location),
     * then the notes (Android: notifications), then, only with [askAlways] on, Always (Android: "Allow all the time",
     * asked only while its BACKGROUND_RECORDING is on). Off: nothing.
     */
    fun missing(level: TrackingLevel, hasLocation: Boolean, precise: Boolean, hasAlways: Boolean, hasNotes: Boolean, askAlways: Boolean = ASK_ALWAYS): List<Step> = buildList {
        if (level == TrackingLevel.Off) return@buildList
        if (!hasLocation || !precise) add(Step.Precise)
        if (!hasNotes) add(Step.Notifications)
        if (askAlways && hasLocation && !hasAlways) add(Step.Background)
    }

    /**
     * How [level] records ([always]: Always is given; [resting]: Detailed while the phone stays put): Low and Balanced
     * on Wi-Fi and cell towers at their distance, Detailed on GPS every 15 m (at rest on Wi-Fi every 50 m); with the
     * Always path on ([askAlways]) and Always given, the significant-change service beside them, and for Low alone (the
     * lightest: no standard updates). With the path off, Always given changes nothing here.
     */
    fun request(level: TrackingLevel, always: Boolean, resting: Boolean = false, askAlways: Boolean = ASK_ALWAYS): RecordRequest =
        changes(level, askAlways && always, resting)

    private fun changes(level: TrackingLevel, always: Boolean, resting: Boolean): RecordRequest = when (level) {
        TrackingLevel.Detailed ->
            if (resting) RecordRequest(level, precise = false, distanceM = REST_DISTANCE_M, continuous = true, significantChanges = always)
            else RecordRequest(level, precise = true, distanceM = level.minDistanceM.toDouble(), continuous = true, significantChanges = always)
        TrackingLevel.Low -> RecordRequest(level, precise = false, distanceM = level.minDistanceM.toDouble(), continuous = !always, significantChanges = always)
        else -> RecordRequest(level, precise = false, distanceM = level.minDistanceM.toDouble(), continuous = true, significantChanges = always)
    }

    /** The traveller's own trip on [today] (Android's TripRepository.ownCurrentOn): never an example, in demo mode too. */
    fun ownCurrent(trips: List<Trip>, today: LocalDate): Trip? = TripsHomeData.onDay(trips.filter { !it.isDemo }, today)
}

/**
 * The fixes kept, at Android's spacing: Core Location sends a fix whenever the phone moved the level's distance, where
 * Android's fused provider sends one no sooner than half the level's interval and only after its distance
 * (RouteRecordingService.requestFor). Bad fixes go first, as Android's TrackWriter drops them (no position, the
 * middle of the ocean at 0, 0), with what only iPhone sends: a negative accuracy (no fix) and a fix kept from long ago.
 */
object PointSpacing {
    /** A fix older than this when it arrives is the phone's memory, not where it is now. */
    const val STALE_MS = 10 * 60_000L

    fun usable(fix: RouteFix, now: Long): Boolean =
        !fix.lat.isNaN() && !fix.lng.isNaN() && !(fix.lat == 0.0 && fix.lng == 0.0) &&
            fix.lat in -90.0..90.0 && fix.lng in -180.0..180.0 &&
            (fix.accuracyM == null || fix.accuracyM >= 0f) && now - fix.time <= STALE_MS

    /** Whether [fix] comes far enough after [last] (the point kept before it) for [level]. */
    fun spaced(last: RouteFix?, fix: RouteFix, level: TrackingLevel): Boolean {
        if (last == null) return true
        if (fix.time <= last.time) return false
        return fix.time - last.time >= level.intervalMs / 2 && Geo.distanceM(last.lat, last.lng, fix.lat, fix.lng) >= level.minDistanceM
    }

    /** The fixes of one delivery that are kept, after [last]. */
    fun thin(fixes: List<RouteFix>, last: RouteFix?, level: TrackingLevel, now: Long): List<RouteFix> {
        var prev = last
        return fixes.sortedBy { it.time }.filter { f -> usable(f, now) && spaced(prev, f, level).also { if (it) prev = f } }
    }
}

/** What the tracker is doing, for Me › Route tracking and the trip map (the Android app's TrackingStatus). */
data class TrackingStatus(
    val target: TrackingTarget = TrackingTarget.Off,
    val tripTitle: String = "",
    /** False when a level is chosen for today's trip but location isn't allowed. */
    val allowed: Boolean = true,
    /** Recording right now. */
    val recording: Boolean = false,
    /** The traveller stopped today's trip until tomorrow or Start recording. */
    val stopped: Boolean = false,
)

/**
 * What records now, kept in this phone's settings so a start in the background can read it (Android's TrackingState):
 * the trip, its title and level and last day, whether it records or waits for the app, the stop for today, Balanced's
 * last best fix, whether Always was asked, and the points not yet in the trip store. This phone's own: never in a
 * backup (the points are, once in the store).
 */
class TrackingStore(private val file: SettingsFile) {
    private fun read(key: String) = file.read(PREFIX + key)
    private fun write(key: String, value: String?) = file.write(PREFIX + key, value)

    val tripId: String? get() = read("trip")
    val tripTitle: String get() = read("title").orEmpty()
    val level: TrackingLevel get() = read("level")?.let { TrackingLevel.of(it) } ?: TrackingLevel.Off
    val lastDay: Long? get() = read("lastDay")?.toLongOrNull()

    /** [MODE_IDLE], [MODE_RECORDING] or [MODE_WAITING] (it recorded and stopped without the traveller asking). */
    val mode: String get() = read("mode") ?: MODE_IDLE
    val armedAt: Long get() = read("armedAt")?.toLongOrNull() ?: 0L
    val lastPointAt: Long get() = read("lastPointAt")?.toLongOrNull() ?: 0L

    /** Epoch day of the last "location isn't allowed" note: at most one a day. */
    val lastNoticeDay: Long? get() = read("noticeDay")?.toLongOrNull()
    val stoppedTrip: String? get() = read("stoppedTrip")
    val stoppedDay: Long? get() = read("stoppedDay")?.toLongOrNull()

    /** Whether the Always question was asked: iOS asks it once, and says nothing after that. */
    var alwaysAsked: Boolean
        get() = read("alwaysAsked") == "true"
        set(value) = write("alwaysAsked", if (value) "true" else null)

    val bestFix: Pair<Double, Double>? get() = read("bestFix")?.split(",")?.let { p ->
        val lat = p.getOrNull(0)?.toDoubleOrNull()
        val lng = p.getOrNull(1)?.toDoubleOrNull()
        if (lat != null && lng != null) lat to lng else null
    }
    val bestFixStill: Int get() = read("bestFixStill")?.toIntOrNull() ?: 0

    fun setBestFix(lat: Double, lng: Double, still: Int) {
        write("bestFix", "$lat,$lng")
        write("bestFixStill", still.toString())
    }

    fun resetBestFixStill() {
        if (bestFixStill != 0) write("bestFixStill", "0")
    }

    val target: TrackingTarget
        get() = if (tripId == null || level == TrackingLevel.Off) TrackingTarget.Off
        else TrackingTarget(tripId, level, lastDay?.let { com.justdataplease.boringtravel.time.localDateOfEpochDay(it) })

    fun isPastLastDay(today: LocalDate): Boolean = lastDay?.let { today.toEpochDay() > it } ?: false

    fun save(target: TrackingTarget, title: String, mode: String, armedAt: Long) {
        write("trip", target.tripId)
        write("title", title)
        write("level", target.level.id)
        write("lastDay", target.lastDay?.toEpochDay()?.toString())
        write("mode", mode)
        write("armedAt", armedAt.toString())
    }

    fun setLastPointAt(at: Long) {
        if (at > lastPointAt) write("lastPointAt", at.toString())
    }

    fun setLastNoticeDay(day: Long) = write("noticeDay", day.toString())

    fun setStopped(tripId: String, day: LocalDate) {
        write("stoppedTrip", tripId)
        write("stoppedDay", day.toEpochDay().toString())
    }

    fun clearStopped() {
        write("stoppedTrip", null)
        write("stoppedDay", null)
    }

    fun clear() {
        listOf("trip", "title", "level", "lastDay", "armedAt").forEach { write(it, null) }
        write("mode", MODE_IDLE)
    }

    /** Points recorded and not yet in the trip store (each fix is kept here at once; the store gets them in batches). */
    var inbox: List<TrackPointRow>
        get() = read("inbox")?.let { runCatching { BackupJson.decodeFromString(ROWS, it) }.getOrNull() }.orEmpty()
        set(value) = write("inbox", if (value.isEmpty()) null else BackupJson.encodeToString(ROWS, value))

    companion object {
        const val PREFIX = "tracking."
        const val MODE_IDLE = "idle"
        const val MODE_RECORDING = "recording"
        const val MODE_WAITING = "waiting"
        private val ROWS = ListSerializer(TrackPointRow.serializer())
    }
}

/**
 * Starts and stops recording as the Android app's TrackingController does: the level comes from the trip (its own) or
 * Me's, and applies only while a trip of the traveller's own is on (never an example). It starts when the app comes on
 * screen during a trip (or Start recording) and goes on in the background until Stop, the trip's last day or Off; with
 * Always it also starts when iOS starts the app in the background. Checked again when the app comes on screen, when a
 * level or a trip changes, when the location permission or Location Services change, just after midnight while it
 * runs, and in the nightly run.
 *
 * Fixes become the trip store's [TrackPoint] rows, the Android app's own (time, position, accuracy, speed, altitude,
 * level), so a backup carries them to either phone. Each fix is kept at once in [TrackingStore.inbox] (small) and the
 * store gets them in batches ([flush]): every [FLUSH_POINTS] points, after [FLUSH_MS], and whenever the app comes on
 * screen, leaves it, or a map or a note asks.
 *
 * [onScreen] says whether the app is in front; [quiet] (the Simulator's tour) never starts recording by itself.
 */
class RouteTracking(
    private val book: TripBook,
    private val settings: AppSettings,
    val sensor: RouteSensor,
    val notes: PhoneNotes,
    private val nightly: NightlyRuns,
    private val store: TrackingStore,
    private val scope: CoroutineScope,
    private val onScreen: () -> Boolean = { true },
    private val clock: () -> Long = ::nowMs,
    private val zone: () -> String? = { null },
    private val quiet: Boolean = false,
    /** The Always path ([IosTrackingPlan.ASK_ALWAYS], off as Android's background recording); tests turn it on. */
    private val askAlways: Boolean = IosTrackingPlan.ASK_ALWAYS,
    /** What shows outside the app while it records (Android's ongoing notification; iPhone: a Live Activity). */
    val card: RecordingCard = MemoryRecordingCard(),
) {
    /** What records now: Me › Route tracking and the trip map show it. */
    var status by mutableStateOf(TrackingStatus())
        private set

    /** Raised whenever recorded points go into the trip store: screens read the book again. */
    var written by mutableIntStateOf(0)
        private set

    /** Raised whenever a permission may have changed: the steps read the phone again. */
    var checks by mutableIntStateOf(0)
        private set

    private var started = false
    private var cameOnScreenOnce = false
    private var request: RecordRequest? = null
    private var lastKept: RouteFix? = null
    private var lastKeptTrip: String? = null
    private val stillness = Stillness()
    private var resting = false
    private var bestFixJob: Job? = null
    private var restJob: Job? = null
    private var dayJob: Job? = null

    private fun today(): LocalDate = localDateAt(clock(), zone())

    /** Once, when the app starts (in front or in the background): what an earlier run left, then the decision. */
    fun start() {
        if (started) return
        started = true
        sensor.watchChanges(::permissionsChanged)
        notes.readNoteGrant { checks++ }
        flush()
        apply()
    }

    /** The app came on screen: recording may start now (Android's ON_START), and the steps read the phone again. */
    fun cameOnScreen() {
        flush()
        notes.readNoteGrant { checks++ }
        checks++
        val first = !cameOnScreenOnce
        cameOnScreenOnce = true
        apply(force = first)
    }

    /** The app left the screen: what was recorded goes into the store. */
    fun leftScreen() {
        flush()
    }

    /** Me's level for every trip ([AppSettings.trackingLevel]), then what records follows. */
    fun setAppLevel(level: TrackingLevel) {
        settings.trackingLevel = level
        apply()
    }

    /** A trip's own level (null: Me's), as the Android app's TrackingController.setTripLevel. */
    fun setTripLevel(tripId: String, level: TrackingLevel?) {
        book.setTrackingLevel(tripId, level?.id)
        apply()
    }

    /** Start recording: today's trip records again after a Stop, or starts now. */
    fun startNow() {
        store.clearStopped()
        apply(force = true)
    }

    /** Stop for today: nothing records for today's trip until tomorrow or Start recording. */
    fun stopForToday() {
        val tripId = status.target.tripId ?: store.tripId
        if (tripId != null) store.setStopped(tripId, today())
        apply(force = true)
    }

    /** While Using or Always is given (Always given by hand counts as While Using while the Always path is off). */
    fun locationAllowed(): Boolean = sensor.grant().let { it == LocationGrant.WhileUsing || it == LocationGrant.Always }

    /** Always is given and the Always path is on: only then does anything of it start. */
    private fun alwaysCounts(): Boolean = askAlways && sensor.grant() == LocationGrant.Always

    /** What [level] still needs from the phone, in the order the steps ask ([IosTrackingPlan.missing]). */
    fun missing(level: TrackingLevel): List<Step> = IosTrackingPlan.missing(
        level, hasLocation = locationAllowed(), precise = sensor.precise(), hasAlways = sensor.grant() == LocationGrant.Always,
        hasNotes = notes.noteGrant() == NoteGrant.Allowed, askAlways = askAlways,
    )

    /** Whether [level] still needs a step, or Location Services are off: the steps have something to show. */
    fun stepsLeft(level: TrackingLevel): Boolean = level != TrackingLevel.Off && (missing(level).isNotEmpty() || !sensor.servicesOn())

    /**
     * Whether [step] was answered no already, so only the app's page in Settings can change it (iOS asks each question
     * once): location refused, or given without Precise Location; notes refused; Always asked and not given.
     */
    fun settingsFor(step: Step): Boolean = when (step) {
        Step.Precise -> sensor.grant() == LocationGrant.Refused || (locationAllowed() && !sensor.precise())
        Step.Notifications -> notes.noteGrant() == NoteGrant.Refused
        Step.Background -> askAlways && store.alwaysAsked
    }

    /** A step's Allow: the phone's own question for it, or the app's page in Settings once it was answered no. */
    fun allow(step: Step) {
        if (settingsFor(step)) {
            sensor.openSettings()
            return
        }
        when (step) {
            Step.Precise -> sensor.askWhileUsing(::permissionsChanged)
            Step.Notifications -> notes.askNotes { permissionsChanged() }
            Step.Background -> {
                // Never with the Always path off (the step isn't offered then).
                if (!askAlways) return
                // iOS asks this once: from now on the step leads to Settings, whatever the answer.
                store.alwaysAsked = true
                checks++
                sensor.askAlways(::permissionsChanged)
            }
        }
    }

    /** The steps read the phone again (the app came back from Settings). */
    fun recheck() {
        checks++
        notes.readNoteGrant { checks++ }
    }

    /** A trip was deleted: its route goes (the Android app's TrackDao.deleteForTrip), from the store and from what waits. */
    fun tripDeleted(tripId: String) {
        val waiting = store.inbox
        if (waiting.any { it.tripId == tripId }) store.inbox = waiting.filter { it.tripId != tripId }
        if (book.removeTrackPoints(tripId)) written++
        apply()
    }

    /** The latest recorded point (the Android app's TrackDao.latest), for a note's place: what waits counts too. */
    fun latestPoint(): TrackPoint? {
        val waiting = store.inbox.maxByOrNull { it.time }?.let { TrackPoint(0, it.tripId, it.time, it.lat, it.lng, it.accuracyM, it.speedMps, it.altitudeM, it.level) }
        val kept = book.latestTrackPoint()
        return listOfNotNull(waiting, kept).maxByOrNull { it.time }
    }

    /** What waits goes into the trip store now (a map opening, the app leaving the screen). */
    fun flush() {
        val waiting = store.inbox
        if (waiting.isEmpty()) return
        book.addTrackPoints(waiting.map { TrackPoint(0, it.tripId, it.time, it.lat, it.lng, it.accuracyM, it.speedMps, it.altitudeM, it.level) })
        store.inbox = emptyList()
        written++
    }

    /**
     * Brings the recording in line with today's trip and level (Android's TrackingController.apply). [force]: the
     * phone's recording is set again even when nothing seems to have changed.
     */
    fun apply(force: Boolean = false) {
        val now = clock()
        val today = today()
        val trip = IosTrackingPlan.ownCurrent(book.snapshot.trips, today)
        val target = TrackingPlan.target(trip, settings.trackingLevel, today)
        val title = trip?.title.orEmpty()
        val grant = sensor.grant()
        val allowed = grant == LocationGrant.WhileUsing || grant == LocationGrant.Always
        val stopped = TrackingPlan.stoppedToday(store.stoppedTrip, store.stoppedDay, target.tripId, today)
        // As Android's service: already recording, it goes on with the new level or trip.
        when (IosTrackingPlan.decide(target, allowed, stopped, onScreen(), sensor.recording, grant == LocationGrant.Always, askAlways)) {
            Recording.Off, Recording.NoPermission, Recording.Stopped -> {
                if (sensor.recording || store.mode != TrackingStore.MODE_IDLE || force) stopAll()
                store.clear()
                notes.cancelNote(TrackingNotes.PAUSED)
            }
            Recording.StartService, Recording.Receiver -> {
                if (quiet) {
                    store.save(target, title, TrackingStore.MODE_WAITING, store.armedAt)
                } else {
                    store.save(target, title, TrackingStore.MODE_RECORDING, now)
                    record(target.level, alwaysCounts(), force = true)
                    notes.cancelNote(TrackingNotes.PAUSED)
                }
            }
            Recording.KeepService -> {
                store.save(target, title, TrackingStore.MODE_RECORDING, store.armedAt)
                record(target.level, alwaysCounts(), force)
                notes.cancelNote(TrackingNotes.PAUSED)
            }
            Recording.WaitForApp -> {
                // It was recording and stopped without the traveller asking (iOS ended the app, the phone restarted):
                // say so, once.
                val interrupted = store.mode == TrackingStore.MODE_RECORDING && store.tripId == target.tripId
                if (sensor.recording) stopAll()
                store.save(target, title, TrackingStore.MODE_WAITING, store.armedAt)
                if (interrupted) notes.postNote(TrackingNotes.paused(title))
            }
        }
        status = TrackingStatus(
            target, title, allowed = allowed || target.level == TrackingLevel.Off,
            recording = sensor.recording, stopped = stopped && target.level != TrackingLevel.Off,
        )
        showRecordingCard()
        checkLocationOff()
        scheduleDayCheck(target)
        nightly.scheduleNight(now + TrackingPlan.msUntilNextDay(now, zone()))
    }

    /** The nightly run (Android's TrackProcessingWorker): the new day's recording, the recent trips' stops, the reminder. */
    suspend fun nightly(processor: TrackProcessor?) {
        step { start() }
        step { apply() }
        step { flush() }
        step { processor?.processRecent(today(), settings.trackingLevel) }
        step { checkPermissionNote() }
    }

    private inline fun step(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // One failed step (no geocoder, nothing to read) doesn't stop the others.
        }
    }

    /**
     * The nightly reminder (Android's TrackingController.checkPermissionNote): a trip records but location isn't allowed
     * for the app. At most once a day; gone once it is allowed again.
     */
    fun checkPermissionNote() {
        val s = status
        val grant = sensor.grant()
        // With Location Services off iOS reports every app as refused: "Location is off" says it then, not this.
        val missing = s.target.level != TrackingLevel.Off && s.target.tripId != null && grant != LocationGrant.WhileUsing &&
            grant != LocationGrant.Always && sensor.servicesOn()
        if (!missing) {
            notes.cancelNote(TrackingNotes.PERMISSION)
            return
        }
        val day = today().toEpochDay()
        if (store.lastNoticeDay == day) return
        store.setLastNoticeDay(day)
        notes.postNote(TrackingNotes.permission(s.tripTitle))
    }

    /**
     * While the route records, the card outside the app (Android's recording notification: "Recording your route ·
     * Balanced", the trip, Stop), shown as Android shows its notification: only when notifications are allowed (the
     * steps' Notifications: "A quiet notification shows while your route records…"); gone once it stops.
     */
    private fun showRecordingCard() {
        val s = status
        if (s.recording && s.target.level != TrackingLevel.Off && notes.noteGrant() == NoteGrant.Allowed) {
            card.showRecordingCard(TrackingNotes.recording(s.tripTitle, s.target.level))
        } else {
            card.hideRecordingCard()
        }
    }

    /** Location Services off while today's trip records (Android's LocationWatch.check): a note, gone when they are back. */
    private fun checkLocationOff() {
        val s = status
        val on = s.target.tripId != null && s.target.level != TrackingLevel.Off && !s.stopped
        if (on && !sensor.servicesOn()) notes.postNote(TrackingNotes.locationOff(s.tripTitle))
        else notes.cancelNote(TrackingNotes.LOCATION_OFF)
    }

    /** The grant or Location Services changed (in Settings, or the system's question was answered). */
    private fun permissionsChanged() {
        checks++
        apply()
    }

    /** Recording at [level] ([always]: Always counts, [alwaysCounts]), with Balanced's best fix and Detailed's rest. */
    private fun record(level: TrackingLevel, always: Boolean, force: Boolean) {
        val wanted = IosTrackingPlan.request(level, always, resting = resting && level == TrackingLevel.Detailed, askAlways = askAlways)
        if (!force && sensor.recording && wanted == request) return
        if (request?.level != level) {
            resting = false
            stillness.reset(clock())
            if (level == TrackingLevel.Balanced) store.resetBestFixStill()
        }
        request = IosTrackingPlan.request(level, always, resting = resting && level == TrackingLevel.Detailed, askAlways = askAlways)
        sensor.record(request!!, ::onFixes)
        bestFixJob?.cancel()
        bestFixJob = if (level == TrackingLevel.Balanced) scope.launch { bestFixes() } else null
        restJob?.cancel()
        restJob = if (level == TrackingLevel.Detailed) scope.launch { restChecks() } else null
    }

    private fun stopAll() {
        sensor.stopRecording()
        request = null
        resting = false
        bestFixJob?.cancel()
        bestFixJob = null
        restJob?.cancel()
        restJob = null
        flush()
    }

    /** Fixes from the phone (Android's TrackWriter.write): the trip's points, at the level's spacing. */
    private fun onFixes(fixes: List<RouteFix>) {
        val tripId = store.tripId ?: return
        if (store.isPastLastDay(today())) {
            // The trip ended and the midnight check hasn't run yet.
            apply()
            return
        }
        val level = store.level
        val now = clock()
        val last = lastKept?.takeIf { lastKeptTrip == tripId } ?: latestPoint()?.takeIf { it.tripId == tripId }?.let { RouteFix(it.time, it.lat, it.lng) }
        val kept = PointSpacing.thin(fixes, last, level, now)
        if (kept.isNotEmpty()) keep(tripId, level, kept)
        if (level == TrackingLevel.Detailed) {
            var moved = false
            for (f in fixes) moved = stillness.onFix(f.lat, f.lng, f.time, f.accuracyM) || moved
            if (resting && moved) wake()
        }
    }

    private fun keep(tripId: String, level: TrackingLevel, fixes: List<RouteFix>) {
        val rows = fixes.map { TrackPointRow(tripId, it.time, it.lat, it.lng, it.accuracyM, it.speedMps, it.altitudeM, level.id) }
        val waiting = store.inbox + rows
        store.inbox = waiting
        lastKept = fixes.last()
        lastKeptTrip = tripId
        store.setLastPointAt(fixes.maxOf { it.time })
        if (waiting.size >= FLUSH_POINTS || clock() - waiting.first().time >= FLUSH_MS) flush()
    }

    /** Balanced's best fix (Android's BestFixAlarm): every 20 minutes, 40 and 60 while the phone stays at one spot. */
    private suspend fun bestFixes() {
        while (true) {
            delay(TrackingPlan.bestFixDelayMs(store.bestFixStill))
            if (store.level != TrackingLevel.Balanced || !sensor.recording) return
            if (clock() - store.lastPointAt <= IosTrackingPlan.FRESH_MS) {
                store.resetBestFixStill()
            } else {
                val fix = runCatching { sensor.bestFix(IosTrackingPlan.BEST_FIX_LOOKUP_MS, IosTrackingPlan.BEST_FIX_MAX_ACCURACY_M) }.getOrNull()
                val tripId = store.tripId
                if (fix != null && tripId != null && PointSpacing.usable(fix, clock())) {
                    if (lastKept?.time != fix.time) keep(tripId, TrackingLevel.Balanced, listOf(fix))
                    val same = store.bestFix?.let { (lat, lng) -> Geo.distanceM(lat, lng, fix.lat, fix.lng) <= TrackingPlan.BEST_FIX_SAME_SPOT_M } == true
                    store.setBestFix(fix.lat, fix.lng, if (same) store.bestFixStill + 1 else 0)
                }
            }
            checkLocationOff()
        }
    }

    /** Detailed rests after ten minutes at one spot (RouteRecordingService.rest), and wakes on a fix that moved. */
    private suspend fun restChecks() {
        while (true) {
            delay(IosTrackingPlan.REST_CHECK_MS)
            if (store.level != TrackingLevel.Detailed || !sensor.recording) return
            if (!resting && stillness.isStill(clock())) rest()
        }
    }

    private fun rest() {
        resting = true
        request = IosTrackingPlan.request(TrackingLevel.Detailed, alwaysCounts(), resting = true, askAlways = askAlways)
        sensor.record(request!!, ::onFixes)
    }

    private fun wake() {
        resting = false
        stillness.reset(clock())
        request = IosTrackingPlan.request(TrackingLevel.Detailed, alwaysCounts(), resting = false, askAlways = askAlways)
        sensor.record(request!!, ::onFixes)
    }

    /** Just after midnight, while a trip's recording is on or waits: the new day's decision (the trip may have ended). */
    private fun scheduleDayCheck(target: TrackingTarget) {
        dayJob?.cancel()
        dayJob = if (target.tripId == null) null else scope.launch {
            delay(TrackingPlan.msUntilNextDay(clock(), zone()))
            apply()
        }
    }

    /** Whether Detailed rests now (the phone stayed put): tests. */
    val isResting: Boolean get() = resting

    companion object {
        /** The store gets the waiting points once there are this many… */
        const val FLUSH_POINTS = 20

        /** …or once the oldest has waited this long. */
        const val FLUSH_MS = 10 * 60_000L
    }
}

/** The app's route tracking, for the screens; null in previews and tests without one. */
val LocalTracking = staticCompositionLocalOf<RouteTracking?> { null }

/** What records now, in one line (the Android app's recordingText); [appLevel] is Me's level (Off for a new install). */
fun recordingText(s: TrackingStatus, appLevel: TrackingLevel? = null): String {
    val trip = s.tripTitle.ifBlank { "today's trip" }
    return when {
        s.target.tripId == null && s.tripTitle.isNotBlank() -> "Off for $trip."
        s.target.tripId == null && appLevel == TrackingLevel.Off -> "Off. Pick a level to record your route during trips."
        s.target.tripId == null -> "No trip today. Recording starts when you open BoringTravel during a trip."
        !s.allowed -> "Waiting for location permission for $trip."
        s.stopped -> "Stopped for today · $trip"
        s.recording -> "Recording $trip · ${s.target.level.label}"
        else -> "Not recording $trip yet."
    }
}

/**
 * How recording runs (the Android app's howItRecords, where a notification shows while it records): on iPhone the blue
 * location sign shows instead.
 */
fun howItRecords(): String =
    "It starts when you open BoringTravel during a trip and goes on in the background, with the blue location sign. Stop for today pauses it until tomorrow."
