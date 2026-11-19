package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.ai.GeminiPlanner
import com.justdataplease.boringtravel.ai.PictureReading
import com.justdataplease.boringtravel.ai.PlannerAllowance
import com.justdataplease.boringtravel.ai.PlannerError
import com.justdataplease.boringtravel.ai.own.GeminiRest
import com.justdataplease.boringtravel.ai.own.MemoryKeyValues
import com.justdataplease.boringtravel.ai.own.OwnAiProvider
import com.justdataplease.boringtravel.ai.own.OwnKeyPlanner
import com.justdataplease.boringtravel.ai.own.OwnKeyStore
import com.justdataplease.boringtravel.ai.tools.PlannerTools
import com.justdataplease.boringtravel.ai.tools.TravelBrief
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.BackupPreferences
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.firebase.FirebaseSetup
import com.justdataplease.boringtravel.time.today
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The AI planner on the desktop, on recorded model answers ([RecordedAi] through [PlannerNet]; never a model, never the
 * network): the empty chat, New trip's three AI ways, a new trip made from the trip questions and reviewed (one item
 * unticked) then created and shown on Plan, a saved trip changed from its Plan's Chat and saved, an empty plan's Chat
 * beside Add (Android 1.22.0), Stop, the day's limit, a build without Firebase ids, a link and pictures attached and
 * removed, and Me › AI with an own key tested, masked and removed, which never goes into a backup.
 */
@OptIn(ExperimentalTestApi::class)
class PlannerScreensOnDesktopTest {
    init {
        runBlocking { BtFontFiles.load() }
    }

    /** The app at [at] with the planner's parts of the phone made up: Firebase ids ([firebase]), keys in [keys]. */
    private fun ComposeUiTest.startApp(
        book: TripBook,
        vararg at: Place,
        settings: AppSettings = AppSettings.ready(),
        net: PlannerNet = PlannerNet(),
        keys: MemoryKeyValues = MemoryKeyValues(),
        firebase: FirebaseSetup.Config = RecordedAi.FIREBASE,
        media: MediaKit = MediaKit.memory(),
        // Never the desktop's real clipboard.
        phone: Phone = Phone(paste = { null }),
    ) {
        // Made once, outside the composition: the app remembers its kit by them.
        val web = Recorded.web(net)
        val ai = PlannerPhone(keys, firebase = firebase)
        setContent {
            CompositionLocalProvider(LocalPhone provides phone) {
                BoringTravelApp(book, settings, SAMPLE_TODAY, startAt = at.toList(), version = VERSION, media = media, web = web, ai = ai)
            }
        }
    }

    private fun ComposeUiTest.has(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 10_000) =
        waitUntil(timeoutMillis = timeoutMs) { has(text, substring) }

    private fun ComposeUiTest.count(description: String) = onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    /** A photo library that hands over [next] (pictures already under the app's files, as the picker leaves them). */
    private class Library(private val files: MediaFiles) : MediaPicker {
        var next: List<PickedMedia> = emptyList()
        var asked = 0
        var max = 0

        fun photo(name: String): PickedMedia {
            val path = "picked/$name.jpg"
            files.write(path, samplePhoto(Scene.Sea, 600, 800))
            return PickedMedia(path, MediaTypes.JPEG, 600, 800)
        }

        fun video(name: String) = PickedMedia("picked/$name.mov", "video/quicktime", 1920, 1080, durationMs = 4_000)

        override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) {
            asked++
            this.max = max
            done(next)
        }

