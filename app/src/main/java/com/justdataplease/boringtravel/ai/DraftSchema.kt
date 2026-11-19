package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.ai.tools.PlannerTools

/**
 * A JSON schema as a plain tree, so it can be unit-tested on the JVM and written for each provider: Firebase AI
 * Logic's `Schema` ([GeminiPlanner]) and plain JSON Schema ([com.justdataplease.boringtravel.ai.own.JsonSchemas]).
 * Properties not listed in `optional` are required.
 */
sealed interface SchemaNode {
    val description: String?

    data class Obj(
        val properties: Map<String, SchemaNode>,
        val optional: List<String> = emptyList(),
        override val description: String? = null,
    ) : SchemaNode

    data class Arr(val items: SchemaNode, val maxItems: Int? = null, override val description: String? = null) : SchemaNode
    data class Str(override val description: String? = null, val nullable: Boolean = false) : SchemaNode
    data class Choice(val values: List<String>, override val description: String? = null) : SchemaNode
    data class Integer(override val description: String? = null, val nullable: Boolean = false) : SchemaNode
    data class Decimal(override val description: String? = null, val nullable: Boolean = false) : SchemaNode
    data class Bool(override val description: String? = null) : SchemaNode
}

/**
 * The structured answer of a provider without the tool loop (Claude and ChatGPT keys): the planner's tools in one
 * answer, each field one tool's arguments ([PlannerTools.oneShot]).
 */
object DraftSchema {
    val generation: SchemaNode.Obj get() = PlannerTools.oneShot
}
