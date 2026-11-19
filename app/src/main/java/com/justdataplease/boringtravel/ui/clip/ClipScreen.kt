package com.justdataplease.boringtravel.ui.clip

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.clip.ClipBeats
import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipExport
import com.justdataplease.boringtravel.export.Sharing
import com.justdataplease.boringtravel.clip.ClipPlan
import com.justdataplease.boringtravel.clip.ClipScene
import com.justdataplease.boringtravel.clip.ClipScenes
import com.justdataplease.boringtravel.clip.ClipShape
import com.justdataplease.boringtravel.clip.ClipStyle
import com.justdataplease.boringtravel.clip.ClipTarget
import com.justdataplease.boringtravel.clip.ClipText
import com.justdataplease.boringtravel.clip.ClipTimeline
import com.justdataplease.boringtravel.clip.ClipTiming
import com.justdataplease.boringtravel.clip.ExportStage
import com.justdataplease.boringtravel.clip.music.MusicCatalog
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.studio.StudioButton
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioIconButton
import com.justdataplease.boringtravel.ui.studio.StudioIcons
import com.justdataplease.boringtravel.ui.studio.StudioMainButton
import com.justdataplease.boringtravel.ui.studio.StudioNote
import com.justdataplease.boringtravel.ui.studio.StudioScreen
import com.justdataplease.boringtravel.ui.studio.StudioSheet
import com.justdataplease.boringtravel.ui.studio.StudioText
import com.justdataplease.boringtravel.ui.studio.StudioTool
import com.justdataplease.boringtravel.ui.studio.StudioToolGroup
import com.justdataplease.boringtravel.ui.studio.StudioToolbar
import com.justdataplease.boringtravel.ui.studio.StudioTopBar
import com.justdataplease.boringtravel.ui.studio.groupLabel
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlin.math.roundToInt

private val S = StudioColors

/**
 * The trip video editor (owner: "video editor tracks", every clip option in one place): the dark studio with the
 * preview on top, the clip's tracks under it (scenes, photos, texts, music, voice) scrolling under a fixed playhead,
 * and the tools in groups at the bottom, each opening a panel for the whole clip or the chosen scene. Export makes the
 * MP4 and shares or saves it. Entry points: Media › Make a clip, the trip's menu.
 */
@Composable
fun ClipScreen(graph: AppGraph, actions: AppActions, tripId: String) {
    val vm: ClipViewModel = viewModel(key = "clip-$tripId") { ClipViewModel(graph, tripId) }
    val context = LocalContext.current
    val tokens = Bt.tokens
    LaunchedEffect(tokens) { vm.setStyle(ClipStyle.of(context, tokens)) }
    // The open tool and the chosen scene outlive a route change (the editor reloads under them).
    var tool by rememberSaveable { mutableStateOf<String?>(null) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }

    val load by vm.load.collectAsStateWithLifecycle()
    StudioScreen {
        // The app frame already stops above the gesture bar (AppNav paints it dark for the studio).
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            when (load) {
                ClipViewModel.Load.Loading -> {
                    StudioTopBar("Trip video", null, onClose = actions::back)
                    Message(null, "Opening your trip", busy = true)
                }
                ClipViewModel.Load.NoRoute -> {
                    StudioTopBar("Trip video", null, onClose = actions::back)
                    Message(
                        "No route yet",
                        "Add locations to at least two planned places, record a journey, or save two stops to make a trip film.",
                    ) { StudioButton("Open trip plan", onClick = { actions.openTrip(tripId, "plan") }, primary = true) }
                }
                ClipViewModel.Load.Failed -> {
                    StudioTopBar("Trip video", null, onClose = actions::back)
                    Message("Couldn't open this journey", "Your trip is saved. Try opening the route again.") {
                        StudioButton("Try again", onClick = vm::retryLoad, primary = true)
                    }
                }
                ClipViewModel.Load.Ready -> {
                    // The phone videos' pictures for every preview below (LocalClipVideos).
                    val videoFrames by vm.videoFrames.collectAsStateWithLifecycle()
                    CompositionLocalProvider(LocalClipVideos provides videoFrames) {
                        ClipEditor(
                            vm, actions,
                            tool = ClipTool.entries.firstOrNull { it.name == tool }, onTool = { tool = it?.name },
                            chosen = chosen, onChoose = { chosen = it },
                        )
                    }
                }
            }
        }
    }
}

