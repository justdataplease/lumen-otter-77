package com.justdataplease.boringtravel.ui.ai

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanSpan
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.ai.PlacedItem
import com.justdataplease.boringtravel.ai.PlacedRemoval
import com.justdataplease.boringtravel.ai.PlacedUpdate
import com.justdataplease.boringtravel.ai.TripChange
import com.justdataplease.boringtravel.ai.tools.TravelBrief
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.links.LinkUrls
import com.justdataplease.boringtravel.links.MapsLinks
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.site.SiteLinks
import com.justdataplease.boringtravel.speech.Dictation
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTextDialog
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.ProTag
import com.justdataplease.boringtravel.ai.PictureReading
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.nav.PlannerStart
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.trip.clipboardText
import com.justdataplease.boringtravel.util.Dates

/**
 * The AI planner: one box for anything (a pasted itinerary, booking emails, "move the Prado to 11:30"); the planner
 * makes the changes with its tools, the bar above the box offers Review and Save, and the review opens in a sheet
 * (owner, 2026-11-11: the preview is not shown inside the chat).
 */
@Composable
fun PlannerScreen(
    graph: AppGraph, actions: AppActions, tripId: String?, sharedText: String? = null, sharedUri: String? = null,
    sharedPictures: List<String> = emptyList(), onSharedHandled: () -> Unit = {}, @Suppress("UNUSED_PARAMETER") startInChat: Boolean = false,
    start: String? = null,
) {
    val vm = viewModel { PlannerViewModel(graph, tripId, createSavedStateHandle()) }
    // Copy from a link, opened from this chat, offers the chat's trip as where things go.
    val copySite: (String) -> Unit = { actions.copySite(it, vm.tripId) }
    val billing by graph.billing.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(sharedText, sharedUri, sharedPictures) {
        if (sharedText != null || sharedUri != null || sharedPictures.isNotEmpty()) {
            vm.receiveShared(sharedText, sharedUri, sharedPictures)
            onSharedHandled()
        }
    }
    // Pictures attached to the message: screenshots, guide pages, maps, tickets, read when it is sent ([PictureReading]).
    val picturePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PictureReading.MAX_PICTURES)) { uris ->
        vm.addPictures(uris.map { it.toString() })
    }
    val pickPictures = { picturePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    // Add a link: open with the clipboard's link already in, when it holds one (read only on this tap).
    var linkValue by rememberSaveable { mutableStateOf<String?>(null) }
    var linkError by rememberSaveable { mutableStateOf<String?>(null) }
    val askLink = {
        linkError = null
        linkValue = clipboardText(context)?.let { Attachments.link(it) }.orEmpty()
    }
    linkValue?.let { value ->
        BtTextDialog(
            "Add a link", value,
            onSave = { typed ->
                if (vm.addLink(typed)) linkValue = null else linkError = "That isn't a link. Paste one beginning https://"
            },
            onDismiss = { linkValue = null },
            hint = "https://…", note = "A Google Maps place or list, a web page, a blog or a Google Doc.",
            max = 2048, confirm = "Add", error = linkError,
        )
    }
    // Opened from one of New trip's choices: that step comes up once, not again after turning the phone.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(start) {
        if (start == null || started) return@LaunchedEffect
        started = true
        when (start) {
            PlannerStart.QUESTIONS -> vm.openQuestions()
            PlannerStart.PICTURES -> pickPictures()
            PlannerStart.LINK -> askLink()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stopDictation() }
    val onSaved: (String, String) -> Unit = { id, summary ->
        Toast.makeText(context, summary, Toast.LENGTH_SHORT).show()
        actions.plannerDone(id)
    }
    BtScreen {
        Column(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.navigationBars).imePadding()) {
            BtTopBar("AI planner", onBack = actions::back, trailing = {
                // With their own key the planner isn't a Pro feature: no tag then.
                Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) { if (!billing.isPro && vm.ownKey == null) ProTag() }
            })
            Eyebrow(tripLine(vm), Modifier.padding(horizontal = 20.dp))
            vm.ownKeyLine?.let { line ->
                Text(
                    line,
                    Modifier.padding(horizontal = 20.dp).clickable(role = Role.Button, onClick = actions::aiSettings).padding(vertical = 4.dp),
                    style = Bt.text.fine, color = Bt.colors.muted,
                )
            }
            Conversation(vm, billing.isPro, actions, onSaved, copySite, pickPictures, askLink, Modifier.weight(1f))
        }
    }
    if (vm.reviewing) vm.proposal?.let { ReviewSheet(vm, it, onSaved) }
    vm.questions?.let { brief ->
        TripQuestionsSheet(brief, vm.error, sending = vm.busy, onDismiss = vm::closeQuestions, onSend = { vm.sendAnswers(it, actions::paywall) })
    }
}

