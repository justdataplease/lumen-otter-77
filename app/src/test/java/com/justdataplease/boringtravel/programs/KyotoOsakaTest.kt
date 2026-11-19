package com.justdataplease.boringtravel.programs

import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.programs.data.KyotoOsaka
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId

class KyotoOsakaTest {
    private val destination = KyotoOsaka.destination
    private val programs = destination.programs

    /** Kinds that count toward a day's pace; stays, transport and notes don't. */
    private val counted = setOf("place", "food", "activity")

    /** How many counted items a day keeps at each pace. */
    private val range = mapOf(Pace.Chill to 2..3, Pace.Balanced to 4..5, Pace.Energetic to 5..7)

    private fun ProgramItem.where(program: Program) = "${program.id}: $title"

    @Test fun destinationFacts() {
        assertEquals("kyoto-osaka", destination.id)
        assertEquals("Kyoto & Osaka", destination.title)
        assertEquals(listOf("JP"), destination.countryCodes)
        assertEquals(ZoneId.of("Asia/Tokyo"), ZoneId.of(destination.zoneId))
        assertTrue(destination.blurb.isNotBlank() && destination.bestTime.isNotBlank())
    }

    @Test fun threeProgramsOnePerFocus() {
        assertEquals(3, programs.size)
        assertEquals(Focus.entries.toSet(), programs.map { it.focus }.toSet())
        programs.forEach { p ->
            assertEquals("kyoto-osaka-${p.focus.id}", p.id)
            assertEquals(destination.id, p.destinationId)
            assertTrue(p.title.isNotBlank() && p.blurb.isNotBlank())
        }
        assertEquals(programs.size, programs.map { it.id }.distinct().size)
        assertEquals(Focus.Culture, destination.default.focus)
    }

    @Test fun twelveDaysFromKixToKix() {
        programs.forEach { p ->
            assertEquals(p.id, 12, p.days.size)
            val first = p.days.first().items.first()
            val last = p.days.last().items.last()
            assertEquals(p.id, "transport", first.kind)
            assertEquals(p.id, "transport", last.kind)
            assertTrue(p.id, "Kansai" in first.title && "Kansai" in last.title)
            assertEquals(first.at, last.at)
        }
    }

    @Test fun everyPlaceIsInKansai() {
        programs.forEach { p ->
            (p.days.flatMap { it.items } + p.ideas).forEach { item ->
                item.at?.let { (lat, lng) ->
                    assertTrue(item.where(p), lat in 33.9..35.4 && lng in 134.5..136.2)
                    assertTrue(item.where(p), decimals(lat) <= 5 && decimals(lng) <= 5)
                    assertTrue(item.where(p), item.placeName.endsWith(", Japan"))
                }
            }
        }
    }

    @Test fun everyLegSaysHowYouGetThere() {
        programs.forEach { p ->
            val located = p.days.flatMap { d -> d.items.filter { it.at != null } }
            assertNull(p.id, located.first().via)
            located.drop(1).forEach { assertNotNull(it.where(p), it.via) }
            // A leg needs two places: items without a place have no `via`.
            p.days.flatMap { it.items }.filter { it.at == null }.forEach { assertNull(it.where(p), it.via) }
            p.ideas.forEach { assertNull(it.where(p), it.via) }
        }
    }

    @Test fun daysFitEachPace() {
        programs.forEach { p ->
            Pace.entries.forEach { pace ->
                p.daysAt(pace).forEachIndexed { i, day ->
                    val n = day.items.count { it.kind in counted }
                    assertTrue("${p.id} day ${i + 1} at ${pace.id}: $n items", n in range.getValue(pace))
                }
            }
            // Stays and getting-there items show at every pace.
            p.days.flatMap { it.items }.filter { it.kind !in counted && it.kind != "note" }
                .forEach { assertEquals(it.where(p), Pace.Chill, it.minPace) }
        }
    }

    @Test fun oneStayPerBase() {
        programs.forEach { p ->
            var base = ""
            p.days.forEachIndexed { i, day ->
                val stays = day.items.count { it.kind == "stay" }
                if (day.base != base) assertEquals("${p.id} day ${i + 1}", 1, stays) else assertEquals("${p.id} day ${i + 1}", 0, stays)
                base = day.base
            }
            assertTrue(p.id, p.days.map { it.base }.toSet().containsAll(setOf("Kyoto", "Osaka")))
        }
    }

    @Test fun textsAreShortAndFilled() {
        programs.forEach { p ->
            p.days.forEach { day ->
                assertTrue(p.id, day.title.isNotBlank() && day.base.isNotBlank())
                assertTrue("${p.id}: ${day.title}", day.title.length <= 40)
            }
            (p.days.flatMap { it.items } + p.ideas).forEach { item ->
                assertTrue(item.where(p), item.title.isNotBlank())
                assertTrue(item.where(p), item.note.length <= 100)
                assertEquals(item.where(p), item.kind, PlanKind.of(item.kind).id)
                assertTrue(item.where(p), (item.at == null) == item.placeName.isBlank())
                item.time?.let { assertTrue(item.where(p), Regex("\\d{2}:\\d{2}").matches(it)); LocalTime.parse(it) }
            }
            assertTrue(p.id, p.ideas.size in 3..6)
            assertTrue(p.id, p.sources.isNotEmpty() && p.sources.all { it.startsWith("https://") })
        }
    }

    @Test fun noRepeatsWithinAProgram() {
        programs.forEach { p ->
            val titles = p.days.flatMap { d -> d.items.filter { it.kind in counted }.map { it.title } } + p.ideas.map { it.title }
            assertEquals(p.id + " " + titles.groupBy { it }.filter { it.value.size > 1 }.keys, titles.size, titles.distinct().size)
            assertEquals(p.id, p.days.size, p.days.map { it.title }.distinct().size)
        }
    }

    private fun decimals(v: Double) = BigDecimal(v.toString()).stripTrailingZeros().scale()
}
