package com.justdataplease.boringtravel.calendar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.geo.Countries
import com.justdataplease.boringtravel.ios.PlanOrder
import com.justdataplease.boringtravel.ios.SettingsFile
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.time.systemZoneId
import com.justdataplease.boringtravel.time.today
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The trip and the phone's calendars: the Android app's calendar/CalendarLinks.kt, CalendarSync.kt and the part of
 * CalendarAutomation.kt an iPhone can do, written again on the trip store ([TripBook]) and a small interface to the
 * phone's calendars ([PhoneCalendar]: EventKit on iPhone, [MemoryPhoneCalendar] in tests and previews). The decisions
 * are the same pure rules ([TripEvents], [CalendarDiff], [CalendarImport], [IcsWriter], [TripZone]).
 */

/** The app may not read (or write) the phone's calendars. */
class CalendarAccessException : Exception("No calendar access")

/** The linked calendar isn't on the phone any more (account removed, calendar deleted or made read-only). */
class CalendarGoneException : Exception("The calendar is gone")

class CalendarOwnershipException : Exception("A linked event changed outside BoringTravel. Review the calendar connection before updating.")

enum class AutomaticCalendarResult { Done, Retry, Paused }

/** What an add or update did. */
data class SyncResult(val added: Int, val changed: Int, val removed: Int, val calendarName: String) {
    val nothing: Boolean get() = added == 0 && changed == 0 && removed == 0
}

/**
 * The phone's calendars, as the Android app's PhoneCalendar reads and writes Android's calendar provider: reads rows
 * and writes what [CalendarDiff] decided; no other logic. iPhone: EventKit (iosMain/…/IosCalendar.kt).
 */
interface PhoneCalendar {
    /** The app's own name in its events' source lines ("com.justdataplease.boringtravel"). */
    val ourPackage: String

    fun canRead(): Boolean

    fun canWrite(): Boolean

    /** Whether access was refused for good: only Settings can turn it on again. */
    fun refused(): Boolean

    /** Asks for access to the calendars (the system's question, the first time); true when given. */
    suspend fun requestAccess(): Boolean

    /** Every calendar (read-only ones too; callers filter). */
    fun calendars(): List<CalendarInfo>

    /** Events carrying [tripId]'s marker, in any calendar, over [window] (epoch ms; null: around today). */
    fun markedEvents(tripId: String, window: LongRange?): List<PhoneEvent>

    /** Ids of every event this app wrote over [window]: never offered for import. */
    fun markedEventIds(window: LongRange): Set<String>

    fun eventsByIds(ids: Collection<String>): List<PhoneEvent>

    /**
     * Writes [plan]: deletes, updates, then inserts into [calendarId], each carrying [tripId]'s marker. Returns the new
     * events' ids by key.
     */
    fun apply(calendarId: String, tripId: String, zone: String, plan: CalendarPlan): Map<String, String>

    /** Deletes events by id. Returns how many went. */
    fun delete(ids: Collection<String>): Int

    /** Occurrences in the calendars overlapping [from]..[to] (epoch ms), repeating events expanded. */
    fun instances(from: Long, to: Long): List<PhoneInstance>

    /** The app's page in the phone's Settings (to allow calendar access again). */
    fun openSettings()

    /**
     * The phone's own new-event screen filled in (one plan item, Add to calendar): no access needed. False when the
     * phone has none.
     */
    fun newEvent(title: String, location: String, notes: String, url: String?, time: ItemWhen, zone: String): Boolean
}

/** Which phone calendar a trip was added to, and what was written there at the last add or update. */
@Serializable
data class CalendarLink(
    val calendarId: String,
    val calendarName: String,
    val accountName: String = "",
    val accountType: String = "",
    val color: Int = 0,
    /** The zone the times were written in. */
    val zone: String,
    /** [TripEvents.fingerprint] of what was written; the Plan tab offers an update when the plan's differs. */
    val fingerprint: String,
    val updatedAt: Long,
    /** Event ids by key, in case a calendar's sync drops the marker from the events. */
    val events: Map<String, Remembered> = emptyMap(),
    /** New connections turn this on; it can be turned off in the sheet. */
    val autoUpdate: Boolean = false,
) {
    val accountLabel: String get() = CalendarGroup(CalendarAccounts.kind(accountType, accountName), accountName, emptyList()).title

    /** The zone the trip's times are read in now: the one picked since, else the one written. */
    fun zoneNow(chosen: String?): String = TripZone.parse(chosen) ?: TripZone.parse(zone) ?: systemZoneId()

    /** True when the calendar no longer matches the plan (an item, the trip's dates or the zone changed). */
    fun isBehind(trip: Trip, items: List<PlanItem>, chosenZone: String?): Boolean =
        TripEvents.fingerprint(TripEvents.build(trip, items, forPhone = true), zoneNow(chosenZone)) != fingerprint

    fun matches(calendar: CalendarInfo): Boolean = calendar.id == calendarId && calendar.writable &&
        calendar.accountType == accountType && calendar.accountName.equals(accountName, ignoreCase = true)
}

