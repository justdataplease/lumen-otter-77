package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoAlbums
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.doc.moveBlockBefore
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.PlaceResult
import com.justdataplease.boringtravel.speech.DictationMerger
import com.justdataplease.boringtravel.speech.DictationTarget
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.time.systemZoneId
import com.justdataplease.boringtravel.ui.editor.EditorHtml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The journal editor's state, as the Android app's WriteViewModel keeps it (ui/editor/WriteViewModel.kt): the note's
 * rows (text areas, quotes, photos, layouts, checklists, places, lines, page breaks), its title, font, time and place,
 * and every change the editor makes to them. It saves to the trips' store ([TripBook.saveNote]) as the Android one saves
 * to its database: shortly after each change, after each photo goes in, and when the editor is left.
 */

private var lastKey = 0L

/** One row of the editor. Text areas and quotes hold live rich text; the other rows hold their block. */
sealed class EdItem {
    val key: String = "item${++lastKey}"

    /** Paragraphs, list items and headings that follow each other, edited as one rich text. */
    class Text(val state: RichTextState) : EdItem()

    /** A quote's text; [original] as it was stored, written back while its text is [read] unchanged. */
    class Quote(val state: RichTextState, val original: Block.Quote? = null, val read: RichValue? = null) : EdItem()

    class Photo(block: Block.Photo) : EdItem() {
        var block by mutableStateOf(block)
    }

    /** Photos and videos in a layout; tapping it opens the layout sheet. */
    class Album(block: Block.Album) : EdItem() {
        var block by mutableStateOf(block)
    }

    /** A checklist's rows; [original] as stored, written back while the rows say the same. */
    class Check(items: List<CheckItem>, val original: Block.Checklist? = null) : EdItem() {
        val rows = mutableStateListOf<CheckRow>().apply { items.forEach { add(CheckRow(it.text, it.done)) } }
    }

    class Place(block: Block.Place) : EdItem() {
        var block by mutableStateOf(block)
    }

    class Divider : EdItem()
    class PageBreak : EdItem()

    /** A block from a newer app version: not shown, written back unchanged. */
    class Kept(val block: Block) : EdItem()
}

class CheckRow(text: String, done: Boolean) {
    val key: String = "row${++lastKey}"
    var text by mutableStateOf(TextFieldValue(text))
    var done by mutableStateOf(done)
}

/**
 * How a new photo sits on the page (the Android app's PhotoDefaults): [Page], the full column, rounded, straight, what
 * every look does; or a trip's own (Edit trip › Photos in notes: [of]).
 */
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
        fun of(style: com.justdataplease.boringtravel.data.settings.TripPhotoStyle) = PhotoDefaults(style.width, style.frame, 0f)
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

/**
 * The editor for note [entryId] of [book], or a new note in trip [tripId] (null: outside trips) written at [at]. Lives
 * with the Write screen.
 */
/** A route point younger than this names a new note's place (the Android editor's RECENT_POINT_MS). */
const val RECENT_POINT_MS = 12 * 60 * 60_000L

/**
 * The editor's line after a video went in, when it goes up to Drive ([uploads]): that it takes a while, with its length
 * when it is over a minute; null when it stays on the phone (the Android app's JournalLogic.videoAdded).
 */
fun videoAdded(durationMs: Long?, uploads: Boolean): String? = when {
    !uploads -> null
    durationMs != null && durationMs > 60_000 + com.justdataplease.boringtravel.data.repo.VideoLimits.SLACK_MS ->
        "This video is ${com.justdataplease.boringtravel.data.repo.VideoLimits.minutes(durationMs)} min long. It will take a while to upload."
    else -> "Videos take a while to upload."
}

