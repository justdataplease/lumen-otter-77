package com.justdataplease.boringtravel.data.repo

import com.justdataplease.boringtravel.data.db.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class MediaTypesTest {
    @Test fun extensionsFollowTheType() {
        assertEquals("jpg", MediaTypes.extension("image/jpeg"))
        assertEquals("png", MediaTypes.extension("image/png"))
        assertEquals("webp", MediaTypes.extension("image/webp"))
        assertEquals("heic", MediaTypes.extension("image/heif"))
        assertEquals("mp4", MediaTypes.extension("video/mp4"))
        assertEquals("mp4", MediaTypes.extension("VIDEO/MP4"))
        assertEquals("3gp", MediaTypes.extension("video/3gpp"))
        assertEquals("webm", MediaTypes.extension("video/webm"))
        assertEquals("mov", MediaTypes.extension("video/quicktime"))
        assertEquals("mkv", MediaTypes.extension("video/x-matroska"))
        // A video type nobody knows is still a video; anything else was always a .jpg.
        assertEquals("mp4", MediaTypes.extension("video/avi"))
        assertEquals("jpg", MediaTypes.extension("application/pdf"))
        assertEquals("jpg", MediaTypes.extension(null))
    }

    @Test fun aVideoIsToldApartByItsType() {
        assertTrue(MediaTypes.isVideo("video/mp4"))
        assertFalse(MediaTypes.isVideo("image/jpeg"))
        assertFalse(MediaTypes.isVideo(null))
        assertTrue(Media("v", localPath = "media/v.mp4", mime = "video/mp4", createdAt = 1, updatedAt = 1).isVideo)
        assertFalse(Media("p", localPath = "media/p.jpg", createdAt = 1, updatedAt = 1).isVideo)
    }

    @Test fun anotherMembersTypeIsOneThisPhoneKnows() {
        assertEquals("video/mp4", MediaTypes.shared("video/mp4"))
        assertEquals("video/webm", MediaTypes.shared(" video/webm "))
        assertEquals("video/mp4", MediaTypes.shared("video/../../etc"))
        assertEquals("image/jpeg", MediaTypes.shared("image/png"))
        assertEquals("image/jpeg", MediaTypes.shared("text/html"))
        assertEquals("image/jpeg", MediaTypes.shared(null))
    }

    @Test fun theWholeVideoIsKeptUpToHalfAnHour() {
        val minute = 60_000L
        assertEquals(30 * minute, VideoLimits.MAX_MS)
        assertFalse(VideoLimits.tooLong(null))
        assertFalse(VideoLimits.tooLong(75_000))
        assertFalse(VideoLimits.tooLong(30 * minute))
        // A container that rounds up a little is still within the limit.
        assertFalse(VideoLimits.tooLong(30 * minute + 400))
        assertTrue(VideoLimits.tooLong(31 * minute))
    }

    @Test fun longVideosGetLessBitrateAndStayUnderTheBackupLimit() {
        val minute = 60_000L
        assertEquals(3_000_000, VideoLimits.bitrate(null))
        assertEquals(3_000_000, VideoLimits.bitrate(12_000))
        assertEquals(3_000_000, VideoLimits.bitrate(10 * minute))
        // Past ten minutes the rate goes down with the length, never under 1 Mbit/s.
        assertTrue(VideoLimits.bitrate(11 * minute) < 3_000_000)
        assertTrue(VideoLimits.bitrate(20 * minute) < VideoLimits.bitrate(11 * minute))
        assertEquals(1_000_000, VideoLimits.bitrate(30 * minute))
        assertEquals(1_000_000, VideoLimits.bitrate(60 * minute))
        // A minute is about 23 MB; nothing up to the limit passes what a backup file holds, and long ones stay near 240 MB.
        val mb = 1024L * 1024
        assertTrue(VideoLimits.estimatedBytes(minute) in 20 * mb..25 * mb)
        var t = minute
        while (t <= VideoLimits.MAX_MS) {
            assertTrue("$t ms", VideoLimits.estimatedBytes(t) < VideoLimits.HARD_MAX_BYTES)
            if (t in 11 * minute..28 * minute) assertTrue("$t ms", VideoLimits.estimatedBytes(t) <= VideoLimits.MAX_BYTES)
            t += minute
        }
        assertEquals(com.justdataplease.boringtravel.backup.BackupArchive.MAX_PHOTO_BYTES, VideoLimits.HARD_MAX_BYTES)
        assertTrue(com.justdataplease.boringtravel.backup.BackupArchive.limit("media/v1.mp4") >= VideoLimits.HARD_MAX_BYTES)
        assertTrue(VideoLimits.MAX_BYTES < VideoLimits.HARD_MAX_BYTES)
    }

    @Test fun onlyAVideoThatFitsIsKeptUncompressed() {
        val mb = 1024L * 1024
        assertTrue(VideoLimits.canCopy(40 * mb))
        assertTrue(VideoLimits.canCopy(240 * mb))
        assertFalse(VideoLimits.canCopy(240 * mb + 1))
        assertFalse(VideoLimits.canCopy(null))
        assertFalse(VideoLimits.canCopy(0))
    }

    @Test fun lengthsReadAsWholeMinutes() {
        assertEquals(1, VideoLimits.minutes(75_000))
        assertEquals(2, VideoLimits.minutes(95_000))
        assertEquals(4, VideoLimits.minutes(4 * 60_000 + 10_000))
        assertEquals(30, VideoLimits.minutes(30 * 60_000))
        assertEquals(1, VideoLimits.minutes(1_000))
    }

    @Test fun bigVideosGoTo720pAndSmallOnesKeepTheirSize() {
        assertEquals(720, VideoLimits.shortSide(1920, 1080))
        assertEquals(720, VideoLimits.shortSide(1080, 1920))
        assertEquals(720, VideoLimits.shortSide(3840, 2160))
        assertNull(VideoLimits.shortSide(1280, 720))
        assertNull(VideoLimits.shortSide(640, 480))
        // Size unknown: made 720p at most anyway.
        assertEquals(720, VideoLimits.shortSide(0, 0))
    }

    @Test fun progressReadsAsAWholePercent() {
        assertEquals(0, VideoLimits.percent(0f))
        assertEquals(40, VideoLimits.percent(0.4f))
        assertEquals(99, VideoLimits.percent(1f))
        assertEquals(0, VideoLimits.percent(Float.NaN))
        assertEquals(0, VideoLimits.percent(-1f))
    }

    @Test fun aVideosDateTagIsReadInUtc() {
        val t = ZonedDateTime.of(2026, 11, 12, 10, 15, 30, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(t, VideoTags.date("20261112T101530.000Z"))
        assertEquals(t, VideoTags.date("20261112T101530Z"))
        assertEquals(t, VideoTags.date(" 20261112T101530 "))
        // Containers write 1904 or 1970 when nobody set a date.
        assertNull(VideoTags.date("19040101T000000.000Z"))
        assertNull(VideoTags.date("19700101T000000.000Z"))
        assertNull(VideoTags.date("20261332T101530.000Z"))
        assertNull(VideoTags.date("yesterday"))
        assertNull(VideoTags.date(""))
        assertNull(VideoTags.date(null))
    }

    @Test fun aVideosPlaceTagIsReadAsIso6709() {
        assertEquals(37.7749 to -122.4194, VideoTags.location("+37.7749-122.4194/"))
        assertEquals(-33.8688 to 151.2093, VideoTags.location("-33.8688+151.2093+010.000/"))
        assertEquals(36.0 to 25.0, VideoTags.location("+36+025/"))
        // No place, a damaged one, or the 0,0 some phones write for none.
        assertNull(VideoTags.location("+00.0000+000.0000/"))
        assertNull(VideoTags.location("+95.0000+010.0000/"))
        assertNull(VideoTags.location("+45.0000+190.0000/"))
        assertNull(VideoTags.location("somewhere"))
        assertNull(VideoTags.location(null))
    }
}
