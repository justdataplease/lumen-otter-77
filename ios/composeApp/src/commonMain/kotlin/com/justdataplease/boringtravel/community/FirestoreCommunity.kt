package com.justdataplease.boringtravel.community

import com.justdataplease.boringtravel.firebase.FirestoreException
import com.justdataplease.boringtravel.firebase.FirestoreRest
import com.justdataplease.boringtravel.firebase.FsCursor
import com.justdataplease.boringtravel.firebase.FsDoc
import com.justdataplease.boringtravel.firebase.FsQuery
import com.justdataplease.boringtravel.firebase.FsWrites
import com.justdataplease.boringtravel.firebase.Increment
import com.justdataplease.boringtravel.firebase.ServerTime
import com.justdataplease.boringtravel.google.PublicDrive
import com.justdataplease.boringtravel.ios.NetException
import com.justdataplease.boringtravel.ios.Sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

/**
 * The community in Firestore, the Android app's community/FirestoreCommunity.kt written again over Firestore's REST API
 * (round 24): the same paths, documents, queries and writes, shaped the way the owner's rules want them (server times,
 * a like together with the post's count, a username together with the profile that names it, a follow with both its
 * halves), the same failures in people's words ([mapped]). Where the SDK keeps things on the phone, this keeps them by
 * hand: the lists and documents last read ([cache], read when there is no connection: the page then says it came from the
 * phone), and the writes made without a connection ([outbox], sent in order once it is back: [flush]). Reads the SDK
 * makes from the server only (deleting, counting) never use the cache.
 */
