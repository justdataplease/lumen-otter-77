package com.justdataplease.boringtravel.ios

import androidx.compose.material3.CalendarLocale

/**
 * The phone's language for Material's date pickers (their month and weekday names), as Android's pickers take it from
 * the phone's configuration: NSLocale of the phone's first language on iPhone (iOS's own current locale follows the
 * app's localizations, English here), Java's default locale on the desktop.
 */
expect fun phoneCalendarLocale(): CalendarLocale
