package com.justdataplease.boringtravel.ui.community

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.community.CommunityLimits
import com.justdataplease.boringtravel.community.PostDraft
import com.justdataplease.boringtravel.community.PostDrafts
import com.justdataplease.boringtravel.community.PostKind
import com.justdataplease.boringtravel.community.PostPlan
import com.justdataplease.boringtravel.community.PostSource
import com.justdataplease.boringtravel.data.SavedClipIndex
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.google.DriveStep
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtSegmented
import com.justdataplease.boringtravel.ui.components.BtSwitchRow
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.EmptyState
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.MediaImage
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.me.ConfirmDialog
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.nav.CommunityCompose
import com.justdataplease.boringtravel.ui.onboarding.rememberDriveConnect
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.launch

/**
 * Post to community, from a trip's ⋮ menu or its Media tab: what to share (the trip's plan with the notes picked, a
 * saved clip, or up to 30 photos as an album), the cover, title and caption, a preview exactly as the feed will show it,
 * then Post, with progress and Try again. What is still needed (sign-in, a username, Drive) is said at the top.
 */
@Composable
fun ComposeScreen(graph: AppGraph, actions: AppActions, route: CommunityCompose) {
    val community = graph.community
    val vm: ComposeViewModel = viewModel(key = "compose-${route.tripId}-${route.kind}-${route.clipId}-${route.mediaIds}") { ComposeViewModel(graph, route) }
    val source by vm.source.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val upload by vm.upload.collectAsStateWithLifecycle()
    var askStop by rememberSaveable { mutableStateOf(false) }
    val running = upload is UploadUi.Running
    BackHandler(enabled = running) { askStop = true }

    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Post to community", onBack = { if (running) askStop = true else actions.back() })
            val s = source
            val d = draft
            when {
                !community.available -> EmptyState("Not in this build", "The community needs the app from Google Play or GitHub.")
                missing -> EmptyState("This trip is gone", "It was deleted from this phone.")
                s == null || d == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Bt.colors.muted, strokeWidth = 2.dp)
                }
                PostDrafts.blocked(s.trip) != null -> EmptyState(
                    if (s.trip.isDemo) "Demo trips can't be posted" else "This trip can't be posted",
                    if (s.trip.isDemo) "Post a trip of your own. Turn off Demo mode in Me to see yours." else PostDrafts.blocked(s.trip).orEmpty(),
                )
                else -> ComposeForm(graph, actions, vm, s, d, upload)
            }
        }
    }
    if (askStop) {
        ConfirmDialog(
            title = "Stop posting?",
            body = "What went to your Drive so far is deleted.",
            confirm = "Stop",
            onConfirm = {
                askStop = false
                vm.stop()
                actions.back()
            },
            onDismiss = { askStop = false },
        )
    }
}