private fun tripLine(vm: PlannerViewModel): String {
    if (vm.isNewTrip) return "New trip"
    val t = vm.trip ?: return ""
    val s = t.startDate
    val e = t.endDate
    return if (s != null && e != null) "${t.title} · ${Dates.range(s, e)}" else t.title
}

@Composable
private fun Conversation(
    vm: PlannerViewModel, isPro: Boolean, actions: AppActions, onSaved: (String, String) -> Unit, onCopySite: (String) -> Unit,
    onPickPictures: () -> Unit, onAskLink: () -> Unit, modifier: Modifier,
) {
    val focus = LocalFocusManager.current
    val dictationState by vm.dictation.state.collectAsState()
    val listening = dictationState is Dictation.State.Listening
    val language = LocalConfiguration.current.locales[0]?.toLanguageTag() ?: "en"
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startDictation(language) else vm.microphoneDenied()
    }
    val list = rememberLazyListState()
    // Keep the newest message (or the progress line, or the error) in view: intro + messages + busy + error.
    val itemCount = 1 + vm.messages.size + (if (vm.busy) 1 else 0) + (if (vm.error != null) 1 else 0)
    LaunchedEffect(itemCount) { list.animateScrollToItem(itemCount - 1) }
    val empty = vm.messages.isEmpty()
    Column(modifier.fillMaxWidth()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (empty) {
                        // Owner, 19 Nov 2026: say it without "with AI" (the screen is the AI planner already).
                        Text(if (vm.isNewTrip) "Make a new trip" else "Change this trip", style = Bt.text.h2)
                        Text(
                            if (vm.isNewTrip) "Paste your itinerary, booking emails or notes, or just say where and when. The planner sets the dates, every day, the places on the map, times and links. You check it before anything is saved."
                            else "Paste your updated list, or say what to change: dates, times, places, a new stop, something to remove. You check every change before it is saved.",
                            style = Bt.text.sub,
                        )
                        // The attachments in plain sight before the first message; + offers them after.
                        Eyebrow(if (vm.isNewTrip) "Start from" else "Add from", Modifier.padding(top = 8.dp))
                        BtCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                            AttachChoices(enabled = !vm.busy && !vm.importing && !vm.applying, onPictures = onPickPictures, onLink = onAskLink)
                        }
                        // The travel agent without typing (point 11): its questions straight away, then the plan.
                        if (vm.available && vm.planEmpty) {
                            BtGhostButton(
                                if (vm.isNewTrip) "Plan a trip for me" else "Plan the days for me", { vm.openQuestions() },
                                Modifier.fillMaxWidth().padding(top = 4.dp), icon = BtIcons.Sparkle, enabled = !vm.busy && vm.loaded,
                            )
                        }
                    }
                    if (vm.ownKey == null) Allowance(isPro, vm.freeLeft, actions)
                }
            }
            itemsIndexed(vm.messages) { _, m ->
                Bubble(
                    m,
                    onQuestions = if (!vm.busy && !vm.briefAnswered) vm::openQuestions else null,
                    onCopy = onCopySite,
                )
            }
            if (vm.busy) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Working(vm.step ?: "Reading your message")
                    BtGhostButton("Stop", vm::cancelPlan, Modifier.fillMaxWidth())
                }
            }
            vm.error?.let { e -> item { ErrorLine(e) } }
        }
        ImportLine(vm)
        vm.proposal?.takeIf { !vm.busy }?.let { ReviewBar(vm, it, onSaved) }
        BtDivider()
        when (val state = dictationState) {
            is Dictation.State.Listening -> Text("Listening · tap Stop, then check your words before sending.", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = Bt.text.fine)
            is Dictation.State.Failed -> Text(state.message, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = Bt.text.fine, color = Bt.colors.danger)
            else -> Unit
        }
        val free = !vm.busy && !vm.importing && !vm.applying
        AttachmentStrip(vm.attachments, enabled = free, onRemovePicture = vm::removePicture, onRemoveLink = vm::removeLink)
        Row(
            Modifier.fillMaxWidth().background(Bt.colors.bg).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AttachButton(enabled = free, onPictures = onPickPictures, onLink = onAskLink)
            TextBox(
                vm.input, vm::updateInput,
                when {
                    vm.proposal?.layout?.questions?.isNotEmpty() == true -> "Answer the questions above"
                    empty && vm.isNewTrip -> "Paste your trip, or say where and when"
                    empty -> "Paste your list, or say what to change"
                    else -> "Ask for a change"
                },
                minHeight = if (empty) 96.dp else 48.dp, maxLines = 6, modifier = Modifier.weight(1f),
                enabled = !vm.busy && !vm.importing && !vm.applying,
            )
            BtIconButton(if (listening) BtIcons.Stop else BtIcons.Mic, if (listening) "Stop dictation" else "Dictate message", onClick = {
                when {
                    listening -> vm.stopDictation()
                    vm.busy || vm.importing || vm.applying -> Unit
                    vm.dictation.hasPermission() -> vm.startDictation(language)
                    else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }, filled = listening, size = 48.dp)
            val canSend = vm.available && (vm.input.isNotBlank() || !vm.attachments.isEmpty) && free && !listening && vm.loaded
            BtIconButton(BtIcons.Send, "Send", onClick = { if (canSend) { focus.clearFocus(); vm.send(actions::paywall) } }, filled = canSend, size = 48.dp)
        }
    }
}

