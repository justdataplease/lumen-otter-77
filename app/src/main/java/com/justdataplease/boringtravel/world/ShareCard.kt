package com.justdataplease.boringtravel.world

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.graphics.toArgb
import com.justdataplease.boringtravel.art.PlaneMarkBitmap
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.globe.FlagBadges
import com.justdataplease.boringtravel.globe.GlobeGeometry
import com.justdataplease.boringtravel.globe.GlobeMath
import com.justdataplease.boringtravel.globe.GlobePalette
import com.justdataplease.boringtravel.globe.GlobePin
import com.justdataplease.boringtravel.globe.GlobeRenderer
import com.justdataplease.boringtravel.ui.theme.BtTokens
import com.justdataplease.boringtravel.ui.theme.Face
import com.justdataplease.boringtravel.ui.theme.Paper
import com.justdataplease.boringtravel.ui.theme.planeMark
import java.io.File
import java.time.LocalDate

/**
 * The "your world" card for stories and messages: 9:16, drawn offscreen in the current theme (its paper and faces). The globe turned to your
 * countries, the count, a line of numbers, your flags and the brand mark. No tagline.
 */
object ShareCard {
    const val WIDTH = 1080
    const val HEIGHT = 1920

    /** The headline follows the count: "No countries yet", "1 country", "7 countries". */
    fun headline(countries: Int): String = when (countries) {
        0 -> "No countries yet"
        1 -> "1 country"
        else -> "$countries countries"
    }

    /** "4% of the world · 2 continents"; with none yet, the next country if one is planned. */
    fun line(world: World, nameOf: (String) -> String = { CountryInfo.name(it) }): String {
        if (world.countries == 0) return world.nextCodes.firstOrNull()?.let { "Next: ${nameOf(it)}" } ?: ""
        val k = world.continents.size
        return "${world.worldPercent}% of the world · $k ${if (k == 1) "continent" else "continents"}"
    }

    fun render(context: Context, tokens: BtTokens, world: World, geometry: GlobeGeometry?): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = tokens.colors
        val w = WIDTH.toFloat()
        val unit = WIDTH / 360f // one design dp on the card
        canvas.drawColor(c.bg.toArgb())
        paper(canvas, tokens, unit)

        // The theme's own faces (its font files at their weights; variable files get their axes on the paint).
        val face = { f: Face -> runCatching { ResourcesCompat.getFont(context, f.res) }.getOrNull()?.let { Typeface.create(it, f.weight, false) } }
        val type = tokens.type
        val display: Typeface? = face(type.displayFace)
        val body: Typeface? = face(type.bodyFace)
        val mono: Typeface? = face(type.monoFace)

        // The globe, turned to the traveller's countries.
        val palette = GlobePalette.from(tokens)
        if (geometry != null) {
            val renderer = GlobeRenderer(geometry, unit, pinSizeDp = 24f)
            renderer.antiAliasFills = true
            renderer.showLabels = false
            renderer.setPalette(palette, body)
            renderer.setCountries(world.visitedCodes, world.planned + world.wished)
            renderer.setPins(
                world.visited.mapNotNull { s ->
                    val p = CountryInfo.labelPoint(s.code) ?: s.ownCenter?.let { it.lat to it.lng } ?: return@mapNotNull null
                    GlobePin(s.code, p.first, p.second, "")
                },
            )
            val points = world.visited.mapNotNull { CountryInfo.labelPoint(it.code) }
            val (lat, lng) = GlobeMath.meanPoint(points)?.let { (it.first - 6.0).coerceIn(-50.0, 60.0) to it.second } ?: (36.0 to 8.0)
            renderer.draw(canvas, lng, lat, w / 2f, 178f * unit, 132f * unit, w, HEIGHT.toFloat())
        }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        text.typeface = display
        text.fontVariationSettings = type.displayFace.variation
        text.color = c.ink.toArgb()
        // The title size relative to the theme's screen titles (Caveat 44 -> 46 on the card).
        text.textSize = 46f * (type.h1.value / 44f) * unit
        val head = headline(world.countries).let { if (tokens.type.displayUpper) it.uppercase() else it }
        canvas.drawText(head, w / 2f, 400f * unit, text)

        text.typeface = body
        text.fontVariationSettings = type.bodyFace.variation
        text.textSize = 13f * unit
        text.color = c.muted.toArgb()
        val sub = line(world)
        if (sub.isNotEmpty()) canvas.drawText(sub, w / 2f, 432f * unit, text)

