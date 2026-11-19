package com.justdataplease.boringtravel.compat

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat

// The desktop: Java's own default locale, java.time and DecimalFormatSymbols, which is what Android writes with (the
// engine's Dates on the desktop is Android's Dates.java.kt, on the same default locale).

actual fun phoneLanguageTag(): String = java.util.Locale.getDefault().toLanguageTag()

/** The computer's own default, kept the first time a test sets another. */
private var ownDefault: java.util.Locale? = null

actual fun setPhoneLanguage(tag: String?) {
    val own = ownDefault ?: java.util.Locale.getDefault().also { ownDefault = it }
    java.util.Locale.setDefault(if (tag == null) own else java.util.Locale.forLanguageTag(tag))
}

internal actual fun platformDate(pattern: String, epochDay: Long, minuteOfDay: Int, tag: String): String =
    java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.forLanguageTag(tag))
        .format(java.time.LocalDate.ofEpochDay(epochDay).atTime(minuteOfDay / 60, minuteOfDay % 60))

internal actual fun platformNumberSymbols(tag: String): JavaNumberSymbols {
    val locale = java.util.Locale.forLanguageTag(tag)
    val symbols = DecimalFormatSymbols.getInstance(locale)
    val size = (NumberFormat.getIntegerInstance(locale) as? DecimalFormat)?.groupingSize ?: 3
    return JavaNumberSymbols(symbols.decimalSeparator, symbols.groupingSeparator, symbols.zeroDigit, size)
}

actual fun skeletonDate(skeleton: String, epochDay: Long, tag: String): String =
    java.time.format.DateTimeFormatter.ofLocalizedPattern(skeleton).withLocale(java.util.Locale.forLanguageTag(tag))
        .format(java.time.LocalDate.ofEpochDay(epochDay))
