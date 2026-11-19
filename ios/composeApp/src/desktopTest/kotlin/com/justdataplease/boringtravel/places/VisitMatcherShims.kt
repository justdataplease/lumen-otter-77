package com.justdataplease.boringtravel.places

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.TrackPoint
import java.time.ZoneId

/** The Android app's VisitMatcherTest (read in as it is) passes a java.time zone; here VisitMatcher takes its id. */
fun VisitMatcher.visited(items: List<PlanItem>, points: List<TrackPoint>, zone: ZoneId, radiusM: Double = VisitMatcher.RADIUS_M): Set<String> =
    visited(items, points, zone.id, radiusM)
