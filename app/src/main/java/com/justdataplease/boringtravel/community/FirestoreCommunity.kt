package com.justdataplease.boringtravel.community

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.justdataplease.boringtravel.google.PublicDrive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The community in Firestore (paths and rules: android-boringtravel/firebase). Every write is shaped the way the rules want
 * it: server times, a like together with the post's count, a username together with the profile that names it, a follow
 * with both its halves.
 */
class FirestoreCommunity(private val db: FirebaseFirestore, private val auth: FirebaseAuth) : CommunityRepository {
    private val _user = MutableStateFlow(auth.currentUser?.let { CommunityUser(it.uid, it.email, it.displayName) })
    override val user: StateFlow<CommunityUser?> = _user

    init {
        auth.addAuthStateListener { a -> _user.value = a.currentUser?.let { CommunityUser(it.uid, it.email, it.displayName) } }
    }

    private fun uid(): String = auth.currentUser?.uid ?: throw NotSignedInException()
    private val posts get() = db.collection(POSTS)
    private fun postRef(id: String) = posts.document(id)

    override suspend fun profile(uid: String): Profile? = call {
        db.collection(PROFILES).document(uid).get().await().toProfile()
    }

    override suspend fun usernameOwner(username: String): String? = call {
        if (!Usernames.isValid(username)) return@call null
        db.collection(USERNAMES).document(username).get().await().getString("uid")
    }

