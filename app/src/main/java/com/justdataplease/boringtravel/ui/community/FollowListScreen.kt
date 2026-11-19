package com.justdataplease.boringtravel.ui.community

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.community.FollowLink
import com.justdataplease.boringtravel.community.Page
import com.justdataplease.boringtravel.community.PostText
import com.justdataplease.boringtravel.community.Profile
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtSegmented
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.EmptyState
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One paged list of people: who is shown, where the next page starts, and how loading went. */
data class PeopleState(
    val people: List<FollowLink> = emptyList(),
    val next: Any? = null,
    /** True once the first page answered (or failed). */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val end: Boolean = false,
    val error: String? = null,
)

/**
 * Someone's followers and following, 30 a page, newest first. Each page's profiles are read with it (kept a few
 * minutes), so the rows show today's username and picture, not the ones stored when the follow was made.
 */
class FollowListViewModel(private val graph: AppGraph, private val uid: String) : ViewModel() {
    private val community = graph.community

    private val _owner = MutableStateFlow<Profile?>(null)
    /** Whose lists these are (the title). */
    val owner: StateFlow<Profile?> = _owner.asStateFlow()

    private val _profiles = MutableStateFlow<Map<String, Profile?>>(emptyMap())
    /** The listed people's profiles by uid; null = gone (left out), missing = not read (the stored username shows). */
    val profiles: StateFlow<Map<String, Profile?>> = _profiles.asStateFlow()

    private inner class Lister(private val fetch: suspend (Any?) -> Page<FollowLink>) {
        val state = MutableStateFlow(PeopleState())
        private var job: Job? = null

        fun load(more: Boolean) {
            val s = state.value
            if (job?.isActive == true) return
            if (more && (!s.loaded || s.end || s.next == null)) return
            if (!more && s.loaded && s.error == null) return
            state.update { it.copy(loading = true, error = null) }
            job = viewModelScope.launch {
                try {
                    val page = fetch(if (more) s.next else null)
                    val people = page.items
                    state.update {
                        PeopleState(
                            (if (more) it.people + people else people).distinctBy { p -> p.uid }, page.next,
                            loaded = true, end = page.next == null,
                        )
                    }
                    val read = community.follows.profiles(people.map { it.uid })
                    _profiles.update { it + read }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state.update { it.copy(loaded = true, loading = false, error = communityMessage(e)) }
                }
            }
        }
    }

    private val followers = Lister { after -> community.repo.followers(uid, after) }
    private val following = Lister { after -> community.repo.following(uid, after) }

    fun state(followersList: Boolean): StateFlow<PeopleState> = (if (followersList) followers else following).state.asStateFlow()

    init {
        // Usually known already (the profile page just read it): no read then.
        viewModelScope.launch { _owner.value = community.follows.profiles(listOf(uid))[uid] }
    }

    /** The first page of a list (once; again after it failed). */
    fun load(followersList: Boolean) = (if (followersList) followers else following).load(more = false)

    fun more(followersList: Boolean) = (if (followersList) followers else following).load(more = true)
}

/**
 * Followers · Following of someone (the viewer's own too): avatar, @username and name, newest first; a tap opens that
 * profile. People the viewer blocked and profiles that are gone are left out.
 */
@Composable
fun FollowListScreen(graph: AppGraph, actions: AppActions, uid: String, followers: Boolean) {
    val community = graph.community
    val user by community.user.collectAsStateWithLifecycle()
    if (!community.available || user == null) {
        BtScreen {
            Column(Modifier.fillMaxSize()) {
                BtTopBar(if (followers) "Followers" else "Following", onBack = actions::back)
                SignInCard(Modifier.padding(20.dp))
            }
        }
        return
    }
    val vm: FollowListViewModel = viewModel(key = "follows-$uid") { FollowListViewModel(graph, uid) }
    var tab by rememberSaveable { mutableIntStateOf(if (followers) 0 else 1) }
    val showFollowers = tab == 0
    val owner by vm.owner.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val blocked by community.blocked.collectAsStateWithLifecycle()
    val state by remember(showFollowers) { vm.state(showFollowers) }.collectAsStateWithLifecycle()
    val hidden = remember(blocked) { blocked.mapTo(HashSet()) { it.uid } }
    val shown = remember(state.people, profiles, hidden) {
        state.people.filter { it.uid !in hidden && !(it.uid in profiles && profiles[it.uid] == null) }
    }
    val mine = user?.uid == uid
    LaunchedEffect(showFollowers) { vm.load(showFollowers) }
    val list = rememberLazyListState()
    LaunchedEffect(list, showFollowers) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
            .distinctUntilChanged()
            .filter { (last, total) -> last != null && total > 0 && last >= total - 6 }
            .collect { vm.more(showFollowers) }
    }

    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar(owner?.let { PostText.handle(it.username) } ?: "", onBack = actions::back)
            BtSegmented(
                listOf("Followers", "Following"), tab, onSelect = { tab = it },
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                when {
                    !state.loaded -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Bt.colors.muted, strokeWidth = 2.dp)
                        }
                    }
                    state.people.isEmpty() && state.error != null -> item(key = "error") {
                        EmptyState("Couldn't load", state.error.orEmpty()) { BtButton("Try again", onClick = { vm.load(showFollowers) }) }
                    }
                    shown.isEmpty() && state.end -> item(key = "empty") {
                        when {
                            showFollowers && mine -> EmptyState("No followers yet", "People who follow you show here.")
                            showFollowers -> EmptyState("No followers yet", "People who follow them show here.")
                            mine -> EmptyState("Not following anyone", "Follow travellers from their profile. Their posts show under Following.")
                            else -> EmptyState("Not following anyone", "The people they follow show here.")
                        }
                    }
                    else -> items(shown, key = { "person:${it.uid}" }) { link ->
                        val profile = profiles[link.uid]
                        PersonRow(
                            profile?.username ?: link.username, profile?.avatar, profile?.name,
                            onOpen = { actions.communityProfile(link.uid) },
                        )
                    }
                }
                if (state.loaded && state.people.isNotEmpty()) item(key = "foot") {
                    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                        when {
                            state.loading -> CircularProgressIndicator(Modifier.size(24.dp), color = Bt.colors.muted, strokeWidth = 2.dp)
                            state.error != null -> BtGhostButton("Couldn't load more · Try again", onClick = { vm.more(showFollowers) })
                        }
                    }
                }
            }
        }
    }
}
