package com.justdataplease.boringtravel.ui.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.saveable.rememberSaveable
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.util.Dates
import java.time.LocalDate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.art.SceneKind
import com.justdataplease.boringtravel.community.FollowState
import com.justdataplease.boringtravel.community.Follows
import com.justdataplease.boringtravel.community.Leaderboard
import com.justdataplease.boringtravel.community.LikeState
import com.justdataplease.boringtravel.community.Post
import com.justdataplease.boringtravel.community.PostText
import com.justdataplease.boringtravel.community.TopRange
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtSegmented
import com.justdataplease.boringtravel.ui.components.DrawnScene
import com.justdataplease.boringtravel.ui.components.EmptyState
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.H1
import com.justdataplease.boringtravel.ui.components.PhotoFrame
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.onboarding.findActivity
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The Community tab: other travellers' trips, clips and albums. Latest shows the newest posts as big cards; Following
 * the same for the people the viewer follows (empty, it suggests posters of the most liked trips); Top is the
 * leaderboard of the most liked (this month or of all time). Signed out, it says what the community is and offers
 * Google sign-in (reading needs it: the rules let nobody else in).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(graph: AppGraph, actions: AppActions) {
    val community = graph.community
    if (!community.available) {
        BtScreen(statusScrim = true) {
            Column(Modifier.fillMaxSize()) {
                CommunityHeader(null, null, onProfile = null)
                EmptyState("Not in this build", "The community needs the app from Google Play or GitHub.")
            }
        }
        return
    }
    val user by community.user.collectAsStateWithLifecycle()
    if (user == null) {
        SignInGate(graph)
        return
    }
    val vm: FeedViewModel = viewModel(key = "community-feed") { FeedViewModel(graph) }
    val tab by vm.tab.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()
    val following by vm.following.collectAsStateWithLifecycle()
    val top by vm.top.collectAsStateWithLifecycle()
    val likes by community.likes.collectAsStateWithLifecycle()
    val blocked by community.blocked.collectAsStateWithLifecycle()
    val profile by community.profile.collectAsStateWithLifecycle()
    val profileKnown by community.profileKnown.collectAsStateWithLifecycle()
    val hidden = remember(blocked) { blocked.mapTo(HashSet()) { it.uid } }
    val me = user?.uid
    val state = when (tab) {
        FeedViewModel.LATEST -> latest
        FeedViewModel.FOLLOWING -> following
        else -> top
    }
    val leaderboard = tab == FeedViewModel.TOP
    LaunchedEffect(tab) { vm.load() }
    val followingEmpty = tab == FeedViewModel.FOLLOWING && state.loaded && state.error == null && !state.offline &&
        state.posts.none { it.uid !in hidden }
    LaunchedEffect(followingEmpty) { if (followingEmpty) vm.suggest() }
    val list = rememberLazyListState()
    // The next page when the end comes near.
    LaunchedEffect(list, tab) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
            .distinctUntilChanged()
            .filter { (last, total) -> last != null && total > 0 && last >= total - 4 }
            .collect { vm.more() }
    }
    val now = remember(state.posts) { System.currentTimeMillis() }
    var picking by rememberSaveable { mutableStateOf(false) }
    val followsNobody by vm.followsNobody.collectAsStateWithLifecycle()
    val capped by vm.capped.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val follows by community.follows.states.collectAsStateWithLifecycle()
    val followTap = rememberFollowTap(actions)

    BtScreen(statusScrim = true) {
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.refresh(pull = true) }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "header") {
                    CommunityHeader(
                        profile?.username, profile?.avatar,
                        onProfile = {
                            val p = profile
                            if (p != null) actions.communityProfile(p.uid) else actions.editCommunityProfile()
                        },
                        onPost = { picking = true },
                    )
                }
                item(key = "tabs") {
                    BtSegmented(
                        listOf("Latest", "Following", "Top"), tab, onSelect = vm::pick,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                    )
                }
                if (profileKnown && profile == null) item(key = "no-profile") {
                    NoteCard(
                        "Pick a username", "You need one to post and follow. Liking works without.",
                        Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    ) { BtButton("Make my profile", onClick = actions::editCommunityProfile, small = true) }
                }
                if (tab == FeedViewModel.FOLLOWING && capped && state.posts.isNotEmpty()) item(key = "capped") {
                    Text(
                        "Showing posts of the ${Follows.FEED_CAP} people you followed last.",
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), style = Bt.text.fine,
                    )
                }
                if (leaderboard) item(key = "range") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TopRange.entries.forEach { r -> BtChip(r.label, r == range, { vm.pickRange(r) }) }
                    }
                }
                if (state.offline && state.posts.isNotEmpty()) item(key = "offline") {
                    Text(
                        "Offline · showing what was saved",
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), style = Bt.text.fine,
                    )
                }
                when {
                    !state.loaded -> items(2, key = { "skeleton-$it" }) {
                        if (leaderboard) SkeletonRow() else SkeletonCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    state.posts.isEmpty() && state.error != null -> item(key = "error") {
                        EmptyState("Couldn't load", state.error) { BtButton("Try again", onClick = { vm.refresh(pull = false) }) }
                    }
                    // Nothing saved on the phone yet and no connection: not "be the first".
                    state.offline && state.posts.none { it.uid !in hidden } -> item(key = "offline-empty") {
                        EmptyState("You're offline", "Connect to see the community.") {
                            BtButton("Try again", onClick = { vm.refresh(pull = false) })
                        }
                    }
                    followingEmpty -> {
                        item(key = "empty") {
                            if (followsNobody) {
                                EmptyState("Follow travellers", "Their posts show here, newest first. Follow from a profile.")
                            } else {
                                EmptyState("Nothing new yet", "The people you follow haven't posted yet.")
                            }
                        }
                        suggestionItems(suggestions, follows, onOpen = actions::communityProfile, onFollow = followTap)
                    }
                    state.posts.none { it.uid !in hidden } -> item(key = "empty") {
                        if (!leaderboard) {
                            EmptyState("Nothing here yet", "Be the first: post a trip, a clip or photos.") {
                                BtButton("Post", onClick = { picking = true }, icon = BtIcons.Add)
                            }
                        } else {
                            EmptyState(
                                if (range == TopRange.Month) "No posts this month yet" else "No posts yet",
                                "The most liked trips show here.",
                            )
                        }
                    }
                    !leaderboard -> items(state.posts.filter { it.uid !in hidden }, key = { "post:${it.id}" }) { post ->
                        PostCard(
                            post, likes[post.id] ?: LikeState(false, post.likes), mine = post.uid == me,
                            onOpen = { actions.communityPost(post.id) }, onUser = { actions.communityProfile(post.uid) },
                            onLike = { community.toggleLike(post) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), now = now,
                        )
                    }
                    else -> {
                        val rows = Leaderboard.rank(state.posts, hidden)
                        if (range == TopRange.Month) item(key = "month") {
                            Eyebrow(
                                "Most liked · ${PostText.monthLabel(vm.month).orEmpty()}",
                                Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
                            )
                        }
                        items(rows, key = { "post:${it.post.id}" }) { row ->
                            TopRow(row, likes[row.post.id] ?: LikeState(false, row.post.likes), onOpen = { actions.communityPost(row.post.id) })
                        }
                    }
                }
                if (state.loaded && state.posts.isNotEmpty()) item(key = "foot") {
                    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                        when {
                            state.loading -> CircularProgressIndicator(Modifier.size(24.dp), color = Bt.colors.muted, strokeWidth = 2.dp)
                            state.error != null -> BtGhostButton("Couldn't load more · Try again", onClick = vm::more)
                            state.end && state.posts.size > 3 -> Text("That's all", style = Bt.text.fine)
                        }
                    }
                }
            }
        }
    }
    if (picking) TripPickerSheet(graph, onPick = { tripId ->
        picking = false
        actions.postToCommunity(tripId)
    }, onNewTrip = {
        picking = false
        actions.newTrip()
    }, onDismiss = { picking = false })
}

