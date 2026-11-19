package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.clip.Geo
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.TravelTime
import com.justdataplease.boringtravel.model.Vehicle
import kotlin.math.roundToInt

/**
 * A rough door-to-door time for a leg, from the straight-line distance and the vehicle ([TravelTime]: a detour factor
 * for the way roads and paths wind, a typical speed, and a fixed overhead for flights; the trip clip times its legs
 * with the same numbers). Always shown with "≈"; never stored.
 */
object LegEstimate {
    /** Minutes for [distanceM] by [vehicle], or null for no vehicle or no distance. */
    fun minutes(vehicle: Vehicle, distanceM: Double): Int? = TravelTime.minutes(vehicle, distanceM)?.roundToInt()

    /** Rounded for reading: 5 min steps under an hour, quarters under three hours, whole hours after. */
    fun rounded(minutes: Int): Int = when {
        minutes < 60 -> ((minutes + 2) / 5 * 5).coerceAtLeast(5)
        minutes < 180 -> ((minutes + 7) / 15) * 15
        else -> ((minutes + 30) / 60) * 60
    }

    /** "≈ 15 min", "≈ 1 h 30", "≈ 3 h". */
    fun short(minutes: Int): String {
        val m = rounded(minutes)
        return when {
            m < 60 -> "≈ $m min"
            m % 60 == 0 -> "≈ ${m / 60} h"
            else -> "≈ ${m / 60} h ${"%02d".format(m % 60)}"
        }
    }

    /** "About 3 hours by road", "About 40 minutes on foot", "About 2 h 30 by air". */
    fun sentence(minutes: Int, vehicle: Vehicle): String {
        val m = rounded(minutes)
        val time = when {
            m < 60 -> "$m minutes"
            m == 60 -> "1 hour"
            m % 60 == 0 -> "${m / 60} hours"
            else -> "${m / 60} h ${"%02d".format(m % 60)}"
        }
        val how = when (vehicle) {
            Vehicle.Walk -> "on foot"
            Vehicle.Bike -> "by bike"
            Vehicle.Car -> "by road"
            Vehicle.Train -> "by train"
            Vehicle.Ferry -> "by boat"
            Vehicle.Plane -> "by air"
            Vehicle.Auto -> ""
        }
        return "About $time $how".trim()
    }
}

/**
 * What a leg row between two plan items shows: the vehicle chosen for the leg (or none yet) and a rough time,
 * e.g. "Car · ≈ 3 h", "Walk · ≈ 20 min", "Choose travel". [fromLabel] is set only when the leg starts on another day.
 */
internal data class LegRowModel(val vehicle: Vehicle?, val estimate: String?, val fromLabel: String?, val countsKm: Boolean = true) {
    /** The small words after the chip: where it starts from (another day) and "not in km" for a leg left out. */
    val aside: String? get() = listOfNotNull(fromLabel?.let { "from $it" }, "not in km".takeUnless { countsKm }).joinToString(" · ").ifEmpty { null }

    val text: String get() = when {
        vehicle == null -> "Choose travel"
        estimate == null -> vehicle.label
        else -> "${vehicle.label} · $estimate"
    }

    companion object {
        fun of(connection: PlanTravelConnection, from: PlanItem?, to: PlanItem?): LegRowModel {
            val mode = connection.mode
            val distance = if (from != null && to != null) distance(from, to) else null
            val estimate = if (distance != null) LegEstimate.minutes(mode, distance)?.let(LegEstimate::short) else null
            return LegRowModel(mode, estimate, connection.fromLabel.takeIf { connection.originDay != null }, connection.leg.countsKm)
        }

        private fun distance(a: PlanItem, b: PlanItem): Double? {
            val aLat = a.lat ?: return null
            val aLng = a.lng ?: return null
            val bLat = b.lat ?: return null
            val bLng = b.lng ?: return null
            return Geo.distanceM(aLat, aLng, bLat, bLng)
        }
    }
}
