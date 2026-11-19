package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DraftEditsTest {
    private val start = LocalDate.of(2027, 6, 1)
    private val trip = Trip("trip", "Greece", startDate = start, endDate = start.plusDays(9), createdAt = 1, updatedAt = 2)
    private val flight = PlanItem("flight-id", "trip", day = start, timeMinutes = 600, position = 3.5, kind = "transport",
        title = "Flight to Athens", note = "Booking ABC123\nSeat 12A; prepaid bag", placeName = "Athens airport", lat = 37.9364, lng = 23.9445,
        done = true, source = "manual", externalId = "calendar-event", createdAt = 10, updatedAt = 20)

    private fun preview(vararg patches: DraftUpdate) = DraftEdits.preview(patches.toList(), trip, listOf(flight))

    @Test fun partialTimePatchPreservesBookingFieldsAndStableIdentity() {
        val update = preview(DraftUpdate(flight.id, time = "14:35")).updates.single()
        val changed = DraftEdits.resolve(listOf(update), listOf(flight), emptySet(), 99).items.single()
        assertEquals(flight.copy(timeMinutes = 875, updatedAt = 99), changed)
        assertEquals(flight.id, changed.id)
        assertEquals(flight.note, changed.note)
        assertTrue(changed.done)
    }

    @Test fun confirmedReferenceAppendsWithoutReplacingOldNotes() {
        val update = preview(DraftUpdate(flight.id, noteAppend = "New departure confirmed by airline")).updates.single()
        assertEquals(flight.note + "\nNew departure confirmed by airline", update.after.note)
        val second = DraftEdits.preview(listOf(DraftUpdate(flight.id, noteAppend = "New departure confirmed by airline")), trip, listOf(update.after))
        assertFalse(second.updates.single().changed)
    }

    @Test fun onlyExplicitEmptyValuesClearDateTimeOrNote() {
        val keep = preview(DraftUpdate(flight.id)).updates.single()
        assertEquals(flight, keep.after)
        val clear = preview(DraftUpdate(flight.id, day = "", time = "", note = "")).updates.single().after
        assertNull(clear.day)
        assertNull(clear.timeMinutes)
        assertEquals("", clear.note)
        assertEquals(flight.placeName, clear.placeName)
        assertEquals(flight.lat, clear.lat)
    }

    @Test fun unknownForeignAndRepeatedTargetsBlockTheDraft() {
        assertTrue(preview(DraftUpdate("unknown", time = "15:00")).problems.isNotEmpty())
        val foreign = flight.copy(tripId = "other")
        assertTrue(DraftEdits.preview(listOf(DraftUpdate(flight.id, time = "15:00")), trip, listOf(foreign)).problems.isNotEmpty())
        assertTrue(preview(DraftUpdate(flight.id, time = "15:00"), DraftUpdate(flight.id, time = "16:00")).problems.isNotEmpty())
    }

    @Test fun malformedPatchValuesNeverBecomeSilentPartialEdits() {
        for (patch in listOf(DraftUpdate(flight.id, day = "tomorrow"), DraftUpdate(flight.id, day = "2027-07-01"),
            DraftUpdate(flight.id, time = "25:99"), DraftUpdate(flight.id, title = ""), DraftUpdate(flight.id, lat = 12.0),
            DraftUpdate(flight.id, lat = 999.0, lng = 50.0), DraftUpdate(flight.id, note = "Replace", noteAppend = "Append"))) {
            val result = preview(patch)
            assertTrue(patch.toString(), result.problems.isNotEmpty())
            assertTrue(result.updates.isEmpty())
        }
    }

    @Test fun concurrentBookingEditCannotBeOverwritten() {
        val updates = preview(DraftUpdate(flight.id, time = "15:00")).updates
        val current = flight.copy(note = "Traveller added a new confirmation", updatedAt = 21)
        assertTrue(runCatching { DraftEdits.resolve(updates, listOf(current), emptySet(), 99) }.exceptionOrNull() is StaleDraftException)
        // Even a concurrent edit sharing the timestamp is protected.
        assertTrue(runCatching { DraftEdits.resolve(updates, listOf(current.copy(updatedAt = flight.updatedAt)), emptySet(), 99) }.exceptionOrNull() is StaleDraftException)
    }

    @Test fun removedItemIsNeverRecreatedByAStaleProposal() {
        val updates = preview(DraftUpdate(flight.id, time = "15:00")).updates
        assertTrue(runCatching { DraftEdits.resolve(updates, emptyList(), emptySet(), 99) }.exceptionOrNull() is StaleDraftException)
        assertTrue(runCatching { DraftEdits.resolve(updates, listOf(flight.copy(deletedAt = 30)), emptySet(), 99) }.exceptionOrNull() is StaleDraftException)
    }

    @Test fun applyingTheSameReviewedAmendmentTwiceIsANoOp() {
        val updates = preview(DraftUpdate(flight.id, time = "15:00", noteAppend = "Confirmed")).updates
        val first = DraftEdits.resolve(updates, listOf(flight), emptySet(), 99)
        val second = DraftEdits.resolve(updates, first.items, emptySet(), 100)
        assertEquals(1, first.items.size)
        assertTrue(second.items.isEmpty())
        assertEquals(1, second.alreadyApplied)
    }

    @Test fun untickingAChangePreservesThatItemEvenIfItChangedConcurrently() {
        val update = preview(DraftUpdate(flight.id, time = "15:00")).updates.single()
        assertTrue(DraftEdits.resolve(listOf(update), listOf(flight.copy(updatedAt = 500)), setOf(update.key), 600).items.isEmpty())
    }

    @Test fun additionsAndUpdatesShareOneValidatedApplication() {
        val draft = TripDraft(days = listOf(DraftDay(date = start.plusDays(1).toString(), items = listOf(DraftItem(title = "Museum")))), updates = listOf(DraftUpdate(flight.id, time = "15:00")))
        val layout = DraftLayout.of(draft, trip, listOf(flight))
        val first = DraftApplier.apply(layout, trip, listOf(flight), emptySet(), "draft", 99) { "new-id" }
        assertEquals(listOf("new-id"), first.items.map { it.id })
        assertEquals(listOf(flight.id), first.updatedItems.map { it.id })
        val now = first.items + first.updatedItems
        val repeat = DraftApplier.apply(DraftLayout.of(draft, trip, now, listOf(flight)), trip, now, emptySet(), "draft", 100) { "must-not-use" }
        assertTrue(repeat.items.isEmpty())
        assertTrue(repeat.updatedItems.isEmpty())
        assertEquals(1, repeat.alreadyUpdated)
        assertEquals(1, repeat.duplicates)
    }

    @Test fun invalidUpdateBlocksAdditionsToo() {
        val draft = TripDraft(ideas = listOf(DraftItem(title = "Museum")), updates = listOf(DraftUpdate("unknown", time = "15:00")))
        val layout = DraftLayout.of(draft, trip, listOf(flight))
        var ids = 0
        assertTrue(runCatching { DraftApplier.apply(layout, trip, listOf(flight), emptySet(), "draft", 99) { ids++; "new" } }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, ids)
    }

    @Test fun parserPreservesPatchPresenceIncludingExplicitClears() {
        val draft = TripDraftParser.parse("""{"updates":[{"itemId":"flight-id","time":"","noteAppend":"Confirmed"}],"questions":[]}""")
        assertEquals(DraftUpdate(flight.id, time = "", noteAppend = "Confirmed"), draft.updates.single())
        assertEquals(1, draft.changeCount)
        assertEquals(0, draft.itemCount)
    }

    @Test fun anEndCountsFromTheItemsDayAndMovesWithIt() {
        val stay = flight.copy(kind = "stay", title = "Hotel", timeMinutes = 15 * 60)
        fun after(vararg p: DraftUpdate) = DraftEdits.preview(p.toList(), trip, listOf(stay)).updates.single().after
        val three = after(DraftUpdate(stay.id, endDate = start.plusDays(3).toString(), endTime = "11:00"))
        assertEquals(3, three.endDays); assertEquals(660, three.endMinutes)
        // Moved two days with a last day given: counted from the new day.
        val moved = after(DraftUpdate(stay.id, day = start.plusDays(2).toString(), endDate = start.plusDays(4).toString()))
        assertEquals(2, moved.endDays)
        // "" removes the end; a last day before the day is refused.
        val withEnd = stay.copy(endDays = 2, endMinutes = 600)
        val none = DraftEdits.preview(listOf(DraftUpdate(stay.id, endDate = "")), trip, listOf(withEnd)).updates.single().after
        assertNull(none.endDays); assertNull(none.endMinutes)
        val bad = DraftEdits.preview(listOf(DraftUpdate(stay.id, endDate = start.minusDays(1).toString())), trip, listOf(stay))
        assertTrue(bad.updates.isEmpty() && bad.problems.isNotEmpty())
        // A concert's end time on its own: the same day.
        val show = after(DraftUpdate(stay.id, endTime = "18:00"))
        assertNull(show.endDays); assertEquals(18 * 60, show.endMinutes)
    }

    @Test fun aProposedMapsLinkIsTheMapsLinkAndAWebsiteStaysTheWebsite() {
        val hotel = flight.copy(kind = "stay", title = "Hostal Alicante", placeName = "Hostal Alicante, Madrid", url = "https://www.hostalalicante.es/", note = "")
        fun after(patch: DraftUpdate) = DraftEdits.preview(listOf(patch), trip, listOf(hotel)).updates.single().after
        val maps = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"
        val withMaps = after(DraftUpdate(hotel.id, url = maps))
        assertEquals("https://www.hostalalicante.es/", withMaps.url)
        assertEquals(maps, withMaps.mapsUrl)
        val newSite = after(DraftUpdate(hotel.id, url = "https://hostalalicante.com/"))
        assertEquals("https://hostalalicante.com/", newSite.url)
        assertNull(newSite.mapsUrl)
        // "" removes the website: a Google Maps link to the place takes its place, unless the item has its own.
        val removed = after(DraftUpdate(hotel.id, url = ""))
        assertNull(removed.url)
        assertTrue(removed.mapsUrl!!.startsWith("https://www.google.com/maps/search/?api=1&query=Hostal"))
        val own = DraftEdits.preview(listOf(DraftUpdate(hotel.id, url = "")), trip, listOf(hotel.copy(mapsUrl = maps))).updates.single().after
        assertEquals(null to maps, own.url to own.mapsUrl)
        // Another place under another name: the old website goes, the Maps link follows the new place.
        val moved = DraftEdits.preview(
            listOf(DraftUpdate(hotel.id, title = "Suspiro 201", placeName = "Suspiro 201, Granada")), trip, listOf(hotel.copy(mapsUrl = maps)),
        ).updates.single().after
        assertNull(moved.url)
        assertTrue(moved.mapsUrl!!.startsWith("https://www.google.com/maps/search/?api=1&query=Suspiro"))
    }
}
