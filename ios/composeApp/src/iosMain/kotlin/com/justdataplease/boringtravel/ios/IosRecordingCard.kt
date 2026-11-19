package com.justdataplease.boringtravel.ios

/*
 * The route's recording card on iPhone (the second parity round): Android shows an ongoing notification while the route
 * records ("Recording your route · Balanced", the trip, Stop); iOS has no ongoing notifications, so it is a Live
 * Activity on the Lock Screen and in the Dynamic Island with the same words. ActivityKit and App Intents are Swift
 * only: Swift's RecordingActivityBridge (iosApp/BoringTravel/RecordingActivityBridge.swift) implements
 * [NativeRecordingActivity], handed over by Swift's App init ([startBoringTravel]); the Live Activity's view is the widget
 * extension's (iosApp/BoringTravelWidget/RecordingActivity.swift, its Stop an App Intent that calls
 * [stopRouteForToday] in the app, iOS 17 and later; on iOS 16.1 to 16.x a tap on it opens Me › Route tracking).
 */

/**
 * The Live Activity (Swift: RecordingActivityBridge). Its method names are its own: no other interface Swift implements
 * uses them (a Swift class can't take two Kotlin methods of one name).
 */
interface NativeRecordingActivity {
    /** Starts the Live Activity with [title] and [trip], or brings the one running up to date. */
    fun showRecordingActivity(title: String, trip: String)

    /** Ends it at once, if one runs. */
    fun endRecordingActivity()
}

/** [RecordingCard] over the Live Activity; with no [native] (the Simulator's tour, an iOS before 16.1) nothing shows. */
class IosRecordingCard(private val native: NativeRecordingActivity?) : RecordingCard {
    private var showing: RecordingCardText? = null

    /** Ended since it last showed (false at the start: a card an earlier run left behind is ended the first time). */
    private var ended = false

    override fun showRecordingCard(card: RecordingCardText) {
        if (showing == card) return
        showing = card
        ended = false
        native?.showRecordingActivity(card.title, card.trip)
    }

    override fun hideRecordingCard() {
        if (ended) return
        showing = null
        ended = true
        native?.endRecordingActivity()
    }
}
