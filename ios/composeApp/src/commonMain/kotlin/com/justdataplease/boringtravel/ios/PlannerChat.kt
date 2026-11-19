package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.justdataplease.boringtravel.ai.ApplyResult
import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.DraftLayout
import com.justdataplease.boringtravel.ai.DraftLimitException
import com.justdataplease.boringtravel.ai.ItineraryImport
import com.justdataplease.boringtravel.ai.ItineraryImportException
import com.justdataplease.boringtravel.ai.PictureReading
import com.justdataplease.boringtravel.ai.PlannerAllowance
import com.justdataplease.boringtravel.ai.PlannerError
import com.justdataplease.boringtravel.ai.PlannerProcess
import com.justdataplease.boringtravel.ai.StaleDraftException
import com.justdataplease.boringtravel.ai.TripChange
import com.justdataplease.boringtravel.ai.TripDraft
import com.justdataplease.boringtravel.ai.PlannerEngine
import com.justdataplease.boringtravel.ai.own.OwnAiProvider
import com.justdataplease.boringtravel.ai.own.OwnAiState
import com.justdataplease.boringtravel.ai.own.OwnKeyException
import com.justdataplease.boringtravel.ai.own.OwnKeyPlanner
import com.justdataplease.boringtravel.ai.tools.MessageLinks
import com.justdataplease.boringtravel.ai.tools.PendingPlan
import com.justdataplease.boringtravel.ai.tools.PlannerAction
import com.justdataplease.boringtravel.ai.tools.TravelBrief
import com.justdataplease.boringtravel.ai.tools.TripPatch
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.speech.DictationMerger
import com.justdataplease.boringtravel.speech.DictationTarget
import com.justdataplease.boringtravel.time.today
import com.justdataplease.boringtravel.ui.ai.Attachments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/*
 * The AI planner's one conversation (the Android app's ui/ai/PlannerViewModel.kt), as a state holder the screen keeps
 * while it is on the stack: leaving drops the chat and what isn't saved, as on Android. Each message runs the planner's
 * process on the pending changes; the traveller reviews them (or saves them all) and they go into the trip.
 */

/**
 * The changes on review: the planner's pending changes laid out against the trip, and the ones the traveller unticked.
 * [model] is who wrote them, for "Report this answer".
 */
data class Proposal(
    val draft: TripDraft, val layout: DraftLayout, val unticked: Set<String> = emptySet(), val raw: String? = null,
    val baseline: List<PlanItem> = emptyList(), val model: String? = null,
) {
    val ticked: Int get() = layout.addable.count { it.key !in unticked } + layout.updates.count { it.changed && it.key !in unticked } +
        layout.removals.count { it.key !in unticked } + layout.dayNames.count { it.key !in unticked } + (layout.tripChange?.let { c ->
            listOf(c.title != null && TripChange.TITLE !in unticked, c.datesChanged && TripChange.DATES !in unticked, c.route != null && TripChange.ROUTE !in unticked).count { it }
        } ?: 0)
}

/**
 * A chat line; a planner's answer keeps the [model] that wrote it and its calls ([raw]). A hand-over keeps what it hands
 * over: the trip questions ([brief]) or the address for Copy from a link ([copyLink]). A traveller's message with
 * [Attachments] keeps everything the planner read in [text] and shows the words typed ([shown]), how many [pictures]
 * and the links' [domains].
 */
data class ChatMessage(
    val fromTraveller: Boolean, val text: String, val raw: String? = null, val model: String? = null, val note: Boolean = false,
    val brief: TravelBrief? = null, val copyLink: String? = null,
    val shown: String? = null, val pictures: Int = 0, val domains: List<String> = emptyList(),
)

/** What the chat asks of the app: the planner's kit, the trips, and saving. Bound in App.kt; faked in tests. */
interface PlannerSide {
    /** The built-in planner can answer in this build (its build-time values are set). */
    val builtInAvailable: Boolean

    /** Me › AI's choices as they change (the screen follows them: "Using your … key · …"). */
    val ownState: StateFlow<OwnAiState>

    /** The traveller's own key's provider when Me › AI uses one, else null; and "Using your … key · …". */
    val ownKey: OwnAiProvider? get() = ownState.value.active
    val ownKeyLine: String? get() = ownState.value.usingLine()

