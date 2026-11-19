package com.justdataplease.boringtravel.data.settings

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.justdataplease.boringtravel.backup.BackupPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Me › Trips look: Tickets until Pictures is picked, and the pick travels in Drive backups. */
class TripsLookSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store(name: String, scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(folder.root, name).absolutePath.toPath() },
        scope = scope,
    )

    @Test fun ticketsUntilPicturesIsPickedAndUnknownIdsAreTickets() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = store("look.preferences_pb", scope)
            val settings = Settings(store)
            assertEquals(TripsLook.Tickets, settings.tripsLook.first())
            settings.setTripsLook(TripsLook.Pictures)
            assertEquals(TripsLook.Pictures, settings.tripsLook.first())
            assertEquals("pictures", store.data.first()[stringPreferencesKey("trips_look")])
            store.edit { it[stringPreferencesKey("trips_look")] = "gallery" }
            assertEquals(TripsLook.Tickets, settings.tripsLook.first())
            assertEquals(TripsLook.Tickets, TripsLook.of(null))
            assertEquals(listOf("Tickets", "Pictures"), TripsLook.entries.map { it.label })
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun thePickTravelsInTheBackupAndAChoiceOnThisPhoneWins() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val old = Settings(store("old.preferences_pb", scope))
            assertNull(old.backupPreferences().tripsLook)
            old.setTripsLook(TripsLook.Pictures)
            val saved = old.backupPreferences()
            assertEquals("pictures", saved.tripsLook)

            // Through the backup file's JSON and onto a new phone.
            val json = Json { ignoreUnknownKeys = true }
            val decoded = json.decodeFromString(BackupPreferences.serializer(), json.encodeToString(BackupPreferences.serializer(), saved))
            val fresh = Settings(store("new.preferences_pb", scope))
            fresh.restorePreferences(decoded)
            assertEquals(TripsLook.Pictures, fresh.tripsLook.first())

            // A backup from before the setting leaves Tickets; a choice made here wins over the backup's.
            val older = Settings(store("older.preferences_pb", scope))
            older.restorePreferences(json.decodeFromString(BackupPreferences.serializer(), "{\"look\":\"pop\"}"))
            assertEquals(TripsLook.Tickets, older.tripsLook.first())
            older.setTripsLook(TripsLook.Tickets)
            older.restorePreferences(saved)
            assertEquals(TripsLook.Tickets, older.tripsLook.first())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
