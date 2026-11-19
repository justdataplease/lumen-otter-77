package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.ui.components.BtSwitchRow
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.ui.components.BtDeleteButton
import com.justdataplease.boringtravel.ui.components.BtFab
import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import com.justdataplease.boringtravel.ui.journal.EntryRow
import com.justdataplease.boringtravel.ui.calendar.CalendarSheet
import com.justdataplease.boringtravel.ui.components.copyOnLongPress
import com.justdataplease.boringtravel.geo.CurrentLocation
import com.justdataplease.boringtravel.tracking.TrackingPermissions
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.calendar.CalendarIntents
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.data.repo.now
import com.justdataplease.boringtravel.links.LinkFill
import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.links.LinkRenew
import com.justdataplease.boringtravel.links.LinkUrls
import com.justdataplease.boringtravel.links.MapsLinks
import com.justdataplease.boringtravel.links.MapsPlace
import com.justdataplease.boringtravel.links.PlacePhotos
import com.justdataplease.boringtravel.links.SharedLinks
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.PlaceResult
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import com.justdataplease.boringtravel.tags.ItemTags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Search waits this long after the last key press, so typing "halki" sends one request, not five. */
private const val SEARCH_DEBOUNCE_MS = 350L

/** A typed link is looked at once typing pauses this long (a paste shows its preview right after). */
private const val LINK_DEBOUNCE_MS = 700L

/** The place's photo follows a typed title once typing pauses this long (one look, not one per letter). */
private const val TITLE_SETTLE_MS = 800L

/** A change saves by itself once editing pauses this long (one save per pause, not one per letter). */
private const val AUTOSAVE_MS = 700L

private fun PlanKind.icon(): ImageVector = when (this) {
    PlanKind.Place -> BtIcons.Place
    PlanKind.Food -> BtIcons.Food
    PlanKind.Stay -> BtIcons.Stay
    PlanKind.Transport -> BtIcons.Transport
    PlanKind.Activity -> BtIcons.Activity
    PlanKind.Note -> BtIcons.Note
}

