package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.DraftSchema
import com.justdataplease.boringtravel.ai.tools.PlannerTools
import com.justdataplease.boringtravel.ai.own.JsonSchemas.Dialect
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The planner's tools in one answer (DraftSchema.generation) as each provider's structured output takes it (rules from their docs, 2026-11-11). */
class JsonSchemasTest {
    private val gemini = JsonSchemas.of(DraftSchema.generation, Dialect.Gemini)
    private val openAi = JsonSchemas.of(DraftSchema.generation, Dialect.OpenAi)
    private val claude = JsonSchemas.of(DraftSchema.generation, Dialect.Claude)

    private fun JsonObject.props() = getValue("properties").jsonObject
    private fun JsonObject.required() = getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
    private fun JsonObject.prop(vararg path: String): JsonObject = path.fold(this) { o, name ->
        if (name == "[]") o.getValue("items").jsonObject else o.props().getValue(name).jsonObject
    }

    /** Every object in the tree. */
    private fun objects(e: JsonElement): List<JsonObject> = when (e) {
        is JsonObject -> {
            val type = e["type"]
            val isObject = type == JsonPrimitive("object") || (type is JsonArray && JsonPrimitive("object") in type)
            (if (isObject) listOf(e) else emptyList()) + e.values.flatMap(::objects)
        }
        is JsonArray -> e.flatMap(::objects)
        else -> emptyList()
    }

    private fun typeArrays(e: JsonElement): Int = when (e) {
        is JsonObject -> (if (e["type"] is JsonArray) 1 else 0) + e.values.sumOf(::typeArrays)
        is JsonArray -> e.sumOf(::typeArrays)
        else -> 0
    }

    private fun optionalCount(e: JsonElement): Int = when (e) {
        is JsonObject -> {
            val here = if (e["properties"] is JsonObject) e.props().keys.size - e.required().size else 0
            here + e.values.sumOf(::optionalCount)
        }
        is JsonArray -> e.sumOf(::optionalCount)
        else -> 0
    }

    /** No draft field is called [key], so any object holding it uses it as a schema keyword. */
    private fun hasKey(e: JsonElement, key: String): Boolean = when (e) {
        is JsonObject -> key in e.keys || e.values.any { hasKey(it, key) }
        is JsonArray -> e.any { hasKey(it, key) }
        else -> false
    }

    @Test fun everyObjectClosesItsProperties() {
        for (schema in listOf(gemini, openAi, claude)) {
            val objs = objects(schema)
            assertTrue(objs.size >= 5) // the answer, set_trip, add_items + item, update_items + update, remove_items, finish
            objs.forEach { assertEquals(JsonPrimitive(false), it["additionalProperties"]) }
        }
    }

    @Test fun fieldNamesAreTheTools() {
        for (schema in listOf(gemini, openAi, claude)) {
            assertEquals(PlannerTools.oneShot.properties.keys, schema.props().keys)
            assertEquals(
                setOf("read_link", "set_trip", "add_items", "add_maps_list", "update_items", "remove_items", "ask_traveller", "copy_notes_from_link", "finish"),
                schema.props().keys,
            )
            assertEquals(PlannerTools.newItem.properties.keys, schema.prop("add_items", "items", "[]").props().keys)
            assertEquals(PlannerTools.itemUpdate.properties.keys, schema.prop("update_items", "updates", "[]").props().keys)
        }
    }

    @Test fun geminiLeavesOptionalFieldsOut() {
        assertEquals(setOf("finish"), gemini.required())
        val item = gemini.prop("add_items", "items", "[]")
        assertEquals(setOf("kind", "title"), item.required())
        assertEquals(JsonPrimitive("string"), item.prop("time")["type"])
        assertEquals(JsonPrimitive("number"), item.prop("lat")["type"])
        assertTrue(item.prop("kind").getValue("enum").jsonArray.isNotEmpty())
        assertEquals(JsonPrimitive("boolean"), gemini.prop("set_trip", "moveItemsWithDates")["type"])
        assertEquals(setOf("itemId"), gemini.prop("update_items", "updates", "[]").required())
    }

    @Test fun openAiRequiresEveryFieldAndAllowsNullForTheOptionalOnes() {
        objects(openAi).forEach { assertEquals(it.props().keys, it.required()) }
        val update = openAi.prop("update_items", "updates", "[]")
        assertEquals(JsonPrimitive("string"), update.prop("itemId")["type"])
        assertEquals(JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))), update.prop("date")["type"])
        assertEquals(JsonArray(listOf(JsonPrimitive("boolean"), JsonPrimitive("null"))), openAi.prop("set_trip", "moveItemsWithDates")["type"])
        assertEquals(JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))), openAi.prop("set_trip")["type"])
    }

    @Test fun claudeHasNoTypeArraysOrLimits() {
        // Claude gets this schema in its prompt (ClaudeRequests.draft): over 24 optional fields don't fit its strict format.
        assertEquals(0, typeArrays(claude))
        assertFalse(hasKey(claude, "maxItems"))
        assertTrue("optional properties: ${optionalCount(claude)}", optionalCount(claude) > 24)
        assertEquals(setOf("finish"), claude.required())
        assertEquals(setOf("itemId"), claude.prop("update_items", "updates", "[]").required())
        assertEquals(JsonPrimitive("number"), claude.prop("add_items", "items", "[]").prop("lat")["type"])
    }

    @Test fun descriptionsTravelWithTheFields() {
        val title = PlannerTools.newItem.properties.getValue("title").description
        for (schema in listOf(gemini, openAi, claude)) {
            assertEquals(JsonPrimitive(title), schema.prop("add_items", "items", "[]").prop("title")["description"])
        }
    }
}
