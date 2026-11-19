package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.PlaceResult
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtSwitchRow
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * When and where a note is (the Android app's ui/editor/EntryDetailsSheet.kt), opened from the date line on top of the
 * editor: the day, the time, the first photo's time, and the place: typed, found on the map ("On the map"), one of the
 * trip's own ("From this trip"), where the phone is ("Use my location"), or no place at all.
 */

/** The map search waits this long after the last key press (the Android editor's PLACE_SEARCH_DELAY_MS). */
private const val PLACE_SEARCH_DELAY_MS = 450L

/** The most places the map search offers here. */
private const val PLACES_FOUND = 5

/** The most of the trip's places offered at once (about three rows of chips), besides the day's plan items. */
private const val KNOWN_SHOWN = 12

/** When and where: date, time, place (typed, found on the map, the trip's own, or where the phone is), the photo's time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryDetailsSheet(model: WriteModel, onDismiss: () -> Unit, typedAtStart: String? = null) {
    val c = Bt.colors
    val web = LocalWeb.current
    val tracking = LocalTracking.current
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    // The map's answer for what was typed, searched only when typed here (not for the place the note opened with).
    var typed by remember { mutableIntStateOf(0) }
    LaunchedEffect(typedAtStart) {
        if (typedAtStart != null) {
            model.typePlace(typedAtStart)
            typed++
        }
    }
    var found by remember { mutableStateOf<List<PlaceResult>>(emptyList()) }
    LaunchedEffect(typed) {
        if (typed == 0) return@LaunchedEffect
        val q = model.placeName.trim()
        if (q.length < 3) {
            found = emptyList()
            return@LaunchedEffect
        }
        delay(PLACE_SEARCH_DELAY_MS)
        // Near the note's pin, else the last route point (the Android editor's typePlace).
        val near = model.lat?.let { la -> model.lng?.let { LatLng(la, it) } }
            ?: tracking?.latestPoint()?.let { LatLng(it.lat, it.lng) }
        found = try {
            web.places.search(q, near).take(PLACES_FOUND)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    val known = remember(model.date) { model.knownPlaces() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Entry details", style = Bt.text.title)
            Eyebrow("When")
            BtGhostButton(Dates.dayLong(model.date), { pickDate = true }, Modifier.fillMaxWidth(), icon = BtIcons.Calendar)
            BtGhostButton(clockAt(model.occurredAt, model.zoneId) ?: Dates.time(model.minutes), { pickTime = true }, Modifier.fillMaxWidth(), icon = BtIcons.Time)
            model.photoTimeOffer?.let { t ->
                BtChip("${model.photoTimeLabel} · ${clockAt(t, model.zoneId).orEmpty()}", false, { model.usePhotoTime() }, icon = BtIcons.Camera)
            }
            Eyebrow("Where", Modifier.padding(top = 8.dp))
            BtSwitchRow("No place", "This note stays off the map", model.noPlace, { on ->
                model.pickNoPlace(on)
                if (on) found = emptyList()
            })
            if (!model.noPlace) {
                PlaceField(model.placeName, {
                    model.typePlace(it)
                    typed++
                }, "Place, or search the map")
                FoundPlaces(found) {
                    model.pickFound(it)
                    found = emptyList()
                }
                KnownPlaces(known, model.placeName) {
                    model.pickPlace(it, known)
                    found = emptyList()
                }
                UseMyLocation(model, onFound = { found = emptyList() })
            }
            BtButton("Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
    if (pickDate) DayDialog(model.date, onDismiss = { pickDate = false }) {
        model.setDate(it)
        pickDate = false
    }
    if (pickTime) ClockDialog(null, model.minutes, onDismiss = { pickTime = false }) { model.setTime(it) }
}

/**
 * "Use my location": the location question comes on this tap only, then where the phone is, else the last route point
 * (the Android editor's locate), named by the phone's geocoder ("Couldn't find where you are…", "Location isn't
 * allowed…", "Couldn't name this place…" when it can't).
 */
@Composable
private fun UseMyLocation(model: WriteModel, onFound: () -> Unit) {
    val web = LocalWeb.current
    val tracking = LocalTracking.current
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    fun locate() {
        if (locating) return
        locating = true
        note = null
        onFound()
        scope.launch {
            try {
                val here = runCatching { web.location.current() }.getOrNull()
                    ?: tracking?.latestPoint()?.let { LatLng(it.lat, it.lng) }
                if (here == null) {
                    note = if (web.location.allowed()) "Couldn't find where you are. Type the place." else "Location isn't allowed. Type the place."
                    return@launch
                }
                val name = runCatching { web.location.address(here.lat, here.lng, 8_000) }.getOrNull()?.let(GeoNames::noteLabel)
                model.located(here, name)
                if (name == null) note = "Couldn't name this place. Type it in."
            } finally {
                locating = false
            }
        }
    }
    BtGhostButton(
        if (locating) "Finding your location…" else "Use my location",
        { if (web.location.allowed()) locate() else web.location.ask { locate() } },
        Modifier.fillMaxWidth(),
        icon = BtIcons.MyLocation,
        enabled = !locating,
    )
    note?.let { Text(it, style = Bt.text.sub, color = Bt.colors.muted) }
}

/** A plain one-line field on a chip-coloured background. */
@Composable
internal fun PlaceField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val c = Bt.colors
    Box(Modifier.fillMaxWidth().clip(btControlShape()).background(c.chip).padding(horizontal = 16.dp, vertical = 13.dp)) {
        if (value.isEmpty()) Text(placeholder, style = Bt.text.body, color = c.muted)
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.replace("\n", " ")) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = Bt.text.body.copy(color = c.ink),
            cursorBrush = SolidColor(c.route),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
    }
}

