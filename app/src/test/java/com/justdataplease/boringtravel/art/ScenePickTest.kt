package com.justdataplease.boringtravel.art

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ui.components.sceneFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePickTest {
    @Test fun placeWordsPickTheKind() {
        val cases = mapOf(
            "Perito Moreno Glacier" to SceneKind.Glacier,
            "Laguna Capri, El Chaltén" to SceneKind.Lake,
            "Lago Argentino" to SceneKind.Lake,
            "Lake Nahuel Huapi" to SceneKind.Lake,
            "Cerro Campanario" to SceneKind.Mountain,
            "Mount Fitz Roy" to SceneKind.Mountain,
            "Mirador Fitz Roy" to SceneKind.Mountain,
            "Playa Blanca" to SceneKind.Coast,
            "Port of Piraeus" to SceneKind.Coast,
            "Kolymbithres beach" to SceneKind.Coast,
            "Pueblo de Purmamarca" to SceneKind.Village,
            "Bosque de Arrayanes" to SceneKind.Forest,
            "Valle de Uco" to SceneKind.Countryside,
            "A winery lunch" to SceneKind.Countryside,
            "Atacama" to SceneKind.Desert,
            "Tango show" to SceneKind.Night,
            "Buenos Aires" to SceneKind.City,
            "Λίμνη Πλαστήρα" to SceneKind.Lake,
            "Παραλία Αγίου Γεωργίου" to SceneKind.Coast,
        )
        cases.forEach { (name, kind) -> assertEquals(name, kind, ScenePick.forPlace(name)) }
    }

    @Test fun wordsAreWholeWordsOrStems() {
        // "porto" is not "port", "skiathos" is not "ski", "seattle" is not "sea".
        assertEquals(SceneKind.City, ScenePick.kindOf("Lisbon & Porto"))
        assertNull(ScenePick.kindOf("Skiathos"))
        assertNull(ScenePick.kindOf("Seattle"))
        assertEquals(SceneKind.Lake, ScenePick.kindOf("LAGUNA de los Tres"))
        assertEquals(SceneKind.Mountain, ScenePick.kindOf("Hiking in the Dolomites"))
    }

    @Test fun thenTheCountryThenTheSpotThenAnyNamedPlaceIsACity() {
        assertEquals(SceneKind.Coast, ScenePick.forPlace("Halki", country = "GR"))
        assertEquals(SceneKind.Desert, ScenePick.forPlace("Somewhere", country = "ma"))
        assertEquals(SceneKind.Glacier, ScenePick.forPlace("", lat = 78.2, lng = 15.6))
        assertEquals(SceneKind.City, ScenePick.forPlace("Halki"))
        // The same spot always draws the same.
        val a = ScenePick.forPlace("", lat = 37.0587, lng = 25.4807)
        repeat(3) { assertEquals(a, ScenePick.forPlace("", lat = 37.0612, lng = 25.4799)) }
    }

    @Test fun tripsAreTheSameEveryTime() {
        assertEquals(SceneKind.City, ScenePick.forTrip("sample-japan", "Kyoto & Osaka"))
        assertEquals(SceneKind.City, ScenePick.forTrip("sample-portugal", "Lisbon & Porto"))
        assertEquals(SceneKind.Mountain, ScenePick.forTrip("x", "Argentina & Patagonia"))
        assertEquals(SceneKind.Coast, ScenePick.forTrip("x", "Cyclades, slowly"))
        assertEquals(SceneKind.Mountain, ScenePick.forTrip("x", "Summer", country = "CH"))
        val ids = (1..200).map { "trip-$it" }
        val first = ids.map { ScenePick.forTrip(it, "Summer 2027") }
        assertEquals(first, ids.map { ScenePick.forTrip(it, "Summer 2027") })
        // A stable hash spreads over every kind, and its values are fixed (FNV-1a), not Java's hashCode.
        assertEquals(SceneKind.entries.toSet(), first.toSet())
        assertEquals(5, ScenePick.bucket("a", 10)) // FNV-1a("a") = 0xE40C292C, mixed = 0x1A80B1B3
        // Mirroring is spread too (plain FNV-1a's low bit only follows the bytes' parity).
        assertTrue(ids.map { ScenePick.mirrorFor(it) }.toSet().size == 2)
        assertTrue(ScenePick.mirrorFor("sample-japan") != ScenePick.mirrorFor("sample-portugal"))
    }

    @Test fun theUiHelpersUseThePicker() {
        val trip = Trip("t", "Weekend", "Lake Bled", createdAt = 0, updatedAt = 0)
        assertEquals(SceneKind.Lake, sceneFor(trip))
        val entry = Entry("e", occurredAt = 0, zoneId = "UTC", title = "Up early", plainText = "Sunrise over the glacier.", placeName = "", createdAt = 0, updatedAt = 0)
        assertEquals(SceneKind.Glacier, sceneFor(entry))
        val named = entry.copy(placeName = "Laguna Capri")
        assertEquals(SceneKind.Lake, sceneFor(named))
        assertEquals(SceneKind.Night, sceneFor("Bar Sur", country = "AR"))
        assertTrue(SceneKind.entries.all { SceneKind.of(it.id) == it })
    }
}
