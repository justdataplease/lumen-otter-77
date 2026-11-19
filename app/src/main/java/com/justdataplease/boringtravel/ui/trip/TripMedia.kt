package com.justdataplease.boringtravel.ui.trip

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.SavedClip
import com.justdataplease.boringtravel.data.SavedClipIndex
import com.justdataplease.boringtravel.data.SavedClips
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import com.justdataplease.boringtravel.ui.components.BtFab
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.MediaImage
import com.justdataplease.boringtravel.ui.components.SectionLabel
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.journal.EntryInfo
import com.justdataplease.boringtravel.ui.journal.JournalLogic
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * The Media tab inside a trip: a gallery (owner, 2026-11-12: "cleanup in trip media … create clip, video, PDF etc.:
 * it's just a gallery"). The saved clips (tap to play, Share, Delete), then every photo and video of the trip, by day
 * (tap opens its note, press and hold deletes); "Add media" opens a new note's picker, photos and videos together
 * (owner, 2026-11-14). Making a clip or a book, Share and Post to community are in the trip's ⋮ menu.
 */
@Composable
fun TripMediaTab(graph: AppGraph, actions: AppActions, tripId: String) {
    val trip by remember(tripId) { graph.trips.observeTrip(tripId) }.collectAsStateWithLifecycle(initialValue = null)
    val infos by remember(tripId) { graph.journal.observeForTrip(tripId).map { l -> l.map(::EntryInfo) }.flowOn(Dispatchers.Default) }
        .collectAsStateWithLifecycle(initialValue = null)
    val media by remember(tripId) { graph.media.observeForTrip(tripId) }.collectAsStateWithLifecycle(initialValue = null)
    val saved = remember(graph) { SavedClips.of(graph.app) }
    val clips by remember(tripId) { saved.observe(tripId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val list = infos ?: return
    val all = media ?: return
    // The trip's photos that are in no entry show too (brought in from the phone while making the book).
    val days = remember(list, all) { JournalLogic.photoDays(JournalLogic.photoCells(list, all, looseZone = ZoneId.systemDefault().id)) }
    val photoCount = remember(days) { days.sumOf { it.second.size } }
    val anyVideo = remember(days) { days.any { d -> d.second.any { it.media.isVideo } } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deletingPhoto by remember { mutableStateOf<Media?>(null) }
    val t = trip
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (clips.isNotEmpty()) item(key = "clips", span = { GridItemSpan(maxLineSpan) }) {
                ClipsRow(
                    clips, saved,
                    onPlay = { context.startSafely(saved.playIntent(it)) },
                    onShare = { context.startSafely(saved.shareIntent(it)) },
                    onDelete = saved::delete,
                )
            }
            item(key = "photos", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = if (clips.isEmpty()) 14.dp else 22.dp, bottom = 4.dp)) {
                    SectionLabel(
                        when {
                            photoCount == 0 -> "Photos"
                            anyVideo -> "Photos and videos · $photoCount"
                            else -> "Photos · $photoCount"
                        },
                    )
                    if (days.isEmpty()) {
                        Text("Photos and videos you add to this trip's journal show here.", Modifier.padding(top = 4.dp), style = Bt.text.sub)
                        BtButton(
                            "Add media", onClick = { actions.write(tripId = tripId, attachPhotos = true) },
                            modifier = Modifier.padding(top = 12.dp), icon = BtIcons.Photo, small = true,
                        )
                    }
                }
            }
            days.forEach { (day, cells) ->
                item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                    val n = JournalLogic.tripDay(t?.startDate, t?.endDate, day)
                    val label = "${Dates.weekday(day)} ${Dates.dayMonth(day)}"
                    Eyebrow(if (n != null) "Day $n · $label" else Dates.dayLong(day), Modifier.padding(top = 14.dp, bottom = 4.dp))
                }
                items(cells, key = { it.mediaId }) { cell ->
                    // Press and hold deletes one of the traveller's own photos (a shared trip's others delete theirs).
                    PhotoTile(
                        cell.media, onLongClick = if (cell.media.authorId == null) ({ deletingPhoto = cell.media }) else null,
                    ) { cell.entryId?.let(actions::openEntry) }
                }
            }
        }
        deletingPhoto?.let { m ->
            BtConfirmDialog(
                title = if (m.isVideo) "Delete this video?" else "Delete this photo?",
                body = "Are you sure you want to delete it? It goes from its note, the trip's cover and the book. Your phone's gallery isn't touched.",
                confirm = "Delete", danger = true,
                onConfirm = {
                    deletingPhoto = null
                    scope.launch { graph.deletions.deletePhoto(m) }
                },
                onDismiss = { deletingPhoto = null },
            )
        }
        if (days.isNotEmpty()) {
            BtFab(
                "Add media", onClick = { actions.write(tripId = tripId, attachPhotos = true) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), icon = BtIcons.Photo,
            )
        }
    }
}

