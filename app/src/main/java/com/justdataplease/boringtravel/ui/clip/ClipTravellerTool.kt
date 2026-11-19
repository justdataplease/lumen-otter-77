package com.justdataplease.boringtravel.ui.clip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.boringtravel.clip.ClipSource
import com.justdataplease.boringtravel.clip.PaperVehicle3D
import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.clip.Who
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.studio.StudioButton
import com.justdataplease.boringtravel.ui.studio.StudioColorChoices
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioNote
import com.justdataplease.boringtravel.ui.studio.StudioSection
import com.justdataplease.boringtravel.ui.studio.StudioSegmented
import com.justdataplease.boringtravel.ui.studio.StudioText
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.traveller.RideChoices
import com.justdataplease.boringtravel.ui.traveller.RideFigure
import com.justdataplease.boringtravel.ui.traveller.TravellerChoices
import com.justdataplease.boringtravel.ui.traveller.TravellerFigure
import com.justdataplease.boringtravel.ui.traveller.rideThemeColours

private val S = StudioColors

/** The Traveller tool's two halves: the people who walk the legs on foot, and how the vehicles look. */
internal enum class AvatarTab(val label: String) { People("People"), Rides("Rides") }

/**
 * STYLE › Traveller: who walks this video's legs on foot and how its vehicles look, for this video only (kept with
 * the trip's edits); Me › Your traveller & rides is the default. One undo step per pick. [tab]: only that half, for a
 * host that puts them in different places (a Route tool's Rides); null shows both under People | Rides. [vehicle]:
 * the ride picked first (else the first one the route uses).
 */
@Composable
internal fun TravellerAll(ed: ClipEditing, tab: AvatarTab? = null, vehicle: Vehicle? = null) {
    if (tab != null) {
        when (tab) {
            AvatarTab.People -> PeopleAll(ed)
            AvatarTab.Rides -> RidesAll(ed, vehicle)
        }
        return
    }
    var shown by rememberSaveable { mutableStateOf(if (vehicle != null) AvatarTab.Rides else AvatarTab.People) }
    StudioSegmented(AvatarTab.entries.map { it.label }, shown.ordinal, { i -> shown = AvatarTab.entries[i] })
    when (shown) {
        AvatarTab.People -> PeopleAll(ed)
        AvatarTab.Rides -> RidesAll(ed, vehicle)
    }
}

/** Who walks this video's legs on foot (the man, the woman, or both hand in hand) and their look. */
@Composable
internal fun PeopleAll(ed: ClipEditing) {
    val d = ed.design
    val traveller = d.travellerShown
    val own = d.traveller != null
    var editing by rememberSaveable { mutableStateOf(Who.Man) }
    fun set(t: Traveller) = ed.vm.setTraveller(t)
    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(S.stage), contentAlignment = Alignment.Center) {
        TravellerFigure(traveller, Modifier.fillMaxWidth().height(140.dp))
        Text(if (own) "THIS VIDEO" else "FROM ME", Modifier.align(Alignment.TopStart).padding(10.dp), style = StudioText.label.copy(color = if (own) S.yellow else S.muted))
    }
    if (Vehicle.Walk !in ed.plan.vehiclesUsed) {
        val source by ed.vm.source.collectAsStateWithLifecycle()
        StudioNote(
            if (source == ClipSource.Recorded) "No stretch of this journey is on foot. Pick Walk in Route to see your traveller."
            else "No leg of this route is on foot. Set a leg to Walk in the plan to see your traveller.",
        )
    }
    Chips("Who", Who.entries, traveller.who, { it.label }, ed.enabled) { v -> set(traveller.copy(who = v)) }
    if (traveller.who == Who.Couple) {
        StudioSection("Change") {
            StudioSegmented(listOf("Man", "Woman"), if (editing == Who.Woman) 1 else 0, { i -> editing = if (i == 1) Who.Woman else Who.Man })
        }
    }
    val slot = TravellerChoices.slot(traveller, editing)
    val person = traveller.person(slot)
    TravellerChoices.rows(person).forEach { row ->
        val pick: (Int) -> Unit = { i -> set(traveller.change(slot) { row.pick(it, i) }) }
        if (row.swatches) {
            StudioSection(row.label, value = row.choices.getOrNull(row.selected)?.label) {
                StudioColorChoices(row.choices.map { Color(it.argb ?: 0) }, row.choices.map { it.label }, row.selected, pick, ed.enabled)
            }
        } else {
            Chips(row.label, row.choices.indices.toList(), row.selected, { row.choices[it].label }, ed.enabled, columns = 3, onPick = pick)
        }
    }
    StudioSection("Every video") {
        if (own) {
            StudioNote("This video has its own traveller. Me › Your traveller & rides is the default for the others.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioButton("Use my default", { ed.vm.setTraveller(null) }, Modifier.weight(1f), enabled = ed.enabled)
                StudioButton("Make default", ed.vm::makeTravellerDefault, Modifier.weight(1f), enabled = ed.enabled)
            }
        } else {
            StudioNote("Your traveller from Me › Your traveller & rides. Changes here are for this video only.")
        }
    }
}