    /** Who answers now: the own key's planner, else the built-in one. */
    fun engine(): PlannerEngine

    val process: PlannerProcess

    /** What [pictures] (files under the app's files) say, read by [own] or the built-in planner (counted then). */
    suspend fun readPictures(pictures: List<String>, own: OwnKeyPlanner?): String

    /** The built-in planner's allowance today (no Pro on this phone: everyone has it unlocked, as Android before Pro). */
    fun check(startsPlan: Boolean): PlannerAllowance.Check
    fun record(startsPlan: Boolean)

    fun trip(id: String): Trip?
    fun items(tripId: String): List<PlanItem>

    /** The traveller's own trips to add to instead of a new one, the newest first. */
    fun tripChoices(): List<Trip>

    /**
     * Saves [proposal]'s ticked changes into trip [into] (null: a new trip), checked against the plan as it is now
     * (Android's PlannerProcess.save and DraftApplier); throws [StaleDraftException] when the trip was deleted.
     */
    fun save(proposal: Proposal, into: String?): ApplyResult

    /** A picked picture's copy under the app's files is done with (removed, read, or the chat left). */
    fun dropPicture(path: String) {}
}

/**
 * The chat's side on the planner's kit and the trip store: [kit]'s engines, process, allowance (everything unlocked
 * while Pro isn't sold: the daily cap only) and saving; the traveller's own trips, newest first, as Android's
 * ShareTargets lists them (the examples only when there is nothing else); [files] holds the picked pictures' copies.
 */
class KitPlannerSide(private val kit: PlannerKit, private val book: TripBook, private val files: MediaFiles) : PlannerSide {
    override val builtInAvailable: Boolean get() = kit.builtIn.isAvailable
    override val ownState: StateFlow<OwnAiState> get() = kit.ownAi.state
    override fun engine(): PlannerEngine = kit.engine()
    override val process: PlannerProcess get() = kit.process
    override suspend fun readPictures(pictures: List<String>, own: OwnKeyPlanner?): String = kit.readPictures(pictures, own)
    override fun check(startsPlan: Boolean): PlannerAllowance.Check = kit.usage.check(isPro = true, startsPlan = startsPlan)
    override fun record(startsPlan: Boolean) = kit.recordAnswer(startsPlan)
    override fun trip(id: String): Trip? = book.trip(id)
    override fun items(tripId: String): List<PlanItem> = book.items(tripId)
    override fun tripChoices(): List<Trip> {
        val live = book.snapshot.trips.filter { it.deletedAt == null }
        val own = live.filter { !it.isDemo }
        return own.ifEmpty { live }.sortedByDescending { it.createdAt }
    }
    override fun save(proposal: Proposal, into: String?): ApplyResult = kit.save(into, proposal.draft, proposal.baseline, proposal.unticked).result
    override fun dropPicture(path: String) {
        if (path.startsWith("picked/")) files.delete(path)
    }
}

/**
 * The AI planner (the Android app's PlannerViewModel): one conversation. A chat that began as a new trip can save into a
 * trip the traveller has instead ([tripChoices], [useTrip]). [scope] is the screen's; leaving ends what runs.
 */
class PlannerChat(private val side: PlannerSide, startTripId: String?, private val scope: CoroutineScope) {
    /** The trip this chat changes; null for a new trip. */
    var tripId by mutableStateOf(startTripId)
        private set

    /** The chat began as a new trip: what it makes can also go into a trip the traveller has. */
    val startedNew: Boolean = startTripId == null

    /** The traveller's trips to add to instead of a new one (none for a chat opened in a trip). */
    var tripChoices by mutableStateOf<List<Trip>>(if (startTripId == null) side.tripChoices() else emptyList())
        private set

    /** The new trip's own name, dates and route, kept aside while the chat points at a saved trip. */
    private var newTripPatch = TripPatch()

    val ownKey get() = side.ownKey
    val ownKeyLine get() = side.ownKeyLine
    val available: Boolean get() = ownKey != null || side.builtInAvailable

    var input by mutableStateOf("")
        private set

