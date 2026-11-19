package com.justdataplease.boringtravel.ios

import platform.Foundation.ISOCountryCodes
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.currentLocale

/** Every country's name in English and in the phone's language, lowercase, from Foundation's list of regions. */
actual fun countryNames(): Set<String> {
    val english = NSLocale(localeIdentifier = "en")
    val phone = NSLocale.currentLocale
    val out = HashSet<String>()
    for (code in NSLocale.ISOCountryCodes) {
        val c = code as? String ?: continue
        english.displayNameForKey(NSLocaleCountryCode, c)?.let { out += it.lowercase() }
        phone.displayNameForKey(NSLocaleCountryCode, c)?.let { out += it.lowercase() }
    }
    out -= ""
    return out
}
