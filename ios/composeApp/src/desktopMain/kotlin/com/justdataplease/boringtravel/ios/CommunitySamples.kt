package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.community.Community
import com.justdataplease.boringtravel.community.PostText
import com.justdataplease.boringtravel.community.SharedItem
import com.justdataplease.boringtravel.community.SharedNote
import com.justdataplease.boringtravel.community.SharedTrip
import com.justdataplease.boringtravel.community.SharedTrips
import com.justdataplease.boringtravel.firebase.FirebaseSetup
import com.justdataplease.boringtravel.google.GoogleAccount
import com.justdataplease.boringtravel.ios.resources.Res
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/*
 * The community on the fakes, for the tests and the pictures (round 24): Google, Firebase Auth, Firestore and Drive's
 * link-shared files in memory (GoogleFakes.kt, CommunityFakes.kt), the app's account and community made on them, and a
 * few travellers' posts: a trip with its plan, a clip, an album, a plan of ideas and one of the traveller's own. The
 * pictures are the Android app's demo photos (Wikimedia Commons, read in by the build); handles and names are made up.
 */
class CommunityWorld(
    val google: FakeGoogle = FakeGoogle(),
    val settings: AppSettings = AppSettings.ready(),
    val files: MemoryMediaFiles = MemoryMediaFiles(),
    googleClient: String = FakeGoogle.CLIENT,
    appleOn: Boolean = true,
) {
    val cloud: FakeGoogleCloud get() = google.cloud
    val fs: FakeFirestore get() = google.cloud.firestore
    val account: GoogleAccount = google.account(settings, files, clientId = googleClient, appleOn = appleOn)

    /** The caches folder (the lists, documents, pictures and clips kept) and the settings (the writes waiting). */
    val cache = MemoryMediaFiles()
    val outbox = MemorySettingsFile()

    /** The app's community on these fakes, its coroutines in [scope]; [net] the network it asks (the fakes by default). */
    fun community(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default), firebase: Boolean = true, net: Net = cloud,
    ): Community = Community.make(
        net, if (firebase) FIREBASE else FirebaseSetup.Config("", "", ""), google.keys, account, cache, outbox, scope,
        bundleId = BUNDLE, driveKey = DRIVE_KEY, driveConnected = { settings.driveConnected }, deleteDriveFile = { account.drive.delete(it) },
        pictureWait = 0,
    )

    /** The traveller signs in to the app with Google (on the fake's page), as Welcome or Me does. */
    fun signInWithGoogle() = runBlocking { account.signIn() }

    /**
     * The travellers and their posts: [mine] with a profile of the traveller's own (else none yet), [now] the moment the
     * posts are dated from.
     */
    fun seed(now: Long = nowMs(), mine: Boolean = true) {
        val month = PostText.monthKey(now)
        if (mine) fs.profile(CommunityPeople.ME, CommunityPeople.ME_NAME, "Slow ferries", "Islands, ferries and long lunches.")
        fs.profile(CommunityPeople.OTHER, CommunityPeople.OTHER_NAME, "Trail notes", "Long walks, cheap buses, small towns.")
        fs.profile(CommunityPeople.THIRD, CommunityPeople.THIRD_NAME, "Harbour days", "Cities by the water, slowly.")
        fs.post(
            PATAGONIA, CommunityPeople.OTHER, CommunityPeople.OTHER_NAME, "Patagonia on buses", now - 2 * HOUR,
            "caption" to "Twelve days of wind, glaciers and empanadas. Buses everywhere, one flight south.",
            "countries" to listOf("AR", "CL"), "days" to 12, "km" to 1240, "startMonth" to "2027-03", "month" to month,
            "coverFileId" to "drvPatagonia01", "photoFileIds" to listOf("drvPatagonia01", "drvPatagonia02", "drvPatagonia03"),
            "folderId" to "drvFolderPat", "hasTrip" to true, "likes" to 128,
        )
        fs.trip(PATAGONIA, SharedTrips.encode(patagonia))
        fs.post(
            CLIP, CommunityPeople.THIRD, CommunityPeople.THIRD_NAME, "Buenos Aires in forty seconds", now - 26 * HOUR,
            "kind" to "clip", "countries" to listOf("AR"), "days" to 4, "km" to 18, "month" to month,
            "coverFileId" to "drvCaminitoCover", "clipFileId" to "drvBaClip01", "folderId" to "drvFolderBa", "likes" to 42,
        )
        fs.post(
            ALBUM, CommunityPeople.OTHER, CommunityPeople.OTHER_NAME, "Bariloche lakes", now - 3 * DAY,
            "kind" to "album", "caption" to "Six photos of the lakes, one of them in the rain.", "countries" to listOf("AR"), "days" to 5,
            "month" to month, "coverFileId" to "drvBariloche01", "photoFileIds" to BARILOCHE, "folderId" to "drvFolderBar", "likes" to 7,
        )
        fs.post(
            IDEAS, CommunityPeople.THIRD, CommunityPeople.THIRD_NAME, "Iceland someday", now - 9 * DAY,
            "countries" to listOf("IS"), "month" to month, "hasTrip" to true, "likes" to 0,
        )
        fs.trip(IDEAS, SharedTrips.encode(iceland))
        if (mine) {
            fs.post(
                OWN, CommunityPeople.ME, CommunityPeople.ME_NAME, "Naxos by ferry", now - 40 * DAY,
                "countries" to listOf("GR"), "days" to 9, "km" to 210, "startMonth" to "2027-06", "month" to PostText.monthKey(now - 40 * DAY),
                "hasTrip" to true, "likes" to 3,
            )
            fs.trip(OWN, SharedTrips.encode(SharedTrip(days = 9, items = listOf(SharedItem(1, "Ferry from Piraeus", "transport", "Piraeus", 37.94, 23.64, 7 * 60 + 30)))))
        }
    }

    /** The posts' pictures in their posters' Drives, shared by link (the Android app's demo photos). */
    fun pictures() = runBlocking {
        val files = mapOf(
            "drvPatagonia01" to "fitz-roy-cover", "drvPatagonia02" to "laguna-capri", "drvPatagonia03" to "chalten-village",
            "drvCaminitoCover" to "caminito",
        ) + BARILOCHE.zip(listOf("arrayanes", "llao-llao", "campanario-view", "puerto-panuelo-boat", "bariloche-centro", "condores-sunset"))
        files.forEach { (id, name) -> cloud.publicFiles[id] = Res.readBytes("files/demo/argentina/$name.jpg") }
        // A clip's first bytes as an MP4 starts ("ftyp"): the desktop's player shows a still in its place.
        cloud.publicFiles["drvBaClip01"] = byteArrayOf(0, 0, 0, 24) + "ftypmp42".encodeToByteArray() + ByteArray(2048)
    }

    /** A network whose calls [hang] says wait for an answer that doesn't come (a list still loading), the rest [net]'s. */
    class HangingNet(private val net: Net, private val hang: (NetCall) -> Boolean) : Net by net {
        override suspend fun send(call: NetCall): NetAnswer {
            if (hang(call)) kotlinx.coroutines.awaitCancellation()
            return net.send(call)
        }
    }

    companion object {
        const val PATAGONIA = "pPatagonia01"
        const val CLIP = "pBaClip01"
        const val ALBUM = "pBariloche01"
        const val IDEAS = "pIceland01"
        const val OWN = "pNaxos01"
        const val BUNDLE = "com.justdataplease.boringtravel"
        const val DRIVE_KEY = "test-drive-key"
        val FIREBASE = FirebaseSetup.Config("test-project", "test-app", "test-key")
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        val BARILOCHE = (1..6).map { "drvBariloche0$it" }

        /** The Patagonia post's plan: day by day, its travel, an idea, and a note with photos. */
        val patagonia = SharedTrip(
            days = 12,
            items = listOf(
                SharedItem(1, "San Telmo market", "place", "San Telmo, Buenos Aires", -34.6212, -58.3731, 10 * 60),
                SharedItem(1, "Caminito", "activity", "La Boca, Buenos Aires", -34.6390, -58.3626, 15 * 60),
                SharedItem(2, "Flight to El Calafate", "transport", "El Calafate", -50.3379, -72.2648, 9 * 60 + 40, via = "plane"),
                SharedItem(3, "Perito Moreno glacier", "activity", "Los Glaciares", -50.4967, -73.1377, 11 * 60, "https://www.losglaciares.com/"),
                SharedItem(5, "Bus to El Chaltén", "transport", "El Chaltén", -49.3315, -72.8863, 8 * 60, via = "car"),
                SharedItem(6, "Laguna de los Tres", "activity", "Fitz Roy", -49.2700, -73.0010, 6 * 60),
                SharedItem(9, "Torres del Paine", "activity", "Torres del Paine", -50.9423, -73.4068, via = "car"),
                SharedItem(0, "Chocolate in Bariloche", "food", "Bariloche"),
            ),
            notes = listOf(
                SharedNote(3, "2027-03-03", "Ice that cracks like thunder", "We stood on the walkways for two hours. Every few minutes a block fell into the lake.", listOf("drvPatagonia02", "drvPatagonia03")),
            ),
        )

        /** A plan of ideas only (no days): Copy this trip copies it at once, without dates. */
        val iceland = SharedTrip(
            days = 0,
            items = listOf(
                SharedItem(0, "Blue Lagoon", "activity", "Grindavík", 63.8804, -22.4495),
                SharedItem(0, "Ring road", "transport", "Iceland"),
                SharedItem(0, "Hallgrímskirkja", "place", "Reykjavík", 64.1417, -21.9266),
            ),
        )
    }
}
