package com.justdataplease.boringtravel.ui.map

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.tracking.TrackingPermissions
import com.justdataplease.boringtravel.tracking.TrackingPermissions.Step
import com.justdataplease.boringtravel.tracking.TrackingPlan
import com.justdataplease.boringtravel.tracking.TrackingStatus
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.launch

/**
 * What [level] needs from Android, one step at a time, each with one plain sentence: precise location, then
 * notifications for the recording's notification (and "Allow all the time" for Low and Balanced only while
 * [TrackingPlan.BACKGROUND_RECORDING] is on); plus location switched off on the phone and a link to the battery
 * setting. Android's question comes only on Allow, never by itself (Me › Route tracking and a trip's map, once a level
 * is picked). A step asked once and still refused opens the app's settings page next time.
 */
@Composable
fun TrackingPermissionFlow(graph: AppGraph, level: TrackingLevel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val missing = remember(level, tick) { TrackingPermissions.missing(context, level) }
    val locationOn = remember(tick) { TrackingPermissions.locationEnabled(context) }
    var asked by rememberSaveable { mutableStateOf(listOf<String>()) }
    val step = missing.firstOrNull()

    // A permission changed: let the tracker pick it up (not on the first look).
    var seen by remember { mutableStateOf<Pair<List<Step>, Boolean>?>(null) }
    LaunchedEffect(missing, locationOn) {
        val now = missing to locationOn
        if (seen != null && seen != now) graph.tracking.refresh(force = true)
        seen = now
    }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    fun ask(s: Step) {
        if (s.name in asked) {
            context.open(TrackingPermissions.appSettingsIntent(context))
            return
        }
        asked = asked + s.name
        when (s) {
            Step.Precise -> locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            Step.Background -> singleLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            Step.Notifications ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) singleLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.open(TrackingPermissions.appSettingsIntent(context))
        }
    }

    if (level == TrackingLevel.Off) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (step != null) {
            val (title, body) = copy(step)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Bt.text.item)
                Text(body, style = Bt.text.sub)
            }
            BtButton(if (step.name in asked) "Open settings" else "Allow", onClick = { ask(step) }, small = true)
        } else if (!locationOn) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Location is off", style = Bt.text.item)
                Text("The route pauses until you turn it on.", style = Bt.text.sub)
            }
            BtButton("Turn on", onClick = { context.open(TrackingPermissions.locationSettingsIntent()) }, small = true)
        }
        Text(
            "Battery settings",
            Modifier.clickable(role = Role.Button) { context.open(TrackingPermissions.batterySettingsIntent()) }.padding(vertical = 4.dp),
            style = Bt.text.label,
            color = Bt.colors.accentInk,
        )
        Text("If points go missing, let BoringTravel run without battery limits.", style = Bt.text.fine)
    }
}

private fun copy(step: Step): Pair<String, String> = when (step) {
    Step.Precise -> "Precise location" to "Your route needs your exact position. Points are kept on this phone (and in your Drive backup, if it's on)."
    // Asked only while TrackingPlan.BACKGROUND_RECORDING is on. Google Play's prominent disclosure for background
    // location: it must say this, right before Android's request.
    Step.Background -> "Allow all the time" to
        "BoringTravel collects location data to record your trip route for your map, journal and clips, even when the app is closed or not in use. " +
        "It records only between a trip's dates, and the points are kept on this phone (and in your Drive backup, if it's on). " +
        "Choose “Allow all the time” on the next screen. Without it, the route is kept only while the app is open."
    Step.Notifications -> "Notifications" to "A quiet notification shows while your route records, with a Stop button."
}

/** How recording runs, under the levels (Me › Route tracking, a trip's map). */
internal fun howItRecords(): String =
    if (TrackingPlan.BACKGROUND_RECORDING) "Low and Balanced record without a notification; Detailed shows one while it records."
    else "It starts when you open BoringTravel during a trip and shows a notification while it records. Stop there pauses it until tomorrow."

/** What records now, in one line; [appLevel] is Me's level for every trip (Off for a new install). */
internal fun recordingText(s: TrackingStatus, appLevel: TrackingLevel? = null): String {
    val trip = s.tripTitle.ifBlank { "today's trip" }
    return when {
        s.target.tripId == null && s.tripTitle.isNotBlank() -> "Off for $trip."
        s.target.tripId == null && appLevel == TrackingLevel.Off -> "Off. Pick a level to record your route during trips."
        s.target.tripId == null -> "No trip today. Recording starts when you open BoringTravel during a trip."
        !s.allowed -> "Waiting for location permission for $trip."
        s.stopped -> "Stopped for today · $trip"
        s.recording -> "Recording $trip · ${s.target.level.label}"
        else -> "Not recording $trip yet."
    }
}

/**
 * What records now ([recordingText]) and the one action that fits: Start recording (starts the recording service
 * while the app is on screen, and clears a Stop) or Stop for today.
 */
@Composable
internal fun RecordingNow(graph: AppGraph, status: TrackingStatus, modifier: Modifier = Modifier, appLevel: TrackingLevel? = null) {
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(recordingText(status, appLevel), style = Bt.text.item)
        if (status.target.tripId != null && status.allowed) {
            if (status.recording && !status.stopped) {
                BtGhostButton("Stop for today", onClick = { scope.launch { graph.tracking.stopForToday() } })
            } else {
                BtButton("Start recording", onClick = { scope.launch { graph.tracking.startNow() } }, small = true)
            }
        }
    }
}

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        runCatching { startActivity(TrackingPermissions.appSettingsIntent(this)) }
    }
}
