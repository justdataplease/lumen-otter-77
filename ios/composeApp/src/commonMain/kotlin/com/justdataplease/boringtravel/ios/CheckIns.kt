package com.justdataplease.boringtravel.ios

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
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.justdataplease.boringtravel.checkin.CheckInFix
import com.justdataplease.boringtravel.checkin.CheckInPrompt
import com.justdataplease.boringtravel.checkin.DailyCheckIn
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtIconButton
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The daily check-in, as the Android app asks it (checkin/ and ui/journal/DailyJournalCheckIn.kt): on top of a trip's
 * Journal while the trip is on, today's plan has places and nothing is written for today yet, once per trip and day on
 * this phone. The rules are the Android app's own (checkin/DailyCheckIn.kt, read in at build time); the store, the
 * claim and the confirmation are written here on the engine's trip store, the location through [PhoneLocation].
 */

/**
 * Which trips and days were asked about on this phone (the Android app's CheckInStore): this phone's own, never in a
 * backup, kept 60 days. Claimed when the card is shown, so going back and forth never asks again.
 */
class CheckInStore(private val file: SettingsFile) {
    fun claim(tripId: String, day: LocalDate): Boolean {
        val token = "${day.toEpochDay()}:$tripId"
        val previous = file.read(KEY)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        if (token in previous) return false
        // A generous window of local days, including future ones after travel across time zones.
        val kept = previous.filter { it.substringBefore(':').toLongOrNull()?.let { d -> d >= day.toEpochDay() - 60 } == true }
        file.write(KEY, (kept + token).joinToString("\n"))
        return true
    }

    private companion object {
        const val KEY = "checkInsAsked"
    }
}

/** The check-in's claim and confirmation on the trips' store (the Android app's JournalCheckIns). */
class JournalCheckIns(private val book: TripBook, private val store: CheckInStore) {
    /** Today's prompt for trip [tripId], claimed now; null when nothing is to be asked. */
    fun claim(tripId: String, now: Long, zone: String? = null): CheckInPrompt? {
        val day = DailyCheckIn.localDay(now, zone)
        val trip = book.trip(tripId) ?: return null
        val places = DailyCheckIn.places(trip, book.items(trip.id), day)
        if (places.isEmpty()) return null
        // Today is already written for this trip: there is nothing to invite.
        if (DailyCheckIn.writtenOn(book.entries(trip.id), trip.id, day)) return null
        // The latest recorded point, when it is this trip's.
        val recorded = book.snapshot.trackPoints.maxByOrNull { it.time }?.takeIf { it.tripId == trip.id }
        val fix = recorded?.let { CheckInFix(it.time, it.lat, it.lng, it.accuracyM) }
        val nearest = DailyCheckIn.nearby(places, fix, now)
        if (!store.claim(trip.id, day)) return null
        return CheckInPrompt(trip.id, trip.title, day, places, nearest?.itemId, fix?.time.takeIf { nearest != null })
    }

    /**
     * Today's check-in note for [itemId], made (kept with the trips) or the one already there; the plan is read again,
     * so a deleted or moved place can't be confirmed. Throws IllegalArgumentException with the words to show.
     */
    fun confirm(prompt: CheckInPrompt, itemId: String, now: Long, zone: String? = null, member: String? = null): Entry {
        val trip = requireNotNull(book.trip(prompt.tripId)) { "This trip is no longer available." }
        val place = DailyCheckIn.places(trip, book.items(trip.id), DailyCheckIn.localDay(now, zone)).firstOrNull { it.itemId == itemId }
        requireNotNull(place) { "Today's plan changed. Write a note or reopen the journal." }
        // In a shared trip each member's check-in is their own entry ([member]: this phone's id there; round 26), as the
        // same id on every phone would clash.
        val id = DailyCheckIn.entryId(prompt.tripId, prompt.day, member)
        val existing = book.snapshot.entries.firstOrNull { it.id == id }
        val entry = DailyCheckIn.draft(existing, prompt, place, now, zone, member)
        if (entry !== existing) book.putRows(entries = listOf(entry))
        return entry
    }
}

/** Only the check-in's own recovery words may reach the screen. */
private val checkInRecoveryMessages = setOf(
    "A new day has started. Open the journal again.",
    "This check-in was removed. Write a new note instead.",
    "This trip is no longer available.",
    "Today's plan changed. Write a note or reopen the journal.",
)

