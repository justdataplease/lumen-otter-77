package com.justdataplease.boringtravel.backup

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class BackupFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun verifiedBytesReplaceTheFileAndRepeatRestoreSeesItAsComplete() {
        val target = folder.newFile("photo.jpg").apply { writeText("damaged old bytes") }
        val staged = folder.newFile("photo.restore").apply { writeText("original photo bytes") }
        val hash = BackupFiles.sha256(staged)
        assertFalse(BackupFiles.usable(target, hash))
        BackupFiles.publish(staged, target, hash)
        assertEquals("original photo bytes", target.readText())
        assertTrue(BackupFiles.usable(target, hash.uppercase()))
        assertFalse(staged.exists())
    }

    @Test fun wrongHashNeverOverwritesAnExistingOriginalAndCleansTemporaryFiles() {
        val target = folder.newFile("photo.jpg").apply { writeText("keep original") }
        val staged = folder.newFile("photo.restore").apply { writeText("wrong response") }
        val partial = File(staged.path + ".part").apply { writeText("unfinished") }
        try {
            BackupFiles.publish(staged, target, "0".repeat(64))
            fail("Expected checksum failure")
        } catch (_: IOException) { }
        assertEquals("keep original", target.readText())
        assertFalse(staged.exists())
        assertFalse(partial.exists())
    }

    @Test fun cancellationDuringVerificationPreservesTheOriginalAndRemovesStaging() {
        val target = folder.newFile("photo.jpg").apply { writeText("keep original") }
        val staged = folder.newFile("photo.restore").apply { writeBytes(ByteArray(256 * 1024) { 7 }) }
        var checks = 0
        try {
            BackupFiles.publish(staged, target, "0".repeat(64)) {
                if (++checks == 2) throw CancellationException("cancel")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals("keep original", target.readText())
        assertFalse(staged.exists())
        assertEquals(2, checks)
    }

    @Test fun legacyPhotoWithoutHashStillRejectsEmptyOrMissingContent() {
        val empty = folder.newFile("empty")
        assertFalse(BackupFiles.usable(empty, null))
        assertFalse(BackupFiles.usable(File(folder.root, "missing"), null))
        empty.writeText("abc")
        assertTrue(BackupFiles.usable(empty, null))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BackupFiles.sha256(empty))
    }
}