        override val hasCamera: Boolean = false
        override fun takePhoto(done: (PickedMedia?) -> Unit) = done(null)
        override fun recordVideo(done: (PickedMedia?) -> Unit) = done(null)
    }

    @Test
    fun anEmptyChatForANewTripOffersEveryWayToStart() {
        val net = PlannerNet()
        runDesktopComposeUiTest(width = 440, height = 1600) {
            startApp(TripBook(MemoryTripFile()), Place.Planner(null, null), net = net)
            waitForText("Make a new trip")
            onNodeWithText("AI planner").assertExists()
            // Eyebrow upper-cases its text.
            onNodeWithText("NEW TRIP").assertExists()
            onNodeWithText("START FROM").assertExists()
            onNodeWithText("Pictures").assertExists()
            onNodeWithText("Screenshots, guide pages, maps or tickets. Up to ${PictureReading.MAX_PICTURES}.").assertExists()
            onNodeWithText("Link").assertExists()
            onNodeWithText("A Google Maps place or list, a web page, a blog or a Google Doc.").assertExists()
            onNodeWithText("Plan a trip for me").assertExists()
            // the box's placeholder (a Text in the decoration box) is in the semantics tree.
            onNodeWithText("Paste your trip, or say where and when").assertExists()
            // Send with nothing written sends nothing.
            onNodeWithContentDescription("Send").performClick()
            assertFalse(has(TravelBrief.HEADER, substring = true))
            assertTrue(net.builtInAsked.isEmpty())
        }
    }

    @Test
    fun newTripsThreeAiWaysOpenThePlannerAtTheirStep() {
        val files = MemoryMediaFiles()
        val library = Library(files).apply { next = listOf(photo("p1")) }
        val phone = Phone(paste = { Recorded.KYOTO_PAGE_URL })
        runDesktopComposeUiTest(width = 440, height = 1600) {
            startApp(TripBook(MemoryTripFile()), Place.NewTrip, media = MediaKit(files, library), phone = phone)
            waitForText("Make it myself")
            // NewTripChoices' AI panel has Android's titles and lines (EditTripScreen.kt, 18 Nov 2026).
            onNodeWithText("Where, when, who, pace and interests. AI plans every day.").assertExists()
            onNodeWithText("Screenshots, guide pages, maps or tickets. AI reads them.").assertExists()
            onNodeWithText("A web page, your blog, a Google Doc or a Google Maps list. AI reads it.").assertExists()
            onNodeWithText("Start from a ready-made trip").assertExists()

            // From pictures: the photo picker at once, its picture attached to the message.
            onNodeWithText("From pictures").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { count("Remove picture") == 1 }
            assertEquals(1, library.asked)
            onNodeWithText("AI planner").assertExists()
            onNodeWithContentDescription("Back").performClick()

            // From a site, blog or Google list: Add a link, with the clipboard's link already in.
            waitForText("From a site, blog or Google list")
            onNodeWithText("From a site, blog or Google list").performScrollTo().performClick()
            waitForText("Add a link")
            onNode(hasSetTextAction() and hasText(Recorded.KYOTO_PAGE_URL)).assertExists()
            onNodeWithText("Cancel").performClick()
            onNodeWithContentDescription("Back").performClick()

            // From a questionnaire: the trip questions.
            waitForText("From a questionnaire")
            onNodeWithText("From a questionnaire").performScrollTo().performClick()
            waitForText("Plan a trip")
            onNodeWithText("Answer what matters to you. Skip the rest and the planner decides.").assertExists()
        }
    }

    @Test
    fun theTripQuestionsAnsweredMakeANewTripThatIsReviewedAndCreated() {
        val book = TripBook(MemoryTripFile())
        val settings = AppSettings.ready()
        // Days ahead of the real today: the chat plans on the phone's day (a past day would cost a second request).
        val start = today().plusDays(30)
        val net = PlannerNet().answer(RecordedAi::isBuiltIn, RecordedAi.newTrip(start))
        runDesktopComposeUiTest(width = 440, height = 1600) {
            startApp(book, Place.NewTrip, settings = settings, net = net)
            onNodeWithText("From a questionnaire").performScrollTo().performClick()
            waitForText("Plan a trip")

            // Chips only: a place suggested, three days, a couple, balanced, food.
            for (chip in listOf("Suggest one", TravelBrief.daysLabel(3), "Couple", "Balanced", "Food")) {
                onNodeWithText(chip).performScrollTo().performClick()
            }
            // the sheet's chips are BtChip with its default Role.Tab, which marks the chosen one selected.
            onNodeWithText("Couple").assertIsSelected()
            onNodeWithText("Plan my trip").performScrollTo().performClick()

            // The answers sent as the traveller's message; the planner's reply; the review bar.
            waitForText("4 changes ready to check")
            assertTrue(has(TravelBrief.HEADER, substring = true))
            assertTrue(has("Where: suggest a place that fits", substring = true))
            onNodeWithText("PLANNER").assertExists()
            assertTrue(has(RecordedAi.NEW_TRIP_SUMMARY))
            onNodeWithText("Review").assertExists()
            onNodeWithText("Create trip").assertExists()
            // A new-trip chat of a traveller without trips: no trip choice.
            assertFalse(has("A new trip, or add to one you have?"))

            // One request, to Firebase AI Logic with this build's ids, the planner's tools, a tool call required.
            val request = net.builtInAsked.single()
            assertEquals("${GeminiPlanner.BASE}/projects/${RecordedAi.FIREBASE.projectId}/models/${GeminiPlanner.DEFAULT_MODEL}:generateContent", request.url)
            assertEquals(RecordedAi.FIREBASE.apiKey, request.headers["x-goog-api-key"])
            assertEquals(RecordedAi.FIREBASE.appId, request.headers["X-Firebase-AppId"])
            assertEquals(VERSION, request.headers["X-Firebase-AppVersion"])
            // No App Check on the desktop.
            assertNull(request.headers["X-Firebase-AppCheck"])
            val body = Json.parseToJsonElement(request.body!!).jsonObject
            assertEquals("ANY", body.getValue("tool_config").jsonObject.getValue("function_calling_config").jsonObject.getValue("mode").jsonPrimitive.content)
            val declared = body.getValue("tools").jsonArray.single().jsonObject.getValue("functionDeclarations").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertEquals(PlannerTools.all.map { it.name }, declared)
            assertTrue(TravelBrief.HEADER in request.body!!)
            // One answer of the day's allowance used.
            assertEquals(1, settings.plannerUsage.answersToday())

            // Review: the new trip, its items ticked; one unticked.
            onNodeWithText("Review").performClick()
            waitForText("New trip")
            onNodeWithText(RecordedAi.NEW_TRIP_TITLE).assertExists()
            RecordedAi.NEW_TRIP_ITEMS.forEach { onNodeWithText(it).assertExists() }
            onNodeWithText("4 of 4 selected").assertExists()
            onNodeWithText("Time Out Market").performScrollTo().performClick()
            waitForText("3 of 4 selected")
            // The sheet's Create trip (the review bar's is under the sheet). A sheet's nodes come after the
            // screen's, as ShareOnDesktopTest's onLast() relies on.
            onAllNodesWithText("Create trip").onLast().performScrollTo().performClick()

            // The trip is in the book with the three items still ticked, its dates and route from the answer.
            waitUntil(timeoutMillis = 10_000) { book.trips().any { it.title == RecordedAi.NEW_TRIP_TITLE } }
            val trip = book.trips().single { it.title == RecordedAi.NEW_TRIP_TITLE }
            assertEquals(start, trip.startDate)
            assertEquals(start.plusDays(2), trip.endDate)
            assertEquals(RecordedAi.NEW_TRIP_ROUTE, trip.subtitle)
            val items = book.items(trip.id)
            assertEquals(setOf("Alfama walk", "Jerónimos Monastery", "Pastéis de Belém"), items.map { it.title }.toSet())
            val alfama = items.single { it.title == "Alfama walk" }
            assertEquals(start, alfama.day)
            assertEquals(10 * 60, alfama.timeMinutes)
            assertEquals(38.7114, alfama.lat)
            // Nothing more was asked to save it.
            assertEquals(1, net.builtInAsked.size)
            // the summary is said in a snackbar (Android: a Toast), "Trip created. Added 3 items on 2 days."
            waitForText("Trip created", substring = true)

            // The app shows the trip on Plan; the chat is gone.
            // A plan row reads as one line ("10:00, Alfama walk  Alfama, Lisbon, Portugal").
            waitUntil(timeoutMillis = 10_000) { !has("AI planner") && has("Alfama walk", substring = true) }
            assertFalse(has("Time Out Market", substring = true))
            // a trip made from New trip replaces New trip: Back leaves the trip for Trips, not the form (the
            // trip page's back button is "Back").
            onNodeWithContentDescription("Back").performClick()
            waitForIdle()
            assertFalse(has("Make it myself"))
        }
    }

    @Test
    fun aSavedTripIsChangedFromItsPlansChatAndSaved() {
        val book = smallTripBook()
        val trip = book.trips(SAMPLE_TODAY).single()
        val dinner = book.items(trip.id).single { it.title == "Dinner in Chora" }
        val idea = book.items(trip.id).single { it.title == "Portara at sunset" }
        val net = PlannerNet().answer(RecordedAi::isBuiltIn, RecordedAi.change(dinner.id, idea.id))
        runDesktopComposeUiTest(width = 440, height = 1600) {
            startApp(book, Place.Trip(trip.id), net = net)
            // Android 1.22.0: the ✦ left the top bar and the Plan with AI card the plan; Chat floats beside Add.
            waitForText("Dinner in Chora")
            assertFalse(has("Plan with AI"))
            assertTrue(onAllNodesWithContentDescription("Plan with AI").fetchSemanticsNodes().isEmpty())
            onNodeWithText("Add").assertExists()
            onNodeWithText("Chat").performClick()

            // The chat for this trip: its name and dates above, Add from; its plan isn't empty, so no plan button.
            waitForText("Change this trip")
            assertTrue(has("NAXOS ·", substring = true))
            onNodeWithText("ADD FROM").assertExists()
            assertFalse(has("Plan the days for me"))
            onNodeWithText("Paste your list, or say what to change").assertExists()

            onAllNodes(hasSetTextAction()).onFirst().performTextInput(CHANGE_MESSAGE)
            onNodeWithContentDescription("Send").performClick()
            waitForText("2 changes ready to check")
            assertTrue(has(RecordedAi.CHANGE_SUMMARY))
            // The model was given the saved plan with its ids.
            val body = net.builtInAsked.single().body!!
            assertTrue(dinner.id in body && idea.id in body)

            // Save (a chat in a saved trip: Save, not Create trip).
            assertFalse(has("Create trip"))
            onNodeWithText("Save").performClick()
            waitUntil(timeoutMillis = 10_000) { book.item(idea.id) == null }
            assertEquals(20 * 60 + 30, book.item(dinner.id)!!.timeMinutes)
            assertEquals(setOf("Ferry from Paros", "Hotel Grotta", "Dinner in Chora"), book.items(trip.id).map { it.title }.toSet())

            // Back on the trip's Plan, the change in it.
            waitUntil(timeoutMillis = 10_000) { !has("Change this trip") && has("Dinner in Chora") }
            assertTrue(has("20:30"))
            assertFalse(has("Portara at sunset"))
        }
    }

    @Test
    fun anEmptyPlanHasChatBesideAddAPlaceOrANote() {
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos", SAMPLE_TODAY.plusDays(20), SAMPLE_TODAY.plusDays(23))
        runDesktopComposeUiTest(width = 440, height = 1600) {
            startApp(book, Place.Trip(trip.id))
            // Android 1.22.0's AddButtons: Chat (the highlighter's fill) and "Add a place or a note", in the list.
            waitForText("Add a place or a note")
            onNodeWithText("Chat").assertExists()
            assertFalse(has("Plan with AI"))
            assertFalse(has("Add"))
            assertTrue(onAllNodesWithContentDescription("Plan with AI").fetchSemanticsNodes().isEmpty())
            onNodeWithText("Chat").performClick()
            waitForText("Change this trip")
            assertTrue(has("NAXOS ·", substring = true))
            // An empty plan: the planner offers to plan the days.
            onNodeWithText("Plan the days for me").assertExists()
        }
    }

    @Test
    fun stopEndsTheRequestAndPutsTheMessageBack() {
        val settings = AppSettings.ready()
        val net = PlannerNet().hold(RecordedAi::isBuiltIn)
        runDesktopComposeUiTest(width = 440, height = 1600) {
            startApp(TripBook(MemoryTripFile()), Place.Planner(null, null), settings = settings, net = net)
            waitForText("Make a new trip")
            onAllNodes(hasSetTextAction()).onFirst().performTextInput(NEW_MESSAGE)
            onNodeWithContentDescription("Send").performClick()

            // Sent, working: the request is out and waits.
            waitUntil(timeoutMillis = 10_000) { has("Stop") && net.builtInAsked.isNotEmpty() }
            // no step line comes before the model's first answer, so the working line is the default.
            onNodeWithText("Reading your message").assertExists()
            assertTrue(has(NEW_MESSAGE))
            assertFalse(has("Make a new trip"))

            onNodeWithText("Stop").performClick()
            waitForText("Planning stopped. Your message is kept.")
            // The message is back in the box, not in the chat; nothing was counted.
            onNode(hasSetTextAction() and hasText(NEW_MESSAGE)).assertExists()
            assertEquals(1, onAllNodesWithText(NEW_MESSAGE).fetchSemanticsNodes().size)
            assertFalse(has("Stop"))
            assertEquals(0, settings.plannerUsage.answersToday())
            assertEquals(1, net.builtInAsked.size)
        }
    }

    @Test
    fun theDailyLimitStopsTheBuiltInPlannerBeforeItIsAsked() {
        val settings = AppSettings.ready()
        repeat(PlannerAllowance.DAILY_ANSWERS) { settings.plannerUsage.record(isPro = true, startsPlan = false) }
        val net = PlannerNet().answer(RecordedAi::isBuiltIn, RecordedAi.newTrip(today().plusDays(30)))
        runDesktopComposeUiTest(width = 440, height = 1600) {
            startApp(TripBook(MemoryTripFile()), Place.Planner(null, null), settings = settings, net = net)
            onNodeWithText("Plan a trip for me").performClick()
            waitForText("Plan a trip")
            onNodeWithText("Plan my trip").performScrollTo().performClick()
            waitForText(PlannerKit.DAILY_LIMIT)
            assertEquals("That's the limit for today. Try again tomorrow.", PlannerKit.DAILY_LIMIT)
            assertTrue(net.builtInAsked.isEmpty())
            assertEquals(PlannerAllowance.DAILY_ANSWERS, settings.plannerUsage.answersToday())
        }
    }

    @Test
    fun withoutFirebaseIdsOrAnOwnKeyThePlannerSaysItIsNotSetUp() {
        val net = PlannerNet()
        runDesktopComposeUiTest(width = 440, height = 1600) {
            startApp(TripBook(MemoryTripFile()), Place.Planner(null, PlannerStart.QUESTIONS), net = net, firebase = FirebaseSetup.Config("", "", ""))
            // Nobody can answer: no plan button; the questions (opened by their start) say why on Plan my trip.
            waitForText("Plan a trip")
            assertFalse(has("Plan a trip for me"))
            onNodeWithText("Plan my trip").performScrollTo().performClick()
            waitForText(PlannerError.NotSetUp.message)
            assertEquals("The AI planner isn't set up in this build. Use your own key in Me › AI.", PlannerError.NotSetUp.message)
            assertTrue(net.builtInAsked.isEmpty())
        }
    }

    @Test
    fun aLinkFromTheClipboardIsAttachedCheckedAndRemoved() {
        val net = PlannerNet()
        val phone = Phone(paste = { Recorded.YASAKA_SHORT })
        runDesktopComposeUiTest(width = 440, height = 1600) {
            startApp(TripBook(MemoryTripFile()), Place.Planner(null, null), net = net, phone = phone)

            // Link in Start from: Add a link with the clipboard's Google Maps link already in.
            onNodeWithText("Link").performClick()
            waitForText("Add a link")
            onNode(hasSetTextAction() and hasText(Recorded.YASAKA_SHORT)).assertExists()
            onNodeWithText("Add").performClick()
            waitForText("maps.app.goo.gl")
            assertEquals(1, count("Remove link"))

            // + › Link again: a typed text that holds no address is refused, a web page is taken.
            onNodeWithContentDescription("Attach pictures or a link").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Link").fetchSemanticsNodes().size >= 2 }
            onAllNodesWithText("Link").onLast().performClick()
            waitForText("Add a link")
            onNode(hasSetTextAction() and hasText(Recorded.YASAKA_SHORT)).performTextReplacement("not a link")
            onNodeWithText("Add").performClick()
            waitForText("That isn't a link. Paste one beginning https://")
            onNode(hasSetTextAction() and hasText("not a link")).performTextReplacement(Recorded.KYOTO_PAGE_URL)
            onNodeWithText("Add").performClick()
            waitForText("kyoto.travel")
            assertFalse(has("Add a link"))
            assertEquals(2, count("Remove link"))

            // ✕ on the first: the Maps link goes, the page stays.
            onAllNodesWithContentDescription("Remove link")[0].performClick()
            waitUntil(timeoutMillis = 5_000) { !has("maps.app.goo.gl") }
            assertTrue(has("kyoto.travel"))
            assertEquals(1, count("Remove link"))
            // Attaching reads nothing: the links are read when the message is sent.
            assertTrue(net.asked.none { it.url == Recorded.YASAKA_SHORT || it.url == Recorded.KYOTO_PAGE_URL })
            assertTrue(net.builtInAsked.isEmpty())
        }
    }

    @Test
    fun picturesFromThePickerAreAttachedUpToTenAndRemoved() {
        val files = MemoryMediaFiles()
        val library = Library(files)
        // Wide enough for ten thumbnails in the strip (a LazyRow composes only what it shows).
        runDesktopComposeUiTest(width = 900, height = 1400) {
            startApp(TripBook(MemoryTripFile()), Place.Planner(null, null), media = MediaKit(files, library))

            // Pictures in Start from: photos only, the video left out.
            library.next = listOf(library.photo("p1"), library.video("v1"), library.photo("p2"))
            onNodeWithText("Pictures").performClick()
            waitUntil(timeoutMillis = 10_000) { count("Remove picture") == 2 }
            assertEquals(PictureReading.MAX_PICTURES, library.max)

            // ✕ on one.
            onAllNodesWithContentDescription("Remove picture")[0].performClick()
            waitUntil(timeoutMillis = 5_000) { count("Remove picture") == 1 }

            // + › Pictures with twelve more: ten in all are kept, and the chat says so.
            library.next = (1..12).map { library.photo("q$it") }
            onNodeWithContentDescription("Attach pictures or a link").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Pictures").fetchSemanticsNodes().size >= 2 }
            onAllNodesWithText("Pictures").onLast().performClick()
            waitForText("Up to ${PictureReading.MAX_PICTURES} pictures in one message.")
            waitUntil(timeoutMillis = 10_000) { count("Remove picture") == PictureReading.MAX_PICTURES }
            assertEquals(2, library.asked)
        }
    }

    @Test
    fun meAiTestsAnOwnKeyShowsItMaskedAndRemovesIt() {
        val keys = MemoryKeyValues()
        val settings = AppSettings.ready()
        val net = PlannerNet()
            .answer(RecordedAi::isGeminiModels, RecordedAi.GEMINI_MODELS)
            .answer(RecordedAi::isGeminiGenerate, RecordedAi.PING)
        val mask = OwnKeyStore.mask(RecordedAi.OWN_KEY)
        runDesktopComposeUiTest(width = 440, height = 1600) {
            startApp(TripBook(MemoryTripFile()), Place.Home(HomeTab.Me), settings = settings, net = net, keys = keys)

            // Me's row, then Me › AI on the built-in planner. Me's row is MeRow("AI", "Built-in planner"), and
            // AiScreen reads OwnAi's state so a change shows at once (the draft takes an OwnAiKeys with a plain `state`).
            onNodeWithText("Built-in planner").assertExists()
            onNodeWithText("AI").performScrollTo().performClick()
            waitForText("PLANNER ANSWERS COME FROM")
            onNodeWithText("Google's Gemini through BoringTravel.").assertExists()

            // Your own key: Gemini, a key pasted, Test.
            onNodeWithText("Your own key").performClick()
            waitForText("The built-in planner answers until a key works.")
            onNodeWithText("Gemini").performClick()
            onNodeWithText("Get a key at Google AI Studio").assertExists()
            onNodeWithText("Add a key to see the models").assertExists()
            // the key's box is the first text field on the screen (the model's box comes after it).
            onAllNodes(hasSetTextAction()).onFirst().performTextInput(RecordedAi.OWN_KEY)
            onNodeWithText("Test").performClick()
            waitForText("Works. ${GeminiPlanner.DEFAULT_MODEL} answered.")

            // The key masked, the model chosen and the models offered as chips; the planner now uses the key.
            assertTrue(has(mask, substring = true))
            assertFalse(has(RecordedAi.OWN_KEY, substring = true))
            onNodeWithText("gemini-pro-latest").assertExists()
            onNodeWithText("gemini-flash-lite-latest").assertExists()
            onNodeWithText("Using your Gemini key · ${GeminiPlanner.DEFAULT_MODEL}").assertExists()

            // The key went to Google's Gemini API only, in its header: the list, then the ping.
            val withKey = net.asked.filter { r -> r.headers.values.any { RecordedAi.OWN_KEY in it } || RecordedAi.OWN_KEY in r.body.orEmpty() }
            assertEquals(2, withKey.size)
            assertTrue(withKey.all { it.url.startsWith(GeminiRest.BASE) })
            // compat's OkHttp Request keeps a header's name as given ("x-goog-api-key").
            assertTrue(withKey.all { it.headers[GeminiRest.KEY_HEADER] == RecordedAi.OWN_KEY })
            assertTrue(RecordedAi.isGeminiModels(withKey[0].url))
            assertEquals("${GeminiRest.BASE}/models/${GeminiPlanner.DEFAULT_MODEL}:generateContent", withKey[1].url)

            // Kept in the phone's key store, never in what a backup carries.
            assertNotNull(keys.get(OwnKeyStore.keyName(OwnAiProvider.Gemini)))
            val prefs = BackupJson.encodeToString(BackupPreferences.serializer(), settings.backupPreferences())
            assertFalse(RecordedAi.OWN_KEY in prefs)
            assertFalse(OwnKeyStore.keyName(OwnAiProvider.Gemini) in prefs)

            // Me's row says which key answers. Me reads the kit's OwnAi state (Android's line).
            onNodeWithContentDescription("Back").performClick()
            waitForText("Your Gemini key · ${GeminiPlanner.DEFAULT_MODEL}")
            onNodeWithText("AI").performScrollTo().performClick()
            waitForText("PLANNER ANSWERS COME FROM")

            // Remove key: gone from the screen and from the key store (the provider's key and model).
            onNodeWithText("Remove key").performScrollTo().performClick()
            waitForText("Key removed.")
            assertFalse(has(mask, substring = true))
            onNodeWithText("The built-in planner answers until a key works.").assertExists()
            assertNull(keys.get(OwnKeyStore.keyName(OwnAiProvider.Gemini)))
            // "holds nothing" = no key and no model; the switch (mode) and the provider shown stay.
            assertTrue(keys.names.none { it.startsWith("key.") || it.startsWith("model.") }, keys.names.toString())
        }
    }

    @Test
    fun anOwnKeyIsKeptOnlyInTheKeyStoreAndNeverInABackup() = runBlocking {
        val keys = MemoryKeyValues()
        val file = MemorySettingsFile()
        val settings = AppSettings(file).apply { onboardingDone = true }
        val book = smallTripBook()
        val net = PlannerNet()
            .answer(RecordedAi::isGeminiModels, RecordedAi.GEMINI_MODELS)
            .answer(RecordedAi::isGeminiGenerate, RecordedAi.PING)
        val kit = PlannerKit(book, settings, Recorded.web(net), MemoryMediaFiles(), PlannerPhone(keys))

        // No Firebase ids and no key: nobody can answer.
        assertFalse(kit.available)
        assertEquals(PlannerKit.Allowed.No(PlannerError.NotSetUp.message), kit.allowed(startsPlan = true))
        assertTrue(kit.testKey(OwnAiProvider.Gemini, "  ").error)

        // A pasted key (spaces and a line break around it) tested and kept; the planner uses it, no allowance then.
        kit.ownAi.setUseOwnKey(true)
        val test = kit.testKey(OwnAiProvider.Gemini, "  ${RecordedAi.OWN_KEY}\n")
        assertFalse(test.error, test.note)
        assertEquals("Works. ${GeminiPlanner.DEFAULT_MODEL} answered.", test.note)
        assertEquals(OwnKeyStore.mask(RecordedAi.OWN_KEY), test.mask)
        assertEquals(listOf(GeminiPlanner.DEFAULT_MODEL, "gemini-pro-latest", "gemini-flash-lite-latest"), test.models)
        assertEquals(OwnAiProvider.Gemini, kit.ownKey)
        assertEquals("Using your Gemini key · ${GeminiPlanner.DEFAULT_MODEL}", kit.ownKeyLine)
        assertTrue(kit.engine() is OwnKeyPlanner)
        assertEquals(PlannerKit.Allowed.Yes, kit.allowed(startsPlan = true))

        // Only in the key store; not in the settings file, the settings a backup carries, or the trips' data.
        assertEquals(RecordedAi.OWN_KEY, kit.ownAi.key(OwnAiProvider.Gemini))
        assertNull(file.read(OwnKeyStore.keyName(OwnAiProvider.Gemini)))
        val prefs = BackupJson.encodeToString(BackupPreferences.serializer(), settings.backupPreferences())
        assertFalse(RecordedAi.OWN_KEY in prefs)
        assertFalse(RecordedAi.OWN_KEY in book.export())

        // Removed: the built-in planner again (which this build can't reach).
        kit.ownAi.removeKey(OwnAiProvider.Gemini)
        assertNull(kit.ownKey)
        assertSame(kit.builtIn, kit.engine())
        assertTrue(keys.names.none { it.startsWith("key.") || it.startsWith("model.") })
        assertFalse(kit.available)
    }

    private companion object {
        const val VERSION = "1.0"
        const val NEW_MESSAGE = "Three days in Lisbon in May, food and old streets"
        const val CHANGE_MESSAGE = "Move dinner to 20:30 and drop the Portara idea"
    }
}