/** A title, a line and an action in the middle of the dark screen (loading, no route, failed). */
@Composable
private fun Message(title: String?, body: String, busy: Boolean = false, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        if (busy) CircularProgressIndicator(color = S.yellow, strokeWidth = 2.dp)
        if (title != null) Text(title, style = StudioText.panelTitle, textAlign = TextAlign.Center)
        Text(body, style = StudioText.note, textAlign = TextAlign.Center)
        action()
    }
}

/**
 * The editor: top bar, preview, time and play, then the tracks and the tool row, or the open tool's panel. [chosen]
 * is the chosen scene's key ([ScenePick.key]); with none, "This scene" means the scene under the playhead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipEditor(
    vm: ClipViewModel, actions: AppActions, tool: ClipTool?, onTool: (ClipTool?) -> Unit, chosen: String?, onChoose: (String?) -> Unit,
) {
    val context = LocalContext.current
    val options by vm.options.collectAsStateWithLifecycle()
    val replanning by vm.replanning.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val base by vm.base.collectAsStateWithLifecycle()
    val mapStatus by vm.mapStatus.collectAsStateWithLifecycle()
    val mapProgress by vm.mapProgress.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val style by vm.style.collectAsStateWithLifecycle()
    val design by vm.design.collectAsStateWithLifecycle()
    val make by vm.make.collectAsStateWithLifecycle()
    val drive by vm.drive.collectAsStateWithLifecycle()
    val phone by vm.phone.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val timeline by vm.timeline.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val working = make is ClipViewModel.Make.Working
    val done = make as? ClipViewModel.Make.Done
    val mapReady = base.hasMap
    // While the map's pictures still come in, playing waits for them (a close-up is never shown soft first).
    val mapLoading = mapProgress.takeIf { mapStatus == ClipViewModel.MapStatus.Loading }
    val player = remember { PreviewPlayer() }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var exporting by rememberSaveable { mutableStateOf(false) }
    var watching by rememberSaveable { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    val p = plan
    val s = style
    val t = timeline
    if (p == null || t == null) {
        StudioTopBar("Trip video", null, onClose = actions::back)
        Message(null, "Opening your trip", busy = true)
        return
    }
    // The preview prepared ahead once the detailed map is in (shared by the inline and the full-screen preview).
    val cache = if (s != null && mapReady) {
        rememberPreviewCache(p, base, s, photos, vm.noMark, design, settled = mapStatus != ClipViewModel.MapStatus.Loading && !replanning && !working, playhead = { player.frame })
    } else null

    // What is chosen: a scene still in the clip, else nothing (a re-plan or an undo can take it away).
    val scenes = remember(p, design) { ClipScenes.list(p, design) }
    val pick = remember(chosen, p, design) { ScenePick.of(chosen)?.takeIf { exists(it, p, design) } }
    LaunchedEffect(pick, chosen) { if (chosen != null && pick == null) onChoose(null) }
    val atPlayhead by remember(t) { derivedStateOf { ScenePick.at(t, player.frame) } }
    val target = pick ?: atPlayhead
    val opensDay = remember(p) { p.dayStarts.map { it.opens }.toSet() }

    // A new plan keeps the playhead in the clip, on the chosen scene when there is one.
    LaunchedEffect(p) {
        val wanted = pick?.let { sceneFrame(it, p, t, scenes) }
        player.frame = (wanted ?: player.frame).coerceIn(0, (p.frameCount - 1).coerceAtLeast(0))
    }
    fun choose(next: ScenePick?) {
        onChoose(next?.key)
        if (next != null) {
            player.playing = false
            sceneFrame(next, p, t, scenes)?.let { player.frame = it }
        }
    }
    BackHandler(tool != null || pick != null) {
        if (tool != null) onTool(null) else onChoose(null)
    }
    // An export or its sheet closes the open tool (its changes would wait); export starts from the button.
    LaunchedEffect(working) { if (working) onTool(null) }

    val needsPro = !vm.isPro && ClipTiming.needsPro(p.seconds)
    val canMake = s != null && mapReady && !replanning && !needsPro
    val editing = remember(vm, p, design, options, t, scenes, working, opensDay) {
        ClipEditing(vm, p, design, options, t, scenes, vm.isPro, enabled = !working, opensDay = opensDay)
    }
    val media = rememberEditorMedia(vm)
    // The place "+ Record" goes with: the chosen scene's, else the one under the playhead.
    val placeHere = editing.placeOf(target) ?: (atPlayhead as? ScenePick.Place)?.key

    // The music under the preview while it plays (not while exporting).
    PreviewMusic(player, p.fps, MusicCatalog.byId(options.music), vm.musicStartS, active = !working)

    Column(Modifier.fillMaxSize().imePadding()) {
        ClipTopBar(
            title = p.title, shape = options.shape, enabled = !working,
            exportLabel = (make as? ClipViewModel.Make.Working)?.let { "${(it.progress * 100).roundToInt()}%" } ?: "Export",
            onShape = vm::setShape, onClose = actions::back,
            onExport = {
                exporting = true
                if (make == ClipViewModel.Make.Idle && canMake) vm.makeClip()
            },
        )
        val panel = tool != null
        ClipStage(
            plan = p, base = base, style = s, photos = photos, isPro = vm.noMark, design = design, cache = cache, player = player,
            mapStatus = mapStatus, mapLoading = mapLoading, mapReady = mapReady,
            working = working, progress = (make as? ClipViewModel.Make.Working)?.progress,
            movable = pick?.takeIf { it is ScenePick.Place || it is ScenePick.Photo }?.key,
            still = !scrubbing && !fullScreen,
            active = !fullScreen,
            onDrop = { key, o -> vm.editScenes { it.with(key, o) } },
            onFullScreen = { fullScreen = true },
            onRetryMap = vm::retryMap,
            modifier = Modifier.weight(if (panel) 0.9f else 1f).fillMaxWidth(),
        )
        if (!WindowInsets.isImeVisible) {
            TimeRow(p, player, enabled = !working, canUndo = history.canUndo && !working, canRedo = history.canRedo && !working, onUndo = vm::undo, onRedo = vm::redo)
        }
        if (tool != null) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ToolPanel(
                    tool, editing, target, chosen = pick != null, placeHere = placeHere, media = media, musicFailed = done?.musicFailed == true,
                    onChoose = ::choose, onDone = { onTool(null) }, onPro = actions::paywall,
                )
            }
        } else {
            val model = remember(p, design, t, options.music) { TrackModel.of(p, design, t, trackBeats(t, options.music)) }
            val recordingKey = (voice as? ClipViewModel.Voice.Recording)?.sceneKey
            val recordAt = placeHere?.takeIf { k -> design.voice(k) == null }?.let { p.sceneStart(it) }
            ClipTracks(
                model = model, player = player, cache = cache, chosen = pick?.key,
                initialDpPerSecond = remember(t.fps) { TrackScale.fit(t).dpPerSecond },
                music = MusicCatalog.byId(options.music)?.name,
                recordAt = if (recordingKey == null) recordAt else null,
                recording = (voice as? ClipViewModel.Voice.Recording)?.let { r -> p.sceneStart(r.sceneKey)?.let { it to r.startedAt } },
                enabled = !working,
                onScrubbing = { scrubbing = it },
                onPick = ::choose,
                onMusic = { onTool(ClipTool.Audio) },
                onRecord = {
                    // Records for the place at the playhead (asking for the microphone first); the Voice panel shows Stop.
                    placeHere?.let { key ->
                        choose(ScenePick.Place(key))
                        onTool(ClipTool.Audio)
                        media.record(key)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!WindowInsets.isImeVisible) {
                // A planned place can be left out (Skip stop, beside Let go); undo or Route › Left out bring it back.
                val skipIds = (pick as? ScenePick.Place)?.let { pl -> p.labels.firstOrNull { it.key == pl.key }?.stops }?.takeIf { it.isNotEmpty() }
                val canSkip = skipIds != null && p.labels.sumOf { it.stops.size } - skipIds.size >= 2
                ClipToolbar(
                    tool = null, own = ClipTool.forPick(pick, opensDay), sceneName = pick?.let { editing.name(it) }, enabled = !working,
                    onTool = onTool, onWholeClip = { t -> onChoose(null); onTool(t) }, onClear = { onChoose(null) },
                    onSkip = if (canSkip) ({
                        onChoose(null)
                        vm.skipStops(skipIds, true)
                    }) else null,
                )
            }
        }
    }

    if (exporting) {
        ExportSheet(
            plan = p, make = make, drive = drive, music = MusicCatalog.byId(options.music)?.name, needsPro = needsPro,
            canMake = canMake, mapReady = mapReady,
            onMake = vm::makeClip, onCancel = vm::cancelClip,
            onWatch = { watching = true },
            onShare = {
                done?.let {
                    try {
                        context.startActivity(ClipExport.shareIntent(context, it.file))
                    } catch (_: ActivityNotFoundException) {
                        // No app can take a video; nothing else to offer.
                    }
                }
            },
            onDrive = vm::saveToDrive, onConnect = actions::backupSettings, onPro = actions::paywall,
            phone = phone, onPhone = vm::saveToPhone, onDriveLink = vm::shareDriveLink,
            onDismiss = { exporting = false },
        )
    }
    if (fullScreen) {
        if (s != null && mapReady) {
            FullScreenPreview(p, base, s, photos, vm.noMark, design, cache, player, paused = working, mapLoading = mapLoading, onClose = { fullScreen = false })
        } else {
            LaunchedEffect(Unit) { fullScreen = false }
        }
    }
    if (watching) {
        val file = done?.file
        if (file != null) {
            val spot = remember(file) { VideoSpot() }
            FullScreenVideo(file, options.shape, spot, onClose = { watching = false })
        } else {
            LaunchedEffect(Unit) { watching = false }
        }
    }
}

/** The music's beats for the music track: the clip's cuts when it cuts on them, else the track's own grid. */
private fun trackBeats(t: ClipTimeline, music: String?): List<Int> {
    if (t.beats.isNotEmpty()) return t.beats
    val track = MusicCatalog.byId(music) ?: return emptyList()
    return ClipBeats(track.beats, track.durationMs / 1000.0).frames(t.fps, t.frames).map { it.roundToInt() }
}

