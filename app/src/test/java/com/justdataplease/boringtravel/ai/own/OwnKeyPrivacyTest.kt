package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.backup.BackupPreferences
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Own AI keys stay on the phone: no backup or export can carry them. They live only in [OwnAi.PREFS], which Android's
 * cloud backup and device transfer leave out, and which no app code but [OwnAi] opens.
 */
class OwnKeyPrivacyTest {
    private val main = File("src/main")

    @OptIn(ExperimentalSerializationApi::class)
    @Test fun drivePreferencesHaveNoPlaceForAKey() {
        val names = BackupPreferences.serializer().descriptor.elementNames.map { it.lowercase() }
        listOf("key", "secret", "token", "ai", "model", "provider").forEach { word ->
            assertTrue("BackupPreferences.$word", names.none { it == word || it.startsWith(word) || it.endsWith(word) })
        }
    }

    @Test fun onlyOwnAiOpensTheKeyFile() {
        val users = File(main, "java").walk().filter { it.isFile && it.extension == "kt" }
            // The file's name, or the constant passed to a call (KDoc links like [OwnAi.PREFS] don't count).
            .filter { f -> f.readText().let { "\"${OwnAi.PREFS}\"" in it || "(OwnAi.PREFS" in it || " OwnAi.PREFS," in it } }
            .map { it.name }.toSet()
        assertEquals(setOf("OwnAi.kt"), users)
    }

    @Test fun backupAndExportCodeNeverTouchTheKeys() {
        listOf("backup", "export", "together").forEach { dir ->
            File(main, "java/com/justdataplease/boringtravel/$dir").walk().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val text = f.readText()
                assertTrue("${f.name} mentions own keys", "ownAi" !in text && "OwnAi" !in text && "OwnKey" !in text)
            }
        }
    }

    @Test fun androidBackupLeavesPreferencesOut() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        val rules = File(main, "res/xml/data_extraction_rules.xml").readText()
        val cloud = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertTrue(cloud.contains("<exclude domain=\"sharedpref\" />"))
        assertTrue(transfer.contains("<exclude domain=\"sharedpref\" />"))
    }
}
