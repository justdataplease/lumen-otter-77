package com.justdataplease.boringtravel.community

/*
 * Following (owner, 2026-11-12: "make it possible to follow someone and have following and followers").
 *
 * Firestore keeps each follow twice, written together by the follower: users/{me}/following/{them} and
 * users/{them}/followers/{me}, each {username, at} (the other side's username then, the server's time). The counts are
 * Firestore's count queries on those lists, never a number a phone keeps (it could be faked). Everything in this file
 * is pure (FollowsTest).
 */

/** Someone on a followers or following list: their uid, their username when the follow was made, and when. */
data class FollowLink(val uid: String, val username: String, val at: Long = 0)

/** How many follow a profile and how many it follows. */
data class FollowCounts(val followers: Int, val following: Int)

/**
 * Counts as the server gave them, when ([at], epoch ms), and whether the viewer followed that profile then
 * ([followingThen], null when it wasn't known): a later tap moves the shown number by one without asking again.
 */
data class CountedFollows(val counts: FollowCounts, val at: Long, val followingThen: Boolean? = null)

object Follows {
    /** People per page of a followers or following list. */
    const val PAGE = 30

    /** Firestore's `in` takes at most 30 values: the Following feed asks for posts 30 people at a time. */
    const val IN_LIMIT = 30

    /** The newest follows that feed the Following feed: five queries a page at most. */
    const val FEED_CAP = 150

    /** How long counts read from the server are shown again without asking. */
    const val COUNT_TTL_MS = 60_000L

    /** People the Following feed's empty state suggests. */
    const val SUGGESTIONS = 5

    /** Whether [me] may follow [them]: not themselves, nobody they blocked (the rules refuse the rest). */
    fun canFollow(me: String?, them: String, blocked: Set<String>): Boolean = me != null && me != them && them !in blocked

    /** "followers" or "follower" for [n], as the profile's totals label it. */
    fun followersLabel(n: Int): String = if (n == 1) "follower" else "followers"

    /** The followers to show: the server's count, one more or less when the viewer's follow changed since it was read. */
    fun shownFollowers(counted: CountedFollows, followingNow: Boolean?): Int {
        val then = counted.followingThen
        val n = counted.counts.followers
        return when {
            then == null || followingNow == null || then == followingNow -> n
            followingNow -> n + 1
            else -> (n - 1).coerceAtLeast(0)
        }
    }

    /**
     * The uids the Following feed reads, in groups of [IN_LIMIT]: the newest [FEED_CAP] follows ([following] is newest
     * first), people the viewer blocked left out.
     */
    fun feedGroups(following: List<FollowLink>, hidden: Set<String> = emptySet()): List<List<String>> =
        following.take(FEED_CAP).map { it.uid }.filter { it !in hidden }.distinct().chunked(IN_LIMIT)

    /** True when the viewer follows more people than feed the Following feed ([following] read with one extra). */
    fun capped(following: List<FollowLink>): Boolean = following.size > FEED_CAP

    /**
     * People worth following, from the leaderboard's posts ([top], most liked first): each poster once, at their most
     * liked post, without the viewer, people they blocked and people they follow already.
     */
    fun suggestions(top: List<Post>, me: String?, hidden: Set<String>, following: Set<String>, max: Int = SUGGESTIONS): List<Post> =
        top.sortedWith(Leaderboard.order).distinctBy { it.uid }
            .filter { it.uid != me && it.uid !in hidden && it.uid !in following }
            .take(max)
}

/** The viewer's follow of one person: [following] them; [pending] while the change is on its way; [problem] why the last tap didn't go. */
data class FollowState(val following: Boolean, val pending: Boolean = false, val problem: String? = null)

/**
 * Follows across the screens (profile, lists, the feed's suggestions), the way LikeBook keeps hearts: a tap changes the
 * button at once, server answers never undo a change on its way, and a refused change goes back to what the server
 * says (else to what was there before the tap) with a line to show. Pure (FollowsTest); [FollowKeeper] holds it.
 */
class FollowBook {
    private val states = HashMap<String, FollowState>()
    private val before = HashMap<String, FollowState>()

    fun snapshot(): Map<String, FollowState> = HashMap(states)

    /** Whether the server told if the viewer follows [uid]. */
    fun known(uid: String): Boolean = uid in states

    /** What the server says; a change on its way is kept. */
    fun seed(uid: String, following: Boolean) {
        if (states[uid]?.pending == true) return
        states[uid] = FollowState(following)
    }

    /**
     * The tap: returns the new state (its [FollowState.following] is what to send), or null while a change is on its
     * way. Not known yet, it starts from [fallback] (following again is harmless: the server keeps one follow).
     */
    fun toggle(uid: String, fallback: Boolean = false): FollowState? {
        val current = states[uid] ?: FollowState(fallback)
        if (current.pending) return null
        before[uid] = current.copy(problem = null)
        return FollowState(!current.following, pending = true).also { states[uid] = it }
    }

    /** The change went (or waits on the phone for a connection: Firestore sends it then). */
    fun confirm(uid: String) {
        states[uid]?.let { states[uid] = it.copy(pending = false) }
        before.remove(uid)
    }

    /**
     * The change was refused. [server] is the follow as the server has it now (null: couldn't be read): when it is what
     * the tap wanted (another phone did it), nothing is wrong; else it shows, or what was there before, with [problem].
     */
    fun refused(uid: String, server: Boolean?, problem: String) {
        val wanted = states[uid]?.following
        val was = before.remove(uid)
        states[uid] = when {
            server != null && server == wanted -> FollowState(server)
            server != null -> FollowState(server, problem = problem)
            else -> FollowState(was?.following ?: (wanted?.not() ?: false), problem = problem)
        }
    }

    /** The follow between the viewer and [uid] ended (a block): not following, whatever was on its way. */
    fun ended(uid: String) {
        before.remove(uid)
        states[uid] = FollowState(false)
    }

    /** Not known any more: looked up again. */
    fun forget(uid: String) {
        states.remove(uid)
        before.remove(uid)
    }

    fun clear() {
        states.clear()
        before.clear()
    }
}
