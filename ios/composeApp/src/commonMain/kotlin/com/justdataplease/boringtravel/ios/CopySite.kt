package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fleeksoft.ksoup.Ksoup
import com.justdataplease.boringtravel.compat.ZoneId
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.site.ExistingTrip
import com.justdataplease.boringtravel.site.Organisation
import com.justdataplease.boringtravel.site.ProposedTrip
import com.justdataplease.boringtravel.site.SiteBlock
import com.justdataplease.boringtravel.site.SiteImportState
import com.justdataplease.boringtravel.site.SiteImporter
import com.justdataplease.boringtravel.site.SiteLimits
import com.justdataplease.boringtravel.site.SitePost
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtSwitchRow
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates

/*
 * Copy from a link (the Android app's ui/site/CopySiteScreen.kt, written again over this app's frame): an address (a
 * blog, a site, a Google Doc, a Google Maps list), then the posts found and the trips they make (each can be unticked,
 * renamed and unfolded; all in one trip; places on the map and in the plan; a link to one post reads that post, with the
 * whole blog one tap away), then the copy with its progress, then what was copied. The work is the app's
 * ([SiteImporter], the Android app's own, kept by the app as Android's AppGraph keeps it), so this screen can be left
 * and opened again. Android's texts, word for word.
 */

/**
 * Copy from a link (Add from a link on a trip, Me › Import trips from a link, the planner's offer): [importer] is the
 * app's; [address] comes from the planner's offer; [tripId], the trip this was opened from, comes chosen as where
 * everything goes. [onOpenTrip] opens a trip the copy made or added to (its Journal when it got notes, else its Plan);
 * [onDone] goes back to Trips. [openAtStart]: the proposal's trip shown unfolded at the start (previews).
 */
@Composable
fun CopySiteScreen(
    importer: SiteImporter,
    book: TripBook,
    revision: Int,
    address: String? = null,
    tripId: String? = null,
    onBack: () -> Unit,
    onOpenTrip: (tripId: String, notes: Boolean) -> Unit,
    onDone: () -> Unit,
    openAtStart: Int? = null,
) {
    val state by importer.state.collectAsState()
    var field by rememberSaveable { mutableStateOf(address ?: (state as? SiteImportState.Failed)?.address.orEmpty()) }
    // Once per visit: coming back from a copied trip shows what was copied, not a new read.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        started = true
        // An address from the planner starts a new copy, unless one is running or on offer for that address.
        val current = importer.state.value
        if (address != null && !importer.busy && (current as? SiteImportState.Ready)?.address != address) {
            importer.reset()
            // A Google Maps link is read at once (a list is a second's work, and one place is said plainly); a blog
            // waits for Read, since reading one can take minutes.
            if (MapsLinks.isMaps(address)) importer.read(address, tripId)
        }
    }
    BtScreen {
        Column(Modifier.fillMaxSize().imePadding()) {
            BtTopBar("Copy from a link", onBack = onBack)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (val s = state) {
                    SiteImportState.Idle -> AddressForm(field, { field = it }, error = null, onRead = { importer.read(field, tripId) })
                    is SiteImportState.Failed -> AddressForm(field, { field = it }, error = s.message, onRead = { importer.read(field, tripId) })
                    is SiteImportState.Reading -> Reading(s, importer::stopReading, importer::cancel)
                    is SiteImportState.Ready -> Proposal(s, importer, book, revision, onStartOver = importer::reset, openAtStart = openAtStart)
                    is SiteImportState.Copying -> Copying(s, importer::cancel)
                    // Places without notes are in the plan; notes open on the journal.
                    is SiteImportState.Done -> Done(s, onOpen = { onOpenTrip(it, s.notes > 0) }, onDone = {
                        importer.reset()
                        onDone()
                    })
                }
            }
        }
    }
}

