package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.clip.RouteLeg
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.place.PlaceGrouping
import java.time.LocalDate

/** The leg's chosen vehicle, else [TravelDefault] (on foot between the items of one place); the trip video goes by the same. */
val RouteLeg.travel: Vehicle get() = vehicle?.takeUnless { it == Vehicle.Auto } ?: TravelDefault.between(toTitle, samePlace, distanceM, toTravel)

/** Whether the leg adds to the trip's km: its own "Count in km", else every leg but a flight ([TravelDefault.countsKm]). */
val RouteLeg.countsKm: Boolean get() = TravelDefault.countsKm(travel, countKm)

/**
 * The plan item the day title ("Day 1 · Buenos Aires") opens the place page on (owner, 2026-11-07: the city page opens
 * from the day's title, not from "Fly to Buenos Aires"): the day's first located item that is not travel and lies in
 * the title's place, else the day's arrival into that place, else the day's first located item that is not travel,
 * else the day's last located item, else the last
 * located item before the day (a quiet day where you already are). Null: nothing to open. Pure, unit tested.
 */
object PlanDayAnchor {
    fun item(day: LocalDate, items: List<PlanItem>, place: String?): PlanItem? {
        // Plan order over the whole plan first ([PlanSpan.ordered]), then the items that can anchor a page.
        val live = PlanSpan.ordered(items.filter { it.deletedAt == null })
            .filter { it.kind != PlanKind.Note.id && PlaceGrouping.located(it) }
        val today = live.filter { it.day == day }
        val stays = today.filter { it.kind != PlanKind.Transport.id }
        if (place != null) {
            fun inPlace(item: PlanItem) = PlaceGrouping.placeOf(items, item).equals(place, ignoreCase = true)
            stays.firstOrNull(::inPlace)?.let { return it }
            // The arrival the title is named after ("Fly to Mendoza") beats a lodge out of town.
            today.lastOrNull { it.kind == PlanKind.Transport.id && inPlace(it) }?.let { return it }
        }
        stays.firstOrNull()?.let { return it }
        today.lastOrNull()?.let { return it }
        return live.lastOrNull { it.day?.isBefore(day) == true }
    }
}
