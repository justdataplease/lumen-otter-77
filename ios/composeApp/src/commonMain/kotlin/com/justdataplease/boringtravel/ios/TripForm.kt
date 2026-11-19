package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import com.justdataplease.boringtravel.art.SceneKind
import com.justdataplease.boringtravel.art.ScenePick
import com.justdataplease.boringtravel.data.settings.TripPhotoStyle
import com.justdataplease.boringtravel.doc.PhotoFrame as NoteFrame
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.PhotoFrame
import com.justdataplease.boringtravel.ui.components.btCardShape
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDateOfEpochDay
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import com.justdataplease.boringtravel.ui.components.BtDeleteButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates

/**
 * New or edit trip, as the Android app's Edit trip (ui/trips/EditTripScreen.kt): the cover photo (picked, or one of the
 * trip's own; a drawn picture until there is one), name, route line, dates (or none yet), "Photos in notes" (how new
 * photos sit in this trip's notes) and, for a trip that exists, Delete. A new trip first asks how to start
 * ([NewTripChoices]); the form is "Make it myself", and Back goes to the choices. Save stays in the top bar, a button of
 * its own because new dates move the whole plan.
 *
 * [startOn] is a new trip's first day picked on the Calendar tab (one day until the form changes it). [manualAtStart]
 * and [pickingAtStart] open the form, or its dates, straight away (the previews). [settings] keep each trip's photo
 * defaults (none: the defaults every note had, not kept).
 */
