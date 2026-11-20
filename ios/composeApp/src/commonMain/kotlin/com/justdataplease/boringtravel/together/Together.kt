package com.justdataplease.boringtravel.together

import com.justdataplease.boringtravel.compat.Context
import com.justdataplease.boringtravel.compat.File
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.Stamps
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.google.DriveException
import com.justdataplease.boringtravel.google.DriveNotConnectedException
import com.justdataplease.boringtravel.google.PublicDrive
import com.justdataplease.boringtravel.google.RestDriveApi
import com.justdataplease.boringtravel.io.IOException
import com.justdataplease.boringtravel.ios.AppFileStore
import com.justdataplease.boringtravel.ios.AppSettings
import com.justdataplease.boringtravel.ios.BackgroundTicket
import com.justdataplease.boringtravel.ios.BackgroundWork
import com.justdataplease.boringtravel.ios.MediaFiles
import com.justdataplease.boringtravel.ios.MemorySettingsFile
import com.justdataplease.boringtravel.ios.Net
import com.justdataplease.boringtravel.ios.NoBackgroundWork
import com.justdataplease.boringtravel.ios.PhotoPrep
import com.justdataplease.boringtravel.ios.SettingsFile
import com.justdataplease.boringtravel.ios.SharedTripsState
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.time.today
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** What the trips' mailboxes need from the app (Android's MailAccess): the mailboxes, and a Firebase sign-in with the app's account. */
class MailAccess(
    val service: MailService,
    /** Signs in to Firebase with the app's account; [quiet] never shows Google's page. True when signed in. */
    val signIn: suspend (quiet: Boolean) -> Boolean,
)

/**
 * The phone's background turn for shared trips (Android's periodic WorkManager job "together-sync" and its sends'
 * retries): iPhone's BGAppRefreshTask (iosMain, IosTogetherRuns), best effort; nothing on the desktop.
 */
interface TogetherRuns {
    /** Asks the phone for the background catch-up from [atMs] on; null cancels it. */
    fun planCatchUp(atMs: Long?)
}

/** No background turn (previews, the desktop window). */
object NoTogetherRuns : TogetherRuns {
    override fun planCatchUp(atMs: Long?) = Unit
}

/** A background turn that only notes what it was asked (tests and pictures). */
class MemoryTogetherRuns : TogetherRuns {
    val asked = mutableListOf<Long?>()
    val planned: Long? get() = asked.lastOrNull()

    override fun planCatchUp(atMs: Long?) {
        asked += atMs
    }
}

/**
 * Shared trips in the app (the Android app's together/Together.kt written again, round 26): [TogetherEngine] (read in)
 * over this app's trip store ([book]), the owner's Drive ([drive]: round 23's REST), other members' links ([links]:
 * round 24's PublicDrive with the app's API key) and the photo files ([files]). Every change to a shared trip, wherever
 * it is made, queues a send ([sendSoon]) about 4 s later that waits for a connection and is tried again until it gets
 * through, kept on the phone ([keep]) so it outlives the app being closed. The others' changes come in every
 * [OPEN_EVERY_MS] while the trip's screen is in front ([keepInSync]); at start and every 3 h in the background
 * ([runs]: BGAppRefreshTask) for the shared trips that haven't long ended ([inBackground]). Each trip's mailbox
 * ([TripMail], [mail]) tells the members where everyone's copy is: watched while the trip is in front, read once at
 * start, in the background and from the sheets.
 *
 * [bookContext] is where the store is read and written (the main thread on iPhone, as every screen writes it there);
 * the network, the files and the gzip run on [work].
 */
