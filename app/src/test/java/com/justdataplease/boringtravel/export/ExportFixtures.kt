package com.justdataplease.boringtravel.export

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.doc.Align
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.doc.DocRender
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoFrame
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A small "Cyclades, slowly" trip like the debug sample, plus photos, for the export tests. */
object ExportFixtures {
    val zone: ZoneId = ZoneId.of("Europe/Athens")
    val start: LocalDate = LocalDate.of(2027, 6, 16)
    val day4: LocalDate = start.plusDays(3)
    private const val T = 1_000L

    val parikia = GeoPoint(37.0853, 25.1488)
    val naxosPort = GeoPoint(37.1056, 25.3747)
    val agiosGeorgios = GeoPoint(37.0990, 25.3730)
    val halki = GeoPoint(37.0587, 25.4807)

    fun at(day: LocalDate, time: String): Long = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    fun media(id: String, takenAt: Long?, entryId: String? = null) = Media(
        id = id, tripId = "t1", entryId = entryId, localPath = "media/$id.jpg", width = 4000, height = 3000,
        takenAt = takenAt, createdAt = T, updatedAt = T,
    )

    fun entry(id: String, day: LocalDate, time: String, title: String, doc: EntryDoc, place: String = "") = Entry(
        id = id, tripId = "t1", occurredAt = at(day, time), zoneId = zone.id, title = title, body = doc.toJson(),
        plainText = DocRender.plainText(doc), placeName = place, tempC = 27.0, weather = "sunny", createdAt = T, updatedAt = T,
    )

    fun plan(id: String, day: LocalDate?, pos: Double, time: String?, kind: String, title: String, note: String = "", done: Boolean = false, place: GeoPoint? = null) = PlanItem(
        id = id, tripId = "t1", day = day, position = pos, timeMinutes = time?.let { LocalTime.parse(it).let { t -> t.hour * 60 + t.minute } },
        kind = kind, title = title, note = note, done = done, lat = place?.lat, lng = place?.lng, createdAt = T, updatedAt = T,
    )

    private fun leg(from: GeoPoint, to: GeoPoint, startMs: Long, minutes: Int, every: Int, wiggle: Double): List<TrackPoint> {
        val n = minutes / every
        return (0..n).map { i ->
            val f = i.toDouble() / n
            TrackPoint(tripId = "t1", time = startMs + i * every * 60_000L, lat = from.lat + (to.lat - from.lat) * f + kotlin.math.sin(f * Math.PI) * wiggle, lng = from.lng + (to.lng - from.lng) * f)
        }
    }

    fun book(title: String = "Cyclades, slowly", caption: String = "Halki square, 11:40"): TripBook {
        val trip = Trip("t1", title, "Paros → Naxos → Amorgos", start, start.plusDays(9), createdAt = T, updatedAt = T)
        val bus = EntryDoc(
            font = "literata",
            blocks = listOf(
                Block.Paragraph("Forty minutes late and nobody minded. <b>Bought kitron</b> in the square and waited under the plane tree."),
                Block.Photo("p-halki", rotation = -5f, width = 0.5f, frame = PhotoFrame.Polaroid, caption = caption, align = Align.Start),
                Block.Paragraph("The man at the next table explained the island with sugar packets."),
                Block.Checklist(listOf(CheckItem("Postcards for Mum", true), CheckItem("Bus back at 17:00"))),
            ),
        )
        val ferry = EntryDoc(blocks = listOf(Block.Paragraph("Slept on the deck chairs. Paros got smaller and nothing else happened, which was the point."), Block.Photo("p-ferry", rotation = 4f, width = 0.8f, frame = PhotoFrame.Tape, caption = "the ferry")))
        val kolymb = EntryDoc(blocks = listOf(Block.Paragraph("Same rocks in the morning and again at six. <i>Better the second time.</i>")))
        return TripBook(
            trip = trip,
            entries = listOf(
                entry("e1", day4, "13:10", "The bus to Halki", bus, "Halki, Naxos"),
                entry("e2", day4, "09:00", "Ferry day", ferry, "Paros → Naxos"),
                entry("e3", start.plusDays(2), "19:30", "Kolymbithres, twice", kolymb, "Kolymbithres, Paros"),
            ),
            plan = listOf(
                plan("i1", start, 1.0, "16:10", "transport", "Ferry Piraeus → Paros", "About 4 h", done = true, place = parikia),
                plan("i2", day4, 1.0, "07:30", "transport", "Ferry Paros → Naxos", "About 1 h", done = true, place = naxosPort),
                plan("i3", day4, 3.0, "12:30", "food", "Lunch in Halki", "Bus from the port, 40 min", place = halki),
                plan("i4", start.plusDays(6), 1.0, "08:15", "transport", "Ferry Naxos → Amorgos", "Small boat, can be rough"),
                plan("i5", null, 1.0, null, "food", "Kitron tasting in Halki", "Vallindras distillery"),
            ),
            media = listOf(
                media("p-halki", at(day4, "11:40"), "e1"),
                media("p-ferry", at(day4, "07:45"), "e2"),
                media("p-loose1", at(day4, "16:00")),
                media("p-loose2", at(day4, "18:30")),
                media("p-beach", at(start.plusDays(2), "18:00")),
            ),
            track = leg(parikia, naxosPort, at(day4, "07:30"), 65, 3, 0.02) + leg(naxosPort, agiosGeorgios, at(day4, "09:45"), 12, 2, 0.0) +
                leg(agiosGeorgios, halki, at(day4, "11:40"), 40, 2, -0.01),
            stops = listOf(
                Stop("s1", "t1", at(start, "20:30"), at(day4, "07:20"), parikia.lat, parikia.lng, "Parikia, Paros", "GR", createdAt = T, updatedAt = T),
                Stop("s2", "t1", at(day4, "09:45"), at(day4, "11:35"), agiosGeorgios.lat, agiosGeorgios.lng, "Agios Georgios, Naxos", "GR", createdAt = T, updatedAt = T),
                Stop("s3", "t1", at(day4, "12:20"), null, halki.lat, halki.lng, "Halki, Naxos", "GR", createdAt = T, updatedAt = T),
            ),
        )
    }
}