/**
 * "From this trip": the places the trip already has, as chips to pick from, the day's plan items by name first; the
 * ones matching what is typed come first, and the chosen one is marked. Nothing shows outside a trip or for a trip
 * with no places yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KnownPlaces(places: List<KnownPlace>, typed: String, onPick: (KnownPlace) -> Unit) {
    if (places.isEmpty()) return
    val shown = remember(places, typed) { TripPlaces.shown(places, typed, KNOWN_SHOWN) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("From this trip", style = Bt.text.fine)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { p ->
                BtChip(p.name, p.name.equals(typed.trim(), ignoreCase = true), { onPick(p) }, icon = BtIcons.Place)
            }
        }
    }
}

/** Places the map search found for what is typed: name and area; a tap takes the place and its pin. */
@Composable
internal fun FoundPlaces(found: List<PlaceResult>, onPick: (PlaceResult) -> Unit) {
    if (found.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("On the map", style = Bt.text.fine)
        found.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clip(btControlShape()).clickable(role = Role.Button) { onPick(p) }.padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(BtIcons.Place, null, Modifier.size(18.dp), tint = Bt.colors.muted)
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = Bt.text.item, maxLines = 1)
                    if (p.displayName != p.name) Text(p.displayName, style = Bt.text.sub, maxLines = 1)
                }
            }
        }
    }
}

/** Name a place chip: type it, pick one of the trip's own ([known]), or keep the note's place. Remove takes a chip away. */
@Composable
internal fun PlaceDialog(
    initial: String, onSave: (String) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit, known: List<KnownPlace> = emptyList(),
) {
    val c = Bt.colors
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Place", style = Bt.text.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlaceField(name, { name = it }, "Name of the place")
                KnownPlaces(known, name) { name = it.name }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) else onDismiss() }) {
                Text("Save", style = Bt.text.button, color = c.ink)
            }
        },
        dismissButton = {
            if (onRemove != null) {
                TextButton(onClick = onRemove) { Text("Remove", style = Bt.text.button, color = c.danger) }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel", style = Bt.text.button, color = c.muted) }
            }
        },
    )
}
