package com.justdataplease.boringtravel.compat

import com.justdataplease.boringtravel.ios.SettingsFile
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

/*
 * android.content.Context as the trip video's settings files use it (clip/ClipPrefs.kt and clip/music/MusicPrefs.kt,
 * read in at build time): a small named settings file. Each is kept in this phone's settings as one text in the form a
 * backup carries it (the Android app's backup/PrefsBackup.kt: {"key":{"t":"s","v":"…"}}), under the backup's own name
 * ("clip" as clipStyle, "clip_music" as clipMusic, "map_style" as mapStyle), so a backup made on either phone brings
 * them back unchanged.
 */

/** The app's context for its settings files ([settings]: this phone's). */
open class Context(internal val settings: SettingsFile) {
    val applicationContext: Context get() = this

    @Suppress("UNUSED_PARAMETER")
    fun getSharedPreferences(name: String, mode: Int): PrefsFile = PrefsFile(settings, PrefsFile.keyOf(name))

    companion object {
        const val MODE_PRIVATE = 0
    }
}

/** One named settings file, read and written whole as its backup text under [key]. */
class PrefsFile(private val settings: SettingsFile, private val key: String) {
    private fun values(): Map<String, Any> = PrefsText.decode(settings.read(key))

    val all: Map<String, Any> get() = values()

    fun contains(k: String): Boolean = k in values()

    fun getString(k: String, defValue: String?): String? = values()[k] as? String ?: defValue

    fun getInt(k: String, defValue: Int): Int = values()[k] as? Int ?: defValue

    fun getLong(k: String, defValue: Long): Long = values()[k] as? Long ?: defValue

    fun getFloat(k: String, defValue: Float): Float = values()[k] as? Float ?: defValue

    fun getBoolean(k: String, defValue: Boolean): Boolean = values()[k] as? Boolean ?: defValue

    fun edit(): Editor = Editor()

    /** Changes written together on [apply] or [commit], as Android's editor. */
    inner class Editor internal constructor() {
        private val changes = LinkedHashMap<String, Any?>()
        private var cleared = false

        fun putString(k: String, value: String?): Editor = apply { changes[k] = value }
        fun putInt(k: String, value: Int): Editor = apply { changes[k] = value }
        fun putLong(k: String, value: Long): Editor = apply { changes[k] = value }
        fun putFloat(k: String, value: Float): Editor = apply { changes[k] = value }
        fun putBoolean(k: String, value: Boolean): Editor = apply { changes[k] = value }
        fun remove(k: String): Editor = apply { changes[k] = null }
        fun clear(): Editor = apply { cleared = true; changes.clear() }

        fun apply() {
            val next = LinkedHashMap(if (cleared) emptyMap() else values())
            for ((k, v) in changes) if (v == null) next.remove(k) else next[k] = v
            settings.write(key, PrefsText.encode(next))
        }

        fun commit(): Boolean {
            apply()
            return true
        }
    }

    companion object {
        /** The settings key a file is kept under: a backup's name for the ones a backup carries. */
        fun keyOf(name: String): String = when (name) {
            "clip" -> "clipStyle"
            "clip_music" -> "clipMusic"
            "map_style" -> "mapStyle"
            else -> "prefs.$name"
        }
    }
}

/** androidx.core's `prefs.edit { … }`: the changes, applied (or committed). */
inline fun PrefsFile.edit(commit: Boolean = false, action: PrefsFile.Editor.() -> Unit) {
    val editor = edit()
    action(editor)
    if (commit) editor.commit() else editor.apply()
}

/**
 * A settings file as the backup's text (the Android app's backup/PrefsBackup.kt encode and decode, the same rules):
 * keys in order, each with its type ("s", "i", "l", "f", "b", "ss") and value; null when there is nothing.
 */
object PrefsText {
    const val MAX_CHARS = 200_000
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(values: Map<String, Any?>): String? {
        val out = LinkedHashMap<String, JsonElement>()
        for (k in values.keys.sorted()) {
            val (type, v) = when (val value = values[k]) {
                is String -> "s" to JsonPrimitive(value)
                is Int -> "i" to JsonPrimitive(value)
                is Long -> "l" to JsonPrimitive(value)
                is Float -> "f" to JsonPrimitive(value)
                is Boolean -> "b" to JsonPrimitive(value)
                is Set<*> -> "ss" to JsonArray(value.filterIsInstance<String>().sorted().map(::JsonPrimitive))
                else -> continue
            }
            out[k] = JsonObject(mapOf("t" to JsonPrimitive(type), "v" to v))
        }
        if (out.isEmpty()) return null
        return JsonObject(out).toString().takeIf { it.length <= MAX_CHARS }
    }

