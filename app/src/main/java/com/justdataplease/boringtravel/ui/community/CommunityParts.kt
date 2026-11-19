package com.justdataplease.boringtravel.ui.community

import com.justdataplease.boringtravel.community.ProfileAvatar
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.justdataplease.boringtravel.art.ScenePick
import com.justdataplease.boringtravel.community.Community
import com.justdataplease.boringtravel.community.CommunityOfflineException
import com.justdataplease.boringtravel.community.CommunityUnavailableException
import com.justdataplease.boringtravel.community.LikeState
import com.justdataplease.boringtravel.community.Leaderboard
import com.justdataplease.boringtravel.community.NoProfileException
import com.justdataplease.boringtravel.community.NotSignedInException
import com.justdataplease.boringtravel.community.Post
import com.justdataplease.boringtravel.community.PostKind
import com.justdataplease.boringtravel.community.PostText
import com.justdataplease.boringtravel.google.readable
import com.justdataplease.boringtravel.graph
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.DrawnScene
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.onboarding.findActivity
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.traveller.TravellerFigure
import com.justdataplease.boringtravel.ui.world.TripFlags
import kotlinx.coroutines.launch

/*
 * Pieces every community screen shares: pictures from the poster's Drive, the post card the feed shows (and the
 * composer previews), hearts, avatars, the medal colours, a text field and the plain error words.
 */

/** The app's community. */
@Composable
internal fun communityOf(): Community = LocalContext.current.graph.community

/** People's words for what went wrong. */
internal fun communityMessage(e: Throwable, fallback: String = "Couldn't load. Try again."): String = when (e) {
    is NotSignedInException, is NoProfileException, is CommunityUnavailableException, is CommunityOfflineException -> e.message ?: fallback
    else -> e.readable(fallback)
}

/**
 * Picture [fileId] from a poster's Drive (read with the app's key or the public link, never the viewer's own Drive),
 * cropped to fill; the theme's quiet colour while it loads, then [fallback] (or a photo mark) if it can't be read,
 * including an id that isn't a Drive id at all (posts come from anyone).
 */
@Composable
fun DrivePicture(
    fileId: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: (@Composable () -> Unit)? = null,
) {
    val community = communityOf()
    val context = LocalContext.current
    val request = remember(fileId) {
        fileId?.let { id ->
            runCatching {
                val (url, headers) = community.pictureAddress(id)
                ImageRequest.Builder(context)
                    .data(url)
                    .httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
                    .diskCacheKey("drive:$id")
                    .build()
            }.getOrNull()
        }
    }
    if (request == null) {
        Box(modifier.background(Bt.colors.chip), contentAlignment = Alignment.Center) {
            when {
                fallback != null -> fallback()
                fileId != null -> Icon(BtIcons.Photo, null, Modifier.size(28.dp), tint = Bt.colors.muted)
            }
        }
        return
    }
    var failed by remember(fileId) { mutableStateOf(false) }
    Box(modifier.background(Bt.colors.chip), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request, contentDescription = description, imageLoader = community.images, contentScale = contentScale,
            modifier = Modifier.matchParentSize(), onError = { failed = true }, onSuccess = { failed = false },
        )
        if (failed) fallback?.invoke() ?: Icon(BtIcons.Photo, null, Modifier.size(28.dp), tint = Bt.colors.muted)
    }
}

/** A post's cover: its picture, or a drawn picture of its place when it has none (as trips without photos do). */
@Composable
fun PostCover(post: Post, modifier: Modifier = Modifier) {
    val drawn: @Composable () -> Unit = {
        DrawnScene(
            ScenePick.forTrip(post.id, post.title, "", post.countries.firstOrNull()), Modifier.fillMaxSize(), key = post.id,
            description = post.title,
        )
    }
    if (post.coverFileId == null) Box(modifier) { drawn() } else DrivePicture(post.coverFileId, modifier, post.title, fallback = drawn)
}

/** The round play mark over a clip's picture. */
@Composable
fun PlayMark(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(modifier.size(size).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Icon(BtIcons.Play, null, Modifier.size(size * 0.46f), tint = Color.White)
    }
}

/**
 * "CLIP", "ALBUM · 12" or "PLAN · 12 DAYS" on a dark pill over a picture: a trip's plan is what Copy this trip copies,
 * so trip posts are told apart in the feed and on profiles.
 */
@Composable
fun KindTag(post: Post, modifier: Modifier = Modifier) {
    val (icon, label) = when (post.kind) {
        PostKind.Clip -> BtIcons.Movie to "Clip"
        PostKind.Album -> BtIcons.Gallery to "Album · ${post.photoFileIds.size}"
        PostKind.Trip -> if (!post.hasTrip) return else BtIcons.Route to listOf("Plan", PostText.facts(post.days, 0)).filter { it.isNotEmpty() }.joinToString(" · ")
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = Color.White)
        Text(label.uppercase(), style = Bt.text.eyebrow.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1)
    }
}

/** The heart colour: each theme's red. */
internal val heartColor: Color @Composable get() = Bt.colors.danger

