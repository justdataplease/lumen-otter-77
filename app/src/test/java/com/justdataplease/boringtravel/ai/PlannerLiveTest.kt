package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.ai.own.GeminiKeyApi
import com.justdataplease.boringtravel.ai.own.OpenRouterKeyApi
import com.justdataplease.boringtravel.ai.own.OwnKeyPlanner
import com.justdataplease.boringtravel.ai.tools.PendingPlan
import com.justdataplease.boringtravel.ai.tools.PlaceLookup
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.places.PhotonParser
import com.justdataplease.boringtravel.ui.trip.travel
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The planner end to end against real models, run by hand only: `BT_LIVE_AI=1` with `GEMINI_API_KEY` and/or
 * `OPENROUTER_API_KEY` (the owner's keys live in android-boringtravel/.env, never in git). Each engine plans the owner's real
 * Madrid trip from the pasted itinerary, then the follow-ups a traveller sends: the trip moves two days, a corrected
 * list, a new hotel, a removal, a dinner; and a new trip from one line. The app's own code does everything after the
 * model: the tool runner, the map search (Photon), the review layout and the save. Prints what happened; fails on
 * the checks.
 */
class PlannerLiveTest {
    private val today = LocalDate.of(2026, 11, 11)
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
    private val places = PlaceLookup { query, near ->
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("limit", "8")
            .apply { if (near != null) { addQueryParameter("lat", near.lat.toString()); addQueryParameter("lon", near.lng.toString()) } }.build()
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", "BoringTravel-tests").build()).execute().use { it.body?.string().orEmpty() }
        PhotonParser.parse(body)
    }

    @Test fun gemini() = runBlocking {
        assumeTrue(System.getenv("BT_LIVE_AI") == "1")
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        assumeTrue(key.isNotBlank())
        run("gemini-flash-latest", OwnKeyPlanner(GeminiKeyApi(http), key, "gemini-flash-latest"))
    }

    @Test fun deepseek() = runBlocking {
        assumeTrue(System.getenv("BT_LIVE_AI") == "1")
        val key = System.getenv("OPENROUTER_API_KEY").orEmpty()
        assumeTrue(key.isNotBlank())
        run("deepseek-flash-latest", OwnKeyPlanner(OpenRouterKeyApi(http), key, "~deepseek/deepseek-flash-latest"))
    }

    /**
     * A day's own name (owner, 16 Nov 2026: "how can the planner rename a day … not the plan items but the day"): the
     * real model is asked to rename a day, to name one and un-name another, and then to rename an item. The day's name
     * must be set through set_trip and no item touched; the item's rename must leave the days' names alone.
     */
    @Test fun renamesADay() = runBlocking {
        assumeTrue(System.getenv("BT_LIVE_AI") == "1")
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        assumeTrue(key.isNotBlank())
        val engine = OwnKeyPlanner(GeminiKeyApi(http), key, "gemini-flash-latest")
        val start = d(20)
        val phone = Phone().apply {
            trip = Trip("kyoto", "Kyoto", subtitle = "Kyoto → Nara", startDate = start, endDate = start.plusDays(2), createdAt = 1, updatedAt = 1)
            listOf(
                PlanItem("a", "kyoto", day = start, position = 1.0, kind = "transport", title = "Train to Kyoto", placeName = "Kyoto Station, Kyoto, Japan", lat = 34.9858, lng = 135.7588, createdAt = 1, updatedAt = 1),
                PlanItem("b", "kyoto", day = start.plusDays(1), position = 1.0, kind = "place", title = "Kinkaku-ji", placeName = "Kinkaku-ji, Kyoto, Japan", lat = 35.0394, lng = 135.7292, createdAt = 1, updatedAt = 1),
                PlanItem("c", "kyoto", day = start.plusDays(1), position = 2.0, timeMinutes = 15 * 60, kind = "activity", title = "Tea ceremony", placeName = "Gion, Kyoto, Japan", lat = 35.0037, lng = 135.7751, createdAt = 1, updatedAt = 1),
                PlanItem("d", "kyoto", day = start.plusDays(2), position = 1.0, kind = "place", title = "Nara Park", placeName = "Nara Park, Nara, Japan", lat = 34.685, lng = 135.843, createdAt = 1, updatedAt = 1),
            ).forEach { items[it.id] = it }
        }
        val plan = phone.live
        fun names() = com.justdataplease.boringtravel.model.DayNames.all(phone.trip?.travelModes)

        println("\n[1] Rename day 2")
        val (one, _) = ask(engine, phone, "Rename day 2 to Temples and tea")
        check("day 2 is named", names() == mapOf(start.plusDays(1) to "Temples and tea"), names().toString())
        check("no item was touched", phone.live == plan && one.addable.isEmpty() && one.updates.isEmpty() && one.removals.isEmpty())

        println("\n[2] Name the first day, un-name the second")
        ask(engine, phone, "Call the first day Arrival, and take the name off day 2 again.")
        check("day 1 is named and day 2 isn't", names() == mapOf(start to "Arrival"), names().toString())
        check("still no item touched", phone.live == plan)

        println("\n[3] An item's name is not a day's")
        ask(engine, phone, "Rename Kinkaku-ji to Golden Pavilion")
        check("the item is renamed", phone.items.getValue("b").title.contains("Golden", ignoreCase = true), phone.items.getValue("b").title)
        check("the days' names stay", names() == mapOf(start to "Arrival"), names().toString())
        assertTrue("failed: $failures", failures.isEmpty())
    }

    /**
     * A shared Google Maps list (owner, 17 Nov 2026: "import a trip from a Google Maps user list … now that we can rename
     * days, move etc"): the owner's real "Seville" list (or `BT_LIVE_LIST`) read from Google, made into days by the real
     * model, a day renamed, the trip moved; and the same list added to a saved trip in one call.
     */
    @Test fun aMapsListBecomesDays() = runBlocking {
        assumeTrue(System.getenv("BT_LIVE_AI") == "1")
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        assumeTrue(key.isNotBlank())
        val engine = OwnKeyPlanner(GeminiKeyApi(http), key, "gemini-flash-latest")
        val link = System.getenv("BT_LIVE_LIST")?.takeIf { it.isNotBlank() } ?: "https://maps.app.goo.gl/4XbhDugkXP74C4Vu7?g_st=ac"
        val fetcher = com.justdataplease.boringtravel.links.LinkFetcher(http, com.justdataplease.boringtravel.links.LinkFetcher.userAgent("live-test"))
        val started = System.nanoTime()
        val list = fetcher.mapsList(link)!!
        println("[0] the list: \"${list.name}\", ${list.places.size} of ${list.total} places, read in ${"%.1f".format((System.nanoTime() - started) / 1e9)} s")
        list.places.take(5).forEach { println("   ${it.name} · ${it.address} · ${it.lat},${it.lng} · ${it.mapsUrl}") }
        check("the list has places, each with a pin", list.places.isNotEmpty() && list.places.all { it.lat != 0.0 })
        val lists = com.justdataplease.boringtravel.ai.tools.MapsListLookup { url -> if (url.contains("goo.gl") || url.contains("google.")) list else null }
        val names = list.places.map { it.name }.distinct()

        println("\n[1] A new trip of three days from the list")
        val phone = Phone()
        ask(engine, phone, "Make a 3-day trip from 4 May 2027 with the places in this list: $link", lists)
        print(phone)
        val start = LocalDate.of(2027, 5, 4)
        check("the trip runs 4-6 May 2027", phone.trip?.startDate == start && phone.trip?.endDate == start.plusDays(2), "${phone.trip?.startDate}..${phone.trip?.endDate}")
        val inPlan = phone.live.map { it.title }
        check("every place of the list is in the plan", names.all { n -> inPlan.any { it.equals(n, ignoreCase = true) } }, (names - inPlan.toSet()).toString())
        check("most are on days", phone.live.count { it.day != null } * 2 >= phone.live.size, "${phone.live.count { it.day != null }} of ${phone.live.size}")
        check("pins and Maps links from the list", phone.live.filter { it.title in names }.all { it.lat != null && it.mapsUrl?.contains("cid=") == true })
        // The list's places in Córdoba (north of 37.7°) are a day trip: on one day, not spread over three.
        val cordoba = phone.live.filter { (it.lat ?: 0.0) > 37.7 && it.day != null }.mapNotNull { it.day }.toSet()
        check("Córdoba's places share one day", cordoba.size <= 1, cordoba.toString())

        println("\n[2] Rename a day")
        val day = cordoba.firstOrNull() ?: start.plusDays(1)
        val number = (day.toEpochDay() - start.toEpochDay() + 1).toInt()
        ask(engine, phone, "Rename day $number to Córdoba day trip")
        check("the day is named", com.justdataplease.boringtravel.model.DayNames.of(phone.trip?.travelModes, day) == "Córdoba day trip", com.justdataplease.boringtravel.model.DayNames.all(phone.trip?.travelModes).toString())

        println("\n[3] Move the trip")
        val before = phone.live.associate { it.id to it.day }
        ask(engine, phone, "We now go two days later, same plan")
        check("the trip moved two days", phone.trip?.startDate == start.plusDays(2), phone.trip?.startDate.toString())
        check("the items moved with it", phone.live.all { i -> before[i.id]?.let { i.day == it.plusDays(2) } ?: true })
        check("the day's name moved too", com.justdataplease.boringtravel.model.DayNames.of(phone.trip?.travelModes, day.plusDays(2)) == "Córdoba day trip")

        println("\n[4] The list added to a saved trip, in one call")
        val saved = Phone().apply { trip = Trip("andalusia", "Andalusia", startDate = start, endDate = start.plusDays(4), createdAt = 1, updatedAt = 1) }
        val (layout, _) = ask(engine, saved, "add these places $link", lists)
        print(saved)
        check("every place added as an idea", names.all { n -> saved.live.any { it.title.equals(n, ignoreCase = true) && it.day == null } }, "${layout.addable.size} added")
        assertTrue("failed: $failures", failures.isEmpty())
    }

    /**
     * Trips and places from pictures (owner, 17 Nov 2026: "from image to trip or add places, use the LLM for OCR … AI
     * will read all of them and combine text"): two drawn screenshots read by the real model in one request
     * ([readLisbonPictures]); the text goes straight to the planner as a new trip.
     */
    @Test fun picturesBecomeATrip() = runBlocking {
        val engine = pictureEngine()
        val text = readLisbonPictures(engine)

        println("\n[1] Straight to the planner: a new trip")
        val phone = Phone()
        ask(engine, phone, PictureReading.message(text, 2))
        print(phone)
        val start = LocalDate.of(2027, 6, 12)
        check("the trip runs 12-14 June 2027", phone.trip?.startDate == start && phone.trip?.endDate == start.plusDays(2), "${phone.trip?.startDate}..${phone.trip?.endDate}")
        check("Belém Tower on day 1, pinned in Belém", near(phone.find(start, "Bel"), 38.6916, -9.2160, km = 4.0))
        check("Pena Palace on day 3, pinned in Sintra", near(phone.find(start.plusDays(2), "Pena"), 38.7876, -9.3906, km = 4.0))
        check("most places pinned", phone.live.count { it.lat != null } * 4 >= phone.live.size * 3, "${phone.live.count { it.lat != null }} of ${phone.live.size}")
        assertTrue("failed: $failures", failures.isEmpty())
    }

    /**
     * A trip's ⋮ › Add from a picture: the read text alone goes to a saved trip on other dates. Its places are added;
     * the trip keeps its name and dates (seen on the emulator on 17 Nov 2026: the first prompt renamed the trip after
     * the post and moved it to the post's dates).
     */
    @Test fun picturesAddPlacesToASavedTrip() = runBlocking {
        val engine = pictureEngine()
        val text = readLisbonPictures(engine)
        val start = LocalDate.of(2027, 9, 3)
        val saved = Phone().apply { trip = Trip("pt", "Portugal with Mum", startDate = start, endDate = start.plusDays(6), createdAt = 1, updatedAt = 1) }
        val (layout, _) = ask(engine, saved, PictureReading.message(text, 2))
        print(saved)
        check("the places were added", listOf("Bel", "Pena", "Alfama").all { w -> saved.live.any { it.title.contains(w, ignoreCase = true) } }, saved.live.joinToString { it.title })
        check("the trip kept its name", saved.trip?.title == "Portugal with Mum", saved.trip?.title.orEmpty())
        check("the trip kept its dates", saved.trip?.startDate == start && saved.trip?.endDate == start.plusDays(6), "${saved.trip?.startDate}..${saved.trip?.endDate}")
        check("no trip change offered", layout.tripChange?.let { it.title == null && !it.datesChanged } ?: true, layout.tripChange.toString())
        assertTrue("failed: $failures", failures.isEmpty())
    }

    private fun pictureEngine(): OwnKeyPlanner {
        assumeTrue(System.getenv("BT_LIVE_AI") == "1")
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        assumeTrue(key.isNotBlank())
        return OwnKeyPlanner(GeminiKeyApi(http), key, "gemini-flash-latest")
    }

    /**
     * Two drawn screenshots of a Lisbon post (likes, a username and a hashtag around the places), cut as the app cuts
     * them and read by the real model in one request (owner: "AI will read all of them and combine text").
     */
    private suspend fun readLisbonPictures(engine: OwnKeyPlanner): String {
        val first = screenshot(
            "@wanderlust.ana", "Lisbon in 3 days", "12 - 14 June 2027", "",
            "Day 1", "Belem Tower", "Jeronimos Monastery - go early", "Pasteis de Belem for custard tarts", "",
            "Day 2", "Alfama walk", "Sao Jorge Castle", "Miradouro da Senhora do Monte at sunset", "",
            "2,345 likes   #lisbon #travel",
        )
        val second = screenshot(
            "@wanderlust.ana", "Day 3 - Sintra", "Train from Rossio station 9:10", "Pena Palace", "Quinta da Regaleira",
            "Back for dinner at Time Out Market", "", "Reply   Share   Save",
        )
        val pictures = listOf(first, second).flatMap { cut(it, PictureReading.piecesEach(2)) }
        println("[0] ${pictures.size} pieces, ${pictures.sumOf { it.bytes.size } / 1024} KB")
        val started = System.nanoTime()
        val answer = engine.answer(PictureReading.system, PictureReading.prompt(2), PictureReading.schema, pictures)
        val text = PictureReading.parse(answer, 2)
        println("  read in ${"%.1f".format((System.nanoTime() - started) / 1e9)} s:\n" + text.prependIndent("    "))
        listOf("Bel", "Jer", "Alfama", "Jorge", "Pena", "Regaleira", "Time Out").forEach { w -> check("read: $w", text.contains(w, ignoreCase = true)) }
        check("both pictures in one list, Day 3 after Day 2", text.indexOf("Day 3", ignoreCase = true) > text.indexOf("Day 2", ignoreCase = true))
        check("no username, likes or hashtags", "@wanderlust" !in text && "2,345" !in text && "#lisbon" !in text)
        return text
    }

    /** A phone-sized screenshot (1080 wide, as tall as its lines need) with [lines] of dark text on white. */
    private fun screenshot(vararg lines: String): java.awt.image.BufferedImage {
        System.setProperty("java.awt.headless", "true")
        val image = java.awt.image.BufferedImage(1080, 2400, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = java.awt.Color(0x22, 0x22, 0x22)
        var y = 140
        lines.forEachIndexed { i, line ->
            g.font = java.awt.Font("SansSerif", if (i <= 1 || line.startsWith("Day")) java.awt.Font.BOLD else java.awt.Font.PLAIN, if (i == 1) 64 else 44)
            g.drawString(line, 60, y)
            y += if (line.isEmpty()) 50 else 90
        }
        g.dispose()
        return image
    }

    /** [image] scaled and cut as [PicturePrep] does on the phone, each piece a JPEG. */
    private fun cut(image: java.awt.image.BufferedImage, maxPieces: Int): List<AiPicture> {
        val layout = PictureReading.layout(image.width, image.height, maxPieces)
        val scaled = java.awt.image.BufferedImage(layout.width, layout.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, layout.width, layout.height, null)
            dispose()
        }
        return layout.pieces.map { rows ->
            val piece = scaled.getSubimage(0, rows.first, layout.width, rows.last - rows.first + 1)
            val out = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(piece, "jpg", out)
            AiPicture(out.toByteArray())
        }
    }

    // ---- a tiny in-memory phone ----

    private class Phone {
        var trip: Trip? = null
        val items = LinkedHashMap<String, PlanItem>()
        val live: List<PlanItem> get() = items.values.filter { it.deletedAt == null }.sortedWith(compareBy({ it.day == null }, { it.day }, { it.position }))
        val history = ArrayList<ChatTurn>()
    }

    private val failures = ArrayList<String>()
    private fun check(label: String, ok: Boolean, detail: String = "") {
        println((if (ok) "  ok   " else "  FAIL ") + label + if (detail.isNotBlank()) "  [$detail]" else "")
        if (!ok) failures += label
    }

    private suspend fun ask(
        engine: PlannerEngine, phone: Phone, message: String, lists: com.justdataplease.boringtravel.ai.tools.MapsListLookup? = null,
    ): Pair<DraftLayout, ApplyResult?> {
        val started = System.nanoTime()
        // The app's own process, as the planner screen runs it: the model only gives the tools' inputs.
        val process = PlannerProcess(places, lists = lists)
        val outcome = process.run(engine, phone.trip, phone.live, PendingPlan(), phone.history, message, today) { println("    · $it") }
        val layout = outcome.layout
        println("  ${"%.1f".format((System.nanoTime() - started) / 1e9)} s · ${outcome.calls.size} calls: ${outcome.calls.joinToString { it.name }}")
        println("  reply: ${outcome.reply}")
        if (layout.questions.isNotEmpty()) println("  questions: ${layout.questions}")
        if (layout.problems.isNotEmpty()) println("  problems: ${layout.problems}")
        phone.history += ChatTurn(true, message)
        phone.history += ChatTurn(false, outcome.replyWithQuestions)
        if (layout.questions.isNotEmpty() || layout.problems.isNotEmpty() || layout.reviewableCount == 0) return layout to null
        val n = AtomicCounter()
        val applied = process.save(outcome.draft, phone.trip, phone.live, phone.live, emptySet(), "draft", System.currentTimeMillis()) { UUID.randomUUID().toString().take(8) + n.next() }
        phone.trip = applied.trip
        applied.rows.forEach { phone.items[it.id] = it }
        return layout to applied
    }

    private class AtomicCounter { private var i = 0; fun next() = ++i }

    private fun print(phone: Phone) {
        val t = phone.trip ?: return
        println("  TRIP ${t.title} · ${t.startDate}..${t.endDate} · ${t.subtitle}")
        val legs = RouteLegs.planned(t, phone.live).associateBy { it.key.toId }
        phone.live.forEach { i ->
            val time = i.timeMinutes?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "     "
            val pin = if (i.lat != null) "%.4f,%.4f".format(i.lat, i.lng) else "no pin"
            val leg = legs[i.id]?.travel?.label?.let { "← $it" } ?: ""
            println("   ${i.day ?: "idea"} $time ${i.kind.padEnd(9)} ${i.title.take(34).padEnd(34)} $pin $leg | ${i.note.take(60)} | ${i.url?.take(50) ?: ""}")
        }
    }

    private fun Phone.find(day: LocalDate?, vararg words: String): PlanItem? = live.firstOrNull { i ->
        (day == null || i.day == day) && words.all { w -> (i.title + " " + i.placeName + " " + i.note).contains(w, ignoreCase = true) }
    }

    private fun near(i: PlanItem?, lat: Double, lng: Double, km: Double = 3.0): Boolean =
        i?.lat != null && abs(i.lat!! - lat) < km / 111.0 && abs(i.lng!! - lng) < km / 85.0

    private fun d(day: Int, month: Int = 11, year: Int = 2026) = LocalDate.of(year, month, day)

    /** A stay that ends on [day]: its end date, or (as before 1.10.0) the day in its note. */
    private fun stayEnds(i: PlanItem?, day: LocalDate): Boolean =
        i != null && (PlanSpan.endDate(i) == day || i.note.contains(day.dayOfMonth.toString()))

    private suspend fun run(name: String, engine: PlannerEngine) {
        println("=== $name ===")
        val phone = Phone()

        println("\n[1] New trip from the owner's pasted Madrid itinerary")
        ask(engine, phone, MADRID)
        print(phone)
        val t = phone.trip
        check("trip created", t != null)
        check("dates 12–19 Nov 2026", t?.startDate == d(12) && t.endDate == d(19), "${t?.startDate}..${t?.endDate}")
        check("route set", t?.subtitle?.isNotBlank() == true, t?.subtitle.orEmpty())
        val leave = phone.live.firstOrNull { it.day == d(12) && it.kind == PlanKind.Transport.id && near(it, 37.9364, 23.9445, 15.0) }
        val flight = phone.live.firstOrNull { it.day == d(12) && it.kind == PlanKind.Transport.id && near(it, 40.4839, -3.5680, 8.0) }
        check("flight sits at Madrid-Barajas", flight != null, flight?.title.orEmpty())
        check("leaves at 15:50", leave?.timeMinutes == 15 * 60 + 50 || flight?.timeMinutes == 15 * 60 + 50, "${leave?.title} ${leave?.timeMinutes}")
        check("flight note keeps IB 3151 and JMKXT", flight?.note?.let { "3151" in it && "JMKXT" in it } == true, flight?.note.orEmpty())
        val legs = t?.let { RouteLegs.planned(it, phone.live).associateBy { l -> l.key.toId } }.orEmpty()
        check("Athens → Madrid leg flies", flight != null && legs[flight.id]?.travel == Vehicle.Plane, legs[flight?.id]?.let { "${it.fromTitle} → ${it.toTitle}: ${it.travel}" } ?: "no leg")
        check("departure point in Athens", phone.live.any { near(it, 37.9364, 23.9445, 15.0) })
        val reina = phone.find(d(12), "Reina")
        check("Reina Sofía 12 Nov 19:00", reina?.timeMinutes == 19 * 60, reina?.title.orEmpty())
        check("Reina Sofía pin", near(reina, 40.4079, -3.6946))
        check("Reina Sofía website", reina?.url?.contains("museoreinasofia") == true, reina?.url.orEmpty())
        val hostal = phone.find(d(12), "Alicante")
        check("Hostal Alicante stay on 12 Nov", hostal?.kind == PlanKind.Stay.id, hostal?.title.orEmpty())
        // Since 1.10.0 a stay ends on its check-out day (an end date), not in its note.
        check("Hostal Alicante until 15 Nov", stayEnds(hostal, d(15)), "${hostal?.let(PlanSpan::endDate)} ${hostal?.note.orEmpty()}")
        check("Hostal Alicante website", hostal?.url?.contains("hostalalicante") == true, hostal?.url.orEmpty())
        check("Hostal Alicante pin", near(hostal, 40.4168, -3.7067))
        val yandel = phone.find(d(13), "Yandel")
        check("Yandel 13 Nov 18:00", yandel?.timeMinutes == 18 * 60)
        val prado = phone.find(d(14), "Prado")
        check("Prado 14 Nov 18:00", prado?.timeMinutes == 18 * 60)
        check("Prado pin", near(prado, 40.4138, -3.6921))
        val granada = phone.find(d(15), "Suspiro")
        check("Suspiro 201 on 15 Nov in Granada", near(granada, 37.1732, -3.5977, 5.0), "${granada?.lat},${granada?.lng}")
        val triana = phone.find(d(18), "Triana")
        check("Esencia de Triana on 18 Nov in Seville", near(triana, 37.3876, -6.0071, 5.0))
        check("every item but notes on the map", phone.live.filter { it.kind != PlanKind.Note.id }.all { it.lat != null }, phone.live.filter { it.lat == null }.joinToString { it.title })
        // A website when the list gave one, else a Google Maps link (the two links since 1.13).
        check("every item has a link", phone.live.filter { it.kind != PlanKind.Note.id }.all { it.url != null || it.mapsUrl != null })
        check("no item twice", phone.live.groupBy { DraftLayout.normaliseTitle(it.title) to it.day }.all { it.value.size == 1 })

        println("\n[2] The whole trip moves two days later")
        val before = phone.live.associate { it.id to it.day }
        ask(engine, phone, "Change of plans: the whole trip moves two days later, same plan.")
        print(phone)
        check("dates 14–21 Nov", phone.trip?.startDate == d(14) && phone.trip?.endDate == d(21), "${phone.trip?.startDate}..${phone.trip?.endDate}")
        check("every dated item moved two days", phone.live.filter { before[it.id] != null }.all { it.day == before[it.id]!!.plusDays(2) })
        check("nothing added", phone.live.size == before.size, "${before.size} → ${phone.live.size}")
        check("check-out moved too", stayEnds(phone.find(d(14), "Alicante"), d(17)), "${phone.find(d(14), "Alicante")?.let(PlanSpan::endDate)}")

        println("\n[3] Back to the original dates, by pasting the corrected list (Prado now 17:00, Yandel on the 14th)")
        val count = phone.live.size
        ask(engine, phone, MADRID.replace("Museo del Prado (Free)\nNov 14, 2026 (18:00 - 20:00)", "Museo del Prado (Free)\nNov 14, 2026 (17:00 - 19:00)")
            .replace("Yandel Sinfónico\nNov 13, 2026 (18:00 - 19:00)", "Yandel Sinfónico\nNov 14, 2026 (21:00 - 22:00)")
            .replace("Date & Time: November 14, 2026 (18:00 - 20:00)", "Date & Time: November 14, 2026 (17:00 - 19:00)")
            .replace("Date & Time: November 13, 2026 (18:00 - 19:00)", "Date & Time: November 14, 2026 (21:00 - 22:00)"))
        print(phone)
        check("dates back to 12–19 Nov", phone.trip?.startDate == d(12) && phone.trip?.endDate == d(19), "${phone.trip?.startDate}..${phone.trip?.endDate}")
        check("Prado at 17:00 on 14 Nov", phone.find(d(14), "Prado")?.timeMinutes == 17 * 60)
        check("Yandel on 14 Nov at 21:00", phone.find(d(14), "Yandel")?.timeMinutes == 21 * 60)
        check("flight back on 12 Nov", phone.find(d(12), "3151") != null)
        check("no duplicates after re-paste", phone.live.size == count, "$count → ${phone.live.size}")

        println("\n[4] A different hotel in Granada")
        ask(engine, phone, "In Granada we now stay at Hotel Casa Morisca, Cuesta de la Victoria 9, Granada, instead of Suspiro 201.")
        print(phone)
        val morisca = phone.find(null, "Morisca")
        check("Granada stay renamed, same day", morisca?.day == d(15), morisca?.title.orEmpty())
        check("Casa Morisca pin moved", near(morisca, 37.1781, -3.5895, 2.0), "${morisca?.lat},${morisca?.lng}")
        check("no Suspiro left", phone.find(null, "Suspiro") == null)
        check("the old hotel's website went", morisca?.url?.contains("suspiro") != true, morisca?.url.orEmpty())
        check("check-out follows", stayEnds(morisca, d(16)), "${morisca?.let(PlanSpan::endDate)} ${morisca?.note.orEmpty()}")

        println("\n[5] Remove the concert")
        ask(engine, phone, "Remove the Yandel concert, we won't go.")
        check("Yandel removed", phone.find(null, "Yandel") == null)

        println("\n[6] Add a dinner")
        ask(engine, phone, "Add dinner at Botín on the 13th at 21:00.")
        print(phone)
        val botin = phone.find(d(13), "Bot")
        check("Botín dinner on 13 Nov 21:00", botin?.timeMinutes == 21 * 60 && botin.kind == PlanKind.Food.id, botin?.title.orEmpty())
        check("Botín pin in Madrid", near(botin, 40.4141, -3.7081, 1.5), "${botin?.lat},${botin?.lng}")

        println("\n[7] A new trip from one line (no year)")
        val lisbon = Phone()
        ask(engine, lisbon, "Lisbon 3-6 March, flying from Athens on the 3rd at 09:40 with Aegean A3 612. Hotel: Memmo Alfama.")
        print(lisbon)
        check("Lisbon dates 3–6 Mar 2027", lisbon.trip?.startDate == d(3, 3, 2027) && lisbon.trip?.endDate == d(6, 3, 2027), "${lisbon.trip?.startDate}..${lisbon.trip?.endDate}")
        val fly = lisbon.live.firstOrNull { it.kind == PlanKind.Transport.id && near(it, 38.7742, -9.1342, 8.0) }
        val lisbonLegs = lisbon.trip?.let { RouteLegs.planned(it, lisbon.live).associateBy { l -> l.key.toId } }.orEmpty()
        check("Lisbon flight lands at the airport by plane", fly != null && lisbonLegs[fly.id]?.travel == Vehicle.Plane, fly?.title.orEmpty())
        check("leaves Athens at 09:40", lisbon.live.any { it.timeMinutes == 9 * 60 + 40 })
        check("Memmo Alfama on the map", near(lisbon.find(null, "Memmo"), 38.7106, -9.1310, 2.0))

        println("\n=== $name: ${failures.size} failed: $failures")
        assertTrue("$name failed: $failures", failures.isEmpty())
    }

    private companion object {
        val MADRID = """
Madrid Trip Itinerary & Detailed Event Information
Comprehensive schedule of flights, accommodations, free museum entries, and events for your upcoming trip to Spain.
Master Schedule Overview
Event / Activity
Date & Time
Location
Category
ATH -> MAD Flight
Nov 12, 2026 (15:50 - 16:50)
Athens (ATH) -> Madrid (MAD)
 Travel
Museo Reina Sofía (Free)
Nov 12, 2026 (19:00 - 21:00)
Museo Reina Sofía, Madrid
 Culture
Stay: Hostal Alicante
Nov 12, 2026 – Nov 15, 2026
C. del Arenal, 16, 2do piso, 28013 Madrid
 Lodging
Yandel Sinfónico
Nov 13, 2026 (18:00 - 19:00)
Madrid, Spain (City Center)
 Concert
Museo del Prado (Free)
Nov 14, 2026 (18:00 - 20:00)
Museo del Prado, Madrid
 Culture
Stay: Suspiro 201 Suite
Nov 15, 2026 – Nov 16, 2026
Calle Suspiro, 22, 18009 Granada
 Lodging
Stay: Esencia de Triana
Nov 18, 2026 – Nov 19, 2026
Calle Procurador, 20, 41010 Sevilla
 Lodging

Detailed Breakdown
Travel & Flights
ATH -> MAD Flight
Date & Time: November 12, 2026 (15:50 - 16:50)
Flight Info: Iberia IB 3151 (Booking Code: JMKXT)
Departure Airport: Athens International Airport "Eleftherios Venizelos" (ATH)
Address: Attiki Odos, Spata 19019, Greece
Coordinates: 37.9364° N, 23.9445° E
Website: https://www.aia.gr/
Arrival Airport: Adolfo Suárez Madrid-Barajas Airport (MAD)
Address: Av de la Hispanidad, s/n, 28042 Madrid, Spain
Coordinates: 40.4839° N, 3.5680° W
Website: https://www.aena.es/es/adolfo-suarez-madrid-barajas.html
Accommodations
Hostal Alicante
Dates: November 12, 2026 – November 15, 2026
Location: Hostal Alicante
Address: C. del Arenal, 16, 2do piso, Centro, 28013 Madrid, Spain
Coordinates: 40.4168° N, 3.7067° W
Website: http://hostalalicante.com/
Suspiro 201 Elegant Suite
Dates: November 15, 2026 – November 16, 2026
Location: Suspiro 201 Elegant Suite
Address: Calle Suspiro, 22, Centro, 18009 Granada, Spain
Coordinates: 37.1732° N, 3.5977° W
Website: https://suspiro-201-elegant-suite.granadahotels.org/en/
Esencia de Triana Boutique
Dates: November 18, 2026 – November 19, 2026
Location: Esencia de Triana Boutique
Address: Calle Procurador, 20, Triana, 41010 Sevilla, Spain
Coordinates: 37.3876° N, 6.0071° W
Website: https://edomus-esencia-de-triana-boutique.sevilleapartments.org/en/
Cultural Activities & Events
Museo Reina Sofía (GRATIS)
Date & Time: November 12, 2026 (19:00 - 21:00)
Location: Museo Nacional Centro de Arte Reina Sofía
Address: C. de Sta. Isabel, 52, Centro, 28012 Madrid, Spain
Coordinates: 40.4079° N, 3.6946° W
Website: https://www.museoreinasofia.es/
Yandel Sinfónico
Date & Time: November 13, 2026 (18:00 - 19:00)
Location: Madrid, Spain (City Center)
Coordinates: 40.4168° N, 3.7038° W
Museo del Prado (GRATIS)
Date & Time: November 14, 2026 (18:00 - 20:00)
Location: Museo Nacional del Prado
Address: Retiro, 28014 Madrid, Spain
Coordinates: 40.4138° N, 3.6921° W
Website: https://www.museodelprado.es/
        """.trimIndent()
    }
}
