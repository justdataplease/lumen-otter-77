package com.justdataplease.boringtravel

import com.justdataplease.boringtravel.tracking.TrackingPlan
import java.time.ZonedDateTime

/*
 * What the Android app's TrackingPlanTest (read in as it is by the build's androidTestSources) calls in the Android
 * form: here the next day's check takes a moment in epoch milliseconds and a time zone by its id.
 */

fun TrackingPlan.msUntilNextDay(now: ZonedDateTime): Long = msUntilNextDay(now.toInstant().toEpochMilli(), now.zone.id)
