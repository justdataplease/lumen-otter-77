package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.justdataplease.boringtravel.ai.PictureReading
import com.justdataplease.boringtravel.ai.PlacedItem
import com.justdataplease.boringtravel.ai.PlacedRemoval
import com.justdataplease.boringtravel.ai.PlacedUpdate
import com.justdataplease.boringtravel.ai.TripChange
import com.justdataplease.boringtravel.ai.tools.TravelBrief
import com.justdataplease.boringtravel.compat.JavaFormat
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.site.SiteLinks
import com.justdataplease.boringtravel.ui.ai.Attachments
import com.justdataplease.boringtravel.ui.ai.TripQuestionsSheet
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
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates

/*
 * The AI planner (the Android app's ui/ai/PlannerScreen.kt): one box for anything (a pasted itinerary, booking emails,
 * "move the Prado to 11:30"); the planner makes the changes with its tools, the bar above the box offers Review and
 * Save, and the review opens in a sheet. Pictures come from the photo library (PHPicker), a link from Add a link (the
 * clipboard's link already in), dictation from the phone's recognizer.
 */

/** Where the planner opens from New trip's choices: the questions, the photo picker or Add a link (Android's PlannerStart). */
object PlannerStart {
    const val QUESTIONS = "questions"
    const val PICTURES = "pictures"
    const val LINK = "link"
}

@Composable
fun PlannerScreen(
    side: PlannerSide,
    tripId: String?,
    start: String?,
    dictationLanguage: String?,
    onBack: () -> Unit,
    onAiSettings: () -> Unit,
    onCopySite: (address: String, tripId: String?) -> Unit,
    onSaved: (tripId: String, summary: String) -> Unit,
    revision: Int = 0,
    onReport: (answer: String, model: String?) -> Unit,
    chatAtStart: PlannerChat? = null,
    attachAtStart: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    // The app keeps the chat while another screen covers this one (Me › AI, Copy from a link); previews bring their own.
    val chat = remember(side, tripId) { chatAtStart ?: PlannerChat(side, tripId, scope) }
    val own by side.ownState.collectAsState()
    // The trip may change under the chat (a save, another screen): read again on each change.
    LaunchedEffect(revision) { chat.load() }
    val kit = LocalMediaKit.current
    val paste = rememberPaste()
    val pickPictures = {
        kit.picker.pickPhotos(PictureReading.MAX_PICTURES) { picked -> chat.addPictures(picked.filter { !it.video }.map { it.path }) }
    }
    // Add a link: open with the clipboard's link already in, when it holds one (read only on this tap).
    var linkValue by rememberSaveable { mutableStateOf<String?>(null) }
    var linkError by rememberSaveable { mutableStateOf<String?>(null) }
    val askLink = {
        linkError = null
        linkValue = paste()?.let { Attachments.link(it) }.orEmpty()
    }
    linkValue?.let { value ->
        BtTextDialog(
            "Add a link", value,
            onSave = { typed -> if (chat.addLink(typed)) linkValue = null else linkError = "That isn't a link. Paste one beginning https://" },
            onDismiss = { linkValue = null },
            hint = "https://…", note = "A Google Maps place or list, a web page, a blog or a Google Doc.",
            max = 2048, confirm = "Add", error = linkError,
        )
    }
    // Opened from one of New trip's choices: that step comes up once.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(start) {
        if (start == null || started) return@LaunchedEffect
        started = true
        when (start) {
            PlannerStart.QUESTIONS -> chat.openQuestions()
            PlannerStart.PICTURES -> pickPictures()
            PlannerStart.LINK -> askLink()
        }
    }
    BtScreen {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
            BtTopBar("AI planner", onBack = onBack)
            Eyebrow(tripLine(chat), Modifier.padding(horizontal = 20.dp))
            own.usingLine()?.let { line ->
                Text(
                    line,
                    Modifier.padding(horizontal = 20.dp).clickable(role = Role.Button, onClick = onAiSettings).padding(vertical = 4.dp),
                    style = Bt.text.fine, color = Bt.colors.muted,
                )
            }
            Conversation(chat, own.active != null, dictationLanguage, onSaved, { onCopySite(it, chat.tripId) }, pickPictures, askLink, onReport, attachAtStart, Modifier.weight(1f))
        }
    }
    if (chat.reviewing) chat.proposal?.let { ReviewSheet(chat, it, onSaved, onReport) }
    chat.questions?.let { brief ->
        TripQuestionsSheet(brief, chat.error, sending = chat.busy, onDismiss = chat::closeQuestions, onSend = chat::sendAnswers)
    }
}

