package com.justdataplease.boringtravel.data

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.BuildConfig
import com.justdataplease.boringtravel.data.repo.now
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * Demo mode on this phone (see [DemoTrips] for what shows): seeds the examples in debug builds when the app is empty
 * and brings existing examples up to date in any build, decides the mode once (on whenever there are examples), seeds
 * the examples whenever Me › Demo mode is turned on, and keeps the demo around today whenever demo mode turns on and once
 * a day while it stays on. Seeding and moving the demo never overlap ([lock]).
 */
class DemoMode(private val graph: AppGraph) {
    private val lock = Mutex()
    private val _left = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A new trip of the traveller's own ended demo mode ("Demo mode off"). */
    val left: SharedFlow<Unit> = _left.asSharedFlow()

    /** Called once from the Application (main thread). */
    fun start() {
        graph.scope.launch {
            // Debug builds start with the examples so every screen has something real to show; any build moves the
            // examples it has to the current set.
            lock.withLock { SampleData.seedIfEmpty(graph, seedEmpty = BuildConfig.DEBUG) }
            chooseOnce()
            anchor(force = false)
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) graph.scope.launch { anchor(force = false) } },
        )
    }

    /**
     * Me › Demo mode and Trips › Exit demo. Turning it on (in any build) adds the examples that are missing and brings
     * the demo back to today. Runs in the app's scope, so leaving the screen half-way never leaves the examples
     * half-written; returns when done.
     */
    suspend fun set(on: Boolean) {
        graph.scope.launch {
            graph.settings.setDemoMode(on)
            if (on) lock.withLock {
                SampleData.ensure(graph)
                anchorLocked(force = true)
            }
        }.join()
    }

    /** A trip of the traveller's own was just made: show their trips. */
    suspend fun leave() {
        if (!graph.settings.demoMode.first()) return
        graph.settings.setDemoMode(false)
        _left.tryEmit(Unit)
    }

    private suspend fun chooseOnce() {
        if (graph.settings.demoModeChosen()) return
        val trips = graph.db.trips().all().filter { it.deletedAt == null }
        graph.settings.setDemoMode(DemoTrips.initialMode(hasDemoTrips = trips.any { it.isDemo }))
    }

    /** Moves the whole demo so today is Argentina's day 14 again; at most once a day unless [force]. */
    suspend fun anchor(force: Boolean) = lock.withLock { anchorLocked(force) }

    private suspend fun anchorLocked(force: Boolean) {
        if (!graph.settings.demoMode.first()) return
        val today = LocalDate.now()
        if (!force && graph.settings.demoAnchoredDay() == today.toEpochDay()) return
        val db = graph.db
        db.withTransaction {
            val rows = db.trips().all().filter { it.isDemo }.map { t ->
                DemoTrips.Rows(t, db.planItems().forTrip(t.id), db.entries().forTrip(t.id), db.media().forTrip(t.id), db.stops().forTrip(t.id))
            }
            val moved = DemoTrips.reanchor(rows, today, now())
            if (moved.isNotEmpty()) {
                db.trips().upsertAll(moved.map { it.trip })
                db.planItems().upsertAll(moved.flatMap { it.items })
                db.entries().upsertAll(moved.flatMap { it.entries })
                db.media().upsertAll(moved.flatMap { it.media })
                db.stops().upsertAll(moved.flatMap { it.stops })
            }
        }
        graph.settings.setDemoAnchoredDay(today.toEpochDay())
    }
}