/** Whether [pick] is still part of [plan] as [design] shows it. */
private fun exists(pick: ScenePick, plan: ClipPlan, design: ClipDesign): Boolean = when (pick) {
    ScenePick.Opening, ScenePick.End -> true
    is ScenePick.Place -> plan.labels.any { it.key == pick.key }
    is ScenePick.Photo -> plan.photoScenes.any { it.id == pick.id }
    is ScenePick.Video -> design.videos.any { it.id == pick.id }
    is ScenePick.DayCard -> plan.dayStarts.any { it.key == pick.key }
}

/** The frame that shows [pick] best (a place's name fully in, a photo's card, a video's middle). */
private fun sceneFrame(pick: ScenePick, plan: ClipPlan, t: ClipTimeline, scenes: List<ClipScene>): Int? = when (pick) {
    is ScenePick.Video -> t.scenes.firstOrNull { it.id == pick.id }?.let { (it.from + it.to) / 2 }
    is ScenePick.DayCard -> t.scenes.firstOrNull { it.key == pick.key }?.let { (it.from + it.to) / 2 }
        ?: plan.dayStarts.firstOrNull { it.key == pick.key }?.let { c -> scenes.firstOrNull { it.key == c.opens }?.frame }
    else -> scenes.firstOrNull { it.key == pick.key }?.frame
}

