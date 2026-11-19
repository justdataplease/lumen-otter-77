package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.data.SavedClip
import com.justdataplease.boringtravel.data.SavedClipIndex
import com.justdataplease.boringtravel.data.SavedClips
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoAlbums
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.time.systemZoneId
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import com.justdataplease.boringtravel.ui.components.BtFab
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.SectionLabel
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * A trip's Media tab (round 20; the Android app's ui/trip/TripMedia.kt): a gallery. The trip's saved clips first
 * ("Clips · 2": a tap plays one in the phone's player, Share, Delete), then every photo and video of the trip by day, three
 * squares a row ("Day 3 · Tue 14 Mar"), a tap opening its note, press and hold deleting one of the traveller's own; "Add
 * media" opens a new note with the photo library. Making a clip or a book, Share and Post to community are in the trip's ⋮
 * menu, as on Android.
 */

/** A photo in a media grid, with the note it opens ([entryId] null: a photo of the trip in no note); Android's PhotoCell. */
class PhotoCell(val mediaId: String, val entryId: String?, val time: Long, val zoneId: String?, val media: Media) {
    val date: LocalDate get() = localDateAt(time, zoneId)
}

/** The Android app's JournalLogic photo grid rules, on this app's notes. */
object MediaGrid {
    /**
     * Every photo placed in [notes] that still has its row, newest first (capture time, else the note's time); with
     * [looseZone], the trip's photos in no note too (Android's JournalLogic.photoCells).
     */
    fun photoCells(notes: List<NoteInfo>, media: List<Media>, looseZone: String? = null): List<PhotoCell> {
        val byId = media.associateBy { it.id }
        val placed = notes.flatMap { info ->
            info.photoIds.mapNotNull { id -> byId[id]?.let { m -> PhotoCell(id, info.entry.id, m.takenAt ?: info.entry.occurredAt, info.entry.zoneId, m) } }
        }
        val inEntries = placed.mapTo(HashSet()) { it.mediaId }
        val loose = if (looseZone == null) emptyList() else media
            .filter { it.entryId == null && it.deletedAt == null && it.mime.startsWith("image/") && it.id !in inEntries }
            .map { m -> PhotoCell(m.id, null, m.takenAt ?: m.createdAt, looseZone, m) }
        return (placed + loose).distinctBy { it.mediaId }.sortedByDescending { it.time }
    }

    /** Photos grouped by day, days in order (a trip reads front to back). */
    fun photoDays(cells: List<PhotoCell>): List<Pair<LocalDate, List<PhotoCell>>> =
        cells.sortedBy { it.time }.groupBy { it.date }.toList().sortedBy { it.first.toEpochDay() }

    /** "Day 4" when [day] falls inside the trip's dates. */
    fun tripDay(start: LocalDate?, end: LocalDate?, day: LocalDate): Int? {
        if (start == null || day.toEpochDay() < start.toEpochDay() || (end != null && day.toEpochDay() > end.toEpochDay())) return null
        return (day.toEpochDay() - start.toEpochDay() + 1).toInt()
    }

    /**
     * [doc] without photo [mediaId] (the Android app's TripRemoval.withoutPhoto): a single photo goes; a layout keeps its
     * other photos in their order and fits its arrangement to them, turns into a single photo with one left and goes
     * with none.
     */
    fun withoutPhoto(doc: EntryDoc, mediaId: String): EntryDoc {
        if (mediaId !in doc.mediaIds()) return doc
        val blocks = doc.blocks.flatMap { b ->
            when {
                b is Block.Photo && b.mediaId == mediaId -> emptyList()
                b is Block.Album && mediaId in b.mediaIds -> {
                    val left = b.copy(mediaIds = b.mediaIds.filter { it != mediaId }, frames = b.frames - mediaId)
                    when (left.mediaIds.size) {
                        0 -> emptyList()
                        1 -> listOf(Block.Photo(left.mediaIds.single(), frame = left.frameOf(left.mediaIds.single())))
                        else -> listOf(PhotoAlbums.reflow(left, keep = PhotoAlbums.layoutOf(b)))
                    }
                }
                else -> listOf(b)
            }
        }
        return doc.copy(blocks = blocks)
    }

    /**
     * Deletes photo or video [media] (Android's Deletions.deletePhoto): out of every note of the trip it is in (layouts
     * reflow), its row marked deleted and its file freed when no other row uses it. Notes written by others stay.
     */
    fun deletePhoto(book: TripBook, files: MediaFiles, media: Media) {
        val inTrip = media.tripId?.let { book.entries(it) }.orEmpty()
        val own = media.entryId?.takeIf { id -> inTrip.none { it.id == id } }?.let { book.entry(it) }
        for (e in inTrip + listOfNotNull(own)) {
            if (e.authorId != null || e.deletedAt != null) continue
            val doc = EntryDoc.parse(e.body)
            val without = withoutPhoto(doc, media.id)
            if (without != doc) book.saveNote(e, without)
        }
        if (book.removeMedia(media.id)) freeFiles(book, files, book.snapshot.media.filter { it.id == media.id })
    }
}

/**
 * The Media tab inside a trip ([tripId]): the trip's saved clips, then its photos and videos by day. [onOpenNote] opens
 * a photo's note; [onAddMedia] a new note with the photo library; [onChanged] after a photo is deleted.
 */
@Composable
fun TripMediaTab(book: TripBook, tripId: String, revision: Int, onOpenNote: (String) -> Unit, onAddMedia: () -> Unit, onChanged: () -> Unit) {
    val trip = remember(book, tripId, revision) { book.trip(tripId) }
    val saved = LocalSavedClips.current
    LaunchedEffect(saved, revision) { saved?.refresh() }
    val all by (saved?.all ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<SavedClip>()) }).collectAsState()
    val clips = remember(all, tripId) { SavedClipIndex.forTrip(all.orEmpty(), tripId) }
    val files = LocalMediaKit.current.files
    // The trip's photos in no note show too (brought in from the phone while making the book).
    val days = remember(book, tripId, revision) {
        val media = book.snapshot.media.filter { it.tripId == tripId && it.deletedAt == null }
        MediaGrid.photoDays(MediaGrid.photoCells(tripNotes(book, tripId), media, looseZone = systemZoneId()))
    }
    val photoCount = remember(days) { days.sumOf { it.second.size } }
    val anyVideo = remember(days) { days.any { d -> d.second.any { it.media.isVideo } } }
    var deletingPhoto by remember { mutableStateOf<Media?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (clips.isNotEmpty() && saved != null) item(key = "clips", span = { GridItemSpan(maxLineSpan) }) { ClipsRow(clips, saved) }
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
                        BtButton("Add media", onClick = onAddMedia, modifier = Modifier.padding(top = 12.dp), icon = BtIcons.Photo, small = true)
                    }
                }
            }
            days.forEach { (day, cells) ->
                item(key = "day-${day.toEpochDay()}", span = { GridItemSpan(maxLineSpan) }) {
                    val n = MediaGrid.tripDay(trip?.startDate, trip?.endDate, day)
                    val label = "${Dates.weekday(day)} ${Dates.dayMonth(day)}"
                    Eyebrow(if (n != null) "Day $n · $label" else Dates.dayLong(day), Modifier.padding(top = 14.dp, bottom = 4.dp))
                }
                items(cells, key = { it.mediaId }) { cell ->
                    // Press and hold deletes one of the traveller's own photos (a shared trip's others delete theirs).
                    PhotoTile(cell.media, onLongClick = if (cell.media.authorId == null) ({ deletingPhoto = cell.media }) else null) {
                        cell.entryId?.let(onOpenNote)
                    }
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
                    MediaGrid.deletePhoto(book, files, m)
                    onChanged()
                },
                onDismiss = { deletingPhoto = null },
            )
        }
        if (days.isNotEmpty()) {
            BtFab("Add media", onClick = onAddMedia, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), icon = BtIcons.Photo)
        }
    }
}

