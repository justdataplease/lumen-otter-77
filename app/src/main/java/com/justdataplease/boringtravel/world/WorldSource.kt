package com.justdataplease.boringtravel.world

import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.DemoTrips
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.geo.Countries
import com.justdataplease.boringtravel.geo.Continent
import com.justdataplease.boringtravel.globe.GlobeGeometry
import com.justdataplease.boringtravel.stats.TripStats
import com.justdataplease.boringtravel.tracking.TrackProcessor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** What another screen asks the Map tab to show next. */
sealed interface MapRequest {
    /** Routes, framed on the traveller's own places in [code]. */
    data class Routes(val code: String) : MapRequest
    /** World, turned to a country (and its sheet open). */
    data class Country(val code: String) : MapRequest
    /** World, turned to a continent (and its sheet open). */
    data class ContinentView(val continent: Continent) : MapRequest
}

/**
 * The traveller's world for every screen that shows it (globe, Your world, the Trips strip, flags on trips): computed
 * off the main thread from the live rows and recomputed when any of them change, shared while anyone watches.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class WorldSource(private val graph: AppGraph) {
    private val countriesJob: Deferred<Countries?> = graph.scope.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
        runCatching { graph.app.assets.open(TrackProcessor.COUNTRIES_ASSET).bufferedReader().use { Countries.parse(it.readText()) } }.getOrNull()
    }
    private val geometryJob: Deferred<GlobeGeometry?> = graph.scope.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
        countriesJob.await()?.let(GlobeGeometry::from)
    }

    /** The bundled outlines (parsed once, on first use). */
    suspend fun countries(): Countries? = countriesJob.await()

    /** The globe's unit-vector geometry (built once, on first use). */
    suspend fun geometry(): GlobeGeometry? = geometryJob.await()

    /** Offline lookups by ~1 km cell; the same photo spot or stop is looked up once per run of the app. */
    private val lookups = ConcurrentHashMap<Long, String>()

    private fun codeAt(countries: Countries?, lat: Double, lng: Double): String? {
        if (countries == null) return null
        val cell = WorldModel.cell(lat, lng)
        lookups[cell]?.let { return it.ifEmpty { null } }
        val code = countries.codeAt(lat, lng)
        lookups[cell] = code.orEmpty()
        return code
    }

    /** Route length per trip; a trip that has ended keeps its number until it changes. */
    private val meters = ConcurrentHashMap<String, Pair<Long, Double>>()

    private suspend fun metersFor(trips: List<Trip>, today: LocalDate): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (t in trips) {
            if (t.startDate?.isAfter(today) != false) continue
            val cached = meters[t.id]
            if (cached != null && cached.first == t.updatedAt && t.isPast(today)) { out[t.id] = cached.second; continue }
            val m = TripStats.meters(graph.tracks.forTrip(t.id))
            meters[t.id] = t.updatedAt to m
            out[t.id] = m
        }
        return out
    }

    // Only the side on show counts (demo mode: the examples, else the traveller's own trips; see DemoTrips).
    private val inputs = combine(
        combine(graph.trips.observeTrips(), graph.tracks.observeAllStops(), graph.journal.observeEntries()) { t, s, e -> Triple(t, s, e) },
        combine(graph.media.observeAll(), graph.db.planItems().observeAllLive(), graph.countries.observe()) { m, p, k -> Triple(m, p, k) },
        graph.settings.demoMode,
    ) { a, b, demo ->
        DemoTrips.visibleWorld(WorldInput(trips = a.first, stops = a.second, entries = a.third, media = b.first, planItems = b.second, marks = b.third), demo)
    }

    val world: StateFlow<World?> = inputs
        .debounce(150)
        .mapLatest { input ->
            val countries = countries()
            val today = LocalDate.now()
            val withMeters = input.copy(metersByTrip = metersFor(input.trips, today))
            WorldModel.build(withMeters, today, ZoneId.systemDefault(), System.currentTimeMillis()) { lat, lng -> codeAt(countries, lat, lng) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(graph.scope, SharingStarted.WhileSubscribed(15_000), null)

    /** Set by any screen, read (and cleared) by the Map tab. */
    val mapRequest = MutableStateFlow<MapRequest?>(null)

    /** The country at a point, from the offline outlines only (taps on the globe). */
    suspend fun countryAt(lat: Double, lng: Double, nearbyM: Double = 0.0): String? = countries()?.codeAt(lat, lng, nearbyM)
}
