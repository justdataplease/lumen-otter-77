package com.justdataplease.boringtravel.compat

import com.justdataplease.boringtravel.util.PhoneLocale
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSRelativeDateTimeFormatter
import platform.Foundation.NSRelativeDateTimeFormatterStyleNamed
import platform.Foundation.NSRelativeDateTimeFormatterStyleNumeric
import platform.Foundation.NSRelativeDateTimeFormatterUnitsStyleFull
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.canonicalLocaleIdentifierFromString
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneWithName

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

/** NSDateFormatter's localized template on the Gregorian calendar in UTC, with Latin digits as Android's DateUtils. */
actual fun skeletonDate(skeleton: String, epochDay: Long, tag: String): String {
    val f = NSDateFormatter()
    val id = NSLocale.canonicalLocaleIdentifierFromString(tag)
    f.locale = NSLocale(localeIdentifier = if ('@' in id) "$id;numbers=latn" else "$id@numbers=latn")
    NSCalendar.calendarWithIdentifier(NSCalendarIdentifierGregorian)?.let { f.calendar = it }
    NSTimeZone.timeZoneWithName("UTC")?.let { f.timeZone = it }
    f.setLocalizedDateFormatFromTemplate(skeleton)
    return f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochDay * 86_400.0))
}

/**
 * "5 minutes ago" in [tag] (round 26: the Community post sheet's "Last updated", Android's DateUtils): Foundation's
 * relative formatter (iOS 13), full units, numeric, the language's own digits as ICU's.
 */
actual fun relativeAgo(count: Long, unit: RelativeUnit, tag: String): String {
    val f = relativeFormatter(tag, named = false)
    val c = NSDateComponents()
    when (unit) {
        RelativeUnit.MINUTES -> c.minute = -count
        RelativeUnit.HOURS -> c.hour = -count
        RelativeUnit.DAYS -> c.day = -count
    }
    return f.localizedStringFromDateComponents(c)
}

/** "yesterday" or "today" in [tag]: the named style. */
actual fun relativeDay(daysAgo: Int, tag: String): String {
    val c = NSDateComponents()
    c.day = -daysAgo.toLong()
    return relativeFormatter(tag, named = true).localizedStringFromDateComponents(c)
}

private fun relativeFormatter(tag: String, named: Boolean): NSRelativeDateTimeFormatter {
    val f = NSRelativeDateTimeFormatter()
    f.locale = NSLocale(localeIdentifier = NSLocale.canonicalLocaleIdentifierFromString(tag))
    f.unitsStyle = NSRelativeDateTimeFormatterUnitsStyleFull
    f.dateTimeStyle = if (named) NSRelativeDateTimeFormatterStyleNamed
    else NSRelativeDateTimeFormatterStyleNumeric
    return f
}
