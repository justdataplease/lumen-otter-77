package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DraftApplierTest {
    private val start: LocalDate = LocalDate.of(2027, 6, 17)
    private val trip = Trip("t1", "Cyclades, slowly", startDate = start, endDate = start.plusDays(9), createdAt = 1, updatedAt = 1)
    private val existing = listOf(
        PlanItem("e1", "t1", day = start.plusDays(3), position = 1.0, title = "Ferry Paros → Naxos", createdAt = 1, updatedAt = 1),
        PlanItem("e2", "t1", day = start.plusDays(3), position = 4.0, timeMinutes = 19 * 60 + 40, title = "Portara at sunset", createdAt = 1, updatedAt = 1),
        PlanItem("e3", "t1", day = null, position = 2.0, title = "Hozoviotissa monastery", createdAt = 1, updatedAt = 1),
        PlanItem("e4", "t1", day = start, position = 9.0, title = "Deleted one", createdAt = 1, updatedAt = 1, deletedAt = 5),
    )

    private fun ids(): () -> String {
        var n = 0
        return { "id${++n}" }
    }

    private fun item(title: String, time: String? = null, kind: String = "place", note: String = "") = DraftItem(time, kind, title, note)

    private fun apply(draft: TripDraft, trip: Trip? = this.trip, existing: List<PlanItem> = this.existing, unticked: Set<String> = emptySet()): ApplyResult =
        DraftApplier.apply(DraftLayout.of(draft, trip, existing), trip, existing, unticked, draftId = "draft-1", now = 100, newId = ids())

    @Test fun datesAndIndexesPlaceItemsOnDays() {
        val r = apply(
            TripDraft(
                days = listOf(
                    DraftDay(date = "2027-06-18", items = listOf(item("Lefkes walk", "10:00"))),
                    DraftDay(dayIndex = 5, items = listOf(item("Lunch in Halki", "12:30", "food"))),
                ),
            ),
        )
        assertEquals(listOf(start.plusDays(1), start.plusDays(4)), r.items.map { it.day })
        assertEquals(listOf(600, 750), r.items.map { it.timeMinutes })
        assertEquals("2 items on 2 days", r.summary)
    }

    @Test fun aGoogleMapsLinkIsTheMapsLinkAndAWebsiteTheWebsite() {
        val maps = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"
        val r = apply(TripDraft(ideas = listOf(
            item("Lefteris").copy(url = maps),
            item("Blue Star").copy(url = "https://www.bluestarferries.com/"),
            item("Halki").copy(placeName = "Halki, Naxos"),
            item("Buy tickets", kind = "note").copy(placeName = "Port"),
        )))
        val byTitle = r.items.associateBy { it.title }
        assertEquals(null to maps, byTitle.getValue("Lefteris").let { it.url to it.mapsUrl })
        assertEquals("https://www.bluestarferries.com/" to null, byTitle.getValue("Blue Star").let { it.url to it.mapsUrl })
        // No link given: a Google Maps link to the place, kept as the Maps link; a note has none.
        assertEquals("https://www.google.com/maps/search/?api=1&query=Halki%2C+Naxos", byTitle.getValue("Halki").mapsUrl)
        assertEquals(null to null, byTitle.getValue("Buy tickets").let { it.url to it.mapsUrl })
    }

    @Test fun newItemsGoAfterTheExistingOnes() {
        val r = apply(TripDraft(days = listOf(DraftDay(dayIndex = 4, items = listOf(item("Drop bags"), item("Swim at Agios Prokopios"))))))
        assertEquals(listOf(5.0, 6.0), r.items.map { it.position })
        val idea = apply(TripDraft(ideas = listOf(item("Kitron tasting")))).items.single()
        assertEquals(3.0, idea.position, 0.0)
        val emptyDay = apply(TripDraft(days = listOf(DraftDay(dayIndex = 2, items = listOf(item("Beach")))))).items.single()
        assertEquals(1.0, emptyDay.position, 0.0)
    }

    @Test fun duplicatesOnTheSameDayAreSkipped() {
        val draft = TripDraft(
            days = listOf(
                DraftDay(dayIndex = 4, items = listOf(item("Portara at Sunset!"), item("Portára at sunset"), item("Dinner by the port"))),
                DraftDay(dayIndex = 5, items = listOf(item("Portara at sunset"))),
            ),
        )
        val layout = DraftLayout.of(draft, trip, existing)
        assertEquals(listOf(true, true, false), layout.days[0].items.map { it.duplicate })
        assertFalse("another day is not a duplicate", layout.days[1].items.single().duplicate)
        val r = apply(draft)
        assertEquals(listOf("Dinner by the port", "Portara at sunset"), r.items.map { it.title })
        assertEquals(2, r.duplicates)
    }

    @Test fun ideasSkipAnythingAlreadyPlanned() {
        val r = apply(TripDraft(ideas = listOf(item("Hozoviotissa Monastery"), item("Ferry Paros - Naxos"), item("Kitron tasting"), item("Kitron tasting"))))
        assertEquals(listOf("Kitron tasting"), r.items.map { it.title })
        assertNull(r.items.single().day)
        assertEquals("1 idea", r.summary)
        assertEquals(3, r.duplicates)
    }

    @Test fun deletedItemsDontCountAsDuplicates() {
        val r = apply(TripDraft(days = listOf(DraftDay(dayIndex = 1, items = listOf(item("Deleted one"))))))
        assertEquals(1, r.items.size)
        assertEquals(1.0, r.items.single().position, 0.0)
    }

    @Test fun untickedItemsAreLeftOut() {
        val draft = TripDraft(days = listOf(DraftDay(dayIndex = 2, items = listOf(item("A"), item("B")))), ideas = listOf(item("C")))
        val r = apply(draft, unticked = setOf("d0-1", "i0"))
        assertEquals(listOf("A"), r.items.map { it.title })
    }

    @Test fun aDateAfterTheTripMakesItLonger() {
        val r = apply(TripDraft(days = listOf(DraftDay(date = "2027-06-28", items = listOf(item("Late swim", note = "If the weather holds"))))))
        val swim = r.items.single()
        assertEquals(LocalDate.of(2027, 6, 28), swim.day)
        assertEquals("If the weather holds", swim.note)
        assertTrue(r.saveTrip)
        assertEquals(LocalDate.of(2027, 6, 28), r.trip.endDate)
        assertEquals(start, r.trip.startDate)
        // Unticked, the trip keeps its dates and the swim waits outside them.
        val kept = apply(TripDraft(days = listOf(DraftDay(date = "2027-06-28", items = listOf(item("Late swim"))))), unticked = setOf(TripChange.DATES))
        assertSame(trip, kept.trip)
        assertEquals(LocalDate.of(2027, 6, 28), kept.items.single().day)
    }

    @Test fun sameDateTwiceIsOneDayInDateOrder() {
        val layout = DraftLayout.of(
            TripDraft(
                days = listOf(
                    DraftDay(date = "2027-06-20", items = listOf(item("Late"))),
                    DraftDay(date = "2027-06-18", items = listOf(item("Early"))),
                    DraftDay(date = "2027-06-20", items = listOf(item("Later"))),
                ),
            ),
            trip, existing,
        )
        assertEquals(listOf(start.plusDays(1), start.plusDays(3)), layout.days.map { it.date })
        assertEquals(listOf(2, 4), layout.days.map { it.dayNumber })
        assertEquals(listOf("Late", "Later"), layout.days[1].items.map { it.item.title })
    }

    @Test fun newTripTakesTheDraftsTitleAndDates() {
        val r = apply(
            TripDraft(
                title = "Kyoto & Osaka", startDate = "2027-10-03", endDate = "2027-10-14",
                days = listOf(DraftDay(dayIndex = 2, items = listOf(item("Fushimi Inari", "08:00")))),
                ideas = listOf(item("Tea ceremony", kind = "activity")),
            ),
            trip = null, existing = emptyList(),
        )
        assertTrue(r.newTrip)
        assertTrue(r.saveTrip)
        assertEquals("id1", r.trip.id)
        assertEquals("Kyoto & Osaka", r.trip.title)
        assertEquals(LocalDate.of(2027, 10, 3), r.trip.startDate)
        assertEquals(LocalDate.of(2027, 10, 14), r.trip.endDate)
        assertEquals(100L, r.trip.createdAt)
        assertTrue(r.items.all { it.tripId == "id1" })
        assertEquals(LocalDate.of(2027, 10, 4), r.items[0].day)
        assertEquals("1 item on 1 day, 1 idea", r.summary)
    }

    @Test fun newTripEndFollowsTheDays() {
        val layout = DraftLayout.of(
            TripDraft(startDate = "2027-05-01", days = (1..3).map { DraftDay(dayIndex = it, items = listOf(item("Day $it"))) }),
            null, emptyList(),
        )
        assertEquals(LocalDate.of(2027, 5, 1), layout.start)
        assertEquals(LocalDate.of(2027, 5, 3), layout.end)
        assertTrue(layout.datesFromDraft)
        assertEquals(DraftLayout.NEW_TRIP_TITLE, layout.title)
    }

    @Test fun newTripDatesFromDatedDaysAlone() {
        val layout = DraftLayout.of(
            TripDraft(days = listOf(DraftDay(date = "2027-05-04", items = listOf(item("B"))), DraftDay(date = "2027-05-02", items = listOf(item("A"))))),
            null, emptyList(),
        )
        assertEquals(LocalDate.of(2027, 5, 2), layout.start)
        assertEquals(LocalDate.of(2027, 5, 4), layout.end)
    }

    @Test fun tripLengthIsCapped() {
        val layout = DraftLayout.of(TripDraft(startDate = "2027-01-01", endDate = "2028-01-01", ideas = listOf(item("A"))), null, emptyList())
        assertEquals(LocalDate.of(2027, 1, 1).plusDays(DraftLayout.MAX_TRIP_DAYS - 1L), layout.end)
    }

    @Test fun anUndatedScheduleMustBeClarifiedBeforeItCanBeSaved() {
        val draft = TripDraft(days = listOf(DraftDay(dayIndex = 2, items = listOf(item("Bus to Lefkes", note = "From the port"), item("Swim")))))
        val layout = DraftLayout.of(draft, null, emptyList())
        assertNull(layout.start)
        assertEquals(listOf("Day 2 · From the port", "Day 2"), layout.ideas.map { it.note })
        assertTrue(layout.questions.any { "dates" in it })
        assertTrue(runCatching { apply(draft, trip = null, existing = emptyList()) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun questionsBlockSavingEvenIfTheModelAlsoReturnsItems() {
        val draft = TripDraft(days = listOf(DraftDay(dayIndex = 2, items = listOf(item("Flight")))), questions = listOf("Which of the two flight times is confirmed?"))
        assertTrue(runCatching { apply(draft) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun resolvedQuestionsKeepUnknownBookingTimesEmpty() {
        val draft = TripDraft(days = listOf(DraftDay(dayIndex = 2, items = listOf(item("Unconfirmed ferry")))))
        val result = apply(draft)
        assertEquals(start.plusDays(1), result.items.single().day)
        assertNull(result.items.single().timeMinutes)
    }

    @Test fun aTripWithoutDatesGetsTheDraftsDatesAndANameOnlyWhenTicked() {
        val someday = Trip("t2", "Japan someday", createdAt = 1, updatedAt = 1)
        val draft = TripDraft(title = "Other name", startDate = "2027-10-03", endDate = "2027-10-05", days = listOf(DraftDay(dayIndex = 3, items = listOf(item("Nara")))))
        val r = apply(draft, trip = someday, existing = emptyList(), unticked = setOf(TripChange.TITLE))
        assertTrue(r.saveTrip)
        assertFalse(r.newTrip)
        assertEquals("Japan someday", r.trip.title)
        assertEquals(LocalDate.of(2027, 10, 3), r.trip.startDate)
        assertEquals(LocalDate.of(2027, 10, 5), r.trip.endDate)
        assertEquals(100L, r.trip.updatedAt)
        assertEquals(LocalDate.of(2027, 10, 5), r.items.single().day)
        assertEquals("Other name", apply(draft, trip = someday, existing = emptyList()).trip.title)
    }

    @Test fun aSavedTripsNewNameAndDatesAreReviewedChanges() {
        val draft = TripDraft(title = "Renamed", startDate = "2027-01-01", endDate = "2027-01-02", days = listOf(DraftDay(dayIndex = 1, items = listOf(item("A")))))
        val change = DraftLayout.of(draft, trip, existing).tripChange!!
        assertEquals("Renamed", change.title)
        assertEquals(LocalDate.of(2027, 1, 1), change.start)
        assertEquals(0L, change.shiftDays) // the plan moves only when the draft says so
        // Both unticked: the trip stays as it is.
        val kept = apply(draft, unticked = setOf(TripChange.TITLE, TripChange.DATES))
        assertFalse(kept.saveTrip)
        assertSame(trip, kept.trip)
        // Ticked: renamed and moved.
        val moved = apply(draft)
        assertEquals("Renamed", moved.trip.title)
        assertEquals(LocalDate.of(2027, 1, 2), moved.trip.endDate)
        assertEquals(LocalDate.of(2027, 1, 1), moved.items.single().day)
    }

    @Test fun neverTouchesTheTravellersItems() {
        val before = existing.toList()
        val r = apply(TripDraft(days = listOf(DraftDay(dayIndex = 4, items = listOf(item("Portara at sunset"), item("New thing"))))))
        assertEquals(before, existing)
        assertTrue(r.items.none { it.id in existing.map { e -> e.id } })
        assertEquals(listOf("New thing"), r.items.map { it.title })
    }

    @Test fun itemsAreMarkedAsAi() {
        val r = apply(
            TripDraft(
                days = listOf(
                    DraftDay(
                        dayIndex = 1,
                        items = listOf(DraftItem("16:10", "transport", "Ferry Piraeus → Paros", "About 4 h", "Parikia", 37.0853, 25.1488)),
                    ),
                ),
            ),
        )
        val i = r.items.single()
        assertEquals("ai", i.source)
        assertEquals("draft-1", i.externalId)
        assertEquals("transport", i.kind)
        assertEquals(16 * 60 + 10, i.timeMinutes)
        assertEquals("Parikia", i.placeName)
        assertEquals(37.0853, i.lat!!, 0.0)
        assertEquals(100L, i.createdAt)
        assertFalse(i.done)
    }

    @Test fun summaries() {
        fun p(day: LocalDate?) = PlanItem("x", "t", day = day, title = "x", createdAt = 0, updatedAt = 0)
        assertEquals("Nothing new", DraftApplier.summary(emptyList()))
        assertEquals("1 item on 1 day", DraftApplier.summary(listOf(p(start))))
        assertEquals("3 items on 2 days, 2 ideas", DraftApplier.summary(listOf(p(start), p(start), p(start.plusDays(1)), p(null), p(null))))
    }

    @Test fun normalisedTitles() {
        assertEquals("portara at sunset", DraftLayout.normaliseTitle("  Portára at  SUNSET! "))
        assertEquals(DraftLayout.normaliseTitle("Οδός Απόλλωνος"), DraftLayout.normaliseTitle("ΟΔΟΣ ΑΠΟΛΛΩΝΟΣ"))
    }
}
