package com.justdataplease.boringtravel.calendar

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CalendarLinksTest {
    @get:Rule val folder = TemporaryFolder()

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Android FileStorage's SDK stub selects File.renameTo on JVM/Windows. Keep real DataStore/protobuf,
    // using the host-safe filesystem backend and closing its scope before reopening the same file.
    private fun store(scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(folder.root, "calendar.preferences_pb").absolutePath.toPath() },
        scope = scope,
    )
    private fun link(automatic: Boolean = true) = CalendarLink(
        7, "Travel", "traveller@example.invalid", "com.google", zone = "Europe/Athens",
        fingerprint = "saved", updatedAt = 1, autoUpdate = automatic,
    )

    @Test fun onlyAnExplicitAutomaticConnectionQueuesAndRepeatedChangesCoalesce() = runTest {
        val scope = scope()
        try {
            val links = CalendarLinks(store(scope))
            assertFalse(links.enqueue("trip", "new"))
            links.put("trip", link(automatic = false))
            assertFalse(links.enqueue("trip", "new"))
            links.put("trip", link())
            assertFalse(links.enqueue("trip", "saved"))
            assertTrue(links.enqueue("trip", "new"))
            val queued = requireNotNull(links.pending("trip"))
            assertFalse(links.enqueue("trip", "new"))
            assertEquals(queued, links.pending("trip"))
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun oldWorkerCompletionAndFailureCannotEraseOrPauseANewerEdit() = runTest {
        val scope = scope()
        try {
            val links = CalendarLinks(store(scope))
            links.put("trip", link())
            links.enqueue("trip", "flight-at-nine")
            val first = requireNotNull(links.pending("trip"))
            links.progress("trip", first.token, "updating")
            links.enqueue("trip", "flight-at-ten")
            val newer = requireNotNull(links.pending("trip"))
            assertNotEquals(first.token, newer.token)
            links.complete("trip", first.token)
            links.completeFingerprint("trip", first.fingerprint)
            links.progress("trip", first.token, "paused", "permission")
            assertEquals(newer, links.pending("trip"))
            links.put("trip", link().copy(fingerprint = newer.fingerprint))
            links.completeFingerprint("trip", newer.fingerprint)
            assertNull(links.pending("trip"))
            assertFalse(links.enqueue("trip", newer.fingerprint))
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun switchingOffOrRemovingTheLinkDiscardsQueuedWrites() = runTest {
        val scope = scope()
        try {
            val links = CalendarLinks(store(scope))
            links.put("trip", link())
            links.enqueue("trip", "new")
            links.put("trip", link(automatic = false))
            assertNull(links.pending("trip"))
            assertFalse(links.enqueue("trip", "newer"))
            links.put("trip", link())
            links.enqueue("trip", "newest")
            links.put("trip", null)
            assertNull(links.pending("trip"))
            assertTrue(links.bindings().isEmpty())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun connectionZoneAndPausedWorkSurviveProcessRestart() = runTest {
        val firstScope = scope()
        var pending: PendingCalendarUpdate? = null
        try {
            val links = CalendarLinks(store(firstScope))
            links.put("trip", link())
            links.setZone("trip", "America/Santiago")
            links.enqueue("trip", "changed-booking")
            val token = requireNotNull(links.pending("trip")).token
            links.progress("trip", token, "paused", "permission")
            pending = links.pending("trip")
        } finally { firstScope.coroutineContext.job.cancelAndJoin() }
        val secondScope = scope()
        try {
            val reopened = CalendarLinks(store(secondScope))
            assertEquals(pending, reopened.pending("trip"))
            assertEquals(CalendarBinding(link(), "America/Santiago"), reopened.bindings()["trip"])
            reopened.complete("trip", requireNotNull(pending).token)
            assertNull(reopened.pending("trip"))
        } finally { secondScope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun legacyLinksDecodeWithoutSilentlyEnablingAutomation() = runTest {
        val scope = scope()
        try {
            val store = store(scope)
            store.edit { it[stringPreferencesKey("link_trip")] = """{"calendarId":7,"calendarName":"Travel","zone":"UTC","fingerprint":"saved","updatedAt":1,"events":{"item/a":{"id":12,"title":"Flight"}}}""" }
            val links = CalendarLinks(store)
            val old = requireNotNull(links.get("trip"))
            assertFalse(old.autoUpdate)
            assertNull(old.events["item/a"]?.signature)
            assertFalse(links.enqueue("trip", "new"))
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun calendarIdentityRequiresItsAccountAndWritableAccessNotJustTheReusedRowId() {
        val c = CalendarInfo(7, "Travel renamed", "TRAVELLER@example.invalid", "com.google", accessLevel = 700)
        assertTrue(link().matches(c))
        assertFalse(link().matches(c.copy(id = 8)))
        assertFalse(link().matches(c.copy(accountName = "someone-else@example.invalid")))
        assertFalse(link().matches(c.copy(accountType = "LOCAL")))
        assertFalse(link().matches(c.copy(accessLevel = 200)))
    }
}
