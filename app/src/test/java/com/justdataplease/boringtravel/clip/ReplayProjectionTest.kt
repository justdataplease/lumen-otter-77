package com.justdataplease.boringtravel.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayProjectionTest {
    @Test fun flatCameraIsTheIdentityIncludingItsNativeMatrix() {
        for (shape in ClipShape.entries) {
            val projection = ReplayProjection(shape.width.toDouble(), shape.height.toDouble(), 0.0)
            val camera = Camera(0.55, 0.4, 0.02)
            assertEquals(camera.rect(shape.aspect), projection.groundRect(camera))
            for (x in listOf(0.0, shape.width / 2.0, shape.width.toDouble())) {
                for (y in listOf(0.0, shape.height / 2.0, shape.height.toDouble())) {
                    assertPoint(ReplayPoint(x, y), projection.project(x, y))
                    assertPoint(ReplayPoint(x, y), applyMatrix(projection.matrixValues(), x, y), 0.001)
                }
            }
        }
    }

    @Test fun noSkyUnlessAskedForAndTheBandFollowsTheTiltWithIt() {
        // Owner, on 1.4.0's videos: "there is a gradient glare below the top place sign, can you remove it by default?"
        for (shape in ClipShape.entries) {
            val w = shape.width.toDouble()
            val h = shape.height.toDouble()
            for (tilt in listOf(0.0, 12.0, 24.0, 36.0, ReplayProjection.MAX_TILT_NO_SKY, ReplayProjection.MAX_TILT)) {
                val plain = ReplayProjection(w, h, tilt)
                assertEquals(false, plain.sky)
                assertEquals(0.0, plain.horizonY, 0.0)
                // The visible ground (what the map pictures are fetched for) runs up to the top edge of the frame,
                // farther than the band let it.
                val camera = Camera(0.55, 0.4, 0.02)
                val rowZero = camera.cy + (plain.unproject(w / 2, 0.0).y / h - 0.5) * camera.spanY(w / h)
                assertEquals(rowZero, plain.groundRect(camera).y0, 1e-12)
                val banded = ReplayProjection(w, h, tilt, sky = true)
                if (tilt > 0) assertTrue(plain.groundRect(camera).y0 < banded.groundRect(camera).y0)
                assertEquals(h * ReplayProjection.SKY * tilt / ReplayProjection.MAX_TILT, banded.horizonY, 1e-9)
            }
            assertEquals(h * ReplayProjection.SKY, ReplayProjection(w, h, ReplayProjection.MAX_TILT, sky = true).horizonY, 1e-9)
        }
        assertTrue(ReplayProjection.MAX_TILT_NO_SKY in 30.0..ReplayProjection.MAX_TILT)
    }

    @Test fun everyOutputPointRoundTripsThroughTheTiltedGroundPlane() {
        for (shape in ClipShape.entries) for (tilt in listOf(0.0, 24.0, ReplayProjection.MAX_TILT_NO_SKY, ReplayProjection.MAX_TILT)) for (sky in listOf(false, true)) {
            val projection = ReplayProjection(shape.width.toDouble(), shape.height.toDouble(), tilt, sky)
            for (fx in listOf(0.0, 0.1, 0.5, 0.9, 1.0)) for (fy in listOf(0.0, 0.1, 0.5, 0.9, 1.0)) {
                val pixel = ReplayPoint(shape.width * fx, shape.height * fy)
                val ground = projection.unproject(pixel.x, pixel.y)
                assertTrue(ground.x.isFinite() && ground.y.isFinite())
                assertPoint(pixel, projection.project(ground.x, ground.y))
                // The route's native Canvas matrix and the vehicle's explicit projection must coincide.
                assertPoint(pixel, applyMatrix(projection.matrixValues(), ground.x, ground.y), 0.001)
            }
        }
    }

    @Test fun distantGroundIsForeshortenedAndTheCameraCenterDoesNotMove() {
        val projection = ReplayProjection(1080.0, 1920.0, ReplayProjection.MAX_TILT)
        val farLeft = projection.project(300.0, 300.0)
        val farRight = projection.project(780.0, 300.0)
        val nearLeft = projection.project(300.0, 1600.0)
        val nearRight = projection.project(780.0, 1600.0)
        assertTrue(nearRight.x - nearLeft.x > farRight.x - farLeft.x)
        assertPoint(ReplayPoint(540.0, 960.0), projection.project(540.0, 960.0))
    }

    @Test fun previewAndExportHaveIdenticalNormalizedProjection() {
        for (shape in ClipShape.entries) {
            val full = ReplayProjection(shape.width.toDouble(), shape.height.toDouble(), 42.0)
            val preview = ReplayProjection(shape.width / 3.0, shape.height / 3.0, 42.0)
            val a = full.project(shape.width * 0.72, shape.height * 0.38)
            val b = preview.project(shape.width * 0.72 / 3, shape.height * 0.38 / 3)
            assertEquals(a.x / 3, b.x, 1e-8)
            assertEquals(a.y / 3, b.y, 1e-8)
            val camera = Camera(0.57, 0.36, 0.025)
            val fullRect = full.groundRect(camera)
            val previewRect = preview.groundRect(camera)
            assertEquals(fullRect.x0, previewRect.x0, 1e-12)
            assertEquals(fullRect.y0, previewRect.y0, 1e-12)
            assertEquals(fullRect.x1, previewRect.x1, 1e-12)
            assertEquals(fullRect.y1, previewRect.y1, 1e-12)
        }
    }

    private fun assertPoint(expected: ReplayPoint, actual: ReplayPoint, tolerance: Double = 1e-8) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }

    private fun applyMatrix(m: FloatArray, x: Double, y: Double): ReplayPoint {
        val divisor = m[6] * x + m[7] * y + m[8]
        return ReplayPoint((m[0] * x + m[1] * y + m[2]) / divisor, (m[3] * x + m[4] * y + m[5]) / divisor)
    }
}