/** Close, the trip's name, the shape (9:16 ▾) and Export (its progress while it works). */
@Composable
private fun ClipTopBar(
    title: String, shape: ClipShape, enabled: Boolean, exportLabel: String,
    onShape: (ClipShape) -> Unit, onClose: () -> Unit, onExport: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    StudioTopBar(title, null, onClose = onClose) {
        Box {
            Row(
                Modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(10.dp)).background(S.raised)
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Video size") { open = true }
                    .semantics { contentDescription = "Video size ${shape.label}" }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(shape.label, style = StudioText.chip, color = if (enabled) S.ink else S.muted)
                Icon(BtIcons.Down, null, Modifier.size(12.dp), tint = if (enabled) S.ink else S.muted)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = S.panel) {
                ClipShape.entries.forEach { sh ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(sh.label, style = StudioText.chip)
                                Text(shapeUse(sh), style = StudioText.sub)
                            }
                        },
                        trailingIcon = { if (sh == shape) Icon(BtIcons.Check, "Chosen", Modifier.size(18.dp), tint = S.yellow) },
                        onClick = {
                            open = false
                            onShape(sh)
                        },
                    )
                }
            }
        }
        StudioMainButton(exportLabel, onExport)
    }
}

private fun shapeUse(shape: ClipShape): String = when (shape) {
    ClipShape.Story -> "TikTok, Reels and Stories"
    ClipShape.Portrait -> "Feed post"
    ClipShape.Square -> "Square post"
    ClipShape.Wide -> "Wide, for a screen"
}

