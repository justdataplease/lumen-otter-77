package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.calendar.CalendarAccessException
import com.justdataplease.boringtravel.calendar.CalendarInfo
import com.justdataplease.boringtravel.calendar.CalendarMarker
import com.justdataplease.boringtravel.calendar.CalendarPlan
import com.justdataplease.boringtravel.calendar.EventFields
import com.justdataplease.boringtravel.calendar.ItemWhen
import com.justdataplease.boringtravel.calendar.PhoneCalendar
import com.justdataplease.boringtravel.calendar.PhoneEvent
import com.justdataplease.boringtravel.calendar.PhoneInstance
import com.justdataplease.boringtravel.calendar.TripEvent
import com.justdataplease.boringtravel.calendar.TripEvents
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.time.localDateOfEpochDay
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGColorGetComponents
import platform.CoreGraphics.CGColorGetNumberOfComponents
import platform.EventKit.EKAuthorizationStatusAuthorized
import platform.EventKit.EKAuthorizationStatusDenied
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKAuthorizationStatusRestricted
import platform.EventKit.EKCalendar
import platform.EventKit.EKCalendarType
import platform.EventKit.EKSourceType
import platform.EventKit.EKEntityType
import platform.EventKit.EKEvent
import platform.EventKit.EKEventAvailabilityFree
import platform.EventKit.EKEventAvailabilityBusy
import platform.EventKit.EKEventStore
import platform.EventKit.EKSpan
import platform.EventKitUI.EKEventEditViewAction
import platform.EventKitUI.EKEventEditViewController
import platform.EventKitUI.EKEventEditViewDelegateProtocol
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneWithName
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIDevice
import platform.darwin.NSObject
import kotlin.coroutines.resume

/*
 * The phone's calendars on iPhone (EventKit), behind the app's PhoneCalendar. Apple's APIs called here: EventKit's
 * EKEventStore (authorizationStatusForEntityType, requestFullAccessToEventsWithCompletion on iOS 17 and later,
 * requestAccessToEntityType on iOS 16, calendarsForEntityType, defaultCalendarForNewEvents, calendarWithIdentifier,
 * predicateForEventsWithStartDate, eventsMatchingPredicate, eventWithIdentifier, saveEvent, removeEvent, commit),
 * EKEvent and EKCalendar's properties, EventKitUI's EKEventEditViewController (Add to calendar: no access needed on
 * iOS 17), CoreGraphics for a calendar's colour, and UIKit to open Settings. Only type-checked on Windows.
 *
 * Events are read and written as Android's provider keeps them, so the common rules compare like for like: an all-day
 * event as UTC midnights with an exclusive end (EventKit keeps it in floating local days, its end the last day's
 * 23:59:59), a timed one in epoch ms with its zone's name.
 */

private const val DAY_MS = 86_400_000L

private fun date(ms: Long): NSDate = NSDate.dateWithTimeIntervalSince1970(ms / 1000.0)
private fun ms(d: NSDate): Long = (d.timeIntervalSince1970 * 1000.0).toLong()

/** EventKit through the app's [PhoneCalendar]. */
@OptIn(ExperimentalForeignApi::class)
class IosCalendar(override val ourPackage: String = "com.justdataplease.boringtravel") : PhoneCalendar {
    private val store = EKEventStore()

