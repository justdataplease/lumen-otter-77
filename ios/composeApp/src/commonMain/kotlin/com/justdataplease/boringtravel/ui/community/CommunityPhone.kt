package com.justdataplease.boringtravel.ui.community

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.art.SceneKind
import com.justdataplease.boringtravel.community.Community
import com.justdataplease.boringtravel.community.LocalCommunity
import com.justdataplease.boringtravel.community.UnavailableCommunity
import com.justdataplease.boringtravel.compat.File
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.google.LocalGoogle
import com.justdataplease.boringtravel.google.PublicDrive
import com.justdataplease.boringtravel.ios.AppleSignInButton
import com.justdataplease.boringtravel.ios.CommunityGraph
import com.justdataplease.boringtravel.ios.DrawnScene
import com.justdataplease.boringtravel.ios.LocalPhone
import com.justdataplease.boringtravel.ui.ai.AiReport
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.PhotoFrame
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The parts of the Android app's community screens that reach Android (round 24), written again for iPhone; the rest of
 * those files is compiled as it is (the build's `cuts`): the app's community (communityOf), a picture from a poster's
 * Drive (DrivePicture: Coil there, this app's CommunityPictures here), the sign-in card (Google's and Apple's buttons:
 * Apple 4.8 asks for Sign in with Apple beside Google), the signed-out gate with its quiet sign-in, the trip picker
 * behind +, a clip played full screen (Android's VideoView; the app's own player here), and the contact line Apple's
 * rule 1.2 asks for in the community (iPhone only).
 */

/** The app's community; a screen drawn on its own gets one that isn't available. */
@Composable
internal fun communityOf(): Community =
    LocalCommunity.current ?: remember { Community(UnavailableCommunity, null, null, CoroutineScope(SupervisorJob() + Dispatchers.Default)) }

/**
 * Picture [fileId] from a poster's Drive (read with the app's key or the public link, never the viewer's own Drive),
 * cropped to fill; the theme's quiet colour while it loads, then [fallback] (or a photo mark) if it can't be read,
 * including an id that isn't a Drive id at all (posts come from anyone). It fades in as Coil's crossfade does.
 */
@Composable
fun DrivePicture(
    fileId: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: (@Composable () -> Unit)? = null,
) {
    val pictures = communityOf().pictures
    if (fileId == null || !PublicDrive.isId(fileId) || pictures == null) {
        Box(modifier.background(Bt.colors.chip), contentAlignment = Alignment.Center) {
            when {
                fallback != null -> fallback()
                fileId != null -> Icon(BtIcons.Photo, null, Modifier.size(28.dp), tint = Bt.colors.muted)
            }
        }
        return
    }
    var failed by remember(fileId) { mutableStateOf(false) }
    BoxWithConstraints(
        modifier.background(Bt.colors.chip).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val px = with(LocalDensity.current) { max(maxWidth.toPx(), maxHeight.toPx()).let { if (it.isFinite()) it.roundToInt() else 640 } }
        var picture by remember(fileId, px) { mutableStateOf<ImageBitmap?>(pictures.cached(fileId, px)) }
        LaunchedEffect(fileId, px) {
            if (picture == null) {
                picture = pictures.load(fileId, px)
                failed = picture == null
            }
        }
        val alpha by animateFloatAsState(if (picture != null) 1f else 0f, tween(220), label = "crossfade")
        picture?.let { Image(it, null, Modifier.matchParentSize().alpha(alpha), contentScale = contentScale) }
        if (failed) fallback?.invoke() ?: Icon(BtIcons.Photo, null, Modifier.size(28.dp), tint = Bt.colors.muted)
    }
}

/**
 * "Sign in with Google to see and post": the community's sign-in card (Google's page for the account and Drive in one
 * tap, then Firebase with the same account), with Sign in with Apple beside it (Apple 4.8; it signs in to the community
 * with the Apple account), and what went wrong under it. Reading needs it too: the rules let nobody else in. A no to
 * Drive still signs in; posts then ask for Drive when they need it.
 */
@Composable
fun SignInCard(
    modifier: Modifier = Modifier,
    title: String = "Sign in with Google to see and post",
    body: String = "Others see your username. Photos and clips are shared from your Google Drive.",
) {
    val community = communityOf()
    val google = LocalGoogle.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val possible = google?.signInConfigured == true
    BtCard(modifier, padding = PaddingValues(16.dp)) {
        Text(title, style = Bt.text.item)
        Text(body, Modifier.padding(top = 2.dp), style = Bt.text.sub)
        if (!community.available) {
            Text("The community isn't available in this build.", Modifier.padding(top = 10.dp), style = Bt.text.sub, color = Bt.colors.danger)
        } else {
            if (!possible) {
                Text("Google sign-in isn't set up in this build.", Modifier.padding(top = 10.dp), style = Bt.text.sub, color = Bt.colors.danger)
            } else {
                BtButton(
                    if (busy) "Signing in" else "Sign in with Google", icon = BtIcons.Me, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            message = community.signIn(quiet = false)?.takeIf { it.isNotEmpty() }
                            busy = false
                        }
                    },
                )
            }
            // Beside Google's (Apple 4.8): the Apple account is a community account too.
            if (community.appleOffered) {
                AppleSignInButton(
                    onClick = {
                        if (!busy) {
                            busy = true
                            message = null
                            scope.launch {
                                message = community.signInWithApple()?.takeIf { it.isNotEmpty() }
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        message?.let { Text(it, Modifier.padding(top = 8.dp), style = Bt.text.sub, color = Bt.colors.danger) }
    }
}

/**
 * Signed out: what the community is, three drawn postcards, and Sign in. A Google or Apple account already used with
 * the app signs in quietly when the tab opens ("Signing in"). [holdSigningIn] keeps that line (the pictures).
 */
@Composable
internal fun SignInGate(graph: CommunityGraph, holdSigningIn: Boolean = false) {
    val community = graph.community
    var busy by remember { mutableStateOf(holdSigningIn) }
    LaunchedEffect(Unit) {
        if (holdSigningIn) return@LaunchedEffect
        busy = true
        community.signIn(quiet = true)
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
                Text("Report or block anyone, any time.", Modifier.padding(top = 4.dp), style = Bt.text.fine)
                // Apple's rule 1.2: a way to reach the developer (iPhone only).
                CommunityContact(Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

/**
 * Post from the Community tab: the traveller's own trips (demo trips left out), newest first; a tap opens the composer
 * (round 25 on iPhone).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripPickerSheet(graph: CommunityGraph, onPick: (String) -> Unit, onNewTrip: () -> Unit, onDismiss: () -> Unit) {
    val c = Bt.colors
    val own = remember(graph) {
        graph.trips.ownTrips().filter { it.deletedAt == null }
            .sortedWith(compareByDescending<Trip> { it.startDate?.toEpochDay() ?: Long.MIN_VALUE }.thenByDescending { it.updatedAt })
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
            if (own.isEmpty()) {
                Text("Your trips show here. Demo trips can't be posted.", Modifier.padding(vertical = 8.dp), style = Bt.text.body)
                BtButton("New trip", onClick = onNewTrip, icon = BtIcons.Add, modifier = Modifier.padding(top = 4.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
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

/**
 * A video full screen, with sound: plays at once, a tap pauses or plays (the Android app's ClipViewer). A downloaded
 * clip loops ([loop]); [file] is the clip in the caches. The app's own player (AVPlayer under Android's controls).
 */
@Composable
fun ClipViewer(file: File, onClose: () -> Unit, loop: Boolean = true, controls: Boolean = false) =
    com.justdataplease.boringtravel.ios.ClipViewer(file.path, onClose, loop, controls, fullPath = true)

/**
 * A picture on this phone (a saved clip's, in the composer), cropped as [contentScale] says: Coil's AsyncImage for a
 * file there, here the file's bytes read off the main thread and drawn upright at the size it is shown.
 */
@Composable
fun AsyncImage(model: File?, contentDescription: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val said = contentDescription
    BoxWithConstraints(modifier.then(if (said != null) Modifier.semantics { this.contentDescription = said } else Modifier)) {
        val px = with(LocalDensity.current) { max(maxWidth.toPx(), maxHeight.toPx()).let { if (it.isFinite()) it.roundToInt() else 640 } }
        var picture by remember(model?.path, px) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(model?.path, px) {
            val file = model ?: return@LaunchedEffect
            picture = kotlinx.coroutines.withContext(Dispatchers.Default) {
                runCatching { com.justdataplease.boringtravel.ios.Pictures.decode(file.readBytes(), px) }.getOrNull()
            }
        }
        picture?.let { Image(it, null, Modifier.matchParentSize(), contentScale = contentScale) }
    }
}

/**
 * Connect Drive (the Android app's rememberDriveConnect): Google's page for Drive (the app's account, round 23), then
 * [onResult] with how it ended. On iPhone the consent is the page itself (Android's NeedsConsent step has no part here).
 */
@Composable
fun rememberDriveConnect(graph: CommunityGraph, onResult: (com.justdataplease.boringtravel.google.DriveStep) -> Unit): () -> Unit {
    val latest by androidx.compose.runtime.rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    return remember(graph, scope) {
        {
            scope.launch {
                val google = graph.google
                latest(google?.connectDrive() ?: com.justdataplease.boringtravel.google.DriveStep.Failed("Google sign-in isn't set up in this build."))
            }
        }
    }
}

/**
 * "just now", "5 minutes ago", "yesterday" (Android's PostSyncSheet: DateUtils.getRelativeTimeSpanString to the minute,
 * its first letter made small): minutes and hours under a day, then calendar days under a week ("yesterday", "3 days
 * ago"), then the date with its year ("Nov 12, 2026" in English, in the phone's own order elsewhere), all in the phone's
 * language (round 26), as DateUtils writes them. "just now" is Android's own English.
 */
internal fun ago(at: Long): String {
    val now = com.justdataplease.boringtravel.time.nowMs()
    if (now - at < MINUTE) return "just now"
    return relative(at, now).replaceFirstChar { it.lowercase() }
}

/**
 * Android's DateUtils.getRelativeTimeSpanString(at, now, MINUTE_IN_MILLIS) for a moment in the past, in language [tag]
 * (the phone's): ICU's long relative words (compat's [com.justdataplease.boringtravel.compat.relativeAgo]), the day's
 * own name for yesterday and today with its first letter capital as Android's sentence start, the date past a week.
 */
internal fun relative(at: Long, now: Long, tag: String = com.justdataplease.boringtravel.compat.phoneLanguageTag()): String {
    val d = (now - at).coerceAtLeast(0)
    return when {
        d < HOUR -> com.justdataplease.boringtravel.compat.relativeAgo(d / MINUTE, com.justdataplease.boringtravel.compat.RelativeUnit.MINUTES, tag)
        d < DAY -> com.justdataplease.boringtravel.compat.relativeAgo(d / HOUR, com.justdataplease.boringtravel.compat.RelativeUnit.HOURS, tag)
        d < WEEK -> {
            val days = com.justdataplease.boringtravel.time.localDateAt(now, null).toEpochDay() -
                com.justdataplease.boringtravel.time.localDateAt(at, null).toEpochDay()
            when (days) {
                0L, 1L -> com.justdataplease.boringtravel.compat.relativeDay(days.toInt(), tag).replaceFirstChar { it.uppercaseChar() }
                else -> com.justdataplease.boringtravel.compat.relativeAgo(days, com.justdataplease.boringtravel.compat.RelativeUnit.DAYS, tag)
            }
        }
        // FORMAT_SHOW_DATE | FORMAT_SHOW_YEAR | FORMAT_ABBREV_MONTH: "Nov 12, 2026" (the phone's own order: "12 Nov 2026").
        else -> com.justdataplease.boringtravel.compat.skeletonDate("yMMMd", com.justdataplease.boringtravel.time.localDateAt(at, null).toEpochDay(), tag)
    }
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val WEEK = 7 * DAY

/** Where the community's questions and problems go (Apple's rule 1.2): the Android app's AboutApp.CONTACT. */
val COMMUNITY_CONTACT: String get() = AiReport.CONTACT

/**
 * "Something wrong? Write to …": a way to reach the developer in the community's screens, which Apple's rule 1.2 asks
 * for beside Report and Block (iPhone only; Android has none yet). A tap writes a mail.
 */
@Composable
fun CommunityContact(modifier: Modifier = Modifier) {
    val phone = LocalPhone.current
    Text(
        "Something wrong? Write to $COMMUNITY_CONTACT",
        modifier.heightIn(min = 40.dp).clickable(role = Role.Button, onClickLabel = "Write a mail") {
            phone.openLink("mailto:$COMMUNITY_CONTACT?subject=BoringTravel%20community") {}
        }.padding(vertical = 10.dp),
        style = Bt.text.fine,
        color = Bt.colors.accentInk,
    )
}
