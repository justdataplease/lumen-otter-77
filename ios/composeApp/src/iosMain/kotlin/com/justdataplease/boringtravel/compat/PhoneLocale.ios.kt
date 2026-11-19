package com.justdataplease.boringtravel.compat

import com.justdataplease.boringtravel.util.PhoneLocale

// iPhone: the engine's PhoneLocale (the phone's first preferred language; NSDateFormatter and NSNumberFormatter), so
// the engine's dates (util/Dates) and the read-in files' java.time and String.format write in the same language.

actual fun phoneLanguageTag(): String = PhoneLocale.tag

actual fun setPhoneLanguage(tag: String?) {
    PhoneLocale.override = tag
}

internal actual fun platformDate(pattern: String, epochDay: Long, minuteOfDay: Int, tag: String): String =
    PhoneLocale.date(pattern, epochDay, minuteOfDay, tag)

internal actual fun platformNumberSymbols(tag: String): JavaNumberSymbols =
    PhoneLocale.symbols(tag).let { JavaNumberSymbols(it.decimal, it.grouping, it.zero, it.groupSize) }
