package com.justdataplease.boringtravel.ui.ai

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.BuildConfig
import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.DraftLayout
import com.justdataplease.boringtravel.ai.DraftLimitException
import com.justdataplease.boringtravel.ai.ItineraryDocuments
import com.justdataplease.boringtravel.ai.ItineraryImport
import com.justdataplease.boringtravel.ai.ItineraryImportException
import com.justdataplease.boringtravel.ai.PicturePrep
import com.justdataplease.boringtravel.ai.PictureReading
import com.justdataplease.boringtravel.ai.PlannerProcess
import com.justdataplease.boringtravel.ai.PlannerAllowance
import com.justdataplease.boringtravel.ai.PlannerEngine
import com.justdataplease.boringtravel.ai.PlannerError
import com.justdataplease.boringtravel.ai.PlannerUsage
import com.justdataplease.boringtravel.ai.StaleDraftException
import com.justdataplease.boringtravel.ai.TripDraft
import com.justdataplease.boringtravel.ai.own.OwnKeyException
import com.justdataplease.boringtravel.ai.own.OwnKeyPlanner
import com.justdataplease.boringtravel.ai.tools.LinkLookup
import com.justdataplease.boringtravel.ai.tools.LinkReader
import com.justdataplease.boringtravel.ai.tools.MapsListLookup
import com.justdataplease.boringtravel.ai.tools.MapsLookup
import com.justdataplease.boringtravel.ai.tools.MessageLinks
import com.justdataplease.boringtravel.ai.tools.PageLookup
import com.justdataplease.boringtravel.ai.tools.PageTitle
import com.justdataplease.boringtravel.ai.tools.PendingPlan
import com.justdataplease.boringtravel.links.MapsPlace
import com.justdataplease.boringtravel.ai.tools.PlaceLookup
import com.justdataplease.boringtravel.ai.tools.PlannerAction
import com.justdataplease.boringtravel.ai.tools.TravelBrief
import com.justdataplease.boringtravel.ai.tools.TripPatch
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.data.repo.now
import com.justdataplease.boringtravel.speech.Dictation
import com.justdataplease.boringtravel.speech.DictationMerger
import com.justdataplease.boringtravel.speech.DictationTarget
import com.justdataplease.boringtravel.ui.share.ShareTargets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDate

/**
 * The changes on review: the planner's pending changes laid out against the trip, and the ones the traveller unticked.
 * [model] is who wrote them ([PlannerEngine.reportName]), for "Report this answer".
 */
data class Proposal(
    val draft: TripDraft, val layout: DraftLayout, val unticked: Set<String> = emptySet(), val raw: String? = null,
    val baseline: List<PlanItem> = emptyList(), val model: String? = null,
) {
    val ticked: Int get() = layout.addable.count { it.key !in unticked } + layout.updates.count { it.changed && it.key !in unticked } +
        layout.removals.count { it.key !in unticked } + layout.dayNames.count { it.key !in unticked } + (layout.tripChange?.let { c ->
            listOf(c.title != null && TRIP_TITLE !in unticked, c.datesChanged && TRIP_DATES !in unticked, c.route != null && TRIP_ROUTE !in unticked).count { it }
        } ?: 0)

    private companion object {
        const val TRIP_TITLE = com.justdataplease.boringtravel.ai.TripChange.TITLE
        const val TRIP_DATES = com.justdataplease.boringtravel.ai.TripChange.DATES
        const val TRIP_ROUTE = com.justdataplease.boringtravel.ai.TripChange.ROUTE
    }
}

/**
 * A chat line; a planner's answer keeps the [model] that wrote it (Me › AI may change later) and its calls ([raw]). A
 * hand-over keeps what it hands over: the trip questions ([brief], opened again from the line) or the address for
 * Copy from a link ([copyLink]). A traveller's message with [Attachments] keeps everything the planner read in [text]
 * and shows the words typed ([shown]), how many [pictures] and the links' [domains].
 */
data class ChatMessage(
    val fromTraveller: Boolean, val text: String, val raw: String? = null, val model: String? = null, val note: Boolean = false,
    val brief: TravelBrief? = null, val copyLink: String? = null,
    val shown: String? = null, val pictures: Int = 0, val domains: List<String> = emptyList(),
)

