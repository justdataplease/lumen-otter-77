package com.justdataplease.boringtravel.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class CountryInfoTest {
    private val countries by lazy { Countries.parse(File("src/main/assets/geo/countries.json").readText()) }

    @Test fun theTableKnowsEveryIsoCodeAndKosovo() {
        assertEquals(250, CountryInfo.allCodes.size)
        assertTrue("XK" in CountryInfo.allCodes)
        CountryInfo.allCodes.forEach { code ->
            assertNotNull(code, CountryInfo.continentOf(code))
            assertNotNull(code, CountryInfo.labelPoint(code))
        }
    }

    @Test fun theWorldIs195StatesSplitOverTheContinents() {
        assertEquals(195, CountryInfo.worldStates.size)
        assertEquals(195, Continent.entries.sumOf { CountryInfo.statesIn(it) })
        assertEquals(54, CountryInfo.statesIn(Continent.Africa))
        assertEquals(12, CountryInfo.statesIn(Continent.SouthAmerica))
        assertEquals(0, CountryInfo.statesIn(Continent.Antarctica))
        // Observers count, territories don't.
        assertTrue("VA" in CountryInfo.worldStates && "PS" in CountryInfo.worldStates)
        assertTrue("GL" !in CountryInfo.worldStates && "HK" !in CountryInfo.worldStates && "XK" !in CountryInfo.worldStates)
    }

    @Test fun continentsAsTravellersCountThem() {
        mapOf(
            "GR" to Continent.Europe, "RU" to Continent.Europe, "CY" to Continent.Europe, "TR" to Continent.Asia,
            "GE" to Continent.Asia, "EG" to Continent.Africa, "MA" to Continent.Africa, "GL" to Continent.NorthAmerica,
            "PA" to Continent.NorthAmerica, "FK" to Continent.SouthAmerica, "NZ" to Continent.Oceania, "AQ" to Continent.Antarctica,
            "TF" to Continent.Antarctica, "XK" to Continent.Europe, "TW" to Continent.Asia,
        ).forEach { (code, k) -> assertEquals(code, k, CountryInfo.continentOf(code)) }
        assertEquals(Continent.Europe, CountryInfo.continentOf("CYN")) // Northern Cyprus counts with Cyprus
        assertEquals(Continent.Africa, CountryInfo.continentOf("SOL"))
        assertNull(CountryInfo.continentOf("-99"))
    }

    @Test fun everyBundledOutlineHasAContinentAndAPinInsideIt() {
        for (o in countries.outlines) {
            val key = CountryInfo.key(o.code)
            assertNotNull(o.code, key)
            assertNotNull(o.code, CountryInfo.continentOf(key))
            if (o.code == "CYN" || o.code == "SOL") continue
            val (lat, lng) = CountryInfo.labelPoint(key)!!
            assertEquals("pin of ${o.code}", o.code, countries.codeAt(lat, lng))
        }
    }

    @Test fun flagsAreTwoRegionalIndicatorLetters() {
        assertEquals("🇬🇷", CountryInfo.flagEmoji("GR"))
        assertEquals(CountryInfo.flagEmoji("GR"), CountryInfo.flagEmoji(" gr "))
        assertEquals("🇯🇵", CountryInfo.flagEmoji("JP"))
        assertEquals(4, CountryInfo.flagEmoji("XK")!!.length)
        // Natural Earth's own codes have no flag.
        assertNull(CountryInfo.flagEmoji("CYN"))
        assertNull(CountryInfo.flagEmoji("SOL"))
        assertNull(CountryInfo.flagEmoji("-99"))
        assertNull(CountryInfo.flagEmoji(""))
        assertNull(CountryInfo.flagEmoji(null))
    }

    @Test fun codesAreCountedUnderOneKey() {
        assertEquals("GR", CountryInfo.key(" gr"))
        assertEquals("CY", CountryInfo.key("CYN"))
        assertEquals("SO", CountryInfo.key("SOL"))
        assertNull(CountryInfo.key("-99"))
        assertNull(CountryInfo.key("G1"))
        assertNull(CountryInfo.key(null))
    }

    @Test fun shareOfTheWorld() {
        assertEquals(0, CountryInfo.worldPercent(emptyList()))
        assertEquals(1, CountryInfo.worldPercent(listOf("GR")))
        assertEquals(4, CountryInfo.worldPercent(listOf("GR", "IT", "FR", "ES", "PT", "DE", "MA")))
        // Territories and Kosovo are countries you've been to, but not among the 195.
        assertEquals(0, CountryInfo.worldPercent(listOf("GL", "HK", "XK")))
        assertEquals(100, CountryInfo.worldPercent(CountryInfo.worldStates))
    }

    @Test fun namesInTheGivenLanguage() {
        assertEquals("Greece", CountryInfo.name("GR", Locale.ENGLISH))
        assertEquals("Japan", CountryInfo.name("jp", Locale.ENGLISH))
        assertEquals("Northern Cyprus", CountryInfo.name("CYN", Locale.ENGLISH))
        assertEquals("ZZ9", CountryInfo.name("ZZ9", Locale.ENGLISH))
    }
}
