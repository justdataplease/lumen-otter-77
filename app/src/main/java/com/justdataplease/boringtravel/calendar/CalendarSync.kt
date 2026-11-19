package com.justdataplease.boringtravel.calendar

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.icu.util.TimeZone
import androidx.room.withTransaction
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.BuildConfig
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.data.repo.now
import com.justdataplease.boringtravel.export.ExportFiles
import com.justdataplease.boringtravel.export.Sharing
import com.justdataplease.boringtravel.geo.Countries
import com.justdataplease.boringtravel.tracking.TrackProcessor
import com.justdataplease.boringtravel.ui.trip.PlanOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** The linked calendar isn't on the phone any more (account removed, calendar deleted or made read-only). */
class CalendarGoneException : Exception("The calendar is gone")
class CalendarOwnershipException : Exception("A linked event changed outside BoringTravel. Review the calendar connection before updating.")

enum class AutomaticCalendarResult { Done, Retry, Paused }

/** What an add or update did. */
data class SyncResult(val added: Int, val changed: Int, val removed: Int, val calendarName: String) {
    val nothing: Boolean get() = added == 0 && changed == 0 && removed == 0
}

/**
 * Calendar sync for trips: explicitly connect a trip and its plan to one phone calendar, keep it updated,
 * remove it, import selected events into the plan, and write an .ics file for everything else. The phone's own
 * calendar accounts sync to Google, iCloud or Exchange, so no Google Calendar scope is needed.
 * The decisions are pure ([TripEvents], [CalendarDiff], [CalendarImport], [IcsWriter], [TripZone]); this class reads
 * and writes. One operation at a time, so a double tap can't add a second set.
 */
class CalendarSync(private val graph: AppGraph) {
    private val app: Context = graph.app
    val phone = PhoneCalendar(app)
    private val links = CalendarLinks(app)
    private val mutex = Mutex()
    private val automation by lazy { CalendarAutomation(graph, this, links) }

    private val countries: Countries? by lazy {
        runCatching { app.assets.open(TrackProcessor.COUNTRIES_ASSET).bufferedReader().use { Countries.parse(it.readText()) } }.getOrNull()
    }
    private val countryZones = ConcurrentHashMap<String, Set<String>>()

    fun link(tripId: String): Flow<CalendarLink?> = links.observe(tripId)
    fun chosenZone(tripId: String): Flow<String?> = links.observeZone(tripId)
    fun pendingUpdate(tripId: String): Flow<PendingCalendarUpdate?> = links.observePending(tripId)
    fun startAutomaticUpdates() = automation.start()

    /** The zone the trip's times are read in, and the zones worth offering instead. */
    suspend fun zones(tripId: String): ZoneChoice = withContext(Dispatchers.IO) {
        val trip = graph.trips.get(tripId)
        val chosen = links.zone(tripId) ?: links.get(tripId)?.zone
        val entries = graph.journal.forTrip(tripId).map { it.zoneId }
        val places = placeZones(tripId)
        val phoneZone = ZoneId.systemDefault()
        val current = TripZone.guess(chosen, entries, places, phoneZone)
        ZoneChoice(current, TripZone.options(current, phoneZone, entries, places, trip?.startDate ?: LocalDate.now()))
    }

    suspend fun setZone(tripId: String, zone: ZoneId) {
        locked { links.setZone(tripId, zone.id) }
        reconcileAutomatic()
    }

    suspend fun setAutomatic(tripId: String, enabled: Boolean) {
        locked { links.get(tripId)?.let { links.put(tripId, it.copy(autoUpdate = enabled)) } }
        if (enabled) reconcileAutomatic(resume = true)
    }

    /** For each planned place, its country's zones (offline countries + the phone's time zone data). */
    private suspend fun placeZones(tripId: String): List<Set<String>> {
        val c = countries ?: return emptyList()
        return graph.plan.items(tripId).asSequence()
            .filter { it.lat != null && it.lng != null }
            .take(200)
            .map { item -> c.codeAt(item.lat!!, item.lng!!)?.takeIf { Countries.isIsoAlpha2(it) }?.let(::zonesOf).orEmpty() }
            .toList()
    }

