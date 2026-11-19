package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.art.SceneKind
import com.justdataplease.boringtravel.art.SceneMath
import com.justdataplease.boringtravel.data.SampleBuilder.Check
import com.justdataplease.boringtravel.data.SampleBuilder.Day
import com.justdataplease.boringtravel.data.SampleBuilder.Pic
import com.justdataplease.boringtravel.data.SampleBuilder.Text
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.programs.Destination
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.Program
import com.justdataplease.boringtravel.programs.ProgramTrips
import com.justdataplease.boringtravel.programs.data.LisbonPorto
import java.time.LocalDate
import java.time.ZoneId

/**
 * The example trip "Lisbon & Porto": a trip in May about six months back. Its plan is the trip idea's default program
 * ([LisbonPorto], Sights & history) at a balanced pace, built like any trip made from an idea ([ProgramTrips]), all
 * done; plus a short journal with drawn pictures and the two places stayed. The journal's places lie on the same day
 * numbers as the program's (Alfama, the castle, Belém, Sintra, Bairro Alto, the train north, Gaia, the Douro);
 * SampleProgramsTest checks it. Nothing is booked, no weather.
 */
object SampleLisbon {
    const val TRIP_ID = SampleTrips.LISBON
    val PACE = Pace.Balanced
    val ZONE: ZoneId = ZoneId.of("Europe/Lisbon")

    val destination: Destination get() = LisbonPorto.destination
    val program: Program get() = destination.default
    val DAYS: Int get() = program.days.size
    val TITLE: String get() = destination.title

    /** 9 May: this year's once the trip is over, else last year's. */
    fun start(today: LocalDate): LocalDate {
        val year = if (LocalDate.of(today.year, 5, 9).plusDays(DAYS - 1L).isBefore(today)) today.year else today.year - 1
        return LocalDate.of(year, 5, 9)
    }

    /** The trip and its plan from [start], stamped [t]: every planned day done (notes never are). */
    fun plan(start: LocalDate, t: Long): ProgramTrips.Built =
        ProgramTrips.build(destination, program, PACE, start, t, links = LinkPlaces::sortLinks, tripId = TRIP_ID, doneBefore = DAYS + 1, trackingLevel = "off")

    private val alfama = 38.7115 to -9.1300
    private val ribeira = 41.1408 to -8.6131

    private val days = listOf(
        Day(
            1, "21:30", "Alfama, first evening", "Alfama, Lisbon", alfama, listOf(
                Text("Taxi from the airport, then up the hill on foot with the bags. Wrong turn twice."),
                Pic(SceneKind.City, "19:40", 38.7118 to -9.1301),
                Text("Santa Luzia at sunset: tiles, the river, a lot of people being quiet."),
            ),
        ),
        Day(
            2, "20:10", "Tram 28 and the castle", "São Jorge Castle, Lisbon", 38.7139 to -9.1335, listOf(
                Text("Tram 28 from Martim Moniz before nine. It squeals round corners with centimetres to spare."),
                Pic(SceneKind.City, "11:20", shape = SceneMath.Shape.Square, mirror = true),
                Text("Castle walls, peacocks, and the whole city below."),
            ),
        ),
        Day(
            3, "19:00", "Belém", "Belém, Lisbon", 38.6979 to -9.2068, listOf(
                Text("Tram 15 out to Belém. The monastery cloister is all carved stone."),
                Pic(SceneKind.Coast, "11:00", 38.6916 to -9.2160),
                Text("Pastéis de Belém still warm, <b>cinnamon and sugar</b> on top."),
                Text("LX Factory after, mostly for the bookshop."),
            ),
        ),
        Day(
            4, "21:00", "Sintra", "Sintra", 38.7963 to -9.3960, listOf(
                Text("Train from Rossio, then the bus up to Pena in the fog."),
                Pic(SceneKind.Village, "12:10", 38.7876 to -9.3906, SceneMath.Shape.Tall),
                Text("It cleared at Quinta da Regaleira. Walked down the spiral well."),
                Check(listOf(CheckItem("Pena", true), CheckItem("Regaleira", true), CheckItem("Moorish Castle"))),
            ),
        ),
        Day(
            5, "23:30", "Bairro Alto", "Bairro Alto, Lisbon", 38.7130 to -9.1440, listOf(
                Text("Long lunch at the market, a nap, then out late."),
                Pic(SceneKind.Night, "22:40"),
                Text("Fado drifting out of a doorway on the way home."),
            ),
        ),
        Day(
            6, "20:30", "North to Porto", "Ribeira, Porto", ribeira, listOf(
                Text("Three hours on the train, mostly fields and pine."),
                Pic(SceneKind.City, "18:50", 41.1404 to -8.6117, mirror = true),
                Text("Ribeira in the evening: washing on the balconies, boats on the river."),
            ),
        ),
        Day(
            7, "19:30", "Lello and the cellars", "Vila Nova de Gaia", 41.1374 to -8.6123, listOf(
                Text("Livraria Lello at opening time. Worth the early start."),
                Text("Over the top of the bridge to Gaia, then port in a cool cellar."),
                Pic(SceneKind.Coast, "17:30", 41.1397 to -8.6094, SceneMath.Shape.Square, mirror = true),
            ),
        ),
        Day(
            8, "21:10", "Douro valley", "Pinhão, Douro", 41.1902 to -7.5450, listOf(
                Text("Train up the river, vineyards on terraces all the way."),
                Pic(SceneKind.Countryside, "13:00", 41.1950 to -7.5470),
                Text("A short walk above Pinhão, then the evening train back."),
            ),
        ),
    )

    /** The trip from [start]: the plan all done, the journal and the stays (Lisbon until day 6, then Porto). */
    fun demo(start: LocalDate, t: Long): SampleArgentina.Demo {
        val (trip, items) = plan(start, t)
        val album = SampleBuilder.Album(TRIP_ID, t)
        fun at(day: Int, time: String) = SampleBuilder.at(start, ZONE, day, time)
        val entries = SampleBuilder.entries(
            TRIP_ID, ZONE, days, album, t,
            written = { d -> at(d.day, requireNotNull(d.time)) },
            taken = { d, pic, _ -> at(d.day, pic.time) },
        )
        val stops = listOf(
            SampleBuilder.stop(TRIP_ID, at(1, "15:00"), at(6, "09:30"), alfama, "Alfama, Lisbon", "PT", t),
            SampleBuilder.stop(TRIP_ID, at(6, "14:00"), at(DAYS, "11:00"), ribeira, "Ribeira, Porto", "PT", t),
        )
        return SampleArgentina.Demo(trip, items, entries, album.media, album.pictures, stops)
    }
}
