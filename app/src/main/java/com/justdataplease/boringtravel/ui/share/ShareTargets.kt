package com.justdataplease.boringtravel.ui.share

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.links.LinkFill
import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.links.LinkPreview
import com.justdataplease.boringtravel.links.MapsLinks
import com.justdataplease.boringtravel.links.MapsPlace
import com.justdataplease.boringtravel.links.SharedLink
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.ui.trip.PlanOrder
import java.time.LocalDate

/** The trips a shared link can go to: the traveller's own, or the examples when there are no others ([demo]). */
data class ShareTrips(val trips: List<Trip>, val demo: Boolean)

/** Where a link shared into the app goes, and the item it becomes (Add to plan). Pure and unit tested. */
object ShareTargets {
    /** The kinds offered for a shared place. */
    val kinds = listOf(PlanKind.Place, PlanKind.Food, PlanKind.Stay)

    /** The traveller's own trips; only when there are none, the example trips (never both). */
    fun trips(all: List<Trip>): ShareTrips {
        val live = all.filter { it.deletedAt == null }
        val own = live.filterNot { it.isDemo }
        val demo = live.filter { it.isDemo }
        return if (own.isEmpty() && demo.isNotEmpty()) ShareTrips(demo, demo = true) else ShareTrips(own, demo = false)
    }

    /**
     * The trip happening [today] (the one that started last), else the next to start, else one without dates yet
     * (the newest), else the latest (the last to end).
     */
    fun defaultTrip(trips: List<Trip>, today: LocalDate): Trip? =
        trips.filter { it.isCurrent(today) }.maxWithOrNull(compareBy<Trip>({ it.startDate }, { it.createdAt }))
            ?: trips.filter { it.startDate?.isAfter(today) == true }.minWithOrNull(compareBy<Trip>({ it.startDate }, { -it.createdAt }))
            ?: trips.filter { it.startDate == null }.maxByOrNull { it.createdAt }
            ?: trips.maxWithOrNull(compareBy<Trip>({ it.endDate ?: it.startDate }, { it.createdAt }))

    /** Today when [trip] is on, else its first day; null (its ideas) for a trip without dates. */
    fun defaultDay(trip: Trip, today: LocalDate): LocalDate? = if (trip.isCurrent(today)) today else trip.startDate

    /** The kind first offered: Food or Stay when the name says so, else Place. */
    fun defaultKind(name: String?): PlanKind = LinkFill.obviousKind(name) ?: PlanKind.Place

    /**
     * The name to give the item: for a Google Maps link the name the Maps app shared it with (in the traveller's
     * language), else the one in the link; for a site its own title, else the one shared with it.
     */
    fun name(link: SharedLink, preview: LinkPreview?): String? =
        LinkFill.nameFor(link.url, resolved = preview?.title ?: MapsLinks.parse(link.url)?.name, shared = link.name)

    /**
     * The plan item [link] becomes: at the end of [day] in [trip], of [kind], titled by its [name] (else
     * [LinkPlaces.placeholderTitle]), with the Google Maps place when the pin is known by now (else it is filled after
     * saving, with its name: [LinkPlaces.fillLater]).
     */
    fun item(
        id: String,
        trip: Trip,
        day: LocalDate?,
        kind: PlanKind,
        link: SharedLink,
        preview: LinkPreview?,
        dayItems: List<PlanItem>,
        now: Long,
    ): PlanItem {
        val name = name(link, preview)
        val place = MapsLinks.parse(link.url)?.takeIf { it.hasPoint }
            ?: preview?.takeIf { MapsLinks.isMaps(link.url) && it.lat != null && it.lng != null }?.let { MapsPlace(name, it.lat, it.lng) }
        // A Google Maps link is the place's Maps link, any other the website.
        val blank = LinkPlaces.sortLinks(PlanItem(
            id = id, tripId = trip.id, day = day, position = PlanOrder.end(dayItems.filter { it.day == day }),
            kind = kind.id, title = "", url = link.url, createdAt = now, updatedAt = now,
        ))
        val filled = LinkFill.fill(blank, name, place, kindTouched = true)
        return filled.copy(title = filled.title.ifBlank { LinkPlaces.placeholderTitle(link.url) })
    }
}
