package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.PlaceResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A copied note's place on the map: a sight is the answer nearest its town, a town the first answer with its country,
 * a bare name the one nearest the trip. The answers are shaped as the live map gave them on 15 Nov 2026.
 */
class SitePlacesTest {
    private val kyoto = PlaceResult("京都市", "京都市, 京都府, 日本", 35.0116, 135.7681, area = true)
    private val shrine = PlaceResult("伏見稲荷大社", "伏見稲荷大社, 京都市, 日本", 34.9671, 135.7727, kind = PlanKind.Place, externalId = "osm:W1")
    private val tokyo = PlaceResult("Tokyo", "Tokyo, Japan", 35.6812, 139.7671, area = true)
    private val shibuya = PlaceResult("Shibuya", "Shibuya, Tokyo, Japan", 35.6595, 139.7005, area = true)
    private val shibuyaShopInOsaka = PlaceResult("SHIBUYA 109", "SHIBUYA 109, Osaka, Japan", 34.6454, 135.5114)
    private val madrid = PlaceResult("Madrid", "Madrid, Spain", 40.4168, -3.7038, area = true)
    private val madridBotin = PlaceResult("Restaurante Botín", "Restaurante Botín, Madrid, Spain", 40.414, -3.708, kind = PlanKind.Food)
    private val barcelonaBotin = PlaceResult("Botín", "Botín, Barcelona, Spain", 41.38, 2.17, kind = PlanKind.Food)

    private class Map(val answers: (String, LatLng?) -> List<PlaceResult>) {
        val asked = ArrayList<Pair<String, LatLng?>>()
        val places = SitePlaces { q, n ->
            asked += q to n
            answers(q, n)
        }
        suspend fun find(name: String, area: String?, near: LatLng?) = places.find(name, area, near)
    }

    @Test fun aSightIsTheAnswerNearestItsTown() = runTest {
        val map = Map { q, near ->
            when {
                q == "Tokyo, Japan" -> listOf(tokyo)
                // Words alone don't hold the map to Tokyo: its first answer is a shop in Osaka.
                q == "Shibuya, Tokyo, Japan" -> listOf(shibuyaShopInOsaka)
                q == "Shibuya" && near != null -> listOf(shibuyaShopInOsaka, shibuya)
                else -> emptyList()
            }
        }
        // The trip's last place (Kyoto) is not where this one is: the town decides.
        val place = map.find("Shibuya", "Tokyo, Japan", LatLng(35.01, 135.77))!!
        assertEquals(35.6595 to 139.7005, place.lat to place.lng)
        assertEquals("Shibuya", place.name)
        assertEquals("Shibuya, Tokyo, Japan", place.address)
        assertEquals(LatLng(35.6812, 139.7671), map.asked.last().second)
    }

    @Test fun namesInAnotherScriptAreTheSamePlace() = runTest {
        // The map names the shrine in Japanese: it is still the first answer in the town.
        val map = Map { q, _ ->
            when (q) {
                "Kyoto, Japan" -> listOf(kyoto)
                "Fushimi Inari, Kyoto, Japan" -> listOf(shrine)
                else -> emptyList()
            }
        }
        assertEquals(SiteNotes.Place("Fushimi Inari", 34.9671, 135.7727, "伏見稲荷大社, 京都市, 日本", "place", "osm:W1"), map.find("Fushimi Inari", "Kyoto, Japan", null))
        // An answer inside the town needs no second question.
        assertEquals(listOf("Fushimi Inari, Kyoto, Japan", "Kyoto, Japan"), map.asked.map { it.first })
    }

    @Test fun theTownIsLookedUpOnceAndIsTheLastResort() = runTest {
        val map = Map { q, _ ->
            when (q) {
                "Madrid, Spain" -> listOf(madrid)
                "Botín" -> listOf(barcelonaBotin)
                "Lhardy" -> listOf(madridBotin.copy(name = "Lhardy"))
                else -> emptyList()
            }
        }
        // Nothing of that name within a day trip of Madrid: the right town at least, never Barcelona's namesake.
        val botin = map.find("Botín", "Madrid, Spain", null)!!
        assertEquals(40.4168 to -3.7038, botin.lat to botin.lng)
        assertEquals("Botín", botin.name)
        assertEquals(40.414, map.find("Lhardy", "Madrid, Spain", null)!!.lat!!, 0.0)
        assertEquals(1, map.asked.count { it.first == "Madrid, Spain" })
    }

    @Test fun aTownIsTheFirstAnswerWithItsCountry() = runTest {
        val map = Map { q, _ -> if (q == "Kyoto, Japan") listOf(kyoto, shrine) else emptyList() }
        val place = map.find("Kyoto", "Japan", LatLng(40.0, -3.0))!!
        assertEquals(35.0116 to 135.7681, place.lat to place.lng)
        // One question: a country is not looked up to measure from its middle.
        assertEquals(listOf<Pair<String, LatLng?>>("Kyoto, Japan" to null), map.asked)
    }

    @Test fun theMapIsAskedOneQuestionASecond() = runTest {
        // A free map (it stopped answering after bursts on 15 Nov 2026): every question after the first waits.
        val times = ArrayList<Long>()
        val places = SitePlaces(SiteLimits.MAP_GAP_MS) { q, _ ->
            times += testScheduler.currentTime
            if (q == "Tokyo, Japan") listOf(tokyo) else emptyList()
        }
        places.find("Shibuya", "Tokyo, Japan", null) // the place in its town, the town, the place around the town
        places.find("Kyoto", "Japan", null) // the town with its country, then its name alone
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L), times)
    }

    @Test fun withoutAnAreaTheNameNearestTheTripsLastPlace() = runTest {
        val map = Map { q, _ -> if (q == "Botín") listOf(barcelonaBotin, madridBotin) else emptyList() }
        assertEquals(40.414, map.find("Botín", null, LatLng(40.42, -3.70))!!.lat!!, 0.0)
        // Nothing to go by: the map's own first answer.
        assertEquals(41.38, map.find("Botín", " ", null)!!.lat!!, 0.0)
        // An area the map knows nothing of falls back to the name, near the trip.
        assertEquals(40.414, map.find("Botín", "Nowhere", LatLng(40.42, -3.70))!!.lat!!, 0.0)
        assertEquals(40.414, map.find("Botín", "Nowhere, Atlantis", LatLng(40.42, -3.70))!!.lat!!, 0.0)
        assertNull(Map { _, _ -> emptyList() }.find("Atlantis", "The sea", null))
    }
}
