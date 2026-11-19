package com.justdataplease.boringtravel.ui.clip

import com.justdataplease.boringtravel.clip.StopStays
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipEdits
import com.justdataplease.boringtravel.clip.ClipFont
import com.justdataplease.boringtravel.clip.CameraMode
import com.justdataplease.boringtravel.clip.ClipLength
import com.justdataplease.boringtravel.clip.ClipLook
import com.justdataplease.boringtravel.clip.ClipOptions
import com.justdataplease.boringtravel.clip.ClipPlan
import com.justdataplease.boringtravel.clip.ClipScene
import com.justdataplease.boringtravel.clip.ClipSource
import com.justdataplease.boringtravel.clip.ClipText
import com.justdataplease.boringtravel.clip.ClipTimeline
import com.justdataplease.boringtravel.clip.ClipTiming
import com.justdataplease.boringtravel.clip.ClipTone
import com.justdataplease.boringtravel.clip.ClipView
import com.justdataplease.boringtravel.clip.ClipZoom
import com.justdataplease.boringtravel.clip.CloseUp
import com.justdataplease.boringtravel.clip.ZoomOut
import com.justdataplease.boringtravel.clip.LineColor
import com.justdataplease.boringtravel.clip.LineStyle
import com.justdataplease.boringtravel.clip.LineWeight
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.clip.PaperVehicle3D
import com.justdataplease.boringtravel.clip.PhotoMode
import com.justdataplease.boringtravel.clip.SceneOverride
import com.justdataplease.boringtravel.clip.TextColor
import com.justdataplease.boringtravel.clip.VehicleSprites
import com.justdataplease.boringtravel.clip.ZoomLimits
import com.justdataplease.boringtravel.clip.music.MusicCatalog
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.studio.StudioButton
import com.justdataplease.boringtravel.ui.studio.StudioChip
import com.justdataplease.boringtravel.ui.studio.StudioColorChoices
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioField
import com.justdataplease.boringtravel.ui.studio.StudioGrid
import com.justdataplease.boringtravel.ui.studio.StudioNote
import com.justdataplease.boringtravel.ui.studio.StudioPanel
import com.justdataplease.boringtravel.ui.studio.StudioScopeToggle
import com.justdataplease.boringtravel.ui.studio.StudioSection
import com.justdataplease.boringtravel.ui.studio.StudioSwitchRow
import com.justdataplease.boringtravel.ui.studio.StudioText
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.trip.travel
import com.justdataplease.boringtravel.ui.trip.countsKm
import com.justdataplease.boringtravel.ui.trip.legKmChoice
import com.justdataplease.boringtravel.ui.trip.legKmLine
import com.justdataplease.boringtravel.ui.theme.BtFonts
import java.util.Locale
import kotlin.math.roundToInt

private val S = StudioColors

/**
 * What the tool panels read and change: the clip as it is ([plan], [design], [options], [timeline], its [scenes]),
 * and the view-model's steps, every one an undo step. [enabled] is false while the video is exported.
 */
internal class ClipEditing(
    val vm: ClipViewModel,
    val plan: ClipPlan,
    val design: ClipDesign,
    val options: ClipOptions,
    val timeline: ClipTimeline,
    val scenes: List<ClipScene>,
    val isPro: Boolean,
    val enabled: Boolean,
    /** The place keys a day card can open. */
    val opensDay: Set<String>,
) {
    val edits: ClipEdits get() = design.edits

    /** The places in clip order (where a video can go, where a voice line starts). */
    val placeKeys: List<String> = plan.labels.map { it.key }

    /** The frame's width over its height (the thumb pads have its shape). */
    val aspect: Float get() = options.shape.aspect.toFloat()

    /** A planned route: the stops' own titles, Skip stop, on foot between the items of a place. */
    val planned: Boolean get() = vm.source.value == ClipSource.Planned

    /** A change to scene [key] (one undo step, or joined to the last with the same [merge] key: typing). */
    fun change(key: String, merge: String? = null, f: (SceneOverride) -> SceneOverride) = vm.editScenes(merge) { it.change(key, f) }

    fun edit(merge: String? = null, f: (ClipEdits) -> ClipEdits) = vm.editScenes(merge, f)

    /** A change to the whole clip's style (remembered for the next clip). */
    fun style(f: (ClipDesign) -> ClipDesign) = vm.setDesign(f)

    /** "Day 14 · El Chaltén" for a place key, as it shows (its own text, else the automatic one). */
    fun placeName(key: String?): String {
        val range = plan.labels.firstOrNull { it.key == key } ?: return "Place"
        return (design.label(key, range.label) ?: range.label).text.ifEmpty { "Place" }
    }

    /** What the chosen scene is called on the panels and the SCENE group. */
    fun name(pick: ScenePick): String = when (pick) {
        ScenePick.Opening -> "Title"
        ScenePick.End -> "End"
        is ScenePick.Place -> placeName(pick.key)
        is ScenePick.Photo -> "Photo"
        is ScenePick.Video -> "Video ${design.videos.indexOfFirst { it.id == pick.id } + 1}"
        is ScenePick.DayCard -> plan.dayStarts.firstOrNull { it.key == pick.key }?.let { "${it.day ?: it.date} card" } ?: "Day card"
    }

    /** The place a voice line or a video goes with for [pick]: a place itself, a day card's first place, a video's place. */
    fun placeOf(pick: ScenePick?): String? = when (pick) {
        is ScenePick.Place -> pick.key
        is ScenePick.DayCard -> plan.dayStarts.firstOrNull { it.key == pick.key }?.opens
        is ScenePick.Video -> design.videos.firstOrNull { it.id == pick.id }?.sceneKey
        is ScenePick.Photo -> scenes.firstOrNull { it.photoId == pick.id }?.placeKey
        else -> null
    }
}

