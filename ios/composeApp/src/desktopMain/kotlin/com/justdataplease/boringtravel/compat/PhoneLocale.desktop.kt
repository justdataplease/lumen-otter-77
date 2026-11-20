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

/*
 * Android's DateUtils "5 minutes ago" (round 26): the JVM has no relative-time formatter (Android's is ICU's), so the
 * desktop writes CLDR's own words for English and Greek, the languages the pictures and tests use, and English for any
 * other; iPhone asks Foundation's NSRelativeDateTimeFormatter (CLDR's words in every language).
 */
private val RELATIVE: Map<String, Map<RelativeUnit, Pair<String, String>>> = mapOf(
    "en" to mapOf(
        RelativeUnit.MINUTES to ("{0} minute ago" to "{0} minutes ago"),
        RelativeUnit.HOURS to ("{0} hour ago" to "{0} hours ago"),
        RelativeUnit.DAYS to ("{0} day ago" to "{0} days ago"),
    ),
    "el" to mapOf(
        RelativeUnit.MINUTES to ("πριν από {0} λεπτό" to "πριν από {0} λεπτά"),
        RelativeUnit.HOURS to ("πριν από {0} ώρα" to "πριν από {0} ώρες"),
        RelativeUnit.DAYS to ("πριν από {0} ημέρα" to "πριν από {0} ημέρες"),
    ),
)

private val RELATIVE_DAY: Map<String, Pair<String, String>> = mapOf("en" to ("today" to "yesterday"), "el" to ("σήμερα" to "χθες"))

private fun language(tag: String) = java.util.Locale.forLanguageTag(tag).language.takeIf { it in RELATIVE } ?: "en"

actual fun relativeAgo(count: Long, unit: RelativeUnit, tag: String): String {
    val (one, other) = RELATIVE.getValue(language(tag)).getValue(unit)
    return (if (count == 1L) one else other).replace("{0}", count.toString())
}

actual fun relativeDay(daysAgo: Int, tag: String): String {
    val (today, yesterday) = RELATIVE_DAY.getValue(language(tag))
    return if (daysAgo == 0) today else yesterday
}
