package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.justdataplease.boringtravel.ai.SharedItinerary
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.File

/*
 * Round 27's pictures: what another app shares, written into the share extension's folder (in memory) as the extension
 * writes it, and the app's own sheets for it, on the recorded answers (never the network): Add to plan for a Google
 * Maps place in two looks (the link's preview with its Wikipedia photo, the trip on now, today marked "·", Place, Food,
 * Stay), a blog's "Copy its notes and pictures", a Google Maps list ("Add its 3 places", "Add them to a new trip", and
 * with no trips), No trips, only the examples, the Plan on that day after Add, "Nothing to add: share a link", and the
 * planner with shared text, pictures, a Google Doc's link, "Reading the document" with Cancel and a document's error.
 * `gradlew :composeApp:screenshots -PshotsOnly=sharing` draws only these. The extension's own card is SwiftUI
 * (iosApp/BoringTravelShare): it can't be drawn here.
 */
fun sharing(out: File) {
    val yasaka = "Yasaka Shrine\n${Recorded.YASAKA_LONG}"

    /** A share left in the extension's folder, as ShareViewController.swift leaves it. */
    fun inbox(files: MediaFiles, text: String? = null, subject: String? = null, pictures: List<ByteArray> = emptyList(), file: Pair<String, ByteArray>? = null): ShareInbox {
        val group = MemoryMediaFiles()
        val name = "1795000000000-picture"
        val names = pictures.mapIndexed { i, bytes -> "${i + 1}.jpg".also { group.write("${ShareInbox.FOLDER}/$name/$it", bytes) } }
        file?.let { (fileName, bytes) -> group.write("${ShareInbox.FOLDER}/$name/$fileName", bytes) }
        group.write("${ShareInbox.FOLDER}/$name.json", ShareInbox.manifestText(ShareManifest(text = text, subject = subject, pictures = names, file = file?.first)).encodeToByteArray())
        return ShareInbox(group, files)
    }

    fun drive(name: String, frames: Int = 50, content: @Composable () -> Unit, steps: CommunityDriver.() -> Unit = {}) {
        CommunityDriver(PHONE_W, PHONE_H, content).use { d ->
            d.frames(frames)
            d.steps()
            d.frames(16)
            File(out, "$name.png").writeBytes(d.png())
        }
    }

    /** The whole app over Trips, with [shares] waiting, on the recorded answers. */
    fun shot(
        name: String, book: TripBook, look: Look = Look.PostcardPop, kit: MediaKit = MediaKit.memory(), settings: AppSettings = AppSettings.ready(look),
        share: (MediaFiles) -> ShareInbox, steps: CommunityDriver.() -> Unit = {},
    ) {
        val shares = share(kit.files)
        val web = Recorded.web()
        drive(name, content = {
            CompositionLocalProvider(LocalPhone provides Phone(paste = { null })) {
                BoringTravelApp(book, settings, SAMPLE_TODAY, PHONE_FRAME, version = PREVIEW_VERSION, media = kit, web = web, shares = shares)
            }
        }, steps = steps)
    }

    // ------------------------------------------------------------------ Add to plan
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        shot("share-add-to-plan-${look.id}", sampleBook(), look, share = { inbox(it, yasaka) })
    }
    shot("share-add-to-plan-site", sampleBook(), share = { inbox(it, "https://myblog.tumblr.com/") })
    shot("share-add-to-plan-stay", sampleBook(), share = { inbox(it, "https://www.granviakyoto.com/", subject = "Hotel Granvia Kyoto") })
    shot("share-maps-list", sampleBook(), share = { inbox(it, Recorded.LIST_SHORT) })
    shot("share-maps-list-no-trips", TripBook(MemoryTripFile()), share = { inbox(it, Recorded.LIST_SHORT) })
    shot("share-no-trips", TripBook(MemoryTripFile()), share = { inbox(it, yasaka) })
    run {
        val book = TripBook(MemoryTripFile())
        val kit = MediaKit.memory()
        val settings = AppSettings.ready()
        runBlocking { DemoMode(book, settings, kit.files).set(true, SAMPLE_TODAY) }
        shot("share-demo-trips", book, kit = kit, settings = settings, share = { inbox(it, yasaka) })
    }
    // Add: the trip's Plan on that day, the place at its end.
    shot("share-added-plan-day", sampleBook(), share = { inbox(it, yasaka) }) {
        tapText("Add")
        frames(40)
    }
    // A few words without a link.
    shot("share-nothing-to-add", sampleBook(), share = { inbox(it, "see you in Seville") })

    // ------------------------------------------------------------------ into the planner
    val itinerary = "Lisbon in May\nDay 1: Alfama and the castle\nDay 2: Sintra, Pena Palace\nDay 3: Belém, the tower and pastéis\n" +
        "Day 4: Cascais by train\nDay 5: fly home"
    shot("share-planner-text", TripBook(MemoryTripFile()), share = { inbox(it, itinerary) })
    shot("share-planner-pictures", TripBook(MemoryTripFile()), share = {
        inbox(it, pictures = listOf(samplePhoto(Scene.Sea, 600, 1200), samplePhoto(Scene.Town, 600, 1200), samplePhoto(Scene.Sunset, 600, 1200)))
    })
    shot("share-planner-doc-link", TripBook(MemoryTripFile()), share = { inbox(it, "https://docs.google.com/document/d/1AbCdEfGhIjKlMnOp/edit") })
    shot("share-planner-file", TripBook(MemoryTripFile()), share = { inbox(it, file = "document.txt" to itinerary.encodeToByteArray()) })
    shot("share-planner-file-error", TripBook(MemoryTripFile()), share = { inbox(it, file = "document.pdf" to "%PDF-1.7\n1 0 obj".encodeToByteArray()) })

    // "Reading the document" with Cancel: a document that never finishes reading.
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val book = TripBook(MemoryTripFile())
        val files = MemoryMediaFiles()
        files.write("picked/shared-a-1.txt", itinerary.encodeToByteArray())
        val kit = PlannerKit(book, AppSettings.ready(look), Web.offline(), files, PlannerPhone(), PREVIEW_VERSION)
        val side = object : PlannerSide by KitPlannerSide(kit, book, files) {
            override suspend fun readDocument(path: String): String = CompletableDeferred<String>().await()
        }
        File(out, "share-planner-reading-${look.id}.png").writeBytes(render(PHONE_W, PHONE_H, PHONE_DENSITY, 30) {
            CompositionLocalProvider(LocalPlannerKit provides kit, LocalMediaKit provides MediaKit(files), LocalPhone provides Phone(paste = { null })) {
                BoringTravelTheme(look) {
                    Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(PHONE_FRAME)) {
                        PlannerScreen(
                            side, null, start = null, dictationLanguage = null, onBack = {}, onAiSettings = {}, onCopySite = { _, _ -> },
                            onSaved = { _, _ -> }, onReport = { _, _ -> }, shared = SharedItinerary(null, "picked/shared-a-1.txt"),
                        )
                    }
                }
            }
        })
    }
}
