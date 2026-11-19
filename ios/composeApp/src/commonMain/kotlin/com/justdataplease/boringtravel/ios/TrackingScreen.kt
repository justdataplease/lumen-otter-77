package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.tracking.TrackingPermissions.Step
import com.justdataplease.boringtravel.tracking.TrackingPlan
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtRadioRow
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates

/*
 * Route tracking's screens, as the Android app's ui/map/MapScreen.kt TrackingSettingsScreen, TrackingPermissionFlow.kt
 * and TripMapBoard.kt TrackingLevelSheet show them: Me › Route tracking (what records now, the level for every trip,
 * what the phone still needs) and a trip map's Route tracking sheet (the trip's own level). The words are Android's,
 * except where iPhone works differently: location is asked While Using with Precise Location on (Always only behind
 * IosTrackingPlan.ASK_ALWAYS, off as Android's background recording), the blue location sign shows while it records
 * in the background instead of a notification, and Settings is where a refused question is answered again.
 */

/**
 * Me › Route tracking: what records now (with Start recording or Stop for today), the level for every trip, and what
 * the phone still needs. Nothing is asked until a step's Allow is tapped.
 */
@Composable
fun TrackingScreen(tracking: RouteTracking, settings: AppSettings, onBack: () -> Unit) {
    LaunchedEffect(tracking) { tracking.apply() }
    val status = tracking.status
    val level = settings.trackingLevel
    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Route tracking", onBack = onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BtCard(Modifier.fillMaxWidth()) {
                    Eyebrow("Now")
                    RecordingNow(tracking, status, Modifier.padding(top = 4.dp), appLevel = level)
                }
                BtCard(Modifier.fillMaxWidth()) {
                    Eyebrow("For every trip")
                    TrackingLevel.entries.forEach { option ->
                        BtRadioRow(option.label, option.blurb, option == level, onClick = { tracking.setAppLevel(option) })
                    }
                    Text(
                        "Records where you go during a trip's dates, so your map and clips draw themselves. ${howItRecords()} Change a trip's level on its map. Your route is saved on this phone and goes into your backup files.",
                        Modifier.padding(top = 8.dp),
                        style = Bt.text.fine,
                    )
                }
                val needs = status.target.level.takeIf { status.target.tripId != null } ?: level
                // A card only while there is a step (Android's always holds its Battery settings line, which iPhone
                // hasn't got).
                val checks = tracking.checks
                val anything = remember(needs, checks, status) { tracking.stepsLeft(needs) }
                if (anything) {
                    BtCard(Modifier.fillMaxWidth()) {
                        TrackingSteps(tracking, needs)
                    }
                }
            }
        }
    }
}

/**
 * What records now ([recordingText]) and the one action that fits: Start recording (while the app is on screen; it
 * also clears a Stop) or Stop for today.
 */
@Composable
fun RecordingNow(tracking: RouteTracking, status: TrackingStatus, modifier: Modifier = Modifier, appLevel: TrackingLevel? = null) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(recordingText(status, appLevel), style = Bt.text.item)
        if (status.target.tripId != null && status.allowed) {
            if (status.recording && !status.stopped) {
                BtGhostButton("Stop for today", onClick = { tracking.stopForToday() })
            } else {
                BtButton("Start recording", onClick = { tracking.startNow() }, small = true)
            }
        }
    }
}

/**
 * What [level] needs from the phone, one step at a time, each with one plain sentence: precise location While Using,
 * then the notes (then Always, only with the Always path on); and Location Services switched off. The system's question comes only on Allow; a step
 * already answered no opens the app's page in Settings. Read again whenever the app comes back to the front.
 */
