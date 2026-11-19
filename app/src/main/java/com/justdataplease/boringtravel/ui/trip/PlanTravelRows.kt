package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.clip.RouteLeg
import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.Vehicle
import java.time.LocalDate

/** One incoming connection, attached to its destination's stable plan-item ID. */
internal data class PlanTravelConnection(val leg: RouteLeg) {
    val fromLabel: String get() = leg.fromTitle.ifBlank { "Previous place" }
    val toLabel: String get() = leg.toTitle.ifBlank { "Next place" }
    /** The chosen vehicle, else the default (Car unless the destination says otherwise, see [TravelDefault]). */
    val mode: Vehicle get() = leg.travel
    /** Retain prior-day context when this is the first destination on the currently displayed day. */
    val originDay: LocalDate? get() = leg.fromDay?.takeIf { it != leg.toDay }
}

internal object PlanTravelRows {
    /** Build across the whole trip before filtering the displayed day; replay owns ordering/location rules. */
    fun incoming(trip: Trip, items: List<PlanItem>): Map<String, PlanTravelConnection> =
        RouteLegs.planned(trip, items).associate { it.key.toId to PlanTravelConnection(it) }

    /** An open chooser must never drift onto a new origin after a concurrent move, deletion or AI amendment. */
    fun resolve(incoming: Map<String, PlanTravelConnection>, key: RouteLegKey?): PlanTravelConnection? =
        key?.let { incoming[it.toId]?.takeIf { row -> row.leg.key == it } }
}