/**
 * Add or edit one plan item: kind, title, when (the day or Ideas, the start time, the end), "Place or Google Maps link"
 * (one field: a search, a name of the traveller's own, a pasted Google Maps link, or Use my location; owner,
 * 2026-11-14), website (optional; its preview on top; a new item can start with Paste a link), Getting there (how you
 * reach it from the plan's previous place, Car unless chosen), note, Add to calendar, the item's journal, Renew
 * details, delete. Once a link says what it is, it fills what is still empty: the Maps link the place, either the
 * title, and on a new item the kind when the name makes it obvious; a title or place a link gave follows when that
 * link changes, and Renew details reads the links again; a title typed by hand always stays. Without a website, an
 * item with a place shows its place's photo (Me › Place photos).
 *
 * Every change saves by itself a moment after the last edit and when the sheet closes (owner, 2026-11-12: "maybe
 * autosave and done just returns back"); the title row stays in view with Share and Done, and Done only closes. A new
 * item is made by the first save that has a title, a place or a link, and goes again when it is left empty. Its
 * place in the day follows its time; the List's drag moves it ([PlanDrop]). The travel choice saves at once (it
 * belongs to the leg, not the item).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanItemSheet(
    graph: AppGraph,
    tripId: String,
    original: PlanItem?,
    newDay: LocalDate?,
    items: List<PlanItem>,
    days: List<LocalDate>,
    onClose: () -> Unit,
    /** Opens a journal note from the item's notes ([ItemNotes]); null hides the Journal part. */
    onOpenEntry: ((String) -> Unit)? = null,
    /** Writes a new journal note about the saved item (its id); null: no Write button. */
    onWrite: ((String) -> Unit)? = null,
) {
    val c = Bt.colors
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun others(day: LocalDate?, id: String) = items.filter { it.day == day && it.id != id }

    var draft by remember {
        mutableStateOf(
            original ?: newId().let { id ->
                PlanItem(id = id, tripId = tripId, day = newDay, position = PlanOrder.end(others(newDay, id)), title = "", createdAt = 0L, updatedAt = 0L)
            },
        )
    }
    // The item as last saved (null: a new one not saved yet). "Unchanged?", the journal, the leg and Add to calendar
    // all go by this, not by [original]: after the first save the saved row is the baseline.
    var saved by remember { mutableStateOf(original) }
    // Saves run one after another, so an older one never lands after a newer one, or after a delete.
    var lastSave by remember { mutableStateOf<Job?>(null) }
    val trip by remember(tripId) { graph.trips.observeTrip(tripId) }.collectAsState(initial = null)
    val calendarLink by remember(tripId) { graph.calendar.link(tripId) }.collectAsState(initial = null)
    val savedItem = saved
    // How you reach this item from the plan's previous place (saved items only: an unsaved one has no leg yet).
    val incoming = remember(trip, items, savedItem?.id) {
        val t = trip
        if (t == null || savedItem == null) null else PlanTravelRows.incoming(t, items)[savedItem.id]
    }
    val context = LocalContext.current
    var calendarError by remember { mutableStateOf<String?>(null) }
    var calendarOpen by remember { mutableStateOf(false) }
    var kindTouched by remember { mutableStateOf(original != null) }
    // The clock dialogs: the start time and the end time.
    var pickingTime by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    // The website as typed, the link the preview shows (once typing pauses), and whether what was typed isn't one.
    var linkText by remember { mutableStateOf(original?.url.orEmpty()) }
    var link by remember { mutableStateOf(original?.url?.let(LinkUrls::normalize)) }
    var linkInvalid by remember { mutableStateOf(false) }
    // The place's Google Maps link (owner, 2026-11-13: "a website and a google url"), pasted into the place field
    // (owner, 2026-11-14: "have place or Google maps link"): always a whole link, never half-typed text.
    var mapsText by remember { mutableStateOf(original?.mapsUrl.orEmpty()) }
    var mapsLink by remember { mutableStateOf(original?.mapsUrl?.let(LinkUrls::normalize)) }
    // Renew details: how many times the links were read again (the previews follow), whether one is running, its result.
    var renewed by remember { mutableStateOf(0) }
    var renewing by remember { mutableStateOf(false) }
    var renewNote by remember { mutableStateOf<String?>(null) }
    // The name pasted with each link ("Yasaka Shrine" above a Maps link), and whether Paste a link found none.
    var pastedSiteName by remember { mutableStateOf<String?>(null) }
    var pastedMapsName by remember { mutableStateOf<String?>(null) }
    var pasteFailed by remember { mutableStateOf(false) }
    // A place removed here stays removed: the link doesn't put it back.
    var placeCleared by remember { mutableStateOf(false) }
    // The website the title came from and the Maps link the place came from: a new one replaces what the old one gave
    // (owner, 2026-11-13: "when I change website need to update title and image"); the traveller's own stays.
    var titleSite by remember { mutableStateOf(link) }
    var placeMaps by remember { mutableStateOf(mapsLink) }
    val near = remember(items, draft.day) { LinkPlaces.near(items, draft.day) }
    val preview = rememberLinkPreview(link, near, renewed)
    val typedSiteName = remember(linkText) { SharedLinks.parse(linkText)?.name }
    val typedMapsName = remember(mapsText) { SharedLinks.parse(mapsText)?.name }
    val siteHint = pastedSiteName ?: typedSiteName
    val mapsHint = pastedMapsName ?: typedMapsName

    // Without a link, the place's photo (the title is read once typing pauses).
    var settledTitle by remember { mutableStateOf(draft.title) }
    LaunchedEffect(draft.title) {
        delay(TITLE_SETTLE_MS)
        settledTitle = draft.title
    }
    val noLink = linkText.isBlank()
    // Also asked for when the item's site turns out to have no picture of its own.
    val linkHasNoPicture = !noLink && preview != null && preview.imageUrl == null
    val photoQuery = remember(noLink, linkHasNoPicture, draft.kind, draft.lat, draft.lng, draft.placeName, settledTitle) {
        if (noLink || linkHasNoPicture) PlacePhotos.query(draft.copy(title = settledTitle, url = null)) else null
    }
    val placePhoto = rememberPlacePhoto(photoQuery)

    /** A Google Maps link is the place's, any other goes to Website: Website holds only a site. */
    fun putLink(url: String, name: String?) {
        if (MapsLinks.isMaps(url)) {
            pastedMapsName = name
            mapsText = url
            mapsLink = url
            // A link put here may bring its place, also after one was removed.
            placeCleared = false
        } else {
            pastedSiteName = name
            linkText = url
            link = url
            linkInvalid = false
        }
    }

    fun paste() {
        val shared = SharedLinks.parse(clipboardText(context))
        pasteFailed = shared == null
        if (shared == null) return
        putLink(shared.url, shared.name)
    }

    LaunchedEffect(linkText) {
        val typed = linkText.trim()
        if (typed.isEmpty()) {
            link = null
            linkInvalid = false
            return@LaunchedEffect
        }
        val url = LinkUrls.normalize(typed)
        if (url == null || url != link) delay(LINK_DEBOUNCE_MS)
        if (url != null && MapsLinks.isMaps(url)) {
            // A Maps link typed or pasted as the website moves to the Maps field, with the name pasted beside it.
            val name = SharedLinks.parse(typed)?.name ?: pastedSiteName
            linkText = ""
            pastedSiteName = null
            putLink(url, name)
            return@LaunchedEffect
        }
        link = url
        linkInvalid = url == null
    }

    LaunchedEffect(mapsText) {
        val typed = mapsText.trim()
        mapsLink = if (typed.isEmpty()) null else LinkUrls.normalize(typed)?.takeIf(MapsLinks::isMaps)
    }

    // Once the website says what it is, fill what is still empty: the title (the page's name) and, on a new item, the
    // kind when the name makes it obvious. A title the old website gave follows a new one; one of the traveller's own
    // stays (owner, 2026-11-13: "when I change website need to update title and image").
    LaunchedEffect(link) {
        val url = link ?: return@LaunchedEffect
        val from = titleSite
        fun follows() = from != null && from != url && draft.title.isNotBlank() && LinkFill.fromLink(draft.title, from, graph.links.peek(from)?.title)
        if (!follows() && draft.title.isNotBlank() && kindTouched) return@LaunchedEffect
        val name = LinkFill.nameFor(url, graph.links.preview(url, near)?.title, siteHint)
        val renamed = follows()
        val fromThis = renamed || draft.title.isBlank()
        val filled = LinkFill.fill(if (renamed) draft.copy(title = "") else draft, name, null, kindTouched)
        draft = if (renamed && filled.title.isBlank()) filled.copy(title = LinkPlaces.placeholderTitle(url)) else filled
        if (fromThis) titleSite = url
    }

    // Once the Maps link says where, fill what is still empty: the place (a short link is followed first), the title
    // (the place's name) and, on a new item, the kind. A place and a title the old Maps link gave follow a new one; a
    // place picked from the search, typed, or found with Use my location stays.
    LaunchedEffect(mapsLink) {
        val url = mapsLink ?: return@LaunchedEffect
        val from = placeMaps?.takeIf { it != url }
        val old = from?.let { graph.links.mapsPlace(it, near) }
        fun moves() = !placeCleared && LinkPlaces.placeFrom(draft, old)
        // The old link's name: its place's, or what its look found when it had no pin.
        fun retitles() = from != null && draft.title.isNotBlank() && LinkFill.fromLink(draft.title, from, old?.name, graph.links.peek(from)?.title)
        val needsPlace = !placeCleared && (moves() || (draft.lat == null && draft.lng == null))
        if (!needsPlace && !retitles() && draft.title.isNotBlank() && kindTouched) {
            placeMaps = url
            return@LaunchedEffect
        }
        val found = if (needsPlace) graph.links.mapsPlace(url, near) else null
        val resolved = found?.name ?: graph.links.preview(url, near)?.title ?: MapsLinks.parse(url)?.name
        // A pin without a name gets the name of the spot.
        val place = if (found != null && found.hasPoint && found.name.isNullOrBlank()) {
            found.copy(name = graph.places.reverse(found.lat!!, found.lng!!)?.displayName)
        } else found
        val renamed = retitles()
        val moved = if (moves()) LinkPlaces.follow(draft, old, place) else draft
        val filled = LinkFill.fill(if (renamed) moved.copy(title = "") else moved, LinkFill.nameFor(url, resolved, mapsHint), place, kindTouched, placeCleared)
        draft = if (renamed && filled.title.isBlank()) filled.copy(title = LinkPlaces.placeholderTitle(url)) else filled
        placeMaps = url
    }

    /** Runs [work] after every save already on its way. */
    fun queue(work: suspend () -> Unit): Job {
        val previous = lastSave
        return graph.scope.launch {
            previous?.join()
            work()
        }.also { lastSave = it }
    }

    /**
     * Saves the draft when it differs from what was saved last; [final] when the sheet closes (a new item left empty
     * goes then, and a short Maps link is followed). Returns the latest save's job (null: nothing saved yet).
     */
    fun commit(final: Boolean): Job? {
        val before = saved
        // An unreadable link keeps the one the item had; an empty field removes it; a Maps link is never the website.
        val (url, mapsUrl) = LinkPlaces.fromFields(linkText, mapsText, before)
        val d = draft.copy(url = url, mapsUrl = mapsUrl).let { if (placeCleared || mapsUrl == null) it else LinkPlaces.fill(it, MapsLinks.parse(mapsUrl)) }
        val siteName = url?.let { u -> LinkFill.nameFor(u, preview?.takeIf { it.url == u }?.title, siteHint) }
        val mapsName = mapsUrl?.let { u -> LinkFill.nameFor(u, graph.links.peek(u)?.title ?: MapsLinks.parse(u)?.name, mapsHint) }
        // An item that had a name keeps it when the field is emptied.
        val title = d.title.trim().ifBlank { d.placeName.substringBefore(',').trim() }.ifBlank { original?.title.orEmpty() }
            .ifBlank { (mapsName ?: siteName).orEmpty() }.ifBlank { LinkPlaces.placeholderTitle(url, mapsUrl).orEmpty() }
        if (title.isBlank()) {
            // A new item emptied again after a save: it goes when the sheet closes.
            if (final && original == null && before != null) {
                saved = null
                queue { graph.plan.get(before.id)?.let { graph.plan.delete(it) } }
            }
            return lastSave
        }
        val item = PlanSpan.normalized(d.copy(title = title, note = d.note.trim(), createdAt = before?.createdAt ?: now()))
        if (item != before) {
            saved = item
            queue {
                graph.plan.save(item)
                val day = others(item.day, item.id) + item
                if (PlanOrder.crowded(day)) graph.plan.saveAll(PlanOrder.renumber(day))
            }
        }
        // A short Maps link (or one naming a place without its pin) only answers after a look on the network.
        if (final && mapsUrl != null && !placeCleared && item.lat == null && item.lng == null) {
            val saving = lastSave
            graph.scope.launch {
                saving?.join()
                graph.links.mapsPlace(mapsUrl, near)?.let { graph.plan.fillFromLink(item.id, mapsUrl, it) }
            }
        }
        return lastSave
    }

    /** Closes the sheet, saving first with [save]; [then] runs once the item is saved (Write opens on the saved item). */
    fun finish(save: Boolean, then: (() -> Unit)? = null) {
        if (finished) return
        finished = true
        val saving = if (save) commit(final = true) else lastSave
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onClose()
            if (then != null) graph.scope.launch(Dispatchers.Main) {
                saving?.join()
                then()
            }
        }
    }

    // Saves once typing pauses; Done, a swipe or leaving the screen saves the rest.
    LaunchedEffect(draft, linkText, mapsText, placeCleared) {
        delay(AUTOSAVE_MS)
        if (!finished) commit(final = false)
    }
    val flush by rememberUpdatedState {
        if (!finished) {
            finished = true
            commit(final = true)
        }
    }
    DisposableEffect(Unit) { onDispose { flush() } }

    fun share() {
        commit(final = false)
        val item = saved ?: return
        shareItem(context, item.title, shareText(item, incoming, items))
    }

    fun setTime(minutes: Int?) {
        draft = draft.copy(timeMinutes = minutes)
        if (minutes != null) draft = draft.copy(position = PlanOrder.forTime(others(draft.day, draft.id), minutes))
    }

    fun setDay(day: LocalDate?) {
        if (day == draft.day) return
        val o = others(day, draft.id)
        draft = draft.copy(day = day, position = draft.timeMinutes?.let { PlanOrder.forTime(o, it) } ?: PlanOrder.end(o))
    }

    fun setPlace(r: PlaceResult) {
        placeCleared = false
        draft = draft.copy(
            placeName = r.displayName,
            lat = r.lat,
            lng = r.lng,
            externalId = r.externalId ?: draft.externalId,
            title = draft.title.ifBlank { r.name },
            kind = if (!kindTouched && r.kind != null) r.kind!!.id else draft.kind,
        )
    }

    /** A place the search doesn't know, by the name typed (owner, 2026-11-13: "place does not exist"): no pin. */
    fun setPlaceName(name: String) {
        placeCleared = false
        draft = draft.copy(
            placeName = name, lat = null, lng = null,
            externalId = draft.externalId?.takeUnless { it.startsWith("osm:") },
            title = draft.title.ifBlank { name },
        )
    }

    // An item with a point but no name (from the route or an import) gets a name for it.
    LaunchedEffect(Unit) {
        val d = draft
        if (d.placeName.isBlank() && d.lat != null && d.lng != null) {
            val r = graph.places.reverse(d.lat!!, d.lng!!)
            if (r != null && draft.placeName.isBlank() && draft.lat == d.lat && draft.lng == d.lng) draft = draft.copy(placeName = r.displayName)
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!finished) {
                finished = true
                commit(final = true)
            }
            onClose()
        },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = c.surface,
        contentColor = c.ink,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.line) },
    ) {
        // The title row stays put while the rest scrolls (owner, 2026-11-12: "since the list is long it's strange").
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (original == null) "Add to your plan" else "Edit plan item", Modifier.weight(1f),
                style = Bt.text.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (savedItem != null) BtIconButton(BtIcons.Share, "Share", ::share)
            // Like Trips' New trip, with a tick (owner, 2026-11-14).
            BtFab("Done", onClick = { finish(save = true) }, icon = BtIcons.Check)
        }
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val shownLink = link
            if (shownLink != null) {
                if (linkHasNoPicture) placePhoto?.let { PlacePhotoCard(it) }
                LinkPreviewCard(shownLink, preview?.takeIf { it.url == shownLink })
            } else if (noLink) {
                placePhoto?.let { PlacePhotoCard(it) }
                // A new item can start from a link: the clipboard is read only on this tap.
                if (original == null && mapsText.isBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BtGhostButton("Paste a link", onClick = ::paste, icon = BtIcons.World)
                        if (pasteFailed) Text("No link to paste. Copy one first.", style = Bt.text.fine)
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanKind.entries.forEach { k ->
                    BtChip(k.label, PlanKind.of(draft.kind) == k, { kindTouched = true; draft = draft.copy(kind = k.id) }, icon = k.icon())
                }
            }

            OutlinedTextField(
                draft.title, { draft = draft.copy(title = it) }, Modifier.fillMaxWidth(),
                label = { Text("Title") },
                placeholder = { Text(if (PlanKind.of(draft.kind) == PlanKind.Note) "Buy ferry tickets" else "Lunch in Halki") },
                maxLines = 3, textStyle = Bt.text.body,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            // When, together: the day, the start time, the end (owner, 2026-11-12: the day "on top ... where they
            // should be"). The time decides the item's place in the day; a drag in the List changes it.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow("Day")
                val selected = days.indexOf(draft.day)
                LazyRow(
                    state = rememberLazyListState(initialFirstVisibleItemIndex = ((if (selected >= 0) selected else days.size) - 1).coerceAtLeast(0)),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(days, key = { it.toEpochDay() }) { d -> BtChip(Dates.dayShort(d), draft.day == d, { setDay(d) }) }
                    item(key = "ideas") { BtChip("Ideas", draft.day == null, { setDay(null) }) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val t = draft.timeMinutes
                BtGhostButton(t?.let(Dates::time) ?: "Add a time", onClick = { pickingTime = true }, icon = BtIcons.Time)
                if (t != null) BtIconButton(BtIcons.Close, "Remove time", { setTime(null) })
            }

            // Until when (owner, 2026-11-12: "from date time to date time"): an end day and time, its own row at the end.
            draft.day?.let { day -> EndSection(draft, day, days, onChange = { draft = it }, onPickTime = { pickingEnd = true }) }

            // One field for the place: a search, a name of the traveller's own, or a pasted Google Maps link (it brings
            // the place, and Open in Google Maps opens it). Removing the place removes its link too.
            PlaceField(
                graph, draft, items, mapsLink = mapsText.trim().ifEmpty { null },
                onPick = ::setPlace, onName = ::setPlaceName,
                onLink = { url, name -> pasteFailed = false; putLink(url, name) },
                onClear = {
                    placeCleared = true
                    mapsText = ""
                    pastedMapsName = null
                    draft = draft.copy(placeName = "", lat = null, lng = null, externalId = draft.externalId?.takeUnless { it.startsWith("osm:") })
                },
            )

            // Website: optional, the place's own site; a bare domain gets https://. A Maps link moves to the Maps field.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow("Website")
                OutlinedTextField(
                    linkText, { linkText = it; pastedSiteName = null; pasteFailed = false }, Modifier.fillMaxWidth(),
                    placeholder = { Text("A site, like example.com") },
                    leadingIcon = { Icon(BtIcons.World, null) },
                    trailingIcon = { if (linkText.isNotEmpty()) BtIconButton(BtIcons.Close, "Remove link", { linkText = "" }, size = 32.dp) },
                    singleLine = true, textStyle = Bt.text.body, isError = linkInvalid,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
                if (linkInvalid) Text("Use a web address, like example.com", style = Bt.text.fine, color = c.danger)
            }

            // Getting there: every planned place reached from another one (Car unless chosen otherwise).
            if (incoming != null && savedItem != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Getting there")
                    // Within one place say where from ("From Boutique hotel…"); between places, the place ("From Mendoza").
                    val (from, distance) = gettingThereFrom(incoming, items, savedItem)
                    GettingThere(graph, tripId, incoming.leg, incoming.mode, line = gettingThereLine(from, incoming.mode, distance))
                }
            }

            OutlinedTextField(
                draft.note, { draft = draft.copy(note = it) }, Modifier.fillMaxWidth(),
                label = { Text("Note") }, placeholder = { Text("Bus from the port, 40 min") },
                minLines = 2, maxLines = 6, textStyle = Bt.text.body,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            // Tags (owner, 2026-11-12: "on plan items only"); the clip shows them at the item's place.
            ItemTagsField(ItemTags.of(draft), { draft = draft.copy(tags = ItemTags.format(it)) })

            // Add to calendar: on a day only. A trip added to a calendar already has every item there, kept updated.
            val savedDay = savedItem?.day
            if (savedItem != null && savedDay != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Calendar")
                    val inCalendar = calendarLink
                    if (inCalendar != null) {
                        Text(
                            if (inCalendar.autoUpdate) "In ${inCalendar.calendarName}. Plan changes update it." else "In ${inCalendar.calendarName}.",
                            style = Bt.text.sub,
                        )
                    } else {
                        BtGhostButton("Add to calendar", icon = BtIcons.Calendar, modifier = Modifier.fillMaxWidth(), onClick = {
                            calendarError = null
                            scope.launch {
                                val zone = runCatching { graph.calendar.zones(tripId).current }.getOrDefault(java.time.ZoneId.systemDefault())
                                val (site, maps) = LinkPlaces.fromFields(linkText, mapsText, savedItem)
                                val intent = CalendarIntents.insert(draft.copy(day = savedDay, url = site, mapsUrl = maps), trip?.title.orEmpty(), zone) ?: return@launch
                                try {
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    calendarError = "No calendar app on this phone."
                                }
                            }
                        })
                        SyncCalendarLine(onSync = { calendarOpen = true })
                        calendarError?.let { Text(it, style = Bt.text.fine, color = c.danger) }
                    }
                }
            }

            // Every place opens in Google Maps (owner, 2026-11-11: "google links"): its Maps link (owner, 2026-11-13),
            // else its pin, else its name.
            val search = remember(draft.lat, draft.lng, draft.placeName, draft.title) {
                val pin = if (draft.lat != null && draft.lng != null) "${draft.lat},${draft.lng}" else null
                MapsLinks.searchUrl(pin ?: draft.placeName.ifBlank { null }, null, null)
            }
            val openUrl = mapsLink ?: search
            if (openUrl != null && (draft.kind != PlanKind.Note.id || mapsLink != null)) {
                BtGhostButton("Open in Google Maps", icon = BtIcons.Place, modifier = Modifier.fillMaxWidth(), onClick = {
                    try {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(openUrl)))
                    } catch (_: ActivityNotFoundException) {
                        calendarError = "No maps app or browser on this phone."
                    }
                })
            }

            // The item's journal, always there with Write at the end (owner, 2026-11-12: "have a write button at the end
            // and have dedicated space for journal notes"). A note written here is placed at the item: "Alhambra (Granada)".
            if (savedItem != null && onOpenEntry != null) {
                val entries by remember(tripId) { graph.journal.observeForTrip(tripId) }.collectAsState(initial = emptyList())
                val notes = remember(savedItem, entries) { ItemNotes.of(savedItem, entries) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow(when (notes.size) {
                        0 -> "Journal"
                        1 -> "Journal · 1 note"
                        else -> "Journal · ${notes.size} notes"
                    })
                    if (notes.isEmpty()) Text("Nothing written about it yet.", style = Bt.text.sub, color = c.muted)
                    notes.forEach { e -> EntryRow(e, onClick = { finish(save = true); onOpenEntry(e.id) }) }
                    if (onWrite != null) {
                        BtGhostButton("Write", icon = BtIcons.Journal, modifier = Modifier.fillMaxWidth(), onClick = {
                            finish(save = true) { onWrite(savedItem.id) }
                        })
                    }
                }
            }

            // Renew details (owner, 2026-11-14: "how do you get the title or when it's updated, maybe a renew details
            // button at the bottom; if it's manually edited don't change"): the links are read again now.
            val (siteNow, mapsNow) = LinkPlaces.fromFields(linkText, mapsText, savedItem)
            if (siteNow != null || mapsNow != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BtGhostButton(
                        if (renewing) "Reading the links…" else "Renew details", icon = BtIcons.Rotate, modifier = Modifier.fillMaxWidth(), enabled = !renewing,
                        onClick = {
                            renewing = true
                            renewNote = null
                            scope.launch {
                                try {
                                    val before = draft
                                    // What the links said last time: only a title or place they gave is replaced.
                                    val oldSite = siteNow?.let { graph.links.peek(it)?.title }
                                    val oldMaps = mapsNow?.let { graph.links.peek(it) }
                                    val oldPlace = mapsNow?.let { url ->
                                        MapsLinks.parse(url)?.takeIf { it.hasPoint }
                                            ?: oldMaps?.takeIf { it.lat != null && it.lng != null }?.let { MapsPlace(it.title, it.lat, it.lng) }
                                    }
                                    val site = siteNow?.let { graph.links.renew(it, near) }
                                    val maps = mapsNow?.let { graph.links.renew(it, near) }
                                    val place = mapsNow?.let { graph.links.mapsPlace(it, near) }
                                    draft = LinkRenew.apply(
                                        draft, placeCleared,
                                        site = siteNow?.let { LinkRenew.Look(it, oldSite, site?.title, siteHint) },
                                        maps = mapsNow?.let { LinkRenew.Look(it, oldMaps?.title, place?.name ?: maps?.title, mapsHint) },
                                        oldPlace = oldPlace, place = place,
                                    )
                                    if (draft.title != before.title) {
                                        if (mapsNow != null) placeMaps = mapsNow
                                        if (siteNow != null) titleSite = siteNow
                                    }
                                    renewed++
                                    val failed = listOfNotNull(site, maps).let { looks -> looks.isEmpty() || looks.all { it.failed } }
                                    renewNote = when {
                                        failed -> "Couldn't read the links. Check the connection."
                                        draft != before -> "Details renewed."
                                        else -> "Nothing new."
                                    }
                                } finally {
                                    renewing = false
                                }
                            }
                        },
                    )
                    Text(renewNote ?: "Reads the title, place and picture from the links again. A title you typed stays.", style = Bt.text.fine)
                }
            }

            if (savedItem != null) {
                BtDeleteButton("Delete", { confirmDelete = true })
                if (confirmDelete) {
                    BtConfirmDialog(
                        title = "Delete this item?", body = "Are you sure you want to delete it? You can't undo this.", confirm = "Delete", danger = true,
                        onConfirm = {
                            confirmDelete = false
                            val id = savedItem.id
                            finish(save = false)
                            saved = null
                            // After any save still on its way, and from the row as it is now (a link may have filled it).
                            queue { graph.plan.get(id)?.let { graph.plan.delete(it) } }
                        },
                        onDismiss = { confirmDelete = false },
                    )
                }
            }
        }

        if (pickingEnd) {
            ClockDialog("Ends at", draft.endMinutes ?: draft.timeMinutes?.let { (it + 60) % (24 * 60) } ?: (11 * 60), { pickingEnd = false }) {
                draft = draft.copy(endMinutes = it)
            }
        }
        if (pickingTime) ClockDialog("Time", draft.timeMinutes ?: (9 * 60), { pickingTime = false }, ::setTime)
        if (calendarOpen) CalendarSheet(graph, tripId, onClose = { calendarOpen = false })
    }
}

