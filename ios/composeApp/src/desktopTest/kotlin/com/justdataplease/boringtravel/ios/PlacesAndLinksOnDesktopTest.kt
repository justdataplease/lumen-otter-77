package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Places and links driven as a traveller would on the desktop, over recorded answers ([Recorded]: the Android app's own
 * test fixtures) and never the network: the item sheet's place search and its naming, Use my location, a website's
 * preview, a Google Maps link pasted, an item made from a link alone, Renew details, Open in Google Maps, a day's title
 * opening its place page, a stop's page, the note's place found on the map and from the trip, and a Google Maps list
 * copied into a trip. Offline nothing is asked and the Android app's words say so. After each, what was kept still
 * reads as a backup's data.json.
 */
@OptIn(ExperimentalTestApi::class)
class PlacesAndLinksOnDesktopTest {
    private val today = SAMPLE_TODAY

    init {
        runBlocking { BtFontFiles.load() }
    }

    private class Opened : UriHandler {
        val uris = mutableListOf<String>()
        override fun openUri(uri: String) {
            uris += uri
        }
    }

    private fun ui(
        book: TripBook,
        vararg at: Place,
        web: Web = Recorded.web(),
        settings: AppSettings = AppSettings.ready(),
        phone: Phone = Phone(),
        opened: Opened = Opened(),
        block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 440, height = 2400) {
        setContent {
            CompositionLocalProvider(LocalPhone provides phone, LocalUriHandler provides opened) {
                BoringTravelApp(book, settings, today, startAt = at.toList(), web = web)
            }
        }
        block()
        waitForIdle()
    }

    private fun ComposeUiTest.fields() = onAllNodes(hasSetTextAction())

    private fun ComposeUiTest.shows(text: String, timeout: Long = 5_000) =
        waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.byAction(label: String) =
        onAllNodes(SemanticsMatcher("does $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label })

    private fun assertKeptAsABackup(file: MemoryTripFile, book: TripBook) {
        val data = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read()))
        assertEquals(book.snapshot, data.toSnapshot())
        assertEquals(book.snapshot, TripBook(file).snapshot)
    }

    private fun naxos(file: MemoryTripFile): Pair<TripBook, String> {
        val book = TripBook(file, links = LinkPlaces::sortLinks)
        return book to book.newTrip("Naxos", today, today.plusDays(3)).id
    }

    @Test
    fun aPlaceIsSearchedPickedAndNamedAsAndroidNamesIt() {
        val file = MemoryTripFile()
        val (book, tripId) = naxos(file)
        val net = Recorded.net()
        ui(book, Place.Trip(tripId), web = Recorded.web(net)) {
            onNodeWithText("Add a place or a note").performClick()
            onNodeWithText("PLACE OR GOOGLE MAPS LINK").assertExists()
            fields()[1].performTextInput("halki")
            shows("Places © OpenStreetMap contributors")
            // The rows as the Android app's parser names them for the same answer (PhotonParserTest).
            val android = listOf("Halki, Naxos, Greece", "Portara Coffee & Bar, Naxos, Greece", "Studios Agios Georgios, Naxos, Greece", "Naxos Port, Naxos, Greece", "Papavasileiou 12, Naxos, Greece", "Greece")
            assertEquals(android, PhotonParser.parse(Recorded.PHOTON_HALKI).map { it.displayName })
            android.forEach { onNodeWithText(it).assertExists() }
            onNodeWithText("Use “halki” as the place").assertExists()
            onAllNodesWithText("Halki")[0].performClick()
            waitForIdle()
            onNodeWithText("Halki, Naxos, Greece").assertExists()
            onNodeWithText("Done").performClick()
            waitForIdle()
        }
        // One search, as the Android app asks Photon: the words, 8 results, English names, the app's name. (The place picked
        // then has its Wikipedia photo looked up.)
        val ask = net.asked.filter { it.url.startsWith("https://photon.komoot.io") }.single()
        assertEquals("https://photon.komoot.io/api/?q=halki&limit=8&lang=en", ask.url)
        assertEquals("BoringTravel/1.0 (iPhone)", ask.headers["User-Agent"])
        val item = book.items(tripId).single()
        assertEquals("Halki", item.title)
        assertEquals("Halki, Naxos, Greece", item.placeName)
        assertEquals(37.0639027, item.lat!!, 1e-9)
        assertEquals(25.4839886, item.lng!!, 1e-9)
        assertEquals("osm:N806501481", item.externalId)
        assertEquals(PlanKind.Place.id, item.kind)
        assertKeptAsABackup(file, book)
    }

