package com.justdataplease.boringtravel.ui.community

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalDensity
import com.justdataplease.boringtravel.ui.components.BtDivider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.community.CommunityLinks
import com.justdataplease.boringtravel.community.LikeState
import com.justdataplease.boringtravel.community.Post
import com.justdataplease.boringtravel.community.PostKind
import com.justdataplease.boringtravel.community.PostText
import com.justdataplease.boringtravel.community.SharedNote
import com.justdataplease.boringtravel.community.SharedTrip
import com.justdataplease.boringtravel.community.TripCopy
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.links.PlacePhotos
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.components.BtBackButton
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.EmptyState
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.map.BtMap
import com.justdataplease.boringtravel.ui.me.ConfirmDialog
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.trip.KindBadge
import com.justdataplease.boringtravel.ui.world.TripFlags
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * One community post: the pictures to swipe (a clip plays from the poster's Drive), title, @username, the facts and the
 * caption, the heart, Share and the ⋮ (Report and Block, or Delete for the viewer's own). A trip post then shows its plan
 * day by day with a map, the notes the poster chose and Copy this trip; an album shows all its photos.
 */
@Composable
fun PostScreen(graph: AppGraph, actions: AppActions, postId: String) {
    val community = graph.community
    val user by community.user.collectAsStateWithLifecycle()
    if (!community.available || user == null) {
        BtScreen {
            Column(Modifier.fillMaxSize()) {
                BtTopBar("Post", onBack = actions::back)
                SignInCard(Modifier.padding(20.dp))
            }
        }
        return
    }
    val vm: PostViewModel = viewModel(key = "post-$postId") { PostViewModel(graph, postId) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val clip by vm.clip.collectAsStateWithLifecycle()
    val likes by community.likes.collectAsStateWithLifecycle()
    val blocked by community.blocked.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var viewer by remember { mutableStateOf<Int?>(null) }
    var viewerIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var reporting by rememberSaveable { mutableStateOf(false) }
    var askBlock by rememberSaveable { mutableStateOf(false) }
    var askDelete by rememberSaveable { mutableStateOf(false) }
    var copying by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val post = ui.post
    BtScreen {
        when {
            post == null && ui.loading -> Column(Modifier.fillMaxSize()) {
                BtTopBar("", onBack = actions::back)
                SkeletonCard(Modifier.padding(16.dp))
            }
            post == null -> Column(Modifier.fillMaxSize()) {
                BtTopBar("", onBack = actions::back)
                if (ui.gone) {
                    EmptyState("This post is gone", "Its poster deleted it.")
                } else {
                    EmptyState("Couldn't load", ui.error ?: "Try again.") { BtButton("Try again", onClick = vm::load) }
                }
            }
            blocked.any { it.uid == post.uid } -> Column(Modifier.fillMaxSize()) {
                BtTopBar("", onBack = actions::back)
                EmptyState("You blocked ${PostText.handle(post.username)}", "Their posts and profile are hidden for you.") {
                    BtGhostButton("Unblock", onClick = { scope.launch { runCatching { vm.unblock() } } })
                }
            }
            else -> {
                val mine = post.uid == user?.uid
                val like = likes[post.id] ?: LikeState(false, post.likes)
                val menu = if (mine) {
                    listOf(MenuLine("Delete post", BtIcons.Delete, danger = true) { askDelete = true })
                } else {
                    listOf(
                        MenuLine("Report post", BtIcons.Flag) { reporting = true },
                        MenuLine("Block ${PostText.handle(post.username)}", BtIcons.Block, danger = true) { askBlock = true },
                    )
                }
                val list = rememberLazyListState()
                val density = LocalDensity.current
                val statusPx = WindowInsets.statusBars.getTop(density)
                val barPx = with(density) { 64.dp.roundToPx() }
                // Past the pictures, a plain bar (back, title, ⋮) keeps the page off the status bar.
                val collapsed by remember(list, statusPx) {
                    derivedStateOf {
                        val hero = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
                        hero == null || hero.offset + hero.size < statusPx + barPx
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 32.dp)) {
                    item(key = "hero") {
                        Hero(
                            post, clip, menu, onBack = actions::back,
                            onOpen = { i ->
                                if (post.kind == PostKind.Clip) vm.loadClip() else {
                                    viewerIds = post.pictures
                                    viewer = i
                                }
                            },
                        )
                    }
                    item(key = "head") {
                        PostHead(
                            post, ui.author?.avatar, like, mine,
                            onUser = { actions.communityProfile(post.uid) },
                            onLike = { community.toggleLike(post) },
                        )
                    }
                    // Copy this trip shows on every post with a plan (not the viewer's own) while the plan still loads,
                    // so the button is where it will be; it works once the plan is read.
                    val copyable = post.hasTrip && !mine && !ui.tripMissing
                    item(key = "actions") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (copyable) {
                                BtButton("Copy this trip", icon = BtIcons.Duplicate, enabled = !busy && ui.trip != null, modifier = Modifier.weight(1f), onClick = {
                                    val trip = ui.trip ?: return@BtButton
                                    if (TripCopy.needsStart(trip)) {
                                        copying = true
                                    } else {
                                        busy = true
                                        scope.launch {
                                            copy(vm, null, context, actions)
                                            busy = false
                                        }
                                    }
                                })
                            }
                            BtGhostButton("Share", icon = BtIcons.Share, onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, CommunityLinks.shareText(post))
                                runCatching { context.startActivity(Intent.createChooser(send, null)) }
                            })
                        }
                    }
                    if (copyable) item(key = "copy-note") {
                        Text(COPY_NOTE, Modifier.padding(horizontal = 20.dp), style = Bt.text.fine)
                    }
                    if (clip is ClipLoad.Failed) item(key = "clip-error") {
                        Text((clip as ClipLoad.Failed).message, Modifier.padding(horizontal = 20.dp), style = Bt.text.sub, color = Bt.colors.danger)
                    }
                    val trip = ui.trip
                    when {
                        trip != null -> tripContent(trip, ui, onOpen = { ids, i -> viewerIds = ids; viewer = i })
                        post.hasTrip && ui.tripMissing -> item(key = "trip-missing") {
                            Text("This trip's plan can't be read. Update BoringTravel to see it.", Modifier.padding(20.dp), style = Bt.text.sub)
                        }
                        post.hasTrip -> item(key = "trip-loading") {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), color = Bt.colors.muted, strokeWidth = 2.dp)
                            }
                        }
                    }
                    if (post.kind == PostKind.Album && post.pictures.size > 1) item(key = "album") {
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                            Eyebrow("Photos · ${post.pictures.size}", Modifier.padding(bottom = 8.dp))
                            PictureGrid(post.pictures, onOpen = { i -> viewerIds = post.pictures; viewer = i })
                        }
                    }
                }
                AnimatedVisibility(collapsed, enter = fadeIn(tween(150)), exit = fadeOut(tween(120))) {
                    Column(Modifier.fillMaxWidth().background(Bt.colors.bg)) {
                        BtTopBar(post.title, onBack = actions::back) { MoreMenu(menu, description = "Post options") }
                        BtDivider()
                    }
                }
            }
        }
    }

    viewer?.let { start -> PhotoViewer(viewerIds, start, onClose = { viewer = null }) }
    (clip as? ClipLoad.Ready)?.let { ClipViewer(it.file, onClose = vm::clipShown) }
    if (reporting && post != null) ReportSheet("this post", send = { vm.report(it) }, onDone = { reporting = false })
    if (askBlock && post != null) {
        ConfirmDialog(
            title = "Block ${PostText.handle(post.username)}?",
            body = BLOCK_BODY,
            confirm = "Block",
            onConfirm = {
                askBlock = false
                scope.launch {
                    try {
                        // Hidden at once; offline the block goes out once the phone is online.
                        if (!vm.block()) Toast.makeText(context, QUEUED, Toast.LENGTH_SHORT).show()
                        actions.back()
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
    if (askDelete && post != null) {
        ConfirmDialog(
            title = "Delete this post?",
            body = "It leaves the community, and its photos leave your Drive.",
            confirm = "Delete",
            onConfirm = {
                askDelete = false
                busy = true
                scope.launch {
                    try {
                        val folderGone = vm.delete()
                        if (!folderGone) Toast.makeText(context, "Post deleted. Its photos stay in your Drive, in BoringTravel/Community.", Toast.LENGTH_LONG).show()
                        actions.back()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(context, communityMessage(e, "Couldn't delete. Try again."), Toast.LENGTH_SHORT).show()
                    }
                    busy = false
                }
            },
            onDismiss = { askDelete = false },
            danger = true,
        )
    }
    if (copying) {
        CopyTripDialog(ui.trip?.days ?: 1, onDismiss = { copying = false }) { start ->
            copying = false
            busy = true
            scope.launch {
                copy(vm, start, context, actions)
                busy = false
            }
        }
    }
}

/** What Copy this trip copies, under the button (its first-day picker says it again on top). */
internal const val COPY_NOTE = "Copies the plan into your trips, without the journal."

private suspend fun copy(vm: PostViewModel, start: LocalDate?, context: android.content.Context, actions: AppActions) {
    try {
        val id = vm.copyTrip(start)
        Toast.makeText(context, "Copied to your trips", Toast.LENGTH_SHORT).show()
        actions.openTrip(id, "plan")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't copy the trip. Try again.", Toast.LENGTH_SHORT).show()
    }
}

/**
 * The pictures, 4:5 as in the feed, swiped through (a clip: its cover with the play mark, a tap downloads and plays
 * it); back and the ⋮ over them; the kind tag and dots under them.
 */
@Composable
private fun Hero(post: Post, clip: ClipLoad, menu: List<MenuLine>, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val pictures = post.pictures
    val pager = rememberPagerState { pictures.size.coerceAtLeast(1) }
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f).background(Bt.colors.chip)) {
            if (pictures.isEmpty()) {
                PostCover(post, Modifier.fillMaxSize().clickable(enabled = post.kind == PostKind.Clip, role = Role.Button, onClickLabel = "Play") { onOpen(0) })
            } else {
                HorizontalPager(pager, Modifier.fillMaxSize(), key = { pictures.getOrElse(it) { "x$it" } }) { page ->
                    DrivePicture(
                        pictures[page],
                        Modifier.fillMaxSize().clickable(role = Role.Button, onClickLabel = if (post.kind == PostKind.Clip) "Play the clip" else "Open photo") { onOpen(page) },
                        description = if (post.kind == PostKind.Clip) "${post.title}, clip" else "Photo ${page + 1} of ${pictures.size}",
                        fallback = { PostCover(post.copy(coverFileId = null), Modifier.fillMaxSize()) },
                    )
                }
            }
            if (post.kind == PostKind.Clip) {
                Box(Modifier.align(Alignment.Center)) {
                    if (clip is ClipLoad.Loading) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.5.dp)
                        }
                    } else {
                        PlayMark(size = 64.dp)
                    }
                }
            }
            // A soft shade behind the buttons so they read on any photo.
            Box(
                Modifier.fillMaxWidth().height(96.dp).background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x59000000), Color.Transparent)),
                ),
            )
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BtBackButton(onBack)
                Spacer(Modifier.weight(1f))
                MoreMenu(menu, description = "Post options")
            }
            KindTag(post, Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
        if (pictures.size > 1) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) { PagerDots(pager) }
        }
    }
}

