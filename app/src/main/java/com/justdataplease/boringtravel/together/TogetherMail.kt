package com.justdataplease.boringtravel.together

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * This phone and its shared trips' mailboxes ([TripMail]): it posts where its copy of each trip is, takes the entry out
 * when it leaves, and adds the members it finds there as if their link had been opened ([TogetherEngine.add]). Plain
 * Kotlin over [MailService], so conversations between phones run in unit tests.
 *
 * Nothing depends on it: without Firebase, signed out, or with Firestore failing, shared trips work as before through
 * the links. So a failed post is not tried again for [RETRY_MS] unless asked ([post] with force), and a failed read
 * just waits for the next one.
 */
class TogetherMail(
    private val engine: TogetherEngine,
    private val service: MailService,
    private val clock: () -> Long,
    /** Someone was added to the trip: sync it, so their rows come in and this phone's file lists them. */
    private val onAdded: suspend (tripId: String) -> Unit = {},
    /** Who was added, by name, for a word on screen: nobody tapped Add on this phone. */
    private val onJoined: (tripId: String, names: List<String>) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
) {
    private val posting = Mutex()
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** [TripMail.key]s whose file was read and wasn't that member's copy of the trip: not read again while the app runs. */
    private val refused: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Trips whose mailbox is being watched now ([watch]). */
    private val watching: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _seen = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Per trip, the member ids last read in its mailbox ([TripMail.offerLink]). */
    val seen: StateFlow<Map<String, Set<String>>> = _seen.asStateFlow()

    val signedIn: Boolean get() = service.uid.value != null

    fun watching(tripId: String): Boolean = tripId in watching

    /**
     * Posts this phone's address for [tripId] when it has a copy that isn't in the mailbox yet (or not as this Firebase
     * user). True when the mailbox has it. After a failure, waits [RETRY_MS] before trying again unless [force].
     */
    suspend fun post(tripId: String, force: Boolean = false): Boolean = posting.withLock {
        val box = TripMail.box(tripId) ?: return@withLock false
        val s = engine.load().of(tripId) ?: return@withLock false
        val uid = service.uid.value ?: return@withLock false
        val fileId = s.fileId ?: return@withLock false
        val done = TripMail.posted(uid, fileId)
        if (s.mailed == done) return@withLock true
        val failed = failedAt[tripId]
        if (!force && failed != null && clock() - failed < RETRY_MS) return@withLock false
        try {
            service.post(box, MailEntry(uid, s.me, fileId))
        } catch (e: CancellationException) {
            // Also a timeout around this call: Firestore still sends the write once it can, but it isn't known to be in.
            failedAt[tripId] = clock()
            throw e
        } catch (e: Exception) {
            failedAt[tripId] = clock()
            throw e
        }
        failedAt.remove(tripId)
        engine.mailed(tripId, done, clock())
        true
    }

    /** Reads [tripId]'s mailbox once and takes what it says ([take]); null when there is none to read (not signed in). */
    suspend fun look(tripId: String): List<MailEntry>? {
        val box = TripMail.box(tripId) ?: return null
        if (service.uid.value == null || !engine.isShared(tripId)) return null
        return service.read(box).also { take(tripId, it) }
    }

    /**
     * Watches [tripId]'s mailbox while collected (the trip in front): each change is taken ([take]). Follows the
     * Firebase sign-in; a mailbox that fails (offline too long, or the rules refuse it) is left until the next sign-in
     * or the next time the trip opens.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun watch(tripId: String) {
        val box = TripMail.box(tripId) ?: return
        watching += tripId
        try {
            service.uid.flatMapLatest { uid ->
                if (uid == null) emptyFlow() else service.watch(box).catch { log("The trip's mailbox stopped: ${it.message}") }
            }.collect { take(tripId, it) }
        } finally {
            watching -= tripId
        }
    }

    /**
     * Adds the members [entries] bring to [tripId] ([TripMail.newcomers]), each after reading their copy; returns their
     * names. An entry whose copy isn't theirs is remembered and left alone; one that couldn't be read (no connection)
     * is tried again with the next read.
     */
    suspend fun take(tripId: String, entries: List<MailEntry>): List<String> {
        _seen.update { it + (tripId to entries.mapTo(HashSet()) { e -> e.member }) }
        val s = engine.load().of(tripId) ?: return emptyList()
        val added = ArrayList<String>()
        for (e in TripMail.newcomers(s, entries, refused)) {
            try {
                val opened = engine.add(ShareLink(tripId, e.fileId, e.member))
                if (opened is TogetherEngine.Opened.Added) added += opened.name
            } catch (x: CancellationException) {
                throw x
            } catch (x: TogetherException) {
                // Not their copy of this trip, they left, or the trip is full.
                refused += TripMail.key(tripId, e)
            } catch (x: ShareFormatException) {
                refused += TripMail.key(tripId, e)
            } catch (x: Exception) {
                log("A member's copy from the mailbox couldn't be read: ${x.message}")
            }
        }
        if (added.isNotEmpty()) {
            onJoined(tripId, added)
            onAdded(tripId)
        }
        return added
    }

    /** Takes this phone's entry out of [tripId]'s mailbox (it left the trip). */
    suspend fun drop(tripId: String) {
        val box = TripMail.box(tripId) ?: return
        if (service.uid.value == null) return
        service.remove(box)
        _seen.update { it - tripId }
    }

    companion object {
        /** A post that failed waits this long before the syncs try it again (the sheets don't wait). */
        const val RETRY_MS = 10 * 60_000L
    }
}
