package com.justdataplease.boringtravel.ui.editor

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.settings.TripPhotoStyle
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.places.KnownPlace
import com.justdataplease.boringtravel.places.TripPlaces
import com.justdataplease.boringtravel.data.repo.VideoImportException
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.data.repo.now
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoAlbums
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.doc.moveBlockBefore
import com.justdataplease.boringtravel.speech.Dictation
import com.justdataplease.boringtravel.speech.DictationMerger
import com.justdataplease.boringtravel.speech.DictationTarget
import com.justdataplease.boringtravel.geo.CurrentLocation
import com.justdataplease.boringtravel.tracking.TrackingPermissions
import com.justdataplease.boringtravel.ui.journal.JournalLogic
import com.justdataplease.boringtravel.ui.nav.Write
import com.justdataplease.boringtravel.ui.trip.ItemNotes
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

/** One row of the editor. Text areas and quotes hold live rich-text states; the other rows hold their block values. */
sealed class EdItem {
    val key: String = "item${counter.incrementAndGet()}"

    /** Paragraphs, list items and headings that follow each other, edited as one rich text. */
    class Text(val state: RichTextState) : EdItem()
    class Quote(val state: RichTextState) : EdItem()
    class Photo(block: Block.Photo) : EdItem() {
        var block by mutableStateOf(block)
    }

    /** Photos and videos in a layout; tapping it opens the layout sheet ([AlbumSheet]). */
    class Album(block: Block.Album) : EdItem() {
        var block by mutableStateOf(block)
    }
    class Check(items: List<CheckItem>) : EdItem() {
        val rows = mutableStateListOf<CheckRow>().apply { items.forEach { add(CheckRow(it.text, it.done)) } }
    }
    class Place(block: Block.Place) : EdItem() {
        var block by mutableStateOf(block)
    }
    class Divider : EdItem()
    class PageBreak : EdItem()

    /** A block from a newer app version: not shown, written back unchanged. */
    class Kept(val block: Block) : EdItem()

    internal companion object {
        val counter = AtomicLong()
    }
}

class CheckRow(text: String, done: Boolean) {
    val key: String = "row${EdItem.counter.incrementAndGet()}"
    var text by mutableStateOf(TextFieldValue(text))
    var done by mutableStateOf(done)
}

/** Something picked for a note: a photo, or a video ([video]). */
data class Picked(val uri: Uri, val video: Boolean = false)

/** How a new photo sits on the page in the current look. */
data class PhotoDefaults(val width: Float, val frame: PhotoFrame, val tilt: Float) {
    /** Alternates the tilt so a run of photos doesn't lean one way. */
    fun tiltFor(n: Int): Float = when {
        tilt == 0f -> 0f
        n % 2 == 0 -> tilt
        else -> (-tilt * 0.7f * 2f).roundToInt() / 2f
    }

    companion object {
        /** How every look places a new photo: the full column, rounded, straight (aligned photo frames). */
        val Page = PhotoDefaults(1f, PhotoFrame.Rounded, 0f)

        /** A trip's own defaults (Edit trip › Photos in notes), straight like [Page]. */
        fun of(style: TripPhotoStyle) = PhotoDefaults(style.width, style.frame, 0f)

        /** The defaults for a new photo or video in a note of trip [tripId] (null: a note outside trips). */
        suspend fun forTrip(graph: AppGraph, tripId: String?): PhotoDefaults =
            runCatching { of(graph.settings.tripPhotoStyles.first().of(tripId)) }.getOrDefault(Page)
    }
}

/** Everything that gets saved, compared to skip saves that change nothing. */
data class Draft(
    val title: String,
    val occurredAt: Long,
    val zoneId: String,
    val place: String,
    val lat: Double?,
    val lng: Double?,
    val doc: EntryDoc,
) {
    val hasContent: Boolean get() = title.isNotBlank() || !doc.isEmpty
}

internal fun newState(html: String): RichTextState = RichTextState().apply {
    if (html.isNotBlank()) setHtml(html)
    config.exitListOnEmptyItem = true
}

/**
 * The editor's state. Lives with the Write destination (survives rotation); the entry id is kept in the saved state
 * so a new entry is found again after the app was killed. Saves ~800 ms after the last change, when the screen
 * stops and when the writer leaves, so text is never lost.
 */
