package com.justdataplease.boringtravel.community

import java.text.Normalizer
import java.util.Locale

/**
 * Community usernames: 3–20 characters of a–z, 0–9, "_" and ".", lower-case, not starting or ending with a dot and
 * without two dots in a row, and none of [RESERVED]. Firestore's rules check the same (firebase/firestore.rules);
 * `usernames/{name}` is claimed once, so two people can never hold one name.
 */
object Usernames {
    const val MIN = 3
    const val MAX = 20

    /** Names nobody gets: the app's own, staff-sounding ones and words that would read as a page of the app. */
    val RESERVED: Set<String> = setOf(
        "admin", "administrator", "boringtravel", "boring.travel", "boring_travel", "justdataplease", "support", "help",
        "moderator", "mod", "mods", "staff", "official", "team", "system", "root", "null", "undefined", "anonymous",
        "community", "everyone", "settings", "privacy", "terms", "about", "google", "firebase", "android", "api", "app",
        "me", "you",
    )

    /** What a rule says, as the edit screen shows it under the field. */
    enum class Problem(val message: String) {
        Short("At least $MIN characters"),
        Long("At most $MAX characters"),
        Characters("Use a–z, 0–9, _ and . only"),
        Dot("Can't start or end with a dot"),
        Dots("No two dots in a row"),
        Reserved("This name isn't available"),
    }

    private val ALLOWED = Regex("[a-z0-9_.]+")

    /** What is stored for what was typed: trimmed, without a leading "@", lower-case. */
    fun normalize(raw: String): String = raw.trim().removePrefix("@").lowercase(Locale.ROOT)

    /** The first rule [name] (already normalized) breaks, or null when it is fine. */
    fun problem(name: String): Problem? = when {
        name.length < MIN -> Problem.Short
        name.length > MAX -> Problem.Long
        !ALLOWED.matches(name) -> Problem.Characters
        name.startsWith('.') || name.endsWith('.') -> Problem.Dot
        ".." in name -> Problem.Dots
        name in RESERVED -> Problem.Reserved
        else -> null
    }

    fun isValid(name: String): Boolean = problem(name) == null

    /**
     * A first suggestion from the Google name or email: "Maria Papadopoulou" → "maria.papadopoulou", accents dropped,
     * anything else left out; padded with digits when too short; null when nothing usable is left.
     */
    fun suggest(nameOrEmail: String?): String? {
        val source = nameOrEmail?.substringBefore('@')?.trim().orEmpty()
        if (source.isEmpty()) return null
        val plain = Normalizer.normalize(source, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        val joined = plain.replace(Regex("[\\s\\-]+"), ".").filter { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' }
        var name = joined.replace(Regex("\\.{2,}"), ".").trim('.').take(MAX).trimEnd('.')
        if (name.isEmpty()) return null
        while (name.length < MIN) name += "0"
        return name.takeIf { isValid(it) } ?: (name.take(MAX - 3) + "_tr").takeIf { isValid(it) }
    }
}
