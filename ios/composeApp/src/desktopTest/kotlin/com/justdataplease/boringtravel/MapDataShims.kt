package com.justdataplease.boringtravel

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ui.map.TripMapModel
import com.justdataplease.boringtravel.ui.map.TripMapView
import java.time.ZoneId

/*
 * What the Android app's MapDataTest (read in as it is by the build's androidTestSources) calls in the Android form:
 * here TripMapModel takes a time zone by its id.
 */

fun TripMapModel.build(trip: Trip, raw: List<TrackPoint>, stops: List<Stop>, items: List<PlanItem>, entries: List<Entry>, now: Long, zone: ZoneId): TripMapView =
    build(trip, raw, stops, items, entries, now, zone.id)
