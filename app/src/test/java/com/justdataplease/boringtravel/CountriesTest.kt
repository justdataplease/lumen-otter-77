package com.justdataplease.boringtravel

import com.justdataplease.boringtravel.geo.Countries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Reads the bundled asset from disk (unit tests run in the module folder). */
class CountriesTest {
    private val countries by lazy { Countries.parse(File("src/main/assets/geo/countries.json").readText()) }

    @Test fun everyCountryLoads() {
        assertEquals(177, countries.size)
    }

    @Test fun capitalsAndCities() {
        mapOf(
            "GR" to (37.9838 to 23.7275), // Athens
            "FR" to (48.8566 to 2.3522), // Paris (ISO_A2 is -99 in Natural Earth)
            "NO" to (59.9139 to 10.7522), // Oslo (same)
            "XK" to (42.6629 to 21.1655), // Pristina
            "TW" to (25.0330 to 121.5654), // Taipei ("CN-TW" in Natural Earth)
            "JP" to (35.6762 to 139.6503),
            "US" to (39.7392 to -104.9903), // Denver
            "BR" to (-15.7939 to -47.8828),
            "AU" to (-23.70 to 133.88), // Alice Springs
            "ZA" to (-25.7479 to 28.2293),
            "IT" to (41.9028 to 12.4964),
            "TR" to (39.9334 to 32.8597),
        ).forEach { (code, p) -> assertEquals(code, code, countries.codeAt(p.first, p.second)) }
    }

    @Test fun coastsCountWithinAShortDistance() {
        // Lisbon and Piraeus sit on coasts that 1:110m draws a few km off.
        assertEquals("PT", countries.codeAt(38.7223, -9.1393))
        assertEquals("GR", countries.codeAt(37.9420, 23.6465))
    }

    @Test fun openSeaIsNull() {
        assertNull(countries.codeAt(30.0, -40.0)) // mid-Atlantic
        assertNull(countries.codeAt(-40.0, -120.0)) // South Pacific
        // Inland points need no nearby search.
        assertEquals("GR", countries.codeAt(39.6, 22.4, nearbyM = 0.0)) // Larissa
    }

    @Test fun isoCodes() {
        assertTrue(Countries.isIsoAlpha2("GR"))
        assertFalse(Countries.isIsoAlpha2("CYN"))
        assertFalse(Countries.isIsoAlpha2(null))
        assertFalse(Countries.isIsoAlpha2("gr"))
    }
}
