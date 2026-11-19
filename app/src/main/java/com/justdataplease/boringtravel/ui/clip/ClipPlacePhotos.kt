package com.justdataplease.boringtravel.ui.clip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.boringtravel.clip.ClipOrder
import com.justdataplease.boringtravel.clip.ClipText
import com.justdataplease.boringtravel.clip.ClipVideo
import com.justdataplease.boringtravel.clip.PhotoLayout
import com.justdataplease.boringtravel.clip.PhotoPicks
import com.justdataplease.boringtravel.clip.PlaceMedia
import com.justdataplease.boringtravel.clip.PlacePhoto
import com.justdataplease.boringtravel.clip.PlacePhotos
import com.justdataplease.boringtravel.clip.SceneKind
import com.justdataplease.boringtravel.clip.VideoTap
import com.justdataplease.boringtravel.ui.components.MediaImage
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.studio.StudioButton
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioNote
import com.justdataplease.boringtravel.ui.studio.StudioSection
import com.justdataplease.boringtravel.ui.studio.StudioSheet
import com.justdataplease.boringtravel.ui.studio.StudioText
import kotlin.math.max

private val S = StudioColors

/** Thumbnails in a row of the "Media here" grid and the Add media sheet. */
private const val COLUMNS = 4

/**
 * A place's media (owner, 2026-11-12: "in clip I only see one photo I don't seem to select how to add another or change
 * order"; 2026-11-14: "in clips videos are added as photos and have the same structure (so maybe remove photos and have
 * media and delete videos specific wording)"): every photo the place has, the ones in the clip first with their number,
 * then the videos that play after it. Tap a photo to put it in or take it out, hold and drag a ticked one to change the
 * order; tap a video to open it, its bin takes it out. Add media brings any photo or journal video of the trip here, or
 * a video from the phone. A tick the clip has no room for is refused before it is made, with the reason
 * ([PlacePhotos.fits]). With photos off for the clip, only the videos are here.
 */
@Composable
internal fun PlaceMediaPanel(ed: ClipEditing, key: String, media: EditorMedia, onChoose: (ScenePick?) -> Unit) {
    val d = ed.design
    val photosOn = d.photoMode.photos
    val model = remember(ed.plan, d, ed.scenes, key) { PlacePhotos.of(ed.plan, d, ed.scenes, key, ed.isPro) }
    val photos = if (photosOn) model.photos else emptyList()
    val videos = d.videos.filter { it.sceneKey == key }
    val work by ed.vm.mediaWork.collectAsStateWithLifecycle()
    val shown = model.photos.filter { it.shown }.map { it.id }
    var adding by remember(key) { mutableStateOf(false) }
    fun toggle(id: String) = ed.edit { PhotoPicks.toggle(it, key, shown, id) }
    if (!photosOn) StudioNote("Photos are off for this clip. Switch them on in Media for the whole clip.")
    val none = photos.isEmpty() && videos.isEmpty()
    StudioSection("Media here", value = if (none) null else PlaceMedia.countLine(model.shownCount, photos.size, videos.size)) {
        if (none) DashedNote("No media at this place yet.")
        if (photos.isNotEmpty()) {
            PickGrid(model, ed.enabled, onToggle = ::toggle, onMove = { from, to -> ed.edit { PhotoPicks.move(it, key, shown, from, to) } })
        }
        videos.forEach { v -> VideoRow(ed, v, onChoose) }
    }
    if (photosOn) {
        val unticked = model.photos.count { !it.ticked }
        val more = model.more(d.holdSeconds)
        val clipLength = ClipText.clock(ed.plan.frameCount, ed.plan.fps)
        val notes = buildList {
            if (model.photos.size > 1) add(if (model.ticked.size > 1) "Tap a photo to put it in or take it out. Hold and drag to change the order." else "Tap a photo to put it in or take it out.")
            if (model.noRoom.isNotEmpty()) add("A ticked photo with no room left doesn't show.")
            when {
                more == 0 -> add("No room for more photos in a $clipLength clip. A shorter photo pause or a longer clip makes room.")
                unticked > 0 && more < unticked -> add(if (more == 1) "Room for 1 more photo." else "Room for $more more photos.")
            }
            if (model.photos.any { it.added }) add("Take out an added photo and it goes back to its own place.")
            if (d.photoLayout == PhotoLayout.Album) add("Up to ${PhotoLayout.ALBUM_MAX} photos share a page.")
        }
        notes.forEach { StudioNote(it) }
    }
    StudioButton("Add media", { adding = true }, Modifier.fillMaxWidth(), icon = BtIcons.Add, enabled = ed.enabled)
    videoNote(work)?.let { StudioNote(it, warn = work is ClipViewModel.MediaWork.Failed) }
    if (adding) AddMediaSheet(ed, key, model, media, onToggle = ::toggle, onDismiss = { adding = false })
}

