package com.justdataplease.boringtravel.ui.clip

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipFont
import com.justdataplease.boringtravel.clip.PhotoPicks
import com.justdataplease.boringtravel.clip.PlaceMedia
import com.justdataplease.boringtravel.clip.PlacePhotos
import com.justdataplease.boringtravel.clip.ClipTiming
import com.justdataplease.boringtravel.clip.ClipVideo
import com.justdataplease.boringtravel.clip.NamePlace
import com.justdataplease.boringtravel.clip.Opening
import com.justdataplease.boringtravel.clip.PanelShape
import com.justdataplease.boringtravel.clip.PhotoMode
import com.justdataplease.boringtravel.clip.PhotoRow
import com.justdataplease.boringtravel.clip.PhotoSide
import com.justdataplease.boringtravel.clip.PhotoSize
import com.justdataplease.boringtravel.clip.SceneKind
import com.justdataplease.boringtravel.clip.Spots
import com.justdataplease.boringtravel.clip.PhotoFrame
import com.justdataplease.boringtravel.clip.PhotoLayout
import com.justdataplease.boringtravel.clip.TextBack
import com.justdataplease.boringtravel.clip.TextColor
import com.justdataplease.boringtravel.clip.TextSize
import com.justdataplease.boringtravel.clip.StatsShow
import com.justdataplease.boringtravel.clip.ClipLayout
import com.justdataplease.boringtravel.clip.ClipShape
import com.justdataplease.boringtravel.clip.VoiceLine
import com.justdataplease.boringtravel.clip.music.MusicCatalog
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.studio.StudioButton
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioIconButton
import com.justdataplease.boringtravel.ui.studio.StudioNote
import com.justdataplease.boringtravel.ui.studio.StudioRoundButton
import com.justdataplease.boringtravel.ui.studio.StudioSection
import com.justdataplease.boringtravel.ui.studio.StudioSlider
import com.justdataplease.boringtravel.ui.studio.StudioSwitchRow
import com.justdataplease.boringtravel.ui.studio.StudioText
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import com.justdataplease.boringtravel.clip.music.MusicShapes
import com.justdataplease.boringtravel.clip.music.MusicTrack
import com.justdataplease.boringtravel.ui.studio.StudioChip

private val S = StudioColors

private const val NAME_MAX = 40
private const val LINE_MAX = 60
private const val NOTE_MAX = 70
private const val KICKER_MAX = 24

private val TILTS: List<Float?> = listOf(null, -8f, -4f, 0f, 4f, 8f)

private fun tiltLabel(t: Float?): String = t?.let { "${if (it > 0) "+" else if (it < 0) "−" else ""}${abs(it).toInt()}°" } ?: "Auto"

// ---------------------------------------------------------------- STYLE › Text

/**
 * The texts' style for the whole clip: names and the title on or off, the stops' own titles (planned routes), the
 * size slider, where the names go (the thumb pad), the font, what is behind them and the colour. Fewer choices than
 * before (owner, 2026-11-09: "simplify or remove options if they do not make a lot of difference"): the panel's shape
 * goes with the look.
 */
@Composable
internal fun TextAll(ed: ClipEditing) {
    val d = ed.design
    val on = ed.enabled
    StudioSwitchRow("Place names", null, d.name != NamePlace.Off) { show ->
        if (on) ed.style { it.copy(name = if (show) NamePlace.Top else NamePlace.Off) }
    }
    StudioSwitchRow("Title at the start", "The trip's name over the whole route", d.opening != Opening.None) { show ->
        if (on) ed.style { it.copy(opening = if (show) Opening.Title else Opening.None) }
    }
    if (ed.planned) {
        StudioSwitchRow("Stops' own titles", "Lisbon Cathedral under \"Day 2 · Lisbon\"; off, one name for each place", d.stopTitles) { show ->
            if (on) ed.style { it.copy(stopTitles = show) }
        }
    }
    val type = d.textSize.scale * d.textScale
    ValueSlider("Text size", type, Spots.TEXT_SCALE, ::percent, on) { v ->
        ed.style { it.copy(textScale = Spots.scale(v, Spots.TEXT_SCALE) / it.textSize.scale) }
    }
    if (d.name != NamePlace.Off) {
        val spot = d.nameY?.let { y -> (d.nameX ?: 0.5f) to y }
        ThumbPad("Place names go", spot, 0.5f to (if (d.name == NamePlace.Bottom) 0.8f else 0.12f), ed.aspect, on) { p ->
            ed.style { it.copy(nameX = p?.first, nameY = p?.second) }
        }
    }
    Chips("Font", ClipFont.entries, d.font, { it.label }, on, columns = 3, font = { fontFamily(it) }) { v -> ed.style { it.copy(font = v) } }
    Chips("Behind the text", TextBack.entries, d.back, { it.label }, on) { v -> ed.style { it.copy(back = v) } }
    ColourChoice("Colour", TextColor.entries, d.color, { it.label }, { textSwatch(it) }, on) { v -> ed.style { it.copy(color = v) } }
}