@Composable
fun TrackingSteps(tracking: RouteTracking, level: TrackingLevel, modifier: Modifier = Modifier) {
    // Read the phone again on every return to the app (Android's ON_RESUME), and whenever the tracker heard a change.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tracking.recheck() }
    val checks = tracking.checks
    val step = remember(level, checks) { tracking.missing(level).firstOrNull() }
    val servicesOn = remember(checks) { tracking.sensor.servicesOn() }
    if (level == TrackingLevel.Off) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Location Services off first: iOS then reports every app as refused, and Settings' Location is the way back.
        if (!servicesOn) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Location is off", style = Bt.text.item)
                Text("The route pauses until you turn it on.", style = Bt.text.sub)
            }
            // iPhone can't open Location Services itself: the app's page in Settings leads there.
            BtButton("Turn on", onClick = { tracking.sensor.openSettings() }, small = true)
        } else if (step != null) {
            val settingsNext = remember(step, checks) { tracking.settingsFor(step) }
            val (title, body) = stepText(step, settingsNext, tracking.locationAllowed())
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Bt.text.item)
                Text(body, style = Bt.text.sub)
            }
            BtButton(if (settingsNext) "Open settings" else "Allow", onClick = { tracking.allow(step) }, small = true)
        }
    }
}

/**
 * A step's title and sentence (the Android app's TrackingPermissionFlow copy), with iPhone's words where it differs:
 * [settingsNext] = the question was answered no and only Settings can change it; [hasLocation] = While Using is given.
 */
internal fun stepText(step: Step, settingsNext: Boolean, hasLocation: Boolean): Pair<String, String> = when (step) {
    Step.Precise -> "Precise location" to
        if (hasLocation) "Your route needs your exact position. Turn on Precise Location for BoringTravel in Settings. Points are kept on this phone (and in your backup files)."
        else "Your route needs your exact position. Points are kept on this phone (and in your backup files)."
    Step.Notifications -> "Notifications" to "A quiet note tells you when your route stops recording or location is off."
    // Android's prominent disclosure for background location, said here before iOS's own question.
    Step.Background -> "Allow all the time" to
        "BoringTravel collects location data to record your trip route for your map, journal and clips, even when the app is closed or not in use. " +
        "It records only between a trip's dates, and the points are kept on this phone. " +
        (if (settingsNext) "In Settings, choose Location › Always." else "Choose “Change to Always Allow” on the next screen.") +
        " Without it, the route stops when you close the app."
}

/**
 * A trip's Route tracking sheet, from its map (the Android app's TrackingLevelSheet): the trip's own level (the one for
 * every trip is in Me); on a trip day also what records now, with Start recording or Stop for today, and the steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingLevelSheet(tracking: RouteTracking, trip: Trip, appLevel: TrackingLevel, today: LocalDate, onClose: () -> Unit) {
    val status = tracking.status
    val level = TrackingPlan.levelFor(trip, appLevel)
    val current = trip.isCurrent(today)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bt.colors.surface,
        contentColor = Bt.colors.ink,
    ) {
        TrackingLevelContent(tracking, trip, level, status, current)
    }
}

/** The sheet's content, also drawn on its own for the screenshots. */
@Composable
fun TrackingLevelContent(tracking: RouteTracking, trip: Trip, level: TrackingLevel, status: TrackingStatus, current: Boolean) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        Text("Route tracking", style = Bt.text.title)
        val start = trip.startDate
        if (!current && start != null) Text("Starts on ${Dates.dayLong(start)}.", Modifier.padding(top = 2.dp), style = Bt.text.sub)
        TrackingLevel.entries.forEach { l ->
            BtRadioRow(l.label, l.blurb, l == level, onClick = { tracking.setTripLevel(trip.id, l) })
        }
        if (current && level != TrackingLevel.Off && status.target.tripId == trip.id) {
            RecordingNow(tracking, status, Modifier.padding(top = 8.dp))
            TrackingSteps(tracking, level, Modifier.padding(top = 8.dp))
        }
        Text("For this trip. ${howItRecords()} Your route is saved on this phone.", Modifier.padding(top = 8.dp), style = Bt.text.fine)
    }
}
