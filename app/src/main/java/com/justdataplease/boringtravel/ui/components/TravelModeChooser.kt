package com.justdataplease.boringtravel.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.clip.PaperVehicle3D
import com.justdataplease.boringtravel.clip.VehicleSprites
import com.justdataplease.boringtravel.graph
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** One explicit choice for a single journey leg. Null means the traveller has not chosen yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TravelModeChooser(
    fromLabel: String,
    toLabel: String,
    selected: Vehicle?,
    onPick: (Vehicle?) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    error: String? = null,
    sprites: Map<Vehicle, Bitmap> = emptyMap(),
    /** Whether the leg counts in the trip's km (null: no switch), its line, and the switch's change. */
    countedKm: Boolean? = null,
    kmLine: String = "",
    onCountKm: (Boolean) -> Unit = {},
) {
    // The vehicles in the current theme's colours, as Me's rides (the replay's own models; see VehicleSprites).
    val palette = PaperVehicle3D.Palette.of(Bt.tokens)
    val context = LocalContext.current
    var artwork by remember { mutableStateOf(sprites) }
    LaunchedEffect(palette, sprites) {
        artwork = sprites.ifEmpty {
            try { VehicleSprites.load(palette, context.graph.settings.rides.first()) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyMap() }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bt.colors.surface,
        contentColor = Bt.colors.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Travel for this leg", style = Bt.text.h2)
            Text("$fromLabel → $toLabel", style = Bt.text.body)
            if (error != null) Text(error, style = Bt.text.sub, color = Bt.colors.danger)
            Vehicle.entries.filter { it != Vehicle.Auto }.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { vehicle ->
                        val chosen = selected == vehicle
                        val shape = btCardShape()
                        Column(
                            Modifier.weight(1f).heightIn(min = 94.dp).clip(shape)
                                .background(if (chosen) Bt.colors.chipOn else Bt.colors.bg)
                                .border(1.dp, if (chosen) Bt.colors.primary else Bt.colors.line, shape)
                                .semantics { this.selected = chosen }
                                .clickable(enabled = enabled, role = Role.RadioButton, onClickLabel = vehicle.label) { onPick(vehicle) }
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            TravelModeIcon(vehicle, Modifier.size(48.dp), artwork[vehicle])
                            Text(vehicle.label, style = Bt.text.label, color = if (chosen) Bt.colors.onChipOn else Bt.colors.ink)
                        }
                    }
                }
            }
            if (countedKm != null) BtSwitchRow("Count in km", kmLine, countedKm, onCountKm, enabled = enabled)
            if (selected != null && selected != Vehicle.Auto) {
                BtGhostButton("Leave unselected", { onPick(null) }, Modifier.fillMaxWidth(), enabled = enabled)
            }
            if (error != null) BtGhostButton("Keep saved choice", onDismiss, Modifier.fillMaxWidth())
        }
    }
}

/** The paper vehicle (in the theme's colours) for plan rows and the clip's leg list. */
@Composable
fun TravelModeIcon(vehicle: Vehicle, modifier: Modifier = Modifier.size(40.dp), sprite: Bitmap? = null) {
    if (sprite != null) {
        Image(remember(sprite) { sprite.asImageBitmap() }, null, modifier, contentScale = ContentScale.Fit)
    } else {
        // Never construct replay geometry on the UI thread while the cached artwork is loading.
        Icon(BtIcons.Route, null, modifier, tint = Bt.colors.muted)
    }
}