/** The Photo Picker (videos only) and the microphone, asked for here so their answers outlive a panel. */
internal class EditorMedia(val addVideo: (sceneKey: String) -> Unit, val record: (sceneKey: String) -> Unit)

@Composable
internal fun rememberEditorMedia(vm: ClipViewModel): EditorMedia {
    var pickingFor by rememberSaveable { mutableStateOf<String?>(null) }
    var micFor by rememberSaveable { mutableStateOf<String?>(null) }
    // RECORD_AUDIO is asked for here, at the call site; the view-model only records once it is granted.
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        val key = micFor
        micFor = null
        // Granted: it records; refused: the view-model says to allow the microphone.
        if (key != null) vm.startVoice(key)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val key = pickingFor
        pickingFor = null
        if (uri != null && key != null) vm.addVideo(uri, key)
    }
    return remember(vm, mic, picker) {
        EditorMedia(
            addVideo = { key ->
                vm.dismissMediaNote()
                pickingFor = key
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            },
            record = { key ->
                vm.dismissVoiceNote()
                if (vm.hasMicPermission()) {
                    vm.startVoice(key)
                } else {
                    micFor = key
                    mic.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            },
        )
    }
}

/**
 * The open tool's panel: its title, "All scenes · This scene" where the tool has both (This scene acts on [target]:
 * the chosen scene, else the one under the playhead), and the yellow Done.
 */
@Composable
internal fun ToolPanel(
    tool: ClipTool, ed: ClipEditing, target: ScenePick?, chosen: Boolean, placeHere: String?, media: EditorMedia, musicFailed: Boolean,
    onChoose: (ScenePick?) -> Unit, onDone: () -> Unit, onPro: () -> Unit,
) {
    // A scene chosen from a list here (a video, a line, a day) turns the panel to it.
    var sceneSide by remember(tool, target?.key?.takeIf { chosen }) { mutableStateOf(tool.opensOnScene(target, chosen, ed.opensDay)) }
    val hasScene = tool.forScene(target, ed.opensDay)
    val sceneTarget = target?.takeIf { sceneSide && hasScene }
    val onScene = sceneTarget != null
    StudioPanel(
        tool.label, onDone,
        header = {
            if (hasScene) StudioScopeToggle(onScene, { v ->
                sceneSide = v
                // "This scene" chooses it on the tracks too, so it stays the one the tools act on.
                if (v && !chosen) onChoose(target)
            })
        },
    ) {
        if (sceneTarget != null) {
            SceneLine(ed, sceneTarget)
            when (tool) {
                ClipTool.Text -> {
                    TextScene(ed, sceneTarget)
                    // A place that opens a day also has that day's card here.
                    if (sceneTarget is ScenePick.Place && sceneTarget.key in ed.opensDay) {
                        Part("Day card")
                        DayCardScene(ed, sceneTarget)
                    }
                }
                ClipTool.Audio -> VoiceScene(ed, sceneTarget, media)
                ClipTool.Media -> when (sceneTarget) {
                    // A photo, then all its place's media; a place's photos and videos together (owner, 2026-11-14: "in
                    // clips videos are added as photos and have the same structure"); a video's own panel.
                    is ScenePick.Photo -> {
                        OnePhoto(ed, sceneTarget)
                        ed.placeOf(sceneTarget)?.let { place ->
                            Part(ed.placeName(place))
                            PlaceMediaPanel(ed, place, media, onChoose)
                        }
                    }
                    is ScenePick.Place -> PlaceMediaPanel(ed, sceneTarget.key, media, onChoose)
                    else -> VideoScene(ed, sceneTarget, onChoose)
                }
                ClipTool.Camera -> ZoomScene(ed, sceneTarget)
                ClipTool.Length -> TimingScene(ed, sceneTarget)
                ClipTool.Route -> RouteScene(ed, sceneTarget)
                ClipTool.Look, ClipTool.Stats -> Unit
            }
        } else {
            when (tool) {
                ClipTool.Text -> {
                    // The style first (size, font, place), then every text (owner, 2026-11-09: "font size etc are very
                    // important": it was below a long list of names).
                    TextAll(ed)
                    Part("Each text")
                    EachText(ed, onChoose)
                    Part("Day cards")
                    DayCardAll(ed, onChoose)
                    Part("Tags")
                    TagsAll(ed)
                }
                ClipTool.Audio -> {
                    MusicAll(ed, musicFailed)
                    Part("Voice")
                    VoiceAll(ed, placeHere, media, onChoose)
                }
                // How photos show, and each place's media; a video is reached through its place or on the tracks.
                ClipTool.Media -> PhotosAll(ed, onChoose)
                ClipTool.Camera -> {
                    MapAll(ed)
                    Part("How close")
                    ZoomAll(ed)
                }
                ClipTool.Length -> SpeedAll(ed, onPro)
                ClipTool.Route -> {
                    RouteAll(ed)
                    Part("Traveller & rides")
                    TravellerAll(ed)
                }
                ClipTool.Look -> LookAll(ed)
                ClipTool.Stats -> StatsAll(ed)
            }
        }
    }
}

