package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.SampleArgentina
import com.justdataplease.boringtravel.data.SampleLisbon
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/**
 * The default video stays calm (owner, 2026-11-08, on the Argentina video: "it goes too fast and does many zoom in
 * zoom out, it's tiring and confusing"): few heights, held for seconds, and the zoom seldom turns back.
 */
class CalmCameraTest {
    private val argentina: ClipInput by lazy {
        val plan = SampleArgentina.plan(LocalDate.of(2026, 10, 25), 0L)
        ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), SampleArgentina.ZONE).getValue(ClipSource.Planned)
    }
    private val lisbon: ClipInput by lazy {
        val plan = SampleLisbon.plan(LocalDate.of(2026, 6, 1), 0L)
        ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), SampleLisbon.ZONE).getValue(ClipSource.Planned)
    }

    /** How often the zoom turns from in to out or back, sampled every 0.2 s (moves under 2% ignored). */
    private fun reversals(p: ClipPlan): Int {
        var last = Double.NaN
        var dir = 0
        var turns = 0
        for (f in 0 until p.frameCount step 6) {
            val l = ln(p.frame(f).camera.spanX)
            if (!last.isNaN() && abs(l - last) > 0.02) {
                val d = if (l > last) 1 else -1
                if (dir != 0 && d != dir) turns++
                dir = d
            }
            last = l
        }
        return turns
    }

    @Test fun aMonthLongTripHoldsItsHeightsAndTakesAMinute() {
        val p = requireNotNull(ClipPlan.build(argentina, ClipOptions(map = MapStyle.Globe), Locale.ENGLISH))
        assertTrue("about a minute: ${p.seconds}", p.seconds in 55.0..60.5)
        assertTrue("few heights: ${p.cameraRuns.size}", p.cameraRuns.size <= 5)
        assertTrue("each held: ${p.cameraRuns.map { it.frames }}", p.cameraRuns.all { it.frames >= 2 * p.fps })
        // Down into Patagonia and up again: the zoom turns back about once (it turned nine times in 45 s before).
        assertTrue("seldom turns back: ${reversals(p)}", reversals(p) <= 2)
    }

    @Test fun aCityTripChangesHeightOnlyWithTheWayItTravels() {
        val p = requireNotNull(ClipPlan.build(lisbon, ClipOptions(map = MapStyle.Globe), Locale.ENGLISH))
        assertTrue("a nine-day trip runs about half a minute: ${p.seconds}", p.seconds in 28.0..36.0)
        // City on foot, the train, Porto on foot, the Douro by car: a height each, every one held for seconds.
        assertTrue("few heights: ${p.cameraRuns.size}", p.cameraRuns.size <= 5)
        assertTrue("each held: ${p.cameraRuns.map { it.frames }}", p.cameraRuns.all { it.frames >= 4 * p.fps })
        assertTrue("seldom turns back: ${reversals(p)}", reversals(p) <= 3)
    }


}
