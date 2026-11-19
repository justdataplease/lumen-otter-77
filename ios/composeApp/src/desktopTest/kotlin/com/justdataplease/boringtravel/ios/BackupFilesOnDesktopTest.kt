package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.backup.BackupArchive
import com.justdataplease.boringtravel.backup.BackupCodec
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupFormatException
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.BackupManifest
import com.justdataplease.boringtravel.backup.BackupPreferences
import com.justdataplease.boringtravel.backup.Inflater
import com.justdataplease.boringtravel.backup.sampleSnapshot
import com.justdataplease.boringtravel.backup.toData
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Backup files on the desktop: a file written here read by Java's own zip (as Android reads it), a file written the way
 * Android writes it (java.util.zip, deflated rows, stored photos) restored here, a restore over other data that puts
 * the phone back like a database and is undone, the settings carried both ways, every refusal in Android's words, and
 * Me › Backup & sync with the phone's save sheet and file picker faked.
 */
@OptIn(ExperimentalTestApi::class)
class BackupFilesOnDesktopTest {
    init {
        runBlocking { BtFontFiles.load() }
    }

    private val photo = ByteArray(40_000) { (it * 31 % 251).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A trip with a note and its photo (a file under media/), a country marked, and the demo's Kyoto beside them. */
    private fun ownBook(files: MemoryMediaFiles): TripBook {
        var clock = 1_795_000_000_000L
        var n = 0
        val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "own-${n++}" })
        val trip = book.newTrip("Naxos", localDate(2027, 6, 17), localDate(2027, 6, 26), subtitle = "Paros → Naxos")
        book.addItem(trip.id, "Portara at sunset", localDate(2027, 6, 18))
        val note = book.writeNote(trip.id, "The bus to Halki.", "Halki")!!
        files.write("media/p1.jpg", photo)
        book.addMedia(Media("p1", trip.id, note.id, "media/p1.jpg", sha256 = sha(photo), createdAt = 0, updatedAt = 0))
        book.markCountryVisited("GR")
        // An example trip: never in a backup.
        book.putRows(trips = listOf(book.trip(trip.id)!!.copy(id = "sample-kyoto", title = "Kyoto & Osaka")))
        return book
    }

    private fun runner(book: TripBook, settings: AppSettings, files: MediaFiles) = BackupFiles(book, settings, files, "1.0", now = { 1_796_000_000_000L })

    @Test
    fun aFileMadeHereIsTheFormatAndroidReads() = runBlocking {
        val files = MemoryMediaFiles()
        val book = ownBook(files)
        val settings = AppSettings.ready(Look.Trail)
        val (path, report) = runner(book, settings, files).saveFile()
        assertTrue(path.startsWith("exports/BoringTravel-") && path.endsWith(".zip"))
        assertEquals(1, report.items.trips)
        assertEquals(1, report.photos)
        assertEquals(0, report.photosLeftOut)
        // Java's own zip reads it as Android's BackupArchive does: the manifest, data.json, the photo; every CRC checked.
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(files.read(path)!!)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readBytes()
            }
        }
        assertEquals(listOf("manifest.json", "data.json", "media/p1.jpg"), entries.keys.toList())
        assertTrue(entries.getValue("media/p1.jpg").contentEquals(photo))
        val manifest = BackupJson.decodeFromString(BackupManifest.serializer(), entries.getValue("manifest.json").decodeToString())
        assertEquals(2, manifest.format)
        assertEquals("BoringTravel", manifest.app)
        assertEquals(sha(entries.getValue("data.json")), manifest.dataSha256)
        val data = BackupJson.decodeFromString(BackupData.serializer(), entries.getValue("data.json").decodeToString())
        // The traveller's own rows, never the examples; the settings chosen here.
        assertEquals(listOf("Naxos"), data.trips.map { it.title })
        assertEquals(listOf("manual:GR"), data.countryMarks.map { it.id })
        assertEquals("trail", data.preferences?.look)
        assertEquals(manifest.counts, data.toSnapshot().tableCounts())
    }

    /** A backup file as Android's BackupArchive writes it: java.util.zip, the rows deflated, the photos stored at level 0. */
    private fun androidZip(data: BackupData, media: Map<String, ByteArray>, format: Int = 2, damage: Boolean = false): ByteArray {
        val bytes = BackupJson.encodeToString(BackupData.serializer(), data).toByteArray()
        val manifest = BackupCodec.manifest(data.toSnapshot(), "1.21.1", 1_790_000_000_000L, format).copy(dataSha256 = sha(bytes) + if (damage) "0" else "")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(BackupJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(bytes)
            zip.closeEntry()
            for ((name, b) in media) {
                zip.setLevel(Deflater.NO_COMPRESSION)
                zip.putNextEntry(ZipEntry(name))
                zip.write(b)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun anAndroidFileRestoresHere() = runBlocking {
        val sample = sampleSnapshot()
        // The sample's photo m1 is in the file (its checksum is the photo's own here).
        val snapshot = sample.copy(media = sample.media.map { if (it.id == "m1") it.copy(sha256 = sha(photo)) else it })
        val prefs = BackupPreferences(look = "trail", fontSet = "notebook", traveller = "v1|woman", clipStyle = "{\"speed\":2}", autoBackup = false)
        val zip = androidZip(snapshot.toData(prefs).copy(sharedTrips = "{\"trips\":[]}"), mapOf("media/m1.jpg" to photo))
        val files = MemoryMediaFiles()
        files.write(BackupFiles.PICKED, zip)
        val book = TripBook(MemoryTripFile())
        val settings = AppSettings(MemorySettingsFile()).apply { onboardingDone = true; demoMode = true }
        val backups = runner(book, settings, files)
        val manifest = backups.peekFile(BackupFiles.PICKED)
        assertEquals(1_790_000_000_000L, manifest.createdAt)
        val report = backups.restoreFile(BackupFiles.PICKED)
        assertTrue(report.changed)
        assertEquals(1, report.photosDownloaded)
        assertEquals(0, report.photosMissing)
        // Every row as the backup has it (Android's own sample: every field of every table).
        val rows = book.snapshot
        assertEquals(snapshot.trips, rows.trips)
        assertEquals(snapshot.planItems, rows.planItems)
        assertEquals(snapshot.entries, rows.entries)
        assertEquals(snapshot.stops, rows.stops)
        assertEquals(snapshot.countryMarks, rows.countryMarks)
        assertEquals(snapshot.books, rows.books)
        assertEquals(snapshot.trackPoints, rows.trackPoints)
        assertTrue(files.read("media/m1.jpg")!!.contentEquals(photo))
        // The settings come back, demo mode ends, and what this phone only passes on is kept for the next backup.
        assertEquals(Look.Trail, settings.look)
        assertFalse(settings.demoMode)
        assertEquals("{\"trips\":[]}", book.sharedTrips)
        val (path, _) = backups.saveFile()
        val again = BackupArchive.read(BackupArchive.reader(files.size(path)!!) { o, l -> files.readRange(path, o, l)!! })
        assertEquals("v1|woman", again.data.preferences?.traveller)
        assertEquals("{\"speed\":2}", again.data.preferences?.clipStyle)
        assertEquals(false, again.data.preferences?.autoBackup)
        assertEquals("trail", again.data.preferences?.look)
        assertEquals("{\"trips\":[]}", again.data.sharedTrips)
        // The rows still read with the engine's BackupJson, as a restore on Android reads them.
        assertEquals(snapshot.trips, BackupJson.decodeFromString(BackupData.serializer(), book.export()).toSnapshot().trips)
    }

    @Test
    fun aRestoreReplacesLikeADatabaseAndIsUndone() = runBlocking {
        // A file made on one phone…
        val files = MemoryMediaFiles()
        val (path, _) = runner(ownBook(files), AppSettings.ready(Look.Trail), files).saveFile()
        // …restored on another with trips of its own and other settings.
        val phone = MemoryMediaFiles()
        phone.write(BackupFiles.PICKED, files.read(path)!!)
        var clock = 1_795_500_000_000L
        val other = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "other-${clock}" })
        val lisbon = other.newTrip("Lisbon", localDate(2026, 5, 9), localDate(2026, 5, 17))
        other.putRows(trips = listOf(lisbon.copy(id = "sample-argentina", title = "Argentina & Patagonia")))
        val settings = AppSettings.ready(Look.Market)
        val backups = runner(other, settings, phone)
        val report = backups.restoreFile(BackupFiles.PICKED)
        assertEquals(1, report.restored.trips)
        assertEquals(1, report.planItems)
        // The phone is the backup now: Naxos back, Lisbon removed (kept as deleted), the example untouched.
        assertEquals(listOf("Naxos"), other.trips(localDate(2026, 11, 18)).filter { !it.isDemo }.map { it.title })
        assertNotNull(other.snapshot.trips.single { it.id == lisbon.id }.deletedAt)
        assertNull(other.snapshot.trips.single { it.id == "sample-argentina" }.deletedAt)
        assertTrue(phone.read("media/p1.jpg")!!.contentEquals(photo))
        assertEquals(Look.Trail, settings.look)
        // Twice changes nothing.
        phone.write(BackupFiles.PICKED, files.read(path)!!)
        assertFalse(backups.restoreFile(BackupFiles.PICKED).changed)
        // Undo: the phone as it was before the restore (Lisbon live, Naxos gone, Market again).
        val before = assertNotNull(backups.undo)
        assertEquals(1, before.items.trips)
        backups.undoRestore()
        assertEquals(listOf("Lisbon"), other.trips(localDate(2026, 11, 18)).filter { !it.isDemo }.map { it.title })
        assertEquals(Look.Market, settings.look)
        // The undo can itself be undone.
        backups.undoRestore()
        assertEquals(listOf("Naxos"), other.trips(localDate(2026, 11, 18)).filter { !it.isDemo }.map { it.title })
        // A phone with nothing of its own keeps no undo.
        val empty = MemoryMediaFiles()
        empty.write(BackupFiles.PICKED, files.read(path)!!)
        val fresh = runner(TripBook(MemoryTripFile()), AppSettings.ready(), empty)
        fresh.restoreFile(BackupFiles.PICKED)
        assertNull(fresh.undo)
    }

    @Test
    fun aFileThatCantBeRestoredSaysWhyInAndroidsWords() = runBlocking {
        val data = sampleSnapshot().toData()
        suspend fun refusal(bytes: ByteArray): String? {
            val files = MemoryMediaFiles()
            files.write("restore/x.zip", bytes)
            return assertFailsWith<BackupFormatException> { runner(TripBook(MemoryTripFile()), AppSettings.ready(), files).restoreFile("restore/x.zip") }.message
        }
        assertEquals("This isn't a BoringTravel backup.", refusal("not a zip at all".encodeToByteArray()))
        assertEquals("This backup is from a newer BoringTravel. Update the app, then restore it.", refusal(androidZip(data, emptyMap(), format = 3)))
        assertEquals("This backup's checksum doesn't match. It may be damaged.", refusal(androidZip(data, emptyMap(), damage = true)))
        val good = androidZip(data, mapOf("media/m1.jpg" to photo))
        assertEquals("This backup is incomplete and can't be opened.", refusal(good.copyOf(good.size / 2)))
        // A photo whose bytes changed after the zip was made: its CRC fails before any row changes.
        val broken = good.copyOf().also { b -> val at = String(b, Charsets.ISO_8859_1).indexOf("media/m1.jpg") + 12 + 100; b[at] = (b[at] + 1).toByte() }
        assertEquals("This backup is damaged and can't be opened.", refusal(broken))
        // The inflater reads what Java's Deflater writes, at every level.
        val text = BackupJson.encodeToString(BackupData.serializer(), data).toByteArray()
        for (level in listOf(0, 1, 6, 9)) {
            val d = Deflater(level, true).apply { setInput(text); finish() }
            val buf = ByteArray(text.size * 2 + 64)
            val n = d.deflate(buf)
            assertTrue(Inflater.inflate(buf.copyOf(n)).contentEquals(text))
        }
    }

    @Test
    fun meBackupSavesAFileAndRestoresOneAfterAsking() {
        val files = MemoryMediaFiles()
        val book = ownBook(files)
        var exported: String? = null
        val fake = Phone(
            exportFile = { path, done -> exported = path; done(true) },
            pickFile = { to, done ->
                files.write(to, files.read(exported!!) ?: ByteArray(0))
                done(true)
            },
        )
        val settings = AppSettings.ready()
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent {
                CompositionLocalProvider(LocalPhone provides fake) {
                    BoringTravelApp(book, settings, localDate(2026, 11, 18), startAt = listOf(Place.Home(HomeTab.Me)), version = "1.0", media = MediaKit(files))
                }
            }
            waitForIdle()
            onNodeWithText("Backup & sync").performClick()
            waitForIdle()
            onNodeWithText("Trips, entries, photos, saved videos and your settings in one zip. Keep it anywhere: this phone, an SD card, another cloud.").assertExists()
            // Saved, and the copy here goes once the phone's sheet took it.
            onNodeWithText("Save a file").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Saved: 1 trip · 1 entry · 1 photo.").fetchSemanticsNodes().isNotEmpty() }
            assertNull(files.size(assertNotNull(exported)))
        }
    }

    @Test
    fun meBackupRestoresAPickedFileAfterAskingAndUndoes() {
        val source = MemoryMediaFiles()
        val (path, _) = runBlocking { runner(ownBook(source), AppSettings.ready(), source).saveFile() }
        val zip = source.read(path)!!
        val files = MemoryMediaFiles()
        var clock = 1_795_500_000_000L
        val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "here-${clock}" })
        book.newTrip("Lisbon", localDate(2026, 5, 9), localDate(2026, 5, 17))
        val fake = Phone(pickFile = { to, done -> files.write(to, zip); done(true) })
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent {
                CompositionLocalProvider(LocalPhone provides fake) {
                    BoringTravelApp(book, AppSettings.ready(), localDate(2026, 11, 18), startAt = listOf(Place.Backup), version = "1.0", media = MediaKit(files))
                }
            }
            waitForIdle()
            onNodeWithText("Restore a file").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Restore this file?").fetchSemanticsNodes().isNotEmpty() }
            // The dialog's Restore (Google Drive's Restore is on the screen too since round 23).
            onAllNodesWithText("Restore").onLast().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Restored the backup of", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf("Naxos"), book.trips(localDate(2026, 11, 18)).filter { !it.isDemo }.map { it.title })
            onNodeWithText("Before the last restore").assertExists()
            onNodeWithText("Undo restore").performClick()
            onNodeWithText("Undo the restore?").assertExists()
            onAllNodesWithText("Undo restore").onLast().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Undone. This phone is as it was before the restore.").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf("Lisbon"), book.trips(localDate(2026, 11, 18)).map { it.title })
        }
    }
}