    @Test
    fun offlineNothingIsAskedAndTheTypedNameIsThePlace() {
        val file = MemoryTripFile()
        val (book, tripId) = naxos(file)
        val net = Recorded.net(connected = false)
        ui(book, Place.Trip(tripId), web = Recorded.web(net)) {
            onNodeWithText("Add a place or a note").performClick()
            fields()[1].performTextInput("halki")
            shows("Can't search right now. Check the connection.")
            onNodeWithText("Use “halki” as the place").performClick()
            waitForIdle()
            onNodeWithText("Done").performClick()
            waitForIdle()
        }
        // The phone said it is offline: nothing went out.
        assertTrue(net.asked.isEmpty())
        val item = book.items(tripId).single()
        assertEquals("halki", item.title)
        assertEquals("halki", item.placeName)
        assertNull(item.lat)
        assertKeptAsABackup(file, book)
    }

    @Test
    fun useMyLocationNamesWhereThePhoneIs() {
        val file = MemoryTripFile()
        val (book, tripId) = naxos(file)
        val net = Recorded.net()
        ui(book, Place.Trip(tripId), web = Recorded.web(net)) {
            onNodeWithText("Add a place or a note").performClick()
            onNodeWithText("Use my location").performClick()
            // No geocoder on this phone: the place search's reverse names the spot.
            shows("Syntagma Square, Athens, Greece")
            onNodeWithText("Done").performClick()
            waitForIdle()
        }
        assertTrue(net.asked.filter { it.url.startsWith("https://photon.komoot.io") }.single().url.startsWith("https://photon.komoot.io/reverse?lat=37.9755&lon=23.7348&limit=1&lang=en"))
        val item = book.items(tripId).single()
        assertEquals("Syntagma Square", item.title)
        assertEquals(37.9755, item.lat!!, 1e-9)
        assertEquals(23.7348, item.lng!!, 1e-9)
        assertKeptAsABackup(file, book)

        // Refused: one line says so and nothing is found.
        val refused = object : PhoneLocation by NoLocation {}
        ui(book, Place.Trip(tripId), web = Recorded.web(net, location = refused)) {
            onNodeWithText("Add").performClick()
            onNodeWithText("Use my location").performClick()
            shows("No access to your location. Search for the place instead.")
        }
    }

    @Test
    fun aWebsiteShowsItsPreviewAndNamesTheItem() {
        val file = MemoryTripFile()
        val (book, tripId) = naxos(file)
        val net = Recorded.net()
        ui(book, Place.Trip(tripId), web = Recorded.web(net)) {
            onNodeWithText("Add a place or a note").performClick()
            fields()[2].performTextInput("www.kyoto.travel/en/see-and-do/yasaka.html")
            // The page's name fills the empty title; its picture, name and site show on top.
            waitUntil(timeoutMillis = 5_000) { book.items(tripId).singleOrNull()?.title == "Yasaka Shrine" }
            shows("kyoto.travel")
            onNodeWithText("Done").performClick()
            waitForIdle()
        }
        val item = book.items(tripId).single()
        assertEquals(Recorded.KYOTO_PAGE_URL, item.url)
        assertNull(item.mapsUrl)
        val page = net.asked.first { it.url == Recorded.KYOTO_PAGE_URL }
        assertEquals("BoringTravel/1.0 (iPhone; +https://justdataplease.com/apps/boringtravel/)", page.headers["User-Agent"])
        assertEquals(LinkFetcher.MAX_PAGE_BYTES, page.maxBytes)
        // Whether Google knows the site's favicon is asked once (then the icon itself is loaded as a picture).
        assertEquals(1, net.asked.count { it.url == LinkUrls.favicon("www.kyoto.travel") && it.maxBytes == 64L * 1024 })
        assertKeptAsABackup(file, book)
    }