/**
 * The Stats tool (owner, 2026-11-09: "show some statistics like km or days … select font size and position on
 * screen", "select if i want day, day km, km only or nothing at all", "change it same as you have the image … full
 * change in screen", "font size etc are very important"): what the line shows, its size, where it goes (the same pad
 * as photos and names; the bottom left corner by default), its font, background and colour.
 */
@Composable
internal fun StatsAll(ed: ClipEditing) {
    val d = ed.design
    val on = ed.enabled
    val show = StatsShow.of(d.stats)
    Chips("Show", StatsShow.entries, show, { it.label }, on) { v -> ed.style { it.copy(stats = v.stats) } }
    if (show == StatsShow.None) {
        StudioNote("No stats on the video. Pick what to show above.")
        return
    }
    StudioNote("Counts up as the route goes, e.g. Day 3 of 9 · 17 km.")
    ValueSlider("Size", d.statsScale, Spots.STATS_SCALE, ::percent, on) { v -> ed.style { it.copy(statsScale = Spots.scale(v, Spots.STATS_SCALE)) } }
    ThumbPad("Position", d.statsSpot, statsAuto(ed.options.shape, d.statsScale), ed.aspect, on) { p ->
        ed.style { it.copy(statsX = p?.first, statsY = p?.second) }
    }
    Chips("Font", ClipFont.entries, d.statsFont, { it.label }, on, columns = 3, font = { fontFamily(it) }) { v -> ed.style { it.copy(statsFont = v) } }
    Chips("Behind the text", TextBack.entries, d.statsBack, { it.label }, on) { v -> ed.style { it.copy(statsBack = v) } }
    ColourChoice("Colour", TextColor.entries, d.statsColor, { it.label }, { textSwatch(it) }, on) { v -> ed.style { it.copy(statsColor = v) } }
}

/** Where the stats line sits by default on the pad (its Auto dot): the bottom left corner, for about its usual size. */
private fun statsAuto(shape: ClipShape, scale: Float): Pair<Float, Float> {
    val layout = ClipLayout(shape.width, shape.height, shape == ClipShape.Story)
    return layout.statsAuto(150f * layout.u * scale, 18f * layout.u * scale)
}

/** One text's own size (× the clip's) and place on the thumb pad; Auto goes back to the clip's. */
@Composable
private fun OwnSizeAndSpot(ed: ClipEditing, key: String, auto: Pair<Float, Float>) {
    val d = ed.design
    val o = d.scene(key)
    ValueSlider("Size here", o.textScale ?: 1f, Spots.TEXT_SCALE, ::percent, ed.enabled) { v ->
        val s = Spots.scale(v, Spots.TEXT_SCALE)
        ed.change(key) { it.copy(textScale = s.takeIf { x -> kotlin.math.abs(x - 1f) > 0.004f }) }
    }
    val own = o.y?.let { y -> (o.x ?: 0.5f) to y }
    ThumbPad("Position", own, d.nameSpot(null) ?: auto, ed.aspect, ed.enabled) { p ->
        ed.change(key) { it.copy(x = p?.first, y = p?.second, name = null) }
    }
}

/**
 * The small line over a name ("Day 4" by default): shown or not, and its own words. Kept as [SceneOverride.kicker]:
 * null = automatic, blank = left out.
 */
@Composable
private fun KickerField(ed: ClipEditing, key: String, auto: String?) {
    val o = ed.design.scene(key)
    if (auto == null) {
        Field("Small line above", o.kicker.orEmpty(), "e.g. Day 4", KICKER_MAX) { v -> ed.change(key, "kicker:$key") { it.copy(kicker = v.ifBlank { null }) } }
        return
    }
    val shown = o.kicker?.isNotBlank() ?: true
    StudioSwitchRow("Small line above", null, shown) { show -> if (ed.enabled) ed.change(key) { it.copy(kicker = if (show) null else "") } }
    if (shown) {
        Field("Small line", o.kicker.orEmpty(), auto, KICKER_MAX) { v -> ed.change(key, "kicker:$key") { it.copy(kicker = v.ifBlank { null }) } }
    }
}

/** A note under the name and the exact spot under that (both optional). */
@Composable
private fun NoteAndSpot(ed: ClipEditing, key: String, notePlaceholder: String = "A short note, e.g. Sunset at the viewpoint") {
    val o = ed.design.scene(key)
    Field("Note", o.note.orEmpty(), notePlaceholder, NOTE_MAX) { v -> ed.change(key, "note:$key") { it.copy(note = v.ifBlank { null }) } }
    Field("Exact spot", o.spot.orEmpty(), "Street, area or landmark", LINE_MAX) { v -> ed.change(key, "spot:$key") { it.copy(spot = v.ifBlank { null }) } }
}

@Composable
internal fun TextScene(ed: ClipEditing, pick: ScenePick) {
    when (pick) {
        ScenePick.Opening -> OpeningText(ed)
        ScenePick.End -> Field("Closing line", ed.design.scene(ClipDesign.END).text.orEmpty(), ed.plan.endCard.stats, LINE_MAX) { v ->
            ed.change(ClipDesign.END, "end-text") { it.copy(text = v.ifBlank { null }) }
        }
        is ScenePick.Place -> PlaceText(ed, pick.key)
        is ScenePick.Photo -> Caption(ed, pick)
        is ScenePick.DayCard -> DayCardScene(ed, pick)
        is ScenePick.Video -> Unit
    }
}

