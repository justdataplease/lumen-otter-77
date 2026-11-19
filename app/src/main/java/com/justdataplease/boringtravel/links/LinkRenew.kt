package com.justdataplease.boringtravel.links

import com.justdataplease.boringtravel.data.db.PlanItem

/**
 * The item sheet's Renew details (owner, 2026-11-14: "how do you get the title or when it's updated, maybe a renew
 * details button; if it's manually edited don't change"): a plan item after its links were read again. Only what a
 * link gave is replaced: a title equal to what the link said before (or empty), a place on the pin the Maps link
 * gave. What the traveller typed or picked stays. Pure and unit tested.
 */
object LinkRenew {
    /** One link read again: what it said before ([oldTitle]), what it says now ([newTitle]), the words it came with ([shared]). */
    data class Look(val url: String, val oldTitle: String?, val newTitle: String?, val shared: String? = null)

    /**
     * [item] with what its links say now. [maps] and [site] are the looks at its Maps link and its website (null:
     * no such link); [oldPlace] and [place] the Maps link's place before and now. [placeCleared]: the traveller
     * removed the place, so it stays removed.
     */
    fun apply(item: PlanItem, placeCleared: Boolean, site: Look?, maps: Look?, oldPlace: MapsPlace?, place: MapsPlace?): PlanItem {
        var d = item
        if (!placeCleared && place != null && place.hasPoint) {
            d = if (LinkPlaces.placeFrom(d, oldPlace)) {
                // The link's own place follows it; a new look without a name keeps the name it had.
                LinkPlaces.follow(d, oldPlace, place).let { if (place.name.isNullOrBlank()) it.copy(placeName = d.placeName) else it }
            } else {
                LinkPlaces.fill(d, place)
            }
        }
        val title = d.title.trim()
        // The Maps link first (a place's name), then the website, as when the item was made.
        val name = listOfNotNull(maps, site)
            .filter { LinkFill.fromLink(title, it.url, it.oldTitle, it.shared, MapsLinks.parse(it.url)?.name, oldPlace?.name?.takeIf { _ -> it === maps }) }
            .firstNotNullOfOrNull { LinkFill.nameFor(it.url, it.newTitle, it.shared)?.take(LinkFill.MAX_TITLE)?.trim()?.takeIf { n -> n.isNotEmpty() } }
        if (name != null && name != title) d = d.copy(title = name)
        return d
    }
}
