package com.justdataplease.boringtravel.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.album.AlbumRect
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.PhotoAlbums
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.ui.components.MediaImage
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * A layout of photos and videos ([Block.Album]) drawn in Compose: the same arrangement the print HTML and the book
 * use ([PhotoAlbums.grids]), each one cropped to its spot and framed as the layout says. Shared by the entry view, the
 * editor and its layout sheet.
 */

/**
 * [block] across the column, each photo and video in its own frame. [onPhoto] makes each one tappable (its index in the layout), [selected] rings
 * one; without it (the entry view) a tap on a video plays it. [onEmpty] shows the empty spots as "+" (the editor only:
 * print and the entry view leave them blank).
 */
@Composable
fun AlbumView(
    block: Block.Album,
    modifier: Modifier = Modifier,
    selected: Int? = null,
    onPhoto: ((Int) -> Unit)? = null,
    onEmpty: (() -> Unit)? = null,
) {
    val grids = remember(block.layoutId, block.mediaIds) { PhotoAlbums.grids(block) }
    val c = Bt.colors
    val framesUsed = remember(block.mediaIds, block.frames) { block.mediaIds.map(block::frameOf).toSet() }
    val plain = RoundedCornerShape(albumCorner())
    val density = LocalDensity.current
    var playing by remember { mutableStateOf<Media?>(null) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val w = constraints.maxWidth.toDouble()
        val gutter = w * PhotoAlbums.GUTTER
        var top = 0.0
        val placed = grids.map { g ->
            val h = g.layout.heightFor(w, gutter)
            Triple(g, top, g.layout.place(w, h, gutter)).also { top += h + gutter }
        }
        val total = (top - gutter).coerceAtLeast(0.0)
        // Room for what a frame draws past its spot: the tape over the top edge, a polaroid's shadow underneath.
        val room = Modifier.padding(
            top = if (PhotoFrame.Tape in framesUsed) TAPE_RISE else 0.dp,
            bottom = if (PhotoFrame.Polaroid in framesUsed) 4.dp else 0.dp,
        )
        Box(Modifier.fillMaxWidth().then(room).height(with(density) { total.toFloat().toDp() })) {
            for ((g, y0, rects) in placed) {
                rects.forEachIndexed { i, r ->
                    val id = g.spots[i]
                    val index = g.first + i
                    val spot = Modifier.at(r, y0, density::toDpPx)
                    when {
                        id != null -> AlbumSpot(
                            id, block.frameOf(id), with(density) { min(r.w, r.h).toFloat().toDp() }, plain, spot, index, block.mediaIds.size,
                            ring = selected == index,
                            onTap = onPhoto?.let { tap -> { tap(index) } },
                            onPlay = { playing = it },
                        )
                        onEmpty != null -> Box(
                            spot.clip(plain).background(c.chip).border(1.dp, c.line, plain)
                                .clickable(role = Role.Button, onClickLabel = "Add photos or videos", onClick = onEmpty),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(BtIcons.Add, "Add photos or videos", Modifier.size(24.dp), tint = c.route)
                        }
                        else -> Unit
                    }
                }
            }
            // The tape goes on last, so a strip lies over the edge of the photo above it too.
            if (PhotoFrame.Tape in framesUsed) {
                for ((g, y0, rects) in placed) {
                    rects.forEachIndexed { i, r ->
                        if (g.spots[i]?.let(block::frameOf) != PhotoFrame.Tape) return@forEachIndexed
                        val tw = with(density) { (r.w * 0.34).toFloat().toDp() }.coerceIn(26.dp, 76.dp)
                        val th = tw * 0.29f
                        val x = r.x + r.w / 2 - with(density) { tw.toPx() } / 2
                        val y = r.y + y0 - with(density) { th.toPx() } * 0.45
                        Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.size(tw, th).rotate(-4f).background(c.accent.copy(alpha = 0.62f)))
                    }
                }
            }
        }
    }
    playing?.let { PlayVideo(it, onClose = { playing = null }) }
}

/** How far a strip of tape stands over the top of a layout. */
private val TAPE_RISE = 6.dp

