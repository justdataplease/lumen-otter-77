package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.ai.ApplyResult
import com.justdataplease.boringtravel.ai.GeminiPlanner
import com.justdataplease.boringtravel.ai.PlannerAllowance
import com.justdataplease.boringtravel.ai.PlannerEngine
import com.justdataplease.boringtravel.ai.PlannerProcess
import com.justdataplease.boringtravel.ai.own.GeminiRest
import com.justdataplease.boringtravel.ai.own.KeyValues
import com.justdataplease.boringtravel.ai.own.MemoryKeyValues
import com.justdataplease.boringtravel.ai.own.OwnAi
import com.justdataplease.boringtravel.ai.own.OwnAiProvider
import com.justdataplease.boringtravel.ai.own.OwnKeyPlanner
import com.justdataplease.boringtravel.ai.own.PlainCipher
import com.justdataplease.boringtravel.ai.tools.PlannerTools
import com.justdataplease.boringtravel.ai.tools.TravelBrief
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.firebase.FirebaseSetup
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/*
 * The AI planner on recorded answers, for the pictures and the tests: the model's answers as Gemini writes them
 * (generateContent with functionCall parts for the planner's tools), the own-key provider's model list and Test's ping,
 * and a network that answers the model's addresses from them and everything else from [Recorded]. Nothing reaches a
 * model or the network; the Firebase ids are made up, so the built-in planner counts as set up.
 *
 */

/**
 * Recorded answers of the models the planner asks: the built-in planner (Firebase AI Logic's generateContent, the
 * planner's tools called in one turn) and a Gemini key's model list and Test (Google AI Studio's REST API).
 */
object RecordedAi {
    /**
     * Made-up Firebase ids: the built-in planner counts as set up, and its requests go to [PlannerNet] only. Not a key:
     * nothing that looks like Google's key format, so no secret scanner trips on it.
     */
    val FIREBASE = FirebaseSetup.Config("demo-project", "1:000000000000:ios:0000000000000000", "demo-firebase-api-key")

    /** A made-up Gemini key for Me › AI ("dem…wxyz" masked). */
    const val OWN_KEY = "demo-gemini-key-0000-not-real-wxyz"

    /** The built-in planner's requests: POST …/v1beta/projects/<project>/models/<model>:generateContent. */
    fun isBuiltIn(url: String): Boolean = url.startsWith("${GeminiPlanner.BASE}/projects/") && url.endsWith(":generateContent")

    /** A Gemini key's model list: GET …/v1beta/models?pageSize=1000. */
    fun isGeminiModels(url: String): Boolean = url.startsWith("${GeminiRest.BASE}/models?")

    /** A Gemini key's answers (Test's ping, a plan): POST …/v1beta/models/<model>:generateContent. */
    fun isGeminiGenerate(url: String): Boolean = url.startsWith("${GeminiRest.BASE}/models/") && url.endsWith(":generateContent")

    /**
     * A generateContent answer whose one candidate calls the planner's tools in [calls]' order, as Gemini sends them:
     * role "model", one functionCall part per call (the first with a thought signature, which the planner sends back
     * unchanged), finishReason STOP, the token counts.
     */
    fun calls(vararg calls: Pair<String, JsonObject>): String = buildJsonObject {
        putJsonArray("candidates") {
            add(buildJsonObject {
                putJsonObject("content") {
                    put("role", "model")
                    putJsonArray("parts") {
                        calls.forEachIndexed { i, (name, args) ->
                            add(buildJsonObject {
                                putJsonObject("functionCall") {
                                    put("name", name)
                                    put("args", args)
                                }
                                if (i == 0) put("thoughtSignature", "c2lnbmF0dXJlLWZvci10ZXN0cw==")
                            })
                        }
                    }
                }
                put("finishReason", "STOP")
                put("index", 0)
            })
        }
        putJsonObject("usageMetadata") {
            put("promptTokenCount", 5210)
            put("candidatesTokenCount", 640)
            put("totalTokenCount", 5850)
        }
        put("modelVersion", GeminiPlanner.DEFAULT_MODEL)
    }.toString()

    const val NEW_TRIP_TITLE = "Lisbon, food and old streets"
    const val NEW_TRIP_ROUTE = "Lisbon → Belém"
    const val NEW_TRIP_SUMMARY = "Three easy days in Lisbon with food first. Day three is left free."

    /** The new trip's items in the order the answer adds them: two on day 1, two on day 2. */
    val NEW_TRIP_ITEMS = listOf("Alfama walk", "Time Out Market", "Jerónimos Monastery", "Pastéis de Belém")