@Composable
fun TripFormScreen(
    book: TripBook,
    tripId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onReadyMade: () -> Unit = {},
    onDeleted: () -> Unit = {},
    onPlanner: (start: String) -> Unit = {},
    manualAtStart: Boolean = tripId != null,
    pickingAtStart: Boolean = false,
    startOn: LocalDate? = null,
    onNewTripShown: () -> Unit = {},
    settings: AppSettings? = null,
) {
    val saved = remember(book, tripId) { tripId?.let(book::trip) }
    val kit = LocalMediaKit.current
    val files = kit.files
    val scope = rememberCoroutineScope()
    // A new trip is the traveller's own: whatever opened the form, demo mode ends first (the Android app's Edit trip).
    LaunchedEffect(Unit) { if (tripId == null) onNewTripShown() }
    if (tripId != null && saved == null) return
    var title by rememberSaveable { mutableStateOf(saved?.title.orEmpty()) }
    var subtitle by rememberSaveable { mutableStateOf(saved?.subtitle.orEmpty()) }
    var cover by rememberSaveable { mutableStateOf(saved?.coverMediaId) }
    // Photos imported on this screen (their ids, joined); the ones not kept as the cover are removed again on save.
    var pickedIds by rememberSaveable { mutableStateOf("") }
    val picked = pickedIds.split(',').filter { it.isNotBlank() }
    var photoError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // Photos in notes: this trip's defaults for new photos; a new trip starts from the last choice.
    var pickedWidth by rememberSaveable { mutableStateOf<Float?>(null) }
    var pickedFrame by rememberSaveable { mutableStateOf<String?>(null) }
    val savedStyle = settings?.tripPhotoStyles?.forForm(tripId) ?: TripPhotoStyle.Default
    val photoStyle = TripPhotoStyle(pickedWidth ?: savedStyle.width, NoteFrame.entries.firstOrNull { it.id == pickedFrame } ?: savedStyle.frame)
    // The trip's own photos (not its videos: a cover is a photo), oldest first as Android lists them.
    val tripPhotos = remember(book, tripId, pickedIds) {
        tripId?.let { id ->
            book.snapshot.media.filter { it.tripId == id && it.deletedAt == null }
                .sortedWith(compareBy<com.justdataplease.boringtravel.data.db.Media>({ it.takenAt != null }, { it.takenAt }, { it.createdAt }))
        }.orEmpty()
    }

    /** The photo picker for the cover (photos only); the photo goes in at once, as Android imports it. */
    fun pickPhoto() {
        kit.picker.pickPhotos(1) { got ->
            val p = got.firstOrNull() ?: return@pickPhotos
            scope.launch {
                photoError = false
                val media = runCatching { withContext(MediaWork) { MediaImporter(files).prepare(p, tripId, null) } }.getOrNull()
                if (media == null) {
                    photoError = true
                    return@launch
                }
                book.addMedia(media)
                cover = media.id
                pickedIds = (picked + media.id).joinToString(",")
            }
        }
    }
    // Kept as day numbers, which every phone's saved state can hold.
    var startDay by rememberSaveable { mutableStateOf(if (tripId == null) startOn?.toEpochDay() else saved?.startDate?.toEpochDay()) }
    var endDay by rememberSaveable { mutableStateOf(if (tripId == null) startOn?.toEpochDay() else saved?.endDate?.toEpochDay()) }
    val start = startDay?.let(::localDateOfEpochDay)
    val end = endDay?.let(::localDateOfEpochDay)
    var manual by rememberSaveable { mutableStateOf(manualAtStart) }
    var picking by remember { mutableStateOf(pickingAtStart) }
    var confirmDelete by remember { mutableStateOf(false) }
    val hasDatedItems = remember(book, tripId) { tripId != null && book.items(tripId).any { it.day != null } }
    // A dated example trip: one start date moves it all (plan, journal, pictures and stays).
    val demoDates = saved?.isDemo == true && saved.startDate != null && start != null && end != null

    fun save() {
        if (busy || title.isBlank()) return
        busy = true
        // Photos picked here and not kept as the cover go again.
        picked.filter { it != cover }.forEach { id ->
            if (book.removeMedia(id)) freeFiles(book, files, book.snapshot.media.filter { it.id == id })
        }
        if (tripId == null) {
            val trip = book.newTrip(title, start, end, subtitle)
            settings?.setTripPhotoStyle(trip.id, photoStyle)
            cover?.let { id ->
                book.saveDraft(trip.copy(coverMediaId = id), emptyList())
                // The photo was picked before the trip existed; file it under the trip now.
                book.media(id)?.takeIf { it.tripId == null }?.let { book.addMedia(it.copy(tripId = trip.id, updatedAt = 0L)) }
            }
            onSaved(trip.id)
        } else {
            if (pickedWidth != null || pickedFrame != null) settings?.setTripPhotoStyle(tripId, photoStyle)
            val newStart = start
            if (saved?.isDemo == true && saved.startDate != null && newStart != null) DemoMode.moveDemo(book, tripId, newStart, title, subtitle)
            else book.editTrip(tripId, title, subtitle, start, end)
            book.trip(tripId)?.takeIf { it.coverMediaId != cover }?.let { book.saveDraft(it.copy(coverMediaId = cover), emptyList()) }
            onSaved(tripId)
        }
    }

    // New trip's Back from the form goes to the start choices (Android's EditTripScreen BackHandler); else the app's own
    // Back closes the screen.
    BackHandler(enabled = tripId == null && manual) { manual = false }

    BtScreen {
        Column(Modifier.fillMaxSize().imePadding()) {
            BtTopBar(if (tripId == null) "New trip" else "Edit trip", onBack = { if (tripId == null && manual) manual = false else onBack() }) {
                if (manual) BtButton("Save", onClick = ::save, enabled = title.isNotBlank() && !busy, small = true)
            }
            if (!manual) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
                    NewTripChoices(
                        onQuestions = { onPlanner(PlannerStart.QUESTIONS) }, onPictures = { onPlanner(PlannerStart.PICTURES) },
                        onLink = { onPlanner(PlannerStart.LINK) }, onReadyMade = onReadyMade, onMyself = { manual = true },
                    )
                }
            } else Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val country = rememberWorld(book, 0, saved?.isDemo == true, com.justdataplease.boringtravel.time.today()).tripCountries[tripId]?.firstOrNull()
                // Without a photo the trip shows a drawn picture; a new trip shows one once its name says which.
                val drawn = remember(tripId, title, subtitle, country) {
                    tripId?.let { ScenePick.forTrip(it, title, subtitle, country) } ?: ScenePick.kindOf("$title $subtitle")
                }
                CoverField(cover, tripPhotos, drawn, tripId, onPick = ::pickPhoto, onChoose = { cover = it }, onRemove = { cover = null })
                if (photoError) Text("That photo couldn't be added. Try another one.", style = Bt.text.fine, color = Bt.colors.danger)

                OutlinedTextField(
                    title, { title = it }, Modifier.fillMaxWidth(),
                    label = { Text("Name") }, placeholder = { Text("Cyclades, slowly") },
                    singleLine = true, textStyle = Bt.text.body,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                OutlinedTextField(
                    subtitle, { subtitle = it }, Modifier.fillMaxWidth(),
                    label = { Text("Route") }, placeholder = { Text("Paros → Naxos → Amorgos") },
                    singleLine = true, textStyle = Bt.text.body,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Dates")
                    BtGhostButton(
                        if (start != null && end != null) Dates.range(start, end) else "No dates yet",
                        onClick = { picking = true },
                        modifier = Modifier.fillMaxWidth(),
                        icon = BtIcons.Calendar,
                        trailing = if (start != null && end != null) days(start, end) else "Add",
                    )
                    val savedStart = saved?.startDate
                    when {
                        demoDates -> Text("The demo's plan, journal and pictures move with the dates.", style = Bt.text.fine)
                        start == null -> Text("Someday trips keep ideas until you pick dates.", style = Bt.text.fine)
                        hasDatedItems && savedStart != null && start != savedStart -> Text("Planned days move with the new dates.", style = Bt.text.fine)
                    }
                }

                PhotosInNotes(photoStyle, onSize = { pickedWidth = it }, onFrame = { pickedFrame = it.id })

                if (tripId != null) {
                    Spacer(Modifier.height(4.dp))
                    BtDeleteButton("Delete trip", { confirmDelete = true }, enabled = !busy)
                }
            }
        }
    }

    if (picking && demoDates) {
        // An example trip keeps its length: only the start is picked, the end follows.
        val length = end.toEpochDay() - start.toEpochDay()
        DayDialog(start, onDismiss = { picking = false }) { d ->
            startDay = d.toEpochDay()
            endDay = d.toEpochDay() + length
            picking = false
        }
    } else if (picking) TripDatesDialog(
        start, end,
        onPick = { s, e ->
            startDay = s?.toEpochDay()
            endDay = e?.toEpochDay()
            picking = false
        },
        onDismiss = { picking = false },
    )
    if (confirmDelete && tripId != null) DeleteTripDialog(
        onConfirm = {
            confirmDelete = false
            removeTripAndFiles(book, files, tripId)
            onDeleted()
        },
        onDismiss = { confirmDelete = false },
    )
}

