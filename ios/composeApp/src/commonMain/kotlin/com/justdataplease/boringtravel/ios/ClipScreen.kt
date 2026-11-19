package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.justdataplease.boringtravel.clip.ClipBeats
import com.justdataplease.boringtravel.clip.ClipDesign
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
import com.justdataplease.boringtravel.clip.PaperVehicle3D
import com.justdataplease.boringtravel.clip.music.MusicCatalog
import com.justdataplease.boringtravel.compat.collectAsStateWithLifecycle
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.ui.clip.ClipEditing
import com.justdataplease.boringtravel.ui.clip.ClipStage
import com.justdataplease.boringtravel.ui.clip.ClipTool
import com.justdataplease.boringtravel.ui.clip.ClipTracks
import com.justdataplease.boringtravel.ui.clip.FullScreenPreview
import com.justdataplease.boringtravel.ui.clip.FullScreenVideo
import com.justdataplease.boringtravel.ui.clip.VideoSpot
import com.justdataplease.boringtravel.ui.clip.LocalClipVideos
import com.justdataplease.boringtravel.ui.clip.PreviewMusic
import com.justdataplease.boringtravel.ui.clip.PreviewPlayer
import com.justdataplease.boringtravel.ui.clip.ScenePick
import com.justdataplease.boringtravel.ui.clip.ToolPanel
import com.justdataplease.boringtravel.ui.clip.TrackModel
import com.justdataplease.boringtravel.ui.clip.TrackScale
import com.justdataplease.boringtravel.ui.clip.clockPair
import com.justdataplease.boringtravel.ui.clip.rememberEditorMedia
import com.justdataplease.boringtravel.ui.clip.rememberPreviewCache
import com.justdataplease.boringtravel.ui.icons.BtIcons
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

/** Where the video's map pictures come from (iPhone: MapLibre through Swift; elsewhere the countries drawn). */
val LocalMapSnapshots = staticCompositionLocalOf<MapSnapshots> { DrawnSnapshots() }

/** The trip's Media, where every finished clip is kept (one for the app; none in previews that don't keep clips). */
val LocalSavedClips = staticCompositionLocalOf<com.justdataplease.boringtravel.data.SavedClips?> { null }

/**
 * The trip video editor (the Android app's ui/clip/ClipScreen.kt, written again over [ClipStudio]): the dark studio
 * whatever the theme, the preview on top, the time row with play, undo and redo, the clip's tracks under it (scenes,
 * photos, texts, music, voice) scrolling under a fixed playhead, and the tools at the bottom, each opening a panel for
 * the whole clip or the chosen scene (Android's own panels, read in: Text, Audio, Media, Camera, Length, Route, Look,
 * Stats). Export opens Android's Export sheet: Create video, its progress (also over the preview), then Watch, Send
 * video, the apps it goes to straight, Save to phone and Drive (round 20). While it works every control is off, and
 * the app leaving the screen pauses it (an iPhone's encoder works only on screen): it goes on when the app is back.
 *
 * [revision]: the trips' revision (a leg's travel changed on the plan re-plans an open clip). [studioAtStart] and the
 * other *AtStart arguments set the screen up for the tests and the screenshots ([toolAtStart]: a tool's name,
 * [chosenAtStart]: a scene's key, as the screen keeps them; [exportAtStart]: the Export sheet open).
 */
