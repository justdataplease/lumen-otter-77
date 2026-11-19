package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.ai.own.MemoryKeyValues
import com.justdataplease.boringtravel.ai.own.OwnAiProvider
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.google.GoogleAccount
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The account's screens driven on the desktop on the fakes (round 23): Welcome's account step (no Google in this build,
 * Continue with Google finding a backup and restoring it, Start fresh, nothing in Drive, a no to Drive, a closed page,
 * Sign in with Apple), Me's Account card (signed out, signed in with Google, Sign out, Delete account wiping the AI keys
 * and giving Drive back, an Apple account deleted with Apple's token revoked), Backup & sync (Connect, Back up now,
 * the backups in Drive and one restored, Disconnect), Trips' line when backups stall and the restore offer.
 */
@OptIn(ExperimentalTestApi::class)
class AccountScreensOnDesktopTest {
    init {
        runBlocking { BtFontFiles.load() }
    }

    private val photo = ByteArray(20_000) { (it * 7 % 251).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ownBook(files: MemoryMediaFiles): TripBook {
        var clock = 1_795_000_000_000L
        var n = 0
        val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "own-${n++}" })
        val trip = book.newTrip("Naxos", localDate(2027, 6, 17), localDate(2027, 6, 26))
        val note = book.writeNote(trip.id, "The bus to Halki.", "Halki")!!
        files.write("media/p1.jpg", photo)
        book.addMedia(Media("p1", trip.id, note.id, "media/p1.jpg", sha256 = sha(photo), createdAt = 0, updatedAt = 0))
        return book
    }

    /** Another phone's backup in [google]'s Drive (Naxos with its photo). */
    private fun backupInDrive(google: FakeGoogle) = runBlocking {
        val files = MemoryMediaFiles()
        val book = ownBook(files)
        val settings = AppSettings.ready()
        val account = google.account(settings, files)
        account.signIn()
        DriveBackups(BackupFiles(book, settings, files, "1.21.1"), book, settings, files, account.drive, "1.21.1", google.cloud).backupNow()
    }

    private fun ComposeUiTest.waitFor(text: String, timeout: Long = 15_000) =
        waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.app(book: TripBook, settings: AppSettings, files: MemoryMediaFiles, google: GoogleAccount?, vararg at: Place, keys: MemoryKeyValues = MemoryKeyValues(), phone: Phone = Phone()) {
        setContent {
            CompositionLocalProvider(LocalPhone provides phone) {
                BoringTravelApp(
                    book, settings, localDate(2026, 11, 18), startAt = at.toList(), version = "1.21.1", media = MediaKit(files),
                    ai = androidx.compose.runtime.remember { PlannerPhone(keys) }, google = google,
                )
            }
        }
        waitForIdle()
    }

    @Test fun welcomeWithoutGoogleSaysSoAndGoesOn() = runDesktopComposeUiTest(width = 440, height = 956) {
        val files = MemoryMediaFiles()
        val settings = AppSettings(MemorySettingsFile())
        val google = FakeGoogle()
        app(TripBook(MemoryTripFile()), settings, files, google.account(settings, files, clientId = "", appleOn = false))
        onNodeWithText("Google sign-in isn't set up in this build.").assertExists()
        onNodeWithText("Your journal lives on this phone.").assertExists()
        assertTrue(onAllNodesWithText("Continue with Google").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithText("Sign in with Apple").fetchSemanticsNodes().isEmpty())
        onNodeWithText("Continue").performClick()
        waitUntil { settings.onboardingDone }
        assertTrue(google.cloud.calls.isEmpty())
    }

    @Test fun welcomeFindsTheBackupInDriveAndRestoresIt() = runDesktopComposeUiTest(width = 440, height = 956) {
        val google = FakeGoogle()
        backupInDrive(google)
        val files = MemoryMediaFiles()
        val book = TripBook(MemoryTripFile())
        val settings = AppSettings(MemorySettingsFile())
        app(book, settings, files, google.account(settings, files))
        onNodeWithText("Why Google", substring = true, ignoreCase = true).assertExists()
        onNodeWithText("We need access to your Google Drive for backups").assertExists()
        onNodeWithText("Your journal lives on this phone. Backups and exports go to your own Google Drive.").assertExists()
        onNodeWithText("Sign in with Apple").assertExists()
        onNodeWithText("Continue with Google").performClick()
        waitFor("Found in your Google Drive", 30_000)
        onNodeWithText("Signed in with Google").assertExists()
        onNodeWithText("1 trip · 1 entry · 1 photo").assertExists()
        onNodeWithText("Restore").performClick()
        waitUntil(timeoutMillis = 30_000) { settings.onboardingDone }
        assertEquals(listOf("Naxos"), book.trips(localDate(2026, 11, 18)).map { it.title })
        assertTrue(files.read("media/p1.jpg")!!.contentEquals(photo))
        assertTrue(settings.restoreAsked)
    }

    @Test fun welcomeStartFreshAndNothingInDrive() = runDesktopComposeUiTest(width = 440, height = 956) {
        val google = FakeGoogle()
        backupInDrive(google)
        val files = MemoryMediaFiles()
        val book = TripBook(MemoryTripFile())
        val settings = AppSettings(MemorySettingsFile())
        app(book, settings, files, google.account(settings, files))
        onNodeWithText("Continue with Google").performClick()
        waitFor("Found in your Google Drive", 30_000)
        onNodeWithText("Start fresh").performClick()
        waitUntil { settings.onboardingDone }
        assertTrue(settings.restoreAsked)
        assertTrue(book.snapshot.trips.isEmpty())
    }

    @Test fun welcomeWithAnEmptyDriveGoesStraightToTrips() = runDesktopComposeUiTest(width = 440, height = 956) {
        val google = FakeGoogle()
        val files = MemoryMediaFiles()
        val settings = AppSettings(MemorySettingsFile())
        app(TripBook(MemoryTripFile()), settings, files, google.account(settings, files))
        onNodeWithText("Continue with Google").performClick()
        waitUntil(timeoutMillis = 30_000) { settings.onboardingDone }
        // Nothing in Drive to ask about: this phone's backups can start.
        assertTrue(settings.restoreAsked)
        assertTrue(settings.driveConnected)
    }

    @Test fun welcomeANoToDriveAClosedPageAndApple() = runDesktopComposeUiTest(width = 440, height = 956) {
        val google = FakeGoogle()
        val files = MemoryMediaFiles()
        val settings = AppSettings(MemorySettingsFile())
        app(TripBook(MemoryTripFile()), settings, files, google.account(settings, files))
        // Closed: nothing happens, no words.
        google.browser.mode = FakeAuthBrowser.Mode.Closed
        onNodeWithText("Continue with Google").performClick()
        waitForIdle()
        assertFalse(settings.onboardingDone)
        assertNull(settings.accountEmail)
        // Google's page refused by the network: the words in red under it.
        google.browser.mode = FakeAuthBrowser.Mode.Accept
        google.cloud.route = { c -> if (c.host == "oauth2.googleapis.com") throw NetException("offline") else null }
        onNodeWithText("Continue with Google").performClick()
        waitFor(GoogleAccount.NO_CONNECTION)
        // Sign in with Apple: the account, then Trips (Drive later in Me › Backup & sync).
        google.cloud.route = null
        onNodeWithText("Sign in with Apple").performClick()
        waitUntil(timeoutMillis = 15_000) { settings.onboardingDone }
        assertEquals(AppSettings.PROVIDER_APPLE, settings.accountProvider)
        assertFalse(settings.driveConnected)
    }

    @Test fun welcomeANoToDriveStillSignsInAndGoesOn() = runDesktopComposeUiTest(width = 440, height = 956) {
        val google = FakeGoogle()
        val files = MemoryMediaFiles()
        val settings = AppSettings(MemorySettingsFile())
        app(TripBook(MemoryTripFile()), settings, files, google.account(settings, files))
        google.browser.mode = FakeAuthBrowser.Mode.NoDrive
        onNodeWithText("Continue with Google").performClick()
        waitUntil(timeoutMillis = 15_000) { settings.onboardingDone }
        assertEquals("traveller@example.com", settings.accountEmail)
        assertFalse(settings.driveConnected)
    }

    @Test fun meAccountCardSignsInAndOut() = runDesktopComposeUiTest(width = 440, height = 1800) {
        val google = FakeGoogle()
        val files = MemoryMediaFiles()
        val settings = AppSettings.ready()
        app(TripBook(MemoryTripFile()), settings, files, google.account(settings, files), Place.Home(HomeTab.Me))
        onNodeWithText("ACCOUNT").assertExists()
        onNodeWithText("Not signed in").assertExists()
        onNodeWithText("Your trips are saved on this phone.").assertExists()
        onNodeWithText("Sign in with Apple").assertExists()
        // Backup & sync is Off until Drive is connected (Route tracking says Off too).
        assertEquals(2, onAllNodesWithText("Off").fetchSemanticsNodes().size)
        onNodeWithText("Sign in").performClick()
        waitFor("Signed in as traveller@example.com")
        onNodeWithText("Traveller").assertExists()
        onNodeWithText("Google Drive connected").assertExists()
        // Backup & sync's row: Google Drive, until the first backup.
        onNodeWithText("Google Drive").assertExists()
        assertEquals(1, onAllNodesWithText("Off").fetchSemanticsNodes().size)
        assertTrue(onAllNodesWithText("Sign in with Apple").fetchSemanticsNodes().isEmpty())
        onNodeWithText("Sign out").performClick()
        onNodeWithText("Backups to Google Drive stop until you sign in again. Your trips and journal stay on this phone.").assertExists()
        onAllNodesWithText("Sign out").onLast().performClick()
        waitFor("Not signed in")
        assertFalse(settings.driveConnected)
        // Google keeps the grant on a sign-out (nothing revoked).
        assertTrue(google.cloud.revoked.isEmpty())
    }

    @Test fun deleteAccountWipesTheAiKeysAndGivesDriveBack() = runDesktopComposeUiTest(width = 440, height = 1800) {
        val google = FakeGoogle()
        val files = MemoryMediaFiles()
        val settings = AppSettings.ready()
        val account = google.account(settings, files)
        runBlocking { account.signIn() }
        val keys = MemoryKeyValues()
        // An own Claude key in Me › AI.
        com.justdataplease.boringtravel.ai.own.OwnAi(keys, com.justdataplease.boringtravel.ai.own.PlainCipher, OfflineNet).saveKey(OwnAiProvider.Claude, "sk-ant-test-0000")
        assertTrue(keys.get("key.claude") != null)
        app(TripBook(MemoryTripFile()), settings, files, account, Place.Home(HomeTab.Me), keys = keys)
        onNodeWithText("Delete account").performClick()
        onNodeWithText("Delete account?").assertExists()
        onNodeWithText(
            "BoringTravel signs out, gives its Google Drive access back to Google and deletes your Google name, email and own AI keys from this phone.",
        ).assertExists()
        onNodeWithText("How to delete everything").assertExists()
        onNodeWithText("Delete").performClick()
        waitUntil(timeoutMillis = 15_000) { !settings.signedIn }
        waitUntil(timeoutMillis = 15_000) { keys.names.isEmpty() }
        assertEquals(1, google.cloud.revoked.size)
        assertFalse(settings.driveConnected)
        assertNull(google.keys.get(GoogleAccount.REFRESH))
    }

    @Test fun deletingAnAppleAccountRevokesApplesToken() = runDesktopComposeUiTest(width = 440, height = 1800) {
        val google = FakeGoogle()
        val files = MemoryMediaFiles()
        val settings = AppSettings.ready()
        val account = google.account(settings, files)
        runBlocking { account.signInWithApple() }
        app(TripBook(MemoryTripFile()), settings, files, account, Place.Home(HomeTab.Me))
        onNodeWithText("Signed in with Apple").assertExists()
        onNodeWithText("Traveller").assertExists()
        onNodeWithText("Google Drive not connected").assertExists()
        // Apple's sheet closed at the deletion: nothing deleted, and it says so.
        google.apple.closes = true
        onNodeWithText("Delete account").performClick()
        onNodeWithText("Apple asks you once more", substring = true).assertExists()
        onNodeWithText("Delete").performClick()
        waitFor(GoogleAccount.APPLE_CLOSED)
        assertTrue(settings.signedIn)
        google.apple.closes = false
        onNodeWithText("Delete account").performClick()
        onNodeWithText("Delete").performClick()
        waitUntil(timeoutMillis = 15_000) { !settings.signedIn }
        assertEquals(1, google.cloud.appleRevoked.size)
        assertEquals(1, google.cloud.firebaseDeleted.size)
    }

    @Test fun backupAndSyncConnectsBacksUpRestoresAndDisconnects() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val google = FakeGoogle()
        val files = MemoryMediaFiles()
        val book = ownBook(files)
        val settings = AppSettings.ready()
        app(book, settings, files, google.account(settings, files), Place.Backup)
        onNodeWithText("Google Drive not connected").assertExists()
        onNodeWithText("Back up trips, entries and photos to your own Drive.").assertExists()
        onNodeWithText("Connect Google Drive to back up and restore.").assertExists()
        onNodeWithText("Never").assertExists()
        onNodeWithText("Connect Google Drive").performClick()
        waitFor("Google Drive connected.")
        onNodeWithText("traveller@example.com").assertExists()
        onNodeWithText(BackupFolder.DEFAULT_PLACE).assertExists()
        // The restore offer doesn't come: Drive holds nothing yet.
        onNodeWithText("Back up now").performClick()
        waitFor("Backed up: 1 trip · 1 entry · 1 photo.", 30_000)
        assertEquals(1, google.cloud.filesIn(DriveApi.BACKUPS).size)
        onNodeWithText("Today,", substring = true).assertExists()
        // The backups in Drive, one restored after asking.
        onNodeWithText("Restore").performClick()
        waitFor("Backups in your Drive".uppercase())
        waitFor("1 trip · 1 entry · 1 photo")
        onNodeWithText("1 trip · 1 entry · 1 photo").performClick()
        waitFor("Restore this backup?")
        onAllNodesWithText("Restore").onLast().performClick()
        waitFor("This phone already matched the backup of", 30_000)
        onNodeWithText("Hide backups").performClick()
        // The switches, kept.
        onNodeWithText("Only on Wi‑Fi").performClick()
        assertFalse(settings.wifiOnly)
        // Disconnect after asking: Drive's access given back.
        onNodeWithText("Disconnect").performScrollTo().performClick()
        waitFor("Disconnect Google Drive?")
        onAllNodesWithText("Disconnect").onLast().performClick()
        waitFor("Google Drive disconnected.")
        assertFalse(settings.driveConnected)
        assertEquals(1, google.cloud.revoked.size)
    }

    @Test fun backupAndSyncWithoutGoogleSaysSo() = runDesktopComposeUiTest(width = 440, height = 1800) {
        val files = MemoryMediaFiles()
        val settings = AppSettings.ready()
        app(TripBook(MemoryTripFile()), settings, files, FakeGoogle().account(settings, files, clientId = "", appleOn = false), Place.Backup)
        onNodeWithText("Google Drive not connected").assertExists()
        onNodeWithText("Google sign-in isn't set up in this build.").assertExists()
        assertTrue(onAllNodesWithText("Connect Google Drive").fetchSemanticsNodes().isEmpty())
        // Backup files need no account.
        onNodeWithText("Save a file").assertExists()
    }

    @Test fun tripsSaysWhenBackupsStallAndOpensBackupAndSync() = runDesktopComposeUiTest(width = 440, height = 956) {
        val google = FakeGoogle()
        val files = MemoryMediaFiles()
        val settings = AppSettings.ready()
        val account = google.account(settings, files)
        runBlocking { account.signIn() }
        settings.restoreAsked = true
        settings.lastBackupAt = com.justdataplease.boringtravel.time.nowMs() - 5 * 24 * 3_600_000L
        app(ownBook(files), settings, files, account)
        waitFor("No backup for 5 days.")
        onNodeWithText("Open").performClick()
        waitFor("Backup & sync")
        onNodeWithText("Back up now").assertExists()
    }

    @Test fun aNewPhoneIsOfferedTheBackupInDriveOnce() = runDesktopComposeUiTest(width = 440, height = 956) {
        val google = FakeGoogle()
        backupInDrive(google)
        val files = MemoryMediaFiles()
        val settings = AppSettings.ready()
        val account = google.account(settings, files)
        runBlocking { account.connectDrive() }
        app(ownBook(files), settings, files, account)
        waitFor("Restore your backup?", 30_000)
        // This phone has trips of its own: the question says a restore replaces them.
        onNodeWithText("replaces what's on this phone now", substring = true).assertExists()
        onNodeWithText("Not now").performClick()
        waitUntil { settings.restoreAsked }
        assertTrue(onAllNodesWithText("Restore your backup?").fetchSemanticsNodes().isEmpty())
    }
}
