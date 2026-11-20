package com.justdataplease.boringtravel.compat

import com.justdataplease.boringtravel.ios.JavaText

/*
 * The phone's language for dates and numbers, as Android's Locale.getDefault() is the phone's (since the parity round of
 * 19 Nov 2026; before, every date and number on this phone was English): what Locale.getDefault() gives the read-in
 * files, what DateTimeFormatter writes month and weekday names in, and how "%.1f" and "%,d" are written ("18,4 km" on a
 * Greek phone). The root locale and English (US) keep the exact English this file always wrote (every explicit
 * Locale.ROOT, Locale.US and Locale.ENGLISH of the Android files, and Java's own English); every other language goes to
 * the phone's formatters: on iPhone the engine's PhoneLocale (NSDateFormatter, NSNumberFormatter, the phone's first
 * preferred language), on the desktop Java's own java.time and DecimalFormatSymbols, as Android itself writes them.
 */

/** The phone's language with its region, as a BCP 47 tag ("el-GR"): Android's Locale.getDefault(). */
expect fun phoneLanguageTag(): String

/**
 * Tests and pictures: the phone set to language [tag] (null: back to its own). iPhone: the engine's
 * PhoneLocale.override; the desktop: Java's default locale, as Android's tests set it.
 */
expect fun setPhoneLanguage(tag: String?)

/** [pattern] (java.time's letters) for day [epochDay] at [minuteOfDay], as java.time writes it in language [tag]. */
internal expect fun platformDate(pattern: String, epochDay: Long, minuteOfDay: Int, tag: String): String

/**
 * Day [epochDay] written from a CLDR skeleton ("yMMMd") in language [tag], in that language's own order, as Android's
 * DateUtils writes a date (ICU's skeletons): "Nov 12, 2026" in English (US), "12 Nov 2026" in English (UK). iPhone:
 * NSDateFormatter's localized template; the desktop: java.time's localized pattern (Java 19+), the same CLDR data.
 */
expect fun skeletonDate(skeleton: String, epochDay: Long, tag: String = phoneLanguageTag()): String

/** The units Android's DateUtils.getRelativeTimeSpanString counts in, to the minute. */
enum class RelativeUnit { MINUTES, HOURS, DAYS }

/**
 * "[count] [unit] ago" in language [tag], long style, as Android's DateUtils.getRelativeTimeSpanString writes it through
 * ICU's RelativeDateTimeFormatter ("5 minutes ago", "πριν από 5 λεπτά"). iPhone: NSRelativeDateTimeFormatter, full units,
 * numeric (CLDR's words, as ICU's); the desktop: CLDR's English and Greek, English for the rest (pictures and tests).
 */
expect fun relativeAgo(count: Long, unit: RelativeUnit, tag: String = phoneLanguageTag()): String

/**
 * "yesterday" ([daysAgo] 1) or "today" (0) in language [tag], as DateUtils names them (ICU's absolute day, here before
 * Android's capital for the start of a sentence). iPhone: NSRelativeDateTimeFormatter, named.
 */
expect fun relativeDay(daysAgo: Int, tag: String = phoneLanguageTag()): String

/** The separators, zero digit and group size Java's Formatter writes numbers with in language [tag]. */
internal expect fun platformNumberSymbols(tag: String): JavaNumberSymbols

/** Java's DecimalFormatSymbols as its Formatter uses them, and the grouping size of the language's integer format. */
data class JavaNumberSymbols(val decimal: Char, val grouping: Char, val zero: Char, val groupSize: Int)

/** The root locale and English (US): Java's English, which this app writes without asking the phone. */
internal fun Locale.plainEnglish(): Boolean = language.isEmpty() || (language == "en" && script.isEmpty() && (country.isEmpty() || country == "US"))

/**
 * Java's String.format(locale, "%.Nf", x), or "%,.Nf" when [grouped]: the digits as Java's Formatter rounds them (half up
 * from the shortest decimal form: [JavaText.fixed]), then, as its localizedMagnitude does, the language's zero digit,
 * decimal separator and grouping separator every group size.
 */
fun javaNumber(x: Double, decimals: Int, grouped: Boolean, locale: Locale): String {
    val english = JavaText.fixed(x, decimals)
    if (english == "NaN" || english.endsWith("Infinity")) return english
    val symbols = if (locale.plainEnglish()) JavaNumberSymbols('.', ',', '0', 3) else platformNumberSymbols(locale.toLanguageTag())
    val negative = english.startsWith("-")
    val digits = english.removePrefix("-")
    val whole = digits.substringBefore('.')
    val fraction = digits.substringAfter('.', "")
    val out = StringBuilder()
    if (negative) out.append('-')
    whole.forEachIndexed { i, ch ->
        out.append(symbols.zero + (ch - '0'))
        val left = whole.length - 1 - i
        if (grouped && symbols.groupSize > 0 && left > 0 && left % symbols.groupSize == 0) out.append(symbols.grouping)
    }
    if (fraction.isNotEmpty()) {
        out.append(symbols.decimal)
        fraction.forEach { out.append(symbols.zero + (it - '0')) }
    }
    return out.toString()
}

/** Java's String.format(locale, "%,d", n): the language's digits with its grouping separator. */
fun javaGroupedInt(n: Long, locale: Locale): String = javaNumber(n.toDouble(), 0, grouped = true, locale = locale)