/**
 * The heart and its count. A tap changes it at once with a small pop and a tick (the server follows, see
 * Community.toggleLike); on the viewer's own posts it only shows the count.
 */
@Composable
fun HeartButton(state: LikeState, enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, large: Boolean = false) {
    val haptic = LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val c = Bt.colors
    val iconSize = if (large) 26.dp else 22.dp
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .semantics {
                contentDescription = if (enabled) "Like" else "Likes"
                stateDescription = (if (state.liked) "Liked, " else "") + PostText.likes(state.count)
            }
            .then(
                if (enabled) Modifier.clickable(role = Role.Switch) {
                    haptic.performHapticFeedback(if (state.liked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    scope.launch {
                        scale.snapTo(0.7f)
                        scale.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 700f))
                    }
                    onToggle()
                } else Modifier,
            )
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (state.liked) BtIcons.HeartFilled else BtIcons.Heart, null,
            Modifier.size(iconSize).scale(scale.value), tint = if (state.liked) heartColor else c.ink,
        )
        Text(
            PostText.count(state.count),
            style = (if (large) Bt.text.item else Bt.text.label).copy(fontWeight = FontWeight.SemiBold),
            color = c.ink, maxLines = 1,
        )
    }
}

/**
 * A traveller's round avatar: their traveller ([ProfileAvatar]: its look, turned their way, head and shoulders or the
 * whole figure), else the first letter of their username, on the theme's highlighter.
 */
@Composable
fun Avatar(username: String?, avatar: String?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val c = Bt.colors
    val pic = remember(avatar) { ProfileAvatar.decodeOrNull(avatar) }
    Box(modifier.size(size).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
        if (pic != null) {
            TravellerFigure(
                pic.traveller,
                if (pic.full) Modifier.requiredSize(size * 0.86f).offset(y = size * 0.02f)
                else Modifier.requiredSize(width = size * 1.6f, height = size * 2.2f).offset(y = size * 0.55f),
                walking = false, turnable = false, heading = pic.heading,
            )
        } else {
            val initial = username?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            Text(
                initial, style = Bt.text.item.copy(fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp),
                color = c.onAccent,
            )
        }
    }
}

/** Medal fills for ranks 1–3 from the theme (gold: the highlighter; silver and bronze mixed from its colours). */
@Composable
internal fun medalColors(medal: Leaderboard.Medal?): Pair<Color, Color>? {
    val c = Bt.colors
    return when (medal) {
        Leaderboard.Medal.Gold -> c.accent to c.onAccent
        Leaderboard.Medal.Silver -> lerp(c.surface, c.muted, 0.38f).compositeOver(c.surface) to c.ink
        Leaderboard.Medal.Bronze -> lerp(c.accent, c.danger, 0.55f) to c.onAccent
        null -> null
    }
}

/**
 * A post as the feed shows it: the 4:5 cover (a play mark on clips, a tag on clips and albums), the title in the
 * display face, @username and when, flags, "12 days · 1,240 km", and the heart. [cover] is the post's own picture;
 * the composer passes the photo on the phone to preview it exactly as it will look.
 */
@Composable
fun PostCard(
    post: Post,
    like: LikeState,
    mine: Boolean,
    onOpen: () -> Unit,
    onUser: () -> Unit,
    onLike: () -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
    cover: @Composable (Modifier) -> Unit = { PostCover(post, it) },
) {
    val c = Bt.colors
    BtCard(modifier, padding = PaddingValues(0.dp), onClick = onOpen) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f)) {
            cover(Modifier.fillMaxSize())
            KindTag(post, Modifier.align(Alignment.TopStart).padding(12.dp))
            if (post.kind == PostKind.Clip) PlayMark(Modifier.align(Alignment.Center))
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                    Text(Bt.text.display(post.title), style = Bt.text.h2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            PostText.handle(post.username),
                            Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClickLabel = "Open profile", onClick = onUser)
                                .padding(vertical = 4.dp),
                            style = Bt.text.item, color = c.accentInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(" · ${PostText.ago(post.createdAt, now)}", style = Bt.text.sub, maxLines = 1)
                    }
                }
                HeartButton(like, enabled = !mine, onToggle = onLike)
            }
            val facts = PostText.facts(post)
            if (post.countries.isNotEmpty() || facts.isNotEmpty()) {
                Row(Modifier.padding(top = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TripFlags(post.countries, size = 16.dp, max = 4)
                    if (post.countries.isNotEmpty() && facts.isNotEmpty()) Spacer(Modifier.width(8.dp))
                    if (facts.isNotEmpty()) Text(facts, style = Bt.text.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A card-shaped placeholder that breathes while the first page loads. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        0.45f, 0.85f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse",
    )
    val fill = Bt.colors.chip.copy(alpha = pulse)
    BtCard(modifier, padding = PaddingValues(0.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f).background(fill))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.7f).height(22.dp).clip(RoundedCornerShape(6.dp)).background(fill))
            Box(Modifier.fillMaxWidth(0.4f).height(14.dp).clip(RoundedCornerShape(6.dp)).background(fill))
        }
    }
}

