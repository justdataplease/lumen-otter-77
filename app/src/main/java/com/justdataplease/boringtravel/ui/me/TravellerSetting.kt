package com.justdataplease.boringtravel.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.clip.PaperVehicle3D
import com.justdataplease.boringtravel.clip.Ride
import com.justdataplease.boringtravel.clip.Rides
import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.clip.Who
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtSegmented
import com.justdataplease.boringtravel.ui.components.btCardShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.traveller.RideChoices
import com.justdataplease.boringtravel.ui.traveller.RideFigure
import com.justdataplease.boringtravel.ui.traveller.TravellerChoice
import com.justdataplease.boringtravel.ui.traveller.TravellerChoices
import com.justdataplease.boringtravel.ui.traveller.TravellerFigure
import com.justdataplease.boringtravel.ui.traveller.rideThemeColours
import kotlinx.coroutines.launch

/**
 * Me › Your traveller & rides: who walks the legs on foot in every trip video (owner: "be able to select from settings
 * customize avatars for video") and how every vehicle looks ("all avatars editable"), with a small figure; opens
 * [TravellerSheet]. A video can still have its own (the editor's Traveller tool).
 */
@Composable
internal fun TravellerSetting(graph: AppGraph) {
    val traveller by graph.settings.traveller.collectAsStateWithLifecycle(initialValue = Traveller.DEFAULT)
    val rides by graph.settings.rides.collectAsStateWithLifecycle(initialValue = Rides.DEFAULT)
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Change your traveller and rides") { open = true }.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Your traveller & rides", style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val ridesLine = RideChoices.summary(rides) ?: "walks and rides your trip videos"
                Text("${TravellerChoices.summary(traveller)} · $ridesLine", style = Bt.text.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TravellerFigure(traveller, Modifier.size(width = if (traveller.who == Who.Couple) 64.dp else 44.dp, height = 56.dp), walking = false, turnable = false)
            Icon(BtIcons.Forward, null, Modifier.size(20.dp), tint = Bt.colors.muted)
        }
        BtDivider()
    }
    if (open) {
        val scope = rememberCoroutineScope()
        TravellerSheet(
            traveller, rides,
            onChange = { t -> scope.launch { graph.settings.setTraveller(t) } },
            onRides = { r -> scope.launch { graph.settings.setRides(r) } },
            onDismiss = { open = false },
        )
    }
}

/**
 * People: who walks and each one's look. Rides: each vehicle's model and colours. The figure stays in view above.
 * Without [rides] only the people, under [title] (the community picture's own traveller, [com.justdataplease.boringtravel.community.ProfileAvatar]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TravellerSheet(
    traveller: Traveller, rides: Rides?, onChange: (Traveller) -> Unit, onRides: (Rides) -> Unit = {}, onDismiss: () -> Unit,
    title: String = "Your traveller & rides",
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bt.colors.surface,
        contentColor = Bt.colors.ink,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 20.dp)) {
            Text(title, style = Bt.text.h2)
            if (rides != null) {
                Text(
                    if (tab == 0) "Walks the legs on foot in your trip videos." else "How each vehicle looks in your trip videos.",
                    style = Bt.text.sub, modifier = Modifier.padding(top = 4.dp),
                )
                Text("A video can have its own in its Traveller tool.", style = Bt.text.sub)
                BtSegmented(listOf("People", "Rides"), tab, onSelect = { tab = it }, modifier = Modifier.padding(top = 12.dp))
            }
            if (rides == null || tab == 0) PeoplePane(traveller, onChange) else RidesPane(rides, onRides)
        }
    }
}

@Composable
private fun ColumnScope.PeoplePane(traveller: Traveller, onChange: (Traveller) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(Who.Man) }
    // The figure stays in view while the options scroll under it.
    Box(
        Modifier.fillMaxWidth().padding(top = 12.dp).height(190.dp).clip(btCardShape()).background(Bt.colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        TravellerFigure(traveller, Modifier.fillMaxWidth().height(180.dp))
        Text("Drag to turn", Modifier.align(Alignment.BottomEnd).padding(8.dp), style = Bt.text.fine)
    }
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BtSegmented(Who.entries.map { it.label }, traveller.who.ordinal, onSelect = { i -> onChange(traveller.copy(who = Who.entries[i])) })
        if (traveller.who == Who.Couple) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Change", style = Bt.text.label, color = Bt.colors.muted)
                BtSegmented(listOf("Man", "Woman"), if (editing == Who.Woman) 1 else 0, onSelect = { i -> editing = if (i == 1) Who.Woman else Who.Man })
            }
        }
        val slot = TravellerChoices.slot(traveller, editing)
        val person = traveller.person(slot)
        TravellerChoices.rows(person).forEach { row ->
            OptionRow(row.label, row.choices, row.selected, row.swatches) { i -> onChange(traveller.change(slot) { row.pick(it, i) }) }
        }
        BtGhostButton("Back to the default look", onClick = {
            onChange(traveller.change(slot) { if (slot == Who.Woman) Traveller.DEFAULT.woman else Traveller.DEFAULT.man })
        })
    }
}

/** Pick a vehicle, then its model and its Body and Trim colours; the model turns above. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.RidesPane(rides: Rides, onChange: (Rides) -> Unit) {
    var vehicle by rememberSaveable { mutableStateOf(Vehicle.Car) }
    val ride = rides[vehicle] ?: return
    val tokens = Bt.tokens
    val palette = remember(tokens) { PaperVehicle3D.Palette.of(tokens) }
    Box(
        Modifier.fillMaxWidth().padding(top = 12.dp).height(190.dp).clip(btCardShape()).background(Bt.colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        RideFigure(ride, Modifier.fillMaxWidth().height(180.dp))
        Text("Drag to turn", Modifier.align(Alignment.BottomEnd).padding(8.dp), style = Bt.text.fine)
    }
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RideChoices.vehicles.forEach { v -> BtChip(RideChoices.label(v), v == vehicle, { vehicle = v }) }
        }
        val (themeBody, themeTrim) = rideThemeColours(ride, palette)
        RideChoices.rows(ride, themeBody, themeTrim).forEach { row ->
            OptionRow(row.label, row.choices, row.selected, row.swatches) { i -> onChange(rides.with(row.pick(ride, i))) }
        }
        if (!ride.isDefault) {
            BtGhostButton("Back to the default look", onClick = { Ride.default(vehicle)?.let { onChange(rides.with(it)) } })
        }
    }
}

/** A person's or a ride's option: its [label] over chips or colour [swatches]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionRow(label: String, choices: List<TravellerChoice>, selected: Int, swatches: Boolean, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val name = choices.getOrNull(selected)?.label
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = Bt.text.label)
            if (swatches && name != null) Text(name, style = Bt.text.sub)
        }
        if (swatches) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEachIndexed { i, c -> Swatch(Color(c.argb ?: 0), c.label, i == selected) { onPick(i) } }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEachIndexed { i, c -> BtChip(c.label, i == selected, { onPick(i) }) }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).border(2.dp, if (on) Bt.colors.ink else Color.Transparent, CircleShape)
            .semantics { contentDescription = label; selected = on }
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(color).border(1.dp, Bt.colors.line, CircleShape))
    }
}
