package com.justdataplease.boringtravel.ios

import androidx.compose.material3.CalendarLocale
import com.justdataplease.boringtravel.compat.phoneLanguageTag
import platform.Foundation.NSLocale

/** NSLocale of the phone's first preferred language ("el-GR"). */
actual fun phoneCalendarLocale(): CalendarLocale = NSLocale(localeIdentifier = phoneLanguageTag())
