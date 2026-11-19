package com.justdataplease.boringtravel.community

import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.BuildConfig
import com.justdataplease.boringtravel.firebase.FirebaseSetup
import com.justdataplease.boringtravel.google.SignInOutcome
import com.justdataplease.boringtravel.google.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * The community in the app ([AppGraph.community]): who is signed in, their profile, the hearts across screens, the
 * people they follow ([follows]) and blocked, posting (pictures to their Drive, words to Firestore) and deleting, and the pictures of other
 * people's posts (read from their Drive with PublicDrive, never with the viewer's own Drive token).
 *
 * Sign-in is the app's Google account handed to Firebase Auth, only when the community is used (the Community tab's
 * quiet sign-in, or Sign in on its screens): signing in to Google for Drive alone makes no Firebase account. Without
 * Firebase ids in the build [available] is false and the Community tab says so. Debug builds with `community.emulatorHost` use the Firebase emulators instead
 * (README in android-boringtravel/firebase), with a made-up account and pictures from a local folder.
 */
class Community(private val graph: AppGraph) {
    private val app: Context = graph.app

    /** Debug builds only: the host running the Auth and Firestore emulators (and the pictures), or "" (off). */
    private val emulatorHost: String = if (BuildConfig.DEBUG) BuildConfig.COMMUNITY_EMULATOR_HOST.trim() else ""
    val usesEmulator: Boolean get() = emulatorHost.isNotEmpty()

    private val firebase: FirebaseApp? by lazy {
        if (usesEmulator) emulatorApp() else FirebaseSetup.app(app)
    }

    /**
     * False when this build has no Firebase project: the community can't be used. Asked from the main thread (Me),
     * so it only reads the build's ids; Firebase itself starts with the first use of [repo], off the main thread
     * (see init).
     */
    val available: Boolean get() = usesEmulator || FirebaseSetup.isConfigured

    private val auth: FirebaseAuth? by lazy {
        firebase?.let { f -> FirebaseAuth.getInstance(f).also { if (usesEmulator) it.useEmulator(emulatorHost, AUTH_PORT) } }
    }

    val repo: CommunityRepository by lazy {
        val f = firebase
        val a = auth
        if (f == null || a == null) {
            UnavailableCommunity
        } else {
            val db = FirebaseFirestore.getInstance(f)
            if (usesEmulator) db.useEmulator(emulatorHost, FIRESTORE_PORT)
            FirestoreCommunity(db, a)
        }
    }

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
    fun changed() { _changes.value++ }

    /** The viewer's follows: Follow buttons, counts, lists and the Following feed's people (see [FollowKeeper]). */
    val follows: FollowKeeper by lazy { FollowKeeper(repo, graph.scope) }

    // Declared before init: its coroutine clears them, and may run before the rest of the class is set up.
    private val likeBook = LikeBook()
    private val _likes = MutableStateFlow<Map<String, LikeState>>(emptyMap())