/**
 * A photo in a media grid: square, the theme's corner; a tap opens its entry, pressing and holding [onLongClick]. A
 * video shows its first frame with a play mark (MediaImage).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotoTile(media: Media, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    MediaImage(
        media,
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(Bt.tokens.photo.corner.coerceAtMost(8.dp)))
            .combinedClickable(
                role = Role.Button, onClickLabel = "Open entry", onClick = onClick,
                onLongClickLabel = if (onLongClick != null) (if (media.isVideo) "Delete video" else "Delete photo") else null, onLongClick = onLongClick,
            ),
        contentDescription = "Photo",
    )
}

private fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent) }
}

/** The trip's saved clips, in a row above the photos. */
@Composable
private fun ClipsRow(
    clips: List<SavedClip>,
    saved: SavedClips,
    onPlay: (SavedClip) -> Unit,
    onShare: (SavedClip) -> Unit,
    onDelete: (SavedClip) -> Unit,
) {
    var deleting by remember { mutableStateOf<SavedClip?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        SectionLabel("Clips · ${clips.size}")
        LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(clips, key = { it.id }) { clip ->
                ClipCard(clip, saved.thumbnail(clip), onPlay = { onPlay(clip) }, onShare = { onShare(clip) }, onDelete = { deleting = clip })
            }
        }
    }
    deleting?.let { clip ->
        BtConfirmDialog(
            title = "Delete this clip?", body = "Are you sure you want to delete it? It is removed from this phone. Copies you shared stay.",
            confirm = "Delete", danger = true,
            onConfirm = {
                onDelete(clip)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * A clip: its frame with a play mark and the length (tap to play), then the date, Share and Delete (owner, 2026-11-08:
 * "I must be able to delete the videos": in plain sight, not in a menu).
 */
@Composable
private fun ClipCard(clip: SavedClip, thumb: java.io.File?, onPlay: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val c = Bt.colors
    val shape = RoundedCornerShape(Bt.tokens.photo.corner.coerceAtMost(10.dp))
    val date = remember(clip.createdAt) { Instant.ofEpochMilli(clip.createdAt).atZone(ZoneId.systemDefault()).toLocalDate() }
    Column(Modifier.width(156.dp)) {
        Box(
            Modifier.fillMaxWidth().height(104.dp).clip(shape).background(c.chip)
                .clickable(role = Role.Button, onClickLabel = "Play clip", onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                AsyncImage(model = thumb, contentDescription = "Clip", contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            } else {
                Icon(BtIcons.Movie, null, Modifier.size(28.dp), tint = c.muted)
            }
            Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0x8C000000)), contentAlignment = Alignment.Center) {
                Icon(BtIcons.Play, null, Modifier.size(20.dp), tint = Color.White)
            }
            val length = SavedClipIndex.length(clip.durationMs)
            if (length.isNotEmpty()) {
                Text(
                    length,
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x99000000))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    style = Bt.text.mono.copy(fontSize = Bt.text.eyebrow.fontSize), color = Color.White, maxLines = 1,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Dates.dayMonth(date), Modifier.weight(1f), style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BtIconButton(BtIcons.Share, "Share clip", onShare, size = 32.dp)
            BtIconButton(BtIcons.Delete, "Delete clip", onDelete, size = 32.dp, danger = true)
        }
    }
}