/**
 * The planner's hand-over to Copy from a link (copy_notes_from_link, point 12): the traveller's own blog or document,
 * copied there with its words and pictures as written.
 */
@Composable
private fun SiteOffer(link: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(BtIcons.Link, null, Modifier.size(20.dp), tint = Bt.colors.accentInk)
        Text(
            if (SiteLinks.isDoc(link)) "Copy the notes and pictures from this document" else "Copy the notes and pictures from ${SiteLinks.host(link)}",
            Modifier.weight(1f), style = Bt.text.sub, color = Bt.colors.accentInk,
        )
        Icon(BtIcons.Forward, null, Modifier.size(18.dp), tint = Bt.colors.muted)
    }
}

/** Above the box once the planner has changes: how many, Review (the sheet) and Save (all of them). */
@Composable
private fun ReviewBar(vm: PlannerViewModel, proposal: Proposal, onSaved: (String, String) -> Unit) {
    val layout = proposal.layout
    if (layout.questions.isNotEmpty()) return
    val count = layout.reviewableCount
    Column(Modifier.fillMaxWidth().background(Bt.colors.surface).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BtDivider()
        Text(
            when {
                layout.problems.isNotEmpty() -> "Some changes need another look. Ask the planner, or review them."
                count == 0 -> "Nothing new for the trip."
                count == 1 -> "1 change ready to check"
                else -> "$count changes ready to check"
            },
            style = Bt.text.item,
        )
        TripChoice(vm)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BtGhostButton("Review", { vm.reviewing = true }, Modifier.weight(1f), enabled = !vm.applying)
            BtButton(
                if (vm.applying) "Saving" else if (vm.isNewTrip) "Create trip" else "Save",
                onClick = { vm.apply(onSaved) }, modifier = Modifier.weight(1f),
                enabled = !vm.applying && count > 0 && proposal.ticked > 0 && layout.problems.isEmpty(),
            )
        }
    }
}

