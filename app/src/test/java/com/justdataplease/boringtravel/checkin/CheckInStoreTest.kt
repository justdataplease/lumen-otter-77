package com.justdataplease.boringtravel.checkin

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.time.LocalDate

class CheckInStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val day = LocalDate.of(2028, 5, 4)
    private fun create(scope: CoroutineScope) = CheckInStore(PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
            File(folder.root, "check-ins.preferences_pb").absolutePath.toPath()
        }, scope = scope,
    ))

    @Test fun tripAndGlobalJournalConcurrentClaimsAskOnlyOnce() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = create(scope)
            val results = List(20) { async { store.claim("same-trip", day) } }.awaitAll()
            assertEquals(1, results.count { it })
            assertFalse(store.claim("same-trip", day))
            assertTrue(store.claim("same-trip", day.plusDays(1)))
            assertTrue(store.claim("different-trip", day))
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun askedStateSurvivesRelaunchAndCrossingBackAcrossTimeZone() = runTest {
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val first = create(firstScope)
            assertTrue(first.claim("trip", day))
            assertTrue(first.claim("trip", day.plusDays(1)))
        } finally { firstScope.coroutineContext.job.cancelAndJoin() }
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = create(nextScope)
            assertFalse(reopened.claim("trip", day))
            assertFalse(reopened.claim("trip", day.plusDays(1)))
            assertTrue(reopened.claim("trip", day.plusDays(2)))
        } finally { nextScope.coroutineContext.job.cancelAndJoin() }
    }
}
