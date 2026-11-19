package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.startOfDayMs
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/*
 * Notes with photos for the screenshots and the tests: pictures drawn here (no photo files in git), and a note shaped
 * the way the Android editor writes one (its rich-text library's HTML, a photo layout, a checklist).
 */

/** The scenes [samplePhoto] draws: sky, sea or land, and a sun, in a few palettes. */
enum class Scene(val sky: Int, val horizon: Int, val ground: Int, val sun: Int) {
    Sunset(0xFF3B2E5A.toInt(), 0xFFF29A5B.toInt(), 0xFF2B3A55.toInt(), 0xFFFFD27A.toInt()),
    Sea(0xFF5EA8D8.toInt(), 0xFFBFE3F2.toInt(), 0xFF1F6F99.toInt(), 0xFFFFF4C2.toInt()),
    Olive(0xFF9CC9E8.toInt(), 0xFFEFE6C8.toInt(), 0xFF6E7F3A.toInt(), 0xFFFFFFFF.toInt()),
    Town(0xFF7FB3E0.toInt(), 0xFFF4F1EA.toInt(), 0xFFE9E1D3.toInt(), 0xFFFFE9A8.toInt()),
    Night(0xFF0E1A33.toInt(), 0xFF27406B.toInt(), 0xFF101820.toInt(), 0xFFF5F0D0.toInt()),
}

/** A made-up photo, [w] × [h], as a JPEG: a sky, a horizon, the ground or sea, a sun (and white houses in [Scene.Town]). */
fun samplePhoto(scene: Scene, w: Int = 1200, h: Int = 900): ByteArray {
    val surface = Surface.makeRasterN32Premul(w, h)
    val canvas = surface.canvas
    val horizon = h * 0.62f
    /** A colour fading from [from] to [to] between [top] and [bottom], in thin bands. */
    fun fade(top: Float, bottom: Float, from: Int, to: Int) {
        val steps = 48
        for (i in 0 until steps) {
            val t = i / (steps - 1f)
            fun mix(shift: Int) = (((from shr shift) and 0xFF) * (1 - t) + ((to shr shift) and 0xFF) * t).toInt()
            val y0 = top + (bottom - top) * i / steps
            val y1 = top + (bottom - top) * (i + 1) / steps + 1f
            canvas.drawRect(Rect.makeLTRB(0f, y0, w.toFloat(), y1), Paint().apply { color = Color.makeARGB(255, mix(16), mix(8), mix(0)) })
        }
    }
    fade(0f, horizon, scene.sky, scene.horizon)
    canvas.drawCircle(w * 0.68f, horizon * 0.62f, h * 0.09f, Paint().apply { color = scene.sun })
    fade(horizon, h.toFloat(), scene.ground, Color.makeARGB(255, 20, 24, 30))
    if (scene == Scene.Town) {
        val wall = Paint().apply { color = 0xFFFFFFFF.toInt() }
        val door = Paint().apply { color = 0xFF2F6DB5.toInt() }
        for (i in 0 until 6) {
            val x = w * (0.05f + i * 0.15f)
            val top = horizon - h * (0.12f + (i % 3) * 0.05f)
            canvas.drawRect(Rect.makeLTRB(x, top, x + w * 0.12f, horizon + h * 0.05f), wall)
            canvas.drawRect(Rect.makeLTRB(x + w * 0.045f, horizon - h * 0.06f, x + w * 0.075f, horizon + h * 0.05f), door)
        }
    } else {
        val line = Paint().apply { color = Color.makeARGB(60, 255, 255, 255) }
        for (i in 1..4) canvas.drawRect(Rect.makeLTRB(w * 0.1f * i, horizon + h * 0.04f * i, w * (0.1f * i + 0.25f), horizon + h * 0.04f * i + 3f), line)
    }
    return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 88)!!.bytes
}

/** Puts [photo] into [kit]'s files as the importer would, its row in [book]: a note's photo, ready to place. */
fun addSamplePhoto(book: TripBook, kit: MediaKit, photo: ByteArray, tripId: String?, entryId: String?, ids: () -> String, ext: String = "jpg", mime: String = MediaTypes.JPEG): Media {
    val picked = "picked/${ids()}.$ext"
    kit.files.write(picked, photo)
    return book.addMedia(MediaImporter(kit.files, ids = ids).prepare(PickedMedia(picked, mime, width = 1280, height = 720, durationMs = 12_000), tripId, entryId))
}

/**
 * A note as the Android editor writes one, on a trip's third day: a heading, formatted text (bold, italic, a colour,
 * larger text, a link), bullets and numbers, a quote, a photo layout of three in polaroids, a taped photo with a caption,
 * a checklist, a place, a line, and a block from a newer app. Its photos are drawn and kept in [kit].
 */
