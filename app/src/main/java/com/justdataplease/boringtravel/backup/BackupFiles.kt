package com.justdataplease.boringtravel.backup

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Bounded file verification; a failed/cancelled photo never replaces an existing original. */
internal object BackupFiles {
    fun sha256(file: File, checkActive: () -> Unit = {}): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        checkActive()
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun usable(file: File, expectedHash: String?, checkActive: () -> Unit = {}): Boolean =
        file.isFile && file.length() > 0 && (expectedHash == null || sha256(file, checkActive).equals(expectedHash, ignoreCase = true))

    /** The caller downloads into [staged], in the same directory as [target]. */
    fun publish(staged: File, target: File, expectedHash: String?, checkActive: () -> Unit = {}) {
        try {
            if (!usable(staged, expectedHash, checkActive)) throw IOException("The downloaded photo is empty or does not match its backup checksum.")
            checkActive()
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staged.delete()
            File(staged.path + ".part").delete()
        }
    }
}
