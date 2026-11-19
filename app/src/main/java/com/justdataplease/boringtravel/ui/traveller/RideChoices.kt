package com.justdataplease.boringtravel.ui.traveller

import com.justdataplease.boringtravel.clip.Cloth
import com.justdataplease.boringtravel.clip.Ride
import com.justdataplease.boringtravel.clip.RideModel
import com.justdataplease.boringtravel.clip.Rides
import com.justdataplease.boringtravel.model.Vehicle

/**
 * One row of a ride's options (Me › Your traveller & rides and the editor's Traveller tool draw the same rows in
 * their own controls): its [label], the [choices] (colour swatches when [swatches]), the one [selected], and how
 * picking one changes the ride.
 */
internal class RideRow(
    val key: String,
    val label: String,
    val choices: List<TravellerChoice>,
    val selected: Int,
    val swatches: Boolean,
    private val apply: (Ride, Int) -> Ride,
) {
    /** [r] with choice [index] picked (an index off the row changes nothing). */
    fun pick(r: Ride, index: Int): Ride = if (index in choices.indices) apply(r, index) else r
}

/** A ride's options as rows: the model, then its Body and Trim colours ("Theme" first: today's look). Pure. */
internal object RideChoices {
    /** The vehicles with rides, as the editors list them. */
    val vehicles: List<Vehicle> get() = Rides.VEHICLES

    /** What a vehicle's rides are called on the tabs (the ferry's include sailing and speed boats). */
    fun label(v: Vehicle): String = if (v == Vehicle.Ferry) "Boat" else v.label

    /** The colours a ride can take: the theme's (null) and the traveller's cloth swatches. */
    val colours: List<Cloth?> = listOf<Cloth?>(null) + Cloth.entries

    /** [themeBody] and [themeTrim]: what the "Theme" swatches show (the ride's own parts in the theme). */
    fun rows(ride: Ride, themeBody: Int, themeTrim: Int): List<RideRow> {
        val models = RideModel.of(ride.vehicle)
        fun colourRow(key: String, label: String, current: Cloth?, theme: Int, set: (Ride, Cloth?) -> Ride) = RideRow(
            key, label, colours.map { TravellerChoice(it?.label ?: "Theme", it?.argb ?: theme) }, colours.indexOf(current), true,
        ) { r, i -> set(r, colours[i]) }
        return listOf(
            RideRow("model", "Model", models.map { TravellerChoice(it.label) }, models.indexOf(ride.model), false) { r, i ->
                // Another model keeps the colours picked.
                r.copy(model = models[i])
            },
            colourRow("body", "Body", ride.body, themeBody) { r, c -> r.copy(body = c) },
            colourRow("trim", "Trim", ride.trim, themeTrim) { r, c -> r.copy(trim = c) },
        )
    }

    /** "SUV, Sailboat": the rides changed from today's look, or null when none is. */
    fun summary(rides: Rides): String? =
        rides.all.filterNot { it.isDefault }.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.model.label }
}