/** "BoringTravel / Community" with Post (pick a trip) and the traveller's avatar (their profile, or make one). */
@Composable
private fun CommunityHeader(username: String?, avatar: String?, onProfile: (() -> Unit)?, onPost: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Eyebrow("BoringTravel")
            H1("Community")
        }
        if (onPost != null) BtIconButton(BtIcons.Add, "Post to community", onPost, modifier = Modifier.padding(end = 4.dp))
        if (onProfile != null) {
            val label = if (username != null) "Your profile" else "Make your profile"
            Box(
                Modifier.size(48.dp).clip(CircleShape).semantics { contentDescription = label }
                    .clickable(role = Role.Button, onClickLabel = label, onClick = onProfile),
                contentAlignment = Alignment.Center,
            ) {
                // The picture says nothing more (its initial would be read out instead of what the button does).
                Box(Modifier.clearAndSetSemantics {}) {
                    if (username != null) {
                        Avatar(username, avatar, size = 40.dp)
                    } else {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Bt.colors.chip), contentAlignment = Alignment.Center) {
                            Icon(BtIcons.Me, null, Modifier.size(22.dp), tint = Bt.colors.ink)
                        }
                    }
                }
            }
        }
    }
}

/** A leaderboard row: the rank (medals for the first three), the cover, the title, @username and the likes. */
@Composable
private fun TopRow(row: Leaderboard.Row, like: LikeState, onOpen: () -> Unit) {
    val c = Bt.colors
    val post = row.post
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open post", onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val medal = medalColors(row.medal)
        Box(
            Modifier.size(34.dp).clip(CircleShape).then(if (medal != null) Modifier.background(medal.first) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                row.rank.toString(),
                style = (if (medal != null) Bt.text.item else Bt.text.mono).copy(fontWeight = FontWeight.Bold),
                color = medal?.second ?: c.muted, textAlign = TextAlign.Center, maxLines = 1,
            )
        }
        PostCover(post, Modifier.size(60.dp).clip(RoundedCornerShape(Bt.tokens.photo.corner.coerceAtMost(10.dp))))
        Column(Modifier.weight(1f)) {
            Text(post.title, style = Bt.text.item, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val facts = PostText.facts(post)
            Text(
                listOf(PostText.handle(post.username), facts).filter { it.isNotEmpty() }.joinToString(" · "),
                Modifier.padding(top = 2.dp), style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (like.liked) BtIcons.HeartFilled else BtIcons.Heart, null, Modifier.size(18.dp), tint = if (like.liked) heartColor else c.muted)
            Text(PostText.count(like.count), style = Bt.text.item, maxLines = 1)
        }
    }
}

