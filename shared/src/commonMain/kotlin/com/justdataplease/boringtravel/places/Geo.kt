package com.justdataplease.boringtravel.places

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Mean Earth radius in metres. */
    private const val R = 6_371_008.8

    /** Degrees to radians, the constant java.lang.Math.toRadians multiplies by. */
    private const val RAD = 0.017453292519943295

    /** Great-circle distance in metres (haversine). */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = lat1 * RAD
        val p2 = lat2 * RAD
        val dp = p2 - p1
        val dl = (lng2 - lng1) * RAD
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * asin(min(1.0, sqrt(a)))
    }
}
