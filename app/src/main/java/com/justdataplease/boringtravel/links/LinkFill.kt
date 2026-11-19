package com.justdataplease.boringtravel.links

import com.justdataplease.boringtravel.calendar.CalendarImport
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanKind

/** What a link tells a plan item once it is known: its name, its place, its kind. Pure and unit tested. */
object LinkFill {
    const val MAX_TITLE = 100

    /** The name a link gives its item: a Google Maps place's without the address after it ("Yasaka Shrine, Kyoto"). */
    fun nameOf(url: String, raw: String?): String? =
        raw?.let { if (MapsLinks.isMaps(url)) it.substringBefore(',') else it }?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The name for [url]'s item from what its look found ([resolved]: the page's title, the Maps place's name) and
     * the words it was shared or pasted with ([shared]): for a Google Maps link the shared one first (the Maps app's,
     * in the traveller's language), for a site its own title first.
     */
    fun nameFor(url: String, resolved: String?, shared: String?): String? {
        val own = nameOf(url, resolved)
        val given = nameOf(url, shared)
        return if (MapsLinks.isMaps(url)) given ?: own else own ?: given
    }

    /**
     * Whether [title] is one the link [url] gave its item rather than the traveller's own: empty, a name its look
     * found or it was shared with ([names], read as [nameOf] reads them), or its placeholder
     * ([LinkPlaces.placeholderTitle]). Only such a title follows a new link (owner, 2026-11-13: "when I change website
     * need to update title and image").
     */
    fun fromLink(title: String, url: String, vararg names: String?): Boolean {
        val t = title.trim()
        if (t.isEmpty()) return true
        return t == LinkPlaces.placeholderTitle(url) || names.any { n -> nameOf(url, n)?.take(MAX_TITLE)?.trim() == t }
    }

    /** Food or Stay when the name says so plainly ("Hotel Granvia", "Taverna Lefteris"); else null (leave the kind). */
    fun obviousKind(name: String?): PlanKind? =
        name?.let { CalendarImport.guessKind(it) }?.takeIf { it == PlanKind.Food || it == PlanKind.Stay }

    /**
     * [item] with what the traveller left empty filled from its link: the place ([place]'s name and pin, unless
     * [placeCleared] or the item has one), the title ([name], else the place's first part) and, unless [kindTouched],
     * the kind when the name makes it obvious. What the traveller set stays as it is.
     */
    fun fill(item: PlanItem, name: String?, place: MapsPlace?, kindTouched: Boolean, placeCleared: Boolean = false): PlanItem {
        var d = if (placeCleared) item else LinkPlaces.fill(item, place)
        val n = name?.trim()?.take(MAX_TITLE)?.takeIf { it.isNotEmpty() }
        if (d.title.isBlank()) d = d.copy(title = n ?: d.placeName.substringBefore(',').trim())
        if (!kindTouched) obviousKind(n ?: d.title)?.let { d = d.copy(kind = it.id) }
        return d
    }
}
