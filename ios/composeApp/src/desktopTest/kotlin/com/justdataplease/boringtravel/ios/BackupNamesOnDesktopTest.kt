package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.backup.BackupCounts
import com.justdataplease.boringtravel.backup.BackupEntry
import com.justdataplease.boringtravel.backup.BackupManifest
import com.justdataplease.boringtravel.backup.BackupNames
import com.justdataplease.boringtravel.backup.BackupReport
import com.justdataplease.boringtravel.backup.BackupSchedule
import com.justdataplease.boringtravel.backup.BackupText
import com.justdataplease.boringtravel.backup.RestoreReport
import com.justdataplease.boringtravel.compat.Locale
import com.justdataplease.boringtravel.google.RemoteFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drive backups' names, properties, rotation, texts and the daily backup's time (backup/BackupNames.kt), against the
 * Android app's own cases (its BackupNamesTest, with the zone as an id and compat's Locale), and the iPhone's own rule:
 * a daily backup on opening the app once 22:00 has passed without one.
 */
class BackupNamesOnDesktopTest {
    private val athens = "Europe/Athens"
    private val en = Locale.ENGLISH

    /** 5 Nov 2026, 22:00 in Athens (20:00 UTC). */
    private val nov5at22 = 1_793_908_800_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test fun fileNamesUseThePhonesTimeAndReadBack() {
        val name = BackupNames.fileName(nov5at22, athens)
        assertEquals("BoringTravel backup 2026-11-05 22.00.zip", name)
        assertEquals(nov5at22, BackupNames.timeFromName(name, athens))
        assertNull(BackupNames.timeFromName("Holiday.zip", athens))
        assertNull(BackupNames.timeFromName("BoringTravel backup soon.zip", athens))
    }

    @Test fun listEntriesReadDatesAndCountsFromDriveProperties() {
        val manifest = BackupManifest(createdAt = nov5at22 + 5_000, items = BackupCounts(2, 31, 148), appVersion = "0.1.0")
        val props = BackupNames.properties(manifest)
        val file = RemoteFile("id1", "BoringTravel backup 2026-11-05 22.00.zip", "application/zip", size = 4096, appProperties = props)
        assertEquals(BackupEntry("id1", file.name, nov5at22 + 5_000, BackupCounts(2, 31, 148), 4096), BackupNames.entry(file, athens))

        // Without properties: the time comes from the name and there are no counts to show.
        val bare = RemoteFile("id2", "BoringTravel backup 2026-11-05 22.00.zip")
        assertEquals(BackupEntry("id2", bare.name, nov5at22, null, null), BackupNames.entry(bare, athens))

        assertNull(BackupNames.entry(RemoteFile("x", "notes.txt"), athens))
        assertNull(BackupNames.entry(file.copy(trashed = true), athens))
    }

    @Test fun rotationKeepsTheNewestTen() {
        val entries = (1..13).map { BackupEntry("id$it", "n$it", createdAt = it * 1000L, items = null) }.shuffled()
        val gone = BackupNames.toPrune(entries)
        assertEquals(listOf("id3", "id2", "id1"), gone.map { it.id })
        assertEquals(emptyList(), BackupNames.toPrune(entries.take(10)))
        assertEquals("id13", BackupNames.sorted(entries).first().id)
    }

    @Test fun aRestoreSaysWhatThePhoneIsNow() {
        val report = RestoreReport(nov5at22, BackupCounts(2, 31, 148), photosDownloaded = 3, photosMissing = 0, planItems = 14, videos = 1)
        assertEquals(
            "Restored the backup of 5 Nov 2026, 22:00: 2 trips · 14 plan items · 31 entries · 148 photos. 1 video came back.",
            BackupText.restored(report, athens, en),
        )
        assertEquals(
            "This phone already matched the backup of 5 Nov 2026, 22:00: 1 trip. 2 photos couldn't be downloaded. Restore again later to retry.",
            BackupText.restored(RestoreReport(nov5at22, BackupCounts(1, 0, 0), 0, photosMissing = 2, changed = false), athens, en),
        )
        assertEquals(
            "Restored the backup of 5 Nov 2026, 22:00. 1 photo wasn't in the file.",
            BackupText.restored(RestoreReport(nov5at22, BackupCounts(), 0, photosMissing = 1, fromFile = true), athens, en),
        )
    }

    @Test fun aBackupSaysWhatWaitsForTheNextOne() {
        val entry = BackupEntry("id", "n", nov5at22, BackupCounts(2, 31, 148))
        assertEquals("Backed up: 2 trips · 31 entries · 148 photos.", BackupText.backedUp(BackupReport(entry)))
        assertEquals(
            "Backed up: 2 trips · 31 entries · 148 photos. 1 photo couldn't be saved this time; the next backup tries again." +
                " 2 videos saved to BoringTravel › Videos. 1 video waits for the next backup.",
            BackupText.backedUp(BackupReport(entry, photosLeft = 1, videos = 2, videosLeft = 1)),
        )
    }

    @Test fun aBackupWithNothingNewSaysSo() {
        val entry = BackupEntry("id", "n", nov5at22, BackupCounts(2, 31, 148))
        assertEquals("Nothing new since the backup of 5 Nov 2026, 22:00.", BackupText.backedUp(BackupReport(entry, unchanged = true), athens, en))
        assertEquals(
            "Nothing new since the backup of 5 Nov 2026, 22:00. 1 video saved to BoringTravel › Videos.",
            BackupText.backedUp(BackupReport(entry, videos = 1, unchanged = true), athens, en),
        )
        // Under "Last backup": only when a later check found nothing to save.
        assertEquals("Nothing new since. Last checked: Today, 22:00", BackupText.nothingNew(nov5at22, nov5at22 + 2 * day, nov5at22 + 2 * day + 60_000, athens, en))
        assertNull(BackupText.nothingNew(nov5at22, null, nov5at22 + day, athens, en))
        assertNull(BackupText.nothingNew(nov5at22, nov5at22 - day, nov5at22 + day, athens, en))
        assertNull(BackupText.nothingNew(null, nov5at22, nov5at22 + day, athens, en))
    }

