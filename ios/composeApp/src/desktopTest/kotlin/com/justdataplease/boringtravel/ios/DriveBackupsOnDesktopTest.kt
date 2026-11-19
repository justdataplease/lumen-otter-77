package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.backup.BackupCodec
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.BackupManifest
import com.justdataplease.boringtravel.backup.BackupNames
import com.justdataplease.boringtravel.backup.BackupPreferences
import com.justdataplease.boringtravel.backup.toData
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.data.SavedClip
import com.justdataplease.boringtravel.data.SavedClipIndex
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.google.GoogleAccount
import com.justdataplease.boringtravel.google.readable
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drive backups on the fake Drive (ios/DriveBackups.kt, Android's BackupManager): the backup as Android writes it (a
 * format-1 zip in BoringTravel › Backups with its private properties, every own photo once in Photos with its id and
 * checksum, the hidden marker), nothing new writing nothing, the newest 10 kept, a photo that can't be saved never
 * stopping the rest, failures never quiet, a new phone restoring it (rows, checked photos, the settings, Undo), a damaged
 * photo counted and not placed, saved videos once and back, the daily backup waiting for the restore answer, for Wi‑Fi
 * and for a recent one, and an Android-made Drive backup restoring here.
 */
class DriveBackupsOnDesktopTest {
    private val photo = ByteArray(40_000) { (it * 31 % 251).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** One phone: its trips, files, settings, account and backups, on [google]'s Drive. */
    private class Phone(val book: TripBook, val files: MemoryMediaFiles, val settings: AppSettings, val account: GoogleAccount, val files0: BackupFiles, val drive: DriveBackups, val planned: MutableList<Long?>)

    private fun phone(google: FakeGoogle, book: TripBook = TripBook(MemoryTripFile()), files: MemoryMediaFiles = MemoryMediaFiles(), clock: () -> Long = { 1_796_000_000_000L }): Phone {
        val settings = AppSettings.ready()
        val account = google.account(settings, files)
        val runner = BackupFiles(book, settings, files, "1.21.1", now = clock)
        val planned = mutableListOf<Long?>()
        val drive = DriveBackups(runner, book, settings, files, account.drive, "1.21.1", google.cloud, planner = { planned += it }, now = clock, zone = "Europe/Athens")
        // As the app hooks them: Drive connected or disconnected, a restore made, and the daily backup is planned again.
        account.onDriveChanged = { drive.reschedule() }
        runner.onRestored = { drive.reschedule() }
        return Phone(book, files, settings, account, runner, drive, planned)
    }

    /** A trip with a note and its photo (a file under media/), as the backup-file tests make it. */
    private fun ownBook(files: MemoryMediaFiles): TripBook {
        var clock = 1_795_000_000_000L
        var n = 0
        val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "own-${n++}" })
        val trip = book.newTrip("Naxos", localDate(2027, 6, 17), localDate(2027, 6, 26), subtitle = "Paros → Naxos")
        book.addItem(trip.id, "Portara at sunset", localDate(2027, 6, 18))
        val note = book.writeNote(trip.id, "The bus to Halki.", "Halki")!!
        files.write("media/p1.jpg", photo)
        book.addMedia(Media("p1", trip.id, note.id, "media/p1.jpg", sha256 = sha(photo), createdAt = 0, updatedAt = 0))
        return book
    }

    private fun connected(google: FakeGoogle, p: Phone) = runBlocking { p.account.signIn() }

    @Test fun aBackupGoesToDriveAsAndroidsAndNothingNewWritesNothing() = runBlocking<Unit> {
        val google = FakeGoogle()
        val files = MemoryMediaFiles()
        var clock = 1_796_000_000_000L
        val p = phone(google, ownBook(files), files) { clock }
        connected(google, p)
        val report = p.drive.backupNow()
        assertFalse(report.unchanged)
        assertEquals(1, report.entry.items?.trips)
        assertEquals(0, report.photosLeft)
        // The zip in BoringTravel › Backups, named in the phone's time, with its counts as private properties.
        val zip = google.cloud.filesIn(DriveApi.BACKUPS).single()
        assertEquals(BackupNames.fileName(clock, "Europe/Athens"), zip.name)
        assertEquals("1", zip.appProperties["trips"])
        assertEquals("1", zip.appProperties["photos"])
        assertEquals("1", zip.appProperties["format"])
        // Java's zip reads it as Android's BackupCodec.read does: manifest.json and data.json, nothing else.
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip.content)).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        assertEquals(listOf("manifest.json", "data.json"), entries.keys.toList())
        val manifest = BackupJson.decodeFromString(BackupManifest.serializer(), entries.getValue("manifest.json").decodeToString())
        assertEquals(1, manifest.format)
        assertEquals(sha(entries.getValue("data.json")), manifest.dataSha256)
        // The photo once in Photos, with its id and checksum, and the row linked to it.
        val inPhotos = google.cloud.filesIn(DriveApi.PHOTOS).single()
        assertTrue(inPhotos.content.contentEquals(photo))
        assertEquals(mapOf("mediaId" to "p1", "sha256" to sha(photo)), inPhotos.appProperties)
        assertEquals(inPhotos.id, p.book.media("p1")!!.driveFileId)
        // The marker in the hidden app folder names the backup.
        val marker = google.cloud.items.values.single { "appDataFolder" in it.parents }
        assertEquals(zip.id, BackupCodec.decodeMarker(marker.text)!!.id)
        assertEquals(clock, p.settings.lastBackupAt)
        assertTrue(p.settings.restoreAsked)

        // Nothing new: no second zip, no second photo; the check is noted.
        clock += 3_600_000L
        val again = p.drive.backupNow()
        assertTrue(again.unchanged)
        assertEquals(1, google.cloud.filesIn(DriveApi.BACKUPS).size)
        assertEquals(1, google.cloud.filesIn(DriveApi.PHOTOS).size)
        assertEquals(clock, p.settings.backupCheckedAt)
        assertTrue(com.justdataplease.boringtravel.backup.BackupText.backedUp(again).startsWith("Nothing new since the backup of "))

        // A new note: a new zip.
        clock += 3_600_000L
        p.book.writeNote(p.book.snapshot.trips.single().id, "Back at the port.", "Port")
        assertFalse(p.drive.backupNow().unchanged)
        assertEquals(2, google.cloud.filesIn(DriveApi.BACKUPS).size)
    }

    @Test fun theNewestTenAreKept() = runBlocking<Unit> {
        val google = FakeGoogle()
        val files = MemoryMediaFiles()
        val p = phone(google, ownBook(files), files)
        connected(google, p)
        val folder = google.cloud.folder(DriveApi.BACKUPS)
        repeat(11) { i ->
            google.cloud.put("BoringTravel backup 2026-10-${(10 + i)} 22.00.zip", listOf(folder), ByteArray(10), BackupNames.MIME, mapOf("createdAt" to (1_790_000_000_000L + i).toString(), "trips" to "1", "entries" to "1", "photos" to "0"))
        }
        p.drive.backupNow()
        val left = google.cloud.filesIn(DriveApi.BACKUPS)
        assertEquals(BackupNames.KEEP, left.size)
        // The new one and the nine newest before it.
        assertTrue(left.any { it.name == BackupNames.fileName(1_796_000_000_000L, "Europe/Athens") })
        assertTrue(left.none { it.name.startsWith("BoringTravel backup 2026-10-10") || it.name.startsWith("BoringTravel backup 2026-10-11") })
    }

    @Test fun aPhotoThatCantBeSavedNeverStopsTheBackupAndFailuresAreNeverQuiet() = runBlocking<Unit> {
        val google = FakeGoogle()
        val files = MemoryMediaFiles()
        val book = ownBook(files)
        val trip = book.snapshot.trips.single().id
        // A photo whose file is gone from the phone.
        book.addMedia(Media("p2", trip, null, "media/p2.jpg", sha256 = "00", createdAt = 0, updatedAt = 0))
        val p = phone(google, book, files)
        connected(google, p)
        val report = p.drive.backupNow()
        assertEquals(1, report.photosLeft)
        assertEquals(1, google.cloud.filesIn(DriveApi.BACKUPS).size)
        assertEquals(
            "Backed up: 1 trip · 1 entry · 2 photos. 1 photo couldn't be saved this time; the next backup tries again.",
            com.justdataplease.boringtravel.backup.BackupText.backedUp(report),
        )

        // A zip Drive keeps shorter than sent: never counted as a backup, and said.
        p.book.writeNote(trip, "Another day.", "Day 2")
        google.cloud.route = { c ->
            if (c.q("uploadType") == "multipart" && "BoringTravel backup" in c.text) NetAnswer(200, c.url, emptyMap(), """{"id":"drv99999","name":"x.zip","size":"3"}""".encodeToByteArray()) else null
        }
        val short = runCatching { p.drive.backupNow() }.exceptionOrNull()
        assertEquals("The backup didn't reach Google Drive whole. Try Back up now again.", short?.message)
        // Offline: Android's words.
        google.cloud.route = null
        google.cloud.connected = false
        val offline = runCatching { p.drive.backupNow() }.exceptionOrNull()!!
        assertEquals("No internet connection. Check it and try again.", offline.readable("The backup didn't finish. Try again."))
        // Nothing of the traveller's own: no backup at all.
        google.cloud.connected = true
        val empty = phone(google)
        connected(google, empty)
        assertTrue(runCatching { empty.drive.backupNow() }.exceptionOrNull() is NothingToBackUpException)
    }

    @Test fun aNewPhoneRestoresTheBackupWithItsPhotosAndSettings() = runBlocking<Unit> {
        val google = FakeGoogle()
        val filesA = MemoryMediaFiles()
        val a = phone(google, ownBook(filesA), filesA)
        a.settings.look = Look.Trail
        connected(google, a)
        a.drive.backupNow()

        // Phone B: a trip of its own, the demo on, another theme.
        val filesB = MemoryMediaFiles()
        var clock = 1_796_500_000_000L
        val bookB = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "b-${clock}" })
        bookB.newTrip("Lisbon", localDate(2026, 5, 9), localDate(2026, 5, 17))
        val b = phone(google, bookB, filesB)
        b.settings.demoMode = true
        connected(google, b)
        assertTrue(b.drive.hasOwnData())
        val latest = assertNotNull(b.drive.latest())
        assertEquals(1, latest.items?.trips)
        val report = b.drive.restore(latest)
        assertTrue(report.changed)
        assertEquals(1, report.photosDownloaded)
        assertEquals(0, report.photosMissing)
        assertFalse(report.fromFile)
        assertEquals(listOf("Naxos"), bookB.trips(localDate(2026, 11, 18)).map { it.title })
        assertTrue(filesB.read("media/p1.jpg")!!.contentEquals(photo))
        assertEquals(Look.Trail, b.settings.look)
        assertFalse(b.settings.demoMode)
        assertTrue(b.settings.restoreAsked)
        // The daily backup planned again after the restore.
        assertTrue(b.planned.isNotEmpty())
        // The phone as it was is kept for Undo.
        assertNotNull(b.files0.undo)
        b.files0.undoRestore()
        assertEquals(listOf("Lisbon"), bookB.trips(localDate(2026, 11, 18)).map { it.title })
    }

    @Test fun aDamagedPhotoInDriveIsCountedNotPlaced() = runBlocking<Unit> {
        val google = FakeGoogle()
        val filesA = MemoryMediaFiles()
        val a = phone(google, ownBook(filesA), filesA)
        connected(google, a)
        a.drive.backupNow()
        google.cloud.filesIn(DriveApi.PHOTOS).single().content = ByteArray(10) { 7 }
        val filesB = MemoryMediaFiles()
        val b = phone(google, files = filesB)
        connected(google, b)
        val report = b.drive.restore(b.drive.latest()!!)
        assertEquals(0, report.photosDownloaded)
        assertEquals(1, report.photosMissing)
        assertNull(filesB.size("media/p1.jpg"))
        assertTrue(com.justdataplease.boringtravel.backup.BackupText.restored(report).startsWith("Restored the backup of "))
        assertTrue(com.justdataplease.boringtravel.backup.BackupText.restored(report).endsWith("1 photo couldn't be downloaded. Restore again later to retry."))
    }

    @Test fun savedVideosGoOnceWaitForWifiAndComeBack() = runBlocking<Unit> {
        val google = FakeGoogle()
        val files = MemoryMediaFiles()
        val book = ownBook(files)
        val trip = book.snapshot.trips.single().id
        val clip = SavedClip("1795000000000-abc", trip, "Naxos", 1_795_000_000_000L, 42_000, 1080, 1920)
        files.write(SavedClipIndex.video(clip.id), ByteArray(5_000) { 3 })
        files.write(SavedClipIndex.meta(clip.id), SavedClipIndex.encode(clip).encodeToByteArray())
        val a = phone(google, book, files)
        connected(google, a)
        // Only on Wi‑Fi on a costly connection: the video waits, the rest goes.
        google.cloud.metered = true
        val waiting = a.drive.backupNow()
        assertEquals(1, waiting.videosLeft)
        assertTrue(google.cloud.filesIn(DriveApi.VIDEOS).isEmpty())
        google.cloud.metered = false
        val sent = a.drive.backupNow()
        assertEquals(1, sent.videos)
        val video = google.cloud.filesIn(DriveApi.VIDEOS).single()
        assertEquals("Naxos.mp4", video.name)
        assertEquals(clip.id, video.appProperties["clipId"])
        // Once: the next backup leaves it.
        assertEquals(0, a.drive.backupNow().videos)

        val filesB = MemoryMediaFiles()
        val b = phone(google, files = filesB)
        connected(google, b)
        val report = b.drive.restore(b.drive.latest()!!)
        assertEquals(1, report.videos)
        assertEquals(clip.id, SavedClipIndex.list(filesB).single().id)
        assertEquals(5_000L, filesB.size(SavedClipIndex.video(clip.id)))
    }

    @Test fun theDailyBackupWaitsForTheRestoreAnswerWifiAndARecentOne() = runBlocking<Unit> {
        val google = FakeGoogle()
        val filesA = MemoryMediaFiles()
        val a = phone(google, ownBook(filesA), filesA)
        connected(google, a)
        a.drive.backupNow()

        // A new phone with notes of its own, never backed up, not asked yet: nothing goes up.
        val filesB = MemoryMediaFiles()
        var clock = 1_797_000_000_000L
        val b = phone(google, ownBook(filesB), filesB) { clock }
        connected(google, b)
        assertEquals(DailyResult.Skipped, b.drive.daily())
        assertEquals(1, google.cloud.filesIn(DriveApi.BACKUPS).size)
        // Answered (Not now): it backs up.
        b.settings.restoreAsked = true
        assertEquals(DailyResult.Done, b.drive.daily())
        assertEquals(2, google.cloud.filesIn(DriveApi.BACKUPS).size)
        // Within 18 hours of that: skipped.
        clock += 3_600_000L
        assertEquals(DailyResult.Skipped, b.drive.daily())
        // A day later on a costly connection with Only on Wi‑Fi: waits; on Wi‑Fi off it goes.
        clock += 24 * 3_600_000L
        google.cloud.metered = true
        assertEquals(DailyResult.Skipped, b.drive.daily())
        b.settings.wifiOnly = false
        assertEquals(DailyResult.Done, b.drive.daily())
        // Saving power, backups off, or Drive not connected: skipped.
        clock += 24 * 3_600_000L
        assertEquals(DailyResult.Skipped, b.drive.daily(lowPower = true))
        b.settings.autoBackup = false
        assertEquals(DailyResult.Skipped, b.drive.daily())
        b.settings.autoBackup = true
        b.drive.reschedule()
        assertNotNull(b.planned.last())
        b.account.disconnectDrive()
        assertEquals(DailyResult.Skipped, b.drive.daily())
        assertNull(b.planned.last())
        // A failure is tried again, three times in all.
        b.account.signIn()
        b.book.writeNote(b.book.snapshot.trips.single().id, "One more.", "More")
        google.cloud.route = { c -> if (c.q("uploadType") == "multipart" && "BoringTravel backup" in c.text) NetAnswer(500, c.url) else null }
        clock += 24 * 3_600_000L
        assertEquals(DailyResult.Retry, b.drive.daily())
        assertEquals(DailyResult.Retry, b.drive.daily())
        assertEquals(DailyResult.Done, b.drive.daily())
        assertEquals(0, b.settings.dailyBackupTries)
    }

    @Test fun anAndroidMadeDriveBackupRestoresHere() = runBlocking<Unit> {
        // A Drive backup as Android's BackupCodec.write makes it: java.util.zip, both entries deflated.
        val sample = com.justdataplease.boringtravel.backup.sampleSnapshot()
        val snapshot = sample.copy(media = sample.media.map { if (it.id == "m1") it.copy(sha256 = sha(photo), driveFileId = null) else it })
        val data = snapshot.toData(BackupPreferences(look = "market"))
        val manifest = BackupCodec.manifest(data.toSnapshot(), "1.21.1", 1_790_000_000_000L)
        val google = FakeGoogle()
        val photoId = google.cloud.put("m1.jpg", listOf(google.cloud.folder(DriveApi.PHOTOS)), photo, "image/jpeg").id
        val withLink = data.copy(media = data.media.map { if (it.id == "m1") it.copy(driveFileId = photoId) else it })
        val linkedBytes = BackupJson.encodeToString(BackupData.serializer(), withLink).toByteArray()
        val linkedOut = ByteArrayOutputStream()
        ZipOutputStream(linkedOut).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(BackupJson.encodeToString(BackupManifest.serializer(), manifest.copy(dataSha256 = sha(linkedBytes))).toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("data.json"))
            z.write(linkedBytes)
            z.closeEntry()
        }
        google.cloud.put(BackupNames.fileName(1_790_000_000_000L, "Europe/Athens"), listOf(google.cloud.folder(DriveApi.BACKUPS)), linkedOut.toByteArray(), BackupNames.MIME, BackupNames.properties(manifest))
        val files = MemoryMediaFiles()
        val p = phone(google, files = files)
        connected(google, p)
        val report = p.drive.restore(p.drive.latest()!!)
        assertEquals(snapshot.trips, p.book.snapshot.trips)
        assertEquals(1, report.photosDownloaded)
        assertTrue(files.read(snapshot.media.single { it.id == "m1" }.localPath)!!.contentEquals(photo))
        assertEquals(Look.Market, p.settings.look)
    }
}
