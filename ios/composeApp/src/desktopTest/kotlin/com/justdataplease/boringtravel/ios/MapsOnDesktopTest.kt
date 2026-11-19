package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.clip.MapStyleJson
import com.justdataplease.boringtravel.clip.MapTint
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.geo.BBox
import com.justdataplease.boringtravel.geo.Geo
import com.justdataplease.boringtravel.geo.GeoPoint
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.tracking.StopSource
import com.justdataplease.boringtravel.ui.map.LegSheetModel
import com.justdataplease.boringtravel.ui.map.MapData
import com.justdataplease.boringtravel.ui.map.MapMark
import com.justdataplease.boringtravel.ui.map.MapTap
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.ui.theme.BtLooks
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The maps driven on the desktop's drawn stand-in, on recorded answers (OpenFreeMap's style as the Android app's tests
 * record it): the Map tab and a trip's map, their pins and the planned route with its legs, Travelled · Planned, the
 * leg sheet stepping through the days, a pin opening its place page, the map's looks kept as Android keeps them, the
 * style MapLibre gets, the camera's rules, and recorded points becoming stops that are kept in data.json.
 */
@OptIn(ExperimentalTestApi::class)
class MapsOnDesktopTest {
    init {
        runBlocking { BtFontFiles.load() }
        MapBaseStyles.forget()
    }

    private fun ui(
        book: TripBook, settings: AppSettings = AppSettings.ready(), web: Web = Recorded.web(), today: com.justdataplease.boringtravel.time.LocalDate = SAMPLE_TODAY,
        vararg at: Place, block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 440, height = 1100) {
        setContent { BoringTravelApp(book, settings, today, startAt = at.toList(), version = "1.0", web = web) }
        waitForIdle()
        block()
        waitForIdle()
    }