    override suspend fun saveProfile(username: String, name: String, bio: String, avatar: String?): Profile {
        val uid = uid()
        require(Usernames.isValid(username)) { Usernames.problem(username)?.message.orEmpty() }
        val profileRef = db.collection(PROFILES).document(uid)
        val nameRef = db.collection(USERNAMES).document(username)
        try {
            db.runTransaction { tx ->
                val current = tx.get(profileRef)
                val holder = tx.get(nameRef)
                val holderUid = holder.getString("uid")
                if (holder.exists() && holderUid != uid) throw UsernameTakenException(username)
                val oldName = current.getString("username")
                val data = hashMapOf<String, Any?>(
                    "username" to username,
                    "name" to name.trim().take(CommunityLimits.NAME),
                    "bio" to bio.trim().take(CommunityLimits.BIO),
                    "avatar" to avatar?.takeIf { it.length <= CommunityLimits.AVATAR },
                    // The rules keep the first createdAt: the same value goes back.
                    "createdAt" to (current.getTimestamp("createdAt")?.takeIf { current.exists() } ?: FieldValue.serverTimestamp()),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                if (!holder.exists()) tx.set(nameRef, mapOf("uid" to uid))
                if (oldName != null && oldName != username) tx.delete(db.collection(USERNAMES).document(oldName))
                tx.set(profileRef, data)
                Unit
            }.await()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            generateSequence<Throwable>(e) { it.cause }.filterIsInstance<UsernameTakenException>().firstOrNull()?.let { throw it }
            throw mapped(e)
        }
        // The profile is saved: nothing after this fails the save. The posts follow the name, also when an earlier
        // rename's posts didn't all change (the old @name is free for others, so they must not keep it).
        try {
            withTimeoutOrNull(FOLLOW_WAIT_MS) { renamePosts(uid, username) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Posts keep an old username until the next save", e)
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
        val stale = posts.whereEqualTo("uid", uid).get().await().documents.filter { it.getString("username") != username }
        stale.chunked(BATCH).forEach { chunk ->
            db.runBatch { b -> chunk.forEach { b.update(it.reference, mapOf("username" to username, "updatedAt" to FieldValue.serverTimestamp())) } }.await()
        }
    }

    override suspend fun latest(after: Any?, limit: Int): Page<Post> = page(posts.orderBy("createdAt", Query.Direction.DESCENDING), after, limit)

    override suspend fun top(range: TopRange, month: String, after: Any?, limit: Int): Page<Post> {
        var q: Query = posts
        if (range == TopRange.Month) q = q.whereEqualTo("month", month)
        q = q.orderBy("likes", Query.Direction.DESCENDING).orderBy("createdAt", Query.Direction.ASCENDING)
        return page(q, after, limit)
    }

    private suspend fun page(query: Query, after: Any?, limit: Int): Page<Post> = call {
        var q = query.limit(limit.toLong())
        (after as? DocumentSnapshot)?.let { q = q.startAfter(it) }
        val snap = q.get().await()
        val docs = snap.documents
        Page(docs.mapNotNull { it.toPost() }, if (docs.size < limit) null else docs.last(), snap.metadata.isFromCache)
    }

    override suspend fun postsBy(uid: String, limit: Int): List<Post> = call {
        posts.whereEqualTo("uid", uid).orderBy("createdAt", Query.Direction.DESCENDING).limit(limit.toLong()).get().await()
            .documents.mapNotNull { it.toPost() }
    }

    override suspend fun post(id: String): Post? = call { postRef(id).get().await().toPost() }

    override suspend fun trip(postId: String): String? = call {
        postRef(postId).collection(CONTENT).document(TRIP).get().await().getString(TRIP)
    }

    override fun newPostId(): String = posts.document().id

    override suspend fun createPost(post: Post, tripJson: String?): Post {
        val uid = uid()
        val profile = profile(uid) ?: throw NoProfileException()
        val ref = postRef(post.id)
        val data = hashMapOf<String, Any?>(
            "uid" to uid, "username" to profile.username, "kind" to post.kind.id,
            "title" to post.title.take(CommunityLimits.TITLE), "caption" to post.caption.take(CommunityLimits.CAPTION),
            "countries" to post.countries, "days" to post.days, "km" to post.km, "startMonth" to post.startMonth,
            "month" to post.month, "coverFileId" to post.coverFileId, "photoFileIds" to post.photoFileIds,
            "clipFileId" to post.clipFileId, "folderId" to post.folderId, "hasTrip" to (tripJson != null), "likes" to 0,
            "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
        )
        try {
            call {
                db.runBatch { b ->
                    b.set(ref, data)
                    if (tripJson != null) b.set(ref.collection(CONTENT).document(TRIP), mapOf(TRIP to tripJson))
                }.await()
            }
        } catch (e: CommunityRefusedException) {
            // A try that reached the server before its answer was lost: the post is there already.
            val there = runCatching { post(post.id) }.getOrNull()
            if (there == null || there.uid != uid) throw e
            return there
        }
        val now = System.currentTimeMillis()
        return post.copy(uid = uid, username = profile.username, likes = 0, hasTrip = tripJson != null, createdAt = now, updatedAt = now)
    }

    override suspend fun updatePost(post: Post, tripJson: String?): Post {
        val uid = uid()
        val ref = postRef(post.id)
        val data = hashMapOf<String, Any?>(
            "countries" to post.countries, "days" to post.days, "km" to post.km, "startMonth" to post.startMonth,
            "coverFileId" to post.coverFileId, "photoFileIds" to post.photoFileIds, "folderId" to post.folderId,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        call {
            db.runBatch { b ->
                b.update(ref, data)
                if (tripJson != null) b.set(ref.collection(CONTENT).document(TRIP), mapOf(TRIP to tripJson))
            }.await()
        }
        return post.copy(uid = uid, updatedAt = System.currentTimeMillis())
    }

    override suspend fun deletePost(post: Post) {
        val uid = uid()
        if (post.uid != uid) throw CommunityRefusedException("Only its poster can delete a post.")
        call { deletePostDoc(postRef(post.id)) }
    }

    /**
     * Deletes post [ref] with its trip and likes. The likes are read from the server, never the phone's copy: offline
     * this fails at once instead of leaving likes behind (and, for the caller, a Drive folder still shared).
     */
    private suspend fun deletePostDoc(ref: DocumentReference) {
        val likes = ref.collection(LIKES).get(Source.SERVER).await().documents
        val first = likes.take(BATCH - 2)
        db.runBatch { b ->
            b.delete(ref)
            b.delete(ref.collection(CONTENT).document(TRIP))
            first.forEach { b.delete(it.reference) }
        }.await()
        // The rest of the likes once the post is gone (the rules allow clearing a deleted post's likes).
        likes.drop(first.size).chunked(BATCH).forEach { chunk -> db.runBatch { b -> chunk.forEach { b.delete(it.reference) } }.await() }
    }

    override suspend fun liked(postIds: Collection<String>): Map<String, Boolean> {
        val uid = auth.currentUser?.uid ?: return emptyMap()
        return coroutineScope {
            postIds.distinct().map { id ->
                async {
                    try {
                        id to postRef(id).collection(LIKES).document(uid).get().await().exists()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null // not known: asked again later
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    override suspend fun setLiked(postId: String, liked: Boolean) {
        val uid = uid()
        val ref = postRef(postId)
        val like = ref.collection(LIKES).document(uid)
        call {
            db.runBatch { b ->
                if (liked) {
                    b.set(like, mapOf("uid" to uid, "at" to FieldValue.serverTimestamp()))
                    b.update(ref, "likes", FieldValue.increment(1))
                } else {
                    b.delete(like)
                    b.update(ref, "likes", FieldValue.increment(-1))
                }
            }.await()
        }
    }

    override suspend fun report(postId: String?, target: String, reason: ReportReason) {
        val uid = uid()
        call {
            db.collection(REPORTS).document().set(
                mapOf("postId" to postId, "target" to target, "uid" to uid, "reason" to reason.id, "at" to FieldValue.serverTimestamp()),
            ).await()
        }
    }

    override suspend fun blocked(): List<BlockedUser> {
        val uid = auth.currentUser?.uid ?: return emptyList()
        return call {
            blockedOf(uid).get().await().documents.map { d ->
                BlockedUser(d.id, d.getString("username").orEmpty(), d.getTimestamp("at")?.toDate()?.time ?: 0)
            }.sortedByDescending { it.at }
        }
    }

    override suspend fun block(user: BlockedUser) {
        val uid = uid()
        val block = blockedOf(uid).document(user.uid).set(mapOf("username" to user.username.take(Usernames.MAX), "at" to FieldValue.serverTimestamp()))
        // The follows between the two end in a write of their own, sent at the same time (offline both wait on the
        // phone): rules from before follows refuse it, and the block must still go then.
        endFollows(uid, user.uid).addOnFailureListener { Log.w(TAG, "Follows not ended with the block", it) }
        call { block.await() }
    }

    /** Deletes the follows between [me] and [other], both ways (either side may delete a follow). */
    private fun endFollows(me: String, other: String) = db.runBatch { b ->
        b.delete(followingOf(me).document(other))
        b.delete(followersOf(other).document(me))
        b.delete(followingOf(other).document(me))
        b.delete(followersOf(me).document(other))
    }

    override suspend fun unblock(uid: String) {
        val me = uid()
        call { blockedOf(me).document(uid).delete().await() }
    }

    private fun blockedOf(uid: String) = db.collection(USERS).document(uid).collection(BLOCKED)
    private fun followingOf(uid: String) = db.collection(USERS).document(uid).collection(FOLLOWING)
    private fun followersOf(uid: String) = db.collection(USERS).document(uid).collection(FOLLOWERS)

    // ---------------------------------------------------------------- follows

    override suspend fun isFollowing(uid: String): Boolean {
        val me = uid()
        return call { followingOf(me).document(uid).get().await().exists() }
    }

    override suspend fun follow(uid: String, username: String, myUsername: String) {
        val me = uid()
        if (uid == me) throw CommunityRefusedException("You can't follow yourself.")
        call {
            db.runBatch { b ->
                b.set(followingOf(me).document(uid), mapOf("username" to username, "at" to FieldValue.serverTimestamp()))
                b.set(followersOf(uid).document(me), mapOf("username" to myUsername, "at" to FieldValue.serverTimestamp()))
            }.await()
        }
    }

    override suspend fun unfollow(uid: String) {
        val me = uid()
        call {
            db.runBatch { b ->
                b.delete(followingOf(me).document(uid))
                b.delete(followersOf(uid).document(me))
            }.await()
        }
    }

    override suspend fun followCounts(uid: String): FollowCounts = call {
        coroutineScope {
            // Firestore's count: one read per 1,000 people, and nothing a phone could fake.
            val followers = async { followersOf(uid).count().get(AggregateSource.SERVER).await().count }
            val following = async { followingOf(uid).count().get(AggregateSource.SERVER).await().count }
            FollowCounts(followers.await().toInt(), following.await().toInt())
        }
    }

    override suspend fun followers(uid: String, after: Any?, limit: Int): Page<FollowLink> = links(followersOf(uid), after, limit)

    override suspend fun following(uid: String, after: Any?, limit: Int): Page<FollowLink> = links(followingOf(uid), after, limit)

    private suspend fun links(list: CollectionReference, after: Any?, limit: Int): Page<FollowLink> = call {
        var q = list.orderBy("at", Query.Direction.DESCENDING).limit(limit.toLong())
        (after as? DocumentSnapshot)?.let { q = q.startAfter(it) }
        val snap = q.get().await()
        val docs = snap.documents
        Page(docs.mapNotNull { it.toLink() }, if (docs.size < limit) null else docs.last(), snap.metadata.isFromCache)
    }

    override suspend fun postsOf(uids: List<String>, after: Any?, limit: Int): Page<Post> {
        require(uids.size in 1..Follows.IN_LIMIT) { "1 to ${Follows.IN_LIMIT} people at a time" }
        return page(posts.whereIn("uid", uids).orderBy("createdAt", Query.Direction.DESCENDING), after, limit)
    }

    /**
     * The signed-in user's follows, both ways, deleted with the other side's half (account removal). The lists are read
     * from the server; rules from before follows refuse reading them, and then there are none to delete.
     */
    private suspend fun deleteFollows(uid: String) {
        suspend fun ids(list: CollectionReference): List<String> = try {
            list.get(Source.SERVER).await().documents.map { it.id }
        } catch (e: FirebaseFirestoreException) {
            if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
            emptyList()
        }
        val pairs = ids(followingOf(uid)).map { it to true } + ids(followersOf(uid)).map { it to false }
        pairs.chunked(BATCH / 2).forEach { chunk ->
            db.runBatch { b ->
                chunk.forEach { (other, followedByThem) ->
                    if (followedByThem) {
                        b.delete(followingOf(uid).document(other))
                        b.delete(followersOf(other).document(uid))
                    } else {
                        b.delete(followersOf(uid).document(other))
                        b.delete(followingOf(other).document(uid))
                    }
                }
            }.await()
        }
    }

    override suspend fun deleteAll(): List<String> {
        val uid = uid()
        // Every read goes to the server (never the phone's copy): offline the first one fails at once, before anything
        // is deleted, and nothing is missed because the copy was old.
        return call {
            // The likes they gave: each one off its post's count (or just cleared when the post is gone).
            val given = db.collectionGroup(LIKES).whereEqualTo("uid", uid).get(Source.SERVER).await().documents
            for (like in given) {
                val post = like.reference.parent.parent ?: continue
                val exists = post.get(Source.SERVER).await().exists()
                db.runBatch { b ->
                    b.delete(like.reference)
                    if (exists) b.update(post, "likes", FieldValue.increment(-1))
                }.await()
            }
            blockedOf(uid).get(Source.SERVER).await().documents.chunked(BATCH).forEach { chunk ->
                db.runBatch { b -> chunk.forEach { b.delete(it.reference) } }.await()
            }
            deleteFollows(uid)
            // Every post document of theirs, also one the app can't read as a post.
            val mine = posts.whereEqualTo("uid", uid).get(Source.SERVER).await().documents
            for (p in mine) deletePostDoc(p.reference)
            val profileRef = db.collection(PROFILES).document(uid)
            val name = profileRef.get(Source.SERVER).await().getString("username")
            if (name != null) {
                db.runBatch { b ->
                    b.delete(profileRef)
                    b.delete(db.collection(USERNAMES).document(name))
                }.await()
            }
            mine.mapNotNull { it.getString("folderId")?.takeIf(PublicDrive::isId) }
        }
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: FirebaseFirestoreException) {
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
        private const val TAG = "FirestoreCommunity"

        /** How long a save waits for its posts to follow a new username before returning (the writes still go). */
        private const val FOLLOW_WAIT_MS = 15_000L

        /** People's words for Firestore's errors. */
        fun mapped(e: Exception): Exception {
            val f = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull() ?: return e
            return when (f.code) {
                FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> CommunityOfflineException()
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> NotSignedInException()
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> CommunityRefusedException()
                FirebaseFirestoreException.Code.FAILED_PRECONDITION -> CommunityRefusedException("The community is getting ready. Try again in a few minutes.")
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> CommunityOfflineException("The community is busy. Try again later.")
                else -> CommunityRefusedException("The community didn't answer. Try again.")
            }
        }

        private fun DocumentSnapshot.time(field: String): Long =
            getTimestamp(field, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: 0L

        private fun DocumentSnapshot.ids(field: String): List<String> =
            (get(field) as? List<*>).orEmpty().filterIsInstance<String>().filter(PublicDrive::isId)

        internal fun DocumentSnapshot.toProfile(): Profile? {
            if (!exists()) return null
            val username = getString("username")?.takeIf(Usernames::isValid) ?: return null
            return Profile(
                uid = id, username = username, name = getString("name").orEmpty(), bio = getString("bio").orEmpty(),
                avatar = getString("avatar")?.takeIf { it.isNotBlank() }, createdAt = time("createdAt"), updatedAt = time("updatedAt"),
            )
        }

        /** A followers or following entry: the other side's uid (its id) and username then; null when it isn't one. */
        internal fun DocumentSnapshot.toLink(): FollowLink? {
            if (!exists()) return null
            val username = getString("username")?.takeIf(Usernames::isValid) ?: return null
            return FollowLink(id, username, time("at"))
        }

        /** The post, or null when it is gone or isn't one the app would make (an odd id, a kind it doesn't know). */
        internal fun DocumentSnapshot.toPost(): Post? {
            if (!exists() || !Post.isId(id)) return null
            val kind = PostKind.of(getString("kind")) ?: return null
            return Post(
                id = id, uid = getString("uid") ?: return null, username = getString("username").orEmpty(), kind = kind,
                title = getString("title").orEmpty(), caption = getString("caption").orEmpty(),
                countries = (get("countries") as? List<*>).orEmpty().filterIsInstance<String>().filter { it.matches(Regex("[A-Z]{2}")) },
                days = getLong("days")?.toInt()?.coerceAtLeast(0) ?: 0, km = getLong("km")?.toInt()?.coerceAtLeast(0) ?: 0,
                startMonth = getString("startMonth"), month = getString("month").orEmpty(),
                coverFileId = getString("coverFileId")?.takeIf(PublicDrive::isId), photoFileIds = ids("photoFileIds"),
                clipFileId = getString("clipFileId")?.takeIf(PublicDrive::isId), folderId = getString("folderId")?.takeIf(PublicDrive::isId),
                hasTrip = getBoolean("hasTrip") == true, likes = getLong("likes")?.toInt()?.coerceAtLeast(0) ?: 0,
                createdAt = time("createdAt"), updatedAt = time("updatedAt"),
            )
        }

    }
}
