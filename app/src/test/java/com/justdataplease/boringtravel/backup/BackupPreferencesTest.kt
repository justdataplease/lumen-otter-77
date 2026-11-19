package com.justdataplease.boringtravel.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.justdataplease.boringtravel.data.settings.PlanView
import com.justdataplease.boringtravel.data.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

class BackupPreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    // The Android FileStorage sees stub SDK_INT=0 in host tests, selecting File.renameTo, which cannot
    // replace an existing file on Windows. Use real DataStore/protobuf with its host-safe Okio backend.
    private fun store(name: String, scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(folder.root, name).absolutePath.toPath() },
        scope = scope,
    )

    @Test fun portablePreferencesRoundTripButAccountAndTrackingNeverTravel() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val source = store("source.preferences_pb", scope)
            val destination = store("destination.preferences_pb", scope)
            source.edit {
                it[stringPreferencesKey("look")] = "night"
                it[stringPreferencesKey("font_set")] = "serif"
                it[stringPreferencesKey("print_theme")] = "postcard"
                it[stringPreferencesKey("dictation_language")] = "el-GR"
                it[booleanPreferencesKey("auto_backup")] = false
                it[booleanPreferencesKey("wifi_only")] = true
                it[booleanPreferencesKey("place_photos")] = false
                it[stringPreferencesKey("account_email")] = "private@example.invalid"
                it[booleanPreferencesKey("drive_connected")] = true
                it[stringPreferencesKey("tracking_level")] = "detailed"
            }
            val saved = Settings(source).backupPreferences()
            Settings(destination).restorePreferences(saved)
            assertEquals(saved, Settings(destination).backupPreferences())
            val restored = destination.data.first()
            assertNull(restored[stringPreferencesKey("account_email")])
            assertNull(restored[booleanPreferencesKey("drive_connected")])
            assertNull(restored[stringPreferencesKey("tracking_level")])
            assertEquals(false, saved.placePhotos)
            assertEquals(false, Settings(destination).placePhotos.first())
            assertEquals(7, restored.asMap().size)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun repeatedAndOldBackupsPreserveChoicesMadeOnThisPhone() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = store("settings.preferences_pb", scope)
            val settings = Settings(store)
            store.edit {
                it[stringPreferencesKey("look")] = "my-new-look"
                it[booleanPreferencesKey("auto_backup")] = false
            }
            val incoming = BackupPreferences(look = "old-look", printTheme = "notebook", autoBackup = true, wifiOnly = true)
            settings.restorePreferences(incoming)
            val first = settings.backupPreferences()
            assertEquals("my-new-look", first.look)
            assertEquals(false, first.autoBackup)
            assertEquals("notebook", first.printTheme)
            settings.restorePreferences(incoming)
            settings.restorePreferences(null)
            assertEquals(first, settings.backupPreferences())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun placePhotosAreOnUntilTurnedOffAndABackupFillsOnlyAnUnsetChoice() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val fresh = Settings(store("fresh.preferences_pb", scope))
            assertEquals(true, fresh.placePhotos.first())
            // Never chosen: nothing travels, and an older backup without it changes nothing.
            assertNull(fresh.backupPreferences().placePhotos)
            fresh.restorePreferences(BackupPreferences(look = "notebook"))
            assertEquals(true, fresh.placePhotos.first())
            // Turned off on the old phone: off on the new one.
            fresh.restorePreferences(BackupPreferences(placePhotos = false))
            assertEquals(false, fresh.placePhotos.first())

            // A choice made on this phone wins over the backup's.
            val mine = Settings(store("mine.preferences_pb", scope))
            mine.setPlacePhotos(true)
            mine.restorePreferences(BackupPreferences(placePhotos = false))
            assertEquals(true, mine.placePhotos.first())
            assertEquals(true, mine.backupPreferences().placePhotos)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun planViewIsTheCollapsedListUntilPickedAndTravelsInTheBackup() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val old = Settings(store("old-phone.preferences_pb", scope))
            assertEquals(PlanView.List, old.planView.first())
            assertEquals(false, old.planExpanded.first())
            assertNull(old.backupPreferences().planView)
            assertNull(old.backupPreferences().planExpanded)
            old.setPlanView(PlanView.Calendar)
            old.setPlanExpanded(true)
            assertEquals(PlanView.Calendar, old.planView.first())
            val saved = old.backupPreferences()
            assertEquals("calendar", saved.planView)
            assertEquals(true, saved.planExpanded)

            // Through the backup file's JSON and onto a new phone.
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val decoded = json.decodeFromString(BackupPreferences.serializer(), json.encodeToString(BackupPreferences.serializer(), saved))
            val fresh = Settings(store("new-phone.preferences_pb", scope))
            fresh.restorePreferences(decoded)
            assertEquals(PlanView.Calendar, fresh.planView.first())
            assertEquals(true, fresh.planExpanded.first())

            // A backup from before the setting leaves the default; a choice made here wins.
            val older = Settings(store("older.preferences_pb", scope))
            older.restorePreferences(json.decodeFromString(BackupPreferences.serializer(), "{\"look\":\"pop\"}"))
            assertEquals(PlanView.List, older.planView.first())
            assertEquals(false, older.planExpanded.first())
            older.setPlanView(PlanView.List)
            older.setPlanExpanded(false)
            older.restorePreferences(saved)
            assertEquals(PlanView.List, older.planView.first())
            assertEquals(false, older.planExpanded.first())
            // Unknown ids read as the List.
            assertEquals(PlanView.List, PlanView.of("gantt"))
            assertEquals(PlanView.List, PlanView.of(null))
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun theOldViewsMapOntoTheListAndTheCalendar() {
        // Timeline was the List with every day in full; List was the List one line per item.
        assertEquals(PlanView.List, PlanView.of("timeline"))
        assertTrue(PlanView.expanded("timeline", null))
        assertEquals(PlanView.List, PlanView.of("list"))
        assertFalse(PlanView.expanded("list", null))
        assertEquals(PlanView.Calendar, PlanView.of("calendar"))
        assertFalse(PlanView.expanded("calendar", null))
        assertFalse(PlanView.expanded(null, null))
        // A choice made since wins over what the old id meant.
        assertFalse(PlanView.expanded("timeline", false))
        assertTrue(PlanView.expanded("list", true))
    }

    @Test fun anOldTimelinePickStaysExpandedThroughViewSwitchesAndBackups() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // Picked Timeline before the update.
            val store = store("timeline.preferences_pb", scope)
            store.edit { it[stringPreferencesKey("plan_view")] = "timeline" }
            val phone = Settings(store)
            assertEquals(PlanView.List, phone.planView.first())
            assertEquals(true, phone.planExpanded.first())
            // Over to the Calendar and back: the List is still expanded.
            phone.setPlanView(PlanView.Calendar)
            assertEquals(PlanView.Calendar, phone.planView.first())
            assertEquals(true, phone.planExpanded.first())
            phone.setPlanView(PlanView.List)
            assertEquals(true, phone.planExpanded.first())
            phone.setPlanExpanded(false)
            assertEquals(false, phone.planExpanded.first())

            // Picked List before the update: collapsed.
            val list = store("list.preferences_pb", scope)
            list.edit { it[stringPreferencesKey("plan_view")] = "list" }
            assertEquals(PlanView.List, Settings(list).planView.first())
            assertEquals(false, Settings(list).planExpanded.first())

            // An old backup with Timeline restores as the expanded List on a new phone.
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val oldBackup = json.decodeFromString(BackupPreferences.serializer(), "{\"planView\":\"timeline\"}")
            assertNull(oldBackup.planExpanded)
            val fresh = Settings(store("fresh-timeline.preferences_pb", scope))
            fresh.restorePreferences(oldBackup)
            assertEquals(PlanView.List, fresh.planView.first())
            assertEquals(true, fresh.planExpanded.first())

            // This phone's own old Timeline wins over a newer backup's collapsed List.
            val mine = store("mine-timeline.preferences_pb", scope)
            mine.edit { it[stringPreferencesKey("plan_view")] = "timeline" }
            Settings(mine).restorePreferences(BackupPreferences(planView = "calendar", planExpanded = false))
            assertEquals(PlanView.List, Settings(mine).planView.first())
            assertEquals(true, Settings(mine).planExpanded.first())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    /** Owner, 2026-11-14: backups hold the app's settings and a restore brings them back. */
    @Test fun aRestoreAskedForBringsTheSettingsBackButNotHowThisPhoneBacksUp() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val old = Settings(store("old.preferences_pb", scope))
            old.setLook(com.justdataplease.boringtravel.ui.theme.Look.entries.last())
            old.setMapView(com.justdataplease.boringtravel.data.settings.MapView.Map)
            old.setPrintChoices(com.justdataplease.boringtravel.data.settings.PrintChoices(photoSize = "large", paper = "letter", ruled = true, plan = true))
            old.setTripPhotoStyle("t1", com.justdataplease.boringtravel.data.settings.TripPhotoStyle(0.65f, com.justdataplease.boringtravel.doc.PhotoFrame.Polaroid))
            old.setAutoBackup(true)
            val saved = old.backupPreferences()
            assertEquals("map", saved.mapView)
            assertEquals("large", saved.printPhotos)
            assertEquals("letter", saved.printPaper)
            assertEquals(true, saved.printRuled)
            assertEquals(true, saved.printPlan)
            assertEquals("t1=0.65|polaroid;*=0.65|polaroid", saved.tripPhotos)

            // This phone has its own choices: a plain fill keeps them, a restore replaces them.
            val phone = Settings(store("phone.preferences_pb", scope))
            phone.setLook(com.justdataplease.boringtravel.ui.theme.Look.entries.first())
            phone.setPrintChoices(com.justdataplease.boringtravel.data.settings.PrintChoices(photoSize = "small"))
            phone.setAutoBackup(false)
            phone.setPlacePhotos(false)
            val mine = phone.backupPreferences()
            phone.restorePreferences(saved)
            assertEquals(mine.look, phone.backupPreferences().look)
            assertEquals("small", phone.backupPreferences().printPhotos)
            phone.restorePreferences(saved, replace = true)
            val after = phone.backupPreferences()
            assertEquals(saved.look, after.look)
            assertEquals("large", after.printPhotos)
            assertEquals("map", after.mapView)
            assertEquals(saved.tripPhotos, after.tripPhotos)
            // What the backup doesn't hold stays, and so does how this phone backs up.
            assertEquals(false, after.placePhotos)
            assertEquals(false, after.autoBackup)
            // Undo restore: the phone's own settings come back the same way.
            phone.restorePreferences(mine, replace = true)
            assertEquals(mine.look, phone.backupPreferences().look)
            assertEquals("small", phone.backupPreferences().printPhotos)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
