package com.justdataplease.boringtravel.ui.community

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.community.CountedFollows
import com.justdataplease.boringtravel.community.FollowState
import com.justdataplease.boringtravel.community.Follows
import com.justdataplease.boringtravel.community.Post
import com.justdataplease.boringtravel.community.PostKind
import com.justdataplease.boringtravel.community.PostText
import com.justdataplease.boringtravel.community.Profile
import com.justdataplease.boringtravel.community.ReportReason
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.EmptyState
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.me.ConfirmDialog
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProfileUi(
    val profile: Profile? = null,
    val posts: List<Post> = emptyList(),
    val loading: Boolean = true,
    /** No profile by that id (deleted, or never made). */
    val gone: Boolean = false,
    val error: String? = null,
    /** Followers and following as last counted (null: not yet, or offline). */
    val counts: CountedFollows? = null,
    /** True once the viewer's follow of them was looked up (or the look-up failed: Follow then starts from not following). */
    val followKnown: Boolean = false,
) {
    /** Likes on their posts (the newest [PROFILE_POSTS]). */
    val likes: Int get() = posts.sumOf { it.likes }
}

/** Posts a profile page reads; the totals count these. */
internal const val PROFILE_POSTS = 100

/** Someone's profile: who they are, their posts (newest first), the likes those got, followers and following. */
class ProfileViewModel(private val graph: AppGraph, private val uid: String) : ViewModel() {
    private val community = graph.community
    private val _state = MutableStateFlow(ProfileUi())
    val state: StateFlow<ProfileUi> = _state.asStateFlow()

    init {
        load()
        // Their posts change after a post or a delete on this phone (the viewer's own profile).
        viewModelScope.launch { community.changes.drop(1).collect { load() } }
        // The viewer's own following count after a follow elsewhere; counts kept a minute cost nothing.
        viewModelScope.launch { community.follows.changes.drop(1).collect { loadFollows() } }
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        loadFollows()
        viewModelScope.launch {
            try {
                val profile = community.repo.profile(uid)
                if (profile == null) {
                    _state.value = ProfileUi(loading = false, gone = true)
                    return@launch
                }
                community.follows.saw(profile)
                val posts = community.repo.postsBy(uid, PROFILE_POSTS)
                _state.update { ProfileUi(profile, posts, loading = false, counts = it.counts, followKnown = it.followKnown) }
                community.seedLikes(posts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = communityMessage(e)) }
            }
        }
    }

    /** Whether the viewer follows them (looked up once), then the counts (read once a minute at most). */
    private fun loadFollows() {
        viewModelScope.launch {
            community.follows.seed(uid)
            val counted = community.follows.counts(uid)
            _state.update { it.copy(counts = counted ?: it.counts, followKnown = true) }
        }
    }

    /** True when sent, false while it waits for a connection. */
    suspend fun report(reason: ReportReason): Boolean = community.report(null, uid, reason)

    /** True when the server has it, false while it waits for a connection (they are hidden at once either way). */
    suspend fun block(): Boolean {
        val p = _state.value.profile ?: return true
        return community.block(p.uid, p.username)
    }

    suspend fun unblock() = community.unblock(uid)
}

/**
 * A traveller's public page: avatar, @username, name and bio, their totals (posts, likes received, followers and
 * following, which open their lists) and their posts. Anyone else's has Follow, and Report and Block in the ⋮; the
 * viewer's own has Edit and Delete profile.
 */
