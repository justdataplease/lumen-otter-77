package com.justdataplease.boringtravel.programs

import com.justdataplease.boringtravel.art.SceneKind
import com.justdataplease.boringtravel.art.ScenePick
import com.justdataplease.boringtravel.programs.data.ArgentinaPatagonia
import com.justdataplease.boringtravel.programs.data.KyotoOsaka
import com.justdataplease.boringtravel.programs.data.LisbonPorto

/**
 * The drawn picture on a destination's card: streets for Kyoto & Osaka, the coast for Lisbon & Porto, mountains
 * (Fitz Roy) for Argentina & Patagonia, so no two look alike; any other destination by its words, then its country.
 * Beside [ProgramLibrary] (the engine's list of destinations) because the scenes are drawn by Android code.
 */
fun ProgramLibrary.scene(destination: Destination): SceneKind = when (destination.id) {
    KyotoOsaka.destination.id -> SceneKind.City
    LisbonPorto.destination.id -> SceneKind.Coast
    ArgentinaPatagonia.destination.id -> SceneKind.Mountain
    else -> ScenePick.forTrip(destination.id, destination.title, destination.blurb, destination.countryCodes.firstOrNull())
}