/**
 * One photo or video of a layout in its spot ([modifier] places and sizes it; [short] is the spot's shorter side),
 * framed like a single photo: None (the look's corner, kept small: [plain]), Rounded (soft corners), Tape (the strip
 * is drawn by [AlbumView], over everything), Polaroid (a paper card with its wide foot and a shadow). [onTap] is the
 * editor's tap; without it a video plays ([onPlay]).
 */
@Composable
private fun AlbumSpot(
    id: String,
    frame: PhotoFrame,
    short: Dp,
    plain: Shape,
    modifier: Modifier,
    index: Int,
    of: Int,
    ring: Boolean,
    onTap: (() -> Unit)?,
    onPlay: (Media) -> Unit,
) {
    val t = Bt.tokens
    val c = t.colors
    val media = rememberMedia(id)
    val video = media?.isVideo == true
    val label = "${if (video) "Video" else "Photo"} ${index + 1} of $of"
    val shape = when (frame) {
        PhotoFrame.None -> plain
        PhotoFrame.Rounded -> RoundedCornerShape((short * 0.16f).coerceAtMost(18.dp))
        PhotoFrame.Tape -> RoundedCornerShape(3.dp)
        PhotoFrame.Polaroid -> RectangleShape
    }
    val tap = when {
        onTap != null -> Modifier.clickable(role = Role.Image, onClickLabel = label, onClick = onTap)
        media != null && video -> Modifier.clickable(role = Role.Button, onClickLabel = "Play video") { onPlay(media) }
        else -> Modifier
    }
    if (frame == PhotoFrame.Polaroid) {
        val pad = (short * 0.05f).coerceIn(3.dp, 8.dp)
        val paper = if (t.photo.frameColor.alpha > 0f) t.photo.frameColor else c.surface
        Box(modifier.shadow(3.dp, RectangleShape, ambientColor = t.photo.shadowColor, spotColor = t.photo.shadowColor).background(paper).then(tap)) {
            MediaImage(
                media,
                Modifier.fillMaxSize().padding(start = pad, top = pad, end = pad, bottom = pad * 3).clip(RoundedCornerShape(2.dp)),
                contentDescription = label,
            )
            if (ring) Box(Modifier.matchParentSize().border(3.dp, c.route, shape))
        }
    } else {
        Box(modifier.clip(shape).then(tap)) {
            MediaImage(media, Modifier.matchParentSize(), contentDescription = label)
            if (ring) Box(Modifier.matchParentSize().border(3.dp, c.route, shape))
        }
    }
}

/**
 * [block]'s first arrangement filling a small box (a list's photo frame): the shape bounds dropped so it fills the
 * box, a thin gap between photos.
 */
@Composable
fun AlbumMini(block: Block.Album, modifier: Modifier = Modifier) {
    val grid = remember(block) { PhotoAlbums.grids(block).firstOrNull() } ?: return
    val layout = remember(grid) { grid.layout.copy(minAspect = 0.0, maxAspect = Double.POSITIVE_INFINITY) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toDouble()
        val h = constraints.maxHeight.toDouble()
        val gap = with(density) { 2.dp.toPx() }.toDouble()
        layout.place(w, h, gap).forEachIndexed { i, r ->
            val spot = Modifier.at(r, 0.0, density::toDpPx)
            val id = grid.spots[i]
            if (id != null) MediaImage(id, spot, contentDescription = null) else Box(spot.background(Bt.colors.chip))
        }
    }
}

/** The corner of a photo in a layout: the look's photo corner, kept small so the gutters stay straight lines. */
@Composable
private fun albumCorner(): Dp {
    val corner = Bt.tokens.photo.corner
    return if (corner > 8.dp) 8.dp else corner
}

private fun androidx.compose.ui.unit.Density.toDpPx(px: Double): Dp = px.toFloat().toDp()

/** Positions and sizes a spot [r] (px, [dy] down) inside its layout box. */
private fun Modifier.at(r: AlbumRect, dy: Double, toDp: (Double) -> Dp): Modifier =
    offset { IntOffset(r.x.roundToInt(), (r.y + dy).roundToInt()) }.size(toDp(r.w), toDp(r.h))
