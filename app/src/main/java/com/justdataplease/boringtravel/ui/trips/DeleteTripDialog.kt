package com.justdataplease.boringtravel.ui.trips

import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.together.TogetherText
import kotlinx.coroutines.launch

/** What deleting a trip says it takes with it (see data/Deletions.deleteTrip). */
internal object DeleteTripText {
    const val TITLE = "Delete this trip?"
    const val BODY = "Are you sure you want to delete it? Its plan, journal, photos, clips, book and recorded route are deleted, and its calendar events " +
        "removed. Your phone's gallery and your community posts stay as they are."
}

/**
 * Asks before deleting trip [tripId] (trip ⋮ and Edit trip): a shared trip is left instead (Travel together), with
 * that sheet's words. [onDone] runs once it is gone; the trip screen notices and closes itself.
 */
@Composable
fun DeleteTripDialog(graph: AppGraph, tripId: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sharing by graph.together.state.collectAsState()
    val shared = sharing.of(tripId)?.members?.isNotEmpty() == true
    var busy by remember { mutableStateOf(false) }
    BtConfirmDialog(
        title = if (shared) "Leave this trip?" else DeleteTripText.TITLE,
        body = if (shared) TogetherText.LEAVE else DeleteTripText.BODY,
        confirm = if (shared) "Leave" else "Delete",
        danger = true, busy = busy,
        onConfirm = {
            busy = true
            scope.launch {
                graph.deletions.deleteTrip(tripId)
                onDone()
            }
        },
        onDismiss = onDismiss,
    )
}