/**
 * A labelled text field in the theme: [label] over it, then the hint or the error on the left and "12/150" on the
 * right when [max] is given. [prefix] is drawn before the text ("@").
 */
@Composable
fun CommunityField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minHeight: Dp = 48.dp,
    max: Int? = null,
    hint: String? = null,
    error: String? = null,
    ok: String? = null,
    prefix: String? = null,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
) {
    val c = Bt.colors
    val shape = RoundedCornerShape(Bt.tokens.radius.coerceAtMost(14.dp))
    Column(modifier.fillMaxWidth()) {
        Text(label, style = Bt.text.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
        BasicTextField(
            value = value,
            onValueChange = { v -> onValueChange(if (max != null && v.length > max) v.take(max) else v) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).semantics { contentDescription = label },
            textStyle = Bt.text.body.copy(color = c.ink),
            cursorBrush = SolidColor(c.ink),
            singleLine = singleLine,
            enabled = enabled,
            keyboardOptions = keyboard,
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(c.surface)
                        .border(if (error != null) 1.5.dp else 1.dp, if (error != null) c.danger else c.line, shape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    if (prefix != null) Text(prefix, style = Bt.text.body, color = c.muted)
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = Bt.text.body, color = c.muted, maxLines = if (singleLine) 1 else 3)
                        inner()
                    }
                }
            },
        )
        val note = error ?: ok ?: hint
        if (note != null || max != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    note.orEmpty(), Modifier.weight(1f), style = Bt.text.fine,
                    color = when {
                        error != null -> c.danger
                        ok != null -> c.accentInk
                        else -> c.muted
                    },
                )
                if (max != null) Text("${value.length}/$max", style = Bt.text.fine, color = if (value.length >= max) c.danger else c.muted)
            }
        }
    }
}

/**
 * "Sign in with Google to see and post": the community's sign-in card (Google's sign-in and Drive sheets on one tap,
 * then Firebase with the same account), with what went wrong under it. Reading needs it too: the rules let nobody else
 * in. A no to Drive still signs in; posts then ask for Drive when they need it.
 */
@Composable
fun SignInCard(
    modifier: Modifier = Modifier,
    title: String = "Sign in with Google to see and post",
    body: String = "Others see your username. Photos and clips are shared from your Google Drive.",
) {
    val community = communityOf()
    val graph = LocalContext.current.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val possible = graph.google.signInConfigured || community.usesEmulator
    fun joinCommunity() {
        val activity = context.findActivity()
        if (activity == null) {
            busy = false
            return
        }
        scope.launch {
            message = community.signIn(activity)?.takeIf { it.isNotEmpty() }
            busy = false
        }
    }
    val connect = com.justdataplease.boringtravel.ui.onboarding.rememberGoogleConnect(graph) { result ->
        if (result is com.justdataplease.boringtravel.ui.onboarding.GoogleConnect.NotSignedIn) {
            message = result.message
            busy = false
        } else {
            joinCommunity()
        }
    }
    BtCard(modifier, padding = PaddingValues(16.dp)) {
        Text(title, style = Bt.text.item)
        Text(body, Modifier.padding(top = 2.dp), style = Bt.text.sub)
        if (!community.available) {
            Text("The community isn't available in this build.", Modifier.padding(top = 10.dp), style = Bt.text.sub, color = Bt.colors.danger)
        } else if (!possible) {
            Text("Google sign-in isn't set up in this build.", Modifier.padding(top = 10.dp), style = Bt.text.sub, color = Bt.colors.danger)
        } else {
            BtButton(
                if (busy) "Signing in" else "Sign in with Google", icon = BtIcons.Me, enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                onClick = {
                    busy = true
                    message = null
                    // The community emulator (a debug build pointed at it) signs in by itself, with a made-up account.
                    if (graph.google.signInConfigured && !community.usesEmulator) connect() else joinCommunity()
                },
            )
        }
        message?.let { Text(it, Modifier.padding(top = 8.dp), style = Bt.text.sub, color = Bt.colors.danger) }
    }
}

/** A small card with a line and an action, for "Pick a username", "Connect Drive" and the like. */
@Composable
fun NoteCard(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    BtCard(modifier, padding = PaddingValues(16.dp)) {
        Text(title, style = Bt.text.item)
        Text(body, Modifier.padding(top = 2.dp), style = Bt.text.sub)
        if (action != null) Box(Modifier.padding(top = 12.dp)) { action() }
    }
}

/** A row of three square pictures (album grids and note photos), each opening the viewer at its index. */
@Composable
fun PictureGrid(ids: List<String>, onOpen: (Int) -> Unit, modifier: Modifier = Modifier, columns: Int = 3, gap: Dp = 4.dp) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        ids.chunked(columns).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                chunk.forEachIndexed { i, id ->
                    val index = row * columns + i
                    DrivePicture(
                        id,
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(Bt.tokens.photo.corner.coerceAtMost(8.dp)))
                            .clickable(role = Role.Button, onClickLabel = "Open photo") { onOpen(index) },
                        description = "Photo ${index + 1}",
                    )
                }
                repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
