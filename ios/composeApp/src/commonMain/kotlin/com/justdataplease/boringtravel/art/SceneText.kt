package com.justdataplease.boringtravel.art

import kotlin.math.abs
import kotlin.math.floor

/*
 * What the Android app's art/Scenes.kt asks of Java, in plain Kotlin: the build rewrites those calls to these (see
 * `rewrites` in composeApp/build.gradle.kts). Same results for the words and spots the pictures are picked by.
 */

/**
 * [text] with its accented letters written without the accent, as Java's Normalizer (NFD) and the `\p{M}` removal after
 * it leave them: "glaciar" for "glacíar", "λιμνη" for "λίμνη". The letters the trips and places are written in (Latin
 * with its accents, Greek with its tonos and dialytika); any other letter stays as it is.
 */
internal fun decomposed(text: String): String {
    if (text.all { it.code < 0xC0 }) return text
    val out = StringBuilder(text.length)
    for (ch in text) out.append(BASE[ch] ?: ch)
    return out.toString()
}

private val BASE: Map<Char, Char> = HashMap<Char, Char>().apply {
    fun put(from: String, to: Char) = from.forEach { put(it, to) }
    put("àáâãäåāăą", 'a')
    put("ÀÁÂÃÄÅĀĂĄ", 'A')
    put("çćĉċč", 'c')
    put("ÇĆĈĊČ", 'C')
    put("ď", 'd')
    put("Ď", 'D')
    put("èéêëēĕėęě", 'e')
    put("ÈÉÊËĒĔĖĘĚ", 'E')
    put("ĝğġģ", 'g')
    put("ĜĞĠĢ", 'G')
    put("ĥ", 'h')
    put("Ĥ", 'H')
    put("ìíîïĩīĭį", 'i')
    put("ÌÍÎÏĨĪĬĮİ", 'I')
    put("ĵ", 'j')
    put("Ĵ", 'J')
    put("ķ", 'k')
    put("Ķ", 'K')
    put("ĺļľ", 'l')
    put("ĹĻĽ", 'L')
    put("ñńņň", 'n')
    put("ÑŃŅŇ", 'N')
    put("òóôõöōŏő", 'o')
    put("ÒÓÔÕÖŌŎŐ", 'O')
    put("ŕŗř", 'r')
    put("ŔŖŘ", 'R')
    put("śŝşšș", 's')
    put("ŚŜŞŠȘ", 'S')
    put("ţťț", 't')
    put("ŢŤȚ", 'T')
    put("ùúûüũūŭůűų", 'u')
    put("ÙÚÛÜŨŪŬŮŰŲ", 'U')
    put("ŵ", 'w')
    put("Ŵ", 'W')
    put("ýÿŷ", 'y')
    put("ÝŸŶ", 'Y')
    put("źżž", 'z')
    put("ŹŻŽ", 'Z')
    put("ά", 'α')
    put("έ", 'ε')
    put("ή", 'η')
    put("ίϊΐ", 'ι')
    put("ό", 'ο')
    put("ύϋΰ", 'υ')
    put("ώ", 'ω')
    put("Ά", 'Α')
    put("Έ", 'Ε')
    put("Ή", 'Η')
    put("ΊΪ", 'Ι')
    put("Ό", 'Ο')
    put("ΎΫ", 'Υ')
    put("Ώ", 'Ω')
}

/** [v] with one decimal, as Java's "%.1f" writes it in the root locale ("-0.0" keeps its sign, halves round up). */
internal fun oneDecimal(v: Double): String {
    val tenths = floor(abs(v) * 10 + 0.5).toLong()
    val sign = if (v < 0 || (v == 0.0 && 1.0 / v < 0)) "-" else ""
    return "$sign${tenths / 10}.${tenths % 10}"
}
