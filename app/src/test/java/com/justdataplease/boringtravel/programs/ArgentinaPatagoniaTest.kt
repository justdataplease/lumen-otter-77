package com.justdataplease.boringtravel.programs

import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.programs.data.ArgentinaPatagonia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId

class ArgentinaPatagoniaTest {
    private val destination = ArgentinaPatagonia.destination
    private val programs = destination.programs

    /** Kinds that count toward a day's pace; stays, transport and notes don't. */
    private val counted = setOf("place", "food", "activity")

    /** How many counted items a day keeps at each pace. */
    private val range = mapOf(Pace.Chill to 2..3, Pace.Balanced to 4..5, Pace.Energetic to 5..7)

    /** Where the nights are, in route order (Eat & drink adds the Valle de Uco after Mendoza). */
    private val bases = listOf("Buenos Aires", "Mendoza", "Valle de Uco", "Bariloche", "El Chaltén", "El Calafate", "Santiago")

    private fun ProgramItem.where(program: Program) = "${program.id}: $title"

    @Test fun destinationFacts() {
        assertEquals("argentina-patagonia", destination.id)
        assertEquals("Argentina & Patagonia", destination.title)
        assertEquals(listOf("AR", "CL"), destination.countryCodes)
        assertEquals(ZoneId.of("America/Argentina/Buenos_Aires"), ZoneId.of(destination.zoneId))
        assertTrue(destination.blurb.isNotBlank() && destination.bestTime.isNotBlank())
        assertTrue(destination.bestTime, "November" in destination.bestTime && "March" in destination.bestTime)
    }

    @Test fun threeProgramsOnePerFocus() {
        assertEquals(3, programs.size)
        assertEquals(Focus.entries.toSet(), programs.map { it.focus }.toSet())
        programs.forEach { p ->
            assertEquals("argentina-patagonia-${p.focus.id}", p.id)
            assertEquals(destination.id, p.destinationId)
            assertTrue(p.title.isNotBlank() && p.blurb.isNotBlank())
        }
        assertEquals(programs.size, programs.map { it.id }.distinct().size)
        assertEquals(Focus.Culture, destination.default.focus)
    }

    @Test fun threeWeeksFromBuenosAiresToSantiago() {
        programs.forEach { p ->
            assertEquals(p.id, 21, p.days.size)
            val first = p.days.first().items.first()
            val last = p.days.last().items.last()
            assertEquals(p.id, "transport", first.kind)
            assertEquals(p.id, "transport", last.kind)
            assertTrue(p.id, "Buenos Aires" in first.title && first.placeName.startsWith("Ezeiza"))
            assertTrue(p.id, "Santiago" in last.title && last.placeName.endsWith(", Chile"))
            // The route goes the demo's way, each base once, in order.
            val route = ProgramTrips.route(p).split(" → ")
            assertEquals(p.id, route, bases.filter { it in route })
            assertTrue(p.id, route.containsAll(bases - "Valle de Uco"))
        }
    }

    @Test fun flightsFlyAndBusesDrive() {
        programs.forEach { p ->
            val legs = p.days.flatMap { it.items }.filter { it.kind == "transport" }
            // Four flights between the regions, each into an airport with its page; the first one lands with no leg.
            val flights = legs.filter { it.title.startsWith("Fly to") }
            assertEquals(p.id, listOf("Buenos Aires", "Mendoza", "Bariloche", "El Calafate", "Santiago"), flights.map { it.title.removePrefix("Fly to ") })
            flights.drop(1).forEach { assertEquals(it.where(p), Vehicle.Plane, it.via) }
            flights.forEach { assertTrue(it.where(p), it.placeName.contains("Airport") && it.url != null) }
            legs.filter { it.title.startsWith("Bus") }.forEach { assertEquals(it.where(p), Vehicle.Car, it.via) }
        }
    }

