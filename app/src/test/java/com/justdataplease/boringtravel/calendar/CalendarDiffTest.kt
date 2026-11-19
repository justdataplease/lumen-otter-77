package com.justdataplease.boringtravel.calendar

import com.justdataplease.boringtravel.calendar.Fx.athens
import com.justdataplease.boringtravel.calendar.Fx.item
import com.justdataplease.boringtravel.calendar.Fx.trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarDiffTest {
    private val pkg = "com.justdataplease.boringtravel"
    private val cal = 7L

    private fun desired(vararg items: com.justdataplease.boringtravel.data.db.PlanItem) =
        TripEvents.build(trip(), items.toList()).map { it to TripEvents.fields(it, athens) }

    /** An event as the phone would hold it after we wrote [key] for trip [tripId]. */
    private fun ours(id: Long, key: String, fields: EventFields, calendar: Long = cal, tripId: String = "T1") =
        PhoneEvent(id, calendar, fields, pkg, CalendarMarker.uri(tripId, key))

    private fun plan(want: List<Pair<TripEvent, EventFields>>, have: List<PhoneEvent>, remembered: Map<String, Remembered> = emptyMap()) =
        CalendarDiff.plan("T1", pkg, cal, want, have, remembered)

    @Test fun markersRoundTrip() {
        assertEquals("T1" to "item/abc", CalendarMarker.parse(CalendarMarker.uri("T1", "item/abc")))
        assertEquals("T1" to "trip", CalendarMarker.parse("boringtravel://trip/T1/trip"))
        assertNull(CalendarMarker.parse("boringtravel://trip/T1/"))
        assertNull(CalendarMarker.parse("boringtravel://trip//x"))
        assertNull(CalendarMarker.parse("https://example.com/trip/T1/x"))
        assertNull(CalendarMarker.parse(null))
    }

    @Test fun theFirstAddInsertsEverything() {
        val want = desired(item("a", minutes = 600), item("b"))
        val p = plan(want, emptyList())
        assertEquals(listOf("trip", "item/b", "item/a"), p.inserts.map { it.key })
        assertTrue(p.updates.isEmpty() && p.deletes.isEmpty() && p.kept.isEmpty())
    }

    @Test fun anUpdateChangesOnlyWhatChanged() {
        val before = desired(item("a", minutes = 600), item("b"), item("c"))
        val have = before.mapIndexed { i, (e, f) -> ours(100L + i, e.key, f) }
        assertTrue(plan(before, have).isEmpty)

        // a moved to 10:30, c removed, d added.
        val after = desired(item("a", minutes = 630), item("b"), item("d", minutes = 900))
        val p = plan(after, have)
        assertEquals(listOf("item/d"), p.inserts.map { it.key })
        assertEquals(listOf(have.first { it.appUri!!.endsWith("item/a") }.id to "item/a"), p.updates.map { it.first to it.second.key })
        assertEquals(listOf(have.first { it.appUri!!.endsWith("item/c") }.id), p.deletes)
        assertEquals(setOf("trip", "item/a", "item/b"), p.kept.keys)
    }

    @Test fun nobodyElsesEventsAreTouched() {
        val want = desired(item("a", minutes = 600))
        val f = want[1].second
        val have = listOf(
            PhoneEvent(1, cal, f, "com.other.app", "boringtravel://trip/T1/item/a"), // another app's
            ours(2, "item/a", f, tripId = "T2"), // another trip's
            PhoneEvent(3, cal, f.copy(title = "Dentist")), // the owner's own
            ours(4, "item/zzz", f, tripId = "T2"),
        )
        val p = plan(want, have)
        assertTrue(p.deletes.isEmpty())
        assertTrue(p.updates.isEmpty())
        assertEquals(listOf("trip", "item/a"), p.inserts.map { it.key })
        assertTrue(CalendarDiff.removal("T1", pkg, cal, want, have, emptyMap()).isEmpty())
    }

    @Test fun copiesOfOneEventCollapseToOne() {
        val want = desired(item("a", minutes = 600))
        val f = want[1].second
        val have = listOf(ours(10, "item/a", f.copy(title = "old")), ours(11, "item/a", f), ours(12, "item/a", f))
        val p = plan(want, have)
        assertEquals(11L, p.kept["item/a"])
        assertEquals(listOf(10L, 12L), p.deletes)
        assertTrue(p.updates.none { it.second.key == "item/a" })
    }

    @Test fun rememberedIdsFindEventsWhoseMarkerWasDropped() {
        val want = desired(item("a", minutes = 600, title = "Ferry"))
        val f = want[1].second
        val stripped = PhoneEvent(50, cal, f)
        val p = plan(want, listOf(stripped), mapOf("item/a" to Remembered(50, "Ferry", f.signature())))
        assertEquals(listOf(50L to "item/a"), p.updates.map { it.first to it.second.key })
        assertEquals(listOf("trip"), p.inserts.map { it.key })

        // A remembered id now holding something else (other title, other calendar, someone's marker) is not ours.
        for (other in listOf(stripped.copy(fields = f.copy(title = "Dentist")), stripped.copy(fields = f.copy(location = "Changed elsewhere")),
            stripped.copy(calendarId = 8), stripped.copy(appPackage = "x.y", appUri = "x://1"))) {
            val q = plan(want, listOf(other), mapOf("item/a" to Remembered(50, "Ferry", f.signature())))
            assertTrue(q.updates.isEmpty() && q.deletes.isEmpty())
            assertEquals(listOf("trip", "item/a"), q.inserts.map { it.key })
        }
    }

    @Test fun identicalUnmarkedEventsAreNeverClaimedOrRemoved() {
        val want = desired(item("a", minutes = 600, note = "Blue Star"))
        val twinTrip = PhoneEvent(60, cal, want[0].second)
        val twinItem = PhoneEvent(61, cal, want[1].second)
        val p = plan(want, listOf(twinTrip, twinItem))
        assertEquals(listOf("trip", "item/a"), p.inserts.map { it.key })
        assertTrue(p.updates.isEmpty() && p.deletes.isEmpty())
        assertTrue(CalendarDiff.removal("T1", pkg, cal, want, listOf(twinTrip, twinItem), emptyMap()).isEmpty())
        // Not quite the same (the owner's own note), or in another calendar: left alone, ours is added.
        val near = PhoneEvent(62, cal, want[1].second.copy(description = "Booking ref 123"))
        val elsewhere = PhoneEvent(63, 8, want[1].second)
        val q = plan(want, listOf(near, elsewhere))
        assertEquals(listOf("trip", "item/a"), q.inserts.map { it.key })
        assertTrue(q.updates.isEmpty() && q.deletes.isEmpty())
    }

    @Test fun ourEventInAnotherCalendarMovesToTheLinkedOne() {
        val want = desired(item("a", minutes = 600))
        val have = listOf(ours(70, "trip", want[0].second), ours(71, "item/a", want[1].second, calendar = 99))
        val p = plan(want, have)
        assertEquals(listOf(71L), p.deletes)
        assertEquals(listOf("item/a"), p.inserts.map { it.key })
        assertEquals(mapOf("trip" to 70L), p.kept)
    }

    @Test fun removalTakesEveryEventOfTheTripAndNothingElse() {
        val want = desired(item("a", minutes = 600), item("b"))
        val have = listOf(
            ours(1, "trip", want[0].second),
            ours(2, "item/old", want[1].second), // an item deleted since
            PhoneEvent(3, cal, want[2].second), // exact twin without marker
            PhoneEvent(4, cal, want[2].second.copy(title = "Dentist")),
            ours(5, "trip", want[0].second, tripId = "T2"),
            PhoneEvent(6, cal, want[1].second.copy(title = "Ferry")),
        )
        val ids = CalendarDiff.removal("T1", pkg, cal, want, have, mapOf("item/b" to Remembered(6, "Ferry", have.last().fields.signature())))
        assertEquals(setOf(1L, 2L, 6L), ids.toSet())
    }

    @Test fun titleOnlyLegacyMemoryCannotClaimAReusedEventId() {
        val want = desired(item("a", minutes = 600))
        val unrelated = PhoneEvent(50, cal, want[1].second)
        val remembered = mapOf("item/a" to Remembered(50, unrelated.fields.title))
        assertTrue(plan(want, listOf(unrelated), remembered).updates.isEmpty())
        assertTrue(CalendarDiff.removal("T1", pkg, cal, want, listOf(unrelated), remembered).isEmpty())
    }

    @Test fun partialWriteRetryFindsItsSourceLinksWithoutDuplicatingOrTouchingTwins() {
        val want = desired(item("a", minutes = 600), item("b", minutes = 720))
        val unrelated = PhoneEvent(900, cal, want.last().second)
        // Simulate a process stopping after the first two inserts, before any local event IDs were saved.
        val partial = want.take(2).mapIndexed { i, (event, fields) ->
            val description = CalendarMarker.description(fields.description, pkg, "T1", event.key)
            val owner = requireNotNull(CalendarMarker.fromDescription(description, pkg))
            PhoneEvent(100L + i, cal, fields.copy(description = CalendarMarker.cleanDescription(description, pkg)), pkg, CalendarMarker.uri(owner.first, owner.second))
        }
        val retry = plan(want, partial + unrelated)
        assertEquals(listOf(want.last().first.key), retry.inserts.map { it.key })
        assertTrue(retry.updates.isEmpty() && retry.deletes.isEmpty())
        val complete = partial + ours(102, want.last().first.key, want.last().second) + unrelated
        assertTrue(plan(want, complete).isEmpty)
        assertEquals(setOf(100L, 101L, 102L), CalendarDiff.removal("T1", pkg, cal, want, complete, emptyMap()).toSet())
    }

    @Test fun descriptionOwnershipIsPackageScopedAndUserNotesSurvive() {
        val note = "Booking reference: example\nKeep this text."
        val marked = CalendarMarker.description(note, pkg, "T1", "item/a")
        assertEquals("T1" to "item/a", CalendarMarker.fromDescription(marked, pkg))
        assertNull(CalendarMarker.fromDescription(marked, "com.other.app"))
        assertEquals(note, CalendarMarker.cleanDescription(marked, pkg))
        assertEquals(marked, CalendarMarker.description(marked, pkg, "T1", "item/a"))
        assertEquals("$note\n\nLater note", CalendarMarker.cleanDescription("$marked\nLater note", pkg))
    }
}
