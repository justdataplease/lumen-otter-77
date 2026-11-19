package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.backup.Snapshot
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.geo.Continent
import com.justdataplease.boringtravel.geo.Countries
import com.justdataplease.boringtravel.stats.TripStats
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.world.World
import com.justdataplease.boringtravel.world.WorldInput
import com.justdataplease.boringtravel.world.WorldModel

/** What another screen asks the Map tab to show next (the Android app's world/WorldSource.kt MapRequest). */
sealed interface MapRequest {
    /** Routes, framed on the traveller's own places in [code]. */
    data class Routes(val code: String) : MapRequest
    /** World, turned to a country (and its sheet open). */
    data class Country(val code: String) : MapRequest
    /** World, turned to a continent (and its sheet open). */
    data class ContinentView(val continent: Continent) : MapRequest
}

/**
 * The traveller's world for every screen that shows it (the globe, Your world, the flags on Trips, the trip's header
 * and the Calendar), as the Android app's world/WorldSource.kt: worked out from the rows on show ([DemoTrips.visibleWorld])
 * with the bundled outlines, again whenever the trips change. Android computes it off the main thread from its
 * database's flows; here the book is one text in memory, so it is worked out when a screen asks and kept until the
 * rows, demo mode or the day change.
 */
class WorldSource(private val countries: () -> Countries? = { CountryOutlines.loaded }) {
    /** Offline lookups by ~1 km cell; the same photo spot or stop is looked up once per run of the app. */
    private val lookups = HashMap<Long, String>()
    private var lastRows: Snapshot? = null
    private var lastKey: Triple<Boolean, Long, Boolean>? = null
    private var lastWorld: World? = null

    /** Set by any screen, read (and cleared) by the Map tab. */
    var mapRequest by mutableStateOf<MapRequest?>(null)

    fun codeAt(lat: Double, lng: Double): String? {
        val c = countries() ?: return null
        val cell = WorldModel.cell(lat, lng)
        lookups[cell]?.let { return it.ifEmpty { null } }
        val code = c.codeAt(lat, lng)
        lookups[cell] = code.orEmpty()
        return code
    }

    /** The world of [book]'s rows on show in [demoMode], on [today]. */
    fun world(book: TripBook, demoMode: Boolean, today: LocalDate, now: Long = nowOn(today)): World {
        val rows = book.snapshot
        val key = Triple(demoMode, today.toEpochDay(), countries() != null)
        lastWorld?.let { if (rows === lastRows && key == lastKey) return it }
        val input = DemoTrips.visibleWorld(
            WorldInput(
                trips = rows.trips, stops = rows.stops, entries = rows.entries, media = rows.media,
                planItems = rows.planItems, marks = rows.countryMarks, metersByTrip = meters(rows, today),
            ),
            demoMode,
        )
        val world = WorldModel.build(input, today, null, now, ::codeAt)
        lastRows = rows
        lastKey = key
        lastWorld = world
        return world
    }

    /** Route length per trip started by [today] (TripStats.meters over its points). */
    private fun meters(rows: Snapshot, today: LocalDate): Map<String, Double> {
        val points = rows.trackPoints.groupBy { it.tripId }
        return rows.trips.filter { t -> t.deletedAt == null && t.startDate?.let { it.toEpochDay() <= today.toEpochDay() } == true }
            .associate { it.id to TripStats.meters(points[it.id].orEmpty()) }
    }

    /** The country at a point, from the offline outlines only (taps on the globe). */
    suspend fun countryAt(lat: Double, lng: Double, nearbyM: Double = 0.0): String? = CountryOutlines.load()?.codeAt(lat, lng, nearbyM)
}

/** The app's one [WorldSource]; a screen drawn on its own (previews) gets one of its own. */
val LocalWorld = staticCompositionLocalOf { WorldSource() }

/**
 * The world for a screen of [book], read again when [revision] says the trips changed. The outlines are read the
 * first time (they are read at start too, so this is only the first frame of a preview without them).
 */
@Composable
fun rememberWorld(book: TripBook, revision: Int, demoMode: Boolean, today: LocalDate): World {
    val source = LocalWorld.current
    val loaded by produceState(CountryOutlines.loaded != null) { if (!value) value = CountryOutlines.load() != null }
    return remember(source, book, revision, demoMode, today, loaded, book.snapshot) { source.world(book, demoMode, today) }
}
