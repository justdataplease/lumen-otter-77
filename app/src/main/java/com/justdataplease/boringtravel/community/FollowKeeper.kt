package com.justdataplease.boringtravel.community

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The viewer's follows across the screens ([Community.follows]): whether they follow someone (looked up once), the
 * Follow taps (the button changes at once and the server follows, see [FollowBook]), the counts a profile shows (kept a
 * minute), the profiles a list shows (kept a few minutes) and the newest follows that feed the Following feed (read
 * once, then kept up to date by the taps on this phone). Tests drive it with FakeCommunityRepository.
 */
class FollowKeeper(
    private val repo: CommunityRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val waitMs: Long = WAIT_MS,
) {
    private val lock = Any()
    private val book = FollowBook()
    private val counted = HashMap<String, CountedFollows>()
    private val people = HashMap<String, Pair<Profile?, Long>>()
    private var mine: List<FollowLink>? = null
    private val sending = Mutex()

    private val _states = MutableStateFlow<Map<String, FollowState>>(emptyMap())
    /** What the viewer's Follow buttons show, by uid (missing: not known yet). */
    val states: StateFlow<Map<String, FollowState>> = _states.asStateFlow()

    private val _changes = MutableStateFlow(0)
    /** Goes up when a follow changed on this phone, so the Following feed reloads. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun me(): String? = repo.user.value?.uid

    private fun publish() {
        _states.value = synchronized(lock) { book.snapshot() }
    }

    /** Looks up whether the viewer follows [uid], once (again later when the look-up failed). */
    suspend fun seed(uid: String) {
        val me = me() ?: return
        if (uid == me || synchronized(lock) { book.known(uid) }) return
        val following = try {
            repo.isFollowing(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (me() != me) return
        synchronized(lock) { book.seed(uid, following) }
        publish()
    }

    /**
     * The followers and following of [uid]: kept for [Follows.COUNT_TTL_MS], else counted by the server ([fresh]
     * always asks); null when they can't be read (offline).
     */
    suspend fun counts(uid: String, fresh: Boolean = false): CountedFollows? {
        val now = clock()
        if (!fresh) synchronized(lock) { counted[uid] }?.takeIf { now - it.at < Follows.COUNT_TTL_MS }?.let { return it }
        val counts = try {
            repo.followCounts(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        // Whether the viewer followed them as the server counted (a change on its way: not known).
        val then = if (uid == me()) null else _states.value[uid]?.takeIf { !it.pending }?.following
        return CountedFollows(counts, now, then).also { synchronized(lock) { counted[uid] = it } }
    }

    /**
     * The Follow tap on [uid] (whose username is [username]) as [myUsername]: returns the new state at once, or null
     * while a change is on its way. Then the server: offline, Firestore keeps the change and sends it later, which
     * counts as done. Refused, the server's follow is read again (else the old one comes back) with a line to show.
     */
    fun toggle(uid: String, username: String, myUsername: String): FollowState? {
        val me = me() ?: return null
        if (uid == me) return null
        val next = synchronized(lock) { book.toggle(uid) } ?: return null
        publish()
        scope.launch {
            sending.withLock {
                val sent = try {
                    withTimeoutOrNull(waitMs) { if (next.following) repo.follow(uid, username, myUsername) else repo.unfollow(uid) }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: CommunityOfflineException) {
                    true
                } catch (_: Exception) {
                    false
                }
                if (me() != me) return@withLock
                if (sent) {
                    synchronized(lock) {
                        book.confirm(uid)
                        followed(uid, username, next.following)
                    }
                    _changes.update { it + 1 }
                } else {
                    val server = try {
                        repo.isFollowing(uid)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    val problem = if (next.following) "Couldn't follow. Try again." else "Couldn't unfollow. Try again."
                    synchronized(lock) { book.refused(uid, server, problem) }
                }
                publish()
            }
        }
        return next
    }

    /** A follow of [uid] went: the Following feed's people follow, and the viewer's own counts are asked again. */
    private fun followed(uid: String, username: String, following: Boolean) {
        mine = mine?.let { list ->
            val rest = list.filter { it.uid != uid }
            if (following) listOf(FollowLink(uid, username, clock())) + rest else rest
        }
        me()?.let { counted.remove(it) }
    }

    /**
     * The people the viewer follows, newest first: at most [Follows.FEED_CAP] + 1 (so the feed can say the cap was hit),
     * read once and then kept by the taps on this phone; [fresh] reads them again. Throws when they can't be read.
     */
    suspend fun mine(fresh: Boolean = false): List<FollowLink> {
        val me = me() ?: return emptyList()
        if (!fresh) synchronized(lock) { mine }?.let { return it }
        val list = repo.following(me, null, Follows.FEED_CAP + 1).items
        if (me() != me) return emptyList()
        synchronized(lock) {
            mine = list
            list.forEach { book.seed(it.uid, true) }
        }
        publish()
        return list
    }

    /** A profile read elsewhere (a profile page), so lists show it without reading it again. */
    fun saw(profile: Profile) {
        synchronized(lock) { people[profile.uid] = profile to clock() }
    }

    /**
     * The profiles of [uids] for a list, read at the same time and kept a few minutes: a uid maps to null when its
     * profile is gone, and is left out when it couldn't be read (the list then shows the username it stored).
     */
    suspend fun profiles(uids: Collection<String>): Map<String, Profile?> {
        val now = clock()
        val found = HashMap<String, Profile?>()
        val missing = ArrayList<String>()
        synchronized(lock) {
            for (uid in uids.distinct()) {
                val hit = people[uid]
                if (hit != null && now - hit.second < PROFILE_TTL_MS) found[uid] = hit.first else missing += uid
            }
        }
        val read = coroutineScope {
            missing.map { uid ->
                async {
                    try {
                        uid to Result.success(repo.profile(uid))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        uid to Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        synchronized(lock) {
            for ((uid, result) in read) result.onSuccess { p ->
                found[uid] = p
                people[uid] = p to now
            }
        }
        return found
    }

    /** The follows between the viewer and [uid] ended both ways (a block): shown at once, counts asked again. */
    fun ended(uid: String) {
        synchronized(lock) {
            book.ended(uid)
            mine = mine?.filter { it.uid != uid }
            counted.remove(uid)
            me()?.let { counted.remove(it) }
        }
        publish()
        _changes.update { it + 1 }
    }

    /** Forgets what is known about [uid] (a block that didn't go): looked up again next time. */
    fun forget(uid: String) {
        synchronized(lock) {
            book.forget(uid)
            mine = null
            counted.remove(uid)
        }
        publish()
    }

    /** Someone else signed in, or the profile was deleted: nothing known carries over. */
    fun clear() {
        synchronized(lock) {
            book.clear()
            counted.clear()
            people.clear()
            mine = null
        }
        publish()
    }

    companion object {
        /** How long a Follow tap waits for the server before it counts as kept on the phone. */
        const val WAIT_MS = 10_000L

        /** How long a list's profiles are shown again without reading them. */
        const val PROFILE_TTL_MS = 5 * 60_000L
    }
}
