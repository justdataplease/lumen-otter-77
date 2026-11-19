package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.justdataplease.boringtravel.compat.DateTimeFormatter
import com.justdataplease.boringtravel.compat.JavaFormat
import com.justdataplease.boringtravel.compat.LocalConfiguration
import com.justdataplease.boringtravel.compat.Locale
import com.justdataplease.boringtravel.compat.capitalizedIn
import com.justdataplease.boringtravel.compat.uppercase
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDate

/*
 * The texts the Android screens write in the phone's language with java.time and Java's number formats, for the screens
 * this app writes again (the read-in ones call compat/ themselves): the same patterns in the same locale, so a Greek
 * phone reads "Ιούνιος 2027", "ΤΡΙ", "18,4 km" on both phones.
 */

/** The phone's locale as the Android screens read it: LocalConfiguration.current.locales[0], else the root locale. */
@Composable
@ReadOnlyComposable
fun screenLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

object PhoneTexts {
    /** "JUN": a note row's month (Android's EntryRow: "MMM" in [locale], in its capitals). */
    fun shortMonthUpper(day: LocalDate, locale: Locale): String = DateTimeFormatter.ofPattern("MMM", locale).format(day).uppercase(locale)

    /** "June 2027": a month's title (Android's PlanViews and the Calendar tab: "LLLL yyyy", its first letter a capital). */
    fun monthTitle(year: Int, month: Int, locale: Locale): String =
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(localDate(year, month, 1)).capitalizedIn(locale)

    /** "MON": [day]'s weekday short in capitals (Android's DayOfWeek.getDisplayName(SHORT, locale).uppercase(locale)). */
    fun weekdayUpper(day: LocalDate, locale: Locale): String = DateTimeFormatter.ofPattern("EEE", locale).format(day).uppercase(locale)

    /** "Jun": month [month]'s short name (Android's Month.getDisplayName(TextStyle.SHORT, locale)). */
    fun shortMonth(month: Int, locale: Locale): String = DateTimeFormatter.ofPattern("MMM", locale).format(localDate(2001, month, 1))

    /** "37.9838, 23.7275" as Android's "%.4f, %.4f".format(lat, lng) writes it in the phone's language ("37,9838, …"). */
    fun pin(lat: Double, lng: Double?, locale: Locale = Locale.getDefault()): String =
        JavaFormat.fixed(lat, 4, locale) + ", " + (lng?.let { JavaFormat.fixed(it, 4, locale) } ?: "null")

    /** "1,234": Java's integer NumberFormat in the phone's language (Android's WorldBits: NumberFormat.getIntegerInstance()). */
    fun integer(n: Long, locale: Locale = Locale.getDefault()): String = JavaFormat.groupedInt(n, locale)
}
