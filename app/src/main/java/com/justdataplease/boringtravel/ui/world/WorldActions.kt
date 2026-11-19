package com.justdataplease.boringtravel.ui.world

import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.geo.CountryInfo

object WorldActions {
    /**
     * "Plan a trip here": a someday trip named after the country with one idea placed in it, so the globe shows the
     * country as planned straight away. Returns the new trip's id.
     */
    suspend fun planTripIn(graph: AppGraph, code: String): String {
        val name = CountryInfo.name(code)
        val trip = graph.trips.create(name, null, null)
        val item = graph.plan.add(trip.id, name, day = null, kind = "place")
        CountryInfo.labelPoint(code)?.let { (lat, lng) -> graph.plan.save(item.copy(placeName = name, lat = lat, lng = lng)) }
        return trip.id
    }
}
