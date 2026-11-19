package com.justdataplease.boringtravel.compat

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.currentLocale

/** The phone's region (Foundation's NSLocale, its country code); empty when it has none. */
actual fun phoneRegion(): String = (NSLocale.currentLocale.objectForKey(NSLocaleCountryCode) as? String).orEmpty()