/** What deleting a trip says it takes with it (the Android app's DeleteTripText). */
object DeleteTripText {
    const val TITLE = "Delete this trip?"
    const val BODY = "Are you sure you want to delete it? Its plan, journal, photos, clips, book and recorded route are deleted, and its calendar events " +
        "removed. Your phone's gallery and your community posts stay as they are."
}

/**
 * Asks before deleting a trip (the trip's menu and Edit trip), with the Android app's words for what goes with it
 * (this phone has no clips, book, route or calendar events yet, so its photos are all there is beside the plan and
 * journal: [removeTripAndFiles]). Both buttons are held once Delete is tapped.
 */
@Composable
fun DeleteTripDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    BtConfirmDialog(
        title = DeleteTripText.TITLE,
        body = DeleteTripText.BODY,
        confirm = "Delete", danger = true, busy = busy,
        onConfirm = {
            busy = true
            onConfirm()
        },
        onDismiss = onDismiss,
    )
}

private fun days(s: LocalDate, e: LocalDate): String = Dates.days((e.toEpochDay() - s.toEpochDay() + 1).toInt())

/**
 * "Photos in notes": how a new photo or video sits in this trip's notes, its size and frame (the Android app's
 * PhotosInNotes). Each one can still be changed in its note.
 */
@Composable
private fun PhotosInNotes(style: TripPhotoStyle, onSize: (Float) -> Unit, onFrame: (NoteFrame) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow("Photos in notes")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TripPhotoStyle.SIZES.forEach { size -> BtChip(size.label, style.size == size, { onSize(size.width) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NoteFrame.entries.forEach { f -> BtChip(TripPhotoStyle.frameLabel(f), style.frame == f, { onFrame(f) }) }
        }
        Text("New photos and videos in this trip's notes start like this; a layout takes the frame. You can change each one.", style = Bt.text.fine)
    }
}

/** The cover: the photo (change or remove), or an empty frame that opens the photo picker; plus this trip's photos. */
@Composable
private fun CoverField(
    cover: String?, tripPhotos: List<com.justdataplease.boringtravel.data.db.Media>, drawn: SceneKind?, tripId: String?,
    onPick: () -> Unit, onChoose: (String) -> Unit, onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (cover != null) {
            PhotoFrame(Modifier.fillMaxWidth().clickable(onClickLabel = "Change cover photo", onClick = onPick)) {
                MediaImage(cover, Modifier.fillMaxWidth().height(170.dp), contentDescription = "Cover photo", playBadge = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BtGhostButton("Change photo", onClick = onPick, icon = BtIcons.Photo)
                BtGhostButton("Remove", onClick = onRemove)
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(128.dp)
                    .clip(btCardShape())
                    .border(1.dp, Bt.colors.line, btCardShape())
                    .clickable(role = Role.Button, onClickLabel = "Add a cover photo", onClick = onPick),
                contentAlignment = Alignment.Center,
            ) {
                CoverPlaceholder(Modifier.matchParentSize(), drawn, key = tripId)
                BtButton("Add a cover photo", onClick = onPick, icon = BtIcons.Photo, small = true)
            }
        }
        // A cover is a photo: the trip's videos aren't offered.
        val others = tripPhotos.filter { it.id != cover && !it.isVideo }
        if (others.isNotEmpty()) {
            Eyebrow("Or pick one from this trip")
            val thumb = RoundedCornerShape(Bt.tokens.photo.corner)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(others.take(24), key = { it.id }) { m ->
                    MediaImage(
                        m,
                        Modifier.size(56.dp).clip(thumb).clickable(onClickLabel = "Use as cover") { onChoose(m.id) },
                        contentDescription = "Photo from this trip",
                    )
                }
            }
        }
    }
}

