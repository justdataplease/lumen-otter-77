package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PaperVehicle3DTest {
    private val vehicles=Vehicle.entries.filter { it!=Vehicle.Auto }

    @Test fun allSixModelsHaveBoundedCachedVolumetricGeometry() {
        assertNull(PaperVehicleMeshes.get(Vehicle.Auto))
        for(vehicle in vehicles) {
            val mesh=requireNotNull(PaperVehicleMeshes.get(vehicle))
            assertSame(mesh,PaperVehicleMeshes.get(vehicle))
            assertTrue("$vehicle faces",mesh.faces.size in 20..700)
            assertTrue("$vehicle vertices",mesh.vertices.size in 40..3500)
            assertTrue(mesh.radius.isFinite() && mesh.radius>0f)
            assertTrue(mesh.vertices.maxOf { it.x }-mesh.vertices.minOf { it.x }>.1f)
            assertTrue(mesh.vertices.maxOf { it.y }-mesh.vertices.minOf { it.y }>.1f)
            assertTrue(mesh.vertices.maxOf { it.z }-mesh.vertices.minOf { it.z }>.1f)
            mesh.faces.forEach { face ->
                assertTrue(face.indices.size in 3..14)
                assertTrue(face.indices.all {it in mesh.vertices.indices})
                assertEquals(face.indices.size,face.indices.toSet().size)
            }
        }
    }

    @Test fun everyHeadingHasFiniteBoundedVisibleFacesInBackToFrontOrder() {
        val camera=PaperVehicleProjection()
        for(vehicle in vehicles) for(tilt in listOf(0f,24f,48f,65f)) for(heading in 0 until 360 step 15) {
            val mesh=requireNotNull(PaperVehicleMeshes.get(vehicle))
            camera.project(mesh,heading.toFloat(),tilt,.18f)
            assertTrue("$vehicle/$heading/$tilt visible",camera.visibleFaces>0)
            for(i in 0 until mesh.vertices.size) {
                for(axis in 0..2) assertTrue(camera.coordinates[i*3+axis].isFinite())
                assertTrue("$vehicle x",abs(camera.coordinates[i*3])<=.55f)
                assertTrue("$vehicle y",abs(camera.coordinates[i*3+1])<=.55f)
            }
            for(i in 0 until camera.visibleFaces) {
                val face=camera.faceOrder[i]
                assertTrue(camera.faceLight[face] in .66f..1f)
                if(i>0) assertTrue(camera.faceDepth[camera.faceOrder[i-1]]<=camera.faceDepth[face])
            }
        }
    }

    @Test fun modelNoseFollowsProjectedRouteTangentAtEveryCameraTilt() {
        val camera=PaperVehicleProjection(); val mesh=requireNotNull(PaperVehicleMeshes.get(Vehicle.Car))
        for(tilt in listOf(0f,24f,30f,48f,65f)) for(heading in 0 until 360 step 5) {
            camera.project(mesh,heading.toFloat(),tilt,0f)
            val x=sin(camera.yawRadians); val north=cos(camera.yawRadians)*cos(tilt.coerceIn(30f,65f)*PI.toFloat()/180f)
            val length=hypot(x,north)
            val radians=heading*PI/180
            assertEquals(sin(radians),(x/length).toDouble(),.000002)
            assertEquals(cos(radians),(north/length).toDouble(),.000002)
        }
    }

    @Test fun turnsExposeDifferentActualFacesInsteadOfSpinningOneFlatImage() {
        val camera=PaperVehicleProjection()
        for(vehicle in vehicles) {
            val mesh=requireNotNull(PaperVehicleMeshes.get(vehicle))
            fun visibleAt(heading:Float):Set<Int> {
                camera.project(mesh,heading,40f,0f)
                return camera.faceOrder.take(camera.visibleFaces).toSet()
            }
            val rear=visibleAt(0f); val side=visibleAt(90f); val front=visibleAt(180f)
            assertNotEquals("$vehicle rear/front",rear,front)
            assertNotEquals("$vehicle side/front",side,front)
        }
    }

    @Test fun walkingMovesArticulatedLimbsButKeepsTheTorsoStable() {
        val mesh=requireNotNull(PaperVehicleMeshes.get(Vehicle.Walk)); val camera=PaperVehicleProjection()
        camera.project(mesh,35f,40f,0f); val first=camera.coordinates.copyOf()
        camera.project(mesh,35f,40f,.20f)
        var changed=0
        for(i in mesh.vertices.indices) {
            val moved=(0..2).any { abs(first[i*3+it]-camera.coordinates[i*3+it])>.00001f }
            if(mesh.vertices[i].bone==0) assertFalse(moved) else if(moved) changed++
        }
        assertTrue(changed>20)
        val car=requireNotNull(PaperVehicleMeshes.get(Vehicle.Car))
        camera.project(car,35f,40f,0f); val static=camera.coordinates.copyOf()
        camera.project(car,35f,40f,.20f)
        assertArrayEquals(static,camera.coordinates,0f)
    }

    @Test fun previewAndExportWorkspacesAreIndependentAndReuseTheirWarmBuffers() {
        val preview=PaperVehicleProjection(); val export=PaperVehicleProjection()
        vehicles.forEach { preview.project(requireNotNull(PaperVehicleMeshes.get(it)),0f,40f,0f) }
        val coordinates=preview.coordinates; val order=preview.faceOrder; val depths=preview.faceDepth; val light=preview.faceLight
        for(vehicle in vehicles) {
            val mesh=requireNotNull(PaperVehicleMeshes.get(vehicle))
            preview.project(mesh,117f,48f,.12f); export.project(mesh,117f,48f,.12f)
            assertSame(coordinates,preview.coordinates); assertSame(order,preview.faceOrder)
            assertSame(depths,preview.faceDepth); assertSame(light,preview.faceLight)
            assertNotSame(preview.coordinates,export.coordinates)
            assertEquals(preview.visibleFaces,export.visibleFaces)
            for(i in 0 until mesh.vertices.size*3) assertEquals(preview.coordinates[i],export.coordinates[i],0f)
            for(i in 0 until preview.visibleFaces) assertEquals(preview.faceOrder[i],export.faceOrder[i])
        }
    }

    @Test fun invalidAnglesAreSafeAndWholeTurnsRepeatDeterministically() {
        val camera=PaperVehicleProjection(); val mesh=requireNotNull(PaperVehicleMeshes.get(Vehicle.Ferry))
        camera.project(mesh,Float.NaN,Float.POSITIVE_INFINITY,Float.NaN)
        assertTrue(camera.coordinates.all {it.isFinite()})
        camera.project(mesh,15f,40f,.5f); val first=camera.coordinates.copyOf()
        camera.project(mesh,735f,40f,.5f)
        assertArrayEquals(first,camera.coordinates,0f)
    }
}
