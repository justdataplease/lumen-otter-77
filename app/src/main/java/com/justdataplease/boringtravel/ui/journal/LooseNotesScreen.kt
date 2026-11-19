package com.justdataplease.boringtravel.ui.journal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.EmptyState
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/**
 * Notes outside trips: entries written when no trip was on (the widget's Write with no trip, older quick notes), newest
 * first, with their photos. Opened from the card at the end of Trips, which shows only when there are some.
 */
@Composable
fun LooseNotesScreen(graph: AppGraph, actions: AppActions) {
    val infos by remember(graph) {
        combine(graph.journal.observeEntries(), graph.media.observeVideoIds()) { l, videos ->
            l.filter { it.tripId == null }.map { EntryInfo(it, videos) }
        }.flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(initialValue = null)
    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Notes outside trips", onBack = actions::back)
            val list = infos ?: return@Column
            if (list.isEmpty()) {
                EmptyState("No notes outside trips", "Notes written when no trip is on show up here.")
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 32.dp)) {
                    item(key = "about") {
                        Text("Written when no trip was on.", Modifier.padding(bottom = 4.dp), style = Bt.text.sub)
                    }
                    itemsIndexed(list, key = { _, info -> info.entry.id }) { i, info ->
                        EntryRow(info, onClick = { actions.openEntry(info.entry.id) }, index = i)
                    }
                }
            }
        }
    }
}