/** The opening: shown or not, its title, the trip's dates over it. */
@Composable
private fun OpeningText(ed: ClipEditing) {
    val d = ed.design
    val o = d.scene(ClipDesign.OPENING)
    val global = d.opening != Opening.None
    StudioSwitchRow("Show the title", "The trip's name over the whole route", d.openingVisible) { on ->
        if (ed.enabled) ed.change(ClipDesign.OPENING) { it.copy(hidden = (!on).takeIf { h -> h == global }) }
    }
    if (!d.openingVisible) return
    Field("Title", o.text.orEmpty(), ed.plan.title, NAME_MAX * 2) { v -> ed.change(ClipDesign.OPENING, "opening-text") { it.copy(text = v.ifBlank { null }) } }
    Field("Subtitle", o.note.orEmpty(), "e.g. Ten days on the coast", NOTE_MAX) { v -> ed.change(ClipDesign.OPENING, "opening-note") { it.copy(note = v.ifBlank { null }) } }
    if (ed.plan.endCard.dates != null) {
        StudioSwitchRow("Dates", "The trip's dates over the title", o.dates != false) { on ->
            if (ed.enabled) ed.change(ClipDesign.OPENING) { it.copy(dates = if (on) null else false) }
        }
    }
    OwnSizeAndSpot(ed, ClipDesign.OPENING, 0.5f to (if (d.name == NamePlace.Bottom) 0.8f else 0.15f))
    OwnLook(ed, ClipDesign.OPENING)
}

/**
 * One text's own font, background and colour, else the clip's (owner, 2026-11-09: "i want to be able to change
 * screen fonts per cases"): a place name or the title.
 */
@Composable
private fun OwnLook(ed: ClipEditing, key: String) {
    val d = ed.design
    val on = ed.enabled
    Chips("Font", ClipFont.entries, d.labelFont(key), { it.label }, on, columns = 3, font = { fontFamily(it) }) { v ->
        ed.change(key) { it.copy(font = v.takeIf { f -> f != d.font }) }
    }
    Chips("Behind the text", TextBack.entries, d.labelBack(key), { it.label }, on) { v ->
        ed.change(key) { it.copy(back = v.takeIf { b -> b != d.back }) }
    }
    ColourChoice("Colour", TextColor.entries, d.labelColor(key), { it.label }, { textSwatch(it) }, on) { v ->
        ed.change(key) { it.copy(color = v.takeIf { c -> c != d.color }) }
    }
}

/** A place name: shown or not, its words, where it sits, its own font, background and colour. */
@Composable
private fun PlaceText(ed: ClipEditing, key: String) {
    val d = ed.design
    val o = d.scene(key)
    val on = ed.enabled
    val visible = d.labelVisible(key)
    StudioSwitchRow("Show the name", null, visible) { show ->
        if (on) ed.change(key) { it.copy(hidden = if (!show) true else if (d.name == NamePlace.Off) false else null) }
    }
    if (!visible) return
    val auto = ed.plan.labels.firstOrNull { it.key == key }?.label
    // A renamed "Day 4" keeps "Day 4" as its small line (ClipDesign.label), so it gets the switch to leave it out too.
    KickerField(ed, key, auto?.day?.takeIf { auto.place != null || !o.text.isNullOrBlank() })
    Field("Name", o.text.orEmpty(), auto?.place ?: auto?.day.orEmpty(), NAME_MAX) { v -> ed.change(key, "text:$key") { it.copy(text = v.ifBlank { null }) } }
    NoteAndSpot(ed, key)
    OwnSizeAndSpot(ed, key, 0.5f to (if (d.labelPlace(key) == NamePlace.Bottom) 0.8f else 0.12f))
    StudioNote("Or drag it on the picture.")
    OwnLook(ed, key)
    SceneTags(ed, key, 0.5f to (if (d.labelPlace(key) == NamePlace.Bottom) 0.9f else 0.22f))
}

/** A photo's caption: a short line under its card, in the place name's style. */
@Composable
private fun Caption(ed: ClipEditing, pick: ScenePick.Photo) {
    val o = ed.design.scene(pick.key)
    Field("Caption", o.caption.orEmpty(), "A line under the photo", NAME_MAX) { v -> ed.change(pick.key, "caption:${pick.key}") { it.copy(caption = v.ifBlank { null }) } }
}

// ---------------------------------------------------------------- STYLE › Photos

/**
 * The photos for the whole clip (owner, 2026-11-09): on or off (the photo modes went: "many many choices no need them
 * all, last version is good"), how long the journey stops for each ("a slider, how much time an image is paused"),
 * the still map before and after ("wait like half a second … maybe have this as a slider"), the layout ("full screen
 * or 2 pictures, see layout in photo album"), and the cards' size, place (the thumb pad) and border ("the size of the
 * image … or the image frame, or is it with the theme"). The list of places under the switch is about all media, so it
 * stays when photos are off (a place's videos are there too).
 */
