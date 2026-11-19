package com.justdataplease.boringtravel.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The calendar picker (accounts, groups) and the trip's time zone. */
class CalendarPickTest {
    private val athens = ZoneId.of("Europe/Athens")
    private val june = LocalDate.of(2027, 6, 17)

    @Test fun connectedGoogleAccountIsSuggestedButAnExistingDestinationStillWins() {
        val groups = CalendarAccounts.groups(listOf(
            CalendarInfo(1, "Other", "a@example.invalid", "com.google", primary = true, accessLevel = 700),
            CalendarInfo(2, "Travel", "z@example.invalid", "com.google", accessLevel = 700),
            CalendarInfo(3, "Personal", "z@example.invalid", "com.google", primary = true, accessLevel = 700),
            CalendarInfo(4, "Local", "local", "LOCAL", primary = true, accessLevel = 700),
        ))
        assertEquals(3L, CalendarAccounts.defaultChoice(groups, null, "Z@example.invalid")?.id)
        assertEquals(4L, CalendarAccounts.defaultChoice(groups, 4, "z@example.invalid")?.id)
        assertEquals(1L, CalendarAccounts.defaultChoice(groups, null, "missing@example.invalid")?.id)
    }

    @Test fun accountsAreNamedPlainly() {
        assertEquals(AccountKind.Google, CalendarAccounts.kind("com.google", "ana@gmail.com"))
        assertEquals(AccountKind.ICloud, CalendarAccounts.kind("bitfire.at.davdroid", "ana@icloud.com"))
        assertEquals(AccountKind.CalDav, CalendarAccounts.kind("bitfire.at.davdroid", "ana@posteo.de"))
        assertEquals(AccountKind.Outlook, CalendarAccounts.kind("com.microsoft.office.outlook.USER_ACCOUNT", "ana@outlook.com"))
        assertEquals(AccountKind.Exchange, CalendarAccounts.kind("com.samsung.android.exchange", "ana@work.com"))
        assertEquals(AccountKind.Exchange, CalendarAccounts.kind("com.google.android.gm.exchange", "ana@work.com"))
        assertEquals(AccountKind.Samsung, CalendarAccounts.kind("com.osp.app.signin", "ana@samsung.com"))
        assertEquals(AccountKind.Phone, CalendarAccounts.kind("LOCAL", "My calendar"))
        assertEquals(AccountKind.Other, CalendarAccounts.kind("org.example.sync", "x"))
    }

    @Test fun onlyWritableVisibleRealCalendarsAreOfferedGroupedByAccount() {
        val list = listOf(
            CalendarInfo(1, "Work", "ana@work.com", "com.google.android.gm.exchange", accessLevel = 700),
            CalendarInfo(2, "Family", "ana@gmail.com", "com.google", accessLevel = 700),
            CalendarInfo(3, "ana@gmail.com", "ana@gmail.com", "com.google", accessLevel = 700, primary = true),
            CalendarInfo(4, "Holidays in Greece", "ana@gmail.com", "com.google", ownerAccount = "en.greek#holiday@group.v.calendar.google.com", accessLevel = 200),
            CalendarInfo(5, "Birthdays", "ana@gmail.com", "com.google", ownerAccount = "addressbook#contacts@group.v.calendar.google.com", accessLevel = 700),
            CalendarInfo(6, "Hidden", "ana@gmail.com", "com.google", accessLevel = 700, visible = false),
            CalendarInfo(7, "Read only", "x@y.z", "com.google", accessLevel = 200),
            CalendarInfo(8, "Personal", "ana@icloud.com", "bitfire.at.davdroid", accessLevel = 700),
            CalendarInfo(9, "My calendar", "My calendar", "LOCAL", accessLevel = 700),
        )
        val groups = CalendarAccounts.groups(list)
        assertEquals(listOf("Google · ana@gmail.com", "iCloud · ana@icloud.com", "Exchange · ana@work.com", "On this phone"), groups.map { it.title })
        assertEquals(listOf(3L, 2L), groups[0].calendars.map { it.id }) // the main calendar first
        assertEquals(3L, CalendarAccounts.defaultChoice(groups, null)?.id)
        assertEquals(8L, CalendarAccounts.defaultChoice(groups, 8)?.id)
        assertEquals(3L, CalendarAccounts.defaultChoice(groups, 4)?.id) // linked one no longer offered
        assertTrue(CalendarAccounts.isNoise(list[3]))
        assertFalse(CalendarAccounts.isNoise(list[1]))
    }

    @Test fun theZoneIsGuessedFromWhatTheTripKnows() {
        val phone = ZoneId.of("Europe/London")
        // Picked before wins; then where the journal was written; then the one zone of the places' country.
        assertEquals(ZoneId.of("Asia/Tokyo"), TripZone.guess("Asia/Tokyo", listOf("Europe/Athens"), emptyList(), phone))
        assertEquals(athens, TripZone.guess(null, listOf("Europe/Athens", "Europe/Athens", "Europe/Rome"), listOf(setOf("Asia/Tokyo")), phone))
        assertEquals(ZoneId.of("Asia/Tokyo"), TripZone.guess(null, emptyList(), listOf(setOf("Asia/Tokyo"), setOf("Asia/Tokyo"), emptySet()), phone))
        // A country with several zones doesn't decide on its own; nothing known → the phone's zone.
        assertEquals(phone, TripZone.guess(null, emptyList(), listOf(setOf("America/New_York", "America/Chicago")), phone))
        assertEquals(phone, TripZone.guess("not/a zone", emptyList(), emptyList(), phone))
    }

    @Test fun zoneOptionsStartWithTheCurrentOneThenGoEastToWest() {
        val opts = TripZone.options(athens, ZoneId.of("Europe/London"), listOf("Europe/Athens"), listOf(setOf("America/New_York", "America/Los_Angeles"), setOf("Asia/Tokyo")), june)
        assertEquals(listOf("Europe/Athens", "Asia/Tokyo", "Europe/London", "America/New_York", "America/Los_Angeles"), opts.map { it.id })
    }

    @Test fun zoneLabelsAreShort() {
        assertEquals("Athens · GMT+3", TripZone.label(athens, june))
        assertEquals("Athens · GMT+2", TripZone.label(athens, LocalDate.of(2027, 1, 10)))
        assertEquals("New York · GMT-4", TripZone.label(ZoneId.of("America/New_York"), june))
        assertEquals("Kolkata · GMT+5:30", TripZone.label(ZoneId.of("Asia/Kolkata"), june))
        assertEquals("UTC", TripZone.label(ZoneId.of("UTC"), june))
        assertEquals("Port of Spain", TripZone.city(ZoneId.of("America/Port_of_Spain")))
    }

    @Test fun syncResultsReadPlainly() {
        assertEquals("Personal is up to date.", com.justdataplease.boringtravel.ui.calendar.updated(SyncResult(0, 0, 0, "Personal")))
        assertEquals("Calendar updated: 2 new, 1 removed.", com.justdataplease.boringtravel.ui.calendar.updated(SyncResult(2, 0, 1, "Personal")))
    }
}
