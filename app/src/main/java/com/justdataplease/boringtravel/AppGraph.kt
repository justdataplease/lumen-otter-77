package com.justdataplease.boringtravel

import android.content.Context
import com.justdataplease.boringtravel.backup.BackupManager
import com.justdataplease.boringtravel.billing.ProBilling
import com.justdataplease.boringtravel.data.db.BtDatabase
import com.justdataplease.boringtravel.data.repo.CountryRepository
import com.justdataplease.boringtravel.data.repo.JournalRepository
import com.justdataplease.boringtravel.data.repo.MediaRepository
import com.justdataplease.boringtravel.data.repo.PlanRepository
import com.justdataplease.boringtravel.data.repo.TrackRepository
import com.justdataplease.boringtravel.data.repo.TripRepository
import com.justdataplease.boringtravel.data.settings.Settings
import com.justdataplease.boringtravel.export.TripExporter
import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.google.GoogleAccount
import com.justdataplease.boringtravel.places.PhotonPlaceSearch
import com.justdataplease.boringtravel.places.PlaceSearch
import com.justdataplease.boringtravel.tracking.TrackingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's objects, built once in [BoringTravelApp]. Plain constructor wiring instead of a DI framework:
 * later waves add their services here (tracking, Google, export, clip, sync, AI).
 */
class AppGraph(context: Context) {
    val app: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: BtDatabase by lazy { BtDatabase.build(app) }
    val settings = Settings(app)

    /** Demo mode: the example trips or the traveller's own on show, and the demo kept around today. */
    val demo by lazy { com.justdataplease.boringtravel.data.DemoMode(this) }

    val trips by lazy { TripRepository(db, settings.demoMode, onNewOwnTrip = { demo.leave() }) }
    val plan by lazy { PlanRepository(db) }
    val journal by lazy { JournalRepository(db, settings.demoMode) }
    /** A local once-a-day invitation to journal from today's itinerary; never starts tracking. */
    val checkIns by lazy { com.justdataplease.boringtravel.checkin.JournalCheckIns(this) }
    val media by lazy { MediaRepository(app, db, settings.demoMode) }
    val tracks by lazy { TrackRepository(db, settings.demoMode) }

    /** Deleting trips, entries and photos with what hangs off them (files, clips, calendar events). */
    val deletions by lazy { com.justdataplease.boringtravel.data.Deletions(this) }

    /** Countries marked by hand: been there, want to go, removed. */
    val countries by lazy { CountryRepository(db) }

    /** The traveller's world (visited, planned and wished countries, totals) and the globe's shapes (Wave 3J). */
    val world by lazy { com.justdataplease.boringtravel.world.WorldSource(this) }

    /**
     * Google sign-in, Drive access and the Drive REST client. Signing in here never touches the community: Firebase
     * gets the account only when the community or Travel together (its trips' mailboxes) is used (Community.signIn),
     * so Drive-only users have no Firebase record.
     */
    val google: GoogleAccount by lazy {
        GoogleAccount(
            app, settings, onDriveChanged = { backups.reschedule() },
            onSignedOut = { if (community.available) community.signOut() },
        )
    }

    /** The owner's Google Drive (drive.file + drive.appdata over REST). */
    val drive: DriveApi by lazy { google.drive }

    /** Other people's link-shared Drive files (shared trips, community posts), read without their sign-in. */
    val publicDrive by lazy { google.publicDrive }

    /** Drive backups and restore (shared trips' state travels with them, so a new phone keeps syncing them). */
    val backups: BackupManager by lazy {
        BackupManager(
            app, db, settings, google.drive,
            sharedTrips = { together.engine.exportState() },
            restoreSharedTrips = { together.engine.importState(it) },
            sharedNow = { together.engine.load().trips.mapTo(HashSet()) { it.tripId } },
        )
    }

    /**
     * Shared trips: each member's copy in their own Drive, read by link and merged; where each copy is goes through
     * the trip's mailbox in Firestore (together/).
     */
    val together by lazy {
        com.justdataplease.boringtravel.together.Together(
            app, db, settings, google.drive, google.publicDrive, scope,
            driveAccount = { google.driveAccount },
            mailbox = { com.justdataplease.boringtravel.together.firestoreMail(this) },
        )
    }

    /** BoringTravel Pro (Play Billing). `billing.state.value.isPro` gates the AI planner and clip extras. */
    val billing by lazy { ProBilling(app) }

    /** Place search for plan items (Photon + the system geocoder; Tripadvisor later behind the same interface). */
    val places: PlaceSearch by lazy { PhotonPlaceSearch(app) }

    /**
     * Previews of plan items' links (site picture, favicon; Wikipedia photos for Google Maps places) and Wikipedia
     * photos for located items without a link (Me › Place photos), looked up lazily.
     */
    val links by lazy { com.justdataplease.boringtravel.links.LinkPreviews(app, scope, places = { places }, placePhotos = settings.placePhotos) }

    /** Print & export: PDF, print dialog, Google Docs, sharing (Wave 2E). */
    val export by lazy { TripExporter(app, this) }

    /** Books: drafts, photos at print size and the print-ready PDF (Make a book). */
    val books by lazy { com.justdataplease.boringtravel.book.BookExporter(app, this) }

    /** The AI planner (Pro): Gemini through Firebase AI Logic; see firebase/FirebaseSetup for the project ids. */
    val planner by lazy { com.justdataplease.boringtravel.ai.GeminiPlanner(app) }

    /** Me › AI: the traveller's own Gemini, Claude or ChatGPT key, used by the planner instead of [planner] when chosen. */
    val ownAi by lazy { com.justdataplease.boringtravel.ai.own.OwnAi(app) }

    /** Copy from a site: a blog's posts and pictures as notes in new trips, running on while the screen is closed (site/). */
    val siteImport by lazy { com.justdataplease.boringtravel.site.siteImporter(this) }

    /** Trip posts kept in step with their trips (community/PostSync). */
    val postSync by lazy { com.justdataplease.boringtravel.community.PostSync(this) }

    /** Route tracking: levels, location requests, the Detailed service, stop and country processing. */
    val tracking by lazy { TrackingController(this) }

    /** Trips in the phone's calendars (add, update, remove, import) and .ics files (Wave 3L). */
    val calendar by lazy { com.justdataplease.boringtravel.calendar.CalendarSync(this) }

    /** The community: posts, likes, profiles and the leaderboard in Firestore, pictures in each poster's Drive. */
    val community by lazy { com.justdataplease.boringtravel.community.Community(this) }
}

val Context.graph: AppGraph get() = (applicationContext as BoringTravelApp).graph