    @Test
    fun aMapsLinkPastedBecomesThePlaceAndALinkAloneMakesTheItem() {
        val file = MemoryTripFile()
        val (book, tripId) = naxos(file)
        val net = Recorded.net()
        var clipboard: String? = null
        val phone = Phone(paste = { clipboard })
        ui(book, Place.Trip(tripId), web = Recorded.web(net), phone = phone) {
            onNodeWithText("Add a place or a note").performClick()
            // Nothing copied: said plainly.
            onNodeWithText("Paste a link").performClick()
            onNodeWithText("No link to paste. Copy one first.").assertExists()
            // What the Maps app's Share puts on the clipboard: the name and a short link.
            clipboard = "Yasaka Shrine\n${Recorded.YASAKA_SHORT}"
            onNodeWithText("Paste a link").performClick()
            waitUntil(timeoutMillis = 5_000) { book.items(tripId).singleOrNull()?.lat != null }
            onNodeWithText("Done").performClick()
            waitForIdle()
        }
        val item = book.items(tripId).single()
        assertEquals("Yasaka Shrine", item.title)
        assertEquals(Recorded.YASAKA_SHORT, item.mapsUrl)
        assertNull(item.url)
        assertEquals(Recorded.YASAKA.lat, item.lat!!, 1e-9)
        assertEquals(Recorded.YASAKA.lng, item.lng!!, 1e-9)
        // The short link was followed one step, with the app's own name, and never loaded as a page.
        val hop = net.asked.first { it.url == Recorded.YASAKA_SHORT }
        assertEquals(false, hop.followRedirects)
        assertTrue(net.asked.none { it.url.startsWith("https://www.google.com/maps/place/") })
        assertKeptAsABackup(file, book)

        // A site the network can't read: the item is made all the same, named by its domain.
        ui(book, Place.Trip(tripId), web = Recorded.web(net)) {
            onNodeWithText("Add").performClick()
            fields()[2].performTextInput("example.com")
            waitForIdle()
            onNodeWithText("Done").performClick()
            waitForIdle()
        }
        val alone = book.items(tripId).single { it.id != item.id }
        assertEquals("example.com", alone.title)
        assertEquals("https://example.com/", alone.url)
        assertKeptAsABackup(file, book)
    }

    @Test
    fun aShortMapsLinkClosedAtOnceIsFilledAfterTheSheetCloses() {
        val file = MemoryTripFile()
        val (book, tripId) = naxos(file)
        val phone = Phone(paste = { Recorded.YASAKA_SHORT })
        ui(book, Place.Trip(tripId), phone = phone) {
            onNodeWithText("Add a place or a note").performClick()
            onNodeWithText("Paste a link").performClick()
            onNodeWithText("Done").performClick()
            waitUntil(timeoutMillis = 5_000) { book.items(tripId).singleOrNull()?.lat != null }
        }
        val item = book.items(tripId).single()
        assertEquals(Recorded.YASAKA.lat, item.lat!!, 1e-9)
        assertEquals("Yasaka Shrine", item.placeName)
        // The placeholder title gives way to the place's name.
        assertEquals("Yasaka Shrine", item.title)
        assertKeptAsABackup(file, book)
    }

    @Test
    fun renewDetailsReadsTheLinksAgainButATypedTitleStays() {
        val file = MemoryTripFile()
        val (book, tripId) = naxos(file)
        val placeholder = book.saveItem(book.draftItem(tripId, today, 1.0).copy(title = "kyoto.travel", url = Recorded.KYOTO_PAGE_URL))!!
        val typed = book.saveItem(book.draftItem(tripId, today, 2.0).copy(title = "My shrine", url = Recorded.KYOTO_PAGE_URL))!!
        val net = Recorded.net()
        ui(book, Place.Trip(tripId), web = Recorded.web(net)) {
            byAction("Open kyoto.travel")[0].performClick()
            onNodeWithText("Renew details").performScrollTo().performClick()
            shows("Details renewed.")
            onNodeWithText("Done").performClick()
            waitForIdle()
            byAction("Open My shrine").onLast().performClick()
            onNodeWithText("Renew details").performScrollTo().performClick()
            shows("Nothing new.")
            onNodeWithText("Done").performClick()
            waitForIdle()
        }
        assertEquals("Yasaka Shrine", book.item(placeholder.id)!!.title)
        assertEquals("My shrine", book.item(typed.id)!!.title)
        // Offline: what was known stays, and the sheet says so.
        net.connected = false
        ui(book, Place.Trip(tripId), web = Recorded.web(net)) {
            byAction("Open My shrine").onLast().performClick()
            onNodeWithText("Renew details").performScrollTo().performClick()
            shows("Couldn't read the links. Check the connection.")
        }
        assertKeptAsABackup(file, book)
    }

