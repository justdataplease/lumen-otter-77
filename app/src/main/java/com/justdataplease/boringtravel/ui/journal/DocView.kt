package com.justdataplease.boringtravel.ui.journal

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.repo.JournalVideos
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.doc.PhotoPlacement
import com.justdataplease.boringtravel.doc.horizontalPosition
import com.justdataplease.boringtravel.graph
import com.justdataplease.boringtravel.ui.components.MediaImage
import com.justdataplease.boringtravel.ui.community.ClipViewer
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.BtColors
import com.justdataplease.boringtravel.ui.theme.BtFonts
import com.justdataplease.boringtravel.ui.theme.BtLooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/*
 * The entry document drawn in Compose: shared by the entry view and the editor (photos, text style), so what you
 * write is what you read.
 */

/** Body text of an entry in its chosen font (or the look's body face). Caveat needs more size for the same weight. */
@Composable
fun docTextStyle(font: String?): TextStyle {
    val family = BtFonts.journalFont(font) ?: Bt.tokens.type.body
    val handwriting = family == BtFonts.caveat
    return Bt.text.body.copy(
        fontFamily = family,
        fontSize = if (handwriting) 21.sp else 16.sp,
        lineHeight = if (handwriting) 27.sp else 26.sp,
    )
}

/** Heading text in the entry's font: level 2 biggest. */
fun headingStyle(base: TextStyle, level: Int): TextStyle {
    val scale = when (level) {
        2 -> 1.32f
        3 -> 1.15f
        else -> 1.04f
    }
    return base.copy(fontWeight = FontWeight.SemiBold, fontSize = base.fontSize * scale, lineHeight = base.fontSize * scale * 1.3f)
}

@Composable
fun DocView(
    doc: EntryDoc,
    modifier: Modifier = Modifier,
    onToggleCheck: ((blockIndex: Int, itemIndex: Int) -> Unit)? = null,
) {
    val base = docTextStyle(doc.font)
    val pieces = remember(doc) { JournalLogic.pieces(doc.blocks) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        pieces.forEach { piece ->
            when (piece) {
                is JournalLogic.Piece.Items -> DocList(piece, base)
                is JournalLogic.Piece.One -> DocBlock(piece.index, piece.block, base, onToggleCheck)
            }
        }
    }
}

@Composable
private fun DocBlock(index: Int, b: Block, base: TextStyle, onToggleCheck: ((Int, Int) -> Unit)?) {
    val c = Bt.colors
    when (b) {
        is Block.Paragraph -> HtmlText(b.html, base)
        is Block.Heading -> Text(b.text, style = headingStyle(base, b.level))
        is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.route))
            HtmlText(b.html, base.copy(fontStyle = FontStyle.Italic), Modifier.padding(start = 14.dp))
        }
        is Block.Photo -> DocPhoto(b)
        is Block.Album -> AlbumView(b)
        is Block.Checklist -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            b.items.forEachIndexed { i, item ->
                CheckLine(
                    text = item.text,
                    done = item.done,
                    style = base,
                    onClick = onToggleCheck?.let { toggle -> { toggle(index, i) } },
                )
            }
        }
        is Block.Place -> PlaceChip(b)
        Block.Divider -> HorizontalDivider(Modifier.padding(vertical = 6.dp), color = c.line)
        Block.PageBreak -> Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider(Modifier.weight(1f), color = c.line)
            Text("New printed page", style = Bt.text.fine)
            HorizontalDivider(Modifier.weight(1f), color = c.line)
        }
        is Block.Unknown -> Unit
    }
}

