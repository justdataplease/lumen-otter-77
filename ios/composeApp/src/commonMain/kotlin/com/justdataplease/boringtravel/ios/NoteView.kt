package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.album.AlbumRect
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.PhotoAlbums
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.doc.PhotoPlacement
import com.justdataplease.boringtravel.doc.horizontalPosition
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.journal.HtmlRuns
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.BtColors
import com.justdataplease.boringtravel.ui.theme.BtFonts
import com.justdataplease.boringtravel.ui.theme.BtLooks
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * A note's document drawn in Compose, as the Android app draws it (ui/journal/DocView.kt, DocAlbum.kt): shared by the
 * note, the editor and its layout sheet, so what is written is what is read.
 */

/** Body text of a note in its chosen font (or the look's body face). Caveat needs more size for the same weight. */
@Composable
fun docTextStyle(font: String?): TextStyle {
    val family = BtFonts.journalFont(font) ?: Bt.tokens.type.body
    val handwriting = family == BtFonts.caveat
    return Bt.text.body.copy(fontFamily = family, fontSize = if (handwriting) 21.sp else 16.sp, lineHeight = if (handwriting) 27.sp else 26.sp)
}

/** Heading text in the note's font: level 2 biggest. */
fun headingStyle(base: TextStyle, level: Int): TextStyle {
    val scale = headingScale(level)
    return base.copy(fontWeight = FontWeight.SemiBold, fontSize = base.fontSize * scale, lineHeight = base.fontSize * scale * 1.3f)
}

/**
 * Note [doc] block by block. [onToggleCheck] lets a checklist's boxes be ticked (the note's author only); [onOpen] is
 * a tap on a photo or video (its id).
 */
@Composable
fun DocView(
    doc: EntryDoc,
    modifier: Modifier = Modifier,
    onToggleCheck: ((blockIndex: Int, itemIndex: Int) -> Unit)? = null,
    onOpen: ((String) -> Unit)? = null,
) {
    val base = docTextStyle(doc.font)
    val pieces = remember(doc) { DocPieces.pieces(doc.blocks) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        pieces.forEach { piece ->
            when (piece) {
                is DocPieces.Piece.Items -> DocList(piece, base)
                is DocPieces.Piece.One -> DocBlock(piece.index, piece.block, base, onToggleCheck, onOpen)
            }
        }
    }
}

/** A note in pieces as the view lays it out: one block, or a run of list items drawn as one list (the Android app's JournalLogic.pieces). */
object DocPieces {
    sealed interface Piece {
        data class One(val index: Int, val block: Block) : Piece
        data class Items(val style: ListStyle, val items: List<Pair<Int, Block.Paragraph>>) : Piece
    }

    fun pieces(blocks: List<Block>): List<Piece> {
        val out = ArrayList<Piece>()
        var run: MutableList<Pair<Int, Block.Paragraph>>? = null
        var style: ListStyle? = null
        fun flush() {
            val r = run
            val s = style
            if (r != null && s != null && r.isNotEmpty()) out += Piece.Items(s, r.toList())
            run = null
            style = null
        }
        blocks.forEachIndexed { i, b ->
            val list = (b as? Block.Paragraph)?.list
            if (b is Block.Paragraph && list != null) {
                if (list != style) flush()
                if (run == null) {
                    run = ArrayList()
                    style = list
                }
                run?.add(i to b)
            } else {
                flush()
                out += Piece.One(i, b)
            }
        }
        flush()
        return out
    }
}

@Composable
private fun DocBlock(index: Int, b: Block, base: TextStyle, onToggleCheck: ((Int, Int) -> Unit)?, onOpen: ((String) -> Unit)?) {
    val c = Bt.colors
    when (b) {
        is Block.Paragraph -> HtmlText(b.html, base)
        is Block.Heading -> Text(b.text, style = headingStyle(base, b.level))
        is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.route))
            HtmlText(b.html, base.copy(fontStyle = FontStyle.Italic), Modifier.padding(start = 14.dp))
        }
        is Block.Photo -> DocPhoto(b, onOpen = onOpen)
        is Block.Album -> AlbumView(b, onOpen = onOpen)
        is Block.Checklist -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            b.items.forEachIndexed { i, item ->
                CheckLine(item.text, item.done, base, onClick = onToggleCheck?.let { toggle -> { toggle(index, i) } })
            }
        }
        is Block.Place -> PlaceChip(b)
        Block.Divider -> HorizontalDivider(Modifier.padding(vertical = 6.dp), color = c.line)
        Block.PageBreak -> Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HorizontalDivider(Modifier.weight(1f), color = c.line)
            Text("New printed page", style = Bt.text.fine)
            HorizontalDivider(Modifier.weight(1f), color = c.line)
        }
        is Block.Unknown -> Unit
    }
}

