package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.ai.own.GeminiKeyApi
import com.justdataplease.boringtravel.ai.own.OwnKeyPlanner
import com.justdataplease.boringtravel.ai.tools.MapsLookup
import com.justdataplease.boringtravel.ai.tools.PageLookup
import com.justdataplease.boringtravel.ai.tools.PageTitle
import com.justdataplease.boringtravel.ai.tools.PendingPlan
import com.justdataplease.boringtravel.ai.tools.PlaceLookup
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.links.LinkFetcher
import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.links.MapsLinks
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.places.Geo
import com.justdataplease.boringtravel.places.LooseQueries
import com.justdataplease.boringtravel.places.PhotonParser
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * One place at a time, against a real model, by hand only (`BT_LIVE_AI=1` with `GEMINI_API_KEY`, keys in
 * android-boringtravel/.env): a Google Maps link alone, a Maps link with a day and time, a place by its name. Owner, 15 Nov
 * 2026: the planner "does a lot of iterations and does not work with a Google Maps URL, where it should be instant;
 * by giving a name it did many iterations, stuck at finding a place". Prints the model requests and seconds per message.
 */
class PlacesLiveTest {
    private val today = LocalDate.of(2026, 11, 15)
    private var requests = 0
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
        .addInterceptor { chain -> if (chain.request().method == "POST") requests++; chain.proceed(chain.request()) }
        .build()
    private val places = PlaceLookup { query, near ->
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("limit", "8")
            .apply { if (near != null) { addQueryParameter("lat", near.lat.toString()); addQueryParameter("lon", near.lng.toString()) } }.build()
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", "BoringTravel-tests").build()).execute().use { it.body?.string().orEmpty() }
        PhotonParser.parse(body)
    }

    private val trip = Trip(id = "t1", title = "Istanbul", startDate = LocalDate.of(2026, 12, 3), endDate = LocalDate.of(2026, 12, 6), createdAt = 0, updatedAt = 0)
    private val items = listOf(
        PlanItem(id = "a1", tripId = "t1", day = LocalDate.of(2026, 12, 3), kind = PlanKind.Stay.id, title = "Stay in Sultanahmet", placeName = "Sultanahmet, Istanbul", lat = 41.0055, lng = 28.9769, position = 0.0, createdAt = 0, updatedAt = 0),
        PlanItem(id = "a2", tripId = "t1", day = LocalDate.of(2026, 12, 4), timeMinutes = 600, kind = PlanKind.Place.id, title = "Hagia Sophia", placeName = "Hagia Sophia, Istanbul", lat = 41.0086, lng = 28.9802, position = 0.0, createdAt = 0, updatedAt = 0),
    )

    private val madrid = Trip(id = "t2", title = "Madrid", startDate = LocalDate.of(2026, 12, 10), endDate = LocalDate.of(2026, 12, 13), createdAt = 0, updatedAt = 0)
    private val madridItems = listOf(
        PlanItem(id = "m1", tripId = "t2", day = LocalDate.of(2026, 12, 10), kind = PlanKind.Stay.id, title = "Stay in Sol", placeName = "Puerta del Sol, Madrid", lat = 40.4169, lng = -3.7035, position = 0.0, createdAt = 0, updatedAt = 0),
    )

    /** The app's way with a Maps link, without Android: a short link followed, its name searched near the trip. */
    private val fetcher = LinkFetcher(http, "BoringTravel/test (Android; +https://justdataplease.com/apps/boringtravel/)")
    private val maps = MapsLookup { url, near ->
        val long = if (MapsLinks.isShort(url)) fetcher.expandMaps(url) else url
        val parsed = long?.let(MapsLinks::parse) ?: return@MapsLookup null
        if (parsed.hasPoint || parsed.name == null) return@MapsLookup parsed
        LinkPlaces.choose(LooseQueries.search(parsed.name!!, listOfNotNull(near)) { places.find(it, near) }, near)
            ?.let { parsed.copy(lat = it.lat, lng = it.lng) } ?: parsed
    }

    /** A website's title and site name, as the app's link previews read them. */
    private val pages = PageLookup { url -> fetcher.page(url).let { PageTitle(it.title, it.siteName) } }

    /** One message, at most [maxRequests] model requests, every new item pinned within [km] of [lat], [lng]. */
    private data class Case(val trip: String, val message: String, val maxRequests: Int, val lat: Double, val lng: Double, val km: Double = 15.0)

    @Test fun gemini() = runBlocking {
        assumeTrue(System.getenv("BT_LIVE_AI") == "1")
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        assumeTrue(key.isNotBlank())
        val engine = OwnKeyPlanner(GeminiKeyApi(http), key, "gemini-flash-latest")
        val failures = ArrayList<String>()
        for (case in CASES) {
            println("\n> ${case.message}")
            requests = 0
            val started = System.nanoTime()
            val (t, i) = if (case.trip == "madrid") madrid to madridItems else trip to items
            val outcome = PlannerProcess(places, null, maps, pages).run(engine, t, i, PendingPlan(), emptyList(), case.message, today) { println("    · $it") }
            val seconds = (System.nanoTime() - started) / 1e9
            println("  %.1f s · %d model requests · calls: %s".format(seconds, requests, outcome.calls.joinToString { it.name }))
            println("  reply: ${outcome.replyWithQuestions}")
            outcome.pending.added.forEach { p ->
                println("  + ${p.date ?: "idea"} ${p.item.time ?: ""} ${p.item.kind} ${p.item.title} @ ${p.item.placeName} (${p.item.lat}, ${p.item.lng}) ${p.item.url ?: ""}")
            }
            val added = outcome.pending.added
            if (added.isEmpty()) failures += "${case.message}: nothing added"
            added.filter { p -> p.item.lat == null || Geo.distanceM(case.lat, case.lng, p.item.lat!!, p.item.lng!!) > case.km * 1000 }
                .forEach { failures += "${case.message}: ${it.item.title} pinned at ${it.item.lat}, ${it.item.lng}" }
            if (requests > case.maxRequests) failures += "${case.message}: $requests model requests (at most ${case.maxRequests})"
        }
        println("\n" + failures.ifEmpty { listOf("all fine") }.joinToString("\n"))
        assertTrue(failures.joinToString(), failures.isEmpty())
    }

    companion object {
        private const val LINK = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"
        private val CASES = listOf(
            Case("istanbul", LINK, 0, 41.01, 28.97, 3.0),
            Case("istanbul", "Dinner here on the 4th at 20:00 $LINK", 1, 41.01, 28.97, 3.0),
            Case("istanbul", "Add Pandeli", 2, 41.017, 28.971, 3.0),
            Case("istanbul", "Lunch at Karaköy Lokantası on the 5th", 2, 41.025, 28.98, 3.0),
            Case("madrid", "Dinner at Casa Lucio on the 11th at 21:30", 2, 40.4169, -3.7035, 15.0),
            // Websites (owner: "or when adding a website page"): a place's own site alone, and with a day.
            Case("madrid", "https://www.casalucio.es/", 0, 40.4122, -3.7088, 2.0),
            Case("madrid", "https://www.lhardy.com/", 0, 40.4167, -3.7008, 2.0),
            Case("madrid", "Lunch here on the 12th at 14:00 https://botin.es/", 1, 40.4141, -3.7081, 2.0),
        )
    }
}
