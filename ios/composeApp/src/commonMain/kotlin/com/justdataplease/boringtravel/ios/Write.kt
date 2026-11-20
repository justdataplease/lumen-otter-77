package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.components.BtBackButton
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the editor has written is saved again once typing pauses this long (the Android editor's AUTOSAVE_MS). */
private const val NOTE_AUTOSAVE_MS = 800L

/**
 * The journal editor, as the Android app's Write screen (ui/editor/WriteScreen.kt): back, the day and place on top (a tap
 * changes them), the round Done; the title in the display face; the note's blocks (rich text with lists and headings,
 * quotes, photos and their layouts, checklists, places, lines, page breaks) with a "+" between each two to insert
 * there; and the toolbar docked over the keyboard (fonts, text style, lists, camera, photos and videos, place).
 *
 * It writes note [entryId], or a new note in trip [tripId] (null: outside trips) on [today]. What is written is saved
 * once typing pauses, after each photo goes in, and when the screen is left; a new note is made by the first save with
 * something in it, and dropped when it is left empty. [fromCamera]: note [entryId] was just made from a camera shot
 * ([PhotoEntries]): the caret goes under the photo and its place is found, once (Android's fromCamera).
 */
@Composable
fun WriteScreen(
    book: TripBook,
    tripId: String?,
    entryId: String?,
    today: LocalDate,
    onDone: () -> Unit,
    detailsAtStart: Boolean = false,
    selectedAtStart: Int? = null,
    albumAtStart: Int? = null,
    menuAtStart: WriteMenu? = null,
    fromCamera: Boolean = false,
    placeTypedAtStart: String? = null,
    dictate: Boolean = false,
    dictationLanguage: String? = null,
    pickAtStart: Boolean = false,
    stopVideoAskAtStart: Boolean = false,
    onNoteId: (String) -> Unit = {},
    planItemId: String? = null,
    /** How new photos sit in a note of a trip: its Edit trip › Photos in notes (the full column, rounded, by default). */
    photoDefaults: (tripId: String?) -> PhotoDefaults = { PhotoDefaults.Page },
) {
    val kit = LocalMediaKit.current
    val toast = LocalToast.current
    val google = com.justdataplease.boringtravel.google.LocalGoogle.current
    val together = LocalTogether.current
    val model = remember(book, entryId) {
        // A note's media go up to Drive with a Drive backup, or in a shared trip (Android's goesToDrive, round 26).
        WriteModel(book, kit, tripId, entryId, noteTime(today), planItemId, photoDefaults, goesToDrive = { google?.backsUpMedia == true || together?.isShared(tripId) == true })
    }
    // The note's id, for the app to reopen it after the phone closed the app (Android's WriteViewModel KEY_ID).
    LaunchedEffect(model) { onNoteId(model.id) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val pageScroll = rememberScrollState()
    val photoDrag = remember(model, pageScroll) { PhotoDragState(model, pageScroll) }
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(photoDrag.key) {
        while (photoDrag.key != null) {
            val viewport = photoDrag.viewport
            val speed = when {
                photoDrag.pointerY < viewport.top + edge -> -((viewport.top + edge - photoDrag.pointerY) / 5f).coerceAtMost(edge / 4f)
                photoDrag.pointerY > viewport.bottom - edge -> ((photoDrag.pointerY - viewport.bottom + edge) / 5f).coerceAtMost(edge / 4f)
                else -> 0f
            }
            if (speed != 0f) {
                pageScroll.scrollBy(speed)
                photoDrag.afterScroll()
            }
            withFrameNanos { }
        }
    }
    DisposableEffect(photoDrag) { onDispose { photoDrag.cancel() } }

    var showDetails by rememberSaveable { mutableStateOf(detailsAtStart) }
    var placeFor by remember { mutableStateOf<PlaceTarget?>(null) }
    var insertMenuAt by remember { mutableStateOf(if (menuAtStart == WriteMenu.Insert) 1 else null) }
    var albumFor by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<Media?>(null) }
    val rows = LocalMediaRows.current
    LaunchedEffect(model) {
        // Previews and tests: a photo, a layout or the text's tools open at the start.
        selectedAtStart?.let { i -> model.selectedPhotoKey = model.items.filterIsInstance<EdItem.Photo>().getOrNull(i)?.key }
        albumAtStart?.let { i -> albumFor = model.items.filterIsInstance<EdItem.Album>().getOrNull(i)?.key }
        if (menuAtStart == WriteMenu.Style || menuAtStart == WriteMenu.Lists) model.activeKey = model.items.firstOrNull { it is EdItem.Text }?.key
    }

    // Saves once typing pauses; leaving saves the rest.
    LaunchedEffect(model) {
        snapshotFlow { model.contentStamp() }.drop(1).collectLatest {
            delay(NOTE_AUTOSAVE_MS)
            if (!model.finished) model.persist()
        }
    }
    DisposableEffect(model) { onDispose { if (!model.finished) model.persist() } }

    fun busy(): Boolean {
        if (!model.importing) return false
        model.notice = model.busyNotice
        return true
    }

    // A pick, a shot or a recording that comes back while photos or a video still go in waits, in the order they came,
    // and goes in once they are in (Android's pendingPhotos, pendingShot and pendingVideo). Android's also wait for a
    // note the phone is still reading back after it closed the app; here the note is read when the screen is made, and
    // a picker's or the camera's result never outlives the app (iOS ends them with it), so there is nothing to wait for.
    val pending = remember(model) { mutableStateListOf<PendingMedia>() }
    fun add(picked: List<PickedMedia>, at: Int?, into: String?) {
        if (picked.isEmpty()) return
        if (model.importing) pending += PendingMedia(picked, at, into) else model.addMedia(scope, picked, at, into)
    }
    LaunchedEffect(model, model.importing, pending.size) {
        if (model.importing || model.finished) return@LaunchedEffect
        val next = pending.firstOrNull() ?: return@LaunchedEffect
        pending.removeAt(0)
        model.addMedia(scope, next.picked, next.at, next.into)
    }
    // What still waited when the screen was left: its copies go, as with every pick never brought in.
    DisposableEffect(model) { onDispose { pending.forEach { p -> p.picked.forEach { kit.files.delete(it.path) } } } }

    // A new note's place: the last route point when it is recent, named by the phone's geocoder; never asks.
    val tracking = LocalTracking.current
    val web = LocalWeb.current
    LaunchedEffect(model) {
        model.fillPlace(tracking?.latestPoint(), com.justdataplease.boringtravel.time.nowMs()) { lat, lng ->
            web.location.address(lat, lng, 8_000)?.let(GeoNames::noteLabel)
        }
    }

    // A note just made from a camera shot ([fromCamera], Android's KEY_CAMERA): once, the caret under the photo, ready for a
    // line, and the place found: the photo's own point, else where the phone is (only when location is allowed already:
    // nothing is asked), else the last route point, named by the phone's geocoder.
    var cameraDone by rememberSaveable(model) { mutableStateOf(false) }
    LaunchedEffect(model, fromCamera) {
        if (!fromCamera || cameraDone) return@LaunchedEffect
        cameraDone = true
        model.focusKey = model.items.lastOrNull { it is EdItem.Text }?.key
        model.placeShot(
            here = { web.location.takeIf { it.allowed() }?.current()?.let { it.lat to it.lng } },
            point = tracking?.latestPoint(), now = com.justdataplease.boringtravel.time.nowMs(),
        ) { lat, lng -> web.location.address(lat, lng, 8_000)?.let(GeoNames::noteLabel) }
    }

    /** Photos and videos from one picker (the Android editor's), into a layout too ([into], its key). */
    fun pickPhotos(at: Int?, into: String? = null) {
        if (busy()) return
        kit.picker.pickMedia(MediaLimits.PICKED) { add(it, at, into) }
    }

    // The Media tab's Add media ([pickAtStart]): the new note opens with the photo library, once.
    var pickedAtStart by rememberSaveable(model) { mutableStateOf(false) }
    LaunchedEffect(model, pickAtStart) {
        if (pickAtStart && !pickedAtStart) {
            pickedAtStart = true
            pickPhotos(null)
        }
    }

    fun takePhoto(at: Int?) {
        if (busy()) return
        focus.clearFocus()
        if (!kit.picker.hasCamera) {
            toast("No camera app on this phone.", false)
            return
        }
        kit.picker.takePhoto { shot -> if (shot != null) add(listOf(shot), at, null) }
    }

    fun recordVideo(at: Int?) {
        if (busy()) return
        focus.clearFocus()
        if (!kit.picker.hasCamera) {
            toast("No camera app on this phone.", false)
            return
        }
        kit.picker.recordVideo { video -> if (video != null) add(listOf(video), at, null) }
    }

    // Dictation (the Android editor's): the mic starts and stops it; the first time, a word about the microphone first,
    // then the phone's own questions; refused for good, a way to Settings. It stops when the app goes to the background
    // and when the editor is left, and nothing heard after that is written.
    val speech = LocalSpeech.current
    val dictation = remember(model) { Dictation(speech, scope) }
    DisposableEffect(dictation) {
        dictation.onText = model::onDictated
        onDispose { dictation.release() }
    }
    var micAsk by rememberSaveable { mutableStateOf(false) }
    var micBlocked by rememberSaveable { mutableStateOf(false) }
    fun startDictation() {
        model.prepareDictation()
        dictation.start(dictationLanguage ?: speech.phoneLanguage())
    }
    fun toggleMic() {
        when {
            dictation.isActive -> dictation.stop()
            dictation.access() == MicAccess.Allowed -> startDictation()
            dictation.access() == MicAccess.Refused -> micBlocked = true
            else -> micAsk = true
        }
    }
    // Opened by the Now card's mic: dictation starts by itself, once.
    var autoDictateDone by rememberSaveable(model) { mutableStateOf(false) }
    LaunchedEffect(model, dictate) {
        if (dictate && !autoDictateDone) {
            autoDictateDone = true
            toggleMic()
        }
    }
    // Leaving the screen (the home screen, another app): dictation stops and the note is saved now, as Android's
    // WriteScreen does on ON_STOP (stopDictation, flush), so nothing typed is lost if the phone then closes the app.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        dictation.stop()
        if (!model.finished) model.persist()
    }

    // A long video takes minutes to prepare: leaving asks whether to stop it (nothing of it stays) or wait (Android's).
    var stopVideoAsk by remember { mutableStateOf(stopVideoAskAtStart) }
    fun leave() {
        if (model.videoProgress != null) {
            stopVideoAsk = true
            return
        }
        if (model.finish()) {
            dictation.stop()
            onDone()
        }
    }
    // The system's Back (iPhone: the swipe from the left edge) leaves as the top bar's Back does: saved, or an empty
    // new note dropped (Android's BackHandler { leave() }).
    BackHandler { leave() }
    // The screen stays on while a video is prepared, so the phone doesn't stop the app halfway (Android's keepScreenOn).
    KeepScreenOn(model.videoProgress != null)

    BtScreen {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
            WriteTopBar(model, onBack = ::leave, onDone = ::leave, onDetails = {
                focus.clearFocus()
                showDetails = true
            })
            val base = docTextStyle(model.font)
            val firstText = model.items.firstOrNull { it is EdItem.Text }?.key
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
                TitleField(model, onNext = { model.focusKey = firstText })
                model.photoTimeOffer?.let { t ->
                    BtChip("${model.photoTimeLabel} · ${clockAt(t, model.zoneId).orEmpty()}", false, { model.usePhotoTime() }, Modifier.padding(bottom = 12.dp), icon = BtIcons.Time)
                }
                val onlyText = model.items.size == 1

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
                                    else -> model.insertAt(at, listOf(newItem(model, kind)))
                                }
                            },
                        )
                    }
                }
                model.items.forEachIndexed { i, item ->
                    key(item.key) {
                        if (i > 0) InsertHere(i)
                        if (photoDrag.key != null && photoDrag.beforeKey == item.key) {
                            HorizontalDivider(Modifier.padding(vertical = 3.dp), thickness = 2.dp, color = Bt.colors.route)
                        }
                        Box(
                            Modifier.fillMaxWidth()
                                .zIndex(if (photoDrag.key == item.key) 1f else 0f)
                                .graphicsLayer {
                                    if (photoDrag.key == item.key) {
                                        translationY = photoDrag.offsetY
                                        alpha = 0.92f
                                    }
                                }
                                .onGloballyPositioned { coordinates ->
                                    val p = coordinates.positionInRoot()
                                    photoDrag.bounds[item.key] = Rect(p.x, p.y, p.x + coordinates.size.width, p.y + coordinates.size.height)
                                },
                        ) {
                            when (item) {
                                is EdItem.Text -> RichArea(model, item.key, item.state, base, placeholder = if (onlyText && i == 0) "Start writing" else null)
                                is EdItem.Quote -> QuoteItem(model, item, base)
                                is EdItem.Photo -> PhotoItem(model, item, photoDrag, onPlay = { playing = rows(it) })
                                is EdItem.Album -> AlbumItem(model, item, onOpen = { albumFor = item.key }, onAdd = { pickPhotos(null, into = item.key) })
                                is EdItem.Check -> ChecklistItem(model, item, base)
                                is EdItem.Place -> PlaceItem(model, item) { placeFor = PlaceTarget.Edit(item) }
                                is EdItem.Divider -> DividerItem(model, item)
                                is EdItem.PageBreak -> PageBreakItem(model, item)
                                is EdItem.Kept -> KeptItem()
                            }
                        }
                    }
                }
                if (photoDrag.key != null && photoDrag.beforeKey == null) {
                    HorizontalDivider(Modifier.padding(vertical = 3.dp), thickness = 2.dp, color = Bt.colors.route)
                }
                InsertHere(model.items.size)
                // Space under the text: a tap there goes on with the last text.
                Box(
                    Modifier.fillMaxWidth().height(160.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            model.selectedPhotoKey = null
                            model.focusKey = model.items.lastOrNull { it is EdItem.Text }?.key
                        },
                )
            }
            StatusLine(model, dictation.state, dictation.level, onStop = dictation::stop, onDismissError = dictation::dismissError)
            val rich = when (val active = model.items.firstOrNull { it.key == model.activeKey }) {
                is EdItem.Text -> active.state
                is EdItem.Quote -> active.state
                else -> null
            }
            EditorToolbar(
                model, rich,
                menuAtStart = menuAtStart,
                onCamera = { takePhoto(null) },
                onPhoto = { pickPhotos(null) },
                onVideo = { recordVideo(null) },
                onPlace = {
                    if (model.placeName.isNotBlank()) model.insertHere(listOf(model.newPlace(model.placeName))) else placeFor = PlaceTarget.Here
                },
                listening = dictation.state is Dictation.State.Listening,
                onMic = ::toggleMic,
            )
        }
    }

    if (showDetails) EntryDetailsSheet(model, onDismiss = { showDetails = false }, typedAtStart = placeTypedAtStart)

    if (stopVideoAsk) {
        if (model.videoProgress == null) {
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
                    model.cancelVideoThen(scope) {
                        if (model.finish()) {
                            dictation.stop()
                            onDone()
                        }
                    }
                },
                onDismiss = { stopVideoAsk = false },
            )
        }
    }

    albumFor?.let { key ->
        // The layout can turn into a single photo or video (one left) or go away: its sheet closes with it.
        val album = model.items.firstOrNull { it.key == key } as? EdItem.Album
        if (album == null) {
            LaunchedEffect(key) { albumFor = null }
        } else {
            AlbumSheet(
                model, album,
                onAdd = {
                    albumFor = null
                    pickPhotos(null, into = album.key)
                },
                onPlay = { playing = rows(it) },
                onDismiss = { albumFor = null },
            )
        }
    }

    placeFor?.let { target ->
        val editing = target as? PlaceTarget.Edit
        PlaceDialog(
            initial = editing?.item?.block?.name ?: model.placeName,
            onSave = { name ->
                when (target) {
                    is PlaceTarget.Edit -> target.item.block = target.item.block.copy(name = name)
                    is PlaceTarget.InsertAt -> model.insertAt(target.index, listOf(model.newPlace(name)))
                    PlaceTarget.Here -> model.insertHere(listOf(model.newPlace(name)))
                }
                placeFor = null
            },
            onRemove = editing?.let { e ->
                {
                    model.remove(e.item)
                    placeFor = null
                }
            },
            onDismiss = { placeFor = null },
            known = remember(model.date) { model.knownPlaces() },
        )
    }

    playing?.let { PlayVideo(it, onClose = { playing = null }) }

    if (micAsk) MicDialog(
        "Dictation uses the microphone only while the mic button is on.", "Continue", "Not now",
        onConfirm = {
            micAsk = false
            speech.askAccess { granted -> if (granted) startDictation() }
        },
        onDismiss = { micAsk = false },
    )
    if (micBlocked) MicDialog(
        "The microphone is off for this app. You can turn it on in Settings.", "Open Settings", "Cancel",
        onConfirm = {
            micBlocked = false
            speech.openSettings()
        },
        onDismiss = { micBlocked = false },
    )
}

