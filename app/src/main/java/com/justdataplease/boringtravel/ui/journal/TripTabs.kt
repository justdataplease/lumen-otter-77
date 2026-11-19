package com.justdataplease.boringtravel.ui.journal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtFab
import com.justdataplease.boringtravel.ui.components.EmptyState
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/** The Journal tab inside a trip: the trip's entries, newest first. (The Media tab is ui/trip/TripMedia.) */
@Composable
fun TripJournalTab(graph: AppGraph, actions: AppActions, tripId: String) {
    val infos by remember(tripId) {
        combine(graph.journal.observeForTrip(tripId), graph.media.observeVideoIds()) { l, videos -> l.map { EntryInfo(it, videos) } }
            .flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(initialValue = null)
    val list = infos ?: return
    Column(Modifier.fillMaxSize()) {
        DailyJournalCheckIn(graph, actions, tripId)
        if (list.isEmpty()) {
            EmptyState("Nothing written for this trip", "Write a line about today. It can be short.") {
                BtButton("Write", onClick = { actions.write(tripId = tripId) }, icon = BtIcons.Add, small = true)
            }
        } else Box(Modifier.weight(1f)) {
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 104.dp)) {
                itemsIndexed(list, key = { _, info -> info.entry.id }) { i, info ->
                    EntryRow(info, onClick = { actions.openEntry(info.entry.id) }, index = i)
                }
            }
            BtFab("Write", onClick = { actions.write(tripId = tripId) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    }
}
