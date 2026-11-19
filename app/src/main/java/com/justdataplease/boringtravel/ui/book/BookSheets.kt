package com.justdataplease.boringtravel.ui.book

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.toColorInt
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.album.AlbumLayout
import com.justdataplease.boringtravel.album.AlbumLayouts
import com.justdataplease.boringtravel.book.BookFonts
import com.justdataplease.boringtravel.book.BookFormat
import com.justdataplease.boringtravel.book.BookLayout
import com.justdataplease.boringtravel.book.BookPaper
import com.justdataplease.boringtravel.book.BookSpec
import com.justdataplease.boringtravel.book.Crop
import com.justdataplease.boringtravel.book.CropMath
import com.justdataplease.boringtravel.book.PagePlan
import com.justdataplease.boringtravel.book.Template
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.ui.components.MediaImage
import com.justdataplease.boringtravel.ui.export.ThemeThumbs
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.studio.StudioBtColors
import com.justdataplease.boringtravel.ui.studio.StudioButton
import com.justdataplease.boringtravel.ui.studio.StudioChip
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioGrid
import com.justdataplease.boringtravel.ui.studio.StudioNote
import com.justdataplease.boringtravel.ui.studio.StudioSection
import com.justdataplease.boringtravel.ui.studio.StudioSwitchRow
import com.justdataplease.boringtravel.ui.studio.StudioText

/*
 * What the book's panels and sheets share: the whole book's look, layout pictures, the trip's photos to pick from and
 * the crop box. All in the studio's colours.
 */

/** Theme, fonts, size, paper, bleed, pages in fours; and starting the layout again (asks first). */
@Composable
internal fun ColumnScope.LookContent(
    spec: BookSpec,
    padTo4: Boolean,
    photos: List<Media>,
    onSpec: (BookSpec) -> Unit,
    onPad: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    StudioSection("Theme") {
        StudioBtColors { ThemeThumbs(spec.theme, photos, onSelect = { onSpec(spec.copy(theme = it)) }) }
    }
    StudioSection("Fonts") {
        StudioGrid(BookFonts.choosable, 2) { f ->
            StudioChip(if (f == BookFonts.Theme) "Theme's own" else f.label, f == spec.fonts, { onSpec(spec.copy(fonts = f)) }, Modifier.fillMaxWidth())
        }
    }
    StudioSection("Size", value = spec.format.sizeText) {
        StudioGrid(BookFormat.entries, 2) { f -> StudioChip(f.label, f == spec.format, { onSpec(spec.copy(format = f)) }, Modifier.fillMaxWidth()) }
    }
    StudioSection("Paper") {
        StudioGrid(BookPaper.entries, 3) { p -> StudioChip(p.label, p == spec.paper, { onSpec(spec.copy(paper = p)) }, Modifier.fillMaxWidth()) }
        if (spec.theme == PrintTheme.Postcard || spec.theme == PrintTheme.Minimal) StudioNote("Used on notes pages.")
    }
    StudioSwitchRow("Bleed and crop marks", "3 mm extra on every edge, for print shops.", spec.bleed) { onSpec(spec.copy(bleed = it)) }
    StudioSwitchRow("Pages in fours", "Adds notes pages before the back cover, for binding.", padTo4, onPad)
    if (!confirmReset) {
        StudioButton("Start the layout again", { confirmReset = true }, Modifier.fillMaxWidth())
    } else {
        StudioNote("Lay the book out again from the trip? Your page edits go; Undo brings them back.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StudioButton("Cancel", { confirmReset = false }, Modifier.weight(1f))
            StudioButton("Start again", { confirmReset = false; onReset() }, Modifier.weight(1f), primary = true)
        }
    }
}

/**
 * Layout choices, each drawn with [preview] (the page as it would look in it, or a blank page of it) and its name;
 * [current]'s hint above them. [key] is what the previews depend on besides the template (the page).
 */
