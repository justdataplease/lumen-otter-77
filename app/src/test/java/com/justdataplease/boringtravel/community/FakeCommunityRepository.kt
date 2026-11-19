package com.justdataplease.boringtravel.community

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An in-memory community for tests: the same contract as FirestoreCommunity, without the server. */
class FakeCommunityRepository(uid: String? = "me", private val clock: () -> Long = { 1_000L }) : CommunityRepository {
    private val _user = MutableStateFlow(uid?.let { CommunityUser(it) })
    override val user: StateFlow<CommunityUser?> = _user

    val profiles = LinkedHashMap<String, Profile>()
    val usernames = LinkedHashMap<String, String>()
    val posts = LinkedHashMap<String, Post>()
    val trips = HashMap<String, String>()
    val likes = HashMap<String, MutableSet<String>>()
    val reports = ArrayList<Triple<String?, String, ReportReason>>()
    val blocks = LinkedHashMap<String, BlockedUser>()
    var failNext: Exception? = null
    private var ids = 0

    fun signIn(uid: String?) { _user.value = uid?.let { CommunityUser(it) } }

    private fun me(): String = _user.value?.uid ?: throw NotSignedInException()
    private fun maybeFail() { failNext?.let { failNext = null; throw it } }

    override suspend fun profile(uid: String): Profile? = profiles[uid]
    override suspend fun usernameOwner(username: String): String? = usernames[username]

    override suspend fun saveProfile(username: String, name: String, bio: String, avatar: String?): Profile {
        maybeFail()
        val uid = me()
        val holder = usernames[username]
        if (holder != null && holder != uid) throw UsernameTakenException(username)
        val old = profiles[uid]
        if (old != null && old.username != username) usernames.remove(old.username)
        usernames[username] = uid
        val p = Profile(uid, username, name, bio, avatar, old?.createdAt ?: clock(), clock())
        profiles[uid] = p
        posts.replaceAll { _, post -> if (post.uid == uid) post.copy(username = username) else post }
        return p
    }

    private fun <T> page(list: List<T>, after: Any?, limit: Int): Page<T> {
        val from = (after as? Int) ?: 0
        val items = list.drop(from).take(limit)
        return Page(items, if (from + items.size < list.size) from + items.size else null)
    }

    override suspend fun latest(after: Any?, limit: Int): Page<Post> {
        maybeFail()
        return page(posts.values.sortedByDescending { it.createdAt }, after, limit)
    }

    override suspend fun top(range: TopRange, month: String, after: Any?, limit: Int): Page<Post> {
        maybeFail()
        val all = posts.values.filter { range == TopRange.AllTime || it.month == month }.sortedWith(Leaderboard.order)
        return page(all, after, limit)
    }

    override suspend fun postsBy(uid: String, limit: Int): List<Post> = posts.values.filter { it.uid == uid }.sortedByDescending { it.createdAt }.take(limit)
    override suspend fun post(id: String): Post? = posts[id]
    override suspend fun trip(postId: String): String? = trips[postId]
    override fun newPostId(): String = "post${++ids}"

    override suspend fun createPost(post: Post, tripJson: String?): Post {
        maybeFail()
        val uid = me()
        val profile = profiles[uid] ?: throw NoProfileException()
        val saved = post.copy(uid = uid, username = profile.username, likes = 0, createdAt = clock(), updatedAt = clock())
        posts[saved.id] = saved
        if (tripJson != null) trips[saved.id] = tripJson
        return saved
    }

    override suspend fun updatePost(post: Post, tripJson: String?): Post {
        maybeFail()
        val before = posts[post.id] ?: throw CommunityRefusedException()
        check(before.uid == me())
        val saved = before.copy(
            countries = post.countries, days = post.days, km = post.km, startMonth = post.startMonth, coverFileId = post.coverFileId,
            photoFileIds = post.photoFileIds, folderId = before.folderId ?: post.folderId, updatedAt = clock(),
        )
        posts[post.id] = saved
        if (tripJson != null) trips[post.id] = tripJson
        return saved
    }

    override suspend fun deletePost(post: Post) {
        maybeFail()
        check(post.uid == me())
        posts.remove(post.id)
        trips.remove(post.id)
        likes.remove(post.id)
    }

    override suspend fun liked(postIds: Collection<String>): Map<String, Boolean> = postIds.associateWith { me() in likes[it].orEmpty() }

