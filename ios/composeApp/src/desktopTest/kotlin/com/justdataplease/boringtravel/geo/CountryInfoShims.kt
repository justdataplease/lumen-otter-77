package com.justdataplease.boringtravel.geo

import java.util.Locale

/**
 * The Android app's CountryInfoTest (read in as it is) asks for a name in a given language; here CountryInfo.name reads
 * the phone's. Java's own names, as the Android app's CountryInfo writes them.
 */
fun CountryInfo.name(code: String, locale: Locale): String {
    val c = code.trim().uppercase()
    if (c == "CYN") return "Northern Cyprus"
    if (c == "SOL") return "Somaliland"
    return runCatching { Locale.Builder().setRegion(c).build().getDisplayCountry(locale) }.getOrNull()?.takeIf { it.isNotBlank() && it != c } ?: c
}