class WriteViewModel(
    private val graph: AppGraph,
    private val route: Write,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val entryId: String = saved.get<String>(KEY_ID) ?: (route.entryId ?: newId()).also { saved[KEY_ID] = it }

    var loaded by mutableStateOf(false)
        private set
    val items = mutableStateListOf<EdItem>()
    var title by mutableStateOf(TextFieldValue(""))
    var font by mutableStateOf<String?>(null)
    var occurredAt by mutableLongStateOf(0L)
        private set
    var zoneId by mutableStateOf(TimeZone.getDefault().id)
        private set
    var placeName by mutableStateOf("")
    var lat by mutableStateOf<Double?>(null)
        private set
    var lng by mutableStateOf<Double?>(null)
        private set
    var tempC by mutableStateOf<Double?>(null)
        private set

    /**
     * The note has no place on purpose (owner, 2026-11-11: "make it possible for a journal note not to select a place
     * at all … a toggle"): no name, no pin, and nothing fills one in by itself. A saved note without a place opens
     * with it on.
     */
    var noPlace by mutableStateOf(false)
        private set

    /** Turns [noPlace] on (the place and its pin go) or off (the place can be picked again). */
    fun pickNoPlace(on: Boolean) {
        noPlace = on
        if (!on) return
        placeSearch?.cancel()
        foundPlaces = emptyList()
        placeName = ""
        lat = null
        lng = null
    }

    /** A new entry (or one created empty from the calendar): offers "Use photo time" and is dropped if left empty. */
    var isNew by mutableStateOf(false)
        private set
    var photoTimeOffer by mutableStateOf<Long?>(null)
        private set

    /** "Use photo time", or "Use video time" when the offer came from a video. */
    var photoTimeLabel by mutableStateOf("Use photo time")
        private set

    /** The text, quote or block the writer last worked in; toolbar actions go there. */
    var activeKey by mutableStateOf<String?>(null)
    var selectedPhotoKey by mutableStateOf<String?>(null)

    /** An item or checklist row to focus once it is on screen. */
    var focusKey by mutableStateOf<String?>(null)
    var importing by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)

    /** A video being prepared ([addMedia]): how far, 0..1; null when none is. */
    var videoProgress by mutableStateOf<Float?>(null)
        private set
    private var videoJob: Job? = null

    /** What to say while photos or a video are still going in (leaving, or adding more meanwhile). */
    val busyNotice: String
        get() = if (videoProgress != null) "Preparing the video. Please wait a moment." else "Finishing your photos. Please wait a moment."

    /** The latest route point, for the default place and when the phone can't say where it is ("Use my location"). */
    var lastPoint by mutableStateOf<TrackPoint?>(null)
        private set

    /** The trip's own places to pick from (the plan, the stops, earlier entries: [TripPlaces]); none outside a trip. */
    var knownPlaces by mutableStateOf<List<KnownPlace>>(emptyList())
        private set
    private var knownJob: Job? = null

    /**
     * A place picked from [knownPlaces]: its name ("Alhambra (Granada)" for a plan item, so the note shows under it in
     * the plan) and its point ([TripPlaces.pointAfterPick]).
     */
    fun pickPlace(p: KnownPlace) {
        noPlace = false
        placeName = p.name
        foundPlaces = emptyList()
        val point = TripPlaces.pointAfterPick(knownPlaces, p, lat, lng)
        lat = point?.lat
        lng = point?.lng
    }

    /** The places of the note's trip, its own day's plan first; again when the date changes (once loaded). */
    private fun loadKnownPlaces() {
        if (!::base.isInitialized) return
        val trip = base.tripId ?: return
        val day = date
        knownJob?.cancel()
        knownJob = viewModelScope.launch {
            knownPlaces = try {
                TripPlaces.of(graph.plan.items(trip), graph.tracks.stops(trip), graph.journal.forTrip(trip), day, exceptEntry = entryId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** "Use my location" is finding where the phone is. */
    var locating by mutableStateOf(false)
        private set

    /** Why "Use my location" found nothing; null when it did. */
    var locationNote by mutableStateOf<String?>(null)
        private set

    /**
     * "Use my location" (asked for on the tap, by the details sheet): where the phone is now ([CurrentLocation]), else
     * the latest route point; the place is named from it.
     */
    fun locate() {
        if (locating) return
        locating = true
        locationNote = null
        placeSearch?.cancel()
        foundPlaces = emptyList()
        viewModelScope.launch {
            try {
                val here = runCatching { CurrentLocation.get(graph.app, asked = true) }.getOrNull() ?: lastPoint?.let { it.lat to it.lng }
                if (here == null) {
                    locationNote = if (TrackingPermissions.hasLocation(graph.app)) "Couldn't find where you are. Type the place."
                    else "Location isn't allowed. Type the place."
                    return@launch
                }
                noPlace = false
                lat = here.first
                lng = here.second
                val name = Places.reverse(graph.app, here.first, here.second)
                if (name != null) placeName = name else locationNote = "Couldn't name this place. Type it in."
            } finally {
                locating = false
            }
        }
    }

    /**
     * Places on the map matching what is typed (the app's place search), so any place can be picked, not only the
     * trip's own (owner, 2026-11-11: "keep the ability in journal to add another place, not only the existing").
     */
    var foundPlaces by mutableStateOf<List<com.justdataplease.boringtravel.places.PlaceResult>>(emptyList())
        private set
    private var placeSearch: kotlinx.coroutines.Job? = null

    /** [name] typed as the place; looked up on the map after a short pause (three letters or more). */
    fun typePlace(name: String) {
        if (name.isNotBlank()) noPlace = false
        placeName = name
        placeSearch?.cancel()
        val q = name.trim()
        if (q.length < 3) { foundPlaces = emptyList(); return }
        placeSearch = viewModelScope.launch {
            kotlinx.coroutines.delay(PLACE_SEARCH_DELAY_MS)
            val near = lat?.let { la -> lng?.let { com.justdataplease.boringtravel.places.LatLng(la, it) } }
                ?: lastPoint?.let { com.justdataplease.boringtravel.places.LatLng(it.lat, it.lng) }
            foundPlaces = try {
                graph.places.search(q, near).take(PLACES_FOUND)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** A place found on the map: its name and its pin. */
    fun pickFound(p: com.justdataplease.boringtravel.places.PlaceResult) {
        placeSearch?.cancel()
        noPlace = false
        placeName = p.name
        lat = p.lat
        lng = p.lng
        foundPlaces = emptyList()
    }

    val dictation = Dictation(graph.app)
    private val merger = DictationMerger()
    private var dictationState: RichTextState? = null

    var autoDictateDone: Boolean
        get() = saved.get<Boolean>(KEY_DICTATED) == true
        set(v) {
            saved[KEY_DICTATED] = v
        }

    var autoPhotoDone: Boolean
        get() = saved.get<Boolean>(KEY_PHOTOS) == true
        set(value) { saved[KEY_PHOTOS] = value }

    private lateinit var base: Entry
    @Volatile private var everSaved = false
    @Volatile private var lastSaved: Draft? = null
    @Volatile private var finished = false
    private val saves = DraftSaveOrder()

    init {
        dictation.onText = { text, final -> onDictated(text, final) }
        viewModelScope.launch {
            load()
            autosave()
        }
    }

    val zone: ZoneId get() = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    val date: LocalDate get() = Instant.ofEpochMilli(occurredAt).atZone(zone).toLocalDate()

    private suspend fun load() {
        val existing = graph.journal.get(entryId)?.takeIf { it.deletedAt == null }
        val t = now()
        // Written from a plan item: in its trip, at its place, on its day (ItemNotes.newEntry).
        val about = if (existing == null) route.planItemId?.let { graph.plan.get(it) }?.takeIf { it.deletedAt == null } else null
        val tripId = existing?.tripId ?: route.tripId ?: graph.trips.currentOn(LocalDate.now())?.id
        base = existing
            ?: about?.let { ItemNotes.newEntry(entryId, it, graph.plan.items(it.tripId), t, ZoneId.systemDefault()) }
            ?: Entry(id = entryId, tripId = tripId, occurredAt = t, zoneId = TimeZone.getDefault().id, createdAt = t, updatedAt = t)
        val doc = EntryDoc.parse(base.body)
        everSaved = existing != null
        isNew = existing == null || (existing.title.isBlank() && doc.isEmpty)
        title = TextFieldValue(base.title)
        occurredAt = base.occurredAt
        zoneId = base.zoneId
        placeName = base.placeName
        lat = base.lat
        lng = base.lng
        noPlace = existing != null && !isNew && base.placeName.isBlank() && base.lat == null && base.lng == null
        tempC = base.tempC
        font = doc.font
        items.addAll(itemsOf(doc))
        ensureTrailingText()
        if (everSaved) lastSaved = draft()
        // A new entry opens ready to type.
        if (isNew) focusKey = items.firstOrNull { it is EdItem.Text }?.key
        loaded = true
        lastPoint = runCatching { graph.tracks.observeLatest().first() }.getOrNull()
        loadKnownPlaces()
        if (isNew && placeName.isBlank() && lat == null) fillPlace()
        if (route.fromCamera && saved.get<Boolean>(KEY_CAMERA) != true) {
            saved[KEY_CAMERA] = true
            // A photo just taken: the caret under it, ready for a line, and the place filled in.
            focusKey = items.lastOrNull { it is EdItem.Text }?.key
            placeShot()
        }
    }

    /**
     * Names where a camera shot was taken: its EXIF point, else the phone's location now (only if location access
     * was already given; never asks), else the latest route point, as for new entries.
     */
    private fun placeShot() {
        if (noPlace) return
        viewModelScope.launch {
            if (lat == null) {
                val here = runCatching { CurrentLocation.get(graph.app) }.getOrNull()
                if (here == null) {
                    if (placeName.isBlank()) fillPlace()
                    return@launch
                }
                if (lat != null) return@launch
                lat = here.first
                lng = here.second
            }
            val la = lat ?: return@launch
            val ln = lng ?: return@launch
            if (placeName.isNotBlank() || noPlace) return@launch
            val name = Places.reverse(graph.app, la, ln)
            if (name != null && placeName.isBlank() && !noPlace) placeName = name
        }
    }

    private fun itemsOf(doc: EntryDoc): List<EdItem> = EditorHtml.segments(doc.blocks).map { seg ->
        when (seg) {
            is EditorHtml.Segment.Text -> EdItem.Text(newState(EditorHtml.fromBlocks(seg.blocks)))
            is EditorHtml.Segment.Single -> when (val b = seg.block) {
                is Block.Quote -> EdItem.Quote(newState(EditorHtml.fromInline(b.html)))
                is Block.Photo -> EdItem.Photo(b)
                is Block.Album -> EdItem.Album(b)
                is Block.Checklist -> EdItem.Check(b.items)
                is Block.Place -> EdItem.Place(b)
                Block.Divider -> EdItem.Divider()
                Block.PageBreak -> EdItem.PageBreak()
                else -> EdItem.Kept(b)
            }
        }
    }

    private fun blocksOf(item: EdItem): List<Block> = when (item) {
        is EdItem.Text -> if (item.state.annotatedString.text.isBlank()) emptyList() else EditorHtml.toBlocks(item.state.toHtml())
        is EdItem.Quote -> if (item.state.annotatedString.text.isBlank()) emptyList() else {
            val html = EditorHtml.toInline(item.state.toHtml())
            if (RichHtml.toPlainText(html).isBlank()) emptyList() else listOf(Block.Quote(html))
        }
        is EdItem.Photo -> listOf(item.block)
        is EdItem.Album -> if (item.block.mediaIds.isEmpty()) emptyList() else listOf(item.block)
        is EdItem.Check -> item.rows.map { CheckItem(it.text.text.trim(), it.done) }.filter { it.text.isNotEmpty() }
            .let { if (it.isEmpty()) emptyList() else listOf(Block.Checklist(it)) }
        is EdItem.Place -> if (item.block.name.isBlank()) emptyList() else listOf(item.block)
        is EdItem.Divider -> listOf(Block.Divider)
        is EdItem.PageBreak -> listOf(Block.PageBreak)
        is EdItem.Kept -> listOf(item.block)
    }

    fun draft(): Draft = Draft(
        title = title.text.trim(),
        occurredAt = occurredAt,
        zoneId = zoneId,
        place = placeName.trim(),
        lat = lat,
        lng = lng,
        doc = EntryDoc(font, items.flatMap(::blocksOf)),
    )

    @OptIn(FlowPreview::class)
    private suspend fun autosave() {
        snapshotFlow { contentStamp() }.distinctUntilChanged().drop(1)
            .map { saves.next() }
            .debounce(AUTOSAVE_MS)
            .collect { revision -> if (!finished) persist(draft(), revision) }
    }

    /** HTML conversion belongs after the typing debounce, not on every key or photo-drag frame. */
    private fun contentStamp(): List<Any?> = listOf(
        title.text, occurredAt, zoneId, placeName, lat, lng, font,
        items.map { item -> item.key to when (item) {
            is EdItem.Text -> richContentStamp(item.state)
            is EdItem.Quote -> richContentStamp(item.state)
            is EdItem.Photo -> item.block
            is EdItem.Album -> item.block
            is EdItem.Check -> item.rows.map { CheckItem(it.text.text, it.done) }
            is EdItem.Place -> item.block
            is EdItem.Divider -> Block.Divider
            is EdItem.PageBreak -> Block.PageBreak
            is EdItem.Kept -> item.block
        } },
    )

    private suspend fun persist(d: Draft, revision: Long) = saves.save(revision) {
        if (d == lastSaved) return@save
        if (!everSaved && !d.hasContent) return@save
        graph.journal.save(
            base.copy(title = d.title, occurredAt = d.occurredAt, zoneId = d.zoneId, placeName = d.place, lat = d.lat, lng = d.lng, deletedAt = null),
            d.doc,
        )
        everSaved = true
        lastSaved = d
    }

    /** Saves now, on the app's scope so it finishes even if this screen goes away. */
    fun flush() {
        if (!loaded || finished) return
        val d = draft()
        val revision = saves.next()
        graph.scope.launch { persist(d, revision) }
    }

    /** Leaving the editor: save, or drop a new entry that stayed empty. False when already left (double tap). */
    fun finish(): Boolean {
        if (finished) return false
        if (importing) {
            notice = busyNotice
            return false
        }
        finished = true
        dictation.stop()
        if (!loaded) return true
        val d = draft()
        val revision = saves.next()
        val dropEmpty = isNew && !d.hasContent
        graph.scope.launch {
            if (dropEmpty) {
                saves.save(revision) { if (everSaved) graph.journal.delete(entryId) }
            } else {
                persist(d, revision)
            }
        }
        return true
    }

    override fun onCleared() {
        finish()
        dictation.release()
    }

    // ---- Blocks ----

    fun ensureTrailingText() {
        if (items.lastOrNull() !is EdItem.Text) items.add(EdItem.Text(newState("")))
    }

    private fun isBlankText(item: EdItem?): Boolean = item is EdItem.Text && item.state.annotatedString.text.isBlank()

    /** Inserts [new] where the writer is: at the caret of the active text (splitting it), after the active block, or at the end. */
    fun insertHere(new: List<EdItem>) {
        if (new.isEmpty()) return
        val idx = items.indexOfFirst { it.key == activeKey }
        val item = items.getOrNull(idx)
        when {
            item is EdItem.Text -> splitAndInsert(idx, item, new)
            idx >= 0 -> items.addAll(idx + 1, new)
            isBlankText(items.lastOrNull()) -> items.addAll(items.lastIndex, new)
            else -> items.addAll(new)
        }
        ensureTrailingText()
    }

    fun insertAt(index: Int, new: List<EdItem>) {
        items.addAll(index.coerceIn(0, items.size), new)
        ensureTrailingText()
    }

    private fun splitAndInsert(idx: Int, item: EdItem.Text, new: List<EdItem>) {
        val s = item.state
        val text = s.annotatedString.text
        val caret = s.selection.max.coerceIn(0, text.length)
        when {
            text.substring(caret).isBlank() -> items.addAll(idx + 1, new)
            text.substring(0, caret).isBlank() -> items.addAll(idx, new)
            else -> {
                val before = s.toHtml(TextRange(0, caret))
                val after = s.toHtml(TextRange(caret, text.length))
                s.setHtml(before)
                items.addAll(idx + 1, new + EdItem.Text(newState(after)))
            }
        }
    }

    fun newText(heading: Boolean = false): EdItem.Text =
        EdItem.Text(newState("").apply { if (heading) setHeadingStyle(HeadingStyle.H2) }).also { focusKey = it.key }

    fun newQuote(): EdItem.Quote = EdItem.Quote(newState("")).also { focusKey = it.key }

    fun newChecklist(): EdItem.Check = EdItem.Check(listOf(CheckItem(""))).also { focusKey = it.rows.first().key }

    fun newPlace(name: String): EdItem.Place = EdItem.Place(Block.Place(name.trim(), lat, lng))

    fun moveBefore(itemKey: String, beforeKey: String?) {
        val moving = items.firstOrNull { it.key == itemKey } ?: return
        val before = beforeKey?.let { key -> items.firstOrNull { it.key == key } ?: return }
        val reordered = moveBlockBefore(items.toList(), moving, before)
        if (reordered == items) return
        items.clear()
        items.addAll(reordered)
        mergeTexts()
        ensureTrailingText()
        activeKey = moving.key
    }

    fun remove(item: EdItem) {
        items.remove(item)
        when (item) {
            is EdItem.Photo -> {
                deleteIfUnused(item.block.mediaId)
                if (selectedPhotoKey == item.key) selectedPhotoKey = null
            }
            is EdItem.Album -> item.block.mediaIds.distinct().forEach(::deleteIfUnused)
            else -> Unit
        }
        if (activeKey == item.key) activeKey = null
        mergeTexts()
        ensureTrailingText()
    }

    /** Deletes photo [id] once no single photo or photo layout of the entry shows it (imports can repeat a photo). */
    private fun deleteIfUnused(id: String) {
        val used = items.any { (it is EdItem.Photo && it.block.mediaId == id) || (it is EdItem.Album && id in it.block.mediaIds) }
        if (!used) graph.scope.launch { graph.media.get(id)?.let { graph.media.delete(it) } }
    }

    // ---- Photo layouts ----

    /**
     * A single photo or video as Write places it, out of a layout where it was framed [from] (taken apart, or one
     * left): the full column, in that frame ([PhotoAlbums.singleFrame]).
     */
    private fun singlePhoto(id: String, from: PhotoFrame): EdItem.Photo =
        EdItem.Photo(Block.Photo(id, width = PhotoDefaults.Page.width, frame = PhotoAlbums.singleFrame(from)))

    /** [old] replaced by [new] in its place (nothing when it's gone). */
    private fun replace(old: EdItem, new: List<EdItem>) {
        val i = items.indexOf(old)
        if (i < 0) return
        items.removeAt(i)
        items.addAll(i, new)
        if (activeKey == old.key) activeKey = new.firstOrNull()?.key
        mergeTexts()
        ensureTrailingText()
    }

    /** [item] with [block], or as a single photo when one photo is left, or gone when none is. */
    private fun setAlbum(item: EdItem.Album, block: Block.Album) {
        when (block.mediaIds.size) {
            0 -> remove(item)
            1 -> replace(item, listOf(singlePhoto(block.mediaIds.single(), block.frameOf(block.mediaIds.single()))))
            else -> item.block = block
        }
    }

    fun albumLayout(item: EdItem.Album, layoutId: String) {
        item.block = PhotoAlbums.withLayout(item.block, layoutId)
    }

    /** Every photo and video of the layout in [frame] (None: plain), or with [index] only that one. */
    fun albumFrame(item: EdItem.Album, frame: PhotoFrame, index: Int? = null) {
        val id = index?.let { item.block.mediaIds.getOrNull(it) ?: return }
        item.block = if (id == null) item.block.withFrame(frame) else item.block.withFrame(id, frame)
    }

    fun albumSwap(item: EdItem.Album, i: Int, j: Int) {
        item.block = PhotoAlbums.swap(item.block, i, j)
    }

    fun albumMove(item: EdItem.Album, from: Int, to: Int) {
        item.block = PhotoAlbums.move(item.block, from, to)
    }

    /** Takes photo [index] out of the layout (and out of the entry, like removing a single photo). */
    fun albumRemovePhoto(item: EdItem.Album, index: Int) {
        val id = item.block.mediaIds.getOrNull(index) ?: return
        setAlbum(item, PhotoAlbums.remove(item.block, index))
        deleteIfUnused(id)
    }

    /** The layout's photos and videos as single ones, in their order, where the layout was. */
    fun albumToSingles(item: EdItem.Album) {
        replace(item, item.block.mediaIds.map { id -> singlePhoto(id, item.block.frameOf(id)) })
    }

    /** Moves a block up or down, one paragraph at a time when it passes a text. */
    fun move(item: EdItem, up: Boolean) {
        val i = items.indexOf(item)
        if (i < 0) return
        val j = if (up) i - 1 else i + 1
        val other = items.getOrNull(j) ?: return
        val blocks = (other as? EdItem.Text)?.let { EditorHtml.toBlocks(it.state.toHtml()) }
        if (other is EdItem.Text && blocks != null && blocks.size > 1) {
            if (up) {
                other.state.setHtml(EditorHtml.fromBlocks(blocks.dropLast(1)))
                items.add(i + 1, EdItem.Text(newState(EditorHtml.fromBlocks(blocks.takeLast(1)))))
            } else {
                other.state.setHtml(EditorHtml.fromBlocks(blocks.drop(1)))
                items.add(i, EdItem.Text(newState(EditorHtml.fromBlocks(blocks.take(1)))))
            }
        } else {
            items[i] = other
            items[j] = item
        }
        mergeTexts()
        ensureTrailingText()
    }

    /** Joins text areas that ended up next to each other; drops empty ones between blocks. */
    private fun mergeTexts() {
        var k = 0
        while (k < items.size - 1) {
            val a = items[k]
            val b = items[k + 1]
            if (a is EdItem.Text && b is EdItem.Text) {
                when {
                    isBlankText(a) -> items.removeAt(k)
                    isBlankText(b) -> items.removeAt(k + 1)
                    else -> {
                        a.state.setHtml(EditorHtml.fromBlocks(EditorHtml.toBlocks(a.state.toHtml()) + EditorHtml.toBlocks(b.state.toHtml())))
                        items.removeAt(k + 1)
                    }
                }
            } else {
                k++
            }
        }
    }

    fun addRow(check: EdItem.Check, after: CheckRow?) {
        val row = CheckRow("", false)
        val at = check.rows.indexOf(after).let { if (it < 0) check.rows.size else it + 1 }
        check.rows.add(at, row)
        focusKey = row.key
    }

    fun removeRow(check: EdItem.Check, row: CheckRow) {
        val at = check.rows.indexOf(row)
        check.rows.remove(row)
        if (check.rows.isEmpty()) remove(check) else focusKey = check.rows[(at - 1).coerceAtLeast(0)].key
    }

    /** A photo just taken with the camera from the editor: added like a picked one, then its cache file deleted. */
    fun addShot(shot: Shot, at: Int?) = addMedia(listOf(Picked(shot.uri)), at, after = { CameraShots.discard(graph.app, shot.uri) })

    /** A video just recorded: see [addMedia]. */
    fun addVideo(uri: Uri, at: Int?, after: () -> Unit = {}) = addMedia(listOf(Picked(uri, video = true)), at, after = after)

    /**
     * Imports what was picked, photos and videos together in the order picked, and puts it in the text (owner,
     * 2026-11-14: "where you have photos add the ability to select videos too", then "can we mix photos videos
     * layout"): one alone as a single photo or video, several in layouts ([PhotoAlbums.pickGroups]); with [intoAlbum]
     * (the key of a layout) they join that layout, filling its empty spots and growing it ([PhotoAlbums.add]), and
     * what it has no room for goes in a layout after it. New ones sit and are framed as the note's trip says (Edit
     * trip › Photos in notes; a layout takes the frame). [cancelVideo] stops a video being prepared and what is still
     * waiting; what is already in the note stays.
     */
    fun addMedia(picked: List<Picked>, at: Int?, intoAlbum: String? = null, after: () -> Unit = {}) {
        if (picked.isEmpty() || !loaded || finished || importing) return
        importing = true
        notice = null
        videoJob = viewModelScope.launch {
            try {
                val notes = ArrayList<String>()
                importAll(picked, PhotoDefaults.forTrip(graph, base.tripId), at, intoAlbum, notes)
                notice = notes.distinct().joinToString(" ").ifBlank { null }
            } finally {
                importing = false
                videoProgress = null
                videoJob = null
                after()
            }
        }
    }

    /**
     * [addMedia]'s work. A video is the whole of it, compressed first ([videoProgress] shows how far). Each photo or
     * video is in the note and saved as soon as it is imported, even if a later one fails; what went wrong is added to
     * [notes], and for a video that goes up to Drive (a backup or a shared trip) a short line that it takes a while to
     * upload ([JournalLogic.videoAdded]).
     */
    private suspend fun importAll(picked: List<Picked>, defaults: PhotoDefaults, at: Int?, intoAlbum: String?, notes: MutableList<String>) {
        val before = items.count { it is EdItem.Photo }
        val first = items.none { it is EdItem.Photo || it is EdItem.Album }
        var imported = 0
        var failed = 0
        val target = intoAlbum?.let { key -> items.firstOrNull { it.key == key } as? EdItem.Album }
        val placer = PhotoPlacer(target, PhotoAlbums.pickGroups(picked.size), target?.block?.let(PhotoAlbums::mainFrame) ?: defaults.frame)
        try {
            for (p in picked) {
                val (media, durationMs) = try {
                    if (p.video) {
                        videoProgress = 0f
                        graph.media.importVideo(p.uri, base.tripId, base.id) { videoProgress = it }.let { it.media to it.durationMs }
                    } else {
                        graph.media.import(p.uri, base.tripId, base.id) to null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: VideoImportException) {
                    e.message?.let { notes += it }
                    continue
                } catch (_: Exception) {
                    if (p.video) notes += "This video couldn't be added." else failed++
                    continue
                } finally {
                    videoProgress = null
                }
                placer.put(media.id, at) {
                    Block.Photo(it, rotation = if (p.video) 0f else defaults.tiltFor(before + imported), width = defaults.width, frame = defaults.frame)
                }
                // Each completed one is attached and saved immediately, even if a later one fails.
                flush()
                if (isNew && first && imported == 0) {
                    media.takenAt?.let {
                        if (abs(it - occurredAt) > 60_000L) {
                            photoTimeOffer = it
                            photoTimeLabel = if (p.video) "Use video time" else "Use photo time"
                        }
                    }
                }
                if (p.video) JournalLogic.videoAdded(durationMs, uploads = goesToDrive())?.let { notes += it }
                imported++
            }
            if (failed > 0) notes += if (failed == 1) "One photo couldn't be added." else "$failed photos couldn't be added."
        } finally {
            placer.settle()
        }
    }

    /** Stops the video being prepared; nothing of it stays. */
    fun cancelVideo() {
        videoJob?.cancel()
    }

    /** Stops the video being prepared, then, once it has cleaned up, runs [then] (leaving the note). */
    fun cancelVideoThen(then: () -> Unit) {
        val job = videoJob
        job?.cancel()
        viewModelScope.launch {
            job?.join()
            then()
        }
    }

    /** Whether this note's media go up to Google Drive: a Drive backup is on, or its trip is shared. */
    private suspend fun goesToDrive(): Boolean {
        val backup = runCatching { graph.settings.driveConnected.first() && graph.settings.autoBackup.first() }.getOrDefault(false)
        val shared = base.tripId?.let { trip -> runCatching { graph.together.state.value.of(trip) != null }.getOrDefault(false) } == true
        return backup || shared
    }

    /**
     * Puts imported photos and videos one by one where [importAll] wants them: into [target] (a layout) while it has
     * room, else in groups of [groups] sizes (a single one, or a layout that fills as they arrive), each after the
     * last. In a layout it makes or grows each one is framed [frame].
     */
    private inner class PhotoPlacer(private val target: EdItem.Album?, private val groups: List<Int>, private val frame: PhotoFrame) {
        private var previous: EdItem? = target

        /** The last item placed or grown; before any, the one this placer started after (null: none). */
        val last: EdItem? get() = previous

        private var group = 0
        private var inGroup = 0
        private val made = ArrayList<EdItem.Album>()

        private fun place(item: EdItem, at: Int?) {
            val previousIndex = previous?.let(items::indexOf)?.takeIf { it >= 0 }
            when {
                previousIndex != null -> insertAt(previousIndex + 1, listOf(item))
                at != null -> insertAt(at, listOf(item))
                else -> insertHere(listOf(item))
            }
            previous = item
        }

        private fun newAlbum(size: Int, id: String, at: Int?) =
            place(EdItem.Album(Block.Album(PhotoAlbums.usual(size).id, listOf(id)).withFrame(frame)).also { made += it }, at)

        fun put(id: String, at: Int?, single: (String) -> Block.Photo) {
            val last = previous as? EdItem.Album
            if (target != null) {
                // Into a layout (the asked one, then the one that took its overflow): fill and grow it.
                if (last != null && last in items) {
                    val added = PhotoAlbums.add(last.block, listOf(id), frame)
                    last.block = added.album
                    added.rest.forEach { newAlbum(2, it, at) }
                } else {
                    newAlbum(2, id, at)
                }
                return
            }
            val size = groups.getOrElse(group) { 1 }
            val growing = last?.takeIf { inGroup > 0 && it in items }
            when {
                size == 1 -> place(EdItem.Photo(single(id)), at)
                growing == null -> newAlbum(size, id, at)
                else -> growing.block = growing.block.copy(mediaIds = growing.block.mediaIds + id).withFrame(id, frame)
            }
            if (++inGroup >= size) {
                group++
                inGroup = 0
            }
        }

        /** A layout this import made that ended short (some failed): fitted to what it holds, or a single one. */
        fun settle() = made.forEach { a -> if (a in items) setAlbum(a, PhotoAlbums.reflow(a.block)) }
    }

    // ---- Details ----

    fun usePhotoTime() {
        photoTimeOffer?.let { at -> moveTo(at) }
        photoTimeOffer = null
    }

    fun setDate(d: LocalDate) {
        val t = Instant.ofEpochMilli(occurredAt).atZone(zone).toLocalTime()
        moveTo(d.atTime(t).atZone(zone).toInstant().toEpochMilli())
    }

    /** The note's time becomes [at]; on another day, "From this trip" follows that day's plan. */
    private fun moveTo(at: Long) {
        val before = date
        occurredAt = at
        if (date != before) loadKnownPlaces()
    }

    fun setTime(hour: Int, minute: Int) {
        val d = date
        occurredAt = d.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    }

    /** Fills an empty place from the latest route point when it is recent (a new note, a photo just taken); never asks. */
    private fun fillPlace() {
        val p = lastPoint ?: return
        if (noPlace || now() - p.time > RECENT_POINT_MS) return
        lat = p.lat
        lng = p.lng
        viewModelScope.launch {
            val name = Places.reverse(graph.app, p.lat, p.lng)
            if (!noPlace && name != null && placeName.isBlank()) placeName = name
        }
    }

    // ---- Dictation ----

    /** The rich text the writer is in (a text area or a quote), if any. */
    fun activeRich(): RichTextState? = when (val active = items.firstOrNull { it.key == activeKey }) {
        is EdItem.Text -> active.state
        is EdItem.Quote -> active.state
        else -> null
    }

    private fun lastTextState(): RichTextState? = (items.lastOrNull { it is EdItem.Text } as? EdItem.Text)?.state

    fun startDictation() {
        viewModelScope.launch {
            val tag = graph.settings.dictationLanguage.first() ?: Locale.getDefault().toLanguageTag()
            merger.reset()
            dictationState = null
            if (activeRich() == null) {
                ensureTrailingText()
                lastTextState()?.let { it.selection = TextRange(it.annotatedString.text.length) }
            }
            dictation.start(tag)
        }
    }

    fun stopDictation() = dictation.stop()

    private fun onDictated(text: String, final: Boolean) {
        // Leaving saves the visible transcript. A late recognizer result must not mutate that saved draft.
        if (finished) return
        if (merger.idle || dictationState == null) dictationState = activeRich() ?: lastTextState()
        val s = dictationState ?: return
        val target = RichTarget(s)
        if (final) merger.final(target, text) else merger.partial(target, text)
    }

    /** The editor's rich text as a dictation target. Paragraphs are separated by spaces in its text. */
    private class RichTarget(private val s: RichTextState) : DictationTarget {
        override val text: String get() = s.annotatedString.text
        override val caret: Int get() = s.selection.max

        override fun replace(start: Int, end: Int, with: String) {
            val len = text.length
            val a = start.coerceIn(0, len)
            val b = end.coerceIn(a, len)
            if (b > a) s.removeTextRange(TextRange(a, b))
            if (with.isNotEmpty()) s.addTextAtIndex(a, with) else s.selection = TextRange(a)
        }

        override fun isParagraphStart(index: Int): Boolean =
            index == 0 || s.annotatedString.paragraphStyles.any { it.start == index }
    }

    private companion object {
        const val KEY_ID = "entryId"
        const val KEY_DICTATED = "dictated"
        /** Typing pause before a map search, and how many places it offers. */
        const val PLACE_SEARCH_DELAY_MS = 450L
        const val PLACES_FOUND = 5
        const val KEY_CAMERA = "cameraPlaced"
        const val KEY_PHOTOS = "photosOpened"
        const val AUTOSAVE_MS = 800L
        const val RECENT_POINT_MS = 12 * 60 * 60_000L
    }
}
