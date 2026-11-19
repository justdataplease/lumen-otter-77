package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.programs.Destination
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.Program
import com.justdataplease.boringtravel.programs.ProgramTrips
import com.justdataplease.boringtravel.programs.data.KyotoOsaka
import java.time.LocalDate

/**
 * The example trip "Kyoto & Osaka": a trip still to come, in October about eleven months ahead. Its plan is the trip
 * idea's default program ([KyotoOsaka], Sights & history) at a balanced pace, built like any trip made from an idea
 * ([ProgramTrips]): the days with their places, travel modes and times, and the program's ideas. Nothing ticked, no
 * journal, no stays; nothing is booked.
 */
object SampleKyoto {
    const val TRIP_ID = SampleTrips.KYOTO
    val PACE = Pace.Balanced

    val destination: Destination get() = KyotoOsaka.destination
    val program: Program get() = destination.default
    val DAYS: Int get() = program.days.size
    val TITLE: String get() = destination.title

    /** 3 October next year. */
    fun start(today: LocalDate): LocalDate = LocalDate.of(today.year + 1, 10, 3)

    /** The trip and its plan from [start], stamped [t]. */
    fun plan(start: LocalDate, t: Long): ProgramTrips.Built =
        ProgramTrips.build(destination, program, PACE, start, t, links = LinkPlaces::sortLinks, tripId = TRIP_ID, trackingLevel = "off")

    /** The plan from [start]: nothing ticked, no journal, no stays yet. */
    fun demo(start: LocalDate, t: Long): SampleArgentina.Demo {
        val (trip, items) = plan(start, t)
        return SampleArgentina.Demo(trip, items, emptyList(), emptyList(), emptyMap(), emptyList())
    }
}