/** "0:18 / 0:42", play or pause, undo and redo. */
@Composable
private fun TimeRow(plan: ClipPlan, player: PreviewPlayer, enabled: Boolean, canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Text(clockPair(player.frame, plan.frameCount, plan.fps), style = StudioText.value, maxLines = 1)
        }
        val label = if (player.playing) "Pause" else "Play"
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (enabled) S.ink else S.raised)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { player.playing = !player.playing }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Icon(if (player.playing) BtIcons.Pause else BtIcons.Play, null, Modifier.size(18.dp), tint = S.bg) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            StudioIconButton(BtIcons.Undo, "Undo", onUndo, enabled = canUndo)
            StudioIconButton(BtIcons.Redo, "Redo", onRedo, enabled = canRedo)
        }
    }
}

/**
 * The tool bar (as in the usual phone editors): the tools for the whole clip, end to end across the screen; with a
 * block chosen, its own tools first under its name ([own]) and a close to let it go (then the bar scrolls). A
 * whole-clip tool lets the block go before it opens.
 */
@Composable
private fun ClipToolbar(
    tool: ClipTool?, own: List<ClipTool>, sceneName: String?, enabled: Boolean, onTool: (ClipTool?) -> Unit,
    onWholeClip: (ClipTool) -> Unit, onClear: () -> Unit, onSkip: (() -> Unit)? = null,
) {
    val scene = sceneName != null && own.isNotEmpty()
    StudioToolbar(spread = if (scene) 0 else ClipTool.entries.size) {
        if (scene) {
            StudioToolGroup(groupLabel("Scene", sceneName), highlight = true) {
                own.forEach { t -> StudioTool(toolIcon(t), t.label, { onTool(if (tool == t) null else t) }, enabled = enabled, selected = tool == t) }
                // Owner, 2026-11-09: "add ability to deactivate a stop from the map next to let go, and go directly to the next".
                if (onSkip != null) StudioTool(StudioIcons.EyeOff, "Skip stop", onSkip, enabled = enabled)
                StudioTool(BtIcons.Close, "Let go", onClear, enabled = enabled)
            }
        }
        StudioToolGroup(if (sceneName != null && own.isNotEmpty()) "Whole clip" else "Edit", last = true) {
            ClipTool.entries.forEach { t ->
                StudioTool(toolIcon(t), t.label, { if (sceneName != null) onWholeClip(t) else onTool(if (tool == t) null else t) }, enabled = enabled, selected = tool == t && sceneName == null)
            }
        }
    }
}

internal fun toolIcon(t: ClipTool) = when (t) {
    ClipTool.Text -> StudioIcons.Words
    ClipTool.Audio -> StudioIcons.Music
    ClipTool.Media -> StudioIcons.Picture
    ClipTool.Camera -> StudioIcons.FoldedMap
    ClipTool.Length -> StudioIcons.Timing
    ClipTool.Route -> StudioIcons.Path
    ClipTool.Look -> BtIcons.Palette
    ClipTool.Stats -> StudioIcons.Stats
}

/**
 * Export: makes the MP4 (progress and cancel), then watch it, send the video itself, keep it in the phone's gallery,
 * or save it to Drive and send its link (owner, 2026-11-09: "send full video or google drive link?"); says why it
 * can't when the clip runs past a free minute or the map is still coming in.
 */
