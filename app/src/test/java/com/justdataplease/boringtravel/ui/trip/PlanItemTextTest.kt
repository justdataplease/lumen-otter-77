package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** A plan item shared as text (owner, 2026-11-12: "the whole item as a nice structured text ... (except journal)"). */
class PlanItemTextTest {
    private val sat = LocalDate.of(2027, 3, 13)
    private var locale: Locale = Locale.getDefault()

    @Before fun english() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.UK)
    }

    @After fun restore() = Locale.setDefault(locale)

    private val alhambra = PlanItem(
        id = "a", tripId = "t", day = sat, position = 1.0, timeMinutes = 10 * 60, endMinutes = 13 * 60,
        title = "Alhambra", placeName = "Calle Real de la Alhambra, Granada, Spain", lat = 37.176, lng = -3.588,
        url = "https://www.alhambra-patronato.es", note = "Tickets booked. Bring passports.", done = true,
        createdAt = 0, updatedAt = 0,
    )

    @Test fun theWholeItemOneLineEach() {
        val text = PlanItemText.of(alhambra, PlanItemText.gettingThere(Vehicle.Train, "Madrid"))
        assertEquals(
            """
            Alhambra
            Sat 13 Mar 2027 · 10:00–13:00
            Calle Real de la Alhambra, Granada, Spain
            Website: https://www.alhambra-patronato.es
            Map: https://www.google.com/maps/search/?api=1&query=37.176,-3.588
            Getting there: train from Madrid
            Note: Tickets booked. Bring passports.
            """.trimIndent(),
            text,
        )
        // Ticked off or not, it reads the same.
        assertEquals(text, PlanItemText.of(alhambra.copy(done = false), PlanItemText.gettingThere(Vehicle.Train, "Madrid")))
    }

    @Test fun linesWithNothingToSayAreLeftOut() {
        val idea = PlanItem(id = "i", tripId = "t", day = null, title = "Hammam", createdAt = 0, updatedAt = 0)
        assertEquals("Hammam", PlanItemText.of(idea))
        val untilOnly = idea.copy(day = sat, endMinutes = 14 * 60)
        assertEquals("Hammam\nSat 13 Mar 2027 · until 14:00", PlanItemText.of(untilOnly))
        // An address that only repeats the name, and a blank note, say nothing.
        assertEquals(
            "Granada\nMap: https://www.google.com/maps/search/?api=1&query=Granada",
            PlanItemText.of(idea.copy(title = "Granada", placeName = "Granada", note = "  ")),
        )
    }

    @Test fun aStaySaysItsNightsAndCheckOut() {
        val hostal = alhambra.copy(
            title = "Hostal Alicante", kind = "stay", timeMinutes = 15 * 60, endDays = 3, endMinutes = 11 * 60,
            url = null, note = "", placeName = "", lat = null, lng = null,
        )
        assertEquals("Hostal Alicante\nSat 13 Mar 2027 · 15:00\n3 nights, check-out Tue 16 Mar · 11:00", PlanItemText.of(hostal))
        val party = hostal.copy(title = "Party", kind = "activity", timeMinutes = 22 * 60, endDays = 1, endMinutes = 2 * 60)
        assertEquals("Party\nSat 13 Mar 2027 · 22:00\nUntil Sun 14 Mar · 02:00", PlanItemText.of(party))
    }

    @Test fun aGoogleMapsLinkIsTheMapNotTheWebsite() {
        val maps = "https://www.google.com/maps/place/Alhambra/@37.176,-3.588,17z"
        val text = PlanItemText.of(alhambra.copy(url = maps, note = ""))
        assertEquals("Map: $maps", text.lines().first { it.startsWith("Map:") })
        assertFalse(text.contains("Website:"))
    }

    @Test fun theItemsOwnMapsLinkComesAfterTheWebsite() {
        // Owner, 2026-11-13: "add to a place a website and a google url".
        val maps = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"
        val lines = PlanItemText.of(alhambra.copy(mapsUrl = maps, note = "")).lines()
        assertEquals(listOf("Website: https://www.alhambra-patronato.es", "Map: $maps"), lines.filter { it.startsWith("Website:") || it.startsWith("Map:") })
        assertEquals(lines.indexOfFirst { it.startsWith("Website:") } + 1, lines.indexOfFirst { it.startsWith("Map:") })
        // A typed place without a pin: its own Maps link, else a search for its name.
        val named = alhambra.copy(url = null, lat = null, lng = null, placeName = "Abuela's patio")
        assertEquals("Map: $maps", PlanItemText.of(named.copy(mapsUrl = maps)).lines().single { it.startsWith("Map:") })
        assertEquals("Map: https://www.google.com/maps/search/?api=1&query=Abuela%27s+patio", PlanItemText.of(named).lines().single { it.startsWith("Map:") })
    }

    @Test fun mapLinksUseThePinElseTheAddress() {
        assertEquals("https://www.google.com/maps/search/?api=1&query=0.0001,-180", PlanItemText.mapUrl(alhambra.copy(lat = 0.0001, lng = -180.0)))
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=Plaza+Nueva%2C+Granada",
            PlanItemText.mapUrl(alhambra.copy(lat = null, lng = null, placeName = "Plaza Nueva, Granada")),
        )
        assertNull(PlanItemText.mapUrl(alhambra.copy(lat = null, lng = null, placeName = "")))
        assertEquals("on foot from Hotel Ritz", PlanItemText.gettingThere(Vehicle.Walk, "Hotel Ritz"))
        assertEquals("flight from Athens", PlanItemText.gettingThere(Vehicle.Plane, "Athens"))
    }

    @Test fun theTagsComeLast() {
        assertEquals("#moorish #gardens", PlanItemText.of(alhambra.copy(tags = "moorish gardens")).lines().last())
        assertEquals(false, PlanItemText.of(alhambra).contains("#"))
    }
}
