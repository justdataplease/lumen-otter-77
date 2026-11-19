package com.justdataplease.boringtravel.ui.tripcal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.tripcal.TripAnalytics
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates

/** Overlapping trips, one small line each ("Kyoto and Lisbon overlap · 3–5 Apr 2027"). */
@Composable
internal fun OverlapLines(overlaps: List<TripAnalytics.Overlap>, modifier: Modifier = Modifier) {
    if (overlaps.isEmpty()) return
    val c = Bt.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        overlaps.take(3).forEach { o ->
            Text(
                "${o.first.title} and ${o.second.title} overlap · ${Dates.range(o.from, o.to)}",
                style = Bt.text.fine, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (overlaps.size > 3) Text("${overlaps.size - 3} more overlaps", style = Bt.text.fine, color = c.danger)
    }
}