/** Kept before an automatic update runs; its token keeps an older run from clearing a newer edit. */
@Serializable
data class PendingCalendarUpdate(
    val fingerprint: String,
    val token: String,
    val phase: String = "pending",
    val problem: String? = null,
) {
    val label: String get() = when {
        problem == "permission" -> "Calendar access is paused. Allow access to continue."
        problem == "calendar" -> "Choose a calendar to resume updates."
        problem == "ownership" -> "Review the linked calendar event before updating."
        phase == "updating" -> "Updating your calendar…"
        problem == "retry" -> "Calendar update waiting to retry."
        else -> "Calendar update queued."
    }
}

/**
 * Per-trip calendar links, chosen zones and pending updates. They describe this phone's calendar rows, so they stay on
 * the phone: in its settings (UserDefaults), never in a backup or a shared trip. [revision] changes with every write,
 * so the screens follow.
 */
class CalendarLinks(private val file: SettingsFile) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var revision by mutableIntStateOf(0)
        private set

    private fun linkKey(tripId: String) = "calendar.link.$tripId"
    private fun zoneKey(tripId: String) = "calendar.zone.$tripId"
    private fun pendingKey(tripId: String) = "calendar.pending.$tripId"

    /** The trips with a link (the settings file can't list its keys). */
    private fun linked(): List<String> = file.read(INDEX)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private fun write(key: String, value: String?) {
        file.write(key, value)
        revision++
    }

    fun get(tripId: String): CalendarLink? = file.read(linkKey(tripId))?.let { runCatching { json.decodeFromString(CalendarLink.serializer(), it) }.getOrNull() }

    fun put(tripId: String, link: CalendarLink?) {
        write(linkKey(tripId), link?.let { json.encodeToString(CalendarLink.serializer(), it) })
        val ids = linked().toMutableList()
        if (link == null) ids.remove(tripId) else if (tripId !in ids) ids += tripId
        write(INDEX, ids.joinToString("\n").ifEmpty { null })
        if (link?.autoUpdate != true) write(pendingKey(tripId), null)
    }

    /** Every link with the zone picked for it. */
    fun bindings(): Map<String, Pair<CalendarLink, String?>> = linked().mapNotNull { id -> get(id)?.let { id to (it to zone(id)) } }.toMap()

    fun pending(tripId: String): PendingCalendarUpdate? =
        file.read(pendingKey(tripId))?.let { runCatching { json.decodeFromString(PendingCalendarUpdate.serializer(), it) }.getOrNull() }

    private fun putPending(tripId: String, p: PendingCalendarUpdate?) = write(pendingKey(tripId), p?.let { json.encodeToString(PendingCalendarUpdate.serializer(), it) })

    /** Queues an update when the plan's [fingerprint] differs from what was written and isn't queued already. */
    fun enqueue(tripId: String, fingerprint: String): Boolean {
        val link = get(tripId)
        if (link?.autoUpdate == true && link.fingerprint != fingerprint && pending(tripId)?.fingerprint != fingerprint) {
            putPending(tripId, PendingCalendarUpdate(fingerprint, newId()))
            return true
        }
        return false
    }

    fun progress(tripId: String, token: String, phase: String, problem: String? = null) {
        val old = pending(tripId)
        if (old?.token == token) putPending(tripId, old.copy(phase = phase, problem = problem))
    }

    fun complete(tripId: String, token: String) {
        if (pending(tripId)?.token == token) putPending(tripId, null)
    }

    fun completeFingerprint(tripId: String, fingerprint: String) {
        if (pending(tripId)?.fingerprint == fingerprint) putPending(tripId, null)
    }

    /** The zone picked for the trip's times (linked or not), or null when never picked. */
    fun zone(tripId: String): String? = file.read(zoneKey(tripId))

    fun setZone(tripId: String, zone: String) = write(zoneKey(tripId), zone)

    private companion object {
        const val INDEX = "calendar.trips"
    }
}

