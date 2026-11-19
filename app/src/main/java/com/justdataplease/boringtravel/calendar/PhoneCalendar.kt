package com.justdataplease.boringtravel.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import java.time.ZoneId

/** The app may not read (or write) the phone's calendars. */
class CalendarAccessException : Exception("No calendar access")

/**
 * Thin access to Android's calendar provider: the phone's calendar accounts (Google, Samsung, Exchange/Outlook, iCloud
 * through a CalDAV app…), which sync on their own. Reads rows and writes what [CalendarDiff] decided; no other logic.
 * Every call does I/O: call it off the main thread.
 */
class PhoneCalendar(private val context: Context) {
    val ourPackage: String get() = context.packageName
    private val resolver get() = context.contentResolver

    fun canRead(): Boolean = granted(Manifest.permission.READ_CALENDAR)
    fun canWrite(): Boolean = canRead() && granted(Manifest.permission.WRITE_CALENDAR)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun requireRead() { if (!canRead()) throw CalendarAccessException() }
    private fun requireWrite() { if (!canWrite()) throw CalendarAccessException() }

    /** Every calendar that syncs events (read-only ones too; callers filter). */
    fun calendars(): List<CalendarInfo> {
        requireRead()
        val base = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
            Calendars.OWNER_ACCOUNT, Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE,
        )
        val selection = "${Calendars.SYNC_EVENTS} = 1"
        // IS_PRIMARY is old (API 17) but a provider may still lack it; then the account's own calendar counts as main.
        val cursor = try {
            resolver.query(Calendars.CONTENT_URI, base + Calendars.IS_PRIMARY, selection, null, null)
        } catch (_: IllegalArgumentException) {
            resolver.query(Calendars.CONTENT_URI, base, selection, null, null)
        }
        return cursor?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val account = c.str(Calendars.ACCOUNT_NAME)
                    val owner = c.str(Calendars.OWNER_ACCOUNT)
                    add(
                        CalendarInfo(
                            id = c.long(Calendars._ID),
                            name = c.str(Calendars.CALENDAR_DISPLAY_NAME).ifBlank { account.ifBlank { "Calendar" } },
                            accountName = account,
                            accountType = c.str(Calendars.ACCOUNT_TYPE),
                            ownerAccount = owner,
                            color = c.int(Calendars.CALENDAR_COLOR),
                            primary = if (c.getColumnIndex(Calendars.IS_PRIMARY) >= 0) c.int(Calendars.IS_PRIMARY) == 1
                            else owner.isNotBlank() && owner.equals(account, ignoreCase = true),
                            accessLevel = c.int(Calendars.CALENDAR_ACCESS_LEVEL),
                            visible = c.int(Calendars.VISIBLE) == 1,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    /** Events carrying [tripId]'s marker, in any calendar. */
    fun markedEvents(tripId: String): List<PhoneEvent> = (
        events("${Events.DELETED} = 0 AND ${Events.DESCRIPTION} LIKE ?", arrayOf("%" + CalendarMarker.descriptionPrefix(ourPackage, tripId) + "%")) +
            events("${Events.DELETED} = 0 AND ${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.CUSTOM_APP_URI} LIKE ?",
                arrayOf(ourPackage, CalendarMarker.tripPrefix(tripId) + "%"), needsMarker = true)
        ).distinctBy { it.id }.filter { it.appPackage == ourPackage && CalendarMarker.parse(it.appUri)?.first == tripId }

    /** Ids of every event this app wrote (any trip): never offered for import. */
    fun markedEventIds(): Set<Long> {
        requireRead()
        val sourceIds = events("${Events.DELETED} = 0 AND ${Events.DESCRIPTION} LIKE ?",
            arrayOf("%" + CalendarMarker.descriptionPrefix(ourPackage) + "%"))
            .filter { it.appPackage == ourPackage && CalendarMarker.parse(it.appUri) != null }.mapTo(HashSet()) { it.id }
        val customIds = try {
            resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.DELETED} = 0 AND ${Events.CUSTOM_APP_PACKAGE} = ?", arrayOf(ourPackage), null)
                ?.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
        } catch (_: IllegalArgumentException) { emptySet<Long>() }
        return sourceIds + customIds.orEmpty()
    }

    fun eventsByIds(ids: Collection<Long>): List<PhoneEvent> =
        ids.distinct().chunked(200).flatMap { chunk ->
            events("${Events.DELETED} = 0 AND ${Events._ID} IN (${chunk.joinToString(",") { "?" }})", chunk.map { it.toString() }.toTypedArray())
        }

    private fun events(selection: String, args: Array<String>, needsMarker: Boolean = false): List<PhoneEvent> {
        requireRead()
        val base = arrayOf(
            Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.ALL_DAY,
            Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE, Events.AVAILABILITY,
        )
        val cursor = try {
            resolver.query(Events.CONTENT_URI, base + arrayOf(Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI), selection, args, null)
        } catch (e: IllegalArgumentException) {
            // A provider without custom-app columns still supports our description source links.
            if (needsMarker) return emptyList()
            resolver.query(Events.CONTENT_URI, base, selection, args, null)
        }
        return cursor?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val description = c.str(Events.DESCRIPTION)
                    val source = CalendarMarker.fromDescription(description, ourPackage)
                    val packageName = c.strOrNull(Events.CUSTOM_APP_PACKAGE)
                    val uri = c.strOrNull(Events.CUSTOM_APP_URI)
                    val recoverSource = source != null && (packageName.isNullOrEmpty() || packageName == ourPackage) &&
                        (uri.isNullOrEmpty() || uri == CalendarMarker.uri(source.first, source.second))
                    add(
                        PhoneEvent(
                            id = c.long(Events._ID),
                            calendarId = c.long(Events.CALENDAR_ID),
                            fields = EventFields(
                                title = c.str(Events.TITLE),
                                location = c.str(Events.EVENT_LOCATION),
                                description = CalendarMarker.cleanDescription(description, ourPackage),
                                allDay = c.int(Events.ALL_DAY) == 1,
                                startMs = c.long(Events.DTSTART),
                                endMs = c.long(Events.DTEND),
                                timeZone = c.str(Events.EVENT_TIMEZONE),
                                free = c.int(Events.AVAILABILITY) == Events.AVAILABILITY_FREE,
                            ),
                            appPackage = if (recoverSource) ourPackage else packageName,
                            appUri = if (recoverSource) CalendarMarker.uri(source.first, source.second) else uri,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    /**
     * Writes [plan] in one batch per 150 operations: deletes, updates, then inserts into [calendarId]. Returns the new
     * events' ids by key. Every written event carries [tripId]'s marker, unless the provider has no such columns.
     */
    fun apply(calendarId: Long, tripId: String, zone: ZoneId, plan: CalendarPlan): Map<String, Long> {
        requireWrite()
        if (plan.isEmpty) return emptyMap()
        if (plan.deletes.isNotEmpty()) delete(plan.deletes)
        val chunks = writes(calendarId, tripId, zone, plan, marker = supportsCustomMarkers()).chunked(150)
        val uris = ArrayList<Uri?>()
        chunks.forEach { chunk ->
            val results = resolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(chunk))
            results.forEach { uris += it.uri }
        }
        return insertedIds(uris, plan)
    }

    /** Probe before writes; retrying an uncertain partially applied batch inline could add duplicates. */
    private fun supportsCustomMarkers(): Boolean = try {
        resolver.query(Events.CONTENT_URI, arrayOf(Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI), "${Events._ID} = -1", null, null)
            ?.use { it.getColumnIndex(Events.CUSTOM_APP_PACKAGE) >= 0 && it.getColumnIndex(Events.CUSTOM_APP_URI) >= 0 } == true
    } catch (_: IllegalArgumentException) { false }

    /** Updates first, then inserts (so the inserts' results are the tail). */
    private fun writes(calendarId: Long, tripId: String, zone: ZoneId, plan: CalendarPlan, marker: Boolean): List<ContentProviderOperation> =
        plan.updates.map { (id, e) -> ContentProviderOperation.newUpdate(eventUri(id)).withValues(values(e, zone, tripId, null, marker)).build() } +
            plan.inserts.map { e -> ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(values(e, zone, tripId, calendarId, marker)).build() }

    private fun insertedIds(uris: List<Uri?>, plan: CalendarPlan): Map<String, Long> = buildMap {
        plan.inserts.forEachIndexed { i, e -> uris.getOrNull(plan.updates.size + i)?.let { put(e.key, ContentUris.parseId(it)) } }
    }

    /** Deletes events by id (synced calendars drop them at their next sync). */
    fun delete(ids: Collection<Long>): Int {
        requireWrite()
        var n = 0
        for (chunk in ids.distinct().chunked(150)) {
            val ops = chunk.map { ContentProviderOperation.newDelete(eventUri(it)).build() }
            resolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(ops)).forEach { n += it.count ?: 0 }
        }
        return n
    }

    /** Occurrences in visible calendars overlapping [from]..[to] (epoch ms), recurring events expanded. */
    fun instances(from: Long, to: Long): List<PhoneInstance> {
        requireRead()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.EVENT_LOCATION, Instances.DESCRIPTION,
            Instances.ALL_DAY, Instances.BEGIN, Instances.END,
        )
        return Instances.query(resolver, projection, from, to)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        PhoneInstance(
                            eventId = c.long(Instances.EVENT_ID),
                            calendarId = c.long(Instances.CALENDAR_ID),
                            title = c.str(Instances.TITLE),
                            location = c.str(Instances.EVENT_LOCATION),
                            description = c.str(Instances.DESCRIPTION),
                            allDay = c.int(Instances.ALL_DAY) == 1,
                            begin = c.long(Instances.BEGIN),
                            end = c.long(Instances.END),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun values(e: TripEvent, zone: ZoneId, tripId: String, calendarId: Long?, marker: Boolean): ContentValues {
        val f = TripEvents.fields(e, zone)
        return ContentValues().apply {
            if (calendarId != null) put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, f.title)
            put(Events.EVENT_LOCATION, f.location)
            put(Events.DESCRIPTION, CalendarMarker.description(f.description, ourPackage, tripId, e.key))
            put(Events.ALL_DAY, if (f.allDay) 1 else 0)
            put(Events.DTSTART, f.startMs)
            put(Events.DTEND, f.endMs)
            putNull(Events.DURATION)
            put(Events.EVENT_TIMEZONE, f.timeZone)
            put(Events.AVAILABILITY, if (f.free) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY)
            if (marker) {
                put(Events.CUSTOM_APP_PACKAGE, ourPackage)
                put(Events.CUSTOM_APP_URI, CalendarMarker.uri(tripId, e.key))
            }
        }
    }

    private fun eventUri(id: Long): Uri = ContentUris.withAppendedId(Events.CONTENT_URI, id)

    private fun Cursor.str(col: String): String = strOrNull(col).orEmpty()
    private fun Cursor.strOrNull(col: String): String? = getColumnIndex(col).let { if (it < 0 || isNull(it)) null else getString(it) }
    private fun Cursor.long(col: String): Long = getColumnIndex(col).let { if (it < 0 || isNull(it)) 0L else getLong(it) }
    private fun Cursor.int(col: String): Int = getColumnIndex(col).let { if (it < 0 || isNull(it)) 0 else getInt(it) }
}