@Composable
fun ProfileScreen(graph: AppGraph, actions: AppActions, uid: String) {
    val community = graph.community
    val user by community.user.collectAsStateWithLifecycle()
    if (!community.available || user == null) {
        BtScreen {
            Column(Modifier.fillMaxSize()) {
                BtTopBar("Profile", onBack = actions::back)
                SignInCard(Modifier.padding(20.dp))
            }
        }
        return
    }
    val vm: ProfileViewModel = viewModel(key = "profile-$uid") { ProfileViewModel(graph, uid) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val blocked by community.blocked.collectAsStateWithLifecycle()
    val likes by community.likes.collectAsStateWithLifecycle()
    val follows by community.follows.states.collectAsStateWithLifecycle()
    val profileKnown by community.profileKnown.collectAsStateWithLifecycle()
    val followTap = rememberFollowTap(actions)
    val mine = user?.uid == uid
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reporting by rememberSaveable { mutableStateOf(false) }
    var askBlock by rememberSaveable { mutableStateOf(false) }
    var askDelete by rememberSaveable { mutableStateOf(false) }
    val profile = ui.profile
    val isBlocked = blocked.any { it.uid == uid }
    // The viewer's own profile follows edits made on the edit screen.
    val myProfile by community.profile.collectAsStateWithLifecycle()
    LaunchedEffect(myProfile) { if (mine && myProfile != null && myProfile != profile) vm.load() }

    BtScreen {
        val menu = when {
            profile == null -> emptyList()
            mine -> listOf(
                MenuLine("Edit profile", BtIcons.Edit) { actions.editCommunityProfile() },
                MenuLine("Delete profile", BtIcons.Delete, danger = true) { askDelete = true },
            )
            else -> listOf(
                MenuLine("Report ${PostText.handle(profile.username)}", BtIcons.Flag) { reporting = true },
                MenuLine(if (isBlocked) "Unblock" else "Block ${PostText.handle(profile.username)}", BtIcons.Block, danger = !isBlocked) {
                    if (isBlocked) scope.launch { runCatching { vm.unblock() } } else askBlock = true
                },
            )
        }
        Column(Modifier.fillMaxSize()) {
        BtTopBar("", onBack = actions::back) {
            if (menu.isNotEmpty()) MoreMenu(menu, description = "Profile options")
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                profile == null && ui.loading -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = Bt.colors.muted, strokeWidth = 2.dp)
                    }
                }
                profile == null -> item(key = "gone", span = { GridItemSpan(maxLineSpan) }) {
                    if (ui.gone) {
                        EmptyState("No profile here", if (mine) "Make one to post." else "It was deleted.") {
                            if (mine) BtButton("Make my profile", onClick = actions::editCommunityProfile)
                        }
                    } else {
                        EmptyState("Couldn't load", ui.error ?: "Try again.") { BtButton("Try again", onClick = vm::load) }
                    }
                }
                isBlocked && !mine -> item(key = "blocked", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState("You blocked ${PostText.handle(profile.username)}", "Their posts and profile are hidden for you.") {
                        BtGhostButton("Unblock", onClick = { scope.launch { runCatching { vm.unblock() } } })
                    }
                }
                else -> {
                    item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
                        ProfileHead(
                            profile, ui, mine, actions, follow = follows[uid], followReady = ui.followKnown && profileKnown,
                            onFollow = { followTap(profile.uid, profile.username) },
                        )
                    }
                    if (ui.posts.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                "No posts yet",
                                if (mine) "Post a trip, a clip or photos from a trip's ⋮ menu." else "${PostText.handle(profile.username)} hasn't posted yet.",
                            )
                        }
                    } else {
                        item(key = "posts-label", span = { GridItemSpan(maxLineSpan) }) { Eyebrow("Posts", Modifier.padding(top = 8.dp)) }
                        items(ui.posts, key = { "post:${it.id}" }) { post ->
                            ProfileTile(post, likes[post.id]?.count ?: post.likes) { actions.communityPost(post.id) }
                        }
                    }
                }
            }
        }
        }
    }
    if (reporting && profile != null) ReportSheet(PostText.handle(profile.username), send = { vm.report(it) }, onDone = { reporting = false })
    if (askBlock && profile != null) {
        ConfirmDialog(
            title = "Block ${PostText.handle(profile.username)}?",
            body = BLOCK_BODY,
            confirm = "Block",
            onConfirm = {
                askBlock = false
                scope.launch {
                    try {
                        if (!vm.block()) Toast.makeText(context, QUEUED, Toast.LENGTH_SHORT).show()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(context, communityMessage(e, "Couldn't block. Try again."), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { askBlock = false },
        )
    }
    if (askDelete) {
        DeleteProfileDialog(
            onDeleted = {
                askDelete = false
                actions.back()
            },
            onDismiss = { askDelete = false },
        )
    }
}

/**
 * The avatar, @username in the display face, the name, the bio, the totals (posts, likes, followers and following: the
 * last two open their lists), then Follow, or Edit on the viewer's own.
 */
@Composable
private fun ProfileHead(
    profile: Profile,
    ui: ProfileUi,
    mine: Boolean,
    actions: AppActions,
    follow: FollowState?,
    followReady: Boolean,
    onFollow: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(profile.username, profile.avatar, size = 96.dp)
        Text(
            Bt.text.display(PostText.handle(profile.username)), Modifier.padding(top = 12.dp), style = Bt.text.h2,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (profile.name.isNotBlank()) Text(profile.name, Modifier.padding(top = 2.dp), style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (profile.bio.isNotBlank()) {
            Text(profile.bio, Modifier.padding(top = 8.dp, start = 12.dp, end = 12.dp), style = Bt.text.body, textAlign = TextAlign.Center)
        }
        val counted = ui.counts
        // A Follow tap moves the followers at once; the server is asked again only after a minute.
        val followers = counted?.let { Follows.shownFollowers(it, follow?.following) }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Total(ui.posts.size, if (ui.posts.size == 1) "post" else "posts")
            Total(ui.likes, if (ui.likes == 1) "like" else "likes")
            Total(followers, Follows.followersLabel(followers ?: 0)) { actions.communityFollows(profile.uid, followers = true) }
            Total(counted?.counts?.following, "following") { actions.communityFollows(profile.uid, followers = false) }
        }
        if (mine) {
            BtGhostButton("Edit profile", onClick = actions::editCommunityProfile, icon = BtIcons.Edit, modifier = Modifier.padding(top = 14.dp))
        } else {
            // Like the heart: it never greys out while the server answers (a tap then waits, see FollowBook).
            FollowButton(follow, onClick = onFollow, modifier = Modifier.padding(top = 14.dp).widthIn(min = 160.dp), enabled = followReady)
            FollowProblem(follow, Modifier.padding(top = 6.dp))
        }
    }
}

/** A number and its label; [n] null shows a dash (not counted yet, or offline). [onClick] opens what it counts. */
@Composable
private fun Total(n: Int?, label: String, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Show $label", onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(n?.let(PostText::count) ?: "–", style = Bt.text.title)
        Text(label, style = Bt.text.sub)
    }
}

/** One of their posts: the 4:5 cover with its kind (a trip's plan, a clip, an album), the title and the hearts. */
@Composable
private fun ProfileTile(post: Post, likes: Int, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(Bt.tokens.photo.corner.coerceAtMost(10.dp))
    Column(Modifier.clip(shape).clickable(role = Role.Button, onClickLabel = "Open ${post.title}", onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(shape)) {
            PostCover(post, Modifier.fillMaxSize())
            if (post.kind == PostKind.Clip) PlayMark(Modifier.align(Alignment.Center), size = 40.dp)
            KindTag(post, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Text(post.title, Modifier.padding(top = 6.dp), style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(BtIcons.Heart, null, Modifier.size(14.dp), tint = Bt.colors.muted)
            Text(PostText.count(likes), style = Bt.text.sub)
        }
    }
}

/** What a report, a block or an unblock says when it waits for a connection. */
internal const val QUEUED = "Saved. It goes out when you're online."

/** What blocking someone does, as Block asks it (profile and post). */
internal const val BLOCK_BODY = "You won't see their posts or profile, and neither of you follows the other. They aren't told."

/**
 * Delete profile (the profile page and Me › Community profile): what goes, a wait while it goes, then [onDeleted]. The
 * deleting carries on if the screen closes meanwhile.
 */
@Composable
internal fun DeleteProfileDialog(onDeleted: () -> Unit, onDismiss: () -> Unit) {
    val community = communityOf()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    ConfirmDialog(
        title = "Delete your profile?",
        body = if (busy) "Deleting…" else "Your username, profile, posts, likes and follows are deleted, and your posts' photos leave your Drive. This can't be undone.",
        confirm = if (busy) "Deleting" else "Delete",
        onConfirm = {
            if (busy) return@ConfirmDialog
            busy = true
            scope.launch {
                try {
                    val folders = withContext(NonCancellable) { community.deleteProfile() }
                    Toast.makeText(
                        context,
                        if (folders) "Profile deleted" else "Profile deleted. Some photos stay in your Drive, in BoringTravel/Community.",
                        Toast.LENGTH_LONG,
                    ).show()
                    onDeleted()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(context, communityMessage(e, "Couldn't delete. Try again."), Toast.LENGTH_SHORT).show()
                }
                busy = false
            }
        },
        onDismiss = { if (!busy) onDismiss() },
        danger = true,
    )
}
