package com.justdataplease.boringtravel.places

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PlaceNamesTest {
    @Test fun cleanDropsAdministrativeWords() {
        assertEquals("Naxos", PlaceNames.clean("Naxos Regional Unit"))
        assertEquals("Kerry", PlaceNames.clean("County Kerry"))
        assertEquals("Lake District", PlaceNames.clean("Lake District"))
        assertEquals("County", PlaceNames.clean("County"))
    }

    @Test fun compactSkipsRepeatsAndEmptyParts() {
        assertEquals("Halki, Naxos, Greece", PlaceNames.compact("Halki", listOf(null, "Naxos Regional Unit", "Aegean"), "Greece"))
        assertEquals("Naxos, Aegean, Greece", PlaceNames.compact("Naxos", listOf("Naxos", " ", "Aegean"), "Greece"))
        assertEquals("Greece", PlaceNames.compact("Greece", listOf(null), "Greece"))
        assertEquals("Lisbon, Portugal", PlaceNames.compact("Lisbon", listOf("Portugal"), "Portugal"))
        assertEquals("Somewhere", PlaceNames.compact("Somewhere", emptyList(), null))
    }

    @Test fun areaIsThePlaceBeforeTheCountry() {
        assertEquals("Naxos", PlaceNames.area("Halki, Naxos, Greece"))
        assertEquals("Naxos", PlaceNames.area("Agios Georgios, Naxos"))
        assertEquals("Naxos", PlaceNames.area("Naxos, Greece"))
        assertEquals("Νάξος", PlaceNames.area("Πορτάρα, Νάξος, Ελλάς"))
        assertEquals("Kyoto", PlaceNames.area("Kyoto"))
        assertNull(PlaceNames.area(" , "))
    }

    private val zone = ZoneId.of("Europe/Athens")
    private val day4 = LocalDate.of(2027, 6, 20)
    private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun stop(name: String, arrived: Long, left: Long?) = Stop(name, "t", arrived, left, 0.0, 0.0, name = name, createdAt = 0, updatedAt = 0)
    private fun item(day: LocalDate?, place: String, pos: Double) =
        PlanItem("$place-$pos", "t", day, pos, title = place, placeName = place, createdAt = 0, updatedAt = 0)

    @Test fun dayPlaceComesFromTheStopsOfThatDay() {
        val stops = listOf(
            stop("Parikia, Paros", at(day4.minusDays(3), 20, 30), at(day4, 7, 20)),
            stop("Agios Georgios, Naxos", at(day4, 9, 45), at(day4, 11, 35)),
            stop("Halki, Naxos", at(day4, 12, 20), null),
        )
        assertEquals("Naxos", DayPlace.label(day4, stops, emptyList(), zone))
        // Two days earlier the traveller was still in the long Paros stop.
        assertEquals("Paros", DayPlace.label(day4.minusDays(2), stops, emptyList(), zone))
    }

    @Test fun dayPlaceFallsBackToPlannedPlacesAndTiesGoToTheLaterOne() {
        val items = listOf(item(day4, "Lefkes, Paros, Greece", 1.0), item(day4, "Halki, Naxos, Greece", 2.0), item(null, "Chora, Amorgos, Greece", 1.0))
        assertEquals("Naxos", DayPlace.label(day4, emptyList(), items, zone))
        assertNull(DayPlace.label(day4.plusDays(1), emptyList(), items, zone))
    }

    @Test fun anOpenStayLastsUntilTheNextStop() {
        val stops = listOf(
            stop("Bariloche, Argentina", at(day4.minusDays(5), 15), null),
            stop("El Chaltén, Argentina", at(day4.minusDays(1), 18), null),
        )
        // Still in El Chaltén the next day, with no departure yet.
        assertEquals("El Chaltén", DayPlace.label(day4, stops, emptyList(), zone, now = at(day4, 10)))
        // Bariloche lasts until El Chaltén begins, not beyond.
        assertEquals("Bariloche", DayPlace.label(day4.minusDays(3), stops, emptyList(), zone, now = at(day4, 10)))
    }

    @Test fun daysStillToComeAreNamedByTheirPlanNotByTheLatestStop() {
        val stops = listOf(stop("El Chaltén, Argentina", at(day4.minusDays(1), 18), null))
        val items = listOf(item(day4.plusDays(4), "El Calafate, Argentina", 1.0))
        val now = at(day4, 10)
        assertEquals("El Chaltén", DayPlace.label(day4, stops, items, zone, now))
        assertEquals("El Calafate", DayPlace.label(day4.plusDays(4), stops, items, zone, now))
        // Nothing planned on a later day: no name rather than the last stop's.
        assertNull(DayPlace.label(day4.plusDays(2), stops, items, zone, now))
    }
}