/** "THIS SCENE · DAY 14 · EL CHALTÉN", and Reset when the scene was changed. */
@Composable
private fun SceneLine(ed: ClipEditing, pick: ScenePick) {
    val key = pick.key
    val resettable = pick !is ScenePick.Video
    val edited = resettable && (ed.edits.scenes.containsKey(key) || ed.edits.order.containsKey(key) || ed.edits.picks.containsKey(key))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("THIS SCENE · " + ed.name(pick).uppercase(LocalConfiguration.current.locales[0] ?: Locale.ROOT), Modifier.weight(1f), style = StudioText.label.copy(color = S.yellow), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (edited) StudioChip("Reset scene", false, { ed.edit { it.reset(key) } }, enabled = ed.enabled)
    }
}

// ---------------------------------------------------------------- shared controls

/** A part of a merged panel ("Voice" under the music): a hairline and its name. */
@Composable
internal fun Part(label: String) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(S.raised))
        Text(label.uppercase(), style = StudioText.group, color = S.yellow, maxLines = 1)
    }
}

/**
 * The title, each stop and the end, folded under one row (owner, 2026-11-09: "on text you have all the stops and it's
 * difficult to find the below settings … maybe have like edit stops"): open, it lists them; one tapped is chosen.
 */
@Composable
private fun EachText(ed: ClipEditing, onChoose: (ScenePick?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val count = ed.plan.labels.size
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).background(S.raised)
            .clickable(role = Role.Button, onClickLabel = if (open) "Hide the stops" else "Show the stops") { open = !open }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Edit stops", style = StudioText.item, maxLines = 1)
            Text("The title, ${if (count == 1) "1 stop" else "$count stops"} and the end: name, note, size, place and font of one", style = StudioText.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(if (open) BtIcons.Up else BtIcons.Down, null, Modifier.size(18.dp), tint = S.muted)
    }
    if (open) AllTexts(ed, onChoose)
}

/**
 * Every text in clip order, each with its note or spot under it: tap one to edit it (it is chosen on the tracks and
 * the panel turns to it).
 */
@Composable
internal fun AllTexts(ed: ClipEditing, onChoose: (ScenePick?) -> Unit) {
    val d = ed.design
    StudioSection("Tap one to change only it") {
        @Composable
        fun row(title: String, sub: String?, pick: ScenePick, dim: Boolean = false) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(S.raised)
                    .clickable(enabled = ed.enabled, role = Role.Button, onClickLabel = "Edit") { onChoose(pick) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = StudioText.item, color = if (dim) S.muted else S.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sub != null) Text(sub, style = StudioText.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(BtIcons.Forward, null, Modifier.size(16.dp), tint = S.muted)
            }
        }
        val o = d.scene(ClipDesign.OPENING)
        row(o.text?.takeIf { it.isNotBlank() } ?: ed.plan.title, listOfNotNull("Title", d.note(ClipDesign.OPENING)).joinToString(" · "), ScenePick.Opening, dim = !d.openingVisible)
        for (range in ed.plan.labels) {
            val shown = d.label(range.key, range.label)
            val sub = listOfNotNull(d.note(range.key), d.spot(range.key)).joinToString(" · ").ifEmpty { if (shown == null) "Hidden" else "Add a note or the exact spot" }
            row((shown ?: range.label).text.ifEmpty { "Place" }, sub, ScenePick.Place(range.key), dim = shown == null)
        }
        val end = d.scene(ClipDesign.END).text?.takeIf { it.isNotBlank() } ?: ed.plan.endCard.stats
        row(end, "End", ScenePick.End)
    }
}

