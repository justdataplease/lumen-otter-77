package com.justdataplease.boringtravel.backup

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import com.justdataplease.boringtravel.clip.Glasses
import com.justdataplease.boringtravel.clip.Person
import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.clip.Who
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

/** Me › Your traveller in settings and in backups (a new phone gets it; a choice made on this phone stays). */
class TravellerBackupTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store(name: String, scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(folder.root, name).absolutePath.toPath() },
        scope = scope,
    )

    private val couple = Traveller(Who.Couple, man = Person.MAN.copy(glasses = Glasses.Glasses))

    @Test fun theTravellerIsASettingThatTravelsWithBackups() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val source = Settings(store("source.preferences_pb", scope))
            // Unset: the default man.
            assertEquals(Traveller.DEFAULT, source.traveller.first())
            assertNull(source.backupPreferences().traveller)
            source.setTraveller(couple)
            assertEquals(couple, source.traveller.first())
            val saved = source.backupPreferences()
            assertEquals(couple.encode(), saved.traveller)
            // A new phone gets it.
            val fresh = Settings(store("fresh.preferences_pb", scope))
            fresh.restorePreferences(saved)
            assertEquals(couple, fresh.traveller.first())
            // A phone that chose its own keeps it.
            val mine = Settings(store("mine.preferences_pb", scope))
            mine.setTraveller(Traveller(Who.Woman))
            mine.restorePreferences(saved)
            assertEquals(Traveller(Who.Woman), mine.traveller.first())
            // Older backups have none: nothing changes.
            val older = Settings(store("older.preferences_pb", scope))
            older.restorePreferences(BackupPreferences(look = "notebook"))
            assertEquals(Traveller.DEFAULT, older.traveller.first())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
