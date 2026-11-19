package com.justdataplease.boringtravel.ui.nav

/*
 * The bottom bar's two modes and the back-stack rules around a trip, pure so they can be unit tested. AppNav turns the
 * current screen into a [Where], asks [TripNav.bar] what the bar shows and draws it; AppActions asks [TripNav.open]
 * how to open a trip.
 *
 * Outside a trip the bar is Trips · Calendar · Map · Community · Me. Inside a trip it is that trip's Plan · Journal · Map · Media:
 * the four are tabs of one screen (one back-stack entry), so system back leaves the trip in one step, back to where it
 * was opened from (Trips, Calendar, Map), never through the tabs.
 */

/** The bar's items outside a trip. */
enum class HomeTab(val label: String) {
    Trips("Trips"),
    Calendar("Calendar"),
    Map("Map"),
    Community("Community"),
    Me("Me"),
}

/** The bar's items inside a trip; [key] is what [TripDetail.tab] and the trip's saved state store. */
enum class TripTab(val key: String, val label: String) {
    Plan("plan", "Plan"),
    Journal("journal", "Journal"),
    Map("map", "Map"),
    Media("media", "Media");

    companion object {
        fun of(key: String?): TripTab? = entries.firstOrNull { it.key == key }
    }
}

/** Where the traveller is, as far as the bar cares. */
sealed interface Where {
    /** One of the four screens outside a trip. */
    data class Home(val tab: HomeTab) : Where

    /** A page that belongs under a home tab: Your world under Map, Notes outside trips under Trips, a post under Community. */
    data class Under(val tab: HomeTab) : Where

    /** A trip, on [tab]. */
    data class InTrip(val tripId: String, val tab: TripTab) : Where

    /** A place page of trip [tripId], opened over [below] (the trip itself, or the Map tab). */
    data class Place(val tripId: String, val below: Where?) : Where

    /** A screen without the bar (Write, an entry, clip, book, settings pages …). */
    data object Elsewhere : Where
}

/** What the bar shows. */
sealed interface BarMode {
    data object Hidden : BarMode

    data class Outside(val selected: HomeTab) : BarMode

    data class Trip(val tripId: String, val selected: TripTab) : BarMode
}

object TripNav {
    /** The bar for [where]: a place page keeps the bar of what it was opened from. */
    fun bar(where: Where): BarMode = when (where) {
        is Where.Home -> BarMode.Outside(where.tab)
        is Where.Under -> BarMode.Outside(where.tab)
        is Where.InTrip -> BarMode.Trip(where.tripId, where.tab)
        is Where.Place -> when (val b = where.below) {
            is Where.InTrip -> if (b.tripId == where.tripId) BarMode.Trip(b.tripId, b.tab) else BarMode.Outside(HomeTab.Trips)
            is Where.Home -> BarMode.Outside(b.tab)
            is Where.Under -> BarMode.Outside(b.tab)
            else -> BarMode.Outside(HomeTab.Trips)
        }
        Where.Elsewhere -> BarMode.Hidden
    }

    /** The bar's labels in [mode], in order (empty when it is hidden). */
    fun labels(mode: BarMode): List<String> = when (mode) {
        BarMode.Hidden -> emptyList()
        is BarMode.Outside -> HomeTab.entries.map { it.label }
        is BarMode.Trip -> TripTab.entries.map { it.label }
    }

    /** The index of the selected item in [labels] (-1 when hidden). */
    fun selectedIndex(mode: BarMode): Int = when (mode) {
        BarMode.Hidden -> -1
        is BarMode.Outside -> mode.selected.ordinal
        is BarMode.Trip -> mode.selected.ordinal
    }

    /**
     * The tab a trip opens on: the one asked for by name (today's journal from the widget, the map from the Map), else
     * Plan, always (owner, 2026-11-12: "No always return to plan"; it used to reopen on the tab last used).
     */
    fun landingTab(asked: String?): TripTab = TripTab.of(asked) ?: TripTab.Plan

    /** How to open a trip. */
    enum class Open {
        /** The trip is already open: go back to it (closing what is over it) and show the tab asked for. */
        Reuse,

        /** Another trip is open: it closes, so back from the new one goes where the first one was opened from. */
        Replace,

        /** No trip is open: push it over the current screen. */
        Push,
    }

    /** Opening trip [target] while [open] is the trip on the back stack (at most one is, see [Open]). */
    fun open(target: String, open: String?): Open = when (open) {
        null -> Open.Push
        target -> Open.Reuse
        else -> Open.Replace
    }

    /** What a tap on the bar does outside a trip. */
    enum class HomeTap {
        /** Already there: nothing. */
        Stay,

        /** On a page under the tapped tab (Your world, a place, Notes outside trips): back to the tab itself. */
        BackToTab,

        /** Another tab. */
        Switch,
    }

    fun homeTap(where: Where, tapped: HomeTab): HomeTap {
        val selected = (bar(where) as? BarMode.Outside)?.selected
        return when {
            selected != tapped -> HomeTap.Switch
            where is Where.Home -> HomeTap.Stay
            else -> HomeTap.BackToTab
        }
    }

    /** What a tap on the bar does inside a trip: tabs switch in place (no new back-stack entry). */
    enum class TripTap {
        /** The tab already on show: nothing. */
        Stay,

        /** Show the tab; the trip stays one back-stack entry. */
        SetTab,

        /** On a place page over the trip: close it, then show the tab. */
        BackToTrip,
    }

    fun tripTap(where: Where, tapped: TripTab): TripTap = when (where) {
        is Where.InTrip -> if (where.tab == tapped) TripTap.Stay else TripTap.SetTab
        is Where.Place -> TripTap.BackToTrip
        else -> TripTap.Stay
    }
}
