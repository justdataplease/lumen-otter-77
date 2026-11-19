package com.justdataplease.boringtravel.calendar

import com.justdataplease.boringtravel.data.db.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarIntentsTest {
    private val day = LocalDate.of(2027, 3, 13)

    @Test fun untimedItemsAreAllDayAtUtcMidnights() {
        val w = CalendarIntents.whenOf(day, null, ZoneId.of("America/Argentina/Buenos_Aires"))
        assertTrue(w.allDay)
        assertEquals(day.toEpochDay() * 86_400_000L, w.beginMs)
        assertEquals(w.beginMs + 86_400_000L, w.endMs)
    }

    @Test fun timedItemsStartAtTheirLocalTimeForAnHour() {
        val zone = ZoneId.of("Asia/Tokyo")
        val w = CalendarIntents.whenOf(day, 7 * 60 + 30, zone)
        assertFalse(w.allDay)
        assertEquals(ZonedDateTime.of(2027, 3, 13, 7, 30, 0, 0, zone).toInstant().toEpochMilli(), w.beginMs)
        assertEquals(w.beginMs + TripEvents.DEFAULT_MINUTES * 60_000L, w.endMs)
    }

    @Test fun titlesFallBackToThePlaceThenTheKind() {
        fun item(title: String, place: String = "", kind: String = "place") =
            PlanItem("i", "t", day, 1.0, kind = kind, title = title, placeName = place, createdAt = 1, updatedAt = 1)
        assertEquals("Recoleta Cemetery", CalendarIntents.title(item(" Recoleta Cemetery ")))
        assertEquals("Fitz Roy", CalendarIntents.title(item("", "Fitz Roy, Santa Cruz, Argentina")))
        assertEquals("Food", CalendarIntents.title(item("", kind = "food")))
    }

    @Test fun theDescriptionHasTheNoteTheLinkThenTheTrip() {
        val base = PlanItem("i", "t", day, 1.0, title = "Yasaka Shrine", note = " Go early ", createdAt = 1, updatedAt = 1)
        assertEquals("Go early\n\nKyoto & Osaka", CalendarIntents.description(base, " Kyoto & Osaka "))
        val linked = base.copy(url = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA")
        assertEquals("Go early\n\nhttps://maps.app.goo.gl/9ivZQ71CKMqRPDtQA\n\nKyoto & Osaka", CalendarIntents.description(linked, "Kyoto & Osaka"))
        assertEquals("https://example.com/", CalendarIntents.description(linked.copy(note = "", url = "https://example.com/"), ""))
    }
}
