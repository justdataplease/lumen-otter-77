package com.justdataplease.boringtravel.community

import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.compat.File
import com.justdataplease.boringtravel.compat.synchronized
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The community in the app, the Android app's community/Community.kt written again (round 24): who is signed in
 * ([auth]: Firebase Auth over REST), their profile, the hearts across screens, the people they follow ([follows]) and
 * blocked, deleting, and the pictures and clips of other people's posts ([pictures]: read from their Drive by link,
 * never with the viewer's own Drive token). The rules every screen follows are the Android app's own files, read in
 * (Leaderboard's LikeBook, FollowKeeper, Follows); the repository ([repo]) is Firestore's REST API
 * ([FirestoreCommunity]) where Android uses the SDK.
 *
 * Without Firebase ids in the build [available] is false and the Community tab says so. [scope] is the app's own (the
 * hearts and follows go on after a screen closes); [driveConnected] and [drive] are the app's Drive, where a post's
 * folder is deleted from (round 25 posts).
 */
class Community(
    val repo: CommunityRepository,
    private val auth: CommunityAuth?,
    val pictures: CommunityPictures?,
    private val scope: CoroutineScope,
    private val driveConnected: () -> Boolean = { false },
    /** Deletes a file or folder in the traveller's own Drive (the app's Drive client). */
    private val deleteDriveFile: (suspend (String) -> Unit)? = null,
) {
    /** Android's debug builds can use the Firebase emulators; this app's tests use in-memory fakes instead. */
    val usesEmulator: Boolean get() = false

    /** False when this build has no Firebase project: the community can't be used. */
    val available: Boolean get() = auth?.configured == true

    val user: StateFlow<CommunityUser?> get() = repo.user

    // ---------------------------------------------------------------- my profile and blocks

    private val _profile = MutableStateFlow<Profile?>(null)
    /** The signed-in traveller's profile (null: none yet, or not signed in). */
    val profile: StateFlow<Profile?> = _profile.asStateFlow()
    private val _profileKnown = MutableStateFlow(false)
    /** True once [profile] was looked up for the user signed in now. */
    val profileKnown: StateFlow<Boolean> = _profileKnown.asStateFlow()
    private val _profileFailed = MutableStateFlow(false)
    /** True when the last look-up of [profile] failed (offline) and it isn't known yet: screens offer Try again. */
    val profileFailed: StateFlow<Boolean> = _profileFailed.asStateFlow()

    private val _blocked = MutableStateFlow<List<BlockedUser>>(emptyList())
    val blocked: StateFlow<List<BlockedUser>> = _blocked.asStateFlow()

    /** Goes up after a post, a delete or a block, so lists reload. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()
    fun changed() {
        _changes.value++
    }

    /** The viewer's follows: Follow buttons, counts, lists and the Following feed's people (see [FollowKeeper]). */
    val follows: FollowKeeper by lazy { FollowKeeper(repo, scope) }

    // Declared before init: its coroutine clears them, and may run before the rest of the class is set up.
    private val likeBook = LikeBook()
    private val _likes = MutableStateFlow<Map<String, LikeState>>(emptyMap())

    init {
        if (available) scope.launch {
            repo.user.map { it?.uid }.distinctUntilChanged().collect { uid ->
                _profileKnown.value = false
                _profileFailed.value = false
                _profile.value = null
                _blocked.value = emptyList()
                synchronized(likeBook) { likeBook.clear() }
                _likes.value = emptyMap()
                follows.clear()
                if (uid != null) {
                    // Writes made without a connection on an earlier run go first.
                    flush()
                    refreshMe()
                }
            }
        }
    }

    /** Reads the signed-in traveller's profile and blocks again; a failed look-up leaves the profile not known. */
    suspend fun refreshMe() {
        val uid = repo.user.value?.uid ?: return
        _profileFailed.value = false
        try {
            val p = repo.profile(uid)
            if (repo.user.value?.uid == uid) setProfile(p)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (repo.user.value?.uid == uid && !_profileKnown.value) _profileFailed.value = true
        }
        try {
            val b = repo.blocked()
            if (repo.user.value?.uid == uid) _blocked.value = b
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The blocks stay as they are; the next refresh reads them again.
        }
    }

    fun setProfile(p: Profile?) {
        _profile.value = p
        _profileKnown.value = true
        _profileFailed.value = false
    }

    // ---------------------------------------------------------------- signing in

    /**
     * Signs in to the community with the app's account: quietly when the phone is already signed in with Google or
     * Apple, else with Google's page. Returns null when signed in, else what to tell the traveller ("" = nothing to say:
     * cancelled, or [quiet] and no quiet way in). [quiet] never shows a page.
     */
    suspend fun signIn(quiet: Boolean = false): String? {
        val a = auth ?: return CommunityAuth.UNAVAILABLE
        return a.signIn(quiet)
    }

    /** Sign in with Apple, then the community (Apple 4.8: beside Google on the community's sign-in card). */
    suspend fun signInWithApple(): String? {
        val a = auth ?: return CommunityAuth.UNAVAILABLE
        return a.signInWithApple()
    }

    /** Whether Sign in with Apple is offered in this build (its capability and Firebase's Apple provider). */
    val appleOffered: Boolean get() = available && appleOn()

    private var appleOn: () -> Boolean = { false }

    /** Tells the community whether Sign in with Apple is set up (the app's account knows). */
    fun offerApple(on: () -> Boolean) {
        appleOn = on
    }

    /** Signs out of the community on this phone (the app's sign-out does this too); what it kept of the community goes. */
    suspend fun signOut() {
        auth?.signOut()
        (repo as? FirestoreCommunity)?.forgetCache()
    }

    // ---------------------------------------------------------------- hearts

    /** Every heart known on this phone, by post id. */
    val likes: StateFlow<Map<String, LikeState>> = _likes.asStateFlow()
    private val likeLock = Mutex()

    private fun publishLikes() {
        _likes.value = synchronized(likeBook) { likeBook.snapshot() }
    }

    /**
     * Folds fresh posts into the hearts, and looks up which of them the viewer liked: once per post, and again later
     * for any whose look-up failed (offline), so a failure never sticks as "not liked".
     */
    suspend fun seedLikes(posts: List<Post>) {
        if (posts.isEmpty()) return
        val unknown = synchronized(likeBook) { posts.map { it.id }.filter { !likeBook.known(it) } }
        val mine = if (unknown.isEmpty()) {
            emptyMap()
        } else {
            try {
                repo.liked(unknown)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
        }
        synchronized(likeBook) { posts.forEach { p -> likeBook.seed(p, mine[p.id]) } }
        publishLikes()
    }

    fun likeOf(post: Post): LikeState = _likes.value[post.id] ?: LikeState(false, post.likes)

    /**
     * The heart tap: changes at once, then tells the server. Without a connection the change waits on the phone and goes
     * later ([FirestoreCommunity]'s outbox, as the SDK's pending writes), which counts as done. A refusal usually means
     * the heart was wrong (tapped before the look-up came back, or changed on another phone): the server's like and
     * count are read again and shown.
     */
    fun toggleLike(post: Post): LikeState? {
        if (repo.user.value?.uid == post.uid) return null
        val next = synchronized(likeBook) { likeBook.toggle(post.id, likeOf(post)) } ?: return null
        publishLikes()
        scope.launch {
            likeLock.withLock {
                val sent = try {
                    sendOrQueue(LIKE_WAIT_MS) { repo.setLiked(post.id, next.liked) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (sent != null) {
                    synchronized(likeBook) { likeBook.confirm(post.id, onServer = sent) }
                } else {
                    val server = serverLike(post.id)
                    synchronized(likeBook) { if (server != null) likeBook.adopt(post.id, server) else likeBook.fail(post.id) }
                }
                publishLikes()
            }
        }
        return next
    }

    /** The viewer's like on [postId] and its count as the server has them now; null when they can't be read. */
    private suspend fun serverLike(postId: String): LikeState? = try {
        val post = repo.post(postId)
        val liked = repo.liked(listOf(postId))[postId]
        if (post == null || liked == null) null else LikeState(liked, post.likes)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * A write that waits on the phone without a connection and goes later: true when the server took it, false when it
     * still waits (offline or slow) after [waitMs]. Throws when the server refused it.
     */
    private suspend fun sendOrQueue(waitMs: Long = WRITE_WAIT_MS, write: suspend () -> Unit): Boolean = try {
        withTimeoutOrNull(waitMs) { write() } != null
    } catch (_: CommunityOfflineException) {
        false
    }

    // ---------------------------------------------------------------- follows

    /**
     * The Follow tap on [uid] (whose username is [username] now). Null when it can't be made: no profile of the
     * viewer's own yet (the caller offers to make one; others see the username on their lists), or a change on its way.
     */
    fun toggleFollow(uid: String, username: String): FollowState? {
        val me = _profile.value ?: return null
        return follows.toggle(uid, username, me.username)
    }

    // ---------------------------------------------------------------- blocks, reports

    /**
     * Blocks [uid]: their posts and profile are hidden at once, and the follows between the two end. True when the
     * server has it, false while it waits for a connection (it goes then). Throws, and shows them again, when the
     * server refused it.
     */
    suspend fun block(uid: String, username: String): Boolean {
        val user = BlockedUser(uid, username, nowMs())
        val was = _blocked.value.firstOrNull { it.uid == uid }
        _blocked.value = listOf(user) + _blocked.value.filter { it.uid != uid }
        follows.ended(uid)
        changed()
        return try {
            sendOrQueue { repo.block(user) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (was == null) _blocked.value = _blocked.value.filter { it.uid != uid }
            follows.forget(uid)
            changed()
            throw e
        }
    }

    /** Unblocks [uid] at once; true when the server has it, false while it waits for a connection. */
    suspend fun unblock(uid: String): Boolean {
        val was = _blocked.value.firstOrNull { it.uid == uid }
        _blocked.value = _blocked.value.filter { it.uid != uid }
        changed()
        return try {
            sendOrQueue { repo.unblock(uid) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (was != null) _blocked.value = listOf(was) + _blocked.value.filter { it.uid != uid }
            changed()
            throw e
        }
    }

    /** Reports post [postId] (null: the profile) of [target]. True when sent, false while it waits for a connection. */
    suspend fun report(postId: String?, target: String, reason: ReportReason): Boolean =
        sendOrQueue { repo.report(postId, target, reason) }

    /**
     * Sends the writes that waited for a connection (on every return to the app, a pull to refresh, a sign-in), as the
     * SDK sends its pending writes once online.
     */
    suspend fun flush() {
        val r = repo as? FirestoreCommunity ?: return
        try {
            if (r.waiting()) r.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // They wait for the next time.
        }
    }

    // ---------------------------------------------------------------- deleting

    /** The poster's Drive is needed to post (pictures and clips live there). */
    suspend fun driveReady(): Boolean = driveConnected()

    /**
     * Deletes the viewer's own [post] and then its Drive folder. Returns false when the folder could not be deleted
     * from this phone (Drive not connected): the pictures stay in BoringTravel/Community in their Drive.
     */
    suspend fun deletePost(post: Post): Boolean {
        repo.deletePost(post)
        synchronized(likeBook) { likeBook.forget(post.id) }
        publishLikes()
        changed()
        val folder = post.folderId ?: return true
        return deleteFolder(folder)
    }

    private suspend fun deleteFolder(id: String): Boolean {
        val delete = deleteDriveFile ?: return false
        if (!driveReady()) return false
        return try {
            delete(id)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Deletes the profile: their posts (with their Drive folders when Drive is connected), the likes they gave, their
     * blocks, their follows both ways, the profile and the username. Returns false when some Drive folders stayed.
     */
    suspend fun deleteProfile(): Boolean {
        val folders = repo.deleteAll()
        setProfile(null)
        _blocked.value = emptyList()
        follows.clear()
        changed()
        var all = true
        for (f in folders) if (!deleteFolder(f)) all = false
        return all
    }

    /**
     * Everything of theirs in the community, for Me › Delete account (Android's deleteAccount's first half): without a
     * community session on this phone it signs in quietly with the app's account, so posts made before are found and
     * deleted. No quiet way in (no account on the phone): nothing to delete here. Throws when it couldn't.
     */
    suspend fun deleteData() {
        val a = auth ?: return
        if (!available) return
        if (a.user.value == null) {
            val r = a.signIn(quiet = true)
            if (!r.isNullOrEmpty()) throw IllegalStateException(r)
        }
        if (a.user.value == null) return
        deleteProfile()
    }

    /**
     * Me › Delete account with a Google account: the community's data ([deleteData]), then the Firebase account itself.
     * Throws when the data or the account could not be deleted: the account then stays signed in, so they can try again.
     * (A Sign in with Apple account's Firebase account goes with Apple's own deletion, round 23.)
     */
    suspend fun deleteAccount() {
        val a = auth ?: return
        deleteData()
        if (a.user.value == null) return
        if (a.isAppleAccount) {
            signOut()
            return
        }
        try {
            a.deleteAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Couldn't delete your community account. Try again.", e)
        }
        signOut()
    }

    // ---------------------------------------------------------------- pictures and clips of posts

    /** Where picture [fileId] is read from and the headers to send (the app's Drive API key, or the public address). */
    fun pictureAddress(fileId: String): Pair<String, Map<String, String>> =
        pictures?.address(fileId) ?: throw CommunityUnavailableException()

    /** Clip [fileId] downloaded to the cache (once), to play. */
    suspend fun clipFile(fileId: String): File {
        val p = pictures ?: throw CommunityUnavailableException()
        return File(p.absolute(p.clip(fileId)))
    }

    companion object {
        private const val LIKE_WAIT_MS = 10_000L

        /** How long a report or a block waits for the server before saying it goes out once online. */
        private const val WRITE_WAIT_MS = 8_000L

        /**
         * The app's community on the phone's parts: the network [net], this build's Firebase ids [config] (incomplete: the
         * community isn't available), where the account is kept [keys] (the Keychain), the app's account [google], the
         * caches folder [cache] (the lists, documents, pictures and clips kept), the app's settings file [settings] (the
         * writes waiting for a connection), the app's own [scope], the bundle id sent with keyed requests, the build's
         * Drive API key [driveKey], App Check's token, and the app's Drive (connected, and deleting a post's folder).
         */
        fun make(
            net: com.justdataplease.boringtravel.ios.Net,
            config: com.justdataplease.boringtravel.firebase.FirebaseSetup.Config,
            keys: com.justdataplease.boringtravel.ai.own.KeyValues,
            google: com.justdataplease.boringtravel.google.GoogleAccount?,
            cache: com.justdataplease.boringtravel.ios.MediaFiles,
            settings: com.justdataplease.boringtravel.ios.SettingsFile,
            scope: CoroutineScope,
            bundleId: String? = null,
            driveKey: String = "",
            appCheck: com.justdataplease.boringtravel.ai.AppCheckTokens = com.justdataplease.boringtravel.ai.AppCheckTokens { null },
            driveConnected: () -> Boolean = { false },
            deleteDriveFile: (suspend (String) -> Unit)? = null,
            pictureWait: Long = 1_000,
        ): Community {
            val auth = CommunityAuth(net, config, keys, google, bundleId)
            val pictures = CommunityPictures(com.justdataplease.boringtravel.google.PublicDrive(net, driveKey, bundleId, retryWait = pictureWait), cache)
            if (!auth.configured) return Community(UnavailableCommunity, auth, pictures, scope)
            val fs = com.justdataplease.boringtravel.firebase.FirestoreRest(net, config.projectId, { fresh -> auth.token(fresh) }, appCheck)
            val repo = FirestoreCommunity(fs, auth, CommunityCache(cache), CommunityOutbox(settings))
            return Community(repo, auth, pictures, scope, driveConnected, deleteDriveFile).also { c ->
                c.offerApple { google?.appleConfigured == true }
            }
        }
    }
}

/** The community in a build without Firebase: nobody is signed in and every call says it isn't available. */
object UnavailableCommunity : CommunityRepository {
    override val user: StateFlow<CommunityUser?> = MutableStateFlow(null)
    private fun no(): Nothing = throw CommunityUnavailableException()
    override suspend fun profile(uid: String): Profile? = no()
    override suspend fun usernameOwner(username: String): String? = no()
    override suspend fun saveProfile(username: String, name: String, bio: String, avatar: String?): Profile = no()
    override suspend fun latest(after: Any?, limit: Int): Page<Post> = no()
    override suspend fun top(range: TopRange, month: String, after: Any?, limit: Int): Page<Post> = no()
    override suspend fun postsBy(uid: String, limit: Int): List<Post> = no()
    override suspend fun post(id: String): Post? = no()
    override suspend fun trip(postId: String): String? = no()
    override fun newPostId(): String = no()
    override suspend fun createPost(post: Post, tripJson: String?): Post = no()
    override suspend fun updatePost(post: Post, tripJson: String?): Post = no()
    override suspend fun deletePost(post: Post) = no()
    override suspend fun liked(postIds: Collection<String>): Map<String, Boolean> = emptyMap()
    override suspend fun setLiked(postId: String, liked: Boolean) = no()
    override suspend fun report(postId: String?, target: String, reason: ReportReason) = no()
    override suspend fun blocked(): List<BlockedUser> = emptyList()
    override suspend fun block(user: BlockedUser) = no()
    override suspend fun unblock(uid: String) = no()
    override suspend fun isFollowing(uid: String): Boolean = no()
    override suspend fun follow(uid: String, username: String, myUsername: String) = no()
    override suspend fun unfollow(uid: String) = no()
    override suspend fun followCounts(uid: String): FollowCounts = no()
    override suspend fun followers(uid: String, after: Any?, limit: Int): Page<FollowLink> = no()
    override suspend fun following(uid: String, after: Any?, limit: Int): Page<FollowLink> = no()
    override suspend fun postsOf(uids: List<String>, after: Any?, limit: Int): Page<Post> = no()
    override suspend fun deleteAll(): List<String> = no()
}

/** The app's community for every screen; null where a screen is drawn on its own. */
val LocalCommunity = staticCompositionLocalOf<Community?> { null }
