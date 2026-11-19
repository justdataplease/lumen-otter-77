package com.justdataplease.boringtravel.ios

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.currentLocale

/** The country's name in the phone's language (Foundation's NSLocale); the code when it has none. */
actual fun countryName(code: String): String =
    NSLocale.currentLocale.displayNameForKey(NSLocaleCountryCode, code)?.takeIf { it.isNotBlank() && it != code } ?: code