/**
 * A chat that began as a new trip asks where its changes go (owner, 17 Nov 2026: "create new trip or append"): the new
 * trip, or one the traveller has. Nothing for a chat opened in a trip, or a traveller without trips.
 */
@Composable
private fun TripChoice(vm: PlannerViewModel) {
    if (!vm.startedNew || vm.tripChoices.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("A new trip, or add to one you have?", style = Bt.text.sub)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            item(key = "new") { BtChip("New trip", vm.isNewTrip, { vm.useTrip(null) }, icon = BtIcons.Add) }
            items(vm.tripChoices, key = { it.id }) { t -> BtChip(t.title.ifBlank { "Untitled trip" }, vm.tripId == t.id, { vm.useTrip(t.id) }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewSheet(vm: PlannerViewModel, proposal: Proposal, onSaved: (String, String) -> Unit) {
    val c = Bt.colors
    ModalBottomSheet(
        onDismissRequest = { vm.reviewing = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ProposalView(proposal, isNewTrip = vm.isNewTrip, onToggle = vm::toggle)
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                proposal.layout.problems.forEach { ErrorLine(it) }
                vm.error?.let { ErrorLine(it) }
                Text("${proposal.ticked} of ${proposal.layout.reviewableCount} selected", style = Bt.text.fine)
                BtButton(
                    if (vm.applying) "Saving" else if (vm.isNewTrip) "Create trip" else "Save selected",
                    onClick = { vm.apply(onSaved) }, modifier = Modifier.fillMaxWidth(),
                    enabled = !vm.applying && proposal.ticked > 0 && proposal.layout.problems.isEmpty(),
                )
                BtGhostButton("Discard all", vm::discard, Modifier.fillMaxWidth(), enabled = !vm.applying)
                ReportAnswer(proposal.reportText(), proposal.model)
            }
        }
    }
}

/** A text file shared to the app, read into the box: the step and Cancel while it reads, then what went wrong, if anything. */
@Composable
private fun ImportLine(vm: PlannerViewModel) {
    if (!vm.importing && vm.importError == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.importing) {
            Working("Reading the document")
            BtGhostButton("Cancel", onClick = vm::cancelImport, modifier = Modifier.fillMaxWidth())
        }
        vm.importError?.let { ErrorLine(it) }
    }
}

/**
 * + beside the box (owner, 18 Nov 2026: "the import icon is dirty, simplify"; "either import images or link, it's like
 * an attachment to the same chat"; option C of three): a sheet with Pictures and Link, attached to the message being
 * written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachButton(enabled: Boolean, onPictures: () -> Unit, onLink: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    BtIconButton(BtIcons.Add, "Attach pictures or a link", onClick = { if (enabled) open = true }, size = 48.dp)
    if (open) {
        val c = Bt.colors
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.surface,
            contentColor = c.ink,
        ) {
            AttachChoices(
                enabled = true, onPictures = { open = false; onPictures() }, onLink = { open = false; onLink() },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            )
        }
    }
}

/**
 * Pictures or Link, each with a line on what it takes: in the + sheet, and at the start of an empty chat (owner, 18 Nov
 * 2026: "start with the choices so people know that they can start from something").
 */
@Composable
private fun AttachChoices(enabled: Boolean, onPictures: () -> Unit, onLink: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        AttachChoice(BtIcons.Photo, "Pictures", "Screenshots, guide pages, maps or tickets. Up to ${PictureReading.MAX_PICTURES}.", enabled, onPictures)
        BtDivider()
        AttachChoice(BtIcons.Link, "Link", "A Google Maps place or list, a web page, a blog or a Google Doc.", enabled, onLink)
    }
}

@Composable
private fun AttachChoice(icon: ImageVector, title: String, line: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Bt.colors.chip), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = Bt.colors.ink)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Bt.text.item)
            Text(line, style = Bt.text.sub)
        }
    }
}