class WriteModel(
    private val book: TripBook,
    private val kit: MediaKit,
    tripId: String?,
    entryId: String?,
    at: Long,
    /** A new note about this plan item (its sheet's Write): in its trip, at its place, on its day. */
    planItemId: String? = null,
    /** How new photos sit in a note of a trip (null: outside trips): its Edit trip › Photos in notes ([PhotoDefaults]). */
    private val photoDefaults: (tripId: String?) -> PhotoDefaults = { PhotoDefaults.Page },
    /** Whether the note's videos go up to Drive (Android's goesToDrive): then a line says it takes a while. */
    private val goesToDrive: () -> Boolean = { false },
) {
    val items = mutableStateListOf<EdItem>()
    var title by mutableStateOf(TextFieldValue(""))
    var font by mutableStateOf<String?>(null)
    var occurredAt by mutableLongStateOf(at)
        private set
    var zoneId by mutableStateOf(systemZoneId())
        private set
    var placeName by mutableStateOf("")
        private set
    var lat by mutableStateOf<Double?>(null)
        private set
    var lng by mutableStateOf<Double?>(null)
        private set
    var tempC by mutableStateOf<Double?>(null)
        private set

    /** The note has no place on purpose (Android's "No place" switch): no name, no pin. */
    var noPlace by mutableStateOf(false)
        private set

    /** A new note (or one still empty): offers "Use photo time" and is dropped if left empty. */
    var isNew by mutableStateOf(false)
        private set
    var photoTimeOffer by mutableStateOf<Long?>(null)
        private set

    /** "Use photo time", or "Use video time" when the offer came from a video. */
    var photoTimeLabel by mutableStateOf("Use photo time")
        private set

    /** The text, quote or block the writer last worked in; the toolbar's actions go there. */
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

    /** What [addMedia] is bringing in now: cancelling it stops a video being prepared and what still waits. */
    private var importJob: Job? = null

    /** What to say while photos or a video are still going in (leaving, or adding more meanwhile). */
    val busyNotice: String
        get() = if (videoProgress != null) "Preparing the video. Please wait a moment." else "Finishing your photos. Please wait a moment."

    private val base: Entry
    private var everSaved = false
    private var lastSaved: Draft? = null
    var finished = false
        private set
    private val importer = MediaImporter(kit.files, videos = kit.videos)

    /** The note's id: the one opened, or the new one's. */
    val id: String get() = base.id

    /** False for a note another member of a shared trip wrote: only read. */
    val editable: Boolean

    init {
        val existing = entryId?.let(book::entry)
        // Written from a plan item: in its trip, at its place, on its day (Android's ItemNotes.newEntry).
        val about = if (existing == null) planItemId?.let(book::item)?.takeIf { it.deletedAt == null } else null
        base = existing
            ?: about?.let { ItemNotes.newEntry(newId(), it, book.items(it.tripId), at, systemZoneId()).copy(createdAt = 0L, updatedAt = 0L) }
            ?: Entry(id = newId(), tripId = tripId, occurredAt = at, zoneId = systemZoneId(), createdAt = 0L, updatedAt = 0L)
        editable = base.authorId == null
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
        // A new note opens ready to type.
        if (isNew) focusKey = items.firstOrNull { it is EdItem.Text }?.key
    }

    val date: LocalDate get() = localDateAt(occurredAt, zoneId)

    /** The note's time of day in minutes after midnight. */
    val minutes: Int get() = ((occurredAt - startOfDayMs(date, zoneId)) / 60_000L).toInt().coerceIn(0, 24 * 60 - 1)

    private fun itemsOf(doc: EntryDoc): List<EdItem> = EditorHtml.segments(doc.blocks).map { seg ->
        when (seg) {
            is EditorHtml.Segment.Text -> EdItem.Text(RichTextState(RichValue.of(seg.blocks)))
            is EditorHtml.Segment.Single -> when (val b = seg.block) {
                is Block.Quote -> RichValue.ofQuote(b.html).let { EdItem.Quote(RichTextState(it), b, it) }
                is Block.Photo -> EdItem.Photo(b)
                is Block.Album -> EdItem.Album(b)
                is Block.Checklist -> EdItem.Check(b.items, b)
                is Block.Place -> EdItem.Place(b)
                Block.Divider -> EdItem.Divider()
                Block.PageBreak -> EdItem.PageBreak()
                else -> EdItem.Kept(b)
            }
        }
    }

    private fun blocksOf(item: EdItem): List<Block> = when (item) {
        is EdItem.Text -> if (item.state.text.isBlank()) emptyList() else item.state.value.toBlocks()
        is EdItem.Quote -> when {
            item.state.text.isBlank() -> emptyList()
            item.original != null && item.state.value == item.read -> listOf(item.original)
            else -> {
                // The quote's paragraphs as one fragment, joined by line breaks (the Android app's EditorHtml.toInline).
                val html = item.state.value.toBlocks().joinToString("<br>") { b ->
                    when (b) {
                        is Block.Paragraph -> b.html
                        is Block.Heading -> RichHtml.escape(b.text)
                        else -> ""
                    }
                }
                if (RichHtml.toPlainText(html).isBlank()) emptyList() else listOf(Block.Quote(html))
            }
        }
        is EdItem.Photo -> listOf(item.block)
        is EdItem.Album -> if (item.block.mediaIds.isEmpty()) emptyList() else listOf(item.block)
        is EdItem.Check -> {
            val rows = item.rows.map { CheckItem(it.text.text, it.done) }
            val original = item.original
            if (original != null && rows == original.items) listOf(original)
            else rows.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }.let { if (it.isEmpty()) emptyList() else listOf(Block.Checklist(it)) }
        }
        is EdItem.Place -> if (item.block.name.isBlank()) emptyList() else listOf(item.block)
        is EdItem.Divider -> listOf(Block.Divider)
        is EdItem.PageBreak -> listOf(Block.PageBreak)
        is EdItem.Kept -> listOf(item.block)
    }

    fun draft(): Draft = Draft(title.text.trim(), occurredAt, zoneId, placeName.trim(), lat, lng, EntryDoc(font, items.flatMap(::blocksOf)))

    /** What a save would write, cheap to compare: the autosave runs when it changes. */
    fun contentStamp(): List<Any?> = listOf(
        title.text, occurredAt, zoneId, placeName, lat, lng, font, noPlace,
        items.map { item ->
            item.key to when (item) {
                is EdItem.Text -> item.state.value
                is EdItem.Quote -> item.state.value
                is EdItem.Photo -> item.block
                is EdItem.Album -> item.block
                is EdItem.Check -> item.rows.map { CheckItem(it.text.text, it.done) }
                is EdItem.Place -> item.block
                is EdItem.Divider -> Block.Divider
                is EdItem.PageBreak -> Block.PageBreak
                is EdItem.Kept -> item.block
            }
        },
    )

    /** Saves [d] unless it is what was saved last; a new note is made by its first save with something in it. */
    fun persist(d: Draft = draft()) {
        if (!editable || d == lastSaved) return
        if (!everSaved && !d.hasContent) return
        val saved = book.saveNote(
            base.copy(title = d.title, occurredAt = d.occurredAt, zoneId = d.zoneId, placeName = d.place, lat = d.lat, lng = d.lng, deletedAt = null),
            d.doc,
        )
        if (saved != null) {
            everSaved = true
            lastSaved = d
        }
    }

    /** Leaving the editor: save, or drop a new note that stayed empty. False while photos are still going in, or when already left. */
    fun finish(): Boolean {
        if (finished) return false
        if (importing) {
            notice = busyNotice
            return false
        }
        finished = true
        val d = draft()
        if (isNew && !d.hasContent) {
            if (everSaved) book.removeNote(base.id)
        } else persist(d)
        return true
    }

    // ---- When and where ----

    fun usePhotoTime() {
        photoTimeOffer?.let { occurredAt = it }
        photoTimeOffer = null
    }

    /** Another day, at the same time of day. */
    fun setDate(d: LocalDate) {
        occurredAt = startOfDayMs(d, zoneId) + (occurredAt - startOfDayMs(date, zoneId))
    }

    fun setTime(minutes: Int) {
        occurredAt = startOfDayMs(date, zoneId) + minutes * 60_000L
    }

    /** [name] typed as the place. */
    fun typePlace(name: String) {
        if (name.isNotBlank()) noPlace = false
        placeName = name
    }

    /** Turns "No place" on (the place and its pin go) or off. */
    fun pickNoPlace(on: Boolean) {
        noPlace = on
        if (!on) return
        placeName = ""
        lat = null
        lng = null
    }

    /**
     * The trip's own places to pick from (the plan, the stops, earlier notes: [TripPlaces]), the note's day's plan
     * first; none outside a trip.
     */
    fun knownPlaces(): List<KnownPlace> {
        val trip = base.tripId ?: return emptyList()
        val stops = book.snapshot.stops.filter { it.tripId == trip }
        return TripPlaces.of(book.items(trip), stops, book.entries(trip), date, exceptEntry = base.id)
    }

    /** A place picked from [known]: its name ("Alhambra (Granada)" for a plan item) and its point. */
    fun pickPlace(p: KnownPlace, known: List<KnownPlace>) {
        noPlace = false
        placeName = p.name
        val point = TripPlaces.pointAfterPick(known, p, lat, lng)
        lat = point?.lat
        lng = point?.lng
    }

    /** A place found on the map: its name and its pin. */
    fun pickFound(p: PlaceResult) {
        noPlace = false
        placeName = p.name
        lat = p.lat
        lng = p.lng
    }

    /** Where the phone is ("Use my location"): the pin, and the name when the phone's geocoder gave one. */
    fun located(at: LatLng, name: String?) {
        noPlace = false
        lat = at.lat
        lng = at.lng
        if (name != null) placeName = name
    }

    /**
     * A new note with no place yet takes the last route point when it is under 12 hours old, never asking (the Android
     * editor's fillPlace): its pin at once, its name once [name] (the phone's geocoder) answers, if the place is still
     * empty then. Nothing for a note already saved, one set to No place, or an older point.
     */
    suspend fun fillPlace(point: com.justdataplease.boringtravel.data.db.TrackPoint?, now: Long, name: suspend (Double, Double) -> String?) {
        val p = point ?: return
        if (!isNew || noPlace || placeName.isNotBlank() || lat != null || now - p.time > RECENT_POINT_MS) return
        lat = p.lat
        lng = p.lng
        val named = runCatching { name(p.lat, p.lng) }.getOrNull()
        if (!noPlace && named != null && placeName.isBlank()) placeName = named
    }

    /**
     * Names where a camera shot was taken (the Android editor's placeShot): its own point, else where the phone is now
     * ([here]: only when location access was given already; never asks), else the latest route [point] under 12 h old,
     * as for new notes; then the point's name ([name], the phone's geocoder) when the note has none yet.
     */
    suspend fun placeShot(
        here: suspend () -> Pair<Double, Double>?,
        point: com.justdataplease.boringtravel.data.db.TrackPoint?,
        now: Long,
        name: suspend (Double, Double) -> String?,
    ) {
        if (noPlace) return
        if (lat == null) {
            val fix = runCatching { here() }.getOrNull()
            if (fix == null) {
                // Android's fillPlace: the latest route point when it is recent, whatever the note.
                val p = point ?: return
                if (placeName.isNotBlank() || now - p.time > RECENT_POINT_MS) return
                lat = p.lat
                lng = p.lng
                val named = runCatching { name(p.lat, p.lng) }.getOrNull()
                if (!noPlace && named != null && placeName.isBlank()) placeName = named
                return
            }
            if (lat != null) return
            lat = fix.first
            lng = fix.second
        }
        val la = lat ?: return
        val ln = lng ?: return
        if (placeName.isNotBlank() || noPlace) return
        val named = runCatching { name(la, ln) }.getOrNull()
        if (named != null && placeName.isBlank() && !noPlace) placeName = named
    }

    // ---- Blocks ----

    fun ensureTrailingText() {
        if (items.lastOrNull() !is EdItem.Text) items.add(EdItem.Text(RichTextState()))
    }

    // ---- Dictation (the Android editor's WriteViewModel) ----

    private val merger = DictationMerger()
    private var dictating: RichTextState? = null

    /** The rich text the writer is in (a text area or a quote), if any. */
    fun activeRich(): RichTextState? = when (val active = items.firstOrNull { it.key == activeKey }) {
        is EdItem.Text -> active.state
        is EdItem.Quote -> active.state
        else -> null
    }

    private fun lastTextState(): RichTextState? = (items.lastOrNull { it is EdItem.Text } as? EdItem.Text)?.state

    /** Before dictation starts: the words go where the writer is, else at the end of the note's last text. */
    fun prepareDictation() {
        merger.reset()
        dictating = null
        if (activeRich() == null) {
            ensureTrailingText()
            lastTextState()?.let { it.selection = TextRange(it.text.length) }
        }
    }

    /** A partial or final result: written at the caret of the text or quote being written ([DictationMerger]). */
    fun onDictated(text: String, final: Boolean) {
        // Leaving saves the visible words. A late result must not change that saved note.
        if (finished) return
        if (merger.idle || dictating == null) dictating = activeRich() ?: lastTextState()
        val s = dictating ?: return
        val target = RichTarget(s)
        if (final) merger.final(target, text) else merger.partial(target, text)
    }

    /** A text area as dictation's target: its paragraphs end with "\n", so a paragraph's start is after one. */
    private class RichTarget(private val s: RichTextState) : DictationTarget {
        override val text: String get() = s.text
        override val caret: Int get() = s.selection.max

        override fun replace(start: Int, end: Int, with: String) {
            val len = text.length
            val a = start.coerceIn(0, len)
            val b = end.coerceIn(a, len)
            s.setValue(s.value.replace(a, b, with, s.value.inkAt(a, b)), a + with.length)
        }
    }

    private fun isBlankText(item: EdItem?): Boolean = item is EdItem.Text && item.state.text.isBlank()

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
        val text = s.text
        val caret = s.selection.max.coerceIn(0, text.length)
        when {
            text.substring(caret).isBlank() -> items.addAll(idx + 1, new)
            text.substring(0, caret).isBlank() -> items.addAll(idx, new)
            else -> {
                val v = s.value
                val before = v.slice(0, if (text[caret - 1] == '\n') caret - 1 else caret)
                val after = v.slice(if (text[caret] == '\n') caret + 1 else caret, text.length)
                s.setValue(before)
                items.addAll(idx + 1, new + EdItem.Text(RichTextState(after)))
            }
        }
    }

    fun newText(heading: Boolean = false): EdItem.Text =
        EdItem.Text(RichTextState(if (heading) RichValue.Empty.copy(lines = listOf(RichLine(heading = 2))) else RichValue.Empty)).also { focusKey = it.key }

    fun newQuote(): EdItem.Quote = EdItem.Quote(RichTextState()).also { focusKey = it.key }

    fun newChecklist(): EdItem.Check = EdItem.Check(listOf(CheckItem(""))).also { focusKey = it.rows.first().key }

    fun newPlace(name: String): EdItem.Place = EdItem.Place(Block.Place(name.trim(), lat, lng))

    fun moveBefore(itemKey: String, beforeKey: String?) {
        val moving = items.firstOrNull { it.key == itemKey } ?: return
        val before = beforeKey?.let { key -> items.firstOrNull { it.key == key } ?: return }
        val reordered = moveBlockBefore(items.toList(), moving, before)
        if (reordered == items.toList()) return
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

    /** Deletes photo [id] once no single photo or photo layout of the note shows it (the file stays, as on Android). */
    private fun deleteIfUnused(id: String) {
        val used = items.any { (it is EdItem.Photo && it.block.mediaId == id) || (it is EdItem.Album && id in it.block.mediaIds) }
        if (!used) book.removeMedia(id)
    }

    // ---- Photo layouts ----

    /** A single photo or video out of a layout where it was framed [from]: the full column, in that frame. */
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

    /** Every photo and video of the layout in [frame], or with [index] only that one. */
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

    /** Takes photo [index] out of the layout (and out of the note, like removing a single photo). */
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
        val paragraphs = (other as? EdItem.Text)?.state?.value?.split()?.filter { it.text.isNotBlank() }
        if (other is EdItem.Text && paragraphs != null && paragraphs.size > 1) {
            if (up) {
                other.state.setValue(paragraphs.dropLast(1).reduce { a, b -> a + b })
                items.add(i + 1, EdItem.Text(RichTextState(paragraphs.last())))
            } else {
                other.state.setValue(paragraphs.drop(1).reduce { a, b -> a + b })
                items.add(i, EdItem.Text(RichTextState(paragraphs.first())))
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
                        a.state.setValue(a.state.value + b.state.value)
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

    // ---- Photos and videos ----

    /**
     * Brings in what was picked, photos and videos together in the order picked, and puts it in the text (as the Android
     * editor's addMedia): one alone as a single photo or video, several in layouts ([PhotoAlbums.pickGroups]); with
     * [intoAlbum] (a layout's key) they join that layout, filling its empty spots and growing it, and what it has no room
     * for goes in a layout after it. New ones sit and are framed as the note's trip says (Edit trip › Photos in notes; a
     * layout takes the frame). A video is the whole of it made 720p first ([videoProgress] shows how far; [cancelVideo]
     * stops it and what still waits). Each one is in the note and saved as soon as it is in, even if a later one fails;
     * what went wrong is said in [notice]. Runs in [scope] (the editor's).
     */
    fun addMedia(scope: CoroutineScope, picked: List<PickedMedia>, at: Int?, intoAlbum: String? = null): Job? {
        if (picked.isEmpty()) return null
        if (finished || importing || !editable) {
            picked.forEach { kit.files.delete(it.path) }
            return null
        }
        importing = true
        notice = null
        val job = scope.launch {
            try {
                val notes = ArrayList<String>()
                importAll(picked, photoDefaults(base.tripId), at, intoAlbum, notes)
                notice = notes.distinct().joinToString(" ").ifBlank { null }
            } finally {
                importing = false
                videoProgress = null
                importJob = null
                // What was picked and never reached (stopped): its copies go.
                picked.forEach { kit.files.delete(it.path) }
            }
        }
        importJob = job
        return job
    }

    /** Stops the video being prepared; nothing of it stays (Android's cancelVideo). */
    fun cancelVideo() {
        importJob?.cancel()
    }

    /** Stops the video being prepared, then, once it has cleaned up, runs [then] in [scope] (leaving the note). */
    fun cancelVideoThen(scope: CoroutineScope, then: () -> Unit) {
        val job = importJob
        job?.cancel()
        scope.launch {
            job?.join()
            then()
        }
    }

    private suspend fun importAll(picked: List<PickedMedia>, defaults: PhotoDefaults, at: Int?, intoAlbum: String?, notes: MutableList<String>) {
        val before = items.count { it is EdItem.Photo }
        val first = items.none { it is EdItem.Photo || it is EdItem.Album }
        var imported = 0
        var failed = 0
        val target = intoAlbum?.let { key -> items.firstOrNull { it.key == key } as? EdItem.Album }
        val placer = PhotoPlacer(target, PhotoAlbums.pickGroups(picked.size), target?.block?.let(PhotoAlbums::mainFrame) ?: defaults.frame)
        try {
            for (p in picked) {
                var durationMs: Long? = null
                val media = try {
                    if (p.video) {
                        videoProgress = 0f
                        importer.prepareVideo(p, base.tripId, base.id) { videoProgress = it }.also { durationMs = it.durationMs }.media
                    } else {
                        withContext(MediaWork) { importer.prepare(p, base.tripId, base.id) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: MediaImportException) {
                    if (p.video) notes += e.message.orEmpty() else failed++
                    continue
                } catch (_: Exception) {
                    if (p.video) notes += "This video couldn't be added." else failed++
                    continue
                } finally {
                    videoProgress = null
                }
                book.addMedia(media)
                placer.put(media.id, at) {
                    Block.Photo(it, rotation = if (p.video) 0f else defaults.tiltFor(before + imported), width = defaults.width, frame = defaults.frame)
                }
                // Each one in is saved at once, even if a later one fails.
                persist()
                if (isNew && first && imported == 0) {
                    media.takenAt?.let {
                        if (abs(it - occurredAt) > 60_000L) {
                            photoTimeOffer = it
                            photoTimeLabel = if (p.video) "Use video time" else "Use photo time"
                        }
                    }
                }
                if (p.video) videoAdded(durationMs, uploads = goesToDrive())?.let { notes += it }
                imported++
            }
            if (failed > 0) notes += if (failed == 1) "One photo couldn't be added." else "$failed photos couldn't be added."
        } finally {
            placer.settle()
            persist()
        }
    }

    /**
     * Puts imported photos and videos one by one where [importAll] wants them: into [target] (a layout) while it has
     * room, else in groups of [groups] sizes (a single one, or a layout that fills as they arrive), each after the
     * last. In a layout it makes or grows each one is framed [frame].
     */
    private inner class PhotoPlacer(private val target: EdItem.Album?, private val groups: List<Int>, private val frame: PhotoFrame) {
        private var previous: EdItem? = target
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
}
