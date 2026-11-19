package com.justdataplease.boringtravel.data.settings

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.ui.map.recordingText
import com.justdataplease.boringtravel.tracking.TrackingStatus
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

/** Me › Route tracking: off for a new install (nothing asks at the start), and a level already picked stays. */
class TrackingSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun offUntilPickedAndAPickStays() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(folder.root, "tracking.preferences_pb").absolutePath.toPath() },
                scope = scope,
            )
            val settings = Settings(store)
            assertEquals(TrackingLevel.Off, settings.trackingLevel.first())
            // Picked in the first start of 1.11.0 or in Me: kept as it is.
            for (level in TrackingLevel.entries) {
                store.edit { it[stringPreferencesKey("tracking_level")] = level.id }
                assertEquals(level, settings.trackingLevel.first())
            }
            settings.setTrackingLevel(TrackingLevel.Detailed)
            assertEquals(TrackingLevel.Detailed, settings.trackingLevel.first())
            // An id this version doesn't know is Balanced, as before.
            store.edit { it[stringPreferencesKey("tracking_level")] = "medium" }
            assertEquals(TrackingLevel.Balanced, settings.trackingLevel.first())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun meSaysTrackingIsOffWithoutATripRatherThanPromisingARecording() {
        val none = TrackingStatus()
        assertEquals("Off. Pick a level to record your route during trips.", recordingText(none, TrackingLevel.Off))
        assertEquals("No trip today. Recording starts when you open BoringTravel during a trip.", recordingText(none, TrackingLevel.Balanced))
        assertEquals("Off for Lisbon.", recordingText(TrackingStatus(tripTitle = "Lisbon"), TrackingLevel.Off))
    }
}