/**
 * People worth following, under the Following tab's empty state: the posters of the most liked trips, each with their
 * most liked one and Follow. A spinner while they are looked up; nothing when there is nobody to suggest.
 */
private fun LazyListScope.suggestionItems(
    suggestions: List<Suggestion>?,
    follows: Map<String, FollowState>,
    onOpen: (String) -> Unit,
    onFollow: (uid: String, username: String) -> Unit,
) {
    if (suggestions == null) {
        item(key = "suggest-wait") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Bt.colors.muted, strokeWidth = 2.dp)
            }
        }
        return
    }
    if (suggestions.isEmpty()) return
    item(key = "suggest-head") {
        Eyebrow("Most liked travellers", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
    }
    items(suggestions, key = { "suggest:${it.uid}" }) { s ->
        val state = follows[s.uid]
        Column {
            PersonRow(s.username, s.avatar, "${s.post.title} · ${PostText.likes(s.post.likes)}", onOpen = { onOpen(s.uid) }) {
                FollowButton(state, onClick = { onFollow(s.uid, s.username) }, small = true)
            }
            FollowProblem(state, Modifier.padding(start = 76.dp, end = 20.dp, bottom = 4.dp))
        }
    }
}

@Composable
private fun SkeletonRow() {
    val fill = Bt.colors.chip
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(fill))
        Box(Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(fill))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.8f).height(14.dp).clip(RoundedCornerShape(6.dp)).background(fill))
            Box(Modifier.fillMaxWidth(0.5f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(fill))
        }
    }
}

