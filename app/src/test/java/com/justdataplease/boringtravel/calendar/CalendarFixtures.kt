package com.justdataplease.boringtravel.calendar

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import java.time.LocalDate
import java.time.ZoneId

/** Small builders shared by the calendar tests. */
internal object Fx {
    val athens: ZoneId = ZoneId.of("Europe/Athens")
    val d17: LocalDate = LocalDate.of(2027, 6, 17)
    val d19: LocalDate = LocalDate.of(2027, 6, 19)
    val d26: LocalDate = LocalDate.of(2027, 6, 26)

    fun trip(start: LocalDate? = d17, end: LocalDate? = d26, title: String = "Cyclades, slowly", subtitle: String = "") =
        Trip(id = "T1", title = title, subtitle = subtitle, startDate = start, endDate = end, createdAt = 1, updatedAt = 1_800_000_000_000)

    fun item(
        id: String,
        day: LocalDate? = d19,
        minutes: Int? = null,
        title: String = id,
        kind: String = "place",
        note: String = "",
        place: String = "",
        position: Double = 1.0,
        done: Boolean = false,
        deleted: Boolean = false,
        externalId: String? = null,
        source: String = "manual",
        lat: Double? = null,
        lng: Double? = null,
    ) = PlanItem(
        id = id, tripId = "T1", day = day, position = position, timeMinutes = minutes, kind = kind, title = title, note = note,
        placeName = place, lat = lat, lng = lng, done = done, source = source, externalId = externalId,
        createdAt = 1, updatedAt = 1_800_000_000_000, deletedAt = if (deleted) 5 else null,
    )
}
