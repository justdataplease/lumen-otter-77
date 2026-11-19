package com.justdataplease.boringtravel.checkin

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DailyCheckInTest {
    private val day = LocalDate.of(2028, 5, 4)
    private val zone = ZoneId.of("Europe/Athens")
    private val now = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val trip = Trip("today", "Public fixture", startDate = day, endDate = day.plusDays(2), createdAt = 1, updatedAt = 1)
    private fun item(id: String, date: LocalDate? = day, position: Double = 1.0, lat: Double? = 37.98, lng: Double? = 23.72) =
        PlanItem(id = id, tripId = trip.id, title = id, day = date, position = position, lat = lat, lng = lng, createdAt = 1, updatedAt = 1)
    private fun prompt(places: List<CheckInPlace>) = CheckInPrompt(trip.id, trip.title, day, places)

    @Test fun dayUsesPhoneTimeZoneAcrossMidnightAndDst() {
        val at = Instant.parse("2028-05-03T22:30:00Z").toEpochMilli()
        assertEquals(day, DailyCheckIn.localDay(at, zone))
        assertEquals(day.minusDays(1), DailyCheckIn.localDay(at, ZoneId.of("America/New_York")))
        val dst = Instant.parse("2028-03-26T01:30:00Z").toEpochMilli()
        assertEquals(LocalDate.of(2028, 3, 26), DailyCheckIn.localDay(dst, zone))
    }

    @Test fun aDayAlreadyWrittenForTheTripIsNotAskedAbout() {
        fun entry(id: String, at: Long, trip: String? = this.trip.id, deleted: Long? = null, zone: String = "Europe/Athens") =
            com.justdataplease.boringtravel.data.db.Entry(id, trip, at, zone, createdAt = 1, updatedAt = 1, deletedAt = deleted)
        assertFalse(DailyCheckIn.writtenOn(emptyList(), trip.id, day))
        assertTrue(DailyCheckIn.writtenOn(listOf(entry("today", now)), trip.id, day))
        // Yesterday's entry, another trip's, an entry without a trip and a deleted one don't count.
        val yesterday = day.minusDays(1).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        assertFalse(DailyCheckIn.writtenOn(listOf(entry("y", yesterday), entry("o", now, "other"), entry("n", now, null), entry("d", now, deleted = 5)), trip.id, day))
        // The entry's own zone decides its day: 22:30 UTC on the 3rd is the 4th in Athens.
        val lateUtc = Instant.parse("2028-05-03T22:30:00Z").toEpochMilli()
        assertTrue(DailyCheckIn.writtenOn(listOf(entry("late", lateUtc)), trip.id, day))
        assertFalse(DailyCheckIn.writtenOn(listOf(entry("late", lateUtc, zone = "UTC")), trip.id, day))
    }

    @Test fun aCheckInNamesThePlaceAsANoteWrittenFromTheItem() {
        val distillery = item("Kitron distillery").copy(placeName = "Halki, Naxos, Greece")
        assertEquals(listOf("Kitron distillery (Naxos)"), DailyCheckIn.places(trip, listOf(distillery), day).map { it.name })
    }

    @Test fun futurePastUndatedAndDeletedTripsCannotPrompt() {
        val plan = listOf(item("Museum"))
        assertTrue(DailyCheckIn.places(trip.copy(startDate = day.plusDays(1)), plan, day).isEmpty())
        assertTrue(DailyCheckIn.places(trip.copy(endDate = day.minusDays(1)), plan, day).isEmpty())
        assertTrue(DailyCheckIn.places(trip.copy(startDate = null, endDate = null), plan, day).isEmpty())
        assertTrue(DailyCheckIn.places(trip.copy(deletedAt = 2), plan, day).isEmpty())
    }

    @Test fun onlyTodaysActivePlacesInUserOrderIncludingManualUnlocatedPlace() {
        val plan = listOf(item("Later", position = 3.0), item("First", position = 1.0),
            item("Unknown location", position = 2.0, lat = null, lng = null), item("Tomorrow", day.plusDays(1)),
            item("Idea", null), item("Note").copy(kind = "note"), item("Deleted").copy(deletedAt = 2),
            item("Foreign").copy(tripId = "another"), item("Done", position = 4.0).copy(done = true))
        val places = DailyCheckIn.places(trip, plan, day)
        assertEquals(listOf("First", "Unknown location", "Later", "Done"), places.map { it.name })
        assertNull(places[1].lat)
        assertTrue(plan.last().done) // Journal suggestions never modify completion flags.
    }

    @Test fun invalidPlanCoordinatesKeepManualPlaceButCannotMatchGps() {
        val places = DailyCheckIn.places(trip, listOf(item("Bad").copy(lat = Double.NaN), item("Outside").copy(lng = 181.0)), day)
        assertEquals(2, places.size)
        assertTrue(places.all { it.lat == null && it.lng == null })
        assertNull(DailyCheckIn.nearby(places, CheckInFix(now, 37.98, 23.72, 10f), now))
    }

    @Test fun nearestFreshAccurateFixOnlySuggestsTodaysClosestPlace() {
        val places = DailyCheckIn.places(trip, listOf(item("Far", lng = 23.8), item("Near", position = 2.0)), day)
        val fix = CheckInFix(now - 30_000, 37.9801, 23.7201, 12f)
        assertEquals("Near", DailyCheckIn.nearby(places, fix, now)?.name)
        assertEquals("Near", DailyCheckIn.suggested(prompt(places), fix, now)?.name)
    }

    @Test fun staleFutureMissingPoorAndInvalidFixesCannotClaimNearby() {
        val places = DailyCheckIn.places(trip, listOf(item("Museum")), day)
        val good = CheckInFix(now, 37.98, 23.72, 10f)
        listOf(null, good.copy(time = now - DailyCheckIn.FRESH_MS - 1), good.copy(time = now + 1),
            good.copy(accuracyM = null), good.copy(accuracyM = 101f), good.copy(accuracyM = Float.NaN),
            good.copy(accuracyM = -1f), good.copy(lat = Double.NaN), good.copy(lng = 181.0),
            good.copy(lat = 38.0)).forEach { assertNull(DailyCheckIn.nearby(places, it, now)) }
        assertNotNull(DailyCheckIn.nearby(places, good.copy(time = now - DailyCheckIn.FRESH_MS), now))
    }

    @Test fun reportedUncertaintyMustAlsoFitNearbyRadius() {
        val places = DailyCheckIn.places(trip, listOf(item("Museum")), day)
        val approximately250m = CheckInFix(now, 37.98225, 23.72, 100f)
        assertNull(DailyCheckIn.nearby(places, approximately250m, now))
        assertNotNull(DailyCheckIn.nearby(places, approximately250m.copy(accuracyM = 10f), now))
    }

    @Test fun declinedPermissionRetainsManualChoiceWithoutUsingGps() {
        val places = DailyCheckIn.places(trip, listOf(item("First", lng = 23.8), item("Nearby", position = 2.0)), day)
        assertEquals("First", DailyCheckIn.suggested(prompt(places), CheckInFix(now, 37.98, 23.72, 10f), now, locationAllowed = false)?.name)
        assertEquals("First", DailyCheckIn.suggested(prompt(places), null, now)?.name)
    }

    @Test fun confirmedDraftHasCorrectTripDateZoneAndDurablePlaceBlock() {
        val place = DailyCheckIn.places(trip, listOf(item("Museum")), day).single()
        val draft = DailyCheckIn.draft(null, prompt(listOf(place)), place, now, zone)
        assertEquals(trip.id, draft.tripId)
        assertEquals(day, DailyCheckIn.localDay(draft.occurredAt, ZoneId.of(draft.zoneId)))
        assertEquals("Museum", draft.placeName)
        assertEquals(Block.Place("Museum", 37.98, 23.72), EntryDoc.parse(draft.body).blocks.single())
        assertFalse(EntryDoc.parse(draft.body).isEmpty)
    }

    @Test fun confirmationRetryPreservesEditedDraftAndOriginalConfirmedPlace() {
        val places = DailyCheckIn.places(trip, listOf(item("Museum"), item("Cafe", position = 2.0)), day)
        val original = DailyCheckIn.draft(null, prompt(places), places[0], now, zone)
        val edited = original.copy(title = "My own story", body = EntryDoc.ofText("Keep my words").toJson(), updatedAt = now + 100)
        assertSame(edited, DailyCheckIn.draft(edited, prompt(places), places[1], now + 500, zone))
        assertEquals(original.id, DailyCheckIn.entryId(trip.id, day))
        assertNotEquals(original.id, DailyCheckIn.entryId(trip.id, day.plusDays(1)))
    }

    @Test fun inASharedTripEachMemberHasTheirOwnCheckIn() {
        val place = DailyCheckIn.places(trip, listOf(item("Museum")), day).single()
        val ana = DailyCheckIn.draft(null, prompt(listOf(place)), place, now, zone, member = "ana")
        val bo = DailyCheckIn.draft(null, prompt(listOf(place)), place, now, zone, member = "bo")
        // Two phones writing offline make two entries, not two versions of one.
        assertNotEquals(ana.id, bo.id)
        assertEquals(ana.id, DailyCheckIn.entryId(trip.id, day, "ana"))
        assertNotEquals(DailyCheckIn.entryId(trip.id, day), ana.id)
    }

    @Test fun aFriendsEntryAtTheCheckInIdIsNeverOpenedForEditing() {
        val place = DailyCheckIn.places(trip, listOf(item("Museum")), day).single()
        // An entry at this phone's check-in id but written by another member (the same id from an older app).
        val theirs = DailyCheckIn.draft(null, prompt(listOf(place)), place, now, zone).copy(authorId = "ana", title = "Ana's day")
        val mine = DailyCheckIn.draft(theirs, prompt(listOf(place)), place, now + 10, zone, newId = { "fresh-note" })
        assertEquals("fresh-note", mine.id)
        assertNull(mine.authorId)
        assertEquals("", mine.title)
    }

    @Test fun aFriendsEntryTodayStillLeavesTheCheckInForTheOwner() {
        val friends = com.justdataplease.boringtravel.data.db.Entry("f", trip.id, now, "Europe/Athens", createdAt = 1, updatedAt = 1, authorId = "ana")
        assertFalse(DailyCheckIn.writtenOn(listOf(friends), trip.id, day))
        assertTrue(DailyCheckIn.writtenOn(listOf(friends, friends.copy(id = "mine", authorId = null)), trip.id, day))
    }

    @Test fun removedDraftIsNeverResurrectedAndYesterdayPromptCannotSave() {
        val place = DailyCheckIn.places(trip, listOf(item("Museum")), day).single()
        val original = DailyCheckIn.draft(null, prompt(listOf(place)), place, now, zone)
        assertThrows(IllegalArgumentException::class.java) {
            DailyCheckIn.draft(original.copy(deletedAt = now + 1), prompt(listOf(place)), place, now + 2, zone)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DailyCheckIn.draft(null, prompt(listOf(place)), place, now + 86_400_000, zone)
        }
    }
}
