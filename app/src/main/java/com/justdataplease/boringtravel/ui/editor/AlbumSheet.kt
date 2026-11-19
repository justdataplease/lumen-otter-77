package com.justdataplease.boringtravel.ui.editor

import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.justdataplease.boringtravel.album.AlbumLayout
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.doc.PhotoAlbums
import com.justdataplease.boringtravel.graph
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.journal.AlbumView
import com.justdataplease.boringtravel.ui.journal.PlayVideo
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlin.math.min

/** What a layout holds, in words: "4 photos", "2 videos", "3 photos and 1 video". Pure and unit tested. */
internal object LayoutWords {
    fun count(photos: Int, videos: Int): String {
        val p = if (photos == 1) "1 photo" else "$photos photos"
        val v = if (videos == 1) "1 video" else "$videos videos"
        return when {
            videos == 0 -> p
            photos == 0 -> v
            else -> "$p and $v"
        }
    }

    /** "photos", "videos", "photos and videos": what the layout's things are called together. */
    fun kinds(photos: Int, videos: Int): String = when {
        videos == 0 -> "photos"
        photos == 0 -> "videos"
        else -> "photos and videos"
    }
}

/** The media rows of a layout's photos and videos, by id (empty until read). */
@Composable
private fun rememberRows(ids: List<String>): Map<String, Media> {
    val graph = LocalContext.current.graph
    val rows by produceState(emptyMap<String, Media>(), ids) {
        value = ids.distinct().mapNotNull { id -> graph.media.get(id)?.let { id to it } }.toMap()
    }
    return rows
}

/**
 * A layout in the editor: its photos and videos as they will read, the empty spots as "+" ([onAdd]), and a "Layout"
 * pill. A tap on one of them or the pill opens the layout sheet ([onOpen]).
 */
@Composable
internal fun AlbumItem(vm: WriteViewModel, item: EdItem.Album, onOpen: () -> Unit, onAdd: () -> Unit) {
    val c = Bt.colors
    val focus = LocalFocusManager.current
    fun open() {
        focus.clearFocus()
        vm.activeKey = item.key
        vm.selectedPhotoKey = null
        onOpen()
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        AlbumView(item.block, onPhoto = { open() }, onEmpty = onAdd)
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(50))
                .background(c.surface.copy(alpha = 0.94f))
                .border(1.dp, c.line, RoundedCornerShape(50))
                .clickable(role = Role.Button, onClickLabel = "Change the layout") { open() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(BtIcons.Layout, null, Modifier.size(18.dp), tint = c.ink)
            Text("Layout", style = Bt.text.label, color = c.ink)
        }
    }
}