    fun decode(text: String?): Map<String, Any> {
        if (text.isNullOrBlank() || text.length > MAX_CHARS) return emptyMap()
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for ((k, element) in root) {
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
            if (value != null) out[k] = value
        }
        return out
    }
}

/** java.util.Random with Java's own sequence (its 48-bit generator and nextGaussian's polar method). */
class JavaRandom(seed: Long) {
    private var seed = (seed xor MULTIPLIER) and MASK
    private var nextNextGaussian = 0.0
    private var haveNextNextGaussian = false

    private fun next(bits: Int): Int {
        seed = (seed * MULTIPLIER + ADDEND) and MASK
        return (seed ushr (48 - bits)).toInt()
    }

    fun nextInt(): Int = next(32)

    fun nextDouble(): Double = ((next(26).toLong() shl 27) + next(27)) * DOUBLE_UNIT

    fun nextGaussian(): Double {
        if (haveNextNextGaussian) {
            haveNextNextGaussian = false
            return nextNextGaussian
        }
        var v1: Double
        var v2: Double
        var s: Double
        do {
            v1 = 2 * nextDouble() - 1
            v2 = 2 * nextDouble() - 1
            s = v1 * v1 + v2 * v2
        } while (s >= 1 || s == 0.0)
        val multiplier = kotlin.math.sqrt(-2 * kotlin.math.ln(s) / s)
        nextNextGaussian = v2 * multiplier
        haveNextNextGaussian = true
        return v1 * multiplier
    }

    private companion object {
        const val MULTIPLIER = 0x5DEECE66DL
        const val ADDEND = 0xBL
        const val MASK = 0xFFFFFFFFFFFFL
        /** 2^-53, as Java writes it 0x1.0p-53. */
        const val DOUBLE_UNIT = 1.1102230246251565E-16
    }
}

/** java.util.TreeMap as the clip's planner keeps its holds: by key, in key order. */
class TreeMap<K : Comparable<K>, V> : AbstractMutableMap<K, V>() {
    private val map = HashMap<K, V>()

    override val size: Int get() = map.size

    override fun get(key: K): V? = map[key]

    override fun containsKey(key: K): Boolean = map.containsKey(key)

    override fun put(key: K, value: V): V? = map.put(key, value)

    override fun remove(key: K): V? = map.remove(key)

    override fun clear() = map.clear()

    /** Every entry, in key order (a snapshot: changing the map doesn't change it; a value's own contents may change). */
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() {
            val sorted = LinkedHashMap<K, V>()
            for (k in map.keys.sorted()) sorted[k] = map.getValue(k)
            return sorted.entries
        }
}

/** java.util's sortedSetOf for whole numbers, as the clip thins a route: each number once, listed in order. */
class SortedIntSet(vararg first: Int) {
    private val set = HashSet<Int>().apply { first.forEach { add(it) } }

    val size: Int get() = set.size

    operator fun plusAssign(value: Int) {
        set.add(value)
    }

    operator fun contains(value: Int): Boolean = value in set

    fun toList(): List<Int> = set.sorted()
}

/**
 * java.util.LinkedHashMap in access order, as the paper vehicles' cache keeps its last few: [get] makes an entry the
 * newest, and after [set] the eldest goes while [removeEldestEntry] says so.
 */
open class AccessOrderMap<K, V>(
    @Suppress("UNUSED_PARAMETER") initialCapacity: Int, @Suppress("UNUSED_PARAMETER") loadFactor: Float,
    @Suppress("UNUSED_PARAMETER") accessOrder: Boolean,
) {
    private val map = LinkedHashMap<K, V>()

    val size: Int get() = map.size

    operator fun get(key: K): V? {
        val v = map.remove(key) ?: return null
        map[key] = v
        return v
    }

    operator fun set(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.isNotEmpty()) {
            val eldest = map.entries.first()
            if (!removeEldestEntry(Entry(eldest.key, eldest.value))) break
            map.remove(eldest.key)
        }
    }

    protected open fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = false

    private class Entry<K, V>(override val key: K, override var value: V) : MutableMap.MutableEntry<K, V> {
        override fun setValue(newValue: V): V = value.also { value = newValue }
    }
}
