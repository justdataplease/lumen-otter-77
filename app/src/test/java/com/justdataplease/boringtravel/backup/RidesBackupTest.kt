package com.justdataplease.boringtravel.backup

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import com.justdataplease.boringtravel.clip.Cloth
import com.justdataplease.boringtravel.clip.Ride
import com.justdataplease.boringtravel.clip.RideModel
import com.justdataplease.boringtravel.clip.Rides
import com.justdataplease.boringtravel.data.settings.Settings
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Me › Your traveller & rides' rides in settings and in backups (a new phone gets them; a choice made on this phone stays). */
class RidesBackupTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store(name: String, scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(folder.root, name).absolutePath.toPath() },
        scope = scope,
    )

    private val camper = Rides.DEFAULT.with(Ride(RideModel.Camper, Cloth.Teal, Cloth.White)).with(Ride(RideModel.Seaplane))

    @Test fun theRidesAreASettingThatTravelsWithBackups() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val source = Settings(store("source.preferences_pb", scope))
            // Unset: today's look.
            assertEquals(Rides.DEFAULT, source.rides.first())
            assertNull(source.backupPreferences().rides)
            source.setRides(camper)
            assertEquals(camper, source.rides.first())
            val saved = source.backupPreferences()
            assertEquals(camper.encode(), saved.rides)
            // A new phone gets them.
            val fresh = Settings(store("fresh.preferences_pb", scope))
            fresh.restorePreferences(saved)
            assertEquals(camper, fresh.rides.first())
            // A phone that chose its own keeps them.
            val mine = Settings(store("mine.preferences_pb", scope))
            val tram = Rides.DEFAULT.with(Ride(RideModel.Tram))
            mine.setRides(tram)
            mine.restorePreferences(saved)
            assertEquals(tram, mine.rides.first())
            // Older backups have none: nothing changes.
            val older = Settings(store("older.preferences_pb", scope))
            older.restorePreferences(BackupPreferences(look = "notebook"))
            assertEquals(Rides.DEFAULT, older.rides.first())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
