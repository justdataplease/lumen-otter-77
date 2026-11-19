package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.ai.AppCheckTokens
import com.justdataplease.boringtravel.ai.ApplyResult
import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.DraftLimitException
import com.justdataplease.boringtravel.ai.GeminiPlanner
import com.justdataplease.boringtravel.ai.ItineraryImportException
import com.justdataplease.boringtravel.ai.PicturePrep
import com.justdataplease.boringtravel.ai.PictureReading
import com.justdataplease.boringtravel.ai.PlannerAllowance
import com.justdataplease.boringtravel.ai.PlannerEngine
import com.justdataplease.boringtravel.ai.PlannerError
import com.justdataplease.boringtravel.ai.PlannerProcess
import com.justdataplease.boringtravel.ai.PlannerUsage
import com.justdataplease.boringtravel.ai.StaleDraftException
import com.justdataplease.boringtravel.ai.TripDraft
import com.justdataplease.boringtravel.ai.own.KeyValues
import com.justdataplease.boringtravel.ai.own.MemoryKeyValues
import com.justdataplease.boringtravel.ai.own.OwnAi
import com.justdataplease.boringtravel.ai.own.OwnAiModels
import com.justdataplease.boringtravel.ai.own.OwnAiProvider
import com.justdataplease.boringtravel.ai.own.OwnKeyError
import com.justdataplease.boringtravel.ai.own.OwnKeyException
import com.justdataplease.boringtravel.ai.own.OwnKeyPlanner
import com.justdataplease.boringtravel.ai.own.OwnKeyStore
import com.justdataplease.boringtravel.ai.own.PlainCipher
import com.justdataplease.boringtravel.ai.own.modelsOrNull
import com.justdataplease.boringtravel.ai.tools.LinkLookup
import com.justdataplease.boringtravel.ai.tools.LinkReader
import com.justdataplease.boringtravel.ai.tools.MapsListLookup
import com.justdataplease.boringtravel.ai.tools.MapsLookup
import com.justdataplease.boringtravel.ai.tools.PageLookup
import com.justdataplease.boringtravel.ai.tools.PageTitle
import com.justdataplease.boringtravel.ai.tools.PendingPlan
import com.justdataplease.boringtravel.ai.tools.PlaceLookup
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.firebase.FirebaseSetup
import com.justdataplease.boringtravel.io.IOException
import com.justdataplease.boringtravel.site.SiteAi
import com.justdataplease.boringtravel.site.SiteOrganizer
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.time.today
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/*
 * The AI planner's working parts for the screens (the Android app's ui/ai/PlannerViewModel.kt and ui/me/AiScreen.kt
 * call them through its AppGraph: graph.planner, graph.ownAi, PlannerUsage, graph.places, graph.links, the database's
 * transaction): which engine answers, the planner's process with this app's place search and link readers, the daily
 * allowance, Me › AI's keys and their test, the pictures read into text, and a reviewed draft saved into the trip
 * store. The screens get it as [LocalPlannerKit].
 */

/**
 * What the phone gives the AI planner: where Me › AI keeps its keys ([keys]: the Keychain on iPhone, a file on the
 * desktop, memory in tests and previews), the App Check token ([appCheck]: none until the owner's Firebase iPhone app
 * has App Check), the app's bundle id (sent with the built-in planner's requests), and this build's Firebase ids
 * ([firebase], [aiModel]; empty by default, so previews and tests never reach a model).
 */
class PlannerPhone(
    val keys: KeyValues = MemoryKeyValues(),
    val appCheck: AppCheckTokens = AppCheckTokens { null },
    val bundleId: String? = null,
    val firebase: FirebaseSetup.Config = FirebaseSetup.Config("", "", ""),
    val aiModel: String = "",
) {
    companion object {
        /** The phone's own parts with the ids this build was made with ([FirebaseSetup]). */
        fun ofBuild(keys: KeyValues, appCheck: AppCheckTokens = AppCheckTokens { null }, bundleId: String? = null): PlannerPhone =
            PlannerPhone(keys, appCheck, bundleId, FirebaseSetup.config, FirebaseSetup.aiModel)
    }
}

/**
 * The AI planner for the screens, on the trip store [book], this phone's [settings] (the daily count, never in a
 * backup), the network side [web] (place search, link previews, Google Maps lists), the app's [files] (picked
 * pictures) and the phone's parts ([phone]). [version] goes with the built-in planner's requests. [isPro]: Pro is
 * not on sale, so everything is open (Android's EVERYTHING_UNLOCKED): only the daily cap counts.
 */
