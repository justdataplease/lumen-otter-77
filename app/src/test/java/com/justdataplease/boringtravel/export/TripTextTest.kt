package com.justdataplease.boringtravel.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TripTextTest {
    private fun text(options: ExportOptions = ExportOptions(), book: TripBook = ExportFixtures.book()) = TripText.build(book, options, Locale.UK)

    @Test fun titleDatesThenEachDayWithItsNotes() {
        val out = text()
        val lines = out.lines()
        assertEquals("Cyclades, slowly", lines[0])
        assertEquals("Paros → Naxos → Amorgos", lines[1])
        assertEquals("16–25 Jun 2027", lines[2])
        assertTrue(out.contains("\nDay 3 of 10 · Fri 18 Jun 2027 · Kolymbithres, Paros\n"))
        assertTrue(out.contains("\nDay 4 of 10 · Sat 19 Jun 2027 · Halki, Naxos\n"))
        // A note: its title, its time and place, its words (marks dropped), lists and checklists as text.
        assertTrue(out.contains("\nKolymbithres, twice\n19:30 · Kolymbithres, Paros\nSame rocks in the morning and again at six. Better the second time.\n"))
        assertTrue(out.contains("Forty minutes late and nobody minded. Bought kitron in the square"))
        assertTrue(out.contains("[x] Postcards for Mum"))
        // Photos are left out, their captions stay.
        assertTrue(out.contains("Halki square, 11:40"))
        assertFalse(out.contains("<"))
        // Notes in the order they happened.
        assertTrue(out.indexOf("Ferry day") < out.indexOf("The bus to Halki"))
        assertTrue(out.endsWith("\n") && !out.endsWith("\n\n"))
    }

    @Test fun thePlanOnlyWhenIncluded() {
        assertFalse(text().contains("Ferry Paros → Naxos"))
        val out = text(ExportOptions(includePlan = true))
        // Day 1 has only its plan; times, names and places in one line each.
        assertTrue(out.contains("\nDay 1 of 10 · Wed 16 Jun 2027 · Parikia, Paros\n\nPlan\n16:10  Ferry Piraeus → Paros\n"))
        assertTrue(out.contains("\n07:30  Ferry Paros → Naxos\n12:30  Lunch in Halki\n"))
        // Ideas at the end, for the whole trip.
        assertTrue(out.trimEnd().endsWith("Ideas\nKitron tasting in Halki"))
    }

    @Test fun followsTheChosenDays() {
        val d = ExportFixtures.day4
        val out = text(ExportOptions(includePlan = true, from = d, to = d))
        assertTrue(out.contains("Day 4 of 10"))
        assertFalse(out.contains("Day 3 of 10"))
        assertFalse(out.contains("Kolymbithres"))
        assertFalse(out.contains("Ideas"))
        assertEquals("19 Jun 2027", out.lines()[2])
    }

    @Test fun aPlaceDifferentFromTheNameIsAdded() {
        val base = ExportFixtures.book()
        val book = base.copy(plan = base.plan.map { if (it.id == "i3") it.copy(placeName = "Halki square") else it })
        assertTrue(text(ExportOptions(includePlan = true), book).contains("12:30  Lunch in Halki · Halki square\n"))
    }

    @Test fun anEmptyTripSaysSo() {
        val b = ExportFixtures.book()
        val out = text(book = b.copy(entries = emptyList(), media = emptyList(), plan = emptyList()))
        assertTrue(out.contains("Nothing written for these days yet."))
    }
}
