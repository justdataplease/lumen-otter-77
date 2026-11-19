package com.justdataplease.boringtravel.ai

import com.anthropic.core.jsonMapper
import com.justdataplease.boringtravel.ai.own.ClaudeRequests
import com.justdataplease.boringtravel.ai.own.GeminiRest
import com.justdataplease.boringtravel.ai.own.JsonSchemas
import com.justdataplease.boringtravel.ai.own.OpenAiRest
import com.justdataplease.boringtravel.ai.own.OpenRouterRest
import com.justdataplease.boringtravel.ai.own.OwnAiProvider
import com.justdataplease.boringtravel.ai.own.OwnKeyError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Trips and places from pictures (owner, 17 Nov 2026: "from image to trip or add places, use the LLM for OCR"): how a
 * picture is scaled and cut, what is asked, the answer read, and each provider's request with the picture in it.
 */
class PictureReadingTest {
    private val picture = AiPicture(byteArrayOf(1, 2, 3))

    @Test fun aPhoneScreenshotGoesWhole() {
        val l = PictureReading.layout(1080, 2400)
        assertEquals(PictureReading.Layout(1080, 2400, listOf(0 until 2400)), l)
    }

    @Test fun aLargePhotoIsScaledDownToItsShortSide() {
        assertEquals(PictureReading.Layout(1600, 1200, listOf(0 until 1200)), PictureReading.layout(4000, 3000))
        // A wide panorama: its long side at most 2400, nothing cut.
        val wide = PictureReading.layout(12000, 2000)
        assertEquals(2400, wide.width)
        assertEquals(1, wide.pieces.size)
        // A small picture isn't enlarged.
        assertEquals(PictureReading.Layout(300, 200, listOf(0 until 200)), PictureReading.layout(300, 200))
    }

    @Test fun aLongScreenshotIsCutIntoOverlappingPieces() {
        val l = PictureReading.layout(1080, 3000)
        assertEquals(1080, l.width)
        assertEquals(listOf(0 until 1800, 1680 until 3000), l.pieces)
        // Each piece repeats the last rows of the one before, so no line is lost at a cut.
        assertEquals(PictureReading.OVERLAP, l.pieces[0].last + 1 - l.pieces[1].first)
    }

    @Test fun aVeryLongScreenshotIsMadeSmallerToFitThreePieces() {
        val l = PictureReading.layout(1080, 6000)
        assertEquals(PictureReading.MAX_PIECES, l.pieces.size)
        assertEquals(5160, l.height)
        assertEquals(929, l.width)
        assertEquals(l.height - 1, l.pieces.last().last)
        l.pieces.forEach { assertTrue(it.last - it.first + 1 <= PictureReading.PIECE) }
    }

    @Test fun tenPicturesStayInsideOneRequest() {
        assertEquals(3, PictureReading.piecesEach(1))
        assertEquals(3, PictureReading.piecesEach(6))
        assertEquals(2, PictureReading.piecesEach(10))
        assertTrue(PictureReading.MAX_PICTURES * PictureReading.piecesEach(PictureReading.MAX_PICTURES) <= PictureReading.MAX_PARTS)
        // With two pieces each, a very long screenshot is made smaller to fit two.
        val l = PictureReading.layout(1080, 6000, maxPieces = 2)
        assertEquals(2, l.pieces.size)
        assertEquals(3480, l.height)
    }

    @Test fun theAnswerIsTheTextUnderALineSayingWhereItCameFrom() {
        val text = PictureReading.parse("""{"found":true,"text":"Day 1  \nAlcázar, Seville\n\n\n\nTriana market, Seville"}""", 1)
        assertEquals("Day 1\nAlcázar, Seville\n\nTriana market, Seville", text)
        assertEquals("From a picture:\n$text", PictureReading.message(text, 1))
        assertTrue(PictureReading.message("x", 3).startsWith("From 3 pictures:\n"))
        assertEquals(ItineraryImport.MAX_CHARS, PictureReading.message("y".repeat(40_000), 1).length)
        // An answer in a code fence still reads.
        assertEquals("Prado, Madrid", PictureReading.parse("```json\n{\"found\":true,\"text\":\"Prado, Madrid\"}\n```", 1))
    }