    private fun zonesOf(country: String): Set<String> = countryZones.getOrPut(country) {
        runCatching { TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, country, null).toSet() }.getOrDefault(emptySet())
    }

    /** Writable calendars by account. Needs read access. */
    suspend fun groups(): List<CalendarGroup> = io { CalendarAccounts.groups(phone.calendars()) }

    /** Adds the trip to [calendar] (or brings it up to date there); a trip linked elsewhere moves. */
    suspend fun add(tripId: String, calendar: CalendarInfo, zone: ZoneId): SyncResult = locked {
        if (phone.calendars().none { it.id == calendar.id && it.accountName == calendar.accountName && it.accountType == calendar.accountType && it.writable }) {
            throw CalendarGoneException()
        }
        val old = links.get(tripId)
        val trip = graph.trips.get(tripId) ?: throw IllegalStateException("No trip")
        var moved = 0
        if (old != null && !old.matches(calendar)) {
            // A crash while moving must not let queued work recreate events in the old destination.
            links.put(tripId, old.copy(autoUpdate = false))
            moved = removeNow(tripId, old)
        }
        val remembered = if (old?.matches(calendar) == true) old.events else emptyMap()
        // Persist explicit destination before the provider writes. Process death can resume this connection.
        links.setZone(tripId, zone.id)
        links.put(tripId, CalendarLink(calendar.id, calendar.name, calendar.accountName, calendar.accountType, calendar.color,
            zone.id, "", 0, remembered, autoUpdate = true))
        val desired = TripEvents.fingerprint(TripEvents.build(trip, graph.plan.items(tripId), forPhone = true), zone)
        if (links.enqueue(tripId, desired)) CalendarWork.enqueue(app, tripId)
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
        // Prevent queued or retried work from recreating events after an explicit removal, even if deletion fails.
        if (link != null) links.put(tripId, link.copy(autoUpdate = false))
        val trip = graph.trips.get(tripId)
        val n = when {
            link != null && trip != null -> removeNow(tripId, link)
            else -> phone.markedEvents(tripId).map { it.id }.also { if (it.isNotEmpty()) phone.delete(it) }.size
        }
        links.put(tripId, null)
        n
    }

    /**
     * A trip deleted or left on this phone: its events go from the phone's calendars and the link is forgotten
     * ([remove]). Quiet: without calendar access (or with nothing to remove) it changes nothing, and a link whose
     * events can't be reached stops updating.
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

    /** Deletes the trip's events from [link]'s calendar (and any marked ones elsewhere). */
    private fun removeNow(tripId: String, link: CalendarLink): Int {
        // Numeric calendar/event IDs can be reused after an account is removed and re-added.
        val remembered = if (phone.calendars().any(link::matches)) link.events else emptyMap()
        val existing = found(tripId, remembered)
        val ids = CalendarDiff.removal(tripId, phone.ourPackage, link.calendarId, emptyList(), existing, remembered)
        if (ids.isNotEmpty()) phone.delete(ids)
        return ids.size
    }

    private suspend fun sync(tripId: String, calendar: CalendarInfo, zone: ZoneId, remembered: Map<String, Remembered>): SyncResult {
        val (trip, items) = graph.db.withTransaction {
            (graph.trips.get(tripId) ?: throw IllegalStateException("No trip")) to graph.plan.items(tripId)
        }
        val events = TripEvents.build(trip, items, forPhone = true)
        val desired = events.map { it to TripEvents.fields(it, zone) }
        val existing = found(trip.id, remembered)
        // Old IDs without verifiable markers/signatures cannot safely be claimed, even if their title matches.
        val byId = existing.associateBy { it.id }
        if (remembered.values.any { r -> byId[r.id]?.let { it.calendarId == calendar.id && it.unmarked &&
                (r.signature == null || it.fields.signature() != r.signature) } == true }) throw CalendarOwnershipException()
        val plan = CalendarDiff.plan(trip.id, phone.ourPackage, calendar.id, desired, existing, remembered)
        val inserted = phone.apply(calendar.id, trip.id, zone, plan)

        val wanted = desired.associate { it.first.key to it.second }
        val kept = (plan.kept + inserted).mapNotNull { (key, id) -> wanted[key]?.let { key to Remembered(id, it.title, it.signature()) } }.toMap()
        val automatic = links.get(trip.id)?.autoUpdate == true
        val fingerprint = TripEvents.fingerprint(events, zone)
        links.setZone(trip.id, zone.id)
        links.put(
            trip.id,
            CalendarLink(
                calendarId = calendar.id, calendarName = calendar.name, accountName = calendar.accountName,
                accountType = calendar.accountType, color = calendar.color, zone = zone.id,
                fingerprint = fingerprint, updatedAt = now(), events = kept, autoUpdate = automatic,
            ),
        )
        links.completeFingerprint(trip.id, fingerprint)
        val before = existing.associateBy { it.id }
        val changed = plan.updates.count { (id, e) -> before[id]?.fields?.let { f -> wanted[e.key]?.let(f::sameAs) } != true }
        return SyncResult(inserted.size, changed, plan.deletes.size, calendar.name)
    }

    /** Queue from committed local data, including trip deletions. Also repairs a crash between save and enqueue. */
    suspend fun reconcileAutomatic(resume: Boolean = false) = withContext(Dispatchers.IO) {
        val bindings = links.bindings().filterValues { it.link.autoUpdate }
        CalendarWork.recovery(app, bindings.isNotEmpty())
        if (bindings.isEmpty()) return@withContext
        val (trips, items) = graph.db.withTransaction { graph.db.trips().all() to graph.db.planItems().all() }
        val tripsById = trips.associateBy { it.id }
        val itemsByTrip = items.groupBy { it.tripId }
        for ((id, binding) in bindings) {
            val trip = tripsById[id] ?: continue
            val fingerprint = TripEvents.fingerprint(TripEvents.build(trip, itemsByTrip[id].orEmpty(), forPhone = true), binding.link.zoneNow(binding.chosenZone))
            val queued = links.enqueue(id, fingerprint)
            if (queued || (resume && links.pending(id) != null)) CalendarWork.enqueue(app, id)
        }
    }

    /** One-way updates to an explicitly selected provider calendar. Server propagation belongs to its sync adapter. */
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
        } catch (_: SecurityException) {
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

    /** Only explicit markers and remembered IDs; nearby identical appointments are never adopted. */
    private fun found(tripId: String, remembered: Map<String, Remembered>): List<PhoneEvent> =
        (phone.markedEvents(tripId) + phone.eventsByIds(remembered.values.map { it.id }))
            .distinctBy { it.id }

    /** Calendar events in the trip's dates, ready to pick (our own events left out). Needs read access. */
    suspend fun importCandidates(tripId: String): List<ImportCandidate> = io {
        val trip = graph.trips.get(tripId) ?: return@io emptyList()
        val first = trip.startDate ?: return@io emptyList()
        val last = trip.endDate?.takeIf { !it.isBefore(first) } ?: first
        val zone = zones(tripId).current
        val calendars = phone.calendars().filter { !CalendarAccounts.isNoise(it) }.associateBy { it.id }
        val from = first.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = last.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val instances = phone.instances(from, to).filter { it.calendarId in calendars }
        val ours = phone.markedEventIds() + links.get(tripId)?.events?.values?.map { it.id }.orEmpty()
        CalendarImport.candidates(instances, calendars, first, last, zone, graph.plan.items(tripId), ours)
    }

    /** Adds [picked] to the plan (checked again, so nothing lands twice). Returns how many were added. */
    suspend fun import(tripId: String, picked: List<ImportCandidate>): Int = locked {
        val items = graph.plan.items(tripId)
        val fresh = CalendarImport.fresh(picked, items)
        val byDay = items.groupByTo(HashMap()) { it.day }
        val t = now()
        val added = fresh.map { c ->
            val others = byDay.getOrPut(c.day) { ArrayList() }
            val position = c.timeMinutes?.let { PlanOrder.forTime(others, it) } ?: PlanOrder.end(others)
            CalendarImport.toItem(c, tripId, newId(), position, t).also { others += it }
        }
        if (added.isNotEmpty()) graph.plan.saveAll(added)
        added.size
    }

    /** The trip and its plan as an .ics file in cache/exports (shared through the FileProvider). */
    suspend fun icsFile(tripId: String): File = withContext(Dispatchers.IO) {
        val trip = graph.trips.get(tripId) ?: throw IllegalStateException("No trip")
        val events = TripEvents.build(trip, graph.plan.items(tripId))
        val text = IcsWriter.write(trip.title, events, zones(tripId).current, Instant.now(), BuildConfig.VERSION_NAME)
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        File(dir, ExportFiles.fileName(trip.title, "ics")).apply { writeText(text, Charsets.UTF_8) }
    }

    /** The share sheet for an .ics file: Apple Calendar (through Mail, AirDrop…), Outlook, Gmail, a calendar app. */
    fun shareIcs(context: Context, file: File, title: String) {
        val uri = Sharing.uri(context, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(IcsWriter.MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .also { it.clipData = ClipData.newRawUri(title, uri) }
        val chooser = Intent.createChooser(send, title)
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (_: SecurityException) {
            throw CalendarAccessException()
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { io(block) }
}
