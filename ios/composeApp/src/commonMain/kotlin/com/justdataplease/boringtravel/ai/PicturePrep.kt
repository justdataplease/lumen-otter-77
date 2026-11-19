package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.ios.MediaFiles
import com.justdataplease.boringtravel.ios.MediaWork
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * The pictures picked or shared for [PictureReading], made ready to send (the Android app's ai/PicturePrep.kt, written
 * again with Skia for android.graphics): turned upright (EXIF), scaled and cut as [PictureReading.layout] says, each
 * piece a JPEG on white (a transparent PNG would turn black). Only these copies in memory are sent; the pictures
 * themselves are left as they are. [files] holds them where the picker put them ("picked/…").
 */
object PicturePrep {
    suspend fun load(files: MediaFiles, paths: List<String>): List<AiPicture> = withContext(MediaWork) {
        val chosen = paths.take(PictureReading.MAX_PICTURES)
        val each = PictureReading.piecesEach(chosen.size)
        chosen.flatMap { path ->
            ensureActive()
            pieces(files.read(path) ?: throw ItineraryImportException(PictureReading.CANT_OPEN), each)
        }
    }

    /** One picture's pieces, at most [maxPieces]. */
    fun pieces(bytes: ByteArray, maxPieces: Int): List<AiPicture> {
        // A HEIC or AVIF picture (the photo library gives them as they are, as Android's picker does) through the
        // phone's reader first.
        val data = Data.makeFromBytes(com.justdataplease.boringtravel.ios.Images.drawable(bytes, MAX_READ_SIDE) ?: bytes)
        val codec = runCatching { Codec.makeFromData(data) }.getOrNull() ?: run {
            data.close()
            throw ItineraryImportException(PictureReading.CANT_OPEN)
        }
        try {
            val origin = codec.encodedOrigin
            val turned = origin.swapsWidthHeight()
            val width = if (turned) codec.height else codec.width
            val height = if (turned) codec.width else codec.height
            if (width <= 0 || height <= 0) throw ItineraryImportException(PictureReading.CANT_OPEN)
            val layout = PictureReading.layout(width, height, maxPieces)
            val bitmap = runCatching { codec.readPixels() }.getOrNull() ?: throw ItineraryImportException(PictureReading.CANT_OPEN)
            val image = Image.makeFromBitmap(bitmap)
            // Turned and scaled in one step onto white: the size the layout gives, the right way up.
            val upright = Surface.makeRasterN32Premul(layout.width, layout.height)
            try {
                upright.canvas.clear(WHITE)
                upright.canvas.scale(layout.width.toFloat() / width, layout.height.toFloat() / height)
                upright.canvas.concat(origin.toMatrix(bitmap.width, bitmap.height))
                upright.canvas.drawImageRect(
                    image, Rect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat()), Rect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), Paint(), true,
                )
            } finally {
                image.close()
                bitmap.close()
            }
            val whole = upright.makeImageSnapshot()
            try {
                return layout.pieces.map { rows ->
                    val piece = if (rows.first == 0 && rows.last == layout.height - 1) whole else {
                        val part = Surface.makeRasterN32Premul(layout.width, rows.last - rows.first + 1)
                        part.canvas.clear(WHITE)
                        part.canvas.drawImage(whole, 0f, -rows.first.toFloat())
                        part.makeImageSnapshot().also { part.close() }
                    }
                    try {
                        val jpeg = piece.encodeToData(EncodedImageFormat.JPEG, PictureReading.JPEG_QUALITY)?.bytes
                            ?: throw ItineraryImportException(PictureReading.CANT_OPEN)
                        AiPicture(jpeg)
                    } finally {
                        if (piece !== whole) piece.close()
                    }
                }
            } finally {
                whole.close()
                upright.close()
            }
        } finally {
            codec.close()
            data.close()
        }
    }

    private const val WHITE = 0xFFFFFFFF.toInt()

    /** A picture the phone's reader turns into a JPEG for Skia is read no bigger than this (more than any layout uses). */
    private const val MAX_READ_SIDE = 4096
}
