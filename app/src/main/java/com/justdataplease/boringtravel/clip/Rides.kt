package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.model.Vehicle

/**
 * The paper models a vehicle can be drawn as in the trip videos (owner, 2026-11-08: "change cars suv city van
 * aircraft", "all avatars editable"). At most four per vehicle; the first of each is the default and today's look
 * ([PaperVehicleMeshes]). Stored by [id], so labels can change.
 */
enum class RideModel(val vehicle: Vehicle, val id: String, val label: String) {
    Hatchback(Vehicle.Car, "hatch", "Hatchback"),
    Suv(Vehicle.Car, "suv", "SUV"),
    Camper(Vehicle.Car, "camper", "Camper van"),
    Convertible(Vehicle.Car, "convertible", "Convertible"),
    Bicycle(Vehicle.Bike, "bicycle", "Bicycle"),
    Scooter(Vehicle.Bike, "scooter", "Scooter"),
    Motorbike(Vehicle.Bike, "motorbike", "Motorbike"),
    Steam(Vehicle.Train, "steam", "Steam"),
    HighSpeed(Vehicle.Train, "highspeed", "High-speed"),
    Regional(Vehicle.Train, "regional", "Regional"),
    Tram(Vehicle.Train, "tram", "Tram"),
    Ferry(Vehicle.Ferry, "ferry", "Ferry"),
    Sailboat(Vehicle.Ferry, "sailboat", "Sailboat"),
    Speedboat(Vehicle.Ferry, "speedboat", "Speedboat"),
    Cruise(Vehicle.Ferry, "cruise", "Cruise ship"),
    Airliner(Vehicle.Plane, "airliner", "Airliner"),
    Prop(Vehicle.Plane, "prop", "Small plane"),
    Seaplane(Vehicle.Plane, "seaplane", "Seaplane"),
    Helicopter(Vehicle.Plane, "helicopter", "Helicopter");

    companion object {
        /** [vehicle]'s models, the default first (none for Walk and Auto). */
        fun of(vehicle: Vehicle): List<RideModel> = entries.filter { it.vehicle == vehicle }

        /** [vehicle]'s default model (today's look), or null for Walk and Auto. */
        fun default(vehicle: Vehicle): RideModel? = entries.firstOrNull { it.vehicle == vehicle }
    }
}

/**
 * One vehicle's look: its [model] and two colours, [body] (car body, fuselage, hull) and [trim] (roof, stripe, tail,
 * seats), each from the traveller's cloth swatches or null for the theme's (today's look). Glass, tyres and ink always
 * stay the theme's.
 */
data class Ride(val model: RideModel, val body: Cloth? = null, val trim: Cloth? = null) {
    val vehicle: Vehicle get() = model.vehicle

    /** Today's look: the default model in the theme's colours. */
    val isDefault: Boolean get() = model == RideModel.default(vehicle) && body == null && trim == null

    /** "model:suv,body:red,trim:theme". */
    fun encode(): String = "model:${model.id},body:${body?.id ?: THEME},trim:${trim?.id ?: THEME}"

    companion object {
        /** A colour's id when it is the theme's. */
        const val THEME = "theme"

        /** [vehicle]'s default ride, or null for Walk and Auto. */
        fun default(vehicle: Vehicle): Ride? = RideModel.default(vehicle)?.let { Ride(it) }

        /** Reads [encode]'s string over [fallback]: a missing or unknown value (another vehicle's model) keeps the fallback's. */
        fun decode(raw: String?, fallback: Ride): Ride {
            if (raw.isNullOrBlank()) return fallback
            val m = raw.split(',').mapNotNull { part ->
                val i = part.indexOf(':')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }.toMap()
            fun colour(key: String, default: Cloth?): Cloth? = when (val v = m[key]) {
                null -> default
                THEME -> null
                else -> Cloth.entries.firstOrNull { it.id == v } ?: default
            }
            return Ride(
                model = RideModel.of(fallback.vehicle).firstOrNull { it.id == m["model"] } ?: fallback.model,
                body = colour("body", fallback.body),
                trim = colour("trim", fallback.trim),
            )
        }
    }
}

/**
 * How every vehicle looks in the trip videos (Me › Your traveller & rides, the default for every video; a video can
 * have its own set, [ClipEdits.rides]). Pure, so it runs on the JVM; drawn by [PaperVehicle3D].
 */
data class Rides(
    val car: Ride = Ride(RideModel.Hatchback),
    val bike: Ride = Ride(RideModel.Bicycle),
    val train: Ride = Ride(RideModel.Steam),
    val boat: Ride = Ride(RideModel.Ferry),
    val plane: Ride = Ride(RideModel.Airliner),
) {
    init {
        require(car.vehicle == Vehicle.Car && bike.vehicle == Vehicle.Bike && train.vehicle == Vehicle.Train &&
            boat.vehicle == Vehicle.Ferry && plane.vehicle == Vehicle.Plane) { "A ride in another vehicle's place" }
    }

    /** [vehicle]'s ride, or null for Walk (the traveller) and Auto. */
    operator fun get(vehicle: Vehicle): Ride? = when (vehicle) {
        Vehicle.Car -> car
        Vehicle.Bike -> bike
        Vehicle.Train -> train
        Vehicle.Ferry -> boat
        Vehicle.Plane -> plane
        Vehicle.Walk, Vehicle.Auto -> null
    }

    /** [ride] in its vehicle's place. */
    fun with(ride: Ride): Rides = when (ride.vehicle) {
        Vehicle.Car -> copy(car = ride)
        Vehicle.Bike -> copy(bike = ride)
        Vehicle.Train -> copy(train = ride)
        Vehicle.Ferry -> copy(boat = ride)
        Vehicle.Plane -> copy(plane = ride)
        Vehicle.Walk, Vehicle.Auto -> this
    }

    /** Every ride, in [VEHICLES] order. */
    val all: List<Ride> get() = listOf(car, bike, train, boat, plane)

    /** "car=model:suv,body:red,trim:theme;bike=…" — every value an id, so new options never break an old string. */
    fun encode(): String = all.joinToString(";") { "${it.vehicle.id}=${it.encode()}" }

    companion object {
        /** Today's look: every default model in the theme's colours. */
        val DEFAULT = Rides()

        /** The vehicles that have rides, in the order the editors list them. */
        val VEHICLES: List<Vehicle> = listOf(Vehicle.Car, Vehicle.Bike, Vehicle.Train, Vehicle.Ferry, Vehicle.Plane)

        /** Reads [encode]'s string; anything missing or unknown is the default (null and blank: [DEFAULT]). */
        fun decode(raw: String?): Rides {
            if (raw.isNullOrBlank()) return DEFAULT
            val parts = raw.split(';').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }.toMap()
            return VEHICLES.fold(DEFAULT) { rides, v ->
                val fallback = rides[v] ?: return@fold rides
                rides.with(Ride.decode(parts[v.id], fallback))
            }
        }

        /** A stored per-video choice: null when there is none or it can't be read (the default then applies). */
        fun decodeOrNull(raw: String?): Rides? =
            raw?.takeIf { s -> s.isNotBlank() && s.length <= 2_000 && VEHICLES.any { s.contains("${it.id}=") } }?.let(::decode)
    }
}