    private fun status() = EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent)

    private val ios17: Boolean get() = (UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 0) >= 17

    override fun canRead(): Boolean = status().let { it == EKAuthorizationStatusFullAccess || it == EKAuthorizationStatusAuthorized }

    override fun canWrite(): Boolean = canRead()

    override fun refused(): Boolean = status().let { it == EKAuthorizationStatusDenied || it == EKAuthorizationStatusRestricted }

    override suspend fun requestAccess(): Boolean = suspendCancellableCoroutine { done ->
        val answer: (Boolean, platform.Foundation.NSError?) -> Unit = { granted, _ -> if (done.isActive) done.resume(granted) }
        if (ios17) store.requestFullAccessToEventsWithCompletion(answer)
        else store.requestAccessToEntityType(EKEntityType.EKEntityTypeEvent, completion = answer)
    }

    private fun need() {
        if (!canRead()) throw CalendarAccessException()
    }

    private fun ekCalendars(): List<EKCalendar> = store.calendarsForEntityType(EKEntityType.EKEntityTypeEvent).filterIsInstance<EKCalendar>()

    override fun calendars(): List<CalendarInfo> {
        need()
        val main = store.defaultCalendarForNewEvents?.calendarIdentifier
        return ekCalendars().map { c -> info(c, c.calendarIdentifier == main) }
    }

    /** An EKCalendar as the app's calendar row: its account from its source (iCloud, Google, Exchange, on this phone). */
    private fun info(c: EKCalendar, primary: Boolean): CalendarInfo {
        val source = c.source
        val sourceTitle = source?.title.orEmpty()
        val type = when {
            c.type == EKCalendarType.EKCalendarTypeBirthday || source?.sourceType == EKSourceType.EKSourceTypeBirthdays -> "birthday"
            c.type == EKCalendarType.EKCalendarTypeSubscription || source?.sourceType == EKSourceType.EKSourceTypeSubscribed -> "subscribed"
            source?.sourceType == EKSourceType.EKSourceTypeLocal -> "local"
            source?.sourceType == EKSourceType.EKSourceTypeExchange -> "exchange"
            source?.sourceType == EKSourceType.EKSourceTypeMobileMe -> "caldav"
            source?.sourceType == EKSourceType.EKSourceTypeCalDAV && (sourceTitle.contains("gmail", true) || sourceTitle.contains("google", true)) -> "com.google"
            source?.sourceType == EKSourceType.EKSourceTypeCalDAV -> "caldav"
            else -> "other"
        }
        return CalendarInfo(
            id = c.calendarIdentifier,
            name = c.title.ifBlank { sourceTitle.ifBlank { "Calendar" } },
            accountName = sourceTitle,
            accountType = type,
            color = colorOf(c),
            primary = primary,
            accessLevel = if (c.allowsContentModifications) CalendarInfo.OWNER else 200,
            visible = true,
        )
    }

    private fun colorOf(c: EKCalendar): Int {
        val color = c.CGColor ?: return 0
        val parts = CGColorGetComponents(color) ?: return 0
        val n = CGColorGetNumberOfComponents(color).toInt()
        fun part(i: Int) = (parts[i].coerceIn(0.0, 1.0) * 255).toInt()
        val (r, g, b) = if (n >= 3) Triple(part(0), part(1), part(2)) else Triple(part(0), part(0), part(0))
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** The UTC midnight of the local day [d] starts (an all-day event's day). */
    private fun allDayStart(d: NSDate): Long = localDateAt(ms(d), null).toEpochDay() * DAY_MS

    private fun fields(e: EKEvent, notes: String): EventFields {
        val start = e.startDate?.let(::ms) ?: 0L
        val end = e.endDate?.let(::ms) ?: start
        return if (e.allDay) {
            val first = e.startDate?.let(::allDayStart) ?: 0L
            val last = e.endDate?.let { allDayStart(date(ms(it) - 1)) } ?: first
            EventFields(e.title.orEmpty(), e.location.orEmpty(), notes, true, first, maxOf(first, last) + DAY_MS, "UTC", e.availability == EKEventAvailabilityFree)
        } else {
            EventFields(e.title.orEmpty(), e.location.orEmpty(), notes, false, start, end, e.timeZone?.name ?: "UTC", e.availability == EKEventAvailabilityFree)
        }
    }

    private fun read(e: EKEvent): PhoneEvent {
        val notes = e.notes.orEmpty()
        val (pkg, uri) = CalendarMarker.owner(e.URL?.absoluteString, notes, ourPackage)
        return PhoneEvent(e.eventIdentifier.orEmpty(), e.calendar?.calendarIdentifier.orEmpty(), fields(e, CalendarMarker.cleanDescription(notes, ourPackage)), pkg, uri)
    }

    private fun events(window: LongRange): List<EKEvent> {
        // EventKit searches at most four years at a time.
        val out = ArrayList<EKEvent>()
        var from = window.first
        while (from < window.last) {
            val to = minOf(window.last, from + 1_400L * DAY_MS)
            val predicate = store.predicateForEventsWithStartDate(date(from), endDate = date(to), calendars = null)
            out += store.eventsMatchingPredicate(predicate).filterIsInstance<EKEvent>()
            from = to
        }
        return out.distinctBy { it.eventIdentifier }
    }

    private fun around(window: LongRange?): LongRange {
        val now = ms(NSDate())
        return window?.let { (it.first - 30 * DAY_MS)..(it.last + 30 * DAY_MS) } ?: (now - 400 * DAY_MS)..(now + 1_000 * DAY_MS)
    }

    override fun markedEvents(tripId: String, window: LongRange?): List<PhoneEvent> {
        need()
        return events(around(window)).map(::read).filter { it.appPackage == ourPackage && CalendarMarker.parse(it.appUri)?.first == tripId }
    }

    override fun markedEventIds(window: LongRange): Set<String> {
        need()
        return events(window).map(::read).filter { it.appPackage == ourPackage && CalendarMarker.parse(it.appUri) != null }.mapTo(HashSet()) { it.id }
    }

    override fun eventsByIds(ids: Collection<String>): List<PhoneEvent> {
        need()
        return ids.distinct().mapNotNull { store.eventWithIdentifier(it) }.map(::read)
    }

    /** [e] onto an EKEvent as the app writes it: its marker as the link and the source line at the end of the notes. */
    private fun write(ev: EKEvent, e: TripEvent, zone: String, tripId: String) {
        val f = TripEvents.fields(e, zone)
        ev.title = f.title
        ev.location = f.location
        ev.notes = CalendarMarker.description(f.description, ourPackage, tripId, e.key)
        ev.URL = NSURL.URLWithString(CalendarMarker.uri(tripId, e.key))
        ev.availability = if (f.free) EKEventAvailabilityFree else EKEventAvailabilityBusy
        if (f.allDay) {
            ev.allDay = true
            ev.timeZone = null
            // Floating local days: midnight of the first day, midnight of the day after the last.
            ev.startDate = date(startOfDayMs(localDateOfEpochDay(f.startMs / DAY_MS), null))
            ev.endDate = date(startOfDayMs(localDateOfEpochDay(f.endMs / DAY_MS), null) - 1000)
        } else {
            ev.allDay = false
            ev.timeZone = NSTimeZone.timeZoneWithName(zone)
            ev.startDate = date(f.startMs)
            ev.endDate = date(f.endMs)
        }
    }

    override fun apply(calendarId: String, tripId: String, zone: String, plan: CalendarPlan): Map<String, String> {
        if (!canWrite()) throw CalendarAccessException()
        if (plan.isEmpty) return emptyMap()
        val calendar = store.calendarWithIdentifier(calendarId) ?: throw CalendarAccessException()
        plan.deletes.forEach { id -> store.eventWithIdentifier(id)?.let { store.removeEvent(it, span = EKSpan.EKSpanThisEvent, commit = false, error = null) } }
        plan.updates.forEach { (id, e) ->
            val ev = store.eventWithIdentifier(id) ?: return@forEach
            write(ev, e, zone, tripId)
            store.saveEvent(ev, span = EKSpan.EKSpanThisEvent, commit = false, error = null)
        }
        val added = plan.inserts.map { e ->
            val ev = EKEvent.eventWithEventStore(store)
            ev.calendar = calendar
            write(ev, e, zone, tripId)
            store.saveEvent(ev, span = EKSpan.EKSpanThisEvent, commit = false, error = null)
            e.key to ev
        }
        store.commit(null)
        return added.mapNotNull { (key, ev) -> ev.eventIdentifier?.let { key to it } }.toMap()
    }

    override fun delete(ids: Collection<String>): Int {
        if (!canWrite()) throw CalendarAccessException()
        var n = 0
        ids.distinct().forEach { id ->
            val ev = store.eventWithIdentifier(id) ?: return@forEach
            if (store.removeEvent(ev, span = EKSpan.EKSpanThisEvent, commit = false, error = null)) n++
        }
        store.commit(null)
        return n
    }

    override fun instances(from: Long, to: Long): List<PhoneInstance> {
        need()
        val out = ArrayList<PhoneInstance>()
        var start = from
        while (start < to) {
            val end = minOf(to, start + 1_400L * DAY_MS)
            val predicate = store.predicateForEventsWithStartDate(date(start), endDate = date(end), calendars = null)
            store.eventsMatchingPredicate(predicate).filterIsInstance<EKEvent>().forEach { e ->
                val f = fields(e, e.notes.orEmpty())
                out += PhoneInstance(e.eventIdentifier.orEmpty(), e.calendar?.calendarIdentifier.orEmpty(), f.title, f.location, e.notes.orEmpty(), f.allDay, f.startMs, f.endMs)
            }
            start = end
        }
        return out.distinctBy { it.eventId to it.begin }
    }

    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    /** The edit screen's delegate: it only closes the screen (the traveller saved or cancelled there). */
    private val closer = object : NSObject(), EKEventEditViewDelegateProtocol {
        override fun eventEditViewController(controller: EKEventEditViewController, didCompleteWithAction: EKEventEditViewAction) {
            controller.dismissViewControllerAnimated(true, completion = null)
        }
    }

    override fun newEvent(title: String, location: String, notes: String, url: String?, time: ItemWhen, zone: String): Boolean {
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return false
        while (true) top = top.presentedViewController ?: break
        val ev = EKEvent.eventWithEventStore(store)
        ev.title = title
        ev.location = location
        ev.notes = notes
        ev.URL = url?.let { NSURL.URLWithString(it) }
        ev.allDay = time.allDay
        if (time.allDay) {
            ev.startDate = date(startOfDayMs(localDateOfEpochDay(time.beginMs / DAY_MS), null))
            ev.endDate = date(startOfDayMs(localDateOfEpochDay(time.endMs / DAY_MS), null) - 1000)
        } else {
            ev.timeZone = NSTimeZone.timeZoneWithName(zone)
            ev.startDate = date(time.beginMs)
            ev.endDate = date(time.endMs)
        }
        val controller = EKEventEditViewController()
        controller.eventStore = store
        controller.event = ev
        controller.editViewDelegate = closer
        top.presentViewController(controller, animated = true, completion = null)
        return true
    }
}