class FirestoreCommunity(
    private val fs: FirestoreRest,
    private val auth: CommunityAuth,
    private val cache: CommunityCache,
    private val outbox: CommunityOutbox,
    private val random: Random = Random,
) : CommunityRepository {
    override val user: StateFlow<CommunityUser?> = auth.user

    private val sending = Mutex()

    private fun uid(): String = user.value?.uid ?: throw NotSignedInException()

    // ---------------------------------------------------------------- reading

    override suspend fun profile(uid: String): Profile? = call { read("$PROFILES/$uid") }.toProfile()

    override suspend fun usernameOwner(username: String): String? {
        if (!Usernames.isValid(username)) return null
        return call { server { fs.get("$USERNAMES/$username") } }?.string("uid")
    }

    override suspend fun latest(after: Any?, limit: Int): Page<Post> =
        page(null, FsQuery(POSTS, orders = listOf(FsQuery.Order("createdAt", descending = true))), after, limit)

    override suspend fun top(range: TopRange, month: String, after: Any?, limit: Int): Page<Post> {
        val filters = if (range == TopRange.Month) listOf(FsQuery.Filter("month", "EQUAL", month)) else emptyList()
        return page(null, FsQuery(POSTS, filters, listOf(FsQuery.Order("likes", descending = true), FsQuery.Order("createdAt"))), after, limit)
    }

    override suspend fun postsBy(uid: String, limit: Int): List<Post> =
        page(null, FsQuery(POSTS, listOf(FsQuery.Filter("uid", "EQUAL", uid)), listOf(FsQuery.Order("createdAt", descending = true))), null, limit).items

    override suspend fun post(id: String): Post? = call { read("$POSTS/$id") }.toPost()

    override suspend fun trip(postId: String): String? = call { read("$POSTS/$postId/$CONTENT/$TRIP") }?.string(TRIP)

    /** Firestore's own kind of id: 20 letters and digits (the SDK's auto id). */
    override fun newPostId(): String = autoId()

    private fun autoId(): String = buildString { repeat(20) { append(ID_CHARS[random.nextInt(ID_CHARS.length)]) } }

    /**
     * A page of [query] under [parent] after [after] (a [FsCursor]). Without a connection the page last read for the same
     * question comes from the phone, else an empty page that says so (the SDK answers from its cache the same way).
     */
    private suspend fun page(parent: String?, query: FsQuery, after: Any?, limit: Int): Page<Post> {
        val q = query.copy(limit = limit, after = after as? FsCursor)
        val key = pageKey(parent, q)
        val docs = try {
            fs.query(parent, q).also { cache.putPage(key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!offline(e)) throw mapped(e)
            val kept = cache.page(key).orEmpty()
            return Page(kept.mapNotNull { it.toPost() }, null, fromCache = true)
        }
        return Page(docs.mapNotNull { it.toPost() }, if (docs.size < limit) null else q.cursorAfter(docs.last()), fromCache = false)
    }

    private fun pageKey(parent: String?, q: FsQuery) = Sha256.hex(((parent ?: "") + "|" + q.json().toString()).encodeToByteArray())

    /** The document at [path]: the server's, kept; without a connection the one kept, else the failure. */
    private suspend fun read(path: String): FsDoc? = try {
        fs.get(path).also { d -> if (d != null) cache.putDoc(d) else cache.dropDoc(path) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (offline(e)) cache.doc(path) ?: throw e else throw e
    }

    /** A read that only the server may answer (the SDK's Source.SERVER): never the phone's copy. */
    private suspend fun <T> server(block: suspend () -> T): T = block()

    // ---------------------------------------------------------------- profiles

    override suspend fun saveProfile(username: String, name: String, bio: String, avatar: String?): Profile {
        val uid = uid()
        require(Usernames.isValid(username)) { Usernames.problem(username)?.message.orEmpty() }
        var tries = 0
        while (true) {
            val tx = call { fs.begin() }
            try {
                val current = fs.get("$PROFILES/$uid", tx)
                val holder = fs.get("$USERNAMES/$username", tx)
                if (holder != null && holder.string("uid") != uid) {
                    fs.rollback(tx)
                    throw UsernameTakenException(username)
                }
                val oldName = current?.string("username")
                val data = linkedMapOf<String, Any?>(
                    "username" to username,
                    "name" to name.trim().take(CommunityLimits.NAME),
                    "bio" to bio.trim().take(CommunityLimits.BIO),
                    "avatar" to avatar?.takeIf { it.length <= CommunityLimits.AVATAR },
                    // The rules keep the first createdAt: the same value goes back.
                    "createdAt" to (current?.time("createdAt") ?: ServerTime),
                    "updatedAt" to ServerTime,
                )
                val writes = buildList {
                    if (holder == null) add(FsWrites.set(fs.name("$USERNAMES/$username"), mapOf("uid" to uid)))
                    if (oldName != null && oldName != username) add(FsWrites.delete(fs.name("$USERNAMES/$oldName")))
                    add(FsWrites.set(fs.name("$PROFILES/$uid"), data))
                }
                fs.commit(writes, tx)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: UsernameTakenException) {
                throw e
            } catch (e: FirestoreException) {
                // Someone changed one of the two in between: the SDK runs the transaction again (up to five times).
                if (e.status == "ABORTED" && ++tries < 5) continue
                throw mapped(e)
            } catch (e: Exception) {
                throw mapped(e)
            }
        }
        // The profile is saved: nothing after this fails the save. The posts follow the name, also when an earlier
        // rename's posts didn't all change (the old @name is free for others, so they must not keep it).
        try {
            withTimeoutOrNull(FOLLOW_WAIT_MS) { renamePosts(uid, username) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Posts keep an old username until the next save.
        }
        val saved = try {
            profile(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return saved ?: Profile(uid, username, name.trim().take(CommunityLimits.NAME), bio.trim().take(CommunityLimits.BIO), avatar)
    }

    /** Every own post under another name than [username] gets it (the rules let only the username and updatedAt change). */
    private suspend fun renamePosts(uid: String, username: String) {
        val stale = fs.query(null, FsQuery(POSTS, listOf(FsQuery.Filter("uid", "EQUAL", uid)))).filter { it.string("username") != username }
        stale.chunked(BATCH).forEach { chunk ->
            fs.commit(chunk.map { FsWrites.update(it.name, mapOf("username" to username, "updatedAt" to ServerTime)) })
        }
    }

    // ---------------------------------------------------------------- posting (round 25 posts; deleting is here)

    override suspend fun createPost(post: Post, tripJson: String?): Post {
        val uid = uid()
        val profile = profile(uid) ?: throw NoProfileException()
        val ref = fs.name("$POSTS/${post.id}")
        val data = linkedMapOf<String, Any?>(
            "uid" to uid, "username" to profile.username, "kind" to post.kind.id,
            "title" to post.title.take(CommunityLimits.TITLE), "caption" to post.caption.take(CommunityLimits.CAPTION),
            "countries" to post.countries, "days" to post.days, "km" to post.km, "startMonth" to post.startMonth,
            "month" to post.month, "coverFileId" to post.coverFileId, "photoFileIds" to post.photoFileIds,
            "clipFileId" to post.clipFileId, "folderId" to post.folderId, "hasTrip" to (tripJson != null), "likes" to 0,
            "createdAt" to ServerTime, "updatedAt" to ServerTime,
        )
        try {
            call {
                fs.commit(buildList {
                    add(FsWrites.set(ref, data))
                    if (tripJson != null) add(FsWrites.set(fs.name("$POSTS/${post.id}/$CONTENT/$TRIP"), mapOf(TRIP to tripJson)))
                })
            }
        } catch (e: CommunityRefusedException) {
            // A try that reached the server before its answer was lost: the post is there already.
            val there = runCatching { post(post.id) }.getOrNull()
            if (there == null || there.uid != uid) throw e
            return there
        }
        val now = com.justdataplease.boringtravel.time.nowMs()
        return post.copy(uid = uid, username = profile.username, likes = 0, hasTrip = tripJson != null, createdAt = now, updatedAt = now)
    }

    override suspend fun updatePost(post: Post, tripJson: String?): Post {
        val uid = uid()
        val data = linkedMapOf<String, Any?>(
            "countries" to post.countries, "days" to post.days, "km" to post.km, "startMonth" to post.startMonth,
            "coverFileId" to post.coverFileId, "photoFileIds" to post.photoFileIds, "folderId" to post.folderId,
            "updatedAt" to ServerTime,
        )
        call {
            fs.commit(buildList {
                add(FsWrites.update(fs.name("$POSTS/${post.id}"), data))
                if (tripJson != null) add(FsWrites.set(fs.name("$POSTS/${post.id}/$CONTENT/$TRIP"), mapOf(TRIP to tripJson)))
            })
        }
        return post.copy(uid = uid, updatedAt = com.justdataplease.boringtravel.time.nowMs())
    }

    override suspend fun deletePost(post: Post) {
        val uid = uid()
        if (post.uid != uid) throw CommunityRefusedException("Only its poster can delete a post.")
        call { deletePostDoc("$POSTS/${post.id}") }
        cache.dropDoc("$POSTS/${post.id}")
    }

    /**
     * Deletes post [path] with its trip and likes. The likes are read from the server, never the phone's copy: offline
     * this fails at once instead of leaving likes behind (and, for the caller, a Drive folder still shared).
     */
    private suspend fun deletePostDoc(path: String) {
        val likes = server { fs.query(path, FsQuery(LIKES)) }
        val first = likes.take(BATCH - 2)
        fs.commit(listOf(FsWrites.delete(fs.name(path)), FsWrites.delete(fs.name("$path/$CONTENT/$TRIP"))) + first.map { FsWrites.delete(it.name) })
        // The rest of the likes once the post is gone (the rules allow clearing a deleted post's likes).
        likes.drop(first.size).chunked(BATCH).forEach { chunk -> fs.commit(chunk.map { FsWrites.delete(it.name) }) }
    }

    // ---------------------------------------------------------------- likes

    override suspend fun liked(postIds: Collection<String>): Map<String, Boolean> {
        val uid = user.value?.uid ?: return emptyMap()
        val server = coroutineScope {
            postIds.distinct().map { id ->
                async {
                    try {
                        id to (fs.get("$POSTS/$id/$LIKES/$uid") != null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null // not known: asked again later
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        // Likes still waiting to go are what the phone shows (the SDK's own pending writes do the same).
        val pending = outbox.of(uid).filter { (it.kind == "like" || it.kind == "unlike") && it.key in postIds }.associate { it.key to (it.kind == "like") }
        return server + pending
    }

    override suspend fun setLiked(postId: String, liked: Boolean) {
        val uid = uid()
        val post = fs.name("$POSTS/$postId")
        val like = fs.name("$POSTS/$postId/$LIKES/$uid")
        val writes = if (liked) {
            listOf(FsWrites.set(like, mapOf("uid" to uid, "at" to ServerTime)), FsWrites.update(post, mapOf("likes" to Increment(1))))
        } else {
            listOf(FsWrites.delete(like), FsWrites.update(post, mapOf("likes" to Increment(-1))))
        }
        send(if (liked) "like" else "unlike", postId, writes)
    }

    // ---------------------------------------------------------------- reports and blocks

    override suspend fun report(postId: String?, target: String, reason: ReportReason) {
        val uid = uid()
        val data = linkedMapOf<String, Any?>("postId" to postId, "target" to target, "uid" to uid, "reason" to reason.id, "at" to ServerTime)
        send("report", postId ?: target, listOf(FsWrites.set(fs.name("$REPORTS/${autoId()}"), data)))
    }

    override suspend fun blocked(): List<BlockedUser> {
        val uid = user.value?.uid ?: return emptyList()
        val list = call {
            fs.query(BLOCKED_OF(uid), FsQuery(BLOCKED)).map { d -> BlockedUser(d.id, d.string("username").orEmpty(), d.time("at")?.ms ?: 0) }
        }
        // Blocks and unblocks still waiting to go count already.
        var out = list
        outbox.of(uid).forEach { e ->
            when (e.kind) {
                "block" -> if (out.none { it.uid == e.key }) out = out + BlockedUser(e.key, e.name.orEmpty(), 0)
                "unblock" -> out = out.filter { it.uid != e.key }
            }
        }
        return out.sortedByDescending { it.at }
    }

    override suspend fun block(user: BlockedUser) {
        val uid = uid()
        // The follows between the two end in a write of their own (rules from before follows refuse it, and the block
        // must still go then); it waits on the phone like the block when there is no connection.
        runCatching { send("unfollowBoth", user.uid, endFollows(uid, user.uid)) }
        send("block", user.uid, listOf(FsWrites.set(fs.name("${BLOCKED_OF(uid)}/$BLOCKED/${user.uid}"), mapOf("username" to user.username.take(Usernames.MAX), "at" to ServerTime))), user.username)
    }

    /** Deletes the follows between [me] and [other], both ways (either side may delete a follow). */
    private fun endFollows(me: String, other: String): List<JsonObject> = listOf(
        FsWrites.delete(fs.name("${USER(me)}/$FOLLOWING/$other")),
        FsWrites.delete(fs.name("${USER(other)}/$FOLLOWERS/$me")),
        FsWrites.delete(fs.name("${USER(other)}/$FOLLOWING/$me")),
        FsWrites.delete(fs.name("${USER(me)}/$FOLLOWERS/$other")),
    )

    override suspend fun unblock(uid: String) {
        val me = uid()
        send("unblock", uid, listOf(FsWrites.delete(fs.name("${BLOCKED_OF(me)}/$BLOCKED/$uid"))))
    }

    // ---------------------------------------------------------------- follows

    override suspend fun isFollowing(uid: String): Boolean {
        val me = uid()
        val pending = outbox.of(me).lastOrNull { (it.kind == "follow" || it.kind == "unfollow" || it.kind == "unfollowBoth") && it.key == uid }
        if (pending != null) return pending.kind == "follow"
        return call { read("${USER(me)}/$FOLLOWING/$uid") } != null
    }

    override suspend fun follow(uid: String, username: String, myUsername: String) {
        val me = uid()
        if (uid == me) throw CommunityRefusedException("You can't follow yourself.")
        send(
            "follow", uid,
            listOf(
                FsWrites.set(fs.name("${USER(me)}/$FOLLOWING/$uid"), mapOf("username" to username, "at" to ServerTime)),
                FsWrites.set(fs.name("${USER(uid)}/$FOLLOWERS/$me"), mapOf("username" to myUsername, "at" to ServerTime)),
            ),
        )
    }

    override suspend fun unfollow(uid: String) {
        val me = uid()
        send("unfollow", uid, listOf(FsWrites.delete(fs.name("${USER(me)}/$FOLLOWING/$uid")), FsWrites.delete(fs.name("${USER(uid)}/$FOLLOWERS/$me"))))
    }

    override suspend fun followCounts(uid: String): FollowCounts = call {
        coroutineScope {
            // Firestore's count: one read per 1,000 people, and nothing a phone could fake (from the server only).
            val followers = async { fs.count(USER(uid), FOLLOWERS) }
            val following = async { fs.count(USER(uid), FOLLOWING) }
            FollowCounts(followers.await().toInt(), following.await().toInt())
        }
    }

    override suspend fun followers(uid: String, after: Any?, limit: Int): Page<FollowLink> = links(USER(uid), FOLLOWERS, after, limit)

    override suspend fun following(uid: String, after: Any?, limit: Int): Page<FollowLink> = links(USER(uid), FOLLOWING, after, limit)

    private suspend fun links(parent: String, collection: String, after: Any?, limit: Int): Page<FollowLink> {
        val q = FsQuery(collection, orders = listOf(FsQuery.Order("at", descending = true)), limit = limit, after = after as? FsCursor)
        val key = pageKey(parent, q)
        val docs = try {
            fs.query(parent, q).also { cache.putPage(key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!offline(e)) throw mapped(e)
            return Page(cache.page(key).orEmpty().mapNotNull { it.toLink() }, null, fromCache = true)
        }
        return Page(docs.mapNotNull { it.toLink() }, if (docs.size < limit) null else q.cursorAfter(docs.last()), fromCache = false)
    }

    override suspend fun postsOf(uids: List<String>, after: Any?, limit: Int): Page<Post> {
        require(uids.size in 1..Follows.IN_LIMIT) { "1 to ${Follows.IN_LIMIT} people at a time" }
        return page(null, FsQuery(POSTS, listOf(FsQuery.Filter("uid", "IN", uids)), listOf(FsQuery.Order("createdAt", descending = true))), after, limit)
    }

    /**
     * The signed-in user's follows, both ways, deleted with the other side's half (account removal). The lists are read
     * from the server; rules from before follows refuse reading them, and then there are none to delete.
     */
    private suspend fun deleteFollows(uid: String) {
        suspend fun ids(collection: String): List<String> = try {
            fs.query(USER(uid), FsQuery(collection)).map { it.id }
        } catch (e: FirestoreException) {
            if (e.status != "PERMISSION_DENIED") throw e
            emptyList()
        }
        val pairs = ids(FOLLOWING).map { it to true } + ids(FOLLOWERS).map { it to false }
        pairs.chunked(BATCH / 2).forEach { chunk ->
            fs.commit(chunk.flatMap { (other, followedByThem) ->
                if (followedByThem) {
                    listOf(FsWrites.delete(fs.name("${USER(uid)}/$FOLLOWING/$other")), FsWrites.delete(fs.name("${USER(other)}/$FOLLOWERS/$uid")))
                } else {
                    listOf(FsWrites.delete(fs.name("${USER(uid)}/$FOLLOWERS/$other")), FsWrites.delete(fs.name("${USER(other)}/$FOLLOWING/$uid")))
                }
            })
        }
    }

    override suspend fun deleteAll(): List<String> {
        val uid = uid()
        // Every read goes to the server (never the phone's copy): offline the first one fails at once, before anything
        // is deleted, and nothing is missed because the copy was old.
        val folders = call {
            // The likes they gave: each one off its post's count (or just cleared when the post is gone).
            val given = fs.query(null, FsQuery(LIKES, listOf(FsQuery.Filter("uid", "EQUAL", uid)), allDescendants = true))
            for (like in given) {
                val post = like.parentPath ?: continue
                val exists = fs.get(post) != null
                fs.commit(listOfNotNull(FsWrites.delete(like.name), if (exists) FsWrites.update(fs.name(post), mapOf("likes" to Increment(-1))) else null))
            }
            fs.query(BLOCKED_OF(uid), FsQuery(BLOCKED)).chunked(BATCH).forEach { chunk -> fs.commit(chunk.map { FsWrites.delete(it.name) }) }
            deleteFollows(uid)
            // Every post document of theirs, also one the app can't read as a post.
            val mine = fs.query(null, FsQuery(POSTS, listOf(FsQuery.Filter("uid", "EQUAL", uid))))
            for (p in mine) deletePostDoc(p.path)
            val name = fs.get("$PROFILES/$uid")?.string("username")
            if (name != null) fs.commit(listOf(FsWrites.delete(fs.name("$PROFILES/$uid")), FsWrites.delete(fs.name("$USERNAMES/$name"))))
            mine.mapNotNull { it.string("folderId")?.takeIf(PublicDrive::isId) }
        }
        outbox.clear(uid)
        cache.clear()
        return folders
    }

    // ---------------------------------------------------------------- the writes made without a connection

    /**
     * One write that may wait for a connection (a like, a report, a block, a follow): the earlier ones still waiting go
     * first; without a connection this one waits too ([outbox]) and [CommunityOfflineException] says so (the caller
     * counts it as kept on the phone, as Android counts the SDK's pending write). A write cut off on its way (the
     * caller's wait ran out) waits too: it goes again with the next ones (the rules refuse a like twice).
     */
    private suspend fun send(kind: String, key: String, writes: List<JsonObject>, name: String? = null) {
        val uid = uid()
        val entry = CommunityOutbox.Entry(uid, kind, key, CommunityOutbox.writesText(writes), name)
        if (!flush()) {
            outbox.add(entry)
            throw CommunityOfflineException()
        }
        try {
            fs.commit(writes)
        } catch (e: CancellationException) {
            outbox.add(entry)
            throw e
        } catch (e: Exception) {
            if (offline(e)) {
                outbox.add(entry)
                throw CommunityOfflineException()
            }
            throw mapped(e)
        }
    }

    /**
     * Sends the signed-in account's waiting writes, oldest first; true when none is left. A refused one is dropped (the
     * SDK gives up on a write the rules refuse); without a connection the rest wait.
     */
    suspend fun flush(): Boolean = sending.withLock {
        val uid = user.value?.uid ?: return@withLock true
        for (e in outbox.of(uid)) {
            try {
                fs.commit(e.commit)
                outbox.remove(e)
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                if (offline(x)) return@withLock false
                outbox.remove(e)
            }
        }
        true
    }

    /** Whether writes wait for a connection. */
    suspend fun waiting(): Boolean = user.value?.uid?.let { outbox.of(it).isNotEmpty() } == true

    /** Another account (or none): what the phone kept of the community goes. */
    suspend fun forgetCache() = cache.clear()

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: NetException) {
        throw mapped(e)
    } catch (e: FirestoreException) {
        throw mapped(e)
    }

    companion object {
        const val PROFILES = "profiles"
        const val USERNAMES = "usernames"
        const val POSTS = "posts"
        const val LIKES = "likes"
        const val CONTENT = "content"
        const val TRIP = "trip"
        const val REPORTS = "reports"
        const val USERS = "users"
        const val BLOCKED = "blocked"
        const val FOLLOWING = "following"
        const val FOLLOWERS = "followers"
        private const val BATCH = 450
        private const val ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /** How long a save waits for its posts to follow a new username before returning (the writes still go). */
        private const val FOLLOW_WAIT_MS = 15_000L

        private fun USER(uid: String) = "$USERS/$uid"
        private fun BLOCKED_OF(uid: String) = USER(uid)

        /** No connection, or the server not reachable now: the phone's copy answers (the SDK falls back to its cache). */
        fun offline(e: Throwable): Boolean = e is NetException || e is CommunityOfflineException ||
            (e is FirestoreException && (e.status == "UNAVAILABLE" || e.status == "DEADLINE_EXCEEDED"))

        /** People's words for Firestore's errors (Android's FirestoreCommunity.mapped). */
        fun mapped(e: Exception): Exception = when (e) {
            is NetException -> CommunityOfflineException()
            is FirestoreException -> when (e.status) {
                "UNAVAILABLE", "DEADLINE_EXCEEDED" -> CommunityOfflineException()
                "UNAUTHENTICATED" -> NotSignedInException()
                "PERMISSION_DENIED" -> CommunityRefusedException()
                "FAILED_PRECONDITION" -> CommunityRefusedException("The community is getting ready. Try again in a few minutes.")
                "RESOURCE_EXHAUSTED" -> CommunityOfflineException("The community is busy. Try again later.")
                else -> CommunityRefusedException("The community didn't answer. Try again.")
            }
            else -> e
        }

        internal fun FsDoc?.toProfile(): Profile? {
            if (this == null) return null
            val username = string("username")?.takeIf(Usernames::isValid) ?: return null
            return Profile(
                uid = id, username = username, name = string("name").orEmpty(), bio = string("bio").orEmpty(),
                avatar = string("avatar")?.takeIf { it.isNotBlank() }, createdAt = time("createdAt")?.ms ?: 0L, updatedAt = time("updatedAt")?.ms ?: 0L,
            )
        }

        /** A followers or following entry: the other side's uid (its id) and username then; null when it isn't one. */
        internal fun FsDoc.toLink(): FollowLink? {
            val username = string("username")?.takeIf(Usernames::isValid) ?: return null
            return FollowLink(id, username, time("at")?.ms ?: 0L)
        }

        private fun FsDoc.ids(field: String): List<String> = list(field).orEmpty().filterIsInstance<String>().filter(PublicDrive::isId)

        /** The post, or null when it is gone or isn't one the app would make (an odd id, a kind it doesn't know). */
        internal fun FsDoc?.toPost(): Post? {
            if (this == null || !Post.isId(id)) return null
            val kind = PostKind.of(string("kind")) ?: return null
            return Post(
                id = id, uid = string("uid") ?: return null, username = string("username").orEmpty(), kind = kind,
                title = string("title").orEmpty(), caption = string("caption").orEmpty(),
                countries = list("countries").orEmpty().filterIsInstance<String>().filter { it.matches(Regex("[A-Z]{2}")) },
                days = long("days")?.toInt()?.coerceAtLeast(0) ?: 0, km = long("km")?.toInt()?.coerceAtLeast(0) ?: 0,
                startMonth = string("startMonth"), month = string("month").orEmpty(),
                coverFileId = string("coverFileId")?.takeIf(PublicDrive::isId), photoFileIds = ids("photoFileIds"),
                clipFileId = string("clipFileId")?.takeIf(PublicDrive::isId), folderId = string("folderId")?.takeIf(PublicDrive::isId),
                hasTrip = bool("hasTrip") == true, likes = long("likes")?.toInt()?.coerceAtLeast(0) ?: 0,
                createdAt = time("createdAt")?.ms ?: 0L, updatedAt = time("updatedAt")?.ms ?: 0L,
            )
        }
    }
}