@Composable
internal fun PhotosAll(ed: ClipEditing, onChoose: (ScenePick?) -> Unit) {
    val d = ed.design
    val on = ed.enabled
    StudioSwitchRow("Photos", "One photo per place unless you choose; the journey stops while each shows", d.photoMode.photos) { show ->
        if (on) ed.style { it.copy(photoMode = if (show) PhotoMode.Auto else PhotoMode.Off) }
    }
    MediaPerPlace(ed, onChoose)
    if (!d.photoMode.photos) return
    ValueSlider("Photo pause", d.holdSeconds.toFloat(), Spots.HOLD_MIN_S.toFloat()..ClipDesign.MAX_HOLD_S.toFloat(), { secondsLabel(it) }, on) { v ->
        ed.style { it.copy(photoMode = PhotoMode.Auto, photoHoldS = Spots.hold(v.toDouble()).coerceAtLeast(Spots.HOLD_MIN_S)) }
    }
    ValueSlider("Still map before and after", d.photoGapS.toFloat(), 0f..ClipDesign.MAX_GAP_S.toFloat(), { secondsLabel(it, "None") }, on) { v ->
        ed.style { it.copy(photoGapS = kotlin.math.round(v * 4.0) / 4.0) }
    }
    StudioNote("The journey stops, the photo comes and goes, then it moves on.")
    Chips("Layout", PhotoLayout.entries, d.photoLayout, { it.label }, on) { v -> ed.style { it.copy(photoLayout = v) } }
    when (d.photoLayout) {
        PhotoLayout.Full -> StudioNote("Each photo fills the frame while the journey waits.")
        PhotoLayout.Album -> StudioNote("Up to ${PhotoLayout.ALBUM_MAX} photos of a place together, laid out like an album page.")
        PhotoLayout.Card -> {
            ValueSlider("Photo size", d.photoScale, Spots.PHOTO_SCALE, ::percent, on) { v -> ed.style { it.copy(photoScale = Spots.scale(v, Spots.PHOTO_SCALE)) } }
            val spot = d.photoX?.let { x -> d.photoY?.let { y -> x to y } }
            ThumbPad("Photos go", spot, (Spots.sideX(d.photoSide) ?: 0.5f) to photoRowY(d.photoRow), ed.aspect, on) { p ->
                ed.style { it.copy(photoX = p?.first, photoY = p?.second) }
            }
            Chips("Frame", PhotoFrame.entries, d.photoFrame, { it.label }, on) { v -> ed.style { it.copy(photoFrame = v) } }
            StudioNote(
                if (d.photoFrame == PhotoFrame.Theme) "Theme: the print border of your app theme (Me › Theme)."
                else "The photo alone, with rounded corners.",
            )
        }
    }
    StudioNote("Choose a photo on the tracks to change only that one.")
}

/**
 * Each place with how much media it has, folded under one row like Edit stops: tap a place to choose its photos and
 * their order, or add photos and videos (owner, 2026-11-12: "I don't seem to select how to add another or change
 * order"; 2026-11-14: "in clips videos are added as photos and have the same structure").
 */
@Composable
private fun MediaPerPlace(ed: ClipEditing, onChoose: (ScenePick?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val photosOn = ed.design.photoMode.photos
    val counts = remember(ed.scenes, photosOn) {
        if (!photosOn) emptyMap()
        else ed.scenes.filter { it.kind == SceneKind.Photo && it.placeKey != null }.groupBy { it.placeKey!! }
            .mapValues { (_, list) -> list.count { it.shown } to list.size }
    }
    val videos = ed.design.videos.groupingBy { it.sceneKey }.eachCount()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).background(S.raised)
            .clickable(role = Role.Button, onClickLabel = if (open) "Hide the places" else "Show the places") { open = !open }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Choose media per place", style = StudioText.item, maxLines = 1)
            Text("Tap a place to pick, order or add its media", style = StudioText.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(if (open) BtIcons.Up else BtIcons.Down, null, Modifier.size(18.dp), tint = S.muted)
    }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ed.plan.labels.forEach { range ->
            val (shown, all) = counts[range.key] ?: (0 to 0)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(S.raised)
                    .clickable(enabled = ed.enabled, role = Role.Button, onClickLabel = "Choose media") { onChoose(ScenePick.Place(range.key)) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(ed.placeName(range.key), style = StudioText.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(PlaceMedia.countLine(shown, all, videos[range.key] ?: 0), style = StudioText.sub, maxLines = 1)
                }
                Icon(BtIcons.Forward, null, Modifier.size(16.dp), tint = S.muted)
            }
        }
    }
}

/** Where a row's card sits on the pad, about (for the Auto dot). */
private fun photoRowY(row: PhotoRow): Float = when (row) {
    PhotoRow.Top -> 0.25f
    PhotoRow.Middle -> 0.5f
    PhotoRow.Bottom -> 0.7f
}