    private fun ComposeUiTest.waitFor(description: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.has(text: String) = onAllNodes(hasText(text, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty()

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    @Test
    fun theLegSheetIsAndroidsAndStepsThroughTheTrip() {
        val book = patagoniaBook()
        val trip = book.trips(SAMPLE_TODAY).single()
        // The cards the Android app's LegSheetModelTest checks, on the same plan: three legs between places.
        val cards = LegSheetModel.cards(trip, book.items(trip.id))
        assertEquals(listOf("Day 13 · Leg 1 of 3", "Day 13 · Leg 2 of 3", "Day 17 · Leg 3 of 3"), cards.map { it.eyebrow })
        assertEquals("About 2 h 30 by road", cards[1].note)
        assertEquals(1, LegSheetModel.currentIndex(cards, SAMPLE_TODAY))
        ui(book, at = arrayOf(Place.Trip(trip.id, TripTab.Map))) {
            // Day 14: the sheet opens on the leg reached by today.
            onNodeWithText("Day 13 · Leg 2 of 3", ignoreCase = true).assertExists()
            onNodeWithText("El Calafate → El Chaltén").assertExists()
            onNodeWithText("About 2 h 30 by road").assertExists()
            onNodeWithContentDescription("Next leg").performClick()
            onNodeWithText("Day 17 · Leg 3 of 3", ignoreCase = true).assertExists()
            onNodeWithContentDescription("Next leg").performClick()
            onNodeWithText("Day 17 · Leg 3 of 3", ignoreCase = true).assertExists()
            onNodeWithContentDescription("Previous leg").performClick()
            onNodeWithContentDescription("Previous leg").performClick()
            onNodeWithText("Day 13 · Leg 1 of 3", ignoreCase = true).assertExists()
            onNodeWithText("Bariloche → El Calafate").assertExists()
            // Its title opens the destination's place page.
            onNodeWithText("Bariloche → El Calafate").performClick()
            waitForIdle()
            assertTrue(has("El Calafate"))
            onNodeWithText("In your plan", ignoreCase = true).assertExists()
        }
    }

    @Test
    fun thePlannedRouteAndItsPinsAreThePlansOwn() {
        val book = patagoniaBook()
        val trip = book.trips(SAMPLE_TODAY).single()
        val view = TripMapBoardModel.build(book, trip, nowOn(SAMPLE_TODAY))
        val planned = TripMapBoardModel.shown(view, planned = true)
        // Every located item a flag pin, in plan order; the dashed route through the plan's stops (the lookout is
        // 700 m from the transfer: its own stop), as the Android app's PlannedReplay builds it.
        assertEquals(listOf("bariloche", "calafate", "chalten", "mirador", "back"), planned.plans.map { it.id })
        assertEquals(RouteLegs.waypoints(trip, book.items(trip.id)).map { GeoPoint(it.lat, it.lng) }, planned.planned)
        assertEquals(5, planned.planned.size)
        assertTrue(planned.routes.isEmpty() && planned.stops.isEmpty())
        // Travelled: nothing recorded, no stops, so no route of its own; the plan still dashed underneath.
        val travelled = TripMapBoardModel.shown(view, planned = false)
        assertTrue(travelled.routes.isEmpty() && travelled.plans.isEmpty())
        assertEquals(planned.planned, travelled.planned)
        assertFalse(view.hasTrack)

        ui(book, at = arrayOf(Place.Trip(trip.id, TripTab.Map))) {
            onNode(isTab and hasText("Travelled")).assertIsSelected()
            onNode(isTab and hasText("Planned")).performClick()
            onNode(isTab and hasText("Planned")).assertIsSelected()
            waitFor("Fly to Bariloche")
            // Where pins would cover each other, MapLibre shows the first: the lookout hides behind the transfer.
            assertTrue(onAllNodesWithContentDescription("Transfer to El Chaltén").fetchSemanticsNodes().isNotEmpty())
            assertTrue(onAllNodesWithContentDescription("Mirador de los Cóndores").fetchSemanticsNodes().isEmpty())
            // A plan pin opens its place page.
            onNodeWithContentDescription("Fly to Bariloche").performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            assertTrue(has("Bariloche"))
            onNodeWithText("In your plan", ignoreCase = true).assertExists()
        }
    }

    @Test
    fun travelledShowsTheStopsJoinedAndAStopOpensItsPage() {
        val (demo, kit) = demoBook()
        val argentina = SampleArgentina.TRIP_ID
        val stops = demo.snapshot.stops.filter { it.tripId == argentina && it.deletedAt == null }
        runDesktopComposeUiTest(width = 440, height = 1100) {
            setContent {
                BoringTravelApp(demo, AppSettings.ready().apply { demoMode = true }, SAMPLE_TODAY, startAt = listOf(Place.Trip(argentina, TripTab.Map)), media = kit, web = Recorded.web())
            }
            waitFor("El Chaltén")
            // Without a recorded route, the stops are joined in time order; today's one stop, no distance to tell.
            onNodeWithText("1 stop today").assertExists()
            for (s in stops) assertTrue(onAllNodesWithContentDescription(s.name).fetchSemanticsNodes().isNotEmpty(), s.name)
            // Planned: the plan's places, not the stops.
            onNode(isTab and hasText("Planned")).performClick()
            waitForIdle()
            assertTrue(onAllNodesWithContentDescription("El Chaltén").fetchSemanticsNodes().isEmpty())
            onNode(isTab and hasText("Travelled")).performClick()
            waitFor("Lake Nahuel Huapi, Bariloche")
            onNodeWithContentDescription("Lake Nahuel Huapi, Bariloche").performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            // The stop's own page, named by its area, with Remove (it is not a plan item).
            assertTrue(has("Bariloche"))
            onNodeWithContentDescription("Remove this stop", substring = true).assertExists()
        }
        val view = TripMapBoardModel.build(demo, demo.trip(argentina)!!, nowOn(SAMPLE_TODAY))
        assertEquals(1, view.data.routes.size)
        assertEquals(RouteLegs.orderedStops(demo.trip(argentina)!!, stops).map { GeoPoint(it.lat, it.lng) }, view.data.routes.single().pieces.single().points)
    }

    @Test
    fun theMapTabOpensOnTodaysTripAndSwitchesToAllTrips() {
        val book = sampleBook()
        val current = book.sections(SAMPLE_TODAY).now!!
        val settings = AppSettings.ready().apply { mapView = MapView.Map }
        ui(book, settings, at = arrayOf(Place.Home(HomeTab.Map))) {
            // Map, as picked: today's trip, its name over "Map".
            onNodeWithText(current.title, ignoreCase = true).assertExists()
            onNode(isTab and hasText("Travelled")).assertExists()
            onNodeWithContentDescription("All trips").performClick()
            waitForIdle()
            // No trip has a recorded route, a stop or a located note yet.
            onNodeWithText("No routes yet").assertExists()
            onNodeWithText("Your trips' routes, stops and places show up here.").assertExists()
            onNodeWithContentDescription("Today's trip").performClick()
            onNode(isTab and hasText("Travelled")).assertExists()
            // The globe: kept as the choice, under the backup's name.
            onNode(hasText("Globe") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
            assertTrue(onAllNodesWithText("Places I've been").fetchSemanticsNodes().isNotEmpty())
        }
        assertEquals(MapView.Globe, settings.mapView)
        assertEquals("globe", settings.backupPreferences().mapView)
    }

    @Test
    fun allTripsDrawEveryRouteAndARouteOpensItsTripsMap() {
        val book = sampleBook()
        val current = book.sections(SAMPLE_TODAY).now!!
        val t0 = startOfDayMs(SAMPLE_TODAY, null) + 9 * 3_600_000L
        // A walk along the Kamo river, a point a minute: about 2.7 km.
        withPoints(book, current.id, (0..30).map { i -> Triple(t0 + i * 60_000L, 35.0040 + i * 0.0008, 135.7720) })
        val view = AllTripsMapModel.build(book, TripsHomeData.visibleTrips(book, false), demoMode = false)
        assertEquals(1, view.data.routes.size)
        assertEquals("1 trip · 2.7 km", view.summary)
        val settings = AppSettings.ready().apply { mapView = MapView.Map }
        ui(book, settings, at = arrayOf(Place.Home(HomeTab.Map))) {
            onNodeWithContentDescription("All trips").performClick()
            waitForIdle()
            onNodeWithText("1 trip · 2.7 km", ignoreCase = true).assertExists()
            onNodeWithContentDescription("Show everything").assertExists()
        }
        // A tap on the route reaches it (16 points around the finger), as BtMap's does.
        val p = MapProjection(MapFit.camera(view.data.bounds!!, 400f, 600f, MapFit.BASE), 400f, 600f)
        val mid = view.data.routes.single().pieces.single().points.let { it[it.size / 2] }
        assertEquals(MapTap.Trip(current.id), MapHits.hit(view.data, MapPins.placed(view.data, p), p, p.x(mid.lng) + 10f, p.y(mid.lat)))
        assertNull(MapHits.hit(view.data, MapPins.placed(view.data, p), p, p.x(mid.lng) + 60f, p.y(mid.lat)))
    }

    @Test
    fun theTripMapsLookIsKeptAsAndroidKeepsIt() {
        val book = patagoniaBook()
        val trip = book.trips(SAMPLE_TODAY).single()
        val stored = MemorySettingsFile(mapOf("mapStyle" to """{"clip":{"t":"s","v":"globe"}}"""))
        val settings = AppSettings(stored).apply { onboardingDone = true }
        assertEquals(MapStyle.Street, settings.tripMapStyle)
        ui(book, settings, at = arrayOf(Place.Trip(trip.id, TripTab.Map))) {
            onNodeWithContentDescription("Map style").performClick()
            onNode(isTab and hasText("Terrain")).performClick()
            onNode(isTab and hasText("Terrain")).assertIsSelected()
        }
        assertEquals(MapStyle.Terrain, settings.tripMapStyle)
        // The phone's "map_style" file as text, the trip video's choice in it untouched.
        assertEquals("""{"clip":{"t":"s","v":"globe"},"trip_map":{"t":"s","v":"terrain"}}""", stored.read("mapStyle"))
        assertEquals(stored.read("mapStyle"), settings.backupPreferences().mapStyle)
        assertEquals(MapStyle.Terrain, AppSettings(stored).tripMapStyle)
        // The globe is the video's alone: a trip map never takes it.
        assertEquals(MapStyle.Street, MapStylePrefs.tripMap("""{"trip_map":{"t":"s","v":"globe"}}"""))
    }

    @Test
    fun offlineTheFirstTimeTheMapIsThePlainLandWithTryAgain() {
        val book = patagoniaBook()
        val trip = book.trips(SAMPLE_TODAY).single()
        MapBaseStyles.forget()
        ui(book, web = Recorded.web(Recorded.net(connected = false)), at = arrayOf(Place.Trip(trip.id, TripTab.Map))) {
            onNodeWithText("Map unavailable · showing saved places").assertExists()
            onNodeWithText("Try again").assertExists()
            // The plan is still drawn on it.
            onNode(isTab and hasText("Planned")).performClick()
            waitFor("Fly to Bariloche")
        }
        // Seen once online, it builds offline from the copy kept in the caches.
        val files = MemoryMediaFiles()
        val online = Web(Recorded.net(), cache = files)
        runBlocking { assertNotNull(MapBaseStyles.base(online, dark = false)) }
        MapBaseStyles.forget()
        val offline = Web(Recorded.net(connected = false), cache = files)
        assertTrue(MapBaseStyles.isStyle(runBlocking { MapBaseStyles.base(offline, dark = false) }!!))
        MapBaseStyles.forget()
    }

    @Test
    fun theStyleIsOpenFreeMapsInTheLooksColoursWithTheAppsLayersOnTop() {
        val c = MapColors(land = 0xFFF4EBDD.toInt(), sea = 0xFF9CC9D6.toInt(), route = 0xFFE4572E.toInt(), surface = 0xFFFFFFFF.toInt(), ink = 0xFF1E2A3A.toInt(), muted = 0xFF7A7A7A.toInt(), park = 0xFFE9E1D4.toInt(), plan = 0xFF3B6EA8.toInt())
        val base = Recorded.openFreeMapStyle(dark = false)
        for (style in MapStyle.tripMap) {
            val built = MapStyles.build(style, base, dark = false, c, stopNames = true)
            assertFalse(built.offline)
            val root = Json.parseToJsonElement(built.json).jsonObject
            val layers = root["layers"]!!.jsonArray.map { it.jsonObject }
            val ids = layers.map { it["id"]!!.jsonPrimitive.content }
            // The app's layers last, in BtMap's order.
            assertEquals(
                listOf("bt-planned-line", "bt-route-jump", "bt-route", "bt-plans", "bt-stops", "bt-stop-names", "bt-entries", "bt-me-halo", "bt-me"),
                ids.takeLast(9),
            )
            val sources = root["sources"]!!.jsonObject
            for (s in MapLayers.SOURCES) assertEquals("geojson", sources[s]!!.jsonObject["type"]!!.jsonPrimitive.content)
            fun paint(id: String) = layers.first { it["id"]!!.jsonPrimitive.content == id }["paint"]!!.jsonObject
            assertEquals(MapTint.hex(c.route), paint("bt-route")["line-color"]!!.jsonPrimitive.content)
            assertEquals(MapTint.hex(c.plan), paint("bt-planned-line")["line-color"]!!.jsonPrimitive.content)
            when (style) {
                // The street map recoloured as BtMap.recolour does: land, water, parks, names.
                MapStyle.Street -> {
                    assertEquals("#F4EBDD", paint("background")["background-color"]!!.jsonPrimitive.content)
                    assertEquals("#9CC9D6", paint("water")["fill-color"]!!.jsonPrimitive.content)
                    assertEquals("#E9E1D4", paint("landcover_wood")["fill-color"]!!.jsonPrimitive.content)
                    assertEquals("#1E2A3A", paint("label_city")["text-color"]!!.jsonPrimitive.content)
                }
                MapStyle.Terrain -> assertTrue(MapStyleJson.HILLSHADE_LAYER in ids && sources.containsKey(MapStyleJson.DEM_SOURCE))
                else -> {
                    assertEquals(MapStyleJson.EARTH_BACKGROUND, ids.first())
                    assertTrue(sources.containsKey(MapStyleJson.IMAGERY_SOURCE))
                }
            }
        }
        assertEquals(16.0, MapStyles.maxZoom(MapStyle.Earth))
        // Without OpenFreeMap's style: the plain land, no glyphs and so no names; Earth is its pictures alone.
        val plain = MapStyles.build(MapStyle.Street, null, dark = false, c, stopNames = true)
        assertTrue(plain.offline)
        assertFalse("bt-stop-names" in plain.json)
        assertTrue(""""background-color":"#F4EBDD"""" in plain.json)
        assertFalse(MapStyles.build(MapStyle.Earth, null, dark = false, c, stopNames = true).offline)
        // The pins MapLibre gets on iPhone: PNGs at the screen's scale, a stop's 30 x 38 points.
        val png = MapPictures.png(PinKind.Stop, c, 3f)
        val decoded = org.jetbrains.skia.Image.makeFromEncoded(png)
        assertEquals(90 to 114, decoded.width to decoded.height)
        assertEquals(66 to 87, org.jetbrains.skia.Image.makeFromEncoded(MapPictures.png(PinKind.Entry, c, 3f)).let { it.width to it.height })
        // The trip map's colours are the look's (BtMap's MapColors), and each look's differ.
        val trail = MapColors.from(BtLooks.of(Look.Trail).colors)
        val pop = MapColors.from(BtLooks.of(Look.PostcardPop).colors)
        assertTrue(trail.land != pop.land)
    }

    @Test
    fun theCameraFitsAndThePinsAndTapsFollowMapLibresRules() {
        // A box fits inside the free part of the map, the zoom 15 at most.
        val box = BBox(-50.34, -72.89, -41.15, -71.15)
        val insets = MapFit.BASE + MapInsets(top = 56f, bottom = 220f)
        val cam = MapFit.camera(box, 400f, 800f, insets)
        val p = MapProjection(cam, 400f, 800f)
        assertTrue(p.x(box.minLng) >= insets.left - 0.5f && p.x(box.maxLng) <= 400f - insets.right + 0.5f)
        assertTrue(p.y(box.maxLat) >= insets.top - 0.5f && p.y(box.minLat) <= 800f - insets.bottom + 0.5f)
        // It fills one side of the free part exactly.
        val freeH = 800f - insets.top - insets.bottom
        assertEquals(freeH.toDouble(), (p.y(box.minLat) - p.y(box.maxLat)).toDouble(), 1.0)
        // One point: 1.5 km all round, never closer than zoom 15.
        val one = MapFit.camera(BBox(37.1, 25.37, 37.1, 25.37), 400f, 800f, MapFit.BASE)
        assertTrue(one.zoom <= MapFit.MAX_FIT_ZOOM)
        val q = MapProjection(one, 400f, 800f)
        val span = Geo.distanceM(37.1, 25.37, 37.1, 25.37 + 1.0) * (q.x(25.37 + 1.0) - q.x(25.37)).let { 1.0 / it }
        assertTrue(span * (400f - 96f) >= 2 * MapFit.POINT_SPAN_M * 0.99)
        // The world is 512 points wide at zoom 0, twice that each step.
        assertEquals(512.0 * 2.0.pow(3), MapProjection(MapCamera(0.0, 0.0, 3.0), 100f, 100f).scale, 1e-9)

        // Two pins at one spot: the stop shows (its layer is higher), the plan place under it doesn't; notes always.
        fun mark(id: String) = MapMark(id, "t", 37.1, 25.37, id)
        val data = MapData(stops = listOf(mark("stop")), plans = listOf(mark("plan")), entries = listOf(mark("note")))
        val at = MapProjection(MapCamera(37.1, 25.37, 12.0), 400f, 800f)
        val pins = MapPins.placed(data, at)
        assertEquals(listOf("stop", "note"), pins.map { it.mark.id })
        // A tap on the spot: the note first, as BtMap asks the layers.
        assertEquals(MapTap.Entry("note"), MapHits.hit(data, pins, at, 200f, 390f))
        assertEquals(MapTap.StopMark("stop", "t"), MapHits.hit(data.copy(entries = emptyList()), MapPins.placed(data.copy(entries = emptyList()), at), at, 200f, 390f))
        // Pins far enough apart both show (a stop's picture, 30 x 38, with 6 points of padding).
        val apart = MapData(stops = listOf(mark("stop")), plans = listOf(mark("plan").copy(lng = 25.37 + 60.0 / at.scale * 360.0)))
        assertEquals(listOf("stop", "plan"), MapPins.placed(apart, at).map { it.mark.id })
    }

    @Test
    fun recordedPointsBecomeStopsWithANameAndACountryAndAreKept() {
        val file = MemoryTripFile()
        var clock = 1_795_000_000_000L
        val book = TripBook(file, now = { clock += 1000; clock })
        val trip = book.newTrip("Naxos", SAMPLE_TODAY.minusDays(2), SAMPLE_TODAY.plusDays(3))
        val lunch = book.saveItem(book.draftItem(trip.id, SAMPLE_TODAY, 1.0).copy(title = "Lunch in Halki", lat = 37.0587, lng = 25.4807))!!
        val beach = book.saveItem(book.draftItem(trip.id, SAMPLE_TODAY, 2.0).copy(title = "Plaka beach", lat = 37.0480, lng = 25.3790))!!
        val t0 = startOfDayMs(SAMPLE_TODAY, null) + 12 * 3_600_000L
        // An hour at Halki's square, a point every two minutes, then the bus away.
        val stay = (0..30).map { i -> Triple(t0 + i * 120_000L, 37.0587 + (if (i % 2 == 0) 1 else -1) * 0.0002, 25.4807) }
        val away = (1..5).map { i -> Triple(t0 + 3_600_000L + i * 120_000L, 37.0587 + i * 0.007, 25.4807) }
        withPoints(book, trip.id, stay + away)
        val named = GeoAddress(locality = "Chalki", countryCode = "gr")
        val processor = TrackProcessor(book, FixedLocation(LatLng(0.0, 0.0), named), now = { t0 + 5 * 3_600_000L }, ids = { "stop-1" })
        assertTrue(runBlocking { processor.process(trip.id) })
        val stop = book.snapshot.stops.single()
        assertEquals(StopSource.AUTO, stop.source)
        assertEquals("Chalki", stop.name)
        // The country from the bundled outlines first.
        assertEquals("GR", stop.countryCode)
        assertEquals(t0, stop.arrivedAt)
        // Nothing new the second time.
        assertFalse(runBlocking { processor.process(trip.id) })
        // Kept with the trips, as a backup's data.json that Android reads.
        val data = BackupJson.decodeFromString(BackupData.serializer(), file.read()!!)
        assertEquals(book.snapshot, data.toSnapshot())
        assertEquals("Chalki", data.stops.single().name)

        // Opening the plan ticks what the route passed that day, within 200 m: lunch, not the beach.
        ui(book, at = arrayOf(Place.Trip(trip.id))) { waitForIdle() }
        assertTrue(book.item(lunch.id)!!.done)
        assertFalse(book.item(beach.id)!!.done)

        // The trip's map: the route and the stop, today's distance and stops.
        ui(book, at = arrayOf(Place.Trip(trip.id, TripTab.Map))) {
            waitFor("Chalki")
            onNodeWithText("km today · 1 stop", substring = true).assertExists()
        }
        val view = TripMapBoardModel.build(book, book.trip(trip.id)!!, t0 + 5 * 3_600_000L)
        assertEquals(1, view.data.routes.size)
        assertEquals(listOf("Chalki"), view.data.stops.map { it.title })
    }

    @Test
    fun recentTripsAreTheOnesOnNowOrJustEnded() {
        val book = sampleBook()
        book.newTrip("Last week", SAMPLE_TODAY.minusDays(9), SAMPLE_TODAY.minusDays(2))
        book.newTrip("Last month", SAMPLE_TODAY.minusDays(40), SAMPLE_TODAY.minusDays(30))
        val recent = TrackProcessor.recent(book.snapshot.trips, SAMPLE_TODAY).map { it.title }
        assertTrue("Last week" in recent)
        assertFalse("Last month" in recent)
        assertFalse("Iceland ring road" in recent)
        assertTrue(book.sections(SAMPLE_TODAY).now!!.title in recent)
    }

    @Test
    fun aPhoneWithoutAMapSaysSo() {
        runDesktopComposeUiTest(width = 440, height = 1100) {
            setContent {
                CompositionLocalProvider(LocalMapKit provides null) {
                    BoringTravelApp(patagoniaBook(), AppSettings.ready().apply { mapView = MapView.Map }, SAMPLE_TODAY, startAt = listOf(Place.Home(HomeTab.Map)), version = "1.0")
                }
            }
            onNodeWithText("Map not available").assertExists()
            onNodeWithText("This phone can't draw the map.").assertExists()
        }
    }

}
