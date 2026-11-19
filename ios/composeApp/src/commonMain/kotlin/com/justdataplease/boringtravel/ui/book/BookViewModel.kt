package com.justdataplease.boringtravel.ui.book

import com.justdataplease.boringtravel.book.BookDoc
import com.justdataplease.boringtravel.book.BookEdit
import com.justdataplease.boringtravel.book.BookFacts
import com.justdataplease.boringtravel.book.BookFormat
import com.justdataplease.boringtravel.book.BookJson
import com.justdataplease.boringtravel.book.BookLayout
import com.justdataplease.boringtravel.book.BookPage
import com.justdataplease.boringtravel.book.BookSpec
import com.justdataplease.boringtravel.book.EditHistory
import com.justdataplease.boringtravel.book.FreeEdit
import com.justdataplease.boringtravel.book.Template
import com.justdataplease.boringtravel.data.db.BookDraft
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.export.Paper
import com.justdataplease.boringtravel.export.TripBook
import com.justdataplease.boringtravel.ios.BookExports
import com.justdataplease.boringtravel.ios.ExportException
import com.justdataplease.boringtravel.ios.MediaWork
import com.justdataplease.boringtravel.ios.PickedMedia
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The book editor's state: the trip, the draft's spec and pages, undo, and photo previews (the Android app's
 * ui/book/BookViewModel.kt, written again with its members, its State and its rules; Android's reads AppGraph and
 * Room, this one the trip store through [books]). Every edit is a pure [BookEdit] step plus, where text moved,
 * [BookLayout.reflow]; the draft is saved shortly after each change.
 *
 * [scope] is the screen's (Android: viewModelScope); [appScope] outlives it, for the last save after the screen is left
 * (Android: AppGraph.scope; [close] is Android's onCleared). [printTheme] is the Share screen's style, a new book's look.
 * [import] brings photos picked on the phone into the trip as loose photos and answers their rows (Android:
 * MediaRepository.import with no entry). [now] stamps the saves.
 */
class BookViewModel(
    val books: BookExports,
    private val tripId: String,
    private val printTheme: () -> PrintTheme,
    private val scope: CoroutineScope,
    private val appScope: CoroutineScope? = null,
    private val import: suspend (List<PickedMedia>) -> List<Media> = { emptyList() },
    private val now: () -> Long = ::nowMs,
) {
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val trip: TripBook? = null,
        val facts: BookFacts? = null,
        val draft: BookDraft? = null,
        val spec: BookSpec = BookSpec(),
        val doc: BookDoc = BookDoc(),
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        /** Media id → address of a screen-size copy beside the page's HTML, for the page preview. */
        val previews: Map<String, String> = emptyMap(),
        val busy: Boolean = false,
        /** Photos just brought in from the phone ([addFromPhone]), newest first: the pickers list them first. */
        val fresh: List<String> = emptyList(),
        /** Photos from the phone are being copied in. */
        val importing: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val history = EditHistory<Pair<BookSpec, BookDoc>>(MAX_UNDO)

    /** The document a live change (a sheet open with its changes showing) started from; null when none is open. */
    private var liveBase: BookDoc? = null
    private var saveJob: Job? = null

    /** Every text run being poured again after an edit ([pourSoon]); null or done when the pages are settled. */
    private var pourJob: Job? = null

    /**
     * A change not saved yet, and a pour not finished yet: what [close] still has to do. Kept apart from the jobs, which
     * the screen's scope may have cancelled by the time the screen is left (Android's onCleared runs before its scope ends).
     */
    private var unsaved = false
    private var pouring = false

    init {
        load()
    }

    /** The layout for [s] (format, theme, fonts) measured with the phone's fonts. */
    fun layout(s: State = _state.value): BookLayout? = s.facts?.let { BookLayout(s.spec, it, books.measure) }

    private fun load() = scope.launch {
        try {
            val trip = books.loadTrip(tripId)
            val facts = withContext(Dispatchers.Default) { BookFacts(trip) }
            // The fonts the pages name, beside them before a page shows (Android's are the app's own files).
            withContext(MediaWork) { books.writeFonts() }
            val saved = books.latest(tripId)
            if (saved != null) {
                val doc = withContext(Dispatchers.Default) { BookJson.decode(saved.pages) }
                val spec = books.spec(saved, doc)
                _state.update { it.copy(loading = false, trip = trip, facts = facts, draft = saved, spec = spec, doc = doc) }
            } else {
                val theme = printTheme()
                val format = if (Paper.default() == Paper.Letter) BookFormat.Letter else BookFormat.A5
                val spec = BookSpec(format = format, theme = theme)
                val doc = withContext(Dispatchers.Default) { BookLayout(spec, facts, books.measure).auto() }
                val draft = books.newDraft(tripId, spec, doc)
                books.save(draft)
                _state.update { it.copy(loading = false, trip = trip, facts = facts, draft = draft, spec = spec, doc = doc) }
            }
            loadPreviews(trip)
        } catch (e: ExportException) {
            _state.update { it.copy(loading = false, error = e.message) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = "Couldn't open the book. Try again.") }
        }
    }

    /**
     * Photos picked on the phone join the trip (not an entry: the book's own loose photos) and show first wherever the
     * book offers the trip's photos. The pages stay as they are; only what can be chosen grows.
     */
    fun addFromPhone(picked: List<PickedMedia>) {
        if (picked.isEmpty() || _state.value.importing) return
        _state.update { it.copy(importing = true) }
        scope.launch {
            try {
                val added = import(picked).map { it.id }
                if (added.isNotEmpty()) {
                    val trip = books.loadTrip(tripId)
                    val facts = withContext(Dispatchers.Default) { BookFacts(trip) }
                    _state.update { it.copy(trip = trip, facts = facts, fresh = added.reversed() + it.fresh) }
                    loadPreviews(trip)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The photos that came in stay; the rest can be picked again.
            } finally {
                _state.update { it.copy(importing = false) }
            }
        }
    }

    /** Screen-size copies of the trip's photos, a few at a time so the first pages show quickly. */
    private suspend fun loadPreviews(trip: TripBook) {
        val placed = _state.value.doc.pages.flatMap { it.mediaIds }.distinct()
        val rest = trip.media.filter { it.deletedAt == null }.map { it.id }.filter { it !in placed }
        (placed + rest).chunked(4).forEach { chunk ->
            val urls = chunk.mapNotNull { id -> books.previewUrl(trip, id)?.let { id to it } }
            if (urls.isNotEmpty()) _state.update { it.copy(previews = it.previews + urls) }
        }
    }

    /**
     * Applies [change]; [reflowPage] pours that page's text run again, [reflowAll] every run. A change that changes
     * nothing is not an undo step. Pouring every run grows with the book, so it isn't done on the main thread: the
     * change shows at once and the text settles a moment later ([pourSoon]).
     */
    fun edit(reflowPage: String? = null, reflowAll: Boolean = false, change: (BookDoc) -> BookDoc) {
        endLive()
        val s = _state.value
        val layout = layout(s) ?: return
        var d = change(s.doc)
        if (!reflowAll && reflowPage != null) d = layout.reflow(d, reflowPage)
        if (d == s.doc) return
        push(s)
        _state.update { it.copy(doc = d, canUndo = history.canUndo, canRedo = history.canRedo) }
        scheduleSave()
        if (reflowAll) pourSoon()
    }

    /**
     * Pours every text run again off the main thread and shows the result when the document is still the one it was
     * poured from; a change made meanwhile is poured again on top of it. Not an undo step of its own: it belongs to
     * the edit that asked for it.
     */
    private fun pourSoon() {
        pourJob?.cancel()
        pouring = true
        pourJob = scope.launch {
            while (true) {
                val from = _state.value
                val layout = layout(from) ?: return@launch
                val poured = withContext(Dispatchers.Default) { layout.reflow(from.doc) }
                val now = _state.value
                if (now.doc !== from.doc || now.spec != from.spec) continue
                pouring = false
                if (poured != from.doc) {
                    _state.update { it.copy(doc = poured) }
                    scheduleSave()
                }
                return@launch
            }
        }
    }

    /** A pour still on its way is finished here and now: what follows (a change shown live) starts from settled pages. */
    private fun pourNow() {
        val job = pourJob?.takeIf { it.isActive } ?: return
        job.cancel()
        pourJob = null
        pouring = false
        val s = _state.value
        val layout = layout(s) ?: return
        val poured = layout.reflow(s.doc)
        if (poured != s.doc) _state.update { it.copy(doc = poured) }
    }

    /**
     * A change shown while a sheet is open: [change] is applied to the document as it was when the sheet opened, so
     * every call replaces the last; the whole visit is one undo step ([endLive]).
     */
    fun live(reflowPage: String? = null, change: (BookDoc) -> BookDoc) {
        if (liveBase == null) pourNow()
        val s = _state.value
        val layout = layout(s) ?: return
        val base = liveBase ?: s.doc.also {
            liveBase = it
            push(s)
        }
        var d = change(base)
        if (reflowPage != null) d = layout.reflow(d, reflowPage)
        _state.update { it.copy(doc = d, canUndo = history.canUndo, canRedo = history.canRedo) }
        scheduleSave()
    }

    /** Ends a live change; one that changed nothing leaves no undo step. */
    fun endLive() {
        val base = liveBase ?: return
        liveBase = null
        if (_state.value.doc == base) {
            history.dropLast()
            _state.update { it.copy(canUndo = history.canUndo, canRedo = history.canRedo) }
        }
    }

    /** Page [pageId]'s printed number (templates mirror on left-hand pages). */
    fun number(pageId: String, s: State = _state.value): Int = layout(s)?.number(s.doc, pageId) ?: 1

    fun newElementId(): String = com.justdataplease.boringtravel.data.repo.newId()

    /** A new format, theme, fonts, paper or bleed: the text runs are poured again for the new pages. */
    fun setSpec(spec: BookSpec) {
        val s = _state.value
        val facts = s.facts ?: return
        if (spec == s.spec) return
        endLive()
        push(s)
        scope.launch {
            _state.update { it.copy(busy = true) }
            // Elements placed by hand move with the page to a new size.
            val placed = FreeEdit.rescale(s.doc.copy(paper = spec.paper), s.spec.format, spec.format)
            val doc = withContext(Dispatchers.Default) { BookLayout(spec, facts, books.measure).reflow(placed) }
            _state.update { it.copy(spec = spec, doc = doc, canUndo = history.canUndo, canRedo = history.canRedo, busy = false) }
            scheduleSave()
        }
    }

    /** Builds the first layout again from the trip (undo brings the old one back). */
    fun resetLayout() {
        val s = _state.value
        val facts = s.facts ?: return
        endLive()
        push(s)
        scope.launch {
            _state.update { it.copy(busy = true) }
            val doc = withContext(Dispatchers.Default) { BookLayout(s.spec, facts, books.measure).auto().copy(padTo4 = s.doc.padTo4) }
            _state.update { it.copy(doc = doc, canUndo = history.canUndo, canRedo = history.canRedo, busy = false) }
            scheduleSave()
        }
    }

    fun undo() {
        endLive()
        val s = _state.value
        val (spec, doc) = history.undo(s.spec to s.doc) ?: return
        _state.update { it.copy(spec = spec, doc = doc, canUndo = history.canUndo, canRedo = history.canRedo) }
        scheduleSave()
        // A step kept while its text was still being poured comes back settled.
        pourSoon()
    }

    fun redo() {
        endLive()
        val s = _state.value
        val (spec, doc) = history.redo(s.spec to s.doc) ?: return
        _state.update { it.copy(spec = spec, doc = doc, canUndo = history.canUndo, canRedo = history.canRedo) }
        scheduleSave()
        pourSoon()
    }

    private fun push(s: State) = history.push(s.spec to s.doc)

    fun newPage(t: Template, day: LocalDate?): BookPage = BookEdit.blank(t, newElementId(), day)

    private fun scheduleSave() {
        unsaved = true
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            saveNow()
        }
    }

    private suspend fun saveNow() {
        val s = _state.value
        val draft = s.draft ?: return
        // The JSON holds every page's text: written off the main thread.
        val pages = withContext(Dispatchers.Default) { BookJson.encode(s.doc) }
        val updated = draft.copy(
            format = s.spec.format.id, theme = s.spec.theme.id, fontSet = s.spec.fonts.id, bleed = s.spec.bleed,
            pages = pages, updatedAt = now(),
        )
        books.save(updated)
        if (_state.value.doc === s.doc && _state.value.spec == s.spec) unsaved = false
        _state.update { it.copy(draft = updated) }
    }

    /**
     * The screen is left (Android's onCleared): the last edit is kept, its text poured (a pour cut short by leaving
     * is done here), in [appScope] when given, else at once.
     */
    fun close() {
        if (unsaved || pouring) {
            saveJob?.cancel()
            pourJob?.cancel()
            val s = _state.value
            val draft = s.draft ?: return
            val layout = if (pouring) layout(s) else null
            unsaved = false
            pouring = false
            val write = {
                val doc = layout?.reflow(s.doc) ?: s.doc
                books.save(draft.copy(format = s.spec.format.id, theme = s.spec.theme.id, fontSet = s.spec.fonts.id, bleed = s.spec.bleed, pages = BookJson.encode(doc), updatedAt = now()))
            }
            val outside = appScope
            if (outside != null) outside.launch { write() } else write()
        }
    }

    private companion object {
        const val MAX_UNDO = 30
    }
}

/** The photos picked on the phone, made the trip's own loose photos (MediaRepository.import with no entry) on [MediaWork]. */
suspend fun importLoosePhotos(
    picked: List<PickedMedia>, tripId: String, importer: com.justdataplease.boringtravel.ios.MediaImporter,
    keep: (Media) -> Media,
): List<Media> = picked.mapNotNull { p ->
    val row = runCatching { withContext(MediaWork) { importer.prepare(p, tripId, null) } }.getOrNull() ?: return@mapNotNull null
    keep(row)
}