    private fun item(date: LocalDate, time: String, kind: String, title: String, place: String, lat: Double, lng: Double) = buildJsonObject {
        put("date", date.toString())
        put("time", time)
        put("kind", kind)
        put("title", title)
        put("placeName", place)
        // With their pins: the app's map search (step 4) is never asked.
        put("lat", lat)
        put("lng", lng)
    }

    /**
     * The planner's answer to the trip questions: a new trip of three days from [start] (set_trip), four items on its
     * first two days (add_items), and its reply (finish). [start] must not be before today: a past date of a new trip
     * comes back to the model as a problem (ToolRunner.pastProblem) and costs a second request.
     */
    fun newTrip(start: LocalDate): String = calls(
        PlannerTools.SET_TRIP to buildJsonObject {
            put("title", NEW_TRIP_TITLE)
            put("startDate", start.toString())
            put("endDate", start.plusDays(2).toString())
            put("route", NEW_TRIP_ROUTE)
        },
        PlannerTools.ADD_ITEMS to buildJsonObject {
            putJsonArray("items") {
                add(item(start, "10:00", "place", "Alfama walk", "Alfama, Lisbon, Portugal", 38.7114, -9.1300))
                add(item(start, "13:00", "food", "Time Out Market", "Mercado da Ribeira, Lisbon, Portugal", 38.7069, -9.1459))
                add(item(start.plusDays(1), "10:30", "place", "Jerónimos Monastery", "Praça do Império, Belém, Lisbon, Portugal", 38.6979, -9.2068))
                add(item(start.plusDays(1), "12:30", "food", "Pastéis de Belém", "Rua de Belém 84, Lisbon, Portugal", 38.6975, -9.2033))
            }
        },
        PlannerTools.FINISH to buildJsonObject { put("summary", NEW_TRIP_SUMMARY) },
    )

    const val CHANGE_SUMMARY = "Dinner moves half an hour later, and one idea goes."

    /** The planner's answer to "move dinner, drop the idea" on a saved trip: one time changed, one saved item removed. */
    fun change(dinnerId: String, ideaId: String): String = calls(
        PlannerTools.UPDATE_ITEMS to buildJsonObject {
            putJsonArray("updates") { add(buildJsonObject { put("itemId", dinnerId); put("time", "20:30") }) }
        },
        PlannerTools.REMOVE_ITEMS to buildJsonObject { putJsonArray("itemIds") { add(JsonPrimitive(ideaId)) } },
        PlannerTools.FINISH to buildJsonObject { put("summary", CHANGE_SUMMARY) },
    )

    /**
     * A Gemini key's model list (GeminiRestTest's shape): the three aliases the app offers, a fixed version and an
     * embedding model the app leaves out.
     */
    val GEMINI_MODELS = """
        {"models":[
          {"name":"models/gemini-flash-latest","supportedGenerationMethods":["generateContent","countTokens"]},
          {"name":"models/gemini-pro-latest","supportedGenerationMethods":["generateContent","countTokens"]},
          {"name":"models/gemini-flash-lite-latest","supportedGenerationMethods":["generateContent","countTokens"]},
          {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-embedding-002","supportedGenerationMethods":["embedContent"]}
        ]}
    """.trimIndent()

    /** Test's answer: the ping's one field (JsonSchemas.ping: {"reply": "OK"}). */
    val PING = """{"candidates":[{"content":{"role":"model","parts":[{"text":"{\"reply\":\"OK\"}"}]},"finishReason":"STOP"}],""" +
        """"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":5,"totalTokenCount":17}}"""

    /** The questions as the screenshots answer them. */
    val BRIEF = TravelBrief(
        where = "Lisbon", whenText = "May", days = 3, who = TravelBrief.Who.Couple, pace = TravelBrief.Pace.Balanced,
        interests = setOf(TravelBrief.Interest.Food, TravelBrief.Interest.Sights),
    )
}

/**
 * The network for the AI planner's tests and pictures: the model's addresses answered from recorded answers in turn
 * (each request takes the next one for its address, the last one stays), everything else from [web] (the place search,
 * link previews: [Recorded.net]). An address can be held: its requests wait until they are cancelled (Stop). Every
 * request is kept in [asked], with its headers and body.
 */
class PlannerNet(private val web: Net = Recorded.net()) : Net {
    val asked: MutableList<NetRequest> = CopyOnWriteArrayList()

    private class Route(val matches: (String) -> Boolean, val bodies: List<String>, val code: Int, val held: Boolean) {
        var next = 0
    }

    private val routes = CopyOnWriteArrayList<Route>()

