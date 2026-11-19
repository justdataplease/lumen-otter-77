package com.justdataplease.boringtravel.ui.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.community.CommunityLimits
import com.justdataplease.boringtravel.community.FollowingFeed
import com.justdataplease.boringtravel.community.Follows
import com.justdataplease.boringtravel.community.Page
import com.justdataplease.boringtravel.community.Post
import com.justdataplease.boringtravel.community.PostText
import com.justdataplease.boringtravel.community.TopRange
import com.justdataplease.boringtravel.data.repo.Stamps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One paged list of posts: what is shown, where the next page starts, and how loading went. */
data class FeedState(
    val posts: List<Post> = emptyList(),
    val next: Any? = null,
    /** True once the first page answered (or failed). */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val end: Boolean = false,
    /** The first page came from the phone's copy (no connection). */
    val offline: Boolean = false,
    val error: String? = null,
)

/** Someone the Following feed's empty state suggests: a poster of a most liked trip, with that post. */
data class Suggestion(val uid: String, val username: String, val avatar: String?, val post: Post)

/**
 * The Community tab: Latest (newest first), Following (the posts of the people the viewer follows, newest first) and
 * Top (most liked this month or of all time), 20 posts a page. Lists reload after a post, a delete or a block, and when
 * someone else signs in; Following also after a follow on this phone.
 */
@OptIn(FlowPreview::class)
class FeedViewModel(private val graph: AppGraph) : ViewModel() {
    private val community = graph.community

    /** [LATEST], [FOLLOWING] or [TOP]. */
    val tab = MutableStateFlow(LATEST)
    private val _range = MutableStateFlow(TopRange.Month)
    val range: StateFlow<TopRange> = _range.asStateFlow()

    /** The month "This month" means ("2026-11", UTC: the month the server stamps on posts). */
    val month: String get() = PostText.monthKey(Stamps.now())

    private inner class Pager(private val fetch: suspend (Any?) -> Page<Post>) {
        val state = MutableStateFlow(FeedState())
        private var job: Job? = null

        fun refresh(pull: Boolean = false) {
            job?.cancel()
            state.update { it.copy(loading = !pull, refreshing = pull, error = null) }
            job = viewModelScope.launch {
                try {
                    val page = fetch(null)
                    state.value = FeedState(page.items, page.next, loaded = true, end = page.next == null, offline = page.fromCache)
                    community.seedLikes(page.items)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state.update { it.copy(loaded = true, loading = false, refreshing = false, error = communityMessage(e)) }
                }
            }
        }

        fun more() {
            val s = state.value
            if (!s.loaded || s.end || s.next == null || s.loading || job?.isActive == true) return
            state.update { it.copy(loading = true, error = null) }
            job = viewModelScope.launch {
                try {
                    val page = fetch(s.next)
                    state.update { it.copy(posts = (it.posts + page.items).distinctBy { p -> p.id }, next = page.next, end = page.next == null, loading = false) }
                    community.seedLikes(page.items)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state.update { it.copy(loading = false, error = communityMessage(e)) }
                }
            }
        }

        fun ensure() {
            if (!state.value.loaded && job?.isActive != true) refresh()
        }

        fun reset() {
            job?.cancel()
            state.value = FeedState()
        }
    }

    private val latestPager = Pager { after -> community.repo.latest(after) }
    private val followingPager = Pager { after -> followingPage(after) }
    private val topPager = Pager { after -> community.repo.top(_range.value, month, after) }

    val latest: StateFlow<FeedState> = latestPager.state.asStateFlow()
    val following: StateFlow<FeedState> = followingPager.state.asStateFlow()
    val top: StateFlow<FeedState> = topPager.state.asStateFlow()

    private val _followsNobody = MutableStateFlow(false)
    /** The viewer follows nobody yet (the Following tab says how to start). */
    val followsNobody: StateFlow<Boolean> = _followsNobody.asStateFlow()
    private val _capped = MutableStateFlow(false)
    /** The viewer follows more people than feed the Following feed (the newest [Follows.FEED_CAP] do). */
    val capped: StateFlow<Boolean> = _capped.asStateFlow()
    private val _suggestions = MutableStateFlow<List<Suggestion>?>(null)
    /** People worth following for the Following tab's empty state; null until looked up. */
    val suggestions: StateFlow<List<Suggestion>?> = _suggestions.asStateFlow()
    private var suggesting: Job? = null