    @Test
    fun openInGoogleMapsOpensTheLinkElseThePin() {
        val file = MemoryTripFile()
        val (book, tripId) = naxos(file)
        val pinned = book.saveItem(book.draftItem(tripId, today, 1.0).copy(title = "Shrine", placeName = "Yasaka", lat = 35.0036559, lng = 135.7785534))!!
        val linked = book.saveItem(book.draftItem(tripId, today, 2.0).copy(title = "Yasaka Shrine", mapsUrl = Recorded.YASAKA_LONG, lat = 35.0036559, lng = 135.7785534))!!
        val opened = Opened()
        ui(book, Place.Trip(tripId), opened = opened, web = Recorded.web(Recorded.net(connected = false))) {
            byAction("Open ${pinned.title}").onLast().performClick()
            onNodeWithText("Open in Google Maps").performScrollTo().performClick()
            onNodeWithText("Done").performClick()
            waitForIdle()
            byAction("Open ${linked.title}").onLast().performClick()
            onNodeWithText("Open in Google Maps").performScrollTo().performClick()
        }
        assertEquals(
            listOf("https://www.google.com/maps/search/?api=1&query=35.0036559%2C135.7785534", Recorded.YASAKA_LONG),
            opened.uris,
        )
    }

    @Test
    fun aDaysTitleOpensItsPlacePage() {
        val book = argentinaPlanBook()
        val trip = book.trips(today).single()
        val net = Recorded.net(connected = false)
        ui(book, Place.Trip(trip.id), web = Recorded.web(net), settings = AppSettings.ready().apply { planExpanded = true }) {
            // The first day is spent in Buenos Aires; its title opens the page around the hotel there.
            byAction("Open Buenos Aires")[0].performClick()
            waitForIdle()
            shows("Buenos Aires")
            // Its country from the countries' outlines, without the network.
            shows("Argentina")
            onNodeWithText("HOW LONG · IN YOUR PLAN").assertExists()
            onNodeWithText("days in your plan").assertExists()
            onNodeWithText("IN YOUR PLAN").performScrollTo()
            onNodeWithText("Recoleta Cemetery").assertExists()
            onNodeWithText("Add a place").assertExists()
            onNodeWithContentDescription("Back").performClick()
            waitForIdle()
            onNodeWithText("Expand").assertDoesNotExist()
            onNodeWithText("Collapse").assertExists()
        }
        assertTrue(net.asked.isEmpty())
    }

    @Test
    fun aStopsPageAddsItToThePlanOrRemovesIt() {
        val file = MemoryTripFile()
        val (book, tripId) = naxos(file)
        val at = com.justdataplease.boringtravel.time.startOfDayMs(today, null) + 10 * 3_600_000L
        book.putRows(stops = listOf(Stop("stop-1", tripId, at, at + 7_200_000L, 37.1036, 25.3766, "Chora, Naxos, Greece", "GR", createdAt = at, updatedAt = at)))
        ui(book, Place.PlacePage(tripId, stopId = "stop-1"), web = Recorded.web(Recorded.net(connected = false))) {
            onNodeWithText("Naxos").assertExists()
            onNodeWithText("Add to plan").performClick()
            shows("Added to the plan")
        }
        val added = book.items(tripId).single()
        assertEquals("Chora, Naxos, Greece", added.title)
        assertEquals(today, added.day)
        assertEquals(37.1036, added.lat!!, 1e-9)
        ui(book, Place.Trip(tripId), Place.PlacePage(tripId, stopId = "stop-1")) {
            onNodeWithContentDescription("Remove this stop").performClick()
            onNodeWithText("Remove this stop?").assertExists()
            onAllNodesWithText("Remove").onLast().performClick()
            waitForIdle()
        }
        assertNotNull(book.snapshot.stops.single().deletedAt)
        assertKeptAsABackup(file, book)
    }