    /** Requests to an address [matches] takes get [bodies] in turn ([code], JSON); the last one stays. */
    fun answer(matches: (String) -> Boolean, vararg bodies: String, code: Int = 200): PlannerNet = apply {
        routes += Route(matches, bodies.toList(), code, held = false)
    }

    /** Requests to an address [matches] takes are never answered: they wait until cancelled. */
    fun hold(matches: (String) -> Boolean): PlannerNet = apply { routes += Route(matches, emptyList(), 0, held = true) }

    /** The requests to the built-in planner. */
    val builtInAsked: List<NetRequest> get() = asked.filter { RecordedAi.isBuiltIn(it.url) }

    override fun online(): Boolean = true

    override suspend fun get(request: NetRequest): NetAnswer = reply(request)

    override suspend fun post(request: NetRequest): NetAnswer = reply(request)

    private suspend fun reply(request: NetRequest): NetAnswer {
        asked += request
        val route = routes.firstOrNull { it.matches(request.url) }
            ?: return if (request.body == null) web.get(request) else web.post(request)
        if (route.held) awaitCancellation()
        val body = synchronized(route) { route.bodies[minOf(route.next++, route.bodies.lastIndex)] }
        return NetAnswer(route.code, request.url, mapOf("Content-Type" to "application/json; charset=UTF-8"), body.encodeToByteArray())
    }
}

/** A photo library that hands over the pictures it was given (already under the app's files). */
private class PickedPhotos(private val picked: List<PickedMedia>) : MediaPicker {
    override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) = done(picked.take(max))
    override val hasCamera: Boolean = false
    override fun takePhoto(done: (PickedMedia?) -> Unit) = done(null)
    override fun recordVideo(done: (PickedMedia?) -> Unit) = done(null)
}

/** An iPhone 17 Pro Max, as Screenshots.kt draws it (its own constants are private there). */
private const val PLANNER_WIDTH = 1320
private const val PLANNER_HEIGHT = 2868
private const val PLANNER_DENSITY = 3f
private val PLANNER_FRAME = PaddingValues(top = 62.dp, bottom = 34.dp)

private fun plannerShot(file: File, frames: Int = 24, height: Int = PLANNER_HEIGHT, content: @Composable () -> Unit) {
    file.writeBytes(render(PLANNER_WIDTH, height, PLANNER_DENSITY, frames, content))
}

/** Waits for [chat]'s message to be answered (or to fail): the recorded answers come at once. */
private fun settleChat(chat: PlannerChat, timeoutMs: Long = 20_000) {
    val until = System.currentTimeMillis() + timeoutMs
    while ((chat.busy || chat.applying) && System.currentTimeMillis() < until) Thread.sleep(10)
}

