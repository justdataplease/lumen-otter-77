package com.justdataplease.boringtravel.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun concurrentRendersOwnDifferentTemporaryFiles() {
        val output = File(temporary.root, "trip.mp4")
        val first = ClipFiles.temporary(output)
        val second = ClipFiles.temporary(output)
        assertNotEquals(first, second)
        assertEquals(output.parentFile, first.parentFile)
        first.delete()
        assertTrue(second.exists())
    }

    @Test fun unfinishedReplacementDoesNotRemoveThePreviousClip() {
        val output = File(temporary.root, "trip.mp4").apply { writeText("previous complete clip") }
        val unfinished = ClipFiles.temporary(output)
        assertTrue(runCatching { ClipFiles.publish(unfinished, output) }.isFailure)
        assertEquals("previous complete clip", output.readText())
    }

    @Test fun completedReplacementPublishesAndRemovesItsTemporaryFile() {
        val output = File(temporary.root, "trip.mp4").apply { writeText("previous complete clip") }
        val complete = ClipFiles.temporary(output).apply { writeText("new complete clip") }
        assertEquals(output, ClipFiles.publish(complete, output))
        assertEquals("new complete clip", output.readText())
        assertFalse(complete.exists())
    }
}
