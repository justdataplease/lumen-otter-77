package com.justdataplease.boringtravel.clip

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How close the clip's follow camera gets (owner, 2026-11-07: "in video can the zoom be more adjustable especiallye
 * earth maybe i would need a better zoom static to show the teritories very near the building right?").
 *
 * [look] scales the stretch of route kept in view around the head (the seconds ahead and behind, see [FollowCamera]),
 * so a fast leg still shows where it goes; [floorM] is the closest the camera comes, as ground metres on the frame's
 * short side. The map's sharpest zoom ([MapStyle.maxZoom], see [ZoomLimits]) can keep it higher: Earth's pictures
 * stop at about 3 km, the street maps go down to a few buildings. [Route] holds the whole route in view throughout.
 * [Normal] is the camera the clip always had. [followM]: the ground on the short side the Follow camera
 * ([CameraMode.Chase]) holds, whatever the speed.
 */
enum class ClipZoom(val id: String, val label: String, val look: Double, val floorM: Double, val followM: Double) {
    Route("route", "Whole route", Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
    Far("far", "Far", 2.2, 2_800.0, 150_000.0),
    Normal("normal", "Normal", 1.0, ClipPlan.MIN_VIEW_M, 40_000.0),
    Close("close", "Close", 0.55, 550.0, 12_000.0),
    VeryClose("veryclose", "Very close", 0.3, 250.0, 4_000.0);

    /** [steps] closer than this (negative: wider), within the range. */
    fun step(steps: Int): ClipZoom = entries[(ordinal + steps).coerceIn(0, entries.lastIndex)]

    companion object {
        fun of(id: String?): ClipZoom? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How far the camera pulls back (owner, 2026-11-09, on the globe: "can i select the clip zoom", "when zoom out to fix a
 * level to zoom out"): the view the clip opens and closes on, which is also the widest the camera goes during the
 * journey (a long hop or a flight lifts it at most this far). [Auto]: from space for a far or foreign trip, else the
 * region around the route, as the clip always did; [Space]: the globe from space (a flat map: the route's view five
 * times wider); [Region]: the region around the route; [Route]: just the whole route.
 */
enum class ZoomOut(val id: String, val label: String) {
    Auto("auto", "Auto"), Space("space", "Space"), Region("region", "Region"), Route("route", "Whole route");

    companion object {
        fun of(id: String?): ZoomOut? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How the camera moves (owner, 2026-11-08: "the camera zooms out and in … i want strava like effects, this looks
 * awkward at some moments"). [Steady], the default, holds one height for each stretch of the trip (a day, or a stretch
 * with one vehicle; [SteadyCamera]) and keeps the traveller in frame by moving, never by zooming: the height changes
 * once between stretches, eased. [Follow] is the adaptive camera the clip had before ([FollowCamera]): it widens and
 * narrows with the traveller's speed on screen.
 */
enum class CameraMode(val id: String, val label: String) {
    Steady("steady", "Steady"),
    Follow("follow", "Follow"),
    /**
     * Stays at the height How close asks ([ClipZoom.followM]) and follows the traveller however fast (owner,
     * 2026-11-09: "maybe i have a close by region camera that just follows whatever"); only flights lift, as with the
     * steady camera. The editor's "Follow" ([SteadyCamera] with chase on).
     */
    Chase("chase", "Follow");

    companion object {
        fun of(id: String?): CameraMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A close, still view at stops: the journey waits at the place while the camera moves in, holds and pulls back out
 * (during the photo pause when the place has one). [floorM]: how close, as ground metres on the frame's short side.
 */
enum class CloseUp(val id: String, val label: String, val floorM: Double) {
    Off("off", "Off", Double.POSITIVE_INFINITY),
    Close("close", "Close", 500.0),
    VeryClose("veryclose", "Very close", 200.0);

    val on: Boolean get() = this != Off

    companion object {
        fun of(id: String?): CloseUp? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What the plan needs from the scene editor's camera changes, resolved against the global style: each place name
 * key's zoom and close-up (see [ClipDesign.zoom], [ClipDesign.closeUp]).
 */
data class CameraEdits(
    val zoom: Map<String, ClipZoom> = emptyMap(),
    val closeUp: Map<String, CloseUp> = emptyMap(),
)

/**
 * How close each map can honestly go. The clip draws the map at one design dp per map point with a 360 dp short side
 * ([ClipPlan.DESIGN_SHORT_DP]), so map zoom z shows 360 / (256 · 2^z) of the world across the short side. Past
 * [MapStyle.maxZoom] the map's pictures only get blurrier, so the camera stops there: the one place that decides is
 * the map's source in MapStyles.kt (a sharper satellite source would only change its number).
 */
object ZoomLimits {
    /** World units across the frame's short side at map zoom [zoom]. */
    fun shortSpan(zoom: Double): Double = ClipPlan.DESIGN_SHORT_DP / (ClipPlan.MAP_TILE_PX * 2.0.pow(zoom))

    /** Ground metres across the frame's short side at [map]'s sharpest zoom, at world row [y]. */
    fun closestM(map: MapStyle, y: Double): Double = shortSpan(map.maxZoom) / Mercator.unitsPerMeter(y)

    /** Whether [map] stops the camera clearly short of [floorM] (metres on the short side) at world row [y]. */
    fun limits(map: MapStyle, floorM: Double, y: Double): Boolean = floorM.isFinite() && floorM < closestM(map, y) * 0.85

    /** The maps that do go as close as [floorM] at [y]. */
    fun closerMaps(floorM: Double, y: Double): List<MapStyle> = MapStyle.entries.filter { !limits(it, floorM, y) }

    /**
     * The plain note under the zoom when [map] can't go as close as [floorM] at [y]: how close it goes and which maps
     * go closer ("Earth's pictures blur closer than about 2.6 km across. Street and Terrain go closer."); null when
     * it can.
     */
    fun note(map: MapStyle, floorM: Double, y: Double, locale: Locale): String? {
        if (!limits(map, floorM, y)) return null
        val m = closestM(map, y)
        val size = if (m >= 1_000) ClipText.km(m / 1_000, locale) else "${(m / 50).roundToInt() * 50} m"
        val others = closerMaps(floorM, y).map { it.label }
        val more = when (others.size) {
            0 -> ""
            1 -> " ${others[0]} goes closer."
            else -> " ${others.dropLast(1).joinToString(", ")} and ${others.last()} go closer."
        }
        return "${map.label}'s pictures blur closer than about $size across.$more"
    }
}
