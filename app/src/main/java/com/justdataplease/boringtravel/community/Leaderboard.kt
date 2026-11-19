package com.justdataplease.boringtravel.community

/**
 * The leaderboard ("most liked trips"): posts by likes, most first; equal likes keep the one posted first ahead (it got
 * there first), then the id, so the order never flickers. Ranks are shared on a tie ("1, 2, 2, 4") and the first three
 * ranks get a medal. Posts of people the viewer blocked are left out before ranking. Pure (LeaderboardTest).
 *
 * There is no "top travellers" list: summing each person's likes for the month would need every post of the month,
 * not the top page the leaderboard reads, so it could not be both cheap and right.
 */
object Leaderboard {
    enum class Medal { Gold, Silver, Bronze }

    data class Row(val rank: Int, val post: Post) {
        val medal: Medal? get() = when (rank) {
            1 -> Medal.Gold
            2 -> Medal.Silver
            3 -> Medal.Bronze
            else -> null
        }
    }

    val order: Comparator<Post> = compareByDescending<Post> { it.likes }.thenBy { it.createdAt }.thenBy { it.id }

    /** [posts] (pages put together, maybe with repeats) ranked, without [hidden] people's posts. */
    fun rank(posts: List<Post>, hidden: Set<String> = emptySet()): List<Row> {
        val list = posts.distinctBy { it.id }.filter { it.uid !in hidden }.sortedWith(order)
        val rows = ArrayList<Row>(list.size)
        list.forEachIndexed { i, p ->
            val previous = rows.lastOrNull()
            val rank = if (previous != null && previous.post.likes == p.likes) previous.rank else i + 1
            rows += Row(rank, p)
        }
        return rows
    }
}

/** One post's heart as the viewer sees it: [liked] by them, [count] in all; [pending] while the change is on its way. */
data class LikeState(val liked: Boolean, val count: Int, val pending: Boolean = false) {
    /** The tap: the heart and the count change at once (optimistic); the server follows. */
    fun toggled(): LikeState = if (liked) LikeState(false, (count - 1).coerceAtLeast(0), true) else LikeState(true, count + 1, true)
}

/**
 * Hearts across the screens (feed, leaderboard, post page, profile), so a like on the post page shows in the feed
 * too. Server answers are folded in only for posts with no change on its way. Whether the viewer liked a post is
 * "known" only once the server said so: a failed look-up stays unknown and is asked again. A refused change takes the
 * server's state when it can be read, else goes back to what was there before the tap. Pure (LeaderboardTest); the
 * screens hold it through Community.likes.
 */
class LikeBook {
    private val states = HashMap<String, LikeState>()
    private val before = HashMap<String, LikeState>()
    private val likedKnown = HashSet<String>()

    fun snapshot(): Map<String, LikeState> = HashMap(states)

    /** What the server says: [post]'s count, and whether the viewer liked it ([liked] null = not known: kept as shown). */
    fun seed(post: Post, liked: Boolean?) {
        val current = states[post.id]
        if (current?.pending == true) return
        if (liked != null) likedKnown += post.id
        states[post.id] = LikeState(liked ?: current?.liked ?: false, post.likes.coerceAtLeast(0))
    }

    /** Whether the server told the viewer's like on [postId] (else it is looked up again). */
    fun known(postId: String): Boolean = postId in likedKnown

    /** The tap: returns the new state (its [LikeState.liked] is what to send), or null while a change is on its way. */
    fun toggle(postId: String, fallback: LikeState): LikeState? {
        val current = states[postId] ?: fallback
        if (current.pending) return null
        before[postId] = current
        return current.toggled().also { states[postId] = it }
    }

    /** The change went: [onServer] when the server took it (the like is known then), else queued until online. */
    fun confirm(postId: String, onServer: Boolean = true) {
        states[postId]?.let { states[postId] = it.copy(pending = false) }
        before.remove(postId)
        if (onServer) likedKnown += postId
    }

    /** The change was refused and the server's [state] was read again: that is what shows, and it is known. */
    fun adopt(postId: String, state: LikeState) {
        before.remove(postId)
        states[postId] = state.copy(count = state.count.coerceAtLeast(0), pending = false)
        likedKnown += postId
    }

    /** The change failed and the server couldn't be read: back to how it was, and asked again next time. */
    fun fail(postId: String) {
        likedKnown -= postId
        val was = before.remove(postId) ?: return
        states[postId] = was
    }

    fun forget(postId: String) {
        states.remove(postId)
        before.remove(postId)
        likedKnown -= postId
    }

    fun clear() {
        states.clear()
        before.clear()
        likedKnown.clear()
    }
}