fun richNote(book: TripBook, kit: MediaKit, tripId: String, zoneId: String, at: Long, ids: () -> String): Entry {
    val id = RICH_NOTE
    val p1 = addSamplePhoto(book, kit, samplePhoto(Scene.Town), tripId, id, ids)
    val p2 = addSamplePhoto(book, kit, samplePhoto(Scene.Sea, 900, 1200), tripId, id, ids)
    val p3 = addSamplePhoto(book, kit, samplePhoto(Scene.Olive), tripId, id, ids)
    val p4 = addSamplePhoto(book, kit, samplePhoto(Scene.Sunset), tripId, id, ids)
    val doc = EntryDoc(
        blocks = listOf(
            Block.Paragraph("Up before the buses. The path to <b>Fushimi Inari</b> was empty, only the <i>crows</i> and a man sweeping."),
            Block.Heading("Fushimi Inari", 3),
            Block.Paragraph("<span style=\"font-size: 1.3em\">Ten thousand gates</span>, and every one <span style=\"color: rgba(196, 98, 45, 1.0)\">orange</span>.<br>We stopped counting at the fox shrine."),
            Block.Paragraph("Matcha at the top", ListStyle.Bullet),
            Block.Paragraph("Inari sushi for the walk down", ListStyle.Bullet),
            Block.Album("3-top", listOf(p1.id, p2.id, p3.id), mapOf(p1.id to PhotoFrame.Polaroid, p2.id to PhotoFrame.Polaroid, p3.id to PhotoFrame.Polaroid)),
            Block.Quote("Go slowly, the mountain isn&#39;t going anywhere."),
            Block.Paragraph("Train to Nara", ListStyle.Number),
            Block.Paragraph("Deer biscuits", ListStyle.Number),
            Block.Photo(p4.id, rotation = -3f, width = 0.85f, frame = PhotoFrame.Tape, caption = "Last light on the Kamo"),
            Block.Checklist(listOf(CheckItem("Ryokan booked", done = true), CheckItem("JR pass"), CheckItem("Postcards"))),
            Block.Place("Fushimi Inari Taisha", 34.9671, 135.7727),
            Block.Divider,
            Block.Paragraph("More on <a href=\"https://www.inari.jp/en/\">the shrine's site</a>."),
        ),
    )
    val entry = Entry(id, tripId, occurredAt = at, zoneId = zoneId, title = "A thousand gates", placeName = "Fushimi, Kyoto", tempC = 14.0, weather = "clear", createdAt = 0L, updatedAt = 0L)
    return book.saveNote(entry, doc)!!
}

/** The ids of the two sample notes with photos. */
const val RICH_NOTE = "note-rich"
const val PHOTO_NOTE = "note-photos"

/** A short note with a photo, a layout of two and a video, as the editor makes them: for the editor's photo tools. */
fun photoNote(book: TripBook, kit: MediaKit, tripId: String, zoneId: String, at: Long, ids: () -> String): Entry {
    val p1 = addSamplePhoto(book, kit, samplePhoto(Scene.Night), tripId, PHOTO_NOTE, ids)
    val p2 = addSamplePhoto(book, kit, samplePhoto(Scene.Sea), tripId, PHOTO_NOTE, ids)
    val p3 = addSamplePhoto(book, kit, samplePhoto(Scene.Town, 900, 1200), tripId, PHOTO_NOTE, ids)
    val video = addSamplePhoto(book, kit, ByteArray(4096) { (it % 251).toByte() }, tripId, PHOTO_NOTE, ids, ext = "mov", mime = "video/quicktime")
    val doc = EntryDoc(
        font = "caveat",
        blocks = listOf(
            Block.Paragraph("Lanterns on the Kamo after dark."),
            Block.Photo(p1.id, frame = PhotoFrame.Rounded),
            Block.Album("2-side", listOf(p2.id, p3.id)).withFrame(PhotoFrame.Rounded),
            Block.Photo(video.id, frame = PhotoFrame.Rounded),
        ),
    )
    return book.saveNote(Entry(PHOTO_NOTE, tripId, occurredAt = at, zoneId = zoneId, title = "Pontocho", placeName = "Pontocho, Kyoto", createdAt = 0L, updatedAt = 0L), doc)!!
}

/** [sampleBook] with the two notes with photos on the trip that is on, and the kit their photos are in. */
fun richSampleBook(): Pair<TripBook, MediaKit> {
    var n = 0
    val book = sampleBook()
    val kit = MediaKit.memory()
    val trip = book.sections(SAMPLE_TODAY).now!!
    val zone = "Asia/Tokyo"
    richNote(book, kit, trip.id, zone, startOfDayMs(SAMPLE_TODAY.minusDays(1), zone) + 7 * 3_600_000L + 40 * 60_000L) { "sample-photo-${n++}" }
    photoNote(book, kit, trip.id, zone, startOfDayMs(SAMPLE_TODAY.minusDays(1), zone) + 21 * 3_600_000L) { "sample-photo-${n++}" }
    return book to kit
}