@Composable
private fun DocList(piece: JournalLogic.Piece.Items, base: TextStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        piece.items.forEachIndexed { n, (_, p) ->
            Row {
                Text(
                    if (piece.style == ListStyle.Bullet) "•" else "${n + 1}.",
                    Modifier.width(26.dp),
                    style = base.copy(color = Bt.colors.muted),
                )
                HtmlText(p.html, base, Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun CheckLine(text: String, done: Boolean, style: TextStyle, onClick: (() -> Unit)?) {
    val c = Bt.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.heightIn(min = 48.dp).toggleable(value = done, role = Role.Checkbox, onValueChange = { onClick() }) else Modifier)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (done) BtIcons.CheckBox else BtIcons.CheckBoxEmpty,
            if (done) "Done" else "Not done",
            Modifier.padding(top = 2.dp).size(22.dp),
            tint = if (done) c.route else c.muted,
        )
        Text(
            text,
            style = style.copy(
                color = if (done) c.muted else c.ink,
                textDecoration = if (done) TextDecoration.LineThrough else null,
            ),
        )
    }
}

@Composable
fun PlaceChip(place: Block.Place, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = Bt.colors
    val context = LocalContext.current
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(btControlShape())
            .background(c.chip)
            .clickable(role = Role.Button) { if (onClick != null) onClick() else openInMaps(context, place) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(BtIcons.Place, null, Modifier.size(16.dp), tint = c.route)
        Text(place.name, style = Bt.text.label, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun openInMaps(context: Context, place: Block.Place) {
    val q = Uri.encode(place.name)
    val uri = if (place.lat != null && place.lng != null) "geo:${place.lat},${place.lng}?q=${place.lat},${place.lng}($q)" else "geo:0,0?q=$q"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** A paragraph of the HTML subset, with links that open in the browser. */
@Composable
fun HtmlText(html: String, style: TextStyle, modifier: Modifier = Modifier) {
    val c = Bt.colors
    val text = remember(html, c) { annotated(HtmlRuns.parse(html), c) }
    Text(text, modifier, style = style)
}

/** Runs → AnnotatedString. Colours written in one look are shown in the same role of the current look. */
fun annotated(runs: List<HtmlRuns.Run>, c: BtColors): AnnotatedString = buildAnnotatedString {
    for (r in runs) {
        val s = r.style
        val decoration = when {
            s.underline && s.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            s.underline -> TextDecoration.Underline
            s.strike -> TextDecoration.LineThrough
            else -> null
        }
        val span = SpanStyle(
            color = s.color?.let { lookColor(it, c) } ?: Color.Unspecified,
            background = s.background?.let { Color(it) } ?: Color.Unspecified,
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            textDecoration = decoration,
            fontSize = when (val size = s.size) {
                is HtmlRuns.Size.Px -> size.value.sp
                is HtmlRuns.Size.Em -> size.value.em
                null -> TextUnit.Unspecified
            },
        )
        val href = s.href
        if (href != null) {
            withLink(LinkAnnotation.Url(href, TextLinkStyles(SpanStyle(color = c.route, textDecoration = TextDecoration.Underline)))) {
                withStyle(span) { append(r.text) }
            }
        } else {
            withStyle(span) { append(r.text) }
        }
    }
}

private val colorRoles: List<(BtColors) -> Color> = listOf({ it.route }, { it.danger }, { it.accentInk }, { it.muted }, { it.accent })

/** Retired palette colours still follow their semantic role when an older journal is reopened. */
private val retiredRoles: Map<Long, (BtColors) -> Color> = mapOf(
    0x0E8A5CL to { it.route }, 0xB3261EL to { it.danger }, 0x0E6A48L to { it.accentInk }, 0x5B6268L to { it.muted }, 0x7FD8B2L to { it.accent },
    0x5B6585L to { it.muted },
    0x7A6650L to { it.muted },
)

/** Maps a stored text colour that was one of a look's role colours to that role in the current look. */
private fun lookColor(argb: Long, c: BtColors): Color {
    val color = Color(argb)
    val rgb = argb and 0xFFFFFF
    for (look in BtLooks.all) {
        for (role in colorRoles) {
            val roleColor = role(look.colors)
            if (rgbOf(roleColor) == rgb) return role(c)
        }
    }
    // The retired looks (Notebook … Night Route) list their role colours in the same order as colorRoles.
    for (legacy in BtLooks.legacyRoleColors) {
        legacy.forEachIndexed { i, roleColor -> if (i < colorRoles.size && rgbOf(roleColor) == rgb) return colorRoles[i](c) }
    }
    retiredRoles[rgb]?.let { return it(c) }
    return color
}

private fun rgbOf(color: Color): Long =
    ((color.red * 255f + 0.5f).toLong() shl 16) or ((color.green * 255f + 0.5f).toLong() shl 8) or (color.blue * 255f + 0.5f).toLong()

// ---- Photos ----

/** The media row for [id], loaded once. */
@Composable
fun rememberMedia(id: String?): Media? {
    val graph = LocalContext.current.graph
    val media by produceState<Media?>(null, id) { value = id?.let { graph.media.get(it) } }
    return media
}

/**
 * The shapes a picture is shown in, as width / height: from twice as tall as wide to four times as wide as tall
 * (a banner or a panorama, as copied from a site, fills its width without empty bands). Beyond that it is fitted
 * inside the nearest of the two.
 */
private const val TALLEST = 0.5f
private const val WIDEST = 4f

/** Width / height of a photo: from the stored size, else read from the file; 4:3 until known. */
@Composable
fun rememberAspect(media: Media?): Float {
    val context = LocalContext.current
    val aspect by produceState(4f / 3f, media?.id) {
        val m = media ?: return@produceState
        value = if (m.width > 0 && m.height > 0) {
            (m.width.toFloat() / m.height).coerceIn(TALLEST, WIDEST)
        } else {
            withContext(Dispatchers.IO) {
                val file = File(context.filesDir, m.localPath)
                if (m.isVideo) JournalVideos.size(file)?.let { (w, h) -> (w.toFloat() / h).coerceIn(TALLEST, WIDEST) } else fileAspect(file)
            } ?: (4f / 3f)
        }
    }
    return aspect
}

private fun fileAspect(file: File): Float? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, o)
    var w = o.outWidth
    var h = o.outHeight
    if (w <= 0 || h <= 0) return@runCatching null
    val rot = ExifInterface(file).rotationDegrees
    if (rot == 90 || rot == 270) w = h.also { h = w }
    (w.toFloat() / h).coerceIn(TALLEST, WIDEST)
}.getOrNull()

/** A photo block in the text column: its width, alignment, frame, angle and caption. A video plays on a tap. */
@Composable
fun DocPhoto(block: Block.Photo, modifier: Modifier = Modifier) {
    val media = rememberMedia(block.mediaId)
    val aspect = rememberAspect(media)
    var playing by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val placement = PhotoPlacement.of(block, aspect)
        val w = maxWidth * placement.width
        val envelopeWidth = maxWidth * placement.envelope
        Box(
            Modifier.fillMaxWidth().padding(vertical = tiltRoom(w, block.rotation)),
            contentAlignment = BiasAlignment(block.horizontalPosition() * 2f - 1f, 0f),
        ) {
            Box(Modifier.width(envelopeWidth), contentAlignment = Alignment.Center) {
                val play = if (media?.isVideo == true) Modifier.clickable(role = Role.Button, onClickLabel = "Play video") { playing = true } else Modifier
                FramedPhoto(media, aspect, block.frame, block.rotation, block.caption, Modifier.width(w).then(play))
            }
        }
    }
    if (playing && media != null) PlayVideo(media, onClose = { playing = false })
}

/**
 * Journal video [media] full screen with sound and simple controls (the community's clip player). A shared trip's
 * video that hasn't arrived on this phone yet says so instead.
 */
@Composable
fun PlayVideo(media: Media, onClose: () -> Unit) {
    val context = LocalContext.current
    val file = remember(media.localPath) { File(context.filesDir, media.localPath) }
    val ready by produceState<Boolean?>(null, file) { value = withContext(Dispatchers.IO) { file.isFile && file.length() > 0 } }
    when (ready) {
        null -> Unit
        true -> ClipViewer(file, onClose, loop = false, controls = true)
        false -> LaunchedEffect(file) {
            Toast.makeText(context, "This video isn't on this phone yet. Try again in a moment.", Toast.LENGTH_SHORT).show()
            onClose()
        }
    }
}

/** Extra space above and below a tilted photo so its corners don't touch the text. */
fun tiltRoom(width: Dp, rotation: Float): Dp = (width * abs(sin(Math.toRadians(rotation.toDouble()))).toFloat() * 0.5f).coerceAtLeast(0.dp)

/**
 * One photo with its frame, drawn in the current look's colours: None (the look's corners), Polaroid (paper frame
 * with the caption in the display face), Tape (a strip of the accent across the top), Rounded (soft corners).
 * [outline] draws the editor's selection ring.
 */
@Composable
fun FramedPhoto(
    media: Media?,
    aspect: Float,
    frame: PhotoFrame,
    rotation: Float,
    caption: String,
    modifier: Modifier = Modifier,
    outline: Color? = null,
) {
    val t = Bt.tokens
    val c = t.colors
    val turned = modifier
        .rotate(rotation)
        .then(
            if (outline == null) Modifier else Modifier.drawWithContent {
                drawContent()
                val inset = 6.dp.toPx()
                drawRoundRect(
                    outline,
                    topLeft = Offset(-inset, -inset),
                    size = Size(size.width + inset * 2, size.height + inset * 2),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(2.dp.toPx()),
                )
            },
        )
    val image = Modifier.fillMaxWidth().aspectRatio(aspect)
    val captionText = caption.trim()
    when (frame) {
        PhotoFrame.Polaroid -> {
            val paper = if (t.photo.frameColor.alpha > 0f) t.photo.frameColor else c.surface
            Column(
                turned
                    .shadow(6.dp, RectangleShape, ambientColor = t.photo.shadowColor, spotColor = t.photo.shadowColor)
                    .background(paper)
                    .padding(start = 8.dp, top = 8.dp, end = 8.dp),
            ) {
                MediaImage(media, image.clip(RoundedCornerShape(2.dp)), contentDescription = captionText.ifBlank { "Photo" }, contentScale = ContentScale.Fit)
                Box(Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    if (captionText.isNotEmpty()) {
                        Text(
                            Bt.text.display(captionText),
                            style = Bt.text.title.copy(fontFamily = t.type.display, fontWeight = t.type.captionWeight, fontSize = t.type.displaySize(19.sp), lineHeight = t.type.displaySize(21.sp)),
                            color = c.ink,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        PhotoFrame.Tape -> Column(turned) {
            Box {
                MediaImage(media, image.shadow(2.dp, RoundedCornerShape(3.dp)).clip(RoundedCornerShape(3.dp)), contentDescription = captionText.ifBlank { "Photo" }, contentScale = ContentScale.Fit)
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-10).dp)
                        .rotate(-4f)
                        .size(width = 76.dp, height = 22.dp)
                        .background(c.accent.copy(alpha = 0.62f)),
                )
            }
            PhotoCaption(captionText)
        }
        PhotoFrame.Rounded -> Column(turned) {
            val shape = RoundedCornerShape(maxOf(t.radius, 18.dp) * 1.3f)
            MediaImage(media, image.clip(shape), contentDescription = captionText.ifBlank { "Photo" }, contentScale = ContentScale.Fit)
            PhotoCaption(captionText)
        }
        PhotoFrame.None -> Column(turned) {
            MediaImage(media, image.clip(RoundedCornerShape(t.photo.corner)), contentDescription = captionText.ifBlank { "Photo" }, contentScale = ContentScale.Fit)
            PhotoCaption(captionText)
        }
    }
}

@Composable
private fun PhotoCaption(caption: String) {
    if (caption.isEmpty()) return
    val t = Bt.tokens
    if (t.look.isNotebook) {
        Text(
            caption,
            Modifier.fillMaxWidth().padding(top = 4.dp),
            style = Bt.text.title.copy(fontFamily = t.type.display, fontWeight = t.type.captionWeight, fontSize = t.type.displaySize(18.sp), lineHeight = t.type.displaySize(21.sp)),
            color = t.colors.ink,
            textAlign = TextAlign.Center,
        )
    } else {
        Text(caption, Modifier.padding(top = 6.dp), style = Bt.text.mono)
    }
}