/** Waits until the built-in planner has been asked on [net] (the request is out and waiting). */
private fun inFlight(net: PlannerNet, timeoutMs: Long = 20_000) {
    val until = System.currentTimeMillis() + timeoutMs
    while (net.builtInAsked.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(10)
}

/** Three sample photos under picked/ in [files], as the photo picker leaves them. */
private fun pickedPhotos(files: MediaFiles): List<PickedMedia> =
    listOf(Scene.Sea to "a1", Scene.Town to "a2", Scene.Sunset to "a3").map { (scene, name) ->
        val path = "picked/$name.jpg"
        files.write(path, samplePhoto(scene, 900, 1200))
        PickedMedia(path, MediaTypes.JPEG, 900, 1200)
    }

/**
 * The AI planner, in two looks each: the empty chat (a new trip, a saved trip with and without a plan), the + sheet,
 * Add a link with the clipboard's link, the attachments above the box, the trip questions (empty, and filled in as the
 * planner hands them over), working, a reply with the review bar, the review sheet with one change unticked, the trip
 * choice, Stop and the day's limit; Me › AI with the built-in planner and with an own key and its models; New trip's
 * choices with the AI panel; the trip header with Plan with AI. On recorded answers ([RecordedAi]), never a model.
 */
fun planner(out: File) {
    val newTripStart: LocalDate = localDate(2027, 5, 12)
    fun answering(): PlannerNet = PlannerNet()
        .answer(RecordedAi::isBuiltIn, RecordedAi.newTrip(newTripStart))
        .answer(RecordedAi::isGeminiModels, RecordedAi.GEMINI_MODELS)
        .answer(RecordedAi::isGeminiGenerate, RecordedAi.PING)

    /** The whole app at [at], with the planner's parts of the phone made up (Firebase ids, [keys]). */
    fun app(
        name: String, look: Look, book: TripBook, vararg at: Place,
        settings: AppSettings = AppSettings.ready(look), net: PlannerNet = answering(), keys: KeyValues = MemoryKeyValues(),
        media: MediaKit = MediaKit.memory(), phone: Phone = Phone(paste = { null }), frames: Int = 24, height: Int = PLANNER_HEIGHT,
    ) {
        // Made once, outside the composition: the kit is remembered by them.
        val web = Recorded.web(net)
        val ai = PlannerPhone(keys, firebase = RecordedAi.FIREBASE)
        plannerShot(File(out, "$name.png"), frames, height) {
            CompositionLocalProvider(LocalPhone provides phone) {
                BoringTravelApp(book, settings, SAMPLE_TODAY, PLANNER_FRAME, startAt = at.toList(), version = PREVIEW_VERSION, media = media, web = web, ai = ai)
            }
        }
    }

    /** A chat made here on the kit and brought to its state by [prepare] (outside the screen), then drawn. */
    class Prepared(val book: TripBook, val settings: AppSettings, val net: PlannerNet, val media: MediaKit, val tripId: String?) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val web = Recorded.web(net)
        val kit = PlannerKit(book, settings, web, media.files, PlannerPhone(firebase = RecordedAi.FIREBASE), PREVIEW_VERSION)
        val side = KitPlannerSide(kit, book, media.files)
        val chat = PlannerChat(side, tripId, scope)
    }

    fun prepared(
        name: String, look: Look, book: TripBook = TripBook(MemoryTripFile()), tripId: String? = null,
        settings: AppSettings = AppSettings.ready(look), net: PlannerNet = answering(), media: MediaKit = MediaKit.memory(),
        attachAtStart: Boolean = false, height: Int = PLANNER_HEIGHT, prepare: (PlannerChat) -> Unit,
    ) {
        val p = Prepared(book, settings, net, media, tripId)
        prepare(p.chat)
        plannerShot(File(out, "$name.png"), frames = 30, height = height) {
            CompositionLocalProvider(
                LocalPlannerKit provides p.kit, LocalMediaKit provides media, LocalPhone provides Phone(paste = { null }),
                LocalAppVersion provides PREVIEW_VERSION, LocalWeb provides p.web,
            ) {
                BoringTravelTheme(look) {
                    Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(PLANNER_FRAME)) {
                        PlannerScreen(
                            p.side, tripId, start = null, dictationLanguage = null,
                            onBack = {}, onAiSettings = {}, onCopySite = { _, _ -> }, onSaved = { _, _ -> }, onReport = { _, _ -> },
                            // A chat brought to its state outside the screen (working, a reply, the review, an error).
                            chatAtStart = p.chat,
                            // The + sheet open at the start.
                            attachAtStart = attachAtStart,
                        )
                    }
                }
            }
        }
        p.scope.cancel()
    }

    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id

        // The empty chat: a new trip (Start from, Plan a trip for me); a saved trip with a plan (Add from, no plan button);
        // a saved trip without one (Plan the days for me).
        app("planner-new-$id", look, TripBook(MemoryTripFile()), Place.Planner(null, null))
        val small = smallTripBook()
        val naxos = small.trips(SAMPLE_TODAY).single()
        app("planner-trip-$id", look, small, Place.Trip(naxos.id), Place.Planner(naxos.id, null))
        val bare = TripBook(MemoryTripFile())
        val lisbon = bare.newTrip("Lisbon", localDate(2027, 5, 12), localDate(2027, 5, 14), subtitle = "Lisbon → Belém")
        app("planner-trip-empty-$id", look, bare, Place.Trip(lisbon.id), Place.Planner(lisbon.id, null))

        // The + sheet: Pictures and Link.
        prepared("planner-attach-sheet-$id", look, attachAtStart = true) {}

        // Add a link, opened from New trip's "From a site, blog or Google list": the clipboard's link already in.
        app("planner-add-link-$id", look, TripBook(MemoryTripFile()), Place.Planner(null, PlannerStart.LINK), phone = Phone(paste = { Recorded.YASAKA_SHORT }), frames = 12)

        // The attachments above the box: pictures from New trip's "From pictures" (no new parameter needed) …
        val pictureFiles = MemoryMediaFiles()
        val photos = pickedPhotos(pictureFiles)
        app("planner-pictures-$id", look, TripBook(MemoryTripFile()), Place.Planner(null, PlannerStart.PICTURES), media = MediaKit(pictureFiles, PickedPhotos(photos)))
        // … and pictures with two links and words typed.
        val stripFiles = MemoryMediaFiles()
        val stripPhotos = pickedPhotos(stripFiles)
        prepared("planner-attachments-$id", look, media = MediaKit(stripFiles)) { chat ->
            chat.addPictures(stripPhotos.map { it.path })
            chat.addLink(Recorded.YASAKA_SHORT)
            chat.addLink(Recorded.KYOTO_PAGE_URL)
            chat.updateInput("Plan two days around these")
        }

        // The trip questions: empty, from New trip's "From a questionnaire"; then filled in with what the planner already
        // knew, as ask_traveller hands them over.
        app("planner-questions-$id", look, TripBook(MemoryTripFile()), Place.Planner(null, PlannerStart.QUESTIONS))
        prepared("planner-questions-filled-$id", look) { chat ->
            chat.openQuestions(TravelBrief(where = "Lisbon", whenText = "May", days = 3, who = TravelBrief.Who.Couple))
        }

        // Working: the message sent, the model not answering yet, Stop.
        val holding = PlannerNet().hold(RecordedAi::isBuiltIn)
        prepared("planner-working-$id", look, net = holding) { chat ->
            chat.updateInput("Three days in Lisbon in May. Food, old streets and one morning in Belém.")
            chat.send()
            inFlight(holding)
        }

        // A reply with the review bar, then the review sheet with one change unticked.
        prepared("planner-reply-$id", look) { chat ->
            chat.sendAnswers(RecordedAi.BRIEF)
            settleChat(chat)
        }
        prepared("planner-review-$id", look) { chat ->
            chat.sendAnswers(RecordedAi.BRIEF)
            settleChat(chat)
            chat.proposal?.layout?.all?.firstOrNull { it.item.title == "Time Out Market" }?.let { chat.toggle(it.key) }
            chat.reviewing = true
        }

        // The trip choice: a new-trip chat of a traveller with trips of their own.
        prepared("planner-trip-choice-$id", look, book = sampleBook()) { chat ->
            chat.sendAnswers(RecordedAi.BRIEF)
            settleChat(chat)
        }

        // Errors: Stop ("Planning stopped. Your message is kept.", the words back in the box) and the day's limit
        //.
        val stopping = PlannerNet().hold(RecordedAi::isBuiltIn)
        prepared("planner-stopped-$id", look, net = stopping) { chat ->
            chat.updateInput("Three days in Lisbon in May. Food, old streets and one morning in Belém.")
            chat.send()
            // Stopped once the request is out: a coroutine cancelled before it starts never runs its catch and finally.
            inFlight(stopping)
            chat.cancelPlan()
            settleChat(chat)
        }
        val spent = AppSettings.ready(look).apply { repeat(PlannerAllowance.DAILY_ANSWERS) { plannerUsage.record(isPro = true, startsPlan = false) } }
        prepared("planner-limit-$id", look, settings = spent) { chat ->
            chat.updateInput("Three days in Lisbon in May.")
            chat.send()
        }

        // Me › AI: the built-in planner; an own Gemini key saved, its models listed (the chips) on recorded answers.
        app("ai-built-in-$id", look, TripBook(MemoryTripFile()), Place.Home(HomeTab.Me), Place.AiSettings)
        val keys = MemoryKeyValues().also { k ->
            OwnAi(k, PlainCipher, OfflineNet).apply {
                setUseOwnKey(true)
                saveKey(OwnAiProvider.Gemini, RecordedAi.OWN_KEY)
                setModel(OwnAiProvider.Gemini, GeminiPlanner.DEFAULT_MODEL)
                setProvider(OwnAiProvider.Gemini)
            }
        }
        app("ai-own-key-$id", look, TripBook(MemoryTripFile()), Place.Home(HomeTab.Me), Place.AiSettings, keys = keys, frames = 30)
        // Me's row "AI" with the key in use.
        app("me-ai-row-$id", look, TripBook(MemoryTripFile()), Place.Home(HomeTab.Me), keys = keys, frames = 12)

        // New trip's choices with the AI panel (Make it myself, Or, the three AI ways, Or, the ready-made trip).
        app("new-trip-ai-$id", look, TripBook(MemoryTripFile()), Place.NewTrip, frames = 12)

        // The trip header with its Plan with AI sparkle.
        app("trip-plan-with-ai-$id", look, small, Place.Trip(naxos.id), frames = 12)
    }
    // The trip questions whole, in one tall picture.
    app("planner-questions-full", Look.PostcardPop, TripBook(MemoryTripFile()), Place.Planner(null, PlannerStart.QUESTIONS), height = PLANNER_HEIGHT * 2)
}