@Composable
internal fun TemplateGrid(
    current: Template?,
    layout: BookLayout,
    media: Map<String, Media>,
    key: Any?,
    preview: (Template) -> PagePlan,
    onPick: (Template) -> Unit,
) {
    current?.let { StudioNote(templateHint(it)) }
    StudioGrid(Template.choosable, 3, spacing = 8.dp) { t ->
        val on = t == current
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) StudioColors.raised else Color.Transparent)
                .clickable(role = Role.RadioButton, onClickLabel = t.label) { onPick(t) }.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val plan = remember(t, layout, key) { preview(t) }
            PageThumb(plan, layout, media, Modifier.fillMaxWidth().then(if (on) Modifier.border(2.dp, StudioColors.yellow) else Modifier))
            Text(
                t.label, Modifier.padding(top = 5.dp), style = StudioText.sub, color = if (on) StudioColors.yellow else StudioColors.soft,
                maxLines = 2, textAlign = TextAlign.Center,
            )
        }
    }
}

internal fun templateHint(t: Template): String = when (t) {
    Template.PhotoFull -> "One photo to the edges."
    Template.PhotoFullCaption -> "One photo to the edges, its caption over it."
    Template.Photo -> "One photo with a caption."
    Template.PhotoTwo -> "Two photos."
    Template.PhotoTwoSide -> "Two photos side by side."
    Template.PhotoThree -> "One big photo and two small."
    Template.PhotoThreeUp -> "Three photos the same size."
    Template.PhotoFour -> "Four photos in a grid."
    Template.PhotoText -> "A photo above the text."
    Template.PhotoTextSide -> "A photo beside the text."
    Template.Text -> "Text only."
    Template.DayOpener -> "Day number, date, place and the day's route."
    Template.Album -> "Photos in an album layout."
    Template.Map -> "The whole route."
    Template.Stats -> "Days, kilometres, countries, entries, photos."
    Template.Notes -> "A blank page to write on by hand."
    Template.TitlePage -> "Title, dates and countries."
    Template.Cover -> "Photo, title and dates."
    Template.BackCover -> "The route and the stamp."
}

/**
 * Opens the phone's photo picker to bring more photos into the trip from wherever the trip's photos are chosen (owner,
 * 2026-11-10: "when select image let me also select from device if I missed something"); null where the book has no
 * way to take them (none provided).
 */
internal val LocalAddFromPhone = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The first tile of a photo grid: "+ From phone". */
@Composable
private fun FromPhoneTile(onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.aspectRatio(1f).clip(shape).background(StudioColors.raised).border(1.dp, StudioColors.edge, shape)
            .clickable(role = Role.Button, onClickLabel = "Add photos from the phone", onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(BtIcons.Add, null, Modifier.size(22.dp), tint = StudioColors.yellow)
        Text("From phone", style = StudioText.sub, color = StudioColors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A grid with no photos to show: the line saying why, and the way to bring some in from the phone. */
@Composable
private fun NoPhotos(text: String, modifier: Modifier) {
    val fromPhone = LocalAddFromPhone.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioNote(text)
        if (fromPhone != null) StudioButton("Add from phone", fromPhone, Modifier.fillMaxWidth(), icon = BtIcons.Add, primary = true)
    }
}

/** The trip's photos as square tiles to pick from; [selected] ringed in yellow. Scrolls by itself. */
@Composable
internal fun PhotoPicker(photos: List<Media>, selected: String?, onPick: (Media) -> Unit, modifier: Modifier = Modifier, label: String = "Use this photo") {
    if (photos.isEmpty()) {
        NoPhotos("This trip has no photos yet.", modifier)
        return
    }
    val fromPhone = LocalAddFromPhone.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(72.dp),
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (fromPhone != null) item(key = "from-phone") { FromPhoneTile(fromPhone) }
        items(photos, key = { it.id }) { p ->
            val on = p.id == selected
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier.aspectRatio(1f).clip(shape).border(if (on) 3.dp else 0.dp, if (on) StudioColors.yellow else Color.Transparent, shape)
                    .clickable(role = Role.Button, onClickLabel = label) { onPick(p) },
            ) { MediaImage(p, Modifier.matchParentSize().padding(if (on) 3.dp else 0.dp).clip(RoundedCornerShape(4.dp))) }
        }
    }
}

