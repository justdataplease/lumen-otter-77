package com.justdataplease.boringtravel.ui.trips

import com.justdataplease.boringtravel.ui.components.BtDeleteButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.art.SceneKind
import com.justdataplease.boringtravel.art.ScenePick
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.data.settings.TripPhotoStyle
import com.justdataplease.boringtravel.doc.PhotoFrame as NoteFrame
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.MediaImage
import com.justdataplease.boringtravel.ui.components.PhotoFrame
import com.justdataplease.boringtravel.ui.components.btCardShape
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.nav.CommunityHome
import com.justdataplease.boringtravel.ui.nav.PlannerStart
import com.justdataplease.boringtravel.ui.components.BtDivider
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.trip.PlanDays
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * New or edit trip: cover photo, name, route line, dates (or none yet), and delete. A new trip can start from a trip
 * idea instead (a ready day-by-day plan, see ui/programs). An example trip (Demo) picks only its start: the whole demo
 * moves with it and keeps its length. [startOn] is a new trip's first day picked on the Calendar tab (one day until
 * the traveller picks the end). A new trip is never made in demo mode: the form turns it off. Save stays in the top
 * bar, always in reach however long the form (owner, 2026-11-12); it stays a button of its own because new dates move
 * the whole plan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTripScreen(graph: AppGraph, actions: AppActions, tripId: String?, startOn: LocalDate? = null) {
    val scope = rememberCoroutineScope()
    val demo = Trip.isDemoId(tripId)
    var loaded by rememberSaveable { mutableStateOf(tripId == null) }
    var title by rememberSaveable { mutableStateOf("") }
    var subtitle by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf(if (tripId == null) startOn else null) }
    var end by rememberSaveable { mutableStateOf(if (tripId == null) startOn else null) }
    var cover by rememberSaveable { mutableStateOf<String?>(null) }
    var savedStart by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    // Photos imported on this screen; the ones not kept as the cover are removed again on save.
    var picked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var hasDatedItems by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // A new trip first asks how to start (owner, 18 Nov 2026); the form is "Make it myself". Back goes to the choices.
    var manual by rememberSaveable { mutableStateOf(tripId != null) }
    BackHandler(enabled = tripId == null && manual) { manual = false }
    var busy by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf(false) }
    // Photos in notes (owner, 2026-11-14): this trip's defaults for new photos; a new trip starts from the last choice.
    val photoStyles by graph.settings.tripPhotoStyles.collectAsState(initial = null)
    var pickedWidth by rememberSaveable { mutableStateOf<Float?>(null) }
    var pickedFrame by rememberSaveable { mutableStateOf<String?>(null) }
    val savedStyle = photoStyles?.forForm(tripId) ?: TripPhotoStyle.Default
    val photoStyle = TripPhotoStyle(pickedWidth ?: savedStyle.width, NoteFrame.entries.firstOrNull { it.id == pickedFrame } ?: savedStyle.frame)

    // Owner, 2026-11-11: "in order to create a new trip should not be in demo". Whatever opened the New trip form (the
    // Calendar, a shortcut, a shared link, the community), it ends demo mode first ("Demo mode off").
    LaunchedEffect(Unit) { if (tripId == null) graph.demo.leave() }
    LaunchedEffect(tripId) {
        if (tripId == null) return@LaunchedEffect
        hasDatedItems = graph.plan.items(tripId).any { it.day != null }
        if (!loaded) graph.trips.get(tripId)?.let {
            title = it.title
            subtitle = it.subtitle
            start = it.startDate
            end = it.endDate
            cover = it.coverMediaId
            savedStart = it.startDate
        }
        loaded = true
    }
    val tripPhotos by remember(tripId) { tripId?.let { graph.media.observeForTrip(it) } ?: flowOf(emptyList()) }
        .collectAsState(initial = emptyList())
    // A dated example trip: one start date moves it all.
    val demoDates = demo && savedStart != null && start != null && end != null

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            photoError = false
            runCatching { graph.media.import(uri, tripId, null) }
                .onSuccess {
                    cover = it.id
                    picked = picked + it.id
                }
                .onFailure { photoError = true }
        }
    }
    fun pickPhoto() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    fun save() {
        if (busy || title.isBlank()) return
        busy = true
        scope.launch {
            picked.filter { it != cover }.forEach { id -> graph.media.get(id)?.let { graph.media.delete(it) } }
            if (tripId == null) {
                val trip = graph.trips.create(title, start, end, subtitle)
                graph.settings.setTripPhotoStyle(trip.id, photoStyle)
                cover?.let { id ->
                    graph.trips.save(trip.copy(coverMediaId = id))
                    // The photo was picked before the trip existed; file it under the trip now.
                    graph.media.get(id)?.takeIf { it.tripId == null }?.let { graph.media.save(it.copy(tripId = trip.id)) }
                }
                actions.back()
                actions.openTrip(trip.id)
            } else {
                if (pickedWidth != null || pickedFrame != null) graph.settings.setTripPhotoStyle(tripId, photoStyle)
                val old = graph.trips.get(tripId)
                val newStart = start
                if (old != null && old.isDemo && old.startDate != null && newStart != null) {
                    // The demo moves as one: plan, journal, pictures and stays by the same number of days.
                    graph.trips.moveDemo(tripId, newStart) { it.copy(title = title.trim(), subtitle = subtitle.trim(), coverMediaId = cover) }
                } else if (old != null) {
                    val moved = PlanDays.shift(graph.plan.items(tripId), old.startDate, start)
                    if (moved.isNotEmpty()) graph.plan.saveAll(moved)
                    // The days' own names move with the days, like the items.
                    val days = if (old.startDate != null && newStart != null) newStart.toEpochDay() - old.startDate!!.toEpochDay() else 0L
                    graph.trips.save(old.copy(title = title.trim(), subtitle = subtitle.trim(), startDate = start, endDate = end, coverMediaId = cover), shiftDays = days)
                }
                actions.back()
            }
        }
    }

    BtScreen {
        Column(Modifier.fillMaxSize().imePadding()) {
            BtTopBar(if (tripId == null) "New trip" else "Edit trip", onBack = { if (tripId == null && manual) manual = false else actions.back() }) {
                if (manual) BtButton("Save", onClick = ::save, enabled = loaded && title.isNotBlank() && !busy, small = true)
            }
            if (!manual) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
                ) {
                    NewTripChoices(
                        onQuestions = { actions.planner(null, start = PlannerStart.QUESTIONS) },
                        onPictures = { actions.planner(null, start = PlannerStart.PICTURES) },
                        onLink = { actions.planner(null, start = PlannerStart.LINK) },
                        onReadyMade = { actions.tripIdeas() },
                        onCommunity = { actions.tab(CommunityHome) },
                        onMyself = { manual = true },
                    )
                }
            } else Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val country = graph.world.world.collectAsState().value?.tripCountries?.get(tripId)?.firstOrNull()
                // Without a photo the trip shows a drawn picture; a new trip shows one once its name says which.
                val drawn = remember(tripId, title, subtitle, country) { tripId?.let { ScenePick.forTrip(it, title, subtitle, country) } ?: ScenePick.kindOf("$title $subtitle") }
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
                    val s = start
                    val e = end
                    BtGhostButton(
                        if (s != null && e != null) Dates.range(s, e) else "No dates yet",
                        onClick = { picking = true },
                        modifier = Modifier.fillMaxWidth(),
                        icon = BtIcons.Calendar,
                        trailing = if (s != null && e != null) days(s, e) else "Add",
                    )
                    when {
                        demoDates -> Text("The demo's plan, journal and pictures move with the dates.", style = Bt.text.fine)
                        s == null -> Text("Someday trips keep ideas until you pick dates.", style = Bt.text.fine)
                        hasDatedItems && savedStart != null && s != savedStart -> Text("Planned days move with the new dates.", style = Bt.text.fine)
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
        val from = start
        val length = if (from != null) (end ?: from).toEpochDay() - from.toEpochDay() else 0L
        val state = rememberDatePickerState(initialSelectedDateMillis = from?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let(::utcDay)?.let { d -> start = d; end = d.plusDays(length) }
                        picking = false
                    },
                ) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    } else if (picking) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = start?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
            initialSelectedEndDateMillis = end?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        start = state.selectedStartDateMillis?.let(::utcDay)
                        end = (state.selectedEndDateMillis ?: state.selectedStartDateMillis)?.let(::utcDay)
                        picking = false
                    },
                ) { Text("Done") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { start = null; end = null; picking = false }) { Text("No dates yet") }
                    TextButton(onClick = { picking = false }) { Text("Cancel") }
                }
            },
        ) { DateRangePicker(state, Modifier.weight(1f)) }
    }

    if (confirmDelete && tripId != null) {
        // A shared trip isn't deleted for the others: it leaves this phone (Travel together).
        DeleteTripDialog(graph, tripId, onDismiss = { confirmDelete = false }, onDone = {
            confirmDelete = false
            busy = true
            // The trip screen below notices the deletion and closes itself too.
            actions.back()
        })
    }
}

