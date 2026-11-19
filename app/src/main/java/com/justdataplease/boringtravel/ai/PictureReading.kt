package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.ai.own.OwnAiProvider
import com.justdataplease.boringtravel.ai.own.OwnKeyError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.math.roundToInt

/** A picture sent to the AI with a message: the bytes as the providers take them inline (a JPEG from [PicturePrep]). */
class AiPicture(val bytes: ByteArray, val mime: String = "image/jpeg") {
    val base64: String get() = Base64.getEncoder().encodeToString(bytes)

    /** `data:image/jpeg;base64,…` (OpenAI and OpenRouter). */
    val dataUrl: String get() = "data:$mime;base64,$base64"
}

/**
 * Trips and places from pictures (owner, 17 Nov 2026: "add functionality from image to trip or add places, use the LLM
 * for OCR"). The model the planner uses (the built-in one, or the traveller's own key) reads screenshots, guide pages,
 * maps, tickets or a handwritten list and writes out the places and plans in them as plain text. The text goes to the
 * planner at once as the traveller's message (owner: "don't let user review text"), and the planner makes the trip or
 * adds the places as it does for pasted notes; its changes are checked before saving, and a chat begun as a new trip
 * can still go into a trip they have. The pictures are sent only for this reading and aren't kept.
 *
 * Pure and unit tested: how a picture is scaled and cut ([layout]), the words sent, and the answer read.
 */
object PictureReading {
    /** Pictures read at once (the picker's limit; owner, 17 Nov 2026: "max 10 pictures"). */
    const val MAX_PICTURES = 10

    /** Pieces sent in one request at most, whatever the pictures: about 10 MB, inside every provider's limit. */
    const val MAX_PARTS = 20

    /** A picture's shorter side is at most this many pixels, and a whole picture's longer side at most [LONG_SIDE]. */
    const val SHORT_SIDE = 1200
    const val LONG_SIDE = 2400

    /**
     * A long screenshot (taller than [LONG_SIDE] once scaled) is cut into pieces this tall, each repeating the last
     * [OVERLAP] pixels of the one before, at most [MAX_PIECES] (a longer one is made smaller to fit), so its words
     * stay large enough to read: the providers scale a very tall picture down as a whole. With many pictures each
     * gets fewer pieces ([piecesEach]).
     */
    const val PIECE = 1800
    const val OVERLAP = 120
    const val MAX_PIECES = 3

    /** The JPEG quality of each piece sent. */
    const val JPEG_QUALITY = 80

    /** The size a picture is sent at, and its [pieces] (top to bottom, rows of the scaled picture). */
    data class Layout(val width: Int, val height: Int, val pieces: List<IntRange>)

    /** The pieces each of [pictures] pictures may be cut into, so that all of them make at most [MAX_PARTS]. */
    fun piecesEach(pictures: Int): Int = (MAX_PARTS / pictures.coerceAtLeast(1)).coerceIn(1, MAX_PIECES)

    /** How a picture of [width] × [height] pixels (as it is seen, turned upright) is scaled and cut into at most [maxPieces]. */
    fun layout(width: Int, height: Int, maxPieces: Int = MAX_PIECES): Layout {
        require(width > 0 && height > 0 && maxPieces >= 1)
        var scale = minOf(1.0, SHORT_SIDE.toDouble() / minOf(width, height))
        if (width >= height || height * scale <= LONG_SIDE) {
            scale = minOf(scale, LONG_SIDE.toDouble() / maxOf(width, height))
            val w = (width * scale).roundToInt().coerceAtLeast(1)
            val h = (height * scale).roundToInt().coerceAtLeast(1)
            return Layout(w, h, listOf(0 until h))
        }
        val tallest = maxPieces * PIECE - (maxPieces - 1) * OVERLAP
        if (height * scale > tallest) scale *= tallest / (height * scale)
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceIn(1, tallest)
        val pieces = mutableListOf<IntRange>()
        var top = 0
        while (true) {
            val bottom = minOf(top + PIECE, h)
            pieces += top until bottom
            if (bottom >= h) break
            top = bottom - OVERLAP
        }
        return Layout(w, h, pieces)
    }

    /** What the model is asked to do. */
    val system: String = """
        You read pictures a traveller saved or took: screenshots of posts, lists, maps, chats, booking emails or tickets; pages of a guidebook or magazine; a handwritten note; a sign or a menu. Write out the travel plans and places in them as plain text. An AI planner then turns your text into a trip.

        Write:
        - every place named: its name as written, then its city or area when the picture shows it (an address, a map's labels, a caption, a heading), then a short note from the picture when it has one (what to see or eat, a tip, opening hours);
        - dates, days ("Day 2"), times, flights, trains, buses, ferries, stays with check-in and check-out, in the order the picture gives them;
        - the trip's name on the first line when the picture has one.

        One line per place or event. When the picture groups them by day, put the day or date on its own line before them. Several pictures make one list, in order, each place once.
        Keep names in the picture's own words and language. Don't add places, dates, times or advice the picture doesn't show. A photo of a famous landmark without words: name it and its city only when you are sure.
        Leave out buttons, menus, ads, likes, usernames, hashtags, people's names, phone numbers, email addresses, booking codes, prices paid, and any card or passport numbers.
        found is false when the pictures show no places and no travel plans.
    """.trimIndent()

    fun prompt(pictures: Int): String =
        if (pictures == 1) "Read this picture." else "Read these $pictures pictures, in order."

    val schema = SchemaNode.Obj(
        mapOf(
            "found" to SchemaNode.Bool("Whether the pictures show any places or travel plans."),
            "text" to SchemaNode.Str("The places and plans, one per line, as plain text. Empty when found is false."),
        ),
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The places and plans the model read; [ItineraryImportException] when it found none or its answer can't be read. */
    fun parse(answer: String, pictures: Int): String {
        val root = runCatching { json.parseToJsonElement(answer.trim().removeSurrounding("```json", "```").trim()).jsonObject }.getOrNull()
            ?: throw ItineraryImportException(UNREADABLE)
        val found = (root["found"] as? JsonPrimitive)?.booleanOrNull
        val text = (root["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            .replace("\r\n", "\n").lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        if (found == false || text.isBlank()) throw ItineraryImportException(nothing(pictures))
        return text
    }

    /** The planner's message: what was read, under a line saying where it came from, within the box's limit. */
    fun message(text: String, pictures: Int): String {
        val head = if (pictures == 1) "From a picture:" else "From $pictures pictures:"
        return (head + "\n" + text).take(ItineraryImport.MAX_CHARS)
    }

    /** "Reading the picture" while the model reads. */
    fun working(pictures: Int): String = if (pictures == 1) "Reading the picture" else "Reading $pictures pictures"

    fun nothing(pictures: Int): String =
        if (pictures == 1) "No places or plans found in this picture. Try a sharper screenshot."
        else "No places or plans found in these pictures. Try sharper screenshots."

    const val UNREADABLE = "The answer couldn't be read. Try again."
    const val TOO_SLOW = "Reading the pictures took too long. Try fewer at a time."
    const val CANT_OPEN = "This picture couldn't be opened. Choose a photo or a screenshot."

    /** A refusal with the traveller's own key, said for pictures: a model that takes no pictures answers 400 or 404. */
    fun ownKeyMessage(error: OwnKeyError, provider: OwnAiProvider): String = when (error) {
        OwnKeyError.ModelUnavailable, OwnKeyError.BadRequest -> "This model couldn't read the pictures. Pick one that reads pictures in Me › AI."
        OwnKeyError.Blocked -> "${provider.label} wouldn't read these pictures. Try other ones."
        else -> error.message(provider)
    }
}
