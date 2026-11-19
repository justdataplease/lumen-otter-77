package com.justdataplease.boringtravel.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.geo.Countries
import com.justdataplease.boringtravel.geo.ReverseGeocoder
import com.justdataplease.boringtravel.graph
import com.justdataplease.boringtravel.model.TrackingLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * A trip's points → spike filter → stays → suggested stops (merged with the traveller's own) → country codes and
 * place names. Runs every night and whenever a map opens. Raw points are never changed.
 */
class TrackProcessor(private val graph: AppGraph) {
    private val mutex = Mutex()
    private val countries: Countries? by lazy {
        runCatching { graph.app.assets.open(COUNTRIES_ASSET).bufferedReader().use { Countries.parse(it.readText()) } }.getOrNull()
    }

    /** Never throws: a failed run (no Geocoder, a busy database) just waits for the next one. */
    suspend fun process(tripId: String) {
        quietly { processNow(tripId) }
    }

    private suspend fun processNow(tripId: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            val trip = graph.trips.get(tripId) ?: return@withLock
            val now = System.currentTimeMillis()
            val appLevel = graph.settings.trackingLevel.first()
            val recording = trip.isCurrent(LocalDate.now()) && TrackingPlan.levelFor(trip, appLevel) != TrackingLevel.Off
            val points = SpikeFilter.filter(graph.tracks.forTrip(tripId))
            val dwells = StopDetector.detect(points, ongoingUntil = if (recording) now else null)
            val existing = graph.tracks.stopsWithDeleted(tripId)

            val changed = LinkedHashMap<String, Stop>()
            StopDetector.merge(existing, dwells, tripId, now, ::newId).forEach { changed[it.id] = it }
            val known = existing.map { it.id }.toSet()
            val live = existing.filter { it.deletedAt == null }.map { changed[it.id] ?: it } + changed.values.filter { it.id !in known }

            var lookups = 0
            for (s in live) {
                val wantsName = s.name.isBlank() && s.source == StopSource.AUTO
                if (s.countryCode != null && !wantsName) continue
                val offline = countries?.codeAt(s.lat, s.lng)
                val askGeocoder = wantsName || (s.countryCode == null && !Countries.isIsoAlpha2(offline))
                val place = if (askGeocoder && lookups < MAX_LOOKUPS) {
                    lookups++
                    ReverseGeocoder.lookup(graph.app, s.lat, s.lng)
                } else null
                var u = s
                if (u.countryCode == null) {
                    u = u.copy(countryCode = offline?.takeIf(Countries::isIsoAlpha2) ?: place?.countryCode ?: offline)
                }
                val placeName = place?.name
                if (wantsName && !placeName.isNullOrBlank()) u = u.copy(name = placeName)
                if (u != s) changed[u.id] = u
            }
            if (changed.isNotEmpty()) graph.tracks.saveStops(changed.values.toList())
        }
    }

    /** The offline country at a point, else the Geocoder's (for photos and entries later; stats read stops). */
    suspend fun countryAt(lat: Double, lng: Double): String? {
        val offline = countries?.codeAt(lat, lng)
        if (Countries.isIsoAlpha2(offline)) return offline
        return ReverseGeocoder.lookup(graph.app, lat, lng)?.countryCode ?: offline
    }

    companion object {
        const val COUNTRIES_ASSET = "geo/countries.json"
        /** Geocoder calls per run (each can take seconds and needs a network). */
        private const val MAX_LOOKUPS = 12
    }
}

/**
 * The nightly check, a few minutes after local midnight: start or stop recording for the new day, process the
 * points of today's trip and of trips that just ended, and a note if location permission went missing.
 */
class TrackProcessingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        step { graph.tracking.apply() }
        step { graph.tracking.processRecent() }
        step { graph.tracking.checkPermissionNote() }
        // The next night, appended so it runs after this one finishes.
        enqueue(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    private suspend fun step(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // One failed step (no Play services, no Geocoder) shouldn't stop the others.
        }
    }

    companion object {
        private const val NAME = "tracking-day"

        /** Makes sure the nightly check is scheduled (keeps an existing one). */
        fun schedule(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<TrackProcessingWorker>()
                .setInitialDelay(TrackingPlan.msUntilNextDay(ZonedDateTime.now()), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
        }
    }
}
