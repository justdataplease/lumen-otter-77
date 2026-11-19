package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DateRangePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates

/*
 * The date and time pickers, as the Android screens open them: Material 3's own, which Compose draws the same on both
 * phones (not the platforms' pickers, which differ). Material counts a day as its UTC midnight in milliseconds. Their
 * month and weekday names are in the phone's language ([phoneCalendarLocale]), as Android's pickers take it.
 */

/**
 * A trip's dates (Edit trip's Dates): the first and last day on one calendar. Done takes them (one day picked: a
 * one-day trip); "No dates yet" makes it a someday trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDatesDialog(start: LocalDate?, end: LocalDate?, onPick: (LocalDate?, LocalDate?) -> Unit, onDismiss: () -> Unit) {
    val state = remember {
        DateRangePickerState(phoneCalendarLocale(), initialSelectedStartDateMillis = start?.utcMillis(), initialSelectedEndDateMillis = end?.utcMillis())
    }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val first = state.selectedStartDateMillis?.let(::utcDay)
                    onPick(first, (state.selectedEndDateMillis ?: state.selectedStartDateMillis)?.let(::utcDay))
                },
            ) { Text("Done") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null, null) }) { Text("No dates yet") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    ) { DateRangePicker(state, Modifier.weight(1f)) }
}

/**
 * The first day of a trip made from an idea ([days] long): the headline asks for it, then shows the whole trip once a
 * day is picked ("3–14 Oct 2027"). "Make the trip" stays faded until then.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartDateDialog(days: Int, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit, initial: LocalDate? = null) {
    val state = remember { DatePickerState(phoneCalendarLocale(), initialSelectedDateMillis = initial?.utcMillis()) }
    val start = state.selectedDateMillis?.let(::utcDay)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            // The label's colour is set here: the theme's label style carries its own ink, which hid the disabled look.
            TextButton(enabled = start != null, onClick = { start?.let(onPick) }) {
                Text("Make the trip", color = if (start != null) Bt.colors.primary else Bt.colors.muted.copy(alpha = 0.45f))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(
            state,
            title = { Text(Dates.days(days), Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp), style = Bt.text.sub) },
            headline = {
                Text(
                    start?.let { Dates.range(it, it.plusDays((days - 1).coerceAtLeast(0).toLong())) } ?: "Pick the first day",
                    Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp), style = Bt.text.title,
                )
            },
        )
    }
}

/**
 * One day (a note's day, in its details): the month of [day] with it picked; Done hands the day picked to [onPick].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDialog(day: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = remember { DatePickerState(phoneCalendarLocale(), initialSelectedDateMillis = day.utcMillis()) }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let(::utcDay)?.let(onPick) ?: onDismiss() }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

/**
 * The clock for a time: [title] over it (none: the clock alone), starting at [minutes]; Done hands the chosen minutes to
 * [onPick]. On the phone's own 12- or 24-hour clock ([Phone.uses24Hour]), as the Android app's follows the phone's
 * setting; the plan writes its times on a 24-hour clock either way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockDialog(title: String?, minutes: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = LocalPhone.current.uses24Hour)
    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(state.hour * 60 + state.minute)
                onDismiss()
            }) { Text("Done") }
        },
        title = { if (title != null) Text(title, style = Bt.text.item) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { TimePicker(state) }
}