/** How the traveller reached the place [pick] on a planned route (the leg into it), as the plan's travel chips. */
@Composable
internal fun RouteScene(ed: ClipEditing, pick: ScenePick) {
    val key = (pick as? ScenePick.Place)?.key ?: return
    val leg = ed.vm.legInto(key)
    if (leg == null) {
        StudioNote("How each leg travels is set for planned routes. This route follows your recording.")
        return
    }
    val now = leg.travel
    StudioSection("How you got here", value = now.label) {
        StudioGrid(LEG_VEHICLES, 3) { v ->
            StudioChip(v.label, v == now, { ed.vm.setLegVehicle(leg, v.takeIf { it != now }) }, Modifier.fillMaxWidth(), enabled = ed.enabled)
        }
    }
    // Owner, 2026-11-11: the stats line's km leaves a flight out unless it is switched on, as in the plan.
    StudioSwitchRow("Count in km", legKmLine(leg.distanceM, leg.countsKm), leg.countsKm) { on ->
        if (ed.enabled) ed.vm.setLegKm(leg, legKmChoice(now, on))
    }
    val failed by ed.vm.legSaveFailed.collectAsStateWithLifecycle()
    if (failed) StudioNote("Couldn't save travel. Try again.", warn = true)
    StudioNote("From ${leg.fromTitle}. The same choice shows in the plan.")
}

/** The travel a planned leg can take. */
private val LEG_VEHICLES = listOf(Vehicle.Walk, Vehicle.Bike, Vehicle.Car, Vehicle.Train, Vehicle.Ferry, Vehicle.Plane)

/** A labelled row (or grid of [columns]) of chips, the picked one yellow. */
@Composable
internal fun <T> Chips(
    label: String, options: List<T>, selected: T?, text: (T) -> String, enabled: Boolean, columns: Int = options.size,
    font: (@Composable (T) -> FontFamily)? = null, value: String? = null, onPick: (T) -> Unit,
) {
    StudioSection(label, value = value) {
        StudioGrid(options, columns.coerceAtLeast(1)) { o ->
            StudioChip(text(o), o == selected, { onPick(o) }, Modifier.fillMaxWidth(), fontFamily = font?.invoke(o), enabled = enabled)
        }
    }
}

/** A one-line text box under a label; [placeholder] is what shows when it is empty (the automatic text). */
@Composable
internal fun Field(label: String, value: String, placeholder: String, maxLength: Int, onChange: (String) -> Unit) {
    StudioSection(label) {
        StudioField(value, { onChange(it.replace('\n', ' ').take(maxLength)) }, placeholder, singleLine = true)
    }
}

@Composable
internal fun fontFamily(font: ClipFont): FontFamily = when (font) {
    ClipFont.Theme -> Bt.tokens.type.display
    ClipFont.Sans -> BtFonts.albert
    ClipFont.Serif -> BtFonts.fraunces
    ClipFont.Hand -> BtFonts.caveat
    ClipFont.Tall -> BtFonts.barlowCondensed
}

@Composable
internal fun textSwatch(color: TextColor): Color = when (color) {
    TextColor.Light -> Color.White
    TextColor.Dark -> Color(0xFF16161C)
    TextColor.Theme -> Bt.colors.primary
    TextColor.Accent -> Bt.colors.accent
}

@Composable
private fun lineSwatch(color: LineColor): Color = when (color) {
    LineColor.Theme -> Bt.colors.route
    LineColor.White -> Color.White
    LineColor.Black -> Color(0xFF16161C)
    LineColor.Accent -> Bt.colors.accent
}

/** Text or line colours as named swatches. */
@Composable
internal fun <T> ColourChoice(label: String, options: List<T>, selected: T?, name: (T) -> String, swatch: @Composable (T) -> Color, enabled: Boolean, onPick: (T) -> Unit) {
    StudioSection(label) {
        StudioColorChoices(options.map { swatch(it) }, options.map(name), options.indexOf(selected), { onPick(options[it]) }, enabled)
    }
}

