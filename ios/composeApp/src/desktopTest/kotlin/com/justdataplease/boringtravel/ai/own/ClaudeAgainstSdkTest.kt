package com.justdataplease.boringtravel.ai.own

import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.MessageCreateParams
import com.justdataplease.boringtravel.ai.AiPicture
import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.PictureReading
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import com.justdataplease.boringtravel.androidref.ai.own.ClaudeRequests as AndroidRequests

/**
 * This phone's Claude requests against the Android app's: its ai/own/ClaudeKeyApi.kt, compiled as it is with Anthropic's
 * Java SDK (build.gradle.kts androidReferenceSources), builds each request with the SDK's types; the body the SDK
 * would send is the very JSON this app sends (keys in any order), and the betas are the same.
 */
class ClaudeAgainstSdkTest {
    private val mapper = jsonMapper()
    private val turns = listOf(ChatTurn(true, "Rome in May"), ChatTurn(false, "{\"summary\":\"Dates?\"}"), ChatTurn(true, "1 to 4 May"))

    private fun sdk(params: MessageCreateParams): JsonObject = Json.parseToJsonElement(mapper.writeValueAsString(params._body())).jsonObject

    private fun betas(params: MessageCreateParams): List<String> = params.betas().orElse(emptyList()).map { it.asString() }

    @Test fun theDraftIsTheBodyTheSdkSends() {
        for (model in listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-sonnet-4-6", "claude-3-5-haiku-latest", "my-lab/typed-model")) {
            val theirs = AndroidRequests.draft(model, "RULES", turns)
            val mine = ClaudeRequests.draft(model, "RULES", turns)
            assertEquals(sdk(theirs), mine.body, model)
            assertEquals(betas(theirs), mine.betas, model)
        }
    }

    @Test fun picturesAndAnAnswersSchemaAreTheSdksToo() {
        val schema = JsonSchemas.of(PictureReading.schema, JsonSchemas.Dialect.Claude)
        val picture = listOf(ChatTurn(true, "Read these 2 pictures, in order.", listOf(AiPicture(byteArrayOf(1, 2, 3)), AiPicture(byteArrayOf(4, 5), "image/png"))))
        val theirs = AndroidRequests.draft("claude-sonnet-5-5", PictureReading.system, picture, schema)
        val mine = ClaudeRequests.draft("claude-sonnet-5-5", PictureReading.system, picture, schema)
        assertEquals(sdk(theirs), mine.body)
    }

    @Test fun thePingIsTheSdks() {
        for (model in listOf("claude-sonnet-5-5", "claude-haiku-5", "claude-3-5-haiku-latest")) {
            val theirs = AndroidRequests.ping(model)
            val mine = ClaudeRequests.ping(model)
            assertEquals(sdk(theirs), mine.body, model)
            assertEquals(betas(theirs), mine.betas, model)
        }
    }
}