@Composable
private fun DocList(piece: DocPieces.Piece.Items, base: TextStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        piece.items.forEachIndexed { n, (_, p) ->
            Row {
                Text(if (piece.style == ListStyle.Bullet) "•" else "${n + 1}.", Modifier.width(26.dp), style = base.copy(color = Bt.colors.muted))
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
        Text(text, style = style.copy(color = if (done) c.muted else c.ink, textDecoration = if (done) TextDecoration.LineThrough else null))
    }
}

/**
 * A place in a note; a tap opens it in Google Maps (the Android app opens its geo: link, which Google Maps takes on an
 * Android phone), or [onClick] in the editor.
 */
@Composable
fun PlaceChip(place: Block.Place, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = Bt.colors
    val uris = LocalUriHandler.current
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(btControlShape())
            .background(c.chip)
            .clickable(role = Role.Button) { if (onClick != null) onClick() else runCatching { uris.openUri(mapsLink(place)) } }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(BtIcons.Place, null, Modifier.size(16.dp), tint = c.route)
        Text(place.name, style = Bt.text.label, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Google Maps at [place] (its universal link: the Google Maps app when the phone has it, else its web page): the point
 * when the note has one, else the name, as Android's DocView builds its geo: link
 * ("geo:<lat>,<lng>?q=<lat>,<lng>(<name>)" or "geo:0,0?q=<name>"). Never Apple Maps.
 */
fun mapsLink(place: Block.Place): String {
    val lat = place.lat
    val lng = place.lng
    return if (lat != null && lng != null) GoogleMaps.at(lat, lng) else GoogleMaps.search(place.name)
}

/**
 * Google Maps' universal links for what Android hands to its maps app as a geo: link (a note's place, a geo: link in a
 * note's text): https://www.google.com/maps/search/?api=1&query=… with the point, else the words.
 */
object GoogleMaps {
    private const val SEARCH = "https://www.google.com/maps/search/?api=1&query="

    /** The pin at [lat], [lng] (up to 6 decimals, as the item sheet's "Open in Google Maps"). */
    fun at(lat: Double, lng: Double): String = SEARCH + PlanItemText.coordinate(lat) + "," + PlanItemText.coordinate(lng)

    /** A search for [words]. */
    fun search(words: String): String = SEARCH + urlEncode(words)

    /**
     * A geo: link ("geo:37.1,25.4", "geo:0,0?q=Halki", "geo:37.1,25.4?q=37.1,25.4(Halki)") as Google Maps' link: the
     * q's point or words, else the link's own point; null for anything else (left as it is).
     */
    fun ofGeo(link: String): String? {
        if (!link.trim().startsWith("geo:", ignoreCase = true)) return null
        val body = link.trim().substring(4)
        val point = body.substringBefore('?').substringBefore(';')
        val q = body.substringAfter('?', "").split('&').firstOrNull { it.startsWith("q=") }?.substring(2)?.let(::percentDecode)?.trim()
        fun pointOf(text: String): Pair<Double, Double>? {
            val parts = text.substringBefore('(').split(',').map { it.trim() }
            if (parts.size != 2) return null
            val a = parts[0].toDoubleOrNull() ?: return null
            val b = parts[1].toDoubleOrNull() ?: return null
            return a to b
        }
        if (!q.isNullOrBlank()) {
            pointOf(q)?.let { (a, b) -> return at(a, b) }
            return search(q)
        }
        val (a, b) = pointOf(point) ?: return null
        return if (a == 0.0 && b == 0.0) null else at(a, b)
    }

    private fun percentDecode(s: String): String {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%' && i + 2 <= s.lastIndex) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes += v.toByte()
                    i += 3
                    continue
                }
            }
            if (ch == '+') bytes += ' '.code.toByte() else ch.toString().encodeToByteArray().forEach { bytes += it }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }
}

/** [s] for a link's query, its UTF-8 bytes %-encoded outside letters, digits and -._~. */
fun urlEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val ch = (b.toInt() and 0xFF).toChar()
        if (ch.isLetterOrDigit() && ch.code < 128 || ch in "-._~") append(ch)
        else append('%').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
    }
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
        val span = spanOf(r.style.copy(href = null), c)
        val href = r.style.href
        if (href != null) {
            // A geo: link opens Google Maps there (Android hands it to its maps app; an iPhone has no geo: links).
            val target = GoogleMaps.ofGeo(href) ?: href
            withLink(LinkAnnotation.Url(target, TextLinkStyles(SpanStyle(color = c.route, textDecoration = TextDecoration.Underline)))) {
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

/** A stored text colour that was one of a look's role colours, as that role in the current look (the Android app's lookColor). */
fun lookColor(argb: Long, c: BtColors): Color {
    val rgb = argb and 0xFFFFFF
    for (look in BtLooks.all) {
        for (role in colorRoles) if (rgbOf(role(look.colors)) == rgb) return role(c)
    }
    for (legacy in BtLooks.legacyRoleColors) {
        legacy.forEachIndexed { i, roleColor -> if (i < colorRoles.size && rgbOf(roleColor) == rgb) return colorRoles[i](c) }
    }
    retiredRoles[rgb]?.let { return it(c) }
    return Color(argb)
}

private fun rgbOf(color: Color): Long =
    ((color.red * 255f + 0.5f).toLong() shl 16) or ((color.green * 255f + 0.5f).toLong() shl 8) or (color.blue * 255f + 0.5f).toLong()

// ---- Photos ----

/** The shapes a picture is shown in, as width / height: twice as tall as wide to four times as wide as tall. */
private const val TALLEST = 0.5f
private const val WIDEST = 4f

/** Width / height of a photo: from its stored size, else read from its file; 4:3 until known. */
@Composable
fun rememberAspect(media: Media?): Float {
    val kit = LocalMediaKit.current
    val aspect by produceState(media?.let(::storedAspect) ?: (4f / 3f), media?.id) {
        val m = media ?: return@produceState
        if (storedAspect(m) != null || m.isVideo) return@produceState
        withContext(MediaWork) { kit.files.read(m.localPath)?.let(Pictures::size) }?.let { (w, h) -> value = (w.toFloat() / h).coerceIn(TALLEST, WIDEST) }
    }
    return aspect
}

private fun storedAspect(m: Media): Float? = if (m.width > 0 && m.height > 0) (m.width.toFloat() / m.height).coerceIn(TALLEST, WIDEST) else null

/**
 * A photo block in the text column: its width, place across, frame, angle and caption. A video plays on a tap
 * ([onOpen] with its id); a photo is only looked at, as in Android's DocPhoto.
 */
@Composable
fun DocPhoto(block: Block.Photo, modifier: Modifier = Modifier, onOpen: ((String) -> Unit)? = null) {
    val media = LocalMediaRows.current(block.mediaId)
    val aspect = rememberAspect(media)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val placement = PhotoPlacement.of(block, aspect)
        val w = maxWidth * placement.width
        val envelopeWidth = maxWidth * placement.envelope
        Box(
            Modifier.fillMaxWidth().padding(vertical = tiltRoom(w, block.rotation)),
            contentAlignment = BiasAlignment(block.horizontalPosition() * 2f - 1f, 0f),
        ) {
            Box(Modifier.width(envelopeWidth), contentAlignment = Alignment.Center) {
                val open = if (media?.isVideo == true && onOpen != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = "Play video") { onOpen(block.mediaId) }
                } else Modifier
                FramedPhoto(media, aspect, block.frame, block.rotation, block.caption, Modifier.width(w).then(open))
            }
        }
    }
}

