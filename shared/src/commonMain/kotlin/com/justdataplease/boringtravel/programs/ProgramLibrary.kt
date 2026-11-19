package com.justdataplease.boringtravel.programs

import com.justdataplease.boringtravel.programs.data.ArgentinaPatagonia
import com.justdataplease.boringtravel.programs.data.KyotoOsaka
import com.justdataplease.boringtravel.programs.data.LisbonPorto

/** The trip ideas in the app, in the order Trip ideas lists them. All bundled: nothing is fetched. */
object ProgramLibrary {
    val destinations: List<Destination> get() = listOf(KyotoOsaka.destination, LisbonPorto.destination, ArgentinaPatagonia.destination)

    fun destination(id: String?): Destination? = id?.let { wanted -> destinations.firstOrNull { it.id == wanted } }
}
