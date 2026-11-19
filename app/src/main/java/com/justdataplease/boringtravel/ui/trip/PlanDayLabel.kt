package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.ui.place.PlaceGrouping
import java.time.LocalDate

/**
 * Where a plan day is, for "Day 13 · El Chaltén" when neither the stops nor the items' place names say: the
 * destination of the latest travel item on or before that day ("Transfer to El Chaltén" → "El Chaltén"), so the days
 * after an arrival keep its name. Travel items without a destination ("Fly home") are skipped. Null when the plan has
 * no such item yet.
 */
object PlanDayLabel {
    fun label(day: LocalDate, items: List<PlanItem>): String? = PlanSpan.ordered(items.filter { it.deletedAt == null })
        .filter { it.kind == PlanKind.Transport.id && it.day?.isAfter(day) == false }
        .mapNotNull { item -> PlaceGrouping.itemPlaceLabel(item).takeIf { it != item.title.trim() || item.placeName.isNotBlank() } }
        .lastOrNull()
}
