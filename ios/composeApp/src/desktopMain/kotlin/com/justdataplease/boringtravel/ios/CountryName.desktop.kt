package com.justdataplease.boringtravel.ios

import java.util.Locale

/** The country's name in the computer's language (Java's Locale, as the Android app's CountryInfo.name). */
actual fun countryName(code: String): String = runCatching {
    Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault()).takeIf { it.isNotBlank() && it != code }
}.getOrNull() ?: code