/** One photo: in the clip or not, its pause, size, row and side (or where it was dragged), tilt, caption and order. */
@Composable
internal fun OnePhoto(ed: ClipEditing, pick: ScenePick) {
    val photo = pick as? ScenePick.Photo ?: return
    val id = photo.id
    val key = photo.key
    val d = ed.design
    val o = d.scene(key)
    val on = ed.enabled
    val scene = ed.scenes.firstOrNull { it.photoId == id }
    val shown = scene?.shown ?: false
    val place = scene?.placeKey
    if (place == null) {
        // A photo with no place name (rare): in or out on its own.
        StudioSwitchRow("In the clip", if (shown) null else "Left out, or past what the clip has room for", shown) { v ->
            if (on) ed.change(key) { it.copy(show = v) }
        }
    } else {
        // In or out among its place's own photos, as the place's list ticks it.
        val here = remember(ed.plan, d, ed.scenes, place) { PlacePhotos.of(ed.plan, d, ed.scenes, place, ed.isPro) }
        val ticked = id in here.ticked
        val fits = here.fits(id)
        val why = when {
            ticked && !shown -> "Ticked, but the clip has no room for it"
            !ticked && !fits -> "No room for more in this clip. A shorter photo pause or a longer clip makes room."
            !ticked -> "Not in the clip"
            else -> null
        }
        StudioSwitchRow("In the clip", why, ticked) { v ->
            if (on && (v != ticked) && (fits || !v)) ed.edit { PhotoPicks.toggle(it, place, here.photos.filter { p -> p.shown }.map { p -> p.id }, id) }
        }
    }
    if (!shown) return
    PhotoPause(ed, photo)
    ValueSlider("Size", d.photoScale(id), Spots.PHOTO_SCALE, ::percent, on) { v ->
        val s = Spots.scale(v, Spots.PHOTO_SCALE)
        ed.change(key) { it.copy(scale = s.takeIf { x -> kotlin.math.abs(x - d.photoScale) > 0.004f }, size = null) }
    }
    val own = o.x?.let { x -> o.y?.let { y -> x to y } }
    ThumbPad("Position", own, d.photoSpot(id) ?: ((Spots.sideX(d.photoSide(id)) ?: 0.5f) to photoRowY(d.photoRow(id))), ed.aspect, on) { p ->
        ed.change(key) { it.copy(x = p?.first, y = p?.second, photoRow = null, photoSide = null) }
    }
    StudioNote("Or drag it on the picture.")
    Chips("Tilt", TILTS, o.tilt, ::tiltLabel, on, columns = 6) { v -> ed.change(key) { it.copy(tilt = v) } }
    Caption(ed, photo)
    PhotoOrder(ed, photo)
}

/** Earlier and later among its place's photos in the clip (the place's own list, [PhotoPicks]). */
@Composable
private fun PhotoOrder(ed: ClipEditing, pick: ScenePick.Photo) {
    val place = ed.scenes.firstOrNull { it.photoId == pick.id }?.placeKey ?: return
    val shown = ed.scenes.filter { it.kind == SceneKind.Photo && it.placeKey == place && it.shown }.mapNotNull { it.photoId }
    val ids = PhotoPicks.ticked(ed.edits, place, shown)
    if (ids.size < 2) return
    val i = ids.indexOf(pick.id)
    if (i < 0) return
    fun move(to: Int) = ed.edit { PhotoPicks.move(it, place, shown, i, to) }
    StudioSection("Order", value = "${i + 1} of ${ids.size}") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioButton("Earlier", { move(i - 1) }, Modifier.weight(1f), icon = BtIcons.Back, enabled = ed.enabled && i > 0)
            StudioButton("Later", { move(i + 1) }, Modifier.weight(1f), icon = BtIcons.Forward, enabled = ed.enabled && i in 0 until ids.lastIndex)
        }
    }
}

// ---------------------------------------------------------------- SOUND › Music

/** One player for the Music panel's listening, stopped when the panel goes or the app is left. */
@Composable
internal fun rememberMusicPreview(): MusicPreview {
    val context = LocalContext.current
    val preview = remember { MusicPreview(context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, preview) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) preview.stop() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            preview.stop()
        }
    }
    return preview
}

/**
 * Where the song starts (owner, 2026-11-08: "be able to change where the music track starts"): the track's waveform
 * with a window as long as the clip; drag it (or tap where it should start), it snaps to the bar nearest. "Best part"
 * goes back to the track's strongest stretch (the default), "Song start" to 0:00; Listen plays from the start chosen.
 * The cuts on the beat follow the new start.
 */