/**
 * "Opens your calendar app with this event. To keep all plan steps, sync calendar." (owner, 2026-11-12): "sync
 * calendar" is a link in the accent ink that opens the trip's Calendar sheet ([onSync]).
 */
@Composable
private fun SyncCalendarLine(onSync: () -> Unit) {
    val ink = Bt.colors.accentInk
    val text = buildAnnotatedString {
        append("Opens your calendar app with this event. To keep all plan steps, ")
        withLink(LinkAnnotation.Clickable("sync-calendar", TextLinkStyles(SpanStyle(color = ink, textDecoration = TextDecoration.Underline))) { onSync() }) {
            append("sync calendar")
        }
        append(".")
    }
    Text(text, style = Bt.text.fine)
}

/**
 * Until when: No end, the same day or a later one (a stay counts nights), the end time, +1 h, +2 h and +3 h from the
 * start, and once it ends: the end as its own row (on by itself for another day; owner: "a toggle to deactivate second
 * item if needed") and that row's name, the title when left empty.
 */
@Composable
private fun EndSection(draft: PlanItem, day: LocalDate, days: List<LocalDate>, onChange: (PlanItem) -> Unit, onPickTime: () -> Unit) {
    val stay = draft.kind == PlanKind.Stay.id
    val tidy = PlanSpan.normalized(draft)
    val ends = PlanSpan.hasEnd(draft)
    val endDay = PlanSpan.endDate(draft)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("Ends")
        // The trip's later days (a week on when it has none), and the chosen one wherever it is.
        val later = (days.filter { it > day }.ifEmpty { (1..7).map { day.plusDays(it.toLong()) } } + listOfNotNull(endDay?.takeIf { it > day }))
            .distinct().sorted().filter { it.toEpochDay() - day.toEpochDay() <= PlanSpan.MAX_DAYS }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            item(key = "none") { BtChip("No end", !ends, { onChange(draft.copy(endDays = null, endMinutes = null)) }) }
            item(key = "same") {
                BtChip("Same day", ends && PlanSpan.days(draft) == 0, {
                    onChange(PlanSpan.endingAt(draft, day, draft.endMinutes ?: draft.timeMinutes?.let { minOf(it + 60, 24 * 60 - 1) } ?: 12 * 60))
                })
            }
            items(later, key = { it.toEpochDay() }) { d ->
                val n = (d.toEpochDay() - day.toEpochDay()).toInt()
                val label = if (stay) Dates.dayShort(d) + " · " + (if (n == 1) "1 night" else "$n nights") else Dates.dayShort(d)
                BtChip(label, endDay == d, { onChange(PlanSpan.endingAt(draft, d, draft.endMinutes ?: if (stay) 11 * 60 else null)) })
            }
        }
        if (ends) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val t = draft.endMinutes
                BtGhostButton(t?.let(Dates::time) ?: "Add an end time", onClick = onPickTime, icon = BtIcons.Time)
                if (t != null && PlanSpan.days(draft) > 0) BtIconButton(BtIcons.Close, "Remove end time", { onChange(draft.copy(endMinutes = null)) })
                PlanSpan.length(tidy)?.let { Text(it, Modifier.weight(1f), style = Bt.text.mono, maxLines = 1) }
            }
        }
        // From the start time: a concert at 19:00 for 3 hours ends at 22:00.
        val start = draft.timeMinutes
        if (start != null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3).forEach { h ->
                    val end = start + h * 60
                    val picked = ends && PlanSpan.minutes(tidy) == h * 60
                    BtChip("+$h h", picked, { onChange(draft.copy(endDays = if (end >= 24 * 60) 1 else null, endMinutes = end % (24 * 60))) })
                }
            }
        }
        if (ends) {
            val row = PlanSpan.endRow(draft)
            val at = listOfNotNull(endDay?.let(Dates::dayShort), draft.endMinutes?.let(Dates::time)).joinToString(" · ")
            BtSwitchRow("Its own row at the end", "$at in the plan and the video. It opens this item.", row, { onChange(draft.copy(endRow = it)) })
            if (row) {
                OutlinedTextField(
                    draft.endTitle.orEmpty(), { onChange(draft.copy(endTitle = it)) }, Modifier.fillMaxWidth(),
                    label = { Text("Name at the end") },
                    placeholder = { Text(draft.title.ifBlank { if (stay) "Check-out" else "Name" }) },
                    singleLine = true, textStyle = Bt.text.body,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
        }
    }
}