/** Extra space above and below a tilted photo so its corners don't touch the text. */
fun tiltRoom(width: Dp, rotation: Float): Dp = (width * abs(sin(rotation * PI / 180.0)).toFloat() * 0.5f).coerceAtLeast(0.dp)

/**
 * One photo with its frame, in the current look's colours: None (the look's corners), Polaroid (paper frame with the
 * caption in the display face), Tape (a strip of the accent across the top), Rounded (soft corners). [outline] draws
 * the editor's selection ring.
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
                            color = c.ink, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        PhotoFrame.Tape -> Column(turned) {
            Box {
                MediaImage(media, image.shadow(2.dp, RoundedCornerShape(3.dp)).clip(RoundedCornerShape(3.dp)), contentDescription = captionText.ifBlank { "Photo" }, contentScale = ContentScale.Fit)
                Box(Modifier.align(Alignment.TopCenter).offset(y = (-10).dp).rotate(-4f).size(width = 76.dp, height = 22.dp).background(c.accent.copy(alpha = 0.62f)))
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
            caption, Modifier.fillMaxWidth().padding(top = 4.dp),
            style = Bt.text.title.copy(fontFamily = t.type.display, fontWeight = t.type.captionWeight, fontSize = t.type.displaySize(18.sp), lineHeight = t.type.displaySize(21.sp)),
            color = t.colors.ink, textAlign = TextAlign.Center,
        )
    } else {
        Text(caption, Modifier.padding(top = 6.dp), style = Bt.text.mono)
    }
}

// ---- Photo layouts ----

/**
 * [block] across the column, each photo and video in its own frame ([PhotoAlbums.grids]: the arrangement print and
 * the book use). [onPhoto] makes each one tappable (its index in the layout), [selected] rings one; without it only a
 * video answers a tap, playing ([onOpen] with its id, the note), as Android's DocAlbum. [onEmpty] shows the empty
 * spots as "+" (the editor only).
 */