/**
 * "Today's check-in" on top of trip [tripId]'s Journal: "At <place>?" with "From today's plan · <trip>" (or "A recent
 * location suggests you're nearby."), Yes, write (today's check-in note, opened in Write by [onWrite]), Another place
 * (today's places, Use current location, Skip today) and X. Claimed once when shown; the day turns at midnight while it
 * is open. [today]: the day the screens show (previews and tests run on a fixed one).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DailyJournalCheckIn(
    book: TripBook, settings: AppSettings, tripId: String, today: LocalDate, onChanged: () -> Unit, onWrite: (Entry) -> Unit,
    choicesAtStart: Boolean = false,
) {
    val location = LocalWeb.current.location
    val scope = rememberCoroutineScope()
    val checkIns = remember(book, settings) { JournalCheckIns(book, settings.checkIns) }
    // A shared trip's check-in is this member's own (round 26).
    val together = LocalTogether.current
    // The screens' day: the clock, moved to [today] when they show another one.
    val shift = remember(today) { nowOn(today) - nowMs() }
    var clock by remember { mutableLongStateOf(nowMs() + shift) }
    val day = DailyCheckIn.localDay(clock, null)
    var prompt by remember(tripId, day) { mutableStateOf<CheckInPrompt?>(null) }
    var loaded by remember(tripId, day) { mutableStateOf(false) }
    var chosenId by remember(tripId, day) { mutableStateOf<String?>(null) }
    var nearbyId by remember(tripId, day) { mutableStateOf<String?>(null) }
    var nearbyAt by remember(tripId, day) { mutableStateOf<Long?>(null) }
    var choicesOpen by remember(tripId, day) { mutableStateOf(choicesAtStart) }
    var message by remember(tripId, day) { mutableStateOf<String?>(null) }
    var saving by remember(tripId, day) { mutableStateOf(false) }
    var locating by remember(tripId, day) { mutableStateOf(false) }
    var locationJob by remember { mutableStateOf<Job?>(null) }

    // The day turns at midnight while the card is on screen, and when the app comes back to the front.
    LaunchedEffect(day, shift) {
        val wait = startOfDayMs(day.plusDays(1), null) - (nowMs() + shift)
        if (wait > 0) delay(wait)
        clock = nowMs() + shift
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { clock = nowMs() + shift }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { locationJob?.cancel() }
    LaunchedEffect(tripId, day) {
        locationJob?.cancel()
        val now = nowMs() + shift
        clock = now
        if (!loaded && DailyCheckIn.localDay(now, null) == day) {
            try {
                val claimed = checkIns.claim(tripId, now)
                prompt = claimed
                chosenId = claimed?.nearbyItemId ?: claimed?.places?.firstOrNull()?.itemId
                nearbyId = claimed?.nearbyItemId
                nearbyAt = claimed?.nearbyAt
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed read never blocks writing a normal note.
            }
        }
    }

    fun locate() {
        val current = prompt ?: return
        if (locating || saving) return
        locating = true
        message = null
        locationJob = scope.launch {
            try {
                val fix = runCatching { location.fix() }.getOrNull()
                val now = nowMs() + shift
                val nearby = DailyCheckIn.nearby(current.places, fix?.copy(time = fix.time + shift), now)
                nearbyId = nearby?.itemId
                nearbyAt = fix?.time?.plus(shift).takeIf { nearby != null }
                if (nearby != null) {
                    chosenId = nearby.itemId
                    choicesOpen = false
                    message = null
                } else message = "No reliable match nearby. You can choose a place yourself."
                clock = now
            } finally {
                locating = false
            }
        }
    }

    fun useLocation() {
        if (location.allowed()) locate()
        else location.ask { granted ->
            if (granted) locate() else message = "Location is optional. Choose a place from today's plan, or skip."
        }
    }

    fun dismiss() {
        locationJob?.cancel()
        choicesOpen = false
        prompt = null
    }

    val current = prompt ?: return
    val place = current.places.firstOrNull { it.itemId == chosenId } ?: return
    val freshMatch = nearbyId == place.itemId && nearbyAt?.let { clock - it in 0..DailyCheckIn.FRESH_MS } == true
    BtCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), padding = PaddingValues(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Today's check-in", Modifier.weight(1f))
            if (!saving) BtIconButton(BtIcons.Close, "Skip today's check-in", ::dismiss)
        }
        Text("At ${place.name}?", style = Bt.text.h2, color = Bt.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            if (freshMatch) "A recent location suggests you're nearby." else "From today's plan · ${current.tripTitle}",
            style = Bt.text.sub, color = Bt.colors.muted, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BtButton(if (saving) "Opening…" else "Yes, write", enabled = !saving && !locating, small = true, onClick = {
                saving = true
                message = null
                try {
                    val entry = checkIns.confirm(current, place.itemId, nowMs() + shift, member = together?.state?.value?.of(current.tripId)?.me)
                    prompt = null
                    onChanged()
                    onWrite(entry)
                } catch (e: Exception) {
                    message = e.message?.takeIf { e is IllegalArgumentException && it in checkInRecoveryMessages }
                        ?: "Couldn't open the note. Please try again."
                } finally {
                    saving = false
                }
            })
            val canChoose = !saving && !locating
            TextButton(onClick = { choicesOpen = true }, enabled = canChoose) {
                Text("Another place", color = if (canChoose) Bt.colors.ink else Bt.colors.muted.copy(alpha = 0.45f))
            }
        }
        val m = message
        if (m != null && !choicesOpen) Text(m, style = Bt.text.sub, color = Bt.colors.muted, modifier = Modifier.padding(top = 8.dp))
    }

    if (choicesOpen) ModalBottomSheet(
        onDismissRequest = { choicesOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bt.colors.surface,
        contentColor = Bt.colors.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Where are you today?", style = Bt.text.h2)
            Text("Choose from today's plan. You can change the place in your note afterwards.", style = Bt.text.sub, color = Bt.colors.muted)
            BtGhostButton(
                if (locating) "Finding your location…" else "Use current location", ::useLocation,
                icon = BtIcons.MyLocation, enabled = !locating, modifier = Modifier.fillMaxWidth(),
            )
            val m = message
            if (m != null) Text(m, style = Bt.text.sub, color = Bt.colors.muted)
            current.places.forEach { candidate ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !locating, role = Role.RadioButton) {
                        chosenId = candidate.itemId
                        choicesOpen = false
                        message = null
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        candidate.itemId == place.itemId, onClick = null, enabled = !locating,
                        colors = RadioButtonDefaults.colors(selectedColor = Bt.colors.primary, unselectedColor = Bt.colors.muted),
                    )
                    Text(candidate.name, style = Bt.text.body, modifier = Modifier.padding(start = 8.dp))
                }
            }
            TextButton(onClick = ::dismiss, modifier = Modifier.fillMaxWidth()) { Text("Skip today", color = Bt.colors.muted) }
        }
    }
}
