package com.justdataplease.boringtravel.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFilesTest {
    @Test fun fileNamesAreSafe() {
        assertEquals("Cyclades, slowly.pdf", ExportFiles.fileName("Cyclades, slowly", "pdf"))
        assertEquals("Κυκλάδες 2027.pdf", ExportFiles.fileName("Κυκλάδες 2027", "pdf"))
        assertEquals("a b c.html", ExportFiles.fileName("a/b\\c", "html"))
        assertEquals("Trip.pdf", ExportFiles.fileName("../..", "pdf"))
        assertEquals("Trip.pdf", ExportFiles.fileName("  ", "pdf"))
        assertTrue(ExportFiles.fileName("x".repeat(200), "pdf").length <= 64)
    }

    @Test fun webViewMayLoadOnlyFontsPhotosAndDataUris() {
        val dir = "/data/user/0/app/cache/exports/img"
        assertTrue(ExportFiles.isAllowedUrl("file:///android_res/font/literata.ttf", dir))
        assertTrue(ExportFiles.isAllowedUrl("file:///data/user/0/app/cache/exports/img/p1-1600.jpg", dir))
        assertTrue(ExportFiles.isAllowedUrl("data:image/jpeg;base64,AAAA", dir))
        assertFalse(ExportFiles.isAllowedUrl("file:///data/user/0/app/databases/bt.db", dir))
        assertFalse(ExportFiles.isAllowedUrl("file:///data/user/0/app/cache/exports/img/../../../databases/bt.db", dir))
        assertFalse(ExportFiles.isAllowedUrl("file:///data/user/0/app/cache/exports/imgx/a.jpg", dir))
        assertFalse(ExportFiles.isAllowedUrl("content://media/external/images/1", dir))
        assertFalse(ExportFiles.isAllowedUrl("https://example.com/a.jpg", dir))
        assertFalse(ExportFiles.isAllowedUrl("file:///android_asset/x.js", dir))
    }

    @Test fun fitNeverEnlarges() {
        assertEquals(1600 to 1200, ExportFiles.fit(4000, 3000, 1600))
        assertEquals(1200 to 1600, ExportFiles.fit(3000, 4000, 1600))
        assertEquals(800 to 600, ExportFiles.fit(800, 600, 1600))
        assertEquals(1600 to 1, ExportFiles.fit(10000, 2, 1600))
    }

    @Test fun docsPhotosShrinkForLongTrips() {
        assertEquals(1600, ExportFiles.docsMaxPx(PhotoSize.Large, 10))
        assertEquals(800, ExportFiles.docsMaxPx(PhotoSize.Small, 10))
        assertEquals(900, ExportFiles.docsMaxPx(PhotoSize.Large, 100))
        assertEquals(640, ExportFiles.docsMaxPx(PhotoSize.Medium, 500))
    }
}