class PlannerKit(
    private val book: TripBook,
    private val settings: AppSettings,
    private val web: Web,
    private val files: MediaFiles,
    phone: PlannerPhone = PlannerPhone(),
    version: String = "",
    private val isPro: () -> Boolean = { true },
    private val now: () -> Long = ::nowMs,
    private val ids: () -> String = ::newId,
) {
    /** Me › AI: the traveller's own keys and choices, each provider's calls. */
    val ownAi = OwnAi(phone.keys, PlainCipher, web.net)

    /** The built-in planner (Gemini through Firebase AI Logic). */
    val builtIn = GeminiPlanner(web.net, phone.firebase, phone.aiModel, phone.appCheck, version, phone.bundleId)

    /** The daily count behind [PlannerAllowance], this phone's own. */
    val usage: PlannerUsage get() = settings.plannerUsage

    /** Pages for read_link: Copy from a link's readers, as on Android ([LinkReader], read in). */
    val linkReader = LinkReader.forApp(web)

    /** The map search the planner uses: the app's own (Photon), as Android's graph.places. */
    val places = PlaceLookup { query, near -> web.places.search(query, near) }

    /** The planner's process: the same steps whatever model answers; the model only gives inputs ([PlannerProcess]). */
    val process = PlannerProcess(
        places,
        LinkLookup { url -> linkReader.read(url) },
        MapsLookup { url, near ->
            // The link previews' reader: a short link followed, its name searched near the trip when it has no pin.
            web.links.mapsPlace(url, near) ?: web.links.preview(url, near)?.title?.let { MapsPlace(it, null, null) }
        },
        // A website's title and site name, as its link preview reads them (kept for the item's card).
        PageLookup { url -> web.links.preview(url)?.let { PageTitle(it.title, it.siteName) } },
        // A shared list of saved places: every place it holds.
        MapsListLookup { url -> web.links.mapsList(url) },
    )

    /** The engine for the next request (Android's PlannerViewModel.engine): the own key when it answers, else built-in. */
    fun engine(): PlannerEngine = ownAi.engine() ?: builtIn

    /** The provider of the traveller's own key while it answers (no allowance then: their own bill), else null. */
    val ownKey: OwnAiProvider? get() = ownAi.state.value.active

    /** "Using your Claude key · claude-sonnet-latest" while [ownKey] answers. */
    val ownKeyLine: String? get() = ownAi.state.value.usingLine()

    /** Someone can answer: an own key, or this build's Firebase ids. */
    val available: Boolean get() = ownKey != null || builtIn.isAvailable

    /** How many free plans are left (none are counted while Pro isn't on sale). */
    val freeLeft: Int get() = PlannerAllowance.freeLeft(usage.freeUsed)

    /** Whether a message may go to the planner now (Android's PlannerViewModel.allowed). [startsPlan]: a chat's first answer. */
    sealed interface Allowed {
        data object Yes : Allowed

        /** Pro is needed (never while Pro isn't on sale). */
        data object NeedsPro : Allowed

        /** Not now, with the words to show: not set up, or the day's answers used. */
        data class No(val message: String) : Allowed
    }

    fun allowed(startsPlan: Boolean): Allowed {
        // With the traveller's own key there is no allowance and no daily cap: it is their own bill.
        if (ownKey != null) return Allowed.Yes
        if (!available) return Allowed.No(PlannerError.NotSetUp.message)
        return when (usage.check(isPro(), startsPlan)) {
            is PlannerAllowance.Check.Allowed -> Allowed.Yes
            PlannerAllowance.Check.NeedsPro -> Allowed.NeedsPro
            PlannerAllowance.Check.DailyLimit -> Allowed.No(DAILY_LIMIT)
        }
    }

    /** Counts one answer of the built-in planner (Android: after each answer that wasn't the app's or an own key's). */
    fun recordAnswer(startsPlan: Boolean) = usage.record(isPro(), startsPlan)

    /**
     * The model that sorts Copy from a link's posts into trips (Android's siteAi): the traveller's own key when Me › AI
     * uses one (their own bill, no count), else the built-in planner, each request one of the day's answers
     * ([PlannerAllowance.DAILY_ANSWERS]); null when neither can answer (the dates sort the posts then). It is sent the
     * numbered list of dates, titles and first words, and in a copy of a few posts the start of each paragraph and heading.
     */
    fun siteAi(): SiteAi? {
        ownAi.engine()?.let { own -> return SiteAi { system, prompt -> own.answer(system, prompt, SiteOrganizer.schema) } }
        if (!builtIn.isAvailable) return null
        return SiteAi { system, prompt ->
            if (usage.check(isPro(), startsPlan = false) !is PlannerAllowance.Check.Allowed) throw IOException("No AI answers left today")
            builtIn.answer(system, prompt, SiteOrganizer.schema).also { usage.record(isPro(), startsPlan = false) }
        }
    }

    /** Steps 1–5 of a message ([PlannerProcess.run]) with [engine], as the chat sends it. */
    suspend fun run(
        engine: PlannerEngine, trip: Trip?, items: List<PlanItem>, pending: PendingPlan, history: List<ChatTurn>, message: String,
        today: LocalDate = today(), onStep: (String) -> Unit = {},
    ): PlannerProcess.Outcome = process.run(engine, trip, items, pending, history, message, today, onStep)

    /**
     * What attached pictures say (Android's PlannerViewModel.readPictures): read by [own]'s model, else the built-in
     * planner's (one of the day's answers), made into the planner's message ("From 2 pictures: …"). [paths] are under
     * the app's files, where the picker put them.
     */
    suspend fun readPictures(paths: List<String>, own: OwnKeyPlanner?): String {
        val count = paths.size
        val loaded = PicturePrep.load(files, paths)
        val answer = if (own != null) {
            own.answer(PictureReading.system, PictureReading.prompt(count), PictureReading.schema, loaded)
        } else {
            builtIn.answer(PictureReading.system, PictureReading.prompt(count), PictureReading.schema, loaded).also {
                usage.record(isPro(), startsPlan = false)
            }
        }
        return PictureReading.message(PictureReading.parse(answer, count), count)
    }

    /** Why reading the pictures failed, in the words Android shows (PlannerViewModel.pictureError). */
    fun pictureError(e: Throwable, own: OwnKeyPlanner?, count: Int): String = when {
        e is TimeoutCancellationException -> PictureReading.TOO_SLOW
        e is ItineraryImportException -> e.message ?: PictureReading.nothing(count)
        e is OwnKeyException && own != null -> PictureReading.ownKeyMessage(e.error, own.provider)
        e is Exception -> PlannerError.of(e, false).message
        else -> PictureReading.CANT_OPEN
    }

    /** Why a message failed, in the words Android shows (PlannerViewModel.sendText); [own]: the own key's provider. */
    fun planError(e: Throwable, own: OwnAiProvider?): String = when {
        e is TimeoutCancellationException -> TOO_LONG
        e is DraftLimitException -> e.message ?: PlannerError.Unknown.message
        e is OwnKeyException && own != null -> e.error.message(own)
        else -> PlannerError.of(e, false).message
    }

    /**
     * Step 6 (Android's PlannerViewModel.apply): the ticked changes of [draft] laid out again against the plan as it is
     * now, so nothing is doubled, and written into the trip store in one save. [tripId]: the trip the chat changes (null:
     * a new trip). Throws [StaleDraftException] ("This trip was deleted.", "… changed after this draft was made …")
     * with words to show. A new trip of the traveller's own ends demo mode on Android: [Saved.result]'s newTrip says so.
     */
    fun save(tripId: String?, draft: TripDraft, baseline: List<PlanItem>, unticked: Set<String>): Saved {
        val current = tripId?.let { book.snapshot.trips.firstOrNull { t -> t.id == it } }
        if (tripId != null && (current == null || current.deletedAt != null)) throw StaleDraftException("This trip was deleted.")
        val existing = current?.let { t -> book.snapshot.planItems.filter { it.tripId == t.id } }.orEmpty()
        // Additions are deduplicated against today's rows; updates keep the reviewed baseline.
        val change = process.save(draft, current, existing, baseline, unticked, draftId = ids(), now = now(), newId = ids)
        book.saveDraft(if (change.saveTrip) change.trip else null, change.rows)
        return Saved(change, summary(change))
    }

    /** A saved draft and its line for the screen ("Trip created. Added 12 items on 5 days, 3 ideas."). */
    data class Saved(val result: ApplyResult, val summary: String)

    /** Me › AI's Test, done (Android's AiViewModel.test): what the screen shows and whether the key and model were kept. */
    data class KeyTest(
        /** The provider's models to offer; null when none came (or the key can't list them). */
        val models: List<String>?,
        /** This key may not list models (an OpenAI restricted key): a model id is typed instead. */
        val listUnavailable: Boolean,
        val note: String,
        val error: Boolean,
        /** "sk-…a1b2" when a typed key was kept. */
        val mask: String? = null,
    )

    /**
     * Checks [typed] (or the saved key) with [p], then asks [model] (typed or picked; else the saved one or the first
     * offered) for a structured answer. Only when it answers are the key and the model saved and the planner switched
     * to [p]; a failure leaves everything as it was. At most 45 s.
     */
    suspend fun testKey(p: OwnAiProvider, typed: String, model: String? = null): KeyTest {
        val clean = OwnKeyStore.clean(typed)
        val key = clean.ifEmpty { null } ?: ownAi.key(p) ?: return KeyTest(null, false, "Paste a key first.", error = true)
        var offered: List<String>? = null
        var unlisted = false
        return try {
            withTimeout(TEST_TIMEOUT_MS) {
                val listed = ownAi.api(p).modelsOrNull(key)
                offered = listed?.let { OwnAiModels.offered(p, it) }
                unlisted = listed == null
                val saved = ownAi.state.value.model(p)
                val chosen = model ?: (offered?.let { OwnAiModels.choose(it, saved) } ?: if (listed == null) saved ?: OwnAiModels.fallback(p) else null)
                    ?: return@withTimeout KeyTest(offered, unlisted, "This key lists no model that can plan a trip. Type a model id.", error = true)
                ownAi.api(p).ping(key, chosen)
                // It works: now save, and the planner uses it.
                if (clean.isNotEmpty()) ownAi.saveKey(p, clean)
                ownAi.setModel(p, chosen)
                ownAi.setProvider(p)
                KeyTest(offered, unlisted, "Works. $chosen answered.", error = false, mask = clean.takeIf { it.isNotEmpty() }?.let(OwnKeyStore::mask))
            }
        } catch (_: TimeoutCancellationException) {
            KeyTest(offered, unlisted, OwnKeyError.Timeout.message(p, inAiSettings = true), error = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OwnKeyException) {
            // A wrong key also can't list models: it isn't a restricted one, so no "type a model id".
            KeyTest(offered, unlisted && e.error != OwnKeyError.WrongKey, e.error.message(p, inAiSettings = true), error = true)
        } catch (_: Exception) {
            KeyTest(offered, unlisted, OwnKeyError.Unknown.message(p, inAiSettings = true), error = true)
        }
    }

    /** The saved key's models, offered (Android's AiViewModel.showSaved: a free list call); nothing is saved. */
    suspend fun savedModels(p: OwnAiProvider): KeyTest? {
        val key = ownAi.key(p) ?: return null
        return try {
            val listed = withTimeout(LIST_TIMEOUT_MS) { ownAi.api(p).modelsOrNull(key) }
            KeyTest(listed?.let { OwnAiModels.offered(p, it) }, listed == null, "", error = false)
        } catch (_: TimeoutCancellationException) {
            KeyTest(null, false, OwnKeyError.Timeout.message(p, inAiSettings = true), error = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OwnKeyException) {
            KeyTest(null, false, e.error.message(p, inAiSettings = true), error = true)
        } catch (_: Exception) {
            KeyTest(null, false, OwnKeyError.Unknown.message(p, inAiSettings = true), error = true)
        }
    }

    companion object {
        const val DAILY_LIMIT = "That's the limit for today. Try again tomorrow."
        const val TOO_LONG = "The planner took too long. Try again, or send fewer days at a time."

        /** The whole request, every model turn and map search included (Android's REQUEST_TIMEOUT_MS). */
        const val REQUEST_TIMEOUT_MS = 480_000L

        /** Pictures: opened, sent and read by the model (Android's PICTURE_TIMEOUT_MS). */
        const val PICTURE_TIMEOUT_MS = 150_000L

        /** Me › AI's Test and its model list (Android's AiViewModel). */
        const val TEST_TIMEOUT_MS = 45_000L
        const val LIST_TIMEOUT_MS = 20_000L

        /** "Trip created. Dates changed. Added 12 items on 5 days, 3 ideas." (Android's PlannerViewModel.apply). */
        fun summary(result: ApplyResult): String = buildList {
            if (result.newTrip) add("Trip created")
            if (result.datesChanged) add("Dates changed")
            if (result.namedDays > 0) add(if (result.namedDays == 1) "Day renamed" else "${result.namedDays} days renamed")
            if (result.items.isNotEmpty()) add("Added ${result.summary}")
            if (result.updatedItems.isNotEmpty()) add("Changed ${result.updatedItems.size} ${if (result.updatedItems.size == 1) "item" else "items"}")
            if (result.removedItems.isNotEmpty()) add("Removed ${result.removedItems.size}")
            if (result.alreadyUpdated > 0) add("${result.alreadyUpdated} already up to date")
        }.joinToString(". ").ifBlank { "Nothing to change" } + "."
    }
}

/** The AI planner for every screen; null in previews that don't need one. */
val LocalPlannerKit = staticCompositionLocalOf<PlannerKit?> { null }