    /** Pictures (paths under the app's files) and links attached to the message being written. */
    var attachments by mutableStateOf(Attachments())
        private set

    /** What the last pictures sent said, kept so sending them again after a failure doesn't read them again. */
    private var lastRead: Pair<List<String>, String>? = null
    private val merger = DictationMerger()

    /** Lets go of the screen's dictation without its last words (Android's dictation.release()); set by the screen. */
    var onFreezeDictation: () -> Unit = {}

    /** Typing, sending or the questions take the box over: dictation lets go and starts afresh next time. */
    private fun freezeDictation() {
        onFreezeDictation()
        merger.reset()
    }

    var trip by mutableStateOf<Trip?>(null)
        private set
    private var items: List<PlanItem> = emptyList()
    var loaded by mutableStateOf(startTripId == null)
        private set
    val isNewTrip: Boolean get() = tripId == null

    /** The saved trip has no plan yet (or the trip is new): the empty chat offers to plan it. */
    var planEmpty by mutableStateOf(true)
        private set

    /** The trip questions on screen, filled in with this; null when the sheet is closed. */
    var questions by mutableStateOf<TravelBrief?>(null)
        private set

    /** The traveller sent their answers in this chat: the questions aren't offered again. */
    val briefAnswered: Boolean get() = TravelBrief.answered(messages.filter { it.fromTraveller }.map { it.text })

    private var pending = PendingPlan()
    var proposal by mutableStateOf<Proposal?>(null)
        private set
    var reviewing by mutableStateOf(false)
    val messages = mutableStateListOf<ChatMessage>()
    private var counted = false
    private var requestJob: Job? = null