@Composable
internal fun MusicStart(ed: ClipEditing, track: MusicTrack, preview: MusicPreview) {
    val levels = remember(track.id) { MusicShapes.levels(track.id) }
    val songS = track.durationMs / 1000.0
    val clipS = ed.plan.frameCount.toDouble() / ed.plan.fps.coerceAtLeast(1)
    val start = ed.vm.musicStartS
    val hook = MusicCatalog.onBar(track, MusicShapes.hookS(track.id))
    var dragging by remember(track.id) { mutableStateOf<Double?>(null) }
    val shown = dragging ?: start
    // The gesture handlers outlive this composition (keyed by the track): they read the start and length as they are now.
    val startNow by rememberUpdatedState(start)
    val clipNow by rememberUpdatedState(clipS)
    fun pick(s: Double) {
        val at = MusicCatalog.onBar(track, s)
        ed.vm.setMusicStart(if (kotlin.math.abs(at - hook) < 0.01) null else at)
        if (preview.playing == track.id) preview.play(track, (at * 1000).toInt())
    }
    StudioSection("Starts at", value = ClipTiming.clock(shown)) {
        Canvas(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).background(S.raised)
                .pointerInput(track.id, songS, ed.enabled) {
                    if (!ed.enabled) return@pointerInput
                    detectTapGestures { o -> pick((o.x / size.width * songS - clipNow / 2).coerceAtLeast(0.0)) }
                }
                .pointerInput(track.id, songS, ed.enabled) {
                    if (!ed.enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = startNow },
                        onDragEnd = { dragging?.let(::pick); dragging = null },
                        onDragCancel = { dragging = null },
                    ) { _, dx ->
                        val last = MusicCatalog.startOf(track, Double.MAX_VALUE)
                        dragging = ((dragging ?: startNow) + dx / size.width * songS).coerceIn(0.0, last)
                    }
                }
                .semantics { contentDescription = "Music starts at ${ClipTiming.clock(shown)}" },
        ) {
            val w = size.width
            val h = size.height
            val from = (shown / songS * w).toFloat()
            val to = (minOf(songS, shown + clipS) / songS * w).toFloat()
            drawRect(S.yellow.copy(alpha = 0.16f), Offset(from, 0f), androidx.compose.ui.geometry.Size(to - from, h))
            val n = levels.size
            if (n > 0) {
                val step = w / n
                val bar = (step * 0.62f).coerceAtLeast(1f)
                for (i in 0 until n) {
                    val x = i * step
                    val v = 0.12f + 0.88f * levels[i]
                    val inside = x + step / 2 in from..to
                    drawRect(
                        if (inside) S.yellow else S.muted.copy(alpha = 0.55f),
                        Offset(x, (h - v * h * 0.8f) / 2), androidx.compose.ui.geometry.Size(bar, v * h * 0.8f),
                    )
                }
            }
            drawRect(S.yellow, Offset(from, 0f), androidx.compose.ui.geometry.Size(2.dp.toPx(), h))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val playing = preview.playing == track.id
            StudioRoundButton(if (playing) BtIcons.Stop else BtIcons.Play, if (playing) "Stop" else "Listen from here", {
                if (playing) preview.stop() else preview.play(track, (start * 1000).toInt())
            })
            StudioChip("Best part", ed.design.musicStartS == null, { ed.vm.setMusicStart(null) }, Modifier.weight(1f), enabled = ed.enabled)
            StudioChip("Song start", ed.design.musicStartS == 0.0, { ed.vm.setMusicStart(0.0) }, Modifier.weight(1f), enabled = ed.enabled)
        }
    }
    StudioNote("Drag the bright part or tap where the song should start. It starts on a bar, so the cuts stay on the beat.")
}

/** None and the bundled tracks: listen with the round button, tap a row to pick. Previews stop when the panel goes. */
@Composable
internal fun MusicList(selected: String?, enabled: Boolean, preview: MusicPreview, onPick: (String?) -> Unit) {
    StudioNote("Fades in and out with the video.")
    Column {
        MusicRow("None", "Silent video", selected == null, playing = false, enabled = enabled, onPlay = null) {
            preview.stop()
            onPick(null)
        }
        MusicCatalog.tracks.forEach { t ->
            MusicRow(
                t.name, t.mood + " · " + ClipTiming.clock(t.durationMs / 1000.0), selected == t.id, playing = preview.playing == t.id, enabled = enabled,
                onPlay = { preview.toggle(t) },
            ) {
                if (preview.playing != null && preview.playing != t.id) preview.stop()
                onPick(t.id)
            }
        }
    }
}

/** One choice: a play/stop button (none for None), the name over the mood, a tick when chosen. */
@Composable
private fun MusicRow(title: String, body: String, selected: Boolean, playing: Boolean, enabled: Boolean, onPlay: (() -> Unit)?, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(10.dp)).semantics { this.selected = selected }
            .clickable(enabled = enabled, role = Role.RadioButton, onClickLabel = title, onClick = onSelect)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onPlay != null) {
            StudioRoundButton(if (playing) BtIcons.Stop else BtIcons.Play, if (playing) "Stop $title" else "Play $title", onPlay)
        } else {
            Spacer(Modifier.size(40.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = StudioText.item, color = if (selected) S.yellow else S.ink, maxLines = 1)
            Text(body, style = StudioText.sub, maxLines = 1)
        }
        if (selected) Icon(BtIcons.Check, "Chosen", Modifier.size(20.dp), tint = S.yellow)
    }
}

// ---------------------------------------------------------------- SOUND › Voice

/** A ticking "0:04" while a line records. */
@Composable
private fun elapsedSince(startedAt: Long): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    return (now - startedAt).coerceAtLeast(0)
}

private fun seconds(ms: Long): String = "${(ms + 500) / 1000} s"

