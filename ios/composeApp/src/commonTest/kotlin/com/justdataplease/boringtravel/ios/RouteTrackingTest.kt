package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.geo.Geo
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.tracking.TrackingPermissions.Step
import com.justdataplease.boringtravel.tracking.TrackingPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Route tracking on iPhone (ios/Tracking.kt), on a phone in memory: what records and when (the Android app's
 * TrackingController rules), the points as the store's rows at Android's spacing, Balanced's best fix, Detailed's rest,
 * the notes, the steps and their order. Runs on the desktop and on the iPhone Simulator.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RouteTrackingTest {
    private val zone = "Europe/Athens"
    private val today: LocalDate = localDate(2026, 6, 18)
    private val noon = startOfDayMs(today, zone) + 12 * 3_600_000L

    private inner class Phone(
        scope: CoroutineScope, private val time: () -> Long, grant: LocationGrant = LocationGrant.WhileUsing, notes: NoteGrant = NoteGrant.Allowed,
        askAlways: Boolean = IosTrackingPlan.ASK_ALWAYS,
    ) {
        var clockShift = 0L
        val book = TripBook(MemoryTripFile(), now = { time() + clockShift })
        val settings = AppSettings(MemorySettingsFile())
        val sensor = MemoryRouteSensor(grantNow = grant)
        val notes = MemoryPhoneNotes(notes)
        val nightly = MemoryNightlyRuns()
        val storeFile = MemorySettingsFile()
        var onScreen = true
        val tracking = RouteTracking(
            book, settings, sensor, this.notes, nightly, TrackingStore(storeFile), scope,
            onScreen = { onScreen }, clock = { time() + clockShift }, zone = { zone }, askAlways = askAlways,
        )

        fun trip(title: String = "Naxos", start: LocalDate = today.minusDays(2), end: LocalDate = today.plusDays(3)) = book.newTrip(title, start, end)

        fun fix(minutes: Double, northM: Double = 0.0, accuracy: Float? = 12f) = RouteFix(
            time() + clockShift + (minutes * 60_000).toLong(), 37.1 + northM / Geo.METERS_PER_DEGREE, 25.4, accuracy, 1.2f, 30.0,
        )
    }

    /** A phone as the app has it ([alwaysPath]: the Always path, off by default as Android's background recording). */
    private fun TestScope.phone(grant: LocationGrant = LocationGrant.WhileUsing, notes: NoteGrant = NoteGrant.Allowed, alwaysPath: Boolean = IosTrackingPlan.ASK_ALWAYS) =
        Phone(backgroundScope, { noon + testScheduler.currentTime }, grant, notes, alwaysPath)

    @Test
    fun theAlwaysPathIsOffAsAndroidsBackgroundRecording() {
        // Android: TrackingPlan.BACKGROUND_RECORDING is false (its own TrackingPlanTest checks it, run here too).
        assertFalse(IosTrackingPlan.ASK_ALWAYS)
        assertEquals(listOf(Step.Precise, Step.Notifications), IosTrackingPlan.missing(TrackingLevel.Low, false, false, false, false))
        assertEquals(emptyList(), IosTrackingPlan.missing(TrackingLevel.Low, true, true, false, true))
        assertEquals(listOf(Step.Background), IosTrackingPlan.missing(TrackingLevel.Low, true, true, false, true, askAlways = true))
        // Always given doesn't start anything from the background, and no significant changes, while the path is off.
        val on = com.justdataplease.boringtravel.tracking.TrackingTarget("c", TrackingLevel.Low, today)
        assertEquals(com.justdataplease.boringtravel.tracking.Recording.WaitForApp, IosTrackingPlan.decide(on, true, false, false, false, hasAlways = true))
        assertEquals(com.justdataplease.boringtravel.tracking.Recording.StartService, IosTrackingPlan.decide(on, true, false, false, false, hasAlways = true, askAlways = true))
        assertFalse(IosTrackingPlan.request(TrackingLevel.Low, always = true).significantChanges)
        assertTrue(IosTrackingPlan.request(TrackingLevel.Low, always = true, askAlways = true).significantChanges)
    }

    @Test
    fun offUntilALevelIsPickedAndNothingIsAsked() = runTest {
        val p = phone(grant = LocationGrant.NotAsked, notes = NoteGrant.NotAsked)
        p.tracking.start()
        assertEquals(TrackingLevel.Off, p.settings.trackingLevel)
        assertEquals("Off. Pick a level to record your route during trips.", recordingText(p.tracking.status, p.settings.trackingLevel))
        // With a trip on today, as on Android: off for it.
        p.trip()
        p.tracking.apply()
        assertEquals("Off for Naxos.", recordingText(p.tracking.status, p.settings.trackingLevel))
        assertTrue(p.sensor.requests.isEmpty())
        assertTrue(p.sensor.calls.isEmpty())
        assertTrue(p.notes.asked.isEmpty())
        assertEquals(emptyList(), p.tracking.missing(TrackingLevel.Off))
    }

    @Test
    fun aTripDayOnScreenRecordsAtTheLevelsSpacing() = runTest {
        val p = phone()
        p.trip()
        p.tracking.setAppLevel(TrackingLevel.Balanced)
        val request = p.sensor.requests.last()
        assertEquals(RecordRequest(TrackingLevel.Balanced, precise = false, distanceM = 150.0, continuous = true, significantChanges = false), request)
        assertTrue(p.tracking.status.recording)
        assertEquals("Recording Naxos · Balanced", recordingText(p.tracking.status))
        // Balanced keeps a point no sooner than 5 minutes and 150 m after the last one.
        p.sensor.deliver(p.fix(0.0), p.fix(1.0, northM = 400.0), p.fix(6.0, northM = 100.0), p.fix(7.0, northM = 900.0))
        val waiting = TrackingStore(p.storeFile).inbox
        assertEquals(listOf(0.0, 7.0), waiting.map { (it.time - noon) / 60_000.0 })
        assertEquals(TrackingLevel.Balanced.id, waiting.first().level)
        assertEquals(12f, waiting.first().accuracyM)
        assertEquals(30.0, waiting.first().altitudeM)
        // They wait for the store until the app leaves the screen.
        assertTrue(p.book.snapshot.trackPoints.isEmpty())
        assertEquals(waiting.last().time, p.tracking.latestPoint()?.time)
        p.tracking.leftScreen()
        assertEquals(2, p.book.snapshot.trackPoints.size)
        assertTrue(TrackingStore(p.storeFile).inbox.isEmpty())
        assertEquals(p.book.snapshot.trips.single().id, p.book.snapshot.trackPoints.first().tripId)
    }

    @Test
    fun whileItRecordsTheCardOutsideTheAppSaysWhatAndStopEndsIt() = runTest {
        // Android's ongoing notification while the route records; iPhone's Live Activity, from the same words.
        val p = phone()
        val card = p.tracking.card as MemoryRecordingCard
        p.trip()
        assertNull(card.showing)
        p.tracking.setAppLevel(TrackingLevel.Balanced)
        assertEquals(RecordingCardText("Recording your route · Balanced", "Naxos", "Stop"), card.showing)
        // A level changed while it records: the card follows (Android's updateRecording).
        p.tracking.setAppLevel(TrackingLevel.Detailed)
        assertEquals("Recording your route · Detailed", card.showing?.title)
        // Stop (the card's button: the app's stopRouteForToday) stops it for today, and the card goes.
        p.tracking.stopForToday()
        assertFalse(p.sensor.recording)
        assertNull(card.showing)
        assertEquals(listOf("Recording your route · Balanced", "Recording your route · Detailed"), card.shown.map { it.title })
        // Without notifications allowed nothing shows, as Android shows no notification then; it records all the same.
        val quiet = phone(notes = NoteGrant.Refused)
        quiet.trip()
        quiet.tracking.setAppLevel(TrackingLevel.Low)
        assertTrue(quiet.sensor.recording)
        assertNull((quiet.tracking.card as MemoryRecordingCard).showing)
    }

    @Test
    fun badFixesAreDropped() = runTest {
        val p = phone()
        p.trip()
        p.tracking.setAppLevel(TrackingLevel.Detailed)
        p.sensor.deliver(
            p.fix(0.0, accuracy = -1f),
            RouteFix(noon, Double.NaN, 25.4),
            RouteFix(noon, 0.0, 0.0),
            p.fix(-11.0),
            p.fix(0.5, northM = 50.0),
        )
        assertEquals(1, TrackingStore(p.storeFile).inbox.size)
    }

    @Test
    fun theWaitingPointsGoToTheStoreInBatches() = runTest {
        val p = phone()
        p.trip()
        p.tracking.setAppLevel(TrackingLevel.Detailed)
        // Detailed: every 7.5 s and 15 m; 19 points wait, the 20th sends them all.
        repeat(19) { i -> p.sensor.deliver(p.fix(i * 0.2, northM = i * 30.0)) }
        assertEquals(19, TrackingStore(p.storeFile).inbox.size)
        assertTrue(p.book.snapshot.trackPoints.isEmpty())
        p.sensor.deliver(p.fix(19 * 0.2, northM = 19 * 30.0))
        assertEquals(20, p.book.snapshot.trackPoints.size)
        assertTrue(TrackingStore(p.storeFile).inbox.isEmpty())
        assertTrue(p.tracking.written > 0)
    }

    @Test
    fun theExamplesAreNeverRecorded() = runTest {
        val p = phone()
        // A trip with a demo id, on now.
        p.book.putRows(trips = listOf(com.justdataplease.boringtravel.data.db.Trip("demo-argentina", "Argentina", startDate = today.minusDays(3), endDate = today.plusDays(3), createdAt = 1, updatedAt = 1)))
        assertTrue(p.book.snapshot.trips.single().isDemo)
        p.tracking.setAppLevel(TrackingLevel.Detailed)
        assertNull(p.tracking.status.target.tripId)
        assertTrue(p.sensor.requests.isEmpty())
    }

    @Test
    fun aTripsOwnLevelWins() = runTest {
        val p = phone()
        val trip = p.trip()
        p.tracking.setAppLevel(TrackingLevel.Low)
        assertEquals(TrackingLevel.Low, p.sensor.requests.last().level)
        p.tracking.setTripLevel(trip.id, TrackingLevel.Detailed)
        assertEquals("detailed", p.book.trip(trip.id)?.trackingLevel)
        assertEquals(RecordRequest(TrackingLevel.Detailed, precise = true, distanceM = 15.0, continuous = true, significantChanges = false), p.sensor.requests.last())
        p.tracking.setTripLevel(trip.id, TrackingLevel.Off)
        assertFalse(p.sensor.recording)
        assertEquals("Off for Naxos.", recordingText(p.tracking.status, TrackingLevel.Low))
        p.tracking.setTripLevel(trip.id, null)
        assertTrue(p.sensor.recording)
    }

    @Test
    fun withoutAlwaysNothingStartsInTheBackgroundAndAnInterruptedRouteSaysSo() = runTest {
        val p = phone()
        p.trip()
        p.settings.trackingLevel = TrackingLevel.Balanced
        p.onScreen = false
        p.tracking.start()
        assertFalse(p.sensor.recording)
        assertEquals("Not recording Naxos yet.", recordingText(p.tracking.status))
        assertTrue(p.notes.posted.isEmpty())
        // On screen it starts; then iOS ends the app and starts it again in the background (the nightly run).
        p.onScreen = true
        p.tracking.cameOnScreen()
        assertTrue(p.sensor.recording)
        val again = Phone(backgroundScope, { noon + testScheduler.currentTime })
        again.book.restore(p.book.export())
        again.settings.trackingLevel = TrackingLevel.Balanced
        p.storeFile.let { old -> listOf("trip", "title", "level", "lastDay", "mode", "armedAt").forEach { k -> again.storeFile.write(TrackingStore.PREFIX + k, old.read(TrackingStore.PREFIX + k)) } }
        again.onScreen = false
        again.tracking.start()
        assertFalse(again.sensor.recording)
        val note = again.notes.shown[TrackingNotes.PAUSED]
        assertEquals("Your route isn't recording", note?.title)
        assertEquals("Naxos · Open BoringTravel to go on.", note?.text)
        // Opened again: it records, and the note goes.
        again.onScreen = true
        again.tracking.cameOnScreen()
        assertTrue(again.sensor.recording)
        assertNull(again.notes.shown[TrackingNotes.PAUSED])
    }

    @Test
    fun alwaysGivenInSettingsActsAsWhileUsingWhileThePathIsOff() = runTest {
        val p = phone(grant = LocationGrant.Always)
        p.trip()
        p.settings.trackingLevel = TrackingLevel.Low
        // In the background (the nightly run): nothing starts, as with While Using.
        p.onScreen = false
        p.tracking.start()
        assertFalse(p.sensor.recording)
        assertTrue(p.sensor.requests.isEmpty())
        // On screen: the standard updates only, no significant-change service; no Always step, nothing asked.
        p.onScreen = true
        p.tracking.cameOnScreen()
        assertEquals(RecordRequest(TrackingLevel.Low, precise = false, distanceM = 500.0, continuous = true, significantChanges = false), p.sensor.requests.last())
        assertEquals("Recording Naxos · Low", recordingText(p.tracking.status))
        assertEquals(emptyList(), p.tracking.missing(TrackingLevel.Low))
        assertFalse(p.tracking.settingsFor(Step.Background))
        p.tracking.allow(Step.Background)
        assertTrue("askAlways" !in p.sensor.calls)
        assertTrue(p.sensor.calls.isEmpty())
        // Detailed's rest, too, without significant changes.
        p.tracking.setAppLevel(TrackingLevel.Detailed)
        assertFalse(p.sensor.requests.last().significantChanges)
    }

    @Test
    fun withTheAlwaysPathOnItStartsInTheBackgroundAndLowUsesOnlySignificantChanges() = runTest {
        val p = phone(grant = LocationGrant.Always, alwaysPath = true)
        p.trip()
        p.settings.trackingLevel = TrackingLevel.Low
        p.onScreen = false
        p.tracking.start()
        assertTrue(p.sensor.recording)
        assertEquals(RecordRequest(TrackingLevel.Low, precise = false, distanceM = 500.0, continuous = false, significantChanges = true), p.sensor.requests.last())
        p.tracking.setAppLevel(TrackingLevel.Balanced)
        assertEquals(RecordRequest(TrackingLevel.Balanced, precise = false, distanceM = 150.0, continuous = true, significantChanges = true), p.sensor.requests.last())
    }

    @Test
    fun stopLastsForTheRestOfTheDay() = runTest {
        val p = phone()
        p.trip()
        p.tracking.setAppLevel(TrackingLevel.Balanced)
        p.tracking.stopForToday()
        assertFalse(p.sensor.recording)
        assertTrue(p.tracking.status.stopped)
        assertEquals("Stopped for today · Naxos", recordingText(p.tracking.status))
        p.tracking.cameOnScreen()
        assertFalse(p.sensor.recording)
        // Start recording clears the stop.
        p.tracking.startNow()
        assertTrue(p.sensor.recording)
        // A stop yesterday doesn't count today.
        p.tracking.stopForToday()
        p.clockShift = 24 * 3_600_000L
        p.tracking.cameOnScreen()
        assertTrue(p.sensor.recording)
    }

    @Test
    fun afterTheLastDayItStopsAtMidnight() = runTest {
        val p = phone()
        p.trip(end = today)
        p.tracking.setAppLevel(TrackingLevel.Balanced)
        assertTrue(p.sensor.recording)
        advanceTimeBy(TrackingPlan.msUntilNextDay(noon, zone) + 1_000)
        assertFalse(p.sensor.recording)
        assertNull(p.tracking.status.target.tripId)
    }

    @Test
    fun detailedRestsWhileThePhoneStaysPutAndWakesWhenItMoves() = runTest {
        val p = phone()
        p.trip()
        p.tracking.setAppLevel(TrackingLevel.Detailed)
        p.sensor.deliver(p.fix(0.0))
        advanceTimeBy(9 * 60_000L)
        assertFalse(p.tracking.isResting)
        advanceTimeBy(3 * 60_000L)
        assertTrue(p.tracking.isResting)
        assertEquals(RecordRequest(TrackingLevel.Detailed, precise = false, distanceM = 50.0, continuous = true, significantChanges = false), p.sensor.requests.last())
        p.sensor.deliver(p.fix(0.0, northM = 300.0))
        assertFalse(p.tracking.isResting)
        assertTrue(p.sensor.requests.last().precise)
    }

    @Test
    fun balancedTakesABestFixWhenNoPointCame() = runTest {
        val p = phone()
        p.trip()
        p.tracking.setAppLevel(TrackingLevel.Balanced)
        fun calls() = p.sensor.calls.count { it == "bestFix" }
        fun kept() = TrackingStore(p.storeFile).inbox.size + p.book.snapshot.trackPoints.size
        p.sensor.nextBestFix = RouteFix(noon + 20 * 60_000L, 37.2, 25.5, 8f)
        advanceTimeBy(20 * 60_000L + 1_000)
        assertEquals(1, calls())
        assertEquals(1, kept())
        assertEquals(0, TrackingStore(p.storeFile).bestFixStill)
        // The same spot 20 minutes later: kept, and the next one waits 40 minutes.
        p.sensor.nextBestFix = RouteFix(noon + 40 * 60_000L, 37.2, 25.5, 8f)
        advanceTimeBy(20 * 60_000L + 1_000)
        assertEquals(2, calls())
        assertEquals(2, kept())
        assertEquals(1, TrackingStore(p.storeFile).bestFixStill)
        advanceTimeBy(30 * 60_000L)
        assertEquals(2, calls())
        // A fix too vague isn't kept.
        p.sensor.nextBestFix = RouteFix(noon + 80 * 60_000L, 37.3, 25.6, 900f)
        advanceTimeBy(11 * 60_000L)
        assertEquals(3, calls())
        assertEquals(2, kept())
    }

    @Test
    fun theStepsAreAndroidsAndLeadToSettingsOnceAnswered() = runTest {
        val p = phone(grant = LocationGrant.NotAsked, notes = NoteGrant.NotAsked)
        p.trip()
        p.settings.trackingLevel = TrackingLevel.Balanced
        p.tracking.start()
        // Android's order: precise location, then notifications; nothing more.
        assertEquals(listOf(Step.Precise, Step.Notifications), p.tracking.missing(TrackingLevel.Balanced))
        assertEquals("Waiting for location permission for Naxos.", recordingText(p.tracking.status))
        assertFalse(p.tracking.settingsFor(Step.Precise))
        p.tracking.allow(Step.Precise)
        assertEquals(listOf("askWhileUsing"), p.sensor.calls)
        assertTrue(p.sensor.recording)
        assertEquals(listOf(Step.Notifications), p.tracking.missing(TrackingLevel.Balanced))
        p.tracking.allow(Step.Notifications)
        assertEquals(listOf("notes"), p.notes.asked)
        assertEquals(emptyList(), p.tracking.missing(TrackingLevel.Balanced))
        assertTrue("askAlways" !in p.sensor.calls)
        assertFalse(p.sensor.requests.any { it.significantChanges })
        // Refused in Settings: the step leads there.
        p.sensor.grantNow = LocationGrant.Refused
        assertTrue(p.tracking.settingsFor(Step.Precise))
        p.tracking.allow(Step.Precise)
        assertEquals("openSettings", p.sensor.calls.last())
        // Precise Location off: Settings too.
        p.sensor.grantNow = LocationGrant.WhileUsing
        p.sensor.preciseNow = false
        assertEquals(listOf(Step.Precise), p.tracking.missing(TrackingLevel.Balanced))
        assertTrue(p.tracking.settingsFor(Step.Precise))
        assertTrue("askAlways" !in p.sensor.calls)
    }

    @Test
    fun withTheAlwaysPathOnTheStepsEndWithAlways() = runTest {
        val p = phone(grant = LocationGrant.NotAsked, notes = NoteGrant.NotAsked, alwaysPath = true)
        p.trip()
        p.settings.trackingLevel = TrackingLevel.Balanced
        p.tracking.start()
        assertEquals(listOf(Step.Precise, Step.Notifications), p.tracking.missing(TrackingLevel.Balanced))
        p.tracking.allow(Step.Precise)
        assertEquals(listOf(Step.Notifications, Step.Background), p.tracking.missing(TrackingLevel.Balanced))
        p.tracking.allow(Step.Notifications)
        assertEquals(listOf(Step.Background), p.tracking.missing(TrackingLevel.Balanced))
        // Always refused: asked once, then the step leads to Settings.
        p.sensor.answerAlways = LocationGrant.WhileUsing
        p.tracking.allow(Step.Background)
        assertTrue(p.tracking.settingsFor(Step.Background))
        p.tracking.allow(Step.Background)
        assertEquals("openSettings", p.sensor.calls.last())
        // Precise Location off: Settings too.
        p.sensor.preciseNow = false
        assertEquals(Step.Precise, p.tracking.missing(TrackingLevel.Balanced).first())
        assertTrue(p.tracking.settingsFor(Step.Precise))
    }

    @Test
    fun locationOffAndNotAllowedSayItQuietly() = runTest {
        val p = phone()
        p.trip()
        p.tracking.start()
        p.tracking.setAppLevel(TrackingLevel.Balanced)
        p.sensor.servicesNow = false
        p.sensor.changed()
        val off = assertNotNull(p.notes.shown[TrackingNotes.LOCATION_OFF])
        assertEquals("Location is off", off.title)
        assertEquals("Naxos · The route pauses until you turn it on.", off.text)
        assertEquals(NoteOpens.Settings, off.opens)
        p.sensor.servicesNow = true
        p.sensor.changed()
        assertNull(p.notes.shown[TrackingNotes.LOCATION_OFF])
        // Refused in Settings: the nightly note, once a day.
        p.sensor.grantNow = LocationGrant.Refused
        p.sensor.changed()
        p.tracking.checkPermissionNote()
        p.tracking.checkPermissionNote()
        assertEquals(1, p.notes.posted.count { it.id == TrackingNotes.PERMISSION })
        val note = assertNotNull(p.notes.shown[TrackingNotes.PERMISSION])
        assertEquals("Naxos", note.title)
        assertEquals("Location is not allowed for BoringTravel, so the route isn't kept.", note.text)
        p.sensor.grantNow = LocationGrant.WhileUsing
        p.sensor.changed()
        p.tracking.checkPermissionNote()
        assertNull(p.notes.shown[TrackingNotes.PERMISSION])
    }

    @Test
    fun aDeletedTripsRouteGoesFromTheStoreAndFromWhatWaits() = runTest {
        val p = phone()
        val trip = p.trip()
        p.tracking.setAppLevel(TrackingLevel.Balanced)
        p.sensor.deliver(p.fix(0.0))
        p.tracking.flush()
        p.sensor.deliver(p.fix(6.0, northM = 500.0))
        assertEquals(1, p.book.snapshot.trackPoints.size)
        assertEquals(1, TrackingStore(p.storeFile).inbox.size)
        p.book.removeTrip(trip.id)
        p.tracking.tripDeleted(trip.id)
        assertTrue(p.book.snapshot.trackPoints.isEmpty())
        assertTrue(TrackingStore(p.storeFile).inbox.isEmpty())
        assertFalse(p.sensor.recording)
    }

    @Test
    fun theNightlyRunIsAskedForJustAfterMidnight() = runTest {
        val p = phone()
        p.tracking.start()
        assertEquals(startOfDayMs(today.plusDays(1), zone) + 5 * 60_000L, p.nightly.scheduledAt)
    }

    @Test
    fun aNewNotesPlaceComesFromARecentRoutePoint() = runTest {
        val book = TripBook(MemoryTripFile())
        val model = WriteModel(book, MediaKit.memory(), null, null, noon)
        val point = com.justdataplease.boringtravel.data.db.TrackPoint(tripId = "t", time = noon - 3_600_000L, lat = 37.1, lng = 25.4)
        model.fillPlace(point, noon) { _, _ -> "Naxos" }
        assertEquals("Naxos", model.placeName)
        assertEquals(37.1, model.lat)
        // Older than 12 hours: nothing.
        val other = WriteModel(book, MediaKit.memory(), null, null, noon)
        other.fillPlace(point.copy(time = noon - 13 * 3_600_000L), noon) { _, _ -> "Naxos" }
        assertEquals("", other.placeName)
        assertNull(other.lat)
    }
}
