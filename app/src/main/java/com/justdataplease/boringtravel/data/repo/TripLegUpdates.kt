package com.justdataplease.boringtravel.data.repo

import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.Vehicle

/** Fresh reads and pair validation must happen after acquiring the database transaction. */
internal object TripLegUpdates {
    suspend fun set(
        key: RouteLegKey,
        vehicle: Vehicle?,
        transaction: suspend (suspend () -> Trip?) -> Trip?,
        read: suspend () -> Trip?,
        activeKeys: suspend (Trip) -> Set<RouteLegKey>,
        write: suspend (Trip) -> Unit,
        clock: () -> Long,
    ): Trip? = update(key, { TravelModes.set(it, key, vehicle) }, transaction, read, activeKeys, write, clock)

    /** The leg's "Count in km" ([TravelModes.setKm]; null = the default), checked like [set]. */
    suspend fun setKm(
        key: RouteLegKey,
        count: Boolean?,
        transaction: suspend (suspend () -> Trip?) -> Trip?,
        read: suspend () -> Trip?,
        activeKeys: suspend (Trip) -> Set<RouteLegKey>,
        write: suspend (Trip) -> Unit,
        clock: () -> Long,
    ): Trip? = update(key, { TravelModes.setKm(it, key, count) }, transaction, read, activeKeys, write, clock)

    private suspend fun update(
        key: RouteLegKey,
        change: (String?) -> String?,
        transaction: suspend (suspend () -> Trip?) -> Trip?,
        read: suspend () -> Trip?,
        activeKeys: suspend (Trip) -> Set<RouteLegKey>,
        write: suspend (Trip) -> Unit,
        clock: () -> Long,
    ): Trip? = transaction {
        val current = read()?.takeIf { it.deletedAt == null } ?: return@transaction null
        check(key in activeKeys(current)) { "This route leg changed. Reopen it and choose again." }
        val json = change(current.travelModes)
        if (json == current.travelModes) current else current.copy(
            travelModes = json, updatedAt = maxOf(clock(), current.updatedAt + 1),
        ).also { write(it) }
    }

    /** Ordinary title/date/tracking edits must not overwrite newer leg choices from a stale screen snapshot. */
    fun preserveChoices(edit: Trip, current: Trip?): Trip = if (current == null) edit else edit.copy(travelModes = current.travelModes)
}