/** The voice line of the chosen place (or the day a card opens): record, stop, record again, remove. */
@Composable
internal fun VoiceScene(ed: ClipEditing, pick: ScenePick, media: EditorMedia) {
    val voice by ed.vm.voice.collectAsStateWithLifecycle()
    val key = ed.placeOf(pick) ?: return
    val line = ed.design.voice(key)
    val rec = voice as? ClipViewModel.Voice.Recording
    if (rec != null && rec.sceneKey == key) {
        val ms = elapsedSince(rec.startedAt)
        StudioSection("Recording", value = ClipTiming.clock(ms / 1000.0)) {
            StudioNote("Speak now. It stops by itself after ${VoiceLine.MAX_MS / 1000} s.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioButton("Stop", ed.vm::stopVoice, Modifier.weight(1f), icon = BtIcons.Stop, primary = true)
                StudioButton("Cancel", ed.vm::cancelVoice, Modifier.weight(1f))
            }
        }
    } else {
        StudioSection("Voice line", value = line?.let { seconds(it.durationMs) }) {
            StudioNote("It plays from the start of ${ed.placeName(key)}, the music quieter under it.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioButton(
                    if (line == null) "Record" else "Record again", { media.record(key) }, Modifier.weight(1f), icon = BtIcons.Mic,
                    primary = line == null, enabled = ed.enabled && rec == null,
                )
                if (line != null) StudioButton("Remove", { ed.vm.removeVoice(key) }, Modifier.weight(1f), icon = BtIcons.Delete, enabled = ed.enabled && rec == null, danger = true)
            }
        }
    }
    (voice as? ClipViewModel.Voice.Failed)?.let { StudioNote(it.reason, warn = true) }
}

/** Every voice line, and "Record" for the place at the playhead. */
@Composable
internal fun VoiceAll(ed: ClipEditing, placeHere: String?, media: EditorMedia, onChoose: (ScenePick?) -> Unit) {
    val voice by ed.vm.voice.collectAsStateWithLifecycle()
    val lines = ed.design.voices.sortedBy { ed.plan.sceneStart(it.sceneKey) ?: Int.MAX_VALUE }
    val rec = voice as? ClipViewModel.Voice.Recording
    if (rec != null) {
        val ms = elapsedSince(rec.startedAt)
        StudioSection("Recording · " + ed.placeName(rec.sceneKey), value = ClipTiming.clock(ms / 1000.0)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioButton("Stop", ed.vm::stopVoice, Modifier.weight(1f), icon = BtIcons.Stop, primary = true)
                StudioButton("Cancel", ed.vm::cancelVoice, Modifier.weight(1f))
            }
        }
    } else if (placeHere != null) {
        StudioButton("Record for ${ed.placeName(placeHere)}", { media.record(placeHere) }, Modifier.fillMaxWidth(), icon = BtIcons.Mic, primary = true, enabled = ed.enabled)
    }
    StudioNote("One line a place, up to ${VoiceLine.MAX_MS / 1000} s, from the start of its place. The music goes quieter under it.")
    (voice as? ClipViewModel.Voice.Failed)?.let { StudioNote(it.reason, warn = true) }
    StudioSection("Lines") {
        if (lines.isEmpty()) DashedNote("No voice lines yet.")
        lines.forEach { v ->
            ListRow(ed.placeName(v.sceneKey), seconds(v.durationMs), onOpen = { onChoose(ScenePick.Place(v.sceneKey)) }) {
                StudioIconButton(BtIcons.Delete, "Remove the line", { ed.vm.removeVoice(v.sceneKey) }, enabled = ed.enabled && rec == null, tint = StudioColors.danger)
            }
        }
    }
}

/** A row in a panel's list: a name over a quiet line (tap to choose it), then [actions]. */
@Composable
private fun ListRow(title: String, body: String?, onOpen: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(S.raised)
            .clickable(role = Role.Button, onClickLabel = "Choose", onClick = onOpen).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = StudioText.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (body != null) Text(body, style = StudioText.sub, maxLines = 1)
        }
        actions()
    }
}

// ---------------------------------------------------------------- MEDIA › Video

/** "Adding the video…", or why the last add failed; null when there is nothing to say. */
internal fun videoNote(work: ClipViewModel.MediaWork): String? = when (work) {
    ClipViewModel.MediaWork.Adding -> "Adding the video…"
    is ClipViewModel.MediaWork.Failed -> work.reason
    ClipViewModel.MediaWork.Idle -> null
}

/**
 * A chosen video: its trim, sound, place and Remove. A place's videos are listed and added with its photos
 * ([PlaceMediaPanel]; owner, 2026-11-14: "in clips videos are added as photos and have the same structure").
 */
