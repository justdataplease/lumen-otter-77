package com.justdataplease.boringtravel.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The pictures picked or shared for [PictureReading], made ready to send: turned upright (EXIF), scaled and cut as
 * [PictureReading.layout] says, each piece a JPEG on white (a transparent PNG would turn black). Only these copies in
 * memory are sent; the pictures themselves are left as they are.
 */
object PicturePrep {
    suspend fun load(context: Context, uris: List<Uri>): List<AiPicture> = withContext(Dispatchers.IO) {
        val chosen = uris.take(PictureReading.MAX_PICTURES)
        val each = PictureReading.piecesEach(chosen.size)
        chosen.flatMap { uri ->
            ensureActive()
            pieces(context, uri, each)
        }
    }

    private fun pieces(context: Context, uri: Uri, maxPieces: Int): List<AiPicture> {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Reading only the size gives no bitmap (null): the stream itself is what must open.
        val stream = resolver.openInputStream(uri) ?: throw ItineraryImportException(PictureReading.CANT_OPEN)
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ItineraryImportException(PictureReading.CANT_OPEN)
        val rotation = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } }.getOrNull() ?: 0
        val turned = rotation == 90 || rotation == 270
        val width = if (turned) bounds.outHeight else bounds.outWidth
        val height = if (turned) bounds.outWidth else bounds.outHeight
        val layout = PictureReading.layout(width, height, maxPieces)
        // Decoded at the smallest power-of-two size still at least as large as the size sent.
        var sample = 1
        while (width / (sample * 2) >= layout.width && height / (sample * 2) >= layout.height) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw ItineraryImportException(PictureReading.CANT_OPEN)
        // Turned and scaled in one step onto white: the size [layout] gives, the right way up.
        val upright = createBitmap(layout.width, layout.height)
        try {
            val dw = if (turned) decoded.height else decoded.width
            val dh = if (turned) decoded.width else decoded.height
            val matrix = Matrix().apply {
                postTranslate(-decoded.width / 2f, -decoded.height / 2f)
                postRotate(rotation.toFloat())
                postScale(layout.width.toFloat() / dw, layout.height.toFloat() / dh)
                postTranslate(layout.width / 2f, layout.height / 2f)
            }
            Canvas(upright).apply {
                drawColor(Color.WHITE)
                drawBitmap(decoded, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
        } finally {
            decoded.recycle()
        }
        try {
            return layout.pieces.map { rows ->
                val piece = if (rows.first == 0 && rows.last == layout.height - 1) upright
                else createBitmap(upright.width, rows.last - rows.first + 1).also {
                    Canvas(it).drawBitmap(upright, Rect(0, rows.first, upright.width, rows.last + 1), Rect(0, 0, it.width, it.height), null)
                }
                try {
                    val out = ByteArrayOutputStream()
                    piece.compress(Bitmap.CompressFormat.JPEG, PictureReading.JPEG_QUALITY, out)
                    AiPicture(out.toByteArray())
                } finally {
                    if (piece !== upright) piece.recycle()
                }
            }
        } finally {
            upright.recycle()
        }
    }
}
