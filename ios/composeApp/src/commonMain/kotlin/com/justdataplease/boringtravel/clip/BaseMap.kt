package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.compat.Context
import com.justdataplease.boringtravel.compat.edit
import com.justdataplease.boringtravel.compat.graphics.Bitmap
import com.justdataplease.boringtravel.globe.GlobeGeometry
import com.justdataplease.boringtravel.globe.GlobePin

/*
 * The model half of the Android app's clip/BaseMap.kt, word for word: a map picture with the world rectangle it
 * covers, the map under a clip and the globe under a Globe clip. Fetching the pictures (MapLibre's snapshotter on
 * Android) is ios/ClipMaps.kt here.
 */

/** A base-map picture and the world rectangle it covers (measured from the picture itself). */
class MapLayer(val bitmap: Bitmap, val rect: WorldRect)

/**
 * The map under a clip: the first layer covers the whole clip, later ones are closer views (coarse to sharp). No
 * layers means no map could be fetched (offline): the renderer then draws a plain sea-coloured background with a
 * soft grid. [style] is the map look the layers were made with (its sources are credited on the clip). A Globe clip
 * also has [globe] (the app's globe, bundled, so it is there offline too); its layers are the street map draped over
 * the globe close in, covering only the frames that come that close.
 */
class BaseMap(val layers: List<MapLayer>, val style: MapStyle = MapStyle.Street, val globe: GlobeBase? = null) {
    val hasMap: Boolean get() = layers.isNotEmpty() || globe != null

    /** The credit lines for what is drawn: the map's sources (the globe's shapes alone without its street map), nothing without a map. */
    val credits: List<String>
        get() = when {
            globe != null && layers.isEmpty() -> listOf(GlobeShapes.CREDIT)
            hasMap -> style.credits
            else -> emptyList()
        }

    /** The same street map pictures with [globe] under them. */
    fun withGlobe(globe: GlobeBase?): BaseMap = if (globe == null) this else BaseMap(layers, MapStyle.Globe, globe)

    companion object {
        val Plain = BaseMap(emptyList())
    }
}

/**
 * The globe under a Globe clip: the Map tab's country shapes ([GlobeGeometry]), the trip's countries (counted codes,
 * in the order the route reaches them; filled in the look's highlighter) and their flag pins.
 */
class GlobeBase(val geometry: GlobeGeometry, val countries: List<String>, val pins: List<GlobePin>)

/**
 * The last map look chosen for the clip and for the trip map, kept on this phone (the "map_style" settings file, which
 * this app keeps as a backup carries it: ios/Settings.kt's mapStyle; the trip map's own is read there too).
 */
object MapStylePrefs {
    const val CLIP = "clip"
    const val TRIP_MAP = "trip_map"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("map_style", Context.MODE_PRIVATE)

    /**
     * The trip map never gets Globe (a clip-only look); old prefs read as they were. A clip with nothing picked yet
     * starts on Globe (owner, 2026-11-08: "keep the best like globe by default").
     */
    fun load(context: Context, place: String): MapStyle {
        val saved = prefs(context).getString(place, null)
        if (saved == null && place == CLIP) return MapStyle.Globe
        return MapStyle.of(saved).let { if (place == TRIP_MAP && it !in MapStyle.tripMap) MapStyle.Street else it }
    }

    /** Forgets the clip's map, so the next clip starts on the default ([ClipPrefs.newDefaults]). */
    fun forgetClip(context: Context) {
        prefs(context).edit { remove(CLIP) }
    }

    fun save(context: Context, place: String, style: MapStyle) {
        prefs(context).edit().putString(place, style.id).apply()
    }
}
