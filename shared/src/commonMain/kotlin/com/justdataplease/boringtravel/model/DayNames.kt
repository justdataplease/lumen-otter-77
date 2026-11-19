package com.justdataplease.boringtravel.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.parseLocalDateOrNull

/**
 * A trip day's own name (owner, 16 Nov 2026: "how can the planner rename a day … not the plan items but the day"):
 * "Day 3 · Temples and tea" where the app would write the day's place ("Day 3 · Kyoto"). The traveller types it on the
 * day's header; the AI planner sets it when asked.
 *
 * Kept by date in the trip's portable JSON ([com.justdataplease.boringtravel.data.db.Trip.travelModes], beside the
 * travel choices): `"days": {"2026-05-04": "Temples and tea"}`. That text already travels as it is in backups and in
 * shared trips, and every version of the app since it exists keeps the fields it doesn't know through its own edits,
 * so day names need no new column and no new backup format, and a phone with an older version doesn't lose them.
 * By date, like the plan's items: when a trip moves and its items move with it, the names move too ([shift]).
 *
 * Pure and unit tested. A text that isn't a JSON object is read as no names and never rewritten.
 */
object DayNames {
    /** The longest name kept (the header has one line). */
    const val MAX = 40

    private const val KEY = "days"

    /** Every named day of the trip whose JSON is [raw]. */
    fun all(raw: String?): Map<LocalDate, String> {
        val days = root(raw)?.get(KEY) as? JsonObject ?: return emptyMap()
        return buildMap {
            for ((key, value) in days) {
                val day = parseLocalDateOrNull(key) ?: continue
                val name = clean((value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull) ?: continue
                put(day, name)
            }
        }
    }

    fun of(raw: String?, day: LocalDate): String? = all(raw)[day]

    /** [name] as it is kept: one line, single spaces, at most [MAX] characters (cut at a word); null when nothing is left. */
    fun clean(name: String?): String? {
        val t = name?.replace(Regex("[\\p{Cntrl}\\s]+"), " ")?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.length <= MAX) return t
        val cut = t.take(MAX)
        return cut.substringBeforeLast(' ', cut).trimEnd(',', ';', ':', '-', '–', '·', ' ').ifEmpty { cut }
    }

    /** [raw] with [day] named [name]; a blank name takes the day's name away (it shows its place again). */
    fun set(raw: String?, day: LocalDate, name: String?): String? = setAll(raw, mapOf(day to name))

    /** [raw] with each day of [names] named, or un-named by a blank name. The same text back when nothing changes. */
    fun setAll(raw: String?, names: Map<LocalDate, String?>): String? {
        if (names.isEmpty()) return raw
        val before = all(raw)
        val after = before.toMutableMap()
        for ((day, name) in names) clean(name)?.let { after[day] = it } ?: after.remove(day)
        return if (after == before) raw else write(raw, after)
    }

    /** [raw] with every name [days] later (earlier when negative): the trip moved and its plan with it. */
    fun shift(raw: String?, days: Long): String? {
        val before = all(raw)
        if (days == 0L || before.isEmpty()) return raw
        return write(raw, before.mapKeys { it.key.plusDays(days) })
    }

    private fun write(raw: String?, names: Map<LocalDate, String>): String? {
        val root = if (raw.isNullOrBlank()) JsonObject(emptyMap()) else root(raw) ?: return raw
        val next = root.toMutableMap()
        if (names.isEmpty()) next.remove(KEY)
        else next[KEY] = JsonObject(names.entries.sortedBy { it.key.toEpochDay() }.associate { it.key.toString() to JsonPrimitive(it.value) })
        return if (next.isEmpty()) null else JsonObject(next).toString()
    }

    private fun root(raw: String?): JsonObject? =
        if (raw.isNullOrBlank()) null else runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}