/**
 * Pan and zoom a photo inside a frame of [aspect], as large as fits: drag to move, pinch to zoom. [onEnd] gets the
 * crop when the fingers lift, so the page follows once per gesture.
 */
@Composable
internal fun CropBox(m: Media, aspect: Double, crop: Crop, modifier: Modifier = Modifier, onEnd: (Crop) -> Unit) {
    var current by remember(m.id, aspect) { mutableStateOf(crop) }
    val end by rememberUpdatedState(onEnd)
    // A reset or an undo from outside shows at once.
    LaunchedEffect(crop) { current = crop }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val a = aspect.toFloat().coerceIn(0.1f, 10f)
        val w = minOf(maxWidth, maxHeight * a)
        val h = w / a
        Box(
            Modifier.size(w, h).clipToBounds().border(1.dp, StudioColors.edge)
                .pointerInput(m.id, aspect) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var changed = false
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                current = CropMath.pan(m.width, m.height, aspect, current, -pan.x / size.width.toDouble(), -pan.y / size.height.toDouble(), zoom.toDouble())
                                changed = true
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (changed) end(current)
                    }
                },
        ) {
            CroppedPhoto(m, aspect, current, Modifier.matchParentSize())
        }
    }
}

// ---------------------------------------------------------------- albums

/** "1 photo", "3 photos". */
internal fun photosText(n: Int): String = if (n == 1) "1 photo" else "$n photos"

/**
 * The trip's photos to pick several of, in the order tapped (each picked one numbered, ringed in yellow); [used] ones
 * are already in the book (marked, and left out while [onlyNew]). Scrolls by itself.
 */
@Composable
internal fun MultiPhotoPicker(
    photos: List<Media>,
    picked: List<String>,
    used: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    onlyNew: Boolean = false,
) {
    val shown = remember(photos, used, onlyNew) { if (onlyNew) photos.filter { it.id !in used } else photos }
    if (shown.isEmpty()) {
        NoPhotos(if (photos.isEmpty()) "This trip has no photos yet." else "Every photo of the trip is in the book.", modifier)
        return
    }
    val fromPhone = LocalAddFromPhone.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(76.dp),
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (fromPhone != null) item(key = "from-phone") { FromPhoneTile(fromPhone) }
        items(shown, key = { it.id }) { p ->
            val n = picked.indexOf(p.id) + 1
            val on = n > 0
            val inBook = p.id in used
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier.aspectRatio(1f).clip(shape).border(if (on) 3.dp else 0.dp, if (on) StudioColors.yellow else Color.Transparent, shape)
                    .semantics {
                        selected = on
                        contentDescription = listOfNotNull("Photo", if (on) "picked $n" else null, if (inBook) "in the book" else null).joinToString(", ")
                    }
                    .clickable(role = Role.Checkbox, onClickLabel = if (on) "Leave out" else "Pick") { onToggle(p.id) },
            ) {
                MediaImage(p, Modifier.matchParentSize().padding(if (on) 3.dp else 0.dp).clip(RoundedCornerShape(4.dp)))
                if (inBook) Text(
                    "IN BOOK",
                    Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    style = StudioText.group, color = StudioColors.ink, maxLines = 1,
                )
                if (on) Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(StudioColors.yellow),
                    contentAlignment = Alignment.Center,
                ) { Text(n.toString(), style = StudioText.chip, color = StudioColors.onYellow, maxLines = 1) }
            }
        }
    }
}