@Composable
fun ClipScreen(
    book: TripBook,
    tripId: String,
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenPlan: () -> Unit,
    onSay: (String) -> Unit,
    onChanged: () -> Unit = {},
    studioAtStart: ClipStudio? = null,
    frameAtStart: Int? = null,
    sizeMenuAtStart: Boolean = false,
    fullScreenAtStart: Boolean = false,
    toolAtStart: String? = null,
    chosenAtStart: String? = null,
    exportAtStart: Boolean = false,
    revision: Int = 0,
    onConnectDrive: () -> Unit = {},
    watchingAtStart: Boolean = false,
) {
    val files = LocalMediaKit.current.files
    val web = LocalWeb.current
    val snapshots = LocalMapSnapshots.current
    val device = LocalClipDevice.current
    val background = LocalBackgroundWork.current
    val saved = LocalSavedClips.current
    val google = com.justdataplease.boringtravel.google.LocalGoogle.current
    val scope = rememberCoroutineScope()
    val studio = studioAtStart ?: remember(book, tripId) {
        settings.settleClipPrefs()
        val kit = ClipKit(
            settings.prefs, files, web, snapshots, device, background = background, saved = saved, defaults = TravellerDefaults.of(settings),
            drive = google?.drive,
        )
        ClipStudio(book, tripId, kit, scope, settings.traveller, settings.rides, onChanged)
    }
    DisposableEffect(studio) { onDispose { if (studioAtStart == null) studio.close() } }
    // Me › Your traveller & rides changed (or a video's made the default): the clip follows it.
    val traveller = settings.traveller
    val rides = settings.rides
    LaunchedEffect(studio, traveller) { studio.useDefaultTraveller(traveller) }
    LaunchedEffect(studio, rides) { studio.useDefaultRides(rides) }
    // The trip changed elsewhere (a leg's travel on the plan): the clip takes its travel again.
    val seen = remember { intArrayOf(revision) }
    LaunchedEffect(studio, revision) {
        if (revision != seen[0]) {
            seen[0] = revision
            studio.tripChanged()
        }
    }
    // An export pauses while the app is away and goes on when it is back (an iPhone's video encoder works only on
    // screen): Compose's lifecycle says so (paused when the app resigns active, resumed when it is active again).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(studio, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> studio.setOnScreen(false)
                Lifecycle.Event.ON_RESUME -> studio.setOnScreen(true)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val tokens = Bt.tokens
    LaunchedEffect(tokens, studio) {
        studio.useStyle(ClipStyle.of(settings.prefs, tokens))
        studio.usePalette(PaperVehicle3D.Palette.of(tokens))
    }
    // The open tool and the chosen scene outlive a route change (the editor reloads under them).
    var tool by rememberSaveable { mutableStateOf(toolAtStart) }
    var chosen by rememberSaveable { mutableStateOf(chosenAtStart) }
    val load by studio.load.collectAsStateWithLifecycle()
    StudioScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            when (load) {
                ClipStudio.Load.Loading -> {
                    StudioTopBar("Trip video", null, onClose = onBack)
                    Message(null, "Opening your trip", busy = true)
                }
                ClipStudio.Load.NoRoute -> {
                    StudioTopBar("Trip video", null, onClose = onBack)
                    Message(
                        "No route yet",
                        "Add locations to at least two planned places, record a journey, or save two stops to make a trip film.",
                    ) { StudioButton("Open trip plan", onClick = onOpenPlan, primary = true) }
                }
                ClipStudio.Load.Failed -> {
                    StudioTopBar("Trip video", null, onClose = onBack)
                    Message("Couldn't open this journey", "Your trip is saved. Try opening the route again.") {
                        StudioButton("Try again", onClick = studio::retryLoad, primary = true)
                    }
                }
                ClipStudio.Load.Ready -> {
                    // The phone videos' pictures for every preview below (LocalClipVideos).
                    val videoFrames by studio.videoFrames.collectAsStateWithLifecycle()
                    CompositionLocalProvider(LocalClipVideos provides videoFrames) {
                        ClipEditor(
                            studio, onBack, onSay,
                            tool = ClipTool.entries.firstOrNull { it.name == tool }, onTool = { tool = it?.name },
                            chosen = chosen, onChoose = { chosen = it },
                            frameAtStart = frameAtStart, sizeMenuAtStart = sizeMenuAtStart, fullScreenAtStart = fullScreenAtStart,
                            exportAtStart = exportAtStart, onConnectDrive = onConnectDrive, watchingAtStart = watchingAtStart,
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
 * is the chosen scene's key ([ScenePick.key]); with none, "This scene" means the scene under the playhead. Back (the
 * swipe from the left edge) closes a panel, then lets the scene go, as Android's; so do the panel's Done and Let go.
 */
@Composable
private fun ClipEditor(
    studio: ClipStudio, onBack: () -> Unit, onSay: (String) -> Unit, tool: ClipTool?, onTool: (ClipTool?) -> Unit,
    chosen: String?, onChoose: (String?) -> Unit, frameAtStart: Int?, sizeMenuAtStart: Boolean, fullScreenAtStart: Boolean,
    exportAtStart: Boolean, onConnectDrive: () -> Unit, watchingAtStart: Boolean = false,
) {
    val options by studio.options.collectAsStateWithLifecycle()
    val replanning by studio.replanning.collectAsStateWithLifecycle()
    val plan by studio.plan.collectAsStateWithLifecycle()
    val base by studio.base.collectAsStateWithLifecycle()
    val mapStatus by studio.mapStatus.collectAsStateWithLifecycle()
    val mapProgress by studio.mapProgress.collectAsStateWithLifecycle()
    val photos by studio.photos.collectAsStateWithLifecycle()
    val style by studio.style.collectAsStateWithLifecycle()
    val design by studio.design.collectAsStateWithLifecycle()
    val history by studio.history.collectAsStateWithLifecycle()
    val timeline by studio.timeline.collectAsStateWithLifecycle()
    val voice by studio.voice.collectAsStateWithLifecycle()
    val make by studio.make.collectAsStateWithLifecycle()
    val drive by studio.drive.collectAsStateWithLifecycle()
    val phone by studio.phone.collectAsStateWithLifecycle()
    val working = make is ClipStudio.Make.Working
    val done = make as? ClipStudio.Make.Done
    val mapReady = base.hasMap
    // While the map's pictures still come in, playing waits for them (a close-up is never shown soft first).
    val mapLoading = mapProgress.takeIf { mapStatus == ClipStudio.MapStatus.Loading }
    val player = remember { PreviewPlayer() }
    var fullScreen by rememberSaveable { mutableStateOf(fullScreenAtStart) }
    var exporting by rememberSaveable { mutableStateOf(exportAtStart) }
    var watching by rememberSaveable { mutableStateOf(watchingAtStart) }
    var scrubbing by remember { mutableStateOf(false) }
    // The first plan opens at [frameAtStart] (tests, screenshots); later ones keep the playhead.
    val startAt = remember { arrayOf(frameAtStart) }
    val p = plan
    val s = style
    val t = timeline
    if (p == null || t == null) {
        StudioTopBar("Trip video", null, onClose = onBack)
        Message(null, "Opening your trip", busy = true)
        return
    }
    // The preview prepared ahead once the detailed map is in (shared by the inline and the full-screen preview).
    val cache = if (s != null && mapReady) {
        rememberPreviewCache(p, base, s, photos, studio.noMark, design, settled = mapStatus != ClipStudio.MapStatus.Loading && !replanning && !working, playhead = { player.frame })
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
        val wanted = pick?.let { sceneFrame(it, p, t, scenes) } ?: startAt[0]
        startAt[0] = null
        player.frame = (wanted ?: player.frame).coerceIn(0, (p.frameCount - 1).coerceAtLeast(0))
    }
    fun choose(next: ScenePick?) {
        onChoose(next?.key)
        if (next != null) {
            player.playing = false
            sceneFrame(next, p, t, scenes)?.let { player.frame = it }
        }
    }
    // The system's Back (iPhone: the swipe from the left edge), as Android's ClipScreen's BackHandler: it closes the open
    // tool's panel, then lets the chosen scene go; with neither, the app's Back leaves the studio.
    BackHandler(tool != null || pick != null) {
        if (tool != null) onTool(null) else onChoose(null)
    }
    // An export or its sheet closes the open tool (its changes would wait); export starts from the button.
    LaunchedEffect(working) { if (working) onTool(null) }

    val needsPro = !studio.isPro && ClipTiming.needsPro(p.seconds)
    val canMake = s != null && mapReady && !replanning && !needsPro
    val editing = remember(studio, p, design, options, t, scenes, working, opensDay) {
        ClipEditing(studio, p, design, options, t, scenes, studio.isPro, enabled = !working, opensDay = opensDay)
    }
    val media = rememberEditorMedia(studio)
    // The place "+ Record" goes with: the chosen scene's, else the one under the playhead.
    val placeHere = editing.placeOf(target) ?: (atPlayhead as? ScenePick.Place)?.key

    // The music under the preview while it plays (not while exporting).
    PreviewMusic(player, p.fps, MusicCatalog.byId(options.music), studio.musicStartS, active = !working)

    // The time row and the tools step aside while the keyboard is up (a name or a note being typed).
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // The full-screen preview covers the editor (Android: a dialog over it).
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().imePadding()) {
        ClipTopBar(
            title = p.title, shape = options.shape, enabled = !working,
            exportLabel = (make as? ClipStudio.Make.Working)?.let { "${(it.progress * 100).roundToInt()}%" } ?: "Export",
            menuAtStart = sizeMenuAtStart,
            onShape = studio::setShape, onClose = onBack,
            onExport = {
                exporting = true
                if (make == ClipStudio.Make.Idle && canMake) studio.makeClip()
            },
        )
        val panel = tool != null
        ClipStage(
            plan = p, base = base, style = s, photos = photos, isPro = studio.noMark, design = design, cache = cache, player = player,
            mapStatus = mapStatus, mapLoading = mapLoading, mapReady = mapReady,
            working = working, progress = (make as? ClipStudio.Make.Working)?.progress,
            movable = pick?.takeIf { it is ScenePick.Place || it is ScenePick.Photo }?.key,
            still = !scrubbing && !fullScreen,
            active = !fullScreen,
            onDrop = { key, o -> studio.editScenes { it.with(key, o) } },
            onFullScreen = { fullScreen = true },
            onRetryMap = studio::retryMap,
            modifier = Modifier.weight(if (panel) 0.9f else 1f).fillMaxWidth(),
        )
        if (!keyboard) {
            TimeRow(
                p, player, enabled = !working, canUndo = history.canUndo && !working, canRedo = history.canRedo && !working,
                onUndo = { studio.undo() }, onRedo = { studio.redo() },
            )
        }
        if (tool != null) {
            Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                ToolPanel(
                    tool, editing, target, chosen = pick != null, placeHere = placeHere, media = media, musicFailed = done?.musicFailed == true,
                    onChoose = ::choose, onDone = { onTool(null) }, onPro = {},
                )
            }
        } else {
            val model = remember(p, design, t, options.music) { TrackModel.of(p, design, t, trackBeats(t, options.music)) }
            val recording = voice as? ClipStudio.Voice.Recording
            val recordAt = placeHere?.takeIf { k -> design.voice(k) == null }?.let { p.sceneStart(it) }
            ClipTracks(
                model = model, player = player, cache = cache, chosen = pick?.key,
                initialDpPerSecond = remember(t.fps) { TrackScale.fit(t).dpPerSecond },
                music = MusicCatalog.byId(options.music)?.name,
                recordAt = if (recording == null) recordAt else null,
                recording = recording?.let { r -> p.sceneStart(r.sceneKey)?.let { it to r.startedAt } },
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
            if (!keyboard) {
                // A planned place can be left out (Skip stop, beside Let go); undo or Route › Left out bring it back.
                val skipIds = (pick as? ScenePick.Place)?.let { pl -> p.labels.firstOrNull { it.key == pl.key }?.stops }?.takeIf { it.isNotEmpty() }
                val canSkip = skipIds != null && p.labels.sumOf { it.stops.size } - skipIds.size >= 2
                ClipToolbar(
                    tool = null, own = ClipTool.forPick(pick, opensDay), sceneName = pick?.let { editing.name(it) }, enabled = !working,
                    onTool = onTool, onWholeClip = { w -> onChoose(null); onTool(w) }, onClear = { onChoose(null) },
                    onSkip = if (canSkip) ({
                        onChoose(null)
                        studio.skipStops(skipIds, true)
                    }) else null,
                )
            }
            Box(Modifier.navigationBarsPadding())
        }
    }

    if (fullScreen) {
        // Android shows it as a dialog, which the system's Back closes: so does the swipe from the left edge here.
        BackHandler { fullScreen = false }
        if (s != null && mapReady) {
            FullScreenPreview(p, base, s, photos, studio.noMark, design, cache, player, paused = working, mapLoading = mapLoading, onClose = { fullScreen = false })
        } else {
            LaunchedEffect(Unit) { fullScreen = false }
        }
    }
    }

    if (exporting) {
        val share = LocalClipDevice.current.share
        ExportSheet(
            plan = p, make = make, drive = drive, music = MusicCatalog.byId(options.music)?.name, needsPro = needsPro,
            canMake = canMake, mapReady = mapReady,
            onMake = studio::makeClip, onCancel = studio::cancelClip,
            // The finished video full screen, Android's own player (FullScreenVideo: Play/Pause, a bar to scrub, the time).
            onWatch = { watching = true },
            onShare = { done?.let { share.share(it.file) } },
            onDrive = studio::saveToDrive, onConnect = onConnectDrive, onPro = {},
            phone = phone, onPhone = studio::saveToPhone, onDriveLink = studio::shareDriveLink,
            targets = remember(share) { ClipTarget.shown(share::installed) },
            onTarget = { target -> done != null && share.sendTo(target, done.file) },
            onDismiss = { exporting = false },
        )
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

/**
 * Export (the Android app's ExportSheet): makes the MP4 (progress and Cancel), then Watch, Send video, the apps it goes
 * to straight ([targets]; [onTarget] false: that app isn't on the phone), Save to phone, or Drive; says why it can't
 * when the clip runs past a free minute or the map is still coming in. Android's words throughout.
 */
@Composable
private fun ExportSheet(
    plan: ClipPlan, make: ClipStudio.Make, drive: ClipStudio.Drive, music: String?, needsPro: Boolean, canMake: Boolean,
    mapReady: Boolean, onMake: () -> Unit, onCancel: () -> Unit, onWatch: () -> Unit, onShare: () -> Unit, onDrive: () -> Unit,
    onConnect: () -> Unit, onPro: () -> Unit, phone: ClipStudio.Phone, onPhone: () -> Unit, onDriveLink: () -> Unit,
    targets: List<ClipTarget>, onTarget: (ClipTarget) -> Boolean, onDismiss: () -> Unit,
) {
    StudioSheet("Export", onDismiss) {
        StudioNote(listOf(plan.options.shape.label, ClipText.clock(plan.frameCount, plan.fps), music ?: "No music").joinToString(" · "))
        when (make) {
            ClipStudio.Make.Idle -> {
                if (needsPro) {
                    StudioNote("Clips over a minute are Pro. Pick a faster speed in Speed, or go Pro.", warn = true)
                    StudioButton("Go Pro", onPro, Modifier.fillMaxWidth(), icon = BtIcons.Pro)
                } else if (!mapReady) {
                    StudioNote("The map is still coming in. It can export once it's ready.")
                }
                StudioButton("Create video", onMake, Modifier.fillMaxWidth(), icon = BtIcons.Movie, primary = true, enabled = canMake)
            }
            is ClipStudio.Make.Working -> {
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
            is ClipStudio.Make.Done -> {
                Text("Your video is ready", style = StudioText.item)
                if (make.musicFailed) StudioNote("Couldn't add the music, so this video is silent.", warn = true)
                StudioNote("It's also kept in the trip's Media.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioButton("Watch", onWatch, Modifier.weight(1f), icon = BtIcons.Play)
                    StudioButton("Send video", onShare, Modifier.weight(1f), icon = BtIcons.Share, primary = true)
                }
                // Straight to an app: TikTok always, Instagram and WhatsApp when they are on the phone.
                var missing by remember(make.file) { mutableStateOf<ClipTarget?>(null) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    targets.forEach { t ->
                        StudioButton(t.label, { missing = if (onTarget(t)) null else t }, Modifier.weight(1f))
                    }
                }
                missing?.let { StudioNote(ClipTarget.missing(it), warn = true) }
                when (phone) {
                    // Android: "Saved to your gallery, in Movies › BoringTravel."; an iPhone keeps it in Photos (add-only access
                    // makes no album), as Share your world says.
                    ClipStudio.Phone.Saved -> StudioNote(SAVED_TO_PHOTOS)
                    ClipStudio.Phone.Saving -> StudioNote("Saving to your gallery…")
                    else -> {
                        StudioButton("Save to phone", onPhone, Modifier.fillMaxWidth(), icon = BtIcons.Download)
                        if (phone == ClipStudio.Phone.Failed) StudioNote("Couldn't save it to the gallery. Try again.", warn = true)
                    }
                }
                DriveRow(drive, onDrive, onConnect, onDriveLink, plan.title)
            }
            ClipStudio.Make.Failed -> {
                StudioNote("Couldn't make the video on this phone. Try another size or a faster speed.", warn = true)
                StudioButton("Try again", onMake, Modifier.fillMaxWidth(), primary = true, enabled = canMake)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Google Drive (the Android app's DriveRow): Save to Drive, "Saving to Drive…", "Google Drive isn't connected." with
 * Connect Drive, then "Saved to Drive, in BoringTravel › Exports." with Open, and Send Drive link (the link is made on
 * the first tap, then the share sheet opens with it).
 */
@Composable
private fun DriveRow(state: ClipStudio.Drive, onSave: () -> Unit, onConnect: () -> Unit, onLink: () -> Unit, title: String = "") {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val phone = LocalPhone.current
    when (state) {
        ClipStudio.Drive.Idle, ClipStudio.Drive.Failed -> {
            StudioButton("Save to Drive", onSave, Modifier.fillMaxWidth(), icon = BtIcons.Cloud)
            if (state == ClipStudio.Drive.Failed) StudioNote("Couldn't save to Drive. Try again.", warn = true)
        }
        ClipStudio.Drive.Saving -> StudioNote("Saving to Drive…")
        ClipStudio.Drive.NotConnected -> {
            StudioNote("Google Drive isn't connected.")
            StudioButton("Connect Drive", onConnect, Modifier.fillMaxWidth(), icon = BtIcons.Cloud)
        }
        is ClipStudio.Drive.Saved -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved to Drive, in BoringTravel › Exports.", Modifier.weight(1f), style = StudioText.note, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // No browser or Drive app: the file is still saved.
                StudioButton("Open", onClick = { runCatching { uri.openUri(state.url) } })
            }
            if (state.file != null) {
                val link = state.link
                // The link is made on the first tap, then the share sheet opens with it.
                var asked by remember(state.file.id) { mutableStateOf(false) }
                LaunchedEffect(link, asked) {
                    if (asked && link != null) {
                        asked = false
                        phone.share(title, link)
                    }
                }
                StudioButton(if (state.linking) "Making the link…" else "Send Drive link", {
                    asked = true
                    if (link == null) onLink()
                }, Modifier.fillMaxWidth(), icon = BtIcons.Cloud, enabled = !state.linking)
                StudioNote(
                    if (state.linkFailed) "Couldn't make the link. Try again." else "Anyone with the link can watch it; the video stays in your Drive.",
                    warn = state.linkFailed,
                )
            }
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

/** Close, the trip's name, the shape (9:16 ▾) and Export. */
@Composable
private fun ClipTopBar(
    title: String, shape: ClipShape, enabled: Boolean, exportLabel: String, menuAtStart: Boolean,
    onShape: (ClipShape) -> Unit, onClose: () -> Unit, onExport: () -> Unit,
) {
    var open by remember { mutableStateOf(menuAtStart) }
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

internal fun shapeUse(shape: ClipShape): String = when (shape) {
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
