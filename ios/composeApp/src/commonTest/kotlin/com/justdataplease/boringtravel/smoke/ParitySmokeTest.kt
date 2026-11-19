package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.backup.BackupText
import com.justdataplease.boringtravel.compat.DateTimeFormatter
import com.justdataplease.boringtravel.compat.JavaFormat
import com.justdataplease.boringtravel.compat.Locale
import com.justdataplease.boringtravel.compat.setPhoneLanguage
import com.justdataplease.boringtravel.compat.uppercase
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.ios.AppRestore
import com.justdataplease.boringtravel.ios.AppStack
import com.justdataplease.boringtravel.ios.GoogleMaps
import com.justdataplease.boringtravel.ios.PhoneTexts
import com.justdataplease.boringtravel.ios.Place
import com.justdataplease.boringtravel.ios.kmText
import com.justdataplease.boringtravel.ios.mapsLink
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.tracking.RouteShapeText
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.util.Dates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The parity round of 19 Nov 2026, on both the desktop's JVM and the iPhone Simulator: dates and numbers in the
 * phone's language as Android writes them (java.time and String.format with Locale.getDefault(): the same CLDR names
 * through NSDateFormatter and NSNumberFormatter on iPhone), Google Maps for a note's place, and where the app was kept
 * as text and read back.
 */
class ParitySmokeTest {
    private fun inLanguage(tag: String, block: () -> Unit) {
        setPhoneLanguage(tag)
        try {
            block()
        } finally {
            setPhoneLanguage(null)
        }
    }

    @Test fun datesFollowThePhonesLanguageAsAndroidWritesThem() = inLanguage("el-GR") {
        val day = localDate(2027, 6, 18)
        // The engine's dates (Android: Dates.java.kt with Locale.getDefault()).
        assertEquals("Παρ 18 Ιουν 2027", Dates.dayLong(day))
        assertEquals("Παρ 18", Dates.dayShort(day))
        assertEquals("18 Ιουν 2027", Dates.dayMonthYear(day))
        assertEquals("Ιουν 2027", Dates.monthYear(day))
        assertEquals("17–26 Ιουν 2027", Dates.range(localDate(2027, 6, 17), localDate(2027, 6, 26)))
        assertEquals("28 Ιουν – 3 Ιουλ 2027", Dates.range(localDate(2027, 6, 28), localDate(2027, 7, 3)))
        // The read-in files' java.time: the phone's locale by default, the month standing alone (L) and in a date (M).
        assertEquals("el-GR", Locale.getDefault().toLanguageTag())
        assertEquals("Ιούνιος 2027", DateTimeFormatter.ofPattern("LLLL yyyy").format(day))
        assertEquals("18 Ιουνίου", DateTimeFormatter.ofPattern("d MMMM").format(day))
        // What the iPhone's own screens write: the Calendar's and the plan's month, a note row's month, a weekday.
        val el = Locale.getDefault()
        assertEquals("Ιούνιος 2027", PhoneTexts.monthTitle(2027, 6, el))
        assertEquals("ΙΟΥΝ", PhoneTexts.shortMonthUpper(day, el))
        // Android's Greek capitals drop the accents (Τρί → ΤΡΙ), keep the diaeresis (Μαΐ → ΜΑΪ).
        assertEquals("ΤΡΙ", PhoneTexts.weekdayUpper(localDate(2027, 6, 15), el))
        assertEquals("ΜΑΪ", "Μαΐ".uppercase(el))
        // A backup's date ("d MMM yyyy, HH:mm").
        assertEquals("18 Ιουν 2027, 21:30", BackupText.date(20_987 * 86_400_000L + (18 * 60 + 30) * 60_000L, "Europe/Athens"))
    }