@Composable
internal fun VideoScene(ed: ClipEditing, pick: ScenePick, onChoose: (ScenePick?) -> Unit) {
    val id = (pick as? ScenePick.Video)?.id ?: return
    val v = ed.design.videos.firstOrNull { it.id == id } ?: return
    VideoTrim(ed, v)
    StudioSwitchRow("Its own sound", "Quietly under the music", v.sound) { on -> if (ed.enabled) ed.vm.setVideoSound(v.id, on) }
    StudioSection("Plays after") {
        Text(ed.placeName(v.sceneKey), style = StudioText.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val before = stepPlace(ed.placeKeys, v.sceneKey, -1)
        val after = stepPlace(ed.placeKeys, v.sceneKey, 1)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioButton("Earlier place", { before?.let { ed.vm.moveVideo(v.id, it) } }, Modifier.weight(1f), icon = BtIcons.Back, enabled = ed.enabled && before != null)
            StudioButton("Later place", { after?.let { ed.vm.moveVideo(v.id, it) } }, Modifier.weight(1f), icon = BtIcons.Forward, enabled = ed.enabled && after != null)
        }
    }
    StudioButton("Remove", {
        ed.vm.removeVideo(v.id)
        onChoose(null)
    }, Modifier.fillMaxWidth(), icon = BtIcons.Delete, enabled = ed.enabled, danger = true)
}

/** A video in its place's "Media here" list: "Video 1 · 6 s" (and "sound on"); tap to open it, the bin takes it out. */
@Composable
internal fun VideoRow(ed: ClipEditing, v: ClipVideo, onChoose: (ScenePick?) -> Unit) {
    val n = ed.design.videos.indexOf(v) + 1
    ListRow("Video $n · ${seconds(v.lengthMs)}", if (v.sound) "sound on" else null, onOpen = { onChoose(ScenePick.Video(v.id)) }) {
        StudioIconButton(BtIcons.Delete, "Remove video $n", { ed.vm.removeVideo(v.id) }, enabled = ed.enabled, tint = StudioColors.danger)
    }
}

/** Which part of a video plays: its length (1–10 s) and where it starts. Kept when the finger lifts. */
@Composable
internal fun VideoTrim(ed: ClipEditing, v: ClipVideo) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val longest = minOf(ClipVideo.MAX_MS, v.durationMs) / 1000f
    var length by remember(v.id, v.lengthMs) { mutableFloatStateOf(v.lengthMs / 1000f) }
    var start by remember(v.id, v.startMs) { mutableFloatStateOf(v.startMs / 1000f) }
    StudioSection("Length", value = String.format(locale, "%.1f s", length)) {
        if (longest > ClipVideo.MIN_MS / 1000f + 0.05f) {
            StudioSlider(length, { length = it }, (ClipVideo.MIN_MS / 1000f)..longest, onDone = {
                if (ed.enabled) ed.vm.trimVideo(v.id, v.startMs, (length * 1000).roundToLong())
            })
        } else {
            StudioNote("The whole video plays.")
        }
    }
    val latest = (v.durationMs - v.lengthMs) / 1000f
    if (latest > 0.05f) {
        StudioSection("Starts at", value = String.format(locale, "%.1f s", start)) {
            StudioSlider(start, { start = it }, 0f..latest, onDone = {
                if (ed.enabled) ed.vm.trimVideo(v.id, (start * 1000).roundToLong(), v.lengthMs)
            })
        }
    }
    StudioNote("The video is ${String.format(locale, "%.1f", v.durationMs / 1000f)} s long; the clip waits while it plays.")
}

// ---------------------------------------------------------------- ADD › Day card

/** Day cards on every day or not, and each day's own choice. */
@Composable
internal fun DayCardAll(ed: ClipEditing, onChoose: (ScenePick?) -> Unit) {
    val d = ed.design
    StudioSwitchRow("Day cards", "A full-frame card opens each day: \"Day 14\", the place, the date", d.dayCards) { on -> if (ed.enabled) ed.vm.setDayCards(on) }
    val days = ed.plan.dayStarts
    if (days.isEmpty()) {
        StudioNote("This route has no dated days for cards.")
        return
    }
    StudioSection("Days") {
        days.forEach { card ->
            val shown = d.dayCardShown(card.key)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(S.raised)
                    .clickable(role = Role.Button, onClickLabel = "Choose") { onChoose(if (shown) ScenePick.DayCard(card.key) else ScenePick.Place(card.opens)) }
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(card.day ?: card.date.toString(), style = StudioText.item, maxLines = 1)
                    Text(d.scene(card.key).text ?: card.place ?: "", style = StudioText.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.width(110.dp)) {
                    StudioSwitchRow(if (shown) "Shown" else "Off", null, shown) { on -> if (ed.enabled) ed.vm.setDayCard(card.key, on) }
                }
            }
        }
    }
}

/** The chosen day's card: shown or not, and its own line in place of the place name. */
@Composable
internal fun DayCardScene(ed: ClipEditing, pick: ScenePick) {
    val card = ed.plan.dayStarts.firstOrNull { it.key == pick.key || it.opens == pick.key } ?: return
    val shown = ed.design.dayCardShown(card.key)
    StudioSwitchRow("Card before ${card.day ?: "this day"}", "Full frame for 1.5 s", shown) { on -> if (ed.enabled) ed.vm.setDayCard(card.key, on) }
    if (shown) {
        KickerField(ed, card.key, card.day?.takeIf { card.place != null || ed.design.scene(card.key).text != null })
        Field("Title", ed.design.scene(card.key).text.orEmpty(), card.place ?: card.day.orEmpty(), LINE_MAX) { v -> ed.vm.setDayCardText(card.key, v) }
        NoteAndSpot(ed, card.key, "What happened that day")
    }
}
