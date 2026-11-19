package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.doc.DocRender
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.export.TripBook
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The debug sample trip "Cyclades, slowly" (same title, places and entries as SampleData), with a few more days and
 * photos so a whole book can be laid out. Stops are real places; there are no made-up GPS tracks.
 */
object BookFixtures {
    val zone: ZoneId = ZoneId.of("Europe/Athens")
    val start: LocalDate = LocalDate.of(2027, 6, 16)
    private const val T = 1_000L

    fun at(day: LocalDate, time: String): Long = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    /** Photo ids with the sizes of the sample photos used for the rendered pages. */
    val photoSizes: Map<String, Pair<Int, Int>> = linkedMapOf(
        "ferry" to (3840 to 2883), "parikia" to (3840 to 2880), "windmill" to (2304 to 3072), "parikia2" to (3840 to 2875),
        "kolymb" to (1080 to 600), "kolymb2" to (800 to 600), "naousa" to (1280 to 854),
        "chora" to (3456 to 2304), "beach" to (3648 to 2432), "portara2" to (3840 to 2560), "portara" to (640 to 480),
        "amorgos" to (2360 to 1080), "hozo" to (3840 to 2560),
    )

    private fun media(id: String, day: LocalDate, time: String, entryId: String? = null): Media {
        val (w, h) = photoSizes.getValue(id)
        return Media(id = id, tripId = "t1", entryId = entryId, localPath = "media/$id.jpg", width = w, height = h, takenAt = at(day, time), createdAt = T, updatedAt = T)
    }

    private fun entry(id: String, day: LocalDate, time: String, title: String, doc: EntryDoc, place: String) = Entry(
        id = id, tripId = "t1", occurredAt = at(day, time), zoneId = zone.id, title = title, body = doc.toJson(),
        plainText = DocRender.plainText(doc), placeName = place, tempC = 27.0, weather = "sunny", createdAt = T, updatedAt = T,
    )

    private fun stop(id: String, day: LocalDate, time: String, lat: Double, lng: Double, name: String) =
        Stop(id, "t1", at(day, time), null, lat, lng, name, "GR", createdAt = T, updatedAt = T)

    private fun p(text: String) = Block.Paragraph(text)
    private fun photo(id: String, cap: String = "", rot: Float = 0f) = Block.Photo(id, rotation = rot, frame = PhotoFrame.None, caption = cap)

    val longWalk = listOf(
        "The bus to Lefkes left Parikia at ten, and by half past we were the only ones still on it. The driver let us off by the church and pointed at a path between two walls, which turned out to be the whole plan for the day.",
        "The Byzantine path goes down from the village to Prodromos in about an hour, paved in big marble slabs that the mules polished long before anyone thought of hiking boots. It is <b>shaded for the first half</b> and then not at all.",
        "We stopped at a chapel with its door wide open and nobody inside, only a broom and a bunch of dried basil on the altar. Somebody had swept that morning. A cat followed us for a while and then decided we were not worth it.",
        "Lunch in Prodromos was a plate of beans, a tomato salad and bread that had clearly been bought yesterday. It was perfect. The man who brought it asked where we were going next and laughed when we said Naxos, as if that were very far.",
        "On the way back the path climbs the whole time. We counted the slabs for a while, gave up at four hundred and sat on a wall to look at the sea, which had gone from blue to silver while we were not paying attention.",
        "Back in Parikia the windmill by the port had its sails out for the tourists. We bought two postcards and wrote neither.",
    )