@Composable
fun AlbumView(
    block: Block.Album,
    modifier: Modifier = Modifier,
    selected: Int? = null,
    onPhoto: ((Int) -> Unit)? = null,
    onEmpty: (() -> Unit)? = null,
    onOpen: ((String) -> Unit)? = null,
) {
    val grids = remember(block.layoutId, block.mediaIds) { PhotoAlbums.grids(block) }
    val c = Bt.colors
    val framesUsed = remember(block.mediaIds, block.frames) { block.mediaIds.map(block::frameOf).toSet() }
    val plain = RoundedCornerShape(albumCorner())
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val w = constraints.maxWidth.toDouble()
        val gutter = w * PhotoAlbums.GUTTER
        var top = 0.0
        val placed = grids.map { g ->
            val h = g.layout.heightFor(w, gutter)
            Triple(g, top, g.layout.place(w, h, gutter)).also { top += h + gutter }
        }
        val total = (top - gutter).coerceAtLeast(0.0)
        val room = Modifier.padding(top = if (PhotoFrame.Tape in framesUsed) TAPE_RISE else 0.dp, bottom = if (PhotoFrame.Polaroid in framesUsed) 4.dp else 0.dp)
        Box(Modifier.fillMaxWidth().then(room).height(with(density) { total.toFloat().toDp() })) {
            for ((g, y0, rects) in placed) {
                rects.forEachIndexed { i, r ->
                    val id = g.spots[i]
                    val index = g.first + i
                    val spot = Modifier.at(r, y0, density)
                    when {
                        id != null -> AlbumSpot(
                            id, block.frameOf(id), with(density) { min(r.w, r.h).toFloat().toDp() }, plain, spot, index, block.mediaIds.size,
                            ring = selected == index,
                            onTap = onPhoto?.let { tap -> { tap(index) } },
                            onPlay = onOpen?.let { open -> { open(id) } },
                        )
                        onEmpty != null -> Box(
                            spot.clip(plain).background(c.chip).border(1.dp, c.line, plain)
                                .clickable(role = Role.Button, onClickLabel = "Add photos or videos", onClick = onEmpty),
                            contentAlignment = Alignment.Center,
                        ) { Icon(BtIcons.Add, "Add photos or videos", Modifier.size(24.dp), tint = c.route) }
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
}

/** How far a strip of tape stands over the top of a layout. */
private val TAPE_RISE = 6.dp

/** One photo or video of a layout in its spot, framed like a single photo ([AlbumView]). */
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
    onPlay: (() -> Unit)? = null,
) {
    val t = Bt.tokens
    val c = t.colors
    val media = LocalMediaRows.current(id)
    val video = media?.isVideo == true
    val label = "${if (video) "Video" else "Photo"} ${index + 1} of $of"
    val shape = when (frame) {
        PhotoFrame.None -> plain
        PhotoFrame.Rounded -> RoundedCornerShape((short * 0.16f).coerceAtMost(18.dp))
        PhotoFrame.Tape -> RoundedCornerShape(3.dp)
        PhotoFrame.Polaroid -> RectangleShape
    }
    // The editor's tap picks the spot; in a note only a video plays (Android's DocAlbum), a photo is only looked at.
    val tap = when {
        onTap != null -> Modifier.clickable(role = Role.Image, onClickLabel = label, onClick = onTap)
        video && onPlay != null -> Modifier.clickable(role = Role.Button, onClickLabel = "Play video", onClick = onPlay)
        else -> Modifier
    }
    if (frame == PhotoFrame.Polaroid) {
        val pad = (short * 0.05f).coerceIn(3.dp, 8.dp)
        val paper = if (t.photo.frameColor.alpha > 0f) t.photo.frameColor else c.surface
        Box(modifier.shadow(3.dp, RectangleShape, ambientColor = t.photo.shadowColor, spotColor = t.photo.shadowColor).background(paper).then(tap)) {
            MediaImage(media, Modifier.fillMaxSize().padding(start = pad, top = pad, end = pad, bottom = pad * 3).clip(RoundedCornerShape(2.dp)), contentDescription = label)
            if (ring) Box(Modifier.matchParentSize().border(3.dp, c.route, shape))
        }
    } else {
        Box(modifier.clip(shape).then(tap)) {
            MediaImage(media, Modifier.matchParentSize(), contentDescription = label)
            if (ring) Box(Modifier.matchParentSize().border(3.dp, c.route, shape))
        }
    }
}

/** [block]'s first arrangement filling a small box (a list's photo frame), a thin gap between photos. */
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
            val spot = Modifier.at(r, 0.0, density)
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

/** Positions and sizes a spot [r] (px, [dy] down) inside its layout box. */
private fun Modifier.at(r: AlbumRect, dy: Double, density: Density): Modifier =
    offset { IntOffset(r.x.roundToInt(), (r.y + dy).roundToInt()) }.size(with(density) { r.w.toFloat().toDp() }, with(density) { r.h.toFloat().toDp() })
