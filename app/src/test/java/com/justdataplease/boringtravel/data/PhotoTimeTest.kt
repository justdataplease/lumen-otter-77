package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.data.repo.PhotoTime
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoTimeTest {
    @Test fun captureOffsetSurvivesTravelAcrossTimeZones() {
        assertEquals(Instant.parse("2026-07-12T09:30:00Z").toEpochMilli(), PhotoTime.parse("2026:07:12 12:30:00", "+03:00", ZoneId.of("America/New_York")))
    }
    @Test fun missingOffsetUsesExplicitFallbackZone() {
        assertEquals(Instant.parse("2026-11-06T10:30:00Z").toEpochMilli(), PhotoTime.parse("2026:11:06 12:30:00", null, ZoneId.of("Europe/Athens")))
    }
    @Test fun invalidDatesAndMissingTagsStayUnknown() {
        assertNull(PhotoTime.parse("2026:02:31 12:30:00", "+03:00"))
        assertNull(PhotoTime.parse("0000:00:00 00:00:00", null))
        assertNull(PhotoTime.parse(null, null))
    }

    @Test fun aShotKeepsItsExifTimeWhileTheCameraWasOpen() {
        val opened = 1_000_000_000L
        val back = opened + 40_000L
        assertEquals(opened + 25_000L, PhotoTime.forShot(opened + 25_000L, opened, back))
        // A slow save just after the camera came back still counts.
        assertEquals(back + 60_000L, PhotoTime.forShot(back + 60_000L, opened, back))
    }
    @Test fun aWrongCameraClockFallsBackToWhenItCameBack() {
        val opened = 1_000_000_000L
        val back = opened + 40_000L
        assertEquals(back, PhotoTime.forShot(opened - 3 * 3_600_000L, opened, back))
        assertEquals(back, PhotoTime.forShot(back + 2 * 3_600_000L, opened, back))
        assertEquals(back, PhotoTime.forShot(null, opened, back))
    }
}