/**
 * The camera's zoom as a five-step slider from "Whole route" to "Very close", the chosen step's name beside the
 * label. The steps [map] can't reach at [routeY] (Earth's pictures blur closer) show as a dashed end of the track. A
 * drag picks on release, so the clip re-plans once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZoomSlider(label: String, value: ClipZoom, enabled: Boolean, map: MapStyle, routeY: Double?, onPick: (ClipZoom) -> Unit) {
    val steps = ClipZoom.entries
    val last = steps.lastIndex
    var dragging by remember { mutableStateOf<Float?>(null) }
    val at = dragging?.roundToInt()?.coerceIn(0, last) ?: value.ordinal
    val firstCapped = steps.indexOfFirst { z -> routeY != null && ZoomLimits.limits(map, z.floorM, routeY) }.takeIf { it >= 0 }
    StudioSection(label, value = steps[at].label) {
        Slider(
            value = dragging ?: value.ordinal.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { v -> steps[v.roundToInt().coerceIn(0, last)].takeIf { it != value }?.let(onPick) }
                dragging = null
            },
            valueRange = 0f..last.toFloat(),
            steps = last - 1,
            enabled = enabled,
            thumb = { Box(Modifier.size(20.dp).clip(CircleShape).background(if (enabled) S.yellow else S.muted)) },
            track = {
                Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                    val y = size.height / 2
                    val w = 4.dp.toPx()
                    fun x(i: Int) = size.width * i / last
                    // Solid as far as the map goes, dashed past it.
                    val solidTo = firstCapped?.let { x((it - 1).coerceAtLeast(0)) } ?: size.width
                    drawLine(S.raised, Offset(0f, y), Offset(solidTo, y), w, StrokeCap.Round)
                    if (firstCapped != null) {
                        drawLine(
                            S.edge, Offset(solidTo, y), Offset(size.width, y), w / 2,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                        )
                    }
                    drawLine(if (enabled) S.yellow else S.muted, Offset(0f, y), Offset(x(at), y), w, StrokeCap.Round)
                    for (i in 0..last) drawCircle(if (i <= at && enabled) S.yellow else S.edge, 3.dp.toPx(), Offset(x(i), y))
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { contentDescription = "$label: ${steps[at].label}" },
        )
        Row {
            Text(steps.first().label, Modifier.weight(1f), style = StudioText.sub)
            Text(steps.last().label, style = StudioText.sub)
        }
    }
}

/** Under the zoom: when the map can't go as close as [zoom] or [closeUp] ask, how close it goes and which maps do. */
@Composable
internal fun ZoomLimitNote(map: MapStyle, routeY: Double?, zoom: ClipZoom, closeUp: CloseUp) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val y = routeY ?: return
    val note = ZoomLimits.note(map, minOf(zoom.floorM, closeUp.floorM), y, locale) ?: return
    StudioNote(note)
}

// ---------------------------------------------------------------- SCENE › Timing

@Composable
private fun TimingScene(ed: ClipEditing, pick: ScenePick) {
    when (pick) {
        is ScenePick.Photo -> PhotoPause(ed, pick)
        is ScenePick.Video -> ed.design.videos.firstOrNull { it.id == pick.id }?.let { VideoTrim(ed, it) }
        is ScenePick.Place -> StopStay(ed, pick)
        else -> Unit
    }
}

/**
 * A place's "Time here" (owner, 2026-11-12: "a time specific menu to adjust how much time to stay there in seconds a
 * slider"): Auto (longer for a longer From–To) or the seconds set on the slider; the journey stands still there.
 */
@Composable
private fun StopStay(ed: ClipEditing, pick: ScenePick.Place) {
    val own = ed.design.scene(pick.key).stay
    val now = own ?: ed.plan.stays[pick.key] ?: 0.0
    Chips("Time here", listOf(true, false), own == null, { if (it) "Auto" else "Set by hand" }, ed.enabled) { auto ->
        ed.change(pick.key) { it.copy(stay = if (auto) null else StopStays.half(now)) }
    }
    ValueSlider("Seconds here", now.toFloat(), 0f..StopStays.MAX_S.toFloat(), { secondsLabel(it, "No stop") }, ed.enabled) { v ->
        ed.change(pick.key) { it.copy(stay = StopStays.half(v.toDouble())) }
    }
    StudioNote(if (own == null) "Auto: an item with a From and To gets more time the longer it lasts." else "The trip stands still here this long.")
}

/** A photo's own pause (the journey waits while it shows). */
@Composable
internal fun PhotoPause(ed: ClipEditing, pick: ScenePick.Photo) {
    val scene = ed.scenes.firstOrNull { it.photoId == pick.id }
    if (scene != null && !scene.shown) {
        StudioNote("This photo isn't in the clip. Put it in with Photos.")
        return
    }
    val clip = ed.design.holdSeconds
    val hold = ed.plan.photoScenes.firstOrNull { it.id == pick.id }?.hold ?: clip
    ValueSlider("Pause", hold.toFloat(), 0f..ClipDesign.MAX_HOLD_S.toFloat(), { secondsLabel(it, "No pause") }, ed.enabled) { v ->
        val h = com.justdataplease.boringtravel.clip.Spots.hold(v.toDouble())
        ed.change(pick.key) { it.copy(hold = h.takeIf { x -> x != clip }) }
    }
}