@Composable
private fun AddressForm(value: String, onChange: (String) -> Unit, error: String?, onRead: () -> Unit) {
    val focus = LocalFocusManager.current
    val paste = rememberPaste()
    val read = {
        if (value.isNotBlank()) {
            focus.clearFocus()
            onRead()
        }
    }
    Text(
        "Your blog's posts or a Google Doc become notes with their words and pictures as they are, sorted into trips. " +
            "Tumblr, WordPress, Blogger, Substack, Medium and other sites, or a document shared with “Anyone with the link”. " +
            "A link to one post copies only that post.",
        style = Bt.text.sub,
    )
    Text("A Google Maps list (open the list, Share) becomes places in a plan: a new trip, or one you have.", style = Bt.text.sub)
    OutlinedTextField(
        value, { onChange(it.take(SiteLimits.MAX_ADDRESS)) }, Modifier.fillMaxWidth(),
        label = { Text("Address") }, placeholder = { Text("myblog.tumblr.com") },
        singleLine = true, textStyle = Bt.text.body,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { read() }),
        isError = error != null,
        trailingIcon = if (value.isEmpty()) null else {
            { IconButton(onClick = { onChange("") }) { Icon(BtIcons.Close, "Clear the address", tint = Bt.colors.muted) } }
        },
    )
    error?.let { Text(it, style = Bt.text.sub, color = Bt.colors.danger) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BtGhostButton("Paste", onClick = { paste()?.let { onChange(it.trim().take(SiteLimits.MAX_ADDRESS)) } }, modifier = Modifier.weight(1f))
        BtButton("Read", onClick = read, modifier = Modifier.weight(1f), enabled = value.isNotBlank())
    }
    Text("Only public pages are read. Nothing is copied until you choose.", style = Bt.text.fine)
}

@Composable
private fun Reading(s: SiteImportState.Reading, onStop: () -> Unit, onCancel: () -> Unit) {
    Working(
        when {
            s.organising -> "Sorting ${count(s.found, "post", "posts")} into trips…"
            s.found == 0 -> "Reading…"
            else -> "Reading… ${count(s.found, "post", "posts")}"
        },
    )
    Text(s.address, style = Bt.text.fine, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (!s.organising && s.found > 0) BtGhostButton("Stop and use what's found", onClick = onStop, modifier = Modifier.fillMaxWidth())
    BtGhostButton("Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Proposal(s: SiteImportState.Ready, importer: SiteImporter, book: TripBook, revision: Int, onStartOver: () -> Unit, openAtStart: Int? = null) {
    if (s.listOnly) {
        ListProposal(s, importer, book, revision, onStartOver)
        return
    }
    if (s.notes == 0) {
        Text(
            if (s.whole != null && s.alreadyCopied == 1) "This post is already copied." else "All ${count(s.alreadyCopied, "post", "posts")} from ${s.host} are already copied.",
            style = Bt.text.title,
        )
        if (s.reblogs > 0) Text("${count(s.reblogs, "reblog", "reblogs")} left out", style = Bt.text.sub)
        if (s.whole != null) BtGhostButton("Copy the whole blog", onClick = importer::readWhole, modifier = Modifier.fillMaxWidth())
        BtGhostButton("Start over", onClick = onStartOver, modifier = Modifier.fillMaxWidth())
        return
    }
    var renaming by rememberSaveable { mutableStateOf<Int?>(null) }
    var open by rememberSaveable { mutableStateOf(openAtStart) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${count(s.notes, "note", "notes")}, ${count(s.pictures, "picture", "pictures")} from ${s.host}", style = Bt.text.title)
        if (s.alreadyCopied > 0) Text("${s.alreadyCopied} already copied", style = Bt.text.sub)
        // A link to one post reads that post only; the blog around it is one tap away.
        if (s.whole != null) Text("Only the post at this link.", style = Bt.text.sub)
        if (s.reblogs > 0) Text("${count(s.reblogs, "reblog", "reblogs")} left out: they are other people's posts.", style = Bt.text.sub)
        when {
            s.stopped -> Text("The reading stopped early. Copy the same address again later for the rest.", style = Bt.text.sub)
            s.more -> Text("That is as much as one copy takes. Copy the same address again afterwards for the rest.", style = Bt.text.sub)
        }
        // The AI was asked and gave no answer: said plainly, since without it the notes get no places and the plan no
        // items (owner, 15 Nov 2026: copying "should try to find the place").
        if (s.aiSilent) Text("The AI didn't answer, so no places were found.", style = Bt.text.sub)
        // One trip made without AI (a document without dates): nothing was grouped, so nothing to say.
        else if (s.by != Organisation.Dates || s.groups.size > 1) {
            Text(
                when (s.by) {
                    Organisation.Ai -> "Organised by AI"
                    Organisation.Dates -> "Grouped by dates"
                    Organisation.Mixed -> "Organised by AI, some grouped by dates"
                },
                style = Bt.text.sub,
            )
        }
    }
    if (s.aiSilent) BtGhostButton("Ask the AI again", onClick = importer::organiseAgain, modifier = Modifier.fillMaxWidth())
    Column {
        if (s.into == null && (s.groups.size > 1 || s.oneTrip)) BtSwitchRow("One trip for everything", "All the notes in one trip named ${s.site}.", s.oneTrip, importer::setOneTrip)
        // Someone else's guide: its places are wanted, not its words.
        if (s.hasPlaces) BtSwitchRow("Notes and pictures", "Off: only the places go into the plan, as ideas.", s.withNotes, importer::setWithNotes)
        if (s.hasPlaces && s.withNotes) BtSwitchRow("Add places", "The places the AI found: a pin on each note and an item in the trip's plan.", s.addPlaces, importer::setAddPlaces)
    }
    Destination(s, importer, book, revision)
    BtCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 6.dp)) {
        s.trips.forEachIndexed { i, trip ->
            if (i > 0) BtDivider(Modifier.padding(horizontal = 16.dp))
            TripRow(
                trip, open == i, showPlaces = s.addPlaces, into = s.into,
                onToggle = { importer.toggle(i) }, onOpen = { open = if (open == i) null else i }, onRename = { renaming = i },
            )
        }
    }
    if (s.withNotes) Text("Copy only what is yours: your own words and pictures.", style = Bt.text.fine)
    BtButton(
        when {
            !s.withNotes -> "Add ${count(s.selectedPlaces, "place", "places")}"
            s.into != null -> "Add ${count(s.selectedNotes, "note", "notes")}"
            else -> "Copy ${count(s.selectedNotes, "note", "notes")}"
        },
        onClick = importer::copy, modifier = Modifier.fillMaxWidth(), enabled = s.canCopy,
    )
    if (s.whole != null) BtGhostButton("Copy the whole blog instead", onClick = importer::readWhole, modifier = Modifier.fillMaxWidth())
    BtGhostButton("Start over", onClick = onStartOver, modifier = Modifier.fillMaxWidth())
    renaming?.let { i ->
        s.trips.getOrNull(i)?.let { trip ->
            RenameDialog(trip.title, onDismiss = { renaming = null }, onSave = {
                importer.rename(i, it)
                renaming = null
            })
        }
    }
}