/** What waits to go with the next message, above the box: the pictures small, the links by domain, each with its ✕. */
@Composable
private fun AttachmentStrip(attachments: Attachments, enabled: Boolean, onRemovePicture: (String) -> Unit, onRemoveLink: (String) -> Unit) {
    if (attachments.isEmpty) return
    val c = Bt.colors
    val thumb = RoundedCornerShape(8.dp)
    LazyRow(
        Modifier.fillMaxWidth().background(c.bg).padding(top = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(attachments.pictures, key = { it }) { picture ->
            Box(Modifier.size(60.dp)) {
                AsyncImage(
                    model = picture, contentDescription = "Attached picture", contentScale = ContentScale.Crop,
                    modifier = Modifier.align(Alignment.BottomStart).size(52.dp).clip(thumb).border(1.dp, c.line, thumb),
                )
                RemoveBadge("Remove picture", enabled, Modifier.align(Alignment.TopEnd)) { onRemovePicture(picture) }
            }
        }
        items(attachments.links, key = { it }) { link ->
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).background(c.chip).padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(BtIcons.Link, null, Modifier.size(16.dp), tint = c.ink)
                Text(LinkUrls.domain(link), Modifier.widthIn(max = 170.dp), style = Bt.text.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(
                    Modifier.size(36.dp).clickable(enabled = enabled, role = Role.Button, onClickLabel = "Remove link") { onRemoveLink(link) },
                    contentAlignment = Alignment.Center,
                ) { Icon(BtIcons.Close, "Remove link", Modifier.size(14.dp), tint = c.muted) }
            }
        }
    }
}

@Composable
private fun RemoveBadge(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.size(28.dp).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.TopEnd) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Bt.colors.ink), contentAlignment = Alignment.Center) {
            Icon(BtIcons.Close, label, Modifier.size(12.dp), tint = Bt.colors.bg)
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, onQuestions: ((TravelBrief) -> Unit)?, onCopy: (String) -> Unit) {
    val c = Bt.colors
    val shape = RoundedCornerShape(Bt.tokens.radius)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromTraveller) Arrangement.End else Arrangement.Start) {
        when {
            m.fromTraveller -> Column(
                Modifier.widthIn(max = 300.dp).clip(shape).background(c.chip).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // The words typed (what was attached went to the planner too); a long paste shows its start.
                val words = m.shown ?: m.text
                if (words.isNotBlank()) Text(if (words.length > 600) words.take(600).trimEnd() + "…" else words, style = Bt.text.body)
                if (m.pictures > 0 || m.domains.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (m.pictures > 0) SentTag(BtIcons.Photo, if (m.pictures == 1) "1 picture" else "${m.pictures} pictures")
                        m.domains.forEach { SentTag(BtIcons.Link, it) }
                    }
                }
            }
            m.note -> Text(m.text, Modifier.padding(horizontal = 4.dp), style = Bt.text.fine, color = c.muted)
            else -> Column(
                Modifier.widthIn(max = 320.dp).clip(shape).background(c.surface).border(1.dp, c.line, shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Eyebrow("Planner", accent = true)
                Text(m.text, style = Bt.text.body)
                // A closed sheet opens again from here until it is answered.
                if (m.brief != null && onQuestions != null) {
                    BtGhostButton("Answer the questions", { onQuestions(m.brief) }, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                m.copyLink?.let { link -> SiteOffer(link) { onCopy(link) } }
                ReportAnswer(listOfNotNull(m.text, m.raw).joinToString("\n\n"), m.model)
            }
        }
    }
}

/** Under a sent message: what went with it ("3 pictures", a link's domain). */
@Composable
private fun SentTag(icon: ImageVector, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Bt.colors.surface.copy(alpha = 0.7f)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = Bt.colors.ink)
        Text(text, style = Bt.text.fine, color = Bt.colors.ink, maxLines = 1)
    }
}

