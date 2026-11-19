package com.justdataplease.boringtravel.ui.editor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.speech.Dictation
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.BtBackButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.journal.docTextStyle
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.nav.Write
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import kotlin.math.roundToInt

/** The journal editor (canvas "Write"): title, blocks, toolbar, dictation. Saves as you go. */
@Composable
fun WriteScreen(graph: AppGraph, actions: AppActions, route: Write) {
    val vm: WriteViewModel = viewModel { WriteViewModel(graph, route, createSavedStateHandle()) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val focus = LocalFocusManager.current
    val tokens = Bt.tokens
    val dictationState by vm.dictation.state.collectAsState()
    val level by vm.dictation.level.collectAsState()
    val listening = dictationState is Dictation.State.Listening
    val pageScroll = rememberScrollState()
    val photoDrag = remember(vm, pageScroll) { PhotoDragState(vm, pageScroll) }
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(photoDrag.key) {
        while (photoDrag.key != null) {
            val viewport = photoDrag.viewport
            val speed = when {
                photoDrag.pointerY < viewport.top + edge -> -((viewport.top + edge - photoDrag.pointerY) / 5f).coerceAtMost(edge / 4f)
                photoDrag.pointerY > viewport.bottom - edge -> ((photoDrag.pointerY - viewport.bottom + edge) / 5f).coerceAtMost(edge / 4f)
                else -> 0f
            }
            if (speed != 0f) { pageScroll.scrollBy(speed); photoDrag.afterScroll() }
            withFrameNanos { }
        }
    }
    DisposableEffect(photoDrag) { onDispose { photoDrag.cancel() } }

    var showDetails by rememberSaveable { mutableStateOf(false) }
    var micAsk by rememberSaveable { mutableStateOf(false) }
    var micBlocked by rememberSaveable { mutableStateOf(false) }
    var placeFor by remember { mutableStateOf<PlaceTarget?>(null) }
    var insertMenuAt by remember { mutableStateOf<Int?>(null) }
    var photosAt by rememberSaveable { mutableStateOf<Int?>(null) }
    /** The layout the picked photos and videos join (its "Add" button or a "+" spot), else they go in at [photosAt]. */
    var photosInto by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPhotos by rememberSaveable { mutableStateOf<List<String>?>(null) }
    /** The layout whose sheet is open. */
    var albumFor by remember { mutableStateOf<String?>(null) }

    // Photos and videos from one picker (owner, 2026-11-14: "where you have photos add the ability to select videos
    // too"), into a layout as well ("can we mix photos videos layout").
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
        // A picker result can arrive before Room restores the entry after process recreation.
        pendingPhotos = uris.map(Uri::toString)
    }
    fun pickPhotos(at: Int?, into: String? = null) {
        if (!vm.loaded || vm.importing) {
            vm.notice = vm.busyNotice
            return
        }
        photosAt = at
        photosInto = into
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    LaunchedEffect(vm.loaded, vm.importing, pendingPhotos) {
        val selected = pendingPhotos
        if (vm.loaded && !vm.importing && selected != null) {
            // In the order picked, so a layout shows them as they were chosen.
            val picked = selected.map(Uri::parse).map { Picked(it, video = context.contentResolver.getType(it)?.startsWith("video/") == true) }
            vm.addMedia(picked, photosAt, intoAlbum = photosInto)
            pendingPhotos = null
            photosAt = null
            photosInto = null
        }
    }
    LaunchedEffect(vm.loaded) {
        if (vm.loaded && route.attachPhotos && !vm.autoPhotoDone) {
            vm.autoPhotoDone = true
            pickPhotos(null)
        }
    }
    // A camera shot waits like picked photos do, in case the entry is still being restored when the camera returns.
    var pendingShot by rememberSaveable(stateSaver = Shot.Saver) { mutableStateOf<Shot?>(null) }
    val camera = rememberCameraShot { shot ->
        pendingShot = shot
        if (shot == null) photosAt = null
    }
    fun takePhoto(at: Int?) {
        if (!vm.loaded || vm.importing) {
            vm.notice = vm.busyNotice
            return
        }
        photosAt = at
        focus.clearFocus()
        camera.launch()
    }
    LaunchedEffect(vm.loaded, vm.importing, pendingShot) {
        val shot = pendingShot
        if (vm.loaded && !vm.importing && shot != null) {
            vm.addShot(shot, photosAt)
            pendingShot = null
            photosAt = null
        }
    }

    // A video recorded with the camera app (videos from the phone are picked with the photos); it waits like photos do.
    var pendingVideo by rememberSaveable(stateSaver = Recording.Saver) { mutableStateOf<Recording?>(null) }
    val recorder = rememberCameraVideo { recording ->
        pendingVideo = recording
        if (recording == null) photosAt = null
    }
    fun recordVideo(at: Int?) {
        if (!vm.loaded || vm.importing) {
            vm.notice = vm.busyNotice
            return
        }
        photosAt = at
        focus.clearFocus()
        recorder.launch()
    }
    LaunchedEffect(vm.loaded, vm.importing, pendingVideo) {
        val video = pendingVideo
        if (vm.loaded && !vm.importing && video != null) {
            vm.addVideo(video.uri, photosAt, after = { if (video.own) CameraShots.discard(context, video.uri) })
            pendingVideo = null
            photosAt = null
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.startDictation()
        } else if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)) {
            micBlocked = true
        }
    }
    fun toggleMic() {
        when {
            vm.dictation.isActive -> vm.stopDictation()
            vm.dictation.hasPermission() -> vm.startDictation()
            else -> micAsk = true
        }
    }

    LaunchedEffect(vm.loaded) {
        if (vm.loaded && route.dictate && !vm.autoDictateDone) {
            vm.autoDictateDone = true
            toggleMic()
        }
    }

    // A long video takes minutes to prepare: leaving asks whether to stop it (nothing of it stays) or wait.
    var stopVideoAsk by remember { mutableStateOf(false) }
    fun leave() {
        if (vm.videoProgress != null) {
            stopVideoAsk = true
            return
        }
        if (vm.finish()) actions.back()
    }
    BackHandler { leave() }
    // The screen stays on while a video is prepared, so the phone doesn't stop the app halfway.
    val view = LocalView.current
    val preparing = vm.videoProgress != null
    DisposableEffect(view, preparing) {
        view.keepScreenOn = preparing
        onDispose { view.keepScreenOn = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.stopDictation()
        vm.flush()
    }

    BtScreen {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))) {
            WriteTopBar(vm, onBack = ::leave, onDone = ::leave, onDetails = {
                focus.clearFocus()
                showDetails = true
            })
            if (!vm.loaded) {
                Spacer(Modifier.weight(1f))
                return@Column
            }
            val base = docTextStyle(vm.font)
            val firstText = vm.items.firstOrNull { it is EdItem.Text }?.key
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        val p = coordinates.positionInRoot()
                        photoDrag.viewport = Rect(p.x, p.y, p.x + coordinates.size.width, p.y + coordinates.size.height)
                    }
                    .verticalScroll(pageScroll, enabled = photoDrag.key == null)
                    .padding(horizontal = 22.dp),
            ) {
                TitleField(vm, onNext = { vm.focusKey = firstText })
                vm.photoTimeOffer?.let { t ->
                    BtChip(
                        "${vm.photoTimeLabel} · ${Dates.time(t, vm.zoneId)}",
                        false,
                        { vm.usePhotoTime() },
                        Modifier.padding(bottom = 12.dp),
                        icon = BtIcons.Time,
                    )
                }
                val onlyText = vm.items.size == 1
                /** The "+" before item [at] (or after the last one) and its menu. */
                @Composable
                fun InsertHere(at: Int) {
                    Box {
                        InsertPoint { insertMenuAt = at }
                        InsertMenu(
                            expanded = insertMenuAt == at,
                            onDismiss = { insertMenuAt = null },
                            onPick = { kind ->
                                insertMenuAt = null
                                when (kind) {
                                    Insert.Photos -> pickPhotos(at)
                                    Insert.Camera -> takePhoto(at)
                                    Insert.Record -> recordVideo(at)
                                    Insert.Place -> placeFor = PlaceTarget.InsertAt(at)
                                    else -> vm.insertAt(at, listOf(newItem(vm, kind)))
                                }
                            },
                        )
                    }
                }
                vm.items.forEachIndexed { i, item ->
                    key(item.key) {
                        if (i > 0) InsertHere(i)
                        if (photoDrag.key != null && photoDrag.beforeKey == item.key) {
                            HorizontalDivider(Modifier.padding(vertical = 3.dp), thickness = 2.dp, color = Bt.colors.route)
                        }
                        Box(Modifier.fillMaxWidth()
                            .zIndex(if (photoDrag.key == item.key) 1f else 0f)
                            .graphicsLayer { if (photoDrag.key == item.key) { translationY = photoDrag.offsetY; alpha = 0.92f } }
                            .onGloballyPositioned { coordinates ->
                                val p = coordinates.positionInRoot()
                                photoDrag.bounds[item.key] = Rect(p.x, p.y, p.x + coordinates.size.width, p.y + coordinates.size.height)
                            }) {
                        when (item) {
                            is EdItem.Text -> RichArea(vm, item.key, item.state, base, placeholder = if (onlyText && i == 0) "Start writing" else null)
                            is EdItem.Quote -> QuoteItem(vm, item, base)
                            is EdItem.Photo -> PhotoItem(vm, item, photoDrag)
                            is EdItem.Album -> AlbumItem(vm, item, onOpen = { albumFor = item.key }, onAdd = { pickPhotos(null, into = item.key) })
                            is EdItem.Check -> ChecklistItem(vm, item, base)
                            is EdItem.Place -> PlaceItem(vm, item) { placeFor = PlaceTarget.Edit(item) }
                            is EdItem.Divider -> DividerItem(vm, item)
                            is EdItem.PageBreak -> PageBreakItem(vm, item)
                            is EdItem.Kept -> KeptItem()
                        }
                        }
                    }
                }
                if (photoDrag.key != null && photoDrag.beforeKey == null) {
                    HorizontalDivider(Modifier.padding(vertical = 3.dp), thickness = 2.dp, color = Bt.colors.route)
                }
                InsertHere(vm.items.size)
                // Space under the text: a tap there continues the last text.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            vm.selectedPhotoKey = null
                            vm.focusKey = vm.items.lastOrNull { it is EdItem.Text }?.key
                        },
                )
            }
            StatusLine(vm, dictationState, level, onStop = vm::stopDictation)
            EditorToolbar(
                vm = vm,
                listening = listening,
                onCamera = { takePhoto(null) },
                onPhoto = { pickPhotos(null) },
                onVideo = { recordVideo(null) },
                onPlace = {
                    if (vm.placeName.isNotBlank()) vm.insertHere(listOf(vm.newPlace(vm.placeName))) else placeFor = PlaceTarget.Here
                },
                onMic = ::toggleMic,
            )
        }
    }

    if (showDetails) EntryDetailsSheet(vm, onDismiss = { showDetails = false })

    if (stopVideoAsk) {
        if (vm.videoProgress == null) {
            LaunchedEffect(Unit) { stopVideoAsk = false }
        } else {
            BtConfirmDialog(
                title = "Stop adding the video?",
                body = "It isn't in the note yet. Wait, or stop and leave.",
                confirm = "Stop",
                danger = true,
                dismiss = "Wait",
                onConfirm = {
                    stopVideoAsk = false
                    vm.cancelVideoThen { if (vm.finish()) actions.back() }
                },
                onDismiss = { stopVideoAsk = false },
            )
        }
    }

    albumFor?.let { key ->
        // The layout can turn into a single photo or video (one left) or go away: its sheet closes with it.
        val album = vm.items.firstOrNull { it.key == key } as? EdItem.Album
        if (album == null) {
            LaunchedEffect(key) { albumFor = null }
        } else {
            AlbumSheet(
                vm, album,
                onAdd = {
                    albumFor = null
                    pickPhotos(null, into = album.key)
                },
                onDismiss = { albumFor = null },
            )
        }
    }

    placeFor?.let { target ->
        val editing = target as? PlaceTarget.Edit
        PlaceDialog(
            initial = editing?.item?.block?.name ?: vm.placeName,
            onSave = { name ->
                when (target) {
                    is PlaceTarget.Edit -> target.item.block = target.item.block.copy(name = name)
                    is PlaceTarget.InsertAt -> vm.insertAt(target.index, listOf(vm.newPlace(name)))
                    PlaceTarget.Here -> vm.insertHere(listOf(vm.newPlace(name)))
                }
                placeFor = null
            },
            onRemove = editing?.let { e ->
                {
                    vm.remove(e.item)
                    placeFor = null
                }
            },
            onDismiss = { placeFor = null },
            known = vm.knownPlaces,
        )
    }

    if (micAsk) {
        val c = Bt.colors
        AlertDialog(
            onDismissRequest = { micAsk = false },
            containerColor = c.surface,
            text = { Text("Dictation uses the microphone only while the mic button is on.", style = Bt.text.body) },
            confirmButton = {
                TextButton(onClick = {
                    micAsk = false
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Continue", style = Bt.text.button, color = c.ink) }
            },
            dismissButton = { TextButton(onClick = { micAsk = false }) { Text("Not now", style = Bt.text.button, color = c.muted) } },
        )
    }
    if (micBlocked) {
        val c = Bt.colors
        AlertDialog(
            onDismissRequest = { micBlocked = false },
            containerColor = c.surface,
            text = { Text("The microphone is off for this app. You can turn it on in Settings.", style = Bt.text.body) },
            confirmButton = {
                TextButton(onClick = {
                    micBlocked = false
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Open Settings", style = Bt.text.button, color = c.ink) }
            },
            dismissButton = { TextButton(onClick = { micBlocked = false }) { Text("Cancel", style = Bt.text.button, color = c.muted) } },
        )
    }
}

private const val MAX_PHOTOS = 20

/** Where a place chip goes: a new one at an insert point, at the caret, or an existing one being renamed. */
private sealed interface PlaceTarget {
    data class InsertAt(val index: Int) : PlaceTarget
    data object Here : PlaceTarget
    data class Edit(val item: EdItem.Place) : PlaceTarget
}

private enum class Insert(val label: String, val icon: ImageVector) {
    Text("Text", BtIcons.Text),
    Photos("Photos and videos", BtIcons.Photo),
    Camera("Take a photo", BtIcons.Camera),
    Record("Record a video", BtIcons.Movie),
    Checklist("Checklist", BtIcons.Checklist),
    Heading("Heading", BtIcons.Title),
    Quote("Quote", BtIcons.Quote),
    Place("Place", BtIcons.Place),
    Divider("Line", BtIcons.Rule),
    PageBreak("Page break", BtIcons.Rule),
}

private fun newItem(vm: WriteViewModel, kind: Insert): EdItem = when (kind) {
    Insert.Heading -> vm.newText(heading = true)
    Insert.Quote -> vm.newQuote()
    Insert.Checklist -> vm.newChecklist()
    Insert.Divider -> EdItem.Divider()
    Insert.PageBreak -> EdItem.PageBreak()
    else -> vm.newText()
}

@Composable
private fun InsertMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (Insert) -> Unit) {
    val c = Bt.colors
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = c.surface) {
        Insert.entries.forEach { kind ->
            DropdownMenuItem(
                text = { Text(kind.label, style = Bt.text.body, color = c.ink) },
                leadingIcon = { Icon(kind.icon, null, tint = c.ink) },
                onClick = { onPick(kind) },
            )
        }
    }
}

/** Back, "SAT 19 JUN · NAXOS · 27°" (tap: date, time, place) and the round Done. */
@Composable
private fun WriteTopBar(vm: WriteViewModel, onBack: () -> Unit, onDone: () -> Unit, onDetails: () -> Unit) {
    val c = Bt.colors
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BtBackButton(onBack)
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = vm.loaded, role = Role.Button, onClickLabel = "Change date and place", onClick = onDetails)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (vm.loaded) {
                val d = vm.date
                val line = listOfNotNull(
                    "${Dates.weekday(d)} ${Dates.dayMonth(d)}",
                    vm.placeName.trim().ifBlank { null },
                    vm.tempC?.let { "${it.roundToInt()}°" },
                ).joinToString(" · ")
                // "SUN 14 MAR · EL CHALTÉN" in mono, as on the design boards; a tap changes the date and place.
                Text(
                    line.uppercase(),
                    Modifier.weight(1f, fill = false),
                    style = Bt.text.eyebrow,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(BtIcons.Expand, null, Modifier.padding(start = 2.dp).size(14.dp), tint = c.muted)
            }
        }
        BtIconButton(BtIcons.Check, "Done", onDone, filled = true)
    }
}