/**
 * A Google Maps list: its places (unfolded on a tap), the trip they go into, and Add. A new trip takes the list's
 * name, which can be changed.
 */
@Composable
private fun ListProposal(s: SiteImportState.Ready, importer: SiteImporter, book: TripBook, revision: Int, onStartOver: () -> Unit) {
    val trip = s.trips.first()
    var renaming by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${count(s.pins, "place", "places")} from a Google Maps list", style = Bt.text.title)
        if (s.more) Text("The list is longer: these are its first ${s.pins}.", style = Bt.text.sub)
        Text("Each becomes an idea in the plan, with its pin and its Google Maps link.", style = Bt.text.sub)
    }
    Destination(s, importer, book, revision)
    BtCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = if (open) "Hide the places" else "Show the places") { open = !open }
                    .semantics { stateDescription = if (open) "Places shown" else "Places hidden" }.padding(top = 12.dp, bottom = 8.dp, end = 4.dp),
            ) {
                Text(s.into?.let { "Adds ${count(s.pins, "place", "places")} to ${it.title}" } ?: trip.title, style = Bt.text.item)
                Text(
                    trip.placeNames.take(MAX_PLACES_SHOWN).joinToString(" · ") + if (trip.placeNames.size > MAX_PLACES_SHOWN) " …" else "",
                    style = Bt.text.fine, color = Bt.colors.accentInk,
                )
            }
            if (s.into == null) IconButton(onClick = { renaming = true }) { Icon(BtIcons.Edit, "Rename ${trip.title}", Modifier.size(20.dp), tint = Bt.colors.muted) }
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                trip.pins.take(MAX_NOTES_SHOWN).forEach { pin ->
                    Column {
                        Text(pin.name, style = Bt.text.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val line = listOf(pin.note, pin.address).filter { it.isNotBlank() }.joinToString(" · ")
                        if (line.isNotEmpty()) Text(line, style = Bt.text.fine, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (trip.pins.size > MAX_NOTES_SHOWN) Text("and ${trip.pins.size - MAX_NOTES_SHOWN} more", style = Bt.text.fine)
            }
        }
    }
    BtButton("Add ${count(s.pins, "place", "places")}", onClick = importer::copy, modifier = Modifier.fillMaxWidth(), enabled = s.canCopy)
    BtGhostButton("Start over", onClick = onStartOver, modifier = Modifier.fillMaxWidth())
    if (renaming) RenameDialog(trip.title, onDismiss = { renaming = false }, onSave = {
        importer.rename(0, it)
        renaming = false
    })
}