private fun tripLine(chat: PlannerChat): String {
    if (chat.isNewTrip) return "New trip"
    val t = chat.trip ?: return ""
    val s = t.startDate
    val e = t.endDate
    return if (s != null && e != null) "${t.title} · ${Dates.range(s, e)}" else t.title
}

@Composable
private fun Conversation(
    chat: PlannerChat, ownKey: Boolean, dictationLanguage: String?, onSaved: (String, String) -> Unit, onCopySite: (String) -> Unit,
    onPickPictures: () -> Unit, onAskLink: () -> Unit, onReport: (String, String?) -> Unit, attachAtStart: Boolean, modifier: Modifier,
) {
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val speech = LocalSpeech.current
    val dictation = remember(chat) { Dictation(speech, scope) }
    DisposableEffect(dictation) {
        dictation.onText = { text, final -> chat.onDictated(text, final, dictation::stop) }
        chat.onFreezeDictation = { dictation.release() }
        onDispose { dictation.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { dictation.stop() }
    val listening = dictation.state is Dictation.State.Listening
    fun startDictation() {
        chat.prepareDictation()
        dictation.start(dictationLanguage ?: speech.phoneLanguage())
    }
    val list = rememberLazyListState()
    // Keep the newest message (or the progress line, or the error) in view: intro + messages + busy + error.
    val itemCount = 1 + chat.messages.size + (if (chat.busy) 1 else 0) + (if (chat.error != null) 1 else 0)
    LaunchedEffect(itemCount) { list.animateScrollToItem(itemCount - 1) }
    val empty = chat.messages.isEmpty()
    Column(modifier.fillMaxWidth()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (empty) {
                        Text(if (chat.isNewTrip) "Make the whole trip with AI" else "Change the trip with AI", style = Bt.text.h2)
                        Text(
                            if (chat.isNewTrip) "Paste your itinerary, booking emails or notes, or just say where and when. The planner sets the dates, every day, the places on the map, times and links. You check it before anything is saved."
                            else "Paste your updated list, or say what to change: dates, times, places, a new stop, something to remove. You check every change before it is saved.",
                            style = Bt.text.sub,
                        )
                        // The attachments in plain sight before the first message; + offers them after.
                        Eyebrow(if (chat.isNewTrip) "Start from" else "Add from", Modifier.padding(top = 8.dp))
                        BtCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                            AttachChoices(enabled = !chat.busy && !chat.applying, onPictures = onPickPictures, onLink = onAskLink)
                        }
                        // The travel agent without typing: its questions straight away, then the plan.
                        if (chat.available && chat.planEmpty) {
                            BtGhostButton(
                                if (chat.isNewTrip) "Plan a trip for me" else "Plan the days for me", { chat.openQuestions() },
                                Modifier.fillMaxWidth().padding(top = 4.dp), icon = BtIcons.Sparkle, enabled = !chat.busy && chat.loaded,
                            )
                        }
                    }
                }
            }
            itemsIndexed(chat.messages) { _, m ->
                Bubble(m, onQuestions = if (!chat.busy && !chat.briefAnswered) chat::openQuestions else null, onCopy = onCopySite, onReport = onReport)
            }
            if (chat.busy) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Working(chat.step ?: "Reading your message")
                    BtGhostButton("Stop", chat::cancelPlan, Modifier.fillMaxWidth())
                }
            }
            chat.error?.let { e -> item { ErrorLine(e) } }
        }
        chat.proposal?.takeIf { !chat.busy }?.let { ReviewBar(chat, it, onSaved) }
        BtDivider()
        when (val state = dictation.state) {
            is Dictation.State.Listening -> Text("Listening · tap Stop, then check your words before sending.", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = Bt.text.fine)
            is Dictation.State.Failed -> Text(state.message, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = Bt.text.fine, color = Bt.colors.danger)
            else -> Unit
        }
        val free = !chat.busy && !chat.applying
        AttachmentStrip(chat.attachments, enabled = free, onRemovePicture = chat::removePicture, onRemoveLink = chat::removeLink)
        Row(
            Modifier.fillMaxWidth().background(Bt.colors.bg).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AttachButton(enabled = free, onPictures = onPickPictures, onLink = onAskLink, openAtStart = attachAtStart)
            TextBox(
                chat.input, chat::updateInput,
                when {
                    chat.proposal?.layout?.questions?.isNotEmpty() == true -> "Answer the questions above"
                    empty && chat.isNewTrip -> "Paste your trip, or say where and when"
                    empty -> "Paste your list, or say what to change"
                    else -> "Ask for a change"
                },
                minHeight = if (empty) 96.dp else 48.dp, maxLines = 6, modifier = Modifier.weight(1f),
                enabled = free,
            )
            BtIconButton(if (listening) BtIcons.Stop else BtIcons.Mic, if (listening) "Stop dictation" else "Dictate message", onClick = {
                when {
                    listening -> dictation.stop()
                    !free -> Unit
                    dictation.access() == MicAccess.Allowed -> startDictation()
                    dictation.access() == MicAccess.Refused -> chat.microphoneDenied()
                    else -> speech.askAccess { granted -> if (granted) startDictation() else chat.microphoneDenied() }
                }
            }, filled = listening, size = 48.dp)
            // [ownKey] is read so a key saved in Me › AI counts at once.
            val canSend = (ownKey || chat.available) && (chat.input.isNotBlank() || !chat.attachments.isEmpty) && free && !listening && chat.loaded
            BtIconButton(BtIcons.Send, "Send", onClick = { if (canSend) { focus.clearFocus(); chat.send() } }, filled = canSend, size = 48.dp)
        }
    }
}