    init {
        if (available) graph.scope.launch {
            repo.user.map { it?.uid }.distinctUntilChanged().collect { uid ->
                _profileKnown.value = false
                _profileFailed.value = false
                _profile.value = null
                _blocked.value = emptyList()
                synchronized(likeBook) { likeBook.clear() }
                _likes.value = emptyMap()
                follows.clear()
                if (uid != null) refreshMe()
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
        } catch (e: Exception) {
            Log.w(TAG, "Profile look-up failed", e)
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
     * Signs in to the community with the Google account: quietly when the phone already signed in with it, else with
     * Google's sheet over [activity]. Returns null when signed in, else what to tell the traveller ("" = nothing to
     * say: cancelled, or [quiet] and no quiet way in). [quiet] never shows Google's sheet.
     */
    suspend fun signIn(activity: Context, quiet: Boolean = false): String? {
        val a = auth ?: return "The community isn't available in this build."
        if (a.currentUser != null) return null
        if (usesEmulator) return if (quiet) "" else emulatorSignIn(a)
        val silent = if (graph.settings.accountEmail.first() != null) graph.google.freshIdToken(activity) else null
        if (silent == null && quiet) return ""
        val token = silent ?: when (val o = graph.google.signIn(activity)) {
            is SignInOutcome.SignedIn -> o.idToken
            SignInOutcome.Cancelled -> return ""
            SignInOutcome.NotSetUp -> return "Google sign-in isn't set up in this build."
            is SignInOutcome.Failed -> return o.message
        }
        return signInWith(token)
    }

    private suspend fun signInWith(idToken: String): String? = try {
        auth!!.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Firebase sign-in failed", e)
        "Couldn't sign in to the community. Try again."
    }

    /** The emulator's Auth takes a made-up Google account (debug builds with the emulator only). */
    private suspend fun emulatorSignIn(a: FirebaseAuth): String? {
        val email = graph.settings.accountEmail.first() ?: "traveller@example.com"
        val name = graph.settings.accountName.first() ?: "Traveller"
        val sub = "emu-" + email.lowercase().filter { it.isLetterOrDigit() }.take(20)
        val fake = """{"sub":"$sub","email":"$email","email_verified":true,"name":"$name"}"""
        return try {
            a.signInWithCredential(GoogleAuthProvider.getCredential(fake, null)).await()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Emulator sign-in failed", e)
            "The community emulator didn't answer on $emulatorHost."
        }
    }

    /** Signs out of the community on this phone (the app's Google sign-out does this too). */
    fun signOut() {
        runCatching { auth?.signOut() }
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
     * The heart tap: changes at once, then tells the server. Firestore keeps the change while offline and sends it
     * later, so that counts as done. A refusal usually means the heart was wrong (tapped before the look-up came
     * back, or changed on another phone): the server's like and count are read again and shown.
     */
    fun toggleLike(post: Post): LikeState? {
        if (repo.user.value?.uid == post.uid) return null
        val next = synchronized(likeBook) { likeBook.toggle(post.id, likeOf(post)) } ?: return null
        publishLikes()
        graph.scope.launch {
            likeLock.withLock {
                val sent = try {
                    sendOrQueue(LIKE_WAIT_MS) { repo.setLiked(post.id, next.liked) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Like refused", e)
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
     * A write Firestore keeps on the phone while offline and sends later: true when the server took it, false when it
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
        val user = BlockedUser(uid, username, System.currentTimeMillis())
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

    // ---------------------------------------------------------------- posting and deleting

    /** The poster's Drive is needed to post (pictures and clips live there). */
    suspend fun driveReady(): Boolean = usesEmulator || graph.settings.driveConnected.first()

    /** Where a post's pictures go: the poster's Drive, or the emulators' picture server in a debug build on them. */
    private fun store(): PostStore =
        if (usesEmulator) EmulatorPostStore(graph.google.http, "http://$emulatorHost:$PICTURE_PORT") else DrivePostStore(graph.google.drive)

    /** Posts [plan]; [state] keeps where it got to, so a retry carries on. */
    suspend fun post(plan: PostPlan, state: UploadState, onProgress: (UploadProgress) -> Unit): Post {
        val uploader = PostUploader(store(), PostPhotos(app, graph.media) { plan.clip }, repo)
        val post = uploader.run(plan, state, onProgress = onProgress)
        changed()
        return post
    }

    /**
     * Sends [plan] to its post again (a trip post kept in step with its trip, [PostSync]): pictures not in [state] yet
     * go to the post's folder (made when it had none), then the post and its trip are updated.
     */
    suspend fun updatePost(plan: PostPlan, state: UploadState, onProgress: (UploadProgress) -> Unit): Post {
        val uploader = PostUploader(store(), PostPhotos(app, graph.media) { null }, repo)
        val post = uploader.run(plan, state, update = true, onProgress = onProgress)
        changed()
        return post
    }

    /** Deletes pictures a post no longer shows from its Drive folder (best effort). */
    suspend fun dropFiles(ids: Collection<String>) {
        if (ids.isEmpty() || !driveReady()) return
        val s = store()
        for (id in ids) runCatching { s.delete(id) }
    }

    /** Gives up on an unfinished post: its Drive folder goes. */
    suspend fun discard(state: UploadState) {
        PostUploader(store(), PostPhotos(app, graph.media), repo).discard(state)
    }

    /**
     * Deletes the viewer's own [post] and then its Drive folder. Returns false when the folder could not be deleted
     * from this phone (Drive not connected): the pictures stay in BoringTravel/Community in their Drive.
     */
    suspend fun deletePost(post: Post): Boolean {
        repo.deletePost(post)
        // A trip kept in step with this post isn't any more.
        graph.postSync.links.removePost(post.id)
        synchronized(likeBook) { likeBook.forget(post.id) }
        publishLikes()
        changed()
        val folder = post.folderId ?: return true
        return deleteFolder(folder)
    }

    private suspend fun deleteFolder(id: String): Boolean =
        driveReady() && runCatching { store().delete(id) }.isSuccess

    /**
     * Deletes the profile: their posts (with their Drive folders when Drive is connected), the likes they gave, their
     * blocks, their follows both ways, the profile and the username. Returns false when some Drive folders stayed.
     */
    suspend fun deleteProfile(): Boolean {
        val folders = repo.deleteAll()
        graph.postSync.links.clear()
        setProfile(null)
        _blocked.value = emptyList()
        follows.clear()
        changed()
        var all = true
        for (f in folders) if (!deleteFolder(f)) all = false
        return all
    }

    /**
     * Me › Delete account: everything of theirs in the community, then the Firebase account itself. Without a
     * community session on this phone (a reinstall, or Community never opened since signing in) it signs in quietly
     * with the same Google account, so posts made before are found and deleted; the Firebase account that sign-in may
     * create is deleted with the rest. No quiet way in (no Google account on the phone): nothing to delete here.
     * Throws when the data or the account could not be deleted: the account then stays signed in, so they can try again.
     */
    suspend fun deleteAccount(activity: Context) {
        val a = auth ?: return
        if (a.currentUser == null && !usesEmulator && graph.settings.accountEmail.first() != null) {
            val token = graph.google.freshIdToken(activity) ?: return
            signInWith(token)?.let { throw IllegalStateException(it) }
        }
        val user = a.currentUser ?: return
        // The entries in the trip mailboxes first (Travel together): nobody can take them out once the Firebase
        // account is gone. A failure throws, so the account stays and this can be tried again.
        graph.together.forgetMail()
        deleteProfile()
        try {
            try {
                user.delete().await()
            } catch (e: FirebaseAuthRecentLoginRequiredException) {
                // Firebase wants a fresh sign-in before deleting an account: a quiet one with the same Google account.
                val token = (if (usesEmulator) null else graph.google.freshIdToken(activity)) ?: throw e
                user.reauthenticate(GoogleAuthProvider.getCredential(token, null)).await()
                user.delete().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Firebase account not deleted", e)
            throw IllegalStateException("Couldn't delete your community account. Try again.", e)
        }
        signOut()
    }

    // ---------------------------------------------------------------- pictures and clips of posts

    /** Other people's pictures: their own disk cache, cross-fading in, over the app's Google HTTP client. */
    val images: ImageLoader by lazy {
        ImageLoader.Builder(app)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.google.http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(app, 0.15).build() }
            .diskCache { DiskCache.Builder().directory(File(app.cacheDir, "community_images").toOkioPath()).maxSizeBytes(150L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
    }

    /** Where picture [fileId] is read from and the headers to send (the app's Drive API key, or the public address). */
    fun pictureAddress(fileId: String): Pair<String, Map<String, String>> =
        if (usesEmulator) "http://$emulatorHost:$PICTURE_PORT/$fileId" to emptyMap() else graph.publicDrive.address(fileId)

    /** Clip [fileId] downloaded to the cache (once), to play. */
    suspend fun clipFile(fileId: String): File {
        val dir = File(app.cacheDir, "community_clips").apply { mkdirs() }
        val target = File(dir, "$fileId.mp4")
        if (target.isFile && target.length() > 0) return target.also { it.setLastModified(System.currentTimeMillis()) }
        withContext(Dispatchers.IO) { prune(dir) }
        if (usesEmulator) {
            withContext(Dispatchers.IO) {
                graph.google.http.newCall(Request.Builder().url(pictureAddress(fileId).first).build()).await().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("The clip isn't there.")
                    target.outputStream().use { out -> r.body?.byteStream()?.copyTo(out) }
                }
            }
        } else {
            graph.publicDrive.download(fileId, target, CommunityLimits.CLIP_BYTES)
        }
        return target
    }

    /** Keeps the clip cache small: the newest few. */
    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }.orEmpty().sortedByDescending { it.lastModified() }
        var total = 0L
        files.forEachIndexed { i, f ->
            total += f.length()
            if (i >= 6 || total > 300L * 1024 * 1024) f.delete()
        }
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun emulatorApp(): FirebaseApp? = runCatching {
        FirebaseApp.getApps(app).firstOrNull { it.name == EMULATOR_APP } ?: FirebaseApp.initializeApp(
            app,
            FirebaseOptions.Builder().setProjectId(EMULATOR_PROJECT).setApplicationId("1:1:android:1").setApiKey("demo-api-key").build(),
            EMULATOR_APP,
        )
    }.onFailure { Log.w(TAG, "Emulator app didn't start", it) }.getOrNull()

    companion object {
        private const val TAG = "Community"
        private const val EMULATOR_APP = "community-emulator"
        const val EMULATOR_PROJECT = "demo-boringtravel"
        const val AUTH_PORT = 9199
        const val FIRESTORE_PORT = 8180
        const val PICTURE_PORT = 8199
        private const val LIKE_WAIT_MS = 10_000L

        /** How long a report or a block waits for the server before saying it goes out once online. */
        private const val WRITE_WAIT_MS = 8_000L
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
