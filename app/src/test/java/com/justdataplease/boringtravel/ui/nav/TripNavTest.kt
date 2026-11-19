package com.justdataplease.boringtravel.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripNavTest {
    private val outside = listOf("Trips", "Calendar", "Map", "Community", "Me")
    private val inTrip = listOf("Plan", "Journal", "Map", "Media")

    @Test
    fun outsideATripTheBarIsTripsCalendarMapCommunityMe() {
        for (tab in HomeTab.entries) {
            val bar = TripNav.bar(Where.Home(tab))
            assertEquals(BarMode.Outside(tab), bar)
            assertEquals(outside, TripNav.labels(bar))
            assertEquals(tab.ordinal, TripNav.selectedIndex(bar))
        }
    }

    @Test
    fun insideATripTheBarIsThatTripsOwnTabs() {
        for (tab in TripTab.entries) {
            val bar = TripNav.bar(Where.InTrip("t1", tab))
            assertEquals(BarMode.Trip("t1", tab), bar)
            assertEquals(inTrip, TripNav.labels(bar))
            assertEquals(tab.ordinal, TripNav.selectedIndex(bar))
        }
    }

    @Test
    fun pagesUnderATabKeepItsBar() {
        assertEquals(BarMode.Outside(HomeTab.Map), TripNav.bar(Where.Under(HomeTab.Map)))
        assertEquals(BarMode.Outside(HomeTab.Trips), TripNav.bar(Where.Under(HomeTab.Trips)))
    }

    @Test
    fun aPlacePageKeepsTheBarOfWhatItWasOpenedFrom() {
        // From the trip's plan or map: the trip's bar, on the tab it came from.
        assertEquals(BarMode.Trip("t1", TripTab.Map), TripNav.bar(Where.Place("t1", Where.InTrip("t1", TripTab.Map))))
        // From the Map tab outside any trip (a saved stop on the globe): Map stays selected.
        assertEquals(BarMode.Outside(HomeTab.Map), TripNav.bar(Where.Place("t1", Where.Home(HomeTab.Map))))
        assertEquals(BarMode.Outside(HomeTab.Map), TripNav.bar(Where.Place("t1", Where.Under(HomeTab.Map))))
        // Over another trip, or with nothing known below: Trips.
        assertEquals(BarMode.Outside(HomeTab.Trips), TripNav.bar(Where.Place("t1", Where.InTrip("t2", TripTab.Plan))))
        assertEquals(BarMode.Outside(HomeTab.Trips), TripNav.bar(Where.Place("t1", null)))
    }

    @Test
    fun screensWithoutTheBar() {
        val bar = TripNav.bar(Where.Elsewhere)
        assertEquals(BarMode.Hidden, bar)
        assertEquals(emptyList<String>(), TripNav.labels(bar))
        assertEquals(-1, TripNav.selectedIndex(bar))
    }

    @Test
    fun aTripOpensOnTheTabAskedForElsePlan() {
        assertEquals(TripTab.Journal, TripNav.landingTab("journal"))
        assertEquals(TripTab.Map, TripNav.landingTab("map"))
        assertEquals(TripTab.Media, TripNav.landingTab("media"))
        assertEquals(TripTab.Plan, TripNav.landingTab("plan"))
        // Nothing asked for (Trips, the Calendar, a sheet): Plan, whatever tab was used last time.
        assertEquals(TripTab.Plan, TripNav.landingTab(null))
        assertEquals(TripTab.Plan, TripNav.landingTab(""))
        assertEquals(TripTab.Plan, TripNav.landingTab("nonsense"))
    }

    @Test
    fun tabKeysRoundTrip() {
        for (tab in TripTab.entries) assertEquals(tab, TripTab.of(tab.key))
        assertEquals(listOf("plan", "journal", "map", "media"), TripTab.entries.map { it.key })
        assertNull(TripTab.of(null))
        assertNull(TripTab.of("Plan"))
    }

    @Test
    fun neverMoreThanOneTripOnTheBackStack() {
        assertEquals(TripNav.Open.Push, TripNav.open("t1", open = null))
        assertEquals(TripNav.Open.Reuse, TripNav.open("t1", open = "t1"))
        assertEquals(TripNav.Open.Replace, TripNav.open("t2", open = "t1"))
    }

    @Test
    fun tripTabsSwitchInPlaceSoBackLeavesTheTripInOneStep() {
        val onPlan = Where.InTrip("t1", TripTab.Plan)
        assertEquals(TripNav.TripTap.SetTab, TripNav.tripTap(onPlan, TripTab.Journal))
        assertEquals(TripNav.TripTap.SetTab, TripNav.tripTap(onPlan, TripTab.Media))
        assertEquals(TripNav.TripTap.Stay, TripNav.tripTap(onPlan, TripTab.Plan))
        // A place page over the trip closes first.
        assertEquals(TripNav.TripTap.BackToTrip, TripNav.tripTap(Where.Place("t1", onPlan), TripTab.Journal))
        assertEquals(TripNav.TripTap.Stay, TripNav.tripTap(Where.Home(HomeTab.Trips), TripTab.Journal))
    }

    @Test
    fun homeTapsSwitchOrComeBackToTheTab() {
        assertEquals(TripNav.HomeTap.Stay, TripNav.homeTap(Where.Home(HomeTab.Trips), HomeTab.Trips))
        assertEquals(TripNav.HomeTap.Switch, TripNav.homeTap(Where.Home(HomeTab.Trips), HomeTab.Calendar))
        assertEquals(TripNav.HomeTap.BackToTab, TripNav.homeTap(Where.Under(HomeTab.Map), HomeTab.Map))
        assertEquals(TripNav.HomeTap.BackToTab, TripNav.homeTap(Where.Under(HomeTab.Trips), HomeTab.Trips))
        assertEquals(TripNav.HomeTap.Switch, TripNav.homeTap(Where.Under(HomeTab.Trips), HomeTab.Me))
        assertEquals(TripNav.HomeTap.BackToTab, TripNav.homeTap(Where.Place("t1", Where.Home(HomeTab.Map)), HomeTab.Map))
    }

    @Test
    fun aPostOrAProfileKeepsTheCommunityBar() {
        assertEquals(BarMode.Outside(HomeTab.Community), TripNav.bar(Where.Under(HomeTab.Community)))
        assertEquals(TripNav.HomeTap.BackToTab, TripNav.homeTap(Where.Under(HomeTab.Community), HomeTab.Community))
        assertEquals(TripNav.HomeTap.Switch, TripNav.homeTap(Where.Under(HomeTab.Community), HomeTab.Trips))
        assertEquals(3, HomeTab.Community.ordinal)
    }
}
