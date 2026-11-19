package com.justdataplease.boringtravel.world

import com.justdataplease.boringtravel.geo.Continent
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareCardTest {
    private fun stat(code: String, continent: Continent) = CountryStat(code, continent, visited = true)

    @Test fun theHeadlineFollowsTheCount() {
        assertEquals("No countries yet", ShareCard.headline(0))
        assertEquals("1 country", ShareCard.headline(1))
        assertEquals("7 countries", ShareCard.headline(7))
    }

    @Test fun theLineUnderIt() {
        val one = World(visited = listOf(stat("GR", Continent.Europe)), continents = mapOf(Continent.Europe to 1), worldPercent = 1)
        assertEquals("1% of the world · 1 continent", ShareCard.line(one))
        val two = World(
            visited = listOf(stat("GR", Continent.Europe), stat("MA", Continent.Africa)),
            continents = mapOf(Continent.Europe to 1, Continent.Africa to 1), worldPercent = 1,
        )
        assertEquals("1% of the world · 2 continents", ShareCard.line(two))
        // None yet: the next country, if a trip plans one, else nothing.
        assertEquals("Next: Japan", ShareCard.line(World(nextCodes = listOf("JP")), nameOf = { if (it == "JP") "Japan" else it }))
        assertEquals("", ShareCard.line(World()))
    }
}
