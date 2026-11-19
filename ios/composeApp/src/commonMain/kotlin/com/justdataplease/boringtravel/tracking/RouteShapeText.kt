package com.justdataplease.boringtravel.tracking

import com.justdataplease.boringtravel.compat.JavaFormat
import com.justdataplease.boringtravel.compat.Locale

/**
 * The two numbers [RouteShape.formatDistance] and the trip video's ClipText write with Java's String.format on Android
 * ("%.1f km", "%,d km"), as Java writes them in [locale]: the phone's language by default, as Android's
 * Locale.getDefault() ("18.4 km" in English, "18,4 km" in Greek; "1,234 km", "1.234 km"). The build puts these calls
 * into the Android files it reads in (composeApp/build.gradle.kts).
 */
internal object RouteShapeText {
    /** "18.4 km" for 18.42, rounded as Java's "%.1f" rounds, with [locale]'s decimal separator. */
    fun oneDecimalKm(km: Double, locale: Locale = Locale.getDefault()): String = JavaFormat.oneDecimal(km, locale) + " km"

    /** "1,234 km" for 1234, with [locale]'s grouping separator. */
    fun groupedKm(km: Long, locale: Locale = Locale.getDefault()): String = JavaFormat.groupedInt(km, locale) + " km"
}
