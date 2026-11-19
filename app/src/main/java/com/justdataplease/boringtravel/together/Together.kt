package com.justdataplease.boringtravel.together

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.justdataplease.boringtravel.data.db.BtDatabase
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.Stamps
import com.justdataplease.boringtravel.data.settings.Settings
import com.justdataplease.boringtravel.export.PhotoPrep
import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.google.DriveException
import com.justdataplease.boringtravel.google.PublicDrive
import com.justdataplease.boringtravel.google.RestDriveApi
import com.justdataplease.boringtravel.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Shared trips in the app: [TogetherEngine] over Room, the owner's Drive, other members' links and the photo files.
 * Every change to a shared trip, wherever it is made, queues a send ([sendSoon]) that outlives the app and waits for
 * a connection, so nothing stays on one phone. The others' changes come in every [OPEN_EVERY_MS] while the trip's
 * screen is in front; at start and every few hours in the background for the shared trips that haven't long ended
 * ([inBackground]). Each trip's mailbox ([TripMail], [mail]) tells the members where everyone's copy is: watched while
 * the trip is in front, read once at start, in the background and from the sheets; never polled.
 */
class Together(
    context: Context,
    private val db: BtDatabase,
    private val settings: Settings,
    drive: RestDriveApi,
    links: PublicDrive,
    private val scope: CoroutineScope,
    /** The Google account Drive is connected with, for a name when the traveller isn't signed in. */
    private val driveAccount: () -> String? = { null },
    /** The trips' mailboxes and the Firebase sign-in they need, made on first use; null without Firebase. */
    private val mailbox: () -> MailAccess? = { null },
) {
    private val app = context.applicationContext
    private val prep = PhotoPrep(app)

    val engine = TogetherEngine(
        rows = RoomRows(db),
        own = DriveOwn(drive, settings),
        links = object : LinkReader {
            override suspend fun checksum(fileId: String) = links.checksum(fileId)
            override suspend fun read(fileId: String, maxBytes: Long) = links.bytes(fileId, maxBytes)
            override suspend fun download(fileId: String, target: File, maxBytes: Long) = links.download(fileId, target, maxBytes)
        },
        photos = object : PhotoFiles {
            override fun file(media: Media) = File(app.filesDir, media.localPath)
            override fun smallCopy(media: Media) = prep.scaled(media, SMALL_PX, quality = 84)
        },
        store = FileStore(File(app.filesDir, "together/state.json")),
        clock = { Stamps.now() },
        // Big videos keep to Me › Backup › Only on Wi-Fi, as the backup's own videos do.
        bigFilesWait = {
            settings.wifiOnly.first() && runCatching {
                (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
            }.getOrDefault(false)
        },
    )

    init {
        // Read at once: a trip opened right after Android brought the app back shows as shared and keeps in step,
        // without waiting for the first sync.
        scope.launch(Dispatchers.IO) { runCatching { engine.load() } }
    }

    val state get() = engine.state
    val status get() = engine.status

    private val access: MailAccess? by lazy {
        runCatching { mailbox() }.onFailure { Log.w(TAG, "No mailbox for shared trips", it) }.getOrNull()
    }

    /** The shared trips' mailboxes ([TripMail]); null in a build without Firebase. Nothing needs it to work. */
    /** Members the mailbox added to a trip by themselves (nobody tapped Add here), for a word on screen. */
    data class Joined(val tripId: String, val names: List<String>)

    private val _joined = MutableSharedFlow<Joined>(extraBufferCapacity = 8)
    val joined: SharedFlow<Joined> = _joined.asSharedFlow()

    val mail: TogetherMail? by lazy {
        access?.let { a ->
            TogetherMail(
                engine, a.service, clock = { Stamps.now() }, onAdded = { scope.launch { quietly(it) } },
                onJoined = { tripId, names -> _joined.tryEmit(Joined(tripId, names)) }, log = { Log.i(TAG, it) },
            )
        }
    }

    /** Whether a sheet already tried a sign-in by itself this run ([signInForMail] with onceARun). */
    @Volatile private var triedAlone = false

    /**
     * Signs in to Firebase with the app's Google account, for the trips' mailboxes: [quiet] never shows Google's sheet
     * (only an account already signed in to BoringTravel). [activity]: an Activity context. True when signed in.
     * [onceARun]: a try nobody asked for (a sheet opening), made once per run, as Google's quick "signing in" sheet may show.
     */
    suspend fun signInForMail(activity: Context, quiet: Boolean, onceARun: Boolean = false): Boolean {
        // Firebase starts on first use: off the main thread.
        val a = withContext(Dispatchers.IO) { access } ?: return false
        if (a.service.uid.value != null) return true
        if (onceARun) {
            if (triedAlone) return false
            triedAlone = true
        }
        return try {
            a.signIn(activity, quiet)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Firebase sign-in for the mailbox didn't work: ${e.message}")
            false
        }
    }

    /**
     * The mailbox from a sheet: posts this phone's address for [tripId] now (not waiting after a failure) and, with
     * [read], reads the mailbox once. True when the others pick this phone's copy up by themselves: posted, and another
     * member is in the mailbox (so their app reads it).
     */
    suspend fun checkMail(tripId: String, read: Boolean = true): Boolean = withContext(Dispatchers.IO) {
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
    suspend fun forgetMail() = withContext(Dispatchers.IO) {
        val m = mail ?: return@withContext
        val st = engine.load()
        val ids = (st.trips.map { it.tripId } + st.former.keys + st.unfinished.map { it.tripId }).distinct()
        for (id in ids) {
            withTimeoutOrNull(MAIL_WAIT_MS) { m.drop(id) } ?: throw java.io.IOException("Couldn't reach the shared trips' mailboxes. Try again.")
        }
        st.trips.forEach { engine.mailed(it.tripId, null, 0) }
    }

    /** A mailbox call that never fails what it is part of: null when it failed or took over [MAIL_WAIT_MS]. */
    private suspend fun <T> mailQuietly(call: suspend () -> T): T? = try {
        withTimeoutOrNull(MAIL_WAIT_MS) { call() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i(TAG, "The trip's mailbox didn't answer: ${e.message}")
        null
    }

    /** Goes up when other members' photos arrive (photo tiles load again: MediaImage). */
    val photosVersion get() = engine.photosVersion

    /** The name shown to the other members: the Google account's name, else its email's first part. */
    suspend fun myName(): String {
        val name = settings.accountName.first()?.trim().orEmpty()
        if (name.isNotEmpty()) return ShareCodec.cleanName(name.substringBefore(' ').ifBlank { name })
        return ShareCodec.cleanName((settings.accountEmail.first() ?: driveAccount())?.substringBefore('@').orEmpty())
    }

    suspend fun share(tripId: String, title: String): ShareLink = withContext(Dispatchers.IO) {
        engine.share(tripId, myName(), title).also {
            schedule(true)
            // The photos go up now, in the background: the invite is usually sent from another app right away.
            scope.launch { quietly(tripId) }
        }
    }

    suspend fun open(link: ShareLink): TogetherEngine.Opened = withContext(Dispatchers.IO) {
        engine.open(link, myName()).also {
            schedule(true)
            // Fill in the photos and, with Drive, this phone's own copy right away.
            scope.launch { quietly(link.tripId) }
        }
    }

    suspend fun leave(tripId: String) = withContext(Dispatchers.IO) {
        engine.leave(tripId)
        if (engine.load().trips.isEmpty()) schedule(false)
        // Out of the trip's mailbox too, without holding up the leaving (offline, Firestore keeps the delete for later).
        mail?.let { m -> scope.launch { mailQuietly { m.drop(tripId) } } }
        Unit
    }

    suspend fun syncNow(tripId: String): Boolean = withContext(Dispatchers.IO) {
        val was = engine.isShared(tripId)
        val changed = engine.sync(tripId, db.trips().get(tripId)?.title.orEmpty())
        // This phone's copy into the mailbox once there is one (again when it changes), or out of it when the sync left
        // the trip (deleted here). In the background: a slow Firestore never holds up a sync.
        mail?.let { m ->
            if (engine.isShared(tripId)) scope.launch { mailQuietly { m.post(tripId) } }
            else if (was) scope.launch { mailQuietly { m.drop(tripId) } }
        }
        changed
    }

    /**
     * Every shared trip that hasn't long ended, once (app start, background), with a look at its mailbox first; the
     * others sync when opened.
     */
    suspend fun syncAll() = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        runCatching { engine.finishLeaving() }.onFailure { if (it is CancellationException) throw it }
        val due = engine.load().trips.filter { inBackground(db.trips().get(it.tripId), today) }
        // Also after a restore, which brings shared trips back without scheduling their catch-up.
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
        } catch (e: Exception) {
            Log.i(TAG, "Sync of a shared trip didn't finish: ${e.message}")
        }
    }

    /**
     * Keeps [tripId] in step with the others while its screen is in front: call it under `repeatOnLifecycle(STARTED)`,
     * so it stops when the app goes to the background (the tracking service keeps the process alive) and syncs at once
     * on return. This phone's own changes don't wait for it: [sendSoon]. The trip's mailbox is watched meanwhile (one
     * read per member when the trip opens, then only what changes), so a friend who joins shows up by themselves.
     */
    suspend fun keepInSync(tripId: String): Unit = coroutineScope {
        if (!engine.isShared(tripId)) return@coroutineScope
        // The mailbox builds Firebase the first time: not on the main thread (this is called from the trip's screen).
        withContext(Dispatchers.IO) { mail }?.let { m -> launch { m.watch(tripId) } }
        while (true) {
            quietly(tripId)
            delay(OPEN_EVERY_MS)
        }
    }

    /** From app start on: each change to a shared trip, whichever screen made it, queues its send ([sendSoon]). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch {
            engine.state.map { st -> st.trips.map { it.tripId } }.distinctUntilChanged()
                .flatMapLatest { ids -> merge(*ids.map { id -> changes(id).drop(1).map { id } }.toTypedArray()) }
                .collect { sends.changed(it) }
        }
    }

    /** The trip's rows, each time one of them really changes (Room also reports writes to other trips' rows). */
    private fun changes(tripId: String): Flow<Any> = combine(
        db.trips().observe(tripId), db.planItems().observeForTrip(tripId), db.entries().observeForTrip(tripId), db.media().observeForTrip(tripId),
    ) { a, b, c, d -> listOf(a, b, c, d) }.distinctUntilChanged()

    private val sends = SendQueue(::sendSoon)

    /**
     * Sends [tripId]'s changes in a few seconds, as a job Android keeps: it survives closing the app and a restart,
     * waits for a connection and is tried again until it gets through ([TogetherWorker]).
     */
    private fun sendSoon(tripId: String) {
        val request = OneTimeWorkRequestBuilder<TogetherWorker>()
            .setInputData(workDataOf(TogetherWorker.TRIP_ID to tripId))
            .setInitialDelay(CHANGE_WAIT_MS, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Appended, never dropped: a change made while a send runs gets its own send after it.
        WorkManager.getInstance(app).enqueueUniqueWork(SEND_WORK + tripId, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** The queued send of [tripId]. False when it didn't get through: Android then tries it again later. */
    suspend fun send(tripId: String): Boolean {
        sends.started(tripId)
        return try {
            syncNow(tripId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Sending a shared trip's changes didn't finish: ${e.message}")
            false
        }
    }

    /** Plans or cancels the background catch-up. */
    fun schedule(on: Boolean) {
        val work = WorkManager.getInstance(app)
        if (!on) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<TogetherWorker>(3, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        private const val TAG = "Together"
        const val WORK_NAME = "together-sync"

        /** One queue of sends per trip: this and the trip's id. */
        const val SEND_WORK = "together-send-"
        const val OPEN_EVERY_MS = 10_000L

        /** A send waits this long, so the changes of one edit (a note and its photos) go up together. */
        const val CHANGE_WAIT_MS = 4_000L

        /** The longest a mailbox call may take before it counts as not done (Firestore still sends a write later). */
        const val MAIL_WAIT_MS = 10_000L

        /** Long side of the photo copies the other members get. */
        const val SMALL_PX = 1600

        /** How long after its end a shared trip still syncs in the background. */
        const val BACKGROUND_DAYS = 30L

        /**
         * Whether a shared trip syncs at start and in the background: until [BACKGROUND_DAYS] after it ended (a trip
         * without dates always). A trip gone or deleted here goes too, so its sync can finish leaving it.
         */
        fun inBackground(trip: Trip?, today: LocalDate): Boolean =
            trip == null || trip.deletedAt != null || trip.endDate == null || !trip.endDate!!.plusDays(BACKGROUND_DAYS).isBefore(today)
    }
}

/** Shared trips in the background: one trip's queued send ([Together.sendSoon]), or the catch-up of them all. */
class TogetherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val together = applicationContext.graph.together
        val tripId = inputData.getString(TRIP_ID)
        if (tripId == null) {
            together.syncAll()
            return Result.success()
        }
        return if (together.send(tripId)) Result.success() else Result.retry()
    }

    companion object {
        const val TRIP_ID = "tripId"
    }
}

/** Shared trips' rows in Room. */
internal class RoomRows(private val db: BtDatabase) : TogetherRows {
    override suspend fun trip(tripId: String) = db.trips().get(tripId)

    override suspend fun rows(tripId: String) = TripRows(
        db.trips().get(tripId), db.planItems().forTripAll(tripId), db.entries().forTripAll(tripId), db.media().forTripAll(tripId),
    )

    override suspend fun merge(tripId: String, ids: Set<String>, compute: (TripRows, Set<String>) -> TogetherPlan): TogetherPlan =
        db.withTransaction {
            val elsewhere = HashSet<String>()
            ids.chunked(900).forEach { chunk ->
                elsewhere += db.planItems().idsElsewhere(tripId, chunk)
                elsewhere += db.entries().idsElsewhere(tripId, chunk)
                elsewhere += db.media().idsElsewhere(tripId, chunk)
            }
            val plan = compute(rows(tripId), elsewhere)
            plan.trip?.let { db.trips().upsert(it) }
            if (plan.planItems.isNotEmpty()) db.planItems().upsertAll(plan.planItems)
            if (plan.entries.isNotEmpty()) db.entries().upsertAll(plan.entries)
            if (plan.media.isNotEmpty()) db.media().upsertAll(plan.media)
            plan
        }

    override suspend fun setSharedFile(expected: Media, fileId: String): Boolean = db.withTransaction {
        val current = db.media().get(expected.id) ?: return@withTransaction false
        if (current.localPath != expected.localPath || current.sharedFileId != null || current.deletedAt != null) return@withTransaction false
        // A new stamp, so the other members take the row with its file even if they had it already.
        db.media().upsert(current.copy(sharedFileId = fileId, updatedAt = Stamps.next(current.updatedAt)))
        true
    }

    override suspend fun clearSharedFile(expected: Media, fileId: String): Boolean = db.withTransaction {
        val current = db.media().get(expected.id) ?: return@withTransaction false
        if (current.sharedFileId != fileId) return@withTransaction false
        db.media().upsert(current.copy(sharedFileId = null))
        true
    }
}

/** The owner's Drive for shared trips: BoringTravel/Shared/<trip>, link-shared. */
internal class DriveOwn(private val drive: RestDriveApi, private val settings: Settings) : OwnDrive {
    override suspend fun connected(): Boolean = settings.driveConnected.first()

    override suspend fun sharedFolder(name: String): String {
        val parent = drive.intoFolder(SHARED) { it }
        val id = drive.newFolder(name, parent)
        drive.shareAnyone(id)
        return id
    }

    override suspend fun create(folderId: String, name: String, content: ByteArray): String? {
        val file = goneIsNull { drive.createBytes(name, content, ShareCodec.MIME, listOf(folderId)) } ?: return null
        // The folder's link covers what is inside; the file gets its own too, in case Drive is slow to pass it on
        // (and so the others still read "Left" after leaving takes the folder off its link).
        drive.shareAnyone(file.id)
        return file.id
    }

    override suspend fun replace(fileId: String, content: ByteArray): Boolean =
        goneIsNull { drive.replaceBytes(fileId, content, ShareCodec.MIME) } != null

    override suspend fun upload(folderId: String, file: File, name: String, mime: String): String? =
        goneIsNull { drive.uploadFile(file, name, mime, listOf(folderId)) }?.id

    override suspend fun delete(fileId: String) = drive.delete(fileId)

    override suspend fun unshare(id: String) = drive.unshareAnyone(id)

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

/** The shared trips' state as a small JSON file, replaced whole on each write. */
internal class FileStore(private val file: File) : TogetherEngine.StateStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    override suspend fun read(): TogetherState = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext TogetherState()
        runCatching { json.decodeFromString(TogetherState.serializer(), file.readText()) }.getOrElse { TogetherState() }
    }

    override suspend fun write(state: TogetherState) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(TogetherState.serializer(), state))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
