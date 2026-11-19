package com.justdataplease.boringtravel.widget

import androidx.glance.appwidget.updateAll
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.places.DayPlace
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Loads what the widgets show and keeps every placed widget (Today, Quick bar) current. */
object WidgetUpdates {
    suspend fun load(graph: AppGraph, today: LocalDate = LocalDate.now()): TodayInfo {
        val look = graph.settings.look.first()
        val zone = ZoneId.systemDefault()
        val trip = graph.trips.currentOn(today)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val wrote = runCatching { graph.journal.visibleCountBetween(start, end) }.getOrDefault(0)
        // The day's own name when it has one ("Day 4 · Temples and tea"), as on the plan.
        val place = trip?.let { t ->
            DayNames.of(t.travelModes, today)
                ?: runCatching { DayPlace.label(today, graph.tracks.stops(t.id), graph.plan.items(t.id), zone) }.getOrNull()
        }
        val next = if (trip == null) WidgetText.nextTrip(graph.trips.observeTrips().first(), today) else null
        return TodayInfo(today, look, trip, place, next, wrote)
    }

    /** Anything the widgets show: trips, entries, the plan, stops, the look and demo mode. */
    private fun changes(graph: AppGraph): Flow<Unit> = combine(
        graph.trips.observeTrips(),
        graph.journal.observeEntries(),
        graph.db.planItems().observeAllLive(),
        combine(graph.tracks.observeAllStops(), graph.settings.demoMode) { _, _ -> },
        graph.settings.look,
    ) { _, _, _, _, _ -> }

    /** Fresh [TodayInfo] after every change, for a widget's running composition. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun flow(graph: AppGraph): Flow<TodayInfo> = changes(graph).debounce(250).mapLatest { load(graph) }.distinctUntilChanged()

    /** Redraws every placed widget after trips, entries, the plan, stops or the look change (debounced). */
    @OptIn(FlowPreview::class)
    fun keepUpdated(graph: AppGraph) {
        graph.scope.launch {
            changes(graph).debounce(1_500).collect { updateAll(graph) }
        }
    }

    suspend fun updateAll(graph: AppGraph) {
        runCatching { TodayWidget().updateAll(graph.app) }
        runCatching { QuickWidget().updateAll(graph.app) }
        runCatching { QuickSmallWidget().updateAll(graph.app) }
    }
}
