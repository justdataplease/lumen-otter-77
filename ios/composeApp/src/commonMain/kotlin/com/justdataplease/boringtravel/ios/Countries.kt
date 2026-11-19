package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.geo.Countries
import com.justdataplease.boringtravel.globe.GlobeGeometry
import com.justdataplease.boringtravel.ios.resources.Res

/*
 * A place's country without the network, as the Android app finds it: its own geo/Countries.kt (the outlines and the
 * lookup on them) and geo/CountryInfo.kt (the key a country is counted under, its continent, flag pin, flag and name),
 * read in at build time. The outlines are the Android app's own file (assets/geo/countries.json, Natural Earth
 * 1:110m), read in at build time too.
 */

/**
 * The country's name in the phone's language (Foundation's on iPhone, Java's on the desktop); the code when unknown.
 * The Android app's CountryInfo.name reads Java's Locale here.
 */
expect fun countryName(code: String): String

/** The bundled outlines and the globe's shapes made from them, each read or built once (the file is 150 KB). */
object CountryOutlines {
    /** The outlines once read; null before [load], or when the file can't be read. */
    var loaded: Countries? = null
        private set

    private var geometry: GlobeGeometry? = null

    suspend fun load(): Countries? = loaded ?: runCatching { Countries.parse(Res.readBytes("files/geo/countries.json").decodeToString()) }.getOrNull()?.also { loaded = it }

    /** The globe's unit-vector geometry (the Android app's WorldSource.geometry), built on first use. */
    suspend fun geometry(): GlobeGeometry? = geometry ?: load()?.let(GlobeGeometry::from)?.also { geometry = it }
}
