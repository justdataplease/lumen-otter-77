package com.justdataplease.boringtravel.smoke

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every shape of character class with a \p{…} that the Android code uses, each with what Java makes of it. On the
 * iPhone Simulator (19 Nov 2026) Kotlin/Native dropped the characters after the \p{…} of a negated class such as
 * [^\p{L}\p{N}&]; the read-in files now say those two the plain way (composeApp/build.gradle.kts). These tests say which
 * other shapes Kotlin/Native reads as Java does, so a difference shows here first and not in a traveller's trip.
 */
class RegexShapesSmokeTest {
    @Test
    fun aNegatedClassOfLettersAndNumbers() {
        // DraftLayout, Scenes, ClipText, WikiPhotos, LooseQueries.
        assertEquals("Naxos-Paros-2027", "Naxos & Paros, 2027".replace(Regex("[^\\p{L}\\p{N}]+"), "-"))
        assertEquals("Νάξος-Πάρος", "Νάξος / Πάρος".replace(Regex("[^\\p{L}\\p{N}]+"), "-"))
    }

    @Test
    fun theRewrittenFormsMatchWhatJavaMadeOfTheOriginals() {
        // StayNames.SPLIT, as written for iPhone: & stays inside a word.
        assertEquals(listOf("hotel", "lefkes", "b&b"), "hotel lefkes, b&b".split(Regex("(?:(?!&)[^\\p{L}\\p{N}])+")))
        // ExportFiles.fileName, as written for iPhone: spaces, dots, commas, underscores and dashes stay.
        assertEquals("Νάξος, αργά_2-3.", "Νάξος, αργά_2-3.?«»".replace(Regex("(?:(?![ .,_-])[^\\p{L}\\p{N}])+"), ""))
    }

    @Test
    fun positiveClassesWithControlCharacters() {
        // SiteOrganizer: control characters and the bar.
        assertEquals("a b c", "a\u0007b|c".replace(Regex("[\\p{Cntrl}|]"), " "))
        // DayNames (the engine), ShareCodec, ShareLink: control characters and white space.
        assertEquals("Day one", "Day\t\u0001 one".replace(Regex("[\\p{Cntrl}\\s]+"), " "))
        // PostUploader: slashes and control characters.
        assertEquals("a_b_c_d", "a/b\\c\u0002d".replace(Regex("[\\\\/\\p{Cntrl}]+"), "_"))
        // TogetherEngine: control characters and slashes, the other way round.
        assertEquals("a_b_c", "a\u0003b/c".replace(Regex("[\\p{Cntrl}/\\\\]+"), "_"))
    }

    @Test
    fun aLookbehindOfLettersAndNumbers() {
        // CalendarImport: a word that isn't the end of a longer one.
        val found = Regex("(?<![\\p{L}\\p{N}])(day)").findAll("Day day today 1day").map { it.range.first }.toList()
        assertEquals(listOf(4), found)
    }
}
