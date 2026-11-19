package com.justdataplease.boringtravel.ui.journal

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.checkin.CheckInLocation
import com.justdataplease.boringtravel.checkin.CheckInPrompt
import com.justdataplease.boringtravel.checkin.DailyCheckIn
import com.justdataplease.boringtravel.tracking.TrackingPermissions
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/** An inline invitation, claimed once when presented, shared by the global and trip journals. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DailyJournalCheckIn(graph: AppGraph, actions: AppActions, tripId: String? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val day = DailyCheckIn.localDay(clock, ZoneId.systemDefault())
    var prompt by remember(tripId, day) { mutableStateOf<CheckInPrompt?>(null) }
    var loaded by remember(tripId, day) { mutableStateOf(false) }
    var chosenId by remember(tripId, day) { mutableStateOf<String?>(null) }
    var nearbyId by remember(tripId, day) { mutableStateOf<String?>(null) }
    var nearbyAt by remember(tripId, day) { mutableStateOf<Long?>(null) }
    var choicesOpen by remember(tripId, day) { mutableStateOf(false) }
    var message by remember(tripId, day) { mutableStateOf<String?>(null) }
    var saving by remember(tripId, day) { mutableStateOf(false) }
    var locating by remember(tripId, day) { mutableStateOf(false) }
    var locationJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { clock = System.currentTimeMillis(); delay(60_000L) }
        }
    }
    LaunchedEffect(graph, tripId, day, lifecycle) {
        locationJob?.cancel()
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val now = System.currentTimeMillis()
            clock = now
            // Navigation can compose an offscreen destination. Only its resumed, current-day journal may claim.
            if (!loaded && DailyCheckIn.localDay(now, ZoneId.systemDefault()) == day) {
                try {
                    val claimed = graph.checkIns.claim(tripId, now)
                    prompt = claimed
                    chosenId = claimed?.nearbyItemId ?: claimed?.places?.firstOrNull()?.itemId
                    nearbyId = claimed?.nearbyItemId
                    nearbyAt = claimed?.nearbyAt
                    loaded = true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* A failed local read never blocks writing a normal note. */ }
            }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) locationJob?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); locationJob?.cancel() }
    }

    fun locate() {
        val current = prompt ?: return
        if (locating || saving) return
        locating = true
        message = null
        locationJob = scope.launch {
            try {
                val fix = CheckInLocation.current(context)
                val now = System.currentTimeMillis()
                val nearby = DailyCheckIn.nearby(current.places, fix, now)
                nearbyId = nearby?.itemId
                nearbyAt = fix?.time.takeIf { nearby != null }
                if (nearby != null) {
                    chosenId = nearby.itemId
                    choicesOpen = false
                    message = null
                } else message = "No reliable match nearby. You can choose a place yourself."
                clock = now
            } finally { locating = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it } || TrackingPermissions.hasLocation(context)) locate()
        else message = "Location is optional. Choose a place from today's plan, or skip."
    }
    fun useLocation() {
        if (TrackingPermissions.hasLocation(context)) locate()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    fun dismiss() { locationJob?.cancel(); choicesOpen = false; prompt = null }

    val current = prompt ?: return
    val place = current.places.firstOrNull { it.itemId == chosenId } ?: return
    val freshMatch = nearbyId == place.itemId && nearbyAt?.let { clock - it in 0..DailyCheckIn.FRESH_MS } == true
    BtCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), padding = PaddingValues(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Today's check-in", Modifier.weight(1f))
            if (!saving) BtIconButton(BtIcons.Close, "Skip today's check-in", ::dismiss)
        }
        Text("At ${place.name}?", style = Bt.text.h2, color = Bt.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (freshMatch) "A recent location suggests you're nearby." else "From today's plan · ${current.tripTitle}",
            style = Bt.text.sub, color = Bt.colors.muted, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BtButton(if (saving) "Opening…" else "Yes, write", enabled = !saving && !locating, small = true, onClick = {
                saving = true
                message = null
                scope.launch {
                    try {
                        val entry = graph.checkIns.confirm(current, place.itemId)
                        prompt = null
                        actions.write(tripId = entry.tripId, entryId = entry.id)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        message = e.message?.takeIf { e is IllegalArgumentException && it in checkInRecoveryMessages }
                            ?: "Couldn't open the note. Please try again."
                    }
                    finally { saving = false }
                }
            })
            val canChoose = !saving && !locating
            TextButton(onClick = { choicesOpen = true }, enabled = canChoose) {
                Text("Another place", color = if (canChoose) Bt.colors.ink else Bt.colors.muted.copy(alpha = 0.45f))
            }
        }
        if (message != null && !choicesOpen) Text(message!!, style = Bt.text.sub, color = Bt.colors.muted, modifier = Modifier.padding(top = 8.dp))
    }

    if (choicesOpen) ModalBottomSheet(
        onDismissRequest = { choicesOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bt.colors.surface,
        contentColor = Bt.colors.ink,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Where are you today?", style = Bt.text.h2)
            Text("Choose from today's plan. You can change the place in your note afterwards.", style = Bt.text.sub, color = Bt.colors.muted)
            BtGhostButton(if (locating) "Finding your location…" else "Use current location", ::useLocation,
                icon = BtIcons.MyLocation, enabled = !locating, modifier = Modifier.fillMaxWidth())
            if (message != null) Text(message!!, style = Bt.text.sub, color = Bt.colors.muted)
            current.places.forEach { candidate ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !locating, role = Role.RadioButton) {
                    chosenId = candidate.itemId; choicesOpen = false; message = null
                }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(candidate.itemId == place.itemId, onClick = null, enabled = !locating)
                    Text(candidate.name, style = Bt.text.body, modifier = Modifier.padding(start = 8.dp))
                }
            }
            TextButton(onClick = ::dismiss, modifier = Modifier.fillMaxWidth()) { Text("Skip today", color = Bt.colors.muted) }
        }
    }
}

/** Only our intentional recovery text may reach the screen; provider/database details stay private. */
private val checkInRecoveryMessages = setOf(
    "A new day has started. Open the journal again.",
    "This check-in was removed. Write a new note instead.",
    "This trip is no longer available.",
    "Today's plan changed. Write a note or reopen the journal.",
)
