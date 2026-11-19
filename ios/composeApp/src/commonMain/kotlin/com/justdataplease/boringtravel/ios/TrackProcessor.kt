package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.geo.Countries
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.tracking.SpikeFilter
import com.justdataplease.boringtravel.tracking.StopDetector
import com.justdataplease.boringtravel.tracking.StopSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A trip's points → spike filter → stays → suggested stops (merged with the traveller's own) → country codes and place
 * names, as the Android app's tracking/TrackProcessor.kt does it, on the engine's trip store: whenever a map opens, and
 * for today's trip and those that ended in the last three days when the app starts and in the nightly run
 * ([RouteTracking.nightly]: a BGProcessingTask on iPhone, when the phone gives it time). Raw points are never changed;
 * the stops are written with the book's rows ([TripBook.putRows]), so they go into data.json as Android's do.
 *
 * [location]'s geocoder names a stop (CLGeocoder on iPhone, at most [MAX_LOOKUPS] a run); [countries] are the bundled
 * outlines, asked first.
 */
class TrackProcessor(
    private val book: TripBook,
    private val location: PhoneLocation,
    private val countries: suspend () -> Countries? = { CountryOutlines.load() },
    private val now: () -> Long = ::nowMs,
    private val ids: () -> String = ::newId,
    /** Me › Route tracking's level for every trip (a stay stays open while today's trip records). */
    private val levelNow: () -> TrackingLevel = { TrackingLevel.Off },
) {
    private val mutex = Mutex()

    /** Never throws: a failed run (no geocoder, nothing to read) just waits for the next one. True when stops changed. */
    suspend fun process(tripId: String, appLevel: TrackingLevel = levelNow()): Boolean = try {
        processNow(tripId, appLevel)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * Today's trip and those that ended in the last three days (the Android app's TrackingController.processRecent);
     * [appLevel] is Me › Route tracking's level (a stay stays open while today's trip records).
     */
    suspend fun processRecent(today: LocalDate, appLevel: TrackingLevel = levelNow()): Boolean {
        var changed = false
        for (t in recent(book.snapshot.trips, today)) if (process(t.id, appLevel)) changed = true
        return changed
    }

    private suspend fun processNow(tripId: String, appLevel: TrackingLevel): Boolean = mutex.withLock {
        val trip = book.trip(tripId) ?: return@withLock false
        val t = now()
        val snapshot = book.snapshot
        val existing = snapshot.stops.filter { it.tripId == tripId }
        val raw = snapshot.trackPoints.filter { it.tripId == tripId }
        val recording = trip.isCurrent(localDateAt(t, null)) && levelFor(trip, appLevel) != TrackingLevel.Off
        val changed = withContext(Dispatchers.Default) {
            val points = SpikeFilter.filter(raw)
            val dwells = StopDetector.detect(points, ongoingUntil = if (recording) t else null)
            LinkedHashMap<String, Stop>().apply { StopDetector.merge(existing, dwells, tripId, t, ids).forEach { put(it.id, it) } }
        }
        val known = existing.map { it.id }.toSet()
        val live = existing.filter { it.deletedAt == null }.map { changed[it.id] ?: it } + changed.values.filter { it.id !in known }
        val outlines = countries()
        var lookups = 0
        for (s in live) {
            val wantsName = s.name.isBlank() && s.source == StopSource.AUTO
            if (s.countryCode != null && !wantsName) continue
            val offline = outlines?.codeAt(s.lat, s.lng)
            val askGeocoder = wantsName || (s.countryCode == null && !isIsoAlpha2(offline))
            val place = if (askGeocoder && lookups < MAX_LOOKUPS) {
                lookups++
                runCatching { location.address(s.lat, s.lng, GEOCODER_MS) }.getOrNull()
            } else null
            var u = s
            if (u.countryCode == null) u = u.copy(countryCode = offline?.takeIf(::isIsoAlpha2) ?: place?.countryCode?.uppercase() ?: offline)
            val placeName = place?.let(GeoNames::pageName)
            if (wantsName && !placeName.isNullOrBlank()) u = u.copy(name = placeName)
            if (u != s) changed[u.id] = u
        }
        if (changed.isEmpty()) return@withLock false
        book.putRows(stops = changed.values.toList())
        true
    }

    companion object {
        /** Geocoder calls per run (each can take seconds and needs a network). */
        const val MAX_LOOKUPS = 12

        /** How long one geocoder answer is waited for (Android's ReverseGeocoder). */
        const val GEOCODER_MS = 6_000L

        /** A trip's own level wins over the app's (the Android app's TrackingPlan.levelFor). */
        fun levelFor(trip: Trip, appLevel: TrackingLevel): TrackingLevel = trip.trackingLevel?.let { TrackingLevel.of(it) } ?: appLevel

        fun isIsoAlpha2(code: String?): Boolean = code != null && code.length == 2 && code.all { it in 'A'..'Z' }

        /** Dated trips on now or ended at most three days before [today]. */
        fun recent(trips: List<Trip>, today: LocalDate): List<Trip> = trips.filter { t ->
            val s = t.startDate
            val e = t.endDate
            t.deletedAt == null && s != null && e != null && today.toEpochDay() >= s.toEpochDay() && today.toEpochDay() <= e.toEpochDay() + 3
        }
    }
}