/**
 * Signed out: what the community is, three drawn postcards, and Sign in with Google. A Google account already used
 * with the app signs in quietly when the tab opens.
 */
@Composable
private fun SignInGate(graph: AppGraph) {
    val community = graph.community
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val activity = context.findActivity() ?: return@LaunchedEffect
        busy = true
        community.signIn(activity, quiet = true)
        busy = false
    }
    BtScreen(statusScrim = true) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            CommunityHeader(null, null, onProfile = null)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(170.dp),
                horizontalArrangement = Arrangement.spacedBy((-14).dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(SceneKind.Coast to -6f, SceneKind.Mountain to 3f, SceneKind.City to -2f).forEachIndexed { i, (kind, tilt) ->
                    PhotoFrame(Modifier.width(118.dp).padding(top = if (i == 1) 0.dp else 18.dp), tiltDeg = tilt) {
                        DrawnScene(kind, Modifier.fillMaxWidth().height(128.dp), key = "gate-$i")
                    }
                }
            }
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Trips, clips and photo albums from other travellers.", style = Bt.text.title)
                GateLine(BtIcons.Heart, "Like the ones you love")
                GateLine(BtIcons.Top, "See the most liked each month")
                GateLine(BtIcons.Community, "Follow travellers and see what they post")
                GateLine(BtIcons.Duplicate, "Copy a trip's plan into your own trips")
                GateLine(BtIcons.Cloud, "Post your own. Photos and clips stay in your Google Drive")
                if (busy) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Bt.colors.muted, strokeWidth = 2.dp)
                        Text("Signing in", style = Bt.text.sub)
                    }
                } else {
                    SignInCard(Modifier.padding(top = 8.dp))
                }
                Text(
                    "Report or block anyone, any time.",
                    Modifier.padding(top = 4.dp, bottom = 24.dp), style = Bt.text.fine,
                )
            }
        }
    }
}

@Composable
private fun GateLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Bt.colors.chip), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(17.dp), tint = Bt.colors.ink)
        }
        Text(text, Modifier.weight(1f), style = Bt.text.body)
    }
}

/** For the composer's preview: a post card with the photo on the phone as its cover. */
@Composable
internal fun PreviewCard(post: Post, cover: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier) {
    PostCard(post, LikeState(false, 0), mine = true, onOpen = {}, onUser = {}, onLike = {}, modifier = modifier, cover = cover)
}

/** Post from the Community tab: the traveller's own trips (demo trips left out), newest first; a tap opens the composer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripPickerSheet(graph: AppGraph, onPick: (String) -> Unit, onNewTrip: () -> Unit, onDismiss: () -> Unit) {
    val c = Bt.colors
    val trips by remember(graph) { graph.trips.observeOwnTrips() }.collectAsStateWithLifecycle(initialValue = null)
    val own = remember(trips) {
        trips.orEmpty().filter { it.deletedAt == null }
            .sortedWith(compareByDescending<Trip> { it.startDate ?: LocalDate.MIN }.thenByDescending { it.updatedAt })
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("Post a trip", style = Bt.text.h2)
            Text("Its plan, a clip or photos. You choose next.", Modifier.padding(top = 2.dp, bottom = 8.dp), style = Bt.text.sub)
            when {
                trips == null -> Unit
                own.isEmpty() -> {
                    Text("Your trips show here. Demo trips can't be posted.", Modifier.padding(vertical = 8.dp), style = Bt.text.body)
                    BtButton("New trip", onClick = onNewTrip, icon = BtIcons.Add, modifier = Modifier.padding(top = 4.dp))
                }
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(own, key = { it.id }) { t ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button, onClickLabel = "Post ${t.title}") { onPick(t.id) }.padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Dates.tripSummary(t), style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(BtIcons.Forward, null, Modifier.size(18.dp), tint = c.muted)
                        }
                    }
                }
            }
        }
    }
}
