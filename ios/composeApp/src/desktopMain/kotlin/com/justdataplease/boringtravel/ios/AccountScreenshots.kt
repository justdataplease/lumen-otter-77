package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.backup.BackupCounts
import com.justdataplease.boringtravel.backup.BackupEntry
import com.justdataplease.boringtravel.backup.BackupProgress
import com.justdataplease.boringtravel.backup.BackupText
import com.justdataplease.boringtravel.compat.SystemBars
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.google.GoogleAccount
import com.justdataplease.boringtravel.google.LocalGoogle
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.runBlocking
import java.io.File

/*
 * Round 23's pictures (19 Nov 2026), on the fake Google, Drive and Apple (GoogleFakes.kt): Welcome's account step (with
 * Google and Sign in with Apple, a build without Google, looking for a backup, the backup found, restoring it, its
 * failure, a sign-in failure), Me's Account card (signed out, with Google, with Apple, Drive wanting the owner again, a
 * build without Google; Sign out's and Delete account's questions, a deletion that didn't happen), Backup & sync with
 * Drive (not connected, connected after a backup, a backup running, its result, a failure, the backups in Drive, the
 * question before a restore, Disconnect's, Connect again, no Google), the restore offer (and when it replaces trips),
 * Trips' line when backups stall, the book's PDF sent to Drive, and Share's Google Doc saved. In two looks where the
 * colours matter. `gradlew :composeApp:screenshots -PshotsOnly=accounts` draws only these; the trip video's Drive
 * pictures are with the export's (`-PshotsOnly=clip`).
 */