/** Title, the poster (a tap opens their profile) with the heart, the facts, the countries and the caption. */
@Composable
private fun PostHead(post: Post, avatar: String?, like: LikeState, mine: Boolean, onUser: () -> Unit, onLike: () -> Unit) {
    val c = Bt.colors
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp)) {
        Text(Bt.text.display(post.title), Modifier.padding(end = 8.dp), style = Bt.text.h1, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClickLabel = "Open profile", onClick = onUser)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Avatar(post.username, avatar, size = 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(PostText.handle(post.username), style = Bt.text.item, color = c.accentInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(postedLine(post.createdAt, System.currentTimeMillis()), style = Bt.text.sub, maxLines = 1)
                }
            }
            HeartButton(like, enabled = !mine, onToggle = onLike, large = true)
        }
        val facts = listOfNotNull(PostText.facts(post).ifEmpty { null }, PostText.monthLabel(post.startMonth))
        if (post.countries.isNotEmpty() || facts.isNotEmpty()) {
            Row(Modifier.padding(top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TripFlags(post.countries, size = 18.dp, max = 6)
                if (post.countries.isNotEmpty() && facts.isNotEmpty()) Spacer(Modifier.width(10.dp))
                Text(facts.joinToString(" · "), style = Bt.text.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (post.countries.isNotEmpty()) {
            Text(post.countries.joinToString(" · ") { CountryInfo.name(it) }, Modifier.padding(top = 4.dp, end = 8.dp), style = Bt.text.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (post.caption.isNotBlank()) {
            Text(post.caption, Modifier.padding(top = 12.dp, end = 8.dp), style = Bt.text.body)
        }
    }
}

/** "Posted just now", "Posted 3d ago", "Posted 12 Mar 2026". */
internal fun postedLine(createdAt: Long, now: Long): String {
    val ago = PostText.ago(createdAt, now)
    return when {
        ago == "now" -> "Posted just now"
        ago.matches(Regex("[0-9]+[mhdw]")) -> "Posted $ago ago"
        else -> "Posted $ago"
    }
}

/** A trip post's plan (a map, then day by day, then ideas) and the notes the poster chose. */
private fun LazyListScope.tripContent(trip: SharedTrip, ui: PostUi, onOpen: (List<String>, Int) -> Unit) {
    val places = trip.items.size
    item(key = "plan-head") {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp)) {
            Text("The plan", style = Bt.text.h2)
            Text(
                listOfNotNull(trip.days.takeIf { it > 0 }?.let(Dates::days), if (places == 1) "1 item" else "$places items").joinToString(" · "),
                Modifier.padding(top = 2.dp), style = Bt.text.sub,
            )
        }
    }
    ui.map?.let { map ->
        item(key = "map") {
            BtCard(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), padding = PaddingValues(0.dp)) {
                BtMap(map, Modifier.fillMaxWidth().height(220.dp), interactive = false, compactRoute = true)
            }
        }
    }
    trip.plannedDays.forEach { day ->
        item(key = "day-$day") {
            Text(
                "Day $day", Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 2.dp),
                style = Bt.text.label.copy(fontWeight = FontWeight.Bold), color = Bt.colors.ink,
            )
        }
        items(ui.rows.filter { it.day == day }, key = { "row-${it.item.id}" }) { SharedPlanRow(it) }
    }
    val ideas = ui.rows.filter { it.day <= 0 }
    if (ideas.isNotEmpty()) {
        item(key = "ideas") {
            Text(
                if (trip.days > 0) "Ideas · ${ideas.size}" else "Places · ${ideas.size}",
                Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 2.dp),
                style = Bt.text.label.copy(fontWeight = FontWeight.Bold), color = Bt.colors.ink,
            )
        }
        items(ideas, key = { "idea-${it.item.id}" }) { SharedPlanRow(it) }
    }
    if (trip.notes.isNotEmpty()) {
        item(key = "notes-head") {
            Text("Notes", Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp), style = Bt.text.h2)
        }
        items(trip.notes.size, key = { "note-$it" }) { i -> NoteCard(trip.notes[i], onOpen) }
    }
}