    @Test fun everyPlaceIsInArgentinaOrChile() {
        programs.forEach { p ->
            (p.days.flatMap { it.items } + p.ideas).forEach { item ->
                item.at?.let { (lat, lng) ->
                    assertTrue(item.where(p), lat in -56.0..-22.0 && lng in -76.0..-53.0)
                    assertTrue(item.where(p), decimals(lat) <= 5 && decimals(lng) <= 5)
                    assertTrue(item.where(p), item.placeName.endsWith(", Argentina") || item.placeName.endsWith(", Chile"))
                }
            }
            // Santiago's days are in Chile, the rest in Argentina (the flight to Santiago lands there).
            p.days.forEach { day ->
                val country = if (day.base == "Santiago") ", Chile" else ", Argentina"
                day.items.filter { it.at != null && !it.title.startsWith("Fly to Santiago") }
                    .forEach { assertTrue(it.where(p), it.placeName.endsWith(country)) }
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
        }
    }

    @Test fun focusesSpendTheirDaysDifferently() {
        fun nights(focus: Focus, base: String) = destination.program(focus).days.count { it.base == base }
        // Sights & history: the longest Buenos Aires stay; Eat & drink: wine country; Nature & views: the mountains.
        assertTrue(nights(Focus.Culture, "Buenos Aires") > nights(Focus.Nature, "Buenos Aires"))
        assertTrue(nights(Focus.Food, "Mendoza") + nights(Focus.Food, "Valle de Uco") >= 5)
        assertTrue(nights(Focus.Nature, "El Chaltén") >= 5 && nights(Focus.Nature, "Bariloche") >= 5)
        // Few counted titles are shared between two programs (a handful of must-sees, like the glacier).
        val titles = programs.map { p -> p.days.flatMap { d -> d.items.filter { it.kind in counted }.map { it.title } }.toSet() }
        for (i in titles.indices) for (j in i + 1 until titles.size) {
            val shared = titles[i] intersect titles[j]
            assertTrue("${programs[i].id} / ${programs[j].id}: ${shared.size} shared", shared.size * 3 < minOf(titles[i].size, titles[j].size))
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
                // Nothing is booked: no prices.
                assertTrue(item.where(p), !Regex("""€|\$|US\$|ARS|CLP""").containsMatchIn(item.title + " " + item.note))
            }
            assertEquals(p.id, 5, p.ideas.size)
            assertTrue(p.id, p.sources.size >= 20 && p.sources.all { it.startsWith("https://") })
            assertEquals(p.id, p.sources.size, p.sources.distinct().size)
        }
    }

    @Test fun noRepeatsWithinAProgram() {
        programs.forEach { p ->
            val titles = p.days.flatMap { d -> d.items.filter { it.kind in counted }.map { it.title } } + p.ideas.map { it.title }
            assertEquals(p.id + " " + titles.groupBy { it }.filter { it.value.size > 1 }.keys, titles.size, titles.distinct().size)
            assertEquals(p.id, p.days.size, p.days.map { it.title }.distinct().size)
        }
    }

    @Test fun printTheNumbers() {
        // Counted items per day at each pace, for the research notes.
        for (p in programs) for (pace in Pace.entries) {
            println("${p.id} ${pace.id}: " + p.daysAt(pace).joinToString(" ") { d -> d.items.count { it.kind in counted }.toString() })
        }
        for (d in ProgramLibrary.destinations) {
            val titles = d.programs.map { p -> p.id to p.days.flatMap { day -> day.items.filter { it.kind in counted }.map { it.title } }.toSet() }
            for (i in titles.indices) for (j in i + 1 until titles.size) {
                val shared = titles[i].second intersect titles[j].second
                println("${titles[i].first} / ${titles[j].first}: ${shared.size} shared of ${titles[i].second.size} and ${titles[j].second.size}")
            }
        }
    }

    private fun decimals(v: Double) = BigDecimal(v.toString()).stripTrailingZeros().scale()
}
