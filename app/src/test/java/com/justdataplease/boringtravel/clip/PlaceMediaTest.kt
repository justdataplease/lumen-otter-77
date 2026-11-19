package com.justdataplease.boringtravel.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A place's media in the clip's Media tool (owner, 2026-11-14: "in clips videos are added as photos and have the same
 * structure"): the per-place line, the Add media sheet's tiles and what tapping a journal video there does.
 */
class PlaceMediaTest {
    @Test fun aPlaceLineCountsPhotosAndVideos() {
        assertEquals("3 of 5 photos · 1 video", PlaceMedia.countLine(3, 5, 1))
        assertEquals("0 of 2 photos · 3 videos", PlaceMedia.countLine(0, 2, 3))
        assertEquals("1 of 1 photo", PlaceMedia.countLine(1, 1, 0))
        assertEquals("4 of 6 photos", PlaceMedia.countLine(4, 6, 0))
        assertEquals("2 videos", PlaceMedia.countLine(0, 0, 2))
        assertEquals("1 video", PlaceMedia.countLine(0, 0, 1))
        assertEquals("No media", PlaceMedia.countLine(0, 0, 0))
    }

    @Test fun theSheetListsPhotosAndVideosTogetherOldestFirst() {
        val photos = listOf(ClipPhoto("p1", 300L, null, null), ClipPhoto("p2", 100L, null, null), ClipPhoto("p3", null, null, null))
        val videos = listOf(TripVideo("v1", 200L, "video/mp4"), TripVideo("v2", null, "video/mp4"))
        val tiles = PlaceMedia.tiles(photos, videos)
        assertEquals(listOf("p2", "v1", "p1", "p3", "v2"), tiles.map { it.id })
        assertEquals(listOf(false, true, false, false, true), tiles.map { it.video })
        // No videos: the photos as before; no photos (photos off): only the videos.
        assertEquals(listOf("p2", "p1", "p3"), PlaceMedia.tiles(photos, emptyList()).map { it.id })
        assertEquals(listOf("v1", "v2"), PlaceMedia.tiles(emptyList(), videos).map { it.id })
    }

    @Test fun tappingAJournalVideoAddsMovesOrTakesItOut() {
        val here = "label:day:2026-06-16"
        val there = "label:day:2026-06-17"
        val phone = ClipVideo("c0", "c0.mp4", here, 8_000)
        val fromNote = ClipVideo("c1", "c1.mp4", there, 8_000, source = "m1")
        val videos = listOf(phone, fromNote)
        // Not in the clip yet: added after this place.
        assertEquals(VideoTap.Add, PlaceMedia.tap(videos, "m2", here))
        // In at another place: moves here (the clip video's own id).
        assertEquals(VideoTap.Move("c1"), PlaceMedia.tap(videos, "m1", here))
        // Already here: comes out.
        assertEquals(VideoTap.Remove("c1"), PlaceMedia.tap(videos, "m1", there))
        // A phone video is never taken for a journal one.
        assertNull(PlaceMedia.added(videos, "c0"))
        assertEquals(fromNote, PlaceMedia.added(videos, "m1"))
    }
}