    fun book(): TripBook {
        val trip = Trip("t1", "Cyclades, slowly", "Paros → Naxos → Amorgos", start, start.plusDays(9), createdAt = T, updatedAt = T)
        val d1 = start
        val d2 = start.plusDays(1)
        val d3 = start.plusDays(2)
        val d4 = start.plusDays(3)
        val d7 = start.plusDays(6)
        val d8 = start.plusDays(7)
        val entries = listOf(
            entry("e1", d1, "16:10", "Ferry from Piraeus", EntryDoc(blocks = listOf(
                p("Four hours on the top deck with a coffee that went cold in the first ten minutes. Syros came and went. Paros arrived slowly, white and flat, and then all at once."),
                photo("ferry", "Blue Star, 16:10"),
            )), "Piraeus → Paros"),
            entry("e2", d2, "18:40", "Lefkes and the Byzantine path", EntryDoc(font = "literata", blocks = longWalk.map(::p) + listOf(
                photo("parikia", "Parikia from the old path"), photo("windmill", "The windmill by the port"),
            )), "Lefkes, Paros"),
            entry("e3", d3, "19:30", "Kolymbithres, twice", EntryDoc(blocks = listOf(
                p("Same rocks in the morning and again at six. <i>Better the second time.</i>"),
                photo("kolymb", "Morning"), photo("kolymb2", "Six o'clock"), photo("naousa", "Naousa on the way back"),
            )), "Kolymbithres, Paros"),
            entry("e4", d4, "09:00", "Ferry day", EntryDoc(blocks = listOf(
                p("Slept on the deck chairs. Paros got smaller and nothing else happened, which was the point."),
                photo("chora", "Naxos from the harbour"),
            )), "Paros → Naxos"),
            entry("e5", d4, "13:10", "The bus to Halki", EntryDoc(font = "literata", blocks = listOf(
                p("Forty minutes late and nobody minded. <b>Bought kitron</b> in the square and waited under the plane tree."),
                p("The man at the next table explained the island with sugar packets: the port, the mountain, the marble, the bus that is always late."),
                Block.Checklist(listOf(CheckItem("Postcards for Mum", true), CheckItem("Bus back at 17:00"))),
            )), "Halki, Naxos"),
            entry("e6", d4, "21:15", "Portara at sunset", EntryDoc(blocks = listOf(
                p("Half of Naxos walks out to the marble door at sunset. We went anyway and it was worth the crowd."),
                photo("portara2", "The gate"), photo("portara", "Nine o'clock"),
            )), "Portara, Naxos"),
            entry("e7", d7, "12:30", "Amorgos", EntryDoc(blocks = listOf(
                p("The small boat was as rough as everyone said. Katapola was quiet and smelled of grilled octopus."),
                photo("amorgos", "The east coast from the bus"),
            )), "Katapola, Amorgos"),
            entry("e8", d8, "10:00", "Hozoviotissa", EntryDoc(blocks = listOf(
                p("Three hundred steps up to a monastery built into the cliff. They give you loukoumi and a glass of raki at the top, at ten in the morning."),
                photo("hozo"),
            )), "Hozoviotissa, Amorgos"),
        )
        val photoEntry = mapOf("ferry" to "e1", "parikia" to "e2", "windmill" to "e2", "kolymb" to "e3", "kolymb2" to "e3", "naousa" to "e3", "chora" to "e4", "portara2" to "e6", "portara" to "e6", "amorgos" to "e7", "hozo" to "e8")
        val media = listOf(
            media("ferry", d1, "14:30", "e1"), media("parikia", d2, "11:00", "e2"), media("windmill", d2, "17:30", "e2"), media("parikia2", d2, "18:00"),
            media("kolymb", d3, "09:30", "e3"), media("kolymb2", d3, "18:10", "e3"), media("naousa", d3, "19:00", "e3"),
            media("chora", d4, "08:40", "e4"), media("beach", d4, "10:15"), media("portara2", d4, "20:30", "e6"), media("portara", d4, "21:00", "e6"),
            media("amorgos", d7, "11:00", "e7"), media("hozo", d8, "09:40", "e8"),
        )
        check(media.all { it.entryId == null || photoEntry[it.id] == it.entryId })
        val stops = listOf(
            stop("s0", d1, "09:30", 37.9420, 23.6465, "Piraeus"),
            stop("s1", d1, "20:30", 37.0853, 25.1488, "Parikia, Paros"),
            stop("s2", d2, "10:40", 37.0567, 25.1995, "Lefkes, Paros"),
            stop("s3", d2, "13:00", 37.0727, 25.2335, "Prodromos, Paros"),
            stop("s4", d3, "09:20", 37.1347, 25.2389, "Kolymbithres, Paros"),
            stop("s5", d3, "18:50", 37.1236, 25.2378, "Naousa, Paros"),
            stop("s6", d4, "08:30", 37.1056, 25.3747, "Naxos port"),
            stop("s7", d4, "09:45", 37.0990, 25.3730, "Agios Georgios, Naxos"),
            stop("s8", d4, "12:20", 37.0587, 25.4807, "Halki, Naxos"),
            stop("s9", d4, "20:20", 37.1111, 25.3721, "Portara, Naxos"),
            stop("s10", d7, "12:00", 36.8297, 25.8642, "Katapola, Amorgos"),
            stop("s11", d8, "09:30", 36.8287, 25.8991, "Hozoviotissa, Amorgos"),
        )
        return TripBook(trip = trip, entries = entries, media = media, stops = stops)
    }

    fun facts(book: TripBook = book()) = BookFacts(book, java.util.Locale.UK)

    /** Sequential ids so layouts are the same every run. */
    fun ids(): () -> String {
        var n = 0
        return { "p${++n}" }
    }
}