    var busy by mutableStateOf(false)
        private set
    var step by mutableStateOf<String?>(null)
        private set
    var applying by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        startTripId?.let(::load)
    }

    /** Reads trip [id] and its plan again: the chat's context from now on (the trip store isn't observable). */
    fun load(id: String? = tripId) {
        if (startedNew) tripChoices = side.tripChoices()
        val t = id?.let { side.trip(it) }
        trip = t
        items = t?.let { side.items(it.id) }.orEmpty()
        planEmpty = items.none { it.deletedAt == null }
        loaded = id == null || t != null
    }

    /**
     * Where this chat's changes go, for a chat that began as a new trip: trip [id] of the traveller's, or null for the
     * new trip again. The items the planner made come along; the new trip's own name, dates and route don't change a
     * saved trip, and changes to one trip's saved items never follow to another.
     */
    fun useTrip(id: String?) {
        if (!startedNew || busy || applying || id == tripId) return
        val target = id?.let { side.trip(it) }
        if (id != null && target == null) return
        val rows = target?.let { side.items(it.id) }.orEmpty()
        if (tripId == null) newTripPatch = pending.trip
        pending = pending.copy(trip = if (id == null) newTripPatch else TripPatch(), updates = emptyMap(), removed = emptySet())
        tripId = id
        trip = target
        items = rows
        planEmpty = rows.none { it.deletedAt == null }
        loaded = true
        proposal = proposal?.let { p ->
            val draft = pending.toDraft()
            if (pending.hasChanges) p.copy(draft = draft, layout = DraftLayout.of(draft, target, rows), baseline = rows) else null
        }
        error = null
    }

    fun clearError() {
        error = null
    }

    fun cancelPlan() {
        requestJob?.cancel()
        error = "Planning stopped. Your message is kept."
    }

    fun updateInput(text: String) {
        if (text.length > ItineraryImport.MAX_CHARS) {
            error = ItineraryImport.TOO_LARGE
            return
        }
        freezeDictation()
        input = text
    }

    /** A partial or final dictation result, written at the end of the box ([DictationMerger]); [stop] ends dictation. */
    fun onDictated(text: String, final: Boolean, stop: () -> Unit) {
        val target = object : DictationTarget {
            override val text: String get() = input
            override val caret: Int get() = input.length
            override fun isParagraphStart(index: Int) = index == 0 || input.getOrNull(index - 1) == '\n'
            override fun replace(start: Int, end: Int, with: String) {
                val revised = input.replaceRange(start, end, with)
                if (revised.length <= ItineraryImport.MAX_CHARS) {
                    input = revised
                } else {
                    error = ItineraryImport.TOO_LARGE
                    stop()
                }
            }
        }
        if (final) merger.final(target, text) else merger.partial(target, text)
    }

    /** Dictation starts afresh at the end of the box. */
    fun prepareDictation() = merger.reset()

    fun microphoneDenied() {
        error = "Allow the microphone in app settings to dictate, or type your message."
    }

    /** Pictures from the photo picker, attached to the message; past [PictureReading.MAX_PICTURES] it says so. */
    fun addPictures(paths: List<String>) {
        if (paths.isEmpty()) return
        val next = attachments.withPictures(paths)
        val left = (attachments.pictures + paths).distinct().size - next.pictures.size
        attachments = next
        error = if (left > 0) "Up to ${PictureReading.MAX_PICTURES} pictures in one message." else null
    }

    fun removePicture(picture: String) {
        attachments = attachments.withoutPicture(picture)
        side.dropPicture(picture)
    }

    /** The chat is left: what runs stops and the pictures' copies go (Android's ViewModel cleared). */
    fun close() {
        requestJob?.cancel()
        (attachments.pictures + lastRead?.first.orEmpty()).distinct().forEach(side::dropPicture)
    }

    /** A link typed or pasted in Add a link, attached to the message; false when [typed] holds no web address. */
    fun addLink(typed: String): Boolean {
        val link = Attachments.link(typed) ?: return false
        if (attachments.links.size >= Attachments.MAX_LINKS && link !in attachments.links) {
            error = "Up to ${Attachments.MAX_LINKS} links in one message."
            return true
        }
        attachments = attachments.withLink(link)
        return true
    }

    fun removeLink(link: String) {
        attachments = attachments.withoutLink(link)
    }

    private fun pictureError(e: Throwable, own: OwnKeyPlanner?, count: Int): String = when {
        e is TimeoutCancellationException -> PictureReading.TOO_SLOW
        e is ItineraryImportException -> e.message ?: PictureReading.nothing(count)
        e is OwnKeyException && own != null -> PictureReading.ownKeyMessage(e.error, own.provider)
        e is Exception -> PlannerError.of(e, false).message
        else -> PictureReading.CANT_OPEN
    }

    /** Sends the message: the planner reads it with the trip and the pending changes, and changes them with its tools. */
    fun send() {
        freezeDictation()
        sendText(input.trim()) { input = "" }
    }

    /** The trip questions, filled in with [brief] (the saved trip's place and dates). */
    fun openQuestions(brief: TravelBrief = TravelBrief.fromTrip(trip)) {
        if (busy || applying) return
        freezeDictation()
        error = null
        questions = brief
    }

    fun closeQuestions() {
        questions = null
    }

    /** The trip questions answered: sent as the traveller's message; the sheet closes once the planner is asked. */
    fun sendAnswers(brief: TravelBrief) {
        sendText(brief.message()) { questions = null }
    }

    private fun sendText(text: String, onSent: () -> Unit) {
        val attached = attachments
        if ((text.isEmpty() && attached.isEmpty) || busy || applying || !loaded) return
        val starts = !counted
        val pictures = attached.pictures
        val known = lastRead?.takeIf { it.first == pictures }?.second
        // Google Maps links alone are added by the app, without a model: no allowance needed.
        if ((pictures.isNotEmpty() || !MessageLinks.onlyMapsLinks(attached.message(text, null))) && !allowed(startsPlan = starts)) return
        val history = messages.filter { !it.note }.map { ChatTurn(it.fromTraveller, it.text) }
        val sent = ChatMessage(
            fromTraveller = true, text = attached.message(text, known),
            shown = if (attached.isEmpty) null else text, pictures = pictures.size, domains = attached.domains,
        )
        messages += sent
        val at = messages.lastIndex
        onSent()
        attachments = Attachments()
        busy = true
        error = null
        step = null
        val t = trip
        val i = items
        val before = pending
        requestJob = scope.launch {
            val engine = side.engine()
            val ownKey = engine as? OwnKeyPlanner
            var reading = false
            try {
                var message = sent.text
                if (pictures.isNotEmpty() && known == null) {
                    reading = true
                    step = PictureReading.working(pictures.size)
                    val read = withTimeout(PICTURE_TIMEOUT_MS) { side.readPictures(pictures, ownKey) }
                    reading = false
                    step = null
                    lastRead = pictures to read
                    message = attached.message(text, read)
                    if (messages.getOrNull(at) === sent) messages[at] = sent.copy(text = message)
                }
                val outcome = withTimeout(REQUEST_TIMEOUT_MS) {
                    side.process.run(engine, t, i, before, history, message, today()) { step = it }
                }
                currentCoroutineContext().ensureActive()
                if (ownKey == null && !outcome.byApp) side.record(starts && outcome.ready)
                if (starts && outcome.ready && !outcome.byApp) counted = true
                show(outcome, i)
                // Read and sent: the pictures' copies aren't needed again.
                pictures.forEach(side::dropPicture)
                if (lastRead?.first == pictures) lastRead = null
            } catch (e: TimeoutCancellationException) {
                error = if (reading) pictureError(e, ownKey, pictures.size) else "The planner took too long. Try again, or send fewer days at a time."
                restore(text, attached)
            } catch (e: CancellationException) {
                restore(text, attached)
                throw e
            } catch (e: Exception) {
                error = if (reading) {
                    pictureError(e, ownKey, pictures.size)
                } else {
                    when {
                        e is DraftLimitException -> e.message
                        e is OwnKeyException && ownKey != null -> e.error.message(ownKey.provider)
                        else -> PlannerError.of(e, false).message
                    }
                }
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
        attachments = attached
    }

    private fun show(outcome: PlannerProcess.Outcome, baseline: List<PlanItem>) {
        pending = outcome.pending
        val layout = outcome.layout
        proposal = if (outcome.pending.hasChanges || layout.questions.isNotEmpty()) {
            Proposal(outcome.draft, layout, raw = outcome.raw, baseline = baseline, model = outcome.model)
        } else {
            null
        }
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

    /** Saves the ticked changes, laid out again against the plan as it is now, then hands back the trip and a summary. */
    fun apply(onDone: (tripId: String, summary: String) -> Unit) {
        val proposal = proposal ?: return
        if (applying || busy) return
        if (proposal.layout.questions.isNotEmpty()) {
            error = "Answer the questions before saving."
            return
        }
        if (proposal.layout.problems.isNotEmpty()) {
            error = "Ask the planner to sort out the highlighted changes first."
            return
        }
        applying = true
        error = null
        try {
            val result = side.save(proposal, tripId)
            val summary = PlannerKit.summary(result)
            val id = result.trip.id
            pending = PendingPlan()
            this.proposal = null
            reviewing = false
            onDone(id, summary)
        } catch (e: StaleDraftException) {
            error = e.message
        } catch (_: Exception) {
            error = "Couldn't save the changes. Try again."
        } finally {
            applying = false
        }
    }

    private fun allowed(startsPlan: Boolean): Boolean {
        // With the traveller's own key there is no allowance and no daily cap: it is their own bill.
        if (ownKey != null) return true
        if (!available) {
            error = PlannerError.NotSetUp.message
            return false
        }
        return when (side.check(startsPlan)) {
            is PlannerAllowance.Check.Allowed -> true
            // Pro isn't sold on this phone: everything is unlocked, as on Android while Pro isn't on sale.
            PlannerAllowance.Check.NeedsPro -> true
            PlannerAllowance.Check.DailyLimit -> {
                error = "That's the limit for today. Try again tomorrow."
                false
            }
        }
    }

    companion object {
        /** Pictures: opened, sent and read by the model. */
        const val PICTURE_TIMEOUT_MS = 150_000L
        const val REPLY_FALLBACK = "Here is the plan."
        const val NOTHING_REPLY = "Nothing changed. Try saying it another way."
        const val ASK_REPLY = "A few questions first, then I plan the trip."
        const val COPY_REPLY = "Copy from a link copies your notes and pictures exactly as written. You check them there before anything is saved."

        /** The whole request, every model turn and map search included; Stop ends it sooner. */
        const val REQUEST_TIMEOUT_MS = 480_000L
    }
}