/**
 * The place's photos in rows of [COLUMNS]: a ticked one framed in yellow with its number, the others dimmed. Hold and
 * drag a ticked one to move it among the ticked ones (the order they show in); TalkBack offers Earlier and Later.
 */
@Composable
private fun PickGrid(model: PlacePhotos, enabled: Boolean, onToggle: (String) -> Unit, onMove: (Int, Int) -> Unit) {
    val ticked = model.ticked
    val density = LocalDensity.current
    val gapPx = with(density) { 6.dp.toPx() }
    // The order while a finger drags (the edit is made when it lifts).
    var order by remember(ticked) { mutableStateOf(ticked) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var start by remember { mutableIntStateOf(-1) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var pitch by remember { mutableFloatStateOf(1f) }
    val tickedNow by rememberUpdatedState(ticked)
    val onMoveNow by rememberUpdatedState(onMove)
    val byId = model.photos.associateBy { it.id }
    val shownOrder = (if (dragging != null) order else ticked) + model.photos.filter { !it.ticked }.map { it.id }
    Column(
        Modifier.fillMaxWidth()
            .onSizeChanged { pitch = max(1f, (it.width + gapPx) / COLUMNS) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // A cell's index from a point on the grid (cells are square, rows as far apart as columns).
                fun indexAt(p: Offset): Int = (p.y / pitch).toInt().coerceAtLeast(0) * COLUMNS + (p.x / pitch).toInt().coerceIn(0, COLUMNS - 1)
                detectDragGesturesAfterLongPress(
                    onDragStart = { p ->
                        val now = tickedNow
                        val i = indexAt(p)
                        if (i !in now.indices || now.size < 2) return@detectDragGesturesAfterLongPress
                        order = now
                        dragging = now[i]
                        start = i
                        pointer = p
                    },
                    onDrag = { change, amount ->
                        val id = dragging ?: return@detectDragGesturesAfterLongPress
                        change.consume()
                        pointer += amount
                        val from = order.indexOf(id)
                        val to = indexAt(pointer).coerceIn(0, order.lastIndex)
                        if (from >= 0 && to != from) order = ClipOrder.move(order, from, to)
                    },
                    onDragEnd = {
                        val id = dragging
                        val to = id?.let { order.indexOf(it) } ?: -1
                        if (id != null && start >= 0 && to >= 0 && to != start) onMoveNow(start, to)
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                )
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        shownOrder.chunked(COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { id ->
                    val p = byId.getValue(id)
                    val n = (if (dragging != null) order else ticked).indexOf(id)
                    Box(Modifier.weight(1f)) {
                        PickCell(
                            p, number = if (n >= 0) n + 1 else null, of = ticked.size, lifted = id == dragging,
                            enabled = enabled && model.fits(id), onToggle = { onToggle(id) },
                            onEarlier = if (n > 0) ({ onMove(n, n - 1) }) else null,
                            onLater = if (n in 0 until ticked.lastIndex) ({ onMove(n, n + 1) }) else null,
                        )
                    }
                }
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One photo: its number in a yellow circle when ticked ("No room" when it doesn't fit), dimmed when not, "Added" when brought here. */
@Composable
private fun PickCell(
    p: PlacePhoto, number: Int?, of: Int, lifted: Boolean, enabled: Boolean, onToggle: () -> Unit,
    onEarlier: (() -> Unit)?, onLater: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(8.dp)
    val state = when {
        p.ticked && !p.shown -> "ticked, no room in the clip"
        p.ticked -> "in the clip, $number of $of"
        enabled -> "not in the clip"
        else -> "not in the clip, no room for more"
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f)
            .graphicsLayer { if (lifted) { scaleX = 1.08f; scaleY = 1.08f; alpha = 0.92f } }
            .clip(shape)
            .then(if (p.ticked) Modifier.border(2.dp, if (p.shown) S.yellow else S.muted, shape) else Modifier)
            .clickable(enabled = enabled, onClickLabel = if (p.ticked) "Take out" else "Put in", onClick = onToggle)
            .semantics {
                role = Role.Checkbox
                contentDescription = if (p.added) "Photo added here" else "Photo"
                stateDescription = state
                customActions = listOfNotNull(
                    onEarlier?.let { f -> CustomAccessibilityAction("Earlier") { f(); true } },
                    onLater?.let { f -> CustomAccessibilityAction("Later") { f(); true } },
                )
            },
    ) {
        MediaImage(p.id, Modifier.fillMaxWidth().aspectRatio(1f).alpha(if (p.ticked) 1f else if (enabled) 0.45f else 0.25f), playBadge = false)
        if (number != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(if (p.shown) S.yellow else S.raised),
                contentAlignment = Alignment.Center,
            ) { Text("$number", style = StudioText.sub, color = if (p.shown) S.onYellow else S.ink, maxLines = 1) }
        } else {
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).border(2.dp, S.ink.copy(alpha = 0.8f), CircleShape))
        }
        val tag = when {
            p.ticked && !p.shown -> "No room"
            p.added -> "Added"
            else -> null
        }
        if (tag != null) {
            Text(
                tag, Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(S.bg.copy(alpha = 0.85f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                style = StudioText.sub, color = S.ink, maxLines = 1,
            )
        }
    }
}

/**
 * Every photo and journal video of the trip, oldest first ([PlaceMedia.tiles]), under where each is now (here, another
 * place, or none), and "From your phone" for a video from the phone's gallery. Tap a photo to show it here (it moves
 * here) or to take it out again. Tap a journal video to copy it in after this place; one already in the clip moves here
 * from another place, or comes out when it is here ([PlaceMedia.tap]). A photo the clip has no room for, or a video past
 * [ClipVideo.MAX_VIDEOS], can't be added. With photos off for the clip, only the videos are offered.
 */
@Composable
private fun AddMediaSheet(
    ed: ClipEditing, key: String, model: PlacePhotos, media: EditorMedia, onToggle: (String) -> Unit, onDismiss: () -> Unit,
) {
    val photosOn = ed.design.photoMode.photos
    val all = remember(ed.vm, photosOn) { PlaceMedia.tiles(if (photosOn) ed.vm.tripPhotos() else emptyList(), ed.vm.tripVideos()) }
    val placeOf = remember(ed.scenes) {
        ed.scenes.filter { it.kind == SceneKind.Photo && it.photoId != null }.associate { it.photoId!! to it.placeKey }
    }
    val ticked = model.ticked.toHashSet()
    val work by ed.vm.mediaWork.collectAsStateWithLifecycle()
    val videos = ed.design.videos
    val adding = work == ClipViewModel.MediaWork.Adding
    val full = videos.size >= ClipVideo.MAX_VIDEOS
    StudioSheet("Add media to ${ed.placeName(key)}", onDismiss) {
        StudioButton("From your phone", {
            onDismiss()
            media.addVideo(key)
        }, Modifier.fillMaxWidth(), icon = BtIcons.Gallery, enabled = ed.enabled && !adding && !full)
        StudioNote(if (full) "A clip takes up to ${ClipVideo.MAX_VIDEOS} videos." else "Videos play after this place, 1–10 s each, up to ${ClipVideo.MAX_VIDEOS}.")
        videoNote(work)?.let { StudioNote(it, warn = work is ClipViewModel.MediaWork.Failed) }
        if (all.isEmpty()) {
            StudioNote(if (photosOn) "This trip has no photos or videos on this phone yet." else "This trip has no videos on this phone yet.")
            return@StudioSheet
        }
        StudioNote("Tap one to add it here. One from another place moves here.")
        if (photosOn && model.more(ed.design.holdSeconds) == 0) {
            StudioNote("No room for more photos in a ${ClipText.clock(ed.plan.frameCount, ed.plan.fps)} clip. A shorter photo pause or a longer clip makes room.")
        }
        val rows = (all.size + COLUMNS - 1) / COLUMNS
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            modifier = Modifier.fillMaxWidth().height((rows * 104).coerceAtMost(460).dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(all, key = { it.id }) { tile ->
                val id = tile.id
                if (tile.video) {
                    val inClip = PlaceMedia.added(videos, id)
                    val where = inClip?.sceneKey
                    val on = where == key
                    val label = when {
                        on -> "Here"
                        where == null -> "Not added"
                        else -> ed.placeName(where)
                    }
                    val can = !adding && (inClip != null || !full)
                    AddTile(
                        id, label, here = on, on = on, can = can, enabled = ed.enabled, video = true,
                        clickLabel = when {
                            on -> "Take out"
                            inClip != null -> "Move here"
                            else -> "Add here"
                        },
                        state = if (on) "Plays here" else label,
                    ) {
                        when (val tap = PlaceMedia.tap(videos, id, key)) {
                            VideoTap.Add -> ed.vm.addJournalVideo(id, key)
                            is VideoTap.Move -> ed.vm.moveVideo(tap.id, key)
                            is VideoTap.Remove -> ed.vm.removeVideo(tap.id)
                        }
                    }
                } else {
                    val on = id in ticked
                    val where = placeOf[id]
                    val label = when {
                        where == key -> "Here"
                        where == null -> "No place"
                        else -> ed.placeName(where)
                    }
                    AddTile(
                        id, label, here = where == key, on = on, can = on || model.fits(id), enabled = ed.enabled, video = false,
                        clickLabel = if (on) "Take out" else "Add here", state = if (on) "Shows here" else label,
                    ) { onToggle(id) }
                }
            }
        }
    }
}

/**
 * One tile of the Add media sheet: the picture (a video's first frame with its play mark), a yellow frame and tick when
 * it is [on] here, dimmed when it can't be added, and where it is now under it ([label], yellow when [here]).
 */
@Composable
private fun AddTile(
    id: String, label: String, here: Boolean, on: Boolean, can: Boolean, enabled: Boolean, video: Boolean, clickLabel: String,
    state: String, onClick: () -> Unit,
) {
    Column(
        Modifier.clickable(enabled = enabled && can, onClickLabel = clickLabel, onClick = onClick)
            .semantics { stateDescription = state },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                .then(if (on) Modifier.border(2.dp, S.yellow, RoundedCornerShape(8.dp)) else Modifier),
        ) {
            MediaImage(id, Modifier.fillMaxWidth().aspectRatio(1f).alpha(if (on) 1f else if (can) 0.8f else 0.3f), playBadge = video)
            if (on) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(S.yellow),
                    contentAlignment = Alignment.Center,
                ) { Icon(BtIcons.Check, null, Modifier.size(14.dp), tint = S.onYellow) }
            }
        }
        Text(label, style = StudioText.sub, color = if (here) S.yellow else S.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