        flags(canvas, world, tokens, palette, unit, top = 456f * unit, mono = mono)

        // The logo, on the theme's yellow, and the name at the foot.
        text.typeface = mono
        text.fontVariationSettings = null
        text.textSize = 12f * unit
        text.letterSpacing = 0.08f
        text.color = c.ink.toArgb()
        val name = "BORINGTRAVEL"
        val nameW = text.measureText(name)
        val markSize = (20f * unit).toInt()
        val gap = 8f * unit
        val total = markSize + gap + nameW
        val left = (w - total) / 2f
        val baseY = HEIGHT - 26f * unit
        PlaneMarkBitmap.draw(canvas, left, baseY - markSize * 0.82f, markSize.toFloat(), c.planeMark())
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(name, left + markSize + gap, baseY, text)
        return bmp
    }

    private fun paper(canvas: Canvas, tokens: BtTokens, unit: Float) {
        val dot = tokens.colors.paperDot ?: return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dot.toArgb() }
        when (tokens.paper) {
            Paper.Dots -> {
                // The page's own dots: 16 dp on Sticker Book, Market's fine 9 dp kraft fibres.
                val step = tokens.paperStep.value * unit
                val r = tokens.paperDotRadius.value * unit
                var y = step / 2
                while (y < HEIGHT) {
                    var x = step / 2
                    while (x < WIDTH) { canvas.drawCircle(x, y, r, p); x += step }
                    y += step
                }
            }
            Paper.Lines -> {
                p.strokeWidth = 0.8f * unit
                val step = tokens.paperStep.value * unit
                var y = step
                while (y < HEIGHT) { canvas.drawLine(0f, y, WIDTH.toFloat(), y, p); y += step }
                tokens.margin?.let { m ->
                    p.color = m.toArgb()
                    p.strokeWidth = 1.2f * unit
                    canvas.drawLine(14f * unit, 0f, 14f * unit, HEIGHT.toFloat(), p)
                }
            }
            Paper.Grid -> {
                p.strokeWidth = 0.8f * unit
                val step = 18f * unit
                var y = step
                while (y < HEIGHT) { canvas.drawLine(0f, y, WIDTH.toFloat(), y, p); y += step }
                var x = step
                while (x < WIDTH) { canvas.drawLine(x, 0f, x, HEIGHT.toFloat(), p); x += step }
            }
            Paper.Plain -> Unit
        }
    }

    /** Up to two rows of flags; past that, "+N". */
    private fun flags(canvas: Canvas, world: World, tokens: BtTokens, palette: GlobePalette, unit: Float, top: Float, mono: Typeface?) {
        val codes = world.visited.map { it.code }
        if (codes.isEmpty()) return
        val perRow = 8
        val size = (26f * unit).toInt()
        val gap = 6f * unit
        val maxShown = perRow * 2
        val shown = if (codes.size > maxShown) codes.take(maxShown - 1) else codes
        val extra = codes.size - shown.size
        val spec = FlagBadges.Spec(size, 2f * unit, 1.5f * unit, palette.pinTilt, palette.chip, palette.ink)
        val margin = FlagBadges.margin(spec)
        val slots = shown.size + if (extra > 0) 1 else 0
        val rows = (slots + perRow - 1) / perRow
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; typeface = mono; textSize = 10f * unit; color = tokens.colors.ink.toArgb()
        }
        var i = 0
        for (r in 0 until rows) {
            val inRow = minOf(perRow, slots - r * perRow)
            val rowW = inRow * size + (inRow - 1) * gap
            var x = (WIDTH - rowW) / 2f
            val y = top + r * (size + gap)
            repeat(inRow) {
                if (i < shown.size) {
                    val b = FlagBadges.badge(shown[i], spec)
                    canvas.drawBitmap(b, x - margin, y - margin, null)
                } else {
                    canvas.drawText("+$extra", x + size / 2f, y + size / 2f + text.textSize / 3f, text)
                }
                i++
                x += size + gap
            }
        }
    }

    /** cache/exports/boringtravel-world.png (shared through the app's FileProvider). */
    fun writeForSharing(context: Context, bitmap: Bitmap): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "boringtravel-world.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }

    /** Pictures/BoringTravel/<name>.png through MediaStore (no storage permission needed on Android 10+). */
    fun saveToPictures(context: Context, bitmap: Bitmap): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "BoringTravel world ${LocalDate.now()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BoringTravel")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("no stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
