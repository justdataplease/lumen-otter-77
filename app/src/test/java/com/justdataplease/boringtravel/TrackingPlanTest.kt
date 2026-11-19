package com.justdataplease.boringtravel

import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.geo.Geo
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.tracking.RecordMode
import com.justdataplease.boringtravel.tracking.Recording
import com.justdataplease.boringtravel.tracking.Stillness
import com.justdataplease.boringtravel.tracking.TrackingPermissions.Step
import com.justdataplease.boringtravel.tracking.TrackingPlan
import com.justdataplease.boringtravel.tracking.TrackingTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TrackingPlanTest {
    private val today = LocalDate.of(2026, 6, 18)
    private fun trip(start: LocalDate?, end: LocalDate?, level: String? = null, deleted: Boolean = false) =
        Trip("c", "Cyclades, slowly", startDate = start, endDate = end, trackingLevel = level, createdAt = 0, updatedAt = 0, deletedAt = if (deleted) 1 else null)

    @Test fun recordsOnlyDuringATrip() {
        val on = trip(today.minusDays(3), today.plusDays(6))
        assertEquals(TrackingTarget("c", TrackingLevel.Balanced, today.plusDays(6)), TrackingPlan.target(on, TrackingLevel.Balanced, today))
        assertEquals(TrackingTarget.Off, TrackingPlan.target(null, TrackingLevel.Balanced, today))
        assertEquals(TrackingTarget.Off, TrackingPlan.target(trip(today.minusDays(9), today.minusDays(1)), TrackingLevel.Balanced, today))
        assertEquals(TrackingTarget.Off, TrackingPlan.target(trip(today.plusDays(1), today.plusDays(5)), TrackingLevel.Balanced, today))
        assertEquals(TrackingTarget.Off, TrackingPlan.target(trip(null, null), TrackingLevel.Balanced, today))
        assertEquals(TrackingTarget.Off, TrackingPlan.target(trip(today, today, deleted = true), TrackingLevel.Balanced, today))
        // First and last day count.
        assertEquals(TrackingLevel.Low, TrackingPlan.target(trip(today, today), TrackingLevel.Low, today).level)
    }

    @Test fun aTripsOwnLevelWins() {
        val on = trip(today, today.plusDays(2), level = "detailed")
        assertEquals(TrackingLevel.Detailed, TrackingPlan.target(on, TrackingLevel.Low, today).level)
        assertEquals(TrackingTarget.Off, TrackingPlan.target(on.copy(trackingLevel = "off"), TrackingLevel.Detailed, today))
        assertEquals(TrackingTarget.Off, TrackingPlan.target(on.copy(trackingLevel = null), TrackingLevel.Off, today))
    }

    @Test fun dayCheckRunsJustAfterMidnight() {
        val zone = ZoneId.of("Europe/Athens")
        assertEquals(65 * 60_000L, TrackingPlan.msUntilNextDay(ZonedDateTime.of(2026, 6, 18, 23, 0, 0, 0, zone)))
        assertEquals((24 * 60 + 3) * 60_000L, TrackingPlan.msUntilNextDay(ZonedDateTime.of(2026, 6, 18, 0, 2, 0, 0, zone)))
    }

    @Test fun bestFixBacksOffWhileStill() {
        assertEquals(20 * 60_000L, TrackingPlan.bestFixDelayMs(0))
        assertEquals(40 * 60_000L, TrackingPlan.bestFixDelayMs(1))
        assertEquals(60 * 60_000L, TrackingPlan.bestFixDelayMs(2))
        assertEquals(60 * 60_000L, TrackingPlan.bestFixDelayMs(9))
        assertEquals(20 * 60_000L, TrackingPlan.bestFixDelayMs(-1))
    }

    @Test fun stillnessWaitsTenMinutesAndWakesOnMovement() {
        val s = Stillness()
        val t = 1_780_000_000_000L
        s.reset(t)
        assertTrue(s.onFix(37.1, 25.37, t, 10f))
        // Jitter within 30 m is not movement.
        assertFalse(s.onFix(37.1 + 30 / Geo.METERS_PER_DEGREE, 25.37, t + 60_000, 10f))
        assertFalse(s.isStill(t + 9 * 60_000))
        assertTrue(s.isStill(t + 10 * 60_000))
        // A poor fix 100 m off with 80 m accuracy is not movement either; 200 m is.
        assertFalse(s.onFix(37.1 + 100 / Geo.METERS_PER_DEGREE, 25.37, t + 11 * 60_000, 80f))
        assertTrue(s.onFix(37.1 + 200 / Geo.METERS_PER_DEGREE, 25.37, t + 12 * 60_000, 10f))
        assertFalse(s.isStill(t + 13 * 60_000))
    }

    // ---- Levels and the recording service (first Play version: no background location) ----

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test fun backgroundLocationIsRemovedExactlyWhileTheSwitchIsOff() {
        val removed = Regex("""<uses-permission\s+android:name="android\.permission\.ACCESS_BACKGROUND_LOCATION"\s+tools:node="remove"\s*/>""")
        val declared = Regex("""<uses-permission\s+android:name="android\.permission\.ACCESS_BACKGROUND_LOCATION"\s*/>""")
        assertEquals("ACCESS_BACKGROUND_LOCATION removed exactly while background recording is off", !TrackingPlan.BACKGROUND_RECORDING, removed.containsMatchIn(manifest))
        assertEquals(TrackingPlan.BACKGROUND_RECORDING, declared.containsMatchIn(manifest))
        // The recording service is a location foreground service.
        assertTrue(Regex("""\.tracking\.RouteRecordingService"\s+android:exported="false"\s+android:foregroundServiceType="location"""").containsMatchIn(manifest))
    }

    @Test fun withoutBackgroundRecordingEveryLevelRecordsThroughTheService() {
        assertEquals(RecordMode.None, TrackingPlan.mode(TrackingLevel.Off, background = false))
        listOf(TrackingLevel.Low, TrackingLevel.Balanced, TrackingLevel.Detailed).forEach {
            assertEquals(it.name, RecordMode.Service, TrackingPlan.mode(it, background = false))
            assertTrue(it.name, TrackingPlan.showsNotification(it, background = false))
        }
        assertFalse(TrackingPlan.showsNotification(TrackingLevel.Off, background = false))
        // The switch on brings back Low and Balanced through the receiver; Detailed keeps its service.
        assertEquals(RecordMode.Receiver, TrackingPlan.mode(TrackingLevel.Low, background = true))
        assertEquals(RecordMode.Receiver, TrackingPlan.mode(TrackingLevel.Balanced, background = true))
        assertEquals(RecordMode.Service, TrackingPlan.mode(TrackingLevel.Detailed, background = true))
        assertFalse(TrackingPlan.showsNotification(TrackingLevel.Balanced, background = true))
        // The first Play version ships with it off.
        assertFalse(TrackingPlan.BACKGROUND_RECORDING)
    }

    private val on = TrackingTarget("c", TrackingLevel.Balanced, today.plusDays(3))

    private fun decide(
        target: TrackingTarget = on, location: Boolean = true, stopped: Boolean = false, onScreen: Boolean = false,
        running: Boolean = false, hasBackground: Boolean = false, background: Boolean = false,
    ) = TrackingPlan.decide(target, location, stopped, onScreen, running, hasBackground, background)

    @Test fun theServiceStartsOnlyWhileTheAppIsOnScreen() {
        // Opened during a trip: start. Closed (boot, alarm, the nightly job): wait for the app, never start.
        assertEquals(Recording.StartService, decide(onScreen = true))
        assertEquals(Recording.WaitForApp, decide(onScreen = false))
        // "Allow all the time" changes nothing while the switch is off.
        assertEquals(Recording.WaitForApp, decide(onScreen = false, hasBackground = true))
        // Already recording: it goes on with the app closed, at the new level or trip.
        assertEquals(Recording.KeepService, decide(running = true))
        assertEquals(Recording.KeepService, decide(running = true, onScreen = true))
        listOf(TrackingLevel.Low, TrackingLevel.Detailed).forEach { level ->
            assertEquals(Recording.StartService, decide(target = on.copy(level = level), onScreen = true))
            assertEquals(Recording.WaitForApp, decide(target = on.copy(level = level)))
        }
    }

    @Test fun offNoPermissionAndStopWinOverEverything() {
        assertEquals(Recording.Off, decide(target = TrackingTarget.Off, onScreen = true, running = true))
        assertEquals(Recording.Off, decide(target = TrackingTarget(null, TrackingLevel.Detailed, null), onScreen = true))
        assertEquals(Recording.NoPermission, decide(location = false, onScreen = true))
        assertEquals(Recording.NoPermission, decide(location = false, running = true))
        assertEquals(Recording.Stopped, decide(stopped = true, onScreen = true))
        assertEquals(Recording.Stopped, decide(stopped = true, running = true))
    }

    @Test fun withBackgroundRecordingTheOldWaysComeBack() {
        assertEquals(Recording.Receiver, decide(background = true))
        assertEquals(Recording.Receiver, decide(background = true, onScreen = true))
        val detailed = on.copy(level = TrackingLevel.Detailed)
        assertEquals(Recording.StartService, decide(target = detailed, background = true, onScreen = true))
        assertEquals(Recording.StartService, decide(target = detailed, background = true, hasBackground = true))
        // Detailed closed without "Allow all the time": the receiver until the app is open again.
        assertEquals(Recording.Receiver, decide(target = detailed, background = true))
    }

    @Test fun aStopLastsForTheRestOfTheDay() {
        val day = today.toEpochDay()
        assertTrue(TrackingPlan.stoppedToday("c", day, "c", today))
        assertFalse(TrackingPlan.stoppedToday("c", day, "c", today.plusDays(1)))
        assertFalse(TrackingPlan.stoppedToday("c", day, "other", today))
        assertFalse(TrackingPlan.stoppedToday("c", day, null, today))
        assertFalse(TrackingPlan.stoppedToday(null, null, "c", today))
    }

    @Test fun permissionStepsFollowTheSwitch() {
        fun steps(level: TrackingLevel, precise: Boolean = false, location: Boolean = false, bg: Boolean = false, notes: Boolean = false, background: Boolean = false) =
            TrackingPlan.missing(level, precise, location, bg, notes, background)
        // Off: nothing. Every recording level: precise location, then notifications; never "Allow all the time".
        assertEquals(emptyList<Step>(), steps(TrackingLevel.Off))
        listOf(TrackingLevel.Low, TrackingLevel.Balanced, TrackingLevel.Detailed).forEach { level ->
            assertEquals(level.name, listOf(Step.Precise, Step.Notifications), steps(level))
            assertEquals(level.name, listOf(Step.Notifications), steps(level, precise = true, location = true))
            assertEquals(level.name, emptyList<Step>(), steps(level, precise = true, location = true, notes = true))
        }
        // The switch on: Low and Balanced ask for "Allow all the time" once some location is allowed, no notifications.
        assertEquals(listOf(Step.Precise), steps(TrackingLevel.Balanced, background = true))
        assertEquals(listOf(Step.Background), steps(TrackingLevel.Balanced, precise = true, location = true, background = true))
        assertEquals(emptyList<Step>(), steps(TrackingLevel.Low, precise = true, location = true, bg = true, background = true))
        assertEquals(listOf(Step.Notifications), steps(TrackingLevel.Detailed, precise = true, location = true, background = true))
    }

    @Test fun noLevelSaysItRecordsWithoutANotification() {
        TrackingLevel.entries.forEach {
            assertFalse(it.blurb, it.blurb.contains("No notification", ignoreCase = true))
            assertFalse(it.blurb, it.blurb.contains("closed", ignoreCase = true))
        }
    }
}