@Composable
private fun ComposeForm(graph: AppGraph, actions: AppActions, vm: ComposeViewModel, s: PostSource, d: PostDraft, upload: UploadUi) {
    val community = graph.community
    val user by community.user.collectAsStateWithLifecycle()
    val profile by community.profile.collectAsStateWithLifecycle()
    val profileKnown by community.profileKnown.collectAsStateWithLifecycle()
    val profileFailed by community.profileFailed.collectAsStateWithLifecycle()
    val driveConnected by graph.settings.driveConnected.collectAsStateWithLifecycle(initialValue = false)
    val rulesAccepted by graph.settings.communityRulesAccepted.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    var showRules by rememberSaveable { mutableStateOf(false) }
    var driveMessage by remember { mutableStateOf<String?>(null) }
    val connectDrive = rememberDriveConnect(graph) { step ->
        driveMessage = (step as? DriveStep.Failed)?.message
    }
    // Not known yet (the look-up at sign-in failed, or is still on its way): ask again here.
    LaunchedEffect(user?.uid, profileKnown) {
        if (user != null && !profileKnown) community.refreshMe()
    }
    val c = Bt.colors
    val running = upload is UploadUi.Running
    // Drive only when something goes there: a plan with a drawn cover needs none.
    val needsDrive = remember(s, d) { PostDrafts.needsDrive(s, d) }
    val driveReady = !needsDrive || driveConnected || community.usesEmulator
    val problem = remember(s, d) { vm.problem(s, d) }
    val ready = user != null && profile != null && driveReady && problem == null && !running
    val startedAt = remember { System.currentTimeMillis() }
    val preview = remember(s, d, user?.uid, profile?.username) {
        PostDrafts.preview(vm.postId, s, d, user?.uid.orEmpty(), profile?.username ?: "you", startedAt)
    }
    val post: () -> Unit = { vm.post { p -> actions.posted(p.id) } }

    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp)) {
        // What is still needed, in the order it is needed.
        when {
            user == null -> item(key = "gate-sign-in") { SignInCard(Modifier.padding(top = 4.dp, bottom = 12.dp), title = "Sign in with Google to post") }
            !profileKnown && profileFailed -> item(key = "gate-profile-check") {
                NoteCard("Couldn't check your profile", "Check the connection and try again.", Modifier.padding(top = 4.dp, bottom = 12.dp)) {
                    BtButton("Try again", onClick = { scope.launch { community.refreshMe() } }, small = true)
                }
            }
            profileKnown && profile == null -> item(key = "gate-profile") {
                NoteCard("Pick a username first", "Others see your posts under it.", Modifier.padding(top = 4.dp, bottom = 12.dp)) {
                    BtButton("Make my profile", onClick = actions::editCommunityProfile, small = true)
                }
            }
            !driveReady -> item(key = "gate-drive") {
                NoteCard(
                    "Connect Google Drive", "Your photos and clips stay in your Drive, in a folder anyone with its link can view.",
                    Modifier.padding(top = 4.dp, bottom = 12.dp),
                ) {
                    Column {
                        BtButton("Connect Drive", onClick = connectDrive, small = true, icon = BtIcons.Cloud)
                        driveMessage?.let { Text(it, Modifier.padding(top = 6.dp), style = Bt.text.sub, color = c.danger) }
                    }
                }
            }
        }
        item(key = "kind") {
            Column {
                Eyebrow("What to share", Modifier.padding(top = 4.dp, bottom = 8.dp))
                BtSegmented(
                    PostKind.entries.map { it.label }, d.kind.ordinal,
                    onSelect = { i -> vm.edit { src, dr -> PostDrafts.withKind(src, dr, PostKind.entries[i]) } },
                )
            }
        }
        when (d.kind) {
            PostKind.Trip -> tripChoices(vm, s, d)
            PostKind.Clip -> item(key = "clips") { ClipChoices(vm, s, d) { actions.clip(s.trip.id) } }
            PostKind.Album -> albumChoices(vm, s, d)
        }
        val covers = PostDrafts.coverChoices(s, d)
        if (covers.isNotEmpty()) item(key = "cover") {
            Column(Modifier.padding(top = 20.dp)) {
                Eyebrow("Cover", Modifier.padding(bottom = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(covers, key = { it ?: "drawn" }) { key ->
                        val on = key == d.cover
                        Box(
                            Modifier.size(width = 72.dp, height = 90.dp).clip(RoundedCornerShape(8.dp))
                                .then(if (on) Modifier.border(3.dp, c.primary, RoundedCornerShape(8.dp)) else Modifier)
                                .semantics { selected = on }
                                .clickable(role = Role.RadioButton, onClickLabel = "Use as cover") { vm.edit { src, dr -> PostDrafts.withCover(src, dr, key) } },
                        ) {
                            LocalPicture(key, vm, s, d, preview, Modifier.fillMaxSize().padding(if (on) 3.dp else 0.dp).clip(RoundedCornerShape(6.dp)))
                            if (key == null) {
                                Text(
                                    "Drawn", Modifier.align(Alignment.BottomCenter).padding(4.dp).clip(RoundedCornerShape(4.dp))
                                        .background(Color(0x99000000)).padding(horizontal = 4.dp),
                                    style = Bt.text.mono, color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "fields") {
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CommunityField(
                    d.title, { t -> vm.edit { _, dr -> dr.copy(title = t) } }, "Title", max = CommunityLimits.TITLE,
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), enabled = !running,
                )
                CommunityField(
                    d.caption, { t -> vm.edit { _, dr -> dr.copy(caption = t) } }, "Caption", placeholder = "What was it like?",
                    max = CommunityLimits.CAPTION, singleLine = false, minHeight = 96.dp,
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), enabled = !running,
                )
            }
        }
        item(key = "preview") {
            Column(Modifier.padding(top = 24.dp)) {
                Eyebrow("Preview", Modifier.padding(bottom = 8.dp))
                PreviewCard(preview, cover = { m -> LocalPicture(d.cover, vm, s, d, preview, m) })
            }
        }
        item(key = "post") {
            Column(Modifier.padding(top = 20.dp)) {
                when (upload) {
                    is UploadUi.Running -> UploadCard(upload)
                    is UploadUi.Failed -> {
                        Text(upload.message, style = Bt.text.sub, color = c.danger)
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BtButton("Try again", onClick = post, enabled = ready, modifier = Modifier.weight(1f))
                            BtGhostButton("Stop", onClick = { vm.stop() })
                        }
                    }
                    UploadUi.Idle -> {
                        // Owner, 17 Nov 2026: "a button in trip to … resync", by hand only ("not to overprice Firestore").
                        if (d.kind == PostKind.Trip) {
                            Text("Changed the trip later? Its ⋮ › Community post › Update now updates this post.", Modifier.padding(bottom = 10.dp), style = Bt.text.fine)
                        }
                        if (problem != null) Text(problem, Modifier.padding(bottom = 10.dp), style = Bt.text.sub)
                        BtButton(
                            "Post", icon = BtIcons.Send, enabled = ready, modifier = Modifier.fillMaxWidth(),
                            onClick = { if (rulesAccepted) post() else showRules = true },
                        )
                    }
                }
                if (needsDrive) {
                    Text(
                        "Photos go up smaller and without their location, to BoringTravel/Community in your Google Drive. Anyone with the link can view them.",
                        Modifier.padding(top = 10.dp), style = Bt.text.fine,
                    )
                }
            }
        }
    }
    if (showRules) {
        RulesSheet(
            onAgree = {
                showRules = false
                scope.launch {
                    graph.settings.setCommunityRulesAccepted(true)
                    post()
                }
            },
            onDismiss = { showRules = false },
        )
    }
}

/** "Uploading photos · 3 of 12" over a bar. */
@Composable
private fun UploadCard(upload: UploadUi.Running) {
    val c = Bt.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.chip).padding(16.dp)) {
        Text(upload.progress.label, style = Bt.text.item)
        LinearProgressIndicator(
            progress = { upload.progress.fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = c.primary, trackColor = c.line,
        )
        Text("Keep this screen open until it's posted.", Modifier.padding(top = 8.dp), style = Bt.text.fine)
    }
}

/** A trip post: the plan's size, Include notes, then which notes. */
private fun androidx.compose.foundation.lazy.LazyListScope.tripChoices(vm: ComposeViewModel, s: PostSource, d: PostDraft) {
    item(key = "trip-head") {
        val places = s.liveItems.size
        val days = PostDrafts.days(s)
        Column(Modifier.padding(top = 16.dp)) {
            Text(
                listOfNotNull(days.takeIf { it > 0 }?.let(Dates::days), if (places == 1) "1 item in the plan" else "$places items in the plan").joinToString(" · "),
                style = Bt.text.item,
            )
            Text("Places, times and links go. Plan notes, bookings and your route don't.", Modifier.padding(top = 2.dp), style = Bt.text.sub)
            if (s.ownNotes.isNotEmpty()) {
                BtSwitchRow(
                    "Include notes", "Pick which notes others can read", d.includeNotes,
                    onChange = { on -> vm.edit { src, dr -> PostDrafts.withNotes(src, dr, on) } },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
    if (d.includeNotes) {
        items(s.ownNotes, key = { "note-${it.id}" }) { e -> NoteChoice(e, s.photosOf(e.id).size, e.id in d.noteIds) { vm.edit { _, dr -> PostDrafts.toggleNote(dr, e.id) } } }
        val left = PostDrafts.leftOut(s, d)
        if (left > 0) item(key = "notes-left") {
            Text("Up to ${CommunityLimits.PHOTOS} photos: $left of the last notes' photos stay out.", Modifier.padding(top = 6.dp), style = Bt.text.fine)
        }
    }
}

@Composable
private fun NoteChoice(e: Entry, photos: Int, on: Boolean, onToggle: () -> Unit) {
    val c = Bt.colors
    val day = remember(e.occurredAt, e.zoneId) { Dates.localDate(e.occurredAt, e.zoneId) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { selected = on }
            .clickable(role = Role.Checkbox, onClick = onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (on) BtIcons.CheckBox else BtIcons.CheckBoxEmpty, null, Modifier.size(22.dp), tint = if (on) c.primary else c.muted)
        Column(Modifier.weight(1f)) {
            Text(e.title.ifBlank { e.plainText.lineSequence().firstOrNull().orEmpty() }.ifBlank { "Note" }, style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(Dates.dayLong(day), photos.takeIf { it > 0 }?.let { if (it == 1) "1 photo" else "$it photos" }).joinToString(" · "),
                style = Bt.text.sub, maxLines = 1,
            )
        }
    }
}

/** A clip post: the trip's saved clips to pick from, or Make a clip. */
@Composable
private fun ClipChoices(vm: ComposeViewModel, s: PostSource, d: PostDraft, onMake: () -> Unit) {
    val c = Bt.colors
    Column(Modifier.padding(top = 16.dp)) {
        if (s.clips.isEmpty()) {
            Text("No clips of this trip yet.", style = Bt.text.sub)
            BtButton("Make a clip", onClick = onMake, icon = BtIcons.Movie, small = true, modifier = Modifier.padding(top = 10.dp))
            return@Column
        }
        Text(if (s.clips.size == 1) "Your clip" else "Pick a clip", style = Bt.text.item, modifier = Modifier.padding(bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(s.clips, key = { it.id }) { clip ->
                val on = clip.id == d.clipId
                val shape = RoundedCornerShape(10.dp)
                Column(Modifier.width(132.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(96.dp).clip(shape).background(c.chip)
                            .then(if (on) Modifier.border(3.dp, c.primary, shape) else Modifier)
                            .semantics { selected = on }
                            .clickable(role = Role.RadioButton, onClickLabel = "Pick this clip") { vm.edit { src, dr -> PostDrafts.withClip(src, dr, clip.id) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        val thumb = remember(clip.id) { vm.clips.thumbnail(clip) }
                        if (thumb != null) AsyncImage(thumb, "Clip", Modifier.matchParentSize().padding(if (on) 3.dp else 0.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        else Icon(BtIcons.Movie, null, Modifier.size(26.dp), tint = c.muted)
                        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0x8C000000)), contentAlignment = Alignment.Center) {
                            Icon(if (on) BtIcons.Check else BtIcons.Play, null, Modifier.size(18.dp), tint = Color.White)
                        }
                    }
                    Text(
                        listOf(Dates.dayMonth(java.time.Instant.ofEpochMilli(clip.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()), SavedClipIndex.length(clip.durationMs))
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                        Modifier.padding(top = 4.dp), style = Bt.text.sub, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** An album: the trip's photos in rows of four, a tap picks (numbered in the order picked) or drops. */
private fun androidx.compose.foundation.lazy.LazyListScope.albumChoices(vm: ComposeViewModel, s: PostSource, d: PostDraft) {
    item(key = "album-head") {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.ownPhotos.isEmpty()) "No photos in this trip yet." else "Pick up to ${CommunityLimits.PHOTOS}",
                Modifier.weight(1f), style = if (s.ownPhotos.isEmpty()) Bt.text.sub else Bt.text.item,
            )
            if (d.albumIds.isNotEmpty()) Text("${d.albumIds.size} picked", style = Bt.text.mono)
        }
    }
    val rows = s.ownPhotos.chunked(4)
    items(rows.size, key = { "photos-$it" }) { r ->
        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            rows[r].forEach { m ->
                val index = d.albumIds.indexOf(m.id)
                val on = index >= 0
                Box(
                    Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp))
                        .semantics { selected = on }
                        .clickable(role = Role.Checkbox, onClickLabel = if (on) "Leave out" else "Pick") { vm.edit { _, dr -> PostDrafts.toggleAlbum(dr, m.id) } },
                ) {
                    MediaImage(m, Modifier.fillMaxSize(), contentDescription = "Photo")
                    if (on) Box(Modifier.matchParentSize().background(Color(0x33000000)))
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(5.dp).size(24.dp).clip(CircleShape)
                            .background(if (on) Bt.colors.primary else Color(0x66000000))
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Text("${index + 1}", style = Bt.text.mono.copy(fontWeight = FontWeight.Bold), color = Bt.colors.onPrimary)
                    }
                }
            }
            repeat(4 - rows[r].size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * A picture on this phone as the post will use it: a photo, the clip's frame (its saved thumbnail) or the drawn picture
 * of the place (null), which is the feed's own for this post.
 */
@Composable
private fun LocalPicture(
    key: String?,
    vm: ComposeViewModel,
    s: PostSource,
    d: PostDraft,
    preview: com.justdataplease.boringtravel.community.Post,
    modifier: Modifier,
) {
    when (key) {
        null -> PostCover(preview.copy(coverFileId = null), modifier)
        PostPlan.CLIP_FRAME -> {
            val clip = s.clips.firstOrNull { it.id == d.clipId }
            val thumb = remember(clip?.id) { clip?.let(vm.clips::thumbnail) }
            Box(modifier.background(Bt.colors.chip), contentAlignment = Alignment.Center) {
                if (thumb != null) AsyncImage(thumb, "Clip", Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                else Icon(BtIcons.Movie, null, Modifier.size(26.dp), tint = Bt.colors.muted)
            }
        }
        else -> MediaImage(key, modifier, contentDescription = "Cover")
    }
}
