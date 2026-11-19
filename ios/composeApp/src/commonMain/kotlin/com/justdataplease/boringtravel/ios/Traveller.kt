package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.snapshotFlow
import com.justdataplease.boringtravel.clip.Rides
import com.justdataplease.boringtravel.clip.Traveller
import kotlinx.coroutines.flow.Flow

/*
 * Me › Your traveller & rides (round 20): the Android app's ui/me/TravellerSetting.kt is read in at build time and
 * compiled as it is; what it reaches of Android's AppGraph (graph.settings.traveller, rides, setTraveller, setRides) is
 * this app's settings here ([AppSettings.traveller], [AppSettings.rides]: a backup's "traveller" and "rides", in the
 * Android app's own strings), through the same names.
 */

/** Android's AppGraph as Me › Your traveller & rides reads it (the read-in file imports this as AppGraph). */
class TravellerGraph(app: AppSettings) {
    val settings: TravellerSettings = TravellerSettings(app)
}

/** The traveller and the rides every trip video uses unless a video has its own, as Android's Settings has them. */
class TravellerSettings(private val app: AppSettings) {
    /** Me › Your traveller, then each change. */
    val traveller: Flow<Traveller> = snapshotFlow { app.traveller }

    /** Me › Your traveller & rides' rides, then each change. */
    val rides: Flow<Rides> = snapshotFlow { app.rides }

    fun setTraveller(t: Traveller) {
        app.traveller = t
    }

    fun setRides(r: Rides) {
        app.rides = r
    }
}

/**
 * Where a video's own traveller or rides go when the editor's "Make default" makes them Me's (the Android app's
 * ClipViewModel writes them to its settings): this app's settings, or nowhere in previews.
 */
interface TravellerDefaults {
    fun saveTraveller(t: Traveller)

    fun saveRides(r: Rides)

    companion object {
        val NONE: TravellerDefaults = object : TravellerDefaults {
            override fun saveTraveller(t: Traveller) {}
            override fun saveRides(r: Rides) {}
        }

        /** Into [settings] (Me › Your traveller & rides). */
        fun of(settings: AppSettings): TravellerDefaults = object : TravellerDefaults {
            override fun saveTraveller(t: Traveller) {
                settings.traveller = t
            }

            override fun saveRides(r: Rides) {
                settings.rides = r
            }
        }
    }
}
