package com.justdataplease.boringtravel.ui.place

import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.RouteLegSource
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlaceGroupingTest {
    private val start = LocalDate.of(2027, 3, 1)
    private val trip = Trip("t", "Argentina & Patagonia", startDate = start, endDate = start.plusDays(30), createdAt = 1, updatedAt = 1)
    private fun day(n: Int) = start.plusDays(n - 1L)
    private fun item(
        id: String, day: Int?, pos: Double, kind: String = "place", at: Pair<Double, Double>? = null,
        title: String = id, note: String = "", placeName: String = "",
    ) = PlanItem(
        id, trip.id, day?.let(::day), pos, kind = kind, title = title, note = note, placeName = placeName,
        lat = at?.first, lng = at?.second, createdAt = 1, updatedAt = 1,
    )

    // El Calafate airport, El Chaltén and its trails, then back to El Calafate town (≈ 120 km south).
    private val flyIn = item("fly", 13, 1.0, "transport", -50.2803 to -72.0531, "Fly to El Calafate")
    private val transfer = item("transfer", 13, 2.0, "transport", -49.3315 to -72.8863, "Transfer to El Chaltén", "≈ 3 h · stay to book")
    private val mirador = item("mirador", 13, 3.0, "place", -49.3373 to -72.8778, "Mirador de los Cóndores")
    private val packing = item("packing", 13, 4.0, "note", null, "Pack for the wind")
    private val capri = item("capri", 14, 1.0, "activity", -49.296 to -72.927, "Laguna Capri and Mirador Fitz Roy")
    private val hostel = item("hostel", 14, 2.0, "stay", -49.33 to -72.89, "Hostel by the river", "To book")
    private val restNote = item("rest", 15, 1.0, "note", null, "Rest day")
    private val tres = item("tres", 16, 1.0, "activity", -49.272 to -72.999, "Laguna de los Tres")
    private val back = item("back", 17, 1.0, "transport", -50.3379 to -72.2648, "Back to El Calafate")
    private val idea = item("idea", null, 1.0, "place", null, "Laguna Torre")
    private val all = listOf(back, tres, restNote, hostel, capri, packing, mirador, transfer, flyIn, idea)

    @Test fun itemsAndDaysAtAPlace() {
        val at = PlaceGrouping.itemsAt(trip.id, all, mirador.lat!!, mirador.lng!!)
        assertEquals(listOf("transfer", "mirador", "packing", "capri", "hostel", "tres"), at.map { it.id })
        assertEquals(listOf(day(13), day(14), day(16)), PlaceGrouping.days(at))
        assertEquals(listOf(13, 14, 16), PlaceGrouping.dayNumbers(trip, PlaceGrouping.days(at)))
    }

    @Test fun unlocatedItemsFollowTheLocatedItemBeforeThemOrAfterThemOnTheirDay() {
        val first = item("first", 15, 0.5, "note", null, "Coffee first")
        val walk = item("walk", 15, 2.0, "place", -49.287 to -72.881, "Chorrillo del Salto")
        val list = all + first + walk
        val at = PlaceGrouping.itemsAt(trip.id, list, mirador.lat!!, mirador.lng!!).map { it.id }
        // "first" and "rest" open day 15 before any located item: they take the first located item after them.
        assertTrue("first" in at)
        assertTrue("rest" in at)
        // A day with no located item at all gives its notes to no place.
        val alone = PlaceGrouping.itemsAt(trip.id, all, mirador.lat!!, mirador.lng!!).map { it.id }
        assertFalse("rest" in alone)
        // Ideas count only when they are located nearby.
        assertFalse("idea" in at)
    }

    @Test fun deletedAndForeignItemsAreLeftOut() {
        val deleted = mirador.copy(id = "gone", deletedAt = 5)
        val foreign = mirador.copy(id = "other", tripId = "other-trip")
        val at = PlaceGrouping.itemsAt(trip.id, all + deleted + foreign, mirador.lat!!, mirador.lng!!).map { it.id }
        assertFalse("gone" in at)
        assertFalse("other" in at)
    }

    @Test fun summaryHasStaysNotesSightsAndTheLegsInAndOut() {
        val modes = TravelModes.set(null, RouteLegKey(RouteLegSource.Planned, "fly", "transfer"), Vehicle.Car)
        val s = PlaceGrouping.summarize(trip.copy(travelModes = modes), all, mirador.lat!!, mirador.lng!!)
        assertEquals(listOf("hostel"), s.stays.map { it.id })
        assertEquals(listOf("packing"), s.notes.map { it.id })
        assertEquals(listOf("mirador", "capri", "tres"), s.sights.map { it.id })
        val arrival = s.arrival!!
        assertEquals("fly", arrival.leg.key.fromId)
        assertEquals("transfer", arrival.leg.key.toId)
        assertEquals(Vehicle.Car, arrival.vehicle)
        assertEquals("El Calafate", arrival.fromLabel)
        assertEquals("El Chaltén", arrival.toLabel)
        assertEquals("≈ 3 h · stay to book", arrival.toItem?.note)
        assertEquals("Car from El Calafate · ≈ 2 h 30", PlaceGrouping.arrivalLine(arrival))
        val departure = s.departure!!
        assertEquals("tres", departure.leg.key.fromId)
        assertEquals("back", departure.leg.key.toId)
        // Nothing chosen for the way back: by car (owner, 2026-11-07).
        assertNull(departure.leg.vehicle)
        assertEquals(Vehicle.Car, departure.vehicle)
    }

    @Test fun aPlaceIsNamedAfterTheArrivalNearIt() {
        val lat = capri.lat!!
        val lng = capri.lng!!
        assertEquals("El Chaltén", PlaceGrouping.arrivalName(all, day(14), lat, lng))
        // Before the transfer the plan has not arrived here yet; El Calafate's airport is too far to name it.
        assertNull(PlaceGrouping.arrivalName(all, day(12), lat, lng))
        assertEquals("El Calafate", PlaceGrouping.arrivalName(all, day(18), back.lat!!, back.lng!!))
    }

    @Test fun legEndsAreNamedAfterTheirPlaceAndGroupsReachOneStepOut() {
        val mendoza = item("mza", 4, 1.0, "transport", -32.8317 to -68.7929, "Fly to Mendoza")
        val show = item("show", 6, 1.0, "activity", -32.886 to -68.882, "Vendimia: Acto Central")
        val brc = item("brc", 8, 1.0, "transport", -41.1512 to -71.1578, "Fly to Bariloche")
        val hotel = item("hotel", 8, 2.0, "stay", -41.053 to -71.535, "Hotel by the lake")
        val island = item("island", 11, 1.0, "activity", -40.8456 to -71.6369, "Isla Victoria")
        val list = listOf(mendoza, show, brc, hotel, island)
        val s = PlaceGrouping.summarize(trip, list, brc.lat!!, brc.lng!!)
        assertEquals("Mendoza", s.arrival?.fromLabel)
        assertEquals("Bariloche", s.arrival?.toLabel)
        // The island is ≈ 50 km from the airport but ≈ 24 km from the hotel: one step out, it is at Bariloche.
        assertEquals(listOf("brc", "hotel", "island"), s.items.map { it.id })
        assertEquals("Mendoza", PlaceGrouping.placeOf(list, show))
    }

    @Test fun daysLabelJoinsRuns() {
        assertNull(PlaceGrouping.daysLabel(emptyList()))
        assertEquals("Day 13", PlaceGrouping.daysLabel(listOf(13)))
        assertEquals("Days 13–16", PlaceGrouping.daysLabel(listOf(16, 13, 14, 15)))
        assertEquals("Days 4, 6–8", PlaceGrouping.daysLabel(listOf(4, 6, 7, 8, 7)))
        assertEquals("Days 1–2, 5", PlaceGrouping.daysLabel(listOf(1, 2, 5)))
    }

    @Test fun lengthBuckets() {
        assertNull(PlaceGrouping.lengthBucket(0))
        assertEquals(0, PlaceGrouping.lengthBucket(1))
        assertEquals(0, PlaceGrouping.lengthBucket(2))
        assertEquals(1, PlaceGrouping.lengthBucket(3))
        assertEquals(1, PlaceGrouping.lengthBucket(4))
        assertEquals(2, PlaceGrouping.lengthBucket(9))
    }

    @Test fun placeLabelsComeFromPlaceNamesOrTravelTitles() {
        assertEquals("Naxos", PlaceGrouping.itemPlaceLabel(item("a", 1, 1.0, placeName = "Halki, Naxos, Greece")))
        assertEquals("Mendoza", PlaceGrouping.itemPlaceLabel(item("b", 1, 1.0, "transport", title = "Fly to Mendoza")))
        assertEquals("Paine Grande", PlaceGrouping.itemPlaceLabel(item("c", 1, 1.0, "transport", title = "Catamaran to Paine Grande")))
        assertEquals("Fly home", PlaceGrouping.itemPlaceLabel(item("d", 1, 1.0, "transport", title = "Fly home")))
        // Only travel items lose their verb: a place called "Road to nowhere" keeps its name.
        assertEquals("Road to nowhere", PlaceGrouping.itemPlaceLabel(item("e", 1, 1.0, "place", title = "Road to nowhere")))
    }

    @Test fun photosNearThePlaceNewestFirst() {
        fun m(id: String, at: Pair<Double, Double>?, taken: Long) =
            Media(id, trip.id, localPath = "media/$id.jpg", takenAt = taken, lat = at?.first, lng = at?.second, createdAt = 1, updatedAt = 1)
        val photos = listOf(
            m("near-old", -49.30 to -72.90, 10), m("near-new", -49.33 to -72.88, 20),
            m("far", -50.34 to -72.26, 30), m("no-place", null, 40), m("deleted", -49.3 to -72.9, 50).copy(deletedAt = 2),
        )
        assertEquals(listOf("near-new", "near-old"), PlaceGrouping.photosNear(photos, mirador.lat!!, mirador.lng!!).map { it.id })
    }

    @Test fun stickerLabelsAreShort() {
        assertEquals("Carrusel parade", PlaceGrouping.sticker("Vendimia: Carrusel parade"))
        assertEquals("Laguna Capri", PlaceGrouping.sticker("Laguna Capri and Mirador Fitz Roy"))
        assertEquals("Boutique hotel in…", PlaceGrouping.sticker("Boutique hotel in Palermo Soho"))
    }

    @Test fun heroStyleFollowsTheThemeName() {
        assertEquals(PlaceHeroStyle.Postcard, PlaceHeroStyle.of("pop"))
        assertEquals(PlaceHeroStyle.Postcard, PlaceHeroStyle.of("Postcard Pop"))
        assertEquals(PlaceHeroStyle.Trail, PlaceHeroStyle.of("trail"))
        assertEquals(PlaceHeroStyle.Sticker, PlaceHeroStyle.of("Sticker Book"))
        assertEquals(PlaceHeroStyle.Sketch, PlaceHeroStyle.of("sketchbook"))
        assertEquals(PlaceHeroStyle.Market, PlaceHeroStyle.of("market"))
        assertEquals(PlaceHeroStyle.Market, PlaceHeroStyle.of("kraft"))
        assertEquals(PlaceHeroStyle.Postcard, PlaceHeroStyle.of(null, "something new"))
    }
}