// ---------------------------------------------------------------- SCENE › Zoom

@Composable
private fun ZoomAll(ed: ClipEditing) {
    val d = ed.design
    val map = ed.options.map
    val y = ed.plan.overview.cy
    // Owner, 2026-11-09: "maybe i have a close by region camera that just follows whatever ... did not work in
    // preview": the calm camera stays high enough for the whole stretch, so How close shows little on a fast clip.
    val follow = d.camera == CameraMode.Chase
    Chips("Camera moves", listOf(false, true), follow, { if (it) "Follow" else "Calm" }, ed.enabled) { v ->
        ed.style { it.copy(camera = if (v) CameraMode.Chase else CameraMode.Steady) }
    }
    StudioNote(
        if (follow) "Stays as close as you set below and follows the traveller, however fast. Flights still go up."
        else "Stays high enough to keep each day calm, so on a short video of a long trip it stays far.",
    )
    ZoomSlider("How close", d.zoom, ed.enabled, map, y) { v -> ed.style { it.copy(zoom = v) } }
    // Owner, 2026-11-09, on the globe: "when zoom out to fix a level to zoom out".
    Chips("Zoom out to", ZoomOut.entries, d.zoomOut, { it.label }, ed.enabled, columns = 2) { v -> ed.style { it.copy(zoomOut = v) } }
    StudioNote(
        when (d.zoomOut) {
            ZoomOut.Auto -> "At the start, the end and on long hops: from space for a far trip, else the region around it."
            ZoomOut.Space -> if (map == MapStyle.Globe) "The globe from space at the start and the end." else "Far out at the start and the end."
            ZoomOut.Region -> "The region around the route at the start and the end; long hops never go wider."
            ZoomOut.Route -> "Just the whole route at the start and the end; the camera never goes wider."
        },
    )
    Chips("Stop close-up", CloseUp.entries, d.closeUp, { it.label }, ed.enabled) { v -> ed.style { it.copy(closeUp = v) } }
    StudioNote(
        if (d.closeUp.on) "At some stops the trip waits while the camera goes in close. Choose a place for its own close-up."
        else "Go in close at stops: the trip waits there a moment.",
    )
    ZoomLimitNote(map, y, d.zoom, d.closeUp)
}

@Composable
private fun ZoomScene(ed: ClipEditing, pick: ScenePick) {
    val key = (pick as? ScenePick.Place)?.key ?: return
    val d = ed.design
    val map = ed.options.map
    val y = ed.plan.overview.cy
    val o = d.scene(key)
    ZoomSlider("How close here", d.zoom(key), ed.enabled, map, y) { v ->
        ed.change(key) { it.copy(zoom = (v.ordinal - d.zoom.ordinal).takeIf { s -> s != 0 }) }
    }
    o.zoom?.let { StudioNote(if (it > 0) "Closer than the rest of the clip." else "Wider than the rest of the clip.") }
    Chips("Stop close-up here", CloseUp.entries, d.closeUp(key), { it.label }, ed.enabled) { v ->
        ed.change(key) { it.copy(closeUp = v.takeIf { c -> c != d.closeUp }) }
    }
    ZoomLimitNote(map, y, d.zoom(key), d.closeUp(key))
}

// ---------------------------------------------------------------- SCENE › Speed

/**
 * How long the clip runs (owner, 2026-11-08: "make sure speeds are well defined, x1 seems huge"): Auto (25 s, 45 s
 * for long trips and wide clips) or a set length, everything fitting inside it; or Pace, the trip's travel time at ×1
 * to ×10 as before, each with the length it gives.
 */
@Composable
private fun SpeedAll(ed: ClipEditing, onPro: () -> Unit) {
    val p = ed.plan
    val length = ed.options.length
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    StudioSection("Length", value = ClipText.clock(p.frameCount, p.fps)) {
        StudioGrid(ClipLength.entries.toList<ClipLength?>() + null, 3) { l ->
            val label = when (l) {
                null -> "Pace"
                ClipLength.Auto -> "Auto · " + ClipTiming.clock(l.target(p.tripDays, ed.options.shape))
                else -> l.label
            }
            StudioChip(label, l == length, { ed.vm.setLength(l) }, Modifier.fillMaxWidth(), enabled = ed.enabled)
        }
    }
    if (length != null) {
        StudioNote(
            "Photos, videos and day cards fit inside it; the journey goes at about ×" +
                String.format(locale, if (p.pace < 10) "%.1f" else "%.0f", p.pace) + ". Pace follows your travel time instead.",
        )
        return
    }
    // The trip's travel time sets the length; the speed divides it; photo pauses, videos and day cards add to it.
    val lengths = ClipTiming.SPEEDS.associateWith { k -> ClipTiming.seconds(p.travelHours, k, p.photoHolds, p.addedSeconds) }
    val locked = lengths.filterValues { !ed.isPro && ClipTiming.needsPro(it) }.keys
    StudioSection("Pace") {
        StudioGrid(ClipTiming.SPEEDS, 4) { k ->
            SpeedCell(k, lengths.getValue(k), ed.options.speed == k, ed.enabled, k in locked) { if (k in locked) onPro() else ed.vm.setSpeed(k) }
        }
    }
    StudioNote(if (locked.isNotEmpty()) "The length follows your travel time. Clips over a minute are Pro." else "The length follows your travel time; videos, day cards and photo pauses add to it.")
}

