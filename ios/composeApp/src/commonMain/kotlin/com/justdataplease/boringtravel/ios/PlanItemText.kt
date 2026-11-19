package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.tags.ItemTags
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.util.Dates
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * A plan item shared as text, as the Android app writes it (ui/trip/PlanItemText.kt, PlanItemActions.shareText), with
 * the two pieces of its links/MapsLinks.kt it needs (Android reads URLs with OkHttp and encodes them with Java).
 */

/**
 * A plan item as plain text for the share sheet: the name, when (a stay: its nights and check-out; anything else ending
 * on another day: until when), the address, the website, the Google Maps link (the item's own, else one to its pin or
 * address), how you get there ("train from Madrid"), the note and the tags, one line each; lines with nothing to say are
 * left out. Whether it is ticked off, the journal and the app's name are never in it.
 */
object PlanItemText {
    fun of(item: PlanItem, gettingThere: String? = null): String {
        val (url, mapsLink) = sortLinks(item.url?.trim()?.takeIf { it.isNotEmpty() }, item.mapsUrl?.trim()?.takeIf { it.isNotEmpty() })
        val note = item.note.trim().takeIf { it.isNotEmpty() }
        return listOfNotNull(
            item.title.trim(),
            start(item),
            end(item),
            item.placeName.trim().takeIf { it.isNotEmpty() && !it.equals(item.title.trim(), ignoreCase = true) },
            url?.let { "Website: $it" },
            (mapsLink ?: mapUrl(item))?.let { "Map: $it" },
            gettingThere?.trim()?.takeIf { it.isNotEmpty() }?.let { "Getting there: $it" },
            note?.let { if ('\n' in it) "Note:\n$it" else "Note: $it" },
            ItemTags.of(item).takeIf { it.isNotEmpty() }?.let(ItemTags::line),
        ).joinToString("\n")
    }

    /** "Sat 14 Mar 2027 · 10:00–13:00", "… · until 13:00", or with only what is known; null for an idea. */
    private fun start(item: PlanItem): String? {
        val day = item.day ?: return null
        val from = item.timeMinutes?.let(Dates::time)
        // An end later the same day goes on this line; one on another day gets its own ([end]).
        val until = item.endMinutes?.takeIf { PlanSpan.hasEnd(item) && PlanSpan.days(item) == 0 }?.let(Dates::time)
        val times = when {
            from != null && until != null -> "$from–$until"
            until != null -> "until $until"
            else -> from
        }
        return listOfNotNull(Dates.dayLong(day), times).joinToString(" · ")
    }

    /** A stay's "3 nights, check-out Tue 17 Mar · 11:00", else "Until Sun 15 Mar · 02:00" when it ends on a later day. */
    private fun end(item: PlanItem): String? {
        val endDay = PlanSpan.endDate(item) ?: return null
        val nights = PlanSpan.days(item)
        if (nights == 0) return null
        val at = listOfNotNull(dayMonth(endDay), item.endMinutes?.let(Dates::time)).joinToString(" · ")
        return if (item.kind == PlanKind.Stay.id) "${if (nights == 1) "1 night" else "$nights nights"}, check-out $at" else "Until $at"
    }

    private fun dayMonth(d: LocalDate) = "${Dates.weekday(d)} ${Dates.dayMonth(d)}"

    /** A Google Maps link to the item: its pin ("query=37.176,-3.588"), else its address as people search for it. */
    fun mapUrl(item: PlanItem): String? {
        val lat = item.lat
        val lng = item.lng
        if (lat != null && lng != null && lat.isFinite() && lng.isFinite()) {
            return "https://www.google.com/maps/search/?api=1&query=${coordinate(lat)},${coordinate(lng)}"
        }
        return MapsLinks.searchUrl(item.placeName.ifBlank { null }, null, null)
    }

    /** Up to 6 decimals (about 10 cm), without trailing zeros (Java's "%.6f" in the root locale, trimmed). */
    fun coordinate(v: Double): String {
        val micro = (abs(v) * 1_000_000).roundToLong()
        // Java keeps the sign of a small negative number that rounds to nothing ("-0.000000").
        val sign = if (v < 0 || (v == 0.0 && 1.0 / v < 0)) "-" else ""
        val text = "$sign${micro / 1_000_000}.${(micro % 1_000_000).toString().padStart(6, '0')}"
        return text.trimEnd('0').trimEnd('.')
    }

    /** How the item is reached: "train from Madrid", "on foot from Hotel Ritz". */
    fun gettingThere(vehicle: Vehicle, from: String): String {
        val how = when (vehicle) {
            Vehicle.Walk -> "on foot"
            Vehicle.Bike -> "bike"
            Vehicle.Car, Vehicle.Auto -> "car"
            Vehicle.Train -> "train"
            Vehicle.Ferry -> "ferry"
            Vehicle.Plane -> "flight"
        }
        return "$how from $from"
    }

    /** The item's website and Maps link as kept (links/LinkPlaces.sortLinks): a Maps link in the website is the Maps link. */
    fun sortLinks(url: String?, mapsUrl: String?): Pair<String?, String?> =
        if (url != null && MapsLinks.isMaps(url)) null to (mapsUrl ?: url) else url to mapsUrl
}

/** The whole item as text ([PlanItemText]), with how you get there when the plan has a leg into it. */
fun shareText(item: PlanItem, connection: PlanTravelConnection?, items: List<PlanItem>): String =
    PlanItemText.of(item, connection?.let { PlanItemText.gettingThere(it.mode, gettingThereFrom(it, items, item).first) })
