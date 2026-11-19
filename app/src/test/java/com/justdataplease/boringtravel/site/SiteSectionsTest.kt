package com.justdataplease.boringtravel.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SiteSectionsTest {
    private val today = LocalDate.of(2026, 11, 12)
    private fun dates(vararg texts: String) = HeadingDates.resolve(texts.toList(), today)
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun datesWrittenManyWays() {
        assertEquals(listOf(d("2016-04-03")), dates("2016-04-03 Kyoto"))
        assertEquals(listOf(d("2016-04-03")), dates("Saturday 3 April 2016"))
        assertEquals(listOf(d("2016-04-03")), dates("April 3rd, 2016: temples"))
        assertEquals(listOf(d("2016-04-13")), dates("13/4/2016"))
        assertEquals(listOf(d("2016-04-13")), dates("4/13/2016"))
        assertEquals(listOf(d("2016-04-13")), dates("13.04.2016"))
        assertEquals(listOf(d("2019-08-03")), dates("Σάββατο 3 Αυγούστου 2019, Χανιά"))
        assertEquals(listOf(d("2019-05-03")), dates("3η Μαΐου 2019"))
        assertEquals(listOf<LocalDate?>(null), dates("Getting there"))
        assertEquals(listOf<LocalDate?>(null), dates("Ferry at 10.30"))
        assertEquals(listOf<LocalDate?>(null), dates("3 April 1890")) // out of range
    }

    @Test fun theDocumentDecidesWhatOneHeadingCannot() {
        // 3/4 alone is unclear; 13/4 elsewhere says day first.
        assertEquals(listOf(d("2016-04-03"), d("2016-04-13")), dates("3/4/2016", "13/4/2016"))
        // A day counting up while the month stays: day first.
        assertEquals(listOf(d("2016-04-01"), d("2016-04-02"), d("2016-04-03")), dates("1/4/2016", "2/4/2016", "3/4/2016"))
        // Nothing to decide by: no date.
        assertEquals(listOf<LocalDate?>(null), dates("3/4/2016"))
        // Years from the neighbours; December to January is the next year.
        assertEquals(listOf(d("2016-12-30"), d("2016-12-31"), d("2017-01-01")), dates("30 December 2016", "31 December", "1 January"))
        assertEquals(listOf(d("2016-03-30"), d("2016-04-02")), dates("30 March", "2 April 2016"))
        assertEquals(listOf<LocalDate?>(null, null), dates("30 March", "2 April")) // no year anywhere
        // "Day N" counts on from the first dated section.
        assertEquals(listOf(d("2016-04-03"), d("2016-04-04"), d("2016-04-07")), dates("Day 1 · 3 April 2016", "Day 2", "Ημέρα 5"))
        assertEquals(listOf(d("2016-04-03"), d("2016-04-03"), d("2016-04-05")), dates("Day 1", "3 April 2016", "Day 3"))
    }

    private fun post(blocks: List<SiteBlock>) = SitePost("doc", "https://docs.google.com/document/d/x", "Crete, slowly", null, blocks)

    @Test fun aDiaryIsCutAtItsRepeatedHeadings() {
        val zone = ZoneId.of("Europe/Athens")
        val blocks = listOf(
            SiteBlock.Text("Ten days on the island."),
            SiteBlock.Heading("Day 1 · 3 April 2016", 2),
            SiteBlock.Text("Chania."),
            SiteBlock.Heading("Food", 3),
            SiteBlock.Text("Bougatsa."),
            SiteBlock.Heading("Day 2", 2),
            SiteBlock.Picture("https://cdn.example.com/a.jpg"),
            SiteBlock.Heading("Packing", 2),
            SiteBlock.Text("Boots."),
            SiteBlock.Heading("Day 2", 2),
            SiteBlock.Text("Again."),
        )
        val parts = SiteSections.split(post(blocks), zone, today)
        assertEquals(listOf("Crete, slowly", "Day 1 · 3 April 2016", "Day 2", "Packing", "Day 2"), parts.map { it.title })
        assertEquals(listOf("intro", "day 1 · 3 april 2016", "day 2", "packing", "day 2#2"), parts.map { it.part })
        assertEquals(listOf(SiteBlock.Text("Chania."), SiteBlock.Heading("Food", 3), SiteBlock.Text("Bougatsa.")), parts[1].blocks)
        val noon3 = LocalDate.of(2016, 4, 3).atTime(12, 0).atZone(zone).toInstant()
        val noon4 = LocalDate.of(2016, 4, 4).atTime(12, 0).atZone(zone).toInstant()
        assertEquals(
            listOf(noon3.minusSeconds(60), noon3, noon4, noon4.plusSeconds(60), noon4.plusSeconds(120)),
            parts.map { it.at },
        )
        // Every section is its own note.
        assertEquals(5, parts.map(SiteNotes::entryId).toSet().size)
    }

    @Test fun withoutRepeatedHeadingsItStaysOneNote() {
        val one = post(listOf(SiteBlock.Heading("Crete", 1), SiteBlock.Text("All of it."), SiteBlock.Heading("Food", 2)))
        assertEquals(listOf(one), SiteSections.split(one, ZoneId.of("UTC"), today))
        val undated = SiteSections.split(post(listOf(SiteBlock.Heading("A", 2), SiteBlock.Heading("B", 2))), ZoneId.of("UTC"), today)
        assertEquals(listOf<Instant?>(null, null), undated.map { it.at })
        assertNull(undated.first().part?.takeIf { it == "intro" })
    }
}