/** "×2" over the clip's length at that speed ("0:42"); PRO when it runs past a free minute. */
@Composable
private fun SpeedCell(speed: Int, seconds: Double, selected: Boolean, enabled: Boolean, pro: Boolean, onClick: () -> Unit) {
    val fg = when {
        !enabled -> S.muted
        selected -> S.onYellow
        else -> S.ink
    }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) S.yellow else S.raised)
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("×$speed", style = StudioText.chip, color = fg, maxLines = 1)
        Text(if (pro) "PRO" else ClipTiming.clock(seconds), style = StudioText.value.copy(fontSize = StudioText.sub.fontSize), color = if (pro) S.yellow.takeIf { !selected } ?: fg else fg, maxLines = 1)
    }
}

// ---------------------------------------------------------------- STYLE › Look

@Composable
private fun LookAll(ed: ClipEditing) {
    val d = ed.design
    Chips("Look", ClipLook.entries, d.look, { it.label }, ed.enabled, columns = 2) { v -> ed.vm.setLook(v) }
    StudioNote(
        if (d.look == null) "Custom: an option changed after picking a look. Pick one to start again."
        else "A look sets the type, the line and the finish together. Change any of them after.",
    )
    StudioSection("Finish") {
        StudioSwitchRow("Letterbox bars", "Film bars at the top and bottom", d.bars) { on -> if (ed.enabled) ed.style { it.copy(bars = on) } }
        StudioSwitchRow("Grain", "A light film grain over the frame", d.grain) { on -> if (ed.enabled) ed.style { it.copy(grain = on) } }
    }
    Chips("Tone", ClipTone.entries, d.tone, { it.label }, ed.enabled) { v -> ed.style { it.copy(tone = v) } }
    if (ed.edits.hasSceneEdits) {
        StudioSection("Every scene") {
            StudioButton("Reset every scene", { ed.edit { it.resetScenes() } }, Modifier.fillMaxWidth(), enabled = ed.enabled)
            StudioNote("Names, photos and camera changes go back to the clip's own. Your videos and voice lines stay.")
        }
    }
}

// ---------------------------------------------------------------- STYLE › Map

@Composable
private fun MapAll(ed: ClipEditing) {
    val status by ed.vm.mapStatus.collectAsStateWithLifecycle()
    val o = ed.options
    Chips("Map", MapStyle.entries, o.map, { it.label }, ed.enabled) { ed.vm.setMapStyle(it) }
    if (o.map == MapStyle.Earth) StudioNote("Satellite pictures from 2016, so the camera stays a little higher.")
    if (o.map == MapStyle.Globe) StudioNote("Starts from space over your trip, comes down to the streets and goes back up for long flights.")
    ZoomLimitNote(o.map, ed.plan.overview.cy, ed.design.zoom, ed.design.closeUp)
    if (status == ClipViewModel.MapStatus.Plain) {
        StudioNote("Your map couldn't load. Connect to the internet and try again.", warn = true)
        StudioButton("Retry map", ed.vm::retryMap, Modifier.fillMaxWidth())
    }
    Chips("Camera", ClipView.entries, o.view, { if (it == ClipView.Perspective) "3D" else it.label }, ed.enabled) { ed.vm.setView(it) }
    StudioNote(
        if (o.view == ClipView.Perspective) "A tilted camera follows your route. Terrain elevation is not included."
        else "The map seen from straight above, no sky.",
    )
    if (o.view == ClipView.Perspective) {
        val d = ed.design
        StudioSwitchRow("Sky", "A band of sky at the top", d.sky) { on -> if (ed.enabled) ed.style { it.copy(sky = on) } }
    }
}

// ---------------------------------------------------------------- STYLE › Route

