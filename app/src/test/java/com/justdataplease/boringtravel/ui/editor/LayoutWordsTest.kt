package com.justdataplease.boringtravel.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutWordsTest {
    @Test fun aLayoutIsCountedByWhatItHolds() {
        assertEquals("4 photos", LayoutWords.count(4, 0))
        assertEquals("1 photo", LayoutWords.count(1, 0))
        assertEquals("2 videos", LayoutWords.count(0, 2))
        assertEquals("1 video", LayoutWords.count(0, 1))
        assertEquals("3 photos and 1 video", LayoutWords.count(3, 1))
        assertEquals("1 photo and 2 videos", LayoutWords.count(1, 2))
    }

    @Test fun itsThingsAreNamedTogether() {
        assertEquals("photos", LayoutWords.kinds(4, 0))
        assertEquals("videos", LayoutWords.kinds(0, 2))
        assertEquals("photos and videos", LayoutWords.kinds(3, 1))
    }
}