/**
 * The album arrangements in groups by how many photos they hold, each drawn as it sits on this book's page; [current]
 * marked. The first [filled] spots of each are drawn darker: where the page's photos would go.
 */
@Composable
internal fun AlbumGroups(layout: BookLayout, current: String?, filled: Int, onPick: (AlbumLayout) -> Unit) {
    AlbumLayouts.counts.forEach { n ->
        StudioSection(photosText(n)) {
            StudioGrid(AlbumLayouts.forCount(n), 4, spacing = 8.dp) { a -> AlbumTile(layout, a, a.id == current, filled, onPick) }
        }
    }
}

@Composable
private fun AlbumTile(layout: BookLayout, a: AlbumLayout, on: Boolean, filled: Int, onPick: (AlbumLayout) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) StudioColors.raised else Color.Transparent)
            .semantics { selected = on }
            .clickable(role = Role.RadioButton, onClickLabel = "${a.name}, ${photosText(a.count)}") { onPick(a) }.padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AlbumThumb(layout, a, filled, Modifier.fillMaxWidth().then(if (on) Modifier.border(2.dp, StudioColors.yellow) else Modifier))
        Text(
            a.name, Modifier.padding(top = 4.dp), style = StudioText.sub, color = if (on) StudioColors.yellow else StudioColors.soft,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

/** A small drawing of album arrangement [a] on this book's page: the paper and the photo spots where they print. */
@Composable
internal fun AlbumThumb(layout: BookLayout, a: AlbumLayout, filled: Int, modifier: Modifier = Modifier) {
    val f = layout.spec.format
    val cells = remember(layout, a) { layout.pages.albumCells(a) }
    val paper = remember(layout) { Color(layout.pages.look.paperColor.toColorInt()) }
    Canvas(modifier.aspectRatio(f.aspect.toFloat()).background(paper)) {
        val k = size.width / f.widthMm.toFloat()
        cells.forEachIndexed { i, b ->
            drawRect(
                if (i < filled) ALBUM_PHOTO else ALBUM_EMPTY,
                Offset(b.x.toFloat() * k, b.y.toFloat() * k), Size(b.w.toFloat() * k, b.h.toFloat() * k),
            )
        }
    }
}

private val ALBUM_PHOTO = Color(0xFF7D79A6)
private val ALBUM_EMPTY = Color(0xFFCFCDE2)

/**
 * Photo pages in two steps: pick photos (in order), then an album arrangement; [onCreate] makes the pages (as many
 * as the photos need). The photo grid is [gridHeight] tall in a sheet that scrolls, or takes the room left in a panel.
 */
@Composable
internal fun ColumnScope.AlbumPagesPicker(
    layout: BookLayout,
    photos: List<Media>,
    used: Set<String>,
    gridHeight: Dp?,
    picked: List<String>,
    onPicked: (List<String>) -> Unit,
    choosing: Boolean,
    onChoosing: (Boolean) -> Unit,
    onlyNew: Boolean,
    onCreate: (List<String>, AlbumLayout) -> Unit,
) {
    if (!choosing) {
        val grid = if (gridHeight != null) Modifier.height(gridHeight) else Modifier.weight(1f)
        MultiPhotoPicker(photos, picked, used, { id -> onPicked(if (id in picked) picked - id else picked + id) }, grid, onlyNew)
        StudioButton(
            if (picked.isEmpty()) "Pick photos" else "Next · ${photosText(picked.size)}", { onChoosing(true) },
            Modifier.fillMaxWidth(), icon = BtIcons.Forward, primary = true, enabled = picked.isNotEmpty(),
        )
    } else {
        StudioNote("${photosText(picked.size)}. Pick a layout; the pages follow, as many as the photos need.")
        AlbumGroups(layout, null, Int.MAX_VALUE) { a -> onCreate(picked, a) }
    }
}

/** One editable piece of text on a page: an entry's title and its paragraphs there. */
data class TextPart(val entry: String?, val title: String?, val text: String)