/**
 * The layout sheet: the photos and videos in their spots (tap one, then another to swap them; move it earlier or
 * later, take it out, play a video), the arrangements for this many drawn small, the frame (owner, 2026-11-14: "make
 * layout photos also have options like polaroid", "polaroid or style for each": for the one picked, else for all), add
 * more, back to single ones, move or remove the whole layout. The entry editor has no undo; each change shows at once
 * in the text behind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumSheet(vm: WriteViewModel, item: EdItem.Album, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val c = Bt.colors
    val b = item.block
    val n = b.mediaIds.size
    var picked by remember(item) { mutableStateOf<Int?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val sel = picked?.takeIf { it in b.mediaIds.indices }
    val rows = rememberRows(b.mediaIds)
    val videos = b.mediaIds.count { rows[it]?.isVideo == true }
    val kinds = LayoutWords.kinds(n - videos, videos)
    var playing by remember { mutableStateOf<Media?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Layout", Modifier.weight(1f), style = Bt.text.title)
                Text(LayoutWords.count(n - videos, videos), style = Bt.text.mono, color = c.muted)
            }
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // As wide as the sheet allows, but never taller than about half a phone screen (a tall "Stacked").
                val aspect = remember(b.layoutId, n) { PhotoAlbums.layoutOf(b)?.let(PhotoAlbums::aspect)?.toFloat() ?: 1f }
                AlbumView(
                    b,
                    Modifier.width(min(maxWidth * 0.8f, 300.dp * aspect)),
                    selected = sel,
                    onPhoto = { i ->
                        picked = when (sel) {
                            null -> i
                            i -> null
                            else -> {
                                vm.albumSwap(item, sel, i)
                                null
                            }
                        }
                    },
                    onEmpty = onAdd,
                )
            }
            Text(
                if (sel == null) "Tap one, then another to swap them." else "Tap another to swap them, or:",
                Modifier.fillMaxWidth(),
                style = Bt.text.fine,
                textAlign = TextAlign.Center,
            )
            if (sel != null) {
                rows[b.mediaIds[sel]]?.takeIf { it.isVideo }?.let { video ->
                    BtGhostButton("Play", { playing = video }, Modifier.fillMaxWidth(), icon = BtIcons.Play)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BtGhostButton("Earlier", { vm.albumMove(item, sel, sel - 1); picked = sel - 1 }, Modifier.weight(1f), icon = BtIcons.ArrowLeft, enabled = sel > 0)
                    BtGhostButton("Later", { vm.albumMove(item, sel, sel + 1); picked = sel + 1 }, Modifier.weight(1f), icon = BtIcons.ArrowRight, enabled = sel < n - 1)
                    BtGhostButton("Remove", { picked = null; vm.albumRemovePhoto(item, sel) }, Modifier.weight(1f), icon = BtIcons.Delete, danger = true)
                }
            }
            val choices = remember(n) { PhotoAlbums.choices(b) }
            if (choices.size > 1) {
                Eyebrow("Layout")
                val current = PhotoAlbums.layoutOf(b)?.id
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { l -> LayoutTile(l, l.id == current, n, Modifier.weight(1f)) { vm.albumLayout(item, l.id) } }
                }
            }
            // One row for both: the photo or video picked above gets its own frame, else they all get it.
            val one = sel?.let { b.mediaIds[it] }
            Eyebrow(
                when {
                    one == null -> "Frame for all"
                    rows[one]?.isVideo == true -> "Frame for this video"
                    else -> "Frame for this photo"
                },
            )
            FrameChips(if (one == null) b.frame else b.frameOf(one)) { vm.albumFrame(item, it, sel) }
            if (one == null) Text("Tap one above first to frame only that one.", style = Bt.text.fine)
            BtGhostButton("Add photos or videos", onAdd, Modifier.fillMaxWidth(), icon = BtIcons.Add)
            BtGhostButton("Make them single $kinds", { vm.albumToSingles(item); onDismiss() }, Modifier.fillMaxWidth(), icon = BtIcons.Photo)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Arrange", Modifier.weight(1f), style = Bt.text.label, color = c.muted)
                BtIconButton(BtIcons.Up, "Move layout up", { vm.move(item, up = true) })
                BtIconButton(BtIcons.Down, "Move layout down", { vm.move(item, up = false) })
                BtIconButton(BtIcons.Delete, "Remove layout and its $kinds", { confirmRemove = true }, danger = true)
            }
            BtButton("Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
    if (confirmRemove) {
        BtConfirmDialog(
            title = "Remove this layout?",
            body = "Are you sure you want to remove it? Its ${LayoutWords.count(n - videos, videos)} ${if (n == 1) "leaves" else "leave"} the note.",
            confirm = "Remove", danger = true,
            onConfirm = {
                confirmRemove = false
                vm.remove(item)
                onDismiss()
            },
            onDismiss = { confirmRemove = false },
        )
    }
    playing?.let { PlayVideo(it, onClose = { playing = null }) }
}

/** Arrangement [l] drawn small, its first [filled] spots darker (where the photos go), named underneath. */
@Composable
private fun LayoutTile(l: AlbumLayout, on: Boolean, filled: Int, modifier: Modifier = Modifier, onPick: () -> Unit) {
    val c = Bt.colors
    val shape = RoundedCornerShape(10.dp)
    val photo = c.ink.copy(alpha = 0.5f)
    val empty = c.line
    val aspect = remember(l) { PhotoAlbums.aspect(l).toFloat() }
    Column(
        modifier
            .clip(shape)
            .background(if (on) c.chip else c.surface)
            .border(if (on) 2.dp else 1.dp, if (on) c.route else c.line, shape)
            .semantics {
                selected = on
                contentDescription = l.name
            }
            .clickable(role = Role.RadioButton, onClickLabel = l.name, onClick = onPick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            // The arrangement in its own shape, as large as the square allows, centred.
            val w = min(size.width, size.height * aspect)
            val h = w / aspect
            val x0 = (size.width - w) / 2
            val y0 = (size.height - h) / 2
            l.place(w.toDouble(), h.toDouble(), w * PhotoAlbums.GUTTER * 2).forEachIndexed { i, r ->
                drawRect(
                    if (i < filled) photo else empty,
                    Offset(x0 + r.x.toFloat(), y0 + r.y.toFloat()),
                    Size(r.w.toFloat(), r.h.toFloat()),
                )
            }
        }
        Text(
            l.name, style = Bt.text.fine, color = if (on) c.ink else c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