/**
 * How this video's vehicles look: pick a vehicle, then its model and its Body and Trim colours ("Theme": the look's
 * own). [vehicle]: the one picked first (else the first one the route uses).
 */
@Composable
internal fun RidesAll(ed: ClipEditing, vehicle: Vehicle? = null) {
    val d = ed.design
    val rides = d.ridesShown
    val own = d.rides != null
    val used = ed.plan.vehiclesUsed
    var picked by rememberSaveable(vehicle) {
        mutableStateOf(vehicle?.takeIf { it in RideChoices.vehicles } ?: RideChoices.vehicles.firstOrNull { it in used } ?: Vehicle.Car)
    }
    val ride = rides[picked] ?: return
    val tokens = Bt.tokens
    val palette = remember(tokens) { PaperVehicle3D.Palette.of(tokens) }
    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(S.stage), contentAlignment = Alignment.Center) {
        RideFigure(ride, Modifier.fillMaxWidth().height(140.dp))
        Text(if (own) "THIS VIDEO" else "FROM ME", Modifier.align(Alignment.TopStart).padding(10.dp), style = StudioText.label.copy(color = if (own) S.yellow else S.muted))
    }
    Chips("Vehicle", RideChoices.vehicles, picked, { RideChoices.label(it) }, enabled = true, columns = RideChoices.vehicles.size) { picked = it }
    if (picked !in used) {
        val source by ed.vm.source.collectAsStateWithLifecycle()
        val word = RideChoices.label(picked).lowercase()
        StudioNote(
            if (source == ClipSource.Recorded) "No stretch of this journey is by $word. Pick ${picked.label} in Route to see it."
            else "No leg of this route is by $word. Set a leg to ${picked.label} in the plan to see it.",
        )
    }
    val (themeBody, themeTrim) = rideThemeColours(ride, palette)
    RideChoices.rows(ride, themeBody, themeTrim).forEach { row ->
        val pick: (Int) -> Unit = { i -> ed.vm.setRides(rides.with(row.pick(ride, i))) }
        if (row.swatches) {
            StudioSection(row.label, value = row.choices.getOrNull(row.selected)?.label) {
                StudioColorChoices(row.choices.map { Color(it.argb ?: 0) }, row.choices.map { it.label }, row.selected, pick, ed.enabled)
            }
        } else {
            Chips(row.label, row.choices.indices.toList(), row.selected, { row.choices[it].label }, ed.enabled, columns = 2, onPick = pick)
        }
    }
    StudioSection("Every video") {
        if (own) {
            StudioNote("This video has its own rides. Me › Your traveller & rides is the default for the others.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioButton("Use my default", { ed.vm.setRides(null) }, Modifier.weight(1f), enabled = ed.enabled)
                StudioButton("Make default", ed.vm::makeRidesDefault, Modifier.weight(1f), enabled = ed.enabled)
            }
        } else {
            StudioNote("Your rides from Me › Your traveller & rides. Changes here are for this video only.")
        }
    }
}
