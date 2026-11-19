package com.justdataplease.boringtravel.art

import android.graphics.Bitmap
import android.graphics.Canvas
import com.justdataplease.boringtravel.data.repo.PhotoFiles
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The demo trip's photos: drawn pictures written as JPEG files through the same path an imported photo takes
 * (files/media/…, copied whole before the name appears, SHA-256 kept). Drawn on the phone, nothing downloaded.
 *
 * The file name says what it shows ("media/demo-lake-m-<id>.jpg": kind, mirrored or not), so the app draws it live
 * in the current theme ([live], used by MediaImage) while exports, books, sharing and backups use the JPEG, which
 * is in the default palette.
 */
object DemoArt {
    const val QUALITY = 88

    data class Picture(val kind: SceneKind, val shape: SceneMath.Shape = SceneMath.Shape.Wide, val mirror: Boolean = false) {
        val size: SceneMath.Size get() = SceneMath.pixelSize(shape)
    }

    /** What a demo file shows, read back from its name. */
    data class Live(val kind: SceneKind, val mirror: Boolean)

    private val name = Regex("""(?:^|/)demo-([a-z]+)-([mn])-[^/]+\.jpg$""")

    /** Where a demo picture is stored: `media/demo-<kind>-<m|n>-<id>.jpg`. */
    fun localPath(picture: Picture, id: String): String = "media/demo-${picture.kind.id}-${if (picture.mirror) "m" else "n"}-$id.jpg"

    /** The drawn picture behind a stored file, or null for every real photo (and for demo files named before kinds). */
    fun live(localPath: String?): Live? {
        val m = name.find(localPath ?: return null) ?: return null
        val kind = SceneKind.of(m.groupValues[1]) ?: return null
        return Live(kind, m.groupValues[2] == "m")
    }

    fun jpeg(picture: Picture, palette: ScenePalette = ScenePalettes.default): ByteArray {
        val size = picture.size
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        try {
            SceneArt.draw(Canvas(bitmap), size.width.toFloat(), size.height.toFloat(), picture.kind, palette, picture.mirror)
            return ByteArrayOutputStream(256 * 1024).also { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Writes [picture] to `files/[localPath]`; returns the file's SHA-256 like an imported photo. */
    fun write(filesDir: File, localPath: String, picture: Picture): String =
        PhotoFiles.copy(ByteArrayInputStream(jpeg(picture)), File(filesDir, localPath))
}
