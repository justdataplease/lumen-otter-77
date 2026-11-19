package com.justdataplease.boringtravel.clip

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Each render owns its temporary file; a failed/cancelled replacement preserves the last complete clip. */
internal object ClipFiles {
    fun temporary(output: File): File {
        val directory = requireNotNull(output.absoluteFile.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create the clip folder" }
        return File.createTempFile("clip-", ".part", directory)
    }

    fun publish(part: File, output: File): File {
        check(part.isFile && part.length() > 0) { "The encoder did not finish the clip" }
        try {
            Files.move(part.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return output
    }
}
