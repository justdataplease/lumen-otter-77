package com.justdataplease.boringtravel.community

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The Following feed: posts of the people the viewer follows, newest first. Firestore's `in` takes 30 uids, so each
 * group of 30 ([Follows.feedGroups]) is its own query, and their pages are merged by time. A group keeps the posts it
 * read but didn't show yet and is read again only when it holds less than a page, so taking a page never skips an
 * older post a group hasn't read yet. Pure apart from the query it is given (FollowingFeedTest).
 */
object FollowingFeed {
    /** One query's people, the posts read and not shown yet, where its next page starts, and whether it has more. */
    data class Group(val uids: List<String>, val waiting: List<Post> = emptyList(), val next: Any? = null, val ended: Boolean = false)

    /** Where the feed got to: [Page.next] of its pages. Never changed in place, so a failed page can simply be asked again. */
    data class Cursor(val groups: List<Group>)

    /** Newest first; the id keeps equal times in one order. */
    val order: Comparator<Post> = compareByDescending<Post> { it.createdAt }.thenBy { it.id }

    fun start(groups: List<List<String>>): Cursor = Cursor(groups.filter { it.isNotEmpty() }.map { Group(it) })

    /**
     * The next [limit] posts after [cursor]. [fetch] reads a group's posts (its uids, where to start, how many), the
     * groups that need more at the same time.
     */
    suspend fun page(
        cursor: Cursor,
        limit: Int,
        fetch: suspend (uids: List<String>, after: Any?, limit: Int) -> Page<Post>,
    ): Page<Post> {
        val read = coroutineScope {
            cursor.groups.map { g ->
                async {
                    if (g.ended || g.waiting.size >= limit) {
                        g to false
                    } else {
                        val p = fetch(g.uids, g.next, limit)
                        g.copy(waiting = g.waiting + p.items, next = p.next, ended = p.next == null) to p.fromCache
                    }
                }
            }.awaitAll()
        }
        val filled = read.map { it.first }
        val fromCache = read.any { it.second }
        val shown = filled.flatMap { it.waiting }.distinctBy { it.id }.sortedWith(order).take(limit)
        val ids = shown.mapTo(HashSet()) { it.id }
        val rest = filled.map { g -> g.copy(waiting = g.waiting.filter { it.id !in ids }) }
        val more = rest.any { !it.ended || it.waiting.isNotEmpty() }
        return Page(shown, if (more) Cursor(rest) else null, fromCache)
    }
}
