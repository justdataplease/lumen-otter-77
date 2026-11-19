package com.justdataplease.boringtravel.util

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSTimeZone
import platform.Foundation.canonicalLocaleIdentifierFromString
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localeIdentifier
import platform.Foundation.preferredLanguages
import platform.Foundation.timeZoneWithName
import kotlin.native.concurrent.ThreadLocal

/**
 * The phone's language for dates and numbers on iPhone, as Android's Locale.getDefault() is the phone's: the first of
 * the phone's preferred languages ("el-GR"), whatever languages the app itself is written in (iOS's own currentLocale
 * follows the app's localizations, which would keep it English; Android's default locale doesn't). [date] writes a
 * java.time pattern as Android's DateTimeFormatter.ofPattern(pattern, locale) does (NSDateFormatter: the same CLDR
 * names, the Gregorian calendar and Latin digits as java.time always writes them); [symbols] are the separators and
 * zero digit Java's Formatter writes "%.1f" and "%,d" with (NSNumberFormatter's for the language).
 */
object PhoneLocale {
    /** The language as if the phone were set to it (BCP 47, "el-GR"), for tests; null: the phone's own. */
    var override: String? = null

    /** The phone's first language with its region ("el-GR", "en-US"), or the region's locale when it lists none. */
    val tag: String
        get() = override ?: (NSLocale.preferredLanguages.firstOrNull() as? String)?.takeIf { it.isNotBlank() }
            ?: NSLocale.currentLocale.localeIdentifier.replace('_', '-')

    /**
     * [pattern] (java.time's letters: E, d, M, L, y, H, m and quoted text) for day [epochDay] at [minuteOfDay], in
     * language [tag]: "Παρ 18 Ιουν 2027" for "EEE d MMM yyyy" in Greek.
     */
    fun date(pattern: String, epochDay: Long, minuteOfDay: Int = 0, tag: String = this.tag): String {
        val seconds = epochDay * 86_400.0 + minuteOfDay * 60.0
        return formatter(tag, pattern).stringFromDate(NSDate.dateWithTimeIntervalSince1970(seconds))
    }

    /** What Java's Formatter writes numbers with in language [tag]: its decimal and grouping separators and zero digit. */
    fun symbols(tag: String = this.tag): NumberSymbols = cache.symbols.getOrPut(tag) {
        val f = NSNumberFormatter()
        f.locale = NSLocale(localeIdentifier = identifier(tag, latinDigits = false))
        f.numberStyle = NSNumberFormatterDecimalStyle
        NumberSymbols(
            decimal = f.decimalSeparator.firstOrNull() ?: '.',
            grouping = f.groupingSeparator.firstOrNull() ?: ',',
            zero = f.stringFromNumber(NSNumber(int = 0))?.firstOrNull() ?: '0',
            groupSize = f.groupingSize.toInt().takeIf { it > 0 } ?: 3,
        )
    }

    private fun formatter(tag: String, pattern: String): NSDateFormatter = cache.dates.getOrPut("$tag|$pattern") {
        NSDateFormatter().apply {
            locale = NSLocale(localeIdentifier = identifier(tag, latinDigits = true))
            // java.time's ISO calendar, whatever calendar the phone's region uses (Buddhist, Japanese …).
            NSCalendar.calendarWithIdentifier(NSCalendarIdentifierGregorian)?.let { calendar = it }
            NSTimeZone.timeZoneWithName("UTC")?.let { timeZone = it }
            dateFormat = pattern
        }
    }

    /** ICU's identifier for [tag], with Latin digits when asked (java.time writes 0–9 in every language). */
    private fun identifier(tag: String, latinDigits: Boolean): String {
        val id = NSLocale.canonicalLocaleIdentifierFromString(tag)
        return if (!latinDigits) id else if ('@' in id) "$id;numbers=latn" else "$id@numbers=latn"
    }
}

/** A language's decimal separator, grouping separator, zero digit and group size (Java's DecimalFormatSymbols). */
data class NumberSymbols(val decimal: Char, val grouping: Char, val zero: Char, val groupSize: Int)

/** Formatters are made once per thread (NSDateFormatter is costly to make). */
@ThreadLocal
private val cache = FormatCache()

private class FormatCache {
    val dates = HashMap<String, NSDateFormatter>()
    val symbols = HashMap<String, NumberSymbols>()
}
