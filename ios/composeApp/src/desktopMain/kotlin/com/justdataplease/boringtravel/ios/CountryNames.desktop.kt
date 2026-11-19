package com.justdataplease.boringtravel.ios

import java.util.Locale

/** Every country's name in English, in the computer's language and in its own, lowercase (the Android app's way). */
actual fun countryNames(): Set<String> {
    val out = HashSet<String>()
    for (code in Locale.getISOCountries()) {
        val l = Locale.Builder().setRegion(code).build()
        out += l.getDisplayCountry(Locale.ENGLISH).lowercase()
        out += l.getDisplayCountry(Locale.getDefault()).lowercase()
        out += l.getDisplayCountry(l).lowercase()
    }
    out -= ""
    return out
}