    @Test fun tripsSaysWhenTheDailyBackupIsNotHappening() {
        assertNull(BackupText.trouble(autoBackup = true, driveConnected = true, needsReconnect = false, lastBackupAt = nov5at22, now = nov5at22 + 2 * day))
        assertEquals("No backup for 3 days.", BackupText.trouble(true, true, false, nov5at22, nov5at22 + 3 * day + 5))
        assertEquals("Backups stopped. Reconnect Google Drive.", BackupText.trouble(true, true, true, nov5at22, nov5at22))
        // Off, not connected, or never made (a new phone): nothing to say.
        assertNull(BackupText.trouble(false, true, true, nov5at22, nov5at22 + 9 * day))
        assertNull(BackupText.trouble(true, false, false, nov5at22, nov5at22 + 9 * day))
        assertNull(BackupText.trouble(true, true, false, null, nov5at22))
    }

    @Test fun countsReadNaturally() {
        assertEquals("2 trips · 31 entries · 148 photos", BackupText.counts(BackupCounts(2, 31, 148)))
        assertEquals("1 trip · 1 entry · 1 photo", BackupText.counts(BackupCounts(1, 1, 1)))
        assertEquals("0 trips · 0 entries · 0 photos", BackupText.counts(BackupCounts()))
    }

    @Test fun datesForPeople() {
        assertEquals("5 Nov 2026, 22:00", BackupText.date(nov5at22, athens, en))
        assertEquals("Today, 22:00", BackupText.since(nov5at22, nov5at22 + hour, athens, en))
        assertEquals("Yesterday, 22:00", BackupText.since(nov5at22, nov5at22 + 24 * hour, athens, en))
        assertEquals("5 Nov, 22:00", BackupText.since(nov5at22, nov5at22 + 72 * hour, athens, en))
        assertEquals("5 Nov 2026", BackupText.since(nov5at22, nov5at22 + 60 * 24 * hour, athens, en))
        assertEquals("22:00", BackupText.short(nov5at22, nov5at22 + hour, athens, en))
        assertEquals("5 Nov", BackupText.short(nov5at22, nov5at22 + 24 * hour, athens, en))
    }

    @Test fun dailyBackupAimsForTenAtNight() {
        // 5 Nov 2026 in Athens: 9:30, 23:00 and 22:00 exactly (Android's cases, as milliseconds).
        val morning = nov5at22 - 12 * hour - 30 * 60_000L
        assertEquals(12 * hour + 30 * 60_000L, BackupSchedule.firstDelayMs(morning, athens))
        assertEquals(23 * hour, BackupSchedule.firstDelayMs(nov5at22 + hour, athens))
        assertEquals(24 * hour, BackupSchedule.firstDelayMs(nov5at22, athens))
    }

    @Test fun staleMarkerDoesNotHideANewerCompletedBackup() {
        val old = BackupEntry("old", "old.zip", 10, null)
        val newer = BackupEntry("new", "new.zip", 20, null)
        assertEquals(newer, BackupNames.newest(old, newer))
        assertEquals(newer, BackupNames.newest(newer, old))
        assertEquals(old, BackupNames.newest(old, null))
        assertEquals(newer, BackupNames.newest(null, newer))
        assertNull(BackupNames.newest(null, null))
    }

    @Test fun futureClockAndMissingBackupNeverSkipScheduledWork() {
        assertEquals(false, BackupSchedule.isRecent(0, 100))
        assertEquals(false, BackupSchedule.isRecent(200, 100))
        assertEquals(true, BackupSchedule.isRecent(100, 100))
        assertEquals(true, BackupSchedule.isRecent(100, 100 + BackupSchedule.RECENT_MS - 1))
        assertEquals(false, BackupSchedule.isRecent(100, 100 + BackupSchedule.RECENT_MS))
    }

    @Test fun openingTheAppAfterTenAtNightIsTheDailyBackupsTurn() {
        // Backed up yesterday at 22:30: not due at 21:00 today, due after 22:00.
        val last = nov5at22 - day + 30 * 60_000L
        assertFalse(BackupSchedule.dueOnOpen(last, nov5at22 - hour, athens))
        assertTrue(BackupSchedule.dueOnOpen(last, nov5at22 + 10 * 60_000L, athens))
        // A Back up now this morning makes tonight's wait (Android's 18 hours).
        assertFalse(BackupSchedule.dueOnOpen(nov5at22 - 13 * hour, nov5at22 + hour, athens))
        // Never backed up: due.
        assertTrue(BackupSchedule.dueOnOpen(null, nov5at22 - hour, athens))
        // The next try: tonight's 22:00 before it, tomorrow's after it.
        assertEquals(nov5at22, BackupSchedule.nextAt(nov5at22 - hour, athens))
        assertEquals(nov5at22 + day, BackupSchedule.nextAt(nov5at22 + hour, athens))
    }

    @Test fun aBackupWithoutCountsShowsItsSize() {
        assertEquals("4 KB", BackupText.size(4096))
        assertEquals("2.5 MB", BackupText.size(2_621_440))
        assertEquals("Backup", BackupText.size(null))
    }
}