    /** The next Following page reads who the viewer follows from the server again (a pull to refresh). */
    private var freshFollows = false

    /** A follow changed while another tab was on show: Following reloads when it is picked. */
    private var followingStale = false

    init {
        viewModelScope.launch {
            community.user.map { it?.uid }.distinctUntilChanged().collect { uid ->
                latestPager.reset()
                followingPager.reset()
                topPager.reset()
                suggesting?.cancel()
                _suggestions.value = null
                _followsNobody.value = false
                _capped.value = false
                if (uid != null) load()
            }
        }
        viewModelScope.launch {
            community.changes.drop(1).collect {
                if (community.user.value != null) {
                    latestPager.refresh()
                    if (followingPager.state.value.loaded) followingPager.refresh()
                    if (topPager.state.value.loaded) topPager.refresh()
                }
            }
        }
        // Several Follow taps in a row (the suggestions) reload once.
        viewModelScope.launch {
            community.follows.changes.drop(1).debounce(FOLLOW_SETTLE_MS).collect {
                if (community.user.value == null) return@collect
                if (tab.value == FOLLOWING) followingPager.refresh() else followingStale = true
            }
        }
    }

    /**
     * A page of the Following feed: the first one reads who the viewer follows (once, then kept by [Community.follows])
     * and starts the merged queries; the next ones carry on from [after].
     */
    private suspend fun followingPage(after: Any?): Page<Post> {
        val cursor = after as? FollowingFeed.Cursor ?: run {
            val fresh = freshFollows
            freshFollows = false
            val mine = community.follows.mine(fresh)
            _followsNobody.value = mine.isEmpty()
            _capped.value = Follows.capped(mine)
            FollowingFeed.start(Follows.feedGroups(mine, community.blocked.value.mapTo(HashSet()) { it.uid }))
        }
        return FollowingFeed.page(cursor, CommunityLimits.PAGE) { uids, from, limit -> community.repo.postsOf(uids, from, limit) }
    }

    /**
     * People worth following, for the Following tab's empty state: the posters of the most liked trips of all time
     * (the leaderboard's first page), without the viewer, people they blocked and people they follow already.
     */
    fun suggest() {
        if (_suggestions.value != null || suggesting?.isActive == true || community.user.value == null) return
        suggesting = viewModelScope.launch {
            _suggestions.value = try {
                val top = community.repo.top(TopRange.AllTime, month, null).items
                val following = community.follows.mine().mapTo(HashSet()) { it.uid }
                val hidden = community.blocked.value.mapTo(HashSet()) { it.uid }
                val picks = Follows.suggestions(top, community.user.value?.uid, hidden, following)
                val profiles = community.follows.profiles(picks.map { it.uid })
                // A profile known to be gone is left out; one that couldn't be read shows the name on the post.
                picks.filter { !(it.uid in profiles && profiles[it.uid] == null) }.map { p ->
                    val profile = profiles[p.uid]
                    Suggestion(p.uid, profile?.username ?: p.username, profile?.avatar, p)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** Loads what the tab on show needs (once). */
    fun load() {
        if (community.user.value == null) return
        when (tab.value) {
            LATEST -> latestPager.ensure()
            FOLLOWING -> if (followingStale) {
                followingStale = false
                followingPager.refresh()
            } else {
                followingPager.ensure()
            }
            else -> topPager.ensure()
        }
    }

    private fun pager(): Pager = when (tab.value) {
        LATEST -> latestPager
        FOLLOWING -> followingPager
        else -> topPager
    }

    fun pick(tabIndex: Int) {
        tab.value = tabIndex
        load()
    }

    fun pickRange(r: TopRange) {
        if (_range.value == r) return
        _range.value = r
        topPager.reset()
        topPager.refresh()
    }

    fun refresh(pull: Boolean) {
        if (tab.value == FOLLOWING) {
            freshFollows = pull
            followingStale = false
            if (pull) {
                suggesting?.cancel()
                _suggestions.value = null
            }
        }
        pager().refresh(pull)
        if (pull) viewModelScope.launch { community.refreshMe() }
    }

    fun more() {
        pager().more()
    }

    companion object {
        const val LATEST = 0
        const val FOLLOWING = 1
        const val TOP = 2

        /** Follow taps this close together reload the Following feed once. */
        private const val FOLLOW_SETTLE_MS = 1_200L
    }
}