/** The planner's hand-over to Copy from a link: the traveller's own blog or document, copied there as written. */
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
private fun ReviewBar(chat: PlannerChat, proposal: Proposal, onSaved: (String, String) -> Unit) {
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
        TripChoice(chat)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BtGhostButton("Review", { chat.reviewing = true }, Modifier.weight(1f), enabled = !chat.applying)
            BtButton(
                if (chat.applying) "Saving" else if (chat.isNewTrip) "Create trip" else "Save",
                onClick = { chat.apply(onSaved) }, modifier = Modifier.weight(1f),
                enabled = !chat.applying && count > 0 && proposal.ticked > 0 && layout.problems.isEmpty(),
            )
        }
    }
}

/** A chat that began as a new trip asks where its changes go: the new trip, or one the traveller has. */
@Composable
private fun TripChoice(chat: PlannerChat) {
    if (!chat.startedNew || chat.tripChoices.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("A new trip, or add to one you have?", style = Bt.text.sub)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            item(key = "new") { BtChip("New trip", chat.isNewTrip, { chat.useTrip(null) }, icon = BtIcons.Add) }
            items(chat.tripChoices, key = { it.id }) { t -> BtChip(t.title.ifBlank { "Untitled trip" }, chat.tripId == t.id, { chat.useTrip(t.id) }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewSheet(chat: PlannerChat, proposal: Proposal, onSaved: (String, String) -> Unit, onReport: (String, String?) -> Unit) {
    val c = Bt.colors
    ModalBottomSheet(
        onDismissRequest = { chat.reviewing = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ProposalView(proposal, isNewTrip = chat.isNewTrip, onToggle = chat::toggle)
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                proposal.layout.problems.forEach { ErrorLine(it) }
                chat.error?.let { ErrorLine(it) }
                Text("${proposal.ticked} of ${proposal.layout.reviewableCount} selected", style = Bt.text.fine)
                BtButton(
                    if (chat.applying) "Saving" else if (chat.isNewTrip) "Create trip" else "Save selected",
                    onClick = { chat.apply(onSaved) }, modifier = Modifier.fillMaxWidth(),
                    enabled = !chat.applying && proposal.ticked > 0 && proposal.layout.problems.isEmpty(),
                )
                BtGhostButton("Discard all", chat::discard, Modifier.fillMaxWidth(), enabled = !chat.applying)
                ReportAnswer(proposal.reportText(), proposal.model, onReport)
            }
        }
    }
}

/** + beside the box: a sheet with Pictures and Link, attached to the message being written. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachButton(enabled: Boolean, onPictures: () -> Unit, onLink: () -> Unit, openAtStart: Boolean = false) {
    var open by remember { mutableStateOf(openAtStart) }
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

/** Pictures or Link, each with a line on what it takes: in the + sheet, and at the start of an empty chat. */
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
                PickedPicture(picture, Modifier.align(Alignment.BottomStart).size(52.dp).clip(thumb).border(1.dp, c.line, thumb))
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

/** A picked picture (a file under the app's files), small and upright. */
@Composable
private fun PickedPicture(path: String, modifier: Modifier) {
    val kit = LocalMediaKit.current
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = kotlinx.coroutines.withContext(MediaWork) { runCatching { kit.files.read(path)?.let { Pictures.decode(it, 160) } }.getOrNull() }
    }
    Box(modifier.background(Bt.colors.chip)) {
        bitmap?.let { Image(it, "Attached picture", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
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
private fun Bubble(m: ChatMessage, onQuestions: ((TravelBrief) -> Unit)?, onCopy: (String) -> Unit, onReport: (String, String?) -> Unit) {
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
                ReportAnswer(listOfNotNull(m.text, m.raw).joinToString("\n\n"), m.model, onReport)
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
        val bs = b.startDate
        val be = b.endDate
        val old = if (bs != null && be != null) Dates.range(bs, be) else "No dates"
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
    fun clock(value: Int?): String = value?.let { JavaFormat.twoDigits(it / 60) + ":" + JavaFormat.twoDigits(it % 60) } ?: "Not set"
    // Android's "%.4f, %.4f".format(lat, lng): in the phone's language ("37,9838, 23,7275" in Greek).
    fun pin(lat: Double?, lng: Double?): String = if (lat == null) "Not set" else PhoneTexts.pin(lat, lng)
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
            if (before.lat != after.lat || before.lng != after.lng) add(Triple("Map pin", pin(before.lat, before.lng), pin(after.lat, after.lng)))
            if (before.url != after.url) add(Triple("Website", before.url ?: "None", after.url ?: "None"))
            if (before.mapsUrl != after.mapsUrl) add(Triple("Maps link", before.mapsUrl ?: "None", after.mapsUrl ?: "None"))
            update.travel?.let { add(Triple("Getting there", "As before", it.label)) }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (update.changed) Modifier.toggleable(ticked, role = Role.Checkbox) { onToggle(update.key) } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                ticked && update.changed, onCheckedChange = null, enabled = update.changed,
                colors = CheckboxDefaults.colors(checkedColor = Bt.colors.primary, checkmarkColor = Bt.colors.onPrimary),
            )
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

/** The planner's text box: the message, or a question's answer in the trip questions. */
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

/** "Report this answer" under an AI planner answer; [model] wrote it. */
@Composable
internal fun ReportAnswer(answer: String, model: String?, onReport: (String, String?) -> Unit, modifier: Modifier = Modifier) {
    Text(
        "Report this answer",
        modifier.heightIn(min = 40.dp).clickable(role = Role.Button) { onReport(answer, model) }.padding(vertical = 10.dp),
        style = Bt.text.fine,
        color = Bt.colors.muted,
    )
}

/** What a report of this answer quotes: the planner's calls as received, else what the review shows. */
private fun Proposal.reportText(): String = raw ?: buildString {
    append(layout.summary)
    layout.questions.forEach { append('\n').append(it) }
    layout.all.forEach { append('\n').append(it.item.title) }
}
