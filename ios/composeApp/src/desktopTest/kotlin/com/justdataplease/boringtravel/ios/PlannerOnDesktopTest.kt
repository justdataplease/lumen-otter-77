package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.GeminiPlanner
import com.justdataplease.boringtravel.ai.PictureReading
import com.justdataplease.boringtravel.ai.PicturePrep
import com.justdataplease.boringtravel.ai.PlanRequest
import com.justdataplease.boringtravel.ai.PlannerError
import com.justdataplease.boringtravel.ai.PlannerNotSetUpException
import com.justdataplease.boringtravel.ai.PlannerProcess
import com.justdataplease.boringtravel.ai.PlannerPrompt
import com.justdataplease.boringtravel.ai.own.GeminiRest
import com.justdataplease.boringtravel.ai.own.MemoryKeyValues
import com.justdataplease.boringtravel.ai.own.OwnAiProvider
import com.justdataplease.boringtravel.ai.own.OwnKeyPlanner
import com.justdataplease.boringtravel.ai.tools.PendingPlan
import com.justdataplease.boringtravel.ai.tools.PlannerAction
import com.justdataplease.boringtravel.ai.tools.PlannerTools
import com.justdataplease.boringtravel.ai.tools.TravelBrief
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.time.localDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The AI planner on the iPhone's code, whole conversations on a recorded model (Gemini's REST answers through the
 * built-in planner's Firebase AI Logic requests, or a traveller's own key): a trip from the trip questions, from a
 * picture, from a Google Maps link without any model, a saved trip changed through each tool, the review and the save
 * into the trip store (still a backup's data.json), the daily limit, a build without Firebase ids, the fallback model,
 * and an own key kept, used and removed.
 */
class PlannerOnDesktopTest {
    private val today = localDate(2026, 11, 18)
    private val may3 = localDate(2027, 5, 3)

    /** The texts of every part of a request's last content. */
    private fun lastWords(body: JsonObject): String =
        body.getValue("contents").jsonArray.last().jsonObject.getValue("parts").jsonArray.joinToString("\n") { it.toString() }

    private fun restored(file: MemoryTripFile) = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read())).toSnapshot()

    @Test
    fun aTripFromTheTripQuestionsIsProposedReviewedAndSaved() = runBlocking {
        val net = ModelNet()
            .answer(GeminiAnswers.turn(PlannerTools.ASK_TRAVELLER to """{"where":"Lisbon","days":4}"""))
            .answer(
                GeminiAnswers.turn(
                    PlannerTools.SET_TRIP to """{"title":"Lisbon in May","startDate":"2027-05-03","endDate":"2027-05-06","route":"Lisbon → Sintra"}""",
                    PlannerTools.ADD_ITEMS to """{"items":[
                        {"kind":"stay","title":"Stay in Alfama","date":"2027-05-03","endDate":"2027-05-06","placeName":"Alfama, Lisbon, Portugal","lat":38.7118,"lng":-9.1300},
                        {"kind":"place","title":"São Jorge Castle","date":"2027-05-04","time":"09:30","placeName":"Castelo de São Jorge, Lisbon, Portugal","lat":38.7139,"lng":-9.1335},
                        {"kind":"food","title":"Lunch in Alfama","date":"2027-05-04","time":"13:00","note":"Try grilled sardines","placeName":"Alfama, Lisbon, Portugal","lat":38.7118,"lng":-9.1300},
                        {"kind":"transport","title":"Train to Sintra","date":"2027-05-05","time":"09:00","travel":"train","placeName":"Sintra station, Portugal","lat":38.7987,"lng":-9.3864},
                        {"kind":"place","title":"Pena Palace","date":"2027-05-05","time":"10:30","placeName":"Pena Palace, Sintra, Portugal","lat":38.7876,"lng":-9.3906}]}""",
                    PlannerTools.FINISH to """{"summary":"Four easy days in Lisbon with a day in Sintra. I assumed a couple and public transport."}""",
                ),
            )
        val file = MemoryTripFile()
        val book = TripBook(file)
        val kit = aiKit(net, book)
        val steps = mutableListOf<String>()

        // "Plan 4 days in Lisbon in May": the planner asks the trip questions, filled in with what it knows.
        val first = kit.run(kit.engine(), null, emptyList(), PendingPlan(), emptyList(), "Plan 4 days in Lisbon in May", today) { steps += it }
        val ask = assertIs<PlannerAction.Ask>(first.action)
        assertEquals("Lisbon", ask.brief.where)
        assertEquals(4, ask.brief.days)
        assertFalse(first.ready)

        // The answers go as the traveller's next message; the planner makes the trip in one turn.
        val brief = ask.brief.copy(whenText = "3–6 May 2027", who = TravelBrief.Who.Couple, pace = TravelBrief.Pace.Easy)
        val history = listOf(ChatTurn(true, "Plan 4 days in Lisbon in May"), ChatTurn(false, "A few questions first, then I plan the trip."))
        val outcome = kit.run(kit.engine(), null, emptyList(), first.pending, history, brief.message(), today) { steps += it }
        assertTrue(outcome.ready)
        assertEquals("Four easy days in Lisbon with a day in Sintra. I assumed a couple and public transport.", outcome.reply)
        assertEquals("gemini-flash-latest", outcome.model)
        assertTrue("Setting the trip" in steps && "Adding 5 items" in steps)

        // What the model was sent: the planner's own prompt and tools, the answers under the trip as it is now.
        val sent = net.body(1)
        assertEquals("projects/boringtravel-test/models/gemini-flash-latest", sent.getValue("model").jsonPrimitive.content)
        assertEquals(PlannerPrompt.system(today, tools = true), sent.getValue("system_instruction").jsonObject.getValue("parts").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
        val declared = sent.getValue("tools").jsonArray[0].jsonObject.getValue("functionDeclarations").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(PlannerTools.all.map { it.name }, declared)
        assertTrue(TravelBrief.HEADER in lastWords(sent))
        assertEquals("ANY", sent.getValue("tool_config").jsonObject.getValue("function_calling_config").jsonObject.getValue("mode").jsonPrimitive.content)

        // The review: a new trip on its days, the stay over three nights.
        val layout = outcome.layout
        assertEquals("Lisbon in May", layout.title)
        assertEquals(may3, layout.start)
        assertEquals(may3.plusDays(3), layout.end)
        assertEquals("Lisbon → Sintra", layout.route)
        assertEquals(listOf(1, 2, 3), layout.days.map { it.dayNumber })
        assertEquals(5, layout.reviewableCount)

        // Saved: the trip and its plan in the store, a backup's data.json still.
        val saved = kit.save(null, outcome.draft, emptyList(), emptySet())
        assertTrue(saved.result.newTrip)
        assertEquals("Trip created. Added 5 items on 3 days.", saved.summary)
        val trip = assertNotNull(book.trip(saved.result.trip.id))
        assertEquals("Lisbon in May", trip.title)
        assertEquals(listOf("Stay in Alfama", "São Jorge Castle", "Lunch in Alfama", "Train to Sintra", "Pena Palace"), book.items(trip.id).map { it.title })
        assertTrue(book.items(trip.id).all { it.source == "ai" })
        assertEquals(3, book.items(trip.id).first().endDays)
        assertEquals(book.snapshot, restored(file))
    }

    /** A tall screenshot drawn here, as the picker would have put it under the app's files. */
    private fun screenshot(files: MediaFiles, path: String, width: Int, height: Int) {
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.clear(0xFF3366AA.toInt())
        files.write(path, assertNotNull(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)).bytes)
        surface.close()
    }

    @Test
    fun aPictureIsReadIntoTheMessageAndCountedForTheDay() = runBlocking {
        val net = ModelNet().answer(GeminiAnswers.text("""{"found":true,"text":"Alfama, Lisbon\nBelém Tower, Lisbon"}"""))
        val files = MemoryMediaFiles()
        screenshot(files, "picked/shot.png", 1080, 3000)
        val settings = AppSettings.ready()
        val kit = aiKit(net, settings = settings, files = files)

        val message = kit.readPictures(listOf("picked/shot.png"), own = null)
        assertEquals("From a picture:\nAlfama, Lisbon\nBelém Tower, Lisbon", message)
        assertEquals(1, settings.plannerUsage.answersToday())

        // The request: the picture's two pieces as JPEGs first, then the words, an answer in the schema asked for.
        val parts = net.body(0).getValue("contents").jsonArray[0].jsonObject.getValue("parts").jsonArray
        assertEquals(3, parts.size)
        val pieces = parts.take(2).map { java.util.Base64.getDecoder().decode(it.jsonObject.getValue("inlineData").jsonObject.getValue("data").jsonPrimitive.content) }
        assertTrue(pieces.all { it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() })
        assertEquals(listOf(1080 to 1800, 1080 to 1320), pieces.map { Image.makeFromEncoded(it).let { i -> i.width to i.height } })
        assertEquals(PictureReading.prompt(1), parts[2].jsonObject.getValue("text").jsonPrimitive.content)
        val generation = net.body(0).getValue("generation_config").jsonObject
        assertEquals("application/json", generation.getValue("response_mime_type").jsonPrimitive.content)
        assertEquals(setOf("found", "text"), generation.getValue("response_schema").jsonObject.getValue("properties").jsonObject.keys)

        // A picture that can't be opened, and one that is see-through: it goes on white.
        files.write("picked/broken.jpg", byteArrayOf(1, 2, 3))
        val e = assertFailsWith<Exception> { PicturePrep.load(files, listOf("picked/broken.jpg")) }
        assertEquals(PictureReading.CANT_OPEN, kit.pictureError(e, null, 1))
        val clear = Surface.makeRasterN32Premul(20, 20)
        val png = assertNotNull(clear.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)).bytes
        val piece = Image.makeFromEncoded(PicturePrep.pieces(png, 3).single().bytes)
        val pixel = org.jetbrains.skia.Bitmap.makeFromImage(piece).getColor(10, 10)
        assertTrue((pixel and 0xFF) > 240 && ((pixel shr 8) and 0xFF) > 240 && ((pixel shr 16) and 0xFF) > 240)
    }

    @Test
    fun aGoogleMapsLinkAloneIsAddedByTheAppWithoutAModel() = runBlocking {
        val net = ModelNet()
        val kit = aiKit(net)
        val outcome = kit.run(kit.engine(), null, emptyList(), PendingPlan(), emptyList(), Recorded.YASAKA_LONG, today)
        assertTrue(outcome.byApp)
        assertEquals(PlannerProcess.APP_MODEL, outcome.model)
        assertTrue(net.asked.isEmpty())
        val idea = outcome.layout.ideas.single()
        assertEquals("Yasaka Shrine", idea.item.title)
        assertEquals(Recorded.YASAKA.lat, idea.item.lat)
        assertEquals("Added Yasaka Shrine to your ideas, with its pin. Tell me a day and time to plan it.", outcome.reply)

        // A shared Google Maps list alone: all its places, and a new trip takes its name.
        val list = kit.run(kit.engine(), null, emptyList(), PendingPlan(), emptyList(), Recorded.LIST_SHORT, today)
        assertTrue(list.byApp)
        assertEquals("Seville", list.layout.title)
        assertEquals(listOf("Casa de Comidas La Posada", "The White House", "Plaza de España"), list.layout.ideas.map { it.item.title })
        assertTrue(net.asked.isEmpty())
    }

    /** A saved trip of four days with three items, as the Plan shows it. */
    private fun lisbon(book: TripBook): Pair<String, List<PlanItem>> {
        val trip = book.newTrip("Lisbon", may3, may3.plusDays(3), "Lisbon → Sintra")
        val rows = listOf(
            PlanItem("tram", trip.id, may3, 1.0, 600, "activity", "Tram 28", placeName = "Martim Moniz, Lisbon", lat = 38.7163, lng = -9.1359, createdAt = 1, updatedAt = 1),
            PlanItem("fado", trip.id, may3.plusDays(1), 1.0, 1260, "activity", "Fado night", placeName = "Alfama, Lisbon", lat = 38.7118, lng = -9.13, createdAt = 1, updatedAt = 1),
            PlanItem("pena", trip.id, may3.plusDays(2), 1.0, 630, "place", "Pena Palace", placeName = "Sintra", lat = 38.7876, lng = -9.3906, createdAt = 1, updatedAt = 1),
        )
        book.saveItems(rows)
        return trip.id to book.items(trip.id)
    }

    @Test
    fun aSavedTripChangesThroughSetTripAddUpdateAndRemove() = runBlocking {
        val net = ModelNet().answer(
            GeminiAnswers.turn(
                PlannerTools.SET_TRIP to """{"startDate":"2027-05-04","endDate":"2027-05-07","moveItemsWithDates":true,"dayNames":[{"date":"2027-05-04","name":"Old town"}]}""",
                PlannerTools.UPDATE_ITEMS to """{"updates":[{"itemId":"tram","time":"11:00"}]}""",
                PlannerTools.REMOVE_ITEMS to """{"itemIds":["fado"]}""",
                PlannerTools.ADD_ITEMS to """{"items":[{"kind":"food","title":"Time Out Market","date":"2027-05-05","time":"13:00","placeName":"Time Out Market, Lisbon, Portugal","lat":38.7069,"lng":-9.1459}]}""",
                PlannerTools.FINISH to """{"summary":"Moved the trip a day later and changed the first two days."}""",
            ),
        )
        val file = MemoryTripFile()
        val book = TripBook(file)
        val (tripId, items) = lisbon(book)
        val kit = aiKit(net, book)
        val message = "We now go a day later. Call the first day Old town, the tram at 11:00, no fado, and lunch at Time Out Market on the 5th."
        val outcome = kit.run(kit.engine(), book.trip(tripId), items, PendingPlan(), emptyList(), message, today)
        assertTrue(outcome.ready)
        // The saved plan with its ids went to the model.
        assertTrue("tram · 2027-05-03 10:00 · activity · Tram 28" in lastWords(net.body(0)))

        // The review: the dates move the plan, a day named, one change, one removal, one new item.
        val layout = outcome.layout
        val change = assertNotNull(layout.tripChange)
        assertTrue(change.datesChanged)
        assertEquals(1L, change.shiftDays)
        assertEquals(listOf("Old town"), layout.dayNames.map { it.name })
        assertEquals(listOf("tram"), layout.updates.map { it.before.id })
        assertEquals(listOf("fado"), layout.removals.map { it.item.id })
        assertEquals(listOf("Time Out Market"), layout.addable.map { it.item.title })

        val saved = kit.save(tripId, outcome.draft, items, emptySet())
        assertEquals("Dates changed. Day renamed. Added 1 item on 1 day. Changed 1 item. Removed 1.", saved.summary)
        val trip = assertNotNull(book.trip(tripId))
        assertEquals(may3.plusDays(1), trip.startDate)
        assertEquals("Old town", DayNames.all(trip.travelModes)[may3.plusDays(1)])
        val tram = assertNotNull(book.item("tram"))
        assertEquals(may3.plusDays(1), tram.day)
        assertEquals(660, tram.timeMinutes)
        assertEquals(may3.plusDays(3), book.item("pena")?.day)
        assertNull(book.item("fado"))
        val market = book.items(tripId).single { it.title == "Time Out Market" }
        assertEquals(may3.plusDays(2), market.day)
        assertEquals("food", market.kind)
        assertEquals(book.snapshot, restored(file))
    }

    @Test
    fun aGoogleMapsListIsAddedInOneCall() = runBlocking {
        val net = ModelNet()
            .answer(GeminiAnswers.turn(PlannerTools.ADD_MAPS_LIST to """{"url":"${Recorded.LIST_SHORT}"}""", PlannerTools.FINISH to """{"summary":"Added the list's 3 places as ideas."}"""))
        val book = TripBook(MemoryTripFile())
        val (tripId, items) = lisbon(book)
        val kit = aiKit(net, book)
        val outcome = kit.run(kit.engine(), book.trip(tripId), items, PendingPlan(), emptyList(), "Add these places ${Recorded.LIST_SHORT}", today)
        assertTrue(outcome.ready)
        assertEquals(3, outcome.layout.ideas.size)
        // The list read by the app went under the message.
        assertTrue("a Google Maps list \\\"Seville\\\" with 3 places" in lastWords(net.body(0)))
    }

    @Test
    fun findPlacesAnswersFromTheMapSearchAndTheNextTurnAdds() = runBlocking {
        val net = ModelNet()
            .answer(GeminiAnswers.turn(PlannerTools.FIND_PLACES to """{"queries":[{"query":"Portara","near":"Naxos"}]}"""))
            .answer(
                GeminiAnswers.turn(
                    PlannerTools.ADD_ITEMS to """{"items":[{"kind":"place","title":"Portara","placeName":"Portara, Naxos, Greece","lat":37.1074593,"lng":25.3745944}]}""",
                    PlannerTools.FINISH to """{"summary":"Added the Portara as an idea."}""",
                ),
            )
        val book = TripBook(MemoryTripFile())
        val (tripId, items) = lisbon(book)
        val kit = aiKit(net, book)
        val steps = mutableListOf<String>()
        val outcome = kit.run(kit.engine(), book.trip(tripId), items, PendingPlan(), emptyList(), "Add the Portara on Naxos as an idea", today) { steps += it }
        assertTrue("Finding a place" in steps)
        assertEquals(listOf("Portara"), outcome.layout.ideas.map { it.item.title })
        // The second turn carried the map search's answer back, after the model's own content (its signature kept).
        val contents = net.body(1).getValue("contents").jsonArray
        assertTrue("sig-find_places" in contents[contents.size - 2].toString())
        val response = contents.last().jsonObject.getValue("parts").jsonArray[0].jsonObject.getValue("functionResponse").jsonObject
        assertEquals(PlannerTools.FIND_PLACES, response.getValue("name").jsonPrimitive.content)
        assertTrue("Portara Coffee & Bar" in response.toString())
        assertTrue(net.web.any { it.url.startsWith("https://photon.komoot.io/api/") })
    }

    @Test
    fun questionsAskTravellerAndCopyNotesEndTheTurn() = runBlocking {
        val net = ModelNet()
            .answer(GeminiAnswers.turn(PlannerTools.FINISH to """{"summary":"","questions":["Which Belém: the tower or the monastery?"]}"""))
            .answer(GeminiAnswers.turn(PlannerTools.ASK_TRAVELLER to """{"where":"Porto","suggestWhere":false}"""))
            .answer(GeminiAnswers.turn(PlannerTools.COPY_NOTES to """{"url":"https://myblog.tumblr.com"}"""))
        val book = TripBook(MemoryTripFile())
        val (tripId, items) = lisbon(book)
        val kit = aiKit(net, book)
        val trip = book.trip(tripId)

        val asked = kit.run(kit.engine(), trip, items, PendingPlan(), emptyList(), "Add Belém", today)
        assertFalse(asked.ready)
        assertEquals(listOf("Which Belém: the tower or the monastery?"), asked.layout.questions)
        assertEquals("1. Which Belém: the tower or the monastery?", asked.replyWithQuestions)

        val questions = kit.run(kit.engine(), trip, items, PendingPlan(), emptyList(), "Plan a few days in Porto for us", today)
        assertEquals("Porto", assertIs<PlannerAction.Ask>(questions.action).brief.where)

        val copy = kit.run(kit.engine(), trip, items, PendingPlan(), emptyList(), "Copy my blog myblog.tumblr.com into my journal", today)
        assertTrue(assertIs<PlannerAction.CopyNotes>(copy.action).address.startsWith("https://myblog.tumblr.com"))
    }

    @Test
    fun readLinkReadsASharedGoogleDocABlogPostAndSaysAnEmptyPageHasNoText() = runBlocking {
        val doc = "https://docs.google.com/document/d/1AbCdEfGhIjKlMnOpQrStUvWxYz0123/edit"
        fun html(body: String) = { r: NetRequest -> NetAnswer(200, r.url, mapOf("Content-Type" to "text/html; charset=utf-8"), body.encodeToByteArray()) }
        val web = RecordedNet(
            listOf(
                { u: String -> u.startsWith("https://docs.google.com/document/d/1AbCdEfGhIjKlMnOpQrStUvWxYz0123/export") } to { r: NetRequest ->
                    NetAnswer(
                        200, r.url, mapOf("Content-Type" to "text/plain; charset=utf-8", "Content-Disposition" to "attachment; filename=\"Lisbon notes.txt\""),
                        "﻿Day 1\nPastéis de Belém, Lisbon\n\n\n\nDay 2\nSintra".encodeToByteArray(),
                    )
                },
                // A blog post: its article's words as text (since round 17 the site reader is on iPhone too).
                { u: String -> u == "https://example.com/blog/lisbon" } to html(
                    "<html><head><title>Two days in Lisbon</title><meta property=\"og:title\" content=\"Two days in Lisbon\"></head><body>" +
                        "<nav>Home · About</nav><article><h1>Two days in Lisbon</h1><p>Tram 28 early, then the <b>Alfama</b>.</p>" +
                        "<h2>Belém</h2><p>Pastéis de Belém before noon.</p></article><footer>© Made-up blog</footer></body></html>",
                ),
                // A page its script builds: nothing to read without a browser.
                { u: String -> u == "https://example.com/app" } to html("<html><head><title>App</title></head><body><div id=\"root\"></div><script>start()</script></body></html>"),
            ),
        )
        val net = ModelNet(web)
            .answer(GeminiAnswers.turn(PlannerTools.READ_LINK to """{"url":"$doc"}"""))
            .answer(GeminiAnswers.turn(PlannerTools.READ_LINK to """{"url":"https://example.com/blog/lisbon"}"""))
            .answer(GeminiAnswers.turn(PlannerTools.READ_LINK to """{"url":"https://example.com/app"}"""))
            .answer(
                GeminiAnswers.turn(
                    PlannerTools.ADD_ITEMS to """{"items":[{"kind":"food","title":"Pastéis de Belém","placeName":"Pastéis de Belém, Lisbon, Portugal","lat":38.6975,"lng":-9.2032}]}""",
                    PlannerTools.FINISH to """{"summary":"Added the places from your notes as ideas."}""",
                ),
            )
        val book = TripBook(MemoryTripFile())
        val (tripId, items) = lisbon(book)
        val kit = aiKit(net, book)
        val outcome = kit.run(kit.engine(), book.trip(tripId), items, PendingPlan(), emptyList(), "Add the places from my notes $doc", today)
        assertEquals(listOf("Pastéis de Belém"), outcome.layout.ideas.map { it.item.title })
        val docAnswer = net.body(1).getValue("contents").jsonArray.last().toString()
        assertTrue("Day 1\\nPastéis de Belém, Lisbon\\n\\nDay 2\\nSintra" in docAnswer)
        assertTrue("Lisbon notes" in docAnswer)
        val pageAnswer = net.body(2).getValue("contents").jsonArray.last().toString()
        assertTrue("Tram 28 early, then the Alfama.\\n\\n## Belém\\n\\nPastéis de Belém before noon." in pageAnswer, pageAnswer)
        assertTrue("Two days in Lisbon" in pageAnswer)
        assertFalse("Home · About" in pageAnswer)
        val emptyAnswer = net.body(3).getValue("contents").jsonArray.last().toString()
        assertTrue("This page has no text to read (it may need a browser to show it)." in emptyAnswer)
    }

    @Test
    fun theDailyLimitAndABuildWithoutFirebaseIds() = runBlocking {
        val settings = AppSettings.ready()
        val kit = aiKit(ModelNet(), settings = settings)
        repeat(39) { kit.recordAnswer(startsPlan = false) }
        assertEquals(PlannerKit.Allowed.Yes, kit.allowed(startsPlan = true))
        kit.recordAnswer(startsPlan = false)
        assertEquals(PlannerKit.Allowed.No("That's the limit for today. Try again tomorrow."), kit.allowed(startsPlan = false))

        // No Firebase ids in this build (the default for previews and tests): the planner says so and asks nobody.
        val net = ModelNet()
        val bare = aiKit(net, firebase = PlannerPhone().firebase)
        assertFalse(bare.available)
        assertEquals(PlannerKit.Allowed.No(PlannerError.NotSetUp.message), bare.allowed(startsPlan = true))
        val e = assertFailsWith<PlannerNotSetUpException> {
            bare.run(bare.engine(), null, emptyList(), PendingPlan(), emptyList(), "Rome in May", today)
        }
        assertEquals("The AI planner isn't set up in this build. Use your own key in Me › AI.", bare.planError(e, null))
        assertTrue(net.asked.isEmpty())
    }

    private fun request(message: String) = PlanRequest(null, emptyList(), PendingPlan(), emptyList(), message, today)

    @Test
    fun aBusyModelIsAskedAgainThenTheLiteOneAndOutOfQuotaGoesThereAtOnce() = runBlocking {
        val finish = GeminiAnswers.turn(PlannerTools.FINISH to """{"summary":"Nothing to change."}""")
        val busy = ModelNet().answer(GeminiAnswers.BUSY, 503).answer(GeminiAnswers.BUSY, 503).answer(finish).answer(finish)
        val waits = mutableListOf<Long>()
        val planner = GeminiPlanner(busy, TEST_FIREBASE, appCheck = { "app-check-token" }, appVersion = "1.0", bundleId = "com.justdataplease.boringtravel", wait = { waits += it })
        planner.plan(request("Rome in May"))
        val model = { n: Int -> busy.asked[n].url.substringAfter("/models/").substringBefore(":") }
        assertEquals(listOf("gemini-flash-latest", "gemini-flash-latest", "gemini-flash-lite-latest", "gemini-flash-lite-latest"), busy.asked.indices.map(model))
        assertEquals(listOf(2_000L), waits)
        // Firebase AI Logic's headers: the API key, the app, its App Check token, the bundle the key may be kept to.
        val headers = busy.asked[0].headers
        assertEquals(TEST_FIREBASE.apiKey, headers["x-goog-api-key"])
        assertEquals(TEST_FIREBASE.appId, headers["X-Firebase-AppId"])
        assertEquals("app-check-token", headers["X-Firebase-AppCheck"])
        assertEquals("com.justdataplease.boringtravel", headers["X-Ios-Bundle-Identifier"])
        assertEquals("https://firebasevertexai.googleapis.com/v1beta/projects/boringtravel-test/models/gemini-flash-latest:generateContent", busy.asked[0].url)

        val quota = ModelNet().answer(GeminiAnswers.QUOTA, 429).answer(finish).answer(finish)
        val waited = mutableListOf<Long>()
        GeminiPlanner(quota, TEST_FIREBASE, wait = { waited += it }).plan(request("Rome in May"))
        assertEquals(listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-flash-lite-latest"), quota.asked.map { it.url.substringAfter("/models/").substringBefore(":") })
        assertTrue(waited.isEmpty())
        assertNull(quota.asked[0].headers["X-Firebase-AppCheck"])

        // App Check refused (no token on this phone yet): the planner's own words for it.
        val refused = ModelNet().answer(GeminiAnswers.error(401, "Firebase App Check token is invalid.", "UNAUTHENTICATED"), 401)
        val e = assertFailsWith<Exception> { GeminiPlanner(refused, TEST_FIREBASE, wait = {}).plan(request("Rome in May")) }
        assertEquals(PlannerError.AppCheck, PlannerError.of(e, false))
    }

    @Test
    fun anOwnKeyIsTestedKeptUsedAndRemoved() = runBlocking {
        val net = ModelNet(isModel = { it.startsWith(GeminiRest.BASE) || it.startsWith(GeminiPlanner.BASE) })
            .answer("""{"models":[{"name":"models/gemini-flash-latest","supportedGenerationMethods":["generateContent"]},{"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}]}""")
            .answer(GeminiAnswers.text("""{"reply":"OK"}"""))
            .answer(
                GeminiAnswers.turn(
                    PlannerTools.ADD_ITEMS to """{"items":[{"kind":"food","title":"Pizza at Da Michele","placeName":"L'Antica Pizzeria da Michele, Naples, Italy","lat":40.8497,"lng":14.2633}]}""",
                    PlannerTools.FINISH to """{"summary":"Added pizza as an idea."}""",
                ),
            )
        val keys = MemoryKeyValues()
        val settings = AppSettings.ready()
        val kit = aiKit(net, settings = settings, keys = keys)
        repeat(40) { kit.recordAnswer(startsPlan = false) }

        // Test: the pasted key is cleaned, listed, asked; only then kept and the planner switched.
        val test = kit.testKey(OwnAiProvider.Gemini, " \"AIzaSy-own-test-key-0001\"\n")
        assertFalse(test.error)
        assertEquals("Works. gemini-flash-latest answered.", test.note)
        assertEquals("AIz…0001", test.mask)
        assertEquals(listOf("gemini-flash-latest", "gemini-pro-latest", "gemini-flash-lite-latest"), test.models)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", net.asked[0].url)
        assertEquals("AIzaSy-own-test-key-0001", net.asked[0].headers["x-goog-api-key"])
        assertTrue(keys.get("key.gemini")!!.startsWith("v1:"))
        assertFalse("AIzaSy-own-test-key-0001" in settings.backupPreferences().toString())

        // On: the own key answers, with no daily limit (the traveller's own bill).
        kit.ownAi.setUseOwnKey(true)
        assertEquals(OwnAiProvider.Gemini, kit.ownKey)
        assertEquals("Using your Gemini key · gemini-flash-latest", kit.ownKeyLine)
        assertEquals(PlannerKit.Allowed.Yes, kit.allowed(startsPlan = true))
        val engine = assertIs<OwnKeyPlanner>(kit.engine())
        assertFalse("AIzaSy" in engine.toString())
        val outcome = kit.run(engine, null, emptyList(), PendingPlan(), emptyList(), "Pizza in Naples", today)
        assertEquals("gemini-flash-latest (own Gemini key)", outcome.model)
        assertEquals(listOf("Pizza at Da Michele"), outcome.layout.ideas.map { it.item.title })
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent", net.asked[2].url)

        // Removed: the key and its model go; the built-in planner answers again, with its daily limit.
        kit.ownAi.removeKey(OwnAiProvider.Gemini)
        assertNull(keys.get("key.gemini"))
        assertNull(kit.ownKey)
        assertTrue(kit.engine() === kit.builtIn)
        assertEquals(PlannerKit.Allowed.No(PlannerKit.DAILY_LIMIT), kit.allowed(startsPlan = false))
        // A wrong key changes nothing.
        val wrong = ModelNet(isModel = { it.startsWith(GeminiRest.BASE) })
            .answer("""{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""", 400)
        val other = aiKit(wrong, keys = MemoryKeyValues())
        val failed = other.testKey(OwnAiProvider.Gemini, "AIzaSy-wrong")
        assertTrue(failed.error)
        assertEquals("Wrong key. Copy it again from Google AI Studio.", failed.note)
        assertTrue(other.ownAi.state.value.withKey.isEmpty())
    }

    @Test
    fun errorsAreSaidInTheAndroidAppsWords() = runBlocking {
        val kit = aiKit(ModelNet())
        val slow = assertNotNull(runCatching { kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.delay(1_000) } }.exceptionOrNull())
        assertEquals(PlannerKit.TOO_LONG, kit.planError(slow, null))
        assertEquals(PictureReading.TOO_SLOW, kit.pictureError(slow, null, 2))
        assertEquals("No connection. Check the internet and try again.", kit.planError(NetException("No connection"), null))
        assertEquals("That took too long. Try again.", kit.planError(NetException("slow", NetFailure.Timeout), null))
        assertEquals("No internet. Check the connection and try again.", com.justdataplease.boringtravel.ai.own.OwnKeyError.ofNetwork(NetException("No connection")).message(OwnAiProvider.Claude))
        assertEquals(com.justdataplease.boringtravel.ai.own.OwnKeyError.Insecure, com.justdataplease.boringtravel.ai.own.OwnKeyError.ofNetwork(NetException("cert", NetFailure.Insecure)))
    }
}
