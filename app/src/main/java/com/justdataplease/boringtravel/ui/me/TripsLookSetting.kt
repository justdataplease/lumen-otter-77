package com.justdataplease.boringtravel.ui.me

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.settings.TripsLook
import com.justdataplease.boringtravel.ui.components.BtSegmented
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.launch

/** Me › Trips look: Tickets (the default) or Pictures, made like Plan view. */
@Composable
internal fun TripsLookSetting(graph: AppGraph, modifier: Modifier = Modifier) {
    val look by graph.settings.tripsLook.collectAsStateWithLifecycle(initialValue = TripsLook.Tickets)
    val scope = rememberCoroutineScope()
    Column(modifier.padding(vertical = 12.dp)) {
        Text("Trips look", style = Bt.text.item)
        BtSegmented(
            TripsLook.entries.map { it.label }, look.ordinal,
            onSelect = { i -> scope.launch { graph.settings.setTripsLook(TripsLook.entries[i]) } },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