    @Test fun numbersFollowThePhonesLanguageAsJavasFormatterWritesThem() = inLanguage("el-GR") {
        // RouteShape's and ClipText's "%.1f km" and "%,d km", the plan's legs (ClipText.km), Java's half up.
        assertEquals("18,4 km", RouteShapeText.oneDecimalKm(18.42))
        assertEquals("2.370 km", RouteShapeText.groupedKm(2370))
        assertEquals("4,3 km", kmText(4.25))
        assertEquals("2.370 km", kmText(2370.4))
        assertEquals("37,9838, 23,7275", PhoneTexts.pin(37.98381, 23.72754))
        assertEquals("1.234", PhoneTexts.integer(1234))
        // Explicit English and the root locale stay as Java writes them there (CSS, SVG, keys, the ICS file).
        assertEquals("18.4", JavaFormat.oneDecimal(18.42))
        assertEquals("18.4", JavaFormat.oneDecimal(18.42, Locale.ROOT))
        assertEquals("2,370", JavaFormat.grouped(2370.0, Locale.US))
        assertEquals("Fri 18 Jun 2027", DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US).format(localDate(2027, 6, 18)))
    }

    @Test fun englishPhonesReadJavasEnglish() = inLanguage("en-US") {
        val day = localDate(2027, 6, 18)
        assertEquals("Fri 18 Jun 2027", Dates.dayLong(day))
        assertEquals("June 2027", PhoneTexts.monthTitle(2027, 6, Locale.getDefault()))
        assertEquals("18.4 km", RouteShapeText.oneDecimalKm(18.42))
        assertEquals("1,234 km", RouteShapeText.groupedKm(1234))
        assertEquals("TUE", PhoneTexts.weekdayUpper(localDate(2027, 6, 15), Locale.getDefault()))
    }

    @Test fun aNotesPlaceOpensGoogleMapsNeverAppleMaps() {
        // Android hands its maps app "geo:<lat>,<lng>?q=<lat>,<lng>(<name>)" or "geo:0,0?q=<name>" (DocView).
        assertEquals("https://www.google.com/maps/search/?api=1&query=37.1056,25.3747", mapsLink(Block.Place("Naxos port", 37.1056, 25.3747)))
        assertEquals("https://www.google.com/maps/search/?api=1&query=Halki%2C%20Naxos", mapsLink(Block.Place("Halki, Naxos", null, null)))
        assertEquals("https://www.google.com/maps/search/?api=1&query=37.1,25.4", GoogleMaps.ofGeo("geo:37.1,25.4?q=37.1,25.4(Halki)"))
        assertEquals("https://www.google.com/maps/search/?api=1&query=Halki", GoogleMaps.ofGeo("geo:0,0?q=Halki"))
        assertEquals("https://www.google.com/maps/search/?api=1&query=37.1,25.4", GoogleMaps.ofGeo("geo:37.1,25.4"))
        assertEquals(null, GoogleMaps.ofGeo("https://example.com"))
    }

    @Test fun whereTheAppWasIsKeptAsTextAndReadBack() {
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos", localDate(2027, 6, 17), localDate(2027, 6, 26))
        val stack = AppStack(listOf(Place.Trips, Place.Trip(trip.id), Place.Write(trip.id)))
        stack.tabPages[HomeTab.Calendar] = AppStack(listOf(Place.Home(HomeTab.Calendar))).entries.toList()
        val write = stack.entries.last()
        val text = AppRestore.encode(stack, TripTab.Journal, localDate(2027, 6, 18), mapOf(write.id to "note-1"))
        val saved = assertNotNull(AppRestore.decode(text, book))
        // The note being written comes back as that note; the trip on its tab and day; the Calendar's pages.
        assertEquals(listOf(Place.Trips, Place.Trip(trip.id), Place.Write(trip.id, "note-1")), saved.stack)
        assertEquals(TripTab.Journal, saved.tripTab)
        assertEquals(localDate(2027, 6, 18), saved.tripDay)
        assertEquals(listOf(Place.Home(HomeTab.Calendar)), saved.tabs[HomeTab.Calendar])
        // A trip gone since: its screens are left off the top.
        book.removeTrip(trip.id)
        assertEquals(listOf(Place.Trips), assertNotNull(AppRestore.decode(text, book)).stack)
        assertEquals(null, AppRestore.decode("not json", book))
    }
}
