package com.justdataplease.boringtravel.data.repo

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

/** Copies a picked image completely before making its final filename visible. Owns only the output stream. */
internal object PhotoFiles {
    fun copy(input: InputStream, destination: File, checkActive: () -> Unit = {}): String {
        val folder = requireNotNull(destination.parentFile)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Couldn't create the photo folder")
        require(!destination.exists()) { "A photo already exists at this path" }
        val partial = File.createTempFile("photo-", ".part", folder)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    copied += count
                }
            }
            if (copied == 0L) throw IOException("The photo is empty")
            checkActive()
            Files.move(partial.toPath(), destination.toPath())
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            partial.delete()
        }
    }
}