/** The changes by kind: the trip, saved items changed, new items by day, ideas, removals; each with a tick. */
@Composable
private fun ProposalView(proposal: Proposal, isNewTrip: Boolean, onToggle: (String) -> Unit) {
    val layout = proposal.layout
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (isNewTrip) "New trip" else "Changes to the trip", style = Bt.text.h2)
        if (layout.summary.isNotBlank()) Text(layout.summary, style = Bt.text.body)
        if (isNewTrip) {
            Text(layout.title, style = Bt.text.item)
            val s = layout.start
            val e = layout.end
            if (s != null && e != null) Text(Dates.range(s, e), style = Bt.text.sub)
            if (layout.route.isNotBlank()) Text(layout.route, style = Bt.text.sub)
        } else if (layout.datesFromDraft) {
            val s = layout.start
            val e = layout.end
            if (s != null && e != null) Text("Dates: ${Dates.range(s, e)}", style = Bt.text.sub)
        }
        if (layout.all.any { it.item.lat != null && it.item.lng != null }) {
            Text("Map pins come from your notes and a map search. Check them in each place.", style = Bt.text.fine)
        }
    }
    layout.tripChange?.let { TripChangeRows(it, proposal.unticked, onToggle) }
    if (layout.dayNames.isNotEmpty()) {
        DayHeader("Day names", null)
        layout.dayNames.forEach { n ->
            TickRow(n.key, n.key !in proposal.unticked, onToggle, n.number?.let { "Day $it" } ?: Dates.dayMonth(n.day), n.before ?: "No name", n.name ?: "No name")
        }
    }
    if (layout.updates.isNotEmpty()) {
        DayHeader("Changes", null)
        layout.updates.forEach { update -> UpdateRow(update, ticked = update.key !in proposal.unticked, onToggle = onToggle) }
    }
    layout.days.forEach { day ->
        DayHeader(day.dayNumber?.let { "Day $it" } ?: Dates.weekday(day.date), Dates.dayLong(day.date))
        day.items.forEach { PreviewRow(it, ticked = it.key !in proposal.unticked, onToggle) }
    }
    if (layout.ideas.isNotEmpty()) {
        DayHeader("Ideas", null)
        layout.ideas.forEach { PreviewRow(it, ticked = it.key !in proposal.unticked, onToggle) }
    }
    if (layout.removals.isNotEmpty()) {
        DayHeader("Remove", null)
        layout.removals.forEach { RemovalRow(it, ticked = it.key !in proposal.unticked, onToggle) }
    }
    if (layout.reviewableCount == 0) Text("Nothing new for the trip.", Modifier.padding(16.dp), style = Bt.text.sub)
}

@Composable
private fun TripChangeRows(change: TripChange, unticked: Set<String>, onToggle: (String) -> Unit) {
    DayHeader("The trip", null)
    change.title?.let { TickRow(TripChange.TITLE, TripChange.TITLE !in unticked, onToggle, "Name", change.before.title, it) }
    if (change.datesChanged) {
        val b = change.before
        val old = if (b.startDate != null && b.endDate != null) Dates.range(b.startDate!!, b.endDate!!) else "No dates"
        val new = Dates.range(change.start!!, change.end!!) + if (change.shiftDays != 0L) " · the plan moves with them" else ""
        TickRow(TripChange.DATES, TripChange.DATES !in unticked, onToggle, "Dates", old, new)
    }
    change.route?.let { TickRow(TripChange.ROUTE, TripChange.ROUTE !in unticked, onToggle, "Route", change.before.subtitle.ifBlank { "None" }, it) }
}

