package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.google.RemoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class BackupNamesTest {
    private val athens = ZoneId.of("Europe/Athens")

    /** 5 Nov 2026, 22:00 in Athens (20:00 UTC). */
    private val nov5at22 = 1_793_908_800_000L

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
        assertEquals(emptyList<BackupEntry>(), BackupNames.toPrune(entries.take(10)))
        assertEquals("id13", BackupNames.sorted(entries).first().id)
    }

    @Test fun aRestoreSaysWhatThePhoneIsNow() {
        val en = Locale.ENGLISH
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
        val en = Locale.ENGLISH
        val entry = BackupEntry("id", "n", nov5at22, BackupCounts(2, 31, 148))
        assertEquals("Nothing new since the backup of 5 Nov 2026, 22:00.", BackupText.backedUp(BackupReport(entry, unchanged = true), athens, en))
        assertEquals(
            "Nothing new since the backup of 5 Nov 2026, 22:00. 1 video saved to BoringTravel › Videos.",
            BackupText.backedUp(BackupReport(entry, videos = 1, unchanged = true), athens, en),
        )
        val day = 24 * 3_600_000L
        // Under "Last backup": only when a later check found nothing to save.
        assertEquals("Nothing new since. Last checked: Today, 22:00", BackupText.nothingNew(nov5at22, nov5at22 + 2 * day, nov5at22 + 2 * day + 60_000, athens, en))
        assertNull(BackupText.nothingNew(nov5at22, null, nov5at22 + day, athens, en))
        assertNull(BackupText.nothingNew(nov5at22, nov5at22 - day, nov5at22 + day, athens, en))
        assertNull(BackupText.nothingNew(null, nov5at22, nov5at22 + day, athens, en))
    }

    @Test fun tripsSaysWhenTheDailyBackupIsNotHappening() {
        val day = 24 * 3_600_000L
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
        val en = Locale.ENGLISH
        assertEquals("5 Nov 2026, 22:00", BackupText.date(nov5at22, athens, en))
        val hour = 3_600_000L
        assertEquals("Today, 22:00", BackupText.since(nov5at22, nov5at22 + hour, athens, en))
        assertEquals("Yesterday, 22:00", BackupText.since(nov5at22, nov5at22 + 24 * hour, athens, en))
        assertEquals("5 Nov, 22:00", BackupText.since(nov5at22, nov5at22 + 72 * hour, athens, en))
        assertEquals("5 Nov 2026", BackupText.since(nov5at22, nov5at22 + 60 * 24 * hour, athens, en))
        assertEquals("22:00", BackupText.short(nov5at22, nov5at22 + hour, athens, en))
        assertEquals("5 Nov", BackupText.short(nov5at22, nov5at22 + 24 * hour, athens, en))
    }

    @Test fun dailyBackupAimsForTenAtNight() {
        val morning = ZonedDateTime.of(2026, 11, 5, 9, 30, 0, 0, athens)
        assertEquals(Duration.ofMinutes(12 * 60 + 30), BackupSchedule.firstDelay(morning))
        val late = ZonedDateTime.of(2026, 11, 5, 23, 0, 0, 0, athens)
        assertEquals(Duration.ofHours(23), BackupSchedule.firstDelay(late))
        val exactly = ZonedDateTime.of(2026, 11, 5, 22, 0, 0, 0, athens)
        assertEquals(Duration.ofHours(24), BackupSchedule.firstDelay(exactly))
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
}