/**
 * Where everything ticked goes (owner, 17 Nov 2026: "create new trip or append"): new trips as proposed, or one of
 * the traveller's trips (their own; the examples only when they have none), the newest first.
 */
@Composable
private fun Destination(s: SiteImportState.Ready, importer: SiteImporter, book: TripBook, revision: Int) {
    val all = remember(book, revision) { book.snapshot.trips.filter { it.deletedAt == null } }
    // The chosen trip first (the one this was opened from may be an example, or one of yours hidden by the demo), then the rest.
    val trips = remember(all, s.into?.id) {
        val chosen = s.into?.id?.let { id -> all.firstOrNull { it.id == id } }
        val own = all.filterNot { it.isDemo }
        val offered = if (own.isEmpty()) all.filter { it.isDemo } else own
        listOfNotNull(chosen) + offered.filter { it.id != chosen?.id }.sortedByDescending(Trip::createdAt)
    }
    // A trip chosen and then deleted elsewhere: back to new trips.
    LaunchedEffect(all, s.into?.id) {
        val into = s.into ?: return@LaunchedEffect
        if (all.none { it.id == into.id }) importer.setInto(null)
    }
    if (trips.isEmpty()) return
    val many = !s.listOnly && s.trips.count { it.joins == null } > 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow("Add to")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            item(key = "new") { BtChip(if (many) "New trips" else "New trip", s.into == null, { importer.setInto(null) }, icon = BtIcons.Add) }
            items(trips, key = { it.id }) { t ->
                BtChip(t.title.ifBlank { "Untitled trip" }, s.into?.id == t.id, { importer.setInto(ExistingTrip(t.id, t.title.ifBlank { "Untitled trip" }, t.startDate, t.endDate, emptyList())) })
            }
        }
    }
}

@Composable
private fun TripRow(
    trip: ProposedTrip, open: Boolean, showPlaces: Boolean, into: ExistingTrip?, onToggle: () -> Unit, onOpen: () -> Unit, onRename: () -> Unit,
) {
    val c = Bt.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(48.dp).clickable(role = Role.Checkbox, onClick = onToggle).semantics { contentDescription = trip.title },
                contentAlignment = Alignment.Center) {
                Checkbox(trip.selected, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = c.primary, checkmarkColor = c.onPrimary))
            }
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = if (open) "Hide its notes" else "Show its notes", onClick = onOpen)
                    .semantics { stateDescription = if (open) "Notes shown" else "Notes hidden" }.padding(top = 12.dp, bottom = 8.dp, end = 4.dp),
            ) {
                // Where its notes go: the trip the traveller chose, else the trip an earlier copy made, else a new one.
                val joins = into ?: trip.joins
                if (joins != null) {
                    Text("Adds ${count(trip.posts.size, "note", "notes")} to ${joins.title}", style = Bt.text.item)
                } else {
                    Text(trip.title, style = Bt.text.item)
                }
                val dates = trip.start?.let { st -> trip.end?.let { e -> Dates.range(st, e) } } ?: "No dates"
                Text(
                    if (joins != null) "$dates · ${count(trip.pictures, "picture", "pictures")}"
                    else "$dates · ${count(trip.posts.size, "note", "notes")} · ${count(trip.pictures, "picture", "pictures")}",
                    style = Bt.text.sub,
                )
                if (showPlaces && trip.placeNames.isNotEmpty()) {
                    Text(trip.placeNames.take(MAX_PLACES_SHOWN).joinToString(" · ") + if (trip.placeNames.size > MAX_PLACES_SHOWN) " …" else "",
                        style = Bt.text.fine, color = c.accentInk)
                }
            }
            if (into == null && trip.joins == null) {
                IconButton(onClick = onRename) { Icon(BtIcons.Edit, "Rename ${trip.title}", Modifier.size(20.dp), tint = c.muted) }
            }
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                trip.posts.take(MAX_NOTES_SHOWN).forEach { NoteLine(it) }
                if (trip.posts.size > MAX_NOTES_SHOWN) Text("and ${trip.posts.size - MAX_NOTES_SHOWN} more", style = Bt.text.fine)
            }
        }
    }
}

