package com.justdataplease.boringtravel.data.settings

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.justdataplease.boringtravel.backup.BackupPreferences
import com.justdataplease.boringtravel.ui.theme.FontSet
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Saved theme ids from before the five themes keep working, on this phone and from Drive backups. */
class ThemeSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store(name: String, scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(folder.root, name).absolutePath.toPath() },
        scope = scope,
    )

    private val look = stringPreferencesKey("look")

    @Test fun oldSavedThemesReadAsTheirNewTheme() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = store("old.preferences_pb", scope)
            val settings = Settings(store)
            val expected = mapOf(
                "notebook" to Look.StickerBook, "lined" to Look.Sketchbook, "field" to Look.Sketchbook,
                "graph" to Look.StickerBook, "kraft" to Look.Market, "night" to Look.Trail, "logbook" to Look.PostcardPop,
                "something-new" to Look.PostcardPop,
            )
            for ((id, theme) in expected) {
                store.edit { it[look] = id }
                assertEquals(id, theme, settings.look.first())
            }
            store.edit { it.remove(look) }
            assertEquals(Look.PostcardPop, settings.look.first())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun pickingAThemeStoresItsNewId() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = store("new.preferences_pb", scope)
            val settings = Settings(store)
            for (theme in Look.entries) {
                settings.setLook(theme)
                assertEquals(theme.id, store.data.first()[look])
                assertEquals(theme, settings.look.first())
            }
            settings.setFontSet(FontSet.Kraft)
            assertEquals(FontSet.Kraft, settings.fontSet.first())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun demoModeIsUnsetUntilChosenThenKept() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // Unset on a new install (reads as off) until the first start chooses it.
            val settings = Settings(store("demo.preferences_pb", scope))
            assertEquals(false, settings.demoModeChosen())
            assertEquals(false, settings.demoMode.first())
            settings.setDemoMode(true)
            assertEquals(true, settings.demoModeChosen())
            assertEquals(true, settings.demoMode.first())
            settings.setDemoMode(false)
            assertEquals(true, settings.demoModeChosen())
            assertEquals(false, settings.demoMode.first())
            settings.setDemoAnchoredDay(20_400)
            assertEquals(20_400L, settings.demoAnchoredDay())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun backupsWithOldIdsRestoreToTheNearestTheme() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings = Settings(store("restored.preferences_pb", scope))
            settings.restorePreferences(BackupPreferences(look = "night", fontSet = "field"))
            assertEquals(Look.Trail, settings.look.first())
            assertEquals(FontSet.FieldBook, settings.fontSet.first())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
