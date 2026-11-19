package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

enum class RouteLegSource(val id: String) { Planned("planned"), Stops("stops") }

/** Identifies a directed adjacency, never a list position or a place-name spelling. */
data class RouteLegKey(val source: RouteLegSource, val fromId: String, val toId: String) {
    /** The plan items at each end: an end's stop ("x~end", [PlanSpan.endId]) stands for its item. */
    val fromItemId: String get() = PlanSpan.itemIdOf(fromId)
    val toItemId: String get() = PlanSpan.itemIdOf(toId)
}

data class RouteLeg(
    val key: RouteLegKey,
    val fromTitle: String,
    val toTitle: String,
    val fromDay: LocalDate?,
    val toDay: LocalDate?,
    val vehicle: Vehicle?,
    /**
     * Both ends are items of one place on one day, near each other ([com.justdataplease.boringtravel.model.TravelDefault.samePlace]):
     * unchosen, the leg is walked.
     */
    val samePlace: Boolean = false,
    /** Straight-line length; unchosen legs of [TravelDefault.FLY_MIN_M] or more fly. NaN when unknown (saved stops). */
    val distanceM: Double = Double.NaN,
    /** The destination is a travel item ("Athens → Madrid flight"): its name says how wherever it says it. */
    val toTravel: Boolean = false,
    /**
     * The traveller's own "Count in km" for this leg; null = the default ([TravelDefault.countsKm]: every leg but a
     * flight counts).
     */
    val countKm: Boolean? = null,
)

/** Portable choices stored as opaque JSON in Trip; unknown fields/modes survive unrelated edits and backups. */
object TravelModes {
    private const val VERSION = 1

    fun choices(raw: String?): Map<RouteLegKey, Vehicle> {
        val root = runCatching { parse(raw) }.getOrNull() ?: return emptyMap()
        if (version(root) != VERSION) return emptyMap()
        val rows = root["legs"] as? JsonArray ?: return emptyMap()
        return buildMap {
            for (element in rows) {
                val row = element as? JsonObject ?: continue
                val key = parseKey(row) ?: continue
                val id = (row["vehicle"] as? JsonPrimitive)?.contentOrNull
                val mode = Vehicle.entries.firstOrNull { it.id == id && it != Vehicle.Auto }
                if (mode == null) remove(key) else put(key, mode)
            }
        }
    }

    fun set(raw: String?, key: RouteLegKey, vehicle: Vehicle?): String? {
        val explicit = vehicle?.takeUnless { it == Vehicle.Auto }
        return edit(raw, key, clears = explicit == null) { row ->
            if (explicit == null) row.remove("vehicle") else row["vehicle"] = JsonPrimitive(explicit.id)
        }
    }

    /**
     * The legs' own "Count in km" (a row's "km" field; owner, 2026-11-11: "a count in km in every item"). A leg without
     * one counts by [TravelDefault.countsKm].
     */
    fun kmChoices(raw: String?): Map<RouteLegKey, Boolean> {
        val root = runCatching { parse(raw) }.getOrNull() ?: return emptyMap()
        if (version(root) != VERSION) return emptyMap()
        val rows = root["legs"] as? JsonArray ?: return emptyMap()
        return buildMap {
            for (element in rows) {
                val row = element as? JsonObject ?: continue
                val key = parseKey(row) ?: continue
                val count = (row["km"] as? JsonPrimitive)?.booleanOrNull
                if (count == null) remove(key) else put(key, count)
            }
        }
    }

    /** Saves whether the leg counts in km; null goes back to the default. The row's vehicle and other fields stay. */
    fun setKm(raw: String?, key: RouteLegKey, count: Boolean?): String? =
        edit(raw, key, clears = count == null) { row -> if (count == null) row.remove("km") else row["km"] = JsonPrimitive(count) }

    /** Changes the row of [key] (made when missing, unless the change only [clears] a field); unknown fields stay. */
    private fun edit(raw: String?, key: RouteLegKey, clears: Boolean, change: (MutableMap<String, JsonElement>) -> Unit): String? {
        require(key.fromId.isNotBlank() && key.toId.isNotBlank() && key.fromId != key.toId) { "A route leg needs two distinct destinations." }
        val root = parse(raw)
        check(version(root) == VERSION) { "These travel choices were saved by a newer app. Update the app before changing them." }
        val originalRows = root["legs"]
        check(originalRows == null || originalRows is JsonArray) { "These travel choices could not be read. Restore a valid backup before changing them." }
        val rows = originalRows ?: JsonArray(emptyList())
        val existing = rows.filterIsInstance<JsonObject>().lastOrNull { parseKey(it) == key }
        if (existing == null && clears) return raw
        val replacement = (existing ?: JsonObject(emptyMap())).toMutableMap().apply {
            put("source", JsonPrimitive(key.source.id)); put("from", JsonPrimitive(key.fromId)); put("to", JsonPrimitive(key.toId))
            change(this)
        }
        val next = rows.filterNot { it is JsonObject && parseKey(it) == key } + JsonObject(replacement)
        return JsonObject(root.toMutableMap().apply {
            put("v", JsonPrimitive(VERSION)); put("legs", JsonArray(next))
        }).toString()
    }

    private fun parse(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return JsonObject(emptyMap())
        return (Json.parseToJsonElement(raw) as? JsonObject)
            ?: error("These travel choices could not be read. Restore a valid backup before changing them.")
    }

    private fun version(root: JsonObject): Int = if ("v" !in root) VERSION else (root["v"] as? JsonPrimitive)?.intOrNull ?: -1
    private fun parseKey(row: JsonObject): RouteLegKey? {
        fun text(name: String) = (row[name] as? JsonPrimitive)?.contentOrNull
        val source = RouteLegSource.entries.firstOrNull { it.id == text("source") } ?: return null
        val from = text("from")?.takeIf { it.isNotBlank() } ?: return null
        val to = text("to")?.takeIf { it.isNotBlank() && it != from } ?: return null
        return RouteLegKey(source, from, to)
    }
}