@Composable
private fun TickRow(key: String, ticked: Boolean, onToggle: (String) -> Unit, label: String, old: String, new: String) {
    Row(
        Modifier.fillMaxWidth().toggleable(ticked, role = Role.Checkbox) { onToggle(key) }.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Checkbox(ticked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Bt.colors.primary, checkmarkColor = Bt.colors.onPrimary))
        }
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Eyebrow(label)
            Text(old, style = Bt.text.sub)
            Text(new, style = Bt.text.item, color = Bt.colors.accentInk)
        }
    }
}

@Composable
private fun RemovalRow(removal: PlacedRemoval, ticked: Boolean, onToggle: (String) -> Unit) {
    val item = removal.item
    Row(
        Modifier.fillMaxWidth().toggleable(ticked, role = Role.Checkbox) { onToggle(removal.key) }.padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Checkbox(ticked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Bt.colors.danger, checkmarkColor = Bt.colors.onPrimary))
        }
        Column(Modifier.weight(1f).padding(top = 11.dp, bottom = 6.dp)) {
            Text(item.title, style = Bt.text.item, color = if (ticked) Bt.colors.danger else Bt.colors.muted)
            Text(listOfNotNull(item.day?.let(Dates::dayLong), PlanKind.of(item.kind).label).joinToString(" · "), style = Bt.text.sub)
        }
    }
}

@Composable
private fun UpdateRow(update: PlacedUpdate, ticked: Boolean, onToggle: (String) -> Unit) {
    val before = update.before
    val after = update.after
    fun clock(value: Int?): String = value?.let { "%02d:%02d".format(java.util.Locale.ROOT, it / 60, it % 60) } ?: "Not set"
    val changes = remember(update) {
        buildList {
            if (before.title != after.title) add(Triple("Title", before.title, after.title))
            if (before.day != after.day) add(Triple("Date", before.day?.let(Dates::dayLong) ?: "Idea", after.day?.let(Dates::dayLong) ?: "Idea"))
            if (before.timeMinutes != after.timeMinutes) add(Triple("Time", clock(before.timeMinutes), clock(after.timeMinutes)))
            fun end(i: PlanItem) =
                if (!PlanSpan.hasEnd(i)) "Not set" else listOfNotNull(PlanSpan.endDate(i)?.let(Dates::dayLong), i.endMinutes?.let { clock(it) }).joinToString(" · ")
            val endBefore = end(before)
            val endAfter = end(after)
            if (endBefore != endAfter) add(Triple("Ends", endBefore, endAfter))
            if (before.kind != after.kind) add(Triple("Kind", PlanKind.of(before.kind).label, PlanKind.of(after.kind).label))
            if (before.note != after.note) add(Triple("Note", before.note.ifBlank { "No note" }, after.note.ifBlank { "No note" }))
            if (before.placeName != after.placeName) add(Triple("Place", before.placeName.ifBlank { "Not set" }, after.placeName.ifBlank { "Not set" }))
            if (before.lat != after.lat || before.lng != after.lng) add(Triple("Map pin", if (before.lat == null) "Not set" else "%.4f, %.4f".format(before.lat, before.lng), if (after.lat == null) "Not set" else "%.4f, %.4f".format(after.lat, after.lng)))
            if (before.url != after.url) add(Triple("Website", before.url ?: "None", after.url ?: "None"))
            if (before.mapsUrl != after.mapsUrl) add(Triple("Maps link", before.mapsUrl ?: "None", after.mapsUrl ?: "None"))
            update.travel?.let { add(Triple("Getting there", "As before", it.label)) }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (update.changed) Modifier.toggleable(ticked, role = Role.Checkbox) { onToggle(update.key) } else Modifier), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(ticked && update.changed, onCheckedChange = null, enabled = update.changed,
                colors = CheckboxDefaults.colors(checkedColor = Bt.colors.primary, checkmarkColor = Bt.colors.onPrimary))
            Text(before.title, Modifier.weight(1f), style = Bt.text.item)
        }
        if (!update.changed) Text("Already up to date", style = Bt.text.fine)
        changes.forEach { (label, old, new) ->
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Eyebrow(label)
                Text(old, style = Bt.text.sub)
                Text(new, style = Bt.text.body, color = Bt.colors.accentInk)
            }
        }
        BtDivider()
    }
}