class Together(
    private val book: TripBook,
    private val settings: AppSettings,
    private val files: MediaFiles,
    drive: RestDriveApi?,
    links: PublicDrive,
    private val scope: CoroutineScope,
    prep: PhotoPrep,
    private val net: Net? = null,
    private val bookContext: CoroutineContext = EmptyCoroutineContext,
    private val work: CoroutineContext = Dispatchers.Default,
    private val runs: TogetherRuns = NoTogetherRuns,
    private val background: BackgroundWork = NoBackgroundWork,
    /** Where the sends waiting for a connection are kept between starts (the app's settings file). */
    private val keep: SettingsFile = MemorySettingsFile(),
    /** The Google account Drive is connected with, for a name when the traveller isn't signed in. */
    private val driveAccount: () -> String? = { null },
    /** The trips' mailboxes and the Firebase sign-in they need; null without Firebase. */
    private val mailbox: () -> MailAccess? = { null },
    private val clock: () -> Long = { Stamps.now() },
    /** How long a send waits after a change (tests: 0). */
    private val changeWait: Long = CHANGE_WAIT_MS,
    /** How long a failed send waits the first time (doubling, as WorkManager's backoff; tests: short). */
    private val retryFirst: Long = RETRY_FIRST_MS,
) {
    /** The screens read the store again (a merge, a photo's copy): the app's frame sets it. */
    var onRowsChanged: () -> Unit = {}

    val engine = TogetherEngine(
        rows = BookRows(book, bookContext) { onRowsChanged() },
        own = DriveOwn(drive, settings),
        links = object : LinkReader {
            override suspend fun checksum(fileId: String) = links.checksum(fileId)
            override suspend fun read(fileId: String, maxBytes: Long) = links.bytes(fileId, maxBytes)
            override suspend fun download(fileId: String, target: File, maxBytes: Long) = links.download(fileId, files, target.path, maxBytes)
        },
        photos = object : PhotoFiles {
            override fun file(media: Media) = File(media.localPath, AppFileStore(files))
            override fun smallCopy(media: Media): File? = prep.scaled(media, SMALL_PX, quality = 84)?.let { File(it.path, AppFileStore(files)) }
        },
        store = FileStore(files, STATE_FILE),
        clock = clock,
        // Big videos keep to Me › Backup › Only on Wi-Fi, as the backup's own videos do (a costly network: Wi-Fi's place).
        bigFilesWait = { settings.wifiOnly && net?.metered() == true },
    )

    init {
        // Read at once: a trip opened right after the app started shows as shared and keeps in step, without waiting
        // for the first sync.
        scope.launch(work) { runCatching { engine.load() } }
    }

    val state: StateFlow<TogetherState> get() = engine.state
    val status: StateFlow<Map<String, SyncStatus>> get() = engine.status

    /** Whether trip [tripId] is shared on this phone now (Write's video note, the check-in's member). */
    fun isShared(tripId: String?): Boolean = tripId != null && state.value.of(tripId) != null

    /** The trips shared now (a restore never rolls them back: BackupMerge's shared). */
    fun sharedNow(): Set<String> = state.value.trips.mapTo(HashSet()) { it.tripId }

    /** What a backup carries of the shared trips and what a restore brings back (Android's AppGraph hands the same). */
    fun backupState(): SharedTripsState = object : SharedTripsState {
        override suspend fun export(): String? = engine.exportState()
        override suspend fun import(json: String) = engine.importState(json)
        override fun sharedNow(): Set<String> = this@Together.sharedNow()
    }

    /** Left on this phone (the trip's row is deleted, its rows kept for joining again). */
    fun isLeft(tripId: String): Boolean = state.value.of(tripId) == null && tripId in state.value.former

    /** This phone's member id in trip [tripId], or null when it isn't shared. */
    suspend fun memberOf(tripId: String): String? = engine.load().of(tripId)?.me

    /** The name another member shows under ([Member.name]), for a note they wrote. */
    fun nameOf(authorId: String): String? = state.value.trips.firstNotNullOfOrNull { t -> t.members.firstOrNull { it.id == authorId }?.name }

    private val access: MailAccess? by lazy { runCatching { mailbox() }.getOrNull() }

    /** Members the mailbox added to a trip by themselves (nobody tapped Add here), for a word on screen. */
    data class Joined(val tripId: String, val names: List<String>)

    private val _joined = MutableSharedFlow<Joined>(extraBufferCapacity = 8)
    val joined: SharedFlow<Joined> = _joined.asSharedFlow()

    /** The shared trips' mailboxes ([TripMail]); null in a build without Firebase. Nothing needs it to work. */
    val mail: TogetherMail? by lazy {
        access?.let { a ->
            TogetherMail(
                engine, a.service, clock = clock, onAdded = { scope.launch(work) { quietly(it) } },
                onJoined = { tripId, names -> _joined.tryEmit(Joined(tripId, names)) },
            )
        }
    }

    /** Whether a sheet already tried a sign-in by itself this run ([signInForMail] with onceARun). */
    private var triedAlone = false

    /**
     * Signs in to Firebase with the app's account, for the trips' mailboxes: [quiet] never shows Google's page (only an
     * account already signed in to BoringTravel). [activity]: the screen's context (Android's Activity; unused here).
     * [onceARun]: a try nobody asked for (a sheet opening), made once per run. True when signed in.
     */
    suspend fun signInForMail(@Suppress("UNUSED_PARAMETER") activity: Context?, quiet: Boolean, onceARun: Boolean = false): Boolean {
        val a = access ?: return false
        if (a.service.uid.value != null) return true
        if (onceARun) {
            if (triedAlone) return false
            triedAlone = true
        }
        return try {
            a.signIn(quiet)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The mailbox from a sheet: posts this phone's address for [tripId] now (not waiting after a failure) and, with
     * [read], reads the mailbox once. True when the others pick this phone's copy up by themselves: posted, and another
     * member is in the mailbox (so their app reads it).
     */
    suspend fun checkMail(tripId: String, read: Boolean = true): Boolean = withContext(work) {
        val m = mail ?: return@withContext false
        val posted = mailQuietly { m.post(tripId, force = true) } == true
        if (read) mailQuietly { m.look(tripId) }
        val s = engine.load().of(tripId) ?: return@withContext false
        val inBox = m.seen.value[tripId].orEmpty()
        posted && s.members.any { !it.left && it.id in inBox }
    }

    /** Whether the mailbox of [tripId] is watched now (its trip in front): the sheets then need no read of their own. */
    fun mailWatched(tripId: String): Boolean = mail?.watching(tripId) == true

    /**
     * Takes this phone's entries out of every trip's mailbox, also the trips it left. For Me › Delete account, before
     * the Firebase account goes: nobody can take them out after. Throws when Firestore couldn't be told.
     */
    suspend fun forgetMail() = withContext(work) {
        val m = mail ?: return@withContext
        val st = engine.load()
        val ids = (st.trips.map { it.tripId } + st.former.keys + st.unfinished.map { it.tripId }).distinct()
        for (id in ids) {
            withTimeoutOrNull(MAIL_WAIT_MS) { m.drop(id) } ?: throw IOException("Couldn't reach the shared trips' mailboxes. Try again.")
        }
        st.trips.forEach { engine.mailed(it.tripId, null, 0) }
    }

    /** A mailbox call that never fails what it is part of: null when it failed or took over [MAIL_WAIT_MS]. */
    private suspend fun <T> mailQuietly(call: suspend () -> T): T? = try {
        withTimeoutOrNull(MAIL_WAIT_MS) { call() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Goes up when other members' photos arrive (photo tiles load again: MediaImage). */
    val photosVersion: StateFlow<Int> get() = engine.photosVersion

    /** The name shown to the other members: the Google account's first name, else its email's first part. */
    fun myName(): String {
        val name = settings.accountName?.trim().orEmpty()
        if (name.isNotEmpty()) return ShareCodec.cleanName(name.substringBefore(' ').ifBlank { name })
        return ShareCodec.cleanName((settings.accountEmail ?: driveAccount())?.substringBefore('@').orEmpty())
    }

    suspend fun share(tripId: String, title: String): ShareLink = withContext(work) {
        engine.share(tripId, myName(), title).also {
            schedule(true)
            // The photos go up now, in the background: the invite is usually sent from another app right away.
            scope.launch(work) { quietly(tripId) }
        }
    }

    suspend fun open(link: ShareLink): TogetherEngine.Opened = withContext(work) {
        engine.open(link, myName()).also {
            schedule(true)
            // Fill in the photos and, with Drive, this phone's own copy right away.
            scope.launch(work) { quietly(link.tripId) }
        }
    }

    suspend fun leave(tripId: String) = withContext(work) {
        engine.leave(tripId)
        if (engine.load().trips.isEmpty()) schedule(false)
        // Out of the trip's mailbox too, without holding up the leaving.
        mail?.let { m -> scope.launch(work) { mailQuietly { m.drop(tripId) } } }
        Unit
    }

    suspend fun syncNow(tripId: String): Boolean = withContext(work) {
        val was = engine.isShared(tripId)
        val title = withContext(bookContext) { book.snapshot.trips.firstOrNull { it.id == tripId }?.title.orEmpty() }
        val changed = engine.sync(tripId, title)
        // This phone's copy into the mailbox once there is one (again when it changes), or out of it when the sync left
        // the trip (deleted here). In the background: a slow Firestore never holds up a sync.
        mail?.let { m ->
            if (engine.isShared(tripId)) scope.launch(work) { mailQuietly { m.post(tripId) } }
            else if (was) scope.launch(work) { mailQuietly { m.drop(tripId) } }
        }
        changed
    }

    /**
     * Every shared trip that hasn't long ended, once (app start, the background run), with a look at its mailbox first;
     * the others sync when opened.
     */
    suspend fun syncAll() = withContext(work) {
        val day = today()
        runCatching { engine.finishLeaving() }.onFailure { if (it is CancellationException) throw it }
        val trips = withContext(bookContext) { book.snapshot.trips }
        val due = engine.load().trips.filter { s -> inBackground(trips.firstOrNull { it.id == s.tripId }, day) }
        // Also after a restore, which brings shared trips back without planning their catch-up.
        schedule(due.isNotEmpty())
        due.forEach { t ->
            mail?.let { m -> mailQuietly { m.look(t.tripId) } }
            quietly(t.tripId)
        }
    }

    private suspend fun quietly(tripId: String) {
        try {
            syncNow(tripId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A sync that didn't finish says so on its trip's sheet (the engine's status); the next one tries again.
        }
    }

    /**
     * Keeps [tripId] in step with the others while its screen is in front: called under the screen's STARTED state, so
     * it stops when the app goes to the background and syncs at once on return. This phone's own changes don't wait for
     * it ([sendSoon]). The trip's mailbox is watched meanwhile, so a friend who joins shows up by themselves.
     */
    suspend fun keepInSync(tripId: String): Unit = coroutineScope {
        if (!engine.isShared(tripId)) return@coroutineScope
        withContext(work) { mail }?.let { m -> launch(work) { m.watch(tripId) } }
        while (true) {
            quietly(tripId)
            delay(OPEN_EVERY_MS)
        }
    }

    private var started = false

    /**
     * From app start on: each change to a shared trip, whichever screen made it, queues its send ([sendSoon]). [writes]
     * goes up after every write to the store (the app's AppCore; the screens' revision in previews and tests). Sends
     * kept from an earlier run go now.
     */
    fun start(writes: Flow<Int>) {
        if (started) return
        started = true
        scope.launch(work) {
            // A backup restored before round 26 left its shared trips in the store's text only: they come back once.
            val restored = withContext(bookContext) { book.sharedTrips }
            if (restored != null && engine.load().isEmpty) runCatching { engine.importState(restored) }
        }
        scope.launch(work) {
            var last: Map<String, String> = emptyMap()
            var first = true
            combine(writes, engine.state.map { st -> st.trips.map { it.tripId } }.distinctUntilChanged()) { _, ids -> ids }.collect { ids ->
                val now = withContext(bookContext) { ids.associateWith { id -> TogetherMerge.fingerprint(rowsOf(id), emptyList()) } }
                // A trip shared just now (or at start) only starts to be watched: its first state isn't a change.
                if (!first) for ((id, fp) in now) {
                    val before = last[id]
                    if (before != null && before != fp) sends.changed(id)
                }
                first = false
                last = now
            }
        }
        // Sends a closed app left waiting go now (WorkManager keeps them through a restart).
        pending().forEach { launchSend(it, wait = 0) }
    }

    private fun rowsOf(tripId: String): TripRows {
        val s = book.snapshot
        return TripRows(
            s.trips.firstOrNull { it.id == tripId }, s.planItems.filter { it.tripId == tripId }, s.entries.filter { it.tripId == tripId },
            s.media.filter { it.tripId == tripId },
        )
    }

    private val sends = SendQueue(::sendSoon)

    /** The trips whose sends wait (kept in [keep]: they outlive the app being closed). */
    private fun pending(): List<String> = keep.read(PENDING)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private val pendingLock = Mutex()

    private suspend fun setPending(tripId: String, on: Boolean) = pendingLock.withLock {
        val now = pending()
        val next = if (on) (now + tripId).distinct() else now - tripId
        if (next != now) keep.write(PENDING, next.joinToString("\n").ifEmpty { null })
    }

    /** Sends running or waiting, by trip; and the trips changed again while their send ran (Android's APPEND). */
    private val sendJobs = HashMap<String, Job>()
    private val again = HashSet<String>()
    private val jobsLock = Mutex()

    /** Goes up when the app is back (a send waiting after a failure tries again at once). */
    private val kicks = MutableStateFlow(0)

    /**
     * Sends [tripId]'s changes in a few seconds, as a job kept on the phone: it survives closing the app (kept in
     * [keep], sent at the next start or in the background), waits for a connection and is tried again until it gets
     * through, each wait twice the last (30 s, 1 min, …, at most 5 h, as WorkManager's exponential backoff).
     */
    private fun sendSoon(tripId: String) {
        scope.launch(work) {
            setPending(tripId, true)
            launchSend(tripId, wait = changeWait)
        }
    }

    private fun launchSend(tripId: String, wait: Long) {
        scope.launch(work) {
            jobsLock.withLock {
                if (sendJobs[tripId]?.isActive == true) {
                    // Appended, never dropped: a change made while a send runs gets its own send after it.
                    again += tripId
                    return@withLock
                }
                sendJobs[tripId] = scope.launch(work) { runSends(tripId, wait) }
            }
        }
    }

    private suspend fun runSends(tripId: String, firstWait: Long) {
        var wait = firstWait
        while (true) {
            if (wait > 0) delay(wait)
            var failures = 0
            while (true) {
                // WorkManager's CONNECTED: no connection, no try.
                while (net?.online() == false) waitOrKick(OFFLINE_CHECK_MS)
                if (send(tripId)) break
                failures++
                waitOrKick(backoff(failures))
            }
            val more = jobsLock.withLock { again.remove(tripId) }
            if (!more) break
            wait = changeWait
        }
        jobsLock.withLock {
            sendJobs.remove(tripId)
            // A change came in just as the last send ended: one more.
            if (again.remove(tripId)) sendJobs[tripId] = scope.launch(work) { runSends(tripId, changeWait) }
        }
        if (jobsLock.withLock { sendJobs[tripId] == null }) setPending(tripId, false)
        endAwayTicketWhenDone()
    }

    /** Waits [ms], or less when the app comes back to the screen. */
    private suspend fun waitOrKick(ms: Long) {
        val seen = kicks.value
        withTimeoutOrNull(ms) { kicks.first { it != seen } }
    }

    /** The queued send of [tripId]. False when it didn't get through: it is tried again later. */
    suspend fun send(tripId: String): Boolean {
        sends.started(tripId)
        return try {
            syncNow(tripId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** The app is back on screen: sends waiting after a failure try again now, and those a closed app left go. */
    fun cameBack() {
        kicks.value++
        endAway()
        scope.launch(work) {
            val running = jobsLock.withLock { sendJobs.filterValues { it.isActive }.keys.toSet() }
            pending().filter { it !in running }.forEach { launchSend(it, wait = 0) }
        }
    }

    private var awayTicket: BackgroundTicket? = null
    private val ticketLock = Mutex()

    /**
     * The app left the screen: sends still waiting get the time iOS gives (UIApplication's background task, about half
     * a minute), and the background turn is asked for soon, so what is left still goes (Android's WorkManager goes on).
     */
    fun wentAway() {
        scope.launch(work) {
            if (pending().isEmpty()) return@launch
            ticketLock.withLock { if (awayTicket == null) awayTicket = background.beginBackgroundWork(SEND_WORK_NAME) }
            plan(nowMs() + SEND_RETRY_AWAY_MS)
            kicks.value++
        }
    }

    private fun endAway() {
        scope.launch(work) { ticketLock.withLock { awayTicket?.endBackgroundWork(); awayTicket = null } }
    }

    private suspend fun endAwayTicketWhenDone() {
        if (pending().isNotEmpty()) return
        ticketLock.withLock { awayTicket?.endBackgroundWork(); awayTicket = null }
    }

    /**
     * The background turn (BGAppRefreshTask): the sends left waiting, then the catch-up of every shared trip that
     * hasn't long ended; the next turn is asked for after it. [lowPower]: Low Power Mode is on (Android's "battery not
     * low"), when only the sends go.
     */
    suspend fun backgroundTurn(lowPower: Boolean = false) {
        planned = null
        for (id in pending()) {
            if (net?.online() == false) break
            if (send(id) && jobsLock.withLock { sendJobs[id]?.isActive != true }) setPending(id, false)
        }
        if (!lowPower && net?.online() != false) syncAll()
        val shared = engine.load().trips.isNotEmpty()
        when {
            pending().isNotEmpty() -> plan(nowMs() + SEND_RETRY_AWAY_MS)
            shared -> plan(nowMs() + EVERY_MS)
            else -> plan(null)
        }
    }

    /** When the background turn is asked for now (this run's knowledge; the phone keeps the request itself). */
    private var planned: Long?
        get() = keep.read(PLANNED)?.toLongOrNull()
        set(value) = keep.write(PLANNED, value?.toString())

    private fun plan(at: Long?) {
        planned = at
        runs.planCatchUp(at)
    }

    /**
     * Plans or cancels the background catch-up (Android's periodic job every 3 h, kept when already planned): the
     * phone's turn from 3 h on, asked for again after each turn.
     */
    fun schedule(on: Boolean) {
        if (!on) {
            if (planned != null) plan(null)
            return
        }
        val at = planned
        if (at == null || at < nowMs()) plan(nowMs() + EVERY_MS)
    }

    companion object {
        const val OPEN_EVERY_MS = 10_000L

        /** A send waits this long, so the changes of one edit (a note and its photos) go up together. */
        const val CHANGE_WAIT_MS = 4_000L

        /** The longest a mailbox call may take before it counts as not done. */
        const val MAIL_WAIT_MS = 10_000L

        /** Long side of the photo copies the other members get. */
        const val SMALL_PX = 1600

        /** How long after its end a shared trip still syncs in the background. */
        const val BACKGROUND_DAYS = 30L

        /** The background catch-up's turn (Android's periodic job). */
        const val EVERY_MS = 3 * 3_600_000L

        /** A send that failed waits 30 s, then twice as long each time (WorkManager's exponential backoff, up to 5 h). */
        const val RETRY_FIRST_MS = 30_000L
        const val RETRY_MAX_MS = 5 * 3_600_000L

        /** While offline, how often a waiting send looks again for a connection. */
        const val OFFLINE_CHECK_MS = 15_000L

        /** Sends left waiting when the app went away: the background turn is asked for this soon. */
        const val SEND_RETRY_AWAY_MS = 15 * 60_000L

        /** The name the phone shows for the time asked to finish the sends (UIApplication's background task). */
        const val SEND_WORK_NAME = "Sending your shared trip changes"

        /** This phone's state of its shared trips, under the app's files (Android: files/together/state.json). */
        const val STATE_FILE = "together/state.json"

        private const val PENDING = "together.sends"
        private const val PLANNED = "together.catchUpAt"

        fun retryWait(failures: Int): Long = minOf(RETRY_FIRST_MS shl (failures - 1).coerceIn(0, 20), RETRY_MAX_MS)

        /**
         * Whether a shared trip syncs at start and in the background: until [BACKGROUND_DAYS] after it ended (a trip
         * without dates always). A trip gone or deleted here goes too, so its sync can finish leaving it.
         */
        fun inBackground(trip: Trip?, today: LocalDate): Boolean =
            trip == null || trip.deletedAt != null || trip.endDate == null || trip.endDate!!.toEpochDay() + BACKGROUND_DAYS >= today.toEpochDay()
    }

    private fun backoff(failures: Int): Long = minOf(retryFirst shl (failures - 1).coerceIn(0, 20), RETRY_MAX_MS)
}

/**
 * Shared trips' rows in this app's trip store (Android's RoomRows): read and written on [context] (the main thread on
 * iPhone, where the screens write), each merge read, worked out and written in one go. [changed]: the screens read again.
 */
internal class BookRows(private val book: TripBook, private val context: CoroutineContext, private val changed: () -> Unit) : TogetherRows {
    override suspend fun trip(tripId: String): Trip? = withContext(context) { book.snapshot.trips.firstOrNull { it.id == tripId } }

    override suspend fun rows(tripId: String): TripRows = withContext(context) { rowsOf(tripId) }

    private fun rowsOf(tripId: String): TripRows {
        val s = book.snapshot
        return TripRows(
            s.trips.firstOrNull { it.id == tripId }, s.planItems.filter { it.tripId == tripId }, s.entries.filter { it.tripId == tripId },
            s.media.filter { it.tripId == tripId },
        )
    }

    override suspend fun merge(tripId: String, ids: Set<String>, compute: (TripRows, Set<String>) -> TogetherPlan): TogetherPlan = withContext(context) {
        val s = book.snapshot
        // Rows of another trip on this phone with one of these ids (Room's idsElsewhere: another trip, not a loose note).
        val elsewhere = HashSet<String>()
        s.planItems.forEach { if (it.id in ids && it.tripId != tripId) elsewhere += it.id }
        s.entries.forEach { val t = it.tripId; if (it.id in ids && t != null && t != tripId) elsewhere += it.id }
        s.media.forEach { val t = it.tripId; if (it.id in ids && t != null && t != tripId) elsewhere += it.id }
        val plan = compute(rowsOf(tripId), elsewhere)
        if (!plan.isEmpty) {
            book.putRows(trips = listOfNotNull(plan.trip), items = plan.planItems, entries = plan.entries, media = plan.media)
            changed()
        }
        plan
    }

    override suspend fun setSharedFile(expected: Media, fileId: String): Boolean = withContext(context) {
        val current = book.snapshot.media.firstOrNull { it.id == expected.id } ?: return@withContext false
        if (current.localPath != expected.localPath || current.sharedFileId != null || current.deletedAt != null) return@withContext false
        // A new stamp, so the other members take the row with its file even if they had it already.
        book.putRows(media = listOf(current.copy(sharedFileId = fileId, updatedAt = Stamps.next(current.updatedAt))))
        changed()
        true
    }

    override suspend fun clearSharedFile(expected: Media, fileId: String): Boolean = withContext(context) {
        val current = book.snapshot.media.firstOrNull { it.id == expected.id } ?: return@withContext false
        if (current.sharedFileId != fileId) return@withContext false
        book.putRows(media = listOf(current.copy(sharedFileId = null)))
        changed()
        true
    }
}

/** The owner's Drive for shared trips: BoringTravel/Shared/<trip>, link-shared (Android's DriveOwn over round 23's REST). */
internal class DriveOwn(private val drive: RestDriveApi?, private val settings: AppSettings) : OwnDrive {
    private fun d(): RestDriveApi = drive ?: throw DriveNotConnectedException()

    override suspend fun connected(): Boolean = drive != null && settings.driveConnected

    override suspend fun sharedFolder(name: String): String {
        val parent = d().intoFolder(SHARED) { it }
        val id = d().newFolder(name, parent)
        d().shareAnyone(id)
        return id
    }

    override suspend fun create(folderId: String, name: String, content: ByteArray): String? {
        val file = goneIsNull { d().createBytes(name, content, ShareCodec.MIME, listOf(folderId)) } ?: return null
        // The folder's link covers what is inside; the file gets its own too, in case Drive is slow to pass it on
        // (and so the others still read "Left" after leaving takes the folder off its link).
        d().shareAnyone(file.id)
        return file.id
    }

    override suspend fun replace(fileId: String, content: ByteArray): Boolean =
        goneIsNull { d().replaceBytes(fileId, content, ShareCodec.MIME) } != null

    override suspend fun upload(folderId: String, file: File, name: String, mime: String): String? =
        goneIsNull { d().uploadFile(file.path, name, mime, listOf(folderId)) }?.id

    override suspend fun delete(fileId: String) = d().delete(fileId)

    override suspend fun unshare(id: String) = d().unshareAnyone(id)

    /** [call]'s result, or null when Drive says the file or folder it names is gone (404). */
    private suspend fun <T> goneIsNull(call: suspend () -> T): T? = try {
        call()
    } catch (e: DriveException) {
        if (e.code == 404) null else throw e
    }

    private companion object {
        val SHARED = DriveApi.ROOT + "Shared"
    }
}

/** The shared trips' state as a small JSON file under the app's files, written beside and moved over (Android's FileStore). */
internal class FileStore(private val files: MediaFiles, private val path: String) : TogetherEngine.StateStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    override suspend fun read(): TogetherState {
        val text = files.read(path)?.decodeToString() ?: return TogetherState()
        return runCatching { json.decodeFromString(TogetherState.serializer(), text) }.getOrElse { TogetherState() }
    }

    override suspend fun write(state: TogetherState) {
        val tmp = "$path.tmp"
        if (!files.write(tmp, json.encodeToString(TogetherState.serializer(), state).encodeToByteArray())) throw IOException("Couldn't keep the shared trips on this phone.")
        if (!files.move(tmp, path)) {
            files.delete(path)
            if (!files.move(tmp, path)) throw IOException("Couldn't keep the shared trips on this phone.")
        }
    }
}