/** The editor's two questions about the microphone (the Android editor's AlertDialogs). */
@Composable
private fun MicDialog(text: String, confirm: String, dismiss: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Bt.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        text = { Text(text, style = Bt.text.body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, style = Bt.text.button, color = c.ink) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss, style = Bt.text.button, color = c.muted) } },
    )
}

/**
 * When a new note is written: now, or at this time of day on [today] when the screens show another day (previews of
 * a fixed day).
 */
internal fun noteTime(today: LocalDate): Long {
    val now = nowMs()
    val real = localDateAt(now, null)
    return if (real.toEpochDay() == today.toEpochDay()) now else startOfDayMs(today, null) + (now - startOfDayMs(real, null))
}

/** A menu of the editor open at the start (previews): Aa, the lists, or the insert menu of the second "+". */
enum class WriteMenu { Style, Lists, Insert }

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

private fun newItem(model: WriteModel, kind: Insert): EdItem = when (kind) {
    Insert.Heading -> model.newText(heading = true)
    Insert.Quote -> model.newQuote()
    Insert.Checklist -> model.newChecklist()
    Insert.Divider -> EdItem.Divider()
    Insert.PageBreak -> EdItem.PageBreak()
    else -> model.newText()
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

/** Back, "SAT 19 JUN · NAXOS · 27°" (a tap: date, time, place) and the round Done. */
@Composable
private fun WriteTopBar(model: WriteModel, onBack: () -> Unit, onDone: () -> Unit, onDetails: () -> Unit) {
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
                .clickable(role = Role.Button, onClickLabel = "Change date and place", onClick = onDetails)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val d = model.date
            val line = listOfNotNull(
                "${Dates.weekday(d)} ${Dates.dayMonth(d)}",
                model.placeName.trim().ifBlank { null },
                model.tempC?.let { "${it.roundToInt()}°" },
            ).joinToString(" · ")
            // "SUN 14 MAR · EL CHALTÉN" in mono, as on the design boards; a tap changes the date and place.
            Text(line.uppercase(), Modifier.weight(1f, fill = false), style = Bt.text.eyebrow, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(BtIcons.Expand, null, Modifier.padding(start = 2.dp).size(14.dp), tint = c.muted)
        }
        BtIconButton(BtIcons.Check, "Done", onDone, filled = true)
    }
}

/** A pick, a shot or a recording waiting for the photos before it to go in ([WriteScreen]). */
class PendingMedia(val picked: List<PickedMedia>, val at: Int?, val into: String?)
