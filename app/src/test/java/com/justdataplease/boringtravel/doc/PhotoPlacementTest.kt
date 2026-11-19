package com.justdataplease.boringtravel.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPlacementTest {
    @Test fun positionedRotatedPhotosStayInsideTheColumn() {
        for (aspect in listOf(0.4f, 0.7f, 1f, 1.5f, 3f)) {
            for (angle in listOf(-15f, -4f, 0f, 10f, 15f)) {
                for (position in listOf(0f, 0.2f, 0.5f, 1f)) {
                    val p = PhotoPlacement.of(Block.Photo("p", rotation = angle, width = 1f, position = position, caption = "A caption"), aspect)
                    val edge = (p.envelope - p.width) / 2f
                    assertTrue("left edge $aspect $angle $position", p.left - edge >= -0.00001f)
                    assertTrue("right edge $aspect $angle $position", p.left + p.width + edge <= 1.00001f)
                    assertTrue(p.width > 0 && p.width <= 1 && p.verticalRoom >= 0)
                }
            }
        }
    }

    @Test fun horizontalDragUsesSpareWidthAndClampsAtTheEdges() {
        assertEquals(0.75f, draggedPhotoPosition(0.5f, 50f, 400f, 0.5f), 0.0001f)
        assertEquals(1f, draggedPhotoPosition(0.5f, 999f, 400f, 0.5f), 0f)
        assertEquals(0f, draggedPhotoPosition(0.5f, -999f, 400f, 0.5f), 0f)
        assertEquals(0.5f, draggedPhotoPosition(0.5f, 100f, 400f, 1f), 0f)
        assertEquals(0.5f, draggedPhotoPosition(Float.NaN, Float.NaN, 0f, 1f), 0f)
    }

    @Test fun legacyAlignmentRemainsTheDefaultAndInvalidNumbersAreSafe() {
        assertEquals(0f, Block.Photo("p", align = Align.Start).horizontalPosition(), 0f)
        assertEquals(0.5f, Block.Photo("p").horizontalPosition(), 0f)
        assertEquals(1f, Block.Photo("p", align = Align.End).horizontalPosition(), 0f)
        val placement = PhotoPlacement.of(Block.Photo("p", width = Float.NaN, rotation = Float.NaN, position = Float.POSITIVE_INFINITY), Float.NaN)
        assertEquals(1f, placement.width, 0f)
        assertEquals(0f, placement.left, 0f)
    }

    @Test fun movingAcrossNotesPreservesEveryStableBlockAndCanMoveBothDirections() {
        val order = listOf("first note", "photo", "page break", "unknown block", "last note")
        val down = moveBlockBefore(order, "photo", "last note")
        assertEquals(listOf("first note", "page break", "unknown block", "photo", "last note"), down)
        assertEquals(order, moveBlockBefore(down, "photo", "page break"))
        assertEquals(listOf("first note", "page break", "unknown block", "last note", "photo"), moveBlockBefore(order, "photo", null))
        assertEquals(order, moveBlockBefore(order, "photo", "photo"))
        assertEquals(order, moveBlockBefore(order, "photo", "missing target"))
    }
}
