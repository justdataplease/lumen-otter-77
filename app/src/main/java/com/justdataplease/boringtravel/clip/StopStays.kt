package com.justdataplease.boringtravel.clip

import kotlin.math.ln

/**
 * Time at a stop (owner, 2026-11-12: "in clip at its item add a time specific menu to adjust how much time to stay
 * there in seconds a slider. i.e a single event in a day last too little. maybe now with duration from to datine works
 * better but I want that also"): the journey stands still at a place for a few seconds, its name showing. Auto gives a
 * planned item with an end more time the longer it lasts ([auto]); the scene editor's "Time here" sets a place's own
 * (0 to [MAX_S]; [ClipOptions.stays]).
 */
object StopStays {
    /** The most "Time here" offers. */
    const val MAX_S = 10.0

    /** Auto's stays together take at most this share of the clip; more are shortened alike ([fit]). */
    const val AUTO_SHARE = 0.25

    /** Shorter than this, an Auto stay is left out (a blink, not a stop). */
    const val AUTO_MIN_S = 0.25

    /**
     * Auto's time for an item lasting [hours] (null: no end, none): 1 s for an hour, 1.5 s for three, 2 s for a day,
     * 2.5 s for three nights, 3 s at most.
     */
    fun auto(hours: Double?): Double {
        if (hours == null || !hours.isFinite() || hours <= 0.0) return 0.0
        return half((0.6 + 0.5 * ln(1 + hours)).coerceIn(0.5, 3.0))
    }

    /** Seconds as stored: half seconds within 0–[MAX_S]. */
    fun half(s: Double): Double = Spots.hold(s, MAX_S)

    /** [autos] (seconds per place name) shortened alike so that together they stay within [room] seconds. */
    fun fit(autos: Map<String, Double>, room: Double): Map<String, Double> {
        val sum = autos.values.sum()
        if (sum <= 0.0 || sum <= room) return autos
        val f = (room / sum).coerceAtLeast(0.0)
        return autos.mapValues { it.value * f }.filterValues { it >= AUTO_MIN_S }
    }

    /**
     * Each place name's time: its own ([own], 0 = none) or else Auto from the longest of its stops' items ([hours] by
     * stop id; [stops] by place name key), Auto's together within [AUTO_SHARE] of a clip of [clipS] seconds.
     */
    fun plan(stops: Map<String, List<String>>, hours: Map<String, Double?>, own: Map<String, Double>, clipS: Double): Map<String, Double> {
        val set = HashMap<String, Double>()
        val autos = LinkedHashMap<String, Double>()
        for ((key, ids) in stops) {
            val chosen = own[key]
            if (chosen != null) {
                if (chosen > 0.0) set[key] = chosen.coerceAtMost(MAX_S)
                continue
            }
            val a = auto(ids.mapNotNull { hours[it] }.maxOrNull())
            if (a > 0.0) autos[key] = a
        }
        return fit(autos, clipS * AUTO_SHARE) + set
    }
}