/**
 * "Photos in notes": how a new photo or video sits in this trip's notes, its size and frame (owner, 2026-11-14:
 * "medium, postcard … as defaults when writing"). Each one can still be changed in its note.
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

private fun utcDay(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

private fun days(s: LocalDate, e: LocalDate): String = Dates.days((e.toEpochDay() - s.toEpochDay() + 1).toInt())

/** The cover: the photo (change or remove), or an empty frame that opens the photo picker; plus this trip's photos. */
@Composable
private fun CoverField(cover: String?, tripPhotos: List<Media>, drawn: SceneKind?, tripId: String?, onPick: () -> Unit, onChoose: (String) -> Unit, onRemove: () -> Unit) {
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

/**
 * How a new trip starts (owner, 18 Nov 2026: "in first screen have Plan with AI from questionnaire, from pictures,
 * import from site, blog, Google list; start from ready-made trip; make it myself"; then "put make it myself top, the
 * AI, the ready-made … all together, one panel, remove the with AI title"; later that day: Make it myself in its own
 * panel first, then Or, the three AI ways, Or, the ready-made and community trips). The three AI ways open the one
 * planner chat at that step; a ready-made trip opens Trip ideas; Make it myself shows the form.
 */
@Composable
private fun NewTripChoices(
    onQuestions: () -> Unit, onPictures: () -> Unit, onLink: () -> Unit, onReadyMade: () -> Unit, onCommunity: () -> Unit, onMyself: () -> Unit,
) {
    val panel = PaddingValues(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)
    // Owner, 18 Nov 2026: "make it myself in its own panel at start, then Or, the others, Or, the last".
    BtCard(Modifier.padding(top = 4.dp), padding = panel) {
        StartChoice(BtIcons.Edit, "Make it myself", "Name, route, dates and a cover photo.", onMyself)
    }
    OrLine()
    BtCard(padding = panel) {
        // Owner, 18 Nov 2026: "remove plan with AI" from these titles; the lines say AI does it.
        StartChoice(BtIcons.Sparkle, "From a questionnaire", "Where, when, who, pace and interests. AI plans every day.", onQuestions)
        BtDivider()
        StartChoice(BtIcons.Photo, "From pictures", "Screenshots, guide pages, maps or tickets. AI reads them.", onPictures)
        BtDivider()
        StartChoice(BtIcons.Link, "From a site, blog or Google list", "A web page, your blog, a Google Doc or a Google Maps list. AI reads it.", onLink)
    }
    OrLine()
    BtCard(padding = panel) {
        // No names: more ready-made trips will come (owner).
        StartChoice(BtIcons.World, "Start from a ready-made trip", "Day-by-day plans for popular places. Pick one and set your dates.", onReadyMade)
        BtDivider()
        // Owner: "have also a 2nd, a community trip": the Community tab, where a posted trip's Copy this trip makes it yours.
        StartChoice(BtIcons.Community, "Start from a community trip", "Trips other travellers posted. Copy one and set your dates.", onCommunity)
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