@Composable
private fun RouteAll(ed: ClipEditing) {
    val sources by ed.vm.sources.collectAsStateWithLifecycle()
    val source by ed.vm.source.collectAsStateWithLifecycle()
    val planned by ed.vm.planned.collectAsStateWithLifecycle()
    val d = ed.design
    if (sources.size > 1) {
        Chips("Route from", sources, source, { it.label }, ed.enabled, columns = sources.size.coerceAtMost(3)) { ed.vm.setSource(it) }
    }
    val note = when (source) {
        ClipSource.Planned -> buildString {
            append("Straight lines between places. How you travel each leg is set in the plan.")
            planned?.let {
                if (it.missingLocations > 0) append(" ${it.missingLocations} without a map location skipped.")
                if (it.unscheduledPlaces > 0) append(" Undated ideas come after the dated places.")
            }
        }
        ClipSource.Stops -> "Straight lines between your stops."
        else -> null
    }
    note?.let { StudioNote(it) }
    if (source == ClipSource.Planned) SkippedStops(ed)
    if (source == ClipSource.Recorded) {
        val sprites by ed.vm.vehicleSprites.collectAsStateWithLifecycle()
        StudioSection("Travel") {
            StudioGrid(Vehicle.entries.toList(), 4) { v ->
                VehicleCell(v, ed.options.vehicle == v, sprites[v], ed.enabled) { ed.vm.setVehicle(v) }
            }
        }
        if (ed.options.vehicle == Vehicle.Auto) StudioNote("Guessed by speed: " + ed.plan.vehiclesUsed.joinToString(", ") { it.label })
    }
    Chips("Line", LineStyle.entries, d.line, { it.label }, ed.enabled) { v -> ed.style { it.copy(line = v) } }
    Chips("Width", LineWeight.entries, d.weight, { it.label }, ed.enabled) { v -> ed.style { it.copy(weight = v) } }
    ColourChoice("Colour", LineColor.entries, d.lineColor, { it.label }, { lineSwatch(it) }, ed.enabled) { v -> ed.style { it.copy(lineColor = v) } }
}

/** The planned stops left out of the video (Skip stop on a chosen place), each with Put back. */
@Composable
private fun SkippedStops(ed: ClipEditing) {
    val gone = ed.plan.skippedStops
    StudioSection("Left out", value = if (gone.isEmpty()) null else "${gone.size}") {
        if (gone.isEmpty()) {
            StudioNote("Choose a place on the tracks and tap Skip stop to leave it out; the route goes straight to the next.")
        } else {
            gone.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(S.raised).padding(start = 12.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(s.title, style = StudioText.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        s.place?.takeIf { it != s.title }?.let { Text(it, style = StudioText.sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    StudioChip("Put back", false, { ed.vm.skipStops(listOf(s.id), false) }, enabled = ed.enabled)
                }
            }
        }
    }
}

/** A vehicle for a recorded journey: its paper model (or the sparkle for Auto) over its name. */
@Composable
private fun VehicleCell(v: Vehicle, on: Boolean, sprite: Bitmap?, enabled: Boolean, onPick: () -> Unit) {
    val fg = if (on) S.onYellow else S.ink
    Column(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(10.dp)).background(if (on) S.yellow else S.raised)
            .semantics { this.selected = on }
            .clickable(enabled = enabled, role = Role.RadioButton, onClickLabel = v.label, onClick = onPick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        if (v == Vehicle.Auto) {
            Icon(BtIcons.Sparkle, null, Modifier.size(22.dp), tint = fg)
        } else if (sprite != null) {
            Image(remember(sprite) { sprite.asImageBitmap() }, null, Modifier.size(40.dp), contentScale = ContentScale.Fit)
        } else {
            val renderer = remember(v) { PaperVehicle3D() }
            val tokens = Bt.tokens
            val palette = remember(tokens) { PaperVehicle3D.Palette.of(tokens) }
            Canvas(Modifier.size(40.dp)) {
                drawIntoCanvas { canvas ->
                    renderer.draw(canvas.nativeCanvas, v, size.width / 2f, size.height / 2f, size.width, VehicleSprites.HEADING, VehicleSprites.TILT, 0f, palette)
                }
            }
        }
        Text(v.label, style = StudioText.sub, color = fg, maxLines = 1)
    }
}

// ---------------------------------------------------------------- SOUND › Music

@Composable
private fun MusicAll(ed: ClipEditing, failed: Boolean) {
    val onBeat by ed.vm.onBeat.collectAsStateWithLifecycle()
    val preview = rememberMusicPreview()
    MusicCatalog.byId(ed.options.music)?.let { track ->
        MusicStart(ed, track, preview)
        StudioSwitchRow("On the beat", "Scenes change on the music's beats", onBeat) { on -> if (ed.enabled) ed.vm.setOnBeat(on) }
    }
    MusicList(ed.options.music, ed.enabled, preview, onPick = ed.vm::setMusic)
    if (failed) StudioNote("Couldn't add the music, so the last video is silent.", warn = true)
}

/** A ring and a short line: an empty place for something to add. */
@Composable
internal fun DashedNote(text: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, S.edge, RoundedCornerShape(10.dp)).padding(12.dp)) {
        StudioNote(text)
    }
}