/** One note of an unfolded trip: its title or first words, its day, its pictures. */
@Composable
private fun NoteLine(post: SitePost) {
    val name = post.title.ifBlank { firstWords(post) }.ifBlank { "Pictures" }
    val day = post.at?.atZone(ZoneId.systemDefault())?.toLocalDate()?.let(Dates::dayMonthYear) ?: "No date"
    val pictures = post.pictures.size
    Column {
        Text(name, style = Bt.text.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (pictures > 0) "$day · ${count(pictures, "picture", "pictures")}" else day, style = Bt.text.fine)
    }
}

private fun firstWords(post: SitePost): String = post.blocks.firstNotNullOfOrNull { b ->
    when (b) {
        is SiteBlock.Text -> Ksoup.parse(b.html).text()
        is SiteBlock.Heading -> b.text
        is SiteBlock.Quote -> Ksoup.parse(b.html).text()
        else -> null
    }?.takeIf { it.isNotBlank() }
}?.take(FIRST_WORDS).orEmpty()

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = Bt.colors
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Trip name", style = Bt.text.title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(SiteImporter.MAX_TITLE) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = Bt.text.body,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line, cursorColor = c.ink),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save", style = Bt.text.button, color = if (text.isNotBlank()) c.ink else c.muted) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = Bt.text.button, color = c.ink) } },
    )
}

@Composable
private fun Copying(s: SiteImportState.Copying, onCancel: () -> Unit) {
    Working(if (s.places) "Adding places… ${s.done} of ${s.total}" else "Copying… ${s.done} of ${count(s.total, "note", "notes")}")
    LinearProgressIndicator(
        progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth(),
        color = Bt.colors.primary, trackColor = Bt.colors.line,
    )
    Text(
        if (s.places) "You can leave this screen: it goes on." else "${count(s.pictures, "picture", "pictures")} so far. You can leave this screen: the copy goes on.",
        style = Bt.text.sub,
    )
    BtGhostButton("Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Done(s: SiteImportState.Done, onOpen: (String) -> Unit, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (s.stopped) "Stopped" else if (s.placesOnly) "Added" else "Copied", style = Bt.text.title)
        if (s.placesOnly) {
            Text(if (s.places == 0) "No new places for the plan." else "${count(s.places, "place", "places")} added to the plan, as ideas.", style = Bt.text.body)
        } else {
            Text(
                "${count(s.notes, "note", "notes")} and ${count(s.pictures, "picture", "pictures")} into ${count(s.trips.size, "trip", "trips")}.",
                style = Bt.text.body,
            )
            if (s.places > 0) Text("${count(s.places, "place", "places")} added to the plan, with ${if (s.places == 1) "its" else "their"} notes.", style = Bt.text.sub)
        }
        if (s.planned > 0) Text("${count(s.planned, "place", "places")} ${if (s.planned == 1) "was" else "were"} in the plan already.", style = Bt.text.sub)
        if (s.failedPictures > 0) Text("${count(s.failedPictures, "picture", "pictures")} couldn't be copied.", style = Bt.text.sub)
        if (s.failedNotes > 0) Text("${count(s.failedNotes, "note", "notes")} couldn't be copied.", style = Bt.text.sub)
        if (s.skipped > 0) Text("${s.skipped} already copied.", style = Bt.text.sub)
        if (s.stopped) Text("Copy the same address again to continue.", style = Bt.text.sub)
    }
    if (s.trips.isNotEmpty()) {
        BtCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 6.dp)) {
            s.trips.forEachIndexed { i, trip ->
                if (i > 0) BtDivider(Modifier.padding(horizontal = 16.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onOpen(trip.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(trip.title, style = Bt.text.item)
                        val notes = if (trip.joined) "${count(trip.notes, "note", "notes")} added" else count(trip.notes, "note", "notes")
                        Text(
                            when {
                                s.placesOnly -> if (trip.joined) "${count(trip.places, "place", "places")} added" else count(trip.places, "place", "places")
                                trip.places > 0 -> "$notes · ${count(trip.places, "place", "places")}"
                                else -> notes
                            },
                            style = Bt.text.sub,
                        )
                    }
                    Icon(BtIcons.Forward, "Open", Modifier.size(20.dp), tint = Bt.colors.muted)
                }
            }
        }
    }
    BtButton("Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Working(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), color = Bt.colors.route, strokeWidth = 2.dp, trackColor = Color.Transparent)
        Text(label, style = Bt.text.body)
    }
}

private const val MAX_PLACES_SHOWN = 6
private const val MAX_NOTES_SHOWN = 60
private const val FIRST_WORDS = 90

/** "1 note", "214 notes". */
private fun count(n: Int, one: String, many: String): String = if (n == 1) "1 $one" else "$n $many"
