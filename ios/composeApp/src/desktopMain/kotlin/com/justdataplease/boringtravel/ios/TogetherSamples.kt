package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.community.Community
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.google.PublicDrive
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.together.MemoryTogetherRuns
import com.justdataplease.boringtravel.together.ShareLink
import com.justdataplease.boringtravel.together.Together
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/*
 * Travel together on the fakes (round 26), for the tests and the pictures: each traveller's phone is its own Google
 * account with its own Drive (a FakeGoogleCloud of its own, which reads the others' link-shared files as Google serves
 * them), its own trips' store and files, the community's Firebase account, and the one Firestore every phone shares (the
 * trips' mailboxes, under the owner's rules). Every row is made through the engine's trip store; the people are made up
 * (Maria, Nikos, Eleni); nothing reaches the network.
 */

/** The clock every phone of a test stamps with, so "later" means the same on all of them (Google's clock, Stamps). */
class SharedClock(var at: Long = com.justdataplease.boringtravel.time.nowMs()) {
    fun next(): Long = at++
}

/**
 * One traveller's phone: [name] signs in with Google as "<Name> Example" ([sub] their Google id), Drive connected unless
 * [drive] is false, signed in to the community (the mailboxes) unless [firebase] is false.
 */
class TogetherPhone(
    val name: String,
    sub: String,
    firestore: FakeFirestore,
    val clock: SharedClock,
    drive: Boolean = true,
    firebase: Boolean = true,
    signIn: Boolean = true,
) {
    val files = MemoryMediaFiles()
    val cloud = FakeGoogleCloud(account = FakeAccount("${name.lowercase()}@example.com", "$name Example", sub), firestore = firestore, idPrefix = "drv$name")
    val google = FakeGoogle(cloud)
    val settings: AppSettings = AppSettings.ready()
    val world = CommunityWorld(google, settings, files)
    private var n = 0

    /** Ids long enough for a trip's mailbox (TripMail.MIN_TRIP_ID), the same on every run. */
    val ids: () -> String = { "${name.lowercase()}-" + (n++).toString().padStart(22, '0') }
    val book = TripBook(MemoryTripFile(), now = clock::next, ids = ids)
    val kit = MediaKit(files, frames = PostingFrames)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val community: Community = world.community(scope)
    val runs = MemoryTogetherRuns()
    val keep = MemorySettingsFile()

    /** Goes up after a write to the store, as the app's AppCore counts them (Together.start watches it). */
    val writes = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun wrote() {
        writes.value++
    }
    val together = Together(
        book, settings, files, world.account.drive,
        PublicDrive(cloud, CommunityWorld.DRIVE_KEY, CommunityWorld.BUNDLE, retryWait = 0), scope, PhotoPrep(files, PostingFrames), cloud,
        runs = runs, keep = keep, driveAccount = { world.account.driveAccount }, mailbox = { community.mailAccess() },
        clock = clock::next, changeWait = 0, retryFirst = 50,
    )

    init {
        settings.onboardingDone = true
        settings.demoMode = false
        if (signIn) {
            world.signInWithGoogle()
            if (!drive) settings.driveConnected = false
            if (firebase) runBlocking { community.signIn(quiet = true) }
        }
    }

    /** Lets this phone read [others]' link-shared files and they this one's (each member reads the others' copies). */
    fun knows(vararg others: TogetherPhone) {
        others.forEach { o ->
            if (o.cloud !in cloud.others) cloud.others += o.cloud
            if (cloud !in o.cloud.others) o.cloud.others += cloud
        }
    }

    /** This phone's trip [tripId] as kept (deleted or not). */
    fun trip(tripId: String): Trip? = book.snapshot.trips.firstOrNull { it.id == tripId }

    /** The live plan's titles of [tripId], sorted. */
    fun titles(tripId: String): List<String> = book.items(tripId).map { it.title }.sorted()

    /** Its Firebase account's id (the mailbox's poster). */
    val uid: String get() = "fb-google-$subId"
    private val subId = sub
}

