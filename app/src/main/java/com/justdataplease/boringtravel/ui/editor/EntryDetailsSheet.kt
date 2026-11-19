package com.justdataplease.boringtravel.ui.editor

import com.justdataplease.boringtravel.ui.components.BtSwitchRow
import android.Manifest
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.justdataplease.boringtravel.tracking.TrackingPermissions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.places.KnownPlace
import com.justdataplease.boringtravel.places.TripPlaces
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import java.time.Instant
import java.time.ZoneOffset

/** When and where: date, time, place (typed or from the route), and the first photo's time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryDetailsSheet(vm: WriteViewModel, onDismiss: () -> Unit) {
    val c = Bt.colors
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Entry details", style = Bt.text.title)
            Eyebrow("When")
            BtGhostButton(Dates.dayLong(vm.date), { pickDate = true }, Modifier.fillMaxWidth(), icon = BtIcons.Calendar)
            BtGhostButton(Dates.time(vm.occurredAt, vm.zoneId), { pickTime = true }, Modifier.fillMaxWidth(), icon = BtIcons.Time)
            vm.photoTimeOffer?.let { t ->
                BtChip("${vm.photoTimeLabel} · ${Dates.time(t, vm.zoneId)}", false, { vm.usePhotoTime() }, icon = BtIcons.Camera)
            }
            Eyebrow("Where", Modifier.padding(top = 8.dp))
            BtSwitchRow("No place", "This note stays off the map", vm.noPlace, vm::pickNoPlace)
            if (!vm.noPlace) {
                PlaceField(vm.placeName, vm::typePlace, "Place, or search the map")
                FoundPlaces(vm.foundPlaces, vm::pickFound)
                KnownPlaces(vm.knownPlaces, vm.placeName, vm::pickPlace)
                UseMyLocation(vm)
            }
            BtButton("Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = vm.date.toEpochDay() * DAY_MS)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { vm.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickDate = false
                }) { Text("OK", style = Bt.text.button, color = c.ink) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel", style = Bt.text.button, color = c.muted) } },
        ) { DatePicker(state) }
    }
    if (pickTime) {
        val context = LocalContext.current
        val now = remember { Instant.ofEpochMilli(vm.occurredAt).atZone(vm.zone).toLocalTime() }
        val state = rememberTimePickerState(now.hour, now.minute, DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickTime = false },
            containerColor = c.surface,
            confirmButton = {
                TextButton(onClick = {
                    vm.setTime(state.hour, state.minute)
                    pickTime = false
                }) { Text("OK", style = Bt.text.button, color = c.ink) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel", style = Bt.text.button, color = c.muted) } },
            text = { TimePicker(state) },
        )
    }
}

private const val DAY_MS = 86_400_000L

/**
 * "Use my location": Android's location question comes on this tap only (owner, 2026-11-12: "no need to ask when app
 * starts"), approximate is enough; then where the phone is, else the latest route point ([WriteViewModel.locate]).
 */
@Composable
private fun UseMyLocation(vm: WriteViewModel) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.locate() }
    BtGhostButton(
        if (vm.locating) "Finding your location…" else "Use my location",
        {
            if (TrackingPermissions.hasLocation(context)) vm.locate()
            else ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        Modifier.fillMaxWidth(),
        icon = BtIcons.MyLocation,
        enabled = !vm.locating,
    )
    vm.locationNote?.let { Text(it, style = Bt.text.sub, color = Bt.colors.muted) }
}

/** A plain one-line field on a chip-coloured background. */
@Composable
internal fun PlaceField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val c = Bt.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(btControlShape())
            .background(c.chip)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
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
 * "From this trip": the places the trip already has, as chips to pick from (owner, 2026-11-10: "when writing a note
 * should be able to select a place from existing"), the day's plan items by name first; the ones matching what is
 * typed come first, out of all of them, and the chosen one is marked. Nothing shows outside a trip or for a trip with
 * no places yet.
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
internal fun FoundPlaces(found: List<com.justdataplease.boringtravel.places.PlaceResult>, onPick: (com.justdataplease.boringtravel.places.PlaceResult) -> Unit) {
    if (found.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("On the map", style = Bt.text.fine)
        found.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clip(btControlShape()).clickable(role = androidx.compose.ui.semantics.Role.Button) { onPick(p) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                androidx.compose.material3.Icon(BtIcons.Place, null, Modifier.size(18.dp), tint = Bt.colors.muted)
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = Bt.text.item, maxLines = 1)
                    if (p.displayName != p.name) Text(p.displayName, style = Bt.text.sub, maxLines = 1)
                }
            }
        }
    }
}

/** The most places offered at once (about three rows of chips), besides the day's plan items. */
private const val KNOWN_SHOWN = 12

/** Name a place chip: type it, pick one of the trip's own ([known]), or keep the entry's place. */
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

