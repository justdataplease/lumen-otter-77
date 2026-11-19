package com.justdataplease.boringtravel.data

import androidx.room.withTransaction
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.art.DemoArt
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.repo.PhotoFiles
import com.justdataplease.boringtravel.data.repo.now
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * The examples (demo mode, see DemoTrips): "Argentina & Patagonia" happening now (day 14 of 31, with plan, journal,
 * real photos and the places stayed, [SampleArgentina]), "Kyoto & Osaka" next (a trip idea's plan, [SampleKyoto]) and
 * "Lisbon & Porto" in the past (a trip idea's plan, journal and stays, [SampleLisbon]). Debug builds start with them
 * when the app is empty; any build gets them when Me › Demo mode is turned on ([ensure]). Examples only: no recorded
 * GPS tracks, no invented bookings.
 */
object SampleData {
    /**
     * The set of examples; installs with an older set are moved over once: 2 = the Argentina demo
     * ([SampleTrips.upgrade]), 3 = its pictures drawn in the theme ([SampleArgentina.renames]), 4 = full programs for
     * Kyoto and Lisbon ([SampleTrips.fillPrograms]), 5 = their plans from the trip ideas ([SampleTrips.newPrograms]),
     * 6 = the same from the researched programs (main briefly carried placeholder programs at 5), 7 = websites on the
     * examples' places ([SampleTrips.addLinks]; only items still as they were seeded), 8 = Argentina's journal with
     * real photos ([SampleArgentina.newJournal]; only a journal nobody changed, and a drawn cover).
     */
    const val VERSION = 8

    /**
     * At each start: an empty app gets the examples when [seedEmpty] (debug builds); an install that has examples gets
     * the current set. Installs that started without examples (a trip of their own first) get none until Demo mode is
     * turned on.
     */
    suspend fun seedIfEmpty(graph: AppGraph, seedEmpty: Boolean = true) {
        val settings = graph.settings
        when {
            settings.sampleSeeded() -> upgrade(graph)
            seedEmpty && graph.db.trips().count() == 0 -> seed(graph)
        }
    }

    /**
     * Me › Demo mode on, in any build: brings examples the install already has up to date, then seeds the current ones
     * that are missing or were deleted (what was left of a deleted one is soft-deleted first, so nothing doubles). The
     * traveller's own trips are never changed.
     */
    suspend fun ensure(graph: AppGraph) {
        val settings = graph.settings
        if (settings.sampleSeeded()) upgrade(graph)
        val db = graph.db
        val rows = SampleTrips.Rows(
            trips = db.trips().all(), items = db.planItems().all(), entries = db.entries().all(), stops = db.stops().all(), media = db.media().all(),
        )
        val ids = SampleTrips.missing(rows.trips)
        if (ids.isNotEmpty()) {
            val today = LocalDate.now()
            val t = now()
            val demos = ids.map { id ->
                when (id) {
                    SampleArgentina.TRIP_ID -> SampleArgentina.demo(today, Instant.ofEpochMilli(t), t)
                    SampleTrips.KYOTO -> SampleKyoto.demo(SampleKyoto.start(today), t)
                    else -> SampleLisbon.demo(SampleLisbon.start(today), t)
                }
            }
            val media = demos.map { draw(graph, it) }
            val gone = SampleTrips.leftovers(rows, ids.toSet(), t)
            db.withTransaction {
                write(graph, gone)
                db.trips().upsertAll(demos.map { it.trip })
                demos.zip(media).forEach { (demo, m) -> add(graph, demo, m) }
            }
        }
        settings.setSampleSeeded()
        settings.setSampleVersion(VERSION)
    }

    /** An install with an older set of examples gets the current one (each step once). */
    private suspend fun upgrade(graph: AppGraph) {
        val settings = graph.settings
        val version = settings.sampleVersion()
        if (version >= VERSION) return
        if (version < 2) retireFirstExamples(graph)
        // Version 3 renamed the drawn pictures of the first journal; version 8 replaces that journal.
        val filled = if (version < 4) fillPrograms(graph) else emptySet()
        if (version < 5) newPrograms(graph, skip = filled)
        else if (version < 6) newPrograms(graph, skip = emptySet())
        if (version < 7) addLinks(graph)
        if (version < 8) newJournal(graph)
        settings.setSampleVersion(VERSION)
    }

    private suspend fun seed(graph: AppGraph) {
        val db = graph.db
        val today = LocalDate.now()
        val t = now()
        val demos = listOf(
            SampleArgentina.demo(today, Instant.ofEpochMilli(t), t),
            SampleKyoto.demo(SampleKyoto.start(today), t),
            SampleLisbon.demo(SampleLisbon.start(today), t),
        )
        val media = demos.map { draw(graph, it) }
        db.withTransaction {
            db.trips().upsertAll(demos.map { it.trip })
            demos.zip(media).forEach { (demo, m) -> add(graph, demo, m) }
        }
        graph.settings.setSampleSeeded()
        graph.settings.setSampleVersion(VERSION)
    }

    /**
     * Installs that had the first examples: the untouched Cyclades example and fixed-date Argentina plan are
     * soft-deleted, the demo is added and Kyoto comes back if the Argentina plan had removed it. Never touches
     * anything the traveller made or changed.
     */
    private suspend fun retireFirstExamples(graph: AppGraph) {
        val db = graph.db
        val today = LocalDate.now()
        val t = now()
        val samples = listOf(SampleTrips.CYCLADES, SampleArgentina.LEGACY_TRIP_ID)
        val rows = SampleTrips.Rows(
            trips = db.trips().all(), items = db.planItems().all(), entries = db.entries().all(), stops = db.stops().all(),
            media = db.media().all(), trackedTrips = samples.filterTo(HashSet()) { db.tracks().forTrip(it).isNotEmpty() },
        )
        val changes = SampleTrips.upgrade(rows, today, t)
        val demo = if (rows.trips.none { it.id == SampleArgentina.TRIP_ID }) SampleArgentina.demo(today, Instant.ofEpochMilli(t), t) else null
        val media = demo?.let { draw(graph, it) }.orEmpty()
        db.withTransaction {
            db.trips().upsertAll(changes.trips)
            db.planItems().upsertAll(changes.items)
            db.entries().upsertAll(changes.entries)
            db.stops().upsertAll(changes.stops)
            if (demo != null) {
                db.trips().upsert(demo.trip)
                add(graph, demo, media)
            }
        }
    }

    /** Untouched bare Kyoto and Lisbon examples get their full programs, on their own dates. Returns the trips filled. */
    private suspend fun fillPrograms(graph: AppGraph): Set<String> {
        val db = graph.db
        val ids = listOf(SampleTrips.KYOTO, SampleTrips.LISBON)
        val rows = SampleTrips.Rows(
            trips = db.trips().all(), items = db.planItems().all(), entries = db.entries().all(), stops = db.stops().all(),
            media = db.media().all(), trackedTrips = ids.filterTo(HashSet()) { db.tracks().forTrip(it).isNotEmpty() },
        )
        val demos = SampleTrips.fillPrograms(rows, now())
        if (demos.isEmpty()) return emptySet()
        val media = demos.map { draw(graph, it) }
        db.withTransaction {
            db.trips().upsertAll(demos.map { it.trip })
            demos.zip(media).forEach { (demo, m) -> add(graph, demo, m) }
        }
        return demos.mapTo(HashSet()) { it.trip.id }
    }

    /** Kyoto and Lisbon (when there) get their plans from the trip ideas; journal, pictures and stays stay. */
    private suspend fun newPrograms(graph: AppGraph, skip: Set<String>) {
        val db = graph.db
        val rows = SampleTrips.Rows(
            trips = db.trips().all(), items = db.planItems().all(), entries = db.entries().all(), stops = db.stops().all(), media = db.media().all(),
        )
        val changes = SampleTrips.newPrograms(rows, now(), skip)
        if (changes.trips.isEmpty()) return
        db.withTransaction {
            db.trips().upsertAll(changes.trips)
            write(graph, changes)
        }
    }

    /** The examples' plan items still as they were seeded get their places' websites; nothing else changes. */
    private suspend fun addLinks(graph: AppGraph) {
        val db = graph.db
        val rows = SampleTrips.Rows(
            trips = db.trips().all(), items = db.planItems().all(), entries = db.entries().all(), stops = db.stops().all(), media = db.media().all(),
        )
        val changes = SampleTrips.addLinks(rows, now())
        if (changes.items.isEmpty()) return
        db.withTransaction {
            db.trips().upsertAll(changes.trips)
            write(graph, changes)
        }
    }

    /** Plan items, entries, pictures and stays of [c] (its trips are written by the caller). */
    private suspend fun write(graph: AppGraph, c: SampleTrips.Changes) {
        val db = graph.db
        db.planItems().upsertAll(c.items)
        db.entries().upsertAll(c.entries)
        db.media().upsertAll(c.media)
        db.stops().upsertAll(c.stops)
    }

    /** Argentina's first journal (drawn pictures), while nobody changed it, becomes the one with real photos. */
    private suspend fun newJournal(graph: AppGraph) {
        val db = graph.db
        val trip = db.trips().get(SampleArgentina.TRIP_ID) ?: return
        val t = now()
        val swap = SampleArgentina.newJournal(
            trip, db.entries().forTrip(trip.id), db.media().forTrip(trip.id), Instant.ofEpochMilli(t), t, db.planItems().forTrip(trip.id),
        ) ?: return
        val added = files(graph, swap.added, emptyMap(), swap.photos)
        db.withTransaction {
            db.trips().upsert(swap.trip)
            db.entries().upsertAll(swap.entries)
            db.media().upsertAll(swap.gone + added)
            db.planItems().upsertAll(swap.items)
            // A book made from the first journal holds its drawn pictures: Make a book starts again from the new one.
            if (swap.entries.isNotEmpty()) db.books().deleteForTrip(trip.id, t)
        }
    }

    private suspend fun add(graph: AppGraph, demo: SampleArgentina.Demo, media: List<Media>) {
        val db = graph.db
        db.planItems().upsertAll(demo.items)
        db.media().upsertAll(media)
        db.entries().upsertAll(demo.entries)
        db.stops().upsertAll(demo.stops)
    }

    /**
     * Writes the demo's pictures into files/media/, like imported photos: drawn ones (demo-….jpg) drawn, real ones
     * copied from the app's assets ([DemoPhotos]). Returns the rows with checksums.
     */
    private suspend fun draw(graph: AppGraph, demo: SampleArgentina.Demo): List<Media> =
        files(graph, demo.media, demo.pictures, demo.photos)

    private suspend fun files(
        graph: AppGraph, media: List<Media>, pictures: Map<String, DemoArt.Picture>, photos: Map<String, DemoPhotos.Photo>,
    ): List<Media> = withContext(Dispatchers.IO) {
        val dir = graph.app.filesDir
        media.map { m ->
            val sum = pictures[m.id]?.let { DemoArt.write(dir, m.localPath, it) }
                ?: graph.app.assets.open(requireNotNull(photos[m.id]).asset).use { PhotoFiles.copy(it, File(dir, m.localPath)) }
            m.copy(sha256 = sum)
        }
    }
}