/**
 * The AI planner: one conversation (owner, 2026-11-11: "remove Notes and Chat; it should be clever to understand what
 * I am asking or what I am pasting"). Each message runs the planner's tools on the pending changes; the traveller
 * reviews them (or saves them all) and they go into the trip.
 *
 * A chat that began as a new trip can save into a trip the traveller has instead (owner, 17 Nov 2026: "if the user asks
 * add these places to my trip you can ask create new trip or append"): [tripChoices] are offered above Save and
 * [useTrip] moves the chat there.
 */
class PlannerViewModel(private val graph: AppGraph, startTripId: String?, private val saved: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    /** The trip this chat changes; null for a new trip. Fixed for a chat opened in a trip; [useTrip] moves one that began new. */
    var tripId by mutableStateOf(startTripId)
        private set

    /** The chat began as a new trip: what it makes can also go into a trip the traveller has. */
    val startedNew: Boolean = startTripId == null

    /** The traveller's trips to add to instead of a new one (their own, the newest first; none for a chat opened in a trip). */
    var tripChoices by mutableStateOf<List<Trip>>(emptyList())
        private set

    /** The new trip's own name, dates and route, kept aside while the chat points at a saved trip. */
    private var newTripPatch = TripPatch()
    private var watching: Job? = null

    private val builtIn = graph.planner
    private val usage = PlannerUsage(graph.app)

    /** The provider of the traveller's own key when Me › AI uses it (no allowance then: their own bill), else null. */
    var ownKey by mutableStateOf(graph.ownAi.state.value.active)
        private set

    /** "Using your Claude key · claude-sonnet-latest" while [ownKey] answers. */
    var ownKeyLine by mutableStateOf(graph.ownAi.state.value.usingLine())
        private set

    val available: Boolean get() = ownKey != null || builtIn.isAvailable

    /** Looked up for each request, so a change in Me › AI applies at once. */
    private fun engine(): PlannerEngine = graph.ownAi.engine() ?: builtIn

    var input by mutableStateOf(saved.get<String>("input").orEmpty())
        private set
    /** A text file shared to the app is being read into the box ([importDocument]). */
    var importing by mutableStateOf(false)
        private set
    var importError by mutableStateOf<String?>(null)
        private set
    private var importJob: Job? = null

    /** Pictures and links attached to the message being written; they go with the next message sent ([Attachments]). */
    var attachments by mutableStateOf(Attachments(saved.get<ArrayList<String>>(SAVED_PICTURES).orEmpty(), saved.get<ArrayList<String>>(SAVED_LINKS).orEmpty()))
        private set

    /** What the last pictures sent said, kept so sending them again after a failure doesn't read them again. */
    private var lastRead: Pair<List<String>, String>? = null
    val dictation = Dictation(graph.app)
    private val merger = DictationMerger()
    private var dictationStart: Job? = null

    /** Null while loading, or for a new trip ([isNewTrip]). */
    var trip by mutableStateOf<Trip?>(null)
        private set
    private var items: List<PlanItem> = emptyList()
    var loaded by mutableStateOf(startTripId == null)
        private set
    val isNewTrip: Boolean get() = tripId == null

    /** The saved trip has no plan yet (or the trip is new): the empty chat offers to plan it ([openQuestions]). */
    var planEmpty by mutableStateOf(true)
        private set

    /** The trip questions on screen, filled in with this; null when the sheet is closed. */
    var questions by mutableStateOf<TravelBrief?>(null)
        private set

    /** The traveller sent their answers in this chat: the questions aren't offered again. */
    val briefAnswered: Boolean get() = TravelBrief.answered(messages.filter { it.fromTraveller }.map { it.text })

    /** The planner's changes not saved yet; each message works on them. */
    private var pending = PendingPlan()
    var proposal by mutableStateOf<Proposal?>(null)
        private set
    var reviewing by mutableStateOf(false)
    val messages = mutableStateListOf<ChatMessage>()
    private var counted = false
    private var requestJob: Job? = null

    var busy by mutableStateOf(false)
        private set
    /** What the planner is doing now ("Finding 6 places"). */
    var step by mutableStateOf<String?>(null)
        private set
    var applying by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var freeLeft by mutableIntStateOf(PlannerAllowance.freeLeft(usage.freeUsed))
        private set

    private val places = PlaceLookup { query, near -> graph.places.search(query, near) }

    /** Pages for read_link, with Copy from a link's readers; made on the first page asked for. */
    private val linkReader by lazy { LinkReader.forApp() }

    /** The planner's process: the same steps whatever model answers; the model only gives inputs ([PlannerProcess]). */
    private val process = PlannerProcess(places, LinkLookup { url -> linkReader.read(url) }, MapsLookup { url, near ->
        // The link previews' reader: a short link followed, its name searched near the trip when it has no pin.
        graph.links.mapsPlace(url, near) ?: graph.links.preview(url, near)?.title?.let { MapsPlace(it, null, null) }
    }, PageLookup { url ->
        // A website's title and site name, as its link preview reads them (kept for the item's card).
        graph.links.preview(url)?.let { PageTitle(it.title, it.siteName) }
    }, MapsListLookup { url ->
        // A shared list of saved places: every place it holds.
        graph.links.mapsList(url)
    })

    init {
        dictation.onText = { text, final ->
            val target = object : DictationTarget {
                override val text: String get() = input
                override val caret: Int get() = input.length
                override fun isParagraphStart(index: Int) = index == 0 || input.getOrNull(index - 1) == '\n'
                override fun replace(start: Int, end: Int, with: String) {
                    val revised = input.replaceRange(start, end, with)
                    if (revised.length <= ItineraryImport.MAX_CHARS) {
                        input = revised
                        saved["input"] = revised
                    } else { error = ItineraryImport.TOO_LARGE; dictation.stop() }
                }
            }
            if (final) merger.final(target, text) else merger.partial(target, text)
        }
        viewModelScope.launch { graph.ownAi.state.collect { ownKey = it.active; ownKeyLine = it.usingLine() } }
        startTripId?.let(::watch)
        if (startedNew) viewModelScope.launch {
            graph.trips.observeEvery().collect { all -> tripChoices = ShareTargets.trips(all).trips.sortedByDescending { it.createdAt } }
        }
    }

    /** Follows trip [id] and its plan: the chat's context from now on. */
    private fun watch(id: String) {
        watching?.cancel()
        watching = viewModelScope.launch {
            combine(graph.trips.observeTrip(id), graph.plan.observeItems(id)) { t, i -> t to i }.collect { (t, i) ->
                trip = t
                items = i
                planEmpty = i.none { it.deletedAt == null }
                loaded = t != null
            }
        }
    }

    /**
     * Where this chat's changes go, for a chat that began as a new trip: trip [id] of the traveller's, or null for the
     * new trip again. The items the planner made come along; the new trip's own name, dates and route don't change a
     * saved trip (they wait for the new trip), and changes to one trip's saved items never follow to another.
     */
    fun useTrip(id: String?) {
        if (!startedNew || busy || applying || importing || id == tripId) return
        viewModelScope.launch {
            val target = id?.let { graph.trips.get(it)?.takeIf { t -> t.deletedAt == null } }
            if (id != null && target == null) return@launch
            val rows = target?.let { graph.plan.items(it.id) }.orEmpty()
            if (tripId == null) newTripPatch = pending.trip
            pending = pending.copy(trip = if (id == null) newTripPatch else TripPatch(), updates = emptyMap(), removed = emptySet())
            watching?.cancel()
            tripId = id
            trip = target
            items = rows
            planEmpty = rows.none { it.deletedAt == null }
            loaded = true
            if (id != null) watch(id)
            proposal = proposal?.let { p ->
                val draft = pending.toDraft()
                if (pending.hasChanges) p.copy(draft = draft, layout = DraftLayout.of(draft, target, rows), baseline = rows) else null
            }
            error = null
        }
    }

    fun clearError() { error = null }

    fun cancelPlan() {
        requestJob?.cancel()
        error = "Planning stopped. Your message is kept."
    }

    fun updateInput(text: String) {
        if (text.length > ItineraryImport.MAX_CHARS) { error = ItineraryImport.TOO_LARGE; return }
        freezeDictation()
        input = text
        saved["input"] = text
    }

    fun startDictation(language: String) {
        if (busy || importing || applying) return
        dictationStart?.cancel()
        dictationStart = viewModelScope.launch {
            val tag = graph.settings.dictationLanguage.first() ?: language
            merger.reset()
            dictation.start(tag)
        }
    }

    fun stopDictation() { dictationStart?.cancel(); dictation.stop() }
    fun microphoneDenied() { error = "Allow the microphone in app settings to dictate, or type your message." }
    private fun freezeDictation() { dictationStart?.cancel(); dictation.release(); merger.reset() }

    private fun attach(a: Attachments) {
        attachments = a
        saved[SAVED_PICTURES] = ArrayList(a.pictures)
        saved[SAVED_LINKS] = ArrayList(a.links)
    }

    /** Pictures from the photo picker or a share, attached to the message; past [PictureReading.MAX_PICTURES] it says so. */
    fun addPictures(uris: List<String>) {
        if (uris.isEmpty()) return
        val next = attachments.withPictures(uris)
        val left = (attachments.pictures + uris).distinct().size - next.pictures.size
        attach(next)
        error = if (left > 0) "Up to ${PictureReading.MAX_PICTURES} pictures in one message." else null
    }

    fun removePicture(picture: String) = attach(attachments.withoutPicture(picture))

    /** A link typed or pasted in Add a link, attached to the message; false when [input] holds no web address. */
    fun addLink(input: String): Boolean {
        val link = Attachments.link(input) ?: return false
        if (attachments.links.size >= Attachments.MAX_LINKS && link !in attachments.links) {
            error = "Up to ${Attachments.MAX_LINKS} links in one message."
            return true
        }
        attach(attachments.withLink(link))
        return true
    }

    fun removeLink(link: String) = attach(attachments.withoutLink(link))

    /**
     * Something shared to the app for the planner: pictures and a Google Doc's link are attached to the message, a
     * text file or text goes into the box. Nothing is sent until the traveller sends it.
     */
    fun receiveShared(text: String?, uri: String?, pictures: List<String> = emptyList()) {
        when {
            pictures.isNotEmpty() -> addPictures(pictures)
            text != null && ItineraryImport.googleDocId(text) != null && addLink(text) -> Unit
            uri != null -> importDocument(uri.toUri())
            text != null -> try { receiveText(ItineraryImport.validate(text)) } catch (e: ItineraryImportException) { importError = e.message }
        }
    }

    /** A text file shared to the app: its text goes into the box, after anything typed there. */
    private fun importDocument(uri: Uri) {
        if (importing || busy || applying) return
        freezeDictation()
        importError = null
        importing = true
        importJob = viewModelScope.launch {
            try {
                val text = withTimeout(DOCUMENT_TIMEOUT_MS) { ItineraryDocuments.read(graph.app, uri) }
                ensureActive()
                importing = false
                receiveText(text)
            } catch (_: TimeoutCancellationException) {
                importError = "This document took too long to open. Try again, or paste its text."
            } catch (e: CancellationException) { throw e
            } catch (e: ItineraryImportException) {
                importError = e.message ?: ItineraryImport.TOO_LARGE
            } catch (_: Exception) {
                importError = "Couldn't read this document. Paste its text instead."
            } finally { importing = false }
        }
    }

    private fun receiveText(text: String) = updateInput(if (input.isBlank()) text else input.trimEnd() + "\n\n" + text)

    fun cancelImport() {
        importJob?.cancel()
        importError = null
    }

    /**
     * What attached pictures say (owner, 17 Nov 2026: "from image to trip or add places, use the LLM for OCR"): the
     * planner's model reads them ([PictureReading]) and the text goes into the message, after the words typed (owner:
     * "don't let user review text"). One of the day's answers with the built-in planner, as Copy from a site counts
     * its AI.
     */
    private suspend fun readPictures(pictures: List<String>, own: OwnKeyPlanner?): String {
        val count = pictures.size
        val loaded = PicturePrep.load(graph.app, pictures.map { it.toUri() })
        val answer = if (own != null) {
            own.answer(PictureReading.system, PictureReading.prompt(count), PictureReading.schema, loaded)
        } else {
            builtIn.answer(PictureReading.system, PictureReading.prompt(count), PictureReading.schema, loaded).also {
                usage.record(graph.billing.state.value.isPro, startsPlan = false)
            }
        }
        return PictureReading.message(PictureReading.parse(answer, count), count)
    }

    private fun pictureError(e: Throwable, own: OwnKeyPlanner?, count: Int): String {
        if (BuildConfig.DEBUG) {
            // Class names only: messages can carry the request or what was read.
            val classes = generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }.take(6).joinToString(" → ") { it.javaClass.simpleName }
            Log.w("BoringTravelPlanner", "Picture reading failed ($classes); ownKey=${own?.provider?.id ?: "no"}")
        }
        return when {
            e is TimeoutCancellationException -> PictureReading.TOO_SLOW
            e is ItineraryImportException -> e.message ?: PictureReading.nothing(count)
            e is OwnKeyException && own != null -> PictureReading.ownKeyMessage(e.error, own.provider)
            e is java.io.FileNotFoundException || e is SecurityException || e is OutOfMemoryError -> PictureReading.CANT_OPEN
            e is Exception -> PlannerError.of(e, BuildConfig.DEBUG).message
            else -> PictureReading.CANT_OPEN
        }
    }

    /** Sends the message: the planner reads it with the trip and the pending changes, and changes them with its tools. */
    fun send(onNeedsPro: () -> Unit) {
        freezeDictation()
        sendText(input.trim(), onNeedsPro) {
            input = ""
            saved["input"] = ""
        }
    }

    /**
     * The trip questions, filled in with [brief] (the starter: the saved trip's place and dates). The answers go to
     * the planner as the traveller's message ([sendAnswers]).
     */
    fun openQuestions(brief: TravelBrief = TravelBrief.fromTrip(trip)) {
        if (busy || applying) return
        freezeDictation()
        error = null
        questions = brief
    }

    fun closeQuestions() { questions = null }

    /** The trip questions answered: sent as the traveller's message; the sheet closes once the planner is asked. */
    fun sendAnswers(brief: TravelBrief, onNeedsPro: () -> Unit) {
        sendText(brief.message(), onNeedsPro) { questions = null }
    }

    /**
     * Sends [text] to the planner with the [attachments] (the trip questions' answers too): the links and what the
     * pictures say are added to it ([Attachments.message]), the pictures read first. [onSent] runs once it goes (before
     * the request: a failure puts the text and the attachments back). Nothing happens while the planner is busy, or past
     * the allowance.
     */
    private fun sendText(text: String, onNeedsPro: () -> Unit, onSent: () -> Unit) {
        val attached = attachments
        if ((text.isEmpty() && attached.isEmpty) || busy || importing || applying || !loaded) return
        val starts = !counted
        val pictures = attached.pictures
        val known = lastRead?.takeIf { it.first == pictures }?.second
        // Google Maps links alone are added by the app, without a model: no allowance needed.
        if ((pictures.isNotEmpty() || !MessageLinks.onlyMapsLinks(attached.message(text, null))) && !allowed(startsPlan = starts, onNeedsPro)) return
        val history = messages.filter { !it.note }.map { ChatTurn(it.fromTraveller, it.text) }
        val sent = ChatMessage(
            fromTraveller = true, text = attached.message(text, known),
            shown = if (attached.isEmpty) null else text, pictures = pictures.size, domains = attached.domains,
        )
        messages += sent
        val at = messages.lastIndex
        onSent()
        attach(Attachments())
        busy = true
        error = null
        step = null
        val t = trip
        val i = items
        val before = pending
        requestJob = viewModelScope.launch {
            val engine = engine()
            val ownKey = engine as? OwnKeyPlanner
            val own = ownKey?.provider
            var reading = false
            try {
                var message = sent.text
                if (pictures.isNotEmpty() && known == null) {
                    reading = true
                    step = PictureReading.working(pictures.size)
                    val read = withTimeout(PICTURE_TIMEOUT_MS) { readPictures(pictures, ownKey) }
                    reading = false
                    step = null
                    lastRead = pictures to read
                    message = attached.message(text, read)
                    if (messages.getOrNull(at) === sent) messages[at] = sent.copy(text = message)
                }
                val outcome = withTimeout(REQUEST_TIMEOUT_MS) {
                    process.run(engine, t, i, before, history, message, LocalDate.now()) { step = it }
                }
                ensureActive()
                if (own == null && !outcome.byApp) {
                    usage.record(graph.billing.state.value.isPro, starts && outcome.ready)
                    freeLeft = PlannerAllowance.freeLeft(usage.freeUsed)
                }
                if (starts && outcome.ready && !outcome.byApp) counted = true
                show(outcome, i)
            } catch (e: TimeoutCancellationException) {
                error = if (reading) pictureError(e, ownKey, pictures.size) else "The planner took too long. Try again, or send fewer days at a time."
                restore(text, attached)
            } catch (e: CancellationException) {
                restore(text, attached)
                throw e
            } catch (e: Exception) {
                error = if (reading) pictureError(e, ownKey, pictures.size) else {
                    if (BuildConfig.DEBUG) {
                        // Class names only: exception messages can contain a request URL, credentials or itinerary text.
                        val classes = generateSequence<Throwable>(e) { it.cause.takeIf { cause -> cause !== it } }.take(6).joinToString(" → ") { it.javaClass.simpleName }
                        val category = (e as? OwnKeyException)?.error?.name ?: PlannerError.of(e, true).name
                        Log.w("BoringTravelPlanner", "AI request failed ($classes); category=$category; ownKey=${own?.id ?: "no"}")
                    }
                    when {
                        e is DraftLimitException -> e.message
                        e is OwnKeyException && own != null -> e.error.message(own)
                        else -> PlannerError.of(e, BuildConfig.DEBUG).message
                    }
                }
                restore(text, attached)
            } catch (e: OutOfMemoryError) {
                // Only the pictures are big enough for this.
                error = pictureError(e, ownKey, pictures.size)
                restore(text, attached)
            } finally {
                busy = false
                step = null
            }
        }
    }

    /** A failed message goes back in the box, with its attachments, so it can be sent again. */
    private fun restore(text: String, attached: Attachments) {
        if (messages.lastOrNull()?.fromTraveller == true) messages.removeAt(messages.lastIndex)
        input = text
        saved["input"] = text
        attach(attached)
    }

    private fun show(outcome: PlannerProcess.Outcome, baseline: List<PlanItem>) {
        pending = outcome.pending
        val layout = outcome.layout
        proposal = if (outcome.pending.hasChanges || layout.questions.isNotEmpty()) {
            Proposal(outcome.draft, layout, raw = outcome.raw, baseline = baseline, model = outcome.model)
        } else null
        // A hand-over gets the app's own words: the planner reads them back in the next turn.
        messages += when (val action = outcome.action) {
            is PlannerAction.Ask -> ChatMessage(false, ASK_REPLY, outcome.raw, outcome.model, brief = action.brief).also { questions = action.brief }
            is PlannerAction.CopyNotes -> ChatMessage(false, COPY_REPLY, outcome.raw, outcome.model, copyLink = action.address)
            null -> ChatMessage(
                fromTraveller = false, text = outcome.replyWithQuestions.ifBlank { if (outcome.pending.hasChanges) REPLY_FALLBACK else NOTHING_REPLY },
                raw = outcome.raw, model = outcome.model,
            )
        }
    }

    fun toggle(key: String) {
        proposal = proposal?.let { p -> p.copy(unticked = if (key in p.unticked) p.unticked - key else p.unticked + key) }
    }

    /** Throws the pending changes away; the chat stays. */
    fun discard() {
        pending = PendingPlan()
        proposal = null
        reviewing = false
        messages += ChatMessage(false, "Changes discarded. Nothing was saved.", note = true)
    }

    /**
     * Saves the ticked changes, laid out again against the plan as it is now so nothing is doubled, then hands back
     * the trip id and a short summary.
     */
    fun apply(onDone: (tripId: String, summary: String) -> Unit) {
        val proposal = proposal ?: return
        if (applying || busy || importing) return
        if (proposal.layout.questions.isNotEmpty()) { error = "Answer the questions before saving."; return }
        if (proposal.layout.problems.isNotEmpty()) { error = "Ask the planner to sort out the highlighted changes first."; return }
        applying = true
        error = null
        viewModelScope.launch {
            try {
                val into = tripId
                val result = graph.db.withTransaction {
                    val current = into?.let { graph.trips.get(it) }
                    if (into != null && (current == null || current.deletedAt != null)) throw StaleDraftException("This trip was deleted.")
                    val existing = current?.let { graph.plan.items(it.id) }.orEmpty()
                    // Additions are deduplicated against today's rows; updates keep the reviewed baseline.
                    val change = process.save(proposal.draft, current, existing, proposal.baseline, proposal.unticked, draftId = newId(), now = now(), newId = ::newId)
                    // The trip was read in this transaction, so its travel choices and day names are written as worked out.
                    if (change.saveTrip) graph.trips.saveWithChoices(change.trip)
                    if (change.rows.isNotEmpty()) graph.plan.saveAll(change.rows)
                    change
                }
                val summary = buildList {
                    if (result.newTrip) add("Trip created")
                    if (result.datesChanged) add("Dates changed")
                    if (result.namedDays > 0) add(if (result.namedDays == 1) "Day renamed" else "${result.namedDays} days renamed")
                    if (result.items.isNotEmpty()) add("Added ${result.summary}")
                    if (result.updatedItems.isNotEmpty()) add("Changed ${result.updatedItems.size} ${if (result.updatedItems.size == 1) "item" else "items"}")
                    if (result.removedItems.isNotEmpty()) add("Removed ${result.removedItems.size}")
                    if (result.alreadyUpdated > 0) add("${result.alreadyUpdated} already up to date")
                }.joinToString(". ").ifBlank { "Nothing to change" } + "."
                pending = PendingPlan()
                this@PlannerViewModel.proposal = null
                reviewing = false
                onDone(result.trip.id, summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StaleDraftException) {
                error = e.message
            } catch (_: Exception) {
                error = "Couldn't save the changes. Try again."
            } finally {
                applying = false
            }
        }
    }

    private fun allowed(startsPlan: Boolean, onNeedsPro: () -> Unit): Boolean {
        // With the traveller's own key there is no allowance and no daily cap: it is their own bill.
        if (ownKey != null) return true
        if (!available) {
            error = PlannerError.NotSetUp.message
            return false
        }
        return when (usage.check(graph.billing.state.value.isPro, startsPlan)) {
            is PlannerAllowance.Check.Allowed -> true
            PlannerAllowance.Check.NeedsPro -> { onNeedsPro(); false }
            PlannerAllowance.Check.DailyLimit -> { error = "That's the limit for today. Try again tomorrow."; false }
        }
    }

    private companion object {
        const val DOCUMENT_TIMEOUT_MS = 45_000L
        const val SAVED_PICTURES = "attachedPictures"
        const val SAVED_LINKS = "attachedLinks"

        /** Pictures: opened, sent and read by the model (each model call has its own 180 s limit). */
        const val PICTURE_TIMEOUT_MS = 150_000L
        const val REPLY_FALLBACK = "Here is the plan."
        const val NOTHING_REPLY = "Nothing changed. Try saying it another way."
        const val ASK_REPLY = "A few questions first, then I plan the trip."
        const val COPY_REPLY = "Copy from a link copies your notes and pictures exactly as written. You check them there before anything is saved."
        /**
         * The whole request, every model turn and map search included. A proposed trip takes the longest: in the live
         * test of 12 Nov 2026 four days of Lisbon took 196 s to 326 s with a slow map search. Each model call has its
         * own limit (180 s), find_places 40 s; Stop ends it sooner.
         */
        const val REQUEST_TIMEOUT_MS = 480_000L
    }

    override fun onCleared() { dictation.release(); super.onCleared() }
}