/**
 * Calendar sync for trips: connect a trip and its plan to one phone calendar, keep it updated, remove it, import picked
 * events into the plan, and write an .ics file for everything else. The phone's own calendar accounts sync to iCloud,
 * Google or Exchange, so the app needs no account. One operation at a time, so a double tap can't add a second set.
 *
 * Android keeps the calendar in step with a background job; an iPhone does it whenever the trips change while the app
 * is open and when the app comes back to the front ([reconcileAutomatic]).
 */
class CalendarSync(
    private val book: TripBook,
    val links: CalendarLinks,
    val phone: PhoneCalendar,
    private val now: () -> Long = ::nowMs,
    private val ids: () -> String = ::newId,
    private val countries: () -> Countries? = { null },
    private val countryZones: (String) -> Set<String> = { emptySet() },
) {
    private val mutex = Mutex()

    fun link(tripId: String): CalendarLink? = links.get(tripId)

    fun pending(tripId: String): PendingCalendarUpdate? = links.pending(tripId)

    /** The zone the trip's times are read in, and the zones worth offering instead. */
    fun zones(tripId: String): ZoneChoice {
        val trip = book.trip(tripId)
        val chosen = links.zone(tripId) ?: links.get(tripId)?.zone
        val entries = book.entries(tripId).map { it.zoneId }
        val places = placeZones(tripId)
        val phoneZone = systemZoneId()
        val current = TripZone.guess(chosen, entries, places, phoneZone)
        return ZoneChoice(current, TripZone.options(current, phoneZone, entries, places, trip?.startDate ?: today()))
    }

    /**
     * For each planned place, its country's zones: the country from the offline outlines, its zones from [countryZones]
     * (the app's: ios/CountryZones.kt, the IANA zone.tab under ICU's names, as Android's ICU gives them).
     */
    private fun placeZones(tripId: String): List<Set<String>> {
        val c = countries() ?: return emptyList()
        return book.items(tripId).asSequence()
            .filter { it.lat != null && it.lng != null }
            .take(200)
            .map { item -> c.codeAt(item.lat!!, item.lng!!)?.takeIf { Countries.isIsoAlpha2(it) }?.let(countryZones).orEmpty() }
            .toList()
    }

    suspend fun setZone(tripId: String, zone: String) {
        locked { links.setZone(tripId, zone) }
        reconcileAutomatic()
    }

    suspend fun setAutomatic(tripId: String, enabled: Boolean) {
        locked { links.get(tripId)?.let { links.put(tripId, it.copy(autoUpdate = enabled)) } }
        if (enabled) reconcileAutomatic()
    }

    /** Writable calendars by account. Needs read access. */
    fun groups(): List<CalendarGroup> = access { CalendarAccounts.groups(phone.calendars()) }

    /** Adds the trip to [calendar] (or brings it up to date there); a trip linked elsewhere moves. */
    suspend fun add(tripId: String, calendar: CalendarInfo, zone: String): SyncResult = locked {
        if (phone.calendars().none { it.id == calendar.id && it.accountName == calendar.accountName && it.accountType == calendar.accountType && it.writable }) {
            throw CalendarGoneException()
        }
        val old = links.get(tripId)
        book.trip(tripId) ?: throw IllegalStateException("No trip")
        var moved = 0
        if (old != null && !old.matches(calendar)) {
            // A failure while moving must not let a later update recreate events in the old calendar.
            links.put(tripId, old.copy(autoUpdate = false))
            moved = removeNow(tripId, old)
        }
        val remembered = if (old?.matches(calendar) == true) old.events else emptyMap()
        links.setZone(tripId, zone)
        links.put(tripId, CalendarLink(calendar.id, calendar.name, calendar.accountName, calendar.accountType, calendar.color, zone, "", 0, remembered, autoUpdate = true))
        val r = sync(tripId, calendar, zone, remembered)
        r.copy(removed = r.removed + moved)
    }

    /** Brings the linked calendar in step with the plan. */
    suspend fun update(tripId: String): SyncResult = locked {
        val link = links.get(tripId) ?: throw IllegalStateException("Not in a calendar")
        val calendar = phone.calendars().firstOrNull(link::matches) ?: throw CalendarGoneException()
        sync(tripId, calendar, link.zoneNow(links.zone(tripId)), link.events)
    }

    /** Deletes every event of the trip from the phone's calendars and forgets the link. Returns how many. */
    suspend fun remove(tripId: String): Int = locked {
        val link = links.get(tripId)
        // An explicit removal stops later updates from recreating the events, even if deleting fails.
        if (link != null) links.put(tripId, link.copy(autoUpdate = false))
        val n = if (link != null) removeNow(tripId, link)
        else phone.markedEvents(tripId, null).map { it.id }.also { if (it.isNotEmpty()) phone.delete(it) }.size
        links.put(tripId, null)
        n
    }

    /**
     * A trip deleted on this phone: its events go from the phone's calendars and the link is forgotten ([remove]).
     * Quiet: without calendar access it changes nothing.
     */
    suspend fun removeDeleted(tripId: String) {
        if (links.get(tripId) == null && !phone.canWrite()) return
        try {
            remove(tripId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No access any more, or the calendar is gone: the events stay where the traveller can delete them.
        }
    }

    private fun removeNow(tripId: String, link: CalendarLink): Int {
        val remembered = if (phone.calendars().any(link::matches)) link.events else emptyMap()
        val existing = found(tripId, remembered, null)
        val ids = CalendarDiff.removal(tripId, phone.ourPackage, link.calendarId, existing, remembered)
        if (ids.isNotEmpty()) phone.delete(ids)
        return ids.size
    }

    private fun sync(tripId: String, calendar: CalendarInfo, zone: String, remembered: Map<String, Remembered>): SyncResult {
        val trip = book.trip(tripId) ?: throw IllegalStateException("No trip")
        val events = TripEvents.build(trip, book.items(tripId), forPhone = true)
        val desired = events.map { it to TripEvents.fields(it, zone) }
        val existing = found(trip.id, remembered, TripEvents.window(events, zone))
        // Old ids without verifiable markers or signatures can't safely be claimed, even if their title matches.
        val byId = existing.associateBy { it.id }
        if (remembered.values.any { r -> byId[r.id]?.let { it.calendarId == calendar.id && it.unmarked && (r.signature == null || it.fields.signature() != r.signature) } == true }) {
            throw CalendarOwnershipException()
        }
        val plan = CalendarDiff.plan(trip.id, phone.ourPackage, calendar.id, desired, existing, remembered)
        val inserted = phone.apply(calendar.id, trip.id, zone, plan)

        val wanted = desired.associate { it.first.key to it.second }
        val kept = (plan.kept + inserted).mapNotNull { (key, id) -> wanted[key]?.let { key to Remembered(id, it.title, it.signature()) } }.toMap()
        val automatic = links.get(trip.id)?.autoUpdate == true
        val fingerprint = TripEvents.fingerprint(events, zone)
        links.setZone(trip.id, zone)
        links.put(
            trip.id,
            CalendarLink(
                calendarId = calendar.id, calendarName = calendar.name, accountName = calendar.accountName,
                accountType = calendar.accountType, color = calendar.color, zone = zone,
                fingerprint = fingerprint, updatedAt = now(), events = kept, autoUpdate = automatic,
            ),
        )
        links.completeFingerprint(trip.id, fingerprint)
        val before = existing.associateBy { it.id }
        val changed = plan.updates.count { (id, e) -> before[id]?.fields?.let { f -> wanted[e.key]?.let(f::sameAs) } != true }
        return SyncResult(inserted.size, changed, plan.deletes.size, calendar.name)
    }

    /**
     * Brings every calendar kept updated in step with its trip, now: called when the trips change while the app is open
     * and when it comes back to the front (Android queues a background job for the same). A trip deleted here takes its
     * events with it. Never throws.
     */
    suspend fun reconcileAutomatic() {
        val bindings = links.bindings().filterValues { it.first.autoUpdate }
        for ((id, binding) in bindings) {
            val trip = book.trip(id)
            if (trip == null) {
                removeDeleted(id)
                continue
            }
            val fingerprint = TripEvents.fingerprint(TripEvents.build(trip, book.items(id), forPhone = true), binding.first.zoneNow(binding.second))
            links.enqueue(id, fingerprint)
            if (links.pending(id) != null) runAutomatic(id)
        }
    }

    /** One update to a calendar kept updated; what stops it is kept as the pending update's state. */
    suspend fun runAutomatic(tripId: String): AutomaticCalendarResult = locked {
        val link = links.get(tripId) ?: return@locked AutomaticCalendarResult.Done
        if (!link.autoUpdate) return@locked AutomaticCalendarResult.Done
        val pending = links.pending(tripId) ?: return@locked AutomaticCalendarResult.Done
        try {
            if (!phone.canWrite()) throw CalendarAccessException()
            val calendar = phone.calendars().firstOrNull(link::matches) ?: throw CalendarGoneException()
            links.progress(tripId, pending.token, "updating")
            sync(tripId, calendar, link.zoneNow(links.zone(tripId)), link.events)
            links.complete(tripId, pending.token)
            AutomaticCalendarResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (_: CalendarAccessException) {
            links.progress(tripId, pending.token, "paused", "permission")
            AutomaticCalendarResult.Paused
        } catch (_: CalendarGoneException) {
            links.progress(tripId, pending.token, "paused", "calendar")
            AutomaticCalendarResult.Paused
        } catch (_: CalendarOwnershipException) {
            links.progress(tripId, pending.token, "paused", "ownership")
            AutomaticCalendarResult.Paused
        } catch (_: Exception) {
            links.progress(tripId, pending.token, "pending", "retry")
            AutomaticCalendarResult.Retry
        }
    }

    /** Only explicit markers and remembered ids; nearby identical appointments are never adopted. */
    private fun found(tripId: String, remembered: Map<String, Remembered>, window: LongRange?): List<PhoneEvent> =
        (phone.markedEvents(tripId, window) + phone.eventsByIds(remembered.values.map { it.id })).distinctBy { it.id }

    /** Calendar events in the trip's dates, ready to pick (the app's own left out). Needs read access. */
    fun importCandidates(tripId: String): List<ImportCandidate> = access {
        val trip = book.trip(tripId) ?: return@access emptyList()
        val first = trip.startDate ?: return@access emptyList()
        val last = trip.endDate?.takeIf { it.toEpochDay() >= first.toEpochDay() } ?: first
        val zone = zones(tripId).current
        val calendars = phone.calendars().filter { !CalendarAccounts.isNoise(it) }.associateBy { it.id }
        val from = wallMs(first.minusDays(1), 0, zone)
        val to = wallMs(last.plusDays(2), 0, zone)
        val instances = phone.instances(from, to).filter { it.calendarId in calendars }
        val ours = phone.markedEventIds(from..to) + links.get(tripId)?.events?.values?.map { it.id }.orEmpty()
        CalendarImport.candidates(instances, calendars, first, last, zone, book.items(tripId), ours)
    }

    /** Adds [picked] to the plan (checked again, so nothing lands twice). Returns how many were added. */
    suspend fun import(tripId: String, picked: List<ImportCandidate>): Int = locked {
        val items = book.items(tripId)
        val fresh = CalendarImport.fresh(picked, items)
        val byDay = items.groupByTo(HashMap()) { it.day }
        val t = now()
        val added = fresh.map { c ->
            val others = byDay.getOrPut(c.day) { ArrayList() }
            val position = c.timeMinutes?.let { PlanOrder.forTime(others, it) } ?: PlanOrder.end(others)
            CalendarImport.toItem(c, tripId, ids(), position, t).also { others += it }
        }
        if (added.isNotEmpty()) book.saveItems(added)
        added.size
    }

    /** The trip and its plan as an .ics file: its name and its text. */
    fun ics(tripId: String, appVersion: String): Pair<String, String> {
        val trip = book.trip(tripId) ?: throw IllegalStateException("No trip")
        val events = TripEvents.build(trip, book.items(tripId))
        return IcsWriter.fileName(trip.title) to IcsWriter.write(trip.title, events, zones(tripId).current, now(), appVersion)
    }

    private fun <T> access(block: () -> T): T {
        if (!phone.canRead()) throw CalendarAccessException()
        return block()
    }

    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { block() }

}
