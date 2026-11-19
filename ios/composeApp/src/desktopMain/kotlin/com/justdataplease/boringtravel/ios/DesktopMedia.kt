package com.justdataplease.boringtravel.ios

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID

/*
 * The notes' photos and videos on the desktop, where the iPhone's screens are previewed: files in a folder, a file
 * dialog that hands over the files chosen (the iPhone's library picker), no camera, and a silent still player: a video
 * plays in Android's own player with its clock moving over an empty picture ([StillVideoPlayback]; AVPlayer on iPhone),
 * and its frame is the play mark on the chip colour.
 */

/** The notes' media in [root] (composeApp/build/preview for `gradlew :composeApp:run`). */
fun desktopMediaKit(root: File): MediaKit {
    val files = DiskMediaFiles(root)
    return MediaKit(files, DesktopPicker(files), playback = StillVideoPlayback())
}

/** [MediaFiles] in a folder on the desktop. */
class DiskMediaFiles(private val root: File) : MediaFiles {
    private fun file(path: String) = File(root, path)

    override fun write(path: String, bytes: ByteArray): Boolean = runCatching {
        file(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }.isSuccess

    override fun read(path: String): ByteArray? = file(path).takeIf { it.isFile }?.readBytes()

    override fun readChunks(path: String, each: (ByteArray, Int) -> Unit): Boolean {
        val f = file(path).takeIf { it.isFile } ?: return false
        f.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) each(buffer, n)
            }
        }
        return true
    }

    override fun size(path: String): Long? = file(path).takeIf { it.isFile }?.length()

    override fun readRange(path: String, offset: Long, length: Int): ByteArray? = runCatching {
        java.io.RandomAccessFile(file(path), "r").use { f ->
            if (offset < 0 || offset + length > f.length()) return null
            ByteArray(length).also { f.seek(offset); f.readFully(it) }
        }
    }.getOrNull()

    override fun append(path: String, bytes: ByteArray, count: Int): Boolean = runCatching {
        val f = file(path).apply { parentFile?.mkdirs() }
        java.io.FileOutputStream(f, true).use { it.write(bytes, 0, count) }
    }.isSuccess

    override fun move(from: String, to: String): Boolean {
        val target = file(to).apply { parentFile?.mkdirs() }
        return file(from).renameTo(target)
    }

    override fun delete(path: String) {
        // A folder goes with what is in it (the exports), as Foundation's removeItemAtPath on iPhone.
        file(path).deleteRecursively()
    }

    override fun absolute(path: String): String = file(path).absolutePath

    override fun list(path: String): List<String> = file(path).listFiles().orEmpty().filter { it.isFile }.map { it.name }

    override fun modifiedMs(path: String): Long? = file(path).takeIf { it.exists() }?.lastModified()

    override fun copy(from: String, to: String): Boolean = runCatching {
        val target = file(to).apply { parentFile?.mkdirs() }
        java.nio.file.Files.copy(file(from).toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }.isSuccess

    override fun replace(from: String, to: String): Boolean = runCatching {
        val source = file(from).takeIf { it.isFile } ?: return false
        val target = file(to).apply { parentFile?.mkdirs() }
        java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }.isSuccess
}

/** Photos and videos chosen in a file dialog, copied under picked/ in the order the dialog gives them. */
class DesktopPicker(private val files: DiskMediaFiles) : MediaPicker {
    override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) {
        val dialog = FileDialog(null as Frame?, "Choose photos and videos", FileDialog.LOAD).apply {
            isMultipleMode = true
            isVisible = true
        }
        done(dialog.files.orEmpty().take(max).mapNotNull(::handOver))
    }

    /** [file] copied under picked/, typed by its extension. */
    fun handOver(file: File): PickedMedia? {
        val ext = file.extension.lowercase().ifEmpty { "jpg" }
        val path = "picked/${UUID.randomUUID()}.$ext"
        return if (files.write(path, file.readBytes())) PickedMedia(path, MediaTypes.ofExtension(ext)) else null
    }

    override val hasCamera: Boolean = false

    override fun takePhoto(done: (PickedMedia?) -> Unit) = done(null)

    override fun recordVideo(done: (PickedMedia?) -> Unit) = done(null)
}
