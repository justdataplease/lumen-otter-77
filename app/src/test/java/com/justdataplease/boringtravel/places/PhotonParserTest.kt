package com.justdataplease.boringtravel.places

import com.justdataplease.boringtravel.model.PlanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonParserTest {
    // A trimmed real answer for "halki naxos" plus a café, a node/area duplicate, a street-only address and broken
    // features that must be skipped.
    private val canned = """
    {"type":"FeatureCollection","features":[
      {"type":"Feature","properties":{"osm_type":"N","osm_id":806501481,"osm_key":"place","osm_value":"village","type":"city",
        "name":"Halki","county":"Naxos Regional Unit","state":"Aegean","country":"Greece","postcode":"843 02","countrycode":"GR"},
        "geometry":{"type":"Point","coordinates":[25.4839886,37.0639027]}},
      {"type":"Feature","properties":{"osm_type":"R","osm_id":99,"osm_key":"place","osm_value":"village","type":"city",
        "name":"Halki","county":"Naxos Regional Unit","state":"Aegean","country":"Greece","countrycode":"GR"},
        "geometry":{"type":"Point","coordinates":[25.4850,37.0645]}},
      {"type":"Feature","properties":{"osm_type":"N","osm_id":5132512835,"osm_key":"amenity","osm_value":"cafe","type":"house",
        "name":"Portara Coffee & Bar","street":"Naxos - Engares","city":"Naxos","county":"Naxos Regional Unit","state":"Aegean",
        "country":"Greece","countrycode":"gr"},
        "geometry":{"type":"Point","coordinates":[25.3745944,37.1074593]}},
      {"type":"Feature","properties":{"osm_type":"N","osm_id":7,"osm_key":"tourism","osm_value":"hotel","type":"house",
        "name":"Studios Agios Georgios","city":"Naxos","country":"Greece","countrycode":"GR"},
        "geometry":{"type":"Point","coordinates":[25.373,37.099]}},
      {"type":"Feature","properties":{"osm_type":"N","osm_id":8,"osm_key":"amenity","osm_value":"ferry_terminal","type":"house",
        "name":"Naxos Port","city":"Naxos","country":"Greece","countrycode":"GR"},
        "geometry":{"type":"Point","coordinates":[25.3747,37.1056]}},
      {"type":"Feature","properties":{"osm_type":"W","osm_id":9,"osm_key":"building","osm_value":"yes","type":"house",
        "street":"Papavasileiou","housenumber":"12","city":"Naxos","country":"Greece","countrycode":"GR"},
        "geometry":{"type":"Point","coordinates":[25.3723,37.1102]}},
      {"type":"Feature","properties":{"name":"No point"}},
      {"type":"Feature","properties":{"osm_key":"place"},"geometry":{"type":"Point","coordinates":[25.0,37.0]}},
      {"type":"Feature","properties":{"name":"Bad point"},"geometry":{"type":"Point","coordinates":["x",37.0]}},
      {"type":"Feature","properties":{"name":"Greece","osm_key":"place","osm_value":"country","country":"Greece","countrycode":"GR"},
        "geometry":{"type":"Point","coordinates":[22.0,39.0]}}
    ]}
    """.trimIndent()

    @Test fun readsPlacesWithCompactNames() {
        val r = PhotonParser.parse(canned)
        assertEquals(
            listOf("Halki, Naxos, Greece", "Portara Coffee & Bar, Naxos, Greece", "Studios Agios Georgios, Naxos, Greece", "Naxos Port, Naxos, Greece", "Papavasileiou 12, Naxos, Greece", "Greece"),
            r.map { it.displayName },
        )
    }

    @Test fun coordinatesComeOutAsLatLng() {
        val halki = PhotonParser.parse(canned).first()
        assertEquals(37.0639027, halki.lat, 1e-9)
        assertEquals(25.4839886, halki.lng, 1e-9)
        assertEquals("Halki", halki.name)
        assertEquals("GR", halki.countryCode)
        assertEquals("osm:N806501481", halki.externalId)
    }

    @Test fun kindsAreGuessedFromTags() {
        val byName = PhotonParser.parse(canned).associateBy { it.name }
        assertEquals(PlanKind.Place, byName["Halki"]?.kind)
        assertEquals(PlanKind.Food, byName["Portara Coffee & Bar"]?.kind)
        assertEquals(PlanKind.Stay, byName["Studios Agios Georgios"]?.kind)
        assertEquals(PlanKind.Transport, byName["Naxos Port"]?.kind)
        assertNull(byName["Papavasileiou 12"]?.kind)
        assertEquals("GR", byName["Portara Coffee & Bar"]?.countryCode)
    }

    @Test fun nearDuplicatesAreDropped() {
        assertEquals(1, PhotonParser.parse(canned).count { it.name == "Halki" })
    }

    @Test fun brokenAnswersGiveNothing() {
        assertTrue(PhotonParser.parse("").isEmpty())
        assertTrue(PhotonParser.parse("not json").isEmpty())
        assertTrue(PhotonParser.parse("""{"features":"nope"}""").isEmpty())
        assertTrue(PhotonParser.parse("""[1,2,3]""").isEmpty())
    }
}