    @Test
    fun anExamplesPlacePageHasTheDemoTag() {
        val (demo, kit) = demoBook()
        val item = demo.items(SampleArgentina.TRIP_ID).first { it.lat != null }
        ui(demo, Place.PlacePage(SampleArgentina.TRIP_ID, itemId = item.id), settings = AppSettings.ready().apply { demoMode = true }) {
            onNodeWithText("DEMO").assertExists()
            onNodeWithContentDescription("Share").assertExists()
        }
        assertTrue(kit.files.read(demo.snapshot.media.first().localPath) != null)
    }

    @Test
    fun aNotesPlaceIsFoundOnTheMapOrPickedFromTheTrip() {
        val book = sampleBook()
        val kyoto = book.sections(today).now!!
        val net = Recorded.net()
        runDesktopComposeUiTest(width = 440, height = 2400) {
            setContent {
                CompositionLocalProvider(LocalWeb provides Recorded.web(net)) {
                    com.justdataplease.boringtravel.ui.theme.BoringTravelTheme(com.justdataplease.boringtravel.ui.theme.Look.Default) {
                        WriteScreen(book, kyoto.id, null, today, {}, detailsAtStart = true)
                    }
                }
            }
            // The trip's own places: today's plan items by name first.
            onNodeWithText("From this trip").assertExists()
            onNodeWithText("Evening walk by the Kamo river").assertExists()
            onAllNodes(hasSetTextAction()).onLast().performTextInput("halki")
            shows("On the map")
            onAllNodesWithText("Halki")[0].performClick()
            waitForIdle()
            onNodeWithText("On the map").assertDoesNotExist()
        }
        assertEquals(1, net.asked.size)
        assertTrue(net.asked.single().url.startsWith("https://photon.komoot.io/api/?q=halki&limit=8"))
    }

    @Test
    fun aGoogleMapsListBecomesTheTripsIdeasOnce() {
        val file = MemoryTripFile()
        val (book, tripId) = naxos(file)
        val net = Recorded.net()
        ui(book, Place.Trip(tripId), Place.CopySite(tripId), web = Recorded.web(net)) {
            fields()[0].performTextInput(Recorded.LIST_SHORT)
            onNodeWithText("Read").performClick()
            shows("3 places from a Google Maps list")
            onNodeWithText("The list is longer: these are its first 3.").assertExists()
            // Opened from the trip: the places go there.
            onNodeWithText("Adds 3 places to Naxos").assertExists()
            onNodeWithText("Add 3 places").performClick()
            shows("3 places added to the plan, as ideas.")
            onNodeWithText("Done").performClick()
            waitForIdle()
        }
        val ideas = book.ideas(tripId)
        assertEquals(listOf("Casa de Comidas La Posada", "The White House", "Plaza de España"), ideas.map { it.title })
        // Each id is the Android app's, byte for byte (UUID.nameUUIDFromBytes of its name and pin).
        val posada = ideas[0]
        val key = "boringtravel-site-pin:$tripId:casa de comidas la posada@" + String.format(java.util.Locale.ROOT, "%.5f,%.5f", 37.8767624, -4.7836871)
        assertEquals(UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString(), posada.id)
        assertEquals(PlanKind.Food.id, posada.kind)
        assertEquals("Try the salmorejo", posada.note)
        assertEquals("C. San Basilio, 16, 14004 Córdoba", posada.placeName)
        assertEquals("https://maps.google.com/?cid=11832790908565240768", posada.mapsUrl)
        // The short link was followed, then the list asked for as its own page asks.
        assertTrue(net.asked.any { it.url.startsWith("https://www.google.com/maps/preview/entitylist/getlist?authuser=0&hl=en&gl=us&pb=!2e2!3e2!4i500") })
        assertKeptAsABackup(file, book)

        // The same list again adds nothing twice; one place, a blog and a list that can't be read say so.
        ui(book, Place.CopySite(tripId), web = Recorded.web(net)) {
            fields()[0].performTextInput(Recorded.LIST_SHORT)
            onNodeWithText("Read").performClick()
            shows("Add 3 places")
            onNodeWithText("Add 3 places").performClick()
            shows("No new places for the plan.")
            onNodeWithText("3 places were in the plan already.").assertExists()
        }
        assertEquals(3, book.ideas(tripId).size)
        ui(book, Place.CopySite(), web = Recorded.web(net)) {
            fields()[0].performTextInput(Recorded.YASAKA_LONG)
            onNodeWithText("Read").performClick()
            shows(MapsLists.NOT_A_LIST)
        }
        // A Tumblr blog that doesn't answer is private (round 17: blogs are read on iPhone too).
        ui(book, Place.CopySite(), web = Recorded.web(net)) {
            fields()[0].performTextInput("myblog.tumblr.com")
            onNodeWithText("Read").performClick()
            shows(com.justdataplease.boringtravel.site.SiteReader.TUMBLR_PRIVATE)
        }
        val offline = Recorded.net(connected = false)
        ui(book, Place.CopySite(), web = Recorded.web(offline)) {
            fields()[0].performTextInput(Recorded.LIST_SHORT)
            onNodeWithText("Read").performClick()
            shows(com.justdataplease.boringtravel.site.SiteImporter.MAPS_NO_ANSWER)
        }
        assertTrue(offline.asked.isEmpty())
        // A new trip named after the list when it goes nowhere else.
        ui(book, Place.CopySite(), web = Recorded.web(net)) {
            fields()[0].performTextInput(Recorded.LIST_SHORT)
            onNodeWithText("Read").performClick()
            shows("Add 3 places")
            onNodeWithText("New trip").performClick()
            onNodeWithText("Add 3 places").performClick()
            shows("3 places added to the plan, as ideas.")
        }
        val seville = book.trips(today).single { it.title == "Seville" }
        assertEquals(3, book.ideas(seville.id).size)
        assertKeptAsABackup(file, book)
    }

