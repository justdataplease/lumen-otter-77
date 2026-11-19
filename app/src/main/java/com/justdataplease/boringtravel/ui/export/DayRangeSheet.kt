package com.justdataplease.boringtravel.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtRadioRow
import com.justdataplease.boringtravel.ui.theme.Bt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Pages: the whole trip, or a first and last day picked on a slider. Null [from]/[to] = whole trip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayRangeSheet(
    start: LocalDate,
    end: LocalDate,
    from: LocalDate?,
    to: LocalDate?,
    onDone: (from: LocalDate?, to: LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Bt.colors
    val days = (end.toEpochDay() - start.toEpochDay() + 1).toInt().coerceAtLeast(1)
    var whole by remember { mutableStateOf(from == null && to == null) }
    var range by remember {
        val a = from?.let { (it.toEpochDay() - start.toEpochDay()).toFloat() } ?: 0f
        val b = to?.let { (it.toEpochDay() - start.toEpochDay()).toFloat() } ?: (days - 1).toFloat()
        mutableStateOf(a.coerceIn(0f, (days - 1).toFloat())..b.coerceIn(0f, (days - 1).toFloat()))
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Pages", style = Bt.text.title)
            BtRadioRow("Whole trip", if (days == 1) "1 day" else "All $days days", whole, onClick = { whole = true })
            if (days > 1) BtRadioRow("Choose days", "Pick the first and the last day.", !whole, onClick = { whole = false })
            if (!whole && days > 1) {
                RangeSlider(
                    value = range,
                    onValueChange = { range = it.start.roundToInt().toFloat()..it.endInclusive.roundToInt().toFloat() },
                    valueRange = 0f..(days - 1).toFloat(),
                    steps = (days - 2).coerceAtLeast(0),
                    colors = SliderDefaults.colors(
                        thumbColor = c.primary,
                        activeTrackColor = c.primary,
                        inactiveTrackColor = c.line,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                    ),
                )
                val a = start.plusDays(range.start.roundToInt().toLong())
                val b = start.plusDays(range.endInclusive.roundToInt().toLong())
                Text(label(start, a) + if (a != b) "  –  " + label(start, b) else "", style = Bt.text.mono)
            }
            BtButton(
                "Done",
                onClick = {
                    if (whole || days == 1) onDone(null, null)
                    else onDone(start.plusDays(range.start.roundToInt().toLong()), start.plusDays(range.endInclusive.roundToInt().toLong()))
                },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
    }
}

private fun label(start: LocalDate, d: LocalDate) =
    "Day ${d.toEpochDay() - start.toEpochDay() + 1} · ${DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(d)}"