fun accounts(out: File) {
    fun shoot(name: String, tall: Float = 1f, frames: Int = 12, content: @Composable () -> Unit) {
        File(out, "$name.png").writeBytes(render(PHONE_W, (PHONE_H * tall).toInt(), PHONE_DENSITY, frames, content))
    }

    /** A screen on the theme's paper in the phone's frame, with the account (and its Drive backups) on hand. */
    @Composable
    fun Framed(look: Look, google: GoogleAccount?, drive: DriveBackups? = null, files: BackupFiles? = null, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalGoogle provides google, LocalDriveBackups provides drive, LocalBackupFiles provides files, LocalAppVersion provides PREVIEW_VERSION) {
            BoringTravelTheme(look) {
                DisposableEffect(look) {
                    SystemBars.set(!look.dark)
                    onDispose { }
                }
                Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(PHONE_FRAME)) { content() }
            }
        }
    }

    val photo = ByteArray(20_000) { (it * 7 % 251).toByte() }
    fun ownBook(files: MemoryMediaFiles): TripBook {
        var clock = 1_795_000_000_000L
        var n = 0
        val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = { "own-${n++}" })
        val trip = book.newTrip("Naxos", localDate(2027, 6, 17), localDate(2027, 6, 26), subtitle = "Paros → Naxos")
        book.addItem(trip.id, "Portara at sunset", localDate(2027, 6, 18))
        val note = book.writeNote(trip.id, "The bus to Halki.", "Halki")!!
        files.write("media/p1.jpg", photo)
        book.addMedia(Media("p1", trip.id, note.id, "media/p1.jpg", sha256 = Sha256.hex(photo), createdAt = 0, updatedAt = 0))
        return book
    }
    val found = BackupEntry("drv00042", "BoringTravel backup 2026-11-17 22.00.zip", 1_794_945_600_000L, BackupCounts(3, 41, 128), 2_400_000)

    // ------------------------------------------------------------------ Welcome
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        val google = FakeGoogle()
        val settings = AppSettings(MemorySettingsFile()).apply { if (look != Look.Default) this.look = look }
        val account = google.account(settings, MemoryMediaFiles())
        // Google and Sign in with Apple beside it (Apple 4.8), Use without an account.
        shoot("welcome-account-$id") {
            BoringTravelApp(TripBook(MemoryTripFile()), settings, SAMPLE_TODAY, PHONE_FRAME, version = PREVIEW_VERSION, google = account)
        }
        // Signed in, the backup found in Drive, then restoring it.
        val signed = AppSettings.ready(look).apply { setAccount("traveller@example.com", "Traveller", AppSettings.PROVIDER_GOOGLE); driveConnected = true; driveAccount = "traveller@example.com" }
        shoot("welcome-found-$id") { Framed(look, account) { WelcomeScreen(signed, {}, stepAtStart = WelcomeStep.Found, foundAtStart = found) } }
    }
    val google = FakeGoogle()
    val plain = AppSettings(MemorySettingsFile())
    shoot("welcome-account-no-google") {
        BoringTravelApp(TripBook(MemoryTripFile()), plain, SAMPLE_TODAY, PHONE_FRAME, version = PREVIEW_VERSION, google = google.account(plain, MemoryMediaFiles(), clientId = "", appleOn = false))
    }
    val signed = AppSettings.ready().apply { setAccount("traveller@example.com", "Traveller", AppSettings.PROVIDER_GOOGLE); driveConnected = true }
    val account = google.account(signed, MemoryMediaFiles())
    shoot("welcome-looking") { Framed(Look.Default, account) { WelcomeScreen(signed, {}, stepAtStart = WelcomeStep.Looking) } }
    val restoring = BackupFiles(TripBook(MemoryTripFile()), signed, MemoryMediaFiles(), PREVIEW_VERSION).apply {
        progress = BackupProgress(restoring = true, label = "Downloading photos", done = 2, total = 12, file = false)
    }
    shoot("welcome-found-restoring") { Framed(Look.Default, account, files = restoring) { WelcomeScreen(signed, {}, stepAtStart = WelcomeStep.Found, foundAtStart = found) } }
    shoot("welcome-found-error") {
        Framed(Look.Default, account) { WelcomeScreen(signed, {}, stepAtStart = WelcomeStep.Found, foundAtStart = found, errorAtStart = "No internet connection. Check it and try again.") }
    }
    val fresh = AppSettings(MemorySettingsFile())
    shoot("welcome-account-error") {
        Framed(Look.Default, google.account(fresh, MemoryMediaFiles())) { WelcomeScreen(fresh, {}, messageAtStart = "Google sign-in didn't work. Try again.") }
    }

    // ------------------------------------------------------------------ Me's Account card
    fun me(name: String, look: Look, settings: AppSettings, account: GoogleAccount?, tall: Float = 1f) =
        shoot(name, tall) {
            CompositionLocalProvider(LocalGoogle provides account) {
                BoringTravelApp(sampleBook(), settings, SAMPLE_TODAY, PHONE_FRAME, startAt = listOf(Place.Home(HomeTab.Me)), version = PREVIEW_VERSION, google = account)
            }
        }
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        val out1 = AppSettings.ready(look)
        me("me-account-signed-out-$id", look, out1, FakeGoogle().account(out1, MemoryMediaFiles()))
        val withGoogle = AppSettings.ready(look).apply {
            setAccount("traveller@example.com", "Traveller", AppSettings.PROVIDER_GOOGLE)
            driveConnected = true
            driveAccount = "traveller@example.com"
            lastBackupAt = nowMs() - 2 * 3_600_000L
        }
        me("me-account-google-$id", look, withGoogle, FakeGoogle().account(withGoogle, MemoryMediaFiles()))
        val withApple = AppSettings.ready(look).apply { setAccount("apple-traveller@example.com", "Traveller", AppSettings.PROVIDER_APPLE) }
        me("me-account-apple-$id", look, withApple, FakeGoogle().account(withApple, MemoryMediaFiles()))
    }
    val noGoogle = AppSettings.ready()
    me("me-account-no-google", Look.Default, noGoogle, FakeGoogle().account(noGoogle, MemoryMediaFiles(), clientId = "", appleOn = false))
    // Drive wanting the owner again (Google refused the refresh).
    val stale = FakeGoogle()
    val staleSettings = AppSettings.ready()
    val staleAccount = stale.account(staleSettings, MemoryMediaFiles())
    runBlocking {
        staleAccount.signIn()
        stale.cloud.expireAccess()
        stale.cloud.refreshRevoked = true
        runCatching { staleAccount.drive.accountEmail() }
    }
    me("me-account-reconnect", Look.Default, staleSettings, staleAccount)
    // The questions: Sign out, Delete account (Google, Apple), a deletion that didn't happen.
    fun card(name: String, look: Look, settings: AppSettings, account: GoogleAccount, delete: Boolean = false, signOut: Boolean = false, message: String? = null) =
        shoot(name) { Framed(look, account) { AccountCard(settings, Modifier.padding(horizontal = 20.dp), deleteAtStart = delete, signOutAtStart = signOut, messageAtStart = message) } }
    val g = AppSettings.ready().apply { setAccount("traveller@example.com", "Traveller", AppSettings.PROVIDER_GOOGLE); driveConnected = true }
    val a = AppSettings.ready().apply { setAccount("apple-traveller@example.com", "Traveller", AppSettings.PROVIDER_APPLE) }
    card("me-sign-out-ask", Look.Default, g, FakeGoogle().account(g, MemoryMediaFiles()), signOut = true)
    card("me-delete-ask", Look.Default, g, FakeGoogle().account(g, MemoryMediaFiles()), delete = true)
    card("me-delete-ask-apple", Look.Default, a, FakeGoogle().account(a, MemoryMediaFiles()), delete = true)
    card("me-delete-ask-trail", Look.Trail, g, FakeGoogle().account(g, MemoryMediaFiles()), delete = true)
    card("me-delete-not-done", Look.Default, a, FakeGoogle().account(a, MemoryMediaFiles()), message = GoogleAccount.APPLE_CLOSED)

    // ------------------------------------------------------------------ Backup & sync
    fun backup(
        name: String, look: Look, settings: AppSettings, account: GoogleAccount?, drive: DriveBackups? = null, files: BackupFiles? = null, tall: Float = 1.3f,
        note: BackupNote? = null, list: Boolean = false, restoreAsk: BackupEntry? = null, disconnect: Boolean = false, progress: BackupProgress? = null,
    ) = shoot(name, tall, frames = 18) {
        Framed(look, account, drive, files) {
            BackupScreen(settings, {}, {}, driveNoteAtStart = note, listAtStart = list, restoreAskAtStart = restoreAsk, disconnectAtStart = disconnect, progressAtStart = progress)
        }
    }
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        val off = AppSettings.ready(look)
        backup("backup-drive-off-$id", look, off, FakeGoogle().account(off, MemoryMediaFiles()))
        // Connected, a backup made a moment ago (on the fake Drive), and a later check that found nothing new.
        val cloud = FakeGoogle()
        val files = MemoryMediaFiles()
        val book = ownBook(files)
        val on = AppSettings.ready(look)
        val acc = cloud.account(on, files)
        val runner = BackupFiles(book, on, files, PREVIEW_VERSION)
        val drive = DriveBackups(runner, book, on, files, acc.drive, PREVIEW_VERSION, cloud.cloud)
        runBlocking {
            acc.signIn()
            drive.backupNow()
        }
        on.backupCheckedAt = nowMs()
        backup("backup-drive-on-$id", look, on, acc, drive, runner)
        backup("backup-drive-running-$id", look, on, acc, drive, runner, progress = BackupProgress(false, "Saving photos to Drive", 2, 12, file = false))
    }
    // Results, failures, the list, the questions, Connect again, no Google: on Postcard Pop.
    val cloud = FakeGoogle()
    val files = MemoryMediaFiles()
    val book = ownBook(files)
    val on = AppSettings.ready()
    val acc = cloud.account(on, files)
    val runner = BackupFiles(book, on, files, PREVIEW_VERSION)
    val drive = DriveBackups(runner, book, on, files, acc.drive, PREVIEW_VERSION, cloud.cloud)
    runBlocking {
        acc.signIn()
        drive.backupNow()
        on.lastBackupAt = nowMs() - 26 * 3_600_000L
        book.writeNote(book.snapshot.trips.single().id, "Ferry back to Piraeus.", "Port")
        drive.backupNow()
    }
    val report = runBlocking { drive.backupNow() }
    backup("backup-drive-result", Look.Default, on, acc, drive, runner, note = BackupNote(BackupText.backedUp(report.copy(photosLeft = 1, videos = 1))))
    backup("backup-drive-error", Look.Default, on, acc, drive, runner, note = BackupNote("The backup didn't reach Google Drive whole. Try Back up now again.", error = true))
    backup("backup-drive-offline", Look.Default, on, acc, drive, runner, note = BackupNote("No internet connection. Check it and try again.", error = true))
    backup("backup-drive-list", Look.Default, on, acc, drive, runner, list = true, tall = 1.5f)
    backup("backup-drive-restore-ask", Look.Default, on, acc, drive, runner, restoreAsk = found)
    backup("backup-drive-disconnect-ask", Look.Default, on, acc, drive, runner, disconnect = true)
    backup("backup-drive-restored", Look.Default, on, acc, drive, runner, note = BackupNote(
        BackupText.restored(com.justdataplease.boringtravel.backup.RestoreReport(found.createdAt, BackupCounts(3, 41, 128), 126, 2, planItems = 57, videos = 2)),
    ))
    backup("backup-drive-reconnect", Look.Default, staleSettings, staleAccount)
    backup("backup-no-google", Look.Default, noGoogle, FakeGoogle().account(noGoogle, MemoryMediaFiles(), clientId = "", appleOn = false))

    // ------------------------------------------------------------------ The restore offer, Trips' line
    val offerSettings = AppSettings.ready()
    shoot("restore-offer") { Framed(Look.Default, acc) { RestoreOffer(offerSettings, foundAtStart = found) } }
    shoot("restore-offer-replaces") { Framed(Look.Default, acc) { RestoreOffer(offerSettings, foundAtStart = found, replacesAtStart = true) } }
    shoot("restore-offer-trail") { Framed(Look.Trail, acc) { RestoreOffer(offerSettings, foundAtStart = found, replacesAtStart = true) } }
    val stalled = AppSettings.ready().apply {
        setAccount("traveller@example.com", "Traveller", AppSettings.PROVIDER_GOOGLE)
        driveConnected = true
        restoreAsked = true
        lastBackupAt = nowMs() - 4 * 24 * 3_600_000L
    }
    shoot("trips-backup-trouble") {
        BoringTravelApp(sampleBook(), stalled, SAMPLE_TODAY, PHONE_FRAME, version = PREVIEW_VERSION, google = FakeGoogle().account(stalled, MemoryMediaFiles()))
    }
    shoot("trips-backup-reconnect") {
        BoringTravelApp(sampleBook(), staleSettings.apply { restoreAsked = true; lastBackupAt = nowMs() }, SAMPLE_TODAY, PHONE_FRAME, version = PREVIEW_VERSION, google = staleAccount)
    }

    // ------------------------------------------------------------------ The book's PDF to Drive, Share's Google Doc
    val (demo, demoKit) = demoBook()
    val bookCloud = FakeGoogle()
    val bookSettings = AppSettings.ready(Look.PostcardPop).apply { demoMode = true }
    val bookAccount = bookCloud.account(bookSettings, demoKit.files)
    runBlocking { bookAccount.connectDrive() }
    File(out, "book-drive-sent.png").writeBytes(
        renderUntil({ bookCloud.cloud.filesIn(com.justdataplease.boringtravel.google.DriveApi.EXPORTS).isNotEmpty() }) {
            CompositionLocalProvider(
                LocalMediaKit provides demoKit, LocalMediaRows provides { id: String -> demo.media(id) }, LocalPagePrinter provides DesktopPagePrinter(),
                LocalGoogle provides bookAccount,
            ) {
                BoringTravelTheme(Look.PostcardPop) {
                    Box(Modifier.fillMaxSize().background(StudioColors.bg).padding(PHONE_FRAME)) {
                        BookScreen(demo, SampleArgentina.TRIP_ID, bookSettings, onBack = {}, startWith = BookStart.Drive)
                    }
                }
            }
        },
    )
    for ((name, connected) in listOf("share-docs-saved" to true, "share-docs-not-connected" to false)) {
        val shareCloud = FakeGoogle()
        val shareSettings = AppSettings.ready(Look.PostcardPop)
        val shareAccount = shareCloud.account(shareSettings, demoKit.files, clientId = if (connected) FakeGoogle.CLIENT else "")
        if (connected) runBlocking { shareAccount.connectDrive() }
        // Without Google the notice sits under the format buttons: a taller phone shows it.
        File(out, "$name.png").writeBytes(
            renderUntil({ !connected || shareCloud.cloud.filesIn(com.justdataplease.boringtravel.google.DriveApi.EXPORTS).isNotEmpty() }, tall = if (connected) 1f else 1.45f) {
                CompositionLocalProvider(
                    LocalMediaKit provides demoKit, LocalMediaRows provides { id: String -> demo.media(id) }, LocalPagePrinter provides DesktopPagePrinter(),
                    LocalGoogle provides shareAccount,
                ) {
                    BoringTravelTheme(Look.PostcardPop) {
                        Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(PHONE_FRAME)) {
                            ShareScreen(demo, SampleArgentina.TRIP_ID, shareSettings, 0, {}, {}, startWith = ShareStart.Docs)
                        }
                    }
                }
            },
        )
    }
}

/**
 * [content] drawn to a PNG once [done] says the work it shows has finished (a PDF made and sent, a Doc saved), then a
 * few frames more so the screen shows it; at most [maxMs].
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun renderUntil(done: () -> Boolean, maxMs: Long = 240_000, tall: Float = 1f, content: @Composable () -> Unit): ByteArray {
    SystemBars.set(true)
    val scene = androidx.compose.ui.ImageComposeScene(PHONE_W, (PHONE_H * tall).toInt(), androidx.compose.ui.unit.Density(PHONE_DENSITY)) {
        Box(Modifier.fillMaxSize()) {
            content()
            PhoneStatusBar(landscape = false)
        }
    }
    try {
        var t = 0L
        var image = scene.render(0)
        var after = 0
        val start = System.currentTimeMillis()
        while (after < 12 && System.currentTimeMillis() - start < maxMs) {
            Thread.sleep(50)
            t += 50
            image.close()
            image = scene.render(t * 1_000_000)
            if (done()) after++
        }
        return image.use { last -> last.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
    } finally {
        scene.close()
        System.gc()
    }
}