/**
 * A shared trip's world: Maria's trip "Spain 2027" on her phone, and the friends' phones, all on one Firestore.
 * [mariaFirebase] false: her app isn't signed in to Firebase (or is an older one), so it never posts to the trip's
 * mailbox and a friend who joins is asked to send their link once (JoinSheet's "One more step").
 */
class TogetherWorld(
    val today: LocalDate = SAMPLE_TODAY,
    nikosDrive: Boolean = true,
    nikosFirebase: Boolean = true,
    nikosSignedIn: Boolean = true,
    mariaFirebase: Boolean = true,
) {
    val fs = FakeFirestore()
    val clock = SharedClock()
    val maria = TogetherPhone("Maria", "sub-maria", fs, clock, firebase = mariaFirebase)
    val nikos = TogetherPhone("Nikos", "sub-nikos", fs, clock, drive = nikosDrive, firebase = nikosFirebase, signIn = nikosSignedIn)
    val tripId: String

    init {
        maria.knows(nikos)
        tripId = spain(maria, today)
    }

    /** A third friend, on the same Firestore, reading the others' copies. */
    fun eleni(): TogetherPhone = TogetherPhone("Eleni", "sub-eleni", fs, clock).also { it.knows(maria, nikos) }

    /** Maria invites (her copy in her Drive, link-shared), as Invite does; the link she sends. */
    fun invite(): ShareLink = runBlocking { maria.together.share(tripId, "Spain 2027") }

    /** Maria invites and Nikos joins with Drive (his copy in his Drive, its address in the mailbox), as the sheets do. */
    fun joined(): ShareLink {
        val invite = invite()
        runBlocking {
            nikos.together.open(invite)
            nikos.together.share(tripId, "Spain 2027")
            nikos.together.checkMail(tripId)
            maria.together.checkMail(tripId)
            maria.together.syncNow(tripId)
            nikos.together.syncNow(tripId)
        }
        return invite
    }

    companion object {
        /**
         * "Spain 2027" on [phone]: four days in Andalusia with a located plan, a note with a photo and a checklist; its
         * dates from 10 days after [today].
         */
        fun spain(phone: TogetherPhone, today: LocalDate): String {
            val book = phone.book
            val start = today.plusDays(10)
            val trip = book.newTrip("Spain 2027", start, start.plusDays(3), subtitle = "Seville → Granada")
            fun place(day: Int, title: String, kind: PlanKind, lat: Double, lng: Double, minutes: Int?, pos: Double) =
                book.saveItem(book.draftItem(trip.id, start.plusDays(day.toLong()), pos).copy(title = title, kind = kind.id, lat = lat, lng = lng, placeName = title, timeMinutes = minutes))
            place(0, "Alcázar of Seville", PlanKind.Place, 37.3831, -5.9902, 10 * 60, 1.0)
            place(0, "Tapas in Triana", PlanKind.Food, 37.3830, -6.0030, 21 * 60, 2.0)
            place(2, "Train to Granada", PlanKind.Transport, 37.1773, -3.5986, 9 * 60, 1.0)
            place(2, "Alhambra", PlanKind.Activity, 37.1761, -3.5881, 14 * 60, 2.0)
            book.addItem(trip.id, "Flamenco show", null)
            val zone = "Europe/Madrid"
            val note = book.writeNote(trip.id, "Orange trees on every street.", title = "Before we go", at = startOfDayMs(today, zone) + 9 * 3_600_000L, zoneId = zone)!!
            val photo = addSamplePhoto(book, phone.kit, samplePhoto(Scene.Sunset, 2400, 1800), trip.id, note.id, phone.ids)
            val doc = EntryDoc.parse(note.body)
            book.saveNote(note, doc.copy(blocks = doc.blocks + Block.Photo(photo.id) + Block.Checklist(listOf(CheckItem("Passports", false), CheckItem("Train tickets", true)))))
            return trip.id
        }
    }
}
