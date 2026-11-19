package com.justdataplease.boringtravel.programs

import com.justdataplease.boringtravel.programs.data.LisbonPorto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Lisbon & Porto programs: one per focus, nine days each (Lisbon, the train north, Porto), real places inside
 * mainland Portugal, a way to reach every place, day sizes that match each pace, short texts, and the demo journal's
 * places on the same days of the Sights & history program.
 */
class LisbonPortoTest {
    private val destination = LisbonPorto.destination
    private val programs = destination.programs

    /** The kinds that make a day fuller; stays, transport and notes are not counted. */
    private val counted = setOf("place", "food", "activity")
    private val kinds = setOf("place", "food", "stay", "transport", "activity", "note")
    private val range = mapOf(Pace.Chill to 2..3, Pace.Balanced to 4..5, Pace.Energetic to 5..7)

    private fun Program.allItems() = days.flatMap { it.items } + ideas

    @Test
    fun oneProgramPerFocus() {
        assertEquals("lisbon-porto", destination.id)
        assertEquals("Lisbon & Porto", destination.title)
        assertEquals(listOf("PT"), destination.countryCodes)
        assertEquals("Europe/Lisbon", ZoneId.of(destination.zoneId).id)
        assertEquals(3, programs.size)
        assertEquals(Focus.entries.toSet(), programs.map { it.focus }.toSet())
        for (focus in Focus.entries) {
            val p = destination.program(focus)
            assertEquals(focus, p.focus)
            assertEquals("lisbon-porto-${focus.id}", p.id)
            assertEquals(destination.id, p.destinationId)
        }
        assertEquals(Focus.Culture, destination.default.focus)
    }

    @Test
    fun idsAreUnique() {
        val ids = programs.map { it.id } + destination.id
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun nineDaysFromLisbonToPorto() {
        for (p in programs) {
            assertEquals(p.id, 9, p.days.size)
            val bases = p.days.map { it.base }
            assertEquals(p.id, "Lisbon", bases.first())
            assertEquals(p.id, "Porto", bases.last())
            assertEquals(p.id + ": one change of base", 1, bases.zipWithNext().count { (a, b) -> a != b })
            // A stay on the first day and on the day the base changes, nowhere else.
            val stayDays = p.days.withIndex().filter { (_, d) -> d.items.any { it.kind == "stay" } }.map { it.index }
            val change = bases.indexOfFirst { it == "Porto" }
            assertEquals(p.id, listOf(0, change), stayDays)
            // Arrive at Lisbon airport, fly home from Porto airport.
            val first = p.days.first().items.first()
            val last = p.days.last().items.last()
            assertEquals(p.id, "transport", first.kind)
            assertTrue(p.id, first.placeName.startsWith("Lisbon Airport"))
            assertEquals(p.id, "transport", last.kind)
            assertTrue(p.id, last.placeName.startsWith("Porto Airport"))
        }
    }

    @Test
    fun locatedItemsAreInMainlandPortugal() {
        for (p in programs) for (item in p.allItems()) {
            val at = item.at ?: continue
            assertTrue("${item.title}: lat ${at.first}", at.first in 36.9..42.2)
            assertTrue("${item.title}: lng ${at.second}", at.second in -9.6..-6.1)
            assertTrue("${item.title}: placeName", item.placeName.isNotBlank())
            for (v in listOf(at.first, at.second)) {
                val decimals = v.toBigDecimal().stripTrailingZeros().scale()
                assertTrue("${item.title}: $v has more than 5 decimals", decimals <= 5)
            }
        }
    }

    @Test
    fun everyLocatedItemSaysHowToGetThere() {
        for (p in programs) {
            val items = p.days.flatMap { it.items }
            assertNotNull(p.id + ": the first item is located", items.first().at)
            assertNull(p.id + ": no via on the first item", items.first().via)
            for (item in items.drop(1)) {
                if (item.at != null) assertNotNull("${p.id} / ${item.title}: via", item.via)
                else assertNull("${p.id} / ${item.title}: via without a place", item.via)
            }
            for (idea in p.ideas) assertNull("${p.id} / idea ${idea.title}: via", idea.via)
        }
    }

    @Test
    fun daySizesMatchThePace() {
        for (p in programs) for (pace in Pace.entries) {
            p.daysAt(pace).forEachIndexed { i, day ->
                val n = day.items.count { it.kind in counted }
                assertTrue("${p.id} day ${i + 1} at ${pace.id}: $n items", n in range.getValue(pace))
            }
        }
    }

    @Test
    fun textsAreShortAndKindsKnown() {
        for (p in programs) {
            assertTrue(p.title.isNotBlank() && p.blurb.isNotBlank())
            assertTrue(p.id + ": ideas", p.ideas.size in 3..6)
            assertTrue(p.id + ": sources", p.sources.isNotEmpty() && p.sources.all { it.startsWith("https://") })
            for (day in p.days) {
                assertTrue(day.title.isNotBlank() && day.base.isNotBlank())
                assertTrue(p.id + ": " + day.title, day.items.isNotEmpty())
            }
            for (item in p.allItems()) {
                assertTrue("${p.id}: kind ${item.kind}", item.kind in kinds)
                assertTrue("${p.id}: blank title", item.title.isNotBlank())
                assertTrue("${item.title}: note ${item.note.length} chars", item.note.length <= 100)
            }
        }
    }

    @Test
    fun timesAreHoursAndMinutes() {
        val hhmm = DateTimeFormatter.ofPattern("HH:mm")
        for (p in programs) for (item in p.allItems()) {
            val t = item.time ?: continue
            assertTrue("${item.title}: $t", Regex("""\d{2}:\d{2}""").matches(t))
            LocalTime.parse(t, hhmm)
        }
    }

    /** The demo's journal (SampleLisbon) writes about these places on these days; the default program must agree. */
    @Test
    fun demoJournalPlacesSitOnTheirDays() {
        val days = destination.default.days
        val journal = mapOf(
            1 to listOf("Miradouro de Santa Luzia"),
            2 to listOf("Martim Moniz", "São Jorge Castle"),
            3 to listOf("Jerónimos Monastery", "Pastéis de Belém", "Belém Tower", "LX Factory"),
            4 to listOf("Sintra station", "Pena Palace", "Quinta da Regaleira", "Castle of the Moors"),
            5 to listOf("Time Out Market", "Bairro Alto"),
            6 to listOf("Porto Campanhã station", "Ribeira", "Cais da Ribeira"),
            7 to listOf("Livraria Lello", "Dom Luís I Bridge", "Cais de Gaia"),
            8 to listOf("Pinhão station"),
        )
        for ((day, places) in journal) for (place in places) {
            assertTrue("day $day: $place", days[day - 1].items.any { it.placeName.startsWith(place) })
        }
        // The Alfama and Ribeira stays, as in the demo's stops.
        assertTrue(days[0].items.any { it.kind == "stay" && it.placeName.startsWith("Alfama") })
        assertTrue(days[5].items.any { it.kind == "stay" && it.placeName.startsWith("Ribeira") })
    }
}
