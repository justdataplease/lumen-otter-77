package com.justdataplease.boringtravel.ui.trip

import java.util.Locale
import com.justdataplease.boringtravel.ui.components.BtSwitchRow
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.data.db.Trip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.clip.ClipText
import com.justdataplease.boringtravel.clip.RouteLeg
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.components.copyOnLongPress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The ways to travel a leg, in the order the pickers show them. */
internal val TRAVEL_CHOICES = listOf(Vehicle.Car, Vehicle.Train, Vehicle.Plane, Vehicle.Ferry, Vehicle.Bike, Vehicle.Walk)

/**
 * "Getting there": how you reach a planned place from the one before ("From Bariloche · ≈ 2 h"), as one row of
 * vehicle chips, and whether the leg counts in the trip's km. Car unless chosen otherwise ([TravelDefault]); a tap
 * saves the choice for that leg at once.
 */
@Composable
internal fun GettingThere(
    graph: AppGraph,
    tripId: String,
    leg: RouteLeg,
    selected: Vehicle,
    line: String?,
    modifier: Modifier = Modifier,
) {
    val c = Bt.colors
    val key = leg.key
    var saving by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    fun save(change: suspend () -> Trip?) {
        saving = true
        error = null
        graph.scope.launch(Dispatchers.Main) {
            error = try {
                if (change() == null) "This trip is no longer available." else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                if (e.message == "This route leg changed. Reopen it and choose again.") e.message else "Couldn't save travel. Try again."
            } catch (_: Exception) {
                "Couldn't save travel. Try again."
            } finally {
                saving = false
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (line != null) Text(line, Modifier.copyOnLongPress(line, "Getting there"), style = Bt.text.sub)
        // Six equal tiles, icon over name: every choice and the chosen one in view (a scrolling row hid Walk).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TRAVEL_CHOICES.forEach { vehicle ->
                val on = vehicle == selected
                Column(
                    Modifier.weight(1f).clip(btControlShape()).background(if (on) c.chipOn else c.chip)
                        .semantics { this.selected = on }
                        .clickable(role = Role.RadioButton, onClickLabel = vehicle.label, enabled = !saving && !on) {
                            save { graph.trips.setLegVehicle(tripId, key, vehicle) }
                        }
                        .heightIn(min = 56.dp).padding(horizontal = 2.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Icon(vehicleIcon(vehicle), null, Modifier.size(20.dp), tint = if (on) c.onChipOn else c.ink)
                    Text(
                        vehicle.label,
                        style = Bt.text.fine.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (on) c.onChipOn else c.ink, maxLines = 1, softWrap = false,
                    )
                }
            }
        }
        // Owner, 2026-11-11: "a count in km in every item"; a flight is left out unless switched on.
        val counted = TravelDefault.countsKm(selected, leg.countKm)
        BtSwitchRow(
            "Count in km", legKmLine(leg.distanceM, counted), counted, enabled = !saving,
            onChange = { on -> save { graph.trips.setLegKm(tripId, key, legKmChoice(selected, on)) } },
        )
        error?.let { Text(it, style = Bt.text.fine, color = c.danger) }
    }
}

/** "2,370 km in the trip's total" or "… left out of the trip's total" (no number when the leg's length is unknown). */
internal fun legKmLine(distanceM: Double, counted: Boolean): String {
    val km = distanceM.takeIf { it.isFinite() && it > 0 }?.let { ClipText.km(it / 1000.0, Locale.getDefault()) }
    return when {
        km == null -> if (counted) "In the trip's total" else "Left out of the trip's total"
        counted -> "$km in the trip's total"
        else -> "$km, left out of the trip's total"
    }
}

/** What to save when the switch is set to [on] for a leg by [vehicle]: null when that is the default anyway. */
internal fun legKmChoice(vehicle: Vehicle, on: Boolean): Boolean? = on.takeIf { it != TravelDefault.countsKm(vehicle, null) }

/** "From Bariloche · ≈ 2 h" for a leg into a place (the time only when both ends have a location). */
internal fun gettingThereLine(fromLabel: String, vehicle: Vehicle, distanceM: Double?): String =
    listOfNotNull("From $fromLabel", distanceM?.let { LegEstimate.minutes(vehicle, it) }?.let(LegEstimate::short)).joinToString(" · ")