    override suspend fun setLiked(postId: String, liked: Boolean) {
        maybeFail()
        val uid = me()
        val post = posts[postId] ?: throw CommunityRefusedException()
        check(post.uid != uid)
        val set = likes.getOrPut(postId) { mutableSetOf() }
        val changed = if (liked) set.add(uid) else set.remove(uid)
        if (changed) posts[postId] = post.copy(likes = post.likes + if (liked) 1 else -1)
    }

    override suspend fun report(postId: String?, target: String, reason: ReportReason) {
        maybeFail()
        reports += Triple(postId, target, reason)
    }

    override suspend fun blocked(): List<BlockedUser> = blocks.values.toList()

    override suspend fun block(user: BlockedUser) {
        val me = me()
        blocks[user.uid] = user
        follows.removeAll { (it.from == me && it.to == user.uid) || (it.from == user.uid && it.to == me) }
    }

    /** One follow: [from] follows [to]; each side's username when it was made, and when. */
    data class Follow(val from: String, val to: String, val fromName: String, val toName: String, val at: Long)

    val follows = ArrayList<Follow>()

    /** People who blocked the signed-in user (only the rules can see that). */
    val blockedBy = HashSet<String>()

    /** How the follow calls went: their names, in order ("isFollowing", "followCounts", "following", "postsOf"). */
    val calls = ArrayList<String>()

    /** Every postsOf query's uids. */
    val postsOfCalls = ArrayList<List<String>>()

    /** Writes take this long (virtual time in runTest): a slow connection. */
    var writeDelayMs = 0L
    private var tick = 0L

    override suspend fun isFollowing(uid: String): Boolean {
        calls += "isFollowing"
        maybeFail()
        val me = me()
        return follows.any { it.from == me && it.to == uid }
    }

    override suspend fun follow(uid: String, username: String, myUsername: String) {
        if (writeDelayMs > 0) kotlinx.coroutines.delay(writeDelayMs)
        maybeFail()
        val me = me()
        // What the rules refuse: yourself, across a block, usernames that aren't the profiles' own.
        if (uid == me || uid in blocks || uid in blockedBy) throw CommunityRefusedException()
        if (profiles[uid]?.username != username || profiles[me]?.username != myUsername) throw CommunityRefusedException()
        follows.removeAll { it.from == me && it.to == uid }
        follows += Follow(me, uid, myUsername, username, clock() + ++tick)
    }

    override suspend fun unfollow(uid: String) {
        if (writeDelayMs > 0) kotlinx.coroutines.delay(writeDelayMs)
        maybeFail()
        val me = me()
        follows.removeAll { it.from == me && it.to == uid }
    }

    override suspend fun followCounts(uid: String): FollowCounts {
        calls += "followCounts"
        maybeFail()
        return FollowCounts(follows.count { it.to == uid }, follows.count { it.from == uid })
    }

    override suspend fun followers(uid: String, after: Any?, limit: Int): Page<FollowLink> {
        calls += "followers"
        maybeFail()
        return page(follows.filter { it.to == uid }.sortedByDescending { it.at }.map { FollowLink(it.from, it.fromName, it.at) }, after, limit)
    }

    override suspend fun following(uid: String, after: Any?, limit: Int): Page<FollowLink> {
        calls += "following"
        maybeFail()
        return page(follows.filter { it.from == uid }.sortedByDescending { it.at }.map { FollowLink(it.to, it.toName, it.at) }, after, limit)
    }

    override suspend fun postsOf(uids: List<String>, after: Any?, limit: Int): Page<Post> {
        calls += "postsOf"
        maybeFail()
        require(uids.size in 1..Follows.IN_LIMIT)
        postsOfCalls += uids
        return page(posts.values.filter { it.uid in uids }.sortedWith(FollowingFeed.order), after, limit)
    }
    override suspend fun unblock(uid: String) { blocks.remove(uid) }

    override suspend fun deleteAll(): List<String> {
        maybeFail()
        val uid = me()
        likes.forEach { (postId, set) -> if (set.remove(uid)) posts[postId]?.let { posts[postId] = it.copy(likes = it.likes - 1) } }
        blocks.clear()
        follows.removeAll { it.from == uid || it.to == uid }
        val mine = posts.values.filter { it.uid == uid }
        mine.forEach { posts.remove(it.id); trips.remove(it.id); likes.remove(it.id) }
        profiles.remove(uid)?.let { usernames.remove(it.username) }
        return mine.mapNotNull { it.folderId }
    }
}