@Composable
private fun ExportSheet(
    plan: ClipPlan, make: ClipViewModel.Make, drive: ClipViewModel.Drive, music: String?, needsPro: Boolean, canMake: Boolean,
    mapReady: Boolean, onMake: () -> Unit, onCancel: () -> Unit, onWatch: () -> Unit, onShare: () -> Unit, onDrive: () -> Unit,
    onConnect: () -> Unit, onPro: () -> Unit, phone: ClipViewModel.Phone, onPhone: () -> Unit, onDriveLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    StudioSheet("Export", onDismiss) {
        StudioNote(listOf(plan.options.shape.label, ClipText.clock(plan.frameCount, plan.fps), music ?: "No music").joinToString(" · "))
        when (make) {
            ClipViewModel.Make.Idle -> {
                if (needsPro) {
                    StudioNote("Clips over a minute are Pro. Pick a faster speed in Speed, or go Pro.", warn = true)
                    StudioButton("Go Pro", onPro, Modifier.fillMaxWidth(), icon = BtIcons.Pro)
                } else if (!mapReady) {
                    StudioNote("The map is still coming in. It can export once it's ready.")
                }
                StudioButton("Create video", onMake, Modifier.fillMaxWidth(), icon = BtIcons.Movie, primary = true, enabled = canMake)
            }
            is ClipViewModel.Make.Working -> {
                val doing = when (make.stage) {
                    ExportStage.Picture -> "Making the video"
                    ExportStage.Sound -> "Adding the sound"
                    ExportStage.Finishing -> "Finishing"
                }
                Text("$doing · ${(make.progress * 100).roundToInt()}%", style = StudioText.item)
                LinearProgressIndicator(
                    progress = { make.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = S.yellow, trackColor = S.raised,
                )
                StudioNote("You can close this; it carries on.")
                StudioButton("Cancel", onCancel, Modifier.fillMaxWidth())
            }
            is ClipViewModel.Make.Done -> {
                Text("Your video is ready", style = StudioText.item)
                if (make.musicFailed) StudioNote("Couldn't add the music, so this video is silent.", warn = true)
                StudioNote("It's also kept in the trip's Media.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioButton("Watch", onWatch, Modifier.weight(1f), icon = BtIcons.Play)
                    StudioButton("Send video", onShare, Modifier.weight(1f), icon = BtIcons.Share, primary = true)
                }
                // Straight to an app, no chooser (owner, 2026-11-10: "very important: export to TikTok"): TikTok
                // always, Instagram and WhatsApp when they are on the phone.
                val context = LocalContext.current
                val targets = remember { ClipTarget.shown(context) }
                var missing by remember(make.file) { mutableStateOf<ClipTarget?>(null) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    targets.forEach { t ->
                        StudioButton(t.label, {
                            val pkg = t.installed(context)
                            missing = if (pkg != null && runCatching { context.startActivity(ClipTarget.intent(context, make.file, pkg)) }.isSuccess) null else t
                        }, Modifier.weight(1f))
                    }
                }
                missing?.let { StudioNote("${it.label} isn't on this phone. Save the video to your phone and add it there.", warn = true) }
                when (phone) {
                    ClipViewModel.Phone.Saved -> StudioNote("Saved to your gallery, in Movies › BoringTravel.")
                    ClipViewModel.Phone.Saving -> StudioNote("Saving to your gallery…")
                    else -> {
                        StudioButton("Save to phone", onPhone, Modifier.fillMaxWidth(), icon = BtIcons.Download)
                        if (phone == ClipViewModel.Phone.Failed) StudioNote("Couldn't save it to the gallery. Try again.", warn = true)
                    }
                }
                DriveRow(drive, onDrive, onConnect, onDriveLink, plan.title)
            }
            ClipViewModel.Make.Failed -> {
                StudioNote("Couldn't make the video on this phone. Try another size or a faster speed.", warn = true)
                StudioButton("Try again", onMake, Modifier.fillMaxWidth(), primary = true, enabled = canMake)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun DriveRow(state: ClipViewModel.Drive, onSave: () -> Unit, onConnect: () -> Unit, onLink: () -> Unit, title: String) {
    val context = LocalContext.current
    when (state) {
        ClipViewModel.Drive.Idle, ClipViewModel.Drive.Failed -> {
            StudioButton("Save to Drive", onSave, Modifier.fillMaxWidth(), icon = BtIcons.Cloud)
            if (state == ClipViewModel.Drive.Failed) StudioNote("Couldn't save to Drive. Try again.", warn = true)
        }
        ClipViewModel.Drive.Saving -> StudioNote("Saving to Drive…")
        ClipViewModel.Drive.NotConnected -> {
            StudioNote("Google Drive isn't connected.")
            StudioButton("Connect Drive", onConnect, Modifier.fillMaxWidth(), icon = BtIcons.Cloud)
        }
        is ClipViewModel.Drive.Saved -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved to Drive, in BoringTravel › Exports.", Modifier.weight(1f), style = StudioText.note, maxLines = 2, overflow = TextOverflow.Ellipsis)
                StudioButton("Open", onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.url)))
                    } catch (_: ActivityNotFoundException) {
                        // No browser or Drive app; the file is still saved.
                    }
                })
            }
            if (state.file != null) {
                val link = state.link
                // The link is made on the first tap, then the share sheet opens with it.
                var asked by remember(state.file.id) { mutableStateOf(false) }
                LaunchedEffect(link, asked) {
                    if (asked && link != null) {
                        asked = false
                        runCatching { Sharing.shareLink(context, link, title) }
                    }
                }
                StudioButton(if (state.linking) "Making the link…" else "Send Drive link", {
                    asked = true
                    if (link == null) onLink()
                }, Modifier.fillMaxWidth(), icon = BtIcons.Cloud, enabled = !state.linking)
                StudioNote(
                    if (state.linkFailed) "Couldn't make the link. Try again."
                    else "Anyone with the link can watch it; the video stays in your Drive.",
                    warn = state.linkFailed,
                )
            }
        }
    }
}