/** The clock for a time: [title] over it, starting at [minutes]; Done hands the chosen minutes to [onPick]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockDialog(title: String, minutes: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = DateFormat.is24HourFormat(LocalContext.current))
    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(state.hour * 60 + state.minute)
                onDismiss()
            }) { Text("Done") }
        },
        title = { Text(title, style = Bt.text.item) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { TimePicker(state) }
}

/** The clipboard's text (or link), read only when the traveller taps Paste a link (or the planner's Link); null when it holds none. */
internal fun clipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/**
 * "Place or Google Maps link" (owner, 2026-11-14): the chosen place (a long press copies the address), with "Google
 * Maps link" under it when it has one ([mapsLink]), and one button that removes both; or one field that searches as
 * you type, takes the typed name as a place of its own ([onName]) and a pasted link ([onLink]).
 */
@Composable
private fun PlaceField(
    graph: AppGraph,
    draft: PlanItem,
    items: List<PlanItem>,
    mapsLink: String?,
    onPick: (PlaceResult) -> Unit,
    onName: (String) -> Unit,
    onLink: (url: String, name: String?) -> Unit,
    onClear: () -> Unit,
) {
    val c = Bt.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow("Place or Google Maps link")
        val hasPlace = draft.placeName.isNotBlank() || (draft.lat != null && draft.lng != null)
        if (hasPlace || mapsLink != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (hasPlace) BtIcons.Place else BtIcons.Map, null, Modifier.size(20.dp), tint = c.route)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (hasPlace) draft.placeName.ifBlank { "A point on the map" } else "Google Maps link",
                        Modifier.copyOnLongPress(draft.placeName, "Address"),
                        style = Bt.text.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    // Until the link says where (a short one answers after a look), the link itself.
                    if (mapsLink != null) {
                        Text(
                            if (hasPlace) "Google Maps link" else mapsLink.removePrefix("https://").removePrefix("http://"),
                            style = Bt.text.fine, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                BtIconButton(BtIcons.Close, if (mapsLink != null) "Remove place and link" else "Remove place", onClear)
            }
        } else {
            val near = remember(draft.day, items) {
                (items.firstOrNull { it.day == draft.day && it.lat != null && it.lng != null } ?: items.firstOrNull { it.lat != null && it.lng != null })
                    ?.let { LatLng(it.lat!!, it.lng!!) }
            }
            PlaceSearchField(graph, near, onPick, onName, onLink)
        }
    }
}

