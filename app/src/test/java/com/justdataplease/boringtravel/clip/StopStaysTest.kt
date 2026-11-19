package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Time at a stop (owner, 2026-11-12: "a single event in a day last too little"): Auto from From–To, or by hand. */
class StopStaysTest {
    @Test fun autoGrowsWithHowLongTheItemLasts() {
        assertEquals(0.0, StopStays.auto(null), 0.0)
        assertEquals(1.0, StopStays.auto(1.0), 0.0)
        assertEquals(1.5, StopStays.auto(3.0), 0.0)
        assertEquals(2.0, StopStays.auto(24.0), 0.0)
        assertEquals(2.5, StopStays.auto(63.0), 0.0)
        assertEquals(3.0, StopStays.auto(24.0 * 30), 0.0)
    }

    @Test fun ownTimesWinAndAutoKeepsWithinItsShare() {
        val stops = mapOf("a" to listOf("x"), "b" to listOf("y", "z"), "c" to listOf("w"), "d" to listOf("v"))
        val hours = mapOf("x" to 3.0, "y" to 1.0, "z" to 63.0, "w" to null, "v" to 24.0)
        val plan = StopStays.plan(stops, hours, own = mapOf("c" to 4.0, "d" to 0.0), clipS = 60.0)
        // a: 3 h; b: its longest item, three nights; c: set by hand; d: set to none.
        assertEquals(mapOf("a" to 1.5, "b" to 2.5, "c" to 4.0), plan)
        // A short clip shortens Auto's alike (a quarter of 8 s = 2 s for 4 s asked), never the one set by hand.
        val short = StopStays.plan(stops, hours, own = mapOf("c" to 4.0), clipS = 8.0)
        assertEquals(4.0, short.getValue("c"), 1e-9)
        assertEquals(2.0, short.filterKeys { it != "c" }.values.sum(), 1e-9)
    }

    @Test fun theJourneyStandsStillAtAStopForItsTime() {
        val day = LocalDate.of(2035, 7, 3)
        val trip = Trip("t", "Spain", startDate = day, endDate = day.plusDays(3), createdAt = 1, updatedAt = 1)
        fun at(id: String, i: Int, d: LocalDate = day) = PlanItem(id, "t", d, i.toDouble(), title = "Place $id", lat = 40.0 + i * 0.3, lng = -3.7 + i * 0.3, createdAt = 1, updatedAt = 1)
        val items = listOf(
            at("a", 0), at("b", 1).copy(timeMinutes = 19 * 60, endMinutes = 22 * 60), at("c", 2, day.plusDays(1)), at("d", 3, day.plusDays(2)),
        )
        val input = requireNotNull(ClipSources.available(trip, emptyList(), emptyList(), items, emptyList(), ZoneId.of("Europe/Madrid"))[ClipSource.Planned])
        val pace = ClipOptions(length = null, speed = 1, photos = false)
        val auto = requireNotNull(ClipPlan.build(input, pace, Locale.ENGLISH))
        val key = auto.labels.first { "item:b" in it.key }.key
        assertEquals("the 3-hour concert gets Auto's time", 1.5, auto.stays.getValue(key), 0.0)
        assertEquals(1, auto.stays.size)
        val none = requireNotNull(ClipPlan.build(input, pace.copy(stays = mapOf(key to 0.0)), Locale.ENGLISH))
        val long = requireNotNull(ClipPlan.build(input, pace.copy(stays = mapOf(key to 6.0)), Locale.ENGLISH))
        assertTrue(none.stays.isEmpty())
        assertEquals(6.0, long.staySeconds, 0.0)
        // At a pace, a stop's time adds to the clip.
        assertEquals(6.0 * 30, (long.frameCount - none.frameCount).toDouble(), 3.0)
        // A chosen length keeps its length.
        val fixed = ClipOptions(length = ClipLength.S30, photos = false)
        val a = requireNotNull(ClipPlan.build(input, fixed, Locale.ENGLISH))
        val b = requireNotNull(ClipPlan.build(input, fixed.copy(stays = mapOf(key to 6.0)), Locale.ENGLISH))
        assertEquals(a.frameCount.toDouble(), b.frameCount.toDouble(), 2.0)
    }

    @Test fun timeHereIsKeptWithTheTripsEdits() {
        val edits = ClipEdits().change("label:item:b") { it.copy(stay = 3.5) }
        val back = ClipEditsJson.decode(ClipEditsJson.encode(mapOf("planned" to edits))).getValue("planned")
        assertEquals(3.5, back.scene("label:item:b").stay!!, 0.0)
        assertEquals(mapOf("label:item:b" to 3.5), ClipDesign(overrides = back.scenes).stays())
        // Out of range is ignored.
        val bad = ClipEditsJson.decode("""{"v":1,"sources":{"planned":{"scenes":{"label:item:b":{"stay":99,"text":"x"}}}}}""").getValue("planned")
        assertEquals(null, bad.scene("label:item:b").stay)
    }
}