/** A plan item as the trip's own plan shows it: the kind (or its place's photo), the time, title and place. */
@Composable
private fun SharedPlanRow(row: SharedRow) {
    val s = row.shared
    val vehicle = Vehicle.entries.firstOrNull { it.id == s.via }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 14.dp, end = 20.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            val place = remember(row.item) { PlacePhotos.query(row.item.copy(url = null)) }
            KindBadge(s.kind, vehicle, done = false, size = 36.dp, link = s.link, place = place)
        }
        Column(Modifier.weight(1f)) {
            Text(s.title, style = Bt.text.item, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                s.time?.let(Dates::time),
                s.place.trim().takeIf { it.isNotEmpty() && !it.startsWith(s.title.trim(), ignoreCase = true) },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A note the poster chose: its day and date, title, text and photos. */
@Composable
private fun NoteCard(note: SharedNote, onOpen: (List<String>, Int) -> Unit) {
    val date = remember(note.date) { note.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }
    BtCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), padding = PaddingValues(16.dp)) {
        Eyebrow(listOfNotNull(note.day.takeIf { it > 0 }?.let { "Day $it" }, date?.let(Dates::dayLong)).joinToString(" · "))
        if (note.title.isNotBlank()) Text(note.title, Modifier.padding(top = 4.dp), style = Bt.text.title)
        if (note.text.isNotBlank()) Text(note.text, Modifier.padding(top = 6.dp), style = Bt.text.body)
        if (note.photos.isNotEmpty()) {
            PictureGrid(note.photos, onOpen = { i -> onOpen(note.photos, i) }, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