/**
 * Search as you type (debounced), results lean towards [near]; from two letters on, the typed name can be the place
 * itself ([onName]), found or not, online or not. A link typed or pasted here (alone, or as the Maps app shares it:
 * "Yasaka Shrine https://maps.app.goo.gl/…") isn't searched for: it goes to [onLink] with the name beside it.
 */
@Composable
private fun PlaceSearchField(
    graph: AppGraph, near: LatLng?, onPick: (PlaceResult) -> Unit, onName: (String) -> Unit, onLink: (url: String, name: String?) -> Unit,
) {
    val c = Bt.colors
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val pastedLink = remember(query) { SharedLinks.inPlaceField(query) }
    LaunchedEffect(query) {
        failed = false
        val q = query.trim()
        // A link, once typing pauses, is the item's link: never searched for.
        if (pastedLink != null) {
            results = emptyList()
            searching = false
            delay(LINK_DEBOUNCE_MS)
            query = ""
            onLink(pastedLink.url, pastedLink.name)
            return@LaunchedEffect
        }
        if (q.length < 2) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        searching = true
        try {
            results = graph.places.search(q, near)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            results = emptyList()
            failed = true
        }
        searching = false
    }

    OutlinedTextField(
        query, { query = it }, Modifier.fillMaxWidth(),
        placeholder = { Text("Search, or paste a Google Maps link") },
        leadingIcon = { Icon(BtIcons.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) BtIconButton(BtIcons.Close, "Clear search", { query = "" }, size = 32.dp) },
        singleLine = true, textStyle = Bt.text.body,
    )
    val q = query.trim()
    if (q.isEmpty()) UseMyLocation(graph, onPick)
    if (pastedLink != null) return
    when {
        q.length < 2 -> Unit
        searching && results.isEmpty() -> Text("Searching…", style = Bt.text.fine)
        failed -> Text("Can't search right now. Check the connection.", style = Bt.text.fine)
        !searching && results.isEmpty() -> Text("Nothing found. Try another spelling.", style = Bt.text.fine)
    }
    if (q.length >= 2) {
        Column {
            results.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().clip(btControlShape()).clickable { onPick(r); query = "" }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon((r.kind ?: PlanKind.Place).icon(), null, Modifier.size(18.dp), tint = c.muted)
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.displayName, style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // A place the search doesn't know keeps the name typed (owner, 2026-11-13: "place does not exist").
            Row(
                Modifier.fillMaxWidth().clip(btControlShape()).clickable { onName(q); query = "" }.padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(BtIcons.Edit, null, Modifier.size(18.dp), tint = c.muted)
                Text("Use “$q” as the place", Modifier.weight(1f), style = Bt.text.item, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (results.isNotEmpty()) {
                Spacer(Modifier.size(2.dp))
                Text("Places © OpenStreetMap contributors", style = Bt.text.fine)
            }
        }
    }
}

/**
 * "Use my location": the place is where the phone is, named by the place search. Location access is asked on this
 * tap only (precise or approximate, as the traveller chooses), never before; refused, one line says so and the
 * search stays.
 */
@Composable
private fun UseMyLocation(graph: AppGraph, onPick: (PlaceResult) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun locate() {
        if (locating) return
        locating = true
        problem = null
        scope.launch {
            try {
                val fix = CurrentLocation.get(context, asked = true)
                if (fix == null) {
                    problem = "Couldn't find where you are. Search for the place instead."
                    return@launch
                }
                val (lat, lng) = fix
                val named = try {
                    graph.places.reverse(lat, lng)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                // The phone's own point, with the name of the spot (offline: a point on the map).
                onPick(named?.copy(lat = lat, lng = lng) ?: PlaceResult(name = "", displayName = "", lat = lat, lng = lng))
            } finally {
                locating = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it } || TrackingPermissions.hasLocation(context)) locate()
        else problem = "No access to your location. Search for the place instead."
    }
    BtGhostButton(
        if (locating) "Finding your location…" else "Use my location",
        onClick = {
            if (TrackingPermissions.hasLocation(context)) locate()
            else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        icon = BtIcons.MyLocation,
        enabled = !locating,
    )
    problem?.let { Text(it, style = Bt.text.fine) }
}