/** A photo in a media grid: square, the theme's corner; a tap opens its note, pressing and holding [onLongClick]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoTile(media: Media, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
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

/** The trip's saved clips, in a row above the photos (Android's ClipsRow). */
@Composable
private fun ClipsRow(clips: List<SavedClip>, saved: SavedClips) {
    var deleting by remember { mutableStateOf<SavedClip?>(null) }
    var playing by remember { mutableStateOf<SavedClip?>(null) }
    val share = LocalClipDevice.current.share
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        SectionLabel("Clips · ${clips.size}")
        LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(clips, key = { it.id }) { clip ->
                ClipCard(
                    clip, saved.thumbnail(clip),
                    // Android hands a clip to the phone's video app; an iPhone has none to hand it to, so it plays in the
                    // app's own player, as a journal's video does (once, with Play/Pause, the bar and the time).
                    onPlay = { playing = clip },
                    onShare = { share.share(saved.video(clip)) },
                    onDelete = { deleting = clip },
                )
            }
        }
    }
    playing?.let { clip -> ClipViewer(saved.video(clip), onClose = { playing = null }, loop = false, controls = true) }
    deleting?.let { clip ->
        BtConfirmDialog(
            title = "Delete this clip?", body = "Are you sure you want to delete it? It is removed from this phone. Copies you shared stay.",
            confirm = "Delete", danger = true,
            onConfirm = {
                saved.delete(clip)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

/** A clip: its frame with a play mark and the length (tap to play), then the date, Share and Delete. */
@Composable
private fun ClipCard(clip: SavedClip, thumb: String?, onPlay: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val c = Bt.colors
    val shape = RoundedCornerShape(Bt.tokens.photo.corner.coerceAtMost(10.dp))
    val date = remember(clip.createdAt) { localDateAt(clip.createdAt, null) }
    val files = LocalMediaKit.current.files
    val picture by produceState<ImageBitmap?>(null, thumb) {
        value = thumb?.let { path ->
            withContext(Dispatchers.Default) {
                runCatching { files.read(path)?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() } }.getOrNull()
            }
        }
    }
    Column(Modifier.width(156.dp)) {
        Box(
            Modifier.fillMaxWidth().height(104.dp).clip(shape).background(c.chip)
                .clickable(role = Role.Button, onClickLabel = "Play clip", onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            val p = picture
            if (p != null) {
                Image(p, contentDescription = "Clip", contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
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
