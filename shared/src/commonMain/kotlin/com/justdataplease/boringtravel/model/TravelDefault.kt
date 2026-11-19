package com.justdataplease.boringtravel.model

import com.justdataplease.boringtravel.time.LocalDate

/**
 * How a planned leg is travelled when nobody chose: by car (owner, 2026-11-07: "have the car by default if the user
 * does not change it"), unless the destination is named for another way ("Fly to Mendoza", "Train to Nara",
 * "Ferry to Naxos", "Flight Athens → Madrid"), on foot between the items of one place (owner, 2026-11-09: "assume each
 * sub item by foot by default but you can also change that"), and by plane over long hops (owner, 2026-11-11: "if I am
 * in Athens and I have a flight to Madrid then the leg should take the airplane"). The saved choice always wins;
 * nothing here is stored. Pure, unit tested.
 */
object TravelDefault {
    /** Plan items of one place on one day at most this far apart (straight line) are walked between unless chosen otherwise. */
    const val WALK_MAX_M = 5_000.0

    /** Unchosen legs at least this long (straight line) fly: Athens–Madrid, Buenos Aires–Mendoza. Lisbon–Porto drives. */
    const val FLY_MIN_M = 700_000.0

    /** Word edges for every alphabet: `\b` only knows ASCII letters, so "Πτήση" would never end a word. */
    private const val START = "(?<![\\p{L}\\p{N}])"
    private const val END = "(?![\\p{L}\\p{N}])"

    /** The way a destination's name starts with, in the languages travellers here write in ("Fly to", "Πτήση για"). */
    private val leading = listOf(
        Regex("^(fly|flight|plane|πτήση|πτηση|vuelo|volo|vol|flug)$END", RegexOption.IGNORE_CASE) to Vehicle.Plane,
        Regex("^(train|shinkansen|rail|τρένο|τρενο|tren|treno|zug)$END", RegexOption.IGNORE_CASE) to Vehicle.Train,
        Regex("^(ferry|boat|πλοίο|πλοιο|φέρι|φερι|ferribot|barco|ferri|traghetto|fähre)$END", RegexOption.IGNORE_CASE) to Vehicle.Ferry,
        Regex("^(walk|hike|περπάτημα)$END", RegexOption.IGNORE_CASE) to Vehicle.Walk,
        Regex("^(bike|cycle|ποδήλατο)$END", RegexOption.IGNORE_CASE) to Vehicle.Bike,
    )

    /**
     * Anywhere in a travel item's name: "Athens → Madrid flight", "ATH → MAD ✈", "Night train to Vienna". Only for
     * travel items, so "Lunch by the train station" stays a car or a walk.
     */
    private val inTravelItem = listOf(
        Regex("✈|$START(flight|flights|fly|plane|airline|πτήση|πτηση|vuelo|volo|flug)$END", RegexOption.IGNORE_CASE) to Vehicle.Plane,
        Regex("$START(train|rail|railway|shinkansen|eurostar|τρένο|τρενο|tren|treno|zug|ave|renfe|intercity)$END", RegexOption.IGNORE_CASE) to Vehicle.Train,
        Regex("$START(ferry|boat|catamaran|πλοίο|πλοιο|φέρι|φερι|barco|traghetto|fähre)$END", RegexOption.IGNORE_CASE) to Vehicle.Ferry,
        // Airport codes on both sides of an arrow: "ATH -> MAD", "ATH→MAD".
        Regex("\\b[A-Z]{3}\\s*(?:->|→|–|-|>)\\s*[A-Z]{3}\\b") to Vehicle.Plane,
    )

    /**
     * Whether a leg adds to the trip's km (the clip's stats line and end card, a post's km): the traveller's own
     * "Count in km" when set ([explicit]), else every leg but a flight (owner, 2026-11-11: the flight to Madrid should
     * not count).
     */
    fun countsKm(vehicle: Vehicle, explicit: Boolean?): Boolean = explicit ?: (vehicle != Vehicle.Plane)

    /** The way to [destinationTitle] when nothing was chosen. */
    fun guess(destinationTitle: String, travelItem: Boolean = false): Vehicle = named(destinationTitle, travelItem) ?: Vehicle.Car

    /**
     * The way [destinationTitle] names ("Fly to Mendoza": by plane), or null when it names none. A travel item's name
     * ([travelItem]) counts wherever it says it ("Athens → Madrid flight").
     */
    fun named(destinationTitle: String, travelItem: Boolean = false): Vehicle? {
        val t = destinationTitle.trim()
        leading.firstOrNull { (pattern, _) -> pattern.containsMatchIn(t) }?.let { return it.second }
        if (!travelItem) return null
        return inTravelItem.firstOrNull { (pattern, _) -> pattern.containsMatchIn(t) }?.second
    }

    /**
     * The way to [destinationTitle] when nothing was chosen: what its name says, else on foot when the leg runs between
     * the items of one place ([samePlace]), else by plane when the leg is [FLY_MIN_M] or longer ([distanceM], straight
     * line), else by car. The plan's list and the trip video both go by this.
     */
    fun between(destinationTitle: String, samePlace: Boolean, distanceM: Double = Double.NaN, travelItem: Boolean = false): Vehicle =
        named(destinationTitle, travelItem) ?: when {
            samePlace -> Vehicle.Walk
            distanceM.isFinite() && distanceM >= FLY_MIN_M -> Vehicle.Plane
            else -> Vehicle.Car
        }

    /**
     * Whether a leg runs between two items of one place (its "sub items": the cathedral and the lanes of Lisbon on one
     * day): both in the place named [fromPlace] and [toPlace] (the town the plan and the video name them by, compared
     * without case), on the same day (or both undated), and at most [WALK_MAX_M] apart ([distanceM], straight line; an
     * airport out of town is not walked from).
     */
    fun samePlace(fromPlace: String?, toPlace: String?, fromDay: LocalDate?, toDay: LocalDate?, distanceM: Double): Boolean {
        val a = fromPlace?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val b = toPlace?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return a.equals(b, ignoreCase = true) && fromDay == toDay && distanceM.isFinite() && distanceM <= WALK_MAX_M
    }
}
