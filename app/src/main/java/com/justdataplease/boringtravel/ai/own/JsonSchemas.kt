package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.SchemaNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * [com.justdataplease.boringtravel.ai.DraftSchema] as plain JSON Schema for each provider's structured output. They
 * differ only in how a field may be left out (checked against each provider's docs on 2026-11-11):
 * - Gemini (`responseJsonSchema`): optional fields are left out of `required`; nullable ones take `["string", "null"]`.
 * - OpenAI (strict `json_schema`): every field is required, so optional or nullable ones take `[type, "null"]`.
 * - Claude (`output_config.format`): at most 16 union types per request, so optional or nullable fields are simply
 *   left out of `required` (the parser reads a missing field as null) and no type arrays are sent; no `maxItems`.
 * Every object says `additionalProperties: false` (required by OpenAI and Claude, accepted by Gemini).
 */
object JsonSchemas {
    enum class Dialect { Gemini, OpenAi, Claude }

    fun of(node: SchemaNode, dialect: Dialect): JsonObject = node(node, dialect, union = false)

    /**
     * Test's one-field answer (`{"reply": "OK"}`), sent with the same structured-output setting as a draft, so a model
     * that answers it can also answer the planner. Valid in every dialect.
     */
    val ping: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("reply", buildJsonObject { put("type", "string") }) })
        putJsonArray("required") { add(JsonPrimitive("reply")) }
        put("additionalProperties", false)
    }

    /** [union]: the field may be null, sent as a `[type, "null"]` type array (never for Claude). */
    private fun node(node: SchemaNode, dialect: Dialect, union: Boolean): JsonObject = buildJsonObject {
        fun type(name: String) = if (union) put("type", buildJsonArray { add(JsonPrimitive(name)); add(JsonPrimitive("null")) }) else put("type", name)
        when (node) {
            is SchemaNode.Obj -> {
                type("object")
                node.description?.let { put("description", it) }
                put("properties", buildJsonObject {
                    node.properties.forEach { (name, child) ->
                        val childUnion = when (dialect) {
                            Dialect.Gemini -> child.isNullable
                            Dialect.OpenAi -> name in node.optional || child.isNullable
                            Dialect.Claude -> false
                        }
                        put(name, node(child, dialect, childUnion))
                    }
                })
                putJsonArray("required") {
                    node.properties.forEach { (name, child) ->
                        val required = when (dialect) {
                            Dialect.OpenAi -> true
                            Dialect.Gemini -> name !in node.optional
                            Dialect.Claude -> name !in node.optional && !child.isNullable
                        }
                        if (required) add(JsonPrimitive(name))
                    }
                }
                put("additionalProperties", false)
            }
            is SchemaNode.Arr -> {
                type("array")
                node.description?.let { put("description", it) }
                put("items", node(node.items, dialect, union = false))
                if (dialect != Dialect.Claude) node.maxItems?.let { put("maxItems", it) }
            }
            is SchemaNode.Str -> {
                type("string")
                node.description?.let { put("description", it) }
            }
            is SchemaNode.Choice -> {
                type("string")
                node.description?.let { put("description", it) }
                put("enum", JsonArray(node.values.map(::JsonPrimitive) + if (union) listOf(JsonNull) else emptyList()))
            }
            is SchemaNode.Integer -> {
                type("integer")
                node.description?.let { put("description", it) }
            }
            is SchemaNode.Decimal -> {
                type("number")
                node.description?.let { put("description", it) }
            }
            is SchemaNode.Bool -> {
                type("boolean")
                node.description?.let { put("description", it) }
            }
        }
    }

    private val SchemaNode.isNullable: Boolean
        get() = when (this) {
            is SchemaNode.Str -> nullable
            is SchemaNode.Integer -> nullable
            is SchemaNode.Decimal -> nullable
            else -> false
        }
}