/** A trip without a cover photo: its drawn picture when the name says which, else the photo mark on the chip colour. */
@Composable
private fun CoverPlaceholder(modifier: Modifier = Modifier, kind: SceneKind? = null, key: String? = null) {
    if (kind != null) {
        DrawnScene(kind, modifier, key = key)
        return
    }
    Box(modifier.background(Bt.colors.chip), contentAlignment = Alignment.Center) {
        Icon(BtIcons.Photo, "No cover photo", Modifier.size(28.dp), tint = Bt.colors.muted)
    }
}

/**
 * How a new trip starts, in the Android app's order: Make it myself in its own panel first, then "Or", the three AI
 * ways (each opens the one planner chat at its step), "Or" and a ready-made trip. The community trips come with the
 * community.
 */
@Composable
private fun NewTripChoices(onQuestions: () -> Unit, onPictures: () -> Unit, onLink: () -> Unit, onReadyMade: () -> Unit, onMyself: () -> Unit) {
    val panel = PaddingValues(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)
    BtCard(Modifier.padding(top = 4.dp), padding = panel) {
        StartChoice(BtIcons.Edit, "Make it myself", "Name, route and dates.", onMyself)
    }
    OrLine()
    BtCard(padding = panel) {
        StartChoice(BtIcons.Sparkle, "From a questionnaire", "Where, when, who, pace and interests. AI plans every day.", onQuestions)
        BtDivider()
        StartChoice(BtIcons.Photo, "From pictures", "Screenshots, guide pages, maps or tickets. AI reads them.", onPictures)
        BtDivider()
        StartChoice(BtIcons.Link, "From a site, blog or Google list", "A web page, your blog, a Google Doc or a Google Maps list. AI reads it.", onLink)
    }
    OrLine()
    BtCard(padding = panel) {
        StartChoice(BtIcons.World, "Start from a ready-made trip", "Day-by-day plans for popular places. Pick one and set your dates.", onReadyMade)
    }
}

/** "Or" between the panels of [NewTripChoices], centred. */
@Composable
private fun OrLine() {
    Box(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), contentAlignment = Alignment.Center) { Eyebrow("Or") }
}

@Composable
private fun StartChoice(icon: ImageVector, title: String, line: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Bt.colors.ink)
        Column(Modifier.weight(1f)) {
            Text(title, style = Bt.text.item)
            Text(line, Modifier.padding(top = 2.dp), style = Bt.text.sub)
        }
        Icon(BtIcons.Forward, null, Modifier.size(20.dp), tint = Bt.colors.muted)
    }
}
