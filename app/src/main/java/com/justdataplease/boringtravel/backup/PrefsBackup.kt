package com.justdataplease.boringtravel.backup

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The trip video's remembered choices in a backup (owner, 2026-11-11: backups hold "styles … everything"): a small
 * settings file of the phone as one JSON text, {"key":{"t":"s","v":"…"}}. A restore the traveller asked for puts the
 * file back as the backup had it ([replace]; owner, 2026-11-14); [fill] only adds the keys this phone doesn't have.
 * Unknown types and damaged text are skipped.
 */
object PrefsBackup {
    /** The video's speed, length, shape and style (clip/ClipPrefs). */
    const val CLIP = "clip"

    /** The map look of the trip's map and video (clip/BaseMap MapStylePrefs). */
    const val MAP_STYLE = "map_style"

    /** The video's music and "on the beat" (clip/music/MusicPrefs). */
    const val MUSIC = "clip_music"

    /** Bigger than any of these files gets; a text longer than this isn't kept or read. */
    const val MAX_CHARS = 200_000

    private val json = Json { ignoreUnknownKeys = true }

    /** [values] (a SharedPreferences' contents) as JSON text, or null when there is nothing to keep. */
    fun encode(values: Map<String, Any?>): String? {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in values.toSortedMap()) {
            val (type, v) = when (value) {
                is String -> "s" to JsonPrimitive(value)
                is Int -> "i" to JsonPrimitive(value)
                is Long -> "l" to JsonPrimitive(value)
                is Float -> "f" to JsonPrimitive(value)
                is Boolean -> "b" to JsonPrimitive(value)
                is Set<*> -> "ss" to JsonArray(value.filterIsInstance<String>().sorted().map(::JsonPrimitive))
                else -> continue
            }
            out[key] = JsonObject(mapOf("t" to JsonPrimitive(type), "v" to v))
        }
        if (out.isEmpty()) return null
        return JsonObject(out).toString().takeIf { it.length <= MAX_CHARS }
    }

    /** The values in [text], typed as they were kept; empty for null, damaged or oversized text. */
    fun decode(text: String?): Map<String, Any> {
        if (text.isNullOrBlank() || text.length > MAX_CHARS) return emptyMap()
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for ((key, element) in root) {
            val o = element as? JsonObject ?: continue
            val type = (o["t"] as? JsonPrimitive)?.contentOrNull ?: continue
            val v = o["v"] ?: continue
            val value: Any? = when (type) {
                "s" -> (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                "i" -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                "l" -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                "f" -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull
                "b" -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                "ss" -> (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }?.toSet()
                else -> null
            }
            if (value != null) out[key] = value
        }
        return out
    }

    /** The settings file [name] of this phone as text for a backup; null when it is empty. */
    fun read(context: Context, name: String): String? =
        runCatching { encode(context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE).all) }.getOrNull()

    /** Fills the settings file [name] with the keys of [text] this phone has no value for. Never throws. */
    fun fill(context: Context, name: String, text: String?) {
        val saved = decode(text)
        if (saved.isEmpty()) return
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val missing = saved.filterKeys { !prefs.contains(it) }
            if (missing.isEmpty()) return
            prefs.edit { putAll(missing) }
        }
    }

    /**
     * Makes the settings file [name] what [text] holds: the backup's keys and nothing else (so a file kept together,
     * like the video's style and the version of its defaults, comes back together). A backup without the file (null,
     * empty or damaged [text]) leaves this phone's as it is. Never throws.
     */
    fun replace(context: Context, name: String, text: String?) {
        val saved = decode(text)
        if (saved.isEmpty()) return
        runCatching {
            context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit {
                clear()
                putAll(saved)
            }
        }
    }

    private fun android.content.SharedPreferences.Editor.putAll(values: Map<String, Any>) {
        for ((key, value) in values) {
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Boolean -> putBoolean(key, value)
                is Set<*> -> putStringSet(key, value as Set<String>)
            }
        }
    }
}