@Composable
private fun DayHeader(title: String, date: String?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, Modifier.weight(1f), style = Bt.text.item)
        if (date != null) Text(date, style = Bt.text.mono)
    }
}

@Composable
private fun PreviewRow(placed: PlacedItem, ticked: Boolean, onToggle: (String) -> Unit) {
    val c = Bt.colors
    val item = placed.item
    val dim = placed.duplicate || !ticked
    Row(
        Modifier.fillMaxWidth()
            .then(if (placed.duplicate) Modifier else Modifier.toggleable(ticked, role = Role.Checkbox) { onToggle(placed.key) })
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (!placed.duplicate) {
                Checkbox(
                    checked = ticked, onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = c.primary, uncheckedColor = c.muted, checkmarkColor = c.onPrimary),
                )
            }
        }
        Column(Modifier.width(46.dp).padding(top = 13.dp)) {
            Text(item.time.orEmpty(), style = Bt.text.mono)
            // The end time under the start, as in the plan.
            item.endTime?.let { Text(it, style = Bt.text.mono, color = c.muted) }
        }
        Column(Modifier.weight(1f).padding(top = 11.dp, bottom = 6.dp)) {
            Text(item.title, style = Bt.text.item, color = if (dim) c.muted else c.ink)
            val travel = item.travel?.let { id -> Vehicle.entries.firstOrNull { it.id == id } }
            val details = listOfNotNull(
                PlanKind.of(item.kind).label, travel?.let { "by ${it.label.lowercase()}" },
                placed.note.ifBlank { item.placeName.orEmpty() }.ifBlank { null },
            ).joinToString(" · ")
            Text(details, style = Bt.text.sub)
            val where = listOfNotNull(
                if (item.lat != null) "On the map" else if (!item.placeName.isNullOrBlank()) "No pin found" else null,
                item.url?.let { if (MapsLinks.isMaps(it)) "Maps link" else "Website" },
            )
            if (where.isNotEmpty()) Text(where.joinToString(" · "), style = Bt.text.fine)
            if (placed.duplicate) Text("Already in the plan", style = Bt.text.fine)
        }
    }
}

@Composable
internal fun TextBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minHeight: Dp,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    enabled: Boolean = true,
) {
    val c = Bt.colors
    val shape = RoundedCornerShape(Bt.tokens.radius)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = Bt.text.body.copy(color = c.ink),
        cursorBrush = SolidColor(c.ink),
        maxLines = maxLines,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(c.surface).border(1.dp, c.line, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, style = Bt.text.body, color = c.muted)
                inner()
            }
        },
    )
}

@Composable
private fun Allowance(isPro: Boolean, freeLeft: Int, actions: AppActions) {
    if (isPro) return
    val text = when (freeLeft) {
        0 -> "Your free plans are used. Continue with Pro."
        1 -> "1 free plan left. Explore Pro."
        else -> "$freeLeft free plans left. Explore Pro."
    }
    Text(text, Modifier.clickable(role = Role.Button, onClick = actions::paywall), style = Bt.text.fine, color = Bt.colors.accentInk)
}

@Composable
private fun Working(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), color = Bt.colors.route, strokeWidth = 2.dp, trackColor = Color.Transparent)
        Text(label, style = Bt.text.sub)
    }
}

@Composable
internal fun ErrorLine(text: String) {
    Text(text, style = Bt.text.sub, color = Bt.colors.danger)
}

/** What a report of this answer quotes: the planner's calls as received, else what the review shows. */
private fun Proposal.reportText(): String = raw ?: buildString {
    append(layout.summary)
    layout.questions.forEach { append('\n').append(it) }
    layout.all.forEach { append('\n').append(it.item.title) }
}