    @Test
    fun placePhotosAreOnUntilTurnedOffAndABackupCarriesTheChoice() {
        val fresh = AppSettings(MemorySettingsFile())
        assertTrue(fresh.placePhotos)
        assertNull(fresh.backupPreferences().placePhotos)
        fresh.restorePreferences(com.justdataplease.boringtravel.backup.BackupPreferences(placePhotos = false))
        assertEquals(false, fresh.placePhotos)
        val mine = AppSettings(MemorySettingsFile()).apply { placePhotos = true }
        mine.restorePreferences(com.justdataplease.boringtravel.backup.BackupPreferences(placePhotos = false))
        assertEquals(true, mine.placePhotos)
        assertEquals(true, mine.backupPreferences().placePhotos)
    }

    @Test
    fun placePhotosOffNeverAsksWikipedia() {
        val file = MemoryTripFile()
        val (book, tripId) = naxos(file)
        book.saveItem(book.draftItem(tripId, today, 1.0).copy(title = "Yasaka Shrine", placeName = "Yasaka Shrine, Kyoto, Japan", lat = Recorded.YASAKA.lat, lng = Recorded.YASAKA.lng))
        val settings = AppSettings.ready().apply { placePhotos = false }
        val off = Recorded.net()
        ui(book, Place.Trip(tripId), web = Recorded.web(off, placePhotos = { settings.placePhotos }), settings = settings) {
            byAction("Open Yasaka Shrine").onLast().performClick()
            waitForIdle()
        }
        assertTrue(off.asked.none { it.url.startsWith(WikiPhotos.API) })
        // On: the plan's row and the sheet show the article's photo with its credit.
        val on = Recorded.net()
        ui(book, Place.Trip(tripId), web = Recorded.web(on)) {
            byAction("Open Yasaka Shrine").onLast().performClick()
            shows("Photo: Jane Doe, CC BY-SA 4.0 · Wikipedia", timeout = 8_000)
        }
        assertTrue(on.asked.any { it.url.startsWith(WikiPhotos.API) && "imageinfo" in it.url })
        // Every Wikipedia request names the app.
        assertTrue(on.asked.filter { it.url.startsWith(WikiPhotos.API) }.all { it.headers["User-Agent"] == LinkFetcher.userAgent("1.0") })
        assertKeptAsABackup(file, book)
    }
}