    @Test fun nothingFoundOrAnUnreadableAnswerIsSaid() {
        fun fails(answer: String, count: Int): String = try {
            PictureReading.parse(answer, count)
            fail("read $answer")
            ""
        } catch (e: ItineraryImportException) {
            e.message.orEmpty()
        }
        assertEquals(PictureReading.nothing(1), fails("""{"found":false,"text":""}""", 1))
        assertEquals(PictureReading.nothing(2), fails("""{"found":true,"text":"   "}""", 2))
        assertEquals(PictureReading.UNREADABLE, fails("Sorry, I can't.", 1))
    }

    @Test fun askedToLeaveOutPersonalDetails() {
        val s = PictureReading.system
        listOf("booking codes", "phone numbers", "card or passport numbers", "Don't add places").forEach { assertTrue(it, it in s) }
        assertEquals(setOf("found", "text"), PictureReading.schema.properties.keys)
        assertEquals("Read this picture.", PictureReading.prompt(1))
    }

    @Test fun aModelThatTakesNoPicturesIsSaidPlainly() {
        assertTrue("reads pictures" in PictureReading.ownKeyMessage(OwnKeyError.ModelUnavailable, OwnAiProvider.OpenRouter))
        assertTrue("reads pictures" in PictureReading.ownKeyMessage(OwnKeyError.BadRequest, OwnAiProvider.OpenRouter))
        assertEquals(OwnKeyError.WrongKey.message(OwnAiProvider.Claude), PictureReading.ownKeyMessage(OwnKeyError.WrongKey, OwnAiProvider.Claude))
    }

    private val schema = JsonSchemas.of(PictureReading.schema, JsonSchemas.Dialect.Gemini)
    private val turn = ChatTurn(true, "Read this picture.", listOf(picture))

    @Test fun geminiGetsThePictureInlineBeforeTheWords() {
        val parts = GeminiRest.draftBody("RULES", listOf(turn), schema)["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        val inline = parts[0].jsonObject.getValue("inlineData").jsonObject
        assertEquals("image/jpeg", inline.getValue("mimeType").jsonPrimitive.content)
        assertEquals("AQID", inline.getValue("data").jsonPrimitive.content)
        assertEquals("Read this picture.", parts[1].jsonObject.getValue("text").jsonPrimitive.content)
        // A message without pictures is as before: one text part.
        val plain = GeminiRest.draftBody("RULES", listOf(ChatTurn(true, "Rome")), schema)["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(1, plain.size)
    }

    @Test fun chatGptGetsAnInputImage() {
        val content = OpenAiRest.draftBody("gpt-6-astra", "RULES", listOf(turn), schema)["input"]!!.jsonArray[0].jsonObject.getValue("content").jsonArray
        assertEquals("input_text", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        val image = content[1].jsonObject
        assertEquals("input_image", image.getValue("type").jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AQID", image.getValue("image_url").jsonPrimitive.content)
        val plain = OpenAiRest.draftBody("gpt-6-astra", "RULES", listOf(ChatTurn(true, "Rome")), schema)["input"]!!.jsonArray[0].jsonObject.getValue("content")
        assertTrue(plain is JsonPrimitive)
    }

    @Test fun openRouterGetsAnImageUrl() {
        val messages = OpenRouterRest.draftBody("google/gemini-flash", "RULES", listOf(turn), schema)["messages"]!!.jsonArray
        val content = messages[1].jsonObject.getValue("content").jsonArray
        assertEquals("text", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AQID", content[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
    }

    @Test fun claudeGetsABase64ImageBlockBeforeTheWords() {
        val params = ClaudeRequests.draft("claude-sonnet-5-5", "RULES", listOf(turn), JsonSchemas.of(PictureReading.schema, JsonSchemas.Dialect.Claude))
        val message = Json.parseToJsonElement(jsonMapper().writeValueAsString(params.messages().single())).jsonObject
        val blocks = message.getValue("content").jsonArray
        val image = blocks[0].jsonObject
        assertEquals("image", image.getValue("type").jsonPrimitive.content)
        val source = image.getValue("source").jsonObject
        assertEquals("base64", source.getValue("type").jsonPrimitive.content)
        assertEquals("image/jpeg", source.getValue("media_type").jsonPrimitive.content)
        assertEquals("AQID", source.getValue("data").jsonPrimitive.content)
        assertEquals("Read this picture.", blocks[1].jsonObject.getValue("text").jsonPrimitive.content)
        assertFalse("pictures" in message.toString())
    }
}
