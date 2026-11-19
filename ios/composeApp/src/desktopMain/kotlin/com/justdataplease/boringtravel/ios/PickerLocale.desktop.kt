package com.justdataplease.boringtravel.ios

import androidx.compose.material3.CalendarLocale

/** Java's default locale, which the desktop's tests and pictures set as the phone's. */
actual fun phoneCalendarLocale(): CalendarLocale = java.util.Locale.getDefault()
